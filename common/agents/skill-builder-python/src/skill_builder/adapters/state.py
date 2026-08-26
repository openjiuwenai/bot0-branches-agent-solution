# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path

from skill_builder.domain.execution import SkillBuilderState


class InMemoryStateStore:
    def __init__(self) -> None:
        self._states: dict[str, dict[str, object]] = {}
        self._lock = asyncio.Lock()

    async def load(self, workspace_id: str) -> SkillBuilderState | None:
        async with self._lock:
            value = self._states.get(workspace_id)
            return SkillBuilderState.from_dict(value) if value is not None else None

    async def save(self, state: SkillBuilderState) -> None:
        async with self._lock:
            self._states[state.workspace_id] = state.to_dict()


class JsonFileStateStore:
    """Small local state store suitable for CLI and standalone deployments."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, workspace_id: str) -> Path:
        normalized = re.sub(r"[^A-Za-z0-9._-]+", "-", workspace_id).strip("-._")[:80] or "workspace"
        digest = hashlib.sha256(workspace_id.encode("utf-8")).hexdigest()[:12]
        return self.root / f"{normalized}-{digest}.json"

    async def load(self, workspace_id: str) -> SkillBuilderState | None:
        path = self._path(workspace_id)
        if not path.is_file():
            return None
        # State documents are deliberately small. Keeping the atomic local-file
        # adapter synchronous avoids making checkpoint progress depend on the
        # host's default thread executor, which may be unavailable in sandboxed
        # workers or while an event loop is shutting down.
        value = path.read_text(encoding="utf-8")
        payload = json.loads(value)
        if not isinstance(payload, dict):
            raise ValueError(f"Invalid Skill Builder state file: {path}")
        return SkillBuilderState.from_dict(payload)

    async def save(self, state: SkillBuilderState) -> None:
        path = self._path(state.workspace_id)
        content = json.dumps(state.to_dict(), ensure_ascii=False, indent=2) + "\n"
        self._write_atomic(path, content)

    @staticmethod
    def _write_atomic(path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary_name, path)
        finally:
            if os.path.exists(temporary_name):
                os.unlink(temporary_name)


__all__ = ["InMemoryStateStore", "JsonFileStateStore"]
