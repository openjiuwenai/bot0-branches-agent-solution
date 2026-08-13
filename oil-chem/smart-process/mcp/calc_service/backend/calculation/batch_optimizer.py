# -*- coding: utf-8 -*-
"""兼容入口 — 重构后拆分为 hangmei_optimizer / combination_evaluator / combination_optimizer。

保留此文件仅为不破坏现有 import 路径，新代码应直接从子模块导入。
"""
from .hangmei_optimizer import (
    HangmeiContext, build_hangmei_context,
    _compute_effective_input, _compute_device_effective_input,
    _resolve_hangmei_yield,
    _compute_combo_hangmei, _find_optimal_hangmei_start,
)
from .combination_evaluator import (
    CombinationResult, evaluate_combination, recompute_combination_economics,
    _precompute_hangmei_batch_data, _apply_monthly_capacity_reduction,
    _apply_hangmei_output_correction, _build_batch_details_with_overload,
)
from .combination_optimizer import optimize_combinations
