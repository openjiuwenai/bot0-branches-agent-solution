# -*- coding: utf-8 -*-
"""calc_service 数据访问层（PostgreSQL，schema solve_db）。

仅负责 DB 读写 + 构建领域对象，不做任何业务计算。
- RefineryRepository: 装置/产品/连接/能耗/价格/场景
- SchedulingRepository: 排产计划输入/明细/任务/产能

DB 基础设施（engine/SessionLocal/init_db）见 .db。
原 ExcelStore 已随 Excel→PG 迁移删除。
"""
from .db import engine, SessionLocal, get_session, init_db
from .refinery_repo import RefineryRepository
from .scheduling_repo import SchedulingRepository

__all__ = ["engine", "SessionLocal", "get_session", "init_db",
           "RefineryRepository", "SchedulingRepository"]
