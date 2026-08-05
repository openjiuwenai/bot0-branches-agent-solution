"""Tests for TF-GRPO prompt template loading and scenario override."""

from __future__ import annotations

from pathlib import Path

from evo_agent.optimizer.tf_grpo.prompts import (
    LIBRARY_UPDATE,
    VARIANT_EMPTY,
    load_tf_grpo_prompt,
    render_prompt,
)
from evo_agent.optimizer.tf_grpo.variant_generator import build_variant_prompt


def test_load_bundled_variant_empty() -> None:
    text = load_tf_grpo_prompt(VARIANT_EMPTY)
    assert "自由探索" in text
    assert "{current_best}" in text
    assert "{focus_hint}" in text


def test_render_prompt_keeps_json_braces() -> None:
    template = load_tf_grpo_prompt(LIBRARY_UPDATE)
    out = render_prompt(
        template,
        {"current_library": "（空）", "semantic_advantage": "澄清期间矛盾"},
    )
    assert "澄清期间矛盾" in out
    assert '{"operation": "Add"' in out or '"operation": "Add"' in out
    assert "{current_library}" not in out


def test_scenario_override_wins(tmp_path: Path) -> None:
    prompts = tmp_path / "demo" / "prompts"
    prompts.mkdir(parents=True)
    (prompts / f"{VARIANT_EMPTY}.md").write_text(
        "OVERRIDE_EMPTY\n{current_best}\n{epoch}\n{focus_hint}\n",
        encoding="utf-8",
    )
    text = load_tf_grpo_prompt(
        VARIANT_EMPTY,
        scenario_name="demo",
        scenarios_dir=tmp_path,
    )
    assert text.startswith("OVERRIDE_EMPTY")


def test_build_variant_prompt_uses_scenario_override(tmp_path: Path) -> None:
    prompts = tmp_path / "demo" / "prompts"
    prompts.mkdir(parents=True)
    (prompts / f"{VARIANT_EMPTY}.md").write_text(
        "SCENARIO_VARIANT\nDOC={current_best}\nE={epoch}{focus_hint}\n",
        encoding="utf-8",
    )
    (prompts / "variant_focus_empty.md").write_text(
        "\nFOCUS={axis}\n",
        encoding="utf-8",
    )
    prompt = build_variant_prompt(
        current_best="# Skill\n",
        experience_context="",
        epoch=3,
        variant_index=1,
        group_size=2,
        scenario_name="demo",
        scenarios_dir=tmp_path,
    )
    assert "SCENARIO_VARIANT" in prompt
    assert "DOC=# Skill" in prompt
    assert "E=3" in prompt
    assert "FOCUS=" in prompt
