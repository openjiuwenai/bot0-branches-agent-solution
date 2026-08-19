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
    @staticmethod
    def test_default_dimensions_registered() -> None:
        names = list_dimensions()
        for dim in _DEFAULT_DIMS:
            assert dim in names

    @staticmethod
    def test_get_dimension_returns_dataclass() -> None:
        dim = get_dimension("safety")
        assert isinstance(dim, JudgeDimension)
        assert dim.name == "safety"
        assert dim.prompt and dim.rubric

    @staticmethod
    def test_unknown_dimension_raises() -> None:
        with pytest.raises(ValueError, match="Unknown judge dimension"):
            get_dimension("definitely_not_a_dim")

    @staticmethod
    def test_register_then_get() -> None:
        dim = JudgeDimension(name="test_dim_xyz", prompt="p", rubric="r")
        register_dimension("test_dim_xyz", dim)
        assert get_dimension("test_dim_xyz") is dim

    @staticmethod
    def test_faithfulness_dimension_mounts_checklist() -> None:
        dim = get_dimension("answer_faithfulness")
        assert dim.skills == ("faithfulness_checklist",)

    @staticmethod
    def test_other_dimensions_have_no_dim_specific_skills() -> None:
        for name in ("task_completion", "trajectory_quality", "safety", "planning_rationality"):
            assert get_dimension(name).skills == ()


class TestPresetRegistry:
    @staticmethod
    def test_default_presets_registered() -> None:
        names = list_presets()
        assert "default" in names
        assert "codex_default" in names
        assert "safety_focus" in names
        assert "jiuwenswarm_default" in names

    @staticmethod
    def test_default_preset_shape() -> None:
        preset = get_preset("default")
        assert isinstance(preset, JudgePreset)
        assert preset.runtime == "claude"
        assert preset.dimensions == _DEFAULT_DIMS
        assert set(preset.weights) == set(_DEFAULT_DIMS)
        assert preset.tool_allowlist == ("Read", "Grep", "Bash")
        assert preset.pass_threshold == 0.6
        assert preset.scorer == "task_completion_gated"

    @staticmethod
    def test_codex_preset_runtime() -> None:
        assert get_preset("codex_default").runtime == "codex"

    @staticmethod
    def test_jiuwenswarm_preset_runtime() -> None:
        preset = get_preset("jiuwenswarm_default")
        assert preset.runtime == "jiuwenswarm"
        assert preset.dimensions == _DEFAULT_DIMS
        assert preset.tool_allowlist == ("Read", "Grep", "Bash")

    @staticmethod
    def test_safety_focus_weights_safety() -> None:
        preset = get_preset("safety_focus")
        assert preset.weights["safety"] == 0.35

    @staticmethod
    def test_unknown_preset_raises() -> None:
        with pytest.raises(ValueError, match="Unknown judge preset"):
            get_preset("definitely_not_a_preset")

    @staticmethod
    def test_register_then_get() -> None:
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
    @staticmethod
    def test_default_scorers_registered() -> None:
        names = list_scorers()
        assert "weighted_sum" in names
        assert "task_completion_gated" in names

    @staticmethod
    def test_get_scorer_returns_runtime_checkable() -> None:
        s = get_scorer("weighted_sum")
        assert isinstance(s, WeightScorer)

    @staticmethod
    def test_unknown_scorer_raises() -> None:
        with pytest.raises(ValueError, match="Unknown judge scorer"):
            get_scorer("definitely_not_a_scorer")

    @staticmethod
    def test_register_then_get() -> None:
        s = WeightedSumScorer()
        register_scorer("test_scorer_xyz", s)
        assert get_scorer("test_scorer_xyz") is s
