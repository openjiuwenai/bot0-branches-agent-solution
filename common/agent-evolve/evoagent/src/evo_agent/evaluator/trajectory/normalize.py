"""Adapter trace → StandardTrajectory normalization.

Converts Adapter sidecar cleaned-traces into the dict format expected by
``StandardTrajectory.model_validate()``.  Both ``EvoTrainer`` and
``EDPAgentOptimizer._rollout()`` call this before injecting trajectory
data into eval cases.
"""

from __future__ import annotations

from typing import Any


def normalize_trace_to_trajectory(trace_data: dict[str, Any]) -> dict[str, Any]:
    """Convert Adapter trace data to a ``StandardTrajectory``-compatible dict.

    Handles:
    - ``summary``: plain string → ``TrajectorySummary`` dict (or ``None``)
    - ``tool_calls``: flat format ``{name, arguments}`` → OpenAI format
      ``{id, function: {name, arguments}}``
    - Messages that already conform to ``TrajectoryMessage`` are passed through.
    """
    messages: list[dict[str, Any]] = trace_data.get("messages", [])
    raw_summary = trace_data.get("summary")

    summary = _build_summary(raw_summary, messages) if raw_summary else None
    normalized_messages = [_normalize_message(msg) for msg in messages]

    return {"summary": summary, "messages": normalized_messages}


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _build_summary(raw_summary: Any, messages: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Convert a raw summary (string or dict) to a ``TrajectorySummary`` dict."""
    if isinstance(raw_summary, str):
        tool_calls_used = _extract_tool_names(messages)
        return {
            "summary": raw_summary,
            "total_messages": len(messages),
            "tool_calls_used": tool_calls_used,
            "tool_calls_count": sum(
                len(m.get("tool_calls", [])) for m in messages if m.get("role") == "assistant"
            ),
            "total_steps": len(messages),
            "tokens_used": 0,
            "metadata": {},
        }
    if isinstance(raw_summary, dict):
        return raw_summary  # Already structured
    return None


def _extract_tool_names(messages: list[dict[str, Any]]) -> list[str]:
    """Extract unique tool names from assistant messages' tool_calls."""
    names: list[str] = []
    seen: set[str] = set()
    for msg in messages:
        if msg.get("role") != "assistant":
            continue
        for tc in msg.get("tool_calls", []):
            name = _get_tool_name(tc)
            if name and name not in seen:
                seen.add(name)
                names.append(name)
    return names


def _get_tool_name(tc: dict[str, Any]) -> str | None:
    """Extract tool name from either OpenAI or flat format."""
    fn = tc.get("function")
    if isinstance(fn, dict):
        return fn.get("name")
    return tc.get("name")


def _normalize_message(msg: dict[str, Any]) -> dict[str, Any]:
    """Normalize a single message to ``TrajectoryMessage``-compatible dict."""
    tool_calls = msg.get("tool_calls", [])
    normalized_tcs = [_normalize_tool_call(tc) for tc in tool_calls]

    result: dict[str, Any] = {
        "role": msg.get("role", ""),
        "content": msg.get("content"),
    }
    if normalized_tcs:
        result["tool_calls"] = normalized_tcs
    if msg.get("name") is not None:
        result["name"] = msg["name"]
    if msg.get("tool_call_id") is not None:
        result["tool_call_id"] = msg["tool_call_id"]
    return result


def _normalize_tool_call(tc: dict[str, Any]) -> dict[str, Any]:
    """Normalize tool call to OpenAI format: ``{id, function: {name, arguments}}``."""
    fn = tc.get("function")
    if isinstance(fn, dict):
        return tc  # Already OpenAI format
    # Flat format: {name, arguments} → OpenAI format
    return {
        "id": tc.get("id", ""),
        "function": {
            "name": tc.get("name", ""),
            "arguments": tc.get("arguments", ""),
        },
    }
