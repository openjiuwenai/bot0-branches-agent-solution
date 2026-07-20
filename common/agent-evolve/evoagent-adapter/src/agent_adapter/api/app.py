"""FastAPI application factory for the adapter HTTP API."""

import asyncio
from contextlib import asynccontextmanager
from pathlib import Path

import structlog
from fastapi import FastAPI

from agent_adapter.agent_client import AgentClient
from agent_adapter.config import AdapterConfig
from agent_adapter.kafka_consumer.consumer import TraceConsumer
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService
from agent_adapter.managed_doc.task import TaskRegistry
from agent_adapter.pipeline import Pipeline, create_pipeline_for_agent
from agent_adapter.repository.factory import make_repository
from agent_adapter.skill_store_factory import build_skill_store
from agent_adapter.trace_source.factory import make_trace_source

logger = structlog.get_logger(__name__)


def _build_pipelines(config: AdapterConfig) -> dict[str, Pipeline]:
    """Create a Pipeline per agent from the config's agents list.

    If the agents list is empty (e.g. config created directly without load_config),
    creates a single 'default' pipeline from top-level config fields for backward
    compatibility.

    Returns dict mapping agent_name → Pipeline.
    """
    if not config.agents:
        # Backward compat: no agents list → single default pipeline
        return {"default": Pipeline(config, agent_name="default")}

    pipelines: dict[str, Pipeline] = {}
    for agent_cfg in config.agents:
        pipelines[agent_cfg.name] = create_pipeline_for_agent(agent_cfg, config)
    return pipelines


def _output_dirs(config: AdapterConfig) -> dict[str, Path]:
    """log 模式 TraceSource 所需 agent_name → output_dir 映射。"""
    if config.agents:
        return {a.name: Path(a.output_dir) for a in config.agents if a.output_dir}
    return {"default": Path(config.output_dir)}


async def _start_trace_backend(app: FastAPI, config: AdapterConfig) -> None:
    """standard 模式: 起 repo (连接池+建表) + kafka 消费者 + DbTraceSource。

    log 模式的 LogTraceSource 已在 create_app 同步构建 (不依赖 lifespan), 此处无操作。
    repo 连接失败是致命的 (无法读轨迹); kafka 消费者启动失败仅告警 (API 读仍可用),
    兼顾生产鲁棒性与测试 (kafka 不可达时 app 仍启)。
    """
    if config.trace_source != "standard":
        return
    repo = make_repository(config)
    await repo.start()
    await repo.init_schema()
    app.state.repo = repo
    app.state.trace_source = make_trace_source(config, repo=repo)
    consumer = TraceConsumer(
        repo,
        brokers=config.kafka_brokers,
        topic=config.kafka_topic,
        group_id=config.kafka_group,
    )
    try:
        await consumer.start()
        app.state.consumer = consumer
        logger.info(
            "trace_source_standard_started",
            db=config.pg_db, topic=config.kafka_topic, group=config.kafka_group,
        )
    except Exception:
        logger.exception(
            "trace_consumer_start_failed_kafka_unreachable",
            brokers=config.kafka_brokers, topic=config.kafka_topic,
        )
        app.state.consumer = None


async def _stop_trace_backend(app: FastAPI) -> None:
    """shutdown: 停 kafka 消费者 + 关 repo 连接池 (standard 模式)。"""
    consumer = getattr(app.state, "consumer", None)
    if consumer is not None:
        await consumer.stop()
        app.state.consumer = None
    repo = getattr(app.state, "repo", None)
    if repo is not None:
        await repo.stop()
        app.state.repo = None


def _build_agent_clients(config: AdapterConfig) -> dict[str, AgentClient]:
    """Create AgentClient instances for agents that have agent_url configured.

    Returns dict mapping agent_name → AgentClient.
    Agents without agent_url are omitted (log-only, no call proxy).
    """
    clients: dict[str, AgentClient] = {}
    for agent_cfg in config.agents:
        if agent_cfg.agent_url and agent_cfg.project_id and agent_cfg.agent_id:
            clients[agent_cfg.name] = AgentClient(
                agent_url=agent_cfg.agent_url,
                project_id=agent_cfg.project_id,
                agent_id=agent_cfg.agent_id,
                timeout=agent_cfg.timeout,
                request_template=agent_cfg.request_template,
                extra_headers=agent_cfg.extra_headers,
                url_query_params=agent_cfg.url_query_params,
            )
    return clients


