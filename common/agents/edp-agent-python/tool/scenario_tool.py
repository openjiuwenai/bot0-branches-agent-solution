"""场景工具构建器：通用工具始终注册，专属工具按场景声明配套注册。"""

from __future__ import annotations

from loguru import logger

from .ask_user import ask_user_tool
from .call_mcp import call_mcp_tool
from .call_versatile import call_versatile_tool
from .cancel_task import cancel_task_tool
from .lite_todo import lite_todo_tools
from .call_multiagent import call_multiagent_tool
from .call_multiversatile import call_multiversatile_tool

# ── 专属工具映射：tool_name → tool 实例 ─────────────────────────────────
_SCENARIO_TOOL_MAP = {
    "call_multiagent": call_multiagent_tool,
    "call_multiversatile": call_multiversatile_tool,
}

# ── 通用工具（始终注册）──────────────────────────────────────────────────
_COMMON_TOOLS = [
    ask_user_tool,
    call_mcp_tool,
    call_versatile_tool,
    cancel_task_tool,
]


def build_tools(scenario_tools: list[str] | None = None) -> list:
    """运行时构建工具列表——必须在 ``configure_steps()`` 之后调用。

    Args:
        scenario_tools: 场景声明的专属工具列表（如 ["call_multiagent"]）。
            为 None 或空列表时，仅注册通用工具（向后兼容）。

    抛错路径：未调用 configure_steps() 时 ``lite_todo_tools()`` 会触发
    `LiteTodoWriteTool()` 构造，进而抛 RuntimeError。
    """
    tools = [*_COMMON_TOOLS, *lite_todo_tools()]

    if scenario_tools:
        for tool_name in scenario_tools:
            tool = _SCENARIO_TOOL_MAP.get(tool_name)
            if tool is not None:
                tools.append(tool)
                logger.info(f"[build_tools] 场景专属工具已注册：{tool_name}")
            else:
                logger.warning(
                    f"[build_tools] 场景声明了未知工具：{tool_name!r}，跳过"
                )

    return tools
