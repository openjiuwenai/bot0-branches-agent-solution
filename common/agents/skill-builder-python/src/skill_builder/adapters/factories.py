# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Small adapters for hosts that expose runtime factory callables."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


@dataclass(frozen=True, slots=True)
class FactoryWorkspacePort:
    factory: Callable[..., Any]

    def create_accessor(self, *, root: Path, workspace_id: str, purpose: str) -> Any:
        return self.factory(root=root, workspace_id=workspace_id, purpose=purpose)


__all__ = [
    "FactoryWorkspacePort",
]
