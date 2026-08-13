# -*- coding: utf-8 -*-
"""参数化规则引擎。

将原先散落在 solve_service._reassess_feasibility 和 _select_optimal 中的
硬编码可行性判断和选优逻辑抽取为可参数化的独立模块。

设计原则：
  - 规则引擎是纯确定性 Python，不做 LLM 调用
  - 同时支持内部调用（CombinationOutput 对象）和 MCP 调用（dict）
  - 默认参数等价于当前硬编码行为，向后兼容
"""

from .feasibility import assess_feasibility, DEFAULT_FEASIBILITY_RULES
from .selection import select_optimal, DEFAULT_SELECTION_STRATEGY

__all__ = [
    'assess_feasibility',
    'select_optimal',
    'DEFAULT_FEASIBILITY_RULES',
    'DEFAULT_SELECTION_STRATEGY',
]
