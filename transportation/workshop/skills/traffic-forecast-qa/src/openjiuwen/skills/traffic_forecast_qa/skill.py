"""TrafficForecastQaSkill — 流量预测智能问数 Skill 入口。

工作流：意图识别 → 参数抽取 → 多轮澄清 → SQL 生成 → 数据获取 → 预测计算 → 结果组织
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from .intent.intent_recognizer import IntentRecognizer, IntentType
from .intent.param_extractor import ParamExtractor, ForecastParams
from .query.sql_template_builder import SqlTemplateBuilder
from .query.data_fetcher import DataFetcher
from .forecast.forecast_engine import ForecastResult
from .forecast.moving_average import MovingAverageForecaster, YearOverYearForecaster
from .forecast.holt_winters import HoltWintersForecaster
from .clarify.clarify_dialog import ClarifyDialog
from .response.result_assembler import ResultAssembler, SkillResponse


class TrafficForecastQaSkill:
    """流量预测智能问数 Skill。

    Args:
        db_connector: db-connector 工具实例（须 readonly 模式）
        mapping_path: 数据口径映射配置路径
    """

    def __init__(
        self,
        db_connector,
        mapping_path: str | Path | None = None,
    ) -> None:
        self._db = db_connector
        self._intent_recognizer = IntentRecognizer()
        self._param_extractor = ParamExtractor()
        self._template_builder = SqlTemplateBuilder(mapping_path)
        self._data_fetcher = DataFetcher(db_connector, self._template_builder)
        self._clarify = ClarifyDialog(max_rounds=3)
        self._assembler = ResultAssembler()

        # 预测器注册（L1 + L2）
        self._forecasters = {
            "moving_average": MovingAverageForecaster(),
            "year_over_year": YearOverYearForecaster(),
            "holt_winters": HoltWintersForecaster(seasonal_periods=24),
        }

        # 加载配置
        self._config = self._load_config(mapping_path)

    def _load_config(self, path: str | Path | None) -> dict[str, Any]:
        if path is None:
            return {}
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
        return data.get("traffic-forecast-qa", {})

    def ask(self, question: str, context: dict | None = None) -> dict[str, Any]:
        """处理用户自然语言提问。

        Args:
            question: 用户问题
            context: 多轮对话上下文（可选）

        Returns:
            结构化响应（含预测结果或澄清问题）
        """
        # ① 意图识别
        intent = self._intent_recognizer.recognize(question)
        if intent.intent == IntentType.OTHER:
            return SkillResponse(
                status="rejected",
                summary="抱歉，我只能回答流量预测与分析相关问题。",
            ).to_dict()

        # ② 参数抽取
        params = self._param_extractor.extract(question)

        # ③ 多轮澄清
        if self._clarify.needs_clarification(params):
            q = self._clarify.next_question(params)
            if q:
                return self._assembler.assemble_clarify(q.question, q.options).to_dict()

        # 参数仍不完整且追问已用完
        if not params.is_complete():
            # 使用默认值兜底
            if not params.forecast_start:
                from datetime import datetime, timedelta
                tomorrow = datetime.now() + timedelta(days=1)
                params.forecast_start = tomorrow.replace(hour=0, minute=0, second=0).isoformat()
                params.forecast_end = tomorrow.replace(hour=23, minute=0, second=0).isoformat()
            if not params.target_id:
                return SkillResponse(
                    status="error",
                    summary="无法识别目标站点或路段，请提供站点编号（如 S001）。",
                ).to_dict()

        # ④ + ⑤ SQL 生成 + 数据获取
        history = self._data_fetcher.fetch_history(params)

        if history.empty:
            return SkillResponse(
                status="no_data",
                summary=f"未查询到 {params.target_id} 的历史流量数据。",
            ).to_dict()

        # ⑥ 预测计算（优先 L2，数据不足时退化为 L1）
        forecast = self._compute_forecast(params, history)

        # ⑦ 结果组织
        response = self._assembler.assemble(params, forecast)
        return response.to_dict()

    def _compute_forecast(self, params: ForecastParams, history) -> ForecastResult:
        """选择最佳预测方法执行预测。

        优先尝试 Holt-Winters（L2），数据不足或拟合失败时退化为移动平均（L1）。
        """
        # 数据量充足时优先 L2
        if len(history) >= 48:  # 至少 2 天小时数据
            forecaster = self._forecasters["holt_winters"]
        else:
            forecaster = self._forecasters["moving_average"]

        return forecaster.forecast(
            history,
            params.forecast_start,
            params.forecast_end,
            params.granularity,
        )
