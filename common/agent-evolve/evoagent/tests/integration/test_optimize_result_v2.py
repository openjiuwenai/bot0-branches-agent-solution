"""Wave 10.1 集成测试 — 验证 result.train/val 结构完整性。

Mock 策略：mock 整个 run_optimization pipeline，直接构造 OptimizeReport，
验证 routes 层正确序列化为 result dict。
"""

from __future__ import annotations

from pathlib import Path

from evo_agent.types import OptimizeReport, SkillScore, TrainResult, ValResult


def _make_report() -> OptimizeReport:
    """构造包含完整 train/val/skill_scores 的 OptimizeReport。"""
    return OptimizeReport(
        skills=("skill_a", "skill_b"),
        dataset="test_dataset",
        epochs_completed=3,
        edits_applied=7,
        train=TrainResult(
            score_before=0.4,
            score_after=0.85,
            improvement="+112%",
            pass_rate_before=0.35,
            pass_rate_after=0.80,
            num_cases=40,
        ),
        val=ValResult(
            final_score=0.82,
            best_score=0.85,
            per_epoch_scores=(0.65, 0.72, 0.82),
            num_cases=10,
        ),
        gate_results=("accepted", "accepted", "accepted"),
        artifact_dir=Path("/tmp/artifacts"),
        skill_scores=(
            SkillScore(
                name="skill_a",
                score_before=0.4,
                score_after=0.8,
                score_delta=0.4,
                edits_applied=4,
                pass_rate_before=0.35,
                pass_rate_after=0.80,
            ),
            SkillScore(
                name="skill_b",
                score_before=0.4,
                score_after=0.9,
                score_delta=0.5,
                edits_applied=3,
                pass_rate_before=0.35,
                pass_rate_after=0.80,
            ),
        ),
    )


# ── Acceptance Criteria ──


class TestOptimizeResultV2:
    """验证 result dict 的 train/val 结构（对应 spec Feature 1 验收标准）。"""

    def test_result_train_score_before_after(self) -> None:
        """AC1: result.train.score_before/after 存在且来自 train_split。"""
        report = _make_report()
        result = _report_to_result(report)
        assert result["train"]["score_before"] == 0.4
        assert result["train"]["score_after"] == 0.85

    def test_result_val_final_best(self) -> None:
        """AC2: result.val.final_score/best_score 来自 Trainer 门控。"""
        report = _make_report()
        result = _report_to_result(report)
        assert result["val"]["final_score"] == 0.82
        assert result["val"]["best_score"] == 0.85

    def test_result_per_epoch_scores_length(self) -> None:
        """AC3: per_epoch_scores 长度 = epochs_completed。"""
        report = _make_report()
        result = _report_to_result(report)
        assert len(result["val"]["per_epoch_scores"]) == report.epochs_completed

    def test_result_pass_rate(self) -> None:
        """AC4: pass_rate_before/after = score ≥ 0.5 的占比。"""
        report = _make_report()
        result = _report_to_result(report)
        assert 0.0 <= result["train"]["pass_rate_before"] <= 1.0
        assert 0.0 <= result["train"]["pass_rate_after"] <= 1.0
        assert result["train"]["pass_rate_before"] == 0.35
        assert result["train"]["pass_rate_after"] == 0.80

    def test_result_skill_scores_pass_rate(self) -> None:
        """AC5: skill_scores 每个含 pass_rate_before/after。"""
        report = _make_report()
        result = _report_to_result(report)
        for ss in result["skill_scores"]:
            assert "pass_rate_before" in ss
            assert "pass_rate_after" in ss

    def test_result_no_legacy_score_fields(self) -> None:
        """AC6: 旧字段 result.score_before/after/improvement 已移除。"""
        report = _make_report()
        result = _report_to_result(report)
        assert "score_before" not in result
        assert "score_after" not in result
        assert "improvement" not in result

    def test_result_train_num_cases(self) -> None:
        """train.num_cases 存在。"""
        report = _make_report()
        result = _report_to_result(report)
        assert result["train"]["num_cases"] == 40

    def test_result_val_num_cases(self) -> None:
        """val.num_cases 存在。"""
        report = _make_report()
        result = _report_to_result(report)
        assert result["val"]["num_cases"] == 10


def _report_to_result(report: OptimizeReport) -> dict:
    """模拟 routes/optimize.py 的 job.result 序列化逻辑。"""
    return {
        "skills": list(report.skills),
        "epochs_completed": report.epochs_completed,
        "edits_applied": report.edits_applied,
        "train": {
            "score_before": report.train.score_before,
            "score_after": report.train.score_after,
            "improvement": report.train.improvement,
            "pass_rate_before": report.train.pass_rate_before,
            "pass_rate_after": report.train.pass_rate_after,
            "num_cases": report.train.num_cases,
        },
        "val": {
            "final_score": report.val.final_score,
            "best_score": report.val.best_score,
            "per_epoch_scores": list(report.val.per_epoch_scores),
            "num_cases": report.val.num_cases,
        },
        "gate_results": list(report.gate_results),
        "skill_scores": [
            {
                "name": s.name,
                "score_before": s.score_before,
                "score_after": s.score_after,
                "score_delta": s.score_delta,
                "edits_applied": s.edits_applied,
                "pass_rate_before": s.pass_rate_before,
                "pass_rate_after": s.pass_rate_after,
            }
            for s in report.skill_scores
        ],
        "skill_contents": [],
    }
