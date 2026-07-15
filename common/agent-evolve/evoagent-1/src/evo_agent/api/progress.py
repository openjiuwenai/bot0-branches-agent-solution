"""ProgressCallback — 采集 Trainer 进度到 Job 状态 + SSE 事件缓冲。

继承 agent-core ``Callbacks``，对齐签名以确保 ``ComposedCallbacks`` 正确调用。
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from openjiuwen.agent_evolving.trainer.progress import Callbacks, Progress

from evo_agent.api.jobs import Job, JobStatus

if TYPE_CHECKING:
    from openjiuwen.agent_evolving.dataset import EvaluatedCase
    from openjiuwen.core.single_agent import BaseAgent

logger = logging.getLogger(__name__)


class ProgressCallback(Callbacks):  # type: ignore[misc]
    """将 Trainer 生命周期事件写入 Job 进度 + SSE 事件缓冲。

    签名严格对齐 agent-core ``Callbacks`` 基类，
    通过 ``ComposedCallbacks`` 与 ``SkillDocumentCallbacks`` 组合使用。
    """

    def __init__(
        self,
        job: Job,
        *,
        num_train_cases: int = 0,
        num_val_cases: int = 0,
    ) -> None:
        self._job = job
        self._num_train_cases = num_train_cases
        self._num_val_cases = num_val_cases
        self.val_per_epoch_scores: list[float] = []
        self.val_score_before: float = 0.0
        # 每轮候选 fresh eval 分数（报告趋势图数据源）。与 val_per_epoch_scores
        # （门控赢家，单调非降，用于 improved 判定）不同：候选分会上下波动，
        # 反映每轮编辑在验证集上的真实评估。gate 路径取 candidate_score，
        # 非 2-candidate 路径回退到该轮 val_score（即 fresh eval）。
        self.candidate_per_epoch_scores: list[float] = []
        # 每轮赢家 per-case 分数（eval_info 的 [ec.score, ...]），与
        # candidate_per_epoch_scores / val_per_epoch_scores 同序同长。
        # 供 formatter 按 argmax(候选分) 取 best-epoch 的赢家 per-case 算
        # pass_rate_after——in-memory 传参，避开 _artifact_epoch(0-based) vs
        # current_epoch(1-based) off-by-one（formatter rglob 按 epoch 号映射会滤错轮）。
        self.val_per_epoch_case_scores: list[list[float]] = []
        # epoch 开始时的累计 edits_applied 基线，用于算本 epoch 增量
        self._edits_at_epoch_start: int = 0
        # 由 EvoTrainer.on_gate_scored 在 epoch_end 前注入：本 epoch 的
        # {base_score, candidate_score, tie_revalued, ...}。None = 非 2-candidate
        # 路径，回退到从 eval_info 计算 val_score。
        self._last_gate_scores: dict[str, Any] | None = None

    def on_gate_scored(self, payload: dict[str, Any]) -> None:
        """Stash gate scores pushed by EvoTrainer for the next epoch_end event.

        Non-vendor hook (forwarded by ``ComposedCallbacks``). Carries the
        candidate's fresh score so SSE can surface it alongside the winner.
        """
        self._last_gate_scores = dict(payload)

    def on_train_begin(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        self._job.status = JobStatus.RUNNING
        self._job.update_progress(total_epochs=progress.max_epoch)

        # Enhanced train_begin progress event
        baseline_score = 0.0
        logger.debug(
            "on_train_begin: eval_info_len=%d val_score_before=%s",
            len(eval_info) if eval_info else 0,
            self.val_score_before,
        )
        if eval_info:
            scores = [ec.score for ec in eval_info if hasattr(ec, "score")]
            logger.debug("on_train_begin: scores=%s", scores)
            baseline_score = sum(scores) / len(scores) if scores else 0.0
            # 记录验证集基线分数（仅当 eval_info 非空时才更新）
            self.val_score_before = baseline_score
        else:
            # eval_info 为空时，使用已有的 val_score_before（由 optimizer_runner 手动评估设置）
            baseline_score = self.val_score_before

        self._job.push_event(
            "progress",
            {
                "phase": "train_begin",
                "total_epochs": progress.max_epoch,
                "num_train_cases": self._num_train_cases,
                "num_val_cases": self._num_val_cases,
                "baseline_score": round(baseline_score, 3),
            },
        )
        # Companion log event
        self._job.push_event(
            "log",
            {
                "level": "info",
                "message": (
                    f"训练开始：共 {progress.max_epoch} 轮，"
                    f"{self._num_train_cases} 个训练 case，"
                    f"{self._num_val_cases} 个验证 case，"
                    f"基线分数 {baseline_score:.3f}"
                ),
                "phase": "train_begin",
                "data": {
                    "total_epochs": progress.max_epoch,
                    "num_train_cases": self._num_train_cases,
                    "num_val_cases": self._num_val_cases,
                    "baseline_score": round(baseline_score, 3),
                },
            },
        )

    def on_train_epoch_begin(
        self,
        agent: BaseAgent,
        progress: Progress,
    ) -> None:
        """推送 epoch_begin 事件（vendor 签名：2 参，无 eval_info）。"""
        # 记录本 epoch 开始时的累计 edits 基线，供 epoch_end 算增量
        self._edits_at_epoch_start = self._job.progress.edits_applied
        self._job.push_event(
            "progress",
            {
                "phase": "epoch_begin",
                "epoch": progress.current_epoch,
                "total_epochs": progress.max_epoch,
            },
        )
        # Companion log event
        self._job.push_event(
            "log",
            {
                "level": "info",
                "message": f"第 {progress.current_epoch}/{progress.max_epoch} 轮开始",
                "phase": "epoch_begin",
                "data": {
                    "epoch": progress.current_epoch,
                    "total_epochs": progress.max_epoch,
                },
            },
        )

    def on_train_epoch_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        # 优先用 EvoTrainer 注入的 gate 分数（2-candidate 路径）：
        # base=缓存基线，candidate=本 epoch 候选 fresh eval（平局时为均值）。
        # reported_val_score 上报候选分（波动，与趋势图 per_epoch_scores 同语义），
        # 让 live 卡片看到每轮真实波动而非冻在赢家。winner 仍为门控赢家
        # （= vendor best_score），仅供 improved 判定，保留单调语义。
        # gate_decision 与 gate_result.json 同格式用 "base"/"candidate"，避免
        # live（accepted/rejected）与完成后（base/candidate）格式跳变。
        gate = self._last_gate_scores
        self._last_gate_scores = None  # 消费，避免下个 epoch 复用
        base_score: float | None = None
        candidate_score: float | None = None
        tie_revalued = False
        gate_decision: str | None = None
        if gate is not None:
            bs = gate.get("base_score")
            cs = gate.get("candidate_score")
            if isinstance(bs, float) and isinstance(cs, float):
                base_score = bs
                candidate_score = cs
                tie_revalued = bool(gate.get("tie_revalued", False))
                # 赢家 = 严格更高者；平局（均值==base）归 base。
                winner = candidate_score if candidate_score > base_score else base_score
                # 上报候选分（波动），与 winner（赢家，单调）解耦。
                reported_val_score = candidate_score
                # 与 gate_result.json 的 _infer_gate_decision 同格式。
                gate_decision = "candidate" if candidate_score > base_score else "base"
            else:
                reported_val_score = (
                    sum(ec.score for ec in eval_info) / len(eval_info) if eval_info else 0.0
                )
                winner = reported_val_score
        else:
            # 非 2-candidate 路径：从 val_evaluated 计算真实验证分数。
            # 此时候选 = 赢家 = 该轮 fresh eval，无 gate_result.json 对应。
            reported_val_score = (
                sum(ec.score for ec in eval_info) / len(eval_info) if eval_info else 0.0
            )
            winner = reported_val_score
        self.val_per_epoch_scores.append(winner)
        # 趋势图数据源：候选 fresh eval（gate 路径）或该轮 fresh eval（非 gate 路径）。
        # 与 winner（门控赢家，单调）不同：候选分会上下波动。
        self.candidate_per_epoch_scores.append(reported_val_score)
        # 留存该轮赢家 per-case（eval_info），供 formatter 算 best-epoch pass_rate_after。
        # 与 candidate_per_epoch_scores 同序同长（每轮各 append 一次）。
        self.val_per_epoch_case_scores.append([ec.score for ec in eval_info])

        # 训练分数（vendor Trainer 在每个 step 后更新 current_epoch_score）
        train_score = progress.current_epoch_score

        # 门控决策：gate 路径已取真实 base/candidate；非 gate 路径回退 improved 判定。
        # improved 基于赢家序列（val_per_epoch_scores），反映"是否创新高"。
        if gate_decision is None:
            improved = winner > (
                max(self.val_per_epoch_scores[:-1])
                if len(self.val_per_epoch_scores) > 1
                else self.val_score_before
            )
            gate_decision = "accepted" if improved else "rejected"

        # edits_applied 累计值（由 phase_callback 在 apply 阶段更新到 job.progress）
        edits_applied = self._job.progress.edits_applied
        # 本 epoch 增量：当前累计 − epoch 开始时的基线
        epoch_edits = edits_applied - self._edits_at_epoch_start

        # gate 拒绝轮的编辑未生效：Trainer._select_best_candidate_on_val 在 base 赢时
        # 已把 operator 状态回滚到 epoch 开始的基线，但 _phase_callback 在 apply 阶段
        # （gate 前）已把编辑累计进 edits_applied。这里在已知 gate 决策后回滚本 epoch
        # 增量，使 edits_applied 只反映 gate 接受（candidate/accepted）的编辑，与门控
        # 保留的 operator 状态一致——否则 live/完成卡片会把"试过但被拒"的编辑算进去。
        if gate_decision in ("base", "rejected"):
            edits_applied = self._edits_at_epoch_start
            self._job.update_progress(edits_applied=edits_applied)
            epoch_edits = 0  # 拒绝轮无生效编辑

        self._job.update_progress(
            current_epoch=progress.current_epoch,
            val_score=reported_val_score,
            best_score=progress.best_score,
        )
        self._job.push_event(
            "progress",
            {
                "phase": "epoch_end",
                "epoch": progress.current_epoch,
                "total_epochs": progress.max_epoch,
                "val_score": round(reported_val_score, 3),
                "best_score": progress.best_score,
                "train_score": round(train_score, 3),
                "gate_decision": gate_decision,
                "edits_applied": edits_applied,
                "epoch_edits": epoch_edits,
                "base_score": round(base_score, 3) if base_score is not None else None,
                "candidate_score": (
                    round(candidate_score, 3) if candidate_score is not None else None
                ),
                "tie_revalued": tie_revalued,
            },
        )

        # Companion epoch_end log
        cand_part = f"，候选 {candidate_score:.3f}" if candidate_score is not None else ""
        self._job.push_event(
            "log",
            {
                "level": "info",
                "message": (
                    f"第 {progress.current_epoch}/{progress.max_epoch} 轮完成："
                    f"验证分数 {reported_val_score:.3f}{cand_part}，"
                    f"最佳 {progress.best_score:.3f}"
                ),
                "phase": "epoch_end",
                "data": {
                    "epoch": progress.current_epoch,
                    "val_score": round(reported_val_score, 3),
                    "best_score": round(progress.best_score, 3),
                    "candidate_score": (
                        round(candidate_score, 3) if candidate_score is not None else None
                    ),
                },
            },
        )

        # Validation phase log — tie_revalued 标记塞进 message 透传到前端 log 面板
        tie_part = "，平局重eval" if tie_revalued else ""
        self._job.push_event(
            "log",
            {
                "level": "info",
                "message": (
                    f"门控验证：{gate_decision}（分数 {reported_val_score:.3f}"
                    + (f"，候选 {candidate_score:.3f}" if candidate_score is not None else "")
                    + f"{tie_part}）"
                ),
                "phase": "validation",
                "data": {
                    "val_score": round(reported_val_score, 3),
                    "best_score": round(progress.best_score, 3),
                    "gate_decision": gate_decision,
                    "candidate_score": (
                        round(candidate_score, 3) if candidate_score is not None else None
                    ),
                    "tie_revalued": tie_revalued,
                },
            },
        )

    def on_train_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        # Enhanced train_end progress event
        self._job.push_event(
            "progress",
            {
                "phase": "train_end",
                "total_epochs": progress.max_epoch,
                "best_score": progress.best_score,
            },
        )
        # Companion log event
        self._job.push_event(
            "log",
            {
                "level": "info",
                "message": f"训练结束：最佳验证分数 {progress.best_score:.3f}",
                "phase": "train_end",
                "data": {
                    "total_epochs": progress.max_epoch,
                    "best_score": round(progress.best_score, 3),
                },
            },
        )
        # 不在此处推送 completed 事件也不设 COMPLETED 状态：
        # 由 _run_with_progress 在 format() 成功后统一设置，避免 format() 失败时
        # 客户端已看到 completed 却随后状态变 FAILED（状态/事件不一致）。
