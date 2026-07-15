"""JobManager — 异步优化任务生命周期管理。"""

from __future__ import annotations

import asyncio
import uuid
from collections import deque
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from evo_agent.api.events import SSEEvent


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
    event_buffer: deque[SSEEvent] = field(
        default_factory=lambda: deque(maxlen=_EVENT_BUFFER_MAXLEN)
    )
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

    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}

    def submit(self, request_data: dict[str, Any]) -> Job:
        """提交一个优化任务，返回 Job 对象。"""
        job_id = uuid.uuid4().hex[:12]
        job = Job(job_id=job_id)
        self._jobs[job_id] = job
        # 后台异步执行（调用方负责启动实际优化）
        return job

    def get(self, job_id: str) -> Job | None:
        """查询任务状态。"""
        return self._jobs.get(job_id)

    def list_jobs(self) -> list[Job]:
        """列出所有任务。"""
        return list(self._jobs.values())

    def cancel(self, job_id: str) -> bool:
        """取消一个任务。

        只有非终态（queued / running）的任务可以被取消。
        取消后：
        - 状态变为 CANCELLED
        - 推送 cancelled SSE 事件
        - 设置 error 信息
        - 取消 background_task（如有）

        Returns:
            True 表示成功取消，False 表示任务不存在或已处于终态。
        """
        job = self._jobs.get(job_id)
        if job is None:
            return False
        if job.status in _TERMINAL_STATUSES:
            return False

        job.status = JobStatus.CANCELLED
        job.error = "Job cancelled by user"
        job.push_event("cancelled", {"status": "cancelled"})

        if job.background_task is not None and not job.background_task.done():
            job.background_task.cancel()

        return True


# 全局单例
job_manager = JobManager()
