"""Deterministic trajectory compaction shared by every LLM stage and artifacts."""

from __future__ import annotations

import copy
import json
import math
from collections.abc import Mapping
from dataclasses import asdict, dataclass, is_dataclass
from types import MappingProxyType
from typing import Any, Literal


@dataclass(frozen=True)
class TrajectoryCompactionPolicy:
    stage: Literal["evaluator", "goal_generator", "reflect", "artifact_validation"]
    preserve_goal: bool = True
    preserve_final_answer: bool = True
    preserve_errors: bool = True
    preserve_head_tail: bool = True
    preserve_evaluation_reason: bool = False
    prioritize_skill_related: bool = False


@dataclass(frozen=True)
class TrajectoryCompactionContext:
    task_goal: str | None = None
    evaluation_score: float | None = None
    evaluation_reason: str | None = None
    target_skills: tuple[str, ...] = ()


@dataclass(frozen=True)
class TrajectoryCompactionResult:
    text: str
    estimated_tokens_before: int
    estimated_tokens_after: int
    compacted: bool
    metadata: Mapping[str, Any]


class TrajectoryCompactionError(Exception):
    """Minimal causally complete trajectory cannot fit the allocated budget."""


def compact_trajectory(
    trajectory: Any,
    *,
    policy: TrajectoryCompactionPolicy,
    context: TrajectoryCompactionContext,
    token_budget: int,
) -> TrajectoryCompactionResult:
    """Compact tool results without deleting calls, arguments, or causal pairing."""
    if token_budget < 1:
        raise TrajectoryCompactionError("token_budget must be positive")
    original = _to_plain(trajectory)
    if policy.stage != "artifact_validation":
        original = _prompt_safe_view(original)
    if isinstance(original, dict):
        if policy.preserve_goal and context.task_goal:
            original["task_goal"] = context.task_goal
        if policy.preserve_evaluation_reason:
            original["evaluation"] = {
                "score": context.evaluation_score,
                "reason": context.evaluation_reason,
            }
        if policy.prioritize_skill_related and context.target_skills:
            original["target_skills"] = list(context.target_skills)
    before_text = _dump(original)
    view = copy.deepcopy(original)
    messages = _messages(view)
    _reject_orphan_tool_results(messages)
    truncated = 0
    omitted = 0
    tool_message_indexes: list[int] = []
    error_indexes: set[int] = set()

    normal_limit = 800 if policy.stage == "reflect" else 1200
    for index, message in enumerate(messages):
        if not isinstance(message, dict) or message.get("role") != "tool":
            continue
        tool_message_indexes.append(index)
        content = message.get("content")
        if not isinstance(content, str):
            continue
        is_error = _is_error_result(message, content)
        if is_error:
            error_indexes.add(index)
        limit = 2000 if is_error else normal_limit
        if len(content) > limit:
            message["content"] = _head_tail(content, limit)
            truncated += 1

    text = _dump(view)
    tail_tool_index = tool_message_indexes[-1] if tool_message_indexes else None
    for index in tool_message_indexes:
        if _estimate(text) <= token_budget:
            break
        if index in error_indexes or index == tail_tool_index:
            continue
        message = messages[index]
        content = message.get("content")
        if not isinstance(content, str) or content.startswith("[TOOL_RESULT_OMITTED"):
            continue
        message["content"] = f"[TOOL_RESULT_OMITTED original_chars={len(content)}]"
        omitted += 1
        text = _dump(view)

    after = _estimate(text)
    if after > token_budget:
        raise TrajectoryCompactionError(
            "prompt_budget_exceeded: minimal trajectory requires "
            f"{after} tokens, budget={token_budget}"
        )
    metadata = MappingProxyType(
        {
            "tool_results_truncated": truncated,
            "tool_results_omitted": omitted,
            "token_budget": token_budget,
            "stage": policy.stage,
            "task_goal_present": context.task_goal is not None,
        }
    )
    return TrajectoryCompactionResult(
        text=text,
        estimated_tokens_before=_estimate(before_text),
        estimated_tokens_after=after,
        compacted=truncated > 0 or omitted > 0,
        metadata=metadata,
    )


def _messages(value: Any) -> list[Any]:
    if isinstance(value, dict):
        messages = value.get("messages")
        if isinstance(messages, list):
            return messages
    if isinstance(value, list):
        return value
    raise TrajectoryCompactionError("trajectory has no message sequence")


