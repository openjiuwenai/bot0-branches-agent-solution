# coding: utf-8

"""部署级 E2E 变体：把标准 A2A 面挂在 `/a2a` 前缀下，供与存量做对等比对。

## 它与 `e2e_a2a_server.py` 的分工

那一份验的是本版自身的 wire 契约；本份验的是**两侧对不对得上**。

两侧的 A2A 面都在 `/a2a` 前缀下：存量在启动钩子里 `mount("/a2a", ...)`，
本版的 `create_a2a_app` 默认 `mount_path="/a2a"`。对等比对要求两侧的可达路径一致，
否则比的是两个不同的东西。

## 处理器是确定性替身

真实智能体的输出不确定，两侧比不了。替身产出固定内容，
故差异只可能来自 runtime 自身的 wire 形态——**那正是本条要比的东西**。
"""
from __future__ import annotations

import os
import sys
from typing import Any, AsyncIterator

# **append 而非 insert(0)**：仓根本来就由 PYTHONPATH 给到（其余 e2e 服务全靠它，
# 一个都没改过 sys.path），这里只是兜底；抢占 sys.path 首位会让本仓的同名模块
# 盖住标准库与三方库，而那种遮蔽的报错位置看不出根因。
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.append(_REPO_ROOT)

from fastapi import FastAPI  # noqa: E402

from agent_runtime.bootstrap.a2a_app import create_a2a_app  # noqa: E402
from agent_runtime.domain.result import QueryChunk  # noqa: E402

_AGENT_NAME = os.getenv("E2E_A2A_AGENT_NAME", "parity_agent")


class _DeterministicHandler:
    """产出固定内容的处理器替身——两侧比对的前提。

    **方法面按 `AgentHandler` 契约给全**：装配期按契约校验，缺一项即被拒收
    （身份、健康、生命周期三组都要）。少给会让本 server 起不来，
    而报错指向契约而非本文件，排查要绕一圈。
    """

    agent_id = "e2e-parity"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def stream_query(request: Any) -> AsyncIterator[QueryChunk]:
        yield QueryChunk.of_event("thought", content="固定的前半截")
        yield QueryChunk.of_final_answer("固定的答复")

    @staticmethod
    async def query(request: Any) -> Any:
        from agent_runtime.domain.result import QueryResponse

        return QueryResponse(result="固定的答复")

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


#: 本版的 A2A 面。**`create_a2a_app` 自带 `mount_path`，默认就是 `/a2a`**——
#: 与存量的挂载前缀天然一致，不必也不能再套一层 mount：套了会变成 `/a2a/a2a`，
#: 而卡片端点注册在站点根、不受挂载影响，于是**卡片 200 而 JSON-RPC 404**，
#: 看起来像本版没实现 RPC。初版就这么写的，实跑才发现。
#: **卡片的可选字段要与存量配平**，否则比出来的是配置差异不是实现差异。
#: 存量配了 `description`、没配两个模态默认值；本版默认相反。
#: 实测未配平时字段集差三项（本版多 `defaultInputModes`／`defaultOutputModes`、
#: 少 `description`）——那三项都由配置决定，不是 wire 形态的差异。
#:
#: 配平之后剩下的差异才是要报的东西。
app: FastAPI = create_a2a_app(
    _DeterministicHandler(),
    name=_AGENT_NAME,
    description="对等比对用的确定性智能体",
    card_default_input_modes=None,
    card_default_output_modes=None,
)


@app.get("/health")
async def _health() -> dict[str, str]:
    return {"status": "ok", "mode": "a2a-parity", "agent": _AGENT_NAME}
