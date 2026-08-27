# coding: utf-8
# 参考宿主与部署级 E2E 装置：SPI 实现方法必须是实例方法；
# 按场景直接构造 runtime 内部状态是这一层的职责，不是越界访问。
# pylint: disable=protected-access


"""断流降级的部署级 E2E（Feat-Func-004b §7.4）。

## 为什么进程内判据不够

进程内判据用替身产生 `ConnectionError`，验的是降级件收到异常后的分级逻辑。
真实往返多覆盖三样：

| 多出的 | 为什么进程内看不到 |
|---|---|
| 降级真的被装进了调用链 | 进程内直接调降级件；编排件漏包时进程内照样绿 |
| 真实的传输层断开确实触发降级 | 替身抛的是 `ConnectionError`；真实断开由 httpx／h11 抛出，类型未必相同 |
| 重订阅走的是真 A2A 订阅方法 | 进程内的 `resubscribe` 是替身，不校验协议方法存在与否 |

第二样是本项的要害：降级件用 `except Exception` 兜住断流，**但编排件外层的
`asyncio.timeout` 与传输件自己的异常处理都可能先一步把异常转成别的东西**。
真实断开跑一遍才知道降级到底有没有被触发。

## 断流怎么造

**切断代理**：一个 TCP 转发器，主调方连它、它连远端 A2A 服务。
第一个连接在转发若干字节后直接关闭 socket——那是传输层断开，
不是应用层的失败终态（后者会变成 FAILED，走的是另一条路，验不到降级）。
第二个及以后的连接原样透传，好让重订阅能成功。

**替身只挡住不确定性**：被替掉的是「网络在此刻断开」这件事本身。
降级判断、退避、重订阅、终态查询全部是被测件在跑，走真实 socket。
"""
from __future__ import annotations

import asyncio
import os
from typing import AsyncIterator

import httpx

from agent_runtime.adapters.outbound.remote.config import RemoteAgentConfig, RemoteEndpoint
from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.bootstrap.remote_wiring import build_remote_coordinator
from agent_runtime.domain.result import QueryChunk

# 默认 8090 = 容器里的固定监听端口（`Dockerfile` 的 CMD）。本机后端另传 PORT。
# **默认值写错时表现为「卡片发现失败」而非任何报错**：代理照常起、照常接收连接，
# 只是转发到了没人监听的端口。实测容器下读数为 `available: false`、连接数 4、切断 0。
_PORT = int(os.environ.get("PORT", "8090"))
_PROXY_PORT = int(os.environ.get("BREAK_PROXY_PORT", str(_PORT + 1)))
#: 首个调用连接转发多少个下行块后切断。
#: **按块数而非字节数**：要切在「客户端已拿到远端任务标识」之后——没有标识就走
#: §7.4.2 的「不降级、直接判失败」，验不到重订阅。首块是响应头加受理事件（带标识），
#: 从第二块起切才落在「标识已到手、终答未到」这个窗口里。
#: 实测按 64 字节切时读数是 `远端流中断且无任务标识可恢复`——那是正确行为，但验错了分支。
_BREAK_AFTER_CHUNKS = int(os.environ.get("BREAK_AFTER_CHUNKS", "1"))

_SELF_ROOT = os.environ.get("SELF_ROOT_URL", f"http://127.0.0.1:{_PORT}")
_SELF_A2A = os.environ.get("SELF_A2A_URL", f"{_SELF_ROOT}/a2a/")
_PROXY_ROOT = f"http://127.0.0.1:{_PROXY_PORT}"
_PROXY_A2A = f"{_PROXY_ROOT}/a2a/"
_ANSWER = "断流后经降级取回的终答"
_AGENT_ID = "flaky-remote"


class _StubRemoteHandler:
    """远端 A2A Agent：产增量 + 终答（确定性，无 LLM）。"""

    agent_id = _AGENT_ID
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def stream_query(request) -> AsyncIterator[QueryChunk]:
        yield QueryChunk.of_event(event_type="llm_output", content="第一段增量")
        # 留出时间让代理先切断——切在首帧之后、终答之前，才是「流中途断开」
        await asyncio.sleep(0.3)
        yield QueryChunk.of_final_answer(_ANSWER)

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


