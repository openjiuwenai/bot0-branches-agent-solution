# -*- coding: utf-8 -*-
"""calc_service —— solve/ 模块的分层重写版本（独立前后端项目）。

项目根下辖：
  - backend/  服务端（Flask :5081，含 api/service/calculation/scheduling/data/models
             + 自包含 refinery_data.xlsx），入口 python -m calc_service.backend.app
  - frontend/ 客户端（Next.js :5082，BFF 直连 :5081）

与 solve/ 并存运行（默认端口 5081，solve/ 为 5080）。详见 README.md。
"""
