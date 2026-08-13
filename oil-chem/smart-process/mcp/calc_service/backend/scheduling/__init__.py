# -*- coding: utf-8 -*-
"""calc_service 排产层。

原 solve/scheduling/ 存在 v1(models.py)/v2(models_v2.py) 双轨模型 +
data_adapter.py 桥接层 + virtual_tank 概念。本层统一为：
  - switch_planner 减一线阀门切换：批次识别 + 切换组合枚举（只识别不优化，
    最优挑选在 calculation.batch_optimizer）
  - device_input_calc 装置进料计算（物料流口径与 calculate_direct 一致）

注：旧的 LP 排产（planner.py / plan_generator.py）已删除，
排产统一走 CP-SAT（crude_scheduling/ 包）。
"""
from .switch_planner import ValveSwitchPlanner
from .device_input_calc import (
    load_yield_tables,
    compute_device_inputs_by_mode,
)

__all__ = [
    "ValveSwitchPlanner",
    "load_yield_tables",
    "compute_device_inputs_by_mode",
]
