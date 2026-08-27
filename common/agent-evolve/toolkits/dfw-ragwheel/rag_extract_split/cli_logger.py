#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CLI execution logger.

记录每次 ``dfw-rag`` 命令的执行时间、命令内容、工作目录、执行结果以及
stdout/stderr 摘要。日志以 JSONL 格式写入 ``logs/cli_execution.log``，
并按天轮转保留最近 7 天。
"""
from __future__ import annotations

import functools
import io
import json
import logging
import logging.handlers
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, Optional, TypeVar

F = TypeVar("F", bound=Callable[..., Any])

_PROJECT_ROOT = Path(__file__).resolve().parent.parent
_LOG_DIR = _PROJECT_ROOT / "logs"
_LOG_DIR.mkdir(parents=True, exist_ok=True)

_CLI_LOG_FILE = _LOG_DIR / "cli_execution.log"

_logger = logging.getLogger("rag_extract_split.cli_execution")
_logger.setLevel(logging.INFO)
# 避免重复添加 handler（例如模块被多次导入或在测试中被 reload）
if not _logger.handlers:
    _handler = logging.handlers.TimedRotatingFileHandler(
        _CLI_LOG_FILE,
        when="midnight",
        interval=1,
        backupCount=7,
        encoding="utf-8",
        utc=False,
    )
    # 直接输出 JSON 行，每行一条记录
    _handler.setFormatter(logging.Formatter("%(message)s"))
    _logger.addHandler(_handler)


def _now_iso() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).astimezone().isoformat()


def _truncate(text: Optional[str], limit: int = 2000) -> str:
    if not text:
        return ""
    if len(text) <= limit:
        return text
    return text[:limit] + f"\n...（截断，原始长度 {len(text)}）"


def _map_returncode(returncode: Any) -> int:
    if returncode is None:
        return 0
    try:
        return int(returncode)
    except (TypeError, ValueError):
        return 1


def format_command(argv: Optional[Iterable[str]] = None) -> list[str]:
    """将命令行参数格式化为列表，默认使用 ``sys.argv[1:]``。"""
    if argv is None:
        argv = sys.argv[1:]
    return [str(a) for a in argv]


def log_execution(
    cmd: Iterable[str],
    status: str,
    result: Dict[str, Any],
    duration: float,
    cwd: Optional[str] = None,
) -> None:
    """记录一次 CLI 执行结果。

    Args:
        cmd: 命令参数列表（不含可执行文件名）。
        status: ``success`` 或 ``failure``。
        result: 执行结果字典，建议包含 ``returncode``、``stdout``、``stderr``、
            ``error`` 等字段。
        duration: 耗时（秒）。
        cwd: 工作目录，默认当前工作目录。
    """
    returncode = _map_returncode(result.get("returncode"))
    record: Dict[str, Any] = {
        "timestamp": _now_iso(),
        "cwd": cwd or os.getcwd(),
        "command": [str(c) for c in cmd],
        "cmdline": "dfw-rag " + subprocess.list2cmdline([str(c) for c in cmd]),
        "status": status,
        "returncode": returncode,
        "duration_sec": round(duration, 4),
        "stdout_summary": _truncate(result.get("stdout")),
        "stderr_summary": _truncate(result.get("stderr")),
        "error": result.get("error", ""),
    }
    _logger.info(json.dumps(record, ensure_ascii=False))


def log_cli_execution(func: F) -> F:
    """装饰器：捕获 stdout/stderr 并记录函数执行结果。

    通常用于装饰 ``rag_extract_split.cli.main:main``，使其在调用子命令前后
    自动写入 ``logs/cli_execution.log``。
    """

    @functools.wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        start = time.perf_counter()
        cmd = format_command()
        cwd = os.getcwd()

        old_stdout = sys.stdout
        old_stderr = sys.stderr
        stdout_buf = io.StringIO()
        stderr_buf = io.StringIO()
        sys.stdout = stdout_buf
        sys.stderr = stderr_buf

        result: Dict[str, Any] = {}
        status = "success"
        returncode = 0
        try:
            rc = func(*args, **kwargs)
            returncode = _map_returncode(rc)
            status = "success" if returncode == 0 else "failure"
            result["returncode"] = returncode
        except Exception as exc:
            status = "failure"
            returncode = 1
            result["returncode"] = returncode
            result["error"] = f"{type(exc).__name__}: {exc}"
            raise
        finally:
            captured_stdout = stdout_buf.getvalue()
            captured_stderr = stderr_buf.getvalue()
            sys.stdout = old_stdout
            sys.stderr = old_stderr

            # 将捕获的输出写回终端，保证用户仍能看到正常的命令输出
            if captured_stdout:
                old_stdout.write(captured_stdout)
                if not captured_stdout.endswith("\n"):
                    old_stdout.write("\n")
                old_stdout.flush()
            if captured_stderr:
                old_stderr.write(captured_stderr)
                if not captured_stderr.endswith("\n"):
                    old_stderr.write("\n")
                old_stderr.flush()

            result["stdout"] = captured_stdout
            result["stderr"] = captured_stderr
            duration = time.perf_counter() - start
            log_execution(cmd, status, result, duration, cwd=cwd)

    return wrapper  # type: ignore[return-value]
