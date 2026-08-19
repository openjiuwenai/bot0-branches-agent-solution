#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)


def now_ms() -> int:
    return int(time.time() * 1000)


def log_dir() -> Path:
    # 延迟导入 CONFIG，避免与 config.models 形成循环导入
    from rag_extract_split.config.settings import CONFIG

    d = str(CONFIG.get("logging", {}).get("dir") or "logs")
    p = Path(d)
    p.mkdir(parents=True, exist_ok=True)
    return p


def append_jsonl(path: Path, record: Dict[str, Any]) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except (OSError, TypeError, ValueError):
        # trace 失败不影响主流程
        logger.debug("failed to append jsonl %s", path, exc_info=True)


def append_pretty_json_block(path: Path, record: Dict[str, Any]) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False, indent=2))
            f.write("\n\n")
    except (OSError, TypeError, ValueError):
        logger.debug("failed to append pretty json %s", path, exc_info=True)


def truncate(s: str, max_len: int = 480) -> str:
    t = str(s or "").strip().replace("\r", " ").replace("\n", " ")
    if len(t) <= max_len:
        return t
    return t[: max(1, max_len - 1)] + "…"


def set_temp_env_for_proxy(enabled: bool, https_proxy: str, http_proxy: str, no_proxy: str) -> Dict[str, Optional[str]]:
    """返回 old_env，用于 finally 恢复。"""
    if not enabled:
        return {}
    updates: Dict[str, str] = {}
    if https_proxy:
        updates["https_proxy"] = https_proxy
        updates["HTTPS_PROXY"] = https_proxy
    if http_proxy:
        updates["http_proxy"] = http_proxy
        updates["HTTP_PROXY"] = http_proxy
    if no_proxy:
        updates["no_proxy"] = no_proxy
        updates["NO_PROXY"] = no_proxy
    old: Dict[str, Optional[str]] = {}
    for k, v in updates.items():
        old[k] = os.environ.get(k)
        os.environ[k] = v
    return old


def restore_env(old: Dict[str, Optional[str]]) -> None:
    for k, v in old.items():
        if v is None:
            os.environ.pop(k, None)
        else:
            os.environ[k] = v

