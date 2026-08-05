"""traffic-forecast-qa Skill 单元测试。"""

from datetime import datetime, timedelta

import pandas as pd
import pytest

from openjiuwen.skills.traffic_forecast_qa.intent.intent_recognizer import (
    IntentRecognizer, IntentType,
)
from openjiuwen.skills.traffic_forecast_qa.intent.param_extractor import (
    ParamExtractor, ForecastParams,
)
from openjiuwen.skills.traffic_forecast_qa.clarify.clarify_dialog import ClarifyDialog
from openjiuwen.skills.traffic_forecast_qa.forecast.moving_average import (
    MovingAverageForecaster, YearOverYearForecaster,
)
from openjiuwen.skills.traffic_forecast_qa.forecast.holt_winters import HoltWintersForecaster
from openjiuwen.skills.traffic_forecast_qa.response.result_assembler import ResultAssembler


# ---------------------------------------------------------------------------
# 意图识别测试
# ---------------------------------------------------------------------------

class TestIntentRecognizer:
    def setup_method(self):
        self.recognizer = IntentRecognizer()

    def test_forecast_intent(self):
        result = self.recognizer.recognize("预测 S001 明天早高峰流量")
        assert result.intent == IntentType.FORECAST

    def test_trend_intent(self):
        result = self.recognizer.recognize("下周流量趋势如何")
        assert result.intent == IntentType.TREND_ANALYSIS

    def test_comparison_intent(self):
        result = self.recognizer.recognize("对比去年同期流量变化")
        assert result.intent == IntentType.COMPARISON

    def test_other_intent(self):
        result = self.recognizer.recognize("今天天气怎么样")
        assert result.intent == IntentType.OTHER


# ---------------------------------------------------------------------------
# 参数抽取测试
# ---------------------------------------------------------------------------

class TestParamExtractor:
    def setup_method(self):
        self.extractor = ParamExtractor()

    def test_extract_station_id(self):
        params = self.extractor.extract("预测 S001 明天早高峰流量")
        assert params.target_type == "station"
        assert params.target_id == "S001"

    def test_extract_road_code(self):
        params = self.extractor.extract("G4 下周流量趋势")
        assert params.target_type == "road"
        assert params.target_id == "G4"

    def test_extract_time_window_tomorrow(self):
        now = datetime(2026, 7, 30, 10, 0)
        params = self.extractor.extract("预测 S001 明天早高峰流量", now=now)
        assert params.forecast_start != ""
        assert "07:00" in params.forecast_start

    def test_missing_target(self):
        params = self.extractor.extract("预测明天流量")
        assert "target" in params.missing

    def test_granularity_default_hour(self):
        params = self.extractor.extract("预测 S001 明天流量")
        assert params.granularity == "HOUR"


# ---------------------------------------------------------------------------
# 多轮澄清测试
# ---------------------------------------------------------------------------

class TestClarifyDialog:
    def test_needs_clarification_when_missing(self):
        dialog = ClarifyDialog(max_rounds=3)
        params = ForecastParams(missing=["target"])
        assert dialog.needs_clarification(params) is True

    def test_no_clarification_when_complete(self):
        dialog = ClarifyDialog(max_rounds=3)
        params = ForecastParams(missing=[])
        assert dialog.needs_clarification(params) is False

    def test_next_question_for_target(self):
        dialog = ClarifyDialog(max_rounds=3)
        params = ForecastParams(missing=["target"])
        q = dialog.next_question(params)
        assert q is not None
        assert q.param_name == "target"
        assert "站点" in q.question or "路段" in q.question

    def test_max_rounds_exhausted(self):
        dialog = ClarifyDialog(max_rounds=1)
        params = ForecastParams(missing=["target"])
        dialog.next_question(params)
        assert dialog.exhausted is True
        assert dialog.next_question(params) is None


# ---------------------------------------------------------------------------
# 预测引擎测试
# ---------------------------------------------------------------------------

@pytest.fixture
def sample_history():
    """生成 7 天小时级历史数据。"""
    now = datetime(2026, 7, 30, 0, 0)
    timestamps = [now - timedelta(hours=i) for i in range(168, 0, -1)]
    # 模拟日周期：白天流量高，夜间低
    flows = [
        max(50, int(500 + 300 * abs((ts.hour - 12) / 12) + (ts.dayofweek * 50)))
        for ts in timestamps
    ]
    return pd.DataFrame({"ts": timestamps, "flow": flows})


class TestMovingAverageForecaster:
    def test_forecast_returns_points(self, sample_history):
        forecaster = MovingAverageForecaster(window=7)
        result = forecaster.forecast(
            sample_history,
            "2026-07-31T07:00:00",
            "2026-07-31T09:00:00",
            "HOUR",
        )
        assert result.method == "moving_average"
        assert len(result.points) == 3  # 7, 8, 9 点
        assert all(p.value > 0 for p in result.points)
        assert all(p.lower <= p.value <= p.upper for p in result.points)

    def test_forecast_empty_history(self):
        forecaster = MovingAverageForecaster()
        result = forecaster.forecast(
            pd.DataFrame(columns=["ts", "flow"]),
            "2026-07-31T07:00:00",
            "2026-07-31T09:00:00",
        )
        assert len(result.points) == 0


class TestHoltWintersForecaster:
    def test_forecast_returns_points(self, sample_history):
        forecaster = HoltWintersForecaster(seasonal_periods=24)
        result = forecaster.forecast(
            sample_history,
            "2026-07-31T07:00:00",
            "2026-07-31T09:00:00",
            "HOUR",
        )
        assert result.method == "holt_winters"
        assert len(result.points) == 3
        assert all(p.value >= 0 for p in result.points)

    def test_fallback_on_insufficient_data(self):
        forecaster = HoltWintersForecaster(seasonal_periods=24)
        # 仅 10 条数据，不足以拟合
        small = pd.DataFrame({
            "ts": [datetime(2026, 7, 30, i) for i in range(10)],
            "flow": [100 + i * 10 for i in range(10)],
        })
        result = forecaster.forecast(
            small,
            "2026-07-31T07:00:00",
            "2026-07-31T09:00:00",
            "HOUR",
        )
        # 数据不足应退化为移动平均
        assert result.method == "moving_average"


# ---------------------------------------------------------------------------
# 结果组装测试
# ---------------------------------------------------------------------------

class TestResultAssembler:
    def test_assemble_forecast(self):
        from openjiuwen.skills.traffic_forecast_qa.forecast.forecast_engine import (
            ForecastResult, ForecastPoint,
        )
        assembler = ResultAssembler()
        params = ForecastParams(
            target_type="station",
            target_id="S001",
            forecast_start="2026-07-31T07:00:00",
            forecast_end="2026-07-31T09:00:00",
            granularity="HOUR",
        )
        forecast = ForecastResult(
            method="moving_average",
            points=[
                ForecastPoint(ts="2026-07-31T07:00:00", value=1200, lower=1100, upper=1300),
                ForecastPoint(ts="2026-07-31T08:00:00", value=1400, lower=1300, upper=1500),
            ],
        )
        response = assembler.assemble(params, forecast)
        assert response.status == "ok"
        assert "S001" in response.summary
        assert response.forecast["method"] == "moving_average"
        assert len(response.forecast["points"]) == 2

    def test_assemble_clarify(self):
        assembler = ResultAssembler()
        response = assembler.assemble_clarify("请问站点编号？", None)
        assert response.status == "clarify"
        assert response.clarify["question"] == "请问站点编号？"
