"""OptimizeRequest / OptimizeReport / SkillScore / TrainResult / ValResult 类型测试。"""

from __future__ import annotations

from pathlib import Path

import pytest

from evo_agent.types import OptimizeReport, OptimizeRequest, SkillScore, TrainResult, ValResult

# ── OptimizeRequest ──


class TestOptimizeRequest:
    def test_required_fields(self) -> None:
        """scenario 和 agent_name 是必填字段（无默认值）。"""
        with pytest.raises(TypeError):
            OptimizeRequest()  # type: ignore[call-arg]

    def test_minimal_construction(self) -> None:
        """仅传必填字段即可构造（API 模式）。"""
        req = OptimizeRequest(
            scenario="edp_agent",
            agent_name="test_agent",
        )
        assert req.scenario == "edp_agent"
        assert req.agent_name == "test_agent"

    def test_api_mode_fields(self) -> None:
        """API 模式：dataset_path 填充，dataset_manifest_path=None。"""
        req = OptimizeRequest(
            scenario="edp_agent",
            agent_name="test_agent",
            dataset_path="/data/evo_agent/items.json",
            dataset_manifest_path=None,
            task_name="test-task-001",
            train_split=0.8,
            val_split=0.2,
        )
        assert req.dataset_path == "/data/evo_agent/items.json"
        assert req.dataset_manifest_path is None
        assert req.task_name == "test-task-001"

    def test_cli_mode_fields(self) -> None:
        """CLI 模式：dataset_manifest_path 填充，dataset_path=''。"""
        req = OptimizeRequest(
            scenario="edp_agent",
            agent_name="test_agent",
            dataset_manifest_path=Path("/tmp/dataset.yaml"),
            adapter_url="http://localhost:9090",
        )
        assert req.dataset_manifest_path == Path("/tmp/dataset.yaml")
        assert req.dataset_path == ""
        assert req.adapter_url == "http://localhost:9090"

    def test_default_values(self) -> None:
        """验证所有可选字段的默认值。"""
        req = OptimizeRequest(
            scenario="test",
            agent_name="agent",
        )
        assert req.skills == []
        assert req.dataset_path == ""
        assert req.dataset_manifest_path is None
        assert req.evaluator_prompt == ""
        assert req.adapter_url == ""
        assert req.num_epochs is None
        assert req.batch_size is None
        assert req.hyperparams == {}
        assert req.rollout_extra_data == {}
        assert req.train_split == 0.8
        assert req.val_split == 0.2
        assert req.task_name == ""

    def test_no_template_id_field(self) -> None:
        """optimizer_template_id 字段已移除。"""
        req = OptimizeRequest(scenario="test", agent_name="agent")
        assert not hasattr(req, "optimizer_template_id")

    def test_default_splits(self) -> None:
        """默认 train_split=0.8, val_split=0.2。"""
        req = OptimizeRequest(scenario="test", agent_name="agent")
        assert req.train_split == 0.8
        assert req.val_split == 0.2

    def test_skills_list(self) -> None:
        """skills 字段接受 skill 名称列表。"""
        req = OptimizeRequest(
            scenario="test",
            agent_name="agent",
            skills=["skill_a", "skill_b"],
        )
        assert req.skills == ["skill_a", "skill_b"]


# ── OptimizeReport ──


class TestOptimizeReport:
    def test_skills_tuple(self) -> None:
        """skills 字段为 tuple[str, ...]。"""
        report = OptimizeReport(
            skills=("skill_a", "skill_b"),
            dataset="test_dataset",
            epochs_completed=3,
            edits_applied=5,
            train=TrainResult(0.4, 0.7, "+75%", 0.4, 0.7, 20),
            val=ValResult(0.65, 0.7, (0.6, 0.65, 0.7), 5),
            gate_results=("accepted", "accepted", "accepted"),
            artifact_dir=Path("/tmp/artifacts"),
        )
        assert report.skills == ("skill_a", "skill_b")

    def test_no_skill_name_field(self) -> None:
        """OptimizeReport 不应有 skill_name 字段。"""
        report = OptimizeReport(
            skills=("skill_a",),
            dataset="test_dataset",
            epochs_completed=1,
            edits_applied=1,
            train=TrainResult(0.0, 1.0, "+100%", 0.0, 1.0, 10),
            val=ValResult(0.8, 0.8, (0.8,), 5),
            gate_results=("accepted",),
            artifact_dir=Path("/tmp/artifacts"),
        )
        assert not hasattr(report, "skill_name")
        assert not hasattr(report, "output_skill_path")

    def test_report_includes_skill_scores(self) -> None:
        """OptimizeReport.skill_scores 包含所有 skill。"""
        scores = (
            SkillScore(
                name="skill_a",
                score_before=0.4,
                score_after=0.7,
                score_delta=0.3,
                edits_applied=2,
            ),
            SkillScore(
                name="skill_b",
                score_before=0.5,
                score_after=0.8,
                score_delta=0.3,
                edits_applied=3,
            ),
        )
        report = OptimizeReport(
            skills=("skill_a", "skill_b"),
            dataset="test",
            epochs_completed=1,
            edits_applied=5,
            train=TrainResult(0.45, 0.75, "+67%", 0.45, 0.75, 10),
            val=ValResult(0.7, 0.7, (0.7,), 5),
            gate_results=("accepted",),
            artifact_dir=Path("/tmp/art"),
            skill_scores=scores,
        )
        assert len(report.skill_scores) == 2
        assert report.skill_scores[0].name == "skill_a"
        assert report.skill_scores[1].name == "skill_b"

    def test_report_skill_scores_default_empty(self) -> None:
        """skill_scores 默认空 tuple（向下兼容）。"""
        report = OptimizeReport(
            skills=("skill_a",),
            dataset="test",
            epochs_completed=1,
            edits_applied=1,
            train=TrainResult(0.0, 1.0, "+100%", 0.0, 1.0, 10),
            val=ValResult(0.8, 0.8, (0.8,), 5),
            gate_results=("accepted",),
            artifact_dir=Path("/tmp/art"),
        )
        assert report.skill_scores == ()


