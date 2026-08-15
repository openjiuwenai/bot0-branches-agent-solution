"""GepaOptimizer — Genetic-Pareto reflective prompt evolution.

Implements the open-source GEPA algorithm (arXiv:2507.19457) within the
EvoAgent framework. Each epoch runs one GEPA iteration:

1. **Select parent** from Pareto frontier (frequency-weighted sampling)
2. **Sample minibatch** from training set (epoch-shuffled)
3. **Evaluate parent** on minibatch with trajectory capture
4. **Build reflective dataset** from trajectories + scores + feedback
5. **Reflect**: LLM analyzes full traces → proposes improved prompt
6. **Evaluate child** on same minibatch
7. **Accept** if sum(child_scores) > sum(parent_scores) [strict improvement]
8. If accepted: **Full eval on valset → Update Pareto frontier**
9. **Merge** (periodically): combine complementary candidates via crossover

The Pareto frontier tracks per-instance best scores — a candidate that is
uniquely best at ANY single validation example survives, preventing averaging
away specialized improvements.
"""

from __future__ import annotations

import asyncio
import logging
import random
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.optimizer.gepa.gepa_adapter import GEPAAdapter
from evo_agent.optimizer.gepa.pareto_frontier import ParetoFrontier
from evo_agent.optimizer.gepa.reflection_engine import ReflectionEngine
from evo_agent.optimizer.gepa.prompts import (
    MERGE,
    load_gepa_prompt,
    render_prompt,
)
from evo_agent.optimizer.gepa.utils import (
    build_multimodal_content as _build_multimodal_content,
    encode_image as _encode_image,
)
from evo_agent.optimizer.llm_resilience import LLMInvokePolicy, invoke_text_with_retry

logger = logging.getLogger(__name__)

# Component name used when the candidate dict has a single prompt.
_DEFAULT_COMPONENT = "system_prompt"


