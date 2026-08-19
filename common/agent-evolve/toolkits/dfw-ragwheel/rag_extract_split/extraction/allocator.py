#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import math
from typing import Any, Dict, List, Sequence, Set, Tuple

from rag_extract_split.config.settings import CONFIG


def ordered_answer_keys(badcases: Sequence[Dict[str, Any]]) -> List[str]:
    seen: Set[str] = set()
    out: List[str] = []
    for b in badcases:
        a = str(b.get("answer") or b.get("menu") or "").strip()
        if a and a not in seen:
            seen.add(a)
            out.append(a)
    return out


def proportional_integers(total: int, keys: Sequence[str], weights: Dict[str, int]) -> Dict[str, int]:
    """最大余额法：将 total 按 weights 比例整数分配（与 rag_extract_service 一致）。"""
    keys = list(keys)
    if total <= 0 or not keys:
        return {k: 0 for k in keys}
    s = sum(max(0, int(weights.get(k, 0))) for k in keys)
    if s <= 0:
        w = {k: 1 for k in keys}
        s = len(keys)
    else:
        w = {k: max(0, int(weights.get(k, 0))) for k in keys}
    out: Dict[str, int] = {k: 0 for k in keys}
    rem: List[Tuple[float, str]] = []
    allocated = 0
    for k in keys:
        raw = total * w[k] / s
        f = int(raw)
        out[k] = f
        allocated += f
        rem.append((raw - f, k))
    rem.sort(key=lambda x: (-x[0], x[1]))
    i = 0
    while allocated < total and i < len(rem):
        out[rem[i][1]] += 1
        allocated += 1
        i += 1
    return out


def _safe_rate(detail: Dict[str, Dict[str, Any]], answer: str) -> float:
    r = float((detail.get(answer) or {}).get("rate") or 0.0)
    return max(0.0, min(1.0, r))


def _allocation_config() -> Dict[str, Any]:
    cfg = CONFIG.get("allocation", {}) or {}
    if not isinstance(cfg, dict):
        cfg = {}
    return cfg


def _weights_soft_inverse(active: Sequence[str], detail: Dict[str, Dict[str, Any]], cfg: Dict[str, Any]) -> Dict[str, int]:
    eps = float(cfg.get("soft_inverse_eps", 0.03) or 0.03)
    mix = float(cfg.get("soft_inverse_mix", 0.55) or 0.55)
    power = float(cfg.get("soft_inverse_power", 0.5) or 0.5)
    eps = max(1e-6, eps)
    mix = max(0.0, min(1.0, mix))
    power = max(0.05, power)
    w: Dict[str, int] = {}
    for a in active:
        r = _safe_rate(detail, a)
        inv_scaled = (1.0 / (r + eps)) ** power
        blended = mix * inv_scaled + (1.0 - mix) * 1.0
        w[a] = max(1, int(1000.0 * blended))
    return w


def _weights_gap_power(active: Sequence[str], detail: Dict[str, Dict[str, Any]], cfg: Dict[str, Any]) -> Dict[str, int]:
    power = float(cfg.get("gap_power_p", 2.0) or 2.0)
    floor = float(cfg.get("gap_power_floor", 0.01) or 0.01)
    power = max(0.1, power)
    floor = max(1e-6, floor)
    w: Dict[str, int] = {}
    for a in active:
        r = _safe_rate(detail, a)
        score = max(floor, (1.0 - r) ** power)
        w[a] = max(1, int(1000.0 * score))
    return w


def _weights_softmax(active: Sequence[str], detail: Dict[str, Dict[str, Any]], cfg: Dict[str, Any]) -> Dict[str, int]:
    temperature = float(cfg.get("softmax_temperature", 0.45) or 0.45)
    temperature = max(1e-6, temperature)
    scores = [1.0 - _safe_rate(detail, a) for a in active]
    m = max(scores) if scores else 0.0
    exps = [math.exp((s - m) / temperature) for s in scores]
    z = sum(exps) if exps else 1.0
    w: Dict[str, int] = {}
    for a, e in zip(active, exps):
        prob = e / z if z > 0 else 0.0
        w[a] = max(1, int(1000.0 * prob))
    return w


def _weights_piecewise(active: Sequence[str], detail: Dict[str, Dict[str, Any]], cfg: Dict[str, Any]) -> Dict[str, int]:
    bands = cfg.get("piecewise_bands") or [
        {"max_rate": 0.4, "weight": 3.0},
        {"max_rate": 0.7, "weight": 2.0},
        {"max_rate": 0.9, "weight": 1.0},
        {"max_rate": 1.0, "weight": 0.6},
    ]
    if not isinstance(bands, list) or not bands:
        bands = [{"max_rate": 1.0, "weight": 1.0}]
    norm_bands: List[Tuple[float, float]] = []
    for b in bands:
        if not isinstance(b, dict):
            continue
        max_rate = max(0.0, min(1.0, float(b.get("max_rate", 1.0))))
        weight = max(1e-6, float(b.get("weight", 1.0)))
        norm_bands.append((max_rate, weight))
    norm_bands.sort(key=lambda x: x[0])
    if not norm_bands:
        norm_bands = [(1.0, 1.0)]
    w: Dict[str, int] = {}
    for a in active:
        r = _safe_rate(detail, a)
        assigned = norm_bands[-1][1]
        for mr, ww in norm_bands:
            if r <= mr:
                assigned = ww
                break
        w[a] = max(1, int(1000.0 * assigned))
    return w


def recall_based_weights(active: Sequence[str], detail: Dict[str, Dict[str, Any]]) -> Tuple[Dict[str, int], str]:
    cfg = _allocation_config()
    strategy = str(cfg.get("strategy") or "soft_inverse").strip().lower()
    if strategy == "gap_power":
        return _weights_gap_power(active, detail, cfg), "gap_power"
    if strategy == "softmax":
        return _weights_softmax(active, detail, cfg), "softmax"
    if strategy == "piecewise":
        return _weights_piecewise(active, detail, cfg), "piecewise"
    return _weights_soft_inverse(active, detail, cfg), "soft_inverse"


def allocate_round_category_counts(
    *,
    round_num: int,
    count: int,
    active: Sequence[str],
    badcases: Sequence[Dict[str, Any]],
    accumulated_qas_len: int,
    last_recall_detail: Dict[str, Dict[str, Any]],
) -> Tuple[Dict[str, int], int, str]:
    """
    首轮（round_num<=1）：每个待萃取答案类生成 1 条；budget = 活跃类数（= 本轮总生成条数）。
    第二轮起：本轮总生成额度 = count - 已成功萃取（冻结）的 QA 数，余量按可配置策略分配。
    """
    _ = badcases  # 保留参数以兼容上层调用
    act = list(active)
    if not act:
        return {}, 0, "无待萃取类别"
    if round_num <= 1:
        # 首轮：每类 1 条；总条数 = 活跃类数（通常等于 BadCase 中答案类别数）
        alloc = {a: 1 for a in act}
        budget = len(act)
        return alloc, budget, "首轮·每类1条"

    budget = int(count) - int(accumulated_qas_len)
    # 保底：每个未完全召回的答案类别至少分到 1 条生成额度
    budget = max(len(act), budget)
    iw, strategy_name = recall_based_weights(act, last_recall_detail)
    base = {a: 1 for a in act}
    rest = int(budget) - len(act)
    if rest > 0:
        extra = proportional_integers(rest, act, iw)
        for a in act:
            base[a] = int(base.get(a, 0)) + int(extra.get(a, 0))
    return base, budget, f"续轮·保底每类1条+余量按{strategy_name}"

