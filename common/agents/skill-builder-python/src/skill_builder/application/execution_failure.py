# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Core-owned classification and projection of operational failures."""

from __future__ import annotations

from skill_builder.domain.execution import ExecutionFailure


def execution_failure_from_exception(exc: Exception) -> ExecutionFailure:
    message = str(exc) or exc.__class__.__name__
    code = str(getattr(exc, "code", "") or "agent_runtime_failed").strip()
    category = "platform_runtime"
    if code.startswith("candidate_") or code.startswith("scenario_"):
        category = "candidate_lifecycle"
    developer_message = message if message.startswith(f"{code}:") else f"{code}: {message}"
    return ExecutionFailure(
        code=code,
        category=category,
        retryable=category == "platform_runtime",
        repairable=False,
        user_message=(
            "平台运行环境未完成本次 Skill 抽取，请稍后重试。"
            if category == "platform_runtime"
            else "候选 Skill 生命周期未完成。"
        ),
        developer_message=developer_message,
        details={"exception_type": exc.__class__.__name__},
    )

__all__ = [
    "execution_failure_from_exception",
]
