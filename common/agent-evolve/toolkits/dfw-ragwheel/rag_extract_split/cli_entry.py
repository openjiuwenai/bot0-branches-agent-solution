#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""``dfw-rag`` 统一入口。

该模块作为 ``pyproject.toml`` 中 ``dfw-rag`` console_scripts 的实际入口：

    [project.scripts]
    dfw-rag = "rag_extract_split.cli_entry:main"

主要工作：
1. 处理 ``DFW_RAG_HOME`` 环境变量：若未设置，则默认使用本脚本所在目录的
   父目录（即项目根目录）。
2. 将工作目录切换到 ``DFW_RAG_HOME``，确保相对路径（如 ``data/``、``logs/``、
   ``models/``）在任意位置执行 CLI 时都能解析到部署目录。
3. 调用 ``rag_extract_split.cli.main.main()`` 转发所有命令行参数。
"""
from __future__ import annotations

import logging
import os
import sys
from pathlib import Path

logger = logging.getLogger(__name__)


def _resolve_home() -> Path:
    home = os.environ.get("DFW_RAG_HOME", "").strip()
    if home:
        path = Path(home)
        if not path.is_dir():
            raise RuntimeError(f"DFW_RAG_HOME 指向的目录不存在: {home}")
        return path

    # 默认：脚本位于 rag_extract_split/cli_entry.py，父目录即为项目根目录
    entry_file = Path(__file__).resolve()
    return entry_file.parent.parent


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    project_root = _resolve_home()
    os.environ["DFW_RAG_HOME"] = str(project_root)

    # 切换到部署目录，确保配置中的相对路径稳定解析
    try:
        os.chdir(project_root)
    except OSError as exc:
        logger.error("无法切换到 DFW_RAG_HOME: %s (%s)", project_root, exc)
        return 1

    # 延迟导入，避免在解析 home 前触发包内模块初始化
    from rag_extract_split.cli.main import main as cli_main

    return cli_main()


if __name__ == "__main__":
    raise SystemExit(main())
