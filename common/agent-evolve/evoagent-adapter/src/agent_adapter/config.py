"""Adapter configuration model — YAML + environment variable loading."""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any, Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# ── YAML 配置值 ${VAR} / ${VAR:default} 环境变量插值 ────────────────
# yaml 经 yaml.safe_load 读入后是字面量，${AGENT_URL:} 不会被自动解析。
# 这里在 load_config 中对 yaml 原始 dict 递归展开占位，使配置全走 .env：
#   ${VAR}        → os.environ[VAR]，未设置则空串（兼容既有 extra_headers 语义）
#   ${VAR:}       → 未设置时空串
#   ${VAR:default}→ 未设置时取 default
# 环境变量已设置时一律取环境变量值（env > yaml 默认 > 字段默认）。
_ENV_REF_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}")


def _expand_env_ref(match: "re.Match[str]") -> str:
    var_name = match.group(1)
    default = match.group(2)  # None ⇒ `${VAR}` 无默认值；"" ⇒ `${VAR:}` 显式空默认
    env_value = os.environ.get(var_name)
    # 环境变量已设置且非空 → 取环境变量值；空串视为未设置，走默认值。
    # 这样部署侧（start.sh/compose）传入空值时不会击穿 int/float 字段的强制转换。
    if env_value:
        return env_value
    return default if default is not None else ""


