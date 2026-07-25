"""
EDPAgent 配置读取（替代旧版 pydantic-settings）。

变量命名对齐 PLANNING_AGENT_MODEL_* 格式，与 .env 保持一致。
支持自定义 LLM 请求头：token / userId / 额外 JSON header。
"""
from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Optional
from urllib.parse import quote_plus

from dotenv import load_dotenv
from loguru import logger
from pydantic import BaseModel, Field

from common.crypto import decrypt_config_value

# 加载 a2a_service/.env 到 os.environ，让 os.getenv 能读到 PLANNING_AGENT_MODEL_* 等变量
# 优先级：CONFIG_PATH > a2a_service/.env（默认）
_CONFIG_PATH = os.environ.get("CONFIG_PATH")
if _CONFIG_PATH:
    _ENV_FILE = Path(_CONFIG_PATH)
else:
    # 本模块位置：applications/a2a_service/agents/EDPAgent/config.py
    # a2a_service/.env 在上三级目录
    _ENV_FILE = Path(__file__).resolve().parent.parent.parent / ".env"
if _ENV_FILE.exists():
    load_dotenv(_ENV_FILE, override=False)


class DPASettings(BaseModel):
    # ── LLM ─────────────────────────────────────────────────────────────────
    llm_provider: str = "OpenAI"
    llm_api_base: str = ""
    llm_api_key: str = ""
    llm_model_name: str = ""
    llm_verify_ssl: bool = False
    llm_timeout: float = 120.0
    custom_headers: Optional[dict[str, Any]] = None

    # ── Redis（Checkpointer）────────────────────────────────────────────────
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_db: int = 0
    redis_password: str = ""
    redis_checkpointer_ttl_minutes: int = 60

    # ── DPA Agent ───────────────────────────────────────────────────────────
    dpa_agent_id: str = "edp_agent"
    dpa_agent_name: str = "EDP Agent"
    dpa_max_iterations: int = 30

    # ── Context Engine（滑动窗口）──────────────────────────────────────────
    context_engine_enabled: bool = False
    context_engine_max_context_message_num: int = 200
    context_engine_default_window_round_num: int = 10
    context_engine_enable_reload: bool = False

    # ── Context Compression（仅接入 DialogueCompressor）──────────────────
    dialogue_compression_enabled: bool = False
    dialogue_compression_tokens_threshold: int = 10000
    dialogue_compression_target_tokens: int = 1500
    dialogue_compression_keep_last_round: bool = False

    # ── Memory（默认关闭，显式开启）────────────────────────────────────────
    memory_enabled: bool = False
    memory_scope_id: str = "edp_agent"
    memory_write_interval: int = 9999
    memory_idle_flush_timeout_second: int = 600
    memory_pending_flush_chars_threshold: int = 20000
    memory_enable_user_profile: bool = False
    memory_enable_semantic_memory: bool = False
    memory_enable_episodic_memory: bool = False
    memory_enable_summary_memory: bool = False
    memory_variables: list[dict[str, str]] = []

    # ── Memory 存储（仅 memory_enabled=true 时生效）────────────────────────
    memory_gauss_host: str = "localhost"
    memory_gauss_port: int = 8000
    memory_gauss_user: str = "root"
    memory_gauss_password: str = ""
    memory_gauss_database: str = "postgres"

    memory_es_host: str = "http://localhost:9200"
    memory_es_user: str = ""
    memory_es_password: str = ""

    memory_embedding_model_name: str = "text-embedding-3-small"
    memory_embedding_api_base: str = ""
    memory_embedding_api_key: str = ""
    memory_embedding_custom_headers: Optional[dict[str, Any]] = None

    # Memory LLM（可与主 Agent LLM 分离配置）
    memory_llm_provider: str = "OpenAI"
    memory_llm_api_base: str = ""
    memory_llm_api_key: str = ""
    memory_llm_model_name: str = ""
    memory_llm_verify_ssl: bool = False
    memory_llm_timeout_second: float = 120.0
    memory_llm_custom_headers: Optional[dict[str, Any]] = None

    sandbox_url: str = ""
    skill_target_path: str = "/tmp"

    # ── OpenTelemetry ───────────────────────────────────────────────────────
    # 环境变量控制（8 项）
    otel_enabled: bool = False              # OTel 总开关，false 时跳过所有 OTel 逻辑
    otel_exporter_endpoint: str = ""       # OTLP Collector 地址
    otel_sample_rate: float = 1.0           # 采样率，生产可调低
    otel_exporter_type: str = "otlp"        # 导出器类型：otlp / console
    otel_redaction_enabled: bool = False    # 脱敏开关，默认关闭（需求要求记录完整数据）
    otel_max_attr_length: int = 0           # 属性值截断长度，0 表示不截断
    otel_service_name: str = "edpagent"     # 在 OTel 后端标识本服务
    otel_protocol: str = "grpc"             # 传输协议：grpc / http/protobuf

    @property
    def redis_url(self) -> str:
        if self.redis_password:
            pwd = quote_plus(self.redis_password)
            return f"redis://:{pwd}@{self.redis_host}:{self.redis_port}/{self.redis_db}"
        return f"redis://{self.redis_host}:{self.redis_port}/{self.redis_db}"

    @property
    def memory_idle_flush_timeout(self) -> int:
        # Backward compatibility for existing call sites.
        return self.memory_idle_flush_timeout_second

    @property
    def memory_llm_timeout(self) -> float:
        # Backward compatibility for existing call sites.
        return self.memory_llm_timeout_second


