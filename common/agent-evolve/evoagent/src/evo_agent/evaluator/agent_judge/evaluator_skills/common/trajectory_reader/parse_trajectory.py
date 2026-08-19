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
import logging
import sys
from pathlib import Path

logger = logging.getLogger(__name__)


def load_messages(path: Path) -> list[dict]:
    messages = []
    if not path.exists():
        raise FileNotFoundError(f"{path} not found")
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
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser(description="Parse trajectory.jsonl")
    parser.add_argument("--role", help="Filter by role (user/assistant/tool/system)")
    parser.add_argument("--tool", help="Filter by tool name in tool_calls")
    parser.add_argument("--line", type=int, help="Show specific message by index")
    parser.add_argument(
        "--file",
        default="trajectory.jsonl",
        help="Path to trajectory file (default: trajectory.jsonl)",
    )
    args = parser.parse_args()

    try:
        messages = load_messages(Path(args.file))
    except FileNotFoundError as exc:
        logger.error(str(exc))
        sys.exit(1)

    if args.line is not None:
        if 0 <= args.line < len(messages):
            logger.info(json.dumps(messages[args.line], ensure_ascii=False, indent=2))
        else:
            logger.error("index %d out of range (0-%d)", args.line, len(messages) - 1)
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
    logger.info("Total messages: %d", len(messages))
    logger.info("Filtered: %d", len(filtered))
    logger.info("")
    for idx, msg in filtered:
        logger.info(format_message(idx, msg))


if __name__ == "__main__":
    main()
