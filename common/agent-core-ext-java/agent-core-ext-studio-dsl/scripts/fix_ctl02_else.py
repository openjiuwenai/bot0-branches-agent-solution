#!/usr/bin/env python3
"""Add final else branches for G.CTL.02 (if / else-if chains missing else)."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")


def find_chain_end(lines: list[str], start: int) -> int | None:
    """Return index of closing brace for if-else-if chain starting at `start`, or None."""
    depth = 0
    i = start
    while i < len(lines):
        line = lines[i]
        depth += line.count("{") - line.count("}")
        if depth == 0 and i > start:
            return i
        i += 1
    return None


def has_final_else(block: str) -> bool:
  return bool(re.search(r"\}\s*else\s*(?:if\s*\(|\{)", block))


def process_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    changed = False
    i = 0
    while i < len(lines):
        if re.match(r"^[ \t]*if\s*\(", lines[i]):
            chain_start = i
            end = find_chain_end(lines, chain_start)
            if end is None:
                i += 1
                continue
            block = "".join(lines[chain_start : end + 1])
            if "else if" in block and not has_final_else(block):
                indent = re.match(r"^([ \t]*)", lines[chain_start]).group(1)
                close_line = lines[end]
                if re.match(rf"^{re.escape(indent)}\}}\s*$", close_line.rstrip("\n")):
                    insert = f"{indent}}} else {{\n{indent}    // no-op\n{indent}}}\n"
                    # replace final `}` of chain with else branch before outer close
                    # walk back to last branch closing brace
                    j = end
                    while j > chain_start and lines[j].strip() != "}":
                        j -= 1
                    if j > chain_start:
                        lines.insert(j + 1, f"{indent}} else {{\n{indent}    // no-op\n")
                        lines.insert(j + 3, f"{indent}}}\n")
                        changed = True
                        i = j + 4
                        continue
        i += 1
    if changed:
        path.write_text("".join(lines), encoding="utf-8")
    return changed


def main() -> None:
    n = 0
    for p in sorted(ROOT.rglob("*.java")):
        if process_file(p):
            n += 1
            print("updated", p)
    print("changed", n)


if __name__ == "__main__":
    main()
