#!/usr/bin/env python3
"""G.ERR.02 — replace catch (Exception) only (never RuntimeException)."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")


def ensure_import(text: str, imp: str) -> str:
    if imp in text:
        return text
    m = list(re.finditer(r"(?m)^import .+;\n", text))
    if not m:
        return text
    return text[: m[-1].end()] + imp + "\n" + text[m[-1].end() :]


def try_body_before(text: str, catch_start: int) -> str:
    i = catch_start - 1
    while i >= 0 and "catch" not in text[max(0, i - 6) : i + 1]:
        i -= 1
    j = i - 1
    while j >= 0 and text[j : j + 3] != "try":
        j -= 1
    if j < 0:
        return ""
    depth = 0
    k = j
    while k < catch_start:
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
        k += 1
    return text[j:k]


def pick_types(body: str, handler: str) -> tuple[str, list[str]]:
    imps: list[str] = []
    if "readValue" in body or "writeValueAsString" in body or "writeValue" in body:
        imps.append("import com.fasterxml.jackson.core.JsonProcessingException;")
        return "JsonProcessingException", imps
    if "client.send" in body or "HttpClient" in body or "HttpRequest" in body:
        imps.append("import java.io.IOException;")
        return "IOException | InterruptedException", imps
    if "jedis" in body.lower() or "Jedis" in body:
        imps.append("import redis.clients.jedis.exceptions.JedisException;")
        return "JedisException", imps
    if "Integer.parseInt" in body or "Long.parseLong" in body:
        return "NumberFormatException", imps
    if "Process" in body and "waitFor" in body:
        imps.append("import java.io.IOException;")
        return "IOException | InterruptedException", imps
    if ".invoke(" in body and "Model" in body:
        imps.append("import java.io.IOException;")
        return "IOException | InterruptedException", imps
    if "ignored" in handler:
        return "ReflectiveOperationException | IllegalStateException | ClassCastException", imps
    imps.append("import java.io.IOException;")
    return "IOException | InterruptedException", imps


def process(text: str) -> tuple[str, bool]:
    changed = False
    imps: list[str] = []
    pattern = re.compile(r"catch\s*\(\s*Exception(\s+\w+)?\s*\)")
    pos = 0
    while True:
        m = pattern.search(text, pos)
        if not m:
            break
        handler = text[m.end() : m.end() + 120]
        body = try_body_before(text, m.start())
        types, new_imps = pick_types(body, handler)
        imps.extend(new_imps)
        var = m.group(1) or (" ignored" if "ignored" in handler else " e")
        repl = f"catch ({types}{var})"
        text = text[: m.start()] + repl + text[m.end() :]
        changed = True
        pos = m.start() + len(repl)
    for imp in imps:
        text = ensure_import(text, imp)
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
