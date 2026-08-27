#!/usr/bin/env python3
"""G.MET.06 — replace `return null` with Optional.empty() and migrate method return types."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")

PRIMITIVES = frozenset(
    {"void", "boolean", "byte", "short", "int", "long", "float", "double", "char"}
)

RETURN_NULL = re.compile(r"\breturn\s+null\s*;")
METHOD_START = re.compile(
    r"(?P<prefix>(?:(?:public|protected|private|static|final|native|synchronized|abstract|default)\s+)*)"
    r"(?:(?P<gen><[^>]+>)\s+)?"
    r"(?P<rtype>[\w.<>,?\[\]]+)\s+"
    r"(?P<name>\w+)\s*"
    r"(?P<params>\([^;]*\))"
)


def ensure_import(text: str) -> str:
    if "import java.util.Optional;" in text:
        return text
    imports = list(re.finditer(r"(?m)^import .+;\n", text))
    if not imports:
        return "import java.util.Optional;\n\n" + text
    pos = imports[-1].end()
    return text[:pos] + "import java.util.Optional;\n" + text[pos:]


def wrap_optional(rtype: str) -> str:
    rtype = rtype.strip()
    if rtype.startswith("Optional<"):
        return rtype
    return f"Optional<{rtype}>"


def find_method_body_end(lines: list[str], open_line: int) -> int:
    depth = 0
    started = False
    for i in range(open_line, len(lines)):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
                if started and depth == 0:
                    return i
    return open_line


def collect_signature(lines: list[str], start: int) -> tuple[int, str]:
    parts: list[str] = []
    i = start
    while i < len(lines):
        parts.append(lines[i])
        joined = "".join(parts)
        if "{" in joined or ";" in joined.rstrip():
            return i, joined
        i += 1
    return start, "".join(parts)


def find_methods(text: str) -> list[dict]:
    lines = text.splitlines(keepends=True)
    methods: list[dict] = []
    i = 0
    while i < len(lines):
        if lines[i].strip().startswith("@") or lines[i].strip().startswith("//"):
            i += 1
            continue
        if not re.search(r"\b(public|protected|private|static)\b", lines[i]):
            i += 1
            continue
        end_i, sig = collect_signature(lines, i)
        m = METHOD_START.search(sig.replace("\n", " "))
        if not m:
            i += 1
            continue
        rtype = m.group("rtype").strip()
        name = m.group("name")
        if rtype in PRIMITIVES or rtype.startswith("Optional<"):
            i = end_i + 1
            continue
        body_line = end_i
        while body_line < len(lines) and "{" not in lines[body_line]:
            body_line += 1
        if body_line >= len(lines) or "{" not in lines[body_line]:
            i = end_i + 1
            continue
        body_end = find_method_body_end(lines, body_line)
        body = "".join(lines[body_line + 1 : body_end])
        if RETURN_NULL.search(body):
            methods.append(
                {
                    "start": i,
                    "sig_end": end_i,
                    "body_line": body_line,
                    "body_end": body_end,
                    "rtype": rtype,
                    "name": name,
                }
            )
        i = body_end + 1
    return methods


def transform_returns(body_lines: list[str], optional_methods: set[str]) -> list[str]:
    out: list[str] = []
    for ln in body_lines:
        if RETURN_NULL.search(ln):
            out.append(RETURN_NULL.sub("return Optional.empty();", ln))
            continue
        m = re.match(r"^(\s*)return\s+(.+);\s*$", ln.rstrip())
        if not m:
            out.append(ln)
            continue
        indent, expr = m.group(1), m.group(2).strip()
        if expr.startswith("Optional."):
            out.append(ln)
            continue
        call = re.match(r"^([\w.]+)\s*\(", expr)
        if call:
            callee = call.group(1).split(".")[-1]
            if callee in optional_methods:
                out.append(f"{indent}return {expr};\n")
                continue
        out.append(f"{indent}return Optional.ofNullable({expr});\n")
    return out


def find_call_close(text: str, open_paren: int) -> int:
    depth = 0
    i = open_paren
    in_str = False
    esc = False
    while i < len(text):
        ch = text[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def patch_call_sites(text: str, names: set[str]) -> str:
    for name in sorted(names, key=len, reverse=True):
        needle = f"{name}("
        pos = 0
        while True:
            idx = text.find(needle, pos)
            if idx < 0:
                break
            before = text[max(0, idx - 60) : idx]
            if re.search(r"[\w.<>,?\[\]]+\s+" + re.escape(name) + r"\s*$", before):
                pos = idx + len(needle)
                continue
            end = find_call_close(text, idx + len(name))
            if end < 0:
                break
            tail = text[end + 1 : end + 24]
            if tail.lstrip().startswith(".orElse") or tail.lstrip().startswith(".map"):
                pos = end + 1
                continue
            text = text[: end + 1] + ".orElse(null)" + text[end + 1 :]
            pos = end + len(".orElse(null)") + 1
    return text


def process_file(path: Path, optional_methods: set[str]) -> bool:
    original = path.read_text(encoding="utf-8")
    methods = find_methods(original)
    if not methods:
        return False
    lines = original.splitlines(keepends=True)
    converted_names: set[str] = set()
    for meth in reversed(methods):
        rtype = meth["rtype"]
        name = meth["name"]
        new_rtype = wrap_optional(rtype)
        converted_names.add(name)
        for j in range(meth["start"], meth["body_line"] + 1):
            if f"{rtype} {name}" in lines[j]:
                lines[j] = lines[j].replace(f"{rtype} {name}", f"{new_rtype} {name}", 1)
                break
        lines[meth["body_line"] + 1 : meth["body_end"]] = transform_returns(
            lines[meth["body_line"] + 1 : meth["body_end"]], optional_methods | converted_names
        )
    text = "".join(lines)
    text = ensure_import(text)
    text = patch_call_sites(text, converted_names)
    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    files = sorted(ROOT.rglob("*.java"))
    optional_methods: set[str] = set()
    for p in files:
        for m in find_methods(p.read_text(encoding="utf-8")):
            optional_methods.add(m["name"])
    n = 0
    for p in files:
        if process_file(p, optional_methods):
            n += 1
            print("updated", p.relative_to(ROOT))
    print("optional methods:", len(optional_methods), "changed files:", n)


if __name__ == "__main__":
    main()
