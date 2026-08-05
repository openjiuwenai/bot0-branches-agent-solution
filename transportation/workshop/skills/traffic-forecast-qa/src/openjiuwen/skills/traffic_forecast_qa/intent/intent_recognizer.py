"""意图识别 — 判断用户提问是否为流量预测/分析意图。"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum


class IntentType(Enum):
    FORECAST = "forecast"          # 流量预测
    TREND_ANALYSIS = "trend"       # 趋势分析
    COMPARISON = "comparison"      # 同比/环比
    ANOMALY = "anomaly"            # 异常预警
    OTHER = "other"                # 非流量问数意图


@dataclass
class IntentResult:
    """意图识别结果。"""
    intent: IntentType
    confidence: float
    raw_text: str


# 关键词模式
_PATTERNS = [
    (IntentType.FORECAST, re.compile(r"(预测|预报|明天|下周|未来|预计).*流量|流量.*(预测|预报|预计)", re.I)),
    (IntentType.TREND_ANALYSIS, re.compile(r"(趋势|走势|变化|波动)", re.I)),
    (IntentType.COMPARISON, re.compile(r"(同比|环比|对比|相比|比.*(去年|上周|上月))", re.I)),
    (IntentType.ANOMALY, re.compile(r"(异常|偏离|突增|突降|报警|预警)", re.I)),
]


class IntentRecognizer:
    """意图识别器 — 基于关键词模式匹配。"""

    def recognize(self, question: str) -> IntentResult:
        for intent, pattern in _PATTERNS:
            if pattern.search(question):
                return IntentResult(
                    intent=intent,
                    confidence=0.85,
                    raw_text=question,
                )
        return IntentResult(
            intent=IntentType.OTHER,
            confidence=0.3,
            raw_text=question,
        )
