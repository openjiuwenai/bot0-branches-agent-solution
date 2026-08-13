# -*- coding: utf-8 -*-
"""calc_service 数据模型层。

合并了原 solve/refinery_model.py 与 solve/scheduling/models.py + models_v2.py，
消灭 v1/v2 双轨模型与 data_adapter 桥接层。
"""
from .refinery import (
    Product,
    DeviceBase,
    ProcessingUnit,
    Tank,
    EnergyConsumption,
    MaterialFlow,
    RefineryScenario,
)
from .scheduling import (
    ProductionPlansInput,
    ProductionPlanDetail,
    SchedulingTask,
    DeviceCapacity,
)

__all__ = [
    "Product",
    "DeviceBase",
    "ProcessingUnit",
    "Tank",
    "EnergyConsumption",
    "MaterialFlow",
    "RefineryScenario",
    "ProductionPlansInput",
    "ProductionPlanDetail",
    "SchedulingTask",
    "DeviceCapacity",
]
