"""Fatal optimization error marker + structured managed-doc apply error.

``FatalOptimizationError`` is the generic marker for errors that must abort the
optimization job: ``ComposedCallbacks`` re-raises any subclass of it instead of
swallowing it (ordinary progress/observability callback exceptions are still
best-effort logged and skipped).

``ManagedDocApplyError`` is the structured exception raised when a managed-doc
apply fails (task FAILED / unknown terminal state / revision mismatch / deadline
exceeded / TASK_NOT_FOUND not confirmable). It carries the context needed for
log/artifact records without leaking the full document content.
"""

from __future__ import annotations


class FatalOptimizationError(Exception):
    """Generic marker for errors that must abort the optimization job.

    ``ComposedCallbacks`` re-raises any subclass of this marker; ordinary
    callback exceptions are still best-effort logged and skipped. Subclass to
    mark a new category of fatal failures (e.g. ``ManagedDocApplyError``).
    """


class ArtifactPersistenceError(FatalOptimizationError):
    """An artifact could not be durably and atomically published."""

    def __init__(self, path: str, cause: BaseException) -> None:
        self.path = path
        self.cause = cause
        super().__init__(f"artifact persistence failed: path={path} error={cause}")


class ArtifactConsistencyError(FatalOptimizationError):
    """Gate/validation evidence is missing or belongs to a different epoch."""

    def __init__(self, reason: str) -> None:
        self.reason = reason
        super().__init__(f"artifact consistency failed: reason={reason}")


class ValidationCoverageError(FatalOptimizationError):
    """Validation evidence is incomplete or base/candidate identities differ."""

    def __init__(
        self,
        *,
        attempted_count: int,
        evaluated_count: int,
        min_success_ratio: float,
        reason: str,
    ) -> None:
        self.attempted_count = attempted_count
        self.evaluated_count = evaluated_count
        self.min_success_ratio = min_success_ratio
        self.reason = reason
        coverage = evaluated_count / attempted_count if attempted_count else 0.0
        super().__init__(
            "validation coverage failed: "
            f"reason={reason} attempted={attempted_count} evaluated={evaluated_count} "
            f"coverage={coverage:.6f} required={min_success_ratio:.6f}"
        )


class ManagedDocApplyError(FatalOptimizationError):
    """Apply of a managed-doc failed — abort the job, keep baseline artifact.

    Raised by ``ManagedDocApplier`` / ``ManagedDocOperator`` on:
    task ``FAILED``, unknown terminal state, revision mismatch, total deadline
    exceeded, or ``TASK_NOT_FOUND`` not confirmable via revision. No graceful
    skip / auto-retry / auto-compensation: surface the structured context so an
    operator can inspect adapter state and recover from the baseline manually.

    Fields:
        agent_name: the optimization job's agent name.
        doc_kind: the exact managed-doc kind being applied.
        task_id: adapter task id if one was obtained (``None`` when the POST
            itself failed before a task was created).
        phase: apply phase that failed (``"post"`` / ``"poll"`` / ``"snapshot"``).
        adapter_error: the underlying adapter/transport error message.
    """

    def __init__(
        self,
        *,
        agent_name: str,
        doc_kind: str,
        task_id: str | None,
        phase: str,
        adapter_error: str,
    ) -> None:
        self.agent_name = agent_name
        self.doc_kind = doc_kind
        self.task_id = task_id
        self.phase = phase
        self.adapter_error = adapter_error
        super().__init__(
            f"managed-doc apply failed: agent={agent_name} doc_kind={doc_kind} "
            f"task_id={task_id} phase={phase} adapter_error={adapter_error}"
        )


class ManagedDocBaselineError(FatalOptimizationError):
    """job-start baseline for a managed-doc failed — abort before any rollout.

    Raised by ``optimizer_runner`` (spec F7) when the job-start snapshot does
    not satisfy the baseline invariants: ``apply_mode != "restart"``,
    ``pending_apply == true``, ``file_revision != applied_revision``, or the
    configured apply deadline is below ``max_task_seconds + 10s``. No global
    ``restore`` is called and no rollout starts — the baseline is the contract
    that every subsequent apply is measured against, so an unconfirmed baseline
    must abort rather than train against a diverged state.

    Fields:
        agent_name: the optimization job's agent name.
        doc_kind: the exact managed-doc kind being baselined.
        reason: short invariant that failed (``"pending_apply"`` /
            ``"apply_mode"`` / ``"revision_mismatch"`` / ``"deadline"``).
        diagnostics: human-readable snapshot + deadline diagnostics for the
            failure artifact (no full document content).
    """

    def __init__(
        self,
        *,
        agent_name: str,
        doc_kind: str,
        reason: str,
        diagnostics: str,
    ) -> None:
        self.agent_name = agent_name
        self.doc_kind = doc_kind
        self.reason = reason
        self.diagnostics = diagnostics
        super().__init__(
            f"managed-doc baseline failed: agent={agent_name} doc_kind={doc_kind} "
            f"reason={reason} diagnostics={diagnostics}"
        )


class ManagedDocBaselineChangedError(FatalOptimizationError):
    """The Adapter revision no longer matches Studio's durable baseline."""

    code = "MANAGED_DOC_BASELINE_CHANGED"

    def __init__(
        self,
        *,
        agent_name: str,
        doc_kind: str,
        expected_revision: str,
        file_revision: str | None,
        applied_revision: str | None,
        pending_apply: bool,
    ) -> None:
        self.agent_name = agent_name
        self.doc_kind = doc_kind
        self.expected_revision = expected_revision
        self.file_revision = file_revision
        self.applied_revision = applied_revision
        self.pending_apply = pending_apply
        super().__init__(
            "managed-doc baseline changed: "
            f"agent={agent_name} doc_kind={doc_kind} expected_revision={expected_revision} "
            f"file_revision={file_revision} applied_revision={applied_revision} "
            f"pending_apply={pending_apply}"
        )


class CancelRollbackError(FatalOptimizationError):
    """A cancellation rollback failed or exceeded its deadline."""

    def __init__(self, *, code: str, diagnostics: str) -> None:
        self.code = code
        self.diagnostics = diagnostics
        super().__init__(f"cancel rollback failed: code={code} diagnostics={diagnostics}")
