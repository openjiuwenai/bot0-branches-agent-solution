"""Agent-as-judge 评估路由 — POST 提交异步 job，GET 查询 / SSE 流式进度。

与 ``/evaluate/dataset`` 同形（异步 job + SSE 回放 + keepalive），但评估体是
:class:`AgentEvaluator`：用真实编码 Agent CLI（claude / codex）以子进程方式对一条
轨迹做多维度评判，再由 LLM 聚合器产出总分 + 复数 skill 归因。

作用域 = HTTP-only，不接入优化管线（训练 rollout / 验证门禁仍用 ``LLMEvaluator``）。

响应直接读 ``json.loads(evaluated.reason)`` 取复数归因（``skill_attributions[]``、
``attribution_status``、``dimensions``），**不走** ``evaluate_input`` →
``from_evaluated_case`` 的 lossy 路径（后者只解析单数 ``attributed_skill``，会丢复数）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import AsyncGenerator
from pathlib import Path
from typing import Any, Literal, cast

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from evo_agent.api.jobs import Job, JobStatus, job_manager
from evo_agent.api.sse import format_sse
from evo_agent.evaluator.adapters.openjiuwen import to_case_and_placeholder
from evo_agent.evaluator.domain.models import EvaluationInput, StandardTrajectory
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.agent import AgentEvaluator
from evo_agent.evaluator.factory import create_evaluator

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/evaluate/agent-judge", tags=["evaluate-agent-judge"])


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------


class LLMConfig(BaseModel):
    """聚合器 LLM 配置 — 每次请求必传。

    与 ``evaluate.py`` / ``evaluate_dataset.py`` 的 ``LLMConfig`` 同构；本地定义
    以避免跨 route import。``client_provider`` 默认 ``"OpenAI"``。
    """

    model_name: str
    api_key: str
    api_base: str
    client_provider: str = "OpenAI"
    temperature: float = 0.1
    max_tokens: int = 2048
    verify_ssl: bool = False


class AgentJudgeRequest(BaseModel):
    """Agent-as-judge 评估请求体。"""

    trajectory_path: str
    preset: str
    skill_names: list[str] = Field(min_length=1)
    llm_config: LLMConfig
    expected_result: dict[str, Any] | None = None
    runtime: Literal["claude", "codex"] | None = None
    tool_allowlist: list[str] | None = None
    skill_source: Literal["local", "adapter", "none"] = "none"
    skill_root: str | None = None
    max_concurrent: int | None = None
    run_timeout: float | None = None
    keep_on_error: bool = False
    extra_env: dict[str, str] | None = None


class AgentJudgeSubmitResponse(BaseModel):
    """提交响应。"""

    job_id: str
    status: str


class AgentJudgeResultBody(BaseModel):
    """完成事件的富载荷 + job.result。暴露复数归因（route 直读 reason JSON）。"""

    score: float
    is_pass: bool
    per_metric: dict[str, float]
    dimensions: dict[str, float]
    skill_attributions: list[dict[str, Any]]
    attribution_status: str
    attribution_error: str | None
    attributed_skill: str
    reason: str


class JobResponse(BaseModel):
    """任务状态响应（与 ``evaluate_dataset`` 同形）。"""

    job_id: str
    status: str
    progress: dict[str, Any] | None = None
    result: dict[str, Any] | None = None
    error: str | None = None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _load_trajectory(path: Path) -> StandardTrajectory:
    """Load a StandardTrajectory from a JSON file (mirrors evaluate.py)."""
    with open(path, encoding="utf-8") as f:
        raw: dict[str, Any] = json.load(f)
    data: dict[str, Any] = {"messages": raw.get("messages", [])}
    if raw.get("summary") is not None:
        data["summary"] = raw.get("summary")
    return StandardTrajectory.model_validate(data)


def _build_llm_configs(llm_config: LLMConfig) -> tuple[Any, Any]:
    """从 LLMConfig 构建 (ModelRequestConfig, ModelClientConfig)；未知 provider → 422。"""
    from openjiuwen.core.common.exception.errors import (
        ValidationError as ProviderValidationError,
    )
    from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

    model_config = ModelRequestConfig(
        model_name=llm_config.model_name,
        temperature=llm_config.temperature,
        max_tokens=llm_config.max_tokens,
    )
    try:
        model_client_config = ModelClientConfig(
            client_provider=llm_config.client_provider,
            api_key=llm_config.api_key,
            api_base=llm_config.api_base,
            verify_ssl=llm_config.verify_ssl,
        )
    except ProviderValidationError as e:
        raise HTTPException(status_code=422, detail=f"Invalid llm_config: {e}") from e
    return model_config, model_client_config


def _build_evaluator_config(
    request: AgentJudgeRequest, model_config: Any, model_client_config: Any
) -> dict[str, Any]:
    """Assemble the ``create_evaluator`` config dict from the HTTP request."""
    config: dict[str, Any] = {
        "type": "agent",
        "preset": request.preset,
        "model_config": model_config,
        "model_client_config": model_client_config,
        "skill_source": request.skill_source,
    }
    if request.runtime is not None:
        config["runtime"] = request.runtime
    if request.tool_allowlist is not None:
        config["tool_allowlist"] = request.tool_allowlist
    if request.skill_root is not None:
        config["skill_root"] = request.skill_root
    if request.max_concurrent is not None:
        config["max_concurrent"] = request.max_concurrent
    if request.run_timeout is not None:
        config["run_timeout"] = request.run_timeout
    if request.keep_on_error:
        config["keep_on_error"] = True
    if request.extra_env:
        config["extra_env"] = request.extra_env
    return config


def _progress_from_job(job: Job) -> dict[str, Any] | None:
    """从 job 事件 buffer 取最新 progress 事件，派生 {phase, done, total}。"""
    for event in reversed(job.get_events_since(0)):
        if event.event == "progress":
            data = event.data
            return {
                "phase": data.get("phase"),
                "done": data.get("done"),
                "total": data.get("total"),
            }
    return None


def _build_result(evaluated: Any) -> AgentJudgeResultBody:
    """从 EvaluatedCase 直读 reason JSON，构造富结果体（暴露复数归因）。"""
    blob: dict[str, Any] = {}
    if evaluated.reason:
        try:
            blob = json.loads(evaluated.reason)
        except (ValueError, TypeError):
            blob = {}
    per_metric = dict(evaluated.per_metric or {})
    dimensions = blob.get("dimensions", {})
    if not isinstance(dimensions, dict):
        dimensions = {}
    attributions = blob.get("skill_attributions", [])
    if not isinstance(attributions, list):
        attributions = []
    return AgentJudgeResultBody(
        score=float(evaluated.score or 0.0),
        is_pass=bool(blob.get("is_pass", True)),
        per_metric=per_metric,
        dimensions={str(k): float(v) for k, v in dimensions.items()},
        skill_attributions=[dict(a) for a in attributions if isinstance(a, dict)],
        attribution_status=str(blob.get("attribution_status", "completed")),
        attribution_error=blob.get("attribution_error"),
        attributed_skill=str(blob.get("attributed_skill", "")),
        reason=str(evaluated.reason or ""),
    )


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------


@router.post("", response_model=AgentJudgeSubmitResponse)
async def submit_agent_judge(request: AgentJudgeRequest) -> AgentJudgeSubmitResponse:
    """提交一条轨迹的 agent-as-judge 评估 job。

    校验轨迹 / preset / llm_config（422）→ 构建 ``AgentEvaluator``（经
    ``create_evaluator``）→ 提交异步 job → 返回 ``job_id``。评估在后台跑：
    每个 (轨迹×维度) 一次子进程 spawn（claude / codex），LLM 聚合器产出总分 +
    复数 skill 归因。进度经 SSE 推送。
    """
    traj_path = Path(request.trajectory_path)
    if not traj_path.exists():
        raise HTTPException(
            status_code=422,
            detail=f"Trajectory file not found: {request.trajectory_path}",
        )
    try:
        trajectory = _load_trajectory(traj_path)
    except Exception as e:
        raise HTTPException(status_code=422, detail=f"Invalid trajectory format: {e}") from e
    if not trajectory.messages:
        raise HTTPException(status_code=422, detail="Trajectory messages must not be empty")

    # 校验 preset 早返 422（create_evaluator 内部也会校验，但这里给出更清晰消息）。
    from evo_agent.evaluator.agent_judge.presets import get_preset

    try:
        get_preset(request.preset)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    model_config, model_client_config = _build_llm_configs(request.llm_config)
    evaluator_config = _build_evaluator_config(request, model_config, model_client_config)
    try:
        evaluator = cast(AgentEvaluator, create_evaluator(evaluator_config))
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    evaluation_input = EvaluationInput(
        trajectory=trajectory,
        expected_result=request.expected_result,
        skill_names=request.skill_names,
    )

    job = job_manager.submit({"preset": request.preset, "trajectory_path": request.trajectory_path})

    async def _run() -> None:
        await _run_agent_judge(job, evaluator, evaluation_input)

    job.background_task = asyncio.create_task(_run())
    return AgentJudgeSubmitResponse(job_id=job.job_id, status=job.status.value)


async def _run_agent_judge(
    job: Job, evaluator: AgentEvaluator, evaluation_input: EvaluationInput
) -> None:
    """后台任务：在 worker 线程跑 evaluate，推送 progress / completed / error 事件。"""
    job_manager.set_status(job, JobStatus.RUNNING)
    job.push_event("progress", {"phase": "judge", "done": 0, "total": 0, "status": "running"})

    # orchestrator 的 on_progress 在 worker 线程内调用；push_event 追加到 deque，
    # get_events_since 快照读取，与 evaluate_dataset 的 pipeline 同线程模型。
    def _on_progress(done: int, total: int) -> None:
        job.push_event("progress", {"phase": "judge", "done": done, "total": total})

    evaluator.progress_callback = _on_progress

    def _sync() -> AgentJudgeResultBody:
        case, placeholder = to_case_and_placeholder(evaluation_input)
        evaluated = evaluator.evaluate(case, placeholder)
        return _build_result(evaluated)

    try:
        result = await asyncio.to_thread(_sync)
    except asyncio.CancelledError:
        job_manager.set_status(job, JobStatus.CANCELLED)
        job.push_event("completed", {"status": "cancelled"})
        raise
    except EvaluationError as e:
        job.error = str(e)
        job.error_code = e.category
        job_manager.set_status(job, JobStatus.FAILED)
        job.push_event(
            "error",
            {"status": "failed", "error": job.error, "category": e.category},
        )
        return
    except Exception as e:  # noqa: BLE001 — 任意运行期失败统一 FAILED + error 事件
        job.error = f"{type(e).__name__}: {e}"
        job_manager.set_status(job, JobStatus.FAILED)
        job.push_event("error", {"status": "failed", "error": job.error})
        return

    job.result = result.model_dump()
    job_manager.set_status(job, JobStatus.COMPLETED)
    job.push_event("completed", {"status": "completed", **result.model_dump()})


@router.get("/jobs/{job_id}", response_model=JobResponse)
async def get_job(job_id: str) -> JobResponse:
    """查询 agent-judge 任务状态、进度与结果。"""
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
    """SSE 流式推送 agent-judge 进度事件。支持 ``Last-Event-ID`` 重放历史。"""
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
            for event in job.get_events_since(current_last):
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
