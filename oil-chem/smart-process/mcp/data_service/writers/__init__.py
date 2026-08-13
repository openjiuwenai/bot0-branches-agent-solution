# -*- coding: utf-8 -*-
"""data_service 写仓库层（writers）。

各模块为纯函数，接收外部 Session，由调用方（FastAPI 路由）控制事务提交。
写入使用 ON CONFLICT DO UPDATE 实现 upsert 语义。
"""
