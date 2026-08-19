#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import logging
import os
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

# 确保项目根目录在 sys.path 中，使 `python web/app.py` 可直接运行
_PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.append(str(_PROJECT_ROOT))

from flask import Flask, jsonify, render_template
from flask_cors import CORS

from web.api.routes import api_bp
from web.config import config
from web.llmkit_routes import llm_bp


def _ensure_runtime_dirs(app: Flask) -> None:
    """确保上传目录与日志目录存在。"""
    try:
        config.UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
        config.LOG_DIR.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        app.logger.warning("创建运行时目录失败: %s", exc)


def _setup_logging(app: Flask) -> None:
    """配置日志输出到 logs/web_execution.log。"""
    log_file = config.LOG_DIR / "web_execution.log"
    handler = logging.FileHandler(log_file, encoding="utf-8")
    handler.setFormatter(
        logging.Formatter(
            "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
        )
    )
    handler.setLevel(logging.INFO)

    app.logger.addHandler(handler)
    app.logger.setLevel(logging.INFO)

    # 让 CLI 执行器日志也写入同一文件
    logging.getLogger("web.api.cli_executor").addHandler(handler)
    logging.getLogger("web.api.cli_executor").setLevel(logging.INFO)


def _template_globals(app: Flask) -> None:
    """注册模板全局函数。"""
    app.template_global("csrf_token")(lambda: "")
    app.template_global("now")(datetime.now)


def create_app() -> Flask:
    """Flask 应用工厂。"""
    project_root = Path(__file__).resolve().parent
    app = Flask(
        __name__,
        template_folder=str(project_root / "templates"),
        static_folder=str(project_root / "static"),
    )
    app.config.from_object(config)
    app.config["MAX_CONTENT_LENGTH"] = config.MAX_CONTENT_LENGTH

    # CORS：允许所有来源访问 /api/* 接口
    CORS(app, resources={r"/api/*": {"origins": "*"}})

    _ensure_runtime_dirs(app)
    _setup_logging(app)
    _template_globals(app)

    # 注册 API 蓝图，前缀 /api
    app.register_blueprint(api_bp, url_prefix="/api")
    # 注册 llmkit LLM 配置管理蓝图
    app.register_blueprint(llm_bp)

    @app.route("/")
    def index() -> str:
        """根路径渲染首页。"""
        return render_template("index.html", hide_navbar=True, hide_sidebar=True)

    @app.route("/extract/")
    def extract_page() -> str:
        """知识萃取页面。"""
        return render_template("extract.html")

    @app.route("/synthesize/")
    def synthesize_page() -> str:
        """知识合成页面。"""
        return render_template("synthesize.html")

    @app.route("/qc/")
    def qc_page() -> str:
        """知识质检页面。"""
        return render_template("qc.html")

    @app.route("/favicon.ico")
    def favicon() -> Any:
        return "", 204

    @app.errorhandler(404)
    def not_found(_exc: Any) -> Any:
        return jsonify({"success": False, "message": "接口不存在"}), 404

    return app


if __name__ == "__main__":
    app = create_app()
    app.run(
        host="0.0.0.0",
        port=int(os.getenv("DFW_RAG_PORT", "4398")),
        debug=os.getenv("DFW_RAG_DEBUG", "false").lower() in ("1", "true", "yes"),
    )
