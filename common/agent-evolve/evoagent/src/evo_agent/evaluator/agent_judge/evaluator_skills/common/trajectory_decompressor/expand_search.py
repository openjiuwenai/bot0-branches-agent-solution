#!/usr/bin/env python3
"""Search trajectory.jsonl for specific patterns and expand compressed context.

When trajectory.md (the compact summary) lacks detail, use this to locate
and display the full original messages from trajectory.jsonl.

Usage:
    python3 expand_search.py "call_versatile"           # search all messages
    python3 expand_search.py "call_versatile" --role tool  # search only tool returns
    python3 expand_search.py --context 5,10            # show messages 5-10 with full content
    python3 expand_search.py --tool-call               # list all tool calls
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load_messages(path: Path) -> list[dict]:
    messages = []
    if not path.exists():
        print(f"Error: {path} not found", file=sys.stderr)
        sys.exit(1)
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                messages.append(json.loads(line))
    return messages


def search_messages(messages: list[dict], query: str, role: str | None = None) -> list[tuple[int, dict]]:
    results = []
    for idx, msg in enumerate(messages):
        if role and msg.get("role") != role:
            continue
        # Search in content (str or list of parts)
        content = msg.get("content", "")
        if isinstance(content, list):
            content = " ".join(
                p.get("text", "") for p in content if isinstance(p, dict) and p.get("type") == "text"
            )
        content_str = str(content)

        # Search in tool_calls function names and arguments
        tool_calls_str = ""
        for tc in msg.get("tool_calls", []):
            func = tc.get("function", {})
            tool_calls_str += func.get("name", "") + " " + func.get("arguments", "")

        if query.lower() in content_str.lower() or query.lower() in tool_calls_str.lower():
            results.append((idx, msg))
    return results


def show_context(messages: list[dict], start: int, end: int) -> None:
    for idx in range(max(0, start), min(len(messages), end + 1)):
        msg = messages[idx]
        print(f"--- Message [{idx}] ---")
        print(json.dumps(msg, ensure_ascii=False, indent=2))
        print()


def list_tool_calls(messages: list[dict]) -> None:
    for idx, msg in enumerate(messages):
        for tc in msg.get("tool_calls", []):
            func = tc.get("function", {})
            name = func.get("name", "?")
            args_str = func.get("arguments", "{}")
            try:
                args = json.loads(args_str) if isinstance(args_str, str) else args_str
                args_summary = json.dumps(args, ensure_ascii=False)[:200]
            except (json.JSONDecodeError, TypeError):
                args_summary = str(args_str)[:200]
            print(f"[{idx}] {name}({args_summary})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Search and expand trajectory context")
    parser.add_argument("query", nargs="?", help="Search query (case-insensitive)")
    parser.add_argument("--role", help="Filter by role")
    parser.add_argument("--context", help="Show message range: start,end (e.g. 5,10)")
    parser.add_argument("--tool-call", action="store_true", help="List all tool calls")
    parser.add_argument(
        "--file", default="trajectory.jsonl", help="Path to trajectory file (default: trajectory.jsonl)"
    )
    args = parser.parse_args()

    messages = load_messages(Path(args.file))

    if args.tool_call:
        list_tool_calls(messages)
        return

    if args.context:
        parts = args.context.split(",")
        start = int(parts[0])
        end = int(parts[1]) if len(parts) > 1 else start
        show_context(messages, start, end)
        return

    if not args.query:
        parser.error("Provide a search query, --context, or --tool-call")

    results = search_messages(messages, args.query, role=args.role)
    print(f"Found {len(results)} matching messages (out of {len(messages)} total):")
    print()
    for idx, msg in results:
        print(f"--- Message [{idx}] ---")
        print(json.dumps(msg, ensure_ascii=False, indent=2))
        print()


if __name__ == "__main__":
    main()
