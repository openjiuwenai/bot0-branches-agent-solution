"""
EDPAgent 唯一公开入口（对齐需求文档 §4.5 的 17 种事件序列）。

公开接口：
  - initialize_dpa()  — 应用启动时调用一次，配置 Runner 和 ReActAgent
  - agent_stream()    — 每次用户请求时调用，流式返回 AgentEvent

零 A2A 依赖。
"""
from __future__ import annotations

import json
import os
import shlex
from cmath import cos
from pathlib import Path
import asyncio
import tempfile
import zipfile
from dataclasses import asdict, is_dataclass
from typing import Any, AsyncGenerator, Optional
import uuid
from datetime import datetime, timezone
import time

from loguru import logger
from openjiuwen.core.single_agent.interrupt.state import INTERRUPTION_KEY
import httpx

from .agent_rule import (
    AgentRuleConfig,
    FixedScriptsConfig,
    ThinkChunkMode,
    ScriptsConfigData,
    load_agent_rule,
    load_scripts_config,
    # Phase1/2/3 解耦优化：场景与话术解耦相关导入
    ScenarioConfig,
    find_scenario_file,
    load_scenario_config,
    collect_skill_scripts,
)
from .config import get_settings, _env_float, _env_int

from .rail.memory_rail import build_memory_prompt_suffix, regist_memory_rail

# ── 从 fixed_script_feeder 模块导入固定话术相关组件 ───────────────────────
from .fixed_script_feeder import (
    split_scripts_into_frames,
    select_fixed_scripts,
    FixedScriptFeeder,
)
# ── 向后兼容：保留私有别名 ────────────────────────────────────────────────
_split_scripts_into_frames = split_scripts_into_frames
_select_fixed_scripts = select_fixed_scripts
_FixedScriptFeeder = FixedScriptFeeder

from .prompt import build_system_prompt
from .events import (
    AgentEvent,
    ConversationStartEvent, ConversationEndEvent,
    ThinkStartEvent, ThinkChunkEvent, ThinkEndEvent,
    TodoListStartEvent, TodoListItemEvent, TodoListEndEvent,
    TodoStartEvent, TodoStatusEvent, TodoEndEvent,
    ToolStartEvent, ToolStatusEvent, ToolEndEvent,
    InterruptStartEvent, InterruptEndEvent,
    FinalAnswerStartEvent, SummaryEvent, FinalAnswerChunkEvent, FinalAnswerEndEvent,
    DelegateRequest,
    SubAgentDispatchRequest, SubAgentSpec,
    MultiDelegateRequest, WorkflowSpec,
    HeartbeatEvent,
)
from common.logger import (
    Extra,
    Tag,
    bind_context,
    build_http_request_tag_context,
    build_http_trace,
    to_logger,
    get_real_ip, Level, ResultEnum, TagObservation, ObservationType
)


def _log_stream_payload(evt: AgentEvent) -> None:
    """在每次 yield AgentEvent 前打一条日志（对齐抓包的 stream payload 行）。"""
    event_type = getattr(evt, "type", "<unknown>")
    content = getattr(evt, "content", "") or ""
    # 截断过长 content 避免刷屏
    preview = content if len(content) <= 120 else content[:117] + "..."
    logger.info(f"[EDPAgent] stream payload [{event_type}]: {preview}")


def _event_to_dict(evt: AgentEvent | dict[str, Any]) -> dict[str, Any]:
    """Convert local EDPAgent events to the orchestrator dict contract."""
    if isinstance(evt, dict):
        event_type = evt.get("type")
        data = evt.get("data", {})
        if not isinstance(event_type, str) or not event_type:
            raise TypeError("agent_stream event type must be a non-empty string")
        if not isinstance(data, dict):
            raise TypeError("agent_stream event data must be a dict")
        return {"type": event_type, "data": data}

    event_type = getattr(evt, "type", "")
    if not isinstance(event_type, str) or not event_type:
        raise TypeError("agent_stream event type must be a non-empty string")

    if hasattr(evt, "model_dump"):
        data = evt.model_dump(exclude={"type"}, exclude_none=True)
    elif hasattr(evt, "dict"):
        data = evt.dict(exclude={"type"}, exclude_none=True)
    elif is_dataclass(evt):
        data = asdict(evt)
        data.pop("type", None)
        data = {key: value for key, value in data.items() if value is not None}
    else:
        data = {
            key: value
            for key, value in getattr(evt, "__dict__", {}).items()
            if key != "type" and value is not None
        }

    if not isinstance(data, dict):
        raise TypeError("agent_stream event data must be a dict")
    return {"type": event_type, "data": data}


def _extract_user_id(context: Optional[dict], conv_id: str) -> str:
    ctx = context or {}
    body = ctx.get("body") if isinstance(ctx.get("body"), dict) else {}
    headers = ctx.get("headers") if isinstance(ctx.get("headers"), dict) else {}

    for container in (body.get("input") or {}, (body.get("custom_data") or {}).get("inputs") or {}, body):
        if not isinstance(container, dict):
            continue
        for key in (
            "user_id",
            "userId",
            "custUserId",
            "cust_user_id",
            "wap_userName",
            "wapUserName",
        ):
            value = container.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()

    lowered = {str(k).lower(): v for k, v in headers.items()}
    for key in ("x-user-id", "cust-userid", "userid"):
        value = lowered.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()

    return conv_id


# ── LLM sampling 默认覆盖（修复 glm-5 偶发空响应）────────────────────────
# openjiuwen 0.1.11 ModelRequestConfig 默认 temperature=0.95 / top_p=0.1，对
# reasoning 模型（glm-5/DeepSeek-R1/Qwen3-Thinking）会偶发只产 reasoning_content
# 不产 content（finish_reason 异常、content 为空）。这里覆盖到对 reasoning
# 模型友好的默认值；同时支持通过环境变量在不发版的情况下调整，便于运维应对
# LLM 网关偶发超时/502 等瞬时故障（max_retries 默认 3 次自动重试）。
# 排查时：grep "[EDP-LLM-CONFIG]" 验证启动覆盖；grep "[EDP-LLM-EMPTY]" 抓空响应。
_LLM_TEMPERATURE_OVERRIDE = _env_float("LLM_TEMPERATURE", 0.3)
_LLM_TOP_P_OVERRIDE = _env_float("LLM_TOP_P", 0.95)
_LLM_MAX_RETRIES = _env_int("LLM_MAX_RETRIES", 3)

def _apply_sampling_overrides(config: Any) -> None:
    """把硬编码的 sampling 值落到 ReActAgentConfig.model_config_obj 上。

    必须在 configure_model_client(...) 之后、agent.configure(config) 之前调用。
    """
    obj = getattr(config, "model_config_obj", None)
    if obj is None:
        logger.warning(
            "[EDP-LLM-CONFIG] model_config_obj is None; "
            "sampling override SKIPPED (configure_model_client 可能未先调用)"
        )
        return
    obj.temperature = _LLM_TEMPERATURE_OVERRIDE
    obj.top_p = _LLM_TOP_P_OVERRIDE
    logger.info(
        f"[EDP-LLM-CONFIG] applied sampling override: "
        f"temperature={obj.temperature} top_p={obj.top_p}"
    )

    mcc = getattr(config, "model_client_config", None)
    if mcc is None:
        logger.warning(
            "[EDP-LLM-CONFIG] model_config_obj is None; "
            "sampling override SKIPPED (model_client_config 可能未先调用)"
        )
        return    
    mcc.max_retries = _LLM_MAX_RETRIES
    logger.info(
        f"[EDP-LLM-CONFIG] model_client_config override: "
        f"max_retries={mcc.max_retries}"
    )


def _configure_context_engine_and_dialogue_compression(config: Any, settings: Any) -> None:
    """按 .env 开关配置滑动窗口与 DialogueCompressor。"""
    if settings.context_engine_enabled:
        if hasattr(config, "configure_context_engine"):
            config.configure_context_engine(
                max_context_message_num=settings.context_engine_max_context_message_num,
                default_window_round_num=settings.context_engine_default_window_round_num,
                enable_reload=settings.context_engine_enable_reload,
            )
            logger.info(
                "[DPA] ContextEngine enabled: max_context_message_num={}, "
                "default_window_round_num={}, enable_reload={}",
                settings.context_engine_max_context_message_num,
                settings.context_engine_default_window_round_num,
                settings.context_engine_enable_reload,
            )
        else:
            logger.warning("[DPA] SDK 不支持 configure_context_engine，跳过滑动窗口配置")
    else:
        logger.info("[DPA] ContextEngine disabled by env")

    if not settings.dialogue_compression_enabled:
        logger.info("[DPA] DialogueCompressor disabled by env")
        return

    if not hasattr(config, "configure_context_processors"):
        logger.warning("[DPA] SDK 不支持 configure_context_processors，跳过 DialogueCompressor")
        return

    try:
        from openjiuwen.core.foundation.llm.schema.config import (
            ModelClientConfig,
            ModelRequestConfig,
        )
        from openjiuwen.core.context_engine.processor.compressor.dialogue_compressor import (
            DialogueCompressorConfig,
        )
    except Exception as e:
        logger.warning("[DPA] 导入 DialogueCompressor 失败，跳过：{}", e)
        return

    model_cfg = ModelRequestConfig(model=settings.llm_model_name)
    client_cfg = ModelClientConfig(
        client_provider=settings.llm_provider,
        api_key=settings.llm_api_key,
        api_base=settings.llm_api_base,
        verify_ssl=settings.llm_verify_ssl,
        timeout=settings.llm_timeout,
        custom_headers=settings.custom_headers,
    )

    processors = [(
        "DialogueCompressor",
        DialogueCompressorConfig(
            tokens_threshold=settings.dialogue_compression_tokens_threshold,
            compression_target_tokens=settings.dialogue_compression_target_tokens,
            keep_last_round=settings.dialogue_compression_keep_last_round,
            model=model_cfg,
            model_client=client_cfg,
        ),
    )]
    config.configure_context_processors(processors)
    logger.info(
        "[DPA] DialogueCompressor enabled: tokens_threshold={}, "
        "compression_target_tokens={}, keep_last_round={}",
        settings.dialogue_compression_tokens_threshold,
        settings.dialogue_compression_target_tokens,
        settings.dialogue_compression_keep_last_round,
    )