@asynccontextmanager
async def _lifespan(app: FastAPI):
    """Manage the background poll loop and cleanup timer alongside the HTTP server.

    In multi-agent mode, the poll loop iterates over all pipelines.
    The cleanup loop runs cleaners for all agents.
    """
    config: AdapterConfig = app.state.config
    pipelines: dict[str, Pipeline] = app.state.pipelines
    poll_interval = config.poll_interval

    async def _poll_loop() -> None:
        """Periodically poll all agent pipelines at the configured interval."""
        logger.info("poll_loop_started", poll_interval=poll_interval, agent_count=len(pipelines))
        while True:
            try:
                for name, pipeline in pipelines.items():
                    await pipeline.poll()
            except Exception:
                logger.exception("poll_loop_error")
            await asyncio.sleep(poll_interval)

    async def _cleanup_loop() -> None:
        """Periodically run output file cleanup for all agents."""
        from agent_adapter.cleaner import FileCleaner

        cleanup_interval = 60  # seconds — independent of poll_interval

        logger.info("cleanup_loop_started", cleanup_interval=cleanup_interval)
        while True:
            try:
                for agent_cfg in config.agents:
                    cleaner = FileCleaner(config, output_dir_override=agent_cfg.output_dir)
                    cleaner.run_all()
            except Exception:
                logger.exception("cleanup_loop_error")
            await asyncio.sleep(cleanup_interval)

    app.state.poll_task = asyncio.create_task(_poll_loop())
    app.state.cleanup_task = asyncio.create_task(_cleanup_loop())

    # trace backend (log 归档 / standard PG+kafka) —— routes 经 app.state.trace_source 读
    await _start_trace_backend(app, config)

    logger.info("adapter_http_server_started", agent_count=len(pipelines))
    yield
    # Shutdown: cancel the poll loop and cleanup timer
    for task_attr in ("poll_task", "cleanup_task"):
        task: asyncio.Task | None = getattr(app.state, task_attr, None)
        if task is not None and not task.done():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
    await _stop_trace_backend(app)
    # Managed-doc: cancel running apply tasks + await subprocess exit within grace
    managed_doc_service = getattr(app.state, "managed_doc_service", None)
    if managed_doc_service is not None:
        await managed_doc_service.shutdown()
    logger.info("adapter_http_server_stopped")


def create_app(config: AdapterConfig) -> FastAPI:
    """Create and configure the FastAPI application.

    In multi-agent mode, creates a Pipeline per agent and an AgentClient
    for each agent with agent_url configured. Shared state is stored on
    app.state for use by route handlers and the background poll loop.
    """
    app = FastAPI(title="Agent Adapter", version="0.2.0", lifespan=_lifespan)

    # Build per-agent pipelines and clients
    pipelines = _build_pipelines(config)
    agent_clients = _build_agent_clients(config)
    skill_store = build_skill_store(config)

    # Managed-doc service: registry validates restart configs at build time
    # (raises on misconfigured restart doc without health_url/agent_url — spec D4).
    managed_doc_registry = ManagedDocRegistry.from_config(config)
    managed_doc_task_registry = TaskRegistry(
        ttl_seconds=config.managed_doc_defaults.task_ttl_seconds
    )
    managed_doc_service = ManagedDocService(
        registry=managed_doc_registry,
        task_registry=managed_doc_task_registry,
        shutdown_grace_timeout=config.managed_doc_defaults.shutdown_grace_timeout,
    )

    # Attach shared state
    app.state.config = config
    app.state.pipelines = pipelines
    app.state.agent_clients = agent_clients
    app.state.skill_store = skill_store
    app.state.managed_doc_service = managed_doc_service
    app.state.pipeline = next(iter(pipelines.values())) if pipelines else None  # backward compat
    app.state.poll_task: asyncio.Task | None = None
    # log 模式 TraceSource 同步建 (不依赖 lifespan, 兼容不进 lifespan 的 TestClient);
    # standard 模式 DbTraceSource 由 lifespan 异步建 (需 repo 连接池)。
    app.state.trace_source = (
        make_trace_source(config, output_dirs=_output_dirs(config))
        if config.trace_source == "log" else None
    )
    app.state.repo = None  # standard 模式 TraceRepository (lifespan 填充)
    app.state.consumer = None  # standard 模式 kafka 消费者 (lifespan 填充, 可能为 None)
    app.state._config_lock = asyncio.Lock()  # protect YAML concurrent writes

    logger.info(
        "app_created",
        agents=list(pipelines.keys()),
        call_proxy_agents=list(agent_clients.keys()),
    )

    # Register routes
    from agent_adapter.api.routes import router

    app.include_router(router)

    return app
