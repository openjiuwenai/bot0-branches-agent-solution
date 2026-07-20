"""Tests for the standalone artifact run report script."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType


def _load_report() -> ModuleType:
    path = (
        Path(__file__).resolve().parents[2] / "skills" / "optimize_skill" / "scripts" / "report.py"
    )
    spec = importlib.util.spec_from_file_location("artifact_report", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


report = _load_report()


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def test_report_summarizes_validation_changes_and_only_accepted_patches(tmp_path: Path) -> None:
    run_dir = tmp_path / "run-123"
    _write_json(
        run_dir / "epoch_1" / "gate_result.json",
        {
            "schema_version": 2,
            "status": "valid",
            "epoch": 1,
            "base_score": 0.5,
            "candidate_score": 0.7,
            "improvement": 0.2,
            "decision": "candidate",
            "selected_failure_rate": 0.25,
            "evaluated_count": 3,
            "attempted_count": 4,
            "coverage": 0.75,
        },
    )
    _write_json(
        run_dir / "epoch_2" / "gate_result.json",
        {
            "schema_version": 2,
            "status": "valid",
            "epoch": 2,
            "base_score": 0.7,
            "candidate_score": 0.6,
            "decision": "base",
            "selected_failure_rate": 0.5,
            "evaluated_count": 4,
            "attempted_count": 4,
            "coverage": 1.0,
        },
    )
    accepted_patch = run_dir / "search_skill" / "epoch_0" / "applied_diff.patch"
    accepted_patch.parent.mkdir(parents=True)
    accepted_patch.write_text("--- before\n+++ after\n+new rule\n", encoding="utf-8")
    rejected_patch = run_dir / "search_skill" / "epoch_1" / "applied_diff.patch"
    rejected_patch.parent.mkdir(parents=True)
    rejected_patch.write_text("--- before\n+++ after\n+rejected rule\n", encoding="utf-8")

    markdown = report.render_markdown(report.summarize_run(run_dir))

    assert (
        "| 1 | valid | candidate | 0.5000 | 0.7000 | +0.2000 | 75.0% | — | 3/4 | 75.0% |"
        in markdown
    )
    assert (
        "| 2 | valid | base | 0.7000 | 0.6000 | -0.1000 | 50.0% | -25.0% | 4/4 | 100.0% |"
        in markdown
    )
    assert "### search_skill（第 1 轮）" in markdown
    assert "+new rule" in markdown
    assert "rejected rule" not in markdown


def test_report_derives_pass_rate_from_validation_results(tmp_path: Path) -> None:
    run_dir = tmp_path / "run-456"
    _write_json(
        run_dir / "epoch_0" / "gate_result.json",
        {
            "epoch": 0,
            "base_score": 0.4,
            "candidate_score": 0.5,
            "decision": "candidate",
        },
    )
    _write_json(
        run_dir / "epoch_0" / "validation" / "results.json",
        {
            "score_threshold": 0.5,
            "results": [{"score": 0.2}, {"score": 0.5}, {"score": 0.9}],
        },
    )

    summary = report.summarize_run(run_dir)

    assert summary.validations[0].pass_rate == 2 / 3


def test_report_maps_one_based_gate_to_zero_based_single_skill_patch(tmp_path: Path) -> None:
    run_dir = tmp_path / "run-single"
    _write_json(run_dir / "summary.json", {"skills": ["search_skill"]})
    _write_json(
        run_dir / "epoch_1" / "gate_result.json",
        {
            "epoch": 1,
            "base_score": 0.4,
            "candidate_score": 0.6,
            "decision": "candidate",
        },
    )
    patch_path = run_dir / "epoch_0" / "step_0" / "applied_diff.patch"
    patch_path.parent.mkdir(parents=True)
    patch_path.write_text("--- before\n+++ after\n+accepted\n", encoding="utf-8")

    summary = report.summarize_run(run_dir)

    assert [(patch.skill, patch.epoch) for patch in summary.patches] == [("search_skill", 1)]
    assert "+accepted" in summary.patches[0].content


def test_main_writes_markdown_file(tmp_path: Path) -> None:
    run_dir = tmp_path / "run-789"
    run_dir.mkdir()
    output = tmp_path / "reports" / "summary.md"

    exit_code = report.main([str(run_dir), "--output", str(output)])

    assert exit_code == 0
    assert output.read_text(encoding="utf-8").startswith("# 优化 Run 总结")