def _to_plain(value: Any) -> Any:
    if hasattr(value, "steps") and isinstance(getattr(value, "steps"), list):
        return _optimizer_trajectory_view(value)
    if hasattr(value, "model_dump"):
        return value.model_dump()
    if is_dataclass(value) and not isinstance(value, type):
        return asdict(value)
    if isinstance(value, Mapping):
        return {str(key): _to_plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_to_plain(item) for item in value]
    return value


def _optimizer_trajectory_view(trajectory: Any) -> dict[str, Any]:
    """Adapt upstream optimizer steps into the shared ordered message view."""
    messages: list[Any] = []
    for index, step in enumerate(trajectory.steps):
        kind = getattr(step, "kind", "")
        detail = getattr(step, "detail", None)
        if kind == "llm":
            for message in getattr(detail, "messages", []) or []:
                messages.append(_to_plain(message))
            response = getattr(detail, "response", None)
            if response is not None:
                messages.append(_to_plain(response))
            continue
        if kind != "tool":
            continue
        call_id = f"trajectory-tool-{index}"
        tool_name = str(getattr(detail, "tool_name", "") or "")
        arguments = copy.deepcopy(getattr(detail, "call_args", ""))
        result = copy.deepcopy(getattr(detail, "call_result", ""))
        messages.extend(
            [
                {
                    "role": "assistant",
                    "tool_calls": [
                        {
                            "id": call_id,
                            "function": {"name": tool_name, "arguments": arguments},
                        }
                    ],
                },
                {
                    "role": "tool",
                    "tool_call_id": call_id,
                    "name": tool_name,
                    "content": result,
                },
            ]
        )
    return {"messages": messages}


def _dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)


def _estimate(text: str) -> int:
    return math.ceil(len(text) / 2.0)


def _head_tail(content: str, limit: int) -> str:
    marker = f"[TOOL_RESULT_TRUNCATED original_chars={len(content)}]"
    remaining = max(2, limit - len(marker))
    head = remaining // 2
    tail = remaining - head
    return f"{content[:head]}{marker}{content[-tail:]}"


def _is_error_result(message: dict[str, Any], content: str) -> bool:
    status = str(message.get("status", "")).casefold()
    lowered = content.casefold()
    return status in {"error", "failed", "timeout"} or any(
        marker in lowered for marker in ("error", "exception", "timeout", "failed", "重试")
    )


def _prompt_safe_view(value: Any) -> Any:
    """Drop non-contract message diagnostics while preserving full tool arguments."""
    if not isinstance(value, dict) or not isinstance(value.get("messages"), list):
        return value
    result: dict[str, Any] = {}
    if "summary" in value:
        result["summary"] = value["summary"]
    messages: list[dict[str, Any]] = []
    for raw_message in value["messages"]:
        if not isinstance(raw_message, dict):
            continue
        message = {
            key: copy.deepcopy(raw_message[key])
            for key in ("role", "content", "name", "tool_call_id", "status")
            if key in raw_message
        }
        raw_calls = raw_message.get("tool_calls")
        if isinstance(raw_calls, list):
            calls: list[dict[str, Any]] = []
            for raw_call in raw_calls:
                if not isinstance(raw_call, dict):
                    continue
                call = {
                    key: copy.deepcopy(raw_call[key]) for key in ("id", "type") if key in raw_call
                }
                function = raw_call.get("function")
                if isinstance(function, dict):
                    call["function"] = {
                        key: copy.deepcopy(function[key])
                        for key in ("name", "arguments")
                        if key in function
                    }
                calls.append(call)
            message["tool_calls"] = calls
        messages.append(message)
    result["messages"] = messages
    return result


def _reject_orphan_tool_results(messages: list[Any]) -> None:
    call_ids: set[str] = set()
    for message in messages:
        if not isinstance(message, dict):
            continue
        calls = message.get("tool_calls")
        if isinstance(calls, list):
            for call in calls:
                if isinstance(call, dict) and isinstance(call.get("id"), str) and call["id"]:
                    call_ids.add(call["id"])
        if message.get("role") == "tool":
            result_id = message.get("tool_call_id")
            if isinstance(result_id, str) and result_id:
                if result_id not in call_ids:
                    raise TrajectoryCompactionError(f"orphan tool result: tool_call_id={result_id}")


__all__ = [
    "TrajectoryCompactionContext",
    "TrajectoryCompactionError",
    "TrajectoryCompactionPolicy",
    "TrajectoryCompactionResult",
    "compact_trajectory",
]
