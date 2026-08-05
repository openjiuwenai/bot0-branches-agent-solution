"""JobManager — 异步优化任务生命周期管理。"""

from __future__ import annotations

import asyncio
import uuid
from collections import deque
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Any

from evo_agent.api.events import SSEEvent
from evo_agent.cancellation import CancellationToken
from evo_agent.control_store import (
    SubmissionControlStore,
    SubmissionReceipt,
    SubmissionStatus,
)


class JobStatus(StrEnum):
    """任务状态。"""

    QUEUED = "queued"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


# 终态集合 — 处于这些状态的任务不可再被 cancel。
_TERMINAL_STATUSES = frozenset(
    {
        JobStatus.COMPLETED,
        JobStatus.FAILED,
        JobStatus.CANCELLED,
    }
)


@dataclass
class JobProgress:
    """任务进度信息。

    Attributes:
        val_score: 当前 epoch 候选 fresh eval 分数（波动，与趋势图
            per_epoch_scores 同语义）。非门控赢家——赢家走缓存、单调非降，
            看不出优化在做事，故上报候选分让 live 卡片反映真实波动。
        best_score: committed 最佳（门控保留的历史最高，含基线，单调非降）。
    """

    current_epoch: int = 0
    total_epochs: int = 0
    current_step: int = 0
    val_score: float | None = None
    best_score: float | None = None
    edits_applied: int = 0


_EVENT_BUFFER_MAXLEN = 5000


@dataclass
class Job:
    """一个优化任务。"""

    job_id: str
    status: JobStatus = JobStatus.QUEUED
    progress: JobProgress = field(default_factory=JobProgress)
    result: dict[str, Any] | None = None
    error: str | None = None
    error_code: str | None = None
    client_task_id: str | None = None
    cancellation_token: CancellationToken = field(default_factory=CancellationToken)
    event_buffer: deque[SSEEvent] = field(
        default_factory=lambda: deque(maxlen=_EVENT_BUFFER_MAXLEN)
    )
    # managed-doc 模式标记（spec F3）：归一化后的 managed_doc_kind，None = Skill 模式。
    # 供执行包装判断取消时是否需要恢复已验证的 job-start baseline。
    managed_doc_kind: str | None = None
    _next_event_id: int = field(default=0, init=False, repr=False)
    background_task: asyncio.Task[None] | None = field(default=None, init=False, repr=False)

    def update_progress(self, **kwargs: Any) -> None:
        """更新进度字段。"""
        for key, value in kwargs.items():
            if hasattr(self.progress, key):
                setattr(self.progress, key, value)

    def push_event(self, event: str, data: dict[str, Any]) -> None:
        """推送一个 SSE 事件到 buffer。"""
        self._next_event_id += 1
        self.event_buffer.append(SSEEvent(id=self._next_event_id, event=event, data=data))

    def get_events_since(self, last_event_id: int) -> list[SSEEvent]:
        """返回 last_event_id 之后的所有事件（快照后过滤，线程安全）。"""
        snapshot = list(self.event_buffer)
        return [e for e in snapshot if e.id > last_event_id]


class JobManager:
    """内存中的任务管理器。

    管理优化任务的提交、执行和查询。
    生产环境可替换为持久化实现（如 Redis/DB）。
    """

    def __init__(self, *, control_db_path: Path | None = None) -> None:
        self._jobs: dict[str, Job] = {}
        self._control_store: SubmissionControlStore | None = None
        self._control_store_error: str | None = None
        if control_db_path is not None:
            try:
                control_store = SubmissionControlStore(control_db_path)
                control_store.mark_unfinished_lost()
                self._control_store = control_store
            except Exception as exc:  # storage failure must leave /capabilities available
                self._control_store_error = type(exc).__name__

    def submit(
        self,
        request_data: dict[str, Any],
        *,
        client_task_id: str | None = None,
    ) -> Job:
        """提交一个优化任务，返回 Job 对象。"""
        job_id = uuid.uuid4().hex[:12]
        if client_task_id is not None:
            if self._control_store is None:
                raise RuntimeError("durable submission control store is unavailable")
            receipt, created = self._control_store.create_or_get(
                client_task_id=client_task_id,
                request_data=request_data,
                job_id=job_id,
            )
            if not created:
                existing = self._jobs.get(receipt.job_id)
                if existing is not None:
                    return existing
                status = {
                    SubmissionStatus.RECEIVED: JobStatus.QUEUED,
                    SubmissionStatus.RUNNING: JobStatus.RUNNING,
                    SubmissionStatus.COMPLETED: JobStatus.COMPLETED,
                    SubmissionStatus.FAILED: JobStatus.FAILED,
                    SubmissionStatus.CANCELLED: JobStatus.CANCELLED,
                    SubmissionStatus.LOST: JobStatus.FAILED,
                }[receipt.status]
                job = Job(
                    job_id=receipt.job_id,
                    status=status,
                    client_task_id=receipt.client_task_id,
                )
                self._jobs[job.job_id] = job
                return job
            job_id = receipt.job_id
        job = Job(job_id=job_id, client_task_id=client_task_id)
        self._jobs[job_id] = job
        # 后台异步执行（调用方负责启动实际优化）
        return job

    def get(self, job_id: str) -> Job | None:
        """查询任务状态。"""
        return self._jobs.get(job_id)

    def get_submission(self, client_task_id: str) -> SubmissionReceipt | None:
        """Query durable submission metadata by the caller's idempotency key."""
        if self._control_store is None:
            return None
        return self._control_store.get(client_task_id)

    @property
    def durable_available(self) -> bool:
        return self._control_store is not None and self._control_store.is_available()

    def set_status(self, job: Job, status: JobStatus) -> None:
        """Update in-memory execution state and its durable receipt together."""
        job.status = status
        if job.client_task_id is None or self._control_store is None:
            return
        durable_status = {
            JobStatus.QUEUED: SubmissionStatus.RECEIVED,
            JobStatus.RUNNING: SubmissionStatus.RUNNING,
            JobStatus.COMPLETED: SubmissionStatus.COMPLETED,
            JobStatus.FAILED: SubmissionStatus.FAILED,
            JobStatus.CANCELLED: SubmissionStatus.CANCELLED,
        }[status]
        self._control_store.update_status(job.job_id, durable_status)

    def list_jobs(self) -> list[Job]:
        """列出所有任务。"""
        return list(self._jobs.values())

    def cancel(self, job_id: str) -> bool:
        """Latch cooperative cancellation; queued jobs terminate immediately."""
        job = self._jobs.get(job_id)
        if job is None:
            return False
        if job.status in _TERMINAL_STATUSES:
            return False
        job.cancellation_token.request()
        if self._control_store is not None and job.client_task_id is not None:
            self._control_store.request_cancellation(job.job_id)
        if not any(event.data.get("phase") == "cancel_requested" for event in job.event_buffer):
            job.push_event(
                "log",
                {
                    "level": "info",
                    "message": "Cancellation requested",
                    "phase": "cancel_requested",
                },
            )
        if job.status == JobStatus.QUEUED:
            self.set_status(job, JobStatus.CANCELLED)
            job.error = "Job cancelled by user before execution"
            job.push_event("completed", {"status": "cancelled"})
        return True


# 全局单例：Prompt submission receipt 默认即 durable；测试可注入独立 JobManager。
from evo_agent.config import EvolveConfig  # noqa: E402

job_manager = JobManager(control_db_path=EvolveConfig.get().evoagent_control_db_path)