# ── 模块级单例 ──────────────────────────────────────────────────────────
_agent = None
_agent_rule: Optional[AgentRuleConfig] = None
_scripts_config: Optional[ScriptsConfigData] = None

_AGENT_RULE_PATH = Path(__file__).parent / "AgentRule.md"


def _zip_skills_dir_to_file(skills_root: Path, out_zip: Path) -> None:
    if not skills_root.exists():
        raise FileNotFoundError(f"skills 目录不存在：{skills_root}")
    if not skills_root.is_dir():
        raise NotADirectoryError(f"skills 不是目录：{skills_root}")

    out_zip.parent.mkdir(parents=True, exist_ok=True)
    if out_zip.exists():
        out_zip.unlink()

    # 让解压后得到 <target>/skills/... 结构
    # Phase3 解耦：sandbox 不需要场景文件，打包时过滤 skills/scenarios/ 子目录
    with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for p in skills_root.rglob("*"):
            if p.is_dir():
                continue
            rel = p.relative_to(skills_root)
            # 过滤场景配置目录
            if rel.parts and rel.parts[0] == "scenarios":
                continue
            zf.write(p, arcname=str(Path("skills") / rel))


# ════════════════════════════════════════════════════════════════════
# Phase3 解耦优化：场景按需注册辅助函数
# ════════════════════════════════════════════════════════════════════

def _get_required_skills_for_scenario(scenario: ScenarioConfig | None) -> set[str]:
    """从场景配置中提取所需 Skill 名称集合。

    来源：
    - todolist_steps[].skill
    - skill_routing[].skill
    - architecture.applicable_skills / not_applicable_skills

    Returns:
        所需 Skill 名称集合；scenario 为 None 时返回空集合（外层据此回退到全量注册）。
    """
    if scenario is None:
        return set()

    required: set[str] = set()
    for step in scenario.todolist_steps:
        if step.skill:
            required.add(step.skill)
    for routing in scenario.skill_routing:
        if routing.skill:
            required.add(routing.skill)
    if scenario.architecture is not None:
        for s in scenario.architecture.applicable_skills:
            required.add(s)
        for s in scenario.architecture.not_applicable_skills:
            required.add(s)
    return required


def _validate_scenario_skills(skills_root: Path, required_skills: set[str]) -> None:
    """启动期校验：场景声明的 skill 必须在磁盘上存在 SKILL.md（P3 失败快速暴露）。

    Raises:
        RuntimeError: 任意 skill 缺失时抛出，阻断启动。
    """
    if not required_skills:
        return
    missing: list[str] = []
    for name in sorted(required_skills):
        skill_md = skills_root / name / "SKILL.md"
        if not skill_md.exists():
            missing.append(name)
    if missing:
        raise RuntimeError(
            f"[DPA] 场景声明的 Skill 在 skills/ 目录下缺失：{', '.join(missing)}。"
            f"请补齐对应 skill 子目录与 SKILL.md，或修改 scenarios/*.md 移除引用。"
        )


async def _register_all_skills(agent: Any, skills_root: Path) -> int:
    """全量注册：遍历 skills_root 下所有含 SKILL.md 的子目录（向后兼容回退路径）。"""
    registered = 0
    skipped = 0
    for skill_dir in sorted(skills_root.iterdir()):
        if not skill_dir.is_dir():
            continue
        if skill_dir.name == "scenarios":
            continue
        if not (skill_dir / "SKILL.md").exists():
            skipped += 1
            continue
        await agent.register_skill(str(skill_dir))
        registered += 1
    logger.info(
        f"[DPA] 全量注册完成：注册 {registered} 个 Skill，跳过 {skipped} 个非 Skill 目录"
    )
    return registered


async def _register_scenario_skills(
    agent: Any,
    skills_root: Path,
    required_skills: set[str],
    *,
    register_path_prefix: str | None = None,
) -> int:
    """按场景配置注册 Skill（Phase3 按需注册 + Phase4 sandbox 远端路径支持）。

    Args:
        skills_root: 本地 skills/ 目录（用于遍历子目录与 SKILL.md 校验）
        required_skills: 需要注册的 skill 名集合
        register_path_prefix: 远端 skills 根路径（如 sandbox 解压目标 "/tmp/skills"）；
                              非 None 时，传给 register_skill 的路径为
                              f"{register_path_prefix}/{skill_name}"，否则用本地 skill_dir。

    用法对比：
        local：   _register_scenario_skills(agent, local_skills, required)
        sandbox： _register_scenario_skills(agent, local_skills, required,
                                            register_path_prefix=remote_skills_root)
    """
    registered: list[str] = []
    skipped: list[str] = []
    for skill_dir in sorted(skills_root.iterdir()):
        if not skill_dir.is_dir():
            continue
        if skill_dir.name == "scenarios":
            continue
        if not (skill_dir / "SKILL.md").exists():
            continue
        skill_name = skill_dir.name
        if skill_name in required_skills:
            # 远端路径优先；否则用本地路径
            register_path = (
                f"{register_path_prefix.rstrip('/')}/{skill_name}"
                if register_path_prefix
                else str(skill_dir)
            )
            await agent.register_skill(register_path)
            registered.append(skill_name)
        else:
            skipped.append(skill_name)
    logger.info(
        f"[DPA] 场景按需注册完成（{'sandbox' if register_path_prefix else 'local'}）："
        f"注册 {len(registered)} 个 Skill ({', '.join(registered)})，"
        f"跳过 {len(skipped)} 个 Skill ({', '.join(skipped)})"
    )
    return len(registered)


# ════════════════════════════════════════════════════════════════════
# Phase4 sandbox 按需注册：占位识别（与 v2.1.1 D3 警告设计协同）
# ════════════════════════════════════════════════════════════════════
_PLACEHOLDER_SKILL_NAME = "_placeholder_"


def _is_placeholder_only(required_skills: set[str]) -> bool:
    """判断 required_skills 是否仅由占位 skill 构成。

    用于 sandbox 模式下的占位场景识别（与 v2.1.1 AgentRule.md frontmatter
    的 _placeholder_ 兜底设计协同），避免 sandbox 按需注册实施后启动行为反转。

    Returns:
        True：required_skills 非空且全部为占位 skill 名（应跳过 P3 校验，走 fallback）
        False：含至少 1 个真实 skill 名 或 集合为空（前者走 on_demand 主路径；后者走 fallback）
    """
    if not required_skills:
        return False
    return all(name == _PLACEHOLDER_SKILL_NAME for name in required_skills)


def _count_local_skills(skills_root: Path) -> int:
    """统计本地 skills 目录下的真实 skill 数量（含 SKILL.md 的子目录，排除 scenarios）。

    仅用于整目录注册后的日志提示，不参与注册逻辑。
    """
    count = 0
    for entry in skills_root.iterdir():
        if not entry.is_dir() or entry.name == "scenarios":
            continue
        if (entry / "SKILL.md").exists():
            count += 1
    return count


# ════════════════════════════════════════════════════════════════════
# 公开接口
# ════════════════════════════════════════════════════════════════════


def _register_otel_tracer(settings) -> None:
    """注册 OTel Tracer 扩展 Handler 和 OtelRail。

    功能：初始化 OTel TracerProvider，注册 Agent/Workflow 两级 Handler，
    使 SDK 的 tracer 事件能自动转为 OTel span 上报。

    调用时机：Runner.start() 之后、ReActAgent 创建之前。
    容错：未安装 openjiuwen[observability] 时跳过，不影响主流程。
    """
    # 总开关关闭时直接返回，不执行任何 OTel 逻辑
    if not settings.otel_enabled:
        return

    # 延迟 import：仅在 OTel 开启时才加载扩展包，未安装时走 except 跳过
    try:
        from openjiuwen.extensions.tracer_otel import (
            OtelTracerConfig,
            OtelAgentHandler,
            OtelWorkflowHandler,
            init_otel_tracer,
        )
        from openjiuwen.core.session.tracer import TracerHandlerRegistry
    except ImportError:
        logger.warning("[DPA] openjiuwen[observability] 未安装，跳过 OTel 注册")
        return

    # 幂等保护：防止重复注册导致 ValueError
    agent_handlers = TracerHandlerRegistry.get_agent_handlers()
    if "otel_agent" in agent_handlers:
        logger.info("[DPA] OTel handler 已注册，跳过")
        return

    # 构建 OTel 配置
    config = OtelTracerConfig(
        exporter_type=settings.otel_exporter_type,
        exporter_endpoint=settings.otel_exporter_endpoint or None,
        protocol=settings.otel_protocol,
        service_name=settings.otel_service_name,
        sample_rate=settings.otel_sample_rate,
        redaction_enabled=settings.otel_redaction_enabled,
        max_attr_length=settings.otel_max_attr_length,
    )
    tracer = init_otel_tracer(config)

    # 注入 tracer 引用到 otel_span_helper
    from .otel_span_helper import _set_tracer
    _set_tracer(tracer)

    # 注册到全局 HandlerRegistry
    TracerHandlerRegistry.register_handler("otel_agent", OtelAgentHandler(tracer, config))
    TracerHandlerRegistry.register_handler("otel_workflow", OtelWorkflowHandler(tracer, config))
    logger.info(
        f"[DPA] OTel handler 已注册：endpoint={settings.otel_exporter_endpoint}, "
        f"service={settings.otel_service_name}, sample_rate={settings.otel_sample_rate}"
    )


