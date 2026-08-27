#!/usr/bin/env python3
"""G.CMT.01 — add stub Javadoc for public/protected members missing documentation."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")

FIELD_RE = re.compile(
    r"^([ \t]*)(public|protected)\s+"
    r"(?:(?:static|final|volatile|transient)\s+)*"
    r"([\w.<>,?\[\]]+)\s+(\w+)\s*([=;])"
)
METHOD_RE = re.compile(
    r"^([ \t]*)(public|protected)\s+"
    r"(?:(?:static|final|synchronized|default|native|abstract)\s+)*"
    r"(?:<[^>]+>\s+)?([\w.<>,?\[\]]+)\s+(\w+)\s*\("
)
CTOR_RE = re.compile(
    r"^([ \t]*)(public|protected)\s+"
    r"(?:(?:static|final)\s+)*"
    r"(\w+)\s*\("
)
IFACE_METHOD_RE = re.compile(
    r"^([ \t]+)(?:default\s+)?(?:<[^>]+>\s+)?([\w.<>,?\[\]]+)\s+(\w+)\s*\([^;]*\)\s*;"
)
TYPE_DECL_RE = re.compile(
    r"^\s*(public|protected)\s+.*\b(class|interface|enum|record)\b"
)


def has_javadoc_before(lines: list[str], idx: int) -> bool:
    j = idx - 1
    while j >= 0 and lines[j].strip() == "":
        j -= 1
    while j >= 0 and lines[j].strip().startswith("@"):
        j -= 1
        while j >= 0 and lines[j].strip() == "":
            j -= 1
    if j < 0:
        return False
    k = j
    while k >= max(0, idx - 25):
        s = lines[k].strip()
        if s.startswith("/**") or s.endswith("*/") or s == "*/" or (s.startswith("*") and not s.startswith("*/")):
            return True
        if s and not s.startswith("*") and not s.startswith("//"):
            break
        k -= 1
    return False


def collect_signature(lines: list[str], start: int) -> str:
    parts: list[str] = []
    i = start
    while i < len(lines):
        parts.append(lines[i])
        joined = "".join(parts)
        if "{" in joined or ";" in joined.rstrip():
            break
        i += 1
    return "".join(parts)


def parse_params(sig: str) -> list[str]:
    m = re.search(r"\(([^)]*)\)", sig, re.S)
    if not m:
        return []
    raw = m.group(1).strip()
    if not raw:
        return []
    names: list[str] = []
    depth = 0
    chunk = ""
    for ch in raw + ",":
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            p = chunk.strip()
            if p:
                pname = p.replace("...", " ").split()[-1].replace("[]", "")
                if re.match(r"^\w+$", pname):
                    names.append(pname)
            chunk = ""
        else:
            chunk += ch
    return names


def build_doc(indent: str, name: str, ret: str | None, params: list[str]) -> list[str]:
    lines = [f"{indent}/**\n", f"{indent} * {name}.\n"]
    for p in params:
        lines.append(f"{indent} * @param {p} {p}\n")
    if ret is not None and ret not in ("void",) and ret != name:
        lines.append(f"{indent} * @return result\n")
    lines.append(f"{indent} * @since 0.1.0\n")
    lines.append(f"{indent} */\n")
    return lines


def in_interface_block(lines: list[str], idx: int) -> bool:
    depth = 0
    for k in range(idx, -1, -1):
        line = lines[k]
        if re.search(r"\binterface\b", line):
            return True
        if re.search(r"\b(class|enum|record)\b", line):
            return False
    return False


def process_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    changed = False
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if stripped.startswith("//") or (stripped.startswith("*") and not stripped.startswith("*/")):
            out.append(line)
            i += 1
            continue

        if not has_javadoc_before(out, len(out)) and not TYPE_DECL_RE.match(line):
            indent = re.match(r"^([ \t]*)", line).group(1)
            sig = collect_signature(lines, i)

            fm = FIELD_RE.match(line)
            if fm and fm.group(5) in ("=", ";"):
                out.extend(build_doc(indent, fm.group(4), None, []))
                changed = True
                out.append(line)
                i += 1
                continue

            mm = METHOD_RE.match(sig)
            if mm:
                ret, name = mm.group(3).strip(), mm.group(4)
                if name not in ("class", "interface", "enum", "record", "new"):
                    out.extend(build_doc(indent, name, ret, parse_params(sig)))
                    changed = True
                    out.append(line)
                    i += 1
                    continue

            if not METHOD_RE.match(sig):
                cm = CTOR_RE.match(sig if "(" in sig else line)
                if cm and re.match(
                    rf"^{re.escape(indent)}(?:public|protected)\s+{re.escape(cm.group(3))}\s*\(",
                    line,
                ):
                    out.extend(build_doc(indent, cm.group(3), None, parse_params(sig)))
                    changed = True
                    out.append(line)
                    i += 1
                    continue

            im = IFACE_METHOD_RE.match(line)
            if im and in_interface_block(lines, i):
                ret, name = im.group(2).strip(), im.group(3)
                out.extend(build_doc(indent, name, ret, parse_params(line)))
                changed = True
                out.append(line)
                i += 1
                continue

        out.append(line)
        i += 1

    if changed:
        path.write_text("".join(out), encoding="utf-8")
    return changed


def main() -> None:
    n = 0
    for path in sorted(ROOT.rglob("*.java")):
        if process_file(path):
            n += 1
            print(path)
    print(f"updated {n} files under {ROOT}")


if __name__ == "__main__":
    main()