class GepaOptimizer:
    """GEPA (Genetic-Pareto) reflective prompt optimizer.

    This is a standalone optimizer that does NOT inherit from
    ``DictSkillDocumentOptimizer`` — it implements the full GEPA algorithm
    directly, using the ``GEPAAdapter`` for evaluation and the
    ``ReflectionEngine`` for LLM-driven mutation.

    For EvoAgent integration, it is wrapped by a scenario adapter that
    bridges it to the Trainer's epoch loop.
    """

    def __init__(
        self,
        *,
        vision_model: Any = None,
        vision_model_name: str = "",
        evaluator: Any = None,
        train_cases: list[Case] | None = None,
        val_cases: list[Case] | None = None,
        llm_invocation: Any = None,
        model_name: str = "",
        num_parallel: int = 4,
        # GEPA hyperparameters
        minibatch_size: int = 5,
        max_iterations: int = 20,
        perfect_score: float = 1.0,
        candidate_selection_strategy: str = "pareto",
        acceptance_criterion: str = "strict_improvement",
        enable_merge: bool = True,
        merge_frequency: int = 3,
        seed: int = 0,
        component_name: str = _DEFAULT_COMPONENT,
        scenario_name: str | None = None,
        scenarios_dir: Any = None,
        **kwargs: Any,
    ) -> None:
        self._vision_model = vision_model
        self._vision_model_name = vision_model_name
        self._evaluator = evaluator
        self._train_cases = train_cases or []
        self._val_cases = val_cases or []
        self._llm_invocation = llm_invocation
        self._model_name = model_name
        self._num_parallel = num_parallel
        self._minibatch_size = minibatch_size
        self._max_iterations = max_iterations
        self._perfect_score = perfect_score
        self._candidate_selection_strategy = candidate_selection_strategy
        self._acceptance_criterion = acceptance_criterion
        self._enable_merge = enable_merge
        self._merge_frequency = merge_frequency
        self._rng = random.Random(seed)
        self._component_name = component_name
        self._scenario_name = scenario_name
        self._scenarios_dir = scenarios_dir

        # GEPA state
        self._candidates: list[dict[str, str]] = []
        self._parents: list[list[int | None]] = []  # lineage tracking
        self._per_instance_scores: list[dict[str, float]] = []
        self._pareto_frontier: ParetoFrontier | None = None
        self._iteration = -1
        self._best_idx = 0
        self._merge_llm_policy = LLMInvokePolicy(
            attempt_timeout_secs=300.0,
            total_budget_secs=900.0,
            max_attempts=3,
            backoff_base_secs=1.0,
        )

        # Lazy-initialized helpers
        self._adapter: GEPAAdapter | None = None
        self._reflection_engine: ReflectionEngine | None = None

    def _ensure_helpers(self) -> None:
        if self._adapter is None:
            self._adapter = GEPAAdapter(
                vision_model=self._vision_model,
                evaluator=self._evaluator,
                num_parallel=self._num_parallel,
                component_name=self._component_name,
            )
        if self._reflection_engine is None:
            if self._llm_invocation is None or not self._model_name:
                raise RuntimeError(
                    "GEPA requires llm_invocation and model_name for reflection"
                )
            self._reflection_engine = ReflectionEngine(
                self._llm_invocation,
                self._model_name,
                scenario_name=self._scenario_name,
                scenarios_dir=self._scenarios_dir,
            )

    def _push_phase(self, event: str, data: dict[str, Any]) -> None:
        """Hook for SSE phase events. Override in scenario adapter."""
        pass

    def set_phase_callback(self, callback) -> None:
        """Set a callback function for phase events."""
        self._push_phase = callback

    # ── Initialization ───────────────────────────────────────────────────

    async def initialize(self, seed_prompt: str) -> None:
        """Evaluate seed candidate on full valset and initialize state."""
        self._ensure_helpers()
        seed_candidate = {self._component_name: seed_prompt}
        val_batch = await self._adapter.evaluate(
            seed_candidate, self._val_cases, capture_traces=False
        )
        per_instance = {
            cid: score for cid, score in zip(val_batch.case_ids, val_batch.scores)
        }
        self._candidates = [seed_candidate]
        self._parents = [[None]]
        self._per_instance_scores = [per_instance]
        val_ids = [c.case_id for c in self._val_cases]
        self._pareto_frontier = ParetoFrontier(val_ids)
        self._pareto_frontier.add_candidate(0, per_instance)
        self._best_idx = 0
        self._iteration = -1
        logger.info(
            "[gepa] seed initialized: mean_score=%.4f n_val=%d",
            sum(per_instance.values()) / max(len(per_instance), 1),
            len(per_instance),
        )

    # ── Core GEPA loop ────────────────────────────────────────────────────

    async def run_optimization(self, seed_prompt: str) -> dict[str, Any]:
        """Run the full GEPA optimization loop.

        Returns ``{"best_prompt": str, "best_score": float, "n_iterations": int,
        "n_candidates": int, "pareto_frontier": dict}``.
        """
        await self.initialize(seed_prompt)

        for _ in range(self._max_iterations):
            self._iteration += 1
            should_stop = await self._run_iteration()
            if should_stop:
                break

        best_idx = self._pareto_frontier.best_candidate_idx()
        best_prompt = self._candidates[best_idx][self._component_name]
        best_score = self._pareto_frontier.aggregate_score(best_idx)
        return {
            "best_prompt": best_prompt,
            "best_score": best_score,
            "n_iterations": self._iteration + 1,
            "n_candidates": len(self._candidates),
            "best_idx": best_idx,
            "pareto_frontier": self._pareto_frontier.get_state(),
        }

    async def _run_iteration(self) -> bool:
        """Run one GEPA iteration. Returns True if should stop."""
        if self._pareto_frontier is None or self._adapter is None:
            raise RuntimeError("Pareto frontier and adapter must be initialized")
        if self._reflection_engine is None:
            raise RuntimeError("Reflection engine must be initialized")

        # 1. Select parent from Pareto frontier
        parent_idx = self._select_parent()
        parent_candidate = self._candidates[parent_idx]

        self._push_phase("log", {
            "level": "info",
            "message": f"GEPA iter={self._iteration} parent_idx={parent_idx}",
            "phase": "gepa_select",
            "epoch": self._iteration,
            "data": {"parent_idx": parent_idx, "parent_score": self._pareto_frontier.aggregate_score(parent_idx)},
        })

        # 2. Sample minibatch from training set
        minibatch = self._sample_minibatch()
        if not minibatch:
            return True

        # 3. Evaluate parent on minibatch with trajectory capture
        parent_batch = await self._adapter.evaluate(
            parent_candidate, minibatch, capture_traces=True
        )
        parent_score_sum = sum(parent_batch.scores)

        # 4. Build reflective dataset
        components_to_update = [self._component_name]
        reflective_dataset = self._adapter.make_reflective_dataset(
            parent_candidate, parent_batch, components_to_update
        )

        # 5. Reflect: LLM proposes new prompt
        new_texts = await self._reflection_engine.propose(
            current_prompt=parent_candidate[self._component_name],
            reflective_dataset=reflective_dataset,
            components_to_update=components_to_update,
            iteration=self._iteration,
        )

        # 6. Create child candidate
        child_candidate = dict(parent_candidate)
        for comp, text in new_texts.items():
            child_candidate[comp] = text

        # Skip if child is identical to parent
        if child_candidate == parent_candidate:
            logger.info("[gepa] child identical to parent, skipping")
            return self._check_stop()

        # 7. Evaluate child on same minibatch
        child_batch = await self._adapter.evaluate(
            child_candidate, minibatch, capture_traces=False
        )
        child_score_sum = sum(child_batch.scores)

        # 8. Acceptance check
        accepted = self._check_acceptance(parent_score_sum, child_score_sum)

        self._push_phase("log", {
            "level": "info",
            "message": f"GEPA child score={child_score_sum:.4f} parent={parent_score_sum:.4f} accepted={accepted}",
            "phase": "gepa_evaluate",
            "epoch": self._iteration,
            "data": {
                "parent_score": round(parent_score_sum, 4),
                "child_score": round(child_score_sum, 4),
                "accepted": accepted,
            },
        })

        if accepted:
            # 9. Full eval on valset + update Pareto frontier
            await self._add_candidate(
                child_candidate, parent_idx, minibatch, child_batch
            )

            # 10. Merge (periodically)
            if self._enable_merge and (len(self._candidates) % self._merge_frequency == 0):
                await self._attempt_merge()

        return self._check_stop()

    def _select_parent(self) -> int:
        """Select parent from Pareto frontier."""
        if self._pareto_frontier is None:
            raise RuntimeError("Pareto frontier not initialized")
        if self._candidate_selection_strategy == "pareto":
            return self._pareto_frontier.sample_parent(self._rng)
        elif self._candidate_selection_strategy == "current_best":
            return self._pareto_frontier.best_candidate_idx()
        else:
            return self._pareto_frontier.sample_parent(self._rng)

    def _sample_minibatch(self) -> list[Case]:
        """Sample a minibatch from training set (epoch-shuffled)."""
        if not self._train_cases:
            return []
        n = min(self._minibatch_size, len(self._train_cases))
        return self._rng.sample(self._train_cases, n)

    def _check_acceptance(
        self, parent_sum: float, child_sum: float
    ) -> bool:
        """Check if child is accepted based on the acceptance criterion."""
        if self._acceptance_criterion == "strict_improvement":
            return child_sum > parent_sum
        elif self._acceptance_criterion == "improvement_or_equal":
            return child_sum >= parent_sum
        return child_sum > parent_sum

    async def _add_candidate(
        self,
        candidate: dict[str, str],
        parent_idx: int,
        minibatch: list[Case],
        minibatch_eval: Any,
    ) -> None:
        """Add accepted candidate: full val eval + Pareto frontier update."""
        if self._pareto_frontier is None or self._adapter is None:
            raise RuntimeError("Pareto frontier and adapter must be initialized")

        # Full eval on valset
        val_batch = await self._adapter.evaluate(
            candidate, self._val_cases, capture_traces=False
        )
        per_instance = {
            cid: score for cid, score in zip(val_batch.case_ids, val_batch.scores)
        }

        # Add to state
        new_idx = len(self._candidates)
        self._candidates.append(candidate)
        self._parents.append([parent_idx])
        self._per_instance_scores.append(per_instance)
        self._pareto_frontier.add_candidate(new_idx, per_instance)

        # Update best
        if self._pareto_frontier.aggregate_score(new_idx) > self._pareto_frontier.aggregate_score(self._best_idx):
            self._best_idx = new_idx

        logger.info(
            "[gepa] candidate %d accepted: mean_score=%.4f (best=%d: %.4f)",
            new_idx,
            sum(per_instance.values()) / max(len(per_instance), 1),
            self._best_idx,
            self._pareto_frontier.aggregate_score(self._best_idx),
        )

    def _check_stop(self) -> bool:
        """Check if optimization should stop (perfect score or budget)."""
        if self._pareto_frontier is None:
            raise RuntimeError("Pareto frontier not initialized")
        best_score = self._pareto_frontier.aggregate_score(self._best_idx)
        if best_score >= self._perfect_score:
            logger.info("[gepa] perfect score reached: %.4f", best_score)
            return True
        return False

    # ── Merge / Crossover ─────────────────────────────────────────────────

    async def _attempt_merge(self) -> None:
        """Attempt to merge two non-dominated candidates via LLM crossover."""
        if self._pareto_frontier is None or self._reflection_engine is None:
            raise RuntimeError("Pareto frontier and reflection engine must be initialized")
        pair = self._pareto_frontier.find_merge_candidates(self._rng)
        if pair is None:
            return
        idx_a, idx_b = pair
        prompt_a = self._candidates[idx_a][self._component_name]
        prompt_b = self._candidates[idx_b][self._component_name]

        # Count wins
        scores_a = self._pareto_frontier.candidate_scores(idx_a)
        scores_b = self._pareto_frontier.candidate_scores(idx_b)
        a_wins = sum(1 for k, v in scores_a.items() if v > scores_b.get(k, 0))
        b_wins = sum(1 for k, v in scores_b.items() if v > scores_a.get(k, 0))
        ties = sum(1 for k in scores_a if scores_a[k] == scores_b.get(k, 0))

        # Get examples where each wins
        a_examples = self._render_merge_examples(scores_a, scores_b, idx_a, idx_b, a_wins=True)
        b_examples = self._render_merge_examples(scores_b, scores_a, idx_b, idx_a, a_wins=False)

        # LLM merge
        merge_prompt = render_prompt(
            load_gepa_prompt(MERGE, scenario_name=self._scenario_name, scenarios_dir=self._scenarios_dir),
            {
                "prompt_a": prompt_a[:8000],
                "prompt_b": prompt_b[:8000],
                "a_wins": a_wins,
                "b_wins": b_wins,
                "ties": ties,
                "a_examples": a_examples,
                "b_examples": b_examples,
            },
        )

        try:
            from evo_agent.optimizer.gepa.reflection_engine import _extract_from_code_blocks
            response = await invoke_text_with_retry(
                self._llm_invocation,
                self._model_name,
                merge_prompt,
                policy=self._merge_llm_policy,
                stage="reflect",
                temperature=0.5,
                is_result_usable=lambda text: bool((text or "").strip()),
            )
            merged = _extract_from_code_blocks(response or "")
            if not merged.strip():
                return
        except Exception:
            logger.warning("[gepa] merge LLM call failed", exc_info=True)
            return

        # Evaluate merged candidate
        merged_candidate = {self._component_name: merged}
        merged_batch = await self._adapter.evaluate(
            merged_candidate, self._val_cases, capture_traces=False
        )
        per_instance = {
            cid: score for cid, score in zip(merged_batch.case_ids, merged_batch.scores)
        }
        merged_sum = sum(per_instance.values())
        parent_a_sum = sum(scores_a.values())
        parent_b_sum = sum(scores_b.values())

        # Accept if merged is better than the worse parent
        if merged_sum > min(parent_a_sum, parent_b_sum):
            new_idx = len(self._candidates)
            self._candidates.append(merged_candidate)
            self._parents.append([idx_a, idx_b])
            self._per_instance_scores.append(per_instance)
            self._pareto_frontier.add_candidate(new_idx, per_instance)
            if self._pareto_frontier.aggregate_score(new_idx) > self._pareto_frontier.aggregate_score(self._best_idx):
                self._best_idx = new_idx
            logger.info(
                "[gepa] merge accepted: idx=%d merged_score=%.4f (a=%.4f b=%.4f)",
                new_idx,
                merged_sum / max(len(per_instance), 1),
                parent_a_sum / max(len(scores_a), 1),
                parent_b_sum / max(len(scores_b), 1),
            )

    def _render_merge_examples(
        self,
        scores_self: dict[str, float],
        scores_other: dict[str, float],
        self_idx: int,
        other_idx: int,
        *,
        a_wins: bool,
    ) -> str:
        """Render examples where a candidate wins for the merge prompt."""
        parts: list[str] = []
        count = 0
        for case_id, score in scores_self.items():
            other_score = scores_other.get(case_id, 0)
            if a_wins and score > other_score:
                case = next((c for c in self._val_cases if c.case_id == case_id), None)
                if case:
                    expected = case.label.get("expected_result", "") if isinstance(case.label, dict) else ""
                    parts.append(
                        f"- case_id={case_id} score={score:.2f} "
                        f"(other={other_score:.2f}) expected='{expected}'"
                    )
                    count += 1
            if count >= 5:
                break
        return "\n".join(parts) if parts else "(no winning examples)"

    # ── Results ───────────────────────────────────────────────────────────

    def get_best_prompt(self) -> str:
        """Return the current best prompt text."""
        if not self._candidates:
            return ""
        return self._candidates[self._best_idx][self._component_name]

    def get_best_score(self) -> float:
        if self._pareto_frontier is None:
            return 0.0
        return self._pareto_frontier.aggregate_score(self._best_idx)
