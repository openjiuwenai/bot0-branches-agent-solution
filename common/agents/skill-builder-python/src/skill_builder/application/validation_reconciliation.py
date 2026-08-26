# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Reconcile authoring preflight with independent delivery validation."""

from __future__ import annotations

from typing import Any

from skill_builder.application.validation_status import project_validation_status


VALIDATION_INCONSISTENCY_ID = "validation_inconsistency"


def reconcile_preflight_and_delivery(
    *,
    preflight_result: dict[str, Any] | None,
    delivery_result: dict[str, Any],
) -> dict[str, Any]:
    """Return the authoritative delivery result with consistency protection.

    Delivery validation remains authoritative.  The only unsafe disagreement
    is a blocking preflight followed by a ready delivery result for the exact
    same package: that cannot be silently projected as ready and requires
    review of the validation boundary itself.
    """

    if not isinstance(preflight_result, dict):
        return dict(delivery_result)
    preflight = project_validation_status(preflight_result)
    delivery = project_validation_status(delivery_result)
    if not preflight.blocked or not delivery.ready:
        return dict(delivery_result)

    result = dict(delivery_result)
    findings = [
        dict(item)
        for item in result.get("findings") or []
        if isinstance(item, dict)
    ]
    if not any(str(item.get("id") or "") == VALIDATION_INCONSISTENCY_ID for item in findings):
        findings.append(
            {
                "id": VALIDATION_INCONSISTENCY_ID,
                "rootCauseId": VALIDATION_INCONSISTENCY_ID,
                "severity": "fail",
                "category": "validation_consistency",
                "audience": "developer",
                "repairable": False,
                "title": "验收结论不一致",
                "message": (
                    "同一 PackageRevision 的生成预检为阻断，但独立交付验收为通过；"
                    "平台已停止自动放行，需要检查验收环境或规则一致性。"
                ),
            }
        )
    blocker_ids = list(
        dict.fromkeys(
            [
                *(str(item) for item in result.get("blockingFailureIds") or [] if str(item)),
                VALIDATION_INCONSISTENCY_ID,
            ]
        )
    )
    diagnostics = (
        dict(result.get("diagnostics"))
        if isinstance(result.get("diagnostics"), dict)
        else {}
    )
    diagnostics["validationConsistency"] = {
        "preflightStatus": preflight.run_status,
        "preflightDeliveryStatus": preflight.delivery_status,
        "deliveryStatus": delivery.run_status,
        "deliveryDeliveryStatus": delivery.delivery_status,
    }
    summary = (
        dict(result.get("summary"))
        if isinstance(result.get("summary"), dict)
        else {}
    )
    summary.update(
        {
            "status": "fail",
            "title": "验收结论需要复核",
            "message": "生成预检与独立交付验收结论不一致，当前版本未自动放行。",
            "nextSteps": ["检查预检与独立验收的环境、规则和输入是否一致。"],
        }
    )
    result.update(
        {
            "status": "fail",
            "outcome": "needs_review",
            "deliveryStatus": "blocked",
            "blockingFailureIds": blocker_ids,
            "blockingCheckIds": blocker_ids,
            "findings": findings,
            "diagnostics": diagnostics,
            "summary": summary,
        }
    )
    return result


__all__ = [
    "VALIDATION_INCONSISTENCY_ID",
    "reconcile_preflight_and_delivery",
]
