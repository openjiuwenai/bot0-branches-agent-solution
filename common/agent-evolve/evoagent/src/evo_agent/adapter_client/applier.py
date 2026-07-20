"""ManagedDocApplier — 同步深模块：apply managed-doc + 轮询 task + 等待 restart 生效（spec F5）。

外部只暴露 ``apply_and_wait`` + 只读 ``records``。轮询/deadline/hash 去重/状态机
全部封闭在此模块，不散落到 transport 或 runner。全程同步 ``httpx.Client``（经
``AdapterClient._sync_http``），自身禁 ``asyncio.run`` / ``asyncio.to_thread``。
"""

from __future__ import annotations

import hashlib
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import httpx

from evo_agent.adapter_client.client import AdapterClient, AdapterError
from evo_agent.adapter_client.types import (
    AlreadyApplied,
    ManagedDocSnapshot,
    TaskState,
)
from evo_agent.cancellation import CancellationToken
from evo_agent.errors import ManagedDocApplyError


def _content_hash(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class ManagedDocApplyRecord:
    """单次 apply 的 ledger 条目（失败 artifact 数据源）。

    成功/失败/no-op 都追加一条。phase 标识终态阶段（noop/success/recovered/
    failed_post/failed_poll/failed_snapshot/deadline/unknown）。
    """

    phase: str
    content_hash: str
    task_id: str | None
    status: str | None
    noop: bool
    recovered: bool
    error: str | None
    adapter_error: str | None
    post_time: float
    poll_time: float
    total_time: float


@dataclass(frozen=True)
class AppliedDocument:
    """单次 apply 的对外结果。"""

    content_hash: str
    task_id: str | None
    noop: bool
    final_status: str | None
    recovered: bool
    post_time: float
    poll_time: float
    total_time: float


class ManagedDocApplier:
    """同步 apply 深模块。组合 ``AdapterClient`` 的三个 transport 方法。"""

    def __init__(
        self,
        *,
        adapter_client: AdapterClient,
        doc_kind: str,
        last_success_hash: str | None = None,
        poll_interval: float = 2.0,
        deadline: float = 600.0,
        clock: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
        cancellation_token: CancellationToken | None = None,
        phase_callback: Callable[[str, dict[str, Any]], None] | None = None,
    ) -> None:
        self._client = adapter_client
        self._doc_kind = doc_kind
        self._last_success_hash = last_success_hash
        self._poll_interval = poll_interval
        self._deadline = deadline
        self._clock = clock
        self._sleeper = sleeper
        self._cancellation_token = cancellation_token
        self._phase_callback = phase_callback
        self._cancel_waiting_emitted = False
        self._records: list[ManagedDocApplyRecord] = []

    @property
    def records(self) -> tuple[ManagedDocApplyRecord, ...]:
        return tuple(self._records)

    @property
    def last_success_hash(self) -> str | None:
        return self._last_success_hash

    def _remaining(self, start: float) -> float:
        return max(0.0, self._deadline - (self._clock() - start))

    def _record(
        self,
        *,
        phase: str,
        content_hash: str,
        task_id: str | None,
        status: str | None,
        noop: bool,
        recovered: bool,
        error: str | None,
        adapter_error: str | None,
        post_time: float,
        poll_time: float,
        total_time: float,
    ) -> ManagedDocApplyRecord:
        rec = ManagedDocApplyRecord(
            phase=phase,
            content_hash=content_hash,
            task_id=task_id,
            status=status,
            noop=noop,
            recovered=recovered,
            error=error,
            adapter_error=adapter_error,
            post_time=post_time,
            poll_time=poll_time,
            total_time=total_time,
        )
        self._records.append(rec)
        return rec

    def _fail(
        self,
        *,
        phase: str,
        content_hash: str,
        task_id: str | None,
        status: str | None,
        adapter_error: str,
        post_time: float,
        poll_time: float,
        total_time: float,
    ) -> ManagedDocApplyError:
        """记录失败条目并构造 ManagedDocApplyError（不抛出，由调用方 raise）。"""
        self._record(
            phase=phase,
            content_hash=content_hash,
            task_id=task_id,
            status=status,
            noop=False,
            recovered=False,
            error=f"{phase}: {adapter_error}",
            adapter_error=adapter_error,
            post_time=post_time,
            poll_time=poll_time,
            total_time=total_time,
        )
        return ManagedDocApplyError(
            agent_name=getattr(self._client, "_agent_name", ""),
            doc_kind=self._doc_kind,
            task_id=task_id,
            phase=phase,
            adapter_error=adapter_error,
        )

    def apply_and_wait(self, content: str) -> AppliedDocument:
        start = self._clock()
        content_hash = _content_hash(content)

        # Step 2: hash no-op
        if content_hash == self._last_success_hash:
            total = self._clock() - start
            self._record(
                phase="noop",
                content_hash=content_hash,
                task_id=None,
                status=None,
                noop=True,
                recovered=False,
                error=None,
                adapter_error=None,
                post_time=0.0,
                poll_time=0.0,
                total_time=total,
            )
            return AppliedDocument(
                content_hash=content_hash,
                task_id=None,
                noop=True,
                final_status=None,
                recovered=False,
                post_time=0.0,
                poll_time=0.0,
                total_time=total,
            )

        # Step 3: POST update（永不自动重试）
        post_start = self._clock()
        post_timeout = self._remaining(start)
        try:
            post_result = self._client.start_managed_doc_update_sync(
                self._doc_kind, content, request_timeout=post_timeout
            )
        except httpx.TransportError as e:
            post_time = self._clock() - post_start
            return self._recover_snapshot_after_response_loss(
                content_hash=content_hash,
                start=start,
                post_time=post_time,
                post_error=e,
            )
        except AdapterError as e:
            post_time = self._clock() - post_start
            total = self._clock() - start
            raise self._fail(
                phase="failed_post",
                content_hash=content_hash,
                task_id=None,
                status=None,
                adapter_error=f"http {e.status_code}: {e}",
                post_time=post_time,
                poll_time=0.0,
                total_time=total,
            ) from e
        post_time = self._clock() - post_start

        if isinstance(post_result, AlreadyApplied):
            return self._handle_already_applied(
                content_hash=content_hash,
                post_result=post_result,
                start=start,
                post_time=post_time,
            )

        # UpdateStarted → 轮询
        return self._poll_until_terminal(
            content_hash=content_hash,
            task_id=post_result.task_id,
            start=start,
            post_time=post_time,
        )

    def _recover_snapshot_after_response_loss(
        self,
        *,
        content_hash: str,
        start: float,
        post_time: float,
        post_error: httpx.TransportError,
    ) -> AppliedDocument:
        """Confirm an uncertain single POST by snapshot only; never resend it."""
        poll_time = 0.0
        while True:
            remaining = self._remaining(start)
            if remaining <= 0:
                raise self._fail(
                    phase="deadline",
                    content_hash=content_hash,
                    task_id=None,
                    status=None,
                    adapter_error="deadline exceeded confirming uncertain update",
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=self._clock() - start,
                ) from post_error
            read_start = self._clock()
            try:
                snapshot = self._client.get_managed_doc_sync(
                    self._doc_kind, request_timeout=remaining
                )
            except (httpx.TransportError, AdapterError):
                poll_time += self._clock() - read_start
                if not self._sleep_within_budget(start):
                    continue
                continue
            poll_time += self._clock() - read_start
            if self._snapshot_confirmed(snapshot, content_hash):
                self._last_success_hash = content_hash
                total = self._clock() - start
                self._record(
                    phase="recovered",
                    content_hash=content_hash,
                    task_id=None,
                    status=None,
                    noop=False,
                    recovered=True,
                    error=None,
                    adapter_error=None,
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=total,
                )
                return AppliedDocument(
                    content_hash=content_hash,
                    task_id=None,
                    noop=False,
                    final_status=None,
                    recovered=True,
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=total,
                )
            if not snapshot.pending_apply:
                raise self._fail(
                    phase="failed_snapshot",
                    content_hash=content_hash,
                    task_id=None,
                    status=None,
                    adapter_error="uncertain update not confirmed by stable snapshot",
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=self._clock() - start,
                ) from post_error
            self._sleep_within_budget(start)

    def _handle_already_applied(
        self,
        *,
        content_hash: str,
        post_result: AlreadyApplied,
        start: float,
        post_time: float,
    ) -> AppliedDocument:
        """POST 200 AlreadyApplied → 再读 snapshot 确认。"""
        try:
            snap = self._client.get_managed_doc_sync(
                self._doc_kind, request_timeout=self._remaining(start)
            )
        except (httpx.TransportError, AdapterError) as e:
            total = self._clock() - start
            raise self._fail(
                phase="failed_snapshot",
                content_hash=content_hash,
                task_id=None,
                status=None,
                adapter_error=f"snapshot read error: {e}",
                post_time=post_time,
                poll_time=0.0,
                total_time=total,
            ) from e
        total = self._clock() - start
        if self._snapshot_confirmed(snap, content_hash):
            self._last_success_hash = content_hash
            self._record(
                phase="noop",
                content_hash=content_hash,
                task_id=None,
                status=None,
                noop=True,
                recovered=False,
                error=None,
                adapter_error=None,
                post_time=post_time,
                poll_time=0.0,
                total_time=total,
            )
            return AppliedDocument(
                content_hash=content_hash,
                task_id=None,
                noop=True,
                final_status=None,
                recovered=False,
                post_time=post_time,
                poll_time=0.0,
                total_time=total,
            )
        # diverged snapshot → fail
        raise self._fail(
            phase="failed_snapshot",
            content_hash=content_hash,
            task_id=None,
            status=None,
            adapter_error=(
                f"diverged snapshot: pending_apply={snap.pending_apply}, "
                f"file_revision={snap.file_revision}, applied_revision={snap.applied_revision}"
            ),
            post_time=post_time,
            poll_time=0.0,
            total_time=total,
        )

    @staticmethod
    def _snapshot_confirmed(snap: ManagedDocSnapshot, expected_hash: str) -> bool:
        return (
            snap.pending_apply is False
            and snap.file_revision == expected_hash
            and snap.applied_revision == expected_hash
        )

    def _poll_until_terminal(
        self,
        *,
        content_hash: str,
        task_id: str,
        start: float,
        post_time: float,
    ) -> AppliedDocument:
        """轮询 task 至终态。PENDING/RUNNING 继续；GET 临时错误继续轮询。"""
        poll_time = 0.0
        while True:
            if (
                self._cancellation_token is not None
                and self._cancellation_token.is_requested
                and not self._cancel_waiting_emitted
            ):
                self._cancel_waiting_emitted = True
                if self._phase_callback is not None:
                    self._phase_callback(
                        "log",
                        {
                            "level": "info",
                            "message": "Waiting for in-flight managed-doc apply",
                            "phase": "cancel_waiting_inflight",
                        },
                    )
            remaining = self._remaining(start)
            if remaining <= 0:
                total = self._clock() - start
                raise self._fail(
                    phase="deadline",
                    content_hash=content_hash,
                    task_id=task_id,
                    status=None,
                    adapter_error=f"deadline exceeded ({self._deadline}s)",
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=total,
                )
            poll_start = self._clock()
            try:
                task = self._client.get_managed_doc_task_sync(task_id, request_timeout=remaining)
            except httpx.TransportError:
                # 临时网络错误 → 继续轮询（sleep 耗时纳入 poll_time）
                poll_time += self._clock() - poll_start
                sleep_start = self._clock()
                slept = self._sleep_within_budget(start)
                poll_time += self._clock() - sleep_start
                if not slept:
                    total = self._clock() - start
                    raise self._fail(
                        phase="deadline",
                        content_hash=content_hash,
                        task_id=task_id,
                        status=None,
                        adapter_error="deadline exceeded during poll",
                        post_time=post_time,
                        poll_time=poll_time,
                        total_time=total,
                    )
                continue
            except AdapterError as e:
                poll_time += self._clock() - poll_start
                if e.status_code == 404:
                    # TASK_NOT_FOUND → 只读 snapshot 做revision核对
                    total = self._clock() - start
                    return self._handle_task_not_found(
                        content_hash=content_hash,
                        task_id=task_id,
                        start=start,
                        post_time=post_time,
                        poll_time=poll_time,
                    )
                if e.status_code in (502, 503, 504):
                    # 临时 → 继续轮询（sleep 耗时纳入 poll_time）
                    sleep_start = self._clock()
                    slept = self._sleep_within_budget(start)
                    poll_time += self._clock() - sleep_start
                    if not slept:
                        total = self._clock() - start
                        raise self._fail(
                            phase="deadline",
                            content_hash=content_hash,
                            task_id=task_id,
                            status=None,
                            adapter_error="deadline exceeded during poll",
                            post_time=post_time,
                            poll_time=poll_time,
                            total_time=total,
                        ) from e
                    continue
                # 其他 4xx/schema → 立即失败
                total = self._clock() - start
                raise self._fail(
                    phase="failed_poll",
                    content_hash=content_hash,
                    task_id=task_id,
                    status=None,
                    adapter_error=f"http {e.status_code}: {e}",
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=total,
                ) from e
            poll_time += self._clock() - poll_start

            outcome = self._classify_task(task, content_hash)
            if outcome.is_terminal_success:
                self._last_success_hash = content_hash
                total = self._clock() - start
                self._record(
                    phase="success",
                    content_hash=content_hash,
                    task_id=task_id,
                    status=task.status,
                    noop=False,
                    recovered=False,
                    error=None,
                    adapter_error=None,
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=total,
                )
                return AppliedDocument(
                    content_hash=content_hash,
                    task_id=task_id,
                    noop=False,
                    final_status=task.status,
                    recovered=False,
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=total,
                )
            if outcome.is_terminal_failure:
                total = self._clock() - start
                raise self._fail(
                    phase=outcome.phase,
                    content_hash=content_hash,
                    task_id=task_id,
                    status=task.status,
                    adapter_error=outcome.error,
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=total,
                )
            # PENDING / RUNNING → sleep poll_interval 后继续（sleep 耗时纳入 poll_time）
            sleep_start = self._clock()
            slept = self._sleep_within_budget(start)
            poll_time += self._clock() - sleep_start
            if not slept:
                total = self._clock() - start
                raise self._fail(
                    phase="deadline",
                    content_hash=content_hash,
                    task_id=task_id,
                    status=task.status,
                    adapter_error="deadline exceeded during poll",
                    post_time=post_time,
                    poll_time=poll_time,
                    total_time=total,
                )

    def _sleep_within_budget(self, start: float) -> bool:
        """sleep min(poll_interval, remaining)；返回 False 若已无预算。"""
        remaining = self._remaining(start)
        if remaining <= 0:
            return False
        self._sleeper(min(self._poll_interval, remaining))
        return True

    def _classify_task(self, task: TaskState, expected_hash: str) -> _TaskOutcome:
        if task.status == "SUCCEEDED":
            if task.revision == expected_hash:
                return _TaskOutcome(is_terminal_success=True)
            return _TaskOutcome(
                is_terminal_failure=True,
                phase="failed_poll",
                error=f"revision mismatch: task={task.revision} expected={expected_hash}",
            )
        if task.status == "FAILED":
            return _TaskOutcome(
                is_terminal_failure=True,
                phase="failed_poll",
                error=f"task FAILED: {task.last_error or 'unknown'}",
            )
        if task.status in ("PENDING", "RUNNING"):
            return _TaskOutcome()  # 非终态，继续
        # 未知终态
        return _TaskOutcome(
            is_terminal_failure=True,
            phase="unknown",
            error=f"unknown terminal status: {task.status}",
        )

    def _handle_task_not_found(
        self,
        *,
        content_hash: str,
        task_id: str,
        start: float,
        post_time: float,
        poll_time: float,
    ) -> AppliedDocument:
        """GET 404 TASK_NOT_FOUND → 只读 snapshot 做revision核对，不重发 POST。"""
        try:
            snap = self._client.get_managed_doc_sync(
                self._doc_kind, request_timeout=self._remaining(start)
            )
        except (httpx.TransportError, AdapterError) as e:
            total = self._clock() - start
            raise self._fail(
                phase="failed_snapshot",
                content_hash=content_hash,
                task_id=task_id,
                status=None,
                adapter_error=f"snapshot read error after TASK_NOT_FOUND: {e}",
                post_time=post_time,
                poll_time=poll_time,
                total_time=total,
            ) from e
        total = self._clock() - start
        if self._snapshot_confirmed(snap, content_hash):
            self._last_success_hash = content_hash
            self._record(
                phase="recovered",
                content_hash=content_hash,
                task_id=task_id,
                status=None,
                noop=False,
                recovered=True,
                error=None,
                adapter_error=None,
                post_time=post_time,
                poll_time=poll_time,
                total_time=total,
            )
            return AppliedDocument(
                content_hash=content_hash,
                task_id=task_id,
                noop=False,
                final_status=None,
                recovered=True,
                post_time=post_time,
                poll_time=poll_time,
                total_time=total,
            )
        # 不匹配 → fail-fast，不重发 POST
        raise self._fail(
            phase="failed_snapshot",
            content_hash=content_hash,
            task_id=task_id,
            status=None,
            adapter_error=(
                f"TASK_NOT_FOUND and snapshot not confirming: pending_apply="
                f"{snap.pending_apply}, file_revision={snap.file_revision}, "
                f"applied_revision={snap.applied_revision}"
            ),
            post_time=post_time,
            poll_time=poll_time,
            total_time=total,
        )


@dataclass(frozen=True)
class _TaskOutcome:
    is_terminal_success: bool = False
    is_terminal_failure: bool = False
    phase: str = ""
    error: str = ""
