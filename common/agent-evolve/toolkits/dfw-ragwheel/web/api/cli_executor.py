#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence

logger = logging.getLogger(__name__)


def _find_cli_executable() -> Sequence[str]:
    """优先使用已安装的 ``dfw-rag`` 入口，否则回退到模块执行。"""
    exe = shutil.which("dfw-rag")
    if exe:
        return [exe]
    return [sys.executable, "-m", "rag_extract_split.cli.main"]


def _project_root_from_caller() -> Path:
    """通过本文件位置推断项目根目录（web/api/cli_executor.py -> 项目根）。"""
    return Path(__file__).resolve().parent.parent.parent


def run_cli_command(
    args_list: Sequence[str],
    cwd: Optional[Path] = None,
    env: Optional[Mapping[str, str]] = None,
    timeout: Optional[int] = 600,
) -> Dict[str, Any]:
    """执行 ``dfw-rag`` CLI 命令并返回统一结果字典。

    返回字典字段：
        - success (bool): 返回码是否为 0
        - returncode (int): 进程返回码；异常时为 -1
        - stdout (str)
        - stderr (str)
        - duration (float): 耗时（秒）
        - command (str): 实际执行的命令字符串
        - message (str): 简要说明
    """
    cmd = list(_find_cli_executable()) + list(args_list)
    start = time.time()

    run_env = os.environ.copy()
    if env:
        run_env.update(env)
    # 强制确保 DFW_RAG_HOME 指向项目根目录
    run_env.setdefault("DFW_RAG_HOME", str(_project_root_from_caller()))

    logger.info("Run CLI: %s | cwd=%s | timeout=%s", cmd, cwd, timeout)
    try:
        result = subprocess.run(
            cmd,
            cwd=cwd,
            env=run_env,
            capture_output=True,
            text=True,
            timeout=timeout,
            encoding="utf-8",
            errors="replace",
        )
        duration = time.time() - start
        logger.info(
            "CLI finished in %.3fs | returncode=%s | stdout_chars=%s | stderr_chars=%s",
            duration,
            result.returncode,
            len(result.stdout),
            len(result.stderr),
        )
        return {
            "success": result.returncode == 0,
            "returncode": result.returncode,
            "stdout": result.stdout,
            "stderr": result.stderr,
            "duration": round(duration, 3),
            "command": " ".join(str(c) for c in cmd),
            "message": "CLI 执行成功" if result.returncode == 0 else "CLI 执行失败",
        }
    except subprocess.TimeoutExpired as exc:
        duration = time.time() - start
        logger.warning("CLI timeout after %.3fs", duration)
        return {
            "success": False,
            "returncode": -1,
            "stdout": exc.stdout or "",
            "stderr": (exc.stderr or "") + f"\n[timeout after {timeout}s]",
            "duration": round(duration, 3),
            "command": " ".join(str(c) for c in cmd),
            "message": f"CLI 执行超时（{timeout}s）",
        }
    except Exception as exc:  # noqa: BLE001
        duration = time.time() - start
        logger.exception("CLI execution failed")
        return {
            "success": False,
            "returncode": -1,
            "stdout": "",
            "stderr": f"执行异常: {exc}",
            "duration": round(duration, 3),
            "command": " ".join(str(c) for c in cmd),
            "message": f"CLI 执行异常: {exc}",
        }
