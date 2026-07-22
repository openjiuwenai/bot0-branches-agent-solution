# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Artifact exporter for training diagnostics (D1/D3/D7).

Writes intermediate artifacts (JSON, markdown, unified diffs) to a
structured directory. Independent of checkpoint system.

All methods are no-ops when output_dir is None.
"""

from __future__ import annotations

import difflib
import json
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from evo_agent.errors import ArtifactPersistenceError
from evo_agent.evaluator.batch_result import EvaluationBatchResult, EvaluationOutcome
from evo_agent.llm.trajectory_compaction import (
    TrajectoryCompactionContext,
    TrajectoryCompactionPolicy,
    compact_trajectory,
)
from evo_agent.optimizer.artifact_io import (
    atomic_write_json,
    atomic_write_jsonl,
    atomic_write_marker,
    sha256_file,
)
from evo_agent.optimizer.skill_document.types import (
    GateEvaluationRecord,
    ValidationCoverageFailureInput,
)


class ArtifactExporter:
    """Stateless artifact exporter for training diagnostics.

    Directory structure:
        output_dir/epoch_N/step_M/{trajectories.jsonl, eval_results.json, ...}
        output_dir/epoch_N/{skill_before.md, gate_result.json, experience_library.json, ...}
    """

    def __init__(
        self,
        output_dir: str | None,
        *,
        score_threshold: float,
        export_trajectories: bool = True,
    ) -> None:
        self._output_dir = Path(output_dir) if output_dir else None
        self._export_trajectories = export_trajectories
        self._score_threshold = score_threshold

    @property
    def enabled(self) -> bool:
        return self._output_dir is not None

    def _epoch_dir(self, epoch: int) -> Path | None:
        if not self._output_dir:
            return None
        d = self._output_dir / f"epoch_{epoch}"
        self._mkdir(d)
        return d

    def _step_dir(self, epoch: int, step: int) -> Path | None:
        epoch_dir = self._epoch_dir(epoch)
        if not epoch_dir:
            return None
        d = epoch_dir / f"step_{step}"
        self._mkdir(d)
        return d

    @staticmethod
    def _mkdir(path: Path) -> None:
        try:
            path.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise ArtifactPersistenceError(str(path), exc) from exc

    @staticmethod
    def _remove(path: Path) -> None:
        try:
            path.unlink(missing_ok=True)
        except OSError as exc:
            raise ArtifactPersistenceError(str(path), exc) from exc

    @staticmethod
    def _write_json(path: Path, data: Any) -> None:
        atomic_write_json(path, data)

    def export_trajectories(
        self,
        epoch: int,
        step: int,
        trajectories: list[Any],
        eval_results: list[Any],
    ) -> None:
        """Write trajectories as JSONL (one case per line)."""
        if not self._export_trajectories:
            return
        step_dir = self._step_dir(epoch, step)
        if not step_dir:
            return

        rows = []
        for traj, eval_result in zip(trajectories, eval_results):
            rows.append(
                {
                    "case_id": getattr(eval_result, "case_id", ""),
                    "score": getattr(eval_result, "score", 0.0),
                    "steps": [
                        {
                            "kind": getattr(s, "kind", ""),
                            "detail": str(getattr(s, "detail", ""))[:500],
                        }
                        for s in getattr(traj, "steps", [])
                    ],
                }
            )
        atomic_write_jsonl(step_dir / "trajectories.jsonl", rows)

    def export_eval_results(
        self,
        epoch: int,
        step: int,
        eval_results: list[Any],
        cases: list[Any],
        *,
        outcomes: Sequence[EvaluationOutcome] | None = None,
    ) -> None:
        """Write successful scores plus artifact-safe training failures."""
        step_dir = self._step_dir(epoch, step)
        if not step_dir:
            return

        results = []
        for er, case in zip(eval_results, cases):
            results.append(
                {
                    "case_id": getattr(case, "case_id", getattr(er, "case_id", "")),
                    "score": getattr(er, "score", 0.0),
                    "reason": getattr(er, "reason", ""),
                }
            )

        scores = [float(r["score"]) for r in results]
        failures = []
        if outcomes is not None:
            for outcome in outcomes:
                if outcome.failure is None:
                    continue
                failures.append(
                    {
                        "case_id": outcome.case_id,
                        "category": outcome.failure.category,
                        "safe_message": outcome.failure.safe_message,
                        "invocation_id": outcome.failure.invocation_id,
                        "response_sha256": outcome.failure.response_sha256,
                        "response_chars": outcome.failure.response_chars,
                    }
                )
        attempted_count = len(outcomes) if outcomes is not None else len(results)
        evaluated_count = attempted_count - len(failures)
        data = {
            "epoch": epoch,
            "step": step,
            "results": results,
            "failures": failures,
            "attempted_count": attempted_count,
            "evaluated_count": evaluated_count,
            "skipped_count": len(failures),
            "coverage": evaluated_count / attempted_count if attempted_count else 1.0,
            "avg_score": sum(scores) / len(scores) if scores else 0.0,
            "failure_rate": (
                sum(1 for s in scores if s < self._score_threshold) / len(scores) if scores else 0.0
            ),
        }
        self._write_json(step_dir / "eval_results.json", data)

    def export_raw_patches(self, epoch: int, step: int, acc_round: int, patches: list[Any]) -> None:
        """Write raw patches from reflect phase."""
        step_dir = self._step_dir(epoch, step)
        if not step_dir:
            return

        serialized = []
        for p in patches:
            patch_obj = getattr(p, "patch", p)
            serialized.append(
                {
                    "source_type": getattr(p, "source_type", ""),
                    "failure_summary": getattr(p, "failure_summary", ""),
                    "operator_id": getattr(p, "operator_id", ""),
                    "reasoning": getattr(patch_obj, "reasoning", ""),
                    "edits": [
                        {
                            "op": getattr(e, "op", ""),
                            "content": getattr(e, "content", ""),
                            "target": getattr(e, "target", ""),
                        }
                        for e in getattr(patch_obj, "edits", [])
                    ],
                }
            )

        self._write_json(
            step_dir / "raw_patches.json",
            {"epoch": epoch, "step": step, "acc_round": acc_round, "patches": serialized},
        )

    def export_merged_patch(
        self, epoch: int, step: int, patch: Any, *, operator_id: str = ""
    ) -> None:
        """Write merged patch from aggregate phase."""
        step_dir = self._step_dir(epoch, step)
        if not step_dir:
            return

        data = {
            "epoch": epoch,
            "step": step,
            "operator_id": operator_id,
            "reasoning": getattr(patch, "reasoning", ""),
            "edits": [
                {
                    "op": getattr(e, "op", ""),
                    "content": getattr(e, "content", ""),
                    "target": getattr(e, "target", ""),
                }
                for e in getattr(patch, "edits", [])
            ],
        }
        filename = f"merged_patch_{operator_id}.json" if operator_id else "merged_patch.json"
        self._write_json(step_dir / filename, data)

    def export_selected_edits(
        self,
        epoch: int,
        step: int,
        edits: list[Any],
        rejected: list[Any],
        budget: int,
        *,
        operator_id: str = "",
    ) -> None:
        """Write selected edits from ranking phase."""
        step_dir = self._step_dir(epoch, step)
        if not step_dir:
            return

        data = {
            "epoch": epoch,
            "step": step,
            "operator_id": operator_id,
            "budget": budget,
            "selected_count": len(edits),
            "edits": [
                {
                    "op": getattr(e, "op", ""),
                    "content": getattr(e, "content", ""),
                    "target": getattr(e, "target", ""),
                    "support_count": getattr(e, "support_count", 0),
                }
                for e in edits
            ],
            "rejected": rejected,
        }
        filename = f"selected_edits_{operator_id}.json" if operator_id else "selected_edits.json"
        self._write_json(step_dir / filename, data)

    def export_skill_snapshot(
        self,
        epoch: int,
        step: int,
        skill_content: str,
        tag: str,
        *,
        operator_id: str = "",
    ) -> None:
        """Write skill document snapshot (before/after)."""
        epoch_dir = self._epoch_dir(epoch)
        if not epoch_dir:
            return
        if operator_id:
            filename = f"skill_{tag}_{operator_id}.md"
        else:
            filename = f"skill_{tag}.md"
        (epoch_dir / filename).write_text(skill_content, encoding="utf-8")

    def export_experience_library(
        self,
        epoch: int,
        library: dict[str, Any],
        *,
        operator_id: str = "",
    ) -> None:
        """Write TF-GRPO experience library snapshot after an optimization epoch.

        ``library`` should match ``ExperienceLibrary.to_dict()`` shape:
        ``{domain, max_experiences, experiences}``.
        """
        epoch_dir = self._epoch_dir(epoch)
        if not epoch_dir:
            return
        data = {
            "schema_version": 1,
            "epoch": epoch,
            "operator_id": operator_id or None,
            "domain": library.get("domain", "markdown"),
            "max_experiences": library.get("max_experiences"),
            "experiences": list(library.get("experiences") or []),
            "exported_at": datetime.now(UTC).isoformat(),
        }
        filename = (
            f"experience_library_{operator_id}.json"
            if operator_id
            else "experience_library.json"
        )
        self._write_json(epoch_dir / filename, data)

    def export_gate_result(
        self,
        epoch: int,
        base_score: float | None = None,
        candidate_score: float | None = None,
        decision: str | None = None,
        *,
        gate: GateEvaluationRecord | None = None,
        selected_batch: EvaluationBatchResult | None = None,
    ) -> None:
        """Write gate validation result."""
        epoch_dir = self._epoch_dir(epoch)
        if not epoch_dir:
            return

        if gate is not None:
            base_score = gate.base_score
            candidate_score = gate.candidate_score
            decision = gate.decision
        selected_scores = (
            [item.score for item in selected_batch.successes] if selected_batch else []
        )
        attempted = selected_batch.attempted_count if selected_batch else len(selected_scores)
        evaluated_count = selected_batch.evaluated_count if selected_batch else len(selected_scores)
        data = {
            "schema_version": 2,
            "status": "valid",
            "epoch": epoch,
            "base_score": base_score,
            "candidate_score": candidate_score,
            "improvement": (
                candidate_score - base_score
                if base_score is not None and candidate_score is not None
                else None
            ),
            "decision": decision or "unknown",
            "score_threshold": self._score_threshold,
            "selected_failure_rate": (
                sum(score < self._score_threshold for score in selected_scores)
                / len(selected_scores)
                if selected_scores
                else None
            ),
            "attempted_count": attempted,
            "evaluated_count": evaluated_count,
            "skipped_count": attempted - evaluated_count,
            "coverage": evaluated_count / attempted if attempted else 1.0,
            "tie_revalued": gate.tie_revalued if gate else False,
            "candidate_score_first": gate.candidate_score_first if gate else None,
            "candidate_score_reval": gate.candidate_score_reval if gate else None,
        }
        self._write_json(epoch_dir / "gate_result.json", data)

    def export_validation(
        self,
        artifact_epoch: int,
        batch: EvaluationBatchResult,
        gate: GateEvaluationRecord,
    ) -> None:
        """Atomically publish the selected validation batch and completion marker."""
        epoch_dir = self._epoch_dir(artifact_epoch)
        if epoch_dir is None:
            return
        validation_dir = epoch_dir / "validation"
        self._mkdir(validation_dir)
        success_path = validation_dir / "_SUCCESS"
        self._remove(success_path)

        results: list[dict[str, Any]] = []
        failures: list[dict[str, Any]] = []
        trajectories: list[dict[str, Any]] = []
        for outcome in batch.outcomes:
            if outcome.evaluated is None:
                assert outcome.failure is not None
                failures.append(
                    {
                        "case_id": outcome.case_id,
                        "category": outcome.failure.category,
                        "invocation_id": outcome.failure.invocation_id,
                        "safe_message": outcome.failure.safe_message,
                        "response_sha256": outcome.failure.response_sha256,
                        "response_chars": outcome.failure.response_chars,
                    }
                )
                continue
            reason = _reason_data(outcome.evaluated.reason)
            results.append(
                {
                    "case_id": outcome.case_id,
                    "score": outcome.evaluated.score,
                    "reason": reason.get("reason", outcome.evaluated.reason),
                    "repaired": reason.get("repaired", False),
                    "parse_mode": reason.get("parse_mode", "exact"),
                    "repair_operations": reason.get("repair_operations", []),
                }
            )
            compacted = compact_trajectory(
                outcome.trajectory,
                policy=TrajectoryCompactionPolicy(stage="artifact_validation"),
                context=TrajectoryCompactionContext(),
                token_budget=16_384,
            )
            trajectories.append(
                {
                    "case_id": outcome.case_id,
                    "score": outcome.evaluated.score,
                    "trajectory": json.loads(compacted.text),
                    "compaction": {
                        "estimated_tokens_before": compacted.estimated_tokens_before,
                        "estimated_tokens_after": compacted.estimated_tokens_after,
                        **dict(compacted.metadata),
                    },
                }
            )

        result_ids = {row["case_id"] for row in results}
        trajectory_ids = {row["case_id"] for row in trajectories}
        if result_ids != trajectory_ids:
            raise ValueError("validation result and trajectory case ids differ")
        scores = [float(row["score"]) for row in results]
        result_data = {
            "schema_version": 1,
            "epoch": artifact_epoch,
            "decision": gate.decision,
            "score_threshold": self._score_threshold,
            "attempted_count": batch.attempted_count,
            "evaluated_count": batch.evaluated_count,
            "skipped_count": batch.skipped_count,
            "coverage": batch.coverage,
            "avg_score": sum(scores) / len(scores) if scores else None,
            "selected_failure_rate": (
                sum(score < self._score_threshold for score in scores) / len(scores)
                if scores
                else None
            ),
            "results": results,
        }
        results_path = validation_dir / "results.json"
        failures_path = validation_dir / "failures.jsonl"
        trajectories_path = validation_dir / "trajectories.jsonl"
        atomic_write_json(results_path, result_data)
        atomic_write_jsonl(failures_path, failures)
        atomic_write_jsonl(trajectories_path, trajectories)

        gate_path = epoch_dir / "gate_result.json"
        files = {
            "results.json": sha256_file(results_path),
            "failures.jsonl": sha256_file(failures_path),
            "trajectories.jsonl": sha256_file(trajectories_path),
            "../gate_result.json": sha256_file(gate_path),
        }
        atomic_write_json(
            validation_dir / "manifest.json",
            {
                "schema_version": 1,
                "complete": True,
                "generated_at": datetime.now(UTC).isoformat(),
                "decision": gate.decision,
                "coverage": batch.coverage,
                "result_count": len(results),
                "failure_count": len(failures),
                "trajectory_count": len(trajectories),
                "files": files,
            },
        )
        atomic_write_marker(success_path)

    def export_validation_failure(
        self,
        artifact_epoch: int,
        failure: ValidationCoverageFailureInput,
    ) -> None:
        """Publish fail-closed validation diagnostics without a success marker."""
        epoch_dir = self._epoch_dir(artifact_epoch)
        if epoch_dir is None:
            return
        validation_dir = epoch_dir / "validation"
        self._mkdir(validation_dir)
        self._remove(validation_dir / "_SUCCESS")

        error = failure.error
        gate_data = {
            "schema_version": 2,
            "status": "invalid",
            "epoch": artifact_epoch,
            "base_score": None,
            "candidate_score": None,
            "improvement": None,
            "decision": "invalid",
            "retained_state": "base",
            "score_threshold": self._score_threshold,
            "selected_failure_rate": None,
            "attempted_count": error.attempted_count,
            "evaluated_count": error.evaluated_count,
            "skipped_count": error.attempted_count - error.evaluated_count,
            "coverage": (
                error.evaluated_count / error.attempted_count if error.attempted_count else 0.0
            ),
            "failure_reason": error.reason,
            "tie_revalued": False,
            "candidate_score_first": None,
            "candidate_score_reval": None,
        }
        gate_path = epoch_dir / "gate_result.json"
        atomic_write_json(gate_path, gate_data)

        rows: list[dict[str, Any]] = []
        batches = (("base", failure.base_batch),) + tuple(
            (f"candidate_{index}", batch) for index, batch in enumerate(failure.candidate_batches)
        )
        for source, batch in batches:
            for outcome in batch.outcomes:
                if outcome.failure is None:
                    continue
                rows.append(
                    {
                        "source": source,
                        "case_id": outcome.case_id,
                        "category": outcome.failure.category,
                        "invocation_id": outcome.failure.invocation_id,
                        "safe_message": outcome.failure.safe_message,
                        "response_sha256": outcome.failure.response_sha256,
                        "response_chars": outcome.failure.response_chars,
                    }
                )
        failures_path = validation_dir / "failures.jsonl"
        atomic_write_jsonl(failures_path, rows)
        atomic_write_json(
            validation_dir / "manifest.json",
            {
                "schema_version": 1,
                "complete": False,
                "generated_at": datetime.now(UTC).isoformat(),
                "decision": "invalid",
                "retained_state": "base",
                "coverage": gate_data["coverage"],
                "failure_count": len(rows),
                "failure_reason": error.reason,
                "files": {
                    "failures.jsonl": sha256_file(failures_path),
                    "../gate_result.json": sha256_file(gate_path),
                },
            },
        )

    def export_metrics(self, epoch: int, step: int, metrics: dict[str, Any]) -> None:
        """Write step-level metrics."""
        step_dir = self._step_dir(epoch, step)
        if not step_dir:
            return
        self._write_json(step_dir / "metrics.json", metrics)

    def export_skill_diff(self, epoch: int, step: int, before: str, after: str) -> None:
        """Write unified diff between skill versions."""
        step_dir = self._step_dir(epoch, step)
        if not step_dir:
            return

        diff = difflib.unified_diff(
            before.splitlines(keepends=True),
            after.splitlines(keepends=True),
            fromfile="skill_before",
            tofile="skill_after",
        )
        (step_dir / "applied_diff.patch").write_text("".join(diff), encoding="utf-8")


def _reason_data(reason: str) -> dict[str, Any]:
    try:
        value = json.loads(reason)
    except (json.JSONDecodeError, TypeError):
        return {}
    return value if isinstance(value, dict) else {}
