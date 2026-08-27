#!/usr/bin/env python3
"""Round-2 Huawei CodeCheck cleanup for registry-discovery-center."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")


def fix_javadoc_closing_indent(text: str) -> str:
    """G.FMT.18 — closing */ must align with opening /** indent."""
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if "/**" in line and not line.strip().startswith("//"):
            # find matching */
            j = i
            open_indent = re.match(r"^([ \t]*)", line).group(1)
            while j < len(lines):
                if "*/" in lines[j] and j >= i:
                    # normalize closing line indent
                    close = lines[j]
                    # only fix pure closing or trailing */
                    m = re.match(r"^([ \t]*)\*/([ \t]*\n?)$", close)
                    if m:
                        lines[j] = f"{open_indent} */{m.group(2) if m.group(2).endswith(chr(10)) else m.group(2)}\n"
                        if not lines[j].endswith("\n"):
                            lines[j] = lines[j].rstrip("\n") + "\n"
                    elif re.match(r"^([ \t]*)\*/\s*$", close):
                        lines[j] = f"{open_indent} */\n"
                    break
                j += 1
        out.append(lines[i])
        i += 1
    # simpler pass: fix known bad `  */` after class-level docs
    text = "".join(out) if out else text
    # When opening is at column 0 `/**`, closing must be ` */` not `  */`
    text = re.sub(
        r"(?m)^(/\*\*(?:.*\n)*?)(  \*/\s*)$",
        lambda m: m.group(1) + " */\n" if m.group(1).startswith("/**") else m.group(0),
        text,
    )
    # Fix any line that is exactly two-space closing
    text = re.sub(r"(?m)^  \*/\s*$", " */", text)
    return text


def fix_cmt03_blank_before_tags(text: str) -> str:
    """G.CMT.03 — blank javadoc line between description and @tags."""

    def insert_blank(m: re.Match) -> str:
        desc, tag = m.group(1), m.group(2)
        indent = re.match(r"[ \t]*", tag).group(0)
        return f"{desc}\n{indent}*\n{tag}"

    text = re.sub(
        r"(?m)^([ \t]*\*[ \t]+(?![@\s/]).*)\n([ \t]*\*[ \t]*@(?:param|return|throws|since|author|version)\b)",
        insert_blank,
        text,
    )
    # collapse accidental double blanks before @tags
    text = re.sub(
        r"(?m)^([ \t]*\*)\s*\n([ \t]*\*)\s*\n([ \t]*\*[ \t]*@)",
        r"\1\n\3",
        text,
    )
    return text


def move_javadoc_before_annotations(text: str) -> str:
    """Place method javadoc above @Override/@Bean/@Test etc."""
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        # detect annotation run then javadoc then method
        if re.match(r"^[ \t]*@", lines[i]):
            ann_start = i
            j = i
            anns: list[str] = []
            while j < len(lines) and (
                re.match(r"^[ \t]*@", lines[j]) or lines[j].strip() == ""
            ):
                if lines[j].strip() != "":
                    anns.append(lines[j])
                j += 1
            if j < len(lines) and re.match(r"^[ \t]*/\*\*", lines[j]):
                # collect javadoc
                doc_start = j
                k = j
                doc: list[str] = []
                while k < len(lines):
                    doc.append(lines[k])
                    if "*/" in lines[k]:
                        k += 1
                        break
                    k += 1
                # only rearrange if next is a method/type member
                if k < len(lines) and re.match(
                    r"^[ \t]*(?:public|protected|private|static|final|default|abstract|synchronized|native|strictfp|class|interface|enum|record|<)",
                    lines[k],
                ):
                    indent = re.match(r"^([ \t]*)", anns[0]).group(1)
                    # ensure doc indent matches
                    fixed_doc = []
                    for d in doc:
                        if d.strip().startswith("*") or d.strip().startswith("/**") or d.strip() == "*/":
                            # re-indent
                            stripped = d.lstrip()
                            if stripped.startswith("/**"):
                                fixed_doc.append(f"{indent}{stripped}")
                                if not fixed_doc[-1].endswith("\n"):
                                    fixed_doc[-1] += "\n"
                            elif stripped.startswith("*/"):
                                fixed_doc.append(f"{indent} */\n")
                            else:
                                # * content
                                content = stripped[1:]  # drop *
                                if content.startswith(" "):
                                    fixed_doc.append(f"{indent} *{content}")
                                else:
                                    fixed_doc.append(f"{indent} * {content.lstrip()}")
                                if not fixed_doc[-1].endswith("\n"):
                                    fixed_doc[-1] += "\n"
                        else:
                            fixed_doc.append(d)
                    out.extend(fixed_doc)
                    out.extend(anns)
                    i = k
                    continue
            out.append(lines[i])
            i += 1
            continue
        out.append(lines[i])
        i += 1
    return "".join(out)


