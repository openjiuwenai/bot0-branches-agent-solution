#!/usr/bin/env python3
"""Clean and normalize trajectory.jsonl → cleaned_trajectory.jsonl.

Removes noise, deduplicates messages, truncates oversized tool returns,
and outputs a clean version for evaluation.

Usage:
    python3 clean_trajectory.py                              # default cleaning
    python3 clean_trajectory.py --max-tool-chars 3000        # custom tool truncation
    python3 clean_trajectory.py --output cleaned.jsonl       # custom output path
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

logger = logging.getLogger(__name__)


def truncate_content(content: str, max_chars: int) -> str:
    if len(content) <= max_chars:
        return content
    kept = max_chars
    dropped = len(content) - kept
    return content[:kept] + f"\n\n...[truncated {dropped} → kept first {kept}]"


def clean_messages(
    messages: list[dict],
    *,
    max_tool_chars: int = 2000,
    max_content_chars: int = 8000,
) -> list[dict]:
    cleaned = []
    seen_system = False

    for msg in messages:
        role = msg.get("role", "")

        # Filter: skip duplicate system messages (keep only the first)
        if role == "system":
            if seen_system:
                continue
            seen_system = True

        # Filter: skip empty messages (no content and no tool_calls)
        content = msg.get("content", "")
        tool_calls = msg.get("tool_calls", [])
        if not content and not tool_calls:
            continue

        # Truncate tool returns
        if role == "tool" and isinstance(content, str):
            msg = {**msg, "content": truncate_content(content, max_tool_chars)}

        # Truncate oversized assistant content
        elif role == "assistant" and isinstance(content, str) and len(content) > max_content_chars:
            msg = {**msg, "content": truncate_content(content, max_content_chars)}

        cleaned.append(msg)

    return cleaned


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser(description="Clean trajectory.jsonl")
    parser.add_argument(
        "--file", default="trajectory.jsonl", help="Input file (default: trajectory.jsonl)"
    )
    parser.add_argument("--output", default="cleaned_trajectory.jsonl", help="Output file")
    parser.add_argument(
        "--max-tool-chars", type=int, default=2000, help="Max chars for tool returns"
    )
    parser.add_argument("--max-content-chars", type=int, default=8000, help="Max chars for content")
    args = parser.parse_args()

    input_path = Path(args.file)
    if not input_path.exists():
        logger.error("%s not found", input_path)
        sys.exit(1)

    messages = []
    with open(input_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                messages.append(json.loads(line))

    original_count = len(messages)
    cleaned = clean_messages(
        messages,
        max_tool_chars=args.max_tool_chars,
        max_content_chars=args.max_content_chars,
    )

    output_path = Path(args.output)
    with open(output_path, "w", encoding="utf-8") as f:
        for msg in cleaned:
            f.write(json.dumps(msg, ensure_ascii=False, default=str))
            f.write("\n")

    logger.info("Original: %d messages", original_count)
    logger.info("Cleaned:  %d messages", len(cleaned))
    logger.info("Removed:  %d messages", original_count - len(cleaned))
    logger.info("Output:   %s", output_path)


if __name__ == "__main__":
    main()
