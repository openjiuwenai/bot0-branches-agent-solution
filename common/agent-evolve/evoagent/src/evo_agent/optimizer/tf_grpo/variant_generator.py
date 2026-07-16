"""SKILL.md variant generation for TF-GRPO."""

from __future__ import annotations

import re

_FENCE_RE = re.compile(
    r"^```(?:markdown|md)?\s*\n(?P<body>.*?)\n```\s*$",
    re.DOTALL | re.IGNORECASE,
)
_FRONTMATTER_RE = re.compile(r"\A---\s*\n.*?\n---\s*\n?", re.DOTALL)
_ABRUPT_LINE_RE = re.compile(r"""(?:":\s*|,\s*|[\{\[\(]|:\s*)$""")
_SECTION_MARKERS = ("输出契约", "<answer>", "## 依赖")


def skill_document_incompleteness_reason(
    text: str,
    *,
    baseline: str | None = None,
) -> str | None:
    """Return a short reason when SKILL.md looks truncated; otherwise None."""
    raw = (text or "").strip()
    if not raw:
        return "empty"

    fence_count = sum(1 for line in raw.splitlines() if line.strip().startswith("```"))
    if fence_count % 2 != 0:
        return "unclosed_code_fence"

    if raw.count("<answer>") != raw.count("</answer>"):
        return "unclosed_answer_tag"

    last = next((line.rstrip() for line in reversed(raw.splitlines()) if line.strip()), "")
    if last and _ABRUPT_LINE_RE.search(last):
        return "abrupt_line_ending"

    if baseline:
        base = baseline.strip()
        if len(base) >= 500 and len(raw) < int(0.4 * len(base)):
            return "too_short_vs_baseline"
        for marker in _SECTION_MARKERS:
            if marker in base and marker not in raw:
                return f"missing_section:{marker}"
    return None


def is_complete_skill_document(text: str, *, baseline: str | None = None) -> bool:
    return skill_document_incompleteness_reason(text, baseline=baseline) is None


def strip_code_fence(text: str) -> str:
    raw = (text or "").strip()
    match = _FENCE_RE.match(raw)
    if match:
        return match.group("body").strip()
    if raw.startswith("```"):
        lines = raw.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        return "\n".join(lines).strip()
    return raw


def split_frontmatter(content: str) -> tuple[str, str]:
    """Return (frontmatter_with_delimiters_or_empty, body)."""
    match = _FRONTMATTER_RE.match(content or "")
    if not match:
        return "", content or ""
    return match.group(0), content[match.end() :]


def restore_frontmatter(original: str, generated: str, *, preserve: bool) -> str:
    """Keep original YAML frontmatter when preserve is True."""
    generated_clean = strip_code_fence(generated)
    if not preserve:
        return generated_clean
    fm, _ = split_frontmatter(original)
    _, body = split_frontmatter(generated_clean)
    if not fm:
        return generated_clean
    body = body.lstrip("\n")
    return f"{fm}{body}" if body else fm.rstrip() + "\n"


def build_variant_prompt(
    *,
    current_best: str,
    experience_context: str,
    epoch: int,
    max_content_chars: int = 15000,
) -> str:
    content = current_best
    if len(content) > max_content_chars:
        content = content[:max_content_chars] + "\n\n[... content truncated ...]"

    experience_block = experience_context.strip() or "(No prior experiences yet.)"
    return f"""Optimize this skill's documentation (SKILL.md) by making CONCRETE IMPROVEMENTS.

{experience_block}

**Current Best Version:**
{content}

**IMPORTANT INSTRUCTIONS:**
You must generate an IMPROVED version that ADDS missing elements when useful:

1. **Code Examples**: If missing, ADD concrete examples in markdown code blocks
2. **Documentation Structure**: Ensure Overview, Usage, Examples, Edge Cases where relevant
3. **Clarity**: Prefer concrete step-by-step instructions over abstract descriptions
4. **Frontmatter**: If the document starts with YAML frontmatter (--- ... ---), KEEP it unchanged

**Constraints:**
- Output the COMPLETE SKILL.md document only (no preamble, no explanations)
- Do not wrap the document in markdown code fences
- Never truncate: close every code fence / JSON block / <answer> tag; keep sections like 输出契约 and 依赖 if present in the current version
- Prefer targeted, concrete improvements over rewriting the whole document longer
- Epoch hint: {epoch} (vary improvements across rollouts; do not copy the current version verbatim)

Optimized SKILL.md:
"""
