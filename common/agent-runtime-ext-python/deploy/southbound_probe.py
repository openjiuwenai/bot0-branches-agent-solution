# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pylint: disable=line-too-long


"""南向出站的**真实往返**验证装置：受控假远端 + 真 HTTP + 真 SSE。

## 为什么必须有这个文件

本仓对南向出站（versatile 远端代理）的验证此前只有两种手段：

1. 进程内判据 + 变异验证——**帧由我们自己的替身产出**
2. 差分比对——**两侧都用确定性替身**

两者有同一个盲区：**替身产什么帧，是写替身的人决定的**。而写替身时照的是被测代码
自己认识的那些事件名——用被测代码的世界观构造它的输入，那套输入永远打不出
「远端会发一个我们没认识的形态」这件事。

实测代价（2026-08-11，外部测试在真远端上打出来的）：远端返回
`{"event":"error","data":{"message":"未找到匹配的意图"}}`，而本仓当时只认 `exception`
——那一帧被当普通帧走完、文本又取不到（载荷键是 `message` 不是 `text`），于是静默丢弃；
流关闭后报的是「远端流关闭但未观察到任何终止事件」。**误导性错误比没有错误更贵**：
它把排查方向指向本地流处理，而问题在远端的意图匹配。

北向早就有等价装置（`deploy/host_app.py` + 容器往返），其结论逐字是
「单测 venv 全绿也测不到 wire 契约 bug」。**那条教训此前没有推广到南向。**

## 帧集从哪来——不是编的

假远端产的每一帧都取自**存量测试里的真实样例**，逐条带出处。写在这里的理由是：
若帧由本装置的作者凭理解构造，它就退回成了另一个「我以为的世界」，
只是换了个进程边界。真实样例是当时确认过的形态。

## 它与真远端的分工

- **本装置**：形态覆盖（远端会发哪些形态、我们认不认得），可重复、不依赖外部服务
- **真远端**：贯通验证（网络、鉴权、真实内容），不可重复、受远端稳定性影响

外部测试那次打真远端得到过 504 网关超时——那说明真远端不适合做形态覆盖的日常回归。
两者是互补，不是替代。

## 怎么跑

    .venv/bin/python deploy/southbound_probe.py

退出码非零即形态不符。判据 `agent_runtime/tests/test_southbound_roundtrip.py`
在进程内驱动同一份帧集与同一条出站链路，使这套形态在日常回归里也被跑到。
"""
from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any, Iterable

#: 假远端产出的帧集。**每一条都带出处**——它们是存量测试里当时确认过的真实形态，
#: 不是本文件的作者构造的。
#:
#: 出处根目录：`.legacy-oracle/applications/versatile_adapter/tests/`
REAL_FRAMES: list[tuple[str, str]] = [
    # ── 内容帧（透传）──
    ('{"type":"rawData","data":{"content":"x"}}', "test_runner_stream.py 的 test_workflow_intent_matched_skips_filtered_types 内容帧，透传"),
    ('{"type":"text","data":{"content":"hello"}}', "test_runner_stream.py 的 test_workflow_intent_matched_skips_filtered_types 内容帧，透传"),
    # ── `event: "message"` 族：versatile 的**主内容帧形态** ──
    # `applications/versatile_adapter/adapters/versatile_a2a_gateway.py` 逐字：
    #「data_proxy 输出格式与低码工作流一致（`{"event":"message","data":{"text":"..."}}`）」。
    # **第一版帧集漏了整整一族**，由覆盖面校验判据当场读出。
    (
        '{"event":"message","data":{"node_type":"think","text":"thinking..."}}',
        "test_versatile_proxy.py 的 test_message_event_normal_passthrough 思考帧（event=message 族）",
    ),
    (
        '{"event":"message","data":{"node_type":"QA","node_name":"X","text":"分析"}}',
        "test_versatile_proxy.py 的 test_end_node_sets_completed_and_passes_through 问答族（同族，QA 节点）",
    ),
    ('{"event":"message","data":{}}', "test_versatile_proxy.py 的 test_multiple_chunks_accumulate 空载荷帧——不产内容块"),
    ('{"event":"message"}', "test_versatile_proxy.py 的 test_data_prefix_stripped 无载荷帧——不产内容块"),
    # `data:` **无空格**前缀的载体帧。存量 `test_versatile_proxy.py` 的 `test_data_prefix_without_space` 用它测前缀剥离；
    # 帧内容本身无业务含义，但**那条传输形态是真的**——假远端交替使用两种前缀发送，
    # 使两者都被真实往返覆盖（见 `_sse_app`）。
    ('{"x":1}', "test_versatile_proxy.py 的 test_data_prefix_without_space 无空格前缀的载体帧"),
    # ── 控制帧（存量一律吞掉）──
    # test_runner_stream.py 的 docstring 逐字：「finish/runCompleted/dialogId 帧被吞」；
    # test_versatile_proxy.py 的 wf 断言三者全吞、**只有前两类标记完成**。
    ('{"type":"finish","data":{"content":""}}', "test_runner_stream.py 的 test_workflow_intent_matched_skips_filtered_types 控制帧，吞掉且表示不再发帧"),
    ('{"type":"runCompleted","data":{"content":""}}', "test_runner_stream.py 的 test_workflow_intent_matched_skips_filtered_types 同上"),
    ('{"type":"dialogId","data":{"content":"d-1"}}', "test_runner_stream.py 的 test_workflow_intent_matched_skips_filtered_types 控制帧，吞掉但不表示结束"),
    # ── 业务终态帧：**节点属性在 `data` 里，不在顶层** ──
    # 存量形态逐字见 `test_versatile_proxy.py` 的 `test_end_node_sets_completed_and_passes_through` 与 `openJiuwen/agent-runtime/applications/versatile_adapter/tests/test_runner_stream.py` 的 `test_controller_complete_flow_with_end_node`、`test_controller_flow_with_workflow_result_node`。
    # 本仓此前只看顶层，对真实帧恒取不到——业务终态永远识别不出来。
    (
        '{"event":"message","data":{"node_type":"End","is_finished":true}}',
        "test_versatile_proxy.py 的 test_end_node_sets_completed_and_passes_through 业务终态帧（节点属性嵌在 data 里）",
    ),
    # ── 结果节点帧 ──
    (
        '{"data":{"node_type":"QA","node_name":"WorkflowQAResponseNode","text":"工作流答案"}}',
        "test_runner_stream.py 的 test_workflow_flow_with_workflow_result_node 结果节点帧",
    ),
    # ── 错误帧**必须放最后**：失败由异常驱动，第一个抛出后流即结束，
    #    放在前面会让它后面的帧一条都跑不到（第一版正是这样，新增的四帧全被跳过）。
    ('{"event":"exception","data":{"message":"err"}}', "test_versatile_proxy.py 的 test_exception_event_sets_completed_and_failed 错误帧"),
    (
        '{"event":"error","data":{"message":"未找到匹配的意图"}}',
        "test_versatile_proxy.py 的 test_error_event_sets_completed_and_failed 错误帧（另一个事件名）",
    ),
]