async def initialize_dpa() -> None:
    """应用启动时调用一次。"""
    global _agent, _agent_rule, _scripts_config
    if _agent is not None:
        logger.debug("[DPA] 已初始化，跳过重复初始化")
        return

    settings = get_settings()

    import openjiuwen.extensions.checkpointer.redis.checkpointer  # noqa: F401

    from openjiuwen.core.runner import Runner
    from openjiuwen.core.runner.runner_config import DEFAULT_RUNNER_CONFIG
    from openjiuwen.core.session.checkpointer.checkpointer import CheckpointerConfig
    from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig
    from openjiuwen.core.sys_operation import (
        LocalWorkConfig,
        OperationMode,
        SandboxGatewayConfig,
        SysOperationCard,
    )
    from openjiuwen.core.sys_operation.config import (
        ContainerScope,
        PreDeployLauncherConfig,
        SandboxIsolationConfig,
    )

    from .rail import (
        build_rails,
        VersatileInterruptRail,
        IterationLimitRail,
        ExecutionLimitRail,
        MCPInterruptRail,
        AskUserRail,
        CancelRail,
        LogRail,
    )

    # ── 加载 AgentRule.md ────────────────────────────────────────────
    try:
        _agent_rule = load_agent_rule(_AGENT_RULE_PATH)
        system_prompt = _agent_rule.markdown_body
        logger.info(
            f"[DPA] AgentRule 加载成功：body={len(system_prompt)} 字符, "
            f"scope='{_agent_rule.scope.allowed[:30]}...', "
            f"max_iter={_agent_rule.limits.max_iterations}, "
            f"task_limits={_agent_rule.limits.tasks}"
        )
    except FileNotFoundError:
        _agent_rule = AgentRuleConfig()
        system_prompt = "你是一个智能体助手。"
        logger.warning("[DPA] AgentRule.md 未找到，使用默认配置")

    # ── Phase1 解耦：加载场景配置（改造点 A）────────────────────────
    scenario_name = (
        os.environ.get("ACTIVE_SCENARIO")
        or _agent_rule.scenario_discovery.active_scenario
    )
    scenario_base = Path(__file__).resolve().parent / _agent_rule.scenario_discovery.base_path
    try:
        scenario_path = find_scenario_file(scenario_base, scenario_name)
        _agent_rule.active_scenario = load_scenario_config(scenario_path)
        logger.info(
            f"[DPA] 场景配置加载成功：{scenario_name}（{scenario_path.name}），"
            f"todolist={len(_agent_rule.active_scenario.todolist_steps)} 项, "
            f"routing={len(_agent_rule.active_scenario.skill_routing)} 项, "
            f"body={len(_agent_rule.active_scenario.markdown_body)} 字符"
        )
    except FileNotFoundError:
        _agent_rule.active_scenario = None
        logger.warning(
            f"[DPA] 场景配置 {scenario_name} 未找到（base={scenario_base}），"
            f"回退使用 AgentRule 内联配置"
        )
    except Exception as e:
        _agent_rule.active_scenario = None
        logger.warning(f"[DPA] 场景配置加载失败：{e}，回退使用 AgentRule 内联配置")

    # ── Phase3 解耦：构建 system_prompt（改造点 B；改造点 C 删除原第 324 行重复拼接）──
    system_prompt = (
        f"{system_prompt.strip()}\n\n"
        f"{build_system_prompt(scenario=_agent_rule.active_scenario).strip()}"
        f"{build_memory_prompt_suffix(settings.memory_enabled)}"
    )

    # ── 加载 ScriptsConfig.md（话术配置）───────────────────────────────
    try:
        _scripts_config = load_scripts_config()
        logger.info(
            f"[DPA] ScriptsConfig 加载成功："
            f"think_chunk_mode={_scripts_config.think_chunk_mode.value}, "
            f"fixed_scripts_enabled={_scripts_config.think_chunk_fixed_scripts.enabled}"
        )
    except Exception as e:
        _scripts_config = ScriptsConfigData()
        logger.warning(f"[DPA] ScriptsConfig.md 加载失败: {e}，使用默认配置")

    # ── Phase2 解耦：收集 SKILL.md 的业务话术到 extra_scripts（改造点 D）────────
    _skills_root_path = Path(__file__).resolve().parent / "skills"
    skill_scripts = collect_skill_scripts(_skills_root_path)
    if skill_scripts:
        _scripts_config.scripts.extra_scripts.update(skill_scripts)
        logger.info(
            f"[DPA] 业务话术收集完成：{len(skill_scripts)} 项 "
            f"keys={list(skill_scripts.keys())[:5]}{'...' if len(skill_scripts) > 5 else ''}"
        )
    else:
        logger.info("[DPA] 业务话术收集结果为空（skills/ 下无 SKILL.md scripts: 字段）")

    # ── 配置 lite_todo todolist_steps（必须在 build_tools() 之前；改造点 E）─────
    # 优先使用 active_scenario.todolist_steps；回退到 AgentRule 内联
    from .tool.lite_todo.models import configure_steps
    steps_source = (
        _agent_rule.active_scenario.todolist_steps
        if _agent_rule.active_scenario and _agent_rule.active_scenario.todolist_steps
        else _agent_rule.todolist_steps
    )
    if not steps_source:
        raise RuntimeError(
            "[DPA] todolist_steps 不可为空：场景配置与 AgentRule 内联均未提供有效步骤"
        )
    configure_steps(steps_source)
    _steps_source_label = (
        "scenario"
        if _agent_rule.active_scenario and _agent_rule.active_scenario.todolist_steps
        else "agent_rule"
    )
    logger.info(
        f"[DPA] todolist_steps 已配置：来源={_steps_source_label}, "
        f"共 {len(steps_source)} 项 step_ids={[s.step_id for s in steps_source]}"
    )
    # D3：当走 AgentRule 内联回退且命中占位 skill，发出显著警告，提示运维场景未生效
    if _steps_source_label == "agent_rule" and any(
        s.skill == "_placeholder_" for s in steps_source
    ):
        logger.warning(
            "[DPA] 当前 todolist_steps 来自 AgentRule 内联占位（_placeholder_）。"
            "这说明场景配置未加载或加载失败；任意业务工具调用都将因 skill 不存在而失败。"
            "请检查 ACTIVE_SCENARIO 环境变量与 skills/scenarios/*.md 文件是否就位。"
        )

    # 现在 lite_todo schema 已就绪，可安全构建 TOOLS
    from .tool import build_tools
    # 并行调用改进：按场景 tools 声明选择性注册专属工具
    scenario_tools = (
        _agent_rule.active_scenario.tools
        if _agent_rule.active_scenario and _agent_rule.active_scenario.tools
        else None
    )
    TOOLS = build_tools(scenario_tools=scenario_tools)

    # ── 配置 Redis Checkpointer ──────────────────────────────────────
    runner_config = DEFAULT_RUNNER_CONFIG.model_copy(deep=True)
    runner_config.checkpointer_config = CheckpointerConfig(
        type="redis",
        conf={
            "connection": {
                "url": settings.redis_url,
                "connection_args": {
                    "protocol": 2,
                    "socket_connect_timeout": 5,
                    "socket_timeout": 10,
                    "retry_on_timeout": True,
                },
            },
            "ttl": {
                "default_ttl": settings.redis_checkpointer_ttl_minutes,
                "refresh_on_read": True,
            },
        },
    )
    Runner.set_config(runner_config)
    await Runner.start()
    _register_otel_tracer(settings)  # 注册 OTel Handler，在 Runner 启动后、Agent 创建前
    logger.info("[DPA] Runner 已启动，Checkpointer=redis")

    # ── 注册 SysOperationCard（local / sandbox）────────────────────────────
    # 判定规则：只要配置了 SANDBOX_URL 就走沙箱；未配置则默认 local
    sandbox_url = (settings.sandbox_url or "").strip()
    run_mode = "sandbox" if sandbox_url else "local"

    local_zip_for_upload: str | None = None
    if run_mode == "sandbox":
        # 沙箱模式固定从本地 skills/ 打包一个 zip 上传（不再支持 SKILL_PACKAGE_PATH）
        skills_root = Path(__file__).resolve().parent / "skills"
        tmpdir = Path(tempfile.mkdtemp(prefix="edpagent-skills-"))
        auto_zip = tmpdir / "skills.zip"
        _zip_skills_dir_to_file(skills_root, auto_zip)
        local_zip_for_upload = str(auto_zip)
        logger.info(f"[DPA] 已自动打包 skills.zip：{local_zip_for_upload}")
        target = (settings.skill_target_path or "/tmp").strip() or "/tmp"

        # 1) 创建沙箱
        async with httpx.AsyncClient(base_url=sandbox_url, timeout=30.0) as client:
            create_resp = await client.post("/api/v1/sandboxes", json={})
            create_resp.raise_for_status()
            sandbox_id = create_resp.json()["id"]

        sys_op_id = f"jiuwenbox_fs_op_{uuid.uuid4().hex[:8]}"
        sysop_card = SysOperationCard(
            id=sys_op_id,
            mode=OperationMode.SANDBOX,
            gateway_config=SandboxGatewayConfig(
                isolation=SandboxIsolationConfig(
                    container_scope=ContainerScope.CUSTOM,
                    custom_id=sandbox_id,
                ),
                launcher_config=PreDeployLauncherConfig(
                    base_url=sandbox_url,
                    sandbox_type="jiuwenbox",
                    extra_params={"sandbox_id": sandbox_id},
                ),
                timeout_seconds=30,
            ),
        )
        Runner.resource_mgr.add_sys_operation(sysop_card)
        logger.info(
            f"[DPA] SysOperationCard 已注册：id={sysop_card.id}, sandbox_id={sandbox_id}"
        )
    else:
        sysop_card = SysOperationCard(
            mode=OperationMode.LOCAL,
            work_config=LocalWorkConfig(work_dir=None),
        )
        Runner.resource_mgr.add_sys_operation(sysop_card)
        logger.info(f"[DPA] SysOperationCard 已注册：id={sysop_card.id}, mode=local")

    # ── 创建 ReActAgent ──────────────────────────────────────────────
    card = AgentCard(id=settings.dpa_agent_id, name=settings.dpa_agent_name)
    agent = ReActAgent(card=card)
    config = ReActAgentConfig()

    # 自定义 header
    if settings.custom_headers:
        if hasattr(config, "configure_custom_headers"):
            config.configure_custom_headers(settings.custom_headers)
            logger.info(f"[DPA] 自定义请求头已配置：{list(settings.custom_headers.keys())}")
        else:
            logger.warning("[DPA] SDK 不支持 configure_custom_headers，header 未生效")

    config = (
        config.configure_model_client(
            provider=settings.llm_provider,
            api_key=settings.llm_api_key,
            api_base=settings.llm_api_base,
            model_name=settings.llm_model_name,
            verify_ssl=settings.llm_verify_ssl,
        )
        .configure_prompt_template([{"role": "system", "content": system_prompt}])
        .configure_max_iterations(_agent_rule.limits.max_iterations)
    )
    _configure_context_engine_and_dialogue_compression(config, settings)
    config.sys_operation_id = sysop_card.id

    # 覆盖 openjiuwen 0.1.11 不健康的 sampling 默认值（详见 _apply_sampling_overrides）
    _apply_sampling_overrides(config)

    agent.configure(config)

    if hasattr(config, "model_client_config") and config.model_client_config is not None:
        config.model_client_config.timeout = settings.llm_timeout

    # ── 开始注册能力组件（记录耗时）
    capability_start_time = datetime.now(timezone.utc).astimezone()
    capability_start_time_ms = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    registered_tools = []
    registered_sys_tools = []
    # ── Memory（可选，默认关闭；初始化失败不阻断主流程）────────────────────
    memory_rail_registered = await regist_memory_rail(
        agent=agent,
        config=config,
        settings=settings,
        system_prompt=system_prompt,
    )

    # ── 注册 read_file（Skill 按需读取 SKILL.md）──────────────────────
    read_file_card = Runner.resource_mgr.get_sys_op_tool_cards(
        sys_operation_id=sysop_card.id,
        operation_name="fs",
        tool_name="read_file",
    )
    if read_file_card is not None:
        agent.ability_manager.add(read_file_card)
        registered_sys_tools.append("read_file")
        logger.info("[DPA] read_file 已加入 Agent 能力集")
    else:
        logger.warning("[DPA] 未获取到 read_file 能力卡，Skill 将无法按需读取 SKILL.md")

    # ── 注册 shell_tool（Skill 按需执行脚本命令）──────────────────────
    shell_tool_card = Runner.resource_mgr.get_sys_op_tool_cards(
        sys_operation_id=sysop_card.id,
        operation_name="shell",
        tool_name="execute_cmd",
    )
    if shell_tool_card is not None:
        agent.ability_manager.add(shell_tool_card)
        registered_sys_tools.append("execute_cmd")
        logger.info("[DPA] shell_tool 已加入 Agent 能力集")
    else:
        logger.warning("[DPA] 未获取到 shell_tool 能力卡，Skill 将无法按需执行 shell 命令")

    # ── 注册工具 ─────────────────────────────────────────────────────
    for tool in TOOLS:
        Runner.resource_mgr.add_tool(tool)
        agent.ability_manager.add(tool.card)
        registered_tools.append(tool.card.name if hasattr(tool.card, 'name') else str(tool.card))

    # 记录工具初始化耗时
    capability_end_time = datetime.now(timezone.utc).astimezone()
    total_capability_duration_ms = int((capability_end_time - capability_start_time).total_seconds() * 1000)
    capability_end_time_ms = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    to_logger(
        level=Level.INFO, message=TagObservation(
            id=str(int(time.time() * 1000)),
            type=ObservationType.SPAN,
            name="agent初始化",
            start_time=capability_start_time_ms,
            end_time=capability_end_time_ms,
            input={"tool_list": registered_sys_tools + registered_tools},
        ),
        extra=Extra(tag=Tag.TAG_AGENT_INIT_TOOLLIST, cost=total_capability_duration_ms)
    )

    # ── 注册 Rails（顺序决定优先级影响）──────────────────────────────
    # 并行调用改进：使用 build_rails() 按场景 tools 声明配套注册专属 Rail
    scripts_config_to_use = _scripts_config.scripts if _scripts_config else _agent_rule.scripts
    rails = build_rails(
        scenario_tools=scenario_tools,
        agent_rule=_agent_rule,
        scripts_config=scripts_config_to_use,
        sys_operation_id=sysop_card.id,
        model_name=settings.llm_model_name,
        tools=TOOLS,
        memory_rail_registered=memory_rail_registered,
    )
    for rail in rails:
        await agent.register_rail(rail)

    # ── 注册 OtelRail：自动管理 chain.EDPAgent / llm.Model span 的生命周期 ──
    if settings.otel_enabled:
        try:
            from openjiuwen.extensions.tracer_otel.otel_rail import OtelRail
            await agent.register_rail(OtelRail())  # priority=0，最低优先级
            logger.info("[DPA] OtelRail 已注册")
        except ImportError:
            logger.warning("[DPA] OtelRail 注册失败：openjiuwen[observability] 未安装")

    # ── 注册 Skill（改造点 F/G：local 按需注册；sandbox 按需注册 v2.2） ─────
    skill_count = 0
    if run_mode == "sandbox":
        import openjiuwen.extensions.sys_operation.sandbox.providers
        target = (settings.skill_target_path or "/tmp").strip() or "/tmp"
        remote_zip = f"{target.rstrip('/')}/skills.zip"
        remote_skills_root = f"{target.rstrip('/')}/skills"
        local_skills_root = Path(__file__).resolve().parent / "skills"  # 本地校验与遍历

        sys_op = Runner.resource_mgr.get_sys_operation(sysop_card.id)
        if sys_op is None:
            raise RuntimeError(f"[DPA] 未找到 sys_operation: {sysop_card.id}")

        if not local_zip_for_upload:
            raise RuntimeError("[DPA] sandbox 模式未生成本地 skills.zip")
        upload_res = await sys_op.fs().upload_file(local_zip_for_upload, remote_zip)
        logger.info(f"[DPA] sandbox 上传 skill 包完成：{upload_res}")

        unzip_res = await sys_op.shell().execute_cmd(
            f"unzip -o {shlex.quote(remote_zip)} -d {shlex.quote(target)}"
        )
        logger.info(f"[DPA] sandbox 解压 skill 包完成：{unzip_res}")

        # ── v2.2 sandbox 按需注册（与 local 行为对齐）──────────────────
        # 打包仍是整目录（不动 _zip_skills_dir_to_file）；注册按场景声明的 skill 集合
        required = _get_required_skills_for_scenario(_agent_rule.active_scenario)

        if not required:
            # 路径 1 fallback：场景未加载或场景未声明 required_skills → 整目录注册
            await agent.register_skill(remote_skills_root)
            skill_count = _count_local_skills(local_skills_root)
            logger.warning(
                f"[DPA] sandbox 未找到场景配置或场景未声明所需 Skill，"
                f"回退到整目录注册：{remote_skills_root}（共 {skill_count} 个 skill）"
            )
        elif _is_placeholder_only(required):
            # 路径 2 placeholder_fallback：占位识别——走整目录注册 + D3 警告（与 v2.1.1 D3 设计协同）
            # 防御性分支：处理"场景配置加载成功但其 skill 引用全部是 _placeholder_"的边界情形。
            # 在 v2.1.1 默认配置下几乎不会被触发（占位 skill 名仅出现在 AgentRule.md 内联兜底，
            # 而非 scenarios/*.md；后者会走 active_scenario=None → 路径 1 fallback）。
            # 本分支保留是为了：
            #   1) 防御场景生成工具误产出 _placeholder_ skill 名
            #   2) 让 _placeholder_ 字符串在日志中保持"运维警觉"信号（v2.1.1 D3 设计）
            await agent.register_skill(remote_skills_root)
            skill_count = _count_local_skills(local_skills_root)
            logger.warning(
                "[DPA] sandbox 当前 required_skills 仅含占位（_placeholder_），"
                "说明场景配置未加载或加载失败；按 v2.1.1 D3 设计走整目录注册；"
                "任意业务工具调用将因 skill 不存在而失败。"
                "请检查 ACTIVE_SCENARIO 环境变量与 skills/scenarios/*.md 文件是否就位。"
            )
        else:
            # 路径 3 on_demand 主路径：本地 P3 校验 + 远端按需注册（与 local 行为对齐）
            _validate_scenario_skills(local_skills_root, required)  # 缺失抛 RuntimeError
            skill_count = await _register_scenario_skills(
                agent,
                local_skills_root,
                required,
                register_path_prefix=remote_skills_root,
            )
            logger.info(
                f"[DPA] sandbox 已按需注册：path={remote_skills_root}, "
                f"required={sorted(required)}, skill_count={skill_count}"
            )
    else:
        skills_root = Path(__file__).resolve().parent / "skills"
        if skills_root.exists():
            required_skills = _get_required_skills_for_scenario(_agent_rule.active_scenario)
            if required_skills:
                # 启动期校验：缺失即抛错（P3）
                _validate_scenario_skills(skills_root, required_skills)
                skill_count = await _register_scenario_skills(
                    agent, skills_root, required_skills
                )
            else:
                skill_count = await _register_all_skills(agent, skills_root)
                logger.warning(
                    "[DPA] 未找到场景配置或场景未声明所需 Skill，回退到全量注册"
                )
        else:
            logger.warning(f"[DPA] 技能目录不存在：{skills_root}")

    _agent = agent
    rail_count = 7 + (1 if memory_rail_registered else 0)
    logger.info(
        f"[DPA] 初始化完成：agent_id={settings.dpa_agent_id}，"
        f"已注册 {rail_count} 个 Rail，skills={skill_count}, memory_enabled={settings.memory_enabled}"
    )


