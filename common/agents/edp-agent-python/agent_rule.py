"""
AgentRule.md 加载与 schema 定义。

对齐需求文档 §4.2 的六项规则 + 话术 + 终止关键词。

YAML frontmatter 示例：
---
scope:
  allowed: "基金理财相关业务（余额查询、转账）"
  out_of_scope_message: "尚在学习中"

planning_steps:
  - 需求解析
  - 目标拆解
  - 方案生成
  - 规则校验
  - 结果输出

limits:
  max_iterations: 30
  max_input_attempts: 3
  interrupt_timeout_seconds: 300
  tasks:
        call_versatile: 10
        ask_user: 5

summary:
  format: "需求概述→规划过程→任务执行→结果汇总→异常说明"
  max_length: 500
  required_fields:
    - 理财产品名称
    - 购买金额

scripts:
  tool_start: "正在调用：{tool_name}"
  tool_end: "{tool_name} 执行完成"
  interrupt_start: "需要您确认以下信息"
---

# Markdown body 注入到 LLM 系统提示词
"""
from __future__ import annotations

import re
import warnings
from enum import Enum
from pathlib import Path
from typing import Any

import yaml
from loguru import logger
from pydantic import BaseModel, Field


class ThinkChunkMode(str, Enum):
    """think_chunk 推送模式。"""
    REAL_STREAM = "real_stream"      # 真实 LLM 流式 shard
    FIXED_SCRIPT = "fixed_script"    # 固定话术帧


class QueryPatternScripts(BaseModel):
    """按用户 query 关键词匹配的固定话术组。

    YAML 格式示例：
        - keywords: ["推荐", "理财", "产品"]
          scripts:
            - "正在搜索理财产品..."
            - "正在为您匹配最优产品..."
    """
    keywords: list[str] = Field(
        description="关键词列表，用户 query 包含任一关键词即命中该组"
    )
    scripts: list[str] = Field(
        description="命中时使用的固定话术列表，每条作为一个独立帧按序推送"
    )


class FixedScriptsConfig(BaseModel):
    """固定话术配置（仅 think_chunk_mode=fixed_script 时生效）。

    话术选择优先级（按阶段）：
      planning（第1轮思考）：
        query_patterns 首个命中 > default_scripts > scripts
      executing（第2轮及后续思考）：
        execution_scripts > default_scripts > scripts
      resuming（Cascade 续轮）：
        resume_scripts > execution_scripts > default_scripts > scripts
    """
    enabled: bool = Field(default=True, description="是否启用固定话术")
    chars_per_frame: int = Field(
        default=6, ge=0, le=100,
        description="每帧推送的字符数（话术按此粒度切分为子帧；0=不切分，整句推送）"
    )
    tokens_between_frames: int = Field(
        default=80, ge=1, le=200,
        description="两帧之间需要累积的 LLM token 数"
    )
    min_interval_ms: int = Field(
        default=0, ge=0, le=10000,
        description="两帧之间最少间隔毫秒数（0=不限速）；仅在 think_chunk_mode=fixed_script 时生效"
    )
    scripts: list[str] = Field(
        default_factory=list,
        description="固定话术列表（向后兼容；default_scripts 和 query_patterns 均未配置时使用）"
    )
    default_scripts: list[str] = Field(
        default_factory=list,
        description="默认固定话术列表（优先级高于 scripts；query_patterns 未命中时使用）"
    )
    query_patterns: list[QueryPatternScripts] = Field(
        default_factory=list,
        description="按用户 query 关键词匹配的话术组列表，按声明顺序遍历，首个命中即生效"
    )
    # ── 阶段感知：新增字段 ──────────────────────────────────────────
    enable_resume_scripts: bool = Field(
        default=True,
        description="是否启用 resuming 阶段固定话术；为 False 时 resuming 阶段跳过固定话术，直接展示 LLM 真实输出"
    )
    execution_scripts: list[str] = Field(
        default_factory=list,
        description="执行/反思阶段的固定话术（第2轮及后续思考轮次使用）；为空时降级到 default_scripts"
    )
    resume_scripts: list[str] = Field(
        default_factory=list,
        description="Cascade 续轮的固定话术（query='continue' 时使用）；为空时降级到 execution_scripts → default_scripts"
    )


