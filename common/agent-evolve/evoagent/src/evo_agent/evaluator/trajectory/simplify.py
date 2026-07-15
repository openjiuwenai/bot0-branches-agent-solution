"""Deterministic trajectory simplification for evaluator prompts."""

from __future__ import annotations

import json
from typing import Any

from evo_agent.evaluator.domain.models import (
    EvaluationStep,
    EvaluationTrajectory,
    StandardTrajectory,
)

_CONTENT_LIMIT = 1000
_ARGUMENT_LIMIT = 1000
_TOOL_RESULT_LIMIT = 1200
_READ_FILE_RESULT_LIMIT = 2000
_TRUNCATION_MARKER = "... [truncated]"


def simplify_trajectory(
    trajectory: StandardTrajectory | dict[str, Any],
) -> EvaluationTrajectory:
    """Build a compact prompt-only view without modifying the source trajectory.

    Accepts either a ``StandardTrajectory`` object or a raw dict (normalized
    trace). Accepting a raw dict avoids the ``StandardTrajectory.model_validate``
    round-trip (B3 / #5) and tolerates extra fields the strict model would reject.

    Type guard: reject anything that is neither a ``StandardTrajectory`` nor a
    ``dict`` up front — B3 removed the ``model_validate`` schema guard, so without
    this check a malformed trajectory (e.g. a stray string) would silently yield
    empty steps instead of surfacing the structural mismatch.
    """
    if not isinstance(trajectory, (StandardTrajectory, dict)):
        raise TypeError(
            f"simplify_trajectory: unexpected trajectory type "
            f"{type(trajectory).__name__}; expected StandardTrajectory or dict"
        )
    steps: list[EvaluationStep] = []
    tool_names_by_id: dict[str, str] = {}

    messages = _traj_messages(trajectory)
    for index, message in enumerate(messages):
        content = _stringify_content(_msg_get(message, "content"))

        role = _msg_get(message, "role")
        if role in {"user", "assistant"}:
            parsed_calls = [_parse_tool_call(tc) for tc in (_msg_get(message, "tool_calls") or [])]
            parsed_calls = [(n, a, tid) for n, a, tid in parsed_calls if n is not None]

            if parsed_calls:
                # First tool_call absorbs the assistant content.
                first_name, first_args, first_id = parsed_calls[0]
                assert first_name is not None  # filtered by list comprehension above
                if first_id is not None:
                    tool_names_by_id[first_id] = first_name

                steps.append(
                    EvaluationStep(
                        index=index,
                        role=role,
                        content=_truncate(content, _CONTENT_LIMIT) if content else None,
                        tool_name=first_name,
                        tool_arguments=first_args,
                    )
                )

                # Remaining tool_calls get their own steps (no content).
                for name, args, tid in parsed_calls[1:]:
                    assert name is not None  # filtered by list comprehension above
                    if tid is not None:
                        tool_names_by_id[tid] = name
                    steps.append(
                        EvaluationStep(
                            index=index,
                            role=role,
                            tool_name=name,
                            tool_arguments=args,
                        )
                    )
            elif content:
                steps.append(
                    EvaluationStep(
                        index=index,
                        role=role,
                        content=_truncate(content, _CONTENT_LIMIT),
                    )
                )

        if role == "tool" and content:
            tool_name = tool_names_by_id.get(_msg_get(message, "tool_call_id") or "")
            steps.append(
                EvaluationStep(
                    index=index,
                    role="tool",
                    tool_name=tool_name,
                    tool_result=_simplify_tool_result(tool_name, content),
                )
            )

    return EvaluationTrajectory(steps=steps)


def _traj_messages(trajectory: StandardTrajectory | dict[str, Any]) -> list[Any]:
    """Return the messages list from a trajectory object or raw dict."""
    if isinstance(trajectory, dict):
        return trajectory.get("messages") or []
    return trajectory.messages


def _msg_get(message: Any, key: str) -> Any:
    """Read a field from a message object or dict (duck-typed)."""
    if isinstance(message, dict):
        return message.get(key)
    return getattr(message, key, None)


def _stringify_content(content: Any) -> str | None:
    if content is None:
        return None
    if isinstance(content, str):
        stripped = content.strip()
        return stripped or None
    return json.dumps(content, ensure_ascii=False, default=str)


def _parse_tool_call(
    tool_call: dict[str, Any],
) -> tuple[str | None, dict[str, Any] | str | None, str | None]:
    function = tool_call.get("function")
    if not isinstance(function, dict):
        return None, None, None

    name = function.get("name")
    if not isinstance(name, str) or not name:
        return None, None, None

    arguments = function.get("arguments")
    parsed_arguments = _parse_arguments(arguments)
    tool_call_id = tool_call.get("id")
    return name, parsed_arguments, tool_call_id if isinstance(tool_call_id, str) else None


def _parse_arguments(arguments: Any) -> dict[str, Any] | str | None:
    if arguments is None:
        return None
    if isinstance(arguments, dict):
        return _limit_structured_arguments(arguments)
    if not isinstance(arguments, str):
        return _truncate(str(arguments), _ARGUMENT_LIMIT)
    try:
        parsed = json.loads(arguments)
    except json.JSONDecodeError:
        return _truncate(arguments, _ARGUMENT_LIMIT)
    if isinstance(parsed, dict):
        return _limit_structured_arguments(parsed)
    return _truncate(arguments, _ARGUMENT_LIMIT)


def _limit_structured_arguments(arguments: dict[str, Any]) -> dict[str, Any] | str:
    serialized = json.dumps(arguments, ensure_ascii=False, default=str)
    if len(serialized) <= _ARGUMENT_LIMIT:
        return arguments
    return _truncate(serialized, _ARGUMENT_LIMIT)


def _simplify_tool_result(tool_name: str | None, content: str) -> str:
    """Simplify a tool result string.

    ``read_file`` results are kept longer (skill content the agent consumed)
    but still capped at ``_READ_FILE_RESULT_LIMIT`` to avoid bloating the
    evaluator prompt; over-limit results are marked ``... [truncated]``.
    Other tool results use the tighter ``_TOOL_RESULT_LIMIT``.
    """
    if tool_name == "read_file":
        if len(content) <= _READ_FILE_RESULT_LIMIT:
            return content
        return content[:_READ_FILE_RESULT_LIMIT] + _TRUNCATION_MARKER
    return _truncate(content, _TOOL_RESULT_LIMIT)


def _truncate(value: str, limit: int) -> str:
    if len(value) <= limit:
        return value
    return value[:limit] + "…"
