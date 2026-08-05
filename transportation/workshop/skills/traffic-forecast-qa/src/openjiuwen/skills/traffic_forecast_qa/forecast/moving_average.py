"""L1 预测：移动平均 / 同比环比。"""

from __future__ import annotations

import pandas as pd

from .forecast_engine import ForecastEngine, ForecastPoint, ForecastResult


class MovingAverageForecaster(ForecastEngine):
    """移动平均预测器。

    基于最近 N 个同时段历史值的均值做预测，适合短期、数据平稳场景。
    同时段：按小时粒度取同一小时的历史均值。
    """

    def __init__(self, window: int = 7) -> None:
        """Args:
            window: 移动平均窗口（天数），默认取最近 7 天同时段。
        """
        self._window = window

    @property
    def name(self) -> str:
        return "moving_average"

    def forecast(
        self,
        history: pd.DataFrame,
        forecast_start: str,
        forecast_end: str,
        granularity: str = "HOUR",
    ) -> ForecastResult:
        if history.empty:
            return ForecastResult(method=self.name)

        # 提取小时作为分组键
        hist = history.copy()
        hist["hour"] = hist["ts"].dt.hour
        hist["dow"] = hist["ts"].dt.dayofweek

        timestamps = self._generate_timestamps(forecast_start, forecast_end, granularity)
        points: list[ForecastPoint] = []

        from datetime import datetime
        for ts_str in timestamps:
            dt = datetime.fromisoformat(ts_str)
            # 取同时段（同小时）的历史值
            if granularity == "HOUR":
                same_hour = hist[hist["hour"] == dt.hour]
            else:
                same_hour = hist

            if same_hour.empty:
                value = float(hist["flow"].mean())
            else:
                # 取最近 window 天的数据
                recent = same_hour.tail(self._window)
                value = float(recent["flow"].mean())

            # 置信区间：均值 ± 1 标准差
            if len(same_hour) > 1:
                std = float(same_hour["flow"].std())
            else:
                std = value * 0.1

            points.append(ForecastPoint(
                ts=ts_str,
                value=round(value, 1),
                lower=round(max(0, value - std), 1),
                upper=round(value + std, 1),
            ))

        return ForecastResult(
            method=self.name,
            points=points,
            metadata={"window_days": self._window, "data_points": len(history)},
        )


class YearOverYearForecaster(ForecastEngine):
    """同比预测器 — 基于去年同时段数据做预测。"""

    @property
    def name(self) -> str:
        return "year_over_year"

    def forecast(
        self,
        history: pd.DataFrame,
        forecast_start: str,
        forecast_end: str,
        granularity: str = "HOUR",
    ) -> ForecastResult:
        if history.empty:
            return ForecastResult(method=self.name)

        timestamps = self._generate_timestamps(forecast_start, forecast_end, granularity)
        points: list[ForecastPoint] = []

        from datetime import datetime, timedelta
        for ts_str in timestamps:
            dt = datetime.fromisoformat(ts_str)
            # 取去年同期（前后 1 天窗口）
            yoy_start = dt - timedelta(days=365) - timedelta(days=1)
            yoy_end = dt - timedelta(days=365) + timedelta(days=1)
            yoy_data = history[
                (history["ts"] >= pd.Timestamp(yoy_start))
                & (history["ts"] <= pd.Timestamp(yoy_end))
            ]

            if yoy_data.empty:
                # 无去年同期数据，退化为移动平均
                value = float(history["flow"].mean())
                std = value * 0.15
            else:
                value = float(yoy_data["flow"].mean())
                std = float(yoy_data["flow"].std()) if len(yoy_data) > 1 else value * 0.1

            points.append(ForecastPoint(
                ts=ts_str,
                value=round(value, 1),
                lower=round(max(0, value - std), 1),
                upper=round(value + std, 1),
            ))

        return ForecastResult(
            method=self.name,
            points=points,
            metadata={"data_points": len(history)},
        )
