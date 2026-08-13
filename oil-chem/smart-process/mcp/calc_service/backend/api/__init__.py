# -*- coding: utf-8 -*-
"""calc_service API 层（薄路由）。

原 solve/web_app.py 为 4275 行的 God File（36 路由 + 13 helper，0 类）。
本层拆分为 4 个 Flask Blueprint，路由只做参数解析与响应封装，业务编排与 helper 下沉到
service 层，service 再调用 data/calculation/scheduling：
  - crud_routes     装置/产品/连接/能耗/原油品种的增删改查（CRUD 工厂，消灭 16x 重复）
  - yield_routes    产品收率全集 / 排厂基础数据
  - plan_routes     generate_plan / comprehensive_solve / optimize_valve（共享 batch_optimizer）
  - task_routes     排厂任务 CRUD + 锁定/解锁

依赖方向：api → service → calculation/scheduling/data → models（单向，无环）。
"""
from .crud_routes import crud_bp
from .yield_routes import yield_bp
from .plan_routes import plan_bp
from .task_routes import task_bp
from .price_cost_routes import price_cost_bp
from .side_line_routes import side_line_bp

__all__ = ["crud_bp", "yield_bp", "plan_bp", "task_bp", "price_cost_bp", "side_line_bp"]
