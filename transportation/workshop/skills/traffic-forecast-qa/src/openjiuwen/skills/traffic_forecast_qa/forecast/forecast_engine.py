"""预测引擎接口与数据结构。"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any

import pandas as pd


@dataclass
class ForecastPoint:
    """单个预测点。"""
    ts: str          # ISO 时间戳
    value: float     # 点估计
    lower: float = 0.0  # 置信下界
    upper: float = 0.0  # 置信上界


@dataclass
class ForecastResult:
    """预测结果。"""
    method: str = ""
    points: list[ForecastPoint] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "method": self.method,
            "points": [
                {"ts": p.ts, "value": p.value, "lower": p.lower, "upper": p.upper}
                for p in self.points
            ],
            "metadata": self.metadata,
        }


class ForecastEngine(ABC):
    """预测引擎抽象基类。"""

    @property
    @abstractmethod
    def name(self) -> str:
        """方法名称。"""
        ...

    @abstractmethod
    def forecast(
        self,
        history: pd.DataFrame,
        forecast_start: str,
        forecast_end: str,
        granularity: str = "HOUR",
    ) -> ForecastResult:
        """执行预测。

        Args:
            history: 历史数据 DataFrame（列：ts, flow）
            forecast_start: 预测窗口起点（ISO）
            forecast_end: 预测窗口终点（ISO）
            granularity: 粒度（HOUR / DAY）
        """
        ...

    @staticmethod
    def _generate_timestamps(start: str, end: str, granularity: str) -> list[str]:
        """生成预测窗口内的时间戳序列。"""
        from datetime import datetime, timedelta
        dt_start = datetime.fromisoformat(start)
        dt_end = datetime.fromisoformat(end)
        step = timedelta(hours=1) if granularity == "HOUR" else timedelta(days=1)
        timestamps = []
        current = dt_start
        while current <= dt_end:
            timestamps.append(current.isoformat())
            current += step
        return timestamps
