# -*- coding: utf-8 -*-
"""
排产计划数据模型（合并 solve/scheduling/models_v2.py，淘汰 v1 双轨）。

原 solve/scheduling/ 同时存在 models.py (v1) 与 models_v2.py (v2)，
两者 ProductionPlanDetail / DeviceCapacity 同名冲突，需 data_adapter 桥接。
本文件统一为一套模型：
  - 统一用 crude_stock_status（废弃 v1 的 tank_status）
  - 保留 hours 字段（v2 特有，支持小数天数）
  - 删除 virtual_tank 概念
"""
from dataclasses import dataclass, field
from datetime import date
from typing import Dict
import json


@dataclass
class ProductionPlansInput:
    """排产计划输入 - 单个原油品种的月度加工计划"""
    planned_month: str
    crude_type_id: str
    crude_type_name: str
    arrival_plan: Dict[str, float] = field(default_factory=dict)  # {日期: 到货吨数}
    monthly_processing_capacity: float = 0.0
    current_stock: float = 0.0
    max_level_stock: float = 0.0
    min_level_stock: float = 0.0
    cost: float = 1000.0

    def to_dict(self) -> dict:
        d = {k: v for k, v in self.__dict__.items()}
        d['arrival_plan'] = json.dumps(self.arrival_plan, ensure_ascii=False)
        return d


@dataclass
class ProductionPlanDetail:
    """排产计划明细 - 单日加工详情"""
    id: str
    plan_id: str
    plan_date: date
    day_of_month: int
    daily_input: float
    blend_detail: Dict[str, float] = field(default_factory=dict)        # {原油id: 吨}
    crude_stock_status: Dict[str, float] = field(default_factory=dict)  # {原油id: 库存吨}
    device_load_rate: float = 0.0
    hours: float = 24.0  # 支持小数天数（如 0.5 天）

    def to_dict(self) -> dict:
        return {
            'id': self.id,
            'plan_id': self.plan_id,
            'plan_date': self.plan_date.isoformat(),
            'day_of_month': self.day_of_month,
            'daily_input': self.daily_input,
            'blend_detail': json.dumps(self.blend_detail, ensure_ascii=False),
            'crude_stock_status': json.dumps(self.crude_stock_status, ensure_ascii=False),
            'device_load_rate': self.device_load_rate,
            'hours': self.hours,
        }


@dataclass
class SchedulingTask:
    """排厂任务"""
    plan_id: str
    planned_month: str
    status: str = 'draft'        # draft / generated / locked
    locked: bool = False
    created_at: str = ""
    updated_at: str = ""
    generated_at: str = ""

    def to_dict(self) -> dict:
        return {
            'plan_id': self.plan_id,
            'planned_month': self.planned_month,
            'status': self.status,
            'locked': self.locked,
            'created_at': self.created_at,
            'updated_at': self.updated_at,
            'generated_at': self.generated_at,
        }


@dataclass
class DeviceCapacity:
    """装置产能约束（LP 排产用，投影自 devices 表 cjy_01 行）。

    字段来源（见 scheduling_repo.load_device_capacity）：
      daily_max_input ← devices.safety_stock_thrd（安全库存阈值，非 DB 的 max_capacity 列）
      daily_min_input ← devices.low_safety_thrd（低安全阈值）

    注意：此处的 max/min 是 LP 求解器的每日加工量上下界，口径与评估层
    Device.effective_capacity（剩余可吃量，已扣负荷率与已占用量）不同——
    LP 只保证月度可排，单批次是否超容由 direct_calculator 校验。两层口径
    不同是有意设计。
    """
    device_id: str
    device_name: str
    daily_max_input: float
    daily_min_input: float

    def to_dict(self) -> dict:
        return {
            'device_id': self.device_id,
            'device_name': self.device_name,
            'daily_max_input': self.daily_max_input,
            'daily_min_input': self.daily_min_input,
        }
