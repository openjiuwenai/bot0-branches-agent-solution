# -*- coding: utf-8 -*-
"""service 编排层。

承接原 api 层中的业务编排与 helper，让 api 路由回归"参数解析 + 响应封装"。
依赖方向：service → {calculation, scheduling, data} → models，config/logger 为底座。
service 不持有 Flask 对象，返回普通 dict，由路由层 jsonify。
"""
from .solve_service import SolveService
from .yield_service import YieldService

__all__ = ["SolveService", "YieldService"]
