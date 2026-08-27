#!/usr/bin/env python3
"""G.ERR.02 — replace only `catch (RuntimeException ignored)` soft-fail blocks."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")

UNCHECKED = (
    "IllegalStateException | ClassCastException | NullPointerException | "
    "IllegalArgumentException | IndexOutOfBoundsException"
)


def try_body(text: str, catch_pos: int) -> str:
    i = catch_pos - 1
    while i >= 0 and text[i : i + 5] != "catch":
        i -= 1
    j = i - 1
    while j >= 0 and text[j : j + 3] != "try":
        j -= 1
    if j < 0:
        return ""
    k = j
    depth = 0
    started = False
    while k < i:
        if text[k] == "{":
            depth += 1
            started = True
        elif text[k] == "}":
            depth -= 1
        k += 1
        if started and depth == 0:
            return text[j:k]
    return text[j:i]


def process(text: str) -> tuple[str, bool]:
    changed = False
    pattern = re.compile(r"catch\s*\(\s*RuntimeException(\s+ignored)\s*\)")
    pos = 0
    while True:
        m = pattern.search(text, pos)
        if not m:
            break
        body = try_body(text, m.start())
        handler = text[m.end() : m.end() + 160]
        if "rethrowGraphInterrupt" in handler or "throw e" in handler or "throw new" in handler:
            pos = m.end()
            continue
        if ".invoke(" in body and ("Model" in body or "bridge" in body or "invoker" in body):
            pos = m.end()
            continue
        repl = f"catch ({UNCHECKED}{m.group(1)})"
        text = text[: m.start()] + repl + text[m.end() :]
        changed = True
        pos = m.start() + len(repl)
    return text, changed


def main() -> None:
    n = 0
    for p in sorted(ROOT.rglob("*.java")):
        orig = p.read_text(encoding="utf-8")
        text, ch = process(orig)
        if ch:
            p.write_text(text, encoding="utf-8")
            n += 1
            print("updated", p.relative_to(ROOT))
    print("changed", n)


if __name__ == "__main__":
    main()
