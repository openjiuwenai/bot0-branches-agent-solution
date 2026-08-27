#!/usr/bin/env python3
"""Batch-fix Huawei CodeCheck G.CMT.05 / G.FMT.03 / G.FMT.17 for registry-discovery-center."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

HEADER = """/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

"""

HEADER_MARK = "Copyright (c) Huawei Technologies Co., Ltd."


def import_group(stmt: str) -> int:
    """static → android → com.huawei → com.* → other → net → org → java/javax/jakarta."""
    s = stmt.strip()
    if s.startswith("import static "):
        return 0
    m = re.match(r"import\s+([\w.]+)", s)
    if not m:
        return 4
    pkg = m.group(1)
    if pkg.startswith("android."):
        return 1
    if pkg.startswith("com.huawei.") or pkg.startswith("com.hihonor."):
        return 2
    if pkg.startswith("com."):
        return 3
    if pkg.startswith("net."):
        return 5
    if pkg.startswith("org."):
        return 6
    if pkg.startswith(("java.", "javax.", "jakarta.")):
        return 7
    return 4


def reorder_imports(imports: list[str]) -> str:
    seen: set[str] = set()
    uniq: list[str] = []
    for ln in imports:
        if ln not in seen:
            seen.add(ln)
            uniq.append(ln)
    uniq.sort(key=lambda ln: (import_group(ln), ln.lower()))
    out: list[str] = []
    prev_g = None
    for ln in uniq:
        g = import_group(ln)
        if prev_g is not None and g != prev_g:
            out.append("")
        out.append(ln)
        prev_g = g
    return "\n".join(out)


def ensure_header(text: str) -> str:
    head = text[:1200]
    if HEADER_MARK in head:
        return text
    if re.search(r"Copyright\s*\([Cc]\)\s*Huawei", head):
        return text
    # Insert before package declaration
    m = re.search(r"(?m)^package\s+", text)
    if not m:
        return text
    return text[: m.start()] + HEADER + text[m.start() :]


def fix_annotations_same_line(text: str) -> str:
    pattern = re.compile(r"^([ \t]*)(@\w+(?:\([^;\n]*?\))?)\s+(@\w+)", re.MULTILINE)
    prev = None
    while prev != text:
        prev = text
        text = pattern.sub(lambda m: f"{m.group(1)}{m.group(2)}\n{m.group(1)}{m.group(3)}", text)
    return text


def process_file(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    text = ensure_header(original)

    pkg = re.search(r"(?m)^package\s+[\w.]+;\s*$", text)
    if pkg:
        after_pkg = pkg.end()
        # skip blank lines after package
        i = after_pkg
        while i < len(text) and text[i] == "\n":
            i += 1
        imports: list[str] = []
        j = i
        while j < len(text):
            nl = text.find("\n", j)
            if nl < 0:
                nl = len(text)
            line = text[j:nl]
            stripped = line.strip()
            if stripped.startswith("import "):
                imports.append(stripped.rstrip(";").rstrip() + ("" if stripped.endswith(";") else ""))
                # normalize semicolon
                if not imports[-1].endswith(";"):
                    imports[-1] += ";"
                j = nl + 1
                continue
            if stripped == "":
                # peek: if next non-empty is import, skip blank; else end import section
                k = nl + 1
                while k < len(text) and text[k] == "\n":
                    k += 1
                next_nl = text.find("\n", k)
                if next_nl < 0:
                    next_nl = len(text)
                nxt = text[k:next_nl].strip()
                if nxt.startswith("import "):
                    j = nl + 1
                    continue
                break
            break
        if imports:
            new_block = reorder_imports(imports)
            # keep single blank line after package before imports if there was content
            prefix = text[:after_pkg]
            if not prefix.endswith("\n\n"):
                if prefix.endswith("\n"):
                    prefix = prefix  # one newline after package is enough; add another
                prefix = text[:after_pkg]
            # package;\n + \n + imports + \n\n + rest
            rest = text[j:]
            text = text[:after_pkg] + "\n" + new_block + "\n\n" + rest.lstrip("\n")

    text = fix_annotations_same_line(text)

    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    changed = 0
    for path in sorted(ROOT.rglob("*.java")):
        if process_file(path):
            changed += 1
            print("updated", path.relative_to(ROOT))
    print(f"done, changed={changed}")


if __name__ == "__main__":
    main()
