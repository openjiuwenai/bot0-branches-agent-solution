"""Unit tests for the attribution_calculator evaluator skill script."""

from __future__ import annotations

import json
import subprocess
import sys
from importlib.resources import files

import pytest

_SCRIPT = str(
    files(
        "evo_agent.evaluator.agent_judge.evaluator_skills.common.attribution_calculator"
    ).joinpath("attribution_calculator.py")
)


def _run(
    weights: dict[str, float],
    judgments: dict[str, float],
    *,
    gate: str | None = None,
) -> dict:
    """Invoke the calculator script as a subprocess and parse stdout JSON."""
    cmd = [
        sys.executable,
        _SCRIPT,
        "--weights",
        json.dumps(weights),
        "--judgments",
        json.dumps(judgments),
    ]
    if gate is not None:
        cmd.extend(["--gate", gate])
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)  # noqa: S603
    if result.returncode != 0:
        raise RuntimeError(f"script failed: {result.stderr}")
    return json.loads(result.stdout)


class TestAttributionCalculator:
    def test_gate_applied(self) -> None:
        result = _run(
            weights={"task_completion": 0.2, "safety": 0.4, "answer_faithfulness": 0.4},
            judgments={"task_completion": 0.75, "safety": 1.0, "answer_faithfulness": 1.0},
        )
        assert result["gate_applied"] is True
        assert result["gate_score"] == 0.75
        assert result["weighted_avg"] == 1.0
        assert result["overall_score"] == pytest.approx(0.75)

    def test_gate_multiplies_weighted_avg(self) -> None:
        result = _run(
            weights={
                "task_completion": 0.2,
                "trajectory_quality": 0.2,
                "safety": 0.2,
                "answer_faithfulness": 0.2,
                "planning_rationality": 0.2,
            },
            judgments={
                "task_completion": 0.75,
                "trajectory_quality": 0.85,
                "safety": 1.0,
                "answer_faithfulness": 1.0,
                "planning_rationality": 1.0,
            },
        )
        # weighted avg of other 4 = (0.85 + 1.0 + 1.0 + 1.0) / 4 = 0.9625
        # overall = 0.75 * 0.9625 = 0.721875
        assert result["overall_score"] == pytest.approx(0.721875)
        assert result["gate_applied"] is True

    def test_gate_absent_fallback_to_weighted_avg(self) -> None:
        result = _run(
            weights={"safety": 0.5, "answer_faithfulness": 0.5},
            judgments={"safety": 0.8, "answer_faithfulness": 1.0},
        )
        # task_completion not in judgments → fallback
        assert result["gate_applied"] is False
        assert result["overall_score"] == pytest.approx(0.9)

    def test_gate_disabled(self) -> None:
        result = _run(
            weights={"task_completion": 0.5, "safety": 0.5},
            judgments={"task_completion": 0.6, "safety": 0.8},
            gate="",
        )
        assert result["gate_applied"] is False
        # weighted avg = (0.5*0.6 + 0.5*0.8) / 1.0 = 0.7
        assert result["overall_score"] == pytest.approx(0.7)

    def test_custom_gate(self) -> None:
        result = _run(
            weights={"safety": 0.5, "task_completion": 0.5},
            judgments={"safety": 0.6, "task_completion": 0.9},
            gate="safety",
        )
        assert result["gate_applied"] is True
        assert result["gate_score"] == 0.6
        # other dims: task_completion=0.9 with weight 0.5 → wavg = 0.9
        assert result["overall_score"] == pytest.approx(0.54)

    def test_only_gate_dimension(self) -> None:
        result = _run(
            weights={"task_completion": 1.0},
            judgments={"task_completion": 0.5},
        )
        assert result["gate_applied"] is True
        assert result["overall_score"] == pytest.approx(0.5)

    def test_empty_judgments(self) -> None:
        result = _run(weights={}, judgments={})
        assert result["overall_score"] == 0.0
        assert result["gate_applied"] is False

    def test_zero_weights_fallback_to_equal_mean(self) -> None:
        result = _run(
            weights={"safety": 0.0, "answer_faithfulness": 0.0},
            judgments={"safety": 0.8, "answer_faithfulness": 1.0},
        )
        # all weights 0 → equal mean fallback
        assert result["overall_score"] == pytest.approx(0.9)

    def test_clamped_to_01(self) -> None:
        # Weights that could theoretically push above 1.0
        result = _run(
            weights={"safety": 1.0},
            judgments={"safety": 1.5},
            gate="",
        )
        assert result["overall_score"] <= 1.0

    def test_invalid_json_exits_nonzero(self) -> None:
        cmd = [sys.executable, _SCRIPT, "--weights", "not_json", "--judgments", "{}"]
        result = subprocess.run(cmd, capture_output=True, text=True, check=False)  # noqa: S603
        assert result.returncode != 0

    def test_equal_weights_match_preset(self) -> None:
        """Verify the script matches the previous WeightScorer result for the finance trajectory."""
        result = _run(
            weights={
                "task_completion": 0.2,
                "trajectory_quality": 0.2,
                "safety": 0.2,
                "answer_faithfulness": 0.2,
                "planning_rationality": 0.2,
            },
            judgments={
                "task_completion": 0.75,
                "trajectory_quality": 0.85,
                "safety": 1.0,
                "answer_faithfulness": 1.0,
                "planning_rationality": 1.0,
            },
        )
        assert result["overall_score"] == pytest.approx(0.721875)


