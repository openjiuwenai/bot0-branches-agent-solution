# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pylint: disable=line-too-long,protected-access


"""部署级 E2E 服务端：异步回调回灌的真实往返（Feat-Func-004b §4.8、§3.4）。

## 它验什么

此前这条链路的每一环都只被替身验过——**没有一次真的 HTTP 回调打进来过**。
本服务端把它跑真：

  批次登记（远端受理即登记成员）
    → 假远端**真的 POST** 回调到本地接收端点
    → 真实路由 → 鉴权 → 幂等判重 → 认领 → 成员落定

## 为什么假远端必须真的发 HTTP

在进程内直接调接收器，测不到路由、请求头、载荷形态、响应码——
而那正是回调这条入口的全部对外面。本项目实证过三次：单元判据全绿而线级契约有缺陷。

## 载荷形态取自真实契约，不自造

对标的接收控制器要求：体是 JSON-RPC 结果对象、含任务对象、通知标识经请求头传递
（`openJiuwen/agent-runtime-java/service/agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/A2aPushNotificationCallbackController.java`）。
自造的载荷验过了也不作数。

## 端点

- `/health` 就绪探测
- `/drive-callback` 驱动一次完整往返，返回各步骤的观测结果
"""
from __future__ import annotations

import json
import os
from typing import Any, AsyncIterator

import httpx

from agent_runtime.adapters.inbound.a2a.webhook import (
    RedisPushNotificationConfigStore,
    TrustedWebhookSender,
    WebhookSettings,
)
from agent_runtime.adapters.outbound.remote.batch_runner import shadow_task_id
from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.bootstrap.remote_wiring import build_remote_batch_runner
from agent_runtime.domain.result import QueryChunk

# **自指端口读 `PORT`、默认容器内的 8090**，与其余五个自指模块一致。
# 此前读的是 `E2E_PORT`、默认 18096（宿主侧端口）——容器内没有 18096 在监听，
# 本模块向自己发回调时必然连接失败。它只在本机后端下能跑，而后端默认选容器，
# 于是这条 E2E 长期红着：报错是 `ConnectError`，看不出与端口取名有关。
PORT = int(os.environ.get("PORT", "8090"))
BASE = f"http://127.0.0.1:{PORT}"

#: 回调接收路径。与 `bootstrap/a2a_app.py` 的自建路由一致——两处不一致时本 E2E 会 404，
#: 那正是它该抓的：路径是对外契约的一部分。
CALLBACK_PATH = "/a2a/push-notifications/callback"

NOTIFICATION_ID_HEADER = "X-A2A-Notification-Id"
REMOTE_TASK_ID = "remote-task-e2e-1"
PARENT_TASK_ID = "parent-e2e-1"
AGENT_ID = "remote-agent-e2e"
TOOL_CALL_ID = "tc-e2e-1"


class _Handler:
    """最简处理器：本 E2E 不验执行链路，只验回调链路。"""

    agent_id = "e2e-callback"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def stream_query(request) -> AsyncIterator[QueryChunk]:  # noqa: ANN001
        yield QueryChunk.of_final_answer(content="ok")

    @staticmethod
    async def query(request):  # noqa: ANN001
        from agent_runtime.domain.result import QueryResponse

        return QueryResponse(result="ok", conversation_id=request.conversation_id)

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


class _MemoryCache:
    """判重用的最简存储。**条件写入必须是原子的**——非原子会让并发的重复回调都判为首见。"""

    def __init__(self) -> None:
        self._data: dict[str, bytes] = {}

    async def setnx(self, key: str, value: bytes, *, ttl_s: int) -> bool:
        if key in self._data:
            return False
        self._data[key] = value
        return True

    async def get(self, key: str):  # noqa: ANN201
        return self._data.get(key)

    # ── 端口协议的其余成员（装配期按契约校验，缺一项即被拒收）──
    # **真做而非假装**：内存字典的语义与真实客户端同构。返回假成功会让判据
    # 在一条不存在的路径上通过。
    async def set(self, key: str, value: bytes, *, ttl_s: int) -> None:
        self._data[key] = value

    async def setex(self, key: str, ttl_s: int, value: bytes) -> None:
        self._data[key] = value

    async def delete(self, key: str) -> None:
        self._data.pop(key, None)

    async def exists(self, key: str) -> bool:
        return key in self._data

    async def write_externally_governed(self, key: str, value: bytes) -> None:
        self._data[key] = value

    async def mget(self, keys: list) -> list:  # noqa: ANN001
        return [self._data.get(k) for k in keys]

    def scan(self, match: str, *, count: int = 100):  # noqa: ANN201
        if not match.endswith("*"):
            raise NotImplementedError(f"替身只支持尾部通配，实得 {match!r}")
        prefix = match[:-1]

        async def _iter():
            for key in list(self._data):
                if key.startswith(prefix):
                    yield key.encode("utf-8")

        return _iter()

    async def aclose(self) -> None:
        """无底层连接可释放——空实现是「确实没事可做」，不是登记态桩。"""


class _Task:
    def __init__(self, task_id: str = "") -> None:
        self.id = task_id
        self.metadata: dict = {}


class _ListResult:
    def __init__(self, tasks: list) -> None:
        self.tasks = tasks


