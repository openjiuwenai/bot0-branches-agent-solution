"""审计日志 — 只追加、不可篡改。"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any

from ..config import AuditConfig


logger = logging.getLogger(__name__)


@dataclass
class AuditEntry:
    """审计日志条目。"""
    timestamp: str
    agent_id: str
    principal: str
    mode: str
    sql_template_hash: str
    params_summary: str  # 已脱敏
    affected_rows: int
    duration_ms: int
    result_status: str
    client_ip: str = ""


class AuditLogger:
    """审计日志记录器。"""

    def __init__(self, config: AuditConfig) -> None:
        self._config = config
        self._enabled = config.enabled

    def log(self, entry: AuditEntry) -> None:
        """记录一条审计日志。"""
        if not self._enabled:
            return

        line = json.dumps(asdict(entry), ensure_ascii=False)

        if self._config.sink == "file":
            self._write_file(line)
        elif self._config.sink == "logger":
            logger.info("AUDIT: %s", line)
        else:
            # kafka 等其他 sink 预留
            logger.info("AUDIT: %s", line)

    def _write_file(self, line: str) -> None:
        path = Path(self._config.path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as f:
            f.write(line + "\n")

    @staticmethod
    def mask_params(params: list[Any], sensitive_names: list[str] | None = None) -> str:
        """脱敏参数摘要：只保留类型和长度，值替换为 ***。"""
        parts = []
        for p in params:
            if isinstance(p, str):
                parts.append(f"str({len(p)})=***")
            elif isinstance(p, (int, float)):
                parts.append(f"{type(p).__name__}=***")
            else:
                parts.append(f"{type(p).__name__}=***")
        return ", ".join(parts)
