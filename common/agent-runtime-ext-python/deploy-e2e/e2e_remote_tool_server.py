# coding: utf-8
# 参考宿主与部署级 E2E 装置：SPI 实现方法必须是实例方法；
# 按场景直接构造 runtime 内部状态是这一层的职责，不是越界访问。
# pylint: disable=protected-access


"""容器级部署 E2E 入口（FEAT-004 远端工具 A2A 线级往返）。

同一容器内一个 app 同时承载：
  - /a2a/...            远端 A2A Agent（stub handler，确定性终答，无 LLM）——被调方
  - /drive-remote-tool  REST 端点：经 build_remote_coordinator 对**自身** /a2a 发起真实 A2A
                        远端工具调用（协调器 → 真实 a2a-sdk JSONRPC → 回到 /a2a）——主调方
  - /v1/{...}/agents/{...}/conversations/{...}
                        **本仓真实的自定义 REST 入口**（`build_rest_router` 装配），
                        委派链路的生命周期边界帧经它以 SSE 出流——见下方「边界帧为什么
                        必须走这条」

## 边界帧为什么必须走真实 REST 入口

`/drive-delegation-frames` 早先在**容器内进程内**构造编排器、直接 `async for` 消费、
直接调 `MobileBankChannel.format_event` 收帧。那样真 socket 只覆盖了远端 A2A 那一腿，
边界帧本身没有过本仓 REST 出口的 wire——「经真实 socket 往返与存量一致」这句话
对边界帧并不成立（独立复核点名）。

现在装配真实路由：脚本对 `/v1/.../conversations/{会话}` 发 POST、读 SSE，
帧经 `_sse_frames` 的完整出流路径出来，与生产上客户端看到的是同一条。

证明"我的 outbound 远端工具客户端 ↔ 我的 inbound A2A 服务端，经真实 socket + 真实 a2a-sdk 协议
端到端打通、终答归一"。确定性（无 LLM tool-choice 依赖）。容器 --network host，自调 127.0.0.1:8090。
"""
from __future__ import annotations

import asyncio
import os
from typing import AsyncIterator

import httpx

from agent_runtime.adapters.outbound.remote.config import RemoteAgentConfig, RemoteEndpoint
from agent_runtime.application.serve import ServeOrchestrator
from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.bootstrap.remote_wiring import build_remote_coordinator
from agent_runtime.domain.result import QueryChunk


class _MemorySessionStore:
    """进程内共享会话缓存（部署级 E2E 用）：REST 入口写、南向出站读、自环 A2A 入站再写。

    **记录每一次写入尝试**，不管键是否已存在：单成员批次的南向会话标识与父会话相同，
    入站那次 `put_request_if_absent` 必然因键已在而返回假——但那次尝试本身就是
    「数据片段真的经真 socket 到达了对端」的证据（G-01 的部署级判据）。
    """

    def __init__(self) -> None:
        self._rows: dict[str, dict] = {}
        self.put_attempts: list[tuple[str, dict]] = []

    async def get_request(self, conversation_id: str):  # noqa: ANN201
        return self._rows.get(conversation_id)

    async def put_request_if_absent(self, conversation_id: str, snapshot: dict, *, ttl_s: int) -> bool:
        self.put_attempts.append((conversation_id, dict(snapshot)))
        if conversation_id in self._rows:
            return False
        self._rows[conversation_id] = dict(snapshot)
        return True


_SESSION_STORE = _MemorySessionStore()

_PORT = int(os.environ.get("PORT", "8090"))
# 两个 URL 语义不同，**不可共用一个变量**：
# - 站点根：远端端点的配置值。卡片发现在其下拼 well-known（按 RFC 8615 相对站点根）
# - 接口 URL：写进卡片的 supportedInterfaces，指向 JSON-RPC 端点本身，供客户端发起调用
# 曾经两处共用 `.../a2a/`，在卡片挂于挂载前缀下时恰好自洽；卡片回到站点根后，
# 拿接口 URL 当端点就再也发现不了卡片。
_SELF_ROOT = os.environ.get("SELF_ROOT_URL", f"http://127.0.0.1:{_PORT}")
_SELF_A2A = os.environ.get("SELF_A2A_URL", f"{_SELF_ROOT}/a2a/")
_ANSWER = "余额为 6312.58 元"
_AGENT_ID = "remote-planner"

#: 远端的行为由**委派消息的正文**选择，脚本据此在一次部署里覆盖多个落态。
#: 不按 agent_id 分是因为那要在目录里注册多个远端，而落态与「调哪个远端」无关。
_FAIL_MARK = "__e2e_fail__"
_SLOW_MARK = "__e2e_slow__"

#: 慢分支的睡眠时长。**必须大于脚本为超时用例配的端点超时窗**，否则超时不触发。
_SLOW_SECONDS = 5.0