# ── SkillScore ──


class TestSkillScore:
    def test_skill_score_fields(self) -> None:
        """SkillScore 包含 name, score_before, score_after, score_delta, edits_applied。"""
        s = SkillScore(
            name="product_recommend_skill",
            score_before=0.4,
            score_after=0.7,
            score_delta=0.3,
            edits_applied=2,
        )
        assert s.name == "product_recommend_skill"
        assert s.score_before == 0.4
        assert s.score_after == 0.7
        assert s.score_delta == 0.3
        assert s.edits_applied == 2

    def test_skill_score_frozen(self) -> None:
        """SkillScore 是 frozen dataclass。"""
        s = SkillScore(
            name="skill_a",
            score_before=0.0,
            score_after=1.0,
            score_delta=1.0,
            edits_applied=0,
        )
        with pytest.raises(AttributeError):
            s.name = "other"  # type: ignore[misc]


# ── TrainResult ──


class TestTrainResult:
    def test_train_result_fields(self) -> None:
        """TrainResult 包含 score_before/after, improvement, pass_rate, num_cases。"""
        tr = TrainResult(
            score_before=0.6,
            score_after=0.85,
            improvement="+42%",
            pass_rate_before=0.55,
            pass_rate_after=0.80,
            num_cases=40,
        )
        assert tr.score_before == 0.6
        assert tr.score_after == 0.85
        assert tr.improvement == "+42%"
        assert tr.pass_rate_before == 0.55
        assert tr.pass_rate_after == 0.80
        assert tr.num_cases == 40

    def test_train_result_frozen(self) -> None:
        """TrainResult 是 frozen dataclass。"""
        tr = TrainResult(
            score_before=0.5,
            score_after=0.7,
            improvement="+40%",
            pass_rate_before=0.5,
            pass_rate_after=0.7,
            num_cases=10,
        )
        with pytest.raises(AttributeError):
            tr.score_after = 0.9  # type: ignore[misc]


# ── ValResult ──


class TestValResult:
    def test_val_result_per_epoch_scores_tuple(self) -> None:
        """ValResult.per_epoch_scores 为 tuple[float, ...]。"""
        vr = ValResult(
            final_score=0.82,
            best_score=0.85,
            per_epoch_scores=(0.65, 0.72, 0.82),
            num_cases=10,
        )
        assert vr.final_score == 0.82
        assert vr.best_score == 0.85
        assert vr.per_epoch_scores == (0.65, 0.72, 0.82)
        assert isinstance(vr.per_epoch_scores, tuple)
        assert vr.num_cases == 10

    def test_val_result_empty_per_epoch(self) -> None:
        """per_epoch_scores 为空 tuple 时 final_score/best_score 可为 0.0。"""
        vr = ValResult(final_score=0.0, best_score=0.0, per_epoch_scores=(), num_cases=0)
        assert vr.per_epoch_scores == ()
        assert vr.num_cases == 0

    def test_val_result_frozen(self) -> None:
        """ValResult 是 frozen dataclass。"""
        vr = ValResult(final_score=0.8, best_score=0.85, per_epoch_scores=(0.8,), num_cases=5)
        with pytest.raises(AttributeError):
            vr.final_score = 0.9  # type: ignore[misc]

    def test_val_result_card_fields_default_none(self) -> None:
        """improvement/pass_rate_before/pass_rate_after 缺省为 None（向下兼容）。

        镜像 TrainResult 的同名字段，供 studio 完成卡片两卡对称渲染；
        旧报告（无新字段）序列化不报错。
        """
        vr = ValResult(final_score=0.8, best_score=0.85, per_epoch_scores=(0.8,), num_cases=5)
        assert vr.improvement is None
        assert vr.pass_rate_before is None
        assert vr.pass_rate_after is None

    def test_val_result_card_fields_settable(self) -> None:
        """三字段可显式赋值，与 TrainResult 字段语义对齐。"""
        vr = ValResult(
            final_score=0.85,
            best_score=0.85,
            per_epoch_scores=(0.7, 0.85),
            num_cases=5,
            score_before=0.6,
            improvement="+42%",
            pass_rate_before=0.4,
            pass_rate_after=0.8,
        )
        assert vr.improvement == "+42%"
        assert vr.pass_rate_before == 0.4
        assert vr.pass_rate_after == 0.8