def _expand_env_refs(value: Any) -> Any:
    """递归展开配置值中的 ${VAR} / ${VAR:default} 占位为环境变量值。

    仅字符串参与插值；dict / list 递归处理其值，其它类型原样返回。
    """
    if isinstance(value, str):
        return _ENV_REF_PATTERN.sub(_expand_env_ref, value)
    if isinstance(value, dict):
        return {k: _expand_env_refs(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_expand_env_refs(item) for item in value]
    return value

# ── Managed-doc 配置（spec managed-doc-agent-rule §8） ────────────────
# managed-docs 端点支持的可优化文档（如 AgentRule.md）。apply=restart 时
# 由部署侧提供 restart_cmd + health 探测参数；burst/single profile 决定
# burst 训练 vs 单次人工更新两套默认值（spec §8.4）。

# spec §8.4 默认值表：burst（canary 训练，快速失败）与 single（人工单次）。
_BURST_DEFAULTS: dict[str, float | int] = {
    "max_attempts": 2,
    "backoff_base": 3.0,
    "backoff_max": 30.0,
    "health_down_timeout": 15.0,
    "health_up_timeout": 60.0,
    "health_up_consecutive": 2,
    "health_poll_interval": 0.5,
}
_SINGLE_DEFAULTS: dict[str, float | int] = {
    "max_attempts": 3,
    "backoff_base": 5.0,
    "backoff_max": 60.0,
    "health_down_timeout": 30.0,
    "health_up_timeout": 90.0,
    "health_up_consecutive": 2,
    "health_poll_interval": 1.0,
}


class ManagedDocConfig(BaseModel):
    """单条 managed-doc 配置（spec §8.2）。

    kind/path 必填；apply 默认 file_only。apply=restart 时 restart_cmd 必填
    （由 _restart_requires_cmd validator 兜底）。restart 专用字段可由
    ManagedDocDefaults 的 profile 默认值填充（在 registry 构建期解析）。
    """

    model_config = ConfigDict(extra="forbid")

    kind: str
    path: str
    apply: Literal["file_only", "restart"] = "file_only"
    # 最大内容尺寸（UTF-8 字节数，spec G1/C8）。默认 256 KiB；允许 per-doc 覆写。
    # validation.validate 按编码后字节数校验，超限/编码失败 → 400 INVALID_ACTION 不落盘。
    max_content_bytes: int = Field(default=262_144, ge=0)
    # Host-side mount root the configured ``path`` must resolve under (spec D3).
    # When set, DocStorage enforces real path-traversal protection; when None,
    # storage falls back to ``path``'s parent dir (only catches ``..`` escape).
    allow_root: str | None = None
    # apply=restart 专用（可由 managed_doc_defaults 默认，此处覆写）：
    restart_cmd: str | None = None
    restart_timeout: int | None = None
    health_url: str | None = None
    health_down_timeout: float | None = None
    health_up_timeout: float | None = None
    health_up_consecutive: int | None = None
    health_poll_interval: float | None = None
    max_attempts: int | None = None
    backoff_base: float | None = None
    backoff_max: float | None = None

    @model_validator(mode="after")
    def _restart_requires_cmd(self) -> ManagedDocConfig:
        if self.apply == "restart" and not self.restart_cmd:
            raise ValueError("apply=restart requires restart_cmd")
        return self


class ManagedDocDefaults(BaseModel):
    """managed-doc 默认值（spec §8.3 / §8.4 / §8.6）。

    typed（非裸 dict）：键拼错在配置期即报错（extra=forbid）。profile 决定
    burst/single 两套默认基线，effective_defaults() 返回对应基线供 registry
    填充 ManagedDocConfig 的 None 字段。显式覆写值优先于基线。
    """

    model_config = ConfigDict(extra="forbid")

    profile: Literal["burst", "single"] = "burst"
    task_ttl_seconds: int = 600
    shutdown_grace_timeout: float = 10.0
    # restart 专用字段基线覆写（None 表示走 profile 默认表）：
    restart_cmd: str | None = None
    restart_timeout: int | None = None
    health_url: str | None = None
    health_down_timeout: float | None = None
    health_up_timeout: float | None = None
    health_up_consecutive: int | None = None
    health_poll_interval: float | None = None
    max_attempts: int | None = None
    backoff_base: float | None = None
    backoff_max: float | None = None

    def effective_defaults(self) -> dict[str, float | int]:
        """返回当前 profile 对应的默认基线（spec §8.4 表）。"""
        return _SINGLE_DEFAULTS.copy() if self.profile == "single" else _BURST_DEFAULTS.copy()


class RecognizerConfig(BaseModel):
    """单个 skill 识别器 (per-agent, 按需勾选; 评审稿 §3.3)。

    adapter 据识别器判定 "哪些 span 表示某 skill 被激活/执行" (L1/L2 归属信号):
      - skill_span:          认 ``skill.<skill>`` span (业务 agent 自声明, L1 ground truth);
                              span_name_prefix 默认 "skill."。
      - read_file_skill:      认 ``read_file`` 读 SKILL.md (OpenCode 主信号); path_field 指向
                              input 里 skill 文件路径字段, 命中 skills_dir 即开 active 上下文 (L2)。
      - todo_write_boundary: 认 ``lite_todo_write`` 规划出某 skill (EDPAgent 兜底); tool_name
                              指向规划工具名。
    """

    model_config = ConfigDict(extra="forbid")

    kind: Literal["skill_span", "read_file_skill", "todo_write_boundary"]
    span_name_prefix: str | None = None
    tool_name: str | None = None
    path_field: str | None = None


_DEFAULT_OWNERSHIP_VERBS = [
    "调用", "使用", "通过", "借助", "用来", "invoke", "call", "use",
]
_DEFAULT_NEGATION_CUES = [
    "禁止", "不要", "勿", "不应", "不得", "避免", "请勿", "不可",
    "do not", "don't", "must not", "never", "avoid", "without",
]


class ProseMatchingConfig(BaseModel):
    """prose 匹配 own/forbid 分类配置 (L2 多 skill 消歧 / L3 全局匹配; 评审稿 §3.1.1)。

    默认词典可被 per-agent 覆写。forbid 优先 own: 同 skill 既出现 "用 X" 又 "禁用 X"
    (通常条件性使用) 时保守按禁用, X 不归该 skill。
    """

    model_config = ConfigDict(extra="forbid")

    enabled: bool = False
    ownership_verbs: list[str] = Field(default_factory=lambda: list(_DEFAULT_OWNERSHIP_VERBS))
    negation_cues: list[str] = Field(default_factory=lambda: list(_DEFAULT_NEGATION_CUES))


class AttributionConfig(BaseModel):
    """per-agent skill 归属配置 (评审稿 §3.3)。

    adapter 据此把异构 agent 的 span 归一到统一 attribution 二层字段
    {skill, source, confidence, candidates, misuse}。recognizers 按需勾选;
    prose_matching 仅 L2 多 skill 消歧 / L3 全局匹配时用 (EDPAgent 走 skill_span 不需)。
    """

    model_config = ConfigDict(extra="forbid")

    enabled: bool = True
    recognizers: list[RecognizerConfig] = Field(default_factory=list)
    prose_matching: ProseMatchingConfig = Field(default_factory=ProseMatchingConfig)
    fallback_skill: str = "Agent.md"
    # 传输层/入口 span 名 (归 ingress, 非业务行为): EDPAgent 每轮 HTTP 入口是 http.request。
    ingress_span_names: list[str] = Field(default_factory=lambda: ["http.request"])


class AgentEntryConfig(BaseModel):
    """Configuration for a single managed Agent.

    Each agent has its own log directory, output archive, and offset state.
    Optional fields default to None and are filled by load_config:
      - log_dir inherits from top-level AdapterConfig.log_dir (env: ADAPTER_LOG_DIR)
      - log_pattern inherits from top-level AdapterConfig.log_pattern
      - output_dir defaults to data/output/{name}
      - offset_file defaults to data/offsets/{name}.json
      - skills_dir defaults to {skills_root}/{name} (skills_root env: ADAPTER_SKILLS_ROOT)
    """

    name: str
    log_dir: str | None = None
    log_pattern: str | None = None
    output_dir: str | None = None
    offset_file: str | None = None
    skills_dir: str | None = None
    agent_url: str | None = None
    project_id: str | None = None
    agent_id: str | None = None
    timeout: int = 300
    # ── 业务 Agent 请求透传（可选 body/header/URL 自定义）──
    # request_template: 稳定 body 字段底模（深合并到最终 body，调用方 extra_data
    #   仍合并进 custom_data.inputs）。用于 role_id/role_name/timeout 等部署侧字段，
    #   避免 evo_agent 懂业务 body 语义。
    request_template: dict[str, Any] | None = None
    # extra_headers: 稳定请求头。值支持 ${ENV_VAR} 语法从环境变量读取（如 token）。
    extra_headers: dict[str, str] | None = None
    # url_query_params: URL query 参数（如 mode=default&workspace_id=ws_001）。
    url_query_params: dict[str, str] | None = None
    # ── Skill backend (local shared FS vs jiuwenbox sandbox FS) ──
    # skill_backend: "local" (default, host/shared mount) | "jiuwenbox" (upload API)
    skill_backend: Literal["local", "jiuwenbox"] = "local"
    # jiuwenbox_url: management API base, e.g. http://jiuwenbox:8321
    jiuwenbox_url: str | None = None
    # sandbox_id: optional fixed id; required when sandbox_id_resolve=fixed
    sandbox_id: str | None = None
    # sandbox_id_resolve: how to find EDPAgent's sandbox without changing EDPAgent
    # Recommended: from_logs > fixed > list_ready
    #   from_logs  — parse "sandbox_id=..." from agent log_dir, else list_ready
    #   fixed      — use sandbox_id (stable when id is known)
    #   list_ready — unique ready sandbox; fails if multiple ready (fragile in shared box)
    sandbox_id_resolve: Literal["fixed", "list_ready", "from_logs"] = "from_logs"
    # remote_skills_dir: path *inside* the sandbox (EDPAgent SKILL_TARGET_PATH/skills)
    remote_skills_dir: str = "/tmp/skills"
    # managed_docs: 该 agent 的可优化文档配置（spec managed-doc-agent-rule §8.1）。
    managed_docs: list[ManagedDocConfig] = Field(default_factory=list)
    # attribution: 该 agent 的 skill 归属配置 (None=走 AdapterConfig 默认; 评审稿 §3.3)。
    attribution: AttributionConfig | None = None


def _get_adapter_root() -> Path:
    """Get the adapter package root directory.

    The adapter root is the directory containing pyproject.toml,
    which is the parent of the src/ directory containing this module.
    """
    # This file is at: <adapter_root>/src/agent_adapter/config.py
    # Adapter root is: <adapter_root>/
    current_file = Path(__file__).resolve()
    # Go up from src/agent_adapter/config.py to adapter root
    return current_file.parent.parent.parent


# Module-level constant for adapter root (computed once)
_ADAPTER_ROOT = _get_adapter_root()


class AdapterConfig(BaseSettings):
    """All configuration for the agent-adapter service.

    Values are resolved in order: environment variable > YAML file > default.
    Environment variables use the ADAPTER_ prefix, e.g. ADAPTER_POLL_INTERVAL.
    For match_tags, use JSON array format: ADAPTER_MATCH_TAGS='["TAG_A","TAG_B"]'

    Relative paths (log_dir, output_dir, offset_file) are always resolved
    relative to the adapter package root directory, regardless of where
    the config file is located.
    """

    model_config = SettingsConfigDict(env_prefix="ADAPTER_")

    # ── Log source ──
    log_dir: str = "logs"
    log_pattern: str = "process*.log"

    # ── Read strategy ──
    poll_interval: int = 60
    start_from: Literal["tail", "head"] = "tail"

    # ── Extraction rules ──
    match_tags: list[str] = Field(
        default=[
            "TAG_HTTP_REQUEST_START",
            "TAG_HTTP_REQUEST_END",
            "TAG_LLM_CALL_START",
            "TAG_LLM_CALL_END",
            "TAG_PLANNING_DECISION",
            "TAG_TOOL_EXECUTE_START",
            "TAG_TOOL_EXECUTE_END",
            "TAG_SKILL_EXECUTE_START",
            "TAG_SKILL_EXECUTE_END",
            "TAG_VERSATILE_START",
            "TAG_VERSATILE_END",
        ],
    )

    # ── Pairing strategy ──
    pair_timeout: int = 300

    # ── Output ──
    output_dir: str = "data/output"
    offset_file: str = "data/offsets.json"

    # ── Skill storage (shared mount with business agents) ──
    skills_root: str = "data/skills"

    # ── Managed-doc defaults (typed; spec managed-doc-agent-rule §8.3) ──
    managed_doc_defaults: ManagedDocDefaults = Field(default_factory=ManagedDocDefaults)

    # ── Output file cleanup ──
    output_retention_days: int = 30
    output_max_files: int = 2000
    output_max_file_size: str = "20MB"
    output_trim_target_ratio: float = 0.7

    # ── HTTP service ──
    host: str = "0.0.0.0"
    port: int = 8900

    # ── Multi-Agent ──
    agents: list[AgentEntryConfig] = Field(default_factory=list)

    # ── Trace source (设计文档 §5): log 读归档 | standard 读 PG (经 kafka 消费) ──
    trace_source: Literal["log", "standard"] = "log"
    # ── DB (Repository 工厂, standard 模式用; 复用 collector 的 otel 库) ──
    # 默认值对齐容器部署 (start.sh): pg_host=postgres 容器别名, pg_db=agent_adapter 独立库。
    # 本地开发连宿主 PG 时设 ADAPTER_PG_HOST=127.0.0.1 覆写。
    db_type: Literal["postgres"] = "postgres"
    pg_host: str = "postgres"
    pg_port: int = 5432
    pg_db: str = "agent_adapter"
    pg_user: str = "otel_user"
    pg_password: str = "otel_password"
    # ── Kafka (standard 模式消费 otlp_traces) ──
    kafka_brokers: str = "kafka:9092"
    kafka_topic: str = "otlp_traces"
    kafka_group: str = "agent-adapter"
    # ── GET /traces/{conv} 服务端短等待 (设计文档 §7: 5s 上报 + 余量) ──
    trace_wait_timeout: float = 10.0
    # ── AttributionRunner 后台轮询 (trace 完整后异步算归属写回 spans.attribution) ──
    attribution_runner_enabled: bool = True
    attribution_poll_interval: float = 5.0

    # ── SkillHub integration (optional publish/pull to marketplace) ──
    skillhub_enabled: bool = False
    skillhub_base_url: str = ""
    skillhub_auth_mode: Literal["bearer", "system_token"] = "system_token"
    skillhub_token: str = ""
    skillhub_token_env: str = "SKILLHUB_TOKEN"
    skillhub_connect_timeout: float = 30.0
    skillhub_publish_timeout: float = 120.0
    skillhub_version_strategy: Literal["patch", "manual"] = "manual"
    skillhub_default_plugin_type: str = "skill"

    # ── Internal (not from YAML/env) ──
    _yaml_path: str | None = None

    @field_validator("match_tags", mode="before")
    @classmethod
    def parse_match_tags(cls, v: object) -> object:
        """Accept comma-separated string from env var, or a list from YAML."""
        if isinstance(v, str):
            return [tag.strip() for tag in v.split(",") if tag.strip()]
        return v


def load_config(yaml_path: Path | None = None) -> AdapterConfig:
    """Load configuration from a YAML file with env var overrides.

    Resolution order: environment variables > YAML file > defaults.
    If the YAML file does not exist, only defaults + env vars are used.

    Path resolution for relative paths:
    - All relative paths (log_dir, output_dir, offset_file) are resolved
      relative to the adapter package root directory (where pyproject.toml is).
    - This ensures consistent path resolution regardless of config file location.
    - Absolute paths are always used as-is.
    """
    yaml_values: dict = {}

    if yaml_path is not None and yaml_path.exists():
        with open(yaml_path, encoding="utf-8") as f:
            yaml_values = yaml.safe_load(f) or {}

    # 展开 ${VAR} / ${VAR:default} 占位为环境变量值（配置全走 .env 的机制）。
    # 必须在 env_overridden_keys 过滤之前进行：env 已设置的字段随后会被排除，
    # 由 pydantic-settings 直接读 env；env 未设置的字段取此处展开后的（默认）值。
    yaml_values = _expand_env_refs(yaml_values)

    # Only pass YAML values for fields NOT already set by env vars,
    # so env vars always take precedence over YAML.
    env_overridden_keys: set[str] = set()
    for field_name in AdapterConfig.model_fields:
        env_key = f"ADAPTER_{field_name.upper()}"
        # 仅当 env 已设置且非空时才视为覆盖；空串走 yaml 默认（resolver 会用默认值），
        # 避免 start.sh/compose 传入空值时 int/float 字段因空串强制转换而报错。
        if os.environ.get(env_key):
            env_overridden_keys.add(field_name)

    yaml_only_values = {k: v for k, v in yaml_values.items() if k not in env_overridden_keys}

    config = AdapterConfig(**yaml_only_values)

    # ── Fill defaults for agent entries ──
    if config.agents:
        config = _fill_agent_defaults(config)
    else:
        # Backward compat: no agents field → create single agent from top-level paths
        config = _fallback_single_agent(config)

    # Resolve relative paths based on adapter root directory
    # Skip fields that were set via environment variables
    config = _resolve_paths(config, _ADAPTER_ROOT, env_overridden_keys)

    # Store the YAML path for CRUD operations
    if yaml_path is not None:
        config = config.model_copy(update={"_yaml_path": str(yaml_path)})

    return config


def _fill_agent_defaults(config: AdapterConfig) -> AdapterConfig:
    """Fill default values for agent entries that omitted optional fields.

    - log_dir: inherit from top-level AdapterConfig.log_dir (env: ADAPTER_LOG_DIR)
    - log_pattern: inherit from top-level AdapterConfig.log_pattern
    - output_dir: default to data/output/{name}
    - offset_file: default to data/offsets/{name}.json
    - skills_dir: default to {skills_root}/{name} (skills_root env: ADAPTER_SKILLS_ROOT)
    """
    updated_agents: list[AgentEntryConfig] = []
    for agent in config.agents:
        updates: dict = {}
        if agent.log_dir is None:
            updates["log_dir"] = config.log_dir
        if agent.log_pattern is None:
            updates["log_pattern"] = config.log_pattern
        if agent.output_dir is None:
            updates["output_dir"] = f"data/output/{agent.name}"
        if agent.offset_file is None:
            updates["offset_file"] = f"data/offsets/{agent.name}.json"
        if agent.skills_dir is None:
            updates["skills_dir"] = f"{config.skills_root}/{agent.name}"
        if updates:
            updated_agents.append(agent.model_copy(update=updates))
        else:
            updated_agents.append(agent)
    return config.model_copy(update={"agents": updated_agents})


def _fallback_single_agent(config: AdapterConfig) -> AdapterConfig:
    """When no agents list is configured, create a single default agent from top-level paths.

    This preserves backward compatibility with v2 config files that only have
    top-level log_dir / output_dir / offset_file fields.
    """
    default_agent = AgentEntryConfig(
        name="default",
        log_dir=config.log_dir,
        log_pattern=config.log_pattern,
        output_dir=config.output_dir,
        offset_file=config.offset_file,
    )
    return config.model_copy(update={"agents": [default_agent]})


def _resolve_paths(
    config: AdapterConfig,
    base_dir: Path,
    env_overridden_keys: set[str] | None = None,
) -> AdapterConfig:
    """Resolve relative paths in config to absolute paths based on adapter root.

    This modifies only path-type fields (log_dir, output_dir, offset_file) that
    contain relative paths and were NOT set via environment variables.
    Absolute paths are left unchanged.

    Args:
        config: The loaded configuration
        base_dir: The adapter root directory (where pyproject.toml is located)
        env_overridden_keys: Set of field names that were set via env vars

    Returns:
        A new AdapterConfig with resolved absolute paths
    """
    # Fields that represent paths and should be resolved
    # Note: offset_file is a file path, not a directory, but still needs resolution
    path_fields = {"log_dir", "output_dir", "offset_file", "skills_root"}

    updates: dict = {}
    for field_name in path_fields:
        # Skip fields that were set via environment variables
        if env_overridden_keys and field_name in env_overridden_keys:
            continue

        value = getattr(config, field_name)
        if value and not _is_absolute_path(value):
            # Resolve relative path to absolute
            resolved = (base_dir / value).resolve()
            updates[field_name] = str(resolved)

    if updates:
        # Create a new config with updated paths
        config = config.model_copy(update=updates)

    # Resolve per-agent relative paths
    resolved_agents = _resolve_agent_paths(config.agents, base_dir)
    if resolved_agents is not config.agents:
        config = config.model_copy(update={"agents": resolved_agents})

    return config


def _resolve_agent_paths(
    agents: list[AgentEntryConfig],
    base_dir: Path,
) -> list[AgentEntryConfig]:
    """Resolve relative paths in each AgentEntryConfig.

    Per-agent path fields: log_dir, output_dir, offset_file, skills_dir.
    Absolute paths are left unchanged.
    """
    agent_path_fields = {"log_dir", "output_dir", "offset_file", "skills_dir"}
    updated_agents: list[AgentEntryConfig] = []

    for agent in agents:
        agent_updates: dict = {}
        for field_name in agent_path_fields:
            value = getattr(agent, field_name)
            if value and not _is_absolute_path(value):
                resolved = (base_dir / value).resolve()
                agent_updates[field_name] = str(resolved)
        if agent_updates:
            updated_agents.append(agent.model_copy(update=agent_updates))
        else:
            updated_agents.append(agent)

    return updated_agents


def _is_absolute_path(path_str: str) -> bool:
    """Check if a path string is absolute on any platform.

    Handles both Unix-style (`/var/log`) and Windows-style (`C:\\log`) paths.
    """
    path = Path(path_str)
    if path.is_absolute():
        return True
    # On Windows, Unix-style paths like "/var/log" are not absolute
    # Check for Unix-style absolute path (starts with /)
    if path_str.startswith("/"):
        return True
    return False