class _StubRemoteHandler:
    """远端 A2A Agent：产 OUTPUT 增量 + COMPLETED 终答（确定性，无 agent-core/LLM）。

    另按正文标记提供两条非成功路径，供部署级覆盖 `failed` 与 `timeout` 两个落态——
    早先这里只有成功一条，于是部署级只锁得住 `done`，而 `timeout` 那条对外文案
    出过一次与存量不一致的缺陷（内部中文原文上了 wire）正是从这个缺口走出去的。
    """

    agent_id = _AGENT_ID
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    def _text_of(request) -> str:  # noqa: ANN001
        for attr in ("query", "text", "message"):
            value = getattr(request, attr, "")
            if isinstance(value, str) and value:
                return value
        return str(getattr(request, "messages", "") or "")

    async def stream_query(self, request) -> AsyncIterator[QueryChunk]:
        """权威 SPI（S3 收口）：流式产出；续接与首轮同一入口（B1-1 不新增 resume SPI）。"""
        text = self._text_of(request)
        if _FAIL_MARK in text:
            yield QueryChunk.of_error("远端拒绝服务", code="REMOTE_REFUSED")
            return
        if _SLOW_MARK in text:
            await asyncio.sleep(_SLOW_SECONDS)
            yield QueryChunk.of_final_answer("这一帧永远来不及")
            return
        yield QueryChunk.of_event(event_type="llm_output", content="思考中")
        yield QueryChunk.of_final_answer(_ANSWER)

    async def query(self, request):
        """阻塞路径：drain 自身流（对齐 handler 的 query 聚合语义）。"""
        chunks = [c async for c in self.stream_query(request)]
        return chunks[-1] if chunks else None

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


class _DelegatingHandler:
    """父 Agent：把本轮请求原样委派给远端，产一次远端委派。

    **把请求正文透传进委派参数**（键 `query`，与委派轨的既有约定一致）——
    脚本据此在一次部署里驱动远端的成功／失败／超时三条路径。
    早先这里写死「查余额」，于是部署级只走得到成功那一条。
    """

    agent_id = "root-planner"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def stream_query(request) -> AsyncIterator[QueryChunk]:
        if getattr(request, "is_resume", False):
            yield QueryChunk.of_final_answer("父答：" + _ANSWER)
            return
        from agent_runtime.domain.remote.delegation import RemoteDelegation  # noqa: PLC0415

        text = _StubRemoteHandler._text_of(request) or "查余额"  # noqa: SLF001
        yield QueryChunk.of_interrupt(
            delegation=RemoteDelegation(
                tool_call_id="e2e-call-1",
                agent_id=_AGENT_ID,
                node_id="n1",
                arguments={"query": text},
            )
        )

    async def query(self, request):
        chunks = [c async for c in self.stream_query(request)]
        return chunks[-1] if chunks else None

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


# app 本体 = 远端 A2A Agent（挂 /a2a）；再在其上加驱动端点。
app = create_a2a_app(
    _StubRemoteHandler(),
    name=_AGENT_ID,
    description="远端规划 Agent（部署级 E2E）",
    version="1.0.0",
    url=_SELF_A2A,
    session_store=_SESSION_STORE,
)

#: 端点超时窗。**比慢分支的睡眠短**，超时用例才走得到超时分支；
#: 成功与失败两条路径都远快于它，不受影响。
_TIMEOUT_S = float(os.environ.get("E2E_REMOTE_TIMEOUT_S", "1.0"))

#: 装配期建的长连 httpx 客户端，生命周期跟随进程（E2E 容器起停即其边界）。
_HTTPX: "httpx.AsyncClient | None" = None


