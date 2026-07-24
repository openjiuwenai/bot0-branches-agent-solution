"""LongTermMemory global singleton bootstrap for EDPAgent."""
from __future__ import annotations

from loguru import logger

from .config import DPASettings

_memory_engine = None


def get_memory_engine():
    if _memory_engine is None:
        raise RuntimeError("Memory engine not initialized. Call await init_memory_engine(settings) first.")
    return _memory_engine


async def init_memory_engine(settings: DPASettings) -> None:
    from redis.asyncio import Redis
    from elasticsearch import AsyncElasticsearch
    from sqlalchemy.ext.asyncio import create_async_engine

    from openjiuwen.core.foundation.llm.schema.config import ModelClientConfig, ModelRequestConfig
    from openjiuwen.core.memory.config.config import EmbeddingConfig, MemoryEngineConfig, MemoryScopeConfig
    from openjiuwen.core.memory.long_term_memory import LongTermMemory
    from openjiuwen.extensions.store.db.gauss_db_store import GaussDbStore
    from openjiuwen.extensions.store.kv.redis_store import RedisStore
    from openjiuwen.extensions.store.vector.es_vector_store import ElasticsearchVectorStore

    global _memory_engine
    if _memory_engine is not None:
        logger.info("[DPA][MEMORY] Memory engine already initialized, skip")
        return

    memory = LongTermMemory()

    effective_memory_model = settings.memory_llm_model_name or settings.llm_model_name
    effective_memory_api_base = settings.memory_llm_api_base or settings.llm_api_base
    effective_memory_api_key = settings.memory_llm_api_key or settings.llm_api_key
    effective_memory_provider = settings.memory_llm_provider or settings.llm_provider
    effective_memory_verify_ssl = settings.memory_llm_verify_ssl
    effective_memory_timeout = settings.memory_llm_timeout_second or settings.llm_timeout

    if not settings.memory_llm_model_name:
        logger.warning("Memory LLM model not configured, fallback to PLANNING_AGENT_MODEL_NAME")
    if not settings.memory_llm_api_base:
        logger.warning("Memory LLM api_base not configured, fallback to PLANNING_AGENT_MODEL_BASE_URL")
    if not settings.memory_llm_api_key:
        logger.warning("Memory LLM api_key not configured, fallback to PLANNING_AGENT_MODEL_API_KEY")

    logger.info(
        "[DPA][MEMORY] Resolved memory LLM config: provider={}, model={}, api_base={}, timeout={}, verify_ssl={}",
        effective_memory_provider,
        effective_memory_model,
        effective_memory_api_base,
        effective_memory_timeout,
        effective_memory_verify_ssl,
    )

    redis_client = Redis.from_url(
        settings.redis_url,
        decode_responses=True,
        protocol=2,
        socket_connect_timeout=5,
        socket_timeout=10,
        retry_on_timeout=True,
    )
    kv_store = RedisStore(redis=redis_client)

    db_url = (
        f"gaussdb+async_gaussdb://{settings.memory_gauss_user}:{settings.memory_gauss_password}"
        f"@{settings.memory_gauss_host}:{settings.memory_gauss_port}/{settings.memory_gauss_database}"
    )
    db_engine = create_async_engine(db_url, pool_pre_ping=True, query_cache_size=0)
    db_store = GaussDbStore(async_conn=db_engine)

    es_client = AsyncElasticsearch(
        hosts=[settings.memory_es_host],
        basic_auth=(settings.memory_es_user, settings.memory_es_password)
        if settings.memory_es_user else None,
    )
    vector_store = ElasticsearchVectorStore(es=es_client)

    await memory.register_store(kv_store=kv_store, vector_store=vector_store, db_store=db_store)

    memory.set_config(
        MemoryEngineConfig(
            default_model_cfg=ModelRequestConfig(model=effective_memory_model),
            default_model_client_cfg=ModelClientConfig(
                client_provider=effective_memory_provider,
                api_key=effective_memory_api_key,
                api_base=effective_memory_api_base,
                verify_ssl=effective_memory_verify_ssl,
                timeout=effective_memory_timeout,
                custom_headers=(
                    settings.memory_llm_custom_headers or settings.custom_headers
                ),
            ),
        )
    )

    scope_cfg = MemoryScopeConfig(
        embedding_cfg=EmbeddingConfig(
            model_name=settings.memory_embedding_model_name,
            base_url=settings.memory_embedding_api_base,
            api_key=settings.memory_embedding_api_key,
        ),
    )
    if settings.memory_embedding_custom_headers:
        logger.warning(
            "[DPA][MEMORY] DPA_MEMORY_EMBEDDING_* custom headers detected ({}), "
            "but current SDK EmbeddingConfig only applies api_key; non-api-key headers are ignored",
            list(settings.memory_embedding_custom_headers.keys()),
        )
    await memory.set_scope_config(settings.memory_scope_id, scope_cfg)

    _memory_engine = memory
    logger.info(
        "[DPA][MEMORY] Memory engine initialized: scope_id={}, gauss={}:{}/{}, elasticsearch={}",
        settings.memory_scope_id,
        settings.memory_gauss_host,
        settings.memory_gauss_port,
        settings.memory_gauss_database,
        settings.memory_es_host,
    )