def _build_headers_from_env(prefix: str) -> Optional[dict[str, Any]]:
    """
    从环境变量构建自定义 header 字典。

    token / userId 的 header 名必须通过 _HEADER 变量显式指定，不提供默认值。
    EXTRA_HEADERS 支持 JSON 格式注入任意额外 header。
    """
    headers: dict[str, Any] = {}

    token = os.getenv(f"{prefix}_TOKEN", "")
    if token:
        token = decrypt_config_value(token) or ""
        token_header = os.getenv(f"{prefix}_TOKEN_HEADER", "")
        if not token_header:
            raise ValueError(
                f"{prefix}_TOKEN is set but {prefix}_TOKEN_HEADER is missing. "
                f"Please set {prefix}_TOKEN_HEADER to the header name required by your gateway."
            )
        headers[token_header] = token

    user_id = os.getenv(f"{prefix}_USER_ID", "")
    if user_id:
        user_id_header = os.getenv(f"{prefix}_USER_ID_HEADER", "")
        if not user_id_header:
            raise ValueError(
                f"{prefix}_USER_ID is set but {prefix}_USER_ID_HEADER is missing. "
                f"Please set {prefix}_USER_ID_HEADER to the header name required by your gateway."
            )
        headers[user_id_header] = user_id

    extra_raw = os.getenv(f"{prefix}_EXTRA_HEADERS", "")
    if extra_raw:
        try:
            extra = json.loads(extra_raw)
            if isinstance(extra, dict):
                headers.update(extra)
            else:
                logger.warning(
                    "[DPA] %s_EXTRA_HEADERS must be a JSON object, skipping",
                    prefix,
                )
        except json.JSONDecodeError as e:
            logger.warning("[DPA] Invalid JSON in %s_EXTRA_HEADERS: %s, skipping", prefix, e)

    return headers or None


def _build_custom_headers() -> Optional[dict[str, Any]]:
    return _build_headers_from_env("PLANNING_AGENT_MODEL")


def _build_memory_llm_custom_headers() -> Optional[dict[str, Any]]:
    return _build_headers_from_env("DPA_MEMORY_LLM")


def _build_memory_embedding_custom_headers() -> Optional[dict[str, Any]]:
    return _build_headers_from_env("DPA_MEMORY_EMBEDDING")


