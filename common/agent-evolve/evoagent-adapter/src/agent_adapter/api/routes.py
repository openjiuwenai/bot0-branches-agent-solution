"""HTTP API routes for the adapter service."""

import asyncio
import json
import time
from pathlib import Path

import structlog
import yaml
from fastapi import APIRouter, Query, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse

from agent_adapter.config import AgentEntryConfig
from agent_adapter.managed_doc.storage import AgentNotFoundError, DocNotFoundError
from agent_adapter.managed_doc.task import TaskNotFoundError
from agent_adapter.managed_doc.validation import InvalidDocContentError
from agent_adapter.schemas import (
    AgentCallRequest,
    ManagedDocActionRequest,
    ManagedDocListResponse,
    SkillActionRequest,
)
from agent_adapter.skill_store import (
    AgentNotFoundError as SkillAgentNotFoundError,
)
from agent_adapter.skill_store import (
    InvalidSkillNameError,
    SandboxUnavailableError,
    SkillNotFoundError,
    SkillStoreProtocol,
)

logger = structlog.get_logger(__name__)

router = APIRouter()


def _contract_error(code: str, message: str, status_code: int) -> JSONResponse:
    """Return adapter-api-contract unified error payload.

    Includes legacy ``detail`` alongside ``error`` so older AdapterClient
    parsers that only read FastAPI-style ``detail`` keep working.
    """
    return JSONResponse(
        status_code=status_code,
        content={
            "error": {"code": code, "message": message},
            "detail": message,
        },
    )


@router.get("/health")
async def health() -> dict[str, str]:
    """Return adapter health status."""
    return {"status": "ok"}


