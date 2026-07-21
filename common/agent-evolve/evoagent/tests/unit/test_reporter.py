"""ReportFormatter 单元测试。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from evo_agent.reporter.formatter import ReportFormatter
from evo_agent.types import OptimizeReport


def test_format_reads_artifact_dir(tmp_path: Path) -> None:
    """验证从 summary.json 读取报告数据。"""
    summary = {
        "skills": ["test_skill"],
        "dataset": "test_dataset",
        "epochs_completed": 3,
        "score_before": 0.4,
        "score_after": 0.7,
        "edits_applied": 5,
    }
    (tmp_path / "summary.json").write_text(json.dumps(summary), encoding="utf-8")

    formatter = ReportFormatter(tmp_path, skills=("test_skill",))
    report = formatter.format()

    assert isinstance(report, OptimizeReport)
    assert report.skills == ("test_skill",)
    assert report.dataset == "test_dataset"
    assert report.epochs_completed == 3
    assert report.train.score_before == 0.4
    assert report.train.score_after == 0.7
    assert report.edits_applied == 5


def test_format_calculates_improvement(tmp_path: Path) -> None:
    """验证改进百分比计算。"""
    summary = {"score_before": 0.4, "score_after": 0.5}
    (tmp_path / "summary.json").write_text(json.dumps(summary), encoding="utf-8")

    report = ReportFormatter(tmp_path).format()

    assert report.train.improvement == "+25%"


def test_format_handles_missing_epochs(tmp_path: Path) -> None:
    """验证缺少 epoch 数据时使用默认值。"""
    # 空 artifact 目录
    report = ReportFormatter(tmp_path).format()

    assert report.train.score_before == 0.0
    assert report.train.score_after == 0.0
    assert report.train.improvement == "0%"
    assert report.gate_results == ()
    assert report.edits_applied == 0


def test_format_collects_gate_results(tmp_path: Path) -> None:
    """验证从 epoch 目录收集 gate 结果。"""
    for i, decision in enumerate(["accepted", "rejected", "accepted"]):
        epoch_dir = tmp_path / f"epoch_{i}"
        epoch_dir.mkdir()
        (epoch_dir / "gate_result.json").write_text(
            json.dumps({"decision": decision}),
            encoding="utf-8",
        )

    report = ReportFormatter(tmp_path).format()

    assert report.gate_results == ("accepted", "rejected", "accepted")
    assert report.epochs_completed == 3


def test_format_no_output_skill_path(tmp_path: Path) -> None:
    """验证 OptimizeReport 不再有 output_skill_path 字段。"""
    final_dir = tmp_path / "final"
    final_dir.mkdir()
    (final_dir / "skill.md").write_text("# Optimized Skill", encoding="utf-8")

    report = ReportFormatter(tmp_path).format()

    # OptimizeReport 不再包含 output_skill_path
    assert not hasattr(report, "output_skill_path")


# ── Multi-skill report ──


def test_format_multi_skill(tmp_path: Path) -> None:
    """多 skill 子目录正确解析，每个 skill 读取 summary.json。"""
    for skill_name, score_after in [("skill_a", 0.7), ("skill_b", 0.8)]:
        skill_dir = tmp_path / skill_name
        skill_dir.mkdir()
        (skill_dir / "summary.json").write_text(
            json.dumps(
                {
                    "skills": [skill_name],
                    "score_before": 0.4,
                    "score_after": score_after,
                    "edits_applied": 2,
                }
            ),
            encoding="utf-8",
        )

    formatter = ReportFormatter(tmp_path, skills=("skill_a", "skill_b"))
    report = formatter.format()

    assert len(report.skill_scores) == 2
    names = {s.name for s in report.skill_scores}
    assert names == {"skill_a", "skill_b"}


def test_overall_score_is_mean(tmp_path: Path) -> None:
    """overall = mean(per-skill score_after)。"""
    for skill_name, score_after in [("skill_a", 0.6), ("skill_b", 0.8)]:
        skill_dir = tmp_path / skill_name
        skill_dir.mkdir()
        (skill_dir / "summary.json").write_text(
            json.dumps(
                {
                    "skills": [skill_name],
                    "score_before": 0.4,
                    "score_after": score_after,
                }
            ),
            encoding="utf-8",
        )

    formatter = ReportFormatter(tmp_path, skills=("skill_a", "skill_b"))
    report = formatter.format()

    # mean(0.6, 0.8) = 0.7
    assert abs(report.train.score_after - 0.7) < 1e-6


def test_format_single_skill_backward_compat(tmp_path: Path) -> None:
    """单 skill 目录（旧格式，无 skill 子目录）仍正常工作。"""
    summary = {
        "skills": ["test_skill"],
        "score_before": 0.4,
        "score_after": 0.7,
    }
    (tmp_path / "summary.json").write_text(json.dumps(summary), encoding="utf-8")

    report = ReportFormatter(tmp_path, skills=("test_skill",)).format()

    assert report.train.score_after == 0.7
    assert report.skill_scores == ()


def test_missing_skill_dir_defaults(tmp_path: Path) -> None:
    """skill 子目录不存在时 score 默认为 0。"""
    # No skill subdirectories exist
    formatter = ReportFormatter(tmp_path, skills=("missing_skill",))
    report = formatter.format()

    assert len(report.skill_scores) == 1
    assert report.skill_scores[0].name == "missing_skill"
    assert report.skill_scores[0].score_after == 0.0


# ── W10.2: pass_rate + train/val ──


def test_compute_pass_rate_all_pass() -> None:
    """所有 score >= 0.5 → pass_rate = 1.0。"""
    assert ReportFormatter._compute_pass_rate([0.6, 0.7, 0.8]) == 1.0


def test_compute_pass_rate_all_fail() -> None:
    """所有 score < 0.5 → pass_rate = 0.0。"""
    assert ReportFormatter._compute_pass_rate([0.1, 0.2]) == 0.0


def test_compute_pass_rate_empty() -> None:
    """空列表 → pass_rate = 0.0。"""
    assert ReportFormatter._compute_pass_rate([]) == 0.0


def test_compute_pass_rate_mixed() -> None:
    """混合分数，0.5 算 pass。"""
    result = ReportFormatter._compute_pass_rate([0.6, 0.3, 0.5])
    assert abs(result - 2 / 3) < 1e-6


def test_format_train_result_from_eval_results(tmp_path: Path) -> None:
    """从 eval_results.json 计算 train score_before/after + pass_rate + num_cases。"""
    # epoch_0/step_0/eval_results.json (before)
    step_0 = tmp_path / "epoch_0" / "step_0"
    step_0.mkdir(parents=True)
    (step_0 / "eval_results.json").write_text(
        json.dumps(
            {
                "avg_score": 0.4,
                "results": [
                    {"case_id": "c1", "score": 0.3},
                    {"case_id": "c2", "score": 0.5},
                ],
            }
        ),
        encoding="utf-8",
    )
    # epoch_2/step_0/eval_results.json (after)
    step_1 = tmp_path / "epoch_2" / "step_0"
    step_1.mkdir(parents=True)
    (step_1 / "eval_results.json").write_text(
        json.dumps(
            {
                "avg_score": 0.8,
                "results": [
                    {"case_id": "c1", "score": 0.7},
                    {"case_id": "c2", "score": 0.9},
                ],
            }
        ),
        encoding="utf-8",
    )

    report = ReportFormatter(tmp_path).format()
    assert report.train.score_before == 0.4
    assert report.train.score_after == 0.8
    assert report.train.pass_rate_before == 0.5  # 1/2 >= 0.5
    assert report.train.pass_rate_after == 1.0  # 2/2 >= 0.5
    assert report.train.num_cases == 2


def test_format_val_result_from_injected_scores(tmp_path: Path) -> None:
    """val 结果从注入的 val_per_epoch_scores 组装。"""
    report = ReportFormatter(
        tmp_path,
        val_per_epoch_scores=(0.6, 0.7, 0.8),
        num_val_cases=10,
    ).format()
    assert report.val.final_score == 0.8
    assert report.val.best_score == 0.8
    assert report.val.per_epoch_scores == (0.6, 0.7, 0.8)
    assert report.val.num_cases == 10


def test_format_val_result_empty_scores(tmp_path: Path) -> None:
    """无 val 数据时 per_epoch_scores 为空，final/best 为 0.0。"""
    report = ReportFormatter(tmp_path).format()
    assert report.val.final_score == 0.0
    assert report.val.best_score == 0.0
    assert report.val.per_epoch_scores == ()
    assert report.val.num_cases == 0


def test_format_val_result_best_includes_baseline(tmp_path: Path) -> None:
    """best_score = max(基线, 候选峰值)；基线主导时 best=基线（committed 最佳未被动摇）。

    per_epoch_scores 为候选 fresh eval（会波动），best_score/final_score 仍为
    committed 最佳（= 基线 0.9），趋势图则画出候选波动（0.5, 0.6）。
    """
    report = ReportFormatter(
        tmp_path,
        val_per_epoch_scores=(0.5, 0.6),
        val_score_before=0.9,
        num_val_cases=8,
    ).format()
    assert report.val.per_epoch_scores == (0.5, 0.6)  # 候选波动，非赢家直线
    assert report.val.best_score == 0.9  # 基线主导，committed 最佳
    assert report.val.final_score == 0.9  # final = best
    assert report.val.score_before == 0.9
    assert report.val.num_cases == 8


def test_format_val_result_best_is_candidate_peak_when_improved(tmp_path: Path) -> None:
    """候选超越基线时 best=候选峰值；final=best。"""
    report = ReportFormatter(
        tmp_path,
        val_per_epoch_scores=(0.65, 0.72, 0.82),  # 候选波动，末轮峰值
        val_score_before=0.6,
    ).format()
    assert report.val.per_epoch_scores == (0.65, 0.72, 0.82)
    assert report.val.best_score == 0.82  # max(0.6, 0.82)
    assert report.val.final_score == 0.82


def test_format_skill_scores_have_pass_rate(tmp_path: Path) -> None:
    """多 skill 模式下，skill_scores 包含 pass_rate_before/after。"""
    # 创建 eval_results.json（共享）
    step_dir = tmp_path / "epoch_0" / "step_0"
    step_dir.mkdir(parents=True)
    (step_dir / "eval_results.json").write_text(
        json.dumps(
            {
                "avg_score": 0.5,
                "results": [
                    {"case_id": "c1", "score": 0.6},
                    {"case_id": "c2", "score": 0.4},
                ],
            }
        ),
        encoding="utf-8",
    )

    for skill_name in ("skill_a", "skill_b"):
        skill_dir = tmp_path / skill_name
        skill_dir.mkdir()
        (skill_dir / "summary.json").write_text(
            json.dumps({"score_before": 0.4, "score_after": 0.7, "edits_applied": 2}),
            encoding="utf-8",
        )

    report = ReportFormatter(tmp_path, skills=("skill_a", "skill_b")).format()
    for s in report.skill_scores:
        assert hasattr(s, "pass_rate_before")
        assert hasattr(s, "pass_rate_after")
        # 共享 eval_results → 各 skill pass_rate 相同
        assert s.pass_rate_before == 0.5  # 1/2 >= 0.5


# ── train pass_rate_after argmax（A3）：取 avg_score 最高份通过率，非末份 ──


def _write_eval(path: Path, avg_score: float, scores: list[float]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "avg_score": avg_score,
                "results": [{"case_id": f"c{i}", "score": s} for i, s in enumerate(scores)],
            }
        ),
        encoding="utf-8",
    )


def test_train_pass_rate_after_uses_argmax_not_last(tmp_path: Path) -> None:
    """pass_rate_after 取 avg_score 最高份通过率，非末份。

    三份 eval_results.json（按路径排序 epoch_0 < epoch_2 < epoch_3）：
    - epoch_0: avg=0.4, per-case=[0.3, 0.5] → pass 1/2=0.5（first → before）
    - epoch_2: avg=0.6, per-case=[0.6, 0.6] → pass 2/2=1.0（max → 应取）
    - epoch_3: avg=0.5, per-case=[0.4, 0.4] → pass 0/2=0.0（last → 现状误取）

    证红：现状 pass_rate_after=末份=0.0；正确=argmax 份=1.0。
    """
    _write_eval(tmp_path / "epoch_0" / "step_0" / "eval_results.json", 0.4, [0.3, 0.5])
    _write_eval(tmp_path / "epoch_2" / "step_0" / "eval_results.json", 0.6, [0.6, 0.6])
    _write_eval(tmp_path / "epoch_3" / "step_0" / "eval_results.json", 0.5, [0.4, 0.4])

    report = ReportFormatter(tmp_path).format()
    assert report.train.score_before == 0.4  # first avg
    assert report.train.score_after == 0.6  # max avg（不变，已正确）
    assert report.train.pass_rate_before == 0.5  # first per-case
    # 关键：argmax 份（epoch_2）通过率 1.0，非末份 0.0
    assert report.train.pass_rate_after == 1.0


def test_train_pass_rate_after_max_in_last_no_regression(tmp_path: Path) -> None:
    """max avg 在末份时 pass_rate_after 取末份（与现状一致，不回退）。"""
    _write_eval(tmp_path / "epoch_0" / "step_0" / "eval_results.json", 0.4, [0.3, 0.5])
    _write_eval(tmp_path / "epoch_2" / "step_0" / "eval_results.json", 0.8, [0.8, 0.8])

    report = ReportFormatter(tmp_path).format()
    assert report.train.score_after == 0.8
    assert report.train.pass_rate_after == 1.0  # 末份即 max 份


def test_train_pass_rate_after_no_avg_falls_back_to_last(tmp_path: Path) -> None:
    """无 avg_score 字段时回退末份 per-case（与 score_after 回退同源）。"""
    # 写两份无 avg_score 的 eval_results.json
    for epoch, scores in [("epoch_0", [0.3, 0.5]), ("epoch_1", [0.7, 0.7])]:
        p = tmp_path / epoch / "step_0" / "eval_results.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(
            json.dumps(
                {"results": [{"case_id": f"c{i}", "score": s} for i, s in enumerate(scores)]}
            ),
            encoding="utf-8",
        )

    report = ReportFormatter(tmp_path).format()
    # 无 avg → best_data None → best_scores 回退 last_scores
    assert report.train.pass_rate_after == 1.0  # 末份 [0.7,0.7] → 2/2


# ── val 三字段（A2）：improvement / pass_rate_before / pass_rate_after ──
# per-case 走 in-memory 传参，不落 artifact（避开 _artifact_epoch vs
# current_epoch off-by-one）。


def test_val_result_normal_case(tmp_path: Path) -> None:
    """候选超越基线：improvement / pass_rate_before / pass_rate_after 正确。

    baseline per-case=[0.3, 0.5]（thresh 0.5 → 1/2 通过 → 0.5），score_before=0.4。
    per-epoch 候选分=(0.45, 0.7)，best=max(0.4, 0.7)=0.7 → argmax=epoch 1。
    epoch 1 赢家 per-case=[0.7, 0.7] → pass_rate_after=1.0。
    """
    report = ReportFormatter(
        tmp_path,
        val_per_epoch_scores=(0.45, 0.7),
        val_score_before=0.4,
        num_val_cases=2,
        val_baseline_case_scores=[0.3, 0.5],
        val_per_epoch_case_scores=[[0.4, 0.5], [0.7, 0.7]],
    ).format()
    assert report.val.improvement == "+75%"  # (0.7-0.4)/0.4*100
    assert report.val.pass_rate_before == 0.5  # 1/2 >= 0.5
    assert report.val.pass_rate_after == 1.0  # argmax=epoch 1: [0.7,0.7] → 2/2


def test_val_result_degenerate_no_improvement(tmp_path: Path) -> None:
    """基线主导（best==score_before）→ pass_rate_after 回退 pass_rate_before。

    per-epoch 候选分=(0.4, 0.3) 均 < 基线 0.5 → best=0.5=score_before，无改进。
    improvement = _calc_improvement(0.5, 0.5) = "+0%"（复用 train 同款函数）。
    """
    report = ReportFormatter(
        tmp_path,
        val_per_epoch_scores=(0.4, 0.3),
        val_score_before=0.5,
        num_val_cases=2,
        val_baseline_case_scores=[0.5, 0.3],
        val_per_epoch_case_scores=[[0.4, 0.4], [0.3, 0.3]],
    ).format()
    assert report.val.best_score == 0.5  # 基线主导
    assert report.val.improvement == "+0%"
    assert report.val.pass_rate_before == 0.5  # [0.5,0.3] → 1/2
    # best(0.5) > score_before(0.5) 为假 → 回退 pass_rate_before
    assert report.val.pass_rate_after == report.val.pass_rate_before


def test_val_result_argmax_not_last_epoch(tmp_path: Path) -> None:
    """best 在首轮（非末轮）→ pass_rate_after 取首轮赢家 per-case，非末轮。

    证红点：若 pass_rate_after 误取末轮 per-case=[0.3,0.3] → 0.0；
    正确取 argmax(候选分)=epoch 0 的 [0.8,0.8] → 1.0。
    """
    report = ReportFormatter(
        tmp_path,
        val_per_epoch_scores=(0.8, 0.6),  # 峰值在 epoch 0
        val_score_before=0.5,
        num_val_cases=2,
        val_baseline_case_scores=[0.3, 0.3],
        val_per_epoch_case_scores=[[0.8, 0.8], [0.3, 0.3]],
    ).format()
    assert report.val.best_score == 0.8  # max(0.5, 0.8, 0.6)
    assert report.val.improvement == "+60%"  # (0.8-0.5)/0.5*100
    assert report.val.pass_rate_before == 0.0  # [0.3,0.3] → 0/2
    # argmax=epoch 0（非末轮）→ [0.8,0.8] → 2/2 = 1.0；末轮会是 0.0
    assert report.val.pass_rate_after == 1.0


def test_val_result_no_per_case_data(tmp_path: Path) -> None:
    """未传 per-case → pass_rate_before/after=0.0、improvement 由 score 推出。

    旧调用方（仅传 val_per_epoch_scores）不报错；studio 侧 None 仅出现在
    未序列化新字段的旧报告，新报告总有计算值。
    """
    report = ReportFormatter(
        tmp_path,
        val_per_epoch_scores=(0.6, 0.8),
        val_score_before=0.4,
        num_val_cases=2,
    ).format()
    assert report.val.improvement == "+100%"  # (0.8-0.4)/0.4*100
    assert report.val.pass_rate_before == 0.0  # 空 baseline → 0.0
    # best(0.8) > score_before(0.4) 但无 per-case → 回退 pass_rate_before
    assert report.val.pass_rate_after == 0.0


# ── managed-doc formatter artifact（spec F8）──


def _write_summary(tmp_path: Path) -> None:
    summary = {
        "skills": ["managed_doc:agent_rule"],
        "dataset": "agent_rule_dataset",
        "epochs_completed": 1,
        "edits_applied": 0,
    }
    (tmp_path / "summary.json").write_text(json.dumps(summary), encoding="utf-8")


def test_report_includes_managed_doc_kind_before_after_task_ids(tmp_path: Path) -> None:
    """report 回填 F1 四字段：kind / before / after / task_ids。"""
    _write_summary(tmp_path)
    before, after = "# rule v1", "# rule v2"
    report = ReportFormatter(
        tmp_path,
        skills=("managed_doc:agent_rule",),
        managed_doc_kind="agent_rule",
        managed_doc_content_before=before,
        managed_doc_content_after=after,
        managed_doc_task_ids=("task-1", "task-2"),
    ).format()
    assert report.managed_doc_kind == "agent_rule"
    assert report.managed_doc_content_before == before
    assert report.managed_doc_content_after == after
    assert report.managed_doc_task_ids == ("task-1", "task-2")


def test_formatter_writes_before_final_diff_tasks_artifacts(tmp_path: Path) -> None:
    """成功路径四 artifact 落盘：before/tasks 由 runner，final/diff 由 formatter。"""
    _write_summary(tmp_path)
    before = "# agent rule\nkeep this line\nold line"
    after = "# agent rule\nkeep this line\nnew line"
    # runner 已写 before.md + tasks.json（formatter 只读 before 不写）
    (tmp_path / "managed_doc_before.md").write_text(before, encoding="utf-8")
    (tmp_path / "managed_doc_tasks.json").write_text(
        json.dumps({"task_ids": ["t1"]}), encoding="utf-8"
    )
    ReportFormatter(
        tmp_path,
        skills=("managed_doc:agent_rule",),
        managed_doc_kind="agent_rule",
        managed_doc_content_before=before,
        managed_doc_content_after=after,
        managed_doc_task_ids=("t1",),
    ).format()
    # final.md = operator committed content
    assert (tmp_path / "managed_doc_final.md").read_text(encoding="utf-8") == after
    # diff.patch 是 before→final unified diff
    diff = (tmp_path / "managed_doc_diff.patch").read_text(encoding="utf-8")
    assert "managed_doc_before" in diff
    assert "managed_doc_final" in diff
    assert "-old line" in diff
    assert "+new line" in diff
    # before + tasks（runner 写的）仍保留
    assert (tmp_path / "managed_doc_before.md").read_text(encoding="utf-8") == before
    assert (tmp_path / "managed_doc_tasks.json").exists()


def test_formatter_logs_hash_and_length_not_full_content(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    """日志只记 hash + 长度 + task_id，不泄露全文 managed-doc（ADR §10）。"""
    import logging

    _write_summary(tmp_path)
    secret_before = "SECRET_BASELINE_RULE_CONTENT"
    secret_after = "SECRET_FINAL_RULE_CONTENT"
    with caplog.at_level(logging.INFO, logger="evo_agent.reporter.formatter"):
        ReportFormatter(
            tmp_path,
            skills=("managed_doc:agent_rule",),
            managed_doc_kind="agent_rule",
            managed_doc_content_before=secret_before,
            managed_doc_content_after=secret_after,
            managed_doc_task_ids=("task-secret",),
        ).format()
    md_records = [r for r in caplog.records if "managed-doc artifacts written" in r.getMessage()]
    assert md_records, "expected managed-doc artifact log record"
    rec = md_records[0]
    # 只记 hash + 长度 + task_id，不记全文
    assert hasattr(rec, "before_hash") and len(rec.before_hash) == 64  # sha256 hex
    assert hasattr(rec, "after_hash") and len(rec.after_hash) == 64
    assert rec.before_length == len(secret_before)
    assert rec.after_length == len(secret_after)
    assert list(rec.task_ids) == ["task-secret"]
    # 全文不得出现在任何日志记录里
    for record in caplog.records:
        assert secret_before not in record.getMessage()
        assert secret_after not in record.getMessage()
