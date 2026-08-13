# -*- coding: utf-8 -*-
"""计算结果数据结构 — 分离中间传递与最终输出。

重构前：CombinationResult 同时承担"PASS 间传递"和"对外输出"两个职责，
通过 _calc_results / _explanations / _batch_details / _monthly_load 等下划线
临时键在 dict 上搬运中间数据，职责混乱且易遗漏 pop。

重构后：
  - CombinationResult（batch_optimizer.py 内）：PASS 间中间结果，含 calc_results/
    explanations 等完整中间数据，存储在 pass1_results 中。
  - CombinationOutput（本文件）：对外输出的最终结果，只含前端/API 需要的字段，
    无下划线临时键。由 _finalize_combination_outputs 从 CombinationResult 填充。
"""
from dataclasses import dataclass, field
from typing import Dict, List


@dataclass
class CombinationOutput:
    """单个组合的最终输出结果（无中间数据，可直接序列化）。"""
    combination_id: str
    description: str
    switch_position: int
    initial_mode: str
    switches: Dict
    total_revenue: float
    batch_results: List[dict] = field(default_factory=list)
    hangmei_summary: dict = field(default_factory=dict)
    feasible: bool = True
    near_feasible: bool = False
    bottleneck: List[dict] = field(default_factory=list)
    infeasible_summary: str = ""
    tank_check_result: dict = field(default_factory=dict)
    batch_details: List[dict] = field(default_factory=list)
    monthly_load: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        """转换为 dict（供 API JSON 序列化 / _slim_combo_results 裁剪）。"""
        return {
            'combination_id': self.combination_id,
            'description': self.description,
            'switch_position': self.switch_position,
            'initial_mode': self.initial_mode,
            'switches': self.switches,
            'total_revenue': self.total_revenue,
            'batch_results': self.batch_results,
            'hangmei_summary': self.hangmei_summary,
            'feasible': self.feasible,
            'near_feasible': self.near_feasible,
            'bottleneck': self.bottleneck,
            'infeasible_summary': self.infeasible_summary,
            'tank_check_result': self.tank_check_result,
            'batch_details': self.batch_details,
            'monthly_load': self.monthly_load,
        }
