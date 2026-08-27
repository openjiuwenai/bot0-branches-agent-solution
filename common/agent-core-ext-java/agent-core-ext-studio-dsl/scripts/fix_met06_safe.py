#!/usr/bin/env python3
"""G.MET.06 — replace `return null` only for Map/List/Set/Iterator return types."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")
RETURN_NULL = re.compile(r"^(\s*)return\s+null\s*;\s*$")
METHOD = re.compile(r"([\w.<>,?\[\]]+)\s+\w+\s*\(")


def method_rtype(lines: list[str], idx: int) -> str | None:
    for i in range(idx, max(-1, idx - 40), -1):
        if not re.search(r"\b(public|protected|private|static)\b", lines[i]):
            continue
        sig = lines[i]
        j = i
        while j + 1 < len(lines) and "{" not in sig and ";" not in sig:
            j += 1
            sig += lines[j]
        m = METHOD.search(sig.replace("\n", " "))
        return m.group(1).strip() if m else None
    return None


def repl(rtype: str) -> str | None:
    if rtype.startswith("Map"):
        return "return Map.of();"
    if rtype.startswith("List"):
        return "return List.of();"
    if rtype.startswith("Set"):
        return "return Set.of();"
    if rtype.startswith("Iterator"):
        return "return Collections.emptyIterator();"
    return None


def process_file(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    lines = original.splitlines(keepends=True)
    changed = False
    need_map = need_list = need_coll = False
    for i, ln in enumerate(lines):
        if not RETURN_NULL.match(ln.rstrip("\n")):
            continue
        rtype = method_rtype(lines, i)
        if not rtype:
            continue
        r = repl(rtype)
        if not r:
            continue
        indent = RETURN_NULL.match(ln.rstrip("\n")).group(1)
        lines[i] = f"{indent}{r}\n"
        changed = True
        if "Map.of" in r:
            need_map = True
        if "List.of" in r:
            need_list = True
        if "Collections" in r:
            need_coll = True
    if not changed:
        return False
    text = "".join(lines)
    if need_map and "import java.util.Map;" not in text:
        text = text.replace("package ", "import java.util.Map;\n\npackage ", 1)
    if need_list and "import java.util.List;" not in text:
        imports = list(re.finditer(r"(?m)^import .+;\n", text))
        if imports:
            text = text[: imports[-1].end()] + "import java.util.List;\n" + text[imports[-1].end() :]
    if need_coll and "import java.util.Collections;" not in text:
        imports = list(re.finditer(r"(?m)^import .+;\n", text))
        if imports:
            text = text[: imports[-1].end()] + "import java.util.Collections;\n" + text[imports[-1].end() :]
    path.write_text(text, encoding="utf-8")
    return True


def main() -> None:
    n = 0
    for p in sorted(ROOT.rglob("*.java")):
        if process_file(p):
            n += 1
            print("updated", p.relative_to(ROOT))
    print("changed", n)


if __name__ == "__main__":
    main()
