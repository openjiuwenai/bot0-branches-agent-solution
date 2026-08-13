# -*- coding: utf-8 -*-
"""data_service 只读仓库层（repositories）。

各模块为纯函数，接收 sqlalchemy.orm.Session，返回 list[dict]。
Decimal 统一转 float，None 安全处理。
"""
