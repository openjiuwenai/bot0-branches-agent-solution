from .ask_user import ask_user_tool
from .call_mcp import call_mcp_tool
from .call_versatile import call_versatile_tool
from .cancel_task import cancel_task_tool
from .lite_todo import lite_todo_tools
from .call_multiagent import call_multiagent_tool
from .call_multiversatile import call_multiversatile_tool
from .scenario_tool import build_tools


# 向后兼容：保留 TOOLS 名字——访问触发 build_tools()。
def __getattr__(name: str):
    if name == "TOOLS":
        return build_tools()
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


__all__ = [
    "ask_user_tool",
    "call_mcp_tool",
    "call_versatile_tool",
    "cancel_task_tool",
    "lite_todo_tools",
    "call_multiagent_tool",
    "call_multiversatile_tool",
    "build_tools",
    "TOOLS",
]
