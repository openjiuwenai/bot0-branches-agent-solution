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
        experience_context="# 已学习经验\n\n1. Add examples\n",
        epoch=2,
    )
    assert "Add examples" in prompt
    assert "当前最优版本" in prompt
    assert "完整的 SKILL.md" in prompt
    assert "禁止截断" in prompt
    assert "第 2 轮" in prompt
    assert "已学习经验" in prompt
    assert "自由探索" not in prompt


def test_build_variant_prompt_empty_experience_allows_exploration() -> None:
    prompt = build_variant_prompt(
        current_best="# Skill\n",
        experience_context="",
        epoch=1,
    )
    assert "经验库状态" in prompt
    assert "自由探索" in prompt
    assert "新增条款" in prompt
    assert "删除/收紧" in prompt
    assert "多样性要求" in prompt


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
