"""agent_judge 维度 / 预设注册表单元测试 — 克隆 metrics registry 契约。"""

from __future__ import annotations

import pytest

from evo_agent.evaluator.agent_judge.dimensions import (
    JudgeDimension,
    get_dimension,
    list_dimensions,
    register_dimension,
)
from evo_agent.evaluator.agent_judge.presets import (
    JudgePreset,
    get_preset,
    list_presets,
    register_preset,
)
from evo_agent.evaluator.agent_judge.scorers import (
    WeightedSumScorer,
    WeightScorer,
    get_scorer,
    list_scorers,
    register_scorer,
)

_DEFAULT_DIMS = (
    "task_completion",
    "trajectory_quality",
    "safety",
    "answer_faithfulness",
    "planning_rationality",
)


class TestDimensionRegistry:
    def test_default_dimensions_registered(self) -> None:
        names = list_dimensions()
        for dim in _DEFAULT_DIMS:
            assert dim in names

    def test_get_dimension_returns_dataclass(self) -> None:
        dim = get_dimension("safety")
        assert isinstance(dim, JudgeDimension)
        assert dim.name == "safety"
        assert dim.prompt and dim.rubric

    def test_unknown_dimension_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown judge dimension"):
            get_dimension("definitely_not_a_dim")

    def test_register_then_get(self) -> None:
        dim = JudgeDimension(name="test_dim_xyz", prompt="p", rubric="r")
        register_dimension("test_dim_xyz", dim)
        assert get_dimension("test_dim_xyz") is dim

    def test_faithfulness_dimension_mounts_checklist(self) -> None:
        dim = get_dimension("answer_faithfulness")
        assert dim.skills == ("faithfulness_checklist",)

    def test_other_dimensions_have_no_dim_specific_skills(self) -> None:
        for name in ("task_completion", "trajectory_quality", "safety", "planning_rationality"):
            assert get_dimension(name).skills == ()


class TestPresetRegistry:
    def test_default_presets_registered(self) -> None:
        names = list_presets()
        assert "default" in names
        assert "codex_default" in names
        assert "safety_focus" in names

    def test_default_preset_shape(self) -> None:
        preset = get_preset("default")
        assert isinstance(preset, JudgePreset)
        assert preset.runtime == "claude"
        assert preset.dimensions == _DEFAULT_DIMS
        assert set(preset.weights) == set(_DEFAULT_DIMS)
        assert preset.tool_allowlist == ("Read", "Grep", "Bash")
        assert preset.pass_threshold == 0.6
        assert preset.scorer == "task_completion_gated"

    def test_codex_preset_runtime(self) -> None:
        assert get_preset("codex_default").runtime == "codex"

    def test_safety_focus_weights_safety(self) -> None:
        preset = get_preset("safety_focus")
        assert preset.weights["safety"] == 0.35

    def test_unknown_preset_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown judge preset"):
            get_preset("definitely_not_a_preset")

    def test_register_then_get(self) -> None:
        preset = JudgePreset(
            name="test_preset_xyz",
            dimensions=("task_completion",),
            weights={"task_completion": 1.0},
            helper_skills=(),
            runtime="claude",
        )
        register_preset("test_preset_xyz", preset)
        assert get_preset("test_preset_xyz") is preset


class TestScorerRegistry:
    def test_default_scorers_registered(self) -> None:
        names = list_scorers()
        assert "weighted_sum" in names
        assert "task_completion_gated" in names

    def test_get_scorer_returns_runtime_checkable(self) -> None:
        s = get_scorer("weighted_sum")
        assert isinstance(s, WeightScorer)

    def test_unknown_scorer_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown judge scorer"):
            get_scorer("definitely_not_a_scorer")

    def test_register_then_get(self) -> None:
        s = WeightedSumScorer()
        register_scorer("test_scorer_xyz", s)
        assert get_scorer("test_scorer_xyz") is s
