"""结果组织 — 将预测结果组装为结构化响应。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..forecast.forecast_engine import ForecastResult
from ..intent.param_extractor import ForecastParams


@dataclass
class SkillResponse:
    """Skill 响应。"""
    status: str = "ok"
    understood: dict[str, Any] = field(default_factory=dict)
    queries: list[dict[str, Any]] = field(default_factory=list)
    forecast: dict[str, Any] = field(default_factory=dict)
    summary: str = ""
    visualization: dict[str, Any] = field(default_factory=dict)
    clarify: dict[str, Any] | None = None  # 需要澄清时填充

    def to_dict(self) -> dict[str, Any]:
        result = {
            "status": self.status,
            "understood": self.understood,
            "queries": self.queries,
            "forecast": self.forecast,
            "summary": self.summary,
            "visualization": self.visualization,
        }
        if self.clarify:
            result["clarify"] = self.clarify
        return result


class ResultAssembler:
    """结果组装器。"""

    def assemble(
        self,
        params: ForecastParams,
        forecast: ForecastResult,
        audit_ids: list[str] | None = None,
    ) -> SkillResponse:
        """组装最终响应。"""
        total_flow = sum(p.value for p in forecast.points)
        point_count = len(forecast.points)

        summary = self._build_summary(params, total_flow, point_count, forecast.method)

        return SkillResponse(
            status="ok",
            understood={
                "target": {"type": params.target_type, "id": params.target_id},
                "historicalWindow": f"P{params.historical_window_days}D",
                "forecastWindow": f"{params.forecast_start}~{params.forecast_end}",
                "granularity": params.granularity,
            },
            queries=[{"auditIds": audit_ids or []}],
            forecast=forecast.to_dict(),
            summary=summary,
            visualization={
                "type": "line",
                "series": [
                    {"ts": p.ts, "value": p.value, "lower": p.lower, "upper": p.upper}
                    for p in forecast.points
                ],
            },
        )

    def assemble_clarify(self, question_text: str, options: list[str] | None = None) -> SkillResponse:
        """组装澄清响应。"""
        return SkillResponse(
            status="clarify",
            clarify={
                "question": question_text,
                "options": options or [],
            },
        )

    def _build_summary(
        self, params: ForecastParams, total_flow: float, point_count: int, method: str
    ) -> str:
        """生成自然语言摘要。"""
        method_names = {
            "moving_average": "移动平均",
            "year_over_year": "同比",
            "holt_winters": "Holt-Winters 指数平滑",
        }
        method_name = method_names.get(method, method)

        if params.target_type == "station":
            target_desc = f"站点 {params.target_id}"
        else:
            target_desc = f"路段 {params.target_id}"

        if point_count > 0:
            avg_flow = total_flow / point_count if point_count else 0
            return (
                f"基于{method_name}方法，预测{target_desc}在"
                f"{params.forecast_start}至{params.forecast_end}期间"
                f"共 {point_count} 个时段，平均流量约 {avg_flow:.0f} 辆/时段。"
            )
        return f"预测{target_desc}流量，但未能生成有效预测点。"
