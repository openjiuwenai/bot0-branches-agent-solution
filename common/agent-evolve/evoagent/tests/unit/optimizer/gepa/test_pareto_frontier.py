"""ParetoFrontier tests — per-instance non-dominated candidate tracking."""

from __future__ import annotations

import random
from typing import Any

from evo_agent.optimizer.gepa.pareto_frontier import ParetoFrontier


def test_add_candidate_initial() -> None:
    """Seed candidate initializes the frontier."""
    frontier = ParetoFrontier(["a", "b", "c"])
    frontier.add_candidate(0, {"a": 0.5, "b": 0.6, "c": 0.7})
    assert frontier.n_candidates == 1
    assert frontier.aggregate_score(0) == pytest_approx(0.6)


def test_add_candidate_replaces_on_better_score() -> None:
    """A candidate with higher score on an instance replaces the frontier."""
    frontier = ParetoFrontier(["a", "b", "c"])
    frontier.add_candidate(0, {"a": 0.5, "b": 0.6, "c": 0.7})
    frontier.add_candidate(1, {"a": 0.9, "b": 0.3, "c": 0.7})
    # Candidate 1 is uniquely best at "a"
    assert frontier.candidate_scores(1)["a"] == 0.9


def test_add_candidate_ties_kept_on_frontier() -> None:
    """Tied candidates both stay on the frontier."""
    frontier = ParetoFrontier(["a", "b"])
    frontier.add_candidate(0, {"a": 0.5, "b": 0.5})
    frontier.add_candidate(1, {"a": 0.5, "b": 0.6})
    # Both tie on "a" → both on frontier for "a"
    # Candidate 1 is uniquely best at "b"
    assert getattr(frontier, '_candidates_at_front')["a"] == {0, 1}
    assert getattr(frontier, '_candidates_at_front')["b"] == {1}


def test_non_dominated_preserves_uniquely_best() -> None:
    """A candidate uniquely best at any instance is not dominated."""
    frontier = ParetoFrontier(["a", "b", "c"])
    frontier.add_candidate(0, {"a": 0.9, "b": 0.3, "c": 0.5})
    frontier.add_candidate(1, {"a": 0.3, "b": 0.9, "c": 0.5})
    frontier.add_candidate(2, {"a": 0.3, "b": 0.3, "c": 0.9})
    non_dom = getattr(frontier, '_non_dominated')()
    # All three are uniquely best at one instance → all non-dominated
    assert set(non_dom.keys()) == {0, 1, 2}


def test_non_dominated_removes_never_uniquely_best() -> None:
    """A candidate that is never uniquely best is dominated."""
    frontier = ParetoFrontier(["a", "b"])
    frontier.add_candidate(0, {"a": 0.5, "b": 0.5})
    frontier.add_candidate(1, {"a": 0.9, "b": 0.5})  # ties with 0 on b
    frontier.add_candidate(2, {"a": 0.9, "b": 0.9})  # dominates both
    non_dom = getattr(frontier, '_non_dominated')()
    # Candidate 2 is uniquely best at both → only 2 survives
    # Candidates 0 and 1 are never uniquely best → dominated
    assert 2 in non_dom
    assert 0 not in non_dom
    assert 1 not in non_dom


def test_sample_parent_frequency_weighted() -> None:
    """Candidates best at more instances are sampled more frequently."""
    frontier = ParetoFrontier(["a", "b", "c", "d"])
    frontier.add_candidate(0, {"a": 0.9, "b": 0.9, "c": 0.1, "d": 0.1})
    frontier.add_candidate(1, {"a": 0.1, "b": 0.1, "c": 0.9, "d": 0.1})
    rng = random.Random(42)
    counts: dict[int, int] = {0: 0, 1: 0}
    for _ in range(1000):
        idx = frontier.sample_parent(rng)
        counts[idx] = counts.get(idx, 0) + 1
    # Candidate 0 is best at 2 instances, candidate 1 at 1
    # Ratio should be ~2:1
    assert counts[0] > counts[1] * 2.5  # roughly 2:1 ratio


def test_best_candidate_idx() -> None:
    """best_candidate_idx returns highest mean score."""
    frontier = ParetoFrontier(["a", "b", "c"])
    frontier.add_candidate(0, {"a": 0.5, "b": 0.5, "c": 0.5})
    frontier.add_candidate(1, {"a": 0.8, "b": 0.8, "c": 0.1})
    frontier.add_candidate(2, {"a": 0.7, "b": 0.7, "c": 0.7})
    assert frontier.best_candidate_idx() == 2  # mean=0.7 is highest


def test_find_merge_candidates() -> None:
    """find_merge_candidates returns two distinct non-dominated candidates."""
    frontier = ParetoFrontier(["a", "b"])
    frontier.add_candidate(0, {"a": 0.9, "b": 0.1})
    frontier.add_candidate(1, {"a": 0.1, "b": 0.9})
    pair = frontier.find_merge_candidates(random.Random(0))
    assert pair is not None
    assert pair[0] != pair[1]


def test_find_merge_candidates_single_candidate() -> None:
    """Returns None when fewer than 2 candidates."""
    frontier = ParetoFrontier(["a"])
    frontier.add_candidate(0, {"a": 0.5})
    assert frontier.find_merge_candidates(random.Random(0)) is None


def test_get_state_load_state() -> None:
    """State serialization round-trips correctly."""
    frontier = ParetoFrontier(["a", "b", "c"])
    frontier.add_candidate(0, {"a": 0.5, "b": 0.6, "c": 0.7})
    frontier.add_candidate(1, {"a": 0.9, "b": 0.3, "c": 0.7})
    state = frontier.get_state()

    frontier2 = ParetoFrontier(["a", "b", "c"])
    frontier2.load_state(state)
    assert frontier2.n_candidates == 2
    assert frontier2.aggregate_score(0) == pytest_approx(0.6)
    assert frontier2.candidate_scores(1)["a"] == 0.9


def pytest_approx(expected: float) -> Any:
    """Simple approx helper."""
    class _Approx:
        def __init__(self, val: float) -> None:
            self.val = val
        def __eq__(self, other: object) -> bool:
            return abs(self.val - float(other)) < 1e-6
    return _Approx(expected)
