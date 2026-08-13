# -*- coding: utf-8 -*-
"""data_service —— 慧炼共享数据访问包。

封装对两套 PostgreSQL schema 的读写：
  - public      —— 慧炼主库（md_material / md_device / crude_types / material_price 等）
  - solve_db    —— 求解器库（devices_units / devices_tanks / side_lines /
                    device_yields / material_flows 等）

设计要点：
  - 全部使用 sqlalchemy.text() 裸 SQL，不引入 ORM 模型
  - 读仓库（repositories）为纯函数，接收外部 Session，返回 list[dict]
  - 写仓库（writers）同样为纯函数，由 FastAPI 路由层控制事务提交
  - engine 通过 connect_args 设 search_path=solve_db,public，裸表名优先命中 solve_db

供 backend/app/api/data_routes.py 等 FastAPI 路由复用，亦可在脚本中独立使用。
"""
