"""LogTraceSource —— log 模式: 读 output_dir 下的 JSONL 归档 (现 routes 逻辑迁入)。

每个会话一个 {conversation_id}.jsonl, 每行一个 record (trace_assembler 产出)。
多 agent: output_dirs 映射 agent_name → output_dir; agent_name=None 聚合全部。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import aiofiles

from agent_adapter.trace_source.base import TraceSource


class LogTraceSource:
    """log 模式 TraceSource: 从 output_dir 归档文件读取 records。"""

    def __init__(self, output_dirs: dict[str, Path] | None = None) -> None:
        # agent_name → output_dir; 单 agent 可只传一项
        self._output_dirs = dict(output_dirs or {})

    def _dirs_for(self, agent_name: str | None) -> list[Path]:
        if agent_name is not None:
            d = self._output_dirs.get(agent_name)
            return [d] if d is not None else []
        return [d for d in self._output_dirs.values() if d is not None]

    async def list_conversations(self, agent_name: str | None = None) -> list[str]:
        ids: set[str] = set()
        for d in self._dirs_for(agent_name):
            if d.exists():
                ids.update(f.stem for f in d.glob("*.jsonl") if f.is_file())
        return sorted(ids)

    async def get_records(self, agent_name: str | None, conversation_id: str) -> list[dict[str, Any]]:
        for d in self._dirs_for(agent_name):
            path = d / f"{conversation_id}.jsonl"
            if path.is_file():
                async with aiofiles.open(path, encoding="utf-8") as f:
                    content = await f.read()
                # 复用同步解析 (aiofiles 无逐行 async 优势, 小文件直接 split)
                records: list[dict[str, Any]] = []
                for line in content.split("\n"):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rec = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(rec, dict):
                        records.append(rec)
                return records
        return []
