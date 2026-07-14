"""FastAPI application factory for the adapter HTTP API."""

import asyncio
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI

from agent_adapter.agent_client import AgentClient
from agent_adapter.config import AdapterConfig
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService
from agent_adapter.managed_doc.task import TaskRegistry
from agent_adapter.pipeline import Pipeline, create_pipeline_for_agent
from agent_adapter.skill_store_factory import build_skill_store

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
