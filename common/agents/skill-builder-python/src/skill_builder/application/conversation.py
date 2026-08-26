# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Application projection for one conversational turn.

Agent output describes the current turn.  Validation describes the deliverable
workspace.  They are deliberately separate so an old delivery failure cannot
hide a clarification request, and a read-only answer can never be announced as
an applied modification.
"""

from __future__ import annotations

from typing import Any

from skill_builder.domain.conversation import TurnResult, TurnStatus


def _clean_text(value: Any) -> str:
    return str(value or "").strip()


def _pending_lines(values: Any) -> list[str]:
    result: list[str] = []
    for value in values if isinstance(values, list) else []:
        if isinstance(value, dict):
            text = _clean_text(value.get("title") or value.get("message") or value.get("decision_id"))
        else:
            text = _clean_text(value)
        if text and text not in result:
            result.append(text)
    return result


def project_agent_turn_result(
    final_response: dict[str, Any] | None,
    *,
    changed_paths: list[str] | tuple[str, ...] = (),
) -> TurnResult:
    response = final_response if isinstance(final_response, dict) else {}
    changed = tuple(str(value) for value in changed_paths if str(value).strip())
    raw_status = _clean_text(response.get("status")).lower()
    pending = tuple(response.get("pending_decisions") if isinstance(response.get("pending_decisions"), list) else [])
    suggested = _clean_text(response.get("suggested_next_message"))
    answer = _clean_text(response.get("summary") or suggested)

    if raw_status in {"needs_input", "waiting_for_user", "clarify"}:
        status = TurnStatus.NEEDS_INPUT
    elif raw_status in {"failed", "error"}:
        status = TurnStatus.FAILED
    elif changed:
        status = TurnStatus.CHANGES_APPLIED
    else:
        status = TurnStatus.ANSWERED

    if status == TurnStatus.NEEDS_INPUT:
        details = _pending_lines(list(pending))
        if details:
            answer = (answer or "需要补充确认后才能继续。") + "\n\n待确认：\n" + "\n".join(f"- {item}" for item in details)
        if suggested and suggested not in answer:
            answer = (answer + "\n\n" + suggested).strip()
    elif not answer:
        answer = "本轮已完成。" if changed else "本轮未产生文件修改。"

    return TurnResult(
        status=status,
        answer=answer,
        pending_decisions=pending,
        changed_paths=changed,
        suggested_next_message=suggested,
        metadata={"agentStatus": raw_status or None},
    )


__all__ = ["project_agent_turn_result"]
