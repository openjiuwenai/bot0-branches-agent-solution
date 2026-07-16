"""TfGrpoOptimizer — TF-GRPO (Training-Free GRPO) for SKILL.md.

Adapted for agent-solution EvoAgent (direct Model.invoke via llm_resilience).

Algorithm (per TF-GRPO epoch):
1. Sample a fixed train case batch (shared across the group)
2. Generate G SKILL.md variants from current_best + experience library
3. For each variant: hot-update → real Adapter rollout → evaluator score
4. LLM rollout summary → semantic advantage → experience library ops
5. Promote best variant; expose base/candidate to Trainer validation gate
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.dataset.case import merge_extra_data
from evo_agent.evaluator.trajectory.normalize import normalize_trace_to_trajectory
from evo_agent.optimizer.dict_optimizer import DictSkillDocumentOptimizer
from evo_agent.optimizer.llm_resilience import LLMInvokePolicy, invoke_text_with_retry
from evo_agent.optimizer.tf_grpo.experience_library import ExperienceLibrary
from evo_agent.optimizer.tf_grpo.semantic_advantage import (
    RolloutSummary,
    build_library_update_prompt,
    build_rollout_summary_prompt,
    build_semantic_advantage_prompt,
    case_outcome_briefs_from_evaluated,
    fallback_rollout_summary,
    parse_library_operations,
    scores_have_variance,
)
from evo_agent.optimizer.tf_grpo.variant_generator import (
    build_variant_prompt,
    is_complete_skill_document,
    restore_frontmatter,
    skill_document_incompleteness_reason,
    strip_code_fence,
)
from evo_agent.types import TrajectoryUnavailableError

logger = logging.getLogger(__name__)


def _require_messages(trace_data: dict[str, Any]) -> list[dict[str, Any]]:
    messages = (trace_data or {}).get("messages") or []
    if not messages:
        raise TrajectoryUnavailableError("cleaned-traces has no messages")
    return messages


class TfGrpoOptimizer(DictSkillDocumentOptimizer):
    """TF-GRPO optimizer wired into EvoAgent Trainer."""

    _VARIANT_MAX_TOKENS = 8192
    _SUMMARY_MAX_TOKENS = 1024

    def __init__(
        self,
        *,
        adapter_client: Any = None,
        operators: dict[str, Any] | None = None,
        rollout_extra_data: dict[str, Any] | None = None,
        conversation_id_factory: Any = None,
        trace_max_retries: int = 24,
        trace_retry_backoff: float = 5.0,
        phase_callback: Any | None = None,
        group_size: int = 3,
        cases_per_variant: int | None = None,
        variant_temperature: float = 0.7,
        max_experiences: int = 10,
        rollout_temperature: float | None = None,
        **kwargs: Any,
    ) -> None:
        kwargs.setdefault("use_slow_update", False)
        kwargs.setdefault("use_meta_skill", False)
        kwargs.setdefault("steps_per_epoch", 1)
        kwargs.setdefault("accumulation", 1)
        super().__init__(**kwargs)

        self._adapter_client = adapter_client
        self._operators = operators or {}
        self._rollout_extra_data = rollout_extra_data or {}
        self._conversation_id_factory = conversation_id_factory
        self._trace_max_retries = trace_max_retries
        self._trace_retry_backoff = trace_retry_backoff
        self._phase_callback = phase_callback

        self._group_size = max(1, int(group_size))
        self._cases_per_variant = (
            int(cases_per_variant)
            if cases_per_variant is not None
            else int(getattr(self, "_batch_size", 8) or 8)
        )
        self._variant_temperature = float(variant_temperature)
        self._rollout_temperature = rollout_temperature
        self._experience_libs: dict[str, ExperienceLibrary] = {
            op_id: ExperienceLibrary(domain="markdown", max_experiences=max_experiences)
            for op_id in self._operators
        }
        self._llm_policy = LLMInvokePolicy(
            attempt_timeout_secs=300.0,
            total_budget_secs=900.0,
            max_attempts=3,
            backoff_base_secs=1.0,
        )

    def _push_phase(self, event: str, data: dict[str, Any]) -> None:
        if isinstance(data.get("epoch"), int):
            data = {**data, "epoch": data["epoch"] + 1}
        if self._phase_callback is None:
            return
        try:
            self._phase_callback(event, data)
        except Exception:
            logger.warning("phase_callback push failed", exc_info=True)

    async def _get_required_trace(self, conversation_id: str) -> dict[str, Any]:
        for attempt in range(self._trace_max_retries):
            trace_data = await self._adapter_client.get_traces(case_id=conversation_id)
            try:
                _require_messages(trace_data)
                return trace_data
            except TrajectoryUnavailableError:
                if attempt < self._trace_max_retries - 1:
                    await asyncio.sleep(self._trace_retry_backoff)
        raise TrajectoryUnavailableError(
            f"No valid traces after {self._trace_max_retries} attempts "
            f"for conversation_id={conversation_id}"
        )

    async def _generate_variant(
        self,
        *,
        current_best: str,
        experience_context: str,
        epoch: int,
        variant_index: int,
        operator_id: str,
    ) -> str:
        del operator_id  # reserved for logging / future context wiring
        prompt = build_variant_prompt(
            current_best=current_best,
            experience_context=experience_context,
            epoch=epoch,
        )
        prompt += (
            f"\n**Rollout focus hint:** variant {variant_index}/{self._group_size} "
            "— emphasize a DIFFERENT concrete improvement than other variants.\n"
        )
        raw = await invoke_text_with_retry(
            self._llm,
            self._model,
            prompt,
            policy=self._llm_policy,
            temperature=self._variant_temperature,
            max_tokens=self._VARIANT_MAX_TOKENS,
            is_result_usable=lambda text: is_complete_skill_document(
                strip_code_fence(text),
                baseline=current_best,
            ),
        )
        variant = restore_frontmatter(
            current_best,
            raw,
            preserve=bool(getattr(self, "_preserve_frontmatter", True)),
        )
        reason = skill_document_incompleteness_reason(variant, baseline=current_best)
        if reason is not None:
            raise ValueError(f"incomplete SKILL.md variant: {reason}")
        return variant

    async def _extract_and_update_experiences(
        self,
        *,
        rollouts: list[RolloutSummary],
        library: ExperienceLibrary,
        operator_id: str,
        epoch: int,
    ) -> list[str]:
        del operator_id, epoch
        if not scores_have_variance(rollouts):
            logger.info(
                "[tf_grpo] skip experience update: no score variance"
            )
            return []

        advantage_prompt = build_semantic_advantage_prompt(rollouts, library)
        advantage = await invoke_text_with_retry(
            self._llm,
            self._model,
            advantage_prompt,
            policy=self._llm_policy,
            temperature=self._variant_temperature,
            is_result_usable=lambda text: bool((text or "").strip()),
        )
        if not (advantage or "").strip():
            return []

        ops_prompt = build_library_update_prompt(advantage, library)
        ops_raw = await invoke_text_with_retry(
            self._llm,
            self._model,
            ops_prompt,
            policy=self._llm_policy,
            temperature=0.2,
            is_result_usable=lambda text: bool((text or "").strip()),
        )
        operations = parse_library_operations(ops_raw)
        return library.apply_operations(operations)

    def _batch_evaluate(
        self,
        eval_cases: list[Case],
        answers: list[dict[str, Any]],
    ) -> list[EvaluatedCase]:
        if not eval_cases:
            return []
        try:
            return self._evaluator.batch_evaluate(
                eval_cases,
                answers,
                num_parallel=self._num_parallel,
                enable_attribution=False,
            )
        except TypeError:
            return self._evaluator.batch_evaluate(
                eval_cases,
                answers,
                num_parallel=self._num_parallel,
            )

    async def _rollout(
        self,
        cases: list[Case],
    ) -> tuple[list[EvaluatedCase], list[dict]]:
        """Adapter invoke + cleaned-traces + evaluator (same contract as edp_agent)."""
        if not cases:
            return [], []

        sem = asyncio.Semaphore(min(self._num_parallel, len(cases)))

        async def _rollout_one(case: Case) -> tuple[str, Any, dict | None, str]:
            async with sem:
                case_extra = case.inputs.get("extra_data", {})
                extra = merge_extra_data(self._rollout_extra_data, case_extra)
                if self._rollout_temperature is not None:
                    extra = {**extra, "temperature": self._rollout_temperature}

                if self._conversation_id_factory:
                    conversation_id = self._conversation_id_factory.new(
                        phase="train", case_id=case.case_id
                    )
                else:
                    conversation_id = case.case_id

                try:
                    result = await self._agent.invoke(
                        {
                            **case.inputs,
                            "conversation_id": conversation_id,
                            "extra_data": extra,
                        },
                    )
                except Exception as exc:
                    logger.warning(
                        "Training invoke failed for case=%s conversation_id=%s: %s",
                        case.case_id,
                        conversation_id,
                        exc,
                    )
                    result = {"answer": "", "error": str(exc)}

                answer = result if isinstance(result, dict) else {"answer": str(result)}
                try:
                    trace_data = await self._get_required_trace(conversation_id)
                except TrajectoryUnavailableError as exc:
                    logger.warning("Skipping case %s: %s", case.case_id, exc)
                    trace_data = None
                return case.case_id, answer, trace_data, conversation_id

        results = await asyncio.gather(*[_rollout_one(c) for c in cases])

        eval_cases: list[Case] = []
        answers: list[dict[str, Any]] = []
        traj_by_case_id: dict[str, dict[str, Any]] = {}

        for case, (case_id, answer, trace_data, _conv_id) in zip(cases, results):
            if trace_data is None:
                logger.warning("训练 trace 不可用，排除 case %s（不计入评分）", case_id)
                continue

            trajectory_dict = normalize_trace_to_trajectory(trace_data)
            case_for_eval = case.model_copy(
                update={
                    "inputs": {
                        **case.inputs,
                        "trajectory": trajectory_dict,
                        "skill_names": list(self._operators.keys()),
                    }
                },
                deep=False,
            )
            eval_cases.append(case_for_eval)
            answers.append(answer if isinstance(answer, dict) else {"answer": str(answer)})
            messages = trace_data.get("messages", [])
            traj_by_case_id[case_id] = {"case_id": case_id, "messages": messages}

        evaluated_list = self._batch_evaluate(eval_cases, answers)
        trajectories = [
            traj_by_case_id[ec.case_id] for ec in evaluated_list if ec.case_id in traj_by_case_id
        ]
        return evaluated_list, trajectories

    async def _score_variant_on_cases(
        self, cases: list[Case]
    ) -> tuple[float, int, list[EvaluatedCase]]:
        evaluated, _ = await self._rollout(cases)
        if not evaluated:
            return 0.0, 0, []
        mean_score = sum(ec.score for ec in evaluated) / len(evaluated)
        return float(mean_score), len(evaluated), evaluated

    async def _summarize_rollout(
        self,
        *,
        variant_id: str,
        skill_content: str,
        evaluated: list[EvaluatedCase],
        mean_score: float,
        operator_id: str,
        epoch: int,
    ) -> str:
        del operator_id, epoch
        briefs = case_outcome_briefs_from_evaluated(evaluated)
        if not briefs:
            return fallback_rollout_summary(variant_id, mean_score, 0)
        prompt = build_rollout_summary_prompt(
            variant_id=variant_id,
            skill_content=skill_content,
            case_briefs=briefs,
            mean_score=mean_score,
        )
        try:
            summary = await invoke_text_with_retry(
                self._llm,
                self._model,
                prompt,
                policy=self._llm_policy,
                temperature=0.3,
                max_tokens=self._SUMMARY_MAX_TOKENS,
                is_result_usable=lambda text: bool((text or "").strip()),
            )
        except Exception:
            logger.exception(
                "[tf_grpo] rollout summary LLM failed id=%s; using fallback",
                variant_id,
            )
            return fallback_rollout_summary(variant_id, mean_score, len(briefs))
        text = (summary or "").strip()
        return text or fallback_rollout_summary(variant_id, mean_score, len(briefs))

    async def _optimize_one_skill(
        self,
        *,
        operator_id: str,
        base_content: str,
        cases: list[Case],
        epoch: int,
    ) -> str:
        library = self._experience_libs.setdefault(
            operator_id,
            ExperienceLibrary(domain="markdown", max_experiences=10),
        )
        current_best = base_content
        best_score = -1.0
        experience_context = library.to_prompt_context("markdown")
        rollouts: list[RolloutSummary] = []

        self._push_phase(
            "log",
            {
                "level": "info",
                "message": (
                    f"TF-GRPO: op={operator_id} "
                    f"group_size={self._group_size} cases={len(cases)}"
                ),
                "phase": "tf_grpo",
                "epoch": self._artifact_epoch,
                "data": {
                    "operator_id": operator_id,
                    "group_size": self._group_size,
                    "n_cases": len(cases),
                },
            },
        )

        for g in range(1, self._group_size + 1):
            variant_id = f"e{epoch}-g{g}"
            started = time.perf_counter()
            try:
                variant = await self._generate_variant(
                    current_best=current_best,
                    experience_context=experience_context,
                    epoch=epoch,
                    variant_index=g,
                    operator_id=operator_id,
                )
            except Exception:
                logger.exception(
                    "[tf_grpo] variant generation failed id=%s", variant_id
                )
                continue

            if not variant.strip() or variant.strip() == current_best.strip():
                logger.info("[tf_grpo] skip empty/identical variant id=%s", variant_id)
                continue

            incomplete = skill_document_incompleteness_reason(
                variant, baseline=current_best
            )
            if incomplete is not None:
                logger.warning(
                    "[tf_grpo] reject incomplete variant id=%s reason=%s",
                    variant_id,
                    incomplete,
                )
                continue

            self._sync_skill_to_operator_by_id(operator_id, variant)
            score, n_scored, evaluated = await self._score_variant_on_cases(cases)
            summary = await self._summarize_rollout(
                variant_id=variant_id,
                skill_content=variant,
                evaluated=evaluated,
                mean_score=score,
                operator_id=operator_id,
                epoch=epoch,
            )
            rollouts.append(
                RolloutSummary(
                    variant_id=variant_id,
                    content=variant,
                    score=score,
                    summary=summary,
                )
            )
            logger.info(
                "[tf_grpo] variant=%s score=%.4f n=%d elapsed=%.2fs",
                variant_id,
                score,
                n_scored,
                time.perf_counter() - started,
            )
            if score > best_score:
                best_score = score
                current_best = variant

        if rollouts:
            await self._extract_and_update_experiences(
                rollouts=rollouts,
                library=library,
                operator_id=operator_id,
                epoch=epoch,
            )
            best = max(rollouts, key=lambda r: r.score)
            current_best = best.content
            self._sync_skill_to_operator_by_id(operator_id, current_best)
            self._push_phase(
                "log",
                {
                    "level": "info",
                    "message": (
                        f"TF-GRPO best {best.variant_id} "
                        f"score={best.score:.4f} for {operator_id}"
                    ),
                    "phase": "tf_grpo_done",
                    "epoch": self._artifact_epoch,
                    "data": {
                        "operator_id": operator_id,
                        "best_variant": best.variant_id,
                        "best_score": round(best.score, 4),
                        "n_rollouts": len(rollouts),
                    },
                },
            )
        else:
            self._sync_skill_to_operator_by_id(operator_id, base_content)
            current_best = base_content

        return current_best

    async def _backward(self, signals: list[Any]) -> None:
        """One TF-GRPO epoch: sample cases once, optimize each skill operator."""
        del signals
        self._artifact_epoch += 1
        artifact_epoch = self._artifact_epoch
        self._current_epoch = self._artifact_epoch
        self._global_step += 1

        self._current_skill_by_operator = self._read_skills_from_operators()
        self._epoch_base_skill_by_operator = dict(self._current_skill_by_operator)
        self._last_candidate_skill_by_operator = {}
        self._ranked_patch_by_operator = {}
        self._curr_epoch_comparison.clear()

        self._current_skill_content = next(iter(self._current_skill_by_operator.values()), "")
        self._epoch_base_skill_content = self._current_skill_content
        self._last_candidate_skill_content = ""

        self._artifact_exporter.export_skill_snapshot(
            artifact_epoch,
            0,
            self._epoch_base_skill_content,
            "before",
        )

        cases = self._sample_cases(
            self._cases_per_variant,
            seed=artifact_epoch + 1,
        )
        epoch_1based = artifact_epoch + 1

        for op_id, base_content in list(self._epoch_base_skill_by_operator.items()):
            if not base_content:
                continue
            candidate = await self._optimize_one_skill(
                operator_id=op_id,
                base_content=base_content,
                cases=cases,
                epoch=epoch_1based,
            )
            self._current_skill_by_operator[op_id] = candidate

        self._current_skill_content = next(
            iter(self._current_skill_by_operator.values()),
            self._epoch_base_skill_content,
        )
        self._last_candidate_skill_by_operator = dict(self._current_skill_by_operator)
        self._last_candidate_skill_content = self._current_skill_content

        self._artifact_exporter.export_skill_snapshot(
            artifact_epoch,
            0,
            self._current_skill_content,
            "after",
        )
        self._on_step_apply(
            step=1,
            n_edits=sum(
                1
                for op_id, cand in self._current_skill_by_operator.items()
                if cand != self._epoch_base_skill_by_operator.get(op_id, "")
            ),
            n_operators=len(self._operators),
        )

    def _on_step_apply(self, step: int, n_edits: int, n_operators: int) -> None:
        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"TF-GRPO 已应用：{n_edits} 个 skill 更新 / {n_operators} operators",
                "phase": "apply",
                "epoch": self._artifact_epoch,
                "data": {
                    "n_operators": n_operators,
                    "n_edits": n_edits,
                    "step": step,
                },
            },
        )
