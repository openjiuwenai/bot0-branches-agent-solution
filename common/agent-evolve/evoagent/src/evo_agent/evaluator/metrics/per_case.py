"""Deterministic per-case metrics — inherit upstream ``Metric``.

Each metric's ``compute(prediction, label, **kwargs)`` returns a float in
``[0.0, 1.0]``. The ``prediction``/``label`` are whatever the caller passed
to ``MetricEvaluator.evaluate`` — the EvoAgent override unwraps
``expected_result`` from ``case.label`` and passes it as ``label``, and the
predict dict as ``prediction``. Both sides are stringified consistently
(matching ``ExactMatchMetric``'s ``str(...)`` convention) so dict/str/list
inputs compare predictably.
"""

from __future__ import annotations

import json
import re
from typing import Any

from openjiuwen.agent_evolving.evaluator.metrics.base import Metric


def _normalize(value: Any) -> str:
    """Lowercase, strip, and collapse internal whitespace — shared by contains/keyword."""
    return " ".join(str(value).strip().lower().split())


def _stringify(value: Any) -> str:
    """Stringify for substring/regex matching — dicts use compact JSON."""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, default=str)


class ContainsMetric(Metric):  # type: ignore[misc]
    """1.0 if the prediction contains the label as a substring, else 0.0.

    The label is treated as the expected substring and searched within the
    stringified prediction. ``case_insensitive`` applies ``_normalize`` to both
    sides before the substring check.
    """

    def __init__(self, *, case_insensitive: bool = False) -> None:
        self._case_insensitive = case_insensitive

    @property
    def name(self) -> str:
        return "contains"

    def compute(self, prediction: Any, label: Any, **kwargs: Any) -> float:
        pred = _stringify(prediction)
        expected = _stringify(label)
        if self._case_insensitive:
            pred = _normalize(pred)
            expected = _normalize(expected)
        # An empty expected string is trivially "contained" — but that masks a
        # misconfigured label. Treat empty label as a non-match (0.0).
        if not expected:
            return 0.0
        return 1.0 if expected in pred else 0.0


class KeywordHitMetric(Metric):  # type: ignore[misc]
    """1.0 if the prediction contains ANY of the expected keywords, else 0.0.

    ``label`` is a collection of keywords (list/set/tuple); a scalar is treated
    as a single-keyword list. Returns 1.0 as soon as one keyword is found as a
    substring of the prediction (OR semantics), 0.0 otherwise. An empty keyword
    set returns 0.0 (no signal). This is the binary "hit/miss" metric; for the
    fraction-of-keywords variant use :class:`KeywordRecallMetric`.
    """

    def __init__(self, *, case_insensitive: bool = False) -> None:
        self._case_insensitive = case_insensitive

    @property
    def name(self) -> str:
        return "keyword_hit"

    def compute(self, prediction: Any, label: Any, **kwargs: Any) -> float:
        keywords = _as_keyword_list(label)
        if not keywords:
            return 0.0
        pred = _stringify(prediction)
        if self._case_insensitive:
            pred = _normalize(pred)
            keywords = [_normalize(k) for k in keywords]
        return 1.0 if any(k and k in pred for k in keywords) else 0.0


class KeywordRecallMetric(Metric):  # type: ignore[misc]
    """Fraction of expected keywords present in the prediction.

    ``label`` is a collection of keywords (list/set/tuple). Returns
    ``#hit / #expected`` in ``[0.0, 1.0]``; an empty keyword set returns 0.0
    (no signal to recall). When ``label`` is a scalar, it is treated as a
    single-keyword list.
    """

    def __init__(self, *, case_insensitive: bool = False) -> None:
        self._case_insensitive = case_insensitive

    @property
    def name(self) -> str:
        return "keyword_recall"

    def compute(self, prediction: Any, label: Any, **kwargs: Any) -> float:
        keywords = _as_keyword_list(label)
        if not keywords:
            return 0.0
        pred = _stringify(prediction)
        if self._case_insensitive:
            pred = _normalize(pred)
            keywords = [_normalize(k) for k in keywords]
        hits = sum(1 for k in keywords if k and k in pred)
        return hits / len(keywords)


