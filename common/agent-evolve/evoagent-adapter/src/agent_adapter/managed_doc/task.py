"""In-memory task registry for async managed-doc apply (T6).

TaskState is adapter-memory only (spec D7-bis): never persisted. Crash recovery
relies on D8 idempotent re-send, not on task restoration. Completed tasks
(SUCCEEDED/FAILED) are evicted after ``ttl_seconds``; in-flight tasks are not.

Time is injected via a ``now`` callable so TTL can be tested without freezegun.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass, field, fields as dataclass_fields
from datetime import datetime, timezone
from enum import Enum

from agent_adapter.managed_doc.storage import DocStorageError


class TaskNotFoundError(DocStorageError):
    """task_id unknown or already expired/cleaned up."""


class TaskStatus(Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


_COMPLETED = {TaskStatus.SUCCEEDED, TaskStatus.FAILED}


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


@dataclass
class TaskState:
    """Mutable snapshot of one apply task (spec §7.3)."""

    task_id: str
    doc_kind: str
    action: str
    created_at: datetime
    updated_at: datetime
    status: TaskStatus = TaskStatus.PENDING
    attempts: int = 0
    down_seen: bool | None = None
    revision: str | None = None
    pending_apply: bool = False
    last_error: str | None = None

    def to_dict(self) -> dict[str, object]:
        """Project to the GET /tasks/{id} response shape (spec §7.3)."""
        return {
            "task_id": self.task_id,
            "status": self.status.value,
            "doc_kind": self.doc_kind,
            "action": self.action,
            "attempts": self.attempts,
            "down_seen": self.down_seen,
            "revision": self.revision,
            "pending_apply": self.pending_apply,
            "last_error": self.last_error,
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
        }


@dataclass
class TaskRegistry:
    """dict[task_id → TaskState] + TTL eviction of completed tasks."""

    ttl_seconds: int = 600
    now: Callable[[], datetime] = field(default=_utcnow)
    _tasks: dict[str, TaskState] = field(default_factory=dict)

    def create(
        self,
        *,
        doc_kind: str,
        action: str,
        revision: str | None = None,
        pending_apply: bool = False,
    ) -> TaskState:
        task_id = f"t_{uuid.uuid4().hex[:12]}"
        ts = self.now()
        state = TaskState(
            task_id=task_id,
            doc_kind=doc_kind,
            action=action,
            created_at=ts,
            updated_at=ts,
            revision=revision,
            pending_apply=pending_apply,
        )
        self._tasks[task_id] = state
        return state

    def get(self, task_id: str) -> TaskState:
        state = self._tasks.get(task_id)
        if state is None:
            raise TaskNotFoundError(f"task '{task_id}' not found or expired")
        # Lazy TTL: completed tasks past ttl are evicted on read.
        if state.status in _COMPLETED and self._expired(state):
            del self._tasks[task_id]
            raise TaskNotFoundError(f"task '{task_id}' not found or expired")
        return state

    def update(self, task_id: str, **fields: object) -> TaskState:
        state = self._tasks.get(task_id)
        if state is None:
            raise TaskNotFoundError(f"task '{task_id}' not found or expired")
        # Whitelist against the dataclass fields so a typo'd kwarg is rejected
        # loudly instead of being silently dropped (S2).
        allowed = {f.name for f in dataclass_fields(TaskState)}
        unknown = set(fields) - allowed
        if unknown:
            raise ValueError(f"unknown TaskState fields: {sorted(unknown)}")
        for key, value in fields.items():
            setattr(state, key, value)
        state.updated_at = self.now()
        return state

    def cleanup_expired(self) -> list[str]:
        """Evict completed tasks past TTL. Returns the removed task_ids."""
        removed: list[str] = []
        for tid, state in list(self._tasks.items()):
            if state.status in _COMPLETED and self._expired(state):
                del self._tasks[tid]
                removed.append(tid)
        return removed

    def _expired(self, state: TaskState) -> bool:
        age = (self.now() - state.updated_at).total_seconds()
        return age > self.ttl_seconds
