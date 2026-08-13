# -*- coding: utf-8 -*-
"""calc_service 核心计算层。

原 solve/ 的计算逻辑全部散落在 web_app.py（calculate_direct 395行、
generate_explanation 662行、comprehensive_solve 636行）中，且收率选择逻辑
在两处各维护一套。本层将计算逻辑集中，与 HTTP 层解耦。

注：原 solve/solver.py 的 RefinerySolver（LP 求解减一线 X/Y 最优分流比例，
即 /api/solve 的"XY 联合优化"模式）未迁入——业务上减一线只需在边界工况
(MODE_JIAN1_TO_WAX/MODE_JIAN1_TO_DIESEL)间切换，不做按比例分流。批次评估由 calculate_direct
（直接物料流计算）+ batch_optimizer 完成。
"""
from .yield_resolver import resolve_yield_rate, YieldResult
from .direct_calculator import calculate_direct
from .economics import generate_explanation
from .batch_optimizer import (
    evaluate_combination,
    optimize_combinations,
    build_hangmei_context,
    HangmeiContext,
    CombinationResult,
)

__all__ = [
    "resolve_yield_rate",
    "YieldResult",
    "calculate_direct",
    "generate_explanation",
    "evaluate_combination",
    "optimize_combinations",
    "build_hangmei_context",
    "HangmeiContext",
    "CombinationResult",
]
