"""Prompt formatting — placeholder substitution and dimension key building."""

from __future__ import annotations

import re

from evo_agent.evaluator.prompts.policy_v1 import (
    _DIAGNOSTIC_RULES_TEXT,
    DEFAULT_PROMPT_TEMPLATE,
)

# B2: validation 路径关闭归因时，从 prompt 中剥离归因章节与输出字段。
_ATTRIBUTION_SECTION_RE = re.compile(r"## 六、Skill 归因规则.*?---\n\n", re.DOTALL)
_ATTRIBUTED_SKILL_FIELD_RE = re.compile(r'"attributed_skill":\s*""[^\n]*\n')


def _strip_attribution(template: str) -> str:
    """Remove the skill-attribution section and output field from a template.

    Fail-fast: if the input contains an attribution section (detected by the
    ``归因规则`` sentinel) but it survives stripping, the regexes above no
    longer match — the prompt template likely changed. Raise instead of
    silently degrading (B2 / #15b relies on attribution being fully removed
    from the validation prompt to cut token cost).
    """
    had_section = "归因规则" in template
    template = _ATTRIBUTION_SECTION_RE.sub("", template, count=1)
    template = _ATTRIBUTED_SKILL_FIELD_RE.sub("", template, count=1)
    if had_section and "归因规则" in template:
        raise ValueError(
            "_strip_attribution: attribution section survived stripping — "
            "prompt template changed (section title/structure); update "
            "_ATTRIBUTION_SECTION_RE / _ATTRIBUTED_SKILL_FIELD_RE."
        )
    return template


def build_dimension_keys() -> list[str]:
    """Return the evaluation dimension keys.

    Three dimensions: task_completion (result achievement), trajectory_quality
    (execution process), safety (high-risk operation safeguards).
    """
    return [
        "task_completion",
        "trajectory_quality",
        "safety",
    ]


def extract_dimension_keys_from_prompt(prompt: str) -> list[str]:
    """Extract dimension keys from a prompt template by parsing the JSON schema.

    Looks for the "dimensions" field in the JSON output format example and extracts
    the keys. Falls back to build_dimension_keys() if parsing fails.
    """
    # Look for JSON schema with "dimensions" field
    # Pattern: "dimensions": { "key1": ..., "key2": ..., ... }
    pattern = r'"dimensions"\s*:\s*\{([^}]+)\}'
    match = re.search(pattern, prompt, re.DOTALL)

    if not match:
        return build_dimension_keys()

    dimensions_block = match.group(1)

    # Extract all keys from the dimensions block
    # Pattern: "key_name": followed by any value
    key_pattern = r'"([^"]+)"\s*:'
    keys = re.findall(key_pattern, dimensions_block)

    if not keys:
        return build_dimension_keys()

    return keys


def format_evaluation_prompt(
    template: str,
    *,
    trajectory: str,
    expected_result: str | None = None,
    skill_names: list[str] | None = None,
    enable_attribution: bool = True,
) -> str:
    """Fill template placeholders with evaluation data and return the prompt string.

    Uses sequential ``str.replace`` instead of ``str.format`` to avoid conflicts
    with literal braces in JSON output-format examples within the template.

    When ``enable_attribution`` is False, the skill-attribution section and the
    ``attributed_skill`` output field are stripped from the prompt (validation
    path does not consume attribution — see B2 / #15b).
    """
    if not enable_attribution:
        template = _strip_attribution(template)

    skill_names_list = skill_names or []

    expected_section = f"## 可选期望结果\n{expected_result}\n" if expected_result else ""

    # {messages} — raw trajectory content, no tag wrapping.
    messages_section = trajectory

    if skill_names_list:
        skill_lines = "\n".join(f"- {name}" for name in skill_names_list)
        skill_names_section = f"## 可用 Skill 列表\n{skill_lines}\n"
        # {skill_names} — plain comma-separated list for attribution reference.
        skill_names_plain = ", ".join(skill_names_list)
    else:
        skill_names_section = ""
        skill_names_plain = ""

    # Use sequential str.replace instead of str.format() to avoid conflicts
    # with literal braces in JSON output-format examples within the template.
    result = template
    result = result.replace("{expected_section}", expected_section)
    result = result.replace("{messages}", messages_section)
    result = result.replace("{skill_names_section}", skill_names_section)
    result = result.replace("{skill_names}", skill_names_plain)
    result = result.replace("{diagnostic_rules}", _DIAGNOSTIC_RULES_TEXT)
    return result


def build_evaluation_prompt(
    trajectory: str,
    expected_result: str | None = None,
    skill_names: list[str] | None = None,
    *,
    enable_attribution: bool = True,
) -> str:
    """Build a conversation-level LLM evaluation prompt."""
    return format_evaluation_prompt(
        DEFAULT_PROMPT_TEMPLATE,
        expected_result=expected_result,
        trajectory=trajectory,
        skill_names=skill_names,
        enable_attribution=enable_attribution,
    )
