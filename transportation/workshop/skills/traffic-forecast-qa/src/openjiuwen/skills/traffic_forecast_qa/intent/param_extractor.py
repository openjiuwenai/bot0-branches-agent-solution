"""参数抽取 — 从自然语言中提取预测所需参数。"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any


@dataclass
class ForecastParams:
    """预测参数。"""
    target_type: str = ""        # station | road
    target_id: str = ""          # 站点 ID / 路段编码
    road_code: str = ""          # 道路编码
    historical_window_days: int = 30
    forecast_start: str = ""     # ISO 格式
    forecast_end: str = ""
    granularity: str = "HOUR"
    direction: str = ""
    missing: list[str] = field(default_factory=list)  # 缺失参数名

    def is_complete(self) -> bool:
        """检查必填参数是否齐全。"""
        return not self.missing


# 站点 ID 模式（如 S001, S123）
_STATION_RE = re.compile(r"\b([SsGg]\d{3,5})\b")
# 道路编码模式（如 G4, G15, K1200）
_ROAD_RE = re.compile(r"\b([GgSs]\d{1,3})\b")
# 路段里程模式
_MILEAGE_RE = re.compile(r"[Kk](\d{3,4})")
# 早高峰 / 晚高峰
_PEAK_RE = re.compile(r"(早高峰|晚高峰|高峰)", re.I)
# 方向
_DIR_RE = re.compile(r"(上行|下行|双向|往[\u4e00-\u9fa5]+方向)")


class ParamExtractor:
    """参数抽取器。"""

    def extract(self, question: str, now: datetime | None = None) -> ForecastParams:
        now = now or datetime.now()
        params = ForecastParams(granularity="HOUR")

        # 抽取站点 ID
        station_match = _STATION_RE.search(question)
        if station_match:
            params.target_type = "station"
            params.target_id = station_match.group(1).upper()

        # 抽取道路编码
        road_match = _ROAD_RE.search(question)
        if road_match and not params.target_id:
            params.target_type = "road"
            params.target_id = road_match.group(1).upper()
            params.road_code = params.target_id

        # 抽取方向
        dir_match = _DIR_RE.search(question)
        if dir_match:
            params.direction = dir_match.group(1)

        # 抽取预测时间窗口
        params.forecast_start, params.forecast_end = self._extract_time_window(question, now)

        # 检查缺失参数
        if not params.target_id:
            params.missing.append("target")
        if not params.forecast_start:
            params.missing.append("forecast-window")

        return params

    def _extract_time_window(self, question: str, now: datetime) -> tuple[str, str]:
        """从提问中提取预测时间窗口。"""
        # 明天
        if "明天" in question or "明日" in question:
            tomorrow = now + timedelta(days=1)
            # 早高峰 7-9 点
            if _PEAK_RE.search(question) and "早" in question:
                return (
                    tomorrow.replace(hour=7, minute=0, second=0).isoformat(),
                    tomorrow.replace(hour=9, minute=0, second=0).isoformat(),
                )
            # 晚高峰 17-19 点
            if _PEAK_RE.search(question) and "晚" in question:
                return (
                    tomorrow.replace(hour=17, minute=0, second=0).isoformat(),
                    tomorrow.replace(hour=19, minute=0, second=0).isoformat(),
                )
            # 全天
            return (
                tomorrow.replace(hour=0, minute=0, second=0).isoformat(),
                tomorrow.replace(hour=23, minute=0, second=0).isoformat(),
            )

        # 下周
        if "下周" in question:
            next_week = now + timedelta(weeks=1)
            return (
                next_week.replace(hour=0, minute=0, second=0).isoformat(),
                (next_week + timedelta(days=6)).replace(hour=23, minute=0, second=0).isoformat(),
            )

        # 今天
        if "今天" in question or "今日" in question:
            return (
                now.replace(hour=0, minute=0, second=0).isoformat(),
                now.replace(hour=23, minute=0, second=0).isoformat(),
            )

        return "", ""
