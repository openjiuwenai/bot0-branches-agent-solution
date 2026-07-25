"""
DPA Agent 系统提示词补充。

Skill 正文不直接注入系统提示词；技能通过 agent.register_skill 注册，
由框架引导模型在运行时使用 read_file 按需读取对应的 SKILL.md。
"""
from __future__ import annotations

from typing import TYPE_CHECKING

from .config import load_sub_agents_config

if TYPE_CHECKING:
    from .agent_rule import ScenarioConfig

_BASE_PROMPT = """\
## 六、技能与工具补充

### 6.1 可用工具

- call_mcp：通用脚本调用，通过 script_command 指定脚本路径、script_params 传入业务参数 JSON，由对应的 InterruptRail 拦截并执行
- call_versatile：通用业务工作流调用，通过 workflow_id 指定工作流、params 传入业务参数
- ask_user：在关键信息缺失或敏感操作确认时向用户追问
- execute_cmd：执行 shell 命令，用于 Skill 中调用脚本获取数据
- lite_todo_write：管理待办清单（覆盖式写入），用于多步任务规划与进度展示

### 6.2 工具调用架构

工具调用架构由当前场景配置定义。LLM 应：
1. 启动时从场景配置中读取 `architecture` 字段
2. 按照 `architecture` 中定义的调用模式和步骤执行工具调用
3. 若场景配置未指定 architecture，使用默认的单一工具调用模式
"""


def _build_parallel_tools_section(scenario: ScenarioConfig | None) -> str:
    """根据场景 tools 声明，生成并行调用工具的 §6.1 补充段落。"""
    if scenario is None or not getattr(scenario, "tools", None):
        return ""

    lines: list[str] = []
    scenario_tools = scenario.tools or []
    if "call_multiagent" in scenario_tools:
        config = load_sub_agents_config()
        entity_types = [e.entity_type for e in config.sub_agents]
        type_hint = f"（entity_type 必须为以下之一：{'、'.join(entity_types)}）" if entity_types else ""
        lines.append(
            "- call_multiagent：并行调用多个子 Agent，传入实体列表（entities 数组），"
            f"系统自动并行执行各实体的分析流程{type_hint}"
        )
    if "call_multiversatile" in scenario_tools:
        lines.append("- call_multiversatile：并行调用多个 VersatileAdapter 工作流，传入工作流列表（workflows 数组），系统自动并行执行每个工作流")
    return "\n".join(lines)


def build_system_prompt(scenario: "ScenarioConfig | None" = None) -> str:
    """构建系统提示词。

    Phase3 解耦优化：
    - 无 scenario 时返回原 _BASE_PROMPT（向后兼容）
    - 有 scenario 时拼接业务范围、todolist、Skill 路由段落
    """
    if scenario is None:
        return _BASE_PROMPT

    # 根据场景 tools 声明，在 §6.1 末尾补充并行调用工具
    parallel_section = _build_parallel_tools_section(scenario)
    if parallel_section:
        base = _BASE_PROMPT.replace("\n### 6.2", f"\n{parallel_section}\n\n### 6.2")
    else:
        base = _BASE_PROMPT

    parts: list[str] = [base, "", "## 七、场景规则", f"### 7.1 场景：{scenario.name}"]
    if scenario.description:
        parts.append(scenario.description)

    if scenario.scope.allowed:
        parts.append("")
        parts.append("**允许业务**：" + "、".join(scenario.scope.allowed))
    if scenario.scope.denied:
        parts.append("**禁止业务**：" + "、".join(scenario.scope.denied))

    if scenario.todolist_steps:
        parts.append("")
        parts.append("### 7.2 任务规划")
        for step in scenario.todolist_steps:
            skill_label = step.skill if step.skill else "通用"
            parts.append(f"- step {step.step_id}：{step.content}（→ {skill_label}）")

    if scenario.skill_routing:
        parts.append("")
        parts.append("### 7.3 Skill 路由")
        for r in scenario.skill_routing:
            parts.append(f"- {r.trigger} → {r.skill}（priority={r.priority}）")

    if scenario.architecture and scenario.architecture.type:
        parts.append("")
        parts.append("### 7.4 工具调用架构")
        parts.append(f"类型：{scenario.architecture.type}")
        if scenario.architecture.description:
            parts.append(scenario.architecture.description)

    if scenario.markdown_body:
        parts.append("")
        parts.append("### 7.5 场景详细规则")
        parts.append(scenario.markdown_body)

    return "\n".join(parts)
