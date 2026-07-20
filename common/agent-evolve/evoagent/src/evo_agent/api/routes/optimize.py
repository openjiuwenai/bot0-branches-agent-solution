"""优化任务路由。"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncGenerator
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field, field_validator, model_validator
from pydantic_core import PydanticCustomError

from evo_agent.api.jobs import JobStatus, job_manager
from evo_agent.api.progress import ProgressCallback
from evo_agent.api.sse import format_sse
from evo_agent.cancellation import CancellationRequestedError
from evo_agent.config import EvolveConfig
from evo_agent.control_store import SubmissionConflictError
from evo_agent.errors import CancelRollbackError
from evo_agent.optimizer_runner import run_optimization_with_cancellation_recovery
from evo_agent.scenario.registry import ScenarioRegistry
from evo_agent.types import OptimizeRequest as InternalOptimizeRequest

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/optimize", tags=["optimize"])


class RolloutTemplateRequest(BaseModel):
    """平台 rollout 配置。"""

    extra_data: dict[str, Any] = Field(default_factory=dict)


class OptimizerTemplateRequest(BaseModel):
    """平台优化器模板 — 外部 API 请求结构。"""

    name: str  # 映射到 scenario
    scenario: str  # 业务场景标签（仅元数据）
    hyperparams: dict[str, Any] = Field(default_factory=dict)
    rollout: RolloutTemplateRequest = Field(default_factory=RolloutTemplateRequest)
    train_split: float = 0.8
    val_split: float = 0.2


class EvaluatorTemplateRequest(BaseModel):
    """平台评估器模板 — 外部 API 请求结构。"""

    name: str  # 模板显示名（仅元数据）
    scenario: str  # 业务场景标签（仅元数据）
    prompt: str = ""  # 评估 prompt


class OptimizeAPIRequest(BaseModel):
    """外部 API 请求体 — 对接平台模板结构。"""

    task_name: str
    agent_name: str
    optimizer_type: Literal["skill", "prompt", "tool"] = "skill"
    optimizer_template: OptimizerTemplateRequest
    evaluator_template: EvaluatorTemplateRequest
    skills: list[str] = Field(default_factory=list)
    dataset_path: str
    # managed-doc 单文档优化模式（spec F3）：精确 doc_kind，与 skills 互斥（XOR）。
    # adapter ManagedDocRegistry 按配置值精确匹配，故只 strip 不小写化；空白视为
    # 未提供（None）。使用该参数时走 F7 builder 分支，skills 必须为空。
    managed_doc_kind: str | None = None
    client_task_id: str | None = None
    managed_doc_expected_revision: str | None = None

    @field_validator("dataset_path")
    @classmethod
    def validate_dataset_path(cls, v: str) -> str:
        """路径安全校验（基础：存在性 + 文件类型 + 大小限制）。

        allowed_data_roots 校验在 route handler 中执行（需要 EvolveConfig）。
        """
        p = Path(v).resolve()

        if not p.exists():
            raise ValueError(f"Dataset file not found: {v}")
        if not p.is_file():
            raise ValueError(f"Dataset path is not a file: {v}")

        max_size = 500 * 1024 * 1024
        if p.stat().st_size > max_size:
            raise ValueError(f"Dataset file too large: {p.stat().st_size} > {max_size}")

        return str(p)

    @field_validator("optimizer_template")
    @classmethod
    def validate_splits(
        cls,
        t: OptimizerTemplateRequest,
    ) -> OptimizerTemplateRequest:
        """train_split + val_split 必须等于 1.0。"""
        if abs(t.train_split + t.val_split - 1.0) > 1e-6:
            raise ValueError("train_split + val_split must equal 1.0")
        if t.train_split <= 0 or t.val_split <= 0:
            raise ValueError("train_split and val_split must both be > 0")
        return t

    @model_validator(mode="after")
    def validate_prompt_control_fields(self) -> OptimizeAPIRequest:
        has_skills = bool(self.skills)
        has_managed_doc = bool((self.managed_doc_kind or "").strip())
        if self.optimizer_type == "skill":
            if not has_skills or has_managed_doc:
                raise PydanticCustomError(
                    "optimization_target_invalid",
                    "skill optimization requires skills and forbids managed_doc_kind",
                )
        elif self.optimizer_type == "prompt":
            if has_skills or not has_managed_doc:
                raise PydanticCustomError(
                    "optimization_target_invalid",
                    "prompt optimization requires managed_doc_kind and forbids skills",
                )
            if not (self.client_task_id or "").strip():
                raise ValueError("prompt optimization requires client_task_id")
            if not (self.managed_doc_expected_revision or "").strip():
                raise ValueError("prompt optimization requires managed_doc_expected_revision")
        else:
            # Tool keeps its legacy target contract and never enters the new
            # Prompt managed-doc control plane. Prompt-only fields are ignored
            # for backward compatibility instead of rejecting existing calls.
            self.managed_doc_kind = None
            self.client_task_id = None
            self.managed_doc_expected_revision = None
        return self


class JobResponse(BaseModel):
    """任务状态响应。"""

    job_id: str
    status: str
    progress: dict[str, Any] | None = None
    result: dict[str, Any] | None = None
    error: str | None = None
    error_code: str | None = None
    cancellation_requested: bool = False


class SubmissionResponse(BaseModel):
    """Durable idempotent-submission metadata safe for callers."""

    client_task_id: str
    job_id: str
    status: str
    request_hash_version: str
    request_hash: str
    cancellation_requested: bool


def _normalize(api_req: OptimizeAPIRequest, config: EvolveConfig) -> InternalOptimizeRequest:
    """将平台模板请求归一化为内部运行参数。

    职责：
    - 字段映射：optimizer_template.scenario → scenario
    - 类型提取：从 hyperparams dict 提取显式 num_epochs / batch_size 为 typed 字段
    - 范围校验：显式 num_epochs [1,100]，batch_size [1,64]
    - 注入 config：adapter_url ← EvolveConfig
    """
    tpl = api_req.optimizer_template
    logger.debug(
        "optimizer_template: train_split=%s val_split=%s",
        tpl.train_split,
        tpl.val_split,
    )
    hp = dict(tpl.hyperparams)  # 浅拷贝，避免修改原始请求

    # 提取显式 typed 字段（带类型转换防御）。缺省值由 ConfigResolver 统一处理。
    num_epochs = _optional_int(
        hp.pop("num_epochs", None),
        name="num_epochs",
        min_value=1,
        max_value=100,
    )
    batch_size = _optional_int(
        hp.pop("batch_size", None),
        name="batch_size",
        min_value=1,
        max_value=64,
    )

    eval_prompt = api_req.evaluator_template.prompt
    stripped_prompt = eval_prompt.strip()
    logger.debug(
        "evaluator_prompt: len=%s stripped_len=%s "
        "markers: strict=%s compliance=%s weighted=%s safety=%s",
        len(eval_prompt),
        len(stripped_prompt),
        "严格评估" in stripped_prompt,
        "compliance" in stripped_prompt,
        "× 0.5" in stripped_prompt,
        "safety" in stripped_prompt,
    )

    # managed_doc_kind：strip + 空白→None（不小写化，adapter 精确匹配）。
    # XOR 由路由层 _validate_xor 双保险收口（both-present / both-absent 均拒绝）；
    # 此处仅做归一：managed-doc 模式下 skills 强制为空（走 F7 builder 分支）。
    managed_doc_kind_raw = api_req.managed_doc_kind if api_req.optimizer_type == "prompt" else None
    managed_doc_kind = (managed_doc_kind_raw or "").strip() or None
    skills = [] if managed_doc_kind is not None else list(api_req.skills)

    return InternalOptimizeRequest(
        scenario=tpl.scenario or tpl.name,
        agent_name=api_req.agent_name,
        optimizer_type=api_req.optimizer_type,
        skills=skills,
        dataset_path=api_req.dataset_path,
        dataset_manifest_path=None,
        evaluator_prompt=api_req.evaluator_template.prompt,
        adapter_url=config.adapter_url,
        num_epochs=num_epochs,
        batch_size=batch_size,
        hyperparams=hp,
        rollout_extra_data=tpl.rollout.extra_data,
        train_split=tpl.train_split,
        val_split=tpl.val_split,
        task_name=api_req.task_name,
        managed_doc_kind=managed_doc_kind,
        managed_doc_expected_revision=(
            (api_req.managed_doc_expected_revision or "").strip() or None
        ),
    )


def _optional_int(
    raw: Any,
    *,
    name: str,
    min_value: int,
    max_value: int,
) -> int | None:
    if raw is None:
        return None
    try:
        value = int(raw)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be numeric: {exc}") from exc
    if value != raw:
        raise ValueError(f"{name} must be an integer, got {raw!r}")
    if not min_value <= value <= max_value:
        raise ValueError(f"{name} must be between {min_value} and {max_value}, got {value}")
    return value


@router.post("", response_model=JobResponse)
async def start_optimize(api_request: OptimizeAPIRequest) -> JobResponse:
    """提交一个优化任务。"""
    config = EvolveConfig.get()

    if not config.adapter_url:
        raise HTTPException(
            status_code=500,
            detail="EVO_ADAPTER_URL not configured",
        )

    # 校验 dataset_path 在 allowed_data_roots 下（config-dependent，无法在 Pydantic 中完成）
    resolved_path = Path(api_request.dataset_path).resolve()
    raw_roots = config.allowed_data_roots
    if isinstance(raw_roots, str):
        allowed_roots = [Path(p.strip()).resolve() for p in raw_roots.split(",") if p.strip()]
    else:
        allowed_roots = [root.resolve() for root in raw_roots]
    if not any(resolved_path.is_relative_to(root) for root in allowed_roots):
        raise HTTPException(
            status_code=422,
            detail=(f"Dataset path must be under allowed roots: {[str(r) for r in allowed_roots]}"),
        )

    # 校验 scenario 存在性（仅警告，不阻断 — 归一化时会 fallback）
    try:
        ScenarioRegistry().load_scenario_config(api_request.optimizer_template.scenario)
    except (FileNotFoundError, ValueError):
        pass  # _normalize 会 fallback 到默认场景

    # XOR 双保险（spec F3）：skills 与 managed_doc_kind 互斥，且必须提供其一。
    # 叶子 dataclass 仅拒绝 both-present（保留 runner eval-only 空路径），入口层
    # 收口 both-absent（API 无目标即拒绝，422）。strip 后空白视为未提供。
    md_kind_raw = api_request.managed_doc_kind
    md_kind = (md_kind_raw or "").strip() or None
    has_skills = bool(api_request.skills)
    has_managed_doc = md_kind is not None
    if api_request.optimizer_type != "tool" and has_skills and has_managed_doc:
        raise HTTPException(
            status_code=422,
            detail="skills 与 managed_doc_kind 互斥：请只提供一种优化目标。",
        )
    if api_request.optimizer_type != "tool" and not has_skills and not has_managed_doc:
        raise HTTPException(
            status_code=422,
            detail="必须提供 skills 或 managed_doc_kind 之一。",
        )

    # 归一化为内部请求格式（在 submit 之前，避免失败时产生僵尸 Job）
    try:
        internal_request = _normalize(api_request, config)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    try:
        job = job_manager.submit(
            api_request.model_dump(),
            client_task_id=(
                api_request.client_task_id.strip()
                if api_request.optimizer_type == "prompt" and api_request.client_task_id
                else None
            ),
        )
    except SubmissionConflictError as exc:
        raise HTTPException(
            status_code=409,
            detail={
                "code": "OPTIMIZATION_REQUEST_CONFLICT",
                "message": "client_task_id was reused with a different request",
            },
        ) from exc
    except RuntimeError as exc:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "CAPABILITY_STORAGE_UNAVAILABLE",
                "message": "optimization control store is unavailable",
            },
        ) from exc

    # Idempotent replay: the original execution/result remains authoritative.
    if job.background_task is not None or job.status != JobStatus.QUEUED:
        return JobResponse(
            job_id=job.job_id,
            status=job.status.value,
            result=job.result,
            error=job.error,
            error_code=job.error_code,
        )
    # 保存归一化 managed_doc_kind 作 Job metadata，供 cooperative cancellation
    # 判断是否必须执行 job-start baseline rollback。
    job.managed_doc_kind = internal_request.managed_doc_kind

    # 在后台启动优化任务
    progress_callback = ProgressCallback(job)

    def _phase_callback(event: str, data: dict[str, Any]) -> None:
        """SSE 阶段事件推送闭包（拥有 job 引用）。

        除转发事件到 SSE buffer 外，还在 apply 阶段累计 edits_applied，
        使 ProgressCallback.on_train_epoch_end 能读取最新编辑数。
        """
        job.push_event(event, data)
        # 从 optimizer 的 apply 阶段事件中累计编辑数
        if data.get("phase") == "apply":
            n_edits = data.get("data", {}).get("n_edits", 0)
            if n_edits > 0:
                job.update_progress(edits_applied=job.progress.edits_applied + n_edits)

    async def _run_with_progress() -> None:
        if job.status == JobStatus.CANCELLED:
            return
        job_manager.set_status(job, JobStatus.RUNNING)
        try:
            report = await run_optimization_with_cancellation_recovery(
                internal_request,
                config,
                progress_callback=progress_callback,
                phase_callback=_phase_callback,
                cancellation_token=job.cancellation_token,
            )
            job.result = {
                "skills": list(report.skills),
                "epochs_completed": report.epochs_completed,
                # 用 gate-aware 的 live 计数器（on_train_epoch_end 在拒绝轮回滚过），
                # 而非 report.edits_applied：后者由 formatter rglob 扫 selected_edits.json
                # 得来，含被 gate 拒绝（operator 已回滚）的编辑，会把"试过但未生效"的
                # 编辑算进完成卡片的"编辑次数"。计数器只计 gate 接受的编辑。
                "edits_applied": job.progress.edits_applied,
                "train": {
                    "score_before": report.train.score_before,
                    "score_after": report.train.score_after,
                    "improvement": report.train.improvement,
                    "pass_rate_before": report.train.pass_rate_before,
                    "pass_rate_after": report.train.pass_rate_after,
                    "num_cases": report.train.num_cases,
                },
                "val": {
                    "score_before": report.val.score_before,
                    "final_score": report.val.final_score,
                    "best_score": report.val.best_score,
                    "per_epoch_scores": list(report.val.per_epoch_scores),
                    "num_cases": report.val.num_cases,
                    "improvement": report.val.improvement,
                    "pass_rate_before": report.val.pass_rate_before,
                    "pass_rate_after": report.val.pass_rate_after,
                },
                "gate_results": list(report.gate_results),
                "skill_scores": [
                    {
                        "name": s.name,
                        "score_before": s.score_before,
                        "score_after": s.score_after,
                        "score_delta": s.score_delta,
                        "edits_applied": s.edits_applied,
                        "pass_rate_before": s.pass_rate_before,
                        "pass_rate_after": s.pass_rate_after,
                    }
                    for s in (() if report.managed_doc_kind is not None else report.skill_scores)
                ],
                "skill_contents": [
                    {
                        "name": sc.name,
                        "content_before": sc.content_before,
                        "epoch_contents": list(sc.epoch_contents),
                    }
                    for sc in (() if report.managed_doc_kind is not None else report.skill_contents)
                ],
                # managed-doc 单文档优化回填字段（spec F1/F3）：job 完成后填充，
                # managed-doc 模式才有值；Skill 模式四字段为 None/[]。
                "managed_doc_kind": report.managed_doc_kind,
                "managed_doc_content_before": report.managed_doc_content_before,
                "managed_doc_content_after": report.managed_doc_content_after,
                "managed_doc_epoch_contents": [
                    {"round": item.round, "content": item.content}
                    for item in report.managed_doc_epoch_contents
                ],
                "managed_doc_task_ids": list(report.managed_doc_task_ids),
            }
            # COMPLETED 仅在训练 + 报告格式化均成功后设置，
            # 避免 on_train_end 提前设 COMPLETED 而 format() 失败导致状态闪烁。
            job_manager.set_status(job, JobStatus.COMPLETED)
            job.push_event("completed", {"status": "completed"})
        except CancellationRequestedError:
            job_manager.set_status(job, JobStatus.CANCELLED)
            job.error = "Job cancelled by user"
            job.push_event("completed", {"status": "cancelled"})
        except CancelRollbackError as exc:
            job_manager.set_status(job, JobStatus.FAILED)
            job.error = f"{exc.code}: {exc.diagnostics}"
            job.error_code = exc.code
            job.push_event(
                "error",
                {
                    "status": "failed",
                    "error": job.error,
                    "code": exc.code,
                    "diagnostics": exc.diagnostics,
                },
            )
        except asyncio.CancelledError:
            # 仅用于进程关闭等外部协程取消；用户取消走 cooperative token。
            print(f"[OPTIMIZE CANCELLED] job_id={job.job_id}", flush=True)
            raise
        except Exception as e:
            import traceback

            tb = traceback.format_exc()
            print(f"[OPTIMIZE FAILED] {type(e).__name__}: {e}\n{tb}", flush=True)
            job_manager.set_status(job, JobStatus.FAILED)
            job.error = f"{type(e).__name__}: {e}"
            job.error_code = getattr(e, "code", None)
            # 推送 error 事件，让 SSE 客户端能感知失败（on_train_end 不再推 completed）
            job.push_event(
                "error",
                {
                    "status": "failed",
                    "error": job.error,
                    "code": job.error_code,
                },
            )

    job.background_task = asyncio.create_task(_run_with_progress())

    return JobResponse(job_id=job.job_id, status=job.status.value)


@router.get("/submissions/{client_task_id}", response_model=SubmissionResponse)
async def get_submission(client_task_id: str) -> SubmissionResponse:
    receipt = job_manager.get_submission(client_task_id)
    if receipt is None:
        raise HTTPException(status_code=404, detail=f"Submission not found: {client_task_id}")
    return SubmissionResponse(
        client_task_id=receipt.client_task_id,
        job_id=receipt.job_id,
        status=receipt.status.value,
        request_hash_version=receipt.request_hash_version,
        request_hash=receipt.request_hash,
        cancellation_requested=receipt.cancellation_requested,
    )


@router.get("/{job_id}/stream")
async def stream_job(job_id: str, request: Request) -> StreamingResponse:
    """SSE 流式推送任务进度事件。

    支持 ``Last-Event-ID`` header 进行历史事件重放。
    已完成的任务在重放历史后立即结束。
    """
    job = job_manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"Job not found: {job_id}")

    last_event_id = int(request.headers.get("Last-Event-ID", "0"))

    async def event_generator() -> AsyncGenerator[str, None]:
        # 重放历史事件。current_last 锚定在重放快照的尾 id，而非 buffer
        # 最新 id —— 否则重放快照后、取 current_last 之前 producer 推送的事件
        # 会被直接跳过，永久丢失（客户端 Last-Event-ID 仍停在旧值）。
        replayed = job.get_events_since(last_event_id)
        for event in replayed:
            yield format_sse(event)
        current_last = replayed[-1].id if replayed else last_event_id

        # 如果任务已完成，直接结束
        if job.status in (JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED):
            return

        # 实时推送新事件 + keepalive
        import time

        last_keepalive = time.monotonic()

        while job.status not in (JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED):
            await asyncio.sleep(0.5)
            new_events = job.get_events_since(current_last)
            for event in new_events:
                yield format_sse(event)
                current_last = event.id
                last_keepalive = time.monotonic()

            # SSE keepalive：每 30 秒发一个注释，重置 read 计时器
            if time.monotonic() - last_keepalive >= 30:
                yield ": keepalive\n\n"
                last_keepalive = time.monotonic()

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
        },
    )


@router.post("/{job_id}/cancel", response_model=JobResponse, status_code=202)
async def cancel_job(job_id: str) -> JobResponse:
    """取消一个运行中或排队中的优化任务。

    - 202: 已接受取消请求
    - 404: 任务不存在
    - 409: 任务已处于终态（completed / failed / cancelled），不可取消
    """
    job = job_manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"Job not found: {job_id}")
    if not job_manager.cancel(job_id):
        raise HTTPException(
            status_code=409,
            detail=f"Cannot cancel job in terminal status: {job.status.value}",
        )

    return JobResponse(
        job_id=job.job_id,
        status=job.status.value,
        error=job.error,
        error_code=job.error_code,
        cancellation_requested=job.cancellation_token.is_requested,
    )


@router.get("/{job_id}", response_model=JobResponse)
async def get_job(job_id: str) -> JobResponse:
    """查询任务状态和进度。"""
    job = job_manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"Job not found: {job_id}")

    return JobResponse(
        job_id=job.job_id,
        status=job.status.value,
        progress={
            "current_epoch": job.progress.current_epoch,
            "total_epochs": job.progress.total_epochs,
            "current_step": job.progress.current_step,
            "val_score": job.progress.val_score,
            "best_score": job.progress.best_score,
            "edits_applied": job.progress.edits_applied,
        },
        result=job.result,
        error=job.error,
        error_code=job.error_code,
        cancellation_requested=job.cancellation_token.is_requested,
    )
