"""golden_data 路由 — 在线产 EB（同步）+ 离线建 GU（异步 job + SSE）。

- ``POST /golden_data/expected-behavior``：在线产 EB —— 内联 messages + LLMConfig，
  载入持久化 GU 经 ``route_skill`` 路由切片 → 产 ``{id, inputs, expected_behavior}``。
  照 ``routes/evaluate.py`` 的 ``/generate-goal`` 形态（同步 + ``asyncio.to_thread``）。
- ``POST /golden_data/global-understanding``：multipart 上传一批 trace + config，
  提交建 GU job（后台 ``builder.build``），返回 ``job_id``。
  照 ``routes/evaluate_dataset.py`` 形态（``job_manager.submit`` + background task
  + ``GET /jobs/{id}`` 查询 + ``/jobs/{id}/stream`` SSE）。

skill 源：当前支持 ``local``（``skill_root`` 本地目录）；``adapter`` 源需 async with
AdapterClient 生命周期管理，下一步实现（路由层暂 501）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import traceback
from collections.abc import AsyncGenerator
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from evo_agent.api.jobs import JobStatus, job_manager
from evo_agent.api.sse import format_sse
from evo_agent.config import EvolveConfig
from evo_agent.evaluator.domain.models import StandardTrajectory, TrajectoryMessage
from evo_agent.evaluator.golden_data.builder import GlobalUnderstandingBuilder
from evo_agent.evaluator.golden_data.generator import ExpectedBehaviorGenerator
from evo_agent.evaluator.golden_data.models import EBInput, GUSlice
from evo_agent.evaluator.golden_data.skill_provider import LocalSkillProvider

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/golden_data", tags=["golden-data"])

# 上传体量上限（整段读入内存解析）。100 MB。
_MAX_UPLOAD_BYTES = 100 * 1024 * 1024

_TRAJECTORY_MESSAGE_FIELDS = {
    "role",
    "content",
    "name",
    "tool_calls",
    "tool_call_id",
    "reasoning_content",
    "metadata",
}


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------


class LLMConfig(BaseModel):
    """LLM 配置 — 可选；缺省时路由读 ``EvolveConfig``（``evoagent/.env`` 的 ``EVO_LLM_*``）。"""

    model_name: str = ""
    api_key: str
    api_base: str
    client_provider: str = "OpenAI"
    temperature: float = 0.1
    max_tokens: int = 2048
    verify_ssl: bool = False
    extra_body: dict[str, Any] | None = None


class GenerateEBRequest(BaseModel):
    """在线产 EB 请求体 —— 内联轨迹 messages。"""

    messages: list[dict[str, Any]]
    llm_config: LLMConfig | None = None
    attributed_skill: str | None = None


class GenerateEBResponse(BaseModel):
    """在线产 EB 响应 —— 对外口径 ``{id, inputs, expected_behavior}``。

    ``internal`` 仅备查（result/reason/scenario/scope/score），不进 optimizer——
    ``dataset/case.py`` 只读 ``items[*].expected_behavior``，不读 ``internal``。
    """

    status: str = "generated"
    items: list[dict[str, str]] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)
    internal: dict[str, Any] = Field(default_factory=dict)


class BuildGUConfig(BaseModel):
    """建 GU 请求配置（multipart 的 ``config`` 字段，JSON blob）。"""

    source: Literal["local", "adapter"] = "local"
    skill_root: str = ""  # local 源必填：本地 skill 目录根
    adapter_url: str = ""  # adapter 源必填（下一步实现）
    agent_name: str = ""
    llm_config: LLMConfig | None = None
    flat_threshold: int = 30
    batch_size: int = 10
    skill_names: list[str] = Field(default_factory=list)  # 空 → 用 skill_provider.list_skills


class BuildGUSubmitResponse(BaseModel):
    """建 GU 提交响应。"""

    job_id: str
    status: str


class JobResponse(BaseModel):
    """任务状态响应。"""

    job_id: str
    status: str
    progress: dict[str, Any] | None = None
    result: dict[str, Any] | None = None
    error: str | None = None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_model_configs(
    llm_config: LLMConfig | None = None,
) -> tuple[Any, Any]:
    """构建 ``(ModelRequestConfig, ModelClientConfig)``。

    ``llm_config`` 非空时用请求体配置；为 ``None`` 时 fallback 读 ``EvolveConfig``
    （``evoagent/.env`` 的 ``EVO_LLM_*``；CustomSSE 模式读 ``EVO_CUSTOM_SSE_*``）。
    ``import evo_agent.llm`` 触发 CustomSSE provider 注册（幂等）。
    """
    from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

    import evo_agent.llm  # noqa: F401 — 注册 CustomSSE provider（幂等）

    if llm_config is not None:
        client_config = ModelClientConfig(
            client_provider=llm_config.client_provider,
            api_key=llm_config.api_key,
            api_base=llm_config.api_base,
            verify_ssl=llm_config.verify_ssl,
        )
        model_config = ModelRequestConfig(
            model_name=llm_config.model_name,
            temperature=llm_config.temperature,
            max_tokens=llm_config.max_tokens,
        )
        if llm_config.extra_body:
            model_config.extra_body = llm_config.extra_body
        return model_config, client_config

    # fallback：读 EvolveConfig（evoagent/.env）。CustomSSE 模式走端点凭证，否则 OpenAI 兼容。
    cfg = EvolveConfig.get()
    if cfg.llm_provider == "CustomSSE":
        client_config = ModelClientConfig(
            client_provider="CustomSSE",
            api_key=cfg.custom_sse_token,
            api_base=cfg.custom_sse_endpoint,
            user_id=cfg.custom_sse_user_id,
            verify_ssl=False,
            timeout=cfg.custom_sse_timeout,
        )
    else:
        client_config = ModelClientConfig(
            client_provider="OpenAI",
            api_key=cfg.llm_api_key,
            api_base=cfg.llm_base_url,
            verify_ssl=False,
            timeout=cfg.llm_timeout,
        )
    model_config = ModelRequestConfig(model_name=cfg.optimizer_model)
    return model_config, client_config


def _build_trajectory_from_messages(messages: list[dict[str, Any]]) -> StandardTrajectory:
    """从内联 messages 构建 ``StandardTrajectory``（只取已知字段，仿 evaluate.py）。"""
    if not messages:
        raise HTTPException(status_code=422, detail="messages must not be empty")
    trajectory_messages: list[TrajectoryMessage] = []
    for index, message in enumerate(messages):
        filtered = {k: v for k, v in message.items() if k in _TRAJECTORY_MESSAGE_FIELDS}
        if "role" not in filtered:
            raise HTTPException(
                status_code=422,
                detail=f"messages[{index}] missing required field 'role'",
            )
        try:
            trajectory_messages.append(TrajectoryMessage.model_validate(filtered))
        except Exception as e:
            raise HTTPException(
                status_code=422,
                detail=f"Invalid message at index {index}: {e}",
            ) from e
    return StandardTrajectory(messages=trajectory_messages)


def _load_trajectories(data: bytes, filename: str | None) -> list[StandardTrajectory]:
    """把上传 trace 文件解析为 ``list[StandardTrajectory]``。

    支持 JSON 数组 / 单对象 / JSONL。每条只取 ``messages`` / ``summary``（``extra="forbid"``）。
    """
    name = (filename or "").lower()
    text = data.decode("utf-8", errors="replace")
    records: list[dict[str, Any]] = []

    if name.endswith(".jsonl"):
        for line in text.splitlines():
            line = line.strip()
            if line:
                obj = json.loads(line)
                if not isinstance(obj, dict):
                    raise ValueError("JSONL line is not a JSON object")
                records.append(obj)
    else:
        parsed = json.loads(text)
        if isinstance(parsed, list):
            records = [r for r in parsed if isinstance(r, dict)]
        elif isinstance(parsed, dict):
            records = [parsed]
        else:
            raise ValueError("trace file must be a JSON array/object or JSONL")
        if not records:
            raise ValueError("trace file parsed but yielded no records")

    trajectories: list[StandardTrajectory] = []
    for r in records:
        data_dict: dict[str, Any] = {}
        if "messages" in r:
            data_dict["messages"] = r["messages"]
        if r.get("summary") is not None:
            data_dict["summary"] = r["summary"]
        try:
            trajectories.append(StandardTrajectory.model_validate(data_dict))
        except Exception as e:
            raise ValueError(f"Invalid trajectory record: {e}") from e
    return trajectories


def _build_skill_provider(config: BuildGUConfig) -> Any:
    """按 source 构建 SkillProvider；adapter 源暂不支持（async with 生命周期，下一步）。"""
    if config.source == "local":
        if not config.skill_root:
            raise HTTPException(
                status_code=422,
                detail="local skill source requires skill_root",
            )
        return LocalSkillProvider(skill_root=Path(config.skill_root))
    raise HTTPException(
        status_code=501,
        detail="adapter skill source not implemented yet (async with AdapterClient lifecycle)",
    )


def _progress_from_job(job: Any) -> dict[str, Any] | None:
    """从 job 事件 buffer 取最新 progress 事件，派生 {phase, done, total}。"""
    events = job.get_events_since(0)
    for event in reversed(events):
        if event.event == "progress":
            data = event.data
            return {
                "phase": data.get("phase"),
                "done": data.get("done"),
                "total": data.get("total"),
            }
    return None


# ---------------------------------------------------------------------------
# Routes — 在线产 EB（同步）
# ---------------------------------------------------------------------------


@router.post("/expected-behavior", response_model=GenerateEBResponse)
async def generate_expected_behavior(
    request: GenerateEBRequest,
) -> GenerateEBResponse:
    """基于内联轨迹 messages 产 expected_behavior（载入持久化 GU 路由切片）。

    返回对外口径 ``{id, inputs, expected_behavior}``（result/reason/scenario 备查不进）。
    """
    model_config, client_config = _build_model_configs(request.llm_config)
    generator = ExpectedBehaviorGenerator(model_config, client_config)

    trajectory = _build_trajectory_from_messages(request.messages)
    eb_input = EBInput(
        trajectory=trajectory,
        gu_slice=GUSlice(),
        attributed_skill=request.attributed_skill,
    )

    try:
        output = await asyncio.to_thread(generator.generate, eb_input)
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"EB generation failed: {e}",
        ) from e

    # internal 仅备查（result/reason/scenario/scope/score），不进 optimizer 对外口径。
    internal: dict[str, Any] = {}
    if output.items:
        it = output.items[0]
        internal = {
            "result": it.result,
            "reason": it.reason,
            "scenario": it.scenario,
            "scope": it.scope,
            "score": it.score,
        }
    return GenerateEBResponse(
        items=output.to_external(), metadata=output.metadata, internal=internal
    )


# ---------------------------------------------------------------------------
# Routes — 离线建 GU（异步 job + SSE）
# ---------------------------------------------------------------------------


@router.post("/global-understanding", response_model=BuildGUSubmitResponse)
async def submit_build_gu(
    file: UploadFile = File(...),
    config: str = Form(...),
) -> BuildGUSubmitResponse:
    """multipart 上传一批 trace 并提交建 GU job。

    - ``file``：trace 文件（JSON 数组/对象 或 JSONL，每条 ``{messages, summary?}``）。
    - ``config``：JSON blob，解析为 :class:`BuildGUConfig`。
    """
    try:
        parsed = BuildGUConfig.model_validate_json(config)
    except (ValueError, json.JSONDecodeError) as e:
        raise HTTPException(status_code=422, detail=f"Invalid config: {e}") from e

    data = await file.read()
    if len(data) > _MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"Upload too large: {len(data)} > {_MAX_UPLOAD_BYTES}",
        )

    try:
        trajectories = _load_trajectories(data, file.filename)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    skill_provider = _build_skill_provider(parsed)
    skill_names = list(parsed.skill_names) or skill_provider.list_skills()

    model_config, client_config = _build_model_configs(parsed.llm_config)
    builder = GlobalUnderstandingBuilder(
        model_config,
        client_config,
        skill_provider,
        flat_threshold=parsed.flat_threshold,
    )

    job = job_manager.submit({"task": "build_gu"})

    async def _run() -> None:
        try:
            job.status = JobStatus.RUNNING
            job.push_event(
                "progress",
                {"phase": "build", "done": 0, "total": len(trajectories)},
            )
            index = await asyncio.to_thread(
                builder.build, trajectories, skill_names, parsed.batch_size
            )
            job.result = {
                "mode": index.mode,
                "skills": index.skills,
                "out_of_scope_count": index.out_of_scope_count,
                "last_run_id": index.last_run_id,
            }
            job.status = JobStatus.COMPLETED
            job.push_event("completed", {"status": "completed"})
        except asyncio.CancelledError:
            print(f"[BUILD GU CANCELLED] job_id={job.job_id}", flush=True)
            raise
        except Exception as e:  # noqa: BLE001 — 后台 task 吞异常入 job
            tb = traceback.format_exc()
            print(f"[BUILD GU FAILED] {type(e).__name__}: {e}\n{tb}", flush=True)
            job.status = JobStatus.FAILED
            job.error = f"{type(e).__name__}: {e}"
            job.push_event("error", {"status": "failed", "error": job.error})

    job.background_task = asyncio.create_task(_run())
    return BuildGUSubmitResponse(job_id=job.job_id, status=job.status.value)


@router.get("/jobs/{job_id}", response_model=JobResponse)
async def get_job(job_id: str) -> JobResponse:
    """查询建 GU 任务状态、进度与结果。"""
    job = job_manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"Job not found: {job_id}")
    return JobResponse(
        job_id=job.job_id,
        status=job.status.value,
        progress=_progress_from_job(job),
        result=job.result,
        error=job.error,
    )


@router.get("/jobs/{job_id}/stream")
async def stream_job(job_id: str, request: Request) -> StreamingResponse:
    """SSE 流式推送建 GU 进度事件。支持 ``Last-Event-ID`` 重放历史。"""
    job = job_manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"Job not found: {job_id}")

    last_event_id = int(request.headers.get("Last-Event-ID", "0"))

    async def event_generator() -> AsyncGenerator[str, None]:
        replayed = job.get_events_since(last_event_id)
        for event in replayed:
            yield format_sse(event)
        current_last = replayed[-1].id if replayed else last_event_id

        if job.status in (JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED):
            return

        last_keepalive = time.monotonic()
        while job.status not in (
            JobStatus.COMPLETED,
            JobStatus.FAILED,
            JobStatus.CANCELLED,
        ):
            await asyncio.sleep(0.5)
            new_events = job.get_events_since(current_last)
            for event in new_events:
                yield format_sse(event)
                current_last = event.id
                last_keepalive = time.monotonic()
            if time.monotonic() - last_keepalive >= 30:
                yield ": keepalive\n\n"
                last_keepalive = time.monotonic()

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive"},
    )
