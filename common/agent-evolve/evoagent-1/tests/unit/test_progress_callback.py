"""ProgressCallback 单元测试 — val_per_epoch_scores 收集 + epoch_begin 事件。"""

from __future__ import annotations

import inspect
from unittest.mock import MagicMock

from openjiuwen.agent_evolving.trainer.progress import Progress

from evo_agent.api.jobs import Job
from evo_agent.api.progress import ProgressCallback

# ── val_per_epoch_scores 收集 ──


def test_collects_val_per_epoch_scores() -> None:
    """on_train_epoch_end 从 eval_info 计算验证分数追加到 val_per_epoch_scores。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)

    for score in (0.6, 0.7, 0.8):
        progress = Progress(
            current_epoch=len(cb.val_per_epoch_scores) + 1,
            max_epoch=3,
            current_epoch_score=score,
            best_score=score,
        )
        # 构造 mock eval_info：1 个 case，分数为 score
        mock_case = MagicMock()
        mock_case.score = score
        cb.on_train_epoch_end(agent=MagicMock(), progress=progress, eval_info=[mock_case])

    assert cb.val_per_epoch_scores == [0.6, 0.7, 0.8]


def test_val_per_epoch_scores_starts_empty() -> None:
    """val_per_epoch_scores 初始为空列表。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    assert cb.val_per_epoch_scores == []


# ── val per-case 留存（A4：供 formatter 算 pass_rate_after）──


def test_val_per_epoch_case_scores_starts_empty() -> None:
    """val_per_epoch_case_scores 初始为空列表。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    assert cb.val_per_epoch_case_scores == []


def test_val_per_epoch_case_scores_collects_per_case() -> None:
    """on_train_epoch_end 每轮留存该轮赢家 per-case 分数。

    与 candidate_per_epoch_scores / val_per_epoch_scores 同序同长，
    供 formatter 按 argmax(val_per_epoch_scores) 取 best-epoch 的 per-case
    算 pass_rate_after（避免落 artifact 撞 _artifact_epoch off-by-one）。
    """
    job = Job(job_id="t")
    cb = ProgressCallback(job)

    # epoch 1：2 个 case，赢家 per-case = [0.6, 0.4]
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(current_epoch=1, max_epoch=2, best_score=0.5),
        eval_info=[MagicMock(score=0.6), MagicMock(score=0.4)],
    )
    # epoch 2：2 个 case，赢家 per-case = [0.8, 0.7]
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(current_epoch=2, max_epoch=2, best_score=0.75),
        eval_info=[MagicMock(score=0.8), MagicMock(score=0.7)],
    )

    assert cb.val_per_epoch_case_scores == [[0.6, 0.4], [0.8, 0.7]]
    assert len(cb.val_per_epoch_case_scores) == len(cb.candidate_per_epoch_scores)
    assert len(cb.val_per_epoch_case_scores) == len(cb.val_per_epoch_scores)
    # 单条 per-case 列表长度 == 该轮 eval_info 长度
    assert len(cb.val_per_epoch_case_scores[0]) == 2


def test_val_per_epoch_case_scores_empty_eval_info() -> None:
    """eval_info 为空时追加空 per-case 列表（保持与 epoch 序列对齐）。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(current_epoch=1, max_epoch=1, best_score=0.0),
        eval_info=[],
    )
    assert cb.val_per_epoch_case_scores == [[]]


# ── on_train_epoch_begin ──