def _emit_heartbeat(
    conv_id: str,
    heartbeat_type: str,
    status: str,
    is_sub_agent: bool = False,
) -> HeartbeatEvent | None:
    # 子 Agent 不发心跳
    if is_sub_agent:
        return None
    return HeartbeatEvent(
        content="",
        data={
            "contract_version": "HB-CONTRACT-1.0",
            "request_id": conv_id,
            "heartbeat_type": heartbeat_type,
            "status": status,
            "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "source": "edp_agent",
        },
    )


async def _agent_event_stream(
    query: str,
    conv_id: str,
    cascade_result: Optional[dict] = None,
    context: Optional[dict] = None,
    *,
    think_chunk_mode: ThinkChunkMode | None = None,
) -> AsyncGenerator[AgentEvent, None]:
    """
    EDPAgent 唯一请求入口。yield 17 种细粒度事件。

    事件顺序（典型）：
      conversation_start
        → think_start → think_chunk* → [todolist_*] → [todo_status*] → think_end
        → tool_start → tool_end
        → think_start → think_chunk* → think_end
        → ...
        → final_answer_start → final_answer_chunk* → final_answer_end
      conversation_end

    Args:
        query: 用户查询内容
        conv_id: 会话ID
        cascade_result: Cascade 续轮结果（续轮时传入）
        context: 上下文信息
        think_chunk_mode: think_chunk 推送模式（FIXED_SCRIPT/REAL_STREAM）。
                        未传入时使用 ScriptsConfig.md 中的默认值。
    """
    agent = _get_agent()
    settings = get_settings()
    original_body = (context or {}).get("body", {})
    user_id = _extract_user_id(context, conv_id) if settings.memory_enabled else conv_id
    is_sub_agent = (context or {}).get("is_sub_agent", False)

    from openjiuwen.core.session.agent import create_agent_session
    from openjiuwen.core.session.checkpointer.checkpointer import CheckpointerFactory

    session = create_agent_session(session_id=conv_id, card=agent.card)

    # ── 提前 pre_run：从 Redis 恢复 session state，以便检测取消标记 ──────
    # 必须在判断 is_resume / 检查 checkpoint_to_release 之前执行 pre_run，
    # 否则 session.get_state("checkpoint_to_release") 拿到的是空值（内存
    # session 还未从 Redis 恢复）。
    pre_run_inputs = (
        {"query": "continue", "conversation_id": conv_id, "user_id": user_id}
        if cascade_result is not None
        else {"query": query, "conversation_id": conv_id, "user_id": user_id}
    )
    await session.pre_run(inputs=pre_run_inputs)

    # ── 取消检测：上一轮 CancelRail 标记了 checkpoint_to_release ─────────
    # 此时 post_run() 一定已完成（上一轮请求已经完全结束），不存在竞态。
    checkpoint_to_release = session.get_state("checkpoint_to_release")
    if checkpoint_to_release:
        await _reset_session_after_cancel(agent, session, conv_id, pre_run_inputs)

    # 仅在"外部用户请求首轮"发会话开始事件；cascade 续轮不发
    is_external_turn = cascade_result is None
    try:
        checkpoint_exists = await CheckpointerFactory.get_checkpointer().session_exists(conv_id)
    except Exception:
        logger.exception("[DPA] checkpoint_exists 查询异常，默认为 True")
        checkpoint_exists = True
    is_resume = cascade_result is not None or checkpoint_exists
    if is_external_turn:
        yield ConversationStartEvent(content="本轮对话开始")

    # ── 首轮 / 续轮路径 ───────────────────────────────────────────────
    if cascade_result is not None:
        logger.info(f"[DPA] Cascade 续轮：conv_id={conv_id}")
        hb = _emit_heartbeat(conv_id, "end", "completed", is_sub_agent=is_sub_agent)
        if hb is not None:
            yield hb
        yield InterruptEndEvent(content="本次输入全部结束")
        session.update_state({
            "cascade_result": cascade_result,
            "original_body": original_body,
            "pending_delegate": None,
            "pending_dispatch": None,
            "pending_multi_delegate": None,
            "response_template": None,
            "ui_notices": None,
        })
        stream_inputs = {"query": "continue", "conversation_id": conv_id, "user_id": user_id}
    else:
        if not is_resume:
            logger.info(f"[DPA] 确认是首轮请求：conv_id={conv_id}, is_resume={is_resume}")
            # 使用 _scripts_config.scripts 替代 _agent_rule.scripts
            scripts_config_to_use = _scripts_config.scripts if _scripts_config else _agent_rule.scripts
            yield InterruptStartEvent(
                interrupt_id="response_template",
                content=scripts_config_to_use.get_response_template("request_start"),
            )
            yield InterruptStartEvent(
                interrupt_id="response_template",
                content=scripts_config_to_use.get_response_template("planning_start"),
            )
        logger.info(f"[DPA] 首轮：conv_id={conv_id}, query={query!r:.60}")
        session.update_state({"original_body": original_body,"response_template": None,"ui_notices": None,})
        stream_inputs = {"query": query, "conversation_id": conv_id, "user_id": user_id}

    # ── 确定使用的模式（参数 > 配置文件）───────────────────────────────
    mode_to_use = think_chunk_mode if think_chunk_mode is not None else (
        _scripts_config.think_chunk_mode if _scripts_config else ThinkChunkMode.REAL_STREAM
    )

    # ── 状态机处理 Runner 流 ─────────────────────────────────────────
    # 根据 query + 阶段选择差异化固定话术
    fixed_scripts_config = None
    full_fixed_scripts_config = None
    # 使用 _scripts_config 替代 _agent_rule 的相关配置
    scripts_config_data = _scripts_config if _scripts_config else _agent_rule
    if mode_to_use == ThinkChunkMode.FIXED_SCRIPT:
        cfg = scripts_config_data.think_chunk_fixed_scripts
        is_resume = cascade_result is not None
        scenario_query_patterns = (
            _agent_rule.active_scenario.query_patterns
            if _agent_rule and _agent_rule.active_scenario
            else None
        )
        selected = _select_fixed_scripts(
            query, cfg,
            scenario_query_patterns=scenario_query_patterns,
            is_resume=is_resume,
            is_first_thinking_round=True,
        )
        # model_copy 生成"干净"的 config 实例，feeder 只看到选中的 scripts
        fixed_scripts_config = cfg.model_copy(update={
            "scripts": selected,
            "default_scripts": [],
            "query_patterns": [],
        })
        # 保留完整配置供轮次切换时使用
        full_fixed_scripts_config = cfg

    processor = _StreamProcessor(
        scripts=_scripts_config.scripts if _scripts_config else (_agent_rule.scripts if _agent_rule else None),
        think_chunk_mode=mode_to_use.value,
        fixed_scripts_config=fixed_scripts_config,
        full_fixed_scripts_config=full_fixed_scripts_config,
    )
    raw_event_count = 0
    try:
        async for raw_event in agent.stream(inputs=stream_inputs, session=session):
            raw_event_count += 1
            logger.debug(
                f"[DPA] raw event #{raw_event_count}: type={getattr(raw_event, 'type', None)}"
            )
            events = processor.process(raw_event)
            for evt in events:
                _log_stream_payload(evt)
                yield evt

            # 在 tool_end 边界 drain 脚本/Rail 注入的非中断话术（ui_notices）。
            # 仅在 raw event 为 tool_end 时检查，避免频繁查询 session。
            if getattr(raw_event, "type", None) == "tool_end":
                raw_payload = getattr(raw_event, "payload", None) or {}
                raw_plugin = raw_payload.get("plugin", "") if isinstance(raw_payload, dict) else ""
                for evt in _drain_ui_notices(session, "tool_end", plugin=raw_plugin):
                    _log_stream_payload(evt)
                    yield evt
                if raw_plugin == "todolist_modify":
                    for evt in _drain_ui_notices(session, "todo_end", plugin=raw_plugin):
                        _log_stream_payload(evt)
                        yield evt
    except asyncio.CancelledError:
        # 取消属于正常流程（如客户端断开、超时、容器编排 SIGTERM），
        # 仅打 debug 日志，避免误报为异常并淹没真实错误。
        logger.debug(
            f"[DPA] agent.stream 被取消（CancelledError）：conv_id={conv_id}"
        )
        raise
    except Exception as e:
        logger.exception(
            f"[DPA] agent.stream 抛出异常: conv_id={conv_id}, "
            f"err_msg={e}"
        )
        raise

    # 流结束：flush 尾部事件（think_end / final_answer_end 等）
    for evt in processor.finalize():
        _log_stream_payload(evt)
        yield evt
    logger.debug(
        f"[DPA] agent.stream() 结束：共处理 {raw_event_count} 个 raw event"
    )

    # ── 话术后处理：Rail 写入的 response_template → yield 给前端 ──────────
    response_template = session.get_state("response_template")
    if response_template:
        yield InterruptStartEvent(
            interrupt_id="response_template",
            content=response_template,
        )

    # ── 中断检测：VA 委托 ─────────────────────────────────────────────
    pending_delegate = session.get_state("pending_delegate")
    if not pending_delegate:
        # 兜底：openjiuwen 在 commit_interrupt 收尾路径会把 rail 在 raise 前
        # 的最后一帧 state 写入回滚（详见 docs/issues/2026-04-28-versatile-
        # rail-state-lost-on-interrupt.md）。从框架自己持久化的 INTERRUPTION_KEY
        # 中重建 delegate。
        interruption = session.get_state(INTERRUPTION_KEY)
        interrupted_tools = (
            getattr(interruption, "interrupted_tools", None) if interruption else None
        )
        if interrupted_tools:
            entry = next(iter(interrupted_tools.values()))
            tool_call = getattr(entry, "tool_call", None)
            if tool_call is not None and getattr(tool_call, "name", "") == "call_versatile":
                args = tool_call.arguments
                if isinstance(args, str):
                    try:
                        args = json.loads(args)
                    except ValueError as e:
                        logger.warning(
                            f"[DPA] pending_delegate 解析 tool_call.arguments 失败，"
                            f"使用空 dict 兜底：err={e}, raw={args!r:.120}"
                        )
                        args = {}
                if isinstance(args, dict):
                    pending_delegate = {
                        "intent": args.get("query_intent", ""),
                        "task_description": args.get("query_description", ""),
                    }
                    logger.info(
                        f"[DPA] pending_delegate 兜底命中 INTERRUPTION_KEY: "
                        f"intent={pending_delegate['intent']}"
                    )

    if pending_delegate:
        logger.info(
            f"[DPA] 检测到 VA 委托请求：intent={pending_delegate.get('intent')}"
        )
        hb = _emit_heartbeat(conv_id, "initial", "processing", is_sub_agent=is_sub_agent)
        if hb is not None:
            yield hb
        yield DelegateRequest.model_validate(pending_delegate)
        session.update_state({"pending_delegate": None, INTERRUPTION_KEY: None})
        return

    # ── 并行调用检测：子 Agent 并行调度 ────────────────────────────────
    pending_dispatch = session.get_state("pending_dispatch")
    if pending_dispatch:
        # pending_dispatch: list[dict]，含 entity_id/entity_name/query/sub_agent_url（及 entity_type）
        specs = [
            SubAgentSpec(
                # 一律随机生成 16 位 hex，避免 LLM 自填导致冲突
                entity_id=f"entity_{uuid.uuid4().hex[:16]}",
                entity_name=e["entity_name"],
                query=e["query"],
                url=e.get("sub_agent_url", e.get("url", "")),
            )
            if isinstance(e, dict)
            else e
            for e in pending_dispatch
        ]
        logger.info(
            f"[DPA] 检测到子 Agent 并行调度请求："
            f"entities_count={len(specs)}, "
            f"entity_ids={[s.entity_id for s in specs]}, "
            f"sub_agent_urls={[s.url for s in specs]}"
        )
        hb = _emit_heartbeat(conv_id, "initial", "processing", is_sub_agent=is_sub_agent)
        if hb is not None:
            yield hb
        yield SubAgentDispatchRequest(specs=specs)
        # 注意：不清理 multiagent_dispatched，留作防重入标记
        # cascade 续轮时 Rail 会检查此标记并拒绝重复调用
        session.update_state({"pending_dispatch": None, INTERRUPTION_KEY: None})
        return

    # ── 并行调用检测：多工作流并行委托 ─────────────────────────────────
    pending_multi_delegate = session.get_state("pending_multi_delegate")
    if pending_multi_delegate:
        workflows = [WorkflowSpec(**w) if isinstance(w, dict) else w for w in pending_multi_delegate]
        logger.info(
            f"[DPA] 检测到多工作流并行委托请求："
            f"workflows_count={len(workflows)}, "
            f"intents={[w.query_intent for w in workflows if hasattr(w, 'query_intent')]}"
        )
        hb = _emit_heartbeat(conv_id, "initial", "processing", is_sub_agent=is_sub_agent)
        if hb is not None:
            yield hb
        yield MultiDelegateRequest(workflows=workflows)
        session.update_state({"pending_multi_delegate": None, INTERRUPTION_KEY: None})
        return


    # 会话正常结束（只在外部请求首轮入口侧发；cascade 续轮是中间态不发）
    if is_external_turn:
        yield ConversationEndEvent(content="本轮对话结束")


