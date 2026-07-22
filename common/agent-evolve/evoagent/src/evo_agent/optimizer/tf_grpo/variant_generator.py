"""SKILL.md variant generation for TF-GRPO."""

from __future__ import annotations

import re
from pathlib import Path

from evo_agent.optimizer.tf_grpo.prompts import (
    VARIANT_EMPTY,
    VARIANT_FOCUS_EMPTY,
    VARIANT_FOCUS_WITH_EXPERIENCE,
    VARIANT_WITH_EXPERIENCE,
    load_tf_grpo_prompt,
    render_prompt,
)

_FENCE_RE = re.compile(
    r"^```(?:markdown|md)?\s*\n(?P<body>.*?)\n```\s*$",
    re.DOTALL | re.IGNORECASE,
)
_FRONTMATTER_RE = re.compile(r"\A---\s*\n.*?\n---\s*\n?", re.DOTALL)
_ABRUPT_LINE_RE = re.compile(r"""(?:":\s*|,\s*|[\{\[\(]|:\s*)$""")

_EMPTY_AXES = (
    "判定/决策门槛与例外条款（可增删改）",
    "流程步骤与易错点前置",
    "输出契约自检与文档结构重组",
)
_EXPERIENCE_LANDINGS = (
    "把经验落成更前置的判定/澄清步骤",
    "把经验落成更明确的自检清单与失败分支",
    "把经验落成更具体的边界示例与反例",
)


def skill_document_incompleteness_reason(
    text: str,
    *,
    baseline: str | None = None,
) -> str | None:
    """Return a short reason when text looks truncated; otherwise None.

    Generic truncation heuristics only (empty / unclosed fence / abrupt ending /
    too short vs baseline). Scenario-specific section markers are intentionally
    not checked here.
    """
    raw = (text or "").strip()
    if not raw:
        return "empty"

    fence_count = sum(1 for line in raw.splitlines() if line.strip().startswith("```"))
    if fence_count % 2 != 0:
        return "unclosed_code_fence"

    last = next((line.rstrip() for line in reversed(raw.splitlines()) if line.strip()), "")
    if last and _ABRUPT_LINE_RE.search(last):
        return "abrupt_line_ending"

    if baseline:
        base = baseline.strip()
        if len(base) >= 500 and len(raw) < int(0.4 * len(base)):
            return "too_short_vs_baseline"
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
    variant_index: int = 1,
    group_size: int = 1,
    scenario_name: str | None = None,
    scenarios_dir: Path | str | None = None,
    max_content_chars: int = 15000,
) -> str:
    content = current_best
    if len(content) > max_content_chars:
        content = content[:max_content_chars] + "\n\n[... 内容已截断 ...]"

    experience_raw = (experience_context or "").strip()
    experience_empty = not experience_raw
    idx = max(variant_index, 1)

    load_kw = {"scenario_name": scenario_name, "scenarios_dir": scenarios_dir}
    if experience_empty:
        axis = _EMPTY_AXES[(idx - 1) % len(_EMPTY_AXES)]
        focus_hint = render_prompt(
            load_tf_grpo_prompt(VARIANT_FOCUS_EMPTY, **load_kw),
            {
                "variant_index": idx,
                "group_size": max(group_size, 1),
                "axis": axis,
            },
        )
        return render_prompt(
            load_tf_grpo_prompt(VARIANT_EMPTY, **load_kw),
            {
                "current_best": content,
                "epoch": epoch,
                "focus_hint": focus_hint,
            },
        )

    landing = _EXPERIENCE_LANDINGS[(idx - 1) % len(_EXPERIENCE_LANDINGS)]
    focus_hint = render_prompt(
        load_tf_grpo_prompt(VARIANT_FOCUS_WITH_EXPERIENCE, **load_kw),
        {
            "variant_index": idx,
            "group_size": max(group_size, 1),
            "landing": landing,
        },
    )
    return render_prompt(
        load_tf_grpo_prompt(VARIANT_WITH_EXPERIENCE, **load_kw),
        {
            "experience_context": experience_raw,
            "current_best": content,
            "epoch": epoch,
            "focus_hint": focus_hint,
        },
    )