class ScopeConfig(BaseModel):
    """业务范围配置（规则 1）。"""
    allowed: str = Field(default="", description="允许处理的业务类型描述")
    out_of_scope_message: str = Field(
        default="尚在学习中",
        description="超范围时返回的默认话术"
    )


class LimitsConfig(BaseModel):
    """执行限制配置（规则 4、5 + HITL 限制）。"""
    # 规则 4
    max_iterations: int = Field(default=30, ge=1, le=500)
    # HITL
    max_input_attempts: int = Field(default=3, ge=1, le=20)
    interrupt_timeout_seconds: int = Field(default=300, ge=30, le=3600)
    # 规则 5：按工具名配置上限
    tasks: dict[str, int] = Field(default_factory=dict)


class TodoStepConfig(BaseModel):
    """单条 todolist 业务步骤配置——绑定 step_id 到 content 与 skill。

    YAML 格式（场景文件 scenarios/AgentRule_*.md 或 AgentRule.md frontmatter 下 todolist_steps 列表元素）：

        - step_id: 1
          content: "<业务步骤描述>"
          skill: "<对应的 skill 名>"
    """
    step_id: int = Field(ge=1, description="业务步骤编号；必须唯一")
    content: str = Field(description="渲染到 TodoListItemEvent.content 的业务步骤名")
    skill: str = Field(description="该步骤将要调用的 skill 名（与 SKILL.md frontmatter name 一致）")


class SummaryConfig(BaseModel):
    """执行总结格式配置（规则 6）。"""
    format: str = Field(
        default="需求概述→规划过程→任务执行情况→结果汇总→异常说明"
    )
    max_length: int = Field(default=500, ge=100, le=2000)
    required_fields: list[str] = Field(default_factory=list)


class ScriptsConfig(BaseModel):
    """话术配置（对应需求文档 §6）。可选，未配置时使用默认。
    
    Phase2 解耦优化：
    - 通用话术保留为具名字段（框架级，13 项）
    - 业务话术通过 extra_scripts dict 承载（业务级）
    - 业务字段已迁移至各 SKILL.md 的 scripts: 字段，由 collect_skill_scripts() 收集
    """
    # ── 通用话术（框架级，13 项）──
    tool_start: str = Field(default="正在调用：{tool_name}")
    tool_end: str = Field(default="{tool_name} 执行完成")
    todo_start: str = Field(default="开始执行：{title}")
    todo_end: str = Field(default="{title} 已完成")
    todolist_start: str = Field(default="已生成任务规划")
    todolist_end: str = Field(default="任务规划完成")
    interrupt_start: str = Field(default="需要您确认以下信息")
    request_start: str = Field(default="您的请求已收到。")
    planning_start: str = Field(default="我们正在为您进行规划。")
    task_cancelled: str = Field(default="好的，已为您取消当前操作。如需其他帮助，请随时告诉我。")
    cancel_confirm: str = Field(default="确认要取消当前操作吗？")
    out_of_scope: str = Field(default="正在学习中，暂不支持该业务。")
    mcp_result_empty: str = Field(default="根据您的条件没有找到合适产品，您可以从以下产品中选择或者重新筛选。")

    # ── 扩展字段（业务话术 dict，Phase2 新增）──
    extra_scripts: dict[str, str] = Field(
        default_factory=dict,
        description="业务话术字典，由 collect_skill_scripts() 从各 SKILL.md 的 scripts: 字段收集"
    )

    def get_response_template(self, key: str, default: str = "") -> str:
        """两级查找机制：
        1. 先查 Pydantic 具名字段（通用话术）
        2. 再查 extra_scripts dict（业务话术）
        """
        if hasattr(self, key) and key in self.model_fields:
            return getattr(self, key, default)
        return self.extra_scripts.get(key, default)