def test_on_train_epoch_begin_pushes_epoch_begin_event() -> None:
    """on_train_epoch_begin 推送 epoch_begin progress 事件。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    progress = Progress(current_epoch=1, max_epoch=3)

    cb.on_train_epoch_begin(agent=MagicMock(), progress=progress)

    events = [e for e in job.event_buffer if e.event == "progress"]
    assert any(e.data.get("phase") == "epoch_begin" for e in events)
    epoch_begin = [e for e in events if e.data.get("phase") == "epoch_begin"]
    assert epoch_begin[0].data["epoch"] == 1
    assert epoch_begin[0].data["total_epochs"] == 3


def test_epoch_edits_is_per_epoch_increment() -> None:
    """Bug 4: epoch_end 的 epoch_edits 是本 epoch 增量，edits_applied 是累计。

    - epoch 1：apply 5 个 → edits_applied=5, epoch_edits=5
    - epoch 2：apply 3 个 → edits_applied=8（累计）, epoch_edits=3（本 epoch）
    """
    job = Job(job_id="t")
    cb = ProgressCallback(job)

    def _last_epoch_end() -> dict:
        events = [
            e
            for e in job.event_buffer
            if e.event == "progress" and e.data.get("phase") == "epoch_end"
        ]
        assert events, "no epoch_end event"
        return events[-1].data

    # epoch 1
    cb.on_train_epoch_begin(MagicMock(), Progress(current_epoch=1, max_epoch=2))
    job.update_progress(edits_applied=5)  # phase_callback 累计的本 epoch 编辑
    mock_case = MagicMock()
    mock_case.score = 0.5
    cb.on_train_epoch_end(
        MagicMock(),
        Progress(current_epoch=1, max_epoch=2, best_score=0.5),
        [mock_case],
    )
    pe1 = _last_epoch_end()
    assert pe1["edits_applied"] == 5
    assert pe1["epoch_edits"] == 5

    # epoch 2：基线应为 5，本 epoch 再加 3 → 累计 8
    cb.on_train_epoch_begin(MagicMock(), Progress(current_epoch=2, max_epoch=2))
    job.update_progress(edits_applied=8)
    mock_case2 = MagicMock()
    mock_case2.score = 0.6
    cb.on_train_epoch_end(
        MagicMock(),
        Progress(current_epoch=2, max_epoch=2, best_score=0.6),
        [mock_case2],
    )
    pe2 = _last_epoch_end()
    assert pe2["edits_applied"] == 8  # 累计
    assert pe2["epoch_edits"] == 3  # 本 epoch 增量


def test_rejected_epoch_edits_rolled_back_from_edits_applied() -> None:
    """Bug: edits_applied 应只计 gate 接受的编辑，拒绝轮的编辑不计入累计。

    _phase_callback 在 apply 阶段（gate 前）累计编辑，gate 拒绝时 operator 状态
    回滚到 base，但计数器若不回滚会把未生效的编辑算进去。on_train_epoch_end
    在 gate_decision 为拒绝（"base"/"rejected"）时须把本 epoch 增量从累计扣回，
    使 edits_applied 与门控保留的 operator 状态一致。

    - epoch 1: apply 5 个，gate candidate 0.80 > base 0.70（接受）→ 累计 5
    - epoch 2: apply 3 个（累计 8），gate base 0.81 > candidate 0.80（拒绝）→ 回滚到 5
    """
    job = Job(job_id="t")
    cb = ProgressCallback(job)

    def _last_epoch_end() -> dict:
        events = [
            e
            for e in job.event_buffer
            if e.event == "progress" and e.data.get("phase") == "epoch_end"
        ]
        assert events, "no epoch_end event"
        return events[-1].data

    # epoch 1: candidate 0.80 > base 0.70 → 接受
    cb.on_train_epoch_begin(MagicMock(), Progress(current_epoch=1, max_epoch=2))
    job.update_progress(edits_applied=5)  # _phase_callback 累计的 5 个编辑
    cb.on_gate_scored({"base_score": 0.70, "candidate_score": 0.80, "tie_revalued": False})
    cb.on_train_epoch_end(
        MagicMock(),
        Progress(current_epoch=1, max_epoch=2, best_score=0.80),
        [MagicMock(score=0.80)],
    )
    pe1 = _last_epoch_end()
    assert pe1["gate_decision"] == "candidate"  # 接受
    assert pe1["edits_applied"] == 5  # 接受轮计入累计
    assert pe1["epoch_edits"] == 5

    # epoch 2: candidate 0.80 < base 0.81 → 拒绝（base 赢）
    cb.on_train_epoch_begin(MagicMock(), Progress(current_epoch=2, max_epoch=2))
    job.update_progress(edits_applied=8)  # _phase_callback 又累计 3 个（共 8）
    cb.on_gate_scored({"base_score": 0.81, "candidate_score": 0.80, "tie_revalued": False})
    cb.on_train_epoch_end(
        MagicMock(),
        Progress(current_epoch=2, max_epoch=2, best_score=0.81),
        [MagicMock(score=0.81)],
    )
    pe2 = _last_epoch_end()
    assert pe2["gate_decision"] == "base"  # 拒绝
    # 拒绝轮的 3 个编辑未生效 → 累计回滚到 5，本 epoch 贡献 0
    assert pe2["edits_applied"] == 5, (
        f"rejected epoch edits leaked into cumulative: {pe2['edits_applied']}"
    )
    assert pe2["epoch_edits"] == 0  # 拒绝轮无生效编辑
    assert job.progress.edits_applied == 5  # job 状态也已回滚


def test_non_gate_rejected_epoch_edits_rolled_back() -> None:
    """非 gate 路径：gate_decision="rejected"（未创新高）时同样回滚本 epoch 编辑。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    cb.val_score_before = 0.80  # 基线高，候选无法超越 → improved=False → rejected

    def _last_epoch_end() -> dict:
        events = [
            e
            for e in job.event_buffer
            if e.event == "progress" and e.data.get("phase") == "epoch_end"
        ]
        assert events, "no epoch_end event"
        return events[-1].data

    cb.on_train_epoch_begin(MagicMock(), Progress(current_epoch=1, max_epoch=1))
    job.update_progress(edits_applied=4)  # _phase_callback 累计 4 个
    # eval_info 均分 0.70 < val_score_before 0.80 → improved=False → rejected
    cb.on_train_epoch_end(
        MagicMock(),
        Progress(current_epoch=1, max_epoch=1, best_score=0.80),
        [MagicMock(score=0.70)],
    )
    pe = _last_epoch_end()
    assert pe["gate_decision"] == "rejected"
    assert pe["edits_applied"] == 0, f"non-gate rejected edits leaked: {pe['edits_applied']}"
    assert pe["epoch_edits"] == 0
    assert job.progress.edits_applied == 0


