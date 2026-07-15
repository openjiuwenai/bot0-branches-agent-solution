"""Adapter configuration model — YAML + environment variable loading."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

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
    # ── EDPAgent 请求透传（客户现场 body/header/URL 自定义）──
    # request_template: 稳定 body 字段底模（深合并到最终 body，调用方 extra_data
    #   仍合并进 custom_data.inputs）。用于 role_id/role_name/timeout/custom_data.user_profile
    #   等客户环境绑定字段，避免 evo_agent 懂业务 body 语义。
    request_template: dict[str, Any] | None = None
    # extra_headers: 稳定请求头。值支持 ${ENV_VAR} 语法从环境变量读取（如 token）。
    extra_headers: dict[str, str] | None = None
    # url_query_params: URL query 参数（如 type=controller&workspace_id=191）。
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
    log_pattern: str = "process_*.log"

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

    # Only pass YAML values for fields NOT already set by env vars,
    # so env vars always take precedence over YAML.
    env_overridden_keys: set[str] = set()
    for field_name in AdapterConfig.model_fields:
        env_key = f"ADAPTER_{field_name.upper()}"
        if env_key in os.environ:
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