class _LazyOrchestrator(ServeOrchestrator):
    """真实编排器，**批次执行件在首次请求时才装上**。

    ## 为什么要它

    批次执行件依赖 `build_remote_coordinator`——那是个协程（要拉远端 Card 刷新目录），
    模块级装不了。而 `create_a2a_app` 产出的 app 已挂了 `runtime_lifespan`，
    **FastAPI 在有 lifespan 时会忽略 `on_event("startup")`**：早先把装配写在那里，
    路由整个没挂上，脚本对真实 REST 路径发的请求全是 404。

    ## 为什么是继承而不是代理

    `build_rest_router` 的参数标注是具体类 `ServeOrchestrator`。写成鸭型代理时
    `make types` 当场报「incompatible type」——那条报得对：这条依赖边是按具体类
    声明的，一个只转发若干方法的对象与它不是一回事，**下一次编排器新增成员，
    代理会在运行期才暴露**。继承之后只重写 `stream_query` 一处，其余行为原样。
    """

    def __init__(self) -> None:
        super().__init__(_DelegatingHandler())
        self._wiring_lock: "asyncio.Lock | None" = None

    async def _ensure_batch(self) -> None:
        """首次调用时装上批次执行件；之后是空操作。"""
        if self._batch is not None:
            return
        if self._wiring_lock is None:
            self._wiring_lock = asyncio.Lock()
        async with self._wiring_lock:
            if self._batch is not None:  # 并发首请求时只装一次
                return
            global _HTTPX  # noqa: PLW0603  进程级单例，随容器生灭
            from a2a.server.tasks import InMemoryTaskStore  # noqa: PLC0415

            from agent_runtime.adapters.inbound.a2a.chunk_mapper import (  # noqa: PLC0415
                submitted_task,
            )
            from agent_runtime.bootstrap.remote_wiring import (  # noqa: PLC0415
                build_remote_batch_runner,
            )

            _HTTPX = httpx.AsyncClient(timeout=30)
            config = RemoteAgentConfig(
                endpoints=(
                    RemoteEndpoint(
                        agent_id=_AGENT_ID, url=_SELF_A2A, timeout_s=_TIMEOUT_S
                    ),
                )
            )
            coordinator, _directory = await build_remote_coordinator(
                config, httpx_client=_HTTPX
            )
            self._batch = build_remote_batch_runner(
                coordinator,
                task_store=InMemoryTaskStore(),
                task_factory=lambda task_id, context_id: submitted_task(
                    task_id=task_id, context_id=context_id
                ),
                # **经装配根注入会话读取件**——此前该工厂没有这个参数，经组合根装出的
                # 调用器南向恒回落单文本片段，本脚本却照样全绿（2026-08-26 二轮重核 G-01）。
                session_store=_SESSION_STORE,
            )

    async def stream_query(self, ctx, **kwargs):  # noqa: ANN001, ANN003, ANN201
        """**签名要跟着基类走**：`ServeOrchestrator.stream_query` 有
        `on_cooperative_cancel` 关键字参数，路由层（`rest/router.py` 的 `_drive`）
        每次都传它。这里少接一个参数的后果不是「少一个功能」——
        `TypeError` 在 `_drive` 里抛出、被出流层捕获成「流式执行链路失败」，
        对外只剩一帧空 message 的失败帧，整条委派链路一帧都不产出。

        `deploy-e2e/run-remote-tool.sh` 的第 5/7 步因此红了 34 个提交，
        而 16 条部署级 E2E 当时一条都不在门禁序列里，没人看见。
        用 `**kwargs` 透传，基类将来再加参数也不会在这里断掉。
        """
        await self._ensure_batch()
        async for chunk in super().stream_query(ctx, **kwargs):
            yield chunk


# **真实 REST 入口在模块级挂上**（路由表在 app 启动前就定型，不受 lifespan 影响）。
def _mount_real_rest_entry() -> None:
    from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel  # noqa: PLC0415
    from agent_runtime.adapters.inbound.rest.router import build_rest_router  # noqa: PLC0415

    app.include_router(
        build_rest_router(MobileBankChannel(), _LazyOrchestrator(), session_store=_SESSION_STORE)
    )


_mount_real_rest_entry()


@app.get("/drive-delegation-frames")
async def _drive_delegation_frames():
    """走**完整委派链路**取自定义 REST 出口上的帧序。

    与 `/drive-remote-tool` 的区别：那条直接驱动协调器，**不经批次执行件与编排层**，
    于是成员的生命周期边界帧（`node_start` / `node_end`）整条不产生。
    生命周期帧是新增的对外 wire 行为，而 `deploy-e2e/` 对它零覆盖——
    独立复核点名这一处。本端点补上：编排层 → 批次执行件 → 真实 A2A 远端调用 →
    自定义 REST 出口投射，逐帧收下来交给脚本断言。
    """
    from a2a.server.tasks import InMemoryTaskStore

    from agent_runtime.adapters.inbound.a2a.chunk_mapper import submitted_task
    from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel
    from agent_runtime.bootstrap.remote_wiring import build_remote_batch_runner
    from agent_runtime.domain.context import ServeRequest

    config = RemoteAgentConfig(endpoints=(RemoteEndpoint(agent_id=_AGENT_ID, url=_SELF_A2A),))
    async with httpx.AsyncClient(timeout=30) as hc:
        coordinator, _directory = await build_remote_coordinator(config, httpx_client=hc)
        runner = build_remote_batch_runner(
            coordinator,
            task_store=InMemoryTaskStore(),
            task_factory=lambda task_id, context_id: submitted_task(
                task_id=task_id, context_id=context_id
            ),
        )
        orchestrator = ServeOrchestrator(_DelegatingHandler(), batch_runner=runner)
        channel = MobileBankChannel()
        events: list[str] = []
        inners: list[dict] = []
        request = ServeRequest.of_text("查余额", conversation_id="ctx-e2e-frames")
        async for chunk in orchestrator.stream_query(request):
            envelope = channel.format_event(
                chunk, agent_id="root-planner", conversation_id="ctx-e2e-frames", elapsed=0.0
            )
            if envelope is None:
                continue
            inner = envelope.get("custom_rsp_data") or {}
            if inner.get("event") == "sub_task":
                nested = inner.get("data") or {}
                # **只认对外形态的 `event` 键**：上一版还兜底收 `type`，
                # 而「事件名落错键位」这个缺陷正好从那个兜底里逃出去。
                events.append(str(nested.get("event") or ""))
                if nested.get("event") in ("node_start", "node_end"):
                    inners.append(nested)
    # 带出边界帧的**完整内层**，供脚本断言字段集与取值——
    # 只断成员与顺序时，整套落态词表在部署级一条都没锁。
    return {"lifecycle_events": events, "boundary_frames": inners}