def test_on_train_epoch_begin_signature_no_eval_info() -> None:
    """on_train_epoch_begin 签名不含 eval_info（兼容 vendor 2 参调用）。"""
    sig = inspect.signature(ProgressCallback.on_train_epoch_begin)
    assert "eval_info" not in sig.parameters


# ── W10.9: enhanced progress/log events ──


def test_on_train_begin_pushes_train_begin_with_baseline() -> None:
    """on_train_begin 推送增强的 train_begin progress + log 事件。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    job = Job(job_id="t")
    cb = ProgressCallback(job, num_train_cases=40, num_val_cases=10)

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_info = [
        EvaluatedCase(case=case, answer={}, score=0.6),
        EvaluatedCase(case=case, answer={}, score=0.8),
    ]
    cb.on_train_begin(
        agent=MagicMock(),
        progress=Progress(max_epoch=3),
        eval_info=eval_info,
    )

    # Progress event
    progress_events = [e for e in job.event_buffer if e.event == "progress"]
    tb = [e for e in progress_events if e.data.get("phase") == "train_begin"]
    assert tb
    assert tb[0].data["total_epochs"] == 3
    assert tb[0].data["num_train_cases"] == 40

    # Log event
    log_events = [e for e in job.event_buffer if e.event == "log"]
    tb_log = [e for e in log_events if e.data.get("phase") == "train_begin"]
    assert tb_log
    assert "训练开始" in tb_log[0].data["message"]


def test_on_train_epoch_end_enhanced_progress_and_log() -> None:
    """on_train_epoch_end 推送增强的 epoch_end progress + log。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    mock_case = MagicMock()
    mock_case.score = 0.7
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(
            current_epoch=1,
            max_epoch=3,
            current_epoch_score=0.7,
            best_score=0.7,
        ),
        eval_info=[mock_case],
    )

    # Enhanced epoch_end progress has total_epochs
    pe = [
        e for e in job.event_buffer if e.event == "progress" and e.data.get("phase") == "epoch_end"
    ]
    assert pe
    assert pe[-1].data["val_score"] == 0.7
    assert pe[-1].data["total_epochs"] == 3

    # Log event
    log_events = [e for e in job.event_buffer if e.event == "log"]
    epoch_log = [e for e in log_events if e.data.get("phase") == "epoch_end"]
    assert epoch_log


