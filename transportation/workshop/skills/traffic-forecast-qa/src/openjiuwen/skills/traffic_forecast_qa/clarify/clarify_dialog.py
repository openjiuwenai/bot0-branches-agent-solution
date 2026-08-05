"""多轮澄清 — 参数缺失/歧义时主动追问。"""

from __future__ import annotations

from dataclasses import dataclass

from ..intent.param_extractor import ForecastParams


@dataclass
class ClarifyQuestion:
    """澄清问题。"""
    param_name: str       # 缺失参数名
    question: str         # 追问文本
    options: list[str] = None  # 候选选项（可选）


# 参数缺失时的追问模板
_CLARIFY_TEMPLATES = {
    "target": ClarifyQuestion(
        param_name="target",
        question="请问您要预测哪个站点或路段的流量？请提供站点编号（如 S001）或道路编码（如 G4）。",
    ),
    "forecast-window": ClarifyQuestion(
        param_name="forecast-window",
        question="请问您要预测哪个时间段的流量？例如：明天早高峰、下周全天等。",
        options=["明天早高峰", "明天晚高峰", "明天全天", "下周"],
    ),
}


class ClarifyDialog:
    """多轮澄清对话管理器。"""

    def __init__(self, max_rounds: int = 3) -> None:
        self._max_rounds = max_rounds
        self._round = 0

    def needs_clarification(self, params: ForecastParams) -> bool:
        """检查是否需要澄清。"""
        return bool(params.missing) and self._round < self._max_rounds

    def next_question(self, params: ForecastParams) -> ClarifyQuestion | None:
        """获取下一个澄清问题。"""
        if not params.missing or self._round >= self._max_rounds:
            return None

        self._round += 1
        param_name = params.missing[0]
        return _CLARIFY_TEMPLATES.get(param_name)

    @property
    def round(self) -> int:
        return self._round

    @property
    def exhausted(self) -> bool:
        """是否已用完追问轮次。"""
        return self._round >= self._max_rounds
