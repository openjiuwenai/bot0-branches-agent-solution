#!/usr/bin/env python3
"""Parse trajectory.jsonl and output a structured summary.

Usage:
    python3 parse_trajectory.py                     # summary to stdout
    python3 parse_trajectory.py --role tool         # only tool messages
    python3 parse_trajectory.py --role assistant     # only assistant messages
    python3 parse_trajectory.py --tool call_versatile  # messages with specific tool
    python3 parse_trajectory.py --line 5             # specific message by index
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


def format_message(idx: int, msg: dict) -> str:
    role = msg.get("role", "?")
    content = msg.get("content", "")
    if isinstance(content, list):
        # OpenAI-style content parts
        content = " ".join(
            p.get("text", "") for p in content if isinstance(p, dict) and p.get("type") == "text"
        )
    content_str = str(content)[:200] if content else ""

    tool_calls = msg.get("tool_calls", [])
    tool_info = ""
    if tool_calls:
        names = [tc.get("function", {}).get("name", "?") for tc in tool_calls]
        tool_info = f" tools=[{', '.join(names)}]"

    tool_name = msg.get("name", "")
    if role == "tool" and tool_name:
        tool_info = f" name={tool_name}"

    return f"[{idx}] {role}{tool_info}: {content_str}"


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse trajectory.jsonl")
    parser.add_argument("--role", help="Filter by role (user/assistant/tool/system)")
    parser.add_argument("--tool", help="Filter by tool name in tool_calls")
    parser.add_argument("--line", type=int, help="Show specific message by index")
    parser.add_argument(
        "--file", default="trajectory.jsonl", help="Path to trajectory file (default: trajectory.jsonl)"
    )
    args = parser.parse_args()

    messages = load_messages(Path(args.file))

    if args.line is not None:
        if 0 <= args.line < len(messages):
            print(json.dumps(messages[args.line], ensure_ascii=False, indent=2))
        else:
            print(f"Error: index {args.line} out of range (0-{len(messages)-1})", file=sys.stderr)
            sys.exit(1)
        return

    # Apply filters
    filtered = []
    for idx, msg in enumerate(messages):
        if args.role and msg.get("role") != args.role:
            continue
        if args.tool:
            tool_calls = msg.get("tool_calls", [])
            names = [tc.get("function", {}).get("name", "") for tc in tool_calls]
            if args.tool not in names and msg.get("name") != args.tool:
                continue
        filtered.append((idx, msg))

    # Output
    print(f"Total messages: {len(messages)}")
    print(f"Filtered: {len(filtered)}")
    print()
    for idx, msg in filtered:
        print(format_message(idx, msg))


if __name__ == "__main__":
    main()