@app.get("/probe-southbound-arrival")
async def _probe_southbound_arrival(conversation_id: str):
    """自环 A2A 入站是否收到了南向数据片段：报出该会话的入站写入尝试。

    REST 入口先按 `conversation_id` 写首轮快照；批次执行件经装配根读它、组装南向
    数据片段；对端（本进程自身的 `/a2a`）解析片段后再尝试写同一会话——
    这次尝试只会在**数据片段真的经真 socket 到达**时发生。
    """
    # **按写入方的服务身份区分两次写入**：REST 入口写的快照带 REST 侧智能体身份，
    # 自环 A2A 入站写的快照带本 A2A 服务身份（`_AGENT_ID`）。只看「有没有写入」会被
    # REST 入口那一次骗过——实测过：装配根不接读取件时脚本照样绿。
    # **认父会话，也认它派生出的子会话**：南向的会话标识按存量形态派生为
    # `{父会话}-sub-{目标标识}`（2026-08-27 取存量），对端入站因此写在派生会话上，
    # 不是父会话上。只认父会话会把「片段确实到了」误判成「没到」——本步骤就这么红过一次。
    attempts = [
        {"keys": sorted(snapshot), "headers": snapshot.get("headers", {}),
         "trace_id": snapshot.get("trace_id", ""), "agent_id": snapshot.get("agent_id", ""),
         "conversation_id": cid}
        for cid, snapshot in _SESSION_STORE.put_attempts
        if cid == conversation_id or cid.startswith(f"{conversation_id}-sub-")
    ]
    return {"conversation_id": conversation_id, "attempts": attempts}


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "remote-tool", "self_a2a": _SELF_A2A}


async def _drain_final_answer(coordinator, query: str, *, context_id: str) -> str:
    """驱动一次远端调用，归一为终答文本。

    **不用 `remote_tool.RemoteToolInvoker`**：那个模块自陈「已废弃，新代码不得使用」
    （`agent_runtime/adapters/outbound/remote/remote_tool.py`），
    而本 E2E 一直拿它当驱动手段——等于让部署级验证给一个要淘汰的实现背书
    （外部报告 M-5 报的正是这一点）。

    **本 E2E 验的是 A2A 线级往返**（出站客户端 ↔ 入站服务端，经真实 socket 与真实
    a2a-sdk），不是委派编排。故这里只把驱动手段就地内联，不引入批次编排——
    那条链路已有自己的部署级验证（`run-call-depth.sh`、`run-callback.sh`、
    `run-stream-fallback.sh` 三条）。

    归一规则与被替换的那份逐字相同：终答取内容帧、错误取诊断文案。
    中断分支不在本 E2E 的路径上（stub 远端不产中断），故不承接。
    """
    outcome = ""
    async for result in coordinator.call(_AGENT_ID, query, context_id=context_id):
        if result.is_answer:
            outcome = result.content
        elif result.is_completion and result.content and not outcome:
            outcome = result.content  # 完成正文只作回落（Feat-Func-002b §4.4）
        elif result.type == QueryChunk.TYPE_ERROR:
            outcome = f"[远端调用失败] {result.message}"
    return outcome


@app.get("/drive-remote-tool")
async def _drive():
    """对自身 /a2a 发起真实远端工具调用，返回归一后的终答。"""
    config = RemoteAgentConfig(endpoints=(RemoteEndpoint(agent_id=_AGENT_ID, url=_SELF_A2A),))
    async with httpx.AsyncClient(timeout=30) as hc:
        coordinator, directory = await build_remote_coordinator(config, httpx_client=hc)
        entry = directory.get(_AGENT_ID)
        available = bool(entry and entry.available)
        out = await _drain_final_answer(coordinator, "查余额", context_id="ctx-e2e")
    return {"available": available, "answer": out, "expected": _ANSWER, "match": out == _ANSWER}