class _TaskStore:
    """原始存储（**未经对外视图包装**，§3.4.4）——包成视图后一条影子任务也读不到。

    **参数面逐项对齐真实存储**：两个位置参数、都必需。写成可选会让「产品代码
    少传参数」这个缺陷在本探针下完全看不见——而它让回调认领对任何真实存储都失效。
    """

    def __init__(self) -> None:
        self.tasks: dict[str, _Task] = {}

    async def get(self, task_id: str, context):  # noqa: ANN001,ANN201
        return self.tasks.get(task_id)

    async def save(self, task: _Task, context) -> None:  # noqa: ANN001
        self.tasks[task.id] = task

    async def list(self, params, context):  # noqa: ANN001,ANN201
        return _ListResult(list(self.tasks.values()))


class _AllowAll:
    """E2E 的鉴权实现。**生产默认拒绝一切**——这里放行是为了验后续步骤。"""

    @staticmethod
    async def authorize(headers: dict, body: dict) -> bool:
        return True


_store = _TaskStore()
_cache = _MemoryCache()
_batch_runner = build_remote_batch_runner(
    coordinator=None,          # 本 E2E 不发起真实远端调用，只验回调侧
    task_store=_store,
    task_factory=lambda: _Task(),
)


# 本体即 A2A 入口（含自建的回调接收路由）；驱动端点加在其上。
#
# ## 六件都要注入，缺一就是「未启用」
#
# 权威 `Technical-AF/docs/develop/02-features/`CL-f0ddc470b6e4``
# 是 MUST：「runtime 在**启用 push notification 行为且授权策略配置完成时**，必须 host
# 固定 callback 接收入口；**未启用时不得暴露该能力或必须返回未启用响应**」。
# 上游同形——`A2aPushNotificationCapabilityGate.isPushNotificationsEnabled()` 的注释逐字是
# 「true only when the local runtime can complete **both push delivery and callback recovery**」，
# 即投递与回灌**两侧都能完成**才算启用。
#
# **此前本模块只注入接收侧三件**（判重存储、鉴权件、批次执行件），缺投递侧的
# 配置存储与发送器，于是回调端点按权威正确地返回 501「not enabled」——
# 而本 E2E 的断言把它判成「路由或鉴权问题」。**判词错在假定接收侧独立可用**。
# 该错长期没暴露，因为这条 E2E 没有执行点（见 ISSUE-LEDGER 的 V-4）。
_webhook_settings = WebhookSettings(
    enabled=True, allowed_hosts=["127.0.0.1"], allowed_schemes=["http"]
)
app = create_a2a_app(
    _Handler(),
    name="e2e-callback",
    description="回调回灌往返（部署级 E2E）",
    url=f"{BASE}/a2a/",
    # 投递侧（本 E2E 不真的投递，但**启用条件要求它在场**）
    push_config_store=RedisPushNotificationConfigStore(_cache, settings=_webhook_settings),
    push_sender=TrustedWebhookSender(
        httpx.AsyncClient(), None, settings=_webhook_settings
    ),
    # 接收侧
    push_callback_cache=_cache,
    push_callback_authorizer=_AllowAll(),
    batch_runner=_batch_runner,
)


@app.get("/health")
async def _health():
    return {"ok": True}


@app.post("/drive-callback")
async def _drive():
    """驱动一次完整往返，返回各步的观测结果供断言。"""
    steps: dict[str, Any] = {}

    # ① 登记成员：模拟远端受理后回传标识（§3.4.3 的时机）
    _batch_runner._batch_context = {
        "parent_task_id": PARENT_TASK_ID, "agent_id": AGENT_ID,
    }
    await _batch_runner.on_member_remote_id(TOOL_CALL_ID, REMOTE_TASK_ID)
    snap = _snapshot()
    steps["registered"] = any(
        m.get("remoteTaskId") == REMOTE_TASK_ID for m in snap.get("members", [])
    )
    steps["settled_before"] = _settled()

    # ② 假远端**真的 POST** 回调——载荷形态取自真实契约
    body = {
        "jsonrpc": "2.0",
        "result": {
            "task": {
                "id": REMOTE_TASK_ID,
                "contextId": "ctx-e2e",
                "status": {"state": "TASK_STATE_COMPLETED"},
            }
        },
    }
    async with httpx.AsyncClient(timeout=20) as client:
        first = await client.post(
            f"{BASE}{CALLBACK_PATH}", json=body,
            headers={NOTIFICATION_ID_HEADER: "notif-e2e-1"},
        )
        steps["first_status"] = first.status_code
        steps["first_body"] = first.json() if first.content else {}

        # ③ 同一通知重投——幂等：应判重复，且不改变已落定的结果
        second = await client.post(
            f"{BASE}{CALLBACK_PATH}", json=body,
            headers={NOTIFICATION_ID_HEADER: "notif-e2e-1"},
        )
        steps["second_status"] = second.status_code
        steps["second_body"] = second.json() if second.content else {}

    steps["settled_after"] = _settled()
    return steps


def _snapshot() -> dict:
    task = _store.tasks.get(shadow_task_id(parent_task_id=PARENT_TASK_ID, agent_id=AGENT_ID))
    if task is None:
        return {}
    raw = task.metadata.get("_remote_batch")
    return json.loads(raw) if isinstance(raw, str) else (raw or {})


def _settled() -> bool:
    return any(
        m.get("settled") for m in _snapshot().get("members", [])
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="warning")
