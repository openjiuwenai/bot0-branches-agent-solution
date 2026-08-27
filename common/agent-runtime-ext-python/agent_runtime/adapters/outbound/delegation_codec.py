# coding: utf-8

"""委派中断载荷的编解码——**出站适配层的公共件，不属于任何一个适配器包**。

## 为什么在这里，不在某个适配器包里

这四件东西被两个出站适配器共用：`remote` 包里的占位工具**构造**委派载荷，
`agentcore` 包里的流翻译件**识别**它。修前四件全在 `remote/delegation_rail.py`，
于是 `agentcore/stream_adapter.py` 要反过来导入 `remote` 包——
**一个适配器依赖另一个适配器**，两侧耦合在一个谁也不拥有的约定上。

同层横向依赖门禁 `tools/layer_lateral_guard.py` 自己的判词逐字是：
「共享的东西不属于任何一侧时，**它就该下沉**」。而它此前只判 `inbound` 与 `outbound`
之间，`outbound` 内部各适配器包之间它一句话不说，于是这处依赖从它下面走过去，
读数是「✓ 无同层横向依赖」——**规则面是人定的，因此一定有洞**。

放在 `outbound/` 顶层而不是 `domain/`：这套标记键与字段名是**我方与 agent-core
之间关于中断值形态的约定**，是适配细节，上浮进领域层会让领域知道框架的中断值格式。
同目录的 `interruptible.py` 是同一档的公共件，两者位置一致。

## 委派中断必须与纯用户交互中断可区分

否则会把委派投影给用户看（撞 FEAT-008 的边界）。故用**显式标记键**包裹，
不靠载荷形状猜测——形状会撞，标记不会。
"""
from __future__ import annotations

import uuid
from typing import Any, Optional

from agent_runtime.domain.remote.delegation import RemoteDelegation

#: 委派中断的显式标记键——靠标记而非载荷形状识别，避免与用户交互中断混淆。
DELEGATION_MARKER = "__remote_delegation__"


def build_delegation_payload(
    *,
    agent_id: str,
    tool_name: str,
    arguments: dict,
    node_id: str,
    tool_call_id: str = "",
) -> dict:
    """构造委派中断载荷。`tool_call_id` 缺省时生成——它是全程关联身份，必须唯一。"""
    return {
        DELEGATION_MARKER: {
            "toolCallId": tool_call_id or f"call-{uuid.uuid4().hex}",
            "agentId": agent_id,
            "toolName": tool_name or agent_id,
            "arguments": dict(arguments or {}),
            "nodeId": node_id,  # agent-core 回灌定位键，随委派一起过河
        }
    }


def is_delegation_interrupt(value: Any) -> bool:
    """该中断值是否为远端委派（区别于纯用户交互中断）。"""
    return isinstance(value, dict) and isinstance(value.get(DELEGATION_MARKER), dict)


def parse_delegation(value: Any) -> Optional[RemoteDelegation]:
    """委派载荷 → 领域对象；非委派返回 ``None``（调用方据此走用户交互中断路径）。"""
    if not is_delegation_interrupt(value):
        return None
    inner = value[DELEGATION_MARKER]
    return RemoteDelegation(
        tool_call_id=inner.get("toolCallId", ""),
        agent_id=inner.get("agentId", ""),
        tool_name=inner.get("toolName", ""),
        arguments=dict(inner.get("arguments") or {}),
        node_id=inner.get("nodeId", ""),
    )