def _infer_provider(api_base: str) -> str:
    """由 base_url 推导 provider。"""
    if "dashscope" in api_base or "aliyun" in api_base:
        return "DashScope"
    if "siliconflow" in api_base:
        return "SiliconFlow"
    return "OpenAI"


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        logger.warning("[DPA] Invalid int for %s=%r, fallback=%d", name, raw, default)
        return default


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return float(raw)
    except ValueError:
        logger.warning("[DPA] Invalid float for %s=%r, fallback=%s", name, raw, default)
        return default


def _load_memory_variables() -> list[dict[str, str]]:
    raw = os.getenv("DPA_MEMORY_VARIABLES_JSON", "")
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, list):
            normalized: list[dict[str, str]] = []
            for item in parsed:
                if isinstance(item, dict):
                    name = str(item.get("name", "")).strip()
                    description = str(item.get("description", "")).strip()
                    if name:
                        normalized.append({"name": name, "description": description})
            return normalized
    except json.JSONDecodeError as e:
        logger.warning("[DPA] Invalid JSON in DPA_MEMORY_VARIABLES_JSON: %s", e)
    return []


@lru_cache
def get_settings() -> DPASettings:
    """从环境变量构建 DPASettings。"""
    api_base = os.getenv("PLANNING_AGENT_MODEL_BASE_URL", "")

    raw_timeout = os.getenv("PLANNING_AGENT_MODEL_TIMEOUT", "120")
    try:
        timeout = float(raw_timeout)
    except ValueError:
        logger.warning(
            f"[DPA] PLANNING_AGENT_MODEL_TIMEOUT 非法值 raw={raw_timeout!r}，使用默认 120s"
        )
        timeout = 120.0

    raw_api_key = os.getenv("PLANNING_AGENT_MODEL_API_KEY", "")
    raw_redis_pwd = os.getenv("REDIS_PASSWORD", "")
    raw_memory_gauss_pwd = os.getenv("DPA_MEMORY_GAUSS_PASSWORD", "")
    raw_memory_embedding_key = os.getenv("DPA_MEMORY_EMBEDDING_API_KEY", "")
    if not raw_memory_embedding_key:
        raw_memory_embedding_key = os.getenv("DPA_MEMORY_EMBEDDING_TOKEN", "")
    if not raw_memory_embedding_key:
        raw_memory_embedding_key = os.getenv("PLANNING_AGENT_MODEL_API_KEY", "")
    raw_memory_llm_api_key = os.getenv("DPA_MEMORY_LLM_API_KEY", "")
    dpa_agent_id = os.getenv("DPA_AGENT_ID", "edp_agent")

    memory_llm_api_base = os.getenv("DPA_MEMORY_LLM_API_BASE", "")
    memory_llm_timeout_second = _env_float(
        "DPA_MEMORY_LLM_TIMEOUT_SECOND",
        _env_float("DPA_MEMORY_LLM_TIMEOUT", 120.0),
    )
    memory_llm_provider = os.getenv(
        "DPA_MEMORY_LLM_PROVIDER",
        _infer_provider(memory_llm_api_base) if memory_llm_api_base else "OpenAI",
    )

    return DPASettings(
        llm_provider=_infer_provider(api_base),
        llm_api_base=api_base,
        llm_api_key=decrypt_config_value(raw_api_key) if raw_api_key else "",
        llm_model_name=os.getenv("PLANNING_AGENT_MODEL_NAME", ""),
        llm_verify_ssl=os.getenv("SKILL_LLM_TLS_VERIFY", "false").lower() == "true",
        llm_timeout=timeout,
        custom_headers=_build_custom_headers(),
        redis_host=os.getenv("REDIS_HOST", "localhost"),
        redis_port=int(os.getenv("REDIS_PORT", "6379")),
        redis_db=int(os.getenv("REDIS_DB", "0")),
        redis_password=decrypt_config_value(raw_redis_pwd) if raw_redis_pwd else "",
        redis_checkpointer_ttl_minutes=int(
            os.getenv("REDIS_CHECKPOINTER_TTL_MINUTES", "60")
        ),
        dpa_agent_id=dpa_agent_id,
        dpa_agent_name=os.getenv("DPA_AGENT_NAME", "EDP Agent"),
        dpa_max_iterations=int(os.getenv("DPA_MAX_ITERATIONS", "30")),

        context_engine_enabled=_env_bool("DPA_CONTEXT_ENGINE_ENABLED", False),
        context_engine_max_context_message_num=_env_int(
            "DPA_CONTEXT_ENGINE_MAX_CONTEXT_MESSAGE_NUM", 200
        ),
        context_engine_default_window_round_num=_env_int(
            "DPA_CONTEXT_ENGINE_DEFAULT_WINDOW_ROUND_NUM", 10
        ),
        context_engine_enable_reload=_env_bool("DPA_CONTEXT_ENGINE_ENABLE_RELOAD", False),

        dialogue_compression_enabled=_env_bool("DPA_DIALOGUE_COMPRESSION_ENABLED", False),
        dialogue_compression_tokens_threshold=_env_int(
            "DPA_DIALOGUE_COMPRESSION_TOKENS_THRESHOLD", 10000
        ),
        dialogue_compression_target_tokens=_env_int(
            "DPA_DIALOGUE_COMPRESSION_TARGET_TOKENS", 1500
        ),
        dialogue_compression_keep_last_round=_env_bool(
            "DPA_DIALOGUE_COMPRESSION_KEEP_LAST_ROUND", False
        ),

        memory_enabled=_env_bool("DPA_MEMORY_ENABLED", False),
        memory_scope_id=dpa_agent_id,
        memory_write_interval=_env_int("DPA_MEMORY_WRITE_INTERVAL", 9999),
        memory_idle_flush_timeout_second=_env_int(
            "DPA_MEMORY_IDLE_FLUSH_TIMEOUT_SECOND",
            _env_int("DPA_MEMORY_IDLE_FLUSH_TIMEOUT", 600),
        ),
        memory_pending_flush_chars_threshold=_env_int(
            "DPA_MEMORY_PENDING_FLUSH_CHARS_THRESHOLD", 20000
        ),
        memory_enable_user_profile=_env_bool("DPA_MEMORY_ENABLE_USER_PROFILE", False),
        memory_enable_semantic_memory=_env_bool("DPA_MEMORY_ENABLE_SEMANTIC_MEMORY", False),
        memory_enable_episodic_memory=_env_bool("DPA_MEMORY_ENABLE_EPISODIC_MEMORY", False),
        memory_enable_summary_memory=_env_bool("DPA_MEMORY_ENABLE_SUMMARY_MEMORY", False),
        memory_variables=_load_memory_variables(),
        memory_gauss_host=os.getenv("DPA_MEMORY_GAUSS_HOST", "localhost"),
        memory_gauss_port=_env_int("DPA_MEMORY_GAUSS_PORT", 8000),
        memory_gauss_user=os.getenv("DPA_MEMORY_GAUSS_USER", "root"),
        memory_gauss_password=decrypt_config_value(raw_memory_gauss_pwd) if raw_memory_gauss_pwd else "",
        memory_gauss_database=os.getenv("DPA_MEMORY_GAUSS_DATABASE", "postgres"),
        memory_es_host=os.getenv("DPA_MEMORY_ELASTICSEARCH_HOST", "http://localhost:9200"),
        memory_es_user=os.getenv("DPA_MEMORY_ELASTICSEARCH_USER", ""),
        memory_es_password=os.getenv("DPA_MEMORY_ELASTICSEARCH_PASSWORD", ""),
        memory_embedding_model_name=os.getenv(
            "DPA_MEMORY_EMBEDDING_MODEL_NAME", "text-embedding-3-small"
        ),
        memory_embedding_api_base=os.getenv(
            "DPA_MEMORY_EMBEDDING_API_BASE", os.getenv("PLANNING_AGENT_MODEL_BASE_URL", "")
        ),
        memory_embedding_api_key=decrypt_config_value(raw_memory_embedding_key) if raw_memory_embedding_key else "",
        memory_embedding_custom_headers=_build_memory_embedding_custom_headers(),
        memory_llm_provider=memory_llm_provider,
        memory_llm_api_base=memory_llm_api_base,
        memory_llm_api_key=decrypt_config_value(raw_memory_llm_api_key) if raw_memory_llm_api_key else "",
        memory_llm_model_name=os.getenv("DPA_MEMORY_LLM_MODEL_NAME", ""),
        memory_llm_verify_ssl=_env_bool("DPA_MEMORY_LLM_VERIFY_SSL", False),
        memory_llm_timeout_second=memory_llm_timeout_second,
        memory_llm_custom_headers=_build_memory_llm_custom_headers(),

        sandbox_url=os.getenv("SANDBOX_URL", ""),
        skill_target_path=os.getenv("SKILL_TARGET_PATH", "/tmp") or "/tmp",

        # ── OpenTelemetry ───────────────────────────────────────────────
        # 环境变量控制（6 项）
        otel_enabled=_env_bool("OTEL_ENABLED", False),
        otel_exporter_endpoint=os.getenv("OTEL_EXPORTER_ENDPOINT", ""),
        otel_sample_rate=_env_float("OTEL_SAMPLE_RATE", 1.0),
        otel_exporter_type=os.getenv("OTEL_EXPORTER_TYPE", "otlp"),
        otel_redaction_enabled=_env_bool("OTEL_REDACTION_ENABLED", False),
        otel_max_attr_length=_env_int("OTEL_MAX_ATTR_LENGTH", 0),
        otel_service_name=os.getenv("OTEL_SERVICE_NAME", "edpagent"),
        otel_protocol=os.getenv("OTEL_PROTOCOL", "grpc"),
    )


