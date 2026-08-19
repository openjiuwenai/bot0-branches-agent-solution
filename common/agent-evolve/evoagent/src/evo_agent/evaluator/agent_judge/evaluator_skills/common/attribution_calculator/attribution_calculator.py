#!/usr/bin/env python3
"""Dimension scoring utilities for the attribution agent.

Two modes:

1. **Score computation** (--weights + --judgments):
   Compute overall_score from dimension weights and scores.

       python3 attribution_calculator.py \\
           --weights '{"task_completion":0.3,"safety":0.25,...}' \\
           --judgments '{"task_completion":0.75,"safety":1.0,...}'

   Output: {"overall_score": 0.82, "gate_applied": true, ...}

2. **Threshold checking** (--thresholds + --judgments):
   Check each dimension score against its threshold.

       python3 attribution_calculator.py \\
           --thresholds '{"task_completion":0.5,"safety":0.8,...}' \\
           --judgments '{"task_completion":0.75,"safety":1.0,...}'

   Output: {"checks": [...], "all_pass": true, "failed": []}

Use ``python3 attribution_calculator.py --help`` for all options.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys

logger = logging.getLogger(__name__)


def _clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def _weighted_avg(scores: dict[str, float], weights: dict[str, float]) -> float:
    """Normalized weighted average; falls back to simple mean if total weight is 0."""
    if not scores:
        return 0.0
    num = 0.0
    den = 0.0
    for dim, score in scores.items():
        w = float(weights.get(dim, 0.0))
        num += w * score
        den += w
    if den <= 0.0:
        return _clamp(sum(scores.values()) / len(scores))
    return num / den


def compute_score(
    judgments: dict[str, float],
    weights: dict[str, float],
    *,
    gate: str = "task_completion",
) -> dict:
    """Return ``{overall_score, gate_applied, gate_score, weighted_avg}``."""
    if not judgments:
        return {
            "overall_score": 0.0,
            "gate_applied": False,
            "gate_score": None,
            "weighted_avg": None,
        }

    # Gate mode: multiply gate dimension score by weighted average of the rest.
    if gate and gate in judgments:
        gate_score = float(judgments[gate])
        others = {d: s for d, s in judgments.items() if d != gate}
        if not others:
            return {
                "overall_score": _clamp(gate_score),
                "gate_applied": True,
                "gate_score": gate_score,
                "weighted_avg": None,
            }
        wavg = _weighted_avg(others, weights)
        return {
            "overall_score": _clamp(gate_score * wavg),
            "gate_applied": True,
            "gate_score": gate_score,
            "weighted_avg": _clamp(wavg),
        }

    # Fallback: plain weighted average (gate dimension absent or disabled).
    wavg = _weighted_avg(judgments, weights)
    return {
        "overall_score": _clamp(wavg),
        "gate_applied": False,
        "gate_score": None,
        "weighted_avg": _clamp(wavg),
    }


def check_thresholds(
    judgments: dict[str, float],
    thresholds: dict[str, float],
) -> dict:
    """Check each dimension score against its threshold.

    Returns ``{checks: [...], all_pass: bool, failed: [...]}``.
    """
    checks: list[dict] = []
    failed: list[str] = []
    for dim, threshold in sorted(thresholds.items()):
        score = judgments.get(dim)
        if score is None:
            checks.append(
                {"dimension": dim, "score": None, "threshold": threshold, "pass": False}
            )
            failed.append(dim)
            continue
        passed = float(score) >= float(threshold)
        checks.append(
            {"dimension": dim, "score": float(score), "threshold": float(threshold), "pass": passed}
        )
        if not passed:
            failed.append(dim)
    return {"checks": checks, "all_pass": len(failed) == 0, "failed": failed}


def _parse_json_arg(raw: str, label: str) -> dict[str, float]:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError(f"--{label} is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"--{label} must be a JSON object, got {type(data).__name__}")
    result: dict[str, float] = {}
    for key, value in data.items():
        if not isinstance(key, str):
            raise ValueError(f"--{label} keys must be strings")
        try:
            result[key] = float(value)
        except (TypeError, ValueError) as exc:
            raise ValueError(
                f"--{label}[{key!r}] must be a number, got {value!r}"
            ) from exc
    return result


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser(
        description="Dimension scoring utilities: score computation or threshold checking."
    )
    parser.add_argument(
        "--judgments",
        required=True,
        help='JSON object mapping dimension names to scores (0-1).',
    )
    parser.add_argument(
        "--weights",
        help='JSON object mapping dimension names to weights (score mode).',
    )
    parser.add_argument(
        "--thresholds",
        help='JSON object mapping dimension names to thresholds (check mode).',
    )
    parser.add_argument(
        "--gate",
        default="task_completion",
        help="Gate dimension for score mode (default: task_completion). Empty to disable.",
    )
    args = parser.parse_args()

    if not args.weights and not args.thresholds:
        parser.error("Provide --weights (score mode) or --thresholds (check mode).")

    try:
        judgments = _parse_json_arg(args.judgments, "judgments")
        if args.thresholds:
            thresholds = _parse_json_arg(args.thresholds, "thresholds")
        else:
            weights = _parse_json_arg(args.weights, "weights")
    except ValueError as exc:
        logger.error(str(exc))
        sys.exit(1)

    if args.thresholds:
        result = check_thresholds(judgments, thresholds)
    else:
        result = compute_score(judgments, weights, gate=args.gate)

    sys.stdout.write(json.dumps(result, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