# ── SkillScore pass_rate ──


class TestSkillScorePassRate:
    def test_skill_score_pass_rate_defaults(self) -> None:
        """pass_rate_before/pass_rate_after 默认值为 0.0。"""
        s = SkillScore(
            name="x",
            score_before=0.5,
            score_after=0.7,
            score_delta=0.2,
            edits_applied=3,
        )
        assert s.pass_rate_before == 0.0
        assert s.pass_rate_after == 0.0

    def test_skill_score_pass_rate_explicit(self) -> None:
        """显式传入 pass_rate_before/pass_rate_after。"""
        s = SkillScore(
            name="x",
            score_before=0.5,
            score_after=0.7,
            score_delta=0.2,
            edits_applied=3,
            pass_rate_before=0.45,
            pass_rate_after=0.75,
        )
        assert s.pass_rate_before == 0.45
        assert s.pass_rate_after == 0.75


# ── OptimizeReport train/val ──


class TestOptimizeReportTrainVal:
    def test_optimize_report_uses_train_val(self) -> None:
        """OptimizeReport 使用 train: TrainResult + val: ValResult。"""
        report = OptimizeReport(
            skills=("s",),
            dataset="d",
            epochs_completed=3,
            edits_applied=5,
            train=TrainResult(0.5, 0.7, "+40%", 0.5, 0.7, 10),
            val=ValResult(0.65, 0.7, (0.6, 0.65, 0.7), 5),
            gate_results=("accepted",),
            artifact_dir=Path("/tmp"),
        )
        assert hasattr(report, "train")
        assert hasattr(report, "val")
        assert report.train.score_before == 0.5
        assert report.train.score_after == 0.7
        assert report.val.final_score == 0.65
        assert report.val.per_epoch_scores == (0.6, 0.65, 0.7)

    def test_optimize_report_no_legacy_fields(self) -> None:
        """OptimizeReport 不再有 score_before/score_after/improvement 顶层字段。"""
        report = OptimizeReport(
            skills=("s",),
            dataset="d",
            epochs_completed=1,
            edits_applied=1,
            train=TrainResult(0.5, 0.7, "+40%", 0.5, 0.7, 10),
            val=ValResult(0.65, 0.7, (0.65,), 5),
            gate_results=("accepted",),
            artifact_dir=Path("/tmp"),
        )
        assert not hasattr(report, "score_before")
        assert not hasattr(report, "score_after")
        assert not hasattr(report, "improvement")

    def test_optimize_report_with_skill_scores(self) -> None:
        """OptimizeReport 携带 skill_scores（含 pass_rate）。"""
        scores = (
            SkillScore(
                name="skill_a",
                score_before=0.4,
                score_after=0.7,
                score_delta=0.3,
                edits_applied=2,
                pass_rate_before=0.4,
                pass_rate_after=0.7,
            ),
        )
        report = OptimizeReport(
            skills=("skill_a",),
            dataset="test",
            epochs_completed=1,
            edits_applied=2,
            train=TrainResult(0.4, 0.7, "+75%", 0.4, 0.7, 10),
            val=ValResult(0.6, 0.6, (0.6,), 5),
            gate_results=("accepted",),
            artifact_dir=Path("/tmp"),
            skill_scores=scores,
        )
        assert len(report.skill_scores) == 1
        assert report.skill_scores[0].pass_rate_before == 0.4
        assert report.skill_scores[0].pass_rate_after == 0.7


# ── TrajectoryUnavailableError ──


class TestTrajectoryUnavailableError:
    def test_is_exception(self) -> None:
        """TrajectoryUnavailableError 继承 Exception。"""
        from evo_agent.types import TrajectoryUnavailableError

        err = TrajectoryUnavailableError("test message")
        assert isinstance(err, Exception)
        assert str(err) == "test message"

    def test_not_evaluation_error(self) -> None:
        """不继承 EvaluationError，避免被评估器静默捕获。"""
        from evo_agent.evaluator.domain.scoring import EvaluationError
        from evo_agent.types import TrajectoryUnavailableError

        err = TrajectoryUnavailableError("msg")
        assert not isinstance(err, EvaluationError)