def expand_single_line_braces(text: str) -> str:
    """G.FMT.06/13 — expand `) { stmt; }` one-liners for methods."""
    # method bodies like: public String current() { return current; }
    pat = re.compile(
        r"(?m)^([ \t]*(?:(?:public|protected|private|static|final|default|synchronized|native|abstract|strictfp)\s+)*"
        r"[\w.<>,?\[\]]+\s+\w+\s*\([^;]*\))\s*\{\s*(.+?)\s*\}\s*$"
    )

    def repl(m: re.Match) -> str:
        sig, body = m.group(1), m.group(2)
        indent = re.match(r"^([ \t]*)", sig).group(1)
        # skip lambdas / very complex
        if "->" in body or "\n" in body:
            return m.group(0)
        return f"{sig} {{\n{indent}    {body}\n{indent}}}"

    return pat.sub(repl, text)


def spaces_around_keywords(text: str) -> str:
    text = re.sub(r"\b(if|for|while|switch|catch|synchronized)\(", r"\1 (", text)
    text = re.sub(r"\)\{", ") {", text)
    text = re.sub(r"else\{", "else {", text)
    text = re.sub(r"try\{", "try {", text)
    text = re.sub(r"\}\s*catch\s*\(", "} catch (", text)
    text = re.sub(r"\}\s*finally\s*\{", "} finally {", text)
    text = re.sub(r"\}\s*else\s*\{", "} else {", text)
    return text


def collapse_blank_lines(text: str) -> str:
    return re.sub(r"\n{3,}", "\n\n", text)


def remove_section_banners(text: str) -> str:
    """G.OTH.03 — delete // ===== ... ===== banner comments."""
    text = re.sub(r"(?m)^[ \t]*//\s*={3,}.*\n?", "", text)
    return text


def remove_blank_after_class_open(text: str) -> str:
    """G.FMT.12 — no blank line right after class/interface/enum `{` before first member."""
    text = re.sub(
        r"(?m)^(public |protected |private )?(final )?(class|interface|enum|record) .+\{\n\n([ \t]+\S)",
        r"\g<0>",
        text,
    )
    # simpler:
    text = re.sub(
        r"(\{)\n\n([ \t]*(?:/\*|//|private|public|protected|static|final|@|\w))",
        r"\1\n\2",
        text,
    )
    return text


def ensure_class_since(text: str) -> str:
    """G.CMT.02 — top-level public type javadoc needs @since."""
    # If public class/interface/enum/record has javadoc without @since, add it
    def add_since(m: re.Match) -> str:
        doc, rest = m.group(1), m.group(2)
        if "@since" in doc:
            return m.group(0)
        # insert before closing */
        doc2 = re.sub(r"\n([ \t]*)\*/", r"\n\n\1 * @since 0.1.0 (2026)\n\1 */", doc, count=1)
        return doc2 + rest

    text = re.sub(
        r"(?s)(/\*\*.*?\*/)(\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+(?:final\s+)?(?:class|interface|enum|record)\s)",
        add_since,
        text,
        count=1,
    )
    return text


def ensure_test_class_javadoc(text: str, path: Path) -> str:
    if "/test/" not in str(path).replace("\\", "/"):
        return text
    if not re.search(r"(?m)^public class \w+", text):
        return text
    # if already has class javadoc before public class, ensure description
    if re.search(r"(?s)/\*\*.*?\*/\s*(?:@\w+.*\n)*public class", text):
        return ensure_class_since(text)
    m = re.search(r"(?m)^public class (\w+)", text)
    if not m:
        return text
    name = m.group(1)
    insert = (
        f"/**\n"
        f" * {name} coverage.\n"
        f" *\n"
        f" * @since 0.1.0 (2026)\n"
        f" */\n"
    )
    return text[: m.start()] + insert + text[m.start() :]


def process(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    text = original
    text = remove_section_banners(text)
    text = move_javadoc_before_annotations(text)
    text = fix_javadoc_closing_indent(text)
    text = fix_cmt03_blank_before_tags(text)
    text = expand_single_line_braces(text)
    text = spaces_around_keywords(text)
    text = remove_blank_after_class_open(text)
    text = collapse_blank_lines(text)
    text = ensure_test_class_javadoc(text, path)
    text = ensure_class_since(text)
    # final closing indent pass
    text = re.sub(r"(?m)^  \*/\s*$", " */", text)
    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    changed = 0
    for p in sorted(ROOT.rglob("*.java")):
        if process(p):
            changed += 1
            print("updated", p)
    print("changed", changed)


if __name__ == "__main__":
    main()