# ════════════════════════════════════════════════════════════════════
# 内部辅助
# ════════════════════════════════════════════════════════════════════


async def agent_stream(
    query: str,
    conv_id: str,
    cascade_result: Optional[dict] = None,
    context: Optional[dict] = None,
    *,
    think_chunk_mode: ThinkChunkMode | None = None,
) -> AsyncGenerator[dict[str, Any], None]:
    """Public stream contract for orchestrator: ``{"type": str, "data": dict}``."""
    async for evt in _agent_event_stream(
        query=query,
        conv_id=conv_id,
        cascade_result=cascade_result,
        context=context,
        think_chunk_mode=think_chunk_mode,
    ):
        yield _event_to_dict(evt)


async def _reset_session_after_cancel(
    agent,
    session,
    conv_id: str,
    pre_run_inputs: dict,
) -> None:
    """上一轮 CancelRail 标记了 checkpoint_to_release 后，在下一轮请求开头彻底重置会话。

    时序保证：此函数在 agent_stream() 的 pre_run() 之后调用，
    此时上一轮的 post_run()（含 checkpointer.save）一定已完成，
    不存在 release() 与 save() 的竞态。

    三层清理：
      1. Redis：release() 删除所有 {conv_id}:* key（checkpoint / agent state / workflow state / graph state）
      2. 内存 session state：清空所有业务字段（context / INTERRUPTION_KEY 等）
      3. context_engine 内存池：清除该 session 的上下文对象
         （context_engine 是 agent 单例上的跨请求复用池，不清理会导致
         create_context 返回旧对象，旧对话历史仍在内存中）

    清理完后重新 pre_run，让 checkpointer 在空 state 上重建干净的状态。
    """
    from openjiuwen.core.session.checkpointer.checkpointer import CheckpointerFactory

    logger.info(f"[DPA] 检测到取消标记，重置会话：conv_id={conv_id}")

    try:
        # 1. 删除 Redis 所有 checkpoint key
        try:
            await CheckpointerFactory.get_checkpointer().release(conv_id)
            logger.info(f"[DPA] checkpoint 已释放：conv_id={conv_id}")
        except Exception as e:
            logger.warning(f"[DPA] checkpoint 释放失败（忽略）：conv_id={conv_id}, err={e}")

        # 2. 清空内存 session state
        session.update_state({
            "checkpoint_to_release": None,
            "response_template": None,
            "pending_delegate": None,
            "pending_dispatch": None,
            "pending_multi_delegate": None,
            "cascade_result": None,
            "ui_notices": None,
            "original_body": None,
            "context": None,
            INTERRUPTION_KEY: None,
        })

        # 3. 清空 context_engine 内存池
        await agent.context_engine.clear_context(session_id=conv_id)
        logger.info(f"[DPA] context_engine 已清理：conv_id={conv_id}")

        # 重新 pre_run：checkpointer 在空 state 上 recover，session 干净启动
        session._pre_run_done = False
        await session.pre_run(inputs=pre_run_inputs)

    except Exception as e:
        # 重置是补偿性操作，失败时降级为继续使用现有 session（最坏情况残留旧上下文），
        # 不应阻断 agent_stream 主流程。
        logger.exception(
            f"[DPA] 会话重置异常，降级继续（可能残留旧上下文）：conv_id={conv_id}, err={e}"
        )


