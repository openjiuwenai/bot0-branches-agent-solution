"""Unit tests for SKILL.md variant helpers."""

from __future__ import annotations

from evo_agent.optimizer.tf_grpo.variant_generator import (
    build_variant_prompt,
    is_complete_skill_document,
    restore_frontmatter,
    skill_document_incompleteness_reason,
    strip_code_fence,
)


def test_strip_code_fence_markdown() -> None:
    raw = "```markdown\n# Hello\n\nBody\n```"
    assert strip_code_fence(raw) == "# Hello\n\nBody"


def test_restore_frontmatter_preserves_yaml() -> None:
    original = "---\nname: demo\n---\n\n# Old\n"
    generated = "```\n# New body\nwith examples\n```"
    out = restore_frontmatter(original, generated, preserve=True)
    assert out.startswith("---\nname: demo\n---\n")
    assert "# New body" in out


def test_build_variant_prompt_includes_experience() -> None:
    prompt = build_variant_prompt(
        current_best="# Skill\n",
        experience_context="# Learned Experiences\n\n1. Add examples\n",
        epoch=2,
    )
    assert "Add examples" in prompt
    assert "Current Best Version" in prompt
    assert "COMPLETE SKILL.md" in prompt
    assert "Never truncate" in prompt


def test_reject_truncated_skill_with_unclosed_fence() -> None:
    truncated = (
        '# Skill\n\n```json\n{\n  "used_workdays": 13,\n  "is_violated":'
    )
    assert skill_document_incompleteness_reason(truncated) == "unclosed_code_fence"
    assert not is_complete_skill_document(truncated)


def test_reject_missing_output_contract_vs_baseline() -> None:
    baseline = (
        "# A\n\n## 输出契约\n\n<body>\n\n```\n<answer>\n{}\n</answer>\n```\n\n## 依赖\n\n- x\n"
    )
    incomplete = "# A\n\nOnly overview rewritten.\n"
    assert (
        skill_document_incompleteness_reason(incomplete, baseline=baseline)
        == "missing_section:输出契约"
    )


def test_accept_complete_skill_document() -> None:
    complete = (
        "---\nname: demo\n---\n\n# Skill\n\n## 输出契约\n\n"
        '```json\n{"a": 1}\n```\n\n'
        "```\n<answer>\n{}\n</answer>\n```\n\n## 依赖\n\n- script\n"
    )
    assert is_complete_skill_document(complete, baseline=complete)
