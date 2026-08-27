#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import logging
import os
from pathlib import Path

logger = logging.getLogger(__name__)


class Config:
    """Flask Web APP 配置。"""

    # 项目根目录：web/config.py -> 项目根
    PROJECT_ROOT: Path = Path(__file__).resolve().parent.parent

    # 上传文件保存目录
    UPLOAD_DIR: Path = PROJECT_ROOT / "web" / "uploads"

    # 日志目录（与 CLI 共享 logs/）
    LOG_DIR: Path = PROJECT_ROOT / "logs"

    # Flask 最大上传文件大小：500 MB
    MAX_CONTENT_LENGTH: int = 500 * 1024 * 1024

    # CLI 默认超时（秒）
    CLI_TIMEOUT: int = int(os.getenv("DFW_RAG_CLI_TIMEOUT", "600"))

    # 部署目录环境变量：与 CLI 保持一致，确保 registry/data 等路径统一
    DFW_RAG_HOME: str = os.getenv("DFW_RAG_HOME", str(PROJECT_ROOT))


config = Config()

# 统一 DFW_RAG_HOME 环境变量，使 web 进程与 CLI 子进程使用同一套配置/数据目录
os.environ.setdefault("DFW_RAG_HOME", str(config.PROJECT_ROOT))

# 兼容旧数据：若之前 web 进程把 LLM 配置写到了 web/data/llm_configs.json，
# 则迁移到项目根目录 data/llm_configs.json，避免配置“保存后实际未生效”。
_old_web_llm_configs = config.PROJECT_ROOT / "web" / "data" / "llm_configs.json"
_new_llm_configs = config.PROJECT_ROOT / "data" / "llm_configs.json"
if _old_web_llm_configs.exists():
    try:
        _new_llm_configs.parent.mkdir(parents=True, exist_ok=True)
        if _new_llm_configs.exists():
            _bak = config.PROJECT_ROOT / "data" / "llm_configs.json.bak"
            _bak.write_text(_new_llm_configs.read_text(encoding="utf-8"), encoding="utf-8")
        _new_llm_configs.write_text(_old_web_llm_configs.read_text(encoding="utf-8"), encoding="utf-8")
    except Exception:
        logger.warning("迁移旧 LLM 配置失败", exc_info=True)