class AgentRuleConfig(BaseModel):
    """AgentRule.md 完整配置（六规则 + 话术 + 场景发现）。
    
    Phase1 解耦优化：
    - 新增 scenario_discovery 字段用于场景发现
    - 新增 active_scenario 字段用于运行时加载的场景配置
    - todolist_steps / scope 保留为无场景配置时的回退
    """

    # 规则 1
    scope: ScopeConfig = Field(default_factory=ScopeConfig)
    # 规则 2
    planning_steps: list[str] = Field(default_factory=list)
    # 规则 3（任务依赖，结构暂时简化为 dict）
    task_dependencies: dict[str, list[str]] = Field(
        default_factory=dict,
        description="任务 ID → 前置任务 ID 列表；暂不强制使用"
    )
    # 规则 4、5
    limits: LimitsConfig = Field(default_factory=LimitsConfig)
    # 规则 6
    summary: SummaryConfig = Field(default_factory=SummaryConfig)
    # todolist 业务步骤目录（与 lite_todo_write step_id 枚举绑定）
    # 优先使用 active_scenario.todolist_steps；本字段作为无场景配置时的回退
    todolist_steps: list[TodoStepConfig] = Field(default_factory=list)
    # 话术
    scripts: ScriptsConfig = Field(default_factory=ScriptsConfig)
    # think_chunk 推送模式开关
    think_chunk_mode: ThinkChunkMode = Field(
        default=ThinkChunkMode.REAL_STREAM,
        description="think_chunk 推送模式：real_stream 或 fixed_script"
    )
    # 固定话术帧配置（仅 think_chunk_mode=fixed_script 时生效）
    think_chunk_fixed_scripts: FixedScriptsConfig = Field(
        default_factory=FixedScriptsConfig,
        description="固定话术帧配置"
    )
    # 场景发现配置（Phase1 新增）
    scenario_discovery: "ScenarioDiscoveryConfig" = Field(
        default_factory=lambda: ScenarioDiscoveryConfig(),
        description="场景发现配置"
    )
    # 运行时加载的激活场景（Phase1 新增；不参与序列化）
    active_scenario: "ScenarioConfig | None" = Field(
        default=None,
        exclude=True,
        description="运行时加载的激活场景配置"
    )
    # 原始 frontmatter（保留以供后续扩展）
    raw_frontmatter: dict[str, Any] = Field(default_factory=dict)
    # 注入到 LLM system prompt 的 markdown body
    markdown_body: str = Field(
        default="",
        description="Markdown body，注入到 LLM 系统提示词"
    )


_FRONTMATTER_PATTERN = re.compile(r"^---\s*\n(.*?)\n---\s*\n", re.DOTALL)