# ════════════════════════════════════════════════════════════════════
# 并行调用新增：子 Agent 路由配置
# ════════════════════════════════════════════════════════════════════


class SubAgentEntry(BaseModel):
    """单个子 Agent 路由条目。"""
    entity_type: str = Field(default="default", description="实体类型标识")
    url: str = Field(default="", description="子 Agent A2A 端点 URL")
    name: str = Field(default="SubEDPAgent", description="子 Agent 名称")


class SubAgentsConfig(BaseModel):
    """子 Agent 路由配置（从 config/sub_agents.yaml 加载）。"""
    sub_agents: list[SubAgentEntry] = Field(default_factory=list, description="子 Agent 路由列表")


def load_sub_agents_config(config_path: str | Path | None = None) -> SubAgentsConfig:
    """从 config/sub_agents.yaml 加载子 Agent 路由配置。

    Args:
        config_path: 配置文件路径，默认为 agents/EDPAgent/config/sub_agents.yaml

    Returns:
        SubAgentsConfig 实例，文件不存在时返回空配置
    """
    import yaml

    if config_path is None:
        config_path = Path(__file__).resolve().parent / "config" / "sub_agents.yaml"
    else:
        config_path = Path(config_path)

    if not config_path.exists():
        logger.warning(f"[DPA] 子 Agent 路由配置文件不存在：{config_path}，使用空配置")
        return SubAgentsConfig()

    try:
        with open(config_path, "r", encoding="utf-8") as f:
            raw = yaml.safe_load(f) or {}
        config = SubAgentsConfig.model_validate(raw)
        logger.info(
            f"[DPA] 子 Agent 路由配置加载成功：{len(config.sub_agents)} 个子 Agent"
        )
        return config
    except Exception as e:
        logger.error(f"[DPA] 子 Agent 路由配置加载失败：{e}，使用空配置")
        return SubAgentsConfig()
