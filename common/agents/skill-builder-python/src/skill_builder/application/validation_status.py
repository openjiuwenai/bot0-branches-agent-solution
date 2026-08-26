# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Canonical projection from validation results to lifecycle disposition."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal


TerminalStatus = Literal["ready", "needs_review", "failed"]


@dataclass(frozen=True, slots=True)
class ValidationStatusProjection:
    run_status: str
    delivery_status: str
    blocking_failure_ids: tuple[str, ...]
    terminal_status: TerminalStatus

    @property
    def ready(self) -> bool:
        return self.terminal_status == "ready"

    @property
    def blocked(self) -> bool:
        return bool(self.blocking_failure_ids) or self.delivery_status == "blocked"

    @property
    def failed(self) -> bool:
        return self.terminal_status == "failed"


def project_validation_status(
    validation_result: dict[str, Any] | None,
    *,
    artifact_available: bool = True,
    operational_error: bool = False,
) -> ValidationStatusProjection:
    """Interpret the single minimum-package validation result.

    Deterministic validation blockers and operational errors are failed state.
    ``needs_review`` is reserved for a non-blocking result that explicitly
    requires human confirmation. Hosts only consume this projection and must
    not create a second validation policy.
    """

    value = validation_result if isinstance(validation_result, dict) else {}
    run_status = str(value.get("status") or "").strip().lower()
    delivery_status = str(value.get("deliveryStatus") or "").strip().lower()
    blockers = tuple(
        dict.fromkeys(
            str(item).strip()
            for item in value.get("blockingFailureIds") or []
            if str(item).strip()
        )
    )
    blocked = delivery_status == "blocked" or bool(blockers)
    if operational_error:
        terminal_status: TerminalStatus = "failed"
    elif blocked:
        terminal_status = "failed"
    elif delivery_status == "ready" and run_status in {"pass", "warn"} and artifact_available:
        terminal_status = "ready"
    else:
        terminal_status = "needs_review"
    return ValidationStatusProjection(
        run_status=run_status,
        delivery_status=delivery_status,
        blocking_failure_ids=blockers,
        terminal_status=terminal_status,
    )


__all__ = [
    "ValidationStatusProjection",
    "project_validation_status",
]