def load_agent_rule(rule_path: str | Path) -> AgentRuleConfig:
    """从 AgentRule.md 加载完整规则。

    向后兼容说明（Phase1/2 解耦优化）：
    - scope / todolist_steps 已迁移到 scenarios/*.md，
      但若 AgentRule.md 中仍存在这些字段，会作为回退值加载并打印 deprecation 警告。
    - 新场景应通过 scenario_discovery + scenarios/*.md 配置，
      不再在 AgentRule.md 中硬编码业务数据。
    """
    path = Path(rule_path)
    if not path.exists():
        raise FileNotFoundError(f"AgentRule file not found: {path}")

    content = path.read_text(encoding="utf-8")

    match = _FRONTMATTER_PATTERN.match(content)
    if match:
        yaml_content = match.group(1)
        data = yaml.safe_load(yaml_content) or {}
        markdown_body = content[match.end():].strip()
    else:
        data = {}
        markdown_body = content.strip()

    # ── 向后兼容 + deprecation 警告（仅加载阶段一次性触发）────────────
    inline_todolist_steps = data.get("todolist_steps")
    if inline_todolist_steps:
        warnings.warn(
            "AgentRule.md 中的 todolist_steps 已迁移到 scenarios/*.md，"
            "请移除 AgentRule.md 中的 todolist_steps 字段。"
            "当前值将作为无场景配置时的回退。",
            DeprecationWarning,
            stacklevel=2,
        )

    inline_scope = data.get("scope")
    if inline_scope and isinstance(inline_scope, dict) and inline_scope.get("allowed"):
        raw_scope = ScopeConfig(**inline_scope)
        if raw_scope.allowed and "尚在学习" not in raw_scope.allowed:
            warnings.warn(
                "AgentRule.md 中的 scope.allowed 硬编码值已迁移到 scenarios/*.md，"
                "请改为框架级占位值（如 allowed: ''）。"
                "当前值将作为无场景配置时的回退。",
                DeprecationWarning,
                stacklevel=2,
            )

    # 解析 scenario_discovery 配置（Phase1 新增）
    scenario_discovery_data = data.get("scenario_discovery") or {}
    scenario_discovery = ScenarioDiscoveryConfig(**scenario_discovery_data)
    
    return AgentRuleConfig(
        scope=ScopeConfig(**(data.get("scope") or {})),
        planning_steps=data.get("planning_steps") or [],
        task_dependencies=data.get("task_dependencies") or {},
        limits=LimitsConfig(**(data.get("limits") or {})),
        summary=SummaryConfig(**(data.get("summary") or {})),
        todolist_steps=[TodoStepConfig(**s) for s in (inline_todolist_steps or [])],
        scripts=ScriptsConfig(**(data.get("scripts") or {})),
        think_chunk_mode=data.get("think_chunk_mode", ThinkChunkMode.REAL_STREAM),
        think_chunk_fixed_scripts=FixedScriptsConfig(
            **(data.get("think_chunk_fixed_scripts") or {})
        ),
        scenario_discovery=scenario_discovery,
        active_scenario=None,  # 由 initialize_dpa() 运行时加载
        raw_frontmatter=data,
        markdown_body=markdown_body,
    )


# ============================================================================
# 话术配置加载（从 ScriptsConfig.md）
# ============================================================================

class ScriptsConfigData(BaseModel):
    """话术配置数据（从 ScriptsConfig.md 加载）。
    
    包含：
    - scripts: 通用话术配置（用于工具调用、todolist 等）
    - think_chunk_mode: think_chunk 推送模式
    - think_chunk_fixed_scripts: 固定话术帧配置
    """
    scripts: ScriptsConfig = Field(default_factory=ScriptsConfig)
    think_chunk_mode: ThinkChunkMode = Field(default=ThinkChunkMode.REAL_STREAM)
    think_chunk_fixed_scripts: FixedScriptsConfig = Field(default_factory=FixedScriptsConfig)