# **卡片里的接口地址必须写代理**：目录项从卡片的 supportedInterfaces 解析出调用地址，
# 卡片写直连地址时调用就绕过代理——实测读数是「连接 1 个、切断 0 次、结果却正确」，
# 那个「正确」来自一次根本没断过的直连调用。
app = create_a2a_app(
    _StubRemoteHandler(),
    name=_AGENT_ID,
    description="断流降级 E2E 的远端 Agent",
    version="1.0.0",
    url=_PROXY_A2A,
)


class _BreakingProxy:
    """转发到远端 A2A 服务；**第一个调用连接中途切断**，其后放行。

    切断发生在传输层（RST 中止连接），不是应用层错误终态——
    后者会被远端投射成 FAILED，走的是失败路径而非降级路径，验错对象。

    **按请求方法区分连接**：卡片发现是 `GET /.well-known/...`，调用是 `POST`。
    只切断调用连接——切在发现上会把「断流恢复」验成「发现容错」，那是另一件事。
    """

    def __init__(self) -> None:
        self.connections = 0
        self.call_connections = 0
        self.broken = 0
        self.started = False
        self._server: asyncio.Server | None = None

    async def start(self) -> None:
        self._server = await asyncio.start_server(
            self._handle, "127.0.0.1", _PROXY_PORT
        )
        self.started = True

    async def _handle(
        self, client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter
    ) -> None:
        self.connections += 1
        try:
            up_reader, up_writer = await asyncio.open_connection("127.0.0.1", _PORT)
        except OSError:
            client_writer.close()
            return

        # **持续嗅探，不能只看首块**：httpx 的 keep-alive 让卡片发现（GET）与
        # 调用（POST）复用同一个 TCP 连接。只判首块时，这条连接永远被认成 GET，
        # 其上的 POST 再也不会被切——实测读数正是「连接 1 个、切断 0 次」。
        state = {"break_armed": False, "chunks": 0}

        async def pump_up() -> None:
            try:
                while True:
                    data = await client_reader.read(4096)
                    if not data:
                        break
                    if b"POST " in data:
                        self.call_connections += 1
                        if self.call_connections == 1:
                            # 从这个响应开始计块：之前发现响应的块不算在内
                            state["break_armed"] = True
                            state["chunks"] = 0
                    up_writer.write(data)
                    await up_writer.drain()
            # `ConnectionResetError` 是 `OSError` 的子类，不并列写（重复捕获同类异常）。
            except OSError:
                pass
            finally:
                up_writer.close()

        async def pump_down() -> None:
            try:
                while True:
                    data = await up_reader.read(4096)
                    if not data:
                        break
                    if state["break_armed"] and state["chunks"] >= _BREAK_AFTER_CHUNKS:
                        self.broken += 1
                        state["break_armed"] = False   # 只切这一次
                        client_writer.transport.abort()   # RST，不是优雅关闭
                        return
                    client_writer.write(data)
                    await client_writer.drain()
                    state["chunks"] += 1
            # `ConnectionResetError` 是 `OSError` 的子类，不并列写（重复捕获同类异常）。
            except OSError:
                pass
            finally:
                if not client_writer.is_closing():
                    client_writer.close()

        await asyncio.gather(pump_up(), pump_down(), return_exceptions=True)


_proxy = _BreakingProxy()


async def _ensure_proxy() -> None:
    """惰性启动切断代理。

    **不用启动钩子**：`create_a2a_app` 装的是 lifespan 上下文，
    而 lifespan 与 `on_event` 互斥——挂了 lifespan 的应用不会执行 `on_event`。
    此前用启动钩子时代理静默没起，读数是「连接数 0」而非任何报错。
    """
    if _proxy.started:
        return
    await _proxy.start()


