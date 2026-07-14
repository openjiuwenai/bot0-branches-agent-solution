"""SSEEvent — Server-Sent Events 事件数据类 + 事件类型 / Pipeline 阶段常量。"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class EventType(StrEnum):
    """SSE 事件类型。"""

    PROGRESS = "progress"
    LOG = "log"
    COMPLETED = "completed"
    ERROR = "error"


class PipelinePhase(StrEnum):
    """Pipeline 阶段名 — 集中管理 phase 常量，避免散落字符串。"""

    TRAIN_BEGIN = "train_begin"
    EPOCH_BEGIN = "epoch_begin"
    ROLLOUT = "rollout"
    ROLLOUT_DONE = "rollout_done"
    EVALUATE = "evaluate"
    ATTRIBUTE = "attribute"
    REFLECT = "reflect"
    AGGREGATE = "aggregate"
    SELECT = "select"
    APPLY = "apply"
    SKILL_SYNC = "skill_sync"
    VALIDATION = "validation"
    EPOCH_END = "epoch_end"
    TRAIN_END = "train_end"


@dataclass(frozen=True)
class SSEEvent:
    """Server-Sent Events 事件。

    Attributes:
        id: 事件自增 ID。
        event: 事件类型（progress, log, completed, error）。
        data: 事件数据。
        timestamp: 事件时间戳（秒级 Unix 时间）。
    """

    id: int
    event: str
    data: dict[str, Any]
    timestamp: float = field(default_factory=time.time)
