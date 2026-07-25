"""Rail 构建器：通用 Rail 始终注册，专属 Rail 按场景 tools 声明配套注册。"""

from __future__ import annotations

from typing import Optional

from loguru import logger

from .versatile_interrupt_rail import VersatileInterruptRail
from .iteration_limit_rail import IterationLimitRail
from .execution_limit_rail import ExecutionLimitRail
from .mcp_interrupt_rail import MCPInterruptRail
from .cancel_rail import CancelRail
from .log_rail import LogRail
from .ask_user_rail import AskUserRail
from .multiagent_interrupt_rail import MultiagentInterruptRail
from .multiversatile_interrupt_rail import MultiversatileInterruptRail
from ..agent_rule import ScriptsConfig

# ── 专属 Rail 映射：tool_name → Rail 类 ──────────────────────────────────
# 场景声明了对应工具时，需配套注册对应的 Rail
_SCENARIO_RAIL_MAP = {
    "call_multiagent": MultiagentInterruptRail,
    "call_multiversatile": MultiversatileInterruptRail,
}


def build_rails(
    scenario_tools: list[str] | None = None,
    agent_rule=None,
    scripts_config: Optional[ScriptsConfig] = None,
    sys_operation_id: Optional[str] = None,
    model_name: str = "",
    tools: list | None = None,
    memory_rail_registered: bool = False,
) -> list:
    """构建 Rail 列表。

    通用 Rail 始终注册，专属 Rail 按场景 tools 声明配套注册。

    Args:
        scenario_tools: 场景声明的专属工具列表
        agent_rule: AgentRule 配置对象（IterationLimitRail / ExecutionLimitRail 需要）
        scripts_config: 话术配置（CancelRail / MCPInterruptRail / VersatileInterruptRail / AskUserRail 需要）
        sys_operation_id: 系统操作 ID（MCPInterruptRail / VersatileInterruptRail 需要）
        model_name: 模型名称（LogRail 需要）
        tools: 工具列表（LogRail 需要）
        memory_rail_registered: 是否已注册 MemoryRail
    """
    rails = []

    # ── 通用 Rail（始终注册）──────────────────────────────────────────
    if agent_rule is not None:
        rails.append(IterationLimitRail(agent_rule))
        rails.append(ExecutionLimitRail(agent_rule))

    if scripts_config is not None:
        rails.append(CancelRail(scripts_config=scripts_config))
        rails.append(MCPInterruptRail(sys_operation_id=sys_operation_id, scripts_config=scripts_config))
        rails.append(VersatileInterruptRail(sys_operation_id=sys_operation_id, scripts_config=scripts_config))
        rails.append(AskUserRail(scripts_config=scripts_config))

    rails.append(LogRail(model_name=model_name, tools=tools or []))

    # ── 专属 Rail（按场景 tools 声明配套注册）────────────────────────
    if scenario_tools:
        for tool_name in scenario_tools:
            rail_cls = _SCENARIO_RAIL_MAP.get(tool_name)
            if rail_cls is not None:
                # MultiversatileInterruptRail 需要 sys_operation_id 和 scripts_config
                if rail_cls is MultiversatileInterruptRail:
                    rails.append(rail_cls(sys_operation_id=sys_operation_id, scripts_config=scripts_config))
                else:
                    rails.append(rail_cls())
                logger.info(f"[build_rails] 场景专属 Rail 已注册：{rail_cls.__name__}")

    return rails
