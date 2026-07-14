"""Trace cleaner — extract and clean LLM conversation from JSONL trace archives.

Implements the cleaning logic from mock-assets/temp/scripts/clean_traces.py:
1. Read all call records from a JSONL archive
2. Find the last type=GENERATION element
3. Extract input.messages + output
4. Extract task_input (first role=user content)
5. Collect unique tool names from role=tool messages
6. Filter messages: keep only user/assistant/tool, remove usage_metadata

This is a pure function module with no I/O or HTTP dependencies.
"""

from __future__ import annotations

from typing import Any


def clean_traces(
    records: list[dict[str, Any]],
    *,
    session_id: str = "",
    agent_name: str = "",
) -> dict[str, Any]:
    """Clean a list of JSONL trace records into a structured conversation.

    Args:
        records: List of parsed JSONL records (each is a dict).
        session_id: Conversation/session identifier.
        agent_name: Name of the agent that produced these traces.

    Returns:
        A dict with session_id, agent_name, task_input, trajectory summary,
        and filtered messages. Returns empty dict if no GENERATION record found.
    """
    if not records:
        return {}

    # Find the last type=GENERATION element
    generation_item: dict[str, Any] | None = None
    for item in reversed(records):
        if isinstance(item, dict) and item.get("type") == "GENERATION":
            generation_item = item
            break

    if generation_item is None:
        return {}

    input_messages = generation_item.get("input", {}).get("messages", [])
    output_data = generation_item.get("output")

    # Concatenate: input.messages + output (last assistant reply)
    messages: list[dict[str, Any]] = list(input_messages)
    if output_data is not None:
        messages.append(output_data)

    # task_input: first role=user message's content
    task_input = ""
    for msg in messages:
        if msg.get("role") == "user":
            task_input = msg.get("content", "")
            break

    # Collect unique tool names from role=tool messages
    tool_names_set: set[str] = set()
    for msg in messages:
        if msg.get("role") == "tool":
            name = msg.get("name", "")
            if name:
                tool_names_set.add(name)
    tool_names = sorted(tool_names_set)

    # Filter messages: keep only user/assistant/tool, remove usage_metadata
    filtered_messages: list[dict[str, Any]] = []
    for msg in messages:
        if msg.get("role") in ("user", "assistant", "tool"):
            cleaned_msg = {k: v for k, v in msg.items() if k != "usage_metadata"}
            filtered_messages.append(cleaned_msg)

    # Assemble summary
    summary = f"{len(messages)} messages, {len(tool_names)} unique tools: {', '.join(tool_names)}"

    return {
        "session_id": session_id,
        "agent_name": agent_name,
        "task_input": task_input,
        "trajectory": {
            "total_messages": len(messages),
            "tool_calls_used": tool_names,
            "summary": summary,
        },
        "messages": filtered_messages,
    }
