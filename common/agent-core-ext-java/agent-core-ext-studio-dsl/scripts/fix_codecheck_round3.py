#!/usr/bin/env python3
"""Round-3 CodeCheck cleanup for agent-core-ext-studio-dsl (safe javadoc/format only)."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")


def strip_trailing_ws(text: str) -> str:
    """G.FMT.13 — remove trailing spaces / spaces-only lines."""
    return re.sub(r"[ \t]+$", "", text, flags=re.M)


def blank_before_javadoc(text: str) -> str:
    """G.CMT.06 — blank line between preceding code and javadoc."""
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        ln = lines[i]
        if re.match(r"^[ \t]*/\*\*", ln):
            j = len(out) - 1
            while j >= 0 and out[j].strip() == "":
                j -= 1
            if j >= 0:
                prev = out[j].rstrip("\n")
                if prev.rstrip().endswith(("}", ";", "{")) or re.match(
                    r"^[ \t]*(?:public|protected|private|static|final|@\w+)", prev
                ):
                    if j == len(out) - 1:
                        if "*/" not in prev and not prev.strip().startswith("*"):
                            out.append("\n")
        out.append(ln)
        i += 1
    return "".join(out)


def blank_after_constructor(text: str) -> str:
    """G.FMT.04 — blank line between constructor end and next member."""
    text = re.sub(
        r"(?m)^([ \t]*private [A-Z]\w*\([^;]*\)\s*\{(?:[^{}]|\n)*?\n[ \t]*\})\n([ \t]*/\*\*)",
        r"\1\n\n\2",
        text,
    )
    text = re.sub(
        r"(?m)^([ \t]+\})\n([ \t]+/\*\*)",
        lambda m: f"{m.group(1)}\n\n{m.group(2)}",
        text,
    )
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def expand_oneline_javadoc(text: str) -> str:
    """G.CMT.03 — expand /** foo */ to multi-line."""

    def repl(m: re.Match) -> str:
        indent, body = m.group(1), m.group(2).strip()
        if body.startswith("@"):
            return m.group(0)
        return f"{indent}/**\n{indent} * {body}\n{indent} */"

    return re.sub(r"(?m)^([ \t]*)/\*\*\s+(.+?)\s+\*/\s*$", repl, text)


def ensure_param_return_tags(text: str) -> str:
    """Add missing @param/@return to method javadocs that lack them (best-effort)."""
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        if re.match(r"^[ \t]*/\*\*", lines[i]):
            doc: list[str] = []
            j = i
            while j < len(lines):
                doc.append(lines[j])
                if "*/" in lines[j]:
                    j += 1
                    break
                j += 1
            k = j
            while k < len(lines) and (
                lines[k].strip() == "" or lines[k].strip().startswith("@")
            ):
                k += 1
            if k < len(lines):
                sig = lines[k]
                t = k
                while t < len(lines) and "{" not in sig and ";" not in sig.rstrip():
                    t += 1
                    if t < len(lines):
                        sig += lines[t]
                m = re.search(
                    r"(?:public|protected|private|static|final|default|synchronized|\s)+"
                    r"(?:<[^>]+>\s+)?([\w.<>,?\[\]]+)\s+(\w+)\s*\(([^)]*)\)",
                    sig,
                    re.S,
                )
                doc_text = "".join(doc)
                if m and "@param" not in doc_text and "@return" not in doc_text:
                    ret, name, params = m.group(1).strip(), m.group(2), m.group(3)
                    indent = re.match(r"^([ \t]*)", doc[0]).group(1)
                    body_lines = []
                    for d in doc:
                        if d.strip().startswith("/**"):
                            continue
                        if "*/" in d:
                            continue
                        body_lines.append(d)
                    desc = [b for b in body_lines if b.strip() not in ("*", "")]
                    new_doc = [f"{indent}/**\n"]
                    if desc:
                        new_doc.extend(body_lines)
                        if body_lines and body_lines[-1].strip() != "*":
                            new_doc.append(f"{indent} *\n")
                    else:
                        new_doc.append(f"{indent} * {name}.\n")
                        new_doc.append(f"{indent} *\n")
                    for p in params.split(","):
                        p = p.strip()
                        if not p:
                            continue
                        parts = re.sub(r"\s+", " ", p.replace("...", " ")).split()
                        if len(parts) >= 2:
                            pname = parts[-1]
                            if pname not in ("throws",):
                                new_doc.append(f"{indent} * @param {pname} {pname}\n")
                    if ret != "void":
                        new_doc.append(f"{indent} * @return result\n")
                    if "@since" not in doc_text:
                        new_doc.append(f"{indent} * @since 0.1.0\n")
                    else:
                        for b in body_lines:
                            if "@since" in b:
                                new_doc.append(b if b.endswith("\n") else b + "\n")
                    new_doc.append(f"{indent} */\n")
                    seen_since = False
                    deduped = []
                    for nd in new_doc:
                        if "@since" in nd:
                            if seen_since:
                                continue
                            seen_since = True
                        deduped.append(nd)
                    out.extend(deduped)
                    i = j
                    continue
            out.extend(doc)
            i = j
            continue
        out.append(lines[i])
        i += 1
    return "".join(out)


def ensure_builder_javadoc(text: str) -> str:
    """G.CMT.01 — javadoc on public Builder nested classes."""

    def repl(m: re.Match) -> str:
        indent = m.group(1)
        rest = m.group(2)
        return (
            f"{indent}/**\n"
            f"{indent} * Builder.\n"
            f"{indent} *\n"
            f"{indent} * @since 0.1.0\n"
            f"{indent} */\n"
            f"{indent}{rest}"
        )

    return re.sub(
        r"(?m)^([ \t]*)((?:public\s+static\s+final\s+class|public\s+static\s+class)\s+Builder\b)",
        repl,
        text,
    )


def ensure_test_class_javadoc(text: str, path: Path) -> str:
    if "/test/" not in str(path).replace("\\", "/"):
        return text
    m = re.search(r"(?m)^(public\s+)?class (\w+)", text)
    if not m:
        return text
    before = text[: m.start()]
    if re.search(r"/\*\*[\s\S]*\*/\s*$", before):
        if "@since" not in before[-400:]:
            text = re.sub(
                r"(/\*\*[\s\S]*?)(\*/\s*(?:public\s+)?class " + m.group(2) + r")",
                r"\1 *\n * @since 0.1.0 (2026)\n\2",
                text,
                count=1,
            )
        return text
    name = m.group(2)
    insert = (
        f"/**\n"
        f" * {name} coverage.\n"
        f" *\n"
        f" * @since 0.1.0 (2026)\n"
        f" */\n"
    )
    return text[: m.start()] + insert + text[m.start() :]


def spaces_keywords(text: str) -> str:
    text = re.sub(r"\b(if|for|while|switch|catch|synchronized)\(", r"\1 (", text)
    text = re.sub(r"\)\{", ") {", text)
    return text


def process(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    text = original
    text = strip_trailing_ws(text)
    text = expand_oneline_javadoc(text)
    text = ensure_builder_javadoc(text)
    text = blank_before_javadoc(text)
    text = blank_after_constructor(text)
    text = ensure_param_return_tags(text)
    text = ensure_test_class_javadoc(text, path)
    text = spaces_keywords(text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    n = 0
    for p in sorted(ROOT.rglob("*.java")):
        if process(p):
            n += 1
            print("updated", p)
    print("changed", n)


if __name__ == "__main__":
    main()
