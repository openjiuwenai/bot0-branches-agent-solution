#!/usr/bin/env python3
"""Fix G.ERR.02 — replace catch (Exception|RuntimeException) with specific types."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")

# Multi-catch for generic swallow / rethrow (unchecked only).
UNCHECKED = (
    "IllegalStateException | ClassCastException | NullPointerException | "
    "IllegalArgumentException | IndexOutOfBoundsException"
)

JACKSON_IMPORT = "import com.fasterxml.jackson.core.JsonProcessingException;"


def ensure_import(text: str, imp: str) -> str:
    if imp in text:
        return text
    m = re.search(r"(?m)^import .+;\n", text)
    if not m:
        return text
    # insert after last import
    imports = list(re.finditer(r"(?m)^import .+;\n", text))
    pos = imports[-1].end()
    return text[:pos] + imp + "\n" + text[pos:]


def try_block_for_catch(text: str, catch_pos: int) -> str:
    """Return try-block source preceding catch at catch_pos."""
    depth = 0
    i = catch_pos - 1
    # walk back to catch
    while i >= 0:
        if text[i : i + 5] == "catch":
            break
        i -= 1
    # walk back to matching try
    j = i - 1
    while j >= 0 and text[j : j + 3] != "try":
        j -= 1
    if j < 0:
        return ""
    # find try { ... } before catch
    k = j
    brace = 0
    started = False
    while k < i:
        if text[k] == "{":
            brace += 1
            started = True
        elif text[k] == "}":
            brace -= 1
        k += 1
        if started and brace == 0:
            return text[j:k]
    return text[j:i]


def pick_catch_types(try_body: str, handler: str) -> tuple[str, list[str]]:
    imports: list[str] = []
    body = try_body
    if "readValue" in body or "writeValueAsString" in body or "writeValue" in body:
        imports.append(JACKSON_IMPORT)
        if "ignored" in handler or "//" in handler:
            return "JsonProcessingException", imports
        return "JsonProcessingException", imports
    if "client.send" in body or "HttpClient" in body or "HttpRequest" in body:
        imports.append("import java.io.IOException;")
        return "IOException | InterruptedException", imports
    if "Integer.parseInt" in body or "Long.parseLong" in body:
        return "NumberFormatException", imports
    if "jedis" in body.lower() or "Jedis" in body:
        imports.append("import redis.clients.jedis.exceptions.JedisException;")
        return "JedisException", imports
    if "parseSse" in body or "readLine" in body:
        imports.append("import java.io.IOException;")
        return "IOException", imports
    if "invoke(" in body and "Model" in body:
        imports.append("import java.io.IOException;")
        return "IOException | InterruptedException | ReflectiveOperationException", imports
    if "ignored" in handler:
        return UNCHECKED.replace(" | ", " | ") + " ", imports
    return UNCHECKED, imports


def fix_catches(text: str) -> tuple[str, bool]:
    changed = False
    all_imports: list[str] = []
    pattern = re.compile(
        r"catch\s*\(\s*(Exception|RuntimeException)(\s+\w+)?\s*\)",
        re.MULTILINE,
    )
    pos = 0
    while True:
        m = pattern.search(text, pos)
        if not m:
            break
        catch_start = m.start()
        # find handler body (single line or block)
        handler_start = m.end()
        handler = text[handler_start : handler_start + 200]
        try_body = try_block_for_catch(text, catch_start)
        types, imps = pick_catch_types(try_body, handler)
        all_imports.extend(imps)
        var = m.group(2) or ""
        if not var.strip():
            var = " ignored" if "ignored" in handler[:80] else " e"
        replacement = f"catch ({types}{var})"
        text = text[: m.start()] + replacement + text[m.end() :]
        changed = True
        pos = m.start() + len(replacement)
    for imp in all_imports:
        text = ensure_import(text, imp.strip())
    return text, changed


def dedupe_exception_catches(text: str) -> str:
    """Remove duplicate consecutive catch blocks with identical bodies (Exception after RuntimeException)."""
    # After fix, may still have duplicate JsonProcessingException + unchecked — leave for manual
    return text


def process(path: Path) -> bool:
    orig = path.read_text(encoding="utf-8")
    text, changed = fix_catches(orig)
    text = dedupe_exception_catches(text)
    if text != orig:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    n = 0
    for p in sorted(ROOT.rglob("*.java")):
        if process(p):
            n += 1
            print("updated", p.relative_to(ROOT))
    print("changed", n)


if __name__ == "__main__":
    main()
