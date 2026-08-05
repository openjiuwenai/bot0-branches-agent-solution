"""L2 预测：指数平滑 / Holt-Winters。"""

from __future__ import annotations

import pandas as pd

from .forecast_engine import ForecastEngine, ForecastPoint, ForecastResult


class HoltWintersForecaster(ForecastEngine):
    """Holt-Winters 指数平滑预测器。

    适合含趋势与季节性的时序数据。依赖 statsmodels。
    季节周期：小时粒度默认 24（一天 24 小时）。
    """

    def __init__(self, seasonal_periods: int = 24) -> None:
        self._seasonal_periods = seasonal_periods

    @property
    def name(self) -> str:
        return "holt_winters"

    def forecast(
        self,
        history: pd.DataFrame,
        forecast_start: str,
        forecast_end: str,
        granularity: str = "HOUR",
    ) -> ForecastResult:
        if history.empty:
            return ForecastResult(method=self.name)

        try:
            from statsmodels.tsa.holtwinters import ExponentialSmoothing
        except ImportError:
            # statsmodels 未安装，退化为移动平均
            from .moving_average import MovingAverageForecaster
            return MovingAverageForecaster().forecast(
                history, forecast_start, forecast_end, granularity
            )

        # 准备时序数据
        ts = history.set_index("ts")["flow"].sort_index()

        # 数据量不足时退化
        if len(ts) < self._seasonal_periods * 2:
            from .moving_average import MovingAverageForecaster
            return MovingAverageForecaster().forecast(
                history, forecast_start, forecast_end, granularity
            )

        timestamps = self._generate_timestamps(forecast_start, forecast_end, granularity)
        steps = len(timestamps)

        try:
            model = ExponentialSmoothing(
                ts,
                trend="add",
                seasonal="add",
                seasonal_periods=self._seasonal_periods,
            )
            fitted = model.fit()
            forecast_values = fitted.forecast(steps=steps)

            # 置信区间（简化：用残差标准差）
            resid_std = float(fitted.resid.std()) if hasattr(fitted, "resid") else 0.0

            points = []
            for i, ts_str in enumerate(timestamps):
                if i < len(forecast_values):
                    value = float(forecast_values.iloc[i])
                else:
                    value = float(ts.mean())
                points.append(ForecastPoint(
                    ts=ts_str,
                    value=round(max(0, value), 1),
                    lower=round(max(0, value - resid_std), 1),
                    upper=round(value + resid_std, 1),
                ))

            return ForecastResult(
                method=self.name,
                points=points,
                metadata={
                    "seasonal_periods": self._seasonal_periods,
                    "data_points": len(history),
                    "aic": float(fitted.aic) if hasattr(fitted, "aic") else None,
                },
            )

        except Exception:
            # 模型拟合失败，退化为移动平均
            from .moving_average import MovingAverageForecaster
            return MovingAverageForecaster().forecast(
                history, forecast_start, forecast_end, granularity
            )
