# -*- coding: utf-8 -*-
"""calc_service Flask 入口。

替代 solve/web_app.py 的 4275 行 God File：
  - 路由全部下沉到 api/ 的 4 个 Blueprint，本文件只做注册
  - /api/solve 旧三模式接口按约定不迁移
  - 端口 5081，与 solve/（5080）共存，自包含 refinery_data.xlsx（与 solve/ 解耦）

启动：python -m calc_service.backend.app  或  set SOLVER_PORT=5081 && python -m calc_service.backend.app
"""
import os

from flask import Flask, render_template, jsonify
from flask_cors import CORS

from .api import crud_bp, yield_bp, plan_bp, task_bp, price_cost_bp, side_line_bp
from .logger import get_logger
from .data.db import init_db


def create_app() -> Flask:
    app = Flask(__name__, template_folder='templates')
    CORS(app)

    logger = get_logger()

    # 注册 Blueprint（路由均以 /api 开头）
    app.register_blueprint(crud_bp)
    app.register_blueprint(yield_bp)
    app.register_blueprint(plan_bp)
    app.register_blueprint(task_bp)
    app.register_blueprint(price_cost_bp)
    app.register_blueprint(side_line_bp)

    # 幂等地建 schema/表（CREATE ... IF NOT EXISTS）。
    # 首次部署还需跑 migrate_excel_to_db 导入历史数据；此处仅保证结构存在。
    try:
        init_db()
        logger.info("solve_db schema/表已就绪（init_db）")
    except Exception as e:
        # 启动期 DB 不可用不应静默——但也不应让 import 失败（测试需 import app）。
        logger.error(f"init_db 失败，请检查 DATABASE_URL 与 PG 连通性: {e}")

    # 调试用页面（与 solve/ 一致，直接复用模板）
    @app.route('/')
    def index_page():
        return render_template('index.html')

    @app.route('/scheduling')
    def scheduling_page():
        return render_template('scheduling_new.html')

    @app.route('/api/health')
    def health():
        return jsonify({'success': True, 'service': 'calc_service', 'port': os.getenv('SOLVER_PORT', 5081)})

    logger.info("calc_service 应用已创建，注册路由：crud / yield / plan / task")
    return app


app = create_app()


if __name__ == '__main__':
    # 默认 5081，与 solve/ 的 5080 隔离；可通过 SOLVER_PORT 覆盖
    port = int(os.getenv('SOLVER_PORT', 5081))
    # threaded=True：开发服务器开多线程，避免单次 comprehensive_solve 等慢请求
    # 堵死 /health 及其它请求（曾出现 5081 堆 16 条 ESTABLISHED、health 10s 无响应）。
    app.run(debug=False, host='0.0.0.0', port=port, threaded=True)
