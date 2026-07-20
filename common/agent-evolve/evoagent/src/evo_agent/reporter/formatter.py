"""ReportFormatter — 读取 artifact 目录，生成人类可读的优化报告。"""

from __future__ import annotations

import difflib
import hashlib
import json
import logging
from pathlib import Path
from typing import Any

from evo_agent.types import (
    ManagedDocEpochContent,
    OptimizeReport,
    SkillContentSnapshot,
    SkillScore,
    TrainResult,
    ValResult,
)

logger = logging.getLogger(__name__)


def _training_eval_files(root: Path) -> list[Path]:
    """Return only epoch/step training results, excluding validation artifacts."""
    files = []
    for path in root.rglob("eval_results.json"):
        if not path.parent.name.startswith("step_"):
            continue
        if not any(parent.name.startswith("epoch_") for parent in path.parents):
            continue
        files.append(path)
    return sorted(files)


class ReportFormatter:
    """读取 artifact 目录，生成人类可读的优化报告。

    支持两种 artifact 目录结构：
    - **单 skill**（旧格式）：``artifact_dir/summary.json``
    - **多 skill**（新格式）：``artifact_dir/<skill_name>/summary.json``
    """

    def __init__(
        self,
        artifact_dir: Path,
        *,
        skills: tuple[str, ...] = (),
        score_threshold: float = 0.5,
        val_per_epoch_scores: tuple[float, ...] = (),
        val_score_before: float = 0.0,
        num_val_cases: int = 0,
        val_baseline_case_scores: list[float] | None = None,
        val_per_epoch_case_scores: list[list[float]] | None = None,
        # managed-doc 上下文（spec F8）：runner 在成功路径把 baseline snapshot 内容
        # （before）、operator committed 内容（after）、applier records 与非空 task_ids
        # 传入。formatter 写 managed_doc_final.md / managed_doc_diff.patch 并回填 report
        # 字段；失败路径的 before/tasks.json 由 runner finally 负责（不在 formatter）。
        managed_doc_kind: str | None = None,
        managed_doc_content_before: str | None = None,
        managed_doc_content_after: str | None = None,
        managed_doc_epoch_contents: tuple[ManagedDocEpochContent, ...] = (),
        managed_doc_task_ids: tuple[str, ...] = (),
        managed_doc_records: tuple[Any, ...] = (),
    ) -> None:
        self._artifact_dir = artifact_dir
        self._skills = skills
        self._score_threshold = score_threshold
        self._val_per_epoch_scores = val_per_epoch_scores
        self._val_score_before = val_score_before
        self._num_val_cases = num_val_cases
        # 验证集 per-case（in-memory，不落 artifact，避开 _artifact_epoch
        # 0-based vs current_epoch 1-based off-by-one）。与 val_per_epoch_scores
        # （候选 fresh eval）同序同长，按 argmax(候选分) 取 best-epoch 通过率。
        self._val_baseline_case_scores = val_baseline_case_scores or []
        self._val_per_epoch_case_scores = val_per_epoch_case_scores or []
        self._managed_doc_kind = managed_doc_kind
        self._managed_doc_content_before = managed_doc_content_before
        self._managed_doc_content_after = managed_doc_content_after
        self._managed_doc_epoch_contents = managed_doc_epoch_contents
        self._managed_doc_task_ids = managed_doc_task_ids
        self._managed_doc_records = managed_doc_records

    def format(self) -> OptimizeReport:
        """汇总 artifact 目录中的数据，生成 OptimizeReport。

        优先读取 summary.json，回退到扫描 epoch 目录。
        缺失字段使用默认值，不抛出异常。
        """
        # 检测多 skill 格式
        skill_scores = self._collect_skill_scores()
        summary = self._read_summary()
        gate_results = self._collect_gate_results()

        # 收集 train / val 结果
        train = self._collect_train_result(skill_scores)
        val = self._collect_val_result()

        # managed-doc 成功路径 artifact（spec F8）：写 final.md + diff.patch。
        # before.md / tasks.json 由 runner（baseline + finally）负责；formatter 只读
        # 成功结果。日志只记 hash + 长度 + task_id，不记全文（ADR §10）。
        managed_doc_after = self._managed_doc_content_after
        if self._managed_doc_kind is not None:
            self._write_managed_doc_artifacts()

        md_kwargs: dict[str, Any] = {
            "managed_doc_kind": self._managed_doc_kind,
            "managed_doc_content_before": self._managed_doc_content_before,
            "managed_doc_content_after": managed_doc_after,
            "managed_doc_epoch_contents": self._managed_doc_epoch_contents,
            "managed_doc_task_ids": self._managed_doc_task_ids,
        }

        if skill_scores:
            # 多 skill 模式
            total_edits = sum(s.edits_applied for s in skill_scores)

            return OptimizeReport(
                skills=self._skills or tuple(summary.get("skills", [])),
                dataset=summary.get("dataset", ""),
                epochs_completed=summary.get("epochs_completed", len(gate_results)),
                edits_applied=total_edits,
                train=train,
                val=val,
                gate_results=tuple(gate_results),
                artifact_dir=self._artifact_dir,
                skill_scores=tuple(skill_scores),
                skill_contents=tuple(self._collect_skill_contents()),
                **md_kwargs,
            )

        # 单 skill 模式（向下兼容）
        return OptimizeReport(
            skills=self._skills or tuple(summary.get("skills", [])),
            dataset=summary.get("dataset", ""),
            epochs_completed=summary.get("epochs_completed", len(gate_results)),
            edits_applied=summary.get("edits_applied", 0),
            train=train,
            val=val,
            gate_results=tuple(gate_results),
            artifact_dir=self._artifact_dir,
            skill_contents=tuple(self._collect_skill_contents()),
            **md_kwargs,
        )

    def _write_managed_doc_artifacts(self) -> None:
        """成功路径写 managed_doc_final.md + managed_doc_diff.patch（spec F8）。

        参照 edp_agent ``_write_per_operator_snapshots`` 的 before/after/diff 模式。
        before 由 runner baseline 阶段写入（formatter 只读不写 before）。日志只记
        hash + 长度 + task_id，**不输出全文** managed-doc（ADR §10 / F8 AC）。
        """
        before = self._managed_doc_content_before or ""
        after = self._managed_doc_content_after or ""
        before_hash = hashlib.sha256(before.encode("utf-8")).hexdigest()
        after_hash = hashlib.sha256(after.encode("utf-8")).hexdigest()
        self._artifact_dir.mkdir(parents=True, exist_ok=True)
        (self._artifact_dir / "managed_doc_final.md").write_text(after, encoding="utf-8")
        diff = difflib.unified_diff(
            before.splitlines(keepends=True),
            after.splitlines(keepends=True),
            fromfile="managed_doc_before",
            tofile="managed_doc_final",
        )
        (self._artifact_dir / "managed_doc_diff.patch").write_text("".join(diff), encoding="utf-8")
        logger.info(
            "managed-doc artifacts written: kind=%s before_hash=%s before_length=%d "
            "after_hash=%s after_length=%d task_ids=%s",
            self._managed_doc_kind,
            before_hash,
            len(before),
            after_hash,
            len(after),
            list(self._managed_doc_task_ids),
            extra={
                "managed_doc_kind": self._managed_doc_kind,
                "before_hash": before_hash,
                "before_length": len(before),
                "after_hash": after_hash,
                "after_length": len(after),
                "task_ids": list(self._managed_doc_task_ids),
            },
        )

    # ── Train / Val 结果 ────────────────────────────────────────────

    @staticmethod
    def _compute_pass_rate(scores: list[float], threshold: float = 0.5) -> float:
        """计算通过率：score >= threshold 的 case 占比。"""
        if not scores:
            return 0.0
        passed = sum(1 for s in scores if s >= threshold)
        return passed / len(scores)

    def _collect_train_result(
        self,
        skill_scores: list[SkillScore],
    ) -> TrainResult:
        """从 eval_results.json 计算 train 分数 + pass_rate + num_cases。

        多 skill 时 train score 取 per-skill 均值。
        """
        if skill_scores:
            # 多 skill 模式：overall 取 per-skill 均值
            score_before = sum(s.score_before for s in skill_scores) / len(skill_scores)
            score_after = sum(s.score_after for s in skill_scores) / len(skill_scores)
            pass_rate_before = sum(s.pass_rate_before for s in skill_scores) / len(skill_scores)
            pass_rate_after = sum(s.pass_rate_after for s in skill_scores) / len(skill_scores)
            # num_cases: 从 eval_results.json 取（多 skill 共享同一份）
            num_cases = self._count_train_cases_from_eval_results()
            improvement = self._calc_improvement(score_before, score_after)
            return TrainResult(
                score_before=score_before,
                score_after=score_after,
                improvement=improvement,
                pass_rate_before=pass_rate_before,
                pass_rate_after=pass_rate_after,
                num_cases=num_cases,
            )

        # 单 skill 模式：从 summary.json 或 eval_results.json
        summary = self._read_summary()
        eval_data = self._read_first_and_last_eval_results()

        if eval_data:
            first_data, last_data = eval_data
            score_before = first_data.get("avg_score", summary.get("score_before", 0.0))
            # score_after: 取所有 epoch 中最高 avg_score
            score_after = self._read_max_avg_score() or last_data.get(
                "avg_score", summary.get("score_after", 0.0)
            )
            first_scores = [float(r.get("score", 0.0)) for r in first_data.get("results", [])]
            last_scores = [float(r.get("score", 0.0)) for r in last_data.get("results", [])]
            pass_rate_before = self._compute_pass_rate(first_scores, self._score_threshold)
            # pass_rate_after 取 avg_score 最高份的 per-case 通过率（与 score_after
            # 同源同份），而非末份——max 不在末份时末份会给出错误的通过率。
            best_data = self._read_best_eval_results()
            best_scores = (
                [float(r.get("score", 0.0)) for r in best_data.get("results", [])]
                if best_data
                else last_scores
            )
            pass_rate_after = self._compute_pass_rate(best_scores, self._score_threshold)
            num_cases = len(first_data.get("results", []))
        else:
            score_before = summary.get("score_before", 0.0)
            score_after = summary.get("score_after", 0.0)
            pass_rate_before = 0.0
            pass_rate_after = 0.0
            num_cases = 0

        improvement = self._calc_improvement(score_before, score_after)
        return TrainResult(
            score_before=score_before,
            score_after=score_after,
            improvement=improvement,
            pass_rate_before=pass_rate_before,
            pass_rate_after=pass_rate_after,
            num_cases=num_cases,
        )

    def _collect_val_result(self) -> ValResult:
        """组装 val 子对象。

        per_epoch_scores 为每轮候选 fresh eval 分数（趋势图数据源，会波动）。
        best_score = max(基线, 候选峰值) = committed 最佳（门控保留最佳，值与
        改动前一致）。final_score = best_score：最终 committed 即历史最佳。

        improvement / pass_rate_before / pass_rate_after 由 in-memory per-case
        计算（不落 artifact，避开 _artifact_epoch vs current_epoch off-by-one）：
        - improvement = _calc_improvement(score_before, best)
        - pass_rate_before = baseline per-case 通过率
        - pass_rate_after: 若 best > score_before（候选创新高），取 argmax(候选分)
          那轮赢家 per-case 通过率（argmax 落在 candidate 赢的 epoch，此时
          eval_info == candidate per-case）；否则回退 pass_rate_before。
        """
        scores = self._val_per_epoch_scores
        best = max(self._val_score_before, max(scores)) if scores else self._val_score_before
        improvement = self._calc_improvement(self._val_score_before, best)
        pass_rate_before = self._compute_pass_rate(
            self._val_baseline_case_scores, self._score_threshold
        )
        pass_rate_after = self._compute_val_pass_rate_after(best, pass_rate_before)
        return ValResult(
            score_before=self._val_score_before,
            final_score=best,
            best_score=best,
            per_epoch_scores=scores,
            num_cases=self._num_val_cases,
            improvement=improvement,
            pass_rate_before=pass_rate_before,
            pass_rate_after=pass_rate_after,
        )

    def _compute_val_pass_rate_after(self, best: float, pass_rate_before: float) -> float:
        """best-epoch（argmax 锚定）赢家 per-case 通过率；无改进回退 pass_rate_before。

        argmax 落在 candidate 创新高的 epoch（best > score_before 时 best = 候选
        峰值，argmax(候选分) 即该 epoch）；此时门控赢家 == candidate，eval_info
        （赢家 per-case）== candidate per-case，故用赢家 per-case 作
        pass_rate_after 源正确。退化（best == score_before）回退 pass_rate_before。
        """
        scores = self._val_per_epoch_scores
        case_scores = self._val_per_epoch_case_scores
        if best <= self._val_score_before or not scores or not case_scores:
            return pass_rate_before
        # argmax(候选分)：峰值所在的 epoch（候选创新高那轮）。
        best_epoch = max(range(len(scores)), key=lambda i: scores[i])
        if best_epoch >= len(case_scores):
            return pass_rate_before
        return self._compute_pass_rate(case_scores[best_epoch], self._score_threshold)

    def _read_first_and_last_eval_results(
        self,
    ) -> tuple[dict[str, Any], dict[str, Any]] | None:
        """读取第一个和最后一个 eval_results.json。

        Returns:
            (first_data, last_data) 或 None（无 eval_results.json）。
        """
        if not self._artifact_dir.exists():
            return None

        eval_files = _training_eval_files(self._artifact_dir)
        if not eval_files:
            return None

        first_path = eval_files[0]
        last_path = eval_files[-1]

        try:
            with first_path.open(encoding="utf-8") as f:
                first_data = json.load(f)
        except (json.JSONDecodeError, ValueError, KeyError):
            first_data = {}

        try:
            with last_path.open(encoding="utf-8") as f:
                last_data = json.load(f)
        except (json.JSONDecodeError, ValueError, KeyError):
            last_data = {}

        return first_data, last_data

    def _count_train_cases_from_eval_results(self) -> int:
        """从第一个 eval_results.json 获取 case 数量。"""
        if not self._artifact_dir.exists():
            return 0
        eval_files = _training_eval_files(self._artifact_dir)
        if not eval_files:
            return 0
        try:
            with eval_files[0].open(encoding="utf-8") as f:
                data = json.load(f)
            return len(data.get("results", []))
        except (json.JSONDecodeError, ValueError, KeyError):
            return 0

    def _read_max_avg_score(self) -> float | None:
        """扫描所有 eval_results.json，返回最高 avg_score。"""
        if not self._artifact_dir.exists():
            return None
        eval_files = _training_eval_files(self._artifact_dir)
        if not eval_files:
            return None
        scores: list[float] = []
        for ef in eval_files:
            try:
                with ef.open(encoding="utf-8") as f:
                    data = json.load(f)
                avg = data.get("avg_score")
                if avg is not None:
                    scores.append(float(avg))
            except (json.JSONDecodeError, ValueError, KeyError):
                continue
        return max(scores) if scores else None

    def _read_best_eval_results(self) -> dict[str, Any] | None:
        """返回 avg_score 最高的 eval_results.json dict（与 score_after 同源）。

        扫描逻辑与 _read_max_avg_score 一致（按 avg_score 取 max），但返回该份
        dict 以取其 per-case scores 算 pass_rate_after，使通过率与 score_after
        锚同一份。平局（多份同 avg）取首遇那份。无 avg 字段 → None（调用方回退末份）。
        算术无关 _artifact_epoch vs current_epoch off-by-one：按文件内容（avg_score）
        选份，不按 epoch 号映射。
        """
        if not self._artifact_dir.exists():
            return None
        eval_files = _training_eval_files(self._artifact_dir)
        if not eval_files:
            return None
        best_data: dict[str, Any] | None = None
        best_avg: float | None = None
        for ef in eval_files:
            try:
                with ef.open(encoding="utf-8") as f:
                    data = json.load(f)
            except (json.JSONDecodeError, ValueError, KeyError):
                continue
            avg = data.get("avg_score")
            if avg is None:
                continue
            avg_f = float(avg)
            if best_avg is None or avg_f > best_avg:
                best_avg = avg_f
                best_data = data
        return best_data

    # ── Skill 内容快照 ──────────────────────────────────────────────

    def _collect_skill_contents(self) -> list[SkillContentSnapshot]:
        """读取每个 skill 的 before/after 内容快照。

        目录结构：``<artifact_dir>/<skill_name>/epoch_N/skill_before.md``
        和 ``<artifact_dir>/<skill_name>/epoch_N/skill_after.md``。
        """
        if not self._skills:
            return []

        snapshots: list[SkillContentSnapshot] = []
        for skill_name in self._skills:
            skill_dir = self._artifact_dir / skill_name
            if not skill_dir.is_dir():
                continue

            # before: 第一个 epoch 的 skill_before.md
            before_path = skill_dir / "epoch_0" / "skill_before.md"
            content_before = before_path.read_text(encoding="utf-8") if before_path.exists() else ""

            # epoch contents: 每个 epoch 的 skill_after.md
            epoch_contents: list[str] = []
            for epoch_dir in sorted(skill_dir.glob("epoch_*")):
                after_path = epoch_dir / "skill_after.md"
                if after_path.exists():
                    epoch_contents.append(
                        after_path.read_text(encoding="utf-8"),
                    )

            snapshots.append(
                SkillContentSnapshot(
                    name=skill_name,
                    content_before=content_before,
                    epoch_contents=tuple(epoch_contents),
                ),
            )

        return snapshots

    # ── Skill 分数 ──────────────────────────────────────────────────

    def _collect_skill_scores(self) -> list[SkillScore]:
        """扫描 per-skill 子目录，收集 SkillScore 列表。

        返回空列表表示单 skill 模式（向下兼容）。

        优先级：
        1. summary.json（vendor Trainer 标准输出）
        2. eval_results.json + selected_edits_*.json（fallback，vendor 不写 summary 时使用）
        3. 零分默认值
        """
        if not self._skills:
            return []

        # 检测是否存在 skill 子目录
        has_skill_dirs = any((self._artifact_dir / name).is_dir() for name in self._skills)

        # 无 skill 子目录 + 有根 summary.json → 单 skill 兼容模式
        if not has_skill_dirs and (self._artifact_dir / "summary.json").exists():
            return []

        # 多 skill 模式（含 fallback 到 eval_results.json）
        scores: list[SkillScore] = []
        for skill_name in self._skills:
            skill_dir = self._artifact_dir / skill_name
            if skill_dir.is_dir() and (skill_dir / "summary.json").exists():
                # 优先使用 summary.json
                with (skill_dir / "summary.json").open(encoding="utf-8") as f:
                    data = json.load(f)
                before = data.get("score_before", 0.0)
                after = data.get("score_after", 0.0)
                # pass_rate 从 eval_results.json 计算（多 skill 共享）
                pass_rate = self._compute_global_pass_rate()
                scores.append(
                    SkillScore(
                        name=skill_name,
                        score_before=before,
                        score_after=after,
                        score_delta=after - before,
                        edits_applied=data.get("edits_applied", 0),
                        pass_rate_before=pass_rate[0],
                        pass_rate_after=pass_rate[1],
                    )
                )
            elif skill_dir.is_dir() or self._artifact_dir.is_dir():
                # Fallback: 从 eval_results.json 和 selected_edits 计算
                score = self._compute_score_from_artifacts(skill_name)
                scores.append(score)
            else:
                scores.append(self._zero_score(skill_name))
        return scores

    def _compute_global_pass_rate(self) -> tuple[float, float]:
        """从首个/末尾 eval_results.json 计算全局 pass_rate。

        Returns:
            (pass_rate_before, pass_rate_after)
        """
        eval_data = self._read_first_and_last_eval_results()
        if not eval_data:
            return 0.0, 0.0

        first_data, last_data = eval_data
        first_scores = [float(r.get("score", 0.0)) for r in first_data.get("results", [])]
        last_scores = [float(r.get("score", 0.0)) for r in last_data.get("results", [])]
        return (
            self._compute_pass_rate(first_scores, self._score_threshold),
            self._compute_pass_rate(last_scores, self._score_threshold),
        )

    def _compute_score_from_artifacts(self, skill_name: str) -> SkillScore:
        """从 eval_results.json 和 selected_edits_*.json 计算 SkillScore。

        score_before: 第一个 epoch 的第一个 step 的 avg_score
        score_after: 最后一个 epoch 的最后一个 step 的 avg_score
        edits_applied: 所有 step 中该 skill 的 selected_edits 总数
        """
        # 搜索 eval_results.json（可能在 skill 子目录或根目录下）
        search_dirs = []
        skill_dir = self._artifact_dir / skill_name
        if skill_dir.is_dir():
            search_dirs.append(skill_dir)
        if self._artifact_dir.is_dir():
            search_dirs.append(self._artifact_dir)

        # 收集所有 eval_results.json（按 epoch/step 排序）
        eval_files: list[Path] = []
        for search_dir in search_dirs:
            for p in _training_eval_files(search_dir):
                if p not in eval_files:
                    eval_files.append(p)

        if not eval_files:
            return self._zero_score(skill_name)

        # 提取分数
        scores_list: list[float] = []
        for ef in eval_files:
            try:
                with ef.open(encoding="utf-8") as f:
                    data = json.load(f)
                avg = data.get("avg_score")
                if avg is not None:
                    scores_list.append(float(avg))
            except (json.JSONDecodeError, ValueError, KeyError):
                continue

        score_before = scores_list[0] if scores_list else 0.0
        score_after = max(scores_list) if scores_list else 0.0

        # pass_rate 从首个/末尾 eval_results.json 的 per-case scores 计算
        pass_rate_before = 0.0
        pass_rate_after = 0.0
        try:
            with eval_files[0].open(encoding="utf-8") as f:
                first_data = json.load(f)
            first_scores = [float(r.get("score", 0.0)) for r in first_data.get("results", [])]
            pass_rate_before = self._compute_pass_rate(first_scores, self._score_threshold)
        except (json.JSONDecodeError, ValueError, KeyError):
            pass

        try:
            with eval_files[-1].open(encoding="utf-8") as f:
                last_data = json.load(f)
            last_scores = [float(r.get("score", 0.0)) for r in last_data.get("results", [])]
            pass_rate_after = self._compute_pass_rate(last_scores, self._score_threshold)
        except (json.JSONDecodeError, ValueError, KeyError):
            pass

        # 计算该 skill 的 edits 总数
        total_edits = 0
        for search_dir in search_dirs:
            # 新结构: skill 子目录里是 selected_edits.json（无后缀）
            # 旧结构: 根目录里是 selected_edits_{skill_name}.json
            for pattern in ("selected_edits.json", f"selected_edits_{skill_name}.json"):
                for sf in search_dir.rglob(pattern):
                    try:
                        with sf.open(encoding="utf-8") as f:
                            data = json.load(f)
                        total_edits += len(data.get("edits", []))
                    except (json.JSONDecodeError, ValueError, KeyError):
                        continue

        return SkillScore(
            name=skill_name,
            score_before=score_before,
            score_after=score_after,
            score_delta=score_after - score_before,
            edits_applied=total_edits,
            pass_rate_before=pass_rate_before,
            pass_rate_after=pass_rate_after,
        )

    @staticmethod
    def _zero_score(name: str) -> SkillScore:
        """无数据时的默认 SkillScore。"""
        return SkillScore(
            name=name,
            score_before=0.0,
            score_after=0.0,
            score_delta=0.0,
            edits_applied=0,
            pass_rate_before=0.0,
            pass_rate_after=0.0,
        )

    def _read_summary(self) -> dict[str, Any]:
        """尝试读取 summary.json。"""
        path = self._artifact_dir / "summary.json"
        if path.exists():
            with path.open(encoding="utf-8") as f:
                return json.load(f)  # type: ignore[no-any-return]
        return {}

    def _collect_gate_results(self) -> list[str]:
        """扫描 epoch_*/gate_result.json 收集 gate 结果。"""
        results: list[str] = []
        if not self._artifact_dir.exists():
            return results

        for epoch_dir in sorted(self._artifact_dir.glob("epoch_*")):
            gate_path = epoch_dir / "gate_result.json"
            if gate_path.exists():
                with gate_path.open(encoding="utf-8") as f:
                    data = json.load(f)
                results.append(data.get("decision", "unknown"))
        return results

    @staticmethod
    def _calc_improvement(before: float, after: float) -> str:
        """计算改进百分比字符串。"""
        if before == 0:
            if after > 0:
                return "+∞"
            return "0%"
        delta = (after - before) / before * 100
        sign = "+" if delta >= 0 else ""
        return f"{sign}{delta:.0f}%"
