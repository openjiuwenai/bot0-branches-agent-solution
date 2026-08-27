#!/usr/bin/env python3
"""G.CMT.03 — javadoc blank line before tags, expand one-line docs, missing @throws Exception."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")


def blank_before_tags(text: str) -> str:
    def insert_blank(m: re.Match) -> str:
        desc, tag = m.group(1), m.group(2)
        indent = re.match(r"[ \t]*", tag).group(0)
        return f"{desc}\n{indent}*\n{tag}"

    text = re.sub(
        r"(?m)^([ \t]*\*[ \t]+(?![@\s/]).*)\n([ \t]*\*[ \t]*@(?:param|return|throws|since|author|version|see)\b)",
        insert_blank,
        text,
    )
    text = re.sub(
        r"(?m)^([ \t]*\*)\s*\n([ \t]*\*)\s*\n([ \t]*\*[ \t]*@)",
        r"\1\n\3",
        text,
    )
    return text


def expand_oneline_javadoc(text: str) -> str:
    def repl(m: re.Match) -> str:
        indent, body = m.group(1), m.group(2).strip()
        if body.startswith("@"):
            return m.group(0)
        return f"{indent}/**\n{indent} * {body}\n{indent} */"

    return re.sub(r"(?m)^([ \t]*)/\*\*\s+(.+?)\s+\*/\s*$", repl, text)


def add_exception_throws(text: str) -> str:
    """Add @throws Exception when method declares throws Exception and javadoc lacks it."""
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        if re.match(r"^[ \t]*/\*\*", lines[i]):
            doc_start = i
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
            sig_parts = []
            t = k
            while t < len(lines) and "{" not in "".join(sig_parts) and ";" not in "".join(sig_parts):
                sig_parts.append(lines[t])
                t += 1
            sig = "".join(sig_parts)
            doc_text = "".join(doc)
            if (
                "throws Exception" in sig
                and "@throws" not in doc_text
                and re.search(r"\b(public|protected)\b", sig)
            ):
                indent = re.match(r"^([ \t]*)", doc[0]).group(1)
                close_idx = len(doc) - 1
                while close_idx >= 0 and "*/" not in doc[close_idx]:
                    close_idx -= 1
                insert = [
                    f"{indent} *\n",
                    f"{indent} * @throws Exception when invocation fails\n",
                ]
                if close_idx >= 0:
                    doc = doc[:close_idx] + insert + doc[close_idx:]
                else:
                    doc.extend(insert)
            out.extend(doc)
            i = j
            continue
        out.append(lines[i])
        i += 1
    return "".join(out)


def process(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    text = original
    text = expand_oneline_javadoc(text)
    text = blank_before_tags(text)
    text = add_exception_throws(text)
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