async def _sse_app(
    scope: MutableMapping[str, Any],
    receive: Callable[[], Awaitable[MutableMapping[str, Any]]],
    send: Callable[[MutableMapping[str, Any]], Awaitable[None]],
) -> None:
    """最小 ASGI 应用：把帧集按 SSE 逐行发出。

    **真 SSE**：`text/event-stream`、`data: ` 前缀、空行分隔——
    与真远端在传输层上同形。内容受控，传输不受控这件事由它保证。
    """
    if scope["type"] != "http":  # pragma: no cover - 只服务 HTTP
        return
    await send({
        "type": "http.response.start",
        "status": 200,
        "headers": [
            (b"content-type", b"text/event-stream"),
            (b"cache-control", b"no-cache"),
        ],
    })
    for index, (payload, _origin) in enumerate(REAL_FRAMES):
        # **两种前缀交替**：`data: `（带空格）与 `data:`（不带）在真实报文里都出现过
        #（存量 `test_versatile_proxy.py` 的 `test_data_prefix_without_space` 专门测了不带空格那种）。
        # 只发一种，剥离规则的另一半就没被真实往返覆盖过。
        prefix = "data: " if index % 2 == 0 else "data:"
        await send({
            "type": "http.response.body",
            "body": f"{prefix}{payload}\n\n".encode("utf-8"),
            "more_body": True,
        })
    await send({"type": "http.response.body", "body": b"", "more_body": False})


async def drive_once() -> list:
    """经**真实 HTTP 往返**驱动一次南向出站，返回本仓看到的结果块。

    走的是产品链路：`VersatileClient` → `httpx` → 假远端 → SSE 行 →
    `VersatileFrameTranslator`。**没有任何一步被替身短路**——
    客户端注入的是传输层工厂，解析与翻译全走产品代码。
    """
    import httpx  # noqa: PLC0415

    from agent_runtime.adapters.outbound.versatile.client import (  # noqa: PLC0415
        VersatileClient,
    )
    from agent_runtime.adapters.outbound.versatile.config import (  # noqa: PLC0415
        VersatileConfig,
    )
    from agent_runtime.adapters.outbound.versatile.stream_adapter import (  # noqa: PLC0415
        VersatileFrameTranslator,
        VersatileStreamState,
    )

    config = VersatileConfig(
        url_template="http://fake-remote/api/v1/workflow/{conversation_id}/run"
    )
    client = VersatileClient(
        config,
        http_client_factory=lambda **_kw: httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_sse_app), base_url="http://fake-remote"
        ),
    )
    translator = VersatileFrameTranslator(config)
    state = VersatileStreamState()
    seen: list = []
    try:
        async for frame in client.stream(
            conversation_id="probe-1", body={"inputs": {"query": "我要买理财"}}
        ):
            chunk = translator.translate(frame, state)
            if chunk is not None:
                seen.append(chunk)
    except Exception as exc:  # noqa: BLE001  错误帧以异常表达，那是预期路径之一
        seen.append(exc)
    return seen


def describe(items: Iterable[Any]) -> str:
    lines = []
    for item in items:
        if isinstance(item, Exception):
            lines.append(f"  异常 {type(item).__name__}: {item}")
        else:
            lines.append(f"  块 type={item.type} content={item.content!r:.40}")
    return "\n".join(lines) or "  （无产出）"


#: 探针是给人读的命令行装置，输出即结论，故 handler 只写消息本身——
#: 加上级别与时间戳前缀会把逐帧对照表冲散，而那张表正是它的产出。
_log = logging.getLogger("southbound_probe")


def main() -> int:
    if not _log.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(message)s"))
        _log.addHandler(handler)
        _log.setLevel(logging.INFO)
    _log.info("南向真实往返探针 —— 帧集取自存量测试的真实样例")
    for payload, origin in REAL_FRAMES:
        _log.info("  发出 %-62s ← %s", payload[:60], origin)
    _log.info("\n本仓看到的：")
    seen = asyncio.run(drive_once())
    _log.info(describe(seen))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
