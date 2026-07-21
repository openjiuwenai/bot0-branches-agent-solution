"""Transport DTO for managed-doc adapter communication.

Isolated small leaf module: holds the frozen dataclasses returned by the three
synchronous transport methods on ``AdapterClient`` (``get_managed_doc_sync``,
``start_managed_doc_update_sync``, ``get_managed_doc_task_sync``). The transport
layer only does HTTP calls + response parsing — no task state machine, deadline,
or hash dedup (those belong to ``ManagedDocApplier``).

All DTOs are ``@dataclass(frozen=True)``; sequence fields use ``tuple`` so a
frozen shell never holds a mutable ``list``. ``TaskState.status`` uses the
adapter's original four states verbatim (``PENDING | RUNNING | SUCCEEDED |
FAILED``); state folding is the Applier's job, this module does not invent a
second ``running/done/failed`` vocabulary.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True)
class ManagedDocSnapshot:
    """Snapshot of a managed doc at a point in time (GET /managed-docs/{kind})."""

    content: str
    file_revision: str | None
    applied_revision: str | None
    pending_apply: bool
    apply_mode: str
    max_task_seconds: float


@dataclass(frozen=True)
class UpdateStarted:
    """Adapter accepted the update and started a task (HTTP 202)."""

    task_id: str


@dataclass(frozen=True)
class AlreadyApplied:
    """Adapter reported the content is already applied (HTTP 200).

    Only the adapter's ``.meta`` judgment — the Applier must still re-read the
    snapshot to confirm file/applied revision before treating it as no-op.
    """

    revision: str


# Union alias for the two possible POST update outcomes (200 vs 202).
ManagedDocUpdateResult = UpdateStarted | AlreadyApplied


@dataclass(frozen=True)
class TaskState:
    """State of an adapter managed-doc task (GET /tasks/{task_id})."""

    status: Literal["PENDING", "RUNNING", "SUCCEEDED", "FAILED"]
    task_id: str
    revision: str | None
    pending_apply: bool
    last_error: str | None
    attempts: int
    down_seen: bool | None
    created_at: str | None
    updated_at: str | None