@router.post("/api/v1/agents/{agent_name}/conversations/{conversation_id}")
async def call_agent(
    agent_name: str,
    conversation_id: str,
    request: Request,
) -> Response:
    """Call a business Agent through the adapter proxy.

    默认聚合模式：消费完整 SSE 流后返回结构化 AgentCallResponse（JSON），
    供 evo_agent 的 AdapterClient.invoke 消费（httpx 默认 Accept: */*）。

    流式透传模式：客户端带 ``Accept: text/event-stream`` 时，把 edp_agent 的
    SSE 行原样转发（StreamingResponse），客户端实时看到中间事件，避免 VA 慢
    时聚合阻塞导致断连。
    """
    from pydantic import ValidationError

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return _contract_error("INVALID_ACTION", "Request body must be valid JSON", 400)

    try:
        call_request = AgentCallRequest.model_validate(body)
    except ValidationError as exc:
        errors = exc.errors()
        first = errors[0] if errors else {}
        loc = ".".join(str(part) for part in first.get("loc", ())) or "body"
        msg = first.get("msg", "Invalid request parameters")
        return _contract_error("INVALID_ACTION", f"{loc}: {msg}", 400)

    agent_clients: dict = request.app.state.agent_clients

    # Check if agent exists at all (in pipelines)
    pipelines: dict = request.app.state.pipelines
    if agent_name not in pipelines:
        return _contract_error("AGENT_NOT_FOUND", f"Agent '{agent_name}' 不存在", 404)

    # Check if agent has call proxy enabled (agent_url configured)
    if agent_name not in agent_clients:
        return _contract_error(
            "INVALID_ACTION",
            f"Agent '{agent_name}' 未配置 agent_url，不支持调用中转",
            400,
        )

    client = agent_clients[agent_name]

    # 流式透传：客户端显式要 SSE 时，原行转发，不聚合
    accept = request.headers.get("accept", "")
    if "text/event-stream" in accept:
        return StreamingResponse(
            client.iter_sse_stream(
                conversation_id=conversation_id,
                query=call_request.query,
                extra_data=call_request.extra_data,
            ),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    # 默认聚合模式：消费完整 SSE 流后返回 JSON（evo_agent 契约）
    response = await client.call(
        conversation_id=conversation_id,
        query=call_request.query,
        extra_data=call_request.extra_data,
    )

    return JSONResponse(content=response.model_dump())


@router.post("/api/v1/skills")
async def skills_action(request: Request) -> JSONResponse:
    """Skill list / content / update (local FS or jiuwenbox backend)."""
    from pydantic import ValidationError

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return _contract_error("INVALID_ACTION", "Request body must be valid JSON", 400)

    try:
        skill_request = SkillActionRequest.model_validate(body)
    except ValidationError as exc:
        errors = exc.errors()
        if any(err.get("loc") == ("action",) for err in errors):
            return _contract_error("INVALID_ACTION", "Unrecognized or invalid action", 400)
        first = errors[0] if errors else {}
        message = first.get("msg", "Invalid request parameters")
        return _contract_error("INVALID_ACTION", str(message), 400)

    skill_store: SkillStoreProtocol | None = getattr(request.app.state, "skill_store", None)
    if skill_store is None:
        return _contract_error("INTERNAL_ERROR", "Skill store is not initialized", 500)

    agent_name = skill_request.agent_name
    action = skill_request.action

    try:
        if action == "skill_list":
            summaries = skill_store.list_skills(agent_name)
            return JSONResponse(
                content={"skills": [{"name": s.name} for s in summaries]},
            )

        if action == "skill_content":
            assert skill_request.skill_name is not None
            doc = skill_store.read_skill(agent_name, skill_request.skill_name)
            return JSONResponse(
                content={"skill_name": doc.skill_name, "content": doc.content},
            )

        if action == "restore_skill":
            assert skill_request.skill_names is not None
            restored = skill_store.restore_skills(agent_name, skill_request.skill_names)
            return JSONResponse(
                content={
                    "restored": [
                        {
                            "skill_name": item.skill_name,
                            "success": item.success,
                            **({"message": item.message} if item.message else {}),
                        }
                        for item in restored
                    ],
                },
            )

        if action == "update_skill":
            assert skill_request.skill_name is not None
            assert skill_request.skill_content is not None
            result = skill_store.update_skill(
                agent_name,
                skill_request.skill_name,
                skill_request.skill_content,
            )
            payload: dict = {
                "success": result.success,
                "skill_name": result.skill_name,
                "revision": result.revision,
            }
            if result.message:
                payload["message"] = result.message
            return JSONResponse(content=payload)

        return _contract_error("INVALID_ACTION", "Unrecognized or invalid action", 400)

    except SkillAgentNotFoundError as exc:
        return _contract_error("AGENT_NOT_FOUND", str(exc), 404)
    except SkillNotFoundError as exc:
        return _contract_error("SKILL_NOT_FOUND", str(exc), 404)
    except SandboxUnavailableError as exc:
        # Map to INTERNAL_ERROR 500 (not SKILL_NOT_FOUND) so callers can
        # distinguish sandbox/downstream failure from a missing skill file.
        return _contract_error("INTERNAL_ERROR", str(exc), 500)
    except InvalidSkillNameError as exc:
        return _contract_error("INVALID_ACTION", str(exc), 400)
    except Exception:
        logger.exception("skills_action_error", agent_name=agent_name, action=action)
        return _contract_error("INTERNAL_ERROR", "Failed to process skill request", 500)


@router.post("/api/v1/managed-docs")
async def managed_docs_action(request: Request) -> JSONResponse:
    """managed-doc content / update / restore (adapter-api-contract managed-doc §7)."""
    from pydantic import ValidationError

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return _contract_error("INVALID_ACTION", "Request body must be valid JSON", 400)

    try:
        doc_request = ManagedDocActionRequest.model_validate(body)
    except ValidationError as exc:
        errors = exc.errors()
        if any(err.get("loc") == ("action",) for err in errors):
            return _contract_error("INVALID_ACTION", "Unrecognized or invalid action", 400)
        first = errors[0] if errors else {}
        message = first.get("msg", "Invalid request parameters")
        return _contract_error("INVALID_ACTION", str(message), 400)

    service = getattr(request.app.state, "managed_doc_service", None)
    if service is None:
        return _contract_error("INTERNAL_ERROR", "Managed-doc service is not initialized", 500)

    agent_name = doc_request.agent_name
    doc_kind = doc_request.doc_kind
    action = doc_request.action

    try:
        if action == "content":
            result = service.content(agent_name, doc_kind)
            return JSONResponse(content=result)

        if action == "update":
            result = await service.update(agent_name, doc_kind, doc_request.content or "")
            status_code = 202 if "task_id" in result else 200
            return JSONResponse(content=result, status_code=status_code)

        if action == "restore":
            result = await service.restore(agent_name, doc_kind)
            return JSONResponse(content=result, status_code=202)

        # 未知 action（Literal 已拦，理论上不可达）
        return _contract_error(
            "INVALID_ACTION",
            f"action '{action}' is not yet supported",
            400,
        )

    except AgentNotFoundError as exc:
        return _contract_error("AGENT_NOT_FOUND", str(exc), 404)
    except DocNotFoundError as exc:
        return _contract_error("DOC_NOT_FOUND", str(exc), 404)
    except InvalidDocContentError as exc:
        return _contract_error("INVALID_ACTION", str(exc), 400)
    except Exception:
        logger.exception(
            "managed_docs_action_error",
            agent_name=agent_name,
            doc_kind=doc_kind,
            action=action,
        )
        return _contract_error("INTERNAL_ERROR", "Failed to process managed-doc request", 500)


@router.get(
    "/api/v1/agents/{agent_name}/managed-docs",
    response_model=ManagedDocListResponse,
)
async def list_managed_docs(agent_name: str, request: Request) -> JSONResponse:
    """List the Agent's registered managed-document optimization capabilities."""
    service = getattr(request.app.state, "managed_doc_service", None)
    if service is None:
        return _contract_error("INTERNAL_ERROR", "Managed-doc service is not initialized", 500)
    try:
        result = ManagedDocListResponse.model_validate(service.list_documents(agent_name))
        return JSONResponse(content=result.model_dump())
    except AgentNotFoundError as exc:
        return _contract_error("AGENT_NOT_FOUND", str(exc), 404)
    except Exception:
        logger.exception("managed_doc_listing_error", agent_name=agent_name)
        return _contract_error("INTERNAL_ERROR", "Failed to list managed-docs", 500)


@router.get("/api/v1/managed-docs/tasks/{task_id}")
async def get_managed_doc_task(task_id: str, request: Request) -> JSONResponse:
    """Poll an async managed-doc apply task (spec §7.3)."""
    service = getattr(request.app.state, "managed_doc_service", None)
    if service is None:
        return _contract_error("INTERNAL_ERROR", "Managed-doc service is not initialized", 500)
    try:
        state = service.get_task(task_id)
        return JSONResponse(content=state.to_dict())
    except TaskNotFoundError as exc:
        return _contract_error("TASK_NOT_FOUND", str(exc), 404)
    except Exception:
        logger.exception("managed_doc_task_poll_error", task_id=task_id)
        return _contract_error("INTERNAL_ERROR", "Failed to poll managed-doc task", 500)


# ── Per-Agent Traces ──────────────────────────────────────────────────
# 轨迹读取经 app.state.trace_source (log 读归档 / standard 读 PG), 不再直接读归档。
# 三个 API 契约 (路径/响应结构) 不变; /traces/{conv} 附加 complete 信号 (设计文档 §7)。


def _trace_source(request: Request):
    """获取 lifespan 注入的 TraceSource (log | standard)。"""
    return request.app.state.trace_source


def _filter_complete(records: list[dict], complete: bool | None) -> list[dict]:
    """按 _incomplete 标记过滤 (log 模式 trace_assembler 产出的完整性标记)。"""
    if complete is None:
        return records
    return [r for r in records if r.get("_incomplete", False) != complete]


async def _await_root_span(repo, conversation_id: str, timeout: float) -> bool:
    """standard 模式: 轮询 repo.get_root_span 至 timeout。

    根 span (kind=SERVER, parent 空) 到达且 end_time 已设 → True (会话完整)。
    处理 5s 上报 + kafka 异步的时序差 (设计文档 §7 A+D)。
    """
    deadline = time.monotonic() + timeout
    while True:
        root = await repo.get_root_span(conversation_id)
        if root is not None and root.get("end_time"):
            return True
        if time.monotonic() >= deadline:
            return False
        await asyncio.sleep(0.5)


@router.get("/api/v1/agents/{agent_name}/traces")
async def list_agent_traces(agent_name: str, request: Request) -> dict:
    """List conversation IDs for a specific agent's traces.

    Triggers an incremental poll for the specified agent pipeline before listing.
    Returns 404 if the agent name does not exist.
    """
    pipelines: dict = request.app.state.pipelines

    if agent_name not in pipelines:
        return JSONResponse(
            status_code=404,
            content={"detail": f"Agent '{agent_name}' 不存在"},
        )

    await pipelines[agent_name].poll()
    ids = await _trace_source(request).list_conversations(agent_name)
    return {"conversation_ids": ids, "total": len(ids)}


@router.get("/api/v1/agents/{agent_name}/traces/{conversation_id}")
async def get_agent_traces(
    agent_name: str,
    conversation_id: str,
    request: Request,
    complete: bool | None = Query(default=None),
    limit: int | None = Query(default=None, ge=1),
) -> dict:
    """Return trace/observation records for a specific agent and conversation.

    Triggers an incremental poll for the specified agent pipeline before querying.
    Returns 404 if the agent name does not exist.
    """
    pipelines: dict = request.app.state.pipelines

    if agent_name not in pipelines:
        return JSONResponse(
            status_code=404,
            content={"detail": f"Agent '{agent_name}' 不存在"},
        )

    await pipelines[agent_name].poll()
    records = await _trace_source(request).get_records(agent_name, conversation_id)
    records = _filter_complete(records, complete)

    total = len(records)
    if limit is not None:
        records = records[:limit]

    return {"conversation_id": conversation_id, "calls": records, "total": total}


@router.get("/api/v1/traces")
async def list_traces(request: Request) -> dict:
    """List all conversation IDs (multi-agent: 聚合全部 agent)。

    Triggers an incremental poll for each agent pipeline before listing.
    """
    pipelines: dict = request.app.state.pipelines
    for pipeline in pipelines.values():
        await pipeline.poll()

    ids = await _trace_source(request).list_conversations(None)
    return {"conversation_ids": ids, "total": len(ids)}


@router.get("/api/v1/traces/{conversation_id}")
async def get_traces(
    conversation_id: str,
    request: Request,
    complete: bool | None = Query(default=None),
    limit: int | None = Query(default=None, ge=1),
) -> dict:
    """Return trace records for a conversation + complete 信号 (设计文档 §7)。

    standard 模式: 先轮询 PG 等根 span 到达 (至 trace_wait_timeout), 再取 spans;
    complete = 根 span 到达且 end_time 已设。log 模式: complete = 无 _incomplete 记录。
    Triggers an incremental poll for each agent pipeline before querying.
    """
    pipelines: dict = request.app.state.pipelines
    for pipeline in pipelines.values():
        await pipeline.poll()

    config = request.app.state.config
    repo = getattr(request.app.state, "repo", None)
    trace_source = _trace_source(request)

    if repo is not None:  # standard: 先等根 span, 再取 spans
        complete_signal = await _await_root_span(repo, conversation_id, config.trace_wait_timeout)
        records = await trace_source.get_records(None, conversation_id)
    else:  # log: 取归档, complete 按完整性标记
        records = await trace_source.get_records(None, conversation_id)
        complete_signal = not any(r.get("_incomplete", False) for r in records)

    records = _filter_complete(records, complete)

    total = len(records)
    if limit is not None:
        records = records[:limit]

    return {
        "conversation_id": conversation_id,
        "calls": records,
        "total": total,
        "complete": complete_signal,
    }


@router.get("/api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}")
async def get_cleaned_traces(
    agent_name: str,
    conversation_id: str,
    request: Request,
) -> dict:
    """Return cleaned LLM conversation for a specific agent and conversation.

    Reads records via TraceSource, finds the last GENERATION record, extracts
    and filters messages, returns structured result with task_input/trajectory.
    """
    pipelines: dict = request.app.state.pipelines

    if agent_name not in pipelines:
        return JSONResponse(
            status_code=404,
            content={"detail": f"Agent '{agent_name}' 不存在"},
        )

    await pipelines[agent_name].poll()
    records = await _trace_source(request).get_records(agent_name, conversation_id)

    from agent_adapter.trace_cleaner import clean_traces

    return clean_traces(records, session_id=conversation_id, agent_name=agent_name)


@router.get("/api/v1/status")
async def get_status(request: Request) -> dict:
    """Return adapter runtime status.

    In multi-agent mode, returns per-agent status under an 'agents' key.
    """
    pipelines: dict = request.app.state.pipelines

    if len(pipelines) == 1:
        # Single-agent mode: return flat status for backward compat
        pipeline = next(iter(pipelines.values()))
        return pipeline.get_status()

    # Multi-agent mode: return per-agent status
    agents_status: dict[str, dict] = {}
    for name, pipeline in pipelines.items():
        agents_status[name] = pipeline.get_status()

    return {"agents": agents_status}


# ── Config CRUD + Hot Reload ──────────────────────────────────────────


def _get_yaml_path(request: Request) -> Path:
    """Get the YAML config file path from app state."""
    config_path = getattr(request.app.state.config, "_yaml_path", None)
    if config_path:
        return Path(config_path)
    # Fallback: look for config.yaml in adapter root
    from agent_adapter.config import _ADAPTER_ROOT

    return _ADAPTER_ROOT / "agent_adapter_config.yaml"


def _read_yaml(yaml_path: Path) -> dict:
    """Read the YAML config file."""
    if not yaml_path.exists():
        return {}
    with open(yaml_path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def _write_yaml(yaml_path: Path, data: dict) -> None:
    """Write the YAML config file atomically."""
    yaml_path.parent.mkdir(parents=True, exist_ok=True)
    with open(yaml_path, encoding="utf-8") as f:
        pass  # ensure file exists
    with open(yaml_path, "w", encoding="utf-8") as f:
        yaml.dump(data, f, default_flow_style=False, allow_unicode=True, sort_keys=False)


def _agent_entry_to_dict(agent: AgentEntryConfig) -> dict:
    """Convert AgentEntryConfig to a dict for YAML serialization, omitting None values."""
    result: dict = {}
    data = agent.model_dump()
    for key, value in data.items():
        if value is not None:
            result[key] = value
    return result


def _rebuild_pipeline_for_agent(
    app,
    agent_cfg: AgentEntryConfig,
) -> None:
    """Create a Pipeline for the given agent and add it to app.state.pipelines."""
    from agent_adapter.pipeline import create_pipeline_for_agent

    pipeline = create_pipeline_for_agent(agent_cfg, app.state.config)
    app.state.pipelines[agent_cfg.name] = pipeline
    # Also update backward-compat alias if only one agent
    if len(app.state.pipelines) == 1:
        app.state.pipeline = pipeline


def _rebuild_agent_client_for_agent(
    app,
    agent_cfg: AgentEntryConfig,
) -> None:
    """Create an AgentClient for the given agent (if agent_url configured)."""
    from agent_adapter.agent_client import AgentClient

    if agent_cfg.agent_url and agent_cfg.project_id and agent_cfg.agent_id:
        app.state.agent_clients[agent_cfg.name] = AgentClient(
            agent_url=agent_cfg.agent_url,
            project_id=agent_cfg.project_id,
            agent_id=agent_cfg.agent_id,
            timeout=agent_cfg.timeout,
            request_template=agent_cfg.request_template,
            extra_headers=agent_cfg.extra_headers,
            url_query_params=agent_cfg.url_query_params,
        )
    else:
        # Remove client if URL was removed
        app.state.agent_clients.pop(agent_cfg.name, None)


@router.get("/api/v1/config/agents")
async def list_config_agents(request: Request) -> dict:
    """List all configured agent entries."""
    agents: list[dict] = []
    for agent_cfg in request.app.state.config.agents:
        agents.append(_agent_entry_to_dict(agent_cfg))
    return {"agents": agents}


@router.get("/api/v1/config/agents/{name}")
async def get_config_agent(name: str, request: Request) -> JSONResponse:
    """Get the configuration for a specific agent."""
    for agent_cfg in request.app.state.config.agents:
        if agent_cfg.name == name:
            return JSONResponse(content=_agent_entry_to_dict(agent_cfg))
    return JSONResponse(
        status_code=404,
        content={"detail": f"Agent '{name}' 不存在"},
    )


@router.post("/api/v1/config/agents")
async def create_config_agent(request: Request) -> JSONResponse:
    """Add a new agent configuration with hot reload."""
    body = await request.json()

    # Validate with pydantic
    try:
        new_agent = AgentEntryConfig(**body)
    except Exception as exc:
        return JSONResponse(
            status_code=422,
            content={"detail": f"无效的 Agent 配置: {exc}"},
        )

    # Check for duplicate name
    existing_names = {a.name for a in request.app.state.config.agents}
    if new_agent.name in existing_names:
        return JSONResponse(
            status_code=409,
            content={"detail": f"Agent '{new_agent.name}' 已存在"},
        )

    # Fill defaults
    if new_agent.log_pattern is None:
        new_agent = new_agent.model_copy(
            update={"log_pattern": request.app.state.config.log_pattern}
        )
    if new_agent.output_dir is None:
        new_agent = new_agent.model_copy(update={"output_dir": f"data/output/{new_agent.name}"})
    if new_agent.offset_file is None:
        new_agent = new_agent.model_copy(
            update={"offset_file": f"data/offsets/{new_agent.name}.json"}
        )
    if new_agent.skills_dir is None:
        skills_root = request.app.state.config.skills_root
        new_agent = new_agent.model_copy(update={"skills_dir": f"{skills_root}/{new_agent.name}"})

    # Write YAML
    yaml_path = _get_yaml_path(request)
    async with getattr(request.app.state, "_config_lock", asyncio.Lock()) as lock:
        yaml_data = _read_yaml(yaml_path)
        agents_list = yaml_data.get("agents", [])
        agents_list.append(_agent_entry_to_dict(new_agent))
        yaml_data["agents"] = agents_list
        _write_yaml(yaml_path, yaml_data)

    # Update in-memory config
    updated_agents = list(request.app.state.config.agents) + [new_agent]
    request.app.state.config = request.app.state.config.model_copy(
        update={"agents": updated_agents}
    )

    # Hot reload: create Pipeline + AgentClient
    _rebuild_pipeline_for_agent(request.app, new_agent)
    _rebuild_agent_client_for_agent(request.app, new_agent)

    # Create output_dir and skills_dir
    output_dir = Path(new_agent.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    skills_dir = Path(new_agent.skills_dir)
    skills_dir.mkdir(parents=True, exist_ok=True)

    logger.info("config_agent_created", name=new_agent.name)
    return JSONResponse(content=_agent_entry_to_dict(new_agent))


@router.put("/api/v1/config/agents/{name}")
async def update_config_agent(name: str, request: Request) -> JSONResponse:
    """Modify an existing agent configuration with hot reload."""
    body = await request.json()

    # Validate with pydantic
    try:
        updated_agent = AgentEntryConfig(**body)
    except Exception as exc:
        return JSONResponse(
            status_code=422,
            content={"detail": f"无效的 Agent 配置: {exc}"},
        )

    # Check agent exists
    existing_names = {a.name for a in request.app.state.config.agents}
    if name not in existing_names:
        return JSONResponse(
            status_code=404,
            content={"detail": f"Agent '{name}' 不存在"},
        )

    # Fill defaults
    if updated_agent.log_pattern is None:
        updated_agent = updated_agent.model_copy(
            update={"log_pattern": request.app.state.config.log_pattern}
        )
    if updated_agent.output_dir is None:
        updated_agent = updated_agent.model_copy(
            update={"output_dir": f"data/output/{updated_agent.name}"}
        )
    if updated_agent.offset_file is None:
        updated_agent = updated_agent.model_copy(
            update={"offset_file": f"data/offsets/{updated_agent.name}.json"}
        )

    # Write YAML
    yaml_path = _get_yaml_path(request)
    async with getattr(request.app.state, "_config_lock", asyncio.Lock()) as lock:
        yaml_data = _read_yaml(yaml_path)
        agents_list = yaml_data.get("agents", [])
        for i, a in enumerate(agents_list):
            if a.get("name") == name:
                agents_list[i] = _agent_entry_to_dict(updated_agent)
                break
        yaml_data["agents"] = agents_list
        _write_yaml(yaml_path, yaml_data)

    # Update in-memory config
    updated_agents = [
        updated_agent if a.name == name else a for a in request.app.state.config.agents
    ]
    request.app.state.config = request.app.state.config.model_copy(
        update={"agents": updated_agents}
    )

    # Hot reload: rebuild Pipeline + AgentClient
    _rebuild_pipeline_for_agent(request.app, updated_agent)
    _rebuild_agent_client_for_agent(request.app, updated_agent)

    # Create output_dir
    output_dir = Path(updated_agent.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    logger.info("config_agent_updated", name=name)
    return JSONResponse(content=_agent_entry_to_dict(updated_agent))


@router.delete("/api/v1/config/agents/{name}")
async def delete_config_agent(name: str, request: Request) -> JSONResponse:
    """Delete an agent configuration with hot reload."""
    # Check agent exists
    existing_names = {a.name for a in request.app.state.config.agents}
    if name not in existing_names:
        return JSONResponse(
            status_code=404,
            content={"detail": f"Agent '{name}' 不存在"},
        )

    # Write YAML
    yaml_path = _get_yaml_path(request)
    async with getattr(request.app.state, "_config_lock", asyncio.Lock()) as lock:
        yaml_data = _read_yaml(yaml_path)
        agents_list = yaml_data.get("agents", [])
        yaml_data["agents"] = [a for a in agents_list if a.get("name") != name]
        _write_yaml(yaml_path, yaml_data)

    # Update in-memory config
    updated_agents = [a for a in request.app.state.config.agents if a.name != name]
    request.app.state.config = request.app.state.config.model_copy(
        update={"agents": updated_agents}
    )

    # Hot reload: remove Pipeline + AgentClient
    request.app.state.pipelines.pop(name, None)
    request.app.state.agent_clients.pop(name, None)

    # Update backward-compat alias
    if request.app.state.pipelines:
        request.app.state.pipeline = next(iter(request.app.state.pipelines.values()))
    else:
        request.app.state.pipeline = None

    logger.info("config_agent_deleted", name=name)
    return JSONResponse(content={"deleted": name})