def load_scripts_config(scripts_path: str | Path | None = None) -> ScriptsConfigData:
    """从 ScriptsConfig.md 加载话术配置。
    
    Args:
        scripts_path: ScriptsConfig.md 文件路径，若为 None 则使用默认路径
    
    Returns:
        ScriptsConfigData 实例，文件不存在或解析失败时返回默认配置
    """
    # 确定默认路径：与 agent_rule.py 同目录下的 ScriptsConfig.md
    if scripts_path is None:
        scripts_path = Path(__file__).parent / "ScriptsConfig.md"
    else:
        scripts_path = Path(scripts_path)
    
    # 如果文件不存在，返回默认配置
    if not scripts_path.exists():
        return ScriptsConfigData()
    
    try:
        content = scripts_path.read_text(encoding="utf-8")
        
        # 解析 YAML frontmatter
        match = _FRONTMATTER_PATTERN.match(content)
        if match:
            yaml_content = match.group(1)
            data = yaml.safe_load(yaml_content) or {}
        else:
            # 没有 frontmatter，尝试解析整个文件
            # 使用 safe_load_all 并取第一个文档
            docs = list(yaml.safe_load_all(content))
            data = docs[0] if docs else {}
        
        # 解析 think_chunk_mode
        think_chunk_mode_value = data.get("think_chunk_mode", ThinkChunkMode.REAL_STREAM)
        if isinstance(think_chunk_mode_value, str):
            think_chunk_mode = ThinkChunkMode(think_chunk_mode_value)
        else:
            think_chunk_mode = ThinkChunkMode.REAL_STREAM
        
        # 解析 FixedScriptsConfig
        fixed_scripts_data = data.get("think_chunk_fixed_scripts") or {}
        fixed_scripts_config = FixedScriptsConfig(**fixed_scripts_data)
        
        # 解析 ScriptsConfig（通用话术）
        scripts_data = data.get("scripts") or {}
        scripts_config = ScriptsConfig(**scripts_data)
        
        return ScriptsConfigData(
            scripts=scripts_config,
            think_chunk_mode=think_chunk_mode,
            think_chunk_fixed_scripts=fixed_scripts_config,
        )
    except Exception as e:
        # 解析失败时返回默认配置
        return ScriptsConfigData()


# ============================================================================
# 场景配置（Phase1 解耦优化）
# ============================================================================

class ScenarioSkillRouting(BaseModel):
    """场景内 Skill 路由规则。"""
    trigger: str = Field(description="触发条件描述")
    skill: str = Field(description="目标 Skill 名称")
    priority: int = Field(default=1, ge=1, description="优先级，数字越小越优先")


class ScenarioScopeConfig(BaseModel):
    """场景业务范围。"""
    allowed: list[str] = Field(default_factory=list, description="允许的业务范围列表")
    denied: list[str] = Field(default_factory=list, description="禁止的业务范围列表")


class ScenarioArchitectureStep(BaseModel):
    """架构调用步骤。"""
    step_id: int = Field(description="步骤序号")
    description: str = Field(default="", description="步骤描述")
    tool: str = Field(default="", description="使用的工具")
    notes: str = Field(default="", description="注意事项")


class ScenarioArchitectureConfig(BaseModel):
    """场景工具调用架构配置。"""
    type: str = Field(default="", description="架构类型，如 mcp_first / parallel / chain")
    description: str = Field(default="", description="架构说明")
    steps: list[ScenarioArchitectureStep] = Field(default_factory=list, description="调用步骤")
    applicable_skills: list[str] = Field(default_factory=list, description="适用技能列表")
    not_applicable_skills: list[str] = Field(default_factory=list, description="不适用技能列表")


class ScenarioConfig(BaseModel):
    """场景编排配置（Phase1 新增）。"""
    name: str = Field(description="场景名称")
    description: str = Field(default="", description="场景描述")
    scope: ScenarioScopeConfig = Field(default_factory=ScenarioScopeConfig, description="业务范围")
    todolist_steps: list[TodoStepConfig] = Field(default_factory=list, description="todolist 步骤")
    skill_routing: list[ScenarioSkillRouting] = Field(default_factory=list, description="Skill 路由规则")
    architecture: ScenarioArchitectureConfig | None = Field(default=None, description="工具调用架构")
    # 场景级固定话术关键词匹配（用于 think_chunk 固定话术）
    query_patterns: list[QueryPatternScripts] = Field(
        default_factory=list,
        description="场景专属的关键词匹配配置"
    )

    # 并行调用新增：场景声明专属工具
    tools: list[str] = Field(
        default_factory=list,
        description="场景声明的专属工具列表（如 call_multiagent、call_multiversatile），agent.py 按声明注册 Tool + 配套 Rail"
    )

    # 并行调用新增：Agent-card 信息
    agent_name: str = Field(default="EDPAgent", description="Agent 名称（用于 AgentCard）")
    agent_description: str = Field(default="", description="Agent 描述（用于 AgentCard）")

    # scenarios/*.md 中第二个 --- 之后的 Markdown 正文，注入 LLM 系统提示词（与 AgentRule.md 一致）
    markdown_body: str = Field(
        default="",
        description="场景 Markdown 正文，注入到 LLM 系统提示词 §7.5",
    )


