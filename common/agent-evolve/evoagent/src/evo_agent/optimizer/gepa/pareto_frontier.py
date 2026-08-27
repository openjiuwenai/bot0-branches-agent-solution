"""Pareto frontier — per-instance non-dominated candidate tracking.

Implements the core GEPA data structure: for each validation example, tracks
which candidate(s) achieve the best score on that example. A candidate that
is uniquely best at ANY single example survives on the frontier — this prevents
averaging away specialized improvements.

Key operations:
- ``add_candidate(idx, per_instance_scores)`` — insert a new candidate, update frontier
- ``sample_parent(rng)`` — frequency-weighted sampling from the frontier
- ``remove_dominated()`` — prune candidates that are never uniquely best
"""

from __future__ import annotations

import random
from typing import Any

# Type alias: per-instance scores keyed by example ID.
PerInstanceScores = dict[str, float]


class ParetoFrontier:
    """Per-instance Pareto frontier of prompt candidates.

    Attributes
    ----------
    _best_scores:
        ``{instance_id: best_score}`` — the best score seen for each instance.
    _candidates_at_front:
        ``{instance_id: set[candidate_idx]}`` — candidates achieving the best
        score for each instance.  Multiple candidates can tie.
    _all_scores:
        ``{candidate_idx: {instance_id: score}}`` — complete per-instance
        scores for every candidate ever evaluated.
    """

    def __init__(self, seed_instance_ids: list[str]) -> None:
        self._best_scores: dict[str, float] = {
            iid: -float("inf") for iid in seed_instance_ids
        }
        self._candidates_at_front: dict[str, set[int]] = {
            iid: set() for iid in seed_instance_ids
        }
        self._all_scores: dict[int, PerInstanceScores] = {}

    @property
    def n_candidates(self) -> int:
        return len(self._all_scores)

    @property
    def instance_ids(self) -> list[str]:
        return list(self._best_scores.keys())

    def add_candidate(
        self, idx: int, scores: PerInstanceScores
    ) -> bool:
        """Add a candidate's per-instance scores and update the frontier.

        Returns ``True`` if the candidate is on the Pareto frontier for at
        least one instance (i.e., it is not dominated).
        """
        self._all_scores[idx] = dict(scores)
        on_frontier = False
        for iid, score in scores.items():
            if iid not in self._best_scores:
                self._best_scores[iid] = score
                self._candidates_at_front[iid] = {idx}
                on_frontier = True
                continue
            best = self._best_scores[iid]
            if score > best:
                self._best_scores[iid] = score
                self._candidates_at_front[iid] = {idx}
                on_frontier = True
            elif score == best:
                self._candidates_at_front[iid].add(idx)
                on_frontier = True
        return on_frontier

    def _non_dominated(self) -> dict[int, int]:
        """Return ``{candidate_idx: frequency}`` for non-dominated candidates.

        A candidate is dominated if, for every instance where it appears on
        the frontier, there is at least one *other* candidate also on that
        instance's frontier (i.e., it is never *uniquely* best).
        """
        # Count how many instances each candidate appears on
        freq: dict[int, int] = {}
        for front in self._candidates_at_front.values():
            for cand_idx in front:
                freq[cand_idx] = freq.get(cand_idx, 0) + 1

        # A candidate is dominated if for every instance it's on, others are too
        dominated: set[int] = set()
        for cand_idx in freq:
            is_dominated = True
            for iid, front in self._candidates_at_front.items():
                if cand_idx in front and len(front) == 1:
                    # Uniquely best at this instance → not dominated
                    is_dominated = False
                    break
            if is_dominated:
                dominated.add(cand_idx)

        return {idx: f for idx, f in freq.items() if idx not in dominated}

    def sample_parent(self, rng: random.Random) -> int:
        """Sample a parent candidate from the frontier.

        Frequency-weighted: candidates that are best at more instances get
        proportionally higher selection probability.
        """
        non_dom = self._non_dominated()
        if not non_dom:
            # Fallback: sample from all candidates
            all_idx = list(self._all_scores.keys())
            return rng.choice(all_idx) if all_idx else 0

        # Build weighted sampling list
        sampling_list: list[int] = []
        for cand_idx, frequency in non_dom.items():
            sampling_list.extend([cand_idx] * frequency)
        return rng.choice(sampling_list)

    def best_candidate_idx(self) -> int:
        """Return the index of the candidate with the highest mean score."""
        if not self._all_scores:
            return 0
        best_idx = 0
        best_mean = -float("inf")
        for idx, scores in self._all_scores.items():
            if scores:
                mean = sum(scores.values()) / len(scores)
                if mean > best_mean:
                    best_mean = mean
                    best_idx = idx
        return best_idx

    def candidate_scores(self, idx: int) -> PerInstanceScores:
        """Return per-instance scores for a candidate."""
        return self._all_scores.get(idx, {})

    def aggregate_score(self, idx: int) -> float:
        """Return the mean per-instance score for a candidate."""
        scores = self._all_scores.get(idx, {})
        if not scores:
            return 0.0
        return sum(scores.values()) / len(scores)

    def find_merge_candidates(self, rng: random.Random) -> tuple[int, int] | None:
        """Find two non-dominated candidates for crossover.

        Returns ``(idx1, idx2)`` or ``None`` if fewer than 2 candidates exist.
        """
        non_dom = self._non_dominated()
        candidates = list(non_dom.keys())
        if len(candidates) < 2:
            return None
        idx1 = rng.choice(candidates)
        idx2 = rng.choice([c for c in candidates if c != idx1])
        return idx1, idx2

    def get_state(self) -> dict[str, Any]:
        """Serialize frontier state for checkpointing."""
        return {
            "best_scores": dict(self._best_scores),
            "candidates_at_front": {
                k: list(v) for k, v in self._candidates_at_front.items()
            },
            "all_scores": {
                str(k): dict(v) for k, v in self._all_scores.items()
            },
        }

    def load_state(self, state: dict[str, Any]) -> None:
        """Restore frontier from serialized state."""
        self._best_scores = dict(state["best_scores"])
        self._candidates_at_front = {
            k: set(v) for k, v in state["candidates_at_front"].items()
        }
        self._all_scores = {
            int(k): dict(v) for k, v in state["all_scores"].items()
        }