@app.get("/health")
async def _health():
    return {
        "status": "ok",
        "mode": "stream-fallback",
        "self_a2a": _SELF_A2A,
        "proxy_a2a": _PROXY_A2A,
        "proxy_started": _proxy.started,
    }


@app.get("/proxy-stats")
async def _proxy_stats():
    """代理的真实读数——**用于证明断流确实发生过**。

    没有这个读数，「调用成功」既可能是降级生效，也可能是压根没断过。
    """
    return {"connections": _proxy.connections, "broken": _proxy.broken}


async def _drain_final_answer(coordinator, query: str, *, context_id: str) -> str:
    """驱动一次远端调用，归一为终答文本。

    **不用 `remote_tool.RemoteToolInvoker`**：那个模块自陈「已废弃，新代码不得使用」
    （`agent_runtime/adapters/outbound/remote/remote_tool.py`），
    而本 E2E 一直拿它当驱动手段——部署级验证不该给一个要淘汰的实现背书
    （外部报告 M-5）。

    本 E2E 验的是**断流降级**（传输层切断后能否重连并把终答兜回来），
    驱动手段只是达到那一步的路径，故就地内联、不引入批次编排。
    归一规则与被替换的那份逐字相同。
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


@app.get("/drive-stream-fallback")
async def _drive():
    """经切断代理对自身 /a2a 发起真实远端调用；断流由代理制造，恢复由降级件完成。"""
    await _ensure_proxy()
    config = RemoteAgentConfig(
        endpoints=(RemoteEndpoint(agent_id=_AGENT_ID, url=_PROXY_A2A, timeout_s=60.0),)
    )
    async with httpx.AsyncClient(timeout=60) as hc:
        # **全程走代理**：发现与调用同一地址，与真实部署一致。
        # 代理按请求方法区分——发现（GET）放行、首个调用（POST）切断，
        # 断流因此落在调用阶段而不是发现阶段。
        coordinator, directory = await build_remote_coordinator(config, httpx_client=hc)
        entry = directory.get(_AGENT_ID)
        available = bool(entry and entry.available)
        out = await _drain_final_answer(
            coordinator, "触发断流", context_id="ctx-fallback-e2e"
        )
    return {
        "available": available,
        "answer": out,
        "expected": _ANSWER,
        "match": out == _ANSWER,
        "remote_task_id": coordinator.last_remote_task_id,
        "probe": await _probe_get_task(coordinator.last_remote_task_id),
        "proxy": {"connections": _proxy.connections, "broken": _proxy.broken},
    }


async def _probe_get_task(task_id: str) -> dict:
    """直接查一次远端任务，报出终态查询这一级实际能拿到什么。

    **诊断用**：降级取回空结果时，要能区分「查询没被调用」与「查询调了但任务里没内容」。
    """
    if not task_id:
        return {"note": "无远端任务标识"}
    from a2a.types.a2a_pb2 import GetTaskRequest

    from agent_runtime.bootstrap.remote_wiring import build_remote_coordinator as _b

    async with httpx.AsyncClient(timeout=30) as hc:
        coord, _ = await _b(
            RemoteAgentConfig(
                endpoints=(RemoteEndpoint(agent_id=_AGENT_ID, url=_SELF_A2A, timeout_s=30.0),)
            ),
            httpx_client=hc,
        )
        client = coord._client_factory(_SELF_A2A)  # noqa: SLF001  诊断端点
        try:
            task = await client._client.get_task(GetTaskRequest(id=task_id))  # noqa: SLF001
        except Exception as exc:  # noqa: BLE001
            return {"error": f"{type(exc).__name__}: {exc}"}
        status = getattr(task, "status", None)
        msg = getattr(status, "message", None) if status else None
        parts = list(getattr(msg, "parts", []) or []) if msg else []
        return {
            "state": int(getattr(status, "state", -1)) if status else -1,
            "has_message": msg is not None,
            "part_count": len(parts),
            "texts": [str(getattr(p, "text", "") or "") for p in parts],
            "artifact_count": len(list(getattr(task, "artifacts", []) or [])),
        }