class ScenarioDiscoveryConfig(BaseModel):
    """场景发现配置（Phase1 新增）。"""
    base_path: str = Field(default="skills/scenarios", description="场景配置文件目录（相对 EDPAgent 根目录）")
    active_scenario: str = Field(
        default="AgentRule_wealth_purchase",
        description="当前激活的场景名（与 scenarios/{name}.md 文件名一致）"
    )


def find_scenario_file(scenario_base: Path, scenario_name: str) -> Path:
    """查找场景配置文件，优先 .md 格式，然后 .yaml 格式。

    Raises:
        FileNotFoundError: 未找到任何格式的场景文件时抛出
    """
    scenario_path_md = scenario_base / f"{scenario_name}.md"
    if scenario_path_md.exists():
        return scenario_path_md

    scenario_path_yaml = scenario_base / f"{scenario_name}.yaml"
    if scenario_path_yaml.exists():
        return scenario_path_yaml

    raise FileNotFoundError(
        f"Scenario config not found: {scenario_path_md} or {scenario_path_yaml}"
    )


def load_scenario_config(scenario_path: str | Path) -> ScenarioConfig:
    """从 scenarios/xxx.md 或 scenarios/xxx.yaml 加载场景配置。

    - .md 文件：解析 YAML frontmatter + 保留第二个 --- 之后的 Markdown 正文
    - .yaml 文件：纯 YAML 加载（向后兼容，无 markdown_body）
    """
    path = Path(scenario_path)
    if not path.exists():
        raise FileNotFoundError(f"Scenario config not found: {path}")

    content = path.read_text(encoding="utf-8")
    data: dict[str, Any] = {}
    markdown_body = ""

    if path.suffix.lower() == ".md":
        match = _FRONTMATTER_PATTERN.match(content)
        if match:
            yaml_content = match.group(1)
            markdown_body = content[match.end():].strip()
            try:
                data = yaml.safe_load(yaml_content) or {}
            except yaml.YAMLError as e:
                raise ValueError(f"Failed to parse YAML frontmatter in {path}: {e}") from e
        else:
            try:
                data = yaml.safe_load(content) or {}
            except yaml.YAMLError as e:
                raise ValueError(f"Failed to parse {path} as YAML: {e}") from e
    else:
        try:
            data = yaml.safe_load(content) or {}
        except yaml.YAMLError as e:
            raise ValueError(f"Failed to parse {path}: {e}") from e

    data.pop("markdown_body", None)
    return ScenarioConfig(**data, markdown_body=markdown_body)


def collect_skill_scripts(skills_dir: Path) -> dict[str, str]:
    """从 skills/*/SKILL.md 的 frontmatter 中收集所有 scripts 字段，合并为一个 dict。

    - 跳过 scenarios/ 子目录
    - 单文件解析失败不阻断，仅跳过
    """
    merged: dict[str, str] = {}

    if not skills_dir.exists():
        return merged
    
    for skill_dir in sorted(skills_dir.iterdir()):
        if not skill_dir.is_dir():
            continue
        if skill_dir.name == "scenarios":
            continue
        
        skill_md = skill_dir / "SKILL.md"
        if not skill_md.exists():
            continue
        
        try:
            content = skill_md.read_text(encoding="utf-8")
            match = _FRONTMATTER_PATTERN.match(content)
            if not match:
                continue
            
            data = yaml.safe_load(match.group(1)) or {}
            skill_scripts = data.get("scripts") or {}
            if isinstance(skill_scripts, dict):
                merged.update(skill_scripts)
        except Exception as e:
            logger.warning(f"Failed to collect skill scripts from {skill_md}: {e}")
            continue
    
    return merged