def _run_thresholds(
    thresholds: dict[str, float],
    judgments: dict[str, float],
) -> dict:
    """Invoke the calculator script in threshold-check mode and parse stdout JSON."""
    cmd = [
        sys.executable,
        _SCRIPT,
        "--thresholds",
        json.dumps(thresholds),
        "--judgments",
        json.dumps(judgments),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)  # noqa: S603
    if result.returncode != 0:
        raise RuntimeError(f"script failed: {result.stderr}")
    return json.loads(result.stdout)


class TestAttributionCalculatorThresholds:
    def test_all_pass(self) -> None:
        result = _run_thresholds(
            thresholds={"task_completion": 0.5, "safety": 0.8},
            judgments={"task_completion": 0.75, "safety": 1.0},
        )
        assert result["all_pass"] is True
        assert result["failed"] == []
        assert len(result["checks"]) == 2
        assert all(c["pass"] for c in result["checks"])

    def test_some_fail(self) -> None:
        result = _run_thresholds(
            thresholds={"task_completion": 0.5, "safety": 0.8},
            judgments={"task_completion": 0.3, "safety": 1.0},
        )
        assert result["all_pass"] is False
        assert result["failed"] == ["task_completion"]
        tc_check = next(c for c in result["checks"] if c["dimension"] == "task_completion")
        assert tc_check["pass"] is False
        assert tc_check["score"] == 0.3
        assert tc_check["threshold"] == 0.5

    def test_exact_threshold_passes(self) -> None:
        result = _run_thresholds(
            thresholds={"safety": 0.8},
            judgments={"safety": 0.8},
        )
        assert result["all_pass"] is True
        assert result["checks"][0]["pass"] is True

    def test_missing_dimension_fails(self) -> None:
        result = _run_thresholds(
            thresholds={"safety": 0.8, "missing_dim": 0.5},
            judgments={"safety": 1.0},
        )
        assert result["all_pass"] is False
        assert "missing_dim" in result["failed"]
        missing_check = next(c for c in result["checks"] if c["dimension"] == "missing_dim")
        assert missing_check["score"] is None
        assert missing_check["pass"] is False

    def test_sorted_output(self) -> None:
        result = _run_thresholds(
            thresholds={"zebra": 0.5, "alpha": 0.5},
            judgments={"zebra": 0.8, "alpha": 0.8},
        )
        dims = [c["dimension"] for c in result["checks"]]
        assert dims == sorted(dims)
