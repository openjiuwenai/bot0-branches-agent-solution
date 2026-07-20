# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""ReflACT skill document optimizer.

Self-managing optimizer that runs the full ReflACT pipeline:
  rollout -> reflect -> aggregate -> select -> apply

Extensible via inheritance: override _rollout / _format_batch /
_format_single / _reflect / _aggregate / _select for customization.

Called by SingleDimUpdater.update() -> backward() -> _backward().
_step() returns base/candidate updates for Trainer validation selection.
"""

from __future__ import annotations

import asyncio
import json
import math
import random
import time
from collections.abc import Callable
from dataclasses import asdict, replace
from typing import TYPE_CHECKING, Any, cast

from openjiuwen.agent_evolving.dataset import Case, CaseLoader, EvaluatedCase
from openjiuwen.agent_evolving.optimizer.base import BaseOptimizer
from openjiuwen.agent_evolving.trajectory import TracerTrajectoryExtractor
from openjiuwen.agent_evolving.trajectory.types import (
    LLMCallDetail,
    ToolCallDetail,
    Trajectory,
)
from openjiuwen.core.common.logging import logger
from openjiuwen.core.foundation.llm.schema.message import UserMessage

from evo_agent.adapter_client.operator import split_frontmatter
from evo_agent.cancellation import CancellationRequestedError, CancellationToken
from evo_agent.evaluator.batch_result import (
    EvaluationBatchResult,
    EvaluationFailure,
    EvaluationOutcome,
)
from evo_agent.llm.invocation import (
    LLMInvocation,
    LLMInvocationContext,
    LLMInvocationError,
    LLMInvocationResult,
    LLMProviderCapabilities,
    PromptBudgetExceededError,
)
from evo_agent.llm.structured_output import (
    StructuredOutputResult,
    log_structured_output,
    parse_structured_output,
)
from evo_agent.llm.trajectory_compaction import (
    TrajectoryCompactionContext,
    TrajectoryCompactionPolicy,
    compact_trajectory,
)
from evo_agent.optimizer.llm_resilience import (
    LLMInvokePolicy,
    invoke_with_retry,
)
from evo_agent.optimizer.skill_document.artifact_exporter import ArtifactExporter
from evo_agent.optimizer.skill_document.edit_apply import apply_patch_with_report
from evo_agent.optimizer.skill_document.prompts import load_skill_opt_prompt
from evo_agent.optimizer.skill_document.scheduler import build_scheduler
from evo_agent.optimizer.skill_document.structured_validators import (
    MERGE_FAILURE_POLICY,
    MERGE_FINAL_POLICY,
    MERGE_SUCCESS_POLICY,
    RANKING_POLICY,
    REFLECT_FAILURE_POLICY,
    REFLECT_SUCCESS_POLICY,
    reflect_patch_data,
    valid_edit_items,
    valid_selected_indices,
    validate_merge_output,
    validate_ranking_output,
    validate_reflect_output,
)
from evo_agent.optimizer.skill_document.types import (
    AttributedBatch,
    Edit,
    GateEpochArtifactInput,
    Patch,
    RawPatch,
    ValidationCoverageFailureInput,
)
from evo_agent.optimizer.skill_document.update_modes import (
    normalize_update_mode,
)
from evo_agent.protocols import SKILL_CONTENT_TARGET

if TYPE_CHECKING:
    from openjiuwen.agent_evolving.evaluator.evaluator import BaseEvaluator
    from openjiuwen.core.foundation.llm.model import Model
    from openjiuwen.core.single_agent.base import BaseAgent

_VALID_OPS = frozenset({"append", "insert_after", "replace", "delete"})


def _dumps_for_prompt(data: Any) -> str:
    """Serialize data to compact JSON for embedding in LLM prompts.

    紧凑格式（无缩进、分隔符无空格）以降低 prompt token 成本；
    ``ensure_ascii=False`` 保留中文可读性。落盘 JSON 不走此函数，
    仍用 ``indent=2, ensure_ascii=False``。
    """
    return json.dumps(data, ensure_ascii=False, indent=None, separators=(",", ":"))


def _safe_int(value: Any) -> int:
    """Coerce optional model metadata without letting one bad edit reject its siblings."""
    try:
        return int(value or 0)
    except (TypeError, ValueError, OverflowError):
        return 0


def _string_tuple(value: Any, default: tuple[str, ...]) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)):
        return default
    return tuple(item for item in value if isinstance(item, str))


def _log_failed_structured_invocation(
    exc: Exception,
    *,
    stage: str,
    schema_name: str,
    fallback: str,
    parse: Callable[[str], StructuredOutputResult],
) -> bool:
    """Log the final validator-rejected attempt with parser provenance."""
    if (
        not isinstance(exc, LLMInvocationError)
        or exc.category != "unusable_response"
        or exc.result is None
    ):
        return False
    result = exc.result
    extraction = parse(result.text)
    log_structured_output(
        extraction,
        stage=stage,
        schema_name=schema_name,
        invocation_id=result.invocation_id,
        attempt=result.metadata.get("attempt", "unknown"),
        finish_reason=result.finish_reason or "unknown",
        transport_complete=result.transport_complete,
        fallback=fallback,
    )
    return True


# A8 (#17): tightened timeouts so retry triggers earlier on large cases.
# prev: attempt=180s / total=600s. If 90s proves too tight for some models'
# p99 latency, raise here and observe retry success rate via perf_bench.
# Note: LLMInvokePolicy (openjiuwen) has no name field, so per-policy
# identity is not emitted in llm_resilience timeout warnings — bisect via
# these comments and the attempt_timeout_secs value in the log.
_REFLECT_POLICY = LLMInvokePolicy(
    attempt_timeout_secs=90,  # A8: 180→90，大 case 下 retry 更早触发
    total_budget_secs=300,  # A8: 600→300
    max_attempts=2,
)
_AGGREGATE_POLICY = LLMInvokePolicy(
    attempt_timeout_secs=90,  # A8: 180→90
    total_budget_secs=300,  # A8: 600→300
    max_attempts=2,
)
_RANKING_POLICY = LLMInvokePolicy(
    attempt_timeout_secs=120,
    total_budget_secs=300,  # A8: 600→300
    max_attempts=2,
)


# ── Trajectory formatting helpers ─────────────────────────────────────────


def _clip_text(value: Any, limit: int) -> str:
    if value is None:
        return ""
    return str(value)[:limit]


def _extract_content(msg: Any) -> str:
    """Extract text content from a message (dict or object)."""
    if isinstance(msg, dict):
        return str(msg.get("content", "") or "")
    if hasattr(msg, "content"):
        return str(msg.content or "")
    return str(msg or "")


def _extract_task_description(case: Case) -> str:
    """Extract a task description from a Case for formatting."""
    inputs = case.inputs or {}
    for key in ("task_description", "instruction", "question", "query"):
        if key in inputs:
            return str(inputs[key])[:500]
    return str(inputs)[:500]


def _legacy_training_batch(
    cases: list[Case],
    trajectories: list[Any],
    evaluated: list[EvaluatedCase],
) -> EvaluationBatchResult:
    """Adapt a legacy success-only evaluator to the lossless batch interface."""
    evaluated_by_id = {item.case.case_id: item for item in evaluated}
    outcomes: list[EvaluationOutcome] = []
    for index, (case, trajectory) in enumerate(zip(cases, trajectories)):
        evaluated_case = evaluated_by_id.get(case.case_id)
        failure = None
        if evaluated_case is None:
            failure = EvaluationFailure(
                category="schema_validation_error",
                safe_message="legacy evaluator returned no result for this case",
            )
        outcomes.append(
            EvaluationOutcome(
                index=index,
                case_id=case.case_id,
                case=case,
                trajectory=trajectory,
                evaluated=evaluated_case,
                failure=failure,
            )
        )
    return EvaluationBatchResult(tuple(outcomes))


# ── Main optimizer class ──────────────────────────────────────────────────


class SkillDocumentOptimizer(BaseOptimizer):  # type: ignore[misc]
    """ReflACT skill document optimizer.

    Self-managing: requires_forward_data()=False.
    Extensible: override _rollout / _format_batch / _reflect /
    _aggregate / _select in subclasses to customize per-scenario.

    SingleDimUpdater.update() -> backward() -> _backward() runs the full epoch:
      for step in steps_per_epoch:
        for a in accumulation:
          rollout -> format -> reflect
        aggregate -> select -> apply
    Then _step() returns base/candidate updates for Trainer validation.
    """

    domain = "skill_document"

    # Class-level default so __new__-constructed instances (e.g. unit tests that
    # bypass __init__) inherit the production default. __init__ overrides with
    # the explicit preserve_frontmatter kwarg for normally-constructed instances.
    _preserve_frontmatter: bool = True

    def __init__(
        self,
        *,
        agent: BaseAgent,
        evaluator: BaseEvaluator,
        extractor: TracerTrajectoryExtractor | None = None,
        num_parallel: int = 4,
        llm: Model,
        llm_invocation: LLMInvocation | None = None,
        model: str,
        train_cases: CaseLoader,
        batch_size: int = 40,
        accumulation: int = 2,
        steps_per_epoch: int | None = None,
        minibatch_size: int = 8,
        edit_budget: int = 10,
        scheduler_mode: str = "constant",
        update_mode: str = "patch",
        score_threshold: float = 0.5,
        parallelism: int = 4,
        max_chars_per_traj: int = 12_000,
        max_msg_chars: int = 500,
        max_tool_result_chars: int = 800,
        use_slow_update: bool = True,
        use_meta_skill: bool = True,
        preserve_frontmatter: bool = True,
        artifact_dir: str | None = None,
        artifact_export_trajectories: bool = True,
        cancellation_token: CancellationToken | None = None,
    ):
        super().__init__()

        # Validate
        if update_mode != "patch":
            raise NotImplementedError(
                f"update_mode={update_mode!r} not yet supported (Phase 1: patch only)"
            )
        if scheduler_mode == "autonomous":
            raise NotImplementedError("scheduler_mode='autonomous' not yet supported")

        n_cases = len(train_cases.get_cases()) if train_cases else 0
        if n_cases and batch_size * accumulation > n_cases:
            import warnings

            warnings.warn(
                f"batch_size({batch_size}) * accumulation({accumulation}) = "
                f"{batch_size * accumulation} > train_cases({n_cases}). "
                "Each rollout round will use all cases.",
                stacklevel=2,
            )

        # Store dependencies
        self._agent = agent
        self._evaluator = evaluator
        self._extractor = extractor or TracerTrajectoryExtractor()
        self._num_parallel = num_parallel
        self._train_cases = train_cases
        self._llm = llm_invocation or LLMInvocation(
            llm,
            capabilities=LLMProviderCapabilities(
                context_window_tokens=32768,
                supports_max_output_tokens=False,
                supports_finish_reason=True,
                supports_usage=True,
                supports_json_mode=True,
                completion_signal="either",
            ),
            parallelism=parallelism,
            safety_margin_tokens=512,
            chars_per_token=2.0,
        )
        self._model = model
        self._cancellation_token = cancellation_token or CancellationToken()

        # Hyperparameters
        self._batch_size = batch_size
        self._accumulation = accumulation
        self._minibatch_size = minibatch_size
        self._update_mode = normalize_update_mode(update_mode)
        self._score_threshold = score_threshold

        # Trajectory formatting params
        self._max_chars_per_traj = max_chars_per_traj
        self._max_msg_chars = max_msg_chars
        self._max_tool_result_chars = max_tool_result_chars

        # Compute steps_per_epoch
        self._steps_per_epoch = steps_per_epoch or (
            math.ceil(n_cases / (batch_size * accumulation))
            if n_cases and batch_size and accumulation
            else 1
        )

        self._scheduler = build_scheduler(
            mode=scheduler_mode,
            max_lr=edit_budget,
            total_steps=self._steps_per_epoch,
        )

        # Cross-step state
        self._global_step = 0
        self._step_buffer: list[dict[str, Any]] = []
        self._meta_skill_context = ""

        # Output of _backward(), consumed by _step()
        # Phase 1 (single-operator) backward-compat view.
        # _current_skill_content always reflects the FIRST operator (by insertion order).
        # Do NOT use for multi-operator logic — use _current_skill_by_operator instead.
        self._ranked_patch: Patch | None = None
        self._current_skill_content = ""
        self._epoch_base_skill_content = ""
        self._last_candidate_skill_content = ""

        # Phase 2 (multi-operator) per-operator state
        self._ranked_patch_by_operator: dict[str, Patch] = {}
        self._current_skill_by_operator: dict[str, str] = {}
        self._epoch_base_skill_by_operator: dict[str, str] = {}
        self._last_candidate_skill_by_operator: dict[str, str] = {}

        # Epoch-level state (for slow_update)
        self._use_slow_update = use_slow_update
        self._use_meta_skill = use_meta_skill
        # Frontmatter 优化开关：True 时 LLM 反思输入 strip frontmatter（body-only），
        # 写回/snapshot/diff 全程仍走全文（不变式）；False 时 frontmatter 全程参与。
        self._preserve_frontmatter = preserve_frontmatter
        self._prev_epoch_skill: str = ""
        self._prev_epoch_skill_by_operator: dict[str, str] = {}
        self._prev_epoch_comparison: list[dict[str, Any]] = []
        self._curr_epoch_comparison: list[dict[str, Any]] = []

        # Artifact export config
        self._artifact_dir = artifact_dir
        self._artifact_export_trajectories = artifact_export_trajectories
        self._artifact_exporter = ArtifactExporter(
            artifact_dir,
            score_threshold=score_threshold,
            export_trajectories=artifact_export_trajectories,
        )
        self._artifact_epoch = -1
        self._last_training_batch: EvaluationBatchResult | None = None

    def _raise_if_cancellation_requested(self) -> None:
        """Honor cancellation while tolerating legacy ``__new__`` test fixtures."""
        token = getattr(self, "_cancellation_token", None)
        if token is not None:
            token.raise_if_requested()

    @staticmethod
    def requires_forward_data() -> bool:
        return False

    @staticmethod
    def default_targets() -> list[str]:
        return [SKILL_CONTENT_TARGET]

    # ── Case sampling ────────────────────────────────────────────────────

    def _sample_cases(self, n: int, seed: int = 0) -> list[Case]:
        all_cases = self._train_cases.get_cases()
        # random.sample 直接选取 n 个（O(n)），避免全量 shuffle（O(N)）。
        # k 超过全集时 clamp 到全集大小，与旧 shuffle+slice 行为一致。
        k = min(n, len(all_cases))
        return random.Random(seed).sample(all_cases, k)

    # ── Trajectory formatting ────────────────────────────────────────────

    def _format_batch(
        self,
        batch: list[tuple[Trajectory, EvaluatedCase, Case]],
    ) -> str:
        """Format a minibatch of trajectories into analyst-readable text."""
        parts: list[str] = []
        for idx, (traj, eval_case, case) in enumerate(batch, 1):
            traj_text = self._format_single(traj, eval_case, case)
            header = (
                f"### Trajectory {idx} (id={case.case_id})\n"
                f"Task: {_extract_task_description(case)}\n"
                f"Score: {eval_case.score:.2f}\n"
            )
            if eval_case.reason:
                header += f"Reason: {_clip_text(eval_case.reason, 500)}\n"
            parts.append(header + "\n" + traj_text)
        return "\n\n---\n\n".join(parts)

    def _format_single(
        self,
        trajectory: Trajectory,
        evaluated_case: EvaluatedCase,
        case: Case,
    ) -> str:
        """Format a single trajectory with LLM/tool step rendering."""
        max_chars = self._max_chars_per_traj
        lines: list[str] = []
        for step in trajectory.steps:
            if step.kind == "llm" and isinstance(step.detail, LLMCallDetail):
                for msg in step.detail.messages:
                    role = (
                        msg.get("role", "unknown")
                        if isinstance(msg, dict)
                        else getattr(msg, "role", "unknown")
                    )
                    if role == "system":
                        continue
                    content = _extract_content(msg)
                    lines.append(f"[{role}] {_clip_text(content, self._max_msg_chars)}")
                if step.detail.response:
                    resp_content = _extract_content(step.detail.response)
                    lines.append(f"[assistant] {_clip_text(resp_content, self._max_msg_chars)}")
            elif step.kind == "tool" and isinstance(step.detail, ToolCallDetail):
                lines.append(
                    f"[action] {step.detail.tool_name}: "
                    f"{_clip_text(step.detail.call_args, self._max_msg_chars)}"
                )
                lines.append(
                    f"[obs]    {_clip_text(step.detail.call_result, self._max_tool_result_chars)}"
                )
        text = "\n".join(lines)
        if len(text) > max_chars:
            half = max_chars // 2
            text = text[:half] + "\n...[middle truncated]...\n" + text[-half:]
        return text

    # ── Rollout ──────────────────────────────────────────────────────────

    async def _rollout(
        self,
        cases: list[Case],
    ) -> tuple[list[EvaluatedCase], list[Trajectory]]:
        """Run agent on cases, return evaluated results + trajectories."""
        from openjiuwen.core.session.agent import create_agent_session

        async def run_one(case: Case, sem: asyncio.Semaphore) -> tuple[dict[str, Any], Any]:
            async with sem:
                self._raise_if_cancellation_requested()
                session = create_agent_session()
                try:
                    res = await self._agent.invoke(
                        {**case.inputs, "conversation_id": case.case_id},
                        session=session,
                    )
                except Exception as exc:
                    logger.warning(
                        "[skill_doc_opt] rollout error case=%s: %s",
                        case.case_id,
                        exc,
                    )
                    res = {"error": str(exc)}
                self._raise_if_cancellation_requested()
                return res, session

        sem = asyncio.Semaphore(
            min(self._num_parallel, len(cases)) if cases else 1,
        )
        results = await asyncio.gather(*[run_one(c, sem) for c in cases])
        predicts = [r[0] for r in results]
        sessions = [r[1] for r in results]

        trajectories = [
            self._extractor.extract(sess, case_id=case.case_id)
            for case, sess in zip(cases, sessions)
        ]
        batch = self._evaluate_training_batch(cases, predicts, trajectories)
        return list(batch.successes), trajectories

    def _evaluate_training_batch(
        self,
        cases: list[Case],
        predicts: list[dict[str, Any]],
        trajectories: list[Any],
        *,
        enable_attribution: bool | None = None,
    ) -> EvaluationBatchResult:
        """Evaluate training cases without dropping failure diagnostics."""
        detailed_evaluate = vars(self._evaluator).get("batch_evaluate_detailed")
        if detailed_evaluate is None:
            detailed_method = getattr(type(self._evaluator), "batch_evaluate_detailed", None)
            if detailed_method is not None:
                detailed_evaluate = detailed_method.__get__(self._evaluator)
        evaluation_options: dict[str, Any] = {"num_parallel": self._num_parallel}
        if enable_attribution is not None:
            evaluation_options["enable_attribution"] = enable_attribution
        if callable(detailed_evaluate):
            batch = cast(
                EvaluationBatchResult,
                detailed_evaluate(
                    cases,
                    predicts,
                    **evaluation_options,
                ),
            )
        else:
            evaluated = self._evaluator.batch_evaluate(
                cases,
                predicts,
                **evaluation_options,
            )
            batch = _legacy_training_batch(cases, trajectories, evaluated)
        self._last_training_batch = batch
        return batch

    # ── Attribution ──────────────────────────────────────────────────────

    async def _reflect_all_operators(
        self,
        attributed: dict[str, AttributedBatch],
        *,
        step: int,
        accumulation: int,
    ) -> dict[str, list[RawPatch]]:
        """Run reflect for all operators concurrently (C2 / #3).

        Cross-operator ``asyncio.gather``；每个 operator 的 _reflect 最终进入
        run-scoped ``LLMInvocation`` 获取 permit，故此处不外加 semaphore，避免
        双重获取死锁。总并发仍 ≤ parallelism。

        Fail-fast 语义（与串行版一致）：不使用 ``return_exceptions=True``。
        若某 operator 的 _reflect 抛出未被内层 run_analyst 吞掉的异常，
        ``asyncio.gather`` 会取消其余在途 operator 并向上抛出——与串行
        for-loop 中“B 失败则 C/D 不执行”一致。内层 run_analyst 已 catch
        ``Exception`` 返回 None，故实际几乎不上抛；如需“单 operator 失败不
        影响其他”，须显式改 return_exceptions + 手动过滤（参考 trainer.py
        的 _rollout_and_evaluate），但这会偏离串行语义，当前不采用。
        """
        valid_op_ids = set(self._operators)

        async def _reflect_one(op_id: str) -> tuple[str, list[RawPatch]]:
            self._raise_if_cancellation_requested()
            attr_batch = attributed.get(op_id)
            if attr_batch is not None:
                op_batch_data = list(attr_batch.failures) + list(attr_batch.successes)
            else:
                op_batch_data = []
            formatted_op = self._format_batch(op_batch_data) if op_batch_data else ""
            reflect_started = time.perf_counter()
            raw_patches = await self._reflect(
                formatted_batch=formatted_op,
                skill_content=self._llm_skill_view(self._current_skill_by_operator.get(op_id, "")),
                score_threshold=self._score_threshold,
                batch_data=op_batch_data if op_batch_data else None,
                operator_id=op_id,
            )
            self._raise_if_cancellation_requested()
            logger.info(
                "[timing] train.reflect epoch=%d step=%d accumulation=%d "
                "operator=%s samples=%d patches=%d elapsed=%.3fs",
                self._current_epoch + 1,
                step + 1,
                accumulation + 1,
                op_id,
                len(op_batch_data),
                len(raw_patches),
                time.perf_counter() - reflect_started,
            )
            valid_patches = self._validate_raw_patch_operator_id(raw_patches, valid_op_ids)
            return op_id, valid_patches

        results = await asyncio.gather(*[_reflect_one(op_id) for op_id in self._operators])
        patches_by_operator: dict[str, list[RawPatch]] = {op_id: [] for op_id in self._operators}
        for _op_id, valid_patches in results:
            for raw_patch in valid_patches:
                patches_by_operator[raw_patch.operator_id].append(raw_patch)
        return patches_by_operator

    async def _aggregate_select_apply_all_operators(
        self,
        patches_by_operator: dict[str, list[RawPatch]],
        *,
        budget: int,
        step: int,
        artifact_epoch: int,
    ) -> tuple[dict[str, int], dict[str, int], Patch, list[Edit]]:
        """Cross-operator aggregate→select→apply concurrent (C4 / #19).

        每个 operator 的 aggregate/select 经 run-scoped ``LLMInvocation`` 约束
        总 LLM 并发，跨 operator gather 不外加 semaphore。各 operator 仅写自身
        key，无跨 operator 写冲突。
        返回 (n_merged, n_selected, last_merged, last_selected) 供向后兼容字段。

        Fail-fast 语义（与串行版一致，见 _reflect_all_operators docstring）：不使用
        ``return_exceptions=True``，单 operator 抛异常会取消其余在途 operator 并上抛。
        """
        multi = len(self._operators) > 1

        async def _one(op_id: str, patches: list[RawPatch]) -> tuple[str, Patch, list[Edit]]:
            self._raise_if_cancellation_requested()
            skill_content = self._current_skill_by_operator.get(op_id, "")
            aggregate_started = time.perf_counter()
            merged = await self._aggregate(
                patches=patches, skill_content=self._llm_skill_view(skill_content)
            )
            self._raise_if_cancellation_requested()
            logger.info(
                "[timing] train.aggregate epoch=%d step=%d operator=%s patches=%d "
                "edits=%d elapsed=%.3fs",
                self._current_epoch + 1,
                step + 1,
                op_id,
                len(patches),
                len(merged.edits),
                time.perf_counter() - aggregate_started,
            )
            select_started = time.perf_counter()
            selected_edits = await self._select(
                edits=merged.edits,
                budget=budget,
                skill_content=self._llm_skill_view(skill_content),
            )
            self._raise_if_cancellation_requested()
            logger.info(
                "[timing] train.select epoch=%d step=%d operator=%s edits=%d "
                "selected=%d budget=%d elapsed=%.3fs",
                self._current_epoch + 1,
                step + 1,
                op_id,
                len(merged.edits),
                len(selected_edits),
                budget,
                time.perf_counter() - select_started,
            )
            ranked = Patch(edits=selected_edits, reasoning=merged.reasoning)
            self._ranked_patch_by_operator[op_id] = ranked

            self._artifact_exporter.export_merged_patch(
                artifact_epoch,
                step,
                merged,
                operator_id=op_id if multi else "",
            )
            self._artifact_exporter.export_selected_edits(
                artifact_epoch,
                step,
                selected_edits,
                self._extract_rejected_edits(),
                budget,
                operator_id=op_id if multi else "",
            )

            if ranked.edits:
                self._raise_if_cancellation_requested()
                apply_started = time.perf_counter()
                updated_skill, _ = apply_patch_with_report(skill_content, ranked)
                self._current_skill_by_operator[op_id] = updated_skill
                self._sync_skill_to_operator_by_id(op_id, updated_skill)
                logger.info(
                    "[timing] train.apply epoch=%d step=%d operator=%s edits=%d elapsed=%.3fs",
                    self._current_epoch + 1,
                    step + 1,
                    op_id,
                    len(ranked.edits),
                    time.perf_counter() - apply_started,
                )
            return op_id, merged, selected_edits

        results = await asyncio.gather(
            *[_one(op, patches_by_operator[op]) for op in patches_by_operator]
        )
        n_merged: dict[str, int] = {}
        n_selected: dict[str, int] = {}
        last_merged = Patch(edits=[], reasoning="no patches")
        last_selected: list[Edit] = []
        for op_id, merged, selected_edits in results:
            n_merged[op_id] = len(merged.edits)
            n_selected[op_id] = len(selected_edits)
            last_merged, last_selected = merged, selected_edits
        return n_merged, n_selected, last_merged, last_selected

    async def _attribute(
        self,
        *,
        failure_batch: list[tuple[Trajectory, EvaluatedCase, Case]],
        success_batch: list[tuple[Trajectory, EvaluatedCase, Case]],
        skill_contents: dict[str, str],
    ) -> dict[str, AttributedBatch]:
        """Attribute failures/successes to operators.

        Single operator: short-circuit. badcase is kept only when the evaluator
        attributed it to the sole skill (``attributed_skill`` matches op_id);
        unattributed / non-skill failures are dropped (ADR-0009 F1). goodcase is
        kept in full — the sole skill necessarily contributed to the success.
        Multi operator: rule-based from trajectory step metadata.
        Ambiguous/unknown: conservative — attribute to all operators.
        """
        op_ids = list(self._operators.keys())

        # Single operator short-circuit
        if len(op_ids) == 1:
            op_id = op_ids[0]
            # badcase: 只保留评估器归因到这个 skill 的；非 skill 失败(留空)或归因到
            # 不存在 skill 的 → 丢(保守，避免基于不相关失败误优化)。
            filtered_failures = [
                item for item in failure_batch if self._single_skill_badcase_keep(item[1], op_id)
            ]
            # goodcase: 单 skill 下唯一 skill 必然贡献成功，全量保留(不变)。
            successes = list(success_batch)
            if not filtered_failures and not successes:
                return {}
            return {
                op_id: AttributedBatch(
                    operator_id=op_id,
                    failures=filtered_failures,
                    successes=successes,
                ),
            }

        # Multi operator: rule-based attribution
        result: dict[str, AttributedBatch] = {
            op_id: AttributedBatch(operator_id=op_id, failures=[], successes=[]) for op_id in op_ids
        }

        # Attribute each failure to participating operators
        for item in failure_batch:
            traj = item[0]
            participating = self._extract_participating_operators(traj, op_ids)
            for op_id in participating:
                result[op_id].failures.append(item)

        # Attribute each success to all participating operators
        for item in success_batch:
            traj = item[0]
            participating = self._extract_participating_operators(traj, op_ids)
            for op_id in participating:
                result[op_id].successes.append(item)

        # Remove operators with empty batches
        return {
            op_id: batch for op_id, batch in result.items() if batch.failures or batch.successes
        }

    @staticmethod
    def _single_skill_badcase_keep(eval_case: EvaluatedCase, op_id: str) -> bool:
        """单 skill 场景 badcase 是否保留。

        解析 ``eval_case.reason`` JSON 的 ``attributed_skill``：填了且等于唯一
        op_id → 保留；留空(非 skill 失败)或归因到不存在的 skill → 丢(保守)。
        JSON 解析失败 / 字段缺失 → 视为无归因 → 丢。graceful，不抛异常。

        注：与 EDP ``_single_skill_badcase_match`` 的语义差——基类只精确匹配
        (``attributed_skill == op_id``)、只读 flat 格式，因基类场景不产 legacy
        ``skill_attributions`` 列表也不需要前缀/归一化模糊匹配。EDP 走宽松匹配
        是为了与多 skill 路径(``_match_operator_from_attribution``)一致。
        """
        if not eval_case.reason:
            return False
        try:
            reason_data = json.loads(eval_case.reason)
        except (json.JSONDecodeError, AttributeError, TypeError):
            return False
        if not isinstance(reason_data, dict):
            return False
        attributed_skill = reason_data.get("attributed_skill")
        if not isinstance(attributed_skill, str) or not attributed_skill:
            return False
        return attributed_skill == op_id

    @staticmethod
    def _extract_participating_operators(
        trajectory: Trajectory,
        valid_op_ids: list[str],
    ) -> list[str]:
        """Extract operator_ids from trajectory step metadata.

        Returns participating operators. Falls back to all valid operators
        (conservative) when no operator_id is found in any step.
        """
        valid_set = set(valid_op_ids)
        found: set[str] = set()
        for step in trajectory.steps:
            op_id = step.meta.get("operator_id")
            if op_id and op_id in valid_set:
                found.add(op_id)
        # Conservative fallback: if no operator_id found, attribute to all
        return list(found) if found else list(valid_op_ids)

    # ── Reflect ──────────────────────────────────────────────────────────

    async def _reflect(
        self,
        formatted_batch: str,
        skill_content: str,
        score_threshold: float,
        batch_data: list[tuple[Trajectory, EvaluatedCase, Case]] | None = None,
        operator_id: str = "",
    ) -> list[RawPatch]:
        """Analyze formatted trajectories, produce edit suggestions.

        Backward-compatible entry. Delegates to _reflect_for_operator().
        """
        if batch_data is not None:
            failure_batch = [item for item in batch_data if item[1].score < score_threshold]
            success_batch = [item for item in batch_data if item[1].score >= score_threshold]
            calls = []
            for source_type, source_batch in (
                ("failure", failure_batch),
                ("success", success_batch),
            ):
                if isinstance(getattr(self, "_llm", None), LLMInvocation):
                    formatted_batches = self._build_reflect_batches(
                        source_batch,
                        source_type=source_type,
                        skill_content=skill_content,
                        operator_id=operator_id,
                    )
                else:
                    # Compatibility for subclasses/tests constructed without the
                    # production run-scoped invocation module.
                    formatted_batches = [
                        self._format_batch(source_batch[start : start + self._minibatch_size])
                        for start in range(0, len(source_batch), self._minibatch_size)
                    ]
                for formatted in formatted_batches:
                    source_ids = tuple(
                        case.case_id
                        for trajectory, evaluated, case in source_batch
                        if f"(id={case.case_id})" in formatted
                    )
                    calls.append(
                        self._reflect_for_operator(
                            operator_id=operator_id,
                            formatted_failures=(formatted if source_type == "failure" else ""),
                            formatted_successes=(formatted if source_type == "success" else ""),
                            skill_content=skill_content,
                            source_ids=source_ids,
                        )
                    )
            if not calls:
                return []
            return [patch for patches in await asyncio.gather(*calls) for patch in patches]

        return await self._reflect_for_operator(
            operator_id=operator_id,
            formatted_failures=formatted_batch,
            formatted_successes=formatted_batch,
            skill_content=skill_content,
        )

    def _build_reflect_batches(
        self,
        source_batch: list[tuple[Trajectory, EvaluatedCase, Case]],
        *,
        source_type: str,
        skill_content: str,
        operator_id: str,
    ) -> list[str]:
        """Compact and greedily pack trajectories against the exact final prompt."""
        if not source_batch:
            return []
        template = "analyst_error" if source_type == "failure" else "analyst_success"
        step_context = self._format_step_buffer()
        meta_context = self._format_meta_skill_context()
        input_budget = self._llm.input_token_budget("reflect")
        empty_prompt = self._build_analyst_prompt(
            template, skill_content, "", step_context, meta_context
        )
        fixed_tokens = self._llm.estimate_messages((UserMessage(content=empty_prompt),))

        batches: list[str] = []
        current: list[str] = []
        for index, (trajectory, evaluated, case) in enumerate(source_batch, 1):
            header = (
                f"### Trajectory {index} (id={case.case_id})\n"
                f"Task: {_extract_task_description(case)}\n"
                f"Score: {evaluated.score:.2f}\n"
            )
            if evaluated.reason:
                header += f"Reason: {evaluated.reason}\n"
            header_tokens = self._llm.estimate_messages((UserMessage(content=header),))
            remaining = input_budget - fixed_tokens - header_tokens - 8
            if remaining < 1:
                required = fixed_tokens + header_tokens + 8
                overhead = self._llm.capabilities.context_window_tokens - input_budget
                raise PromptBudgetExceededError(
                    required + overhead, self._llm.capabilities.context_window_tokens
                )
            compacted = compact_trajectory(
                trajectory,
                policy=TrajectoryCompactionPolicy(
                    stage="reflect",
                    preserve_evaluation_reason=True,
                    prioritize_skill_related=bool(operator_id),
                ),
                context=TrajectoryCompactionContext(
                    task_goal=_extract_task_description(case),
                    evaluation_score=evaluated.score,
                    evaluation_reason=evaluated.reason or None,
                    target_skills=(operator_id,) if operator_id else (),
                ),
                # The shared compactor's fallback estimator is chars/2. Halving
                # the provider-space allowance remains safe for chars/token >= 1.
                token_budget=max(1, remaining // 2),
            )
            item = header + "\n" + compacted.text
            tentative = "\n\n---\n\n".join([*current, item])
            prompt = self._build_analyst_prompt(
                template, skill_content, tentative, step_context, meta_context
            )
            estimated = self._llm.estimate_messages((UserMessage(content=prompt),))
            if current and (len(current) >= self._minibatch_size or estimated > input_budget):
                batches.append("\n\n---\n\n".join(current))
                current = [item]
                prompt = self._build_analyst_prompt(
                    template, skill_content, item, step_context, meta_context
                )
                estimated = self._llm.estimate_messages((UserMessage(content=prompt),))
            else:
                current.append(item)
            if estimated > input_budget:
                overhead = self._llm.capabilities.context_window_tokens - input_budget
                raise PromptBudgetExceededError(
                    estimated + overhead, self._llm.capabilities.context_window_tokens
                )
        if current:
            batches.append("\n\n---\n\n".join(current))
        return batches

    async def _reflect_for_operator(
        self,
        *,
        operator_id: str,
        formatted_failures: str,
        formatted_successes: str,
        skill_content: str,
        source_ids: tuple[str, ...] = (),
    ) -> list[RawPatch]:
        """Run reflect analysts for a single operator, tag patches with operator_id."""
        if not formatted_failures.strip() and not formatted_successes.strip():
            return []

        step_buffer_ctx = self._format_step_buffer()
        meta_ctx = self._format_meta_skill_context()

        tasks: list[tuple[str, str, str]] = []

        if formatted_failures.strip():
            error_prompt = self._build_analyst_prompt(
                "analyst_error",
                skill_content,
                formatted_failures,
                step_buffer_ctx,
                meta_ctx,
            )
            error_retry = self._build_analyst_prompt(
                "analyst_error",
                skill_content,
                formatted_failures,
                step_buffer_ctx,
                meta_ctx,
                slim=True,
            )
            tasks.append(("failure", error_prompt, error_retry))

        if formatted_successes.strip():
            success_prompt = self._build_analyst_prompt(
                "analyst_success",
                skill_content,
                formatted_successes,
                step_buffer_ctx,
                meta_ctx,
            )
            success_retry = self._build_analyst_prompt(
                "analyst_success",
                skill_content,
                formatted_successes,
                step_buffer_ctx,
                meta_ctx,
                slim=True,
            )
            tasks.append(("success", success_prompt, success_retry))

        async def run_analyst(source_type: str, prompt: str, retry_prompt: str) -> RawPatch | None:
            structured_policy = (
                REFLECT_FAILURE_POLICY if source_type == "failure" else REFLECT_SUCCESS_POLICY
            )

            def parse(text: str) -> StructuredOutputResult:
                return parse_structured_output(
                    text,
                    policy=structured_policy,
                    validator=validate_reflect_output,
                )

            try:
                invocation = await invoke_with_retry(
                    self._llm,
                    self._model,
                    prompt,
                    policy=_REFLECT_POLICY,
                    stage="reflect",
                    retry_prompt=(
                        f"{retry_prompt}\n\n格式重试：只输出符合 {structured_policy.schema_name} "
                        'schema 的 JSON 对象：{"patch":{"reasoning":"...","edits":[]}}。'
                    ),
                    result_validator=lambda text: parse(text).data is not None,
                    result_error_classifier=lambda text: parse(text).error_category,
                    output_schema_name=structured_policy.schema_name,
                    context=LLMInvocationContext(
                        run_id="optimizer",
                        operator_id=operator_id or None,
                        epoch=getattr(self, "_current_epoch", None),
                        step=getattr(self, "_global_step", None),
                    ),
                )
                return self._parse_reflect_response(
                    invocation,
                    source_type,
                    operator_id=operator_id,
                    source_ids=source_ids,
                )
            except Exception as exc:
                logged = _log_failed_structured_invocation(
                    exc,
                    stage="reflect",
                    schema_name=structured_policy.schema_name,
                    fallback="discard_patch",
                    parse=parse,
                )
                if not logged:
                    logger.warning(
                        "[skill_doc_opt] reflect %s failed: %s",
                        source_type,
                        exc,
                    )
                return None

        results = await asyncio.gather(*[run_analyst(st, p, rp) for st, p, rp in tasks])

        raw_patches: list[RawPatch] = []
        for r in results:
            if r is not None:
                if operator_id:
                    r = replace(r, operator_id=operator_id)
                raw_patches.append(r)

        return raw_patches

    def _build_analyst_prompt(
        self,
        template_name: str,
        skill_content: str,
        trajectories_text: str,
        step_buffer_context: str,
        meta_skill_context: str,
        *,
        slim: bool = False,
    ) -> str:
        """Build the full prompt for an analyst LLM call.

        ``slim=True`` 时省略 step_buffer 与 meta_skill 章节，作为超时重试的
        精简 retry_prompt（B4 / #16），降低 retry 延迟与 token 成本。
        """
        system = load_skill_opt_prompt(template_name)
        user = f"## Current Skill\n{skill_content}\n\n"
        user += f"## Edits Budget\nProduce at most L={self._scheduler.max_lr} edits.\n\n"
        if not slim and step_buffer_context.strip():
            user += f"## Previous Steps in This Epoch\n{step_buffer_context}\n\n"
        if not slim and meta_skill_context.strip():
            user += f"## Optimizer Memory\n{meta_skill_context}\n\n"

        if "error" in template_name:
            user += f"## Failed Trajectories\n{trajectories_text}"
        else:
            user += f"## Successful Trajectories\n{trajectories_text}"

        return f"{system}\n\n{user}"

    def _parse_reflect_response(
        self,
        response: str | LLMInvocationResult,
        source_type: str,
        *,
        operator_id: str = "",
        source_ids: tuple[str, ...] = (),
    ) -> RawPatch | None:
        """Parse and validate an analyst LLM response into a RawPatch."""
        raw = response.text if isinstance(response, LLMInvocationResult) else response
        structured_policy = (
            REFLECT_FAILURE_POLICY if source_type == "failure" else REFLECT_SUCCESS_POLICY
        )
        extraction = parse_structured_output(
            raw,
            policy=structured_policy,
            validator=validate_reflect_output,
        )
        result = extraction.data
        if result is not None:
            log_structured_output(
                extraction,
                stage="reflect",
                schema_name=structured_policy.schema_name,
                invocation_id=(
                    response.invocation_id
                    if isinstance(response, LLMInvocationResult)
                    else "unknown"
                ),
                attempt=(
                    response.metadata.get("attempt", "unknown")
                    if isinstance(response, LLMInvocationResult)
                    else "unknown"
                ),
                finish_reason=(
                    response.finish_reason or "unknown"
                    if isinstance(response, LLMInvocationResult)
                    else "unknown"
                ),
                transport_complete=(
                    response.transport_complete
                    if isinstance(response, LLMInvocationResult)
                    else "unknown"
                ),
            )
        if result is None:
            logger.warning(
                "structured output failed stage=reflect schema_name=%s source_type=%s "
                "operator=%s invocation_id=%s attempt=%s parse_mode=%s "
                "repair_operations=%s finish_reason=%s transport_complete=%s "
                "fallback=discard_patch category=%s raw_response=%s",
                structured_policy.schema_name,
                source_type,
                operator_id or "unknown",
                response.invocation_id if isinstance(response, LLMInvocationResult) else "unknown",
                response.metadata.get("attempt", "unknown")
                if isinstance(response, LLMInvocationResult)
                else "unknown",
                extraction.parse_mode,
                extraction.repair_operations,
                response.finish_reason if isinstance(response, LLMInvocationResult) else "unknown",
                response.transport_complete
                if isinstance(response, LLMInvocationResult)
                else "unknown",
                extraction.error_category or "unknown",
                json.dumps(raw, ensure_ascii=False),
            )
            return None

        # Extract patch from response
        patch_data = reflect_patch_data(result)
        edits_data = patch_data["edits"]
        assert isinstance(edits_data, list)

        # R1 validation: filter to valid edits
        valid_edits: list[Edit] = []
        for ed in valid_edit_items(edits_data):
            op = ed["op"]
            assert op in _VALID_OPS
            valid_edits.append(
                Edit(
                    op=op,
                    content=str(ed.get("content", "")),
                    target=str(ed.get("target", "")),
                    support_count=_safe_int(ed.get("support_count")),
                    source_type=source_type,
                    source_ids=source_ids,
                )
            )

        if not valid_edits:
            # Empty patch is a valid sentinel (no changes suggested)
            return RawPatch(
                patch=Patch(edits=[], reasoning="no valid edits"),
                source_type=source_type,
                failure_summary=str(result.get("failure_summary", "")),
                repaired=extraction.parse_mode not in {"exact", "failed"},
                parse_mode=extraction.parse_mode,
                repair_operations=tuple(
                    asdict(operation) for operation in extraction.repair_operations
                ),
                source_ids=source_ids,
            )

        reasoning = str(result.get("reasoning", patch_data.get("reasoning", "")))
        failure_summary = str(result.get("failure_summary", ""))

        return RawPatch(
            patch=Patch(edits=valid_edits, reasoning=reasoning),
            source_type=source_type,
            failure_summary=failure_summary,
            repaired=extraction.parse_mode not in {"exact", "failed"},
            parse_mode=extraction.parse_mode,
            repair_operations=tuple(
                asdict(operation) for operation in extraction.repair_operations
            ),
            source_ids=source_ids,
        )

    # ── Aggregate ────────────────────────────────────────────────────────

    async def _aggregate(
        self,
        patches: list[RawPatch],
        skill_content: str,
    ) -> Patch:
        """Merge patches from multiple minibatches.

        Three-stage LLM merge: failure -> success -> final.
        P5: <=3 patches use rule-based dedup (skip LLM).
        Fallback: simple concatenation on LLM merge failure.
        """
        if not patches:
            return Patch(edits=[], reasoning="no patches")

        failure_patches = [p for p in patches if p.source_type == "failure"]
        success_patches = [p for p in patches if p.source_type == "success"]

        f_edits = [e for p in failure_patches for e in p.patch.edits]
        s_edits = [e for p in success_patches for e in p.patch.edits]

        all_edits = f_edits + s_edits
        total = len(all_edits)

        # P5: small number of patches -> rule-based dedup
        if total <= 3:
            deduped = self._rule_dedup_edits(all_edits)
            return Patch(edits=deduped, reasoning="rule-based dedup (<=3 edits)")

        # Three-stage LLM merge
        meta_ctx = self._format_meta_skill_context()

        # Stage 1 + Stage 2: merge failure / success patches concurrently (C3 / #7).
        # 两者最终由 run-scoped LLMInvocation 约束总并发；Stage 3 依赖 1+2
        # 结果，仍串行在之后。
        async def _merge_failure() -> list[Edit]:
            if len(f_edits) > 1:
                return await self._bounded_merge_edits(
                    f_edits, "merge_failure", skill_content, meta_ctx
                )
            return f_edits

        async def _merge_success() -> list[Edit]:
            if len(s_edits) > 1:
                return await self._bounded_merge_edits(
                    s_edits, "merge_success", skill_content, meta_ctx
                )
            return s_edits

        failure_merged, success_merged = await asyncio.gather(_merge_failure(), _merge_success())

        # Stage 3: final merge
        combined = failure_merged + success_merged
        if not combined:
            return Patch(edits=[], reasoning="no edits after merge")

        if len(combined) <= 3:
            return Patch(edits=combined, reasoning="final: <=3 edits after stages")

        final_edits = await self._bounded_merge_edits(
            combined,
            "merge_final",
            skill_content,
            meta_ctx,
        )
        return Patch(edits=final_edits, reasoning="three-stage LLM merge")

    async def _bounded_merge_edits(
        self,
        edits: list[Edit],
        template_name: str,
        skill_content: str,
        meta_skill_context: str,
        *,
        depth: int = 0,
    ) -> list[Edit]:
        """Merge stable token-bounded groups, then recursively merge their outputs."""
        groups = self._pack_merge_groups(edits, template_name, skill_content, meta_skill_context)
        merged_groups = await asyncio.gather(
            *[
                self._llm_merge_edits(group, template_name, skill_content, meta_skill_context)
                if len(group) > 1
                else asyncio.sleep(0, result=group)
                for group in groups
            ]
        )
        merged = [edit for group in merged_groups for edit in group]
        if len(groups) > 1 and len(merged) < len(edits) and depth < 8:
            return await self._bounded_merge_edits(
                merged,
                template_name,
                skill_content,
                meta_skill_context,
                depth=depth + 1,
            )
        return merged

    def _pack_merge_groups(
        self,
        edits: list[Edit],
        template_name: str,
        skill_content: str,
        meta_skill_context: str,
    ) -> list[list[Edit]]:
        """Greedily group edits while each exact merge prompt fits its input budget."""
        if not edits:
            return []
        if not isinstance(getattr(self, "_llm", None), LLMInvocation):
            return [edits]
        input_budget = self._llm.input_token_budget("merge")
        groups: list[list[Edit]] = []
        current: list[Edit] = []
        for edit in edits:
            tentative = [*current, edit]
            prompt, _ = self._build_merge_prompts(
                tentative, template_name, skill_content, meta_skill_context
            )
            estimated = self._llm.estimate_messages((UserMessage(content=prompt),))
            if current and estimated > input_budget:
                groups.append(current)
                current = [edit]
            else:
                current = tentative
        if current:
            groups.append(current)
        return groups

    def _build_merge_prompts(
        self,
        edits: list[Edit],
        template_name: str,
        skill_content: str,
        meta_skill_context: str,
    ) -> tuple[str, str]:
        edits_dicts = [
            {
                "op": edit.op,
                "content": edit.content,
                "target": edit.target,
                "support_count": edit.support_count,
                "source_type": edit.source_type,
                "source_ids": list(edit.source_ids),
            }
            for edit in edits
        ]
        system = load_skill_opt_prompt(template_name)
        user = f"## Current Skill\n{skill_content}\n\n"
        if meta_skill_context.strip():
            user += f"## Optimizer Memory\n{meta_skill_context}\n\n"
        edits_json = _dumps_for_prompt(edits_dicts)
        user += f"## Edits to merge ({len(edits)} total)\n{edits_json}"
        slim_user = f"## Current Skill\n{skill_content}\n\n"
        slim_user += f"## Edits to merge ({len(edits)} total)\n{edits_json}"
        return f"{system}\n\n{user}", f"{system}\n\n{slim_user}"

    async def _llm_merge_edits(
        self,
        edits: list[Edit],
        template_name: str,
        skill_content: str,
        meta_skill_context: str,
    ) -> list[Edit]:
        """Call LLM to merge edits, with fallback to concatenation."""
        prompt, retry_prompt = self._build_merge_prompts(
            edits, template_name, skill_content, meta_skill_context
        )
        structured_policy_by_template = {
            "merge_failure": MERGE_FAILURE_POLICY,
            "merge_success": MERGE_SUCCESS_POLICY,
            "merge_final": MERGE_FINAL_POLICY,
        }
        structured_policy = structured_policy_by_template[template_name]

        def parse(text: str) -> StructuredOutputResult:
            return parse_structured_output(
                text,
                policy=structured_policy,
                validator=validate_merge_output,
            )

        try:
            invocation = await invoke_with_retry(
                self._llm,
                self._model,
                prompt,
                policy=_AGGREGATE_POLICY,
                stage="merge",
                retry_prompt=(
                    f"{retry_prompt}\n\n格式重试：只输出符合 {structured_policy.schema_name} "
                    'schema 的 JSON 对象：{"reasoning":"...","edits":[]}。'
                ),
                result_validator=lambda text: parse(text).data is not None,
                result_error_classifier=lambda text: parse(text).error_category,
                output_schema_name=structured_policy.schema_name,
                context=LLMInvocationContext(
                    run_id="optimizer",
                    epoch=getattr(self, "_current_epoch", None),
                    step=getattr(self, "_global_step", None),
                ),
            )
            raw = invocation.text
            extraction = parse(raw)
            result = extraction.data
            if result is not None:
                log_structured_output(
                    extraction,
                    stage="merge",
                    schema_name=structured_policy.schema_name,
                    invocation_id=invocation.invocation_id,
                    attempt=invocation.metadata.get("attempt", "unknown"),
                    finish_reason=invocation.finish_reason or "unknown",
                    transport_complete=invocation.transport_complete,
                )
            if result is not None:
                merged = []
                input_source_ids = tuple(
                    dict.fromkeys(source_id for edit in edits for source_id in edit.source_ids)
                )
                edits_data = result["edits"]
                assert isinstance(edits_data, list)
                for ed in valid_edit_items(edits_data):
                    op = ed["op"]
                    assert op in _VALID_OPS
                    merged.append(
                        Edit(
                            op=op,
                            content=str(ed.get("content", "")),
                            target=str(ed.get("target", "")),
                            support_count=_safe_int(ed.get("support_count")),
                            source_type=str(ed.get("source_type", "failure")),
                            source_ids=_string_tuple(
                                ed.get("source_ids", input_source_ids),
                                input_source_ids,
                            ),
                        )
                    )
                if merged:
                    retained = {source_id for edit in merged for source_id in edit.source_ids}
                    missing = tuple(
                        source_id for source_id in input_source_ids if source_id not in retained
                    )
                    if missing:
                        merged[0] = replace(merged[0], source_ids=(*merged[0].source_ids, *missing))
                    return merged
            if result is None:
                logger.warning(
                    "structured output failed stage=merge schema_name=%s invocation_id=%s "
                    "attempt=%s parse_mode=%s repair_operations=%s finish_reason=%s "
                    "transport_complete=%s fallback=original_edits category=%s raw_response=%s",
                    structured_policy.schema_name,
                    invocation.invocation_id,
                    invocation.metadata.get("attempt", "unknown"),
                    extraction.parse_mode,
                    extraction.repair_operations,
                    invocation.finish_reason or "unknown",
                    invocation.transport_complete,
                    extraction.error_category or "unknown",
                    json.dumps(raw, ensure_ascii=False),
                )
        except Exception as exc:
            logged = _log_failed_structured_invocation(
                exc,
                stage="merge",
                schema_name=structured_policy.schema_name,
                fallback="original_edits",
                parse=parse,
            )
            if not logged:
                logger.warning(
                    "[skill_doc_opt] aggregate %s failed, using fallback: %s",
                    template_name,
                    exc,
                )

        # Fallback: return original edits unchanged
        return edits

    @staticmethod
    def _rule_dedup_edits(edits: list[Edit]) -> list[Edit]:
        """Rule-based dedup for small edit sets (no LLM needed)."""
        seen: set[tuple[str, str, str]] = set()
        deduped: list[Edit] = []
        for e in edits:
            key = (e.op, e.content, e.target)
            if key not in seen:
                seen.add(key)
                deduped.append(e)
        return deduped

    # ── Select ───────────────────────────────────────────────────────────

    async def _select(
        self,
        edits: list[Edit],
        budget: int,
        skill_content: str,
    ) -> list[Edit]:
        """Rank edits and select top-k within budget.

        If edits <= budget, return unchanged. Otherwise use LLM ranking.
        """
        if len(edits) <= budget:
            return edits

        meta_ctx = self._format_meta_skill_context()

        # Build edit pool description
        edits_desc = []
        for i, edit in enumerate(edits):
            desc = f"[{i}] op={edit.op}"
            if edit.target:
                desc += f"  target={edit.target!r}"
            desc += f"  content={edit.content[:200]!r}"
            edits_desc.append(desc)

        system = load_skill_opt_prompt("ranking")
        user = f"## Current Skill\n{skill_content}\n\n"
        if meta_ctx.strip():
            user += f"## Optimizer Memory\n{meta_ctx}\n\n"
        user += (
            f"## Edits Pool ({len(edits)} edits, budget={budget})\n"
            + "\n".join(edits_desc)
            + f"\n\nSelect the {budget} most important edits. "
            f"Return their 0-based indices in priority order."
        )
        prompt = f"{system}\n\n{user}"
        # B4 (#16): 精简 retry_prompt 去掉 Optimizer Memory，超时重试用，
        # 与 _aggregate 一致——降低 retry 延迟与 token 成本。
        slim_user = f"## Current Skill\n{skill_content}\n\n"
        slim_user += (
            f"## Edits Pool ({len(edits)} edits, budget={budget})\n"
            + "\n".join(edits_desc)
            + f"\n\nSelect the {budget} most important edits. "
            f"Return their 0-based indices in priority order."
        )
        retry_prompt = f"{system}\n\n{slim_user}"

        def parse(text: str) -> StructuredOutputResult:
            return parse_structured_output(
                text,
                policy=RANKING_POLICY,
                validator=validate_ranking_output,
            )

        try:
            invocation = await invoke_with_retry(
                self._llm,
                self._model,
                prompt,
                policy=_RANKING_POLICY,
                stage="ranking",
                retry_prompt=(f'{retry_prompt}\n仅输出 ranking JSON：{{"selected_indices":[]}}'),
                result_validator=lambda text: parse(text).data is not None,
                result_error_classifier=lambda text: parse(text).error_category,
                output_schema_name=RANKING_POLICY.schema_name,
                context=LLMInvocationContext(
                    run_id="optimizer",
                    epoch=getattr(self, "_current_epoch", None),
                    step=getattr(self, "_global_step", None),
                ),
            )
            raw = invocation.text
            extraction = parse(raw)
            result = extraction.data
            if result is not None:
                log_structured_output(
                    extraction,
                    stage="ranking",
                    schema_name=RANKING_POLICY.schema_name,
                    invocation_id=invocation.invocation_id,
                    attempt=invocation.metadata.get("attempt", "unknown"),
                    finish_reason=invocation.finish_reason or "unknown",
                    transport_complete=invocation.transport_complete,
                )
            if result is not None:
                indices = result["selected_indices"]
                assert isinstance(indices, list)
                selected = [
                    edits[index]
                    for index in valid_selected_indices(
                        indices,
                        pool_size=len(edits),
                        budget=budget,
                    )
                ]
                if selected:
                    return selected
            if result is None:
                logger.warning(
                    "structured output failed stage=ranking schema_name=ranking invocation_id=%s "
                    "attempt=%s parse_mode=%s repair_operations=%s finish_reason=%s "
                    "transport_complete=%s fallback=stable_truncation category=%s raw_response=%s",
                    invocation.invocation_id,
                    invocation.metadata.get("attempt", "unknown"),
                    extraction.parse_mode,
                    extraction.repair_operations,
                    invocation.finish_reason or "unknown",
                    invocation.transport_complete,
                    extraction.error_category or "unknown",
                    json.dumps(raw, ensure_ascii=False),
                )
        except Exception as exc:
            logged = _log_failed_structured_invocation(
                exc,
                stage="ranking",
                schema_name=RANKING_POLICY.schema_name,
                fallback="stable_truncation",
                parse=parse,
            )
            if not logged:
                logger.warning(
                    "[skill_doc_opt] select ranking failed, fallback truncation: %s",
                    exc,
                )

        # Fallback: simple truncation
        return edits[:budget]

    # ── Apply hook (per step) ───────────────────────────────────────────

    def _on_step_apply(self, step: int, n_edits: int, n_operators: int) -> None:
        """Hook fired after each step's per-operator apply phase.

        Default no-op. Scenario subclasses (e.g. EDPAgentOptimizer) override
        to push SSE/observability events. ``n_edits`` is the number of edits
        selected+applied in THIS step only (summed across operators), so
        multi-step epochs report each step's edits instead of only the last
        step's (the previous behavior lost earlier steps' edits because
        ``_ranked_patch_by_operator`` is overwritten each step).
        """

    # ── _backward: the full epoch orchestrator ───────────────────────────

    async def _backward(self, signals: list[Any]) -> None:
        """Full epoch: rollout -> attribute -> reflect -> aggregate -> select -> apply.

        Called by SingleDimUpdater.process() -> BaseOptimizer.backward().
        signals is empty (requires_forward_data=False), ignored.

        Per-operator: reads skills from all bound operators, attributes
        failures/successes per operator, and runs reflect/aggregate/select/apply
        independently for each operator.
        """
        self._raise_if_cancellation_requested()
        # Read current skills from ALL bound operators
        self._artifact_epoch += 1
        artifact_epoch = self._artifact_epoch
        self._current_epoch = self._artifact_epoch
        self._current_skill_by_operator = self._read_skills_from_operators()
        self._epoch_base_skill_by_operator = dict(self._current_skill_by_operator)
        self._last_candidate_skill_by_operator = {}
        self._ranked_patch_by_operator = {}
        self._curr_epoch_comparison.clear()

        # Backward compat: set old single-value fields
        self._current_skill_content = next(iter(self._current_skill_by_operator.values()), "")
        self._epoch_base_skill_content = self._current_skill_content
        self._last_candidate_skill_content = ""

        self._artifact_exporter.export_skill_snapshot(
            artifact_epoch,
            0,
            self._epoch_base_skill_content,
            "before",
        )

        for step in range(self._steps_per_epoch):
            self._raise_if_cancellation_requested()
            self._global_step += 1
            patches_by_operator: dict[str, list[RawPatch]] = {
                op_id: [] for op_id in self._operators
            }
            step_before_skill = self._current_skill_content
            step_eval_results: list[EvaluatedCase] = []
            step_evaluation_outcomes: list[EvaluationOutcome] = []
            step_trajectories: list[Trajectory] = []
            step_cases: list[Case] = []
            step_case_count = 0

            # Accumulation loop
            for a in range(self._accumulation):
                try:
                    self._raise_if_cancellation_requested()
                    batch_cases = self._sample_cases(
                        self._batch_size,
                        seed=self._global_step * 100 + a,
                    )

                    # 1. Rollout (uses all skills — agent has all operators bound)
                    rollout_started = time.perf_counter()
                    self._last_training_batch = None
                    batch_evaluated, rollout_trajectories = await self._rollout(
                        cases=batch_cases,
                    )
                    self._raise_if_cancellation_requested()
                    if self._last_training_batch is not None:
                        step_evaluation_outcomes.extend(self._last_training_batch.outcomes)
                    logger.info(
                        "[timing] train.rollout epoch=%d step=%d accumulation=%d "
                        "cases=%d num_parallel=%d elapsed=%.3fs",
                        self._current_epoch + 1,
                        step + 1,
                        a + 1,
                        len(batch_cases),
                        self._num_parallel,
                        time.perf_counter() - rollout_started,
                    )
                    trajectories_by_case_id = {
                        case.case_id: trajectory
                        for case, trajectory in zip(batch_cases, rollout_trajectories)
                    }
                    cases_by_id = {case.case_id: case for case in batch_cases}
                    batch_data = []
                    for eval_case in batch_evaluated:
                        evaluated_case_id = eval_case.case.case_id
                        case = cases_by_id.get(evaluated_case_id)
                        trajectory = trajectories_by_case_id.get(evaluated_case_id)
                        if case is None or trajectory is None:
                            logger.warning(
                                "[skill_doc_opt] evaluation identity missing case=%s",
                                evaluated_case_id,
                            )
                            continue
                        batch_data.append((trajectory, eval_case, case))

                    step_eval_results.extend(item[1] for item in batch_data)
                    step_trajectories.extend(item[0] for item in batch_data)
                    step_cases.extend(item[2] for item in batch_data)
                    step_case_count += len(batch_cases)

                    # 2. Split failures/successes
                    failure_batch = [
                        item for item in batch_data if item[1].score < self._score_threshold
                    ]
                    success_batch = [
                        item for item in batch_data if item[1].score >= self._score_threshold
                    ]

                    # 3. Attribute to operators
                    attribute_started = time.perf_counter()
                    attributed = await self._attribute(
                        failure_batch=failure_batch,
                        success_batch=success_batch,
                        skill_contents=self._current_skill_by_operator,
                    )
                    self._raise_if_cancellation_requested()
                    logger.info(
                        "[timing] train.attribute epoch=%d step=%d accumulation=%d "
                        "operators=%d elapsed=%.3fs",
                        self._current_epoch + 1,
                        step + 1,
                        a + 1,
                        len(self._operators),
                        time.perf_counter() - attribute_started,
                    )

                    # 4. Per-operator reflect — cross-operator concurrent (C2 / #3)
                    round_patches = await self._reflect_all_operators(
                        attributed, step=step, accumulation=a
                    )
                    self._raise_if_cancellation_requested()
                    for op_id, valid_patches in round_patches.items():
                        patches_by_operator[op_id].extend(valid_patches)

                    # Track comparison pairs for slow_update (last step only)
                    if step == self._steps_per_epoch - 1:
                        for _trajectory, eval_case, case in batch_data:
                            self._curr_epoch_comparison.append(
                                {
                                    "case_id": case.case_id,
                                    "curr_score": eval_case.score,
                                    "curr_reason": eval_case.reason,
                                }
                            )
                except CancellationRequestedError:
                    raise
                except Exception as exc:
                    logger.warning(
                        "[skill_doc_opt] accumulation round %d/%d in step %d failed: %s",
                        a + 1,
                        self._accumulation,
                        step + 1,
                        exc,
                    )
                    continue

            # Collect all patches for artifact export
            all_patches: list[RawPatch] = []
            for patches in patches_by_operator.values():
                all_patches.extend(patches)

            self._artifact_exporter.export_trajectories(
                artifact_epoch,
                step,
                step_trajectories,
                step_eval_results,
            )
            self._artifact_exporter.export_eval_results(
                artifact_epoch,
                step,
                step_eval_results,
                step_cases,
                outcomes=step_evaluation_outcomes or None,
            )
            self._artifact_exporter.export_raw_patches(
                artifact_epoch,
                step,
                0,
                all_patches,
            )

            # 5. Per-operator aggregate → select → apply — cross-operator
            #    concurrent (C4 / #19). 单 operator 输出与顺序一致。
            budget = self._scheduler.step()
            self._raise_if_cancellation_requested()
            (
                n_merged_edits_by_operator,
                n_selected_edits_by_operator,
                last_merged,
                last_selected,
            ) = await self._aggregate_select_apply_all_operators(
                patches_by_operator, budget=budget, step=step, artifact_epoch=artifact_epoch
            )

            # Per-step apply phase event. _ranked_patch_by_operator currently
            # holds THIS step's ranked patches (set above per operator), so
            # summing gives the step's applied edits — not the epoch's. The
            # hook fires once per step; downstream accumulators (e.g. SSE
            # edits_applied) sum across steps for the correct epoch total.
            n_edits_this_step = sum(
                len(patch.edits) for patch in self._ranked_patch_by_operator.values()
            )
            self._on_step_apply(step, n_edits_this_step, len(self._operators))

            # Backward compat: set old single-value fields from last operator
            self._ranked_patch = Patch(
                edits=last_selected,
                reasoning=last_merged.reasoning,
            )
            self._current_skill_content = next(iter(self._current_skill_by_operator.values()), "")

            self._artifact_exporter.export_skill_snapshot(
                artifact_epoch,
                step,
                self._current_skill_content,
                "after",
            )
            self._artifact_exporter.export_skill_diff(
                artifact_epoch,
                step,
                step_before_skill,
                self._current_skill_content,
            )
            scores = [case.score for case in step_eval_results]
            self._artifact_exporter.export_metrics(
                artifact_epoch,
                step,
                {
                    "global_step": self._global_step,
                    "step": step,
                    "n_cases": step_case_count,
                    "n_raw_patches": len(all_patches),
                    "n_merged_edits": sum(n_merged_edits_by_operator.values()),
                    "n_selected_edits": sum(n_selected_edits_by_operator.values()),
                    "n_merged_edits_by_operator": n_merged_edits_by_operator,
                    "n_selected_edits_by_operator": n_selected_edits_by_operator,
                    "avg_score": sum(scores) / len(scores) if scores else 0.0,
                },
            )

            # Record step buffer entry
            self._step_buffer.append(self._build_step_buffer_entry(step))

        # Store final skill for _step() candidate generation
        self._last_candidate_skill_by_operator = dict(self._current_skill_by_operator)
        self._last_candidate_skill_content = self._current_skill_content
        for op_id, param in self._parameters.items():
            skill = self._current_skill_by_operator.get(op_id, "")
            if skill:
                param.set_gradient(SKILL_CONTENT_TARGET, skill)

    # ── _step: return base/candidate for Trainer gate ────────────────────

    def _step(
        self,
    ) -> list[dict[tuple[str, str], Any]]:
        """Return per-operator base/candidate updates for validation selection.

        R3: base == candidate -> only return base (skip redundant validation).
        Unchanged operators appear in base only (candidate omits them).
        Reads from per-operator dicts (set by _backward) with fallback to
        parameter gradients for backward compatibility.
        """
        base_update: dict[tuple[str, str], Any] = {}
        candidate_update: dict[tuple[str, str], Any] = {}

        for op_id in self._operators:
            # Get current skill: prefer per-operator dict, fallback to gradient
            current_skill = self._current_skill_by_operator.get(op_id, "")
            if not current_skill:
                param = self._parameters.get(op_id)
                if param:
                    current_skill = param.get_gradient(SKILL_CONTENT_TARGET) or ""

            # Get base skill: prefer per-operator dict, fallback to old field
            base_skill = self._epoch_base_skill_by_operator.get(op_id, "")
            if not base_skill and not self._epoch_base_skill_by_operator:
                base_skill = self._epoch_base_skill_content

            if not current_skill:
                continue

            base_update[(op_id, SKILL_CONTENT_TARGET)] = base_skill

            if current_skill != base_skill:
                candidate_update[(op_id, SKILL_CONTENT_TARGET)] = current_skill

        if not candidate_update:
            # R3: no changes, only return base (skip validation)
            if base_update:
                return [base_update]
            return []

        return [base_update, base_update | candidate_update]

    # ── RawPatch routing validation ──────────────────────────────────────

    def _validate_raw_patch_operator_id(
        self, patches: list[RawPatch], valid_operator_ids: set[str]
    ) -> list[RawPatch]:
        """Filter out patches with missing or unknown operator_id.

        Single operator: auto-fill operator_id with sole operator.
        Multi operator: discard + warning for missing/unknown operator_id.
        """
        if len(valid_operator_ids) == 1:
            sole_id = next(iter(valid_operator_ids))

            valid: list[RawPatch] = []
            for p in patches:
                if not p.operator_id:
                    valid.append(replace(p, operator_id=sole_id))
                    continue
                if p.operator_id != sole_id:
                    logger.warning(
                        "[skill_doc_opt] discarding RawPatch with unknown operator_id: %s",
                        p.operator_id,
                    )
                    continue
                valid.append(p)
            return valid

        valid_rest: list[RawPatch] = []
        for p in patches:
            if not p.operator_id:
                logger.warning("[skill_doc_opt] discarding RawPatch with empty operator_id")
                continue
            if p.operator_id not in valid_operator_ids:
                logger.warning(
                    "[skill_doc_opt] discarding RawPatch with unknown operator_id: %s",
                    p.operator_id,
                )
                continue
            valid_rest.append(p)
        return valid_rest

    # ── Skill document I/O ───────────────────────────────────────────────

    def _llm_skill_view(self, content: str) -> str:
        """Return the skill view exposed to LLM prompts (reflection input).

        When ``preserve_frontmatter`` is True (default), strip YAML frontmatter
        so the LLM only reasons over the markdown body — avoiding token waste
        and edits targeting frontmatter (which are silently dropped on
        writeback by ``FrontmatterPreservingSkillDocumentOperator``). When
        False, return the full document so frontmatter participates in
        optimization.

        This is the *only* seam that strips frontmatter; writeback,
        snapshot, diff, and ``_current_skill_by_operator`` all keep the full
        document (apply/slow_update write-back invariant).
        """
        if not self._preserve_frontmatter:
            return content
        _, body = split_frontmatter(content)
        return body

    def _read_skill_from_operator(self) -> str:
        for op in self._operators.values():
            state = op.get_state()
            return cast(str, state.get("skill_content", ""))
        return ""

    def _sync_skill_to_operator(self, skill_content: str) -> None:
        """Make intermediate skill visible to agent before next rollout."""
        for op_id, op in self._operators.items():
            op.set_parameter(SKILL_CONTENT_TARGET, skill_content)
            # spec F6: normalize 可能改写 candidate，reread 回填 cache，
            # 使 cache/operator/remote 一致；普通 operator 原样存即 no-op。
            self._current_skill_by_operator[op_id] = cast(
                str, op.get_state().get("skill_content", "")
            )

    def _read_skills_from_operators(self) -> dict[str, str]:
        """Read skill_content from each bound operator."""
        skills: dict[str, str] = {}
        for op_id, op in self._operators.items():
            state = op.get_state()
            skills[op_id] = state.get("skill_content", "")
        return skills

    def _sync_skill_to_operator_by_id(self, operator_id: str, skill_content: str) -> None:
        """Sync one operator's skill content."""
        op = self._operators.get(operator_id)
        if op is not None:
            op.set_parameter(SKILL_CONTENT_TARGET, skill_content)
            # spec F6: normalize 可能改写 candidate，reread 回填 cache，
            # 使 cache/operator/remote 一致；普通 operator 原样存即 no-op。
            self._current_skill_by_operator[operator_id] = cast(
                str, op.get_state().get("skill_content", "")
            )

    def _sync_skills_to_operators(self, skills: dict[str, str]) -> None:
        """Sync all operators' skill content at once."""
        for op_id, content in skills.items():
            self._sync_skill_to_operator_by_id(op_id, content)

    # ── Step buffer ──────────────────────────────────────────────────────

    def _build_step_buffer_entry(self, step: int) -> dict[str, Any]:
        n_edits_by_operator = {
            op_id: len(patch.edits) for op_id, patch in self._ranked_patch_by_operator.items()
        }
        return {
            "step": self._global_step,
            "n_edits": sum(n_edits_by_operator.values())
            if n_edits_by_operator
            else len(self._ranked_patch.edits)
            if self._ranked_patch
            else 0,
            "n_edits_by_operator": n_edits_by_operator,
            "failure_patterns": self._extract_failure_patterns(),
            "rejected_edits": self._extract_rejected_edits(),
        }

    def _extract_failure_patterns(self) -> list[str]:
        """Extract common failure patterns from the current step."""
        if not self._ranked_patch:
            return []
        return [e.content[:100] for e in self._ranked_patch.edits if e.source_type == "failure"][:3]

    def _extract_rejected_edits(self) -> list[str]:
        """Extract edits rejected by the ranking stage."""
        # For now, return empty — ranking rejection details
        # are available when _select tracks them.
        return []

    def _format_step_buffer(self) -> str:
        if not self._step_buffer:
            return ""
        lines = []
        for entry in self._step_buffer:
            lines.append(f"Step {entry['step']}: {entry['n_edits']} edits applied")
            if entry.get("failure_patterns"):
                lines.append(f"  Failure patterns: {entry['failure_patterns']}")
            if entry.get("rejected_edits"):
                lines.append(f"  Rejected edits: {entry['rejected_edits']}")
        return "\n".join(lines)

    # ── Meta skill context ───────────────────────────────────────────────

    def _format_meta_skill_context(self) -> str:
        if not self._meta_skill_context:
            return ""
        return self._meta_skill_context

    @staticmethod
    def _format_operator_skills(skills: dict[str, str]) -> str:
        """Format per-operator skills as one global meta-skill context."""
        sections = []
        for op_id, skill in skills.items():
            sections.append(f"### Operator: {op_id}\n```markdown\n{skill}\n```")
        return "\n\n".join(sections)

    @staticmethod
    def _mean_eval_score(eval_results: list[EvaluatedCase]) -> float | None:
        if not eval_results:
            return None
        return cast(float, sum(result.score for result in eval_results) / len(eval_results))

    def _infer_gate_decision(self) -> str:
        """Infer gate decision by comparing per-operator current vs base/candidate skills.

        Returns 'base' if ALL operators reverted to base,
        'candidate' if ALL operators match candidate,
        'unknown' otherwise (mixed or ambiguous).
        """
        # Single-operator fallback when per-operator dicts are not populated
        if not self._epoch_base_skill_by_operator:
            if (
                self._epoch_base_skill_content
                and self._current_skill_content == self._epoch_base_skill_content
            ):
                return "base"
            if (
                self._last_candidate_skill_content
                and self._current_skill_content == self._last_candidate_skill_content
            ):
                return "candidate"
            return "unknown"

        all_base = all(
            self._current_skill_by_operator.get(op_id, "") == base_skill
            for op_id, base_skill in self._epoch_base_skill_by_operator.items()
        )
        all_candidate = bool(self._last_candidate_skill_by_operator) and all(
            self._current_skill_by_operator.get(op_id, "") == cand_skill
            for op_id, cand_skill in self._last_candidate_skill_by_operator.items()
        )

        if all_base:
            return "base"
        if all_candidate:
            return "candidate"
        return "unknown"

    # ── Epoch-level: run_epoch_end ───────────────────────────────────────

    def export_validation_failure(self, failure: ValidationCoverageFailureInput) -> None:
        """Export fail-closed diagnostics at the optimizer-owned artifact epoch."""
        artifact_epoch = getattr(self, "_artifact_epoch", -1)
        self._artifact_exporter.export_validation_failure(artifact_epoch, failure)

    async def run_epoch_end(
        self,
        trainer_epoch: int,
        val_results: list[EvaluatedCase] | None = None,
        artifact_input: GateEpochArtifactInput | None = None,
    ) -> None:
        """Called by SkillDocumentCallbacks.on_train_epoch_end().

        slow_update modifies skill_content in-place (force-inject into markers).
        meta_skill only updates optimizer-internal state.
        """
        epoch_end_started = time.perf_counter()
        if self._operators:
            self._current_skill_by_operator = self._read_skills_from_operators()
            self._current_skill_content = next(iter(self._current_skill_by_operator.values()), "")
        artifact_epoch = getattr(self, "_artifact_epoch", -1)
        if artifact_input is not None:
            self._artifact_exporter.export_gate_result(
                artifact_epoch,
                gate=artifact_input.gate,
                selected_batch=artifact_input.selected_batch,
            )
            self._artifact_exporter.export_validation(
                artifact_epoch,
                artifact_input.selected_batch,
                artifact_input.gate,
            )
        else:
            # Direct unit-level calls without a Trainer gate retain diagnostics only.
            selected_score = self._mean_eval_score(val_results or [])
            decision = self._infer_gate_decision()
            self._artifact_exporter.export_gate_result(
                artifact_epoch,
                base_score=selected_score if decision == "base" else None,
                candidate_score=selected_score if decision == "candidate" else None,
                decision=decision,
            )

        # A7: 计算一次 comparison_text，复用给 slow_update + meta_skill（原各调一次）。
        comparison_text = ""
        if trainer_epoch >= 1 and (self._use_slow_update or self._use_meta_skill):
            from evo_agent.optimizer.skill_document.slow_update import (
                build_comparison_text,
            )

            comparison_text = build_comparison_text(
                self._prev_epoch_comparison,
                self._curr_epoch_comparison,
            )

        if self._use_slow_update and trainer_epoch >= 1:
            slow_update_started = time.perf_counter()
            await self._run_slow_update(trainer_epoch, comparison_text)
            logger.info(
                "[timing] epoch_end.slow_update epoch=%d elapsed=%.3fs",
                trainer_epoch,
                time.perf_counter() - slow_update_started,
            )
        if self._use_meta_skill and trainer_epoch >= 1:
            meta_skill_started = time.perf_counter()
            await self._run_meta_skill(trainer_epoch, comparison_text)
            logger.info(
                "[timing] epoch_end.meta_skill epoch=%d elapsed=%.3fs",
                trainer_epoch,
                time.perf_counter() - meta_skill_started,
            )
        self._prev_epoch_skill = self._current_skill_content
        self._prev_epoch_skill_by_operator = dict(self._current_skill_by_operator)
        self._prev_epoch_comparison = list(self._curr_epoch_comparison)
        self._curr_epoch_comparison.clear()
        self._step_buffer.clear()
        logger.info(
            "[timing] epoch_end.total epoch=%d elapsed=%.3fs",
            trainer_epoch,
            time.perf_counter() - epoch_end_started,
        )

    async def _run_slow_update(self, epoch: int, comparison_text: str = "") -> None:
        """Slow update: epoch-level strategic guidance for the protected region."""
        if not self._prev_epoch_comparison:
            return

        from evo_agent.optimizer.skill_document.edit_apply import (
            extract_slow_update_content,
            replace_slow_update_field,
        )
        from evo_agent.optimizer.skill_document.slow_update import (
            run_slow_update,
        )

        if not comparison_text:
            return

        if not self._current_skill_by_operator and not self._current_skill_content:
            return

        if self._current_skill_by_operator:
            for op_id, curr_skill in list(self._current_skill_by_operator.items()):
                prev_guidance = extract_slow_update_content(curr_skill)

                result = await run_slow_update(
                    self._llm,
                    self._model,
                    prev_skill=self._llm_skill_view(
                        self._prev_epoch_skill_by_operator.get(op_id, "")
                    ),
                    curr_skill=self._llm_skill_view(curr_skill),
                    comparison_text=comparison_text,
                    prev_guidance=prev_guidance,
                )

                if result.slow_update_content:
                    updated_skill = replace_slow_update_field(
                        curr_skill,
                        result.slow_update_content,
                    )
                    self._current_skill_by_operator[op_id] = updated_skill
                    self._sync_skill_to_operator_by_id(op_id, updated_skill)

            self._current_skill_content = next(iter(self._current_skill_by_operator.values()), "")
            return

        prev_guidance = extract_slow_update_content(self._current_skill_content)

        result = await run_slow_update(
            self._llm,
            self._model,
            prev_skill=self._llm_skill_view(self._prev_epoch_skill),
            curr_skill=self._llm_skill_view(self._current_skill_content),
            comparison_text=comparison_text,
            prev_guidance=prev_guidance,
        )

        if result.slow_update_content:
            self._current_skill_content = replace_slow_update_field(
                self._current_skill_content,
                result.slow_update_content,
            )
            self._sync_skill_to_operator(self._current_skill_content)

    async def _run_meta_skill(self, epoch: int, comparison_text: str = "") -> None:
        """Meta skill: optimizer-side memory update (does not modify skill document).

        Multi-operator mode uses one global memory by concatenating each
        operator's previous/current skill document into the prompt.
        """
        has_operator_skills = bool(self._prev_epoch_skill_by_operator)
        if not has_operator_skills and not self._prev_epoch_skill:
            return

        from evo_agent.optimizer.skill_document.meta_skill import run_meta_skill

        if has_operator_skills:
            prev_skill = self._format_operator_skills(
                {
                    op: self._llm_skill_view(s)
                    for op, s in self._prev_epoch_skill_by_operator.items()
                }
            )
            current_skills = self._current_skill_by_operator or self._read_skills_from_operators()
            curr_skill = self._format_operator_skills(
                {op: self._llm_skill_view(s) for op, s in current_skills.items()}
            )
        else:
            prev_skill = self._llm_skill_view(self._prev_epoch_skill)
            curr_skill = self._llm_skill_view(self._current_skill_content)

        content = await run_meta_skill(
            self._llm,
            self._model,
            prev_skill=prev_skill,
            curr_skill=curr_skill,
            comparison_text=comparison_text,
            prev_meta_skill=self._meta_skill_context,
        )

        if content:
            self._meta_skill_context = content

    # ── State serialization ──────────────────────────────────────────────

    def get_state(self) -> dict[str, Any]:
        """Serializable optimizer state for checkpoint resume."""
        return {
            "global_step": self._global_step,
            "step_buffer": self._step_buffer,
            "meta_skill_context": self._meta_skill_context,
            "scheduler": self._scheduler.state_dict(),
            "prev_epoch_skill": self._prev_epoch_skill,
            "prev_epoch_skill_by_operator": self._prev_epoch_skill_by_operator,
            "prev_epoch_comparison": self._prev_epoch_comparison,
        }

    def load_state(self, state: dict[str, Any]) -> None:
        """Restore optimizer state from checkpoint."""
        self._global_step = state.get("global_step", 0)
        self._step_buffer = state.get("step_buffer", [])
        self._meta_skill_context = state.get("meta_skill_context", "")
        sched_state = state.get("scheduler", {})
        if sched_state:
            self._scheduler.load_state_dict(sched_state)
        self._prev_epoch_skill = state.get("prev_epoch_skill", "")
        self._prev_epoch_skill_by_operator = state.get("prev_epoch_skill_by_operator", {})
        self._prev_epoch_comparison = state.get("prev_epoch_comparison", [])


__all__ = ["SkillDocumentOptimizer"]