def _get_agent():
    if _agent is None:
        raise RuntimeError("EDPAgent 未初始化，请先调用 await initialize_dpa()")
    return _agent

def _drain_ui_notices(session, event_type: str, plugin: str = "") -> list[AgentEvent]:
    """从 session.ui_notices 中 drain 指定 event 类型的提示话术，yield 对应事件。

    调用者负责在原生 tool_end 边界后调用；剩余 notice 会被写回 session。
    未明文化 notice 仅 yield 带 content 的轻量事件实例，data/args 保持为空。
    """
    notices = session.get_state("ui_notices") or []
    if not isinstance(notices, list) or not notices:
        return []
    matched: list[AgentEvent] = []
    remaining: list[dict] = []
    for item in notices:
        if not isinstance(item, dict):
            continue
        if item.get("event") == event_type:
            content = str(item.get("content", "") or "")
            if not content:
                continue
            if event_type == "tool_end":
                matched.append(ToolEndEvent(content=content, plugin=plugin or "call_versatile", data={}))
            elif event_type == "todo_end":
                matched.append(TodoEndEvent(id="", status="done", content=content))
        else:
            remaining.append(item)
    session.update_state({"ui_notices": remaining or None})
    return matched


class _StreamProcessor:
    """
    把 Runner 原始事件流转换为细粒度 AgentEvent 的状态机。

    原始事件类型：
      llm_reasoning   → think_start / think_chunk / think_end + todolist 解析
    llm_output      → final_answer_start / final_answer_chunk
    answer          → final_answer_end（可能跟在 llm_output 后，也可能独立）
      tool_start      → ToolStartEvent
      tool_end        → ToolEndEvent

    TodoList 任务通过 lite_todo_write 工具管理（覆盖式），工具结果在 tool_end 时解析为 TodoList* 事件，
    话术 content 字段使用 ScriptsConfig.todolist_start / .todolist_end + 中文状态映射拼装。
    """

    STATE_IDLE = "idle"
    STATE_THINKING = "thinking"
    STATE_ANSWERING = "answering"

    def __init__(self, scripts: Any = None,
                 think_chunk_mode: str = "real_stream",
                 fixed_scripts_config: Any = None,
                 full_fixed_scripts_config: Any = None) -> None:
        """
        Args:
            scripts: ScriptsConfig 实例，提供 todolist_start / todolist_end 文案。
                     None 时退化到内置默认（"已生成任务规划" / "任务规划完成"）。
            think_chunk_mode: think_chunk 推送模式，"real_stream" 或 "fixed_script"。
            fixed_scripts_config: FixedScriptsConfig 实例，首轮话术配置（scripts 已选中）。
            full_fixed_scripts_config: 完整的 FixedScriptsConfig 实例（含 execution_scripts
                     等阶段话术），用于第2轮及后续思考的 feeder 重建。为 None 时退化到
                     fixed_scripts_config（兼容旧行为）。
        """
        self.state = self.STATE_IDLE
        self._think_buffer = ""
        self._answer_buffer = ""
        self._scripts = scripts

        # think_chunk 模式开关与固定话术推送器
        self._think_chunk_mode = think_chunk_mode
        self._feeder: _FixedScriptFeeder | None = None
        if self._think_chunk_mode == "fixed_script" and fixed_scripts_config is not None:
            self._feeder = _FixedScriptFeeder(fixed_scripts_config)

        # 完整配置（含阶段话术），供轮次切换时使用（保留用于兼容性）
        self._full_fixed_scripts_config = (
            full_fixed_scripts_config if full_fixed_scripts_config is not None
            else fixed_scripts_config
        )

        # 【新增】性能监控相关属性定义
        self._start_time = time.time()  # 记录模型调用的开始时间
        self._first_token_logged = False  # 记录首token是否被log打印了
        self._last_token_time = None  # 记录上一个token的时间
        self._event_count = 0  # 记录raw_event的序号
        # 【方案2+3c新增】可见思考序列状态标记
        self._visible_think_started = False   # 是否已发送 think_start(display=True)
        self._visible_think_ended = False     # 首个可见思考序列是否已闭合(think_end)

    def process(self, raw_event) -> list[AgentEvent]:
        """把一个原始 event 转为零或多个 AgentEvent。"""
        if raw_event is None:
            return []

        event_type = getattr(raw_event, "type", None)
        payload = getattr(raw_event, "payload", None) or {}
        if not isinstance(payload, dict):
            payload = {}
        content = payload.get("output") or payload.get("content") or ""

        # 【新增】性能监控逻辑
        self._event_count += 1
        self._log_performance(event_type, payload, self._event_count)

        events: list[AgentEvent] = []

        # ── 反思流（llm_reasoning）────────────────────────────────────
        # 话术对齐 docs/prd/talking_points.md §二
        # think_start "准备进行步骤规划" / think_chunk 首块加 "开始进行步骤规划：" 前缀
        if event_type == "llm_reasoning":
            if self.state != self.STATE_THINKING:
                events.extend(self._flush_answer_if_needed())
                events.append(ThinkStartEvent(
                    content="准备进行步骤规划..."
                ))
                self.state = self.STATE_THINKING
                self._think_buffer = ""

            # 真实流式模式：直接推送 LLM shard（不展示）
            chunk_content = (f"开始进行步骤规划...：{content}"
                             if not self._think_buffer else content)
            events.append(ThinkChunkEvent(content=chunk_content))

            self._think_buffer += content
            return events

        # ── 最终答案流（llm_output）──────────────────────────────────
        # 规范定义（feat-north-api-sse.md §4.5.9）：
        #   流式片段走 SummaryEvent（token by token）
        #   全量一次性帧走 FinalAnswerChunkEvent（由 answer 事件触发补发）
        if event_type == "llm_output":
            # 离开 thinking 状态
            events.extend(self._flush_thinking_if_needed())
            
            # 首次进入 answering 状态，标记为正式输出开始
            is_first_output = self.state != self.STATE_ANSWERING
            if is_first_output:
                events.append(FinalAnswerStartEvent())
                self.state = self.STATE_ANSWERING
                self._answer_buffer = ""
            
            # 固定话术模式：用 think_chunk 替换 summary
            if self._think_chunk_mode == "fixed_script" and self._feeder is not None:
                token_count = len(content)
                frames = self._feeder.feed_token(token_count)
                
                if frames:
                    # ── 首帧前补发 think_start(display=True) ──
                    if not self._visible_think_started:
                        events.append(ThinkStartEvent(content="", display=True))
                        self._visible_think_started = True
                    
                    for frame_text in frames:
                        events.append(ThinkChunkEvent(content=frame_text, display=True))
                    
                    if self._feeder.all_sent:
                        events.append(ThinkEndEvent(content="", display=True))
                        self._visible_think_ended = True
                
                elif not self._feeder.all_sent:
                    # 阈值未达：抑制 LLM 内容，不发任何事件
                    # LLM 真实内容仍通过 _answer_buffer 累积，
                    # 最终由 FinalAnswerChunkEvent 一次性全量发出
                    pass
                
                else:
                    # 方案3c：feeder 已耗尽
                    # 【方案B】仅在 visible 序列尚未闭合时才发射占位 think_chunk
                    # 避免在 think_end(display=True) 之后继续发射 display=true 事件
                    if not self._visible_think_ended:
                        events.append(ThinkChunkEvent(content="", display=True))
                    # else: visible 序列已闭合，不再发射 display=true 事件
                    #       LLM 内容静默累积到 _answer_buffer，由 FinalAnswerChunkEvent 发出
            else:
                # 真实流式模式：保持原有逻辑
                events.append(SummaryEvent(content=content))
            
            self._answer_buffer += content
            return events

        # ── 最终答案完成（answer）────────────────────────────────────
        if event_type == "answer":
            events.extend(self._flush_thinking_if_needed())
            # 诊断日志：每次 answer 事件都打 [EDP-LLM-RAW]（INFO，全文不截断），
            # 提供 grep 时间线；当 answer_buffer 与本帧 content 都为空时，再补一条
            # [EDP-LLM-EMPTY]（WARNING）用于快速过滤。
            logger.info(
                f"[EDP-LLM-RAW] answer event: "
                f"think_buffer_len={len(self._think_buffer)} "
                f"answer_buffer_len={len(self._answer_buffer)} "
                f"raw_answer_content_len={len(content)} "
                f"answer_buffer={self._answer_buffer!r} "
                f"raw_content={content!r}"
            )
            if not content and not self._answer_buffer:
                logger.warning(
                    f"[EDP-LLM-EMPTY] empty final answer detected: "
                    f"think_buffer_len={len(self._think_buffer)} "
                    f"answer_buffer_len={len(self._answer_buffer)} "
                    f"raw_answer_content_len={len(content)}"
                )
            if self.state == self.STATE_ANSWERING:
                # 流式已给过 summary × N，这里统一走 flush，确保 answer 边界 drain 固定话术。
                events.extend(self._flush_answer_if_needed())
            else:
                # 没有流式 output，直接 start + chunk(全量) + end
                events.append(FinalAnswerStartEvent())
                events.append(FinalAnswerChunkEvent(content=content))
                events.append(FinalAnswerEndEvent())
            self.state = self.STATE_IDLE
            self._answer_buffer = ""
            return events

        # ── 工具调用 ─────────────────────────────────────────────────
        if event_type == "tool_start":
            events.extend(self._flush_thinking_if_needed())
            events.extend(self._flush_answer_if_needed())
            plugin = payload.get("plugin", "")
            args = payload.get("args", {}) if isinstance(payload.get("args"), dict) else {}
            # 注：[EDP-LLM-TOOL] 已迁移到 ExecutionLimitRail.before_tool_call 入口
            # 处统一打印（覆盖 ask_user / lite_todo_write / read_file 等被 suppress
            # 不发 tool_start 事件的工具）。本分支只保留 ToolStartEvent 派发。
            events.append(ToolStartEvent(
                content=content,
                plugin=plugin,
                args=args,
            ))
            # 跟一个 tool_status（对齐抓包；前端把它当"运行中"提示）
            # content 与 tool_start 同步，简化实现；如需"正在…"措辞，可在话术层定制
            events.append(ToolStatusEvent(
                plugin=plugin,
                content=content,
            ))
            return events

        if event_type == "tool_end":
            events.extend(self._flush_thinking_if_needed())
            events.extend(self._flush_answer_if_needed())
            plugin = payload.get("plugin", "")
            tool_data = payload.get("data", {}) if isinstance(payload.get("data"), dict) else {}
            events.append(ToolEndEvent(
                content=content,
                plugin=plugin,
                data=tool_data,
            ))
            # 解析 lite_todo_write 工具结果 → 派生 TodoList* 事件（含话术）
            if plugin == "lite_todo_write":
                events.extend(self._parse_lite_todo_tool_result(tool_data))
            return events

        # ── 单步执行话术 todo_start / todo_end（rail 补发，对齐 talking_points §四/§七）──
        if event_type == "todo_start":
            events.extend(self._flush_thinking_if_needed())
            events.extend(self._flush_answer_if_needed())
            events.append(TodoStartEvent(
                id=payload.get("id", ""),
                title=payload.get("title", ""),
                content=content,
            ))
            return events

        if event_type == "todo_end":
            events.extend(self._flush_thinking_if_needed())
            events.extend(self._flush_answer_if_needed())
            events.append(TodoEndEvent(
                id=payload.get("id", ""),
                status=payload.get("status", "done"),
                content=content,
            ))
            return events

        # 未识别的事件忽略
        return events

    def finalize(self) -> list[AgentEvent]:
        """流结束时 flush 尚未闭合的状态。"""
        events: list[AgentEvent] = []
        events.extend(self._flush_thinking_if_needed())
        events.extend(self._flush_answer_if_needed())
        return events

    # ── 内部 flush 辅助 ─────────────────────────────────────────────

    def _flush_thinking_if_needed(self) -> list[AgentEvent]:
        if self.state != self.STATE_THINKING:
            return []
        events: list[AgentEvent] = []

        # 如果已开启可见思考序列但未闭合，drain 剩余帧并闭合
        if self._visible_think_started and not self._visible_think_ended:
            if self._feeder is not None and not self._feeder.all_sent:
                for frame_text in self._feeder.drain_all():
                    events.append(ThinkChunkEvent(content=frame_text, display=True))
            events.append(ThinkEndEvent(content="", display=True))
            self._visible_think_ended = True

        # 闭合不可见思考序列（llm_reasoning 阶段）
        events.append(ThinkEndEvent(content="", display=False))

        self.state = self.STATE_IDLE
        self._think_buffer = ""
        return events

    def _flush_answer_if_needed(self) -> list[AgentEvent]:
        if self.state != self.STATE_ANSWERING:
            return []
        events: list[AgentEvent] = []

        # answer 边界执行 R2：LLM 流结束但 fixed_script 未发完时，立即 drain 剩余话术。
        if self._feeder is not None and not self._feeder.all_sent:
            if not self._visible_think_started:
                events.append(ThinkStartEvent(content="", display=True))
                self._visible_think_started = True

            for frame_text in self._feeder.drain_all():
                events.append(ThinkChunkEvent(content=frame_text, display=True))

        # 如果 visible think 序列已经开始但尚未闭合，则补 think_end。
        if self._visible_think_started and not self._visible_think_ended:
            events.append(ThinkEndEvent(content="", display=True))
            self._visible_think_ended = True

        # 补全量 final_answer_chunk + end（保证前端拿到权威文本）
        events.extend([
            FinalAnswerChunkEvent(content=self._answer_buffer),
            FinalAnswerEndEvent(),
        ])
        self.state = self.STATE_IDLE
        self._answer_buffer = ""
        return events

    def _parse_lite_todo_tool_result(self, tool_data: dict) -> list[AgentEvent]:
        """解析 lite_todo_write 工具结果，派生 TodoListStartEvent + N×Item + EndEvent。

        v2 step_id schema：每条 todo 是 ``{"step_id": <1..4>, "status": "pending"|"done"}``，
        content 由框架按 ``CANONICAL_STEPS[step_id]`` 反查（防止 LLM 自创步骤名漂移）。
        不打算做的步骤 LLM 直接不放进 todos——列表里出现的就是即将做或已完成的项。

        Item.content 渲染格式（对齐 docs/prd/talking_points.md L11）：
            "{visible_pos}.{canonical_name}（{status_cn}）<br/>"
        其中 ``visible_pos`` 是**1-based 数组下标**（连续编号 1 / 2 / 3 / ...），
        而 ``Item.id`` 字段仍是 canonical ``step_id``（1 / 2 / 3 / 4）。
        这样前端只渲染 content 时看到的是连续编号（不会出现 1 → 3 → 4 跳号），
        后端用 ``id`` 字段做 step_id ↔ skill 路由 / 状态匹配仍然准确。
        例如 LLM 选 ``[step_id=1, step_id=3, step_id=4]`` 时：
            content: "1.推荐... / 2.确定购买... / 3.查询余额..."
            id:           1            3                4

        TodoListStartEvent.content / TodoListEndEvent.content 取自 ScriptsConfig
        （todolist_start / todolist_end 字段），未配置时退化为内置默认。

        若 todos 为空（全部 done 自动清空场景），不发任何事件——
        避免空清单污染前端。
        """
        from .tool.lite_todo.models import TODO_STATUS_CN, get_canonical_steps

        events: list[AgentEvent] = []
        todos = tool_data.get("todos", [])
        if not todos:
            return events

        start_text = getattr(self._scripts, "todolist_start", None) or "已生成任务规划"
        end_text = getattr(self._scripts, "todolist_end", None) or "任务规划完成"

        # 一次性取当前激活的 step_id → content 映射（运行时来自 AgentRule.md）
        from .tool.lite_todo.models import TODO_STATUS_CN, get_canonical_steps, get_step_to_skill
        canonical_map = get_canonical_steps()
        skill_map = get_step_to_skill()

        events.append(TodoListStartEvent(content=start_text))
        for visible_pos, t in enumerate(todos, start=1):
            step_id = t.get("step_id")
            status = t.get("status", "pending")
            # content 始终从 _active_steps 反查（不信任 LLM 或持久化层传入的 content 串）
            canonical = canonical_map.get(step_id, "<未知步骤>") if isinstance(step_id, int) else "<未知步骤>"
            status_cn = TODO_STATUS_CN.get(status, status)
            events.append(TodoListItemEvent(
                id=step_id if isinstance(step_id, int) else 0,
                title=canonical,
                status=status,
                # 视觉编号用 1-based 下标，避免前端看到 "1.xxx 3.xxx 4.xxx" 跳号
                content=f"{visible_pos}.{canonical}（{status_cn}）<br/>",
            ))
        events.append(TodoListEndEvent(count=len(todos), content=end_text))
        logger.info(f"[DPA] lite_todo_write → {len(todos)} TodoList* events")
        # 记录详细的TodoList信息用于监控和调试
        to_logger(
            level=Level.INFO,
            message=json.dumps({
                "id": str(uuid.uuid4()),
                "name": str(Tag.TAG_PLANNING_DECISION),
                "start_time": datetime.fromtimestamp(time.time()).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                "type": str(ObservationType.EVENT),
                "input": {
                    "todo_list": [
                        {
                            "step_id": t.get("step_id"),
                            "status": t.get("status", "pending"),
                            "tool_name": "lite_todo_write",
                            "skill_name": skill_map.get(t.get("step_id"), "<未知技能>"),
                            "content": (
                                f"{visible_pos}."
                                f"{canonical_map.get(t.get('step_id'), '<未知步骤>')}（"
                                f"{TODO_STATUS_CN.get(t.get('status', 'pending'), t.get('status', 'pending'))}）"
                            ),
                        }
                        for visible_pos, t in enumerate(todos, start=1)
                    ]},
            }, ensure_ascii=False),
            extra=Extra(tag=Tag.TAG_PLANNING_DECISION, cost=0),
        )
        return events

    def _log_performance(self, event_type: str, payload: dict, event_counter: int = 0) -> None:
        """内部方法：记录LLM调用的首字时延、Token间隔及消耗统计"""
        """JiuWen SDK没有chunk的钩子，session级别的write记录在这里实现"""
        current_time = time.time()
        # logger.debug(f"[DPA] raw event #{self._event_count}: type={event_type}")
        # 监控文本 Token 时延 (TTFT & Interval)
        if event_type == "llm_output":
            if not self._first_token_logged:
                ttft_ms = int((current_time - self._start_time) * 1000)
                to_logger(
                    level=Level.INFO,
                    message={},
                    extra=Extra(tag=Tag.TAG_LLM_CALL_FIRST_TOKEN, cost=ttft_ms),
                )
                self._first_token_logged = True
                self._last_token_time = current_time
            else:
                if self._last_token_time:
                    interval_ms = int((current_time - self._last_token_time) * 1000)
                    to_logger(
                        level=Level.DEBUG,
                        message={},
                        extra=Extra(tag=Tag.TAG_LLM_CALL_STREAM_TOKEN, cost=interval_ms),
                    )
                self._last_token_time = current_time

        # 监控 Token 消耗 (监听 llm_usage 事件)
        if event_type == "llm_usage":
            usage_data = payload.get("usage_metadata", {})
            if isinstance(usage_data, dict):
                input_tokens = usage_data.get("input_tokens", 0)
                output_tokens = usage_data.get("output_tokens", 0)
                total_tokens = usage_data.get("total_tokens", 0)
                to_logger(
                    level=Level.INFO,
                    message={
                        "input_tokens": {input_tokens},
                        "output_tokens": {output_tokens},
                        "total_tokens": {total_tokens},
                    },
                    extra=Extra(tag=Tag.TAG_LLM_CALL_STATISTICS, cost=0),
                )