def test_on_train_epoch_end_pushes_validation_log() -> None:
    """on_train_epoch_end 推送 validation phase log 事件。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(
            current_epoch=1,
            max_epoch=3,
            current_epoch_score=0.7,
            best_score=0.7,
        ),
        eval_info=[],
    )

    log_events = [e for e in job.event_buffer if e.event == "log"]
    val_log = [e for e in log_events if e.data.get("phase") == "validation"]
    assert val_log
    assert val_log[0].data["data"]["gate_decision"] in ("accepted", "rejected")


def test_on_gate_scored_surfaces_candidate_score_in_epoch_end() -> None:
    """on_gate_scored 注入的 base/candidate 分数出现在 epoch_end 事件中。

    模拟"候选输给缓存 base"场景：base=0.81（缓存），candidate=0.80（fresh）。
    val_score 上报候选分=0.80（波动，与趋势图 per_epoch_scores 同语义，非赢家），
    gate_decision="base"（与 gate_result.json 同格式），事件同时带
    base_score/candidate_score/tie_revalued 让用户看到完整门控信息。
    """
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    cb.val_score_before = 0.81  # 缓存 base = baseline
    cb.on_gate_scored({"base_score": 0.81, "candidate_score": 0.80, "tie_revalued": False})

    mock_case = MagicMock()
    mock_case.score = 0.81
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(current_epoch=3, max_epoch=5, best_score=0.81),
        eval_info=[mock_case],
    )

    pe = [
        e for e in job.event_buffer if e.event == "progress" and e.data.get("phase") == "epoch_end"
    ]
    assert pe
    # val_score 上报候选分（波动），非赢家 base=0.81
    assert pe[-1].data["val_score"] == 0.80
    assert pe[-1].data["base_score"] == 0.81
    assert pe[-1].data["candidate_score"] == 0.80  # 真实候选分，不再被藏
    assert pe[-1].data["tie_revalued"] is False
    # gate_decision 与 gate_result.json 同格式：候选 0.80 < base 0.81 → base 赢
    assert pe[-1].data["gate_decision"] == "base"

    # stash 被消费：再次 epoch_end（无 gate）应回退到 mean(eval_info)
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(current_epoch=4, max_epoch=5, best_score=0.81),
        eval_info=[mock_case],
    )
    pe2 = [
        e for e in job.event_buffer if e.event == "progress" and e.data.get("phase") == "epoch_end"
    ]
    assert pe2[-1].data["candidate_score"] is None  # 无 gate → None


def test_on_gate_scored_tie_revalued_candidate_wins() -> None:
    """平局重 eval 候选均值赢 → val_score/gate_decision=candidate，log 含平局重eval。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    # base=0.6, candidate 均值=0.65（denoised）→ 候选赢
    cb.on_gate_scored(
        {
            "base_score": 0.6,
            "candidate_score": 0.65,
            "candidate_score_first": 0.6,
            "candidate_score_reval": 0.7,
            "tie_revalued": True,
        }
    )

    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(current_epoch=1, max_epoch=3, best_score=0.0),
        eval_info=[],
    )

    pe = [
        e for e in job.event_buffer if e.event == "progress" and e.data.get("phase") == "epoch_end"
    ]
    assert pe[-1].data["val_score"] == 0.65  # 上报候选分（= 候选赢家）
    assert pe[-1].data["candidate_score"] == 0.65
    assert pe[-1].data["tie_revalued"] is True
    # gate 路径 + 候选 0.65 > base 0.6 → "candidate"（与 gate_result.json 同格式）
    assert pe[-1].data["gate_decision"] == "candidate"

    # P5: tie_revalued 标记塞进 validation log message，透传到前端 log 面板
    val_logs = [
        e for e in job.event_buffer if e.event == "log" and e.data.get("phase") == "validation"
    ]
    assert val_logs
    assert "平局重eval" in val_logs[-1].data["message"]


def test_candidate_per_epoch_scores_tracks_candidate_not_winner() -> None:
    """candidate_per_epoch_scores 收集候选分（趋势图数据源），不是赢家分。

    - epoch 1: gate 注入 base=0.81, candidate=0.80 → 候选输，赢家=base=0.81，
      但 candidate_per_epoch_scores 记 0.80（真实候选评估）。
    - epoch 2: 无 gate（非 2-candidate 路径），eval_info 均分 0.70 → 回退到 val_score=0.70。
    """
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    cb.val_score_before = 0.81

    # epoch 1: 候选 0.80 输给缓存 base 0.81
    cb.on_gate_scored({"base_score": 0.81, "candidate_score": 0.80, "tie_revalued": False})
    mock_case_08 = MagicMock()
    mock_case_08.score = 0.81  # eval_info 不影响 reported（gate 路径 reported=candidate=0.80）
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(current_epoch=1, max_epoch=3, best_score=0.81),
        eval_info=[mock_case_08],
    )

    # epoch 2: 无 gate → 回退 mean(eval_info)=0.70
    cb.on_train_epoch_end(
        agent=MagicMock(),
        progress=Progress(current_epoch=2, max_epoch=3, best_score=0.81),
        eval_info=[MagicMock(score=0.70)],
    )

    # 趋势图数据源 = 候选分（波动），不是赢家直线
    assert cb.candidate_per_epoch_scores == [0.80, 0.70]
    # epoch 1 门控路径：候选 0.80 输给缓存 base 0.81 → 赢家记 0.81，候选记 0.80（关键差异）
    assert cb.val_per_epoch_scores[0] == 0.81
    assert cb.candidate_per_epoch_scores[0] == 0.80
    # epoch 2 非 gate 路径：val_score 即 fresh eval=0.70，两者一致
    assert cb.val_per_epoch_scores[1] == 0.70
    assert cb.candidate_per_epoch_scores[1] == 0.70


def test_on_train_end_pushes_train_end_progress_and_log() -> None:
    """on_train_end 推送 train_end progress + log 事件。"""
    job = Job(job_id="t")
    cb = ProgressCallback(job)
    cb.on_train_end(
        agent=MagicMock(),
        progress=Progress(max_epoch=3, best_score=0.85),
        eval_info=[],
    )

    # Progress event
    progress_events = [e for e in job.event_buffer if e.event == "progress"]
    te = [e for e in progress_events if e.data.get("phase") == "train_end"]
    assert te
    assert te[0].data["best_score"] == 0.85

    # Log event
    log_events = [e for e in job.event_buffer if e.event == "log"]
    te_log = [e for e in log_events if e.data.get("phase") == "train_end"]
    assert te_log
    assert "训练结束" in te_log[0].data["message"]