class LLMJudgeMetric(Metric):  # type: ignore[misc]
    """LLM 分类判定的 per-case 指标——judged_label 由调用方经 kwargs 注入。

    judge 是「分类器」：LLM 从用户声明的 ``labels`` 里选一个规范标签（不读 gold），
    pipeline 在独立的 judge 阶段算好每条 ``judged_label`` 再经 kwargs 注入。本指标只
    读出它落成 per-case correctness 值：``judged_label == str(gold)`` → 1.0（判对）、
    否则 0.0。原始 ``judged_label`` 仍保留在 materialized 上供 scorer 建多类混淆矩阵
    与诊断。缺省（无 kwarg / judged_label 不在 labels 中）→ 0.0（视为判错）。
    """

    @property
    def name(self) -> str:
        return "llm_judge"

    def compute(self, prediction: Any, label: Any, **kwargs: Any) -> float:  # noqa: ARG002
        judged = kwargs.get("judged_label", "")
        return float(judged == str(label))


class RegexMatchMetric(Metric):  # type: ignore[misc]
    """1.0 if the prediction matches a regex.

    ``pattern`` is used directly when provided; otherwise the label is treated
    as the regex string. ``fullmatch`` selects ``re.fullmatch`` over ``re.search``.
    Invalid regex patterns raise ``re.error`` at match time (fail-fast).
    """

    def __init__(self, pattern: str | None = None, *, fullmatch: bool = False) -> None:
        self._pattern = pattern
        self._fullmatch = fullmatch

    @property
    def name(self) -> str:
        return "regex"

    def compute(self, prediction: Any, label: Any, **kwargs: Any) -> float:
        source = self._pattern if self._pattern is not None else _stringify(label)
        if not source:
            return 0.0
        target = _stringify(prediction)
        matched = re.fullmatch(source, target) if self._fullmatch else re.search(source, target)
        return 1.0 if matched is not None else 0.0


class NumericToleranceMetric(Metric):  # type: ignore[misc]
    """1.0 if prediction and label numbers match within tolerance.

    Extracts the first number from each side (via regex) and compares with
    ``abs_tol`` and ``rel_tol`` (mirroring ``math.isclose``). Returns 0.0 if
    either side yields no parseable number.
    """

    _NUMBER_RE = re.compile(r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?")

    def __init__(self, *, abs_tol: float = 1e-6, rel_tol: float = 0.0) -> None:
        self._abs_tol = abs_tol
        self._rel_tol = rel_tol

    @property
    def name(self) -> str:
        return "numeric_tolerance"

    def compute(self, prediction: Any, label: Any, **kwargs: Any) -> float:
        pred_num = _extract_first_number(_stringify(prediction), self._NUMBER_RE)
        label_num = _extract_first_number(_stringify(label), self._NUMBER_RE)
        if pred_num is None or label_num is None:
            return 0.0
        # math.isclose semantics without importing here; both tolerances OR-ed.
        if abs(pred_num - label_num) <= self._abs_tol:
            return 1.0
        denom = max(abs(pred_num), abs(label_num))
        if denom > 0 and abs(pred_num - label_num) / denom <= self._rel_tol:
            return 1.0
        return 0.0


def _as_keyword_list(label: Any) -> list[str]:
    """Coerce a label into a list of keyword strings."""
    if isinstance(label, (list, tuple, set, frozenset)):
        return [_stringify(x) for x in label]
    if isinstance(label, dict):
        # Treat dict values as the keyword set (predictable, documented).
        return [_stringify(x) for x in label.values()]
    return [_stringify(label)]


def _extract_first_number(text: str, number_re: re.Pattern[str]) -> float | None:
    match = number_re.search(text)
    if match is None:
        return None
    try:
        return float(match.group(0))
    except ValueError:
        return None
