"""数据获取 — 调用 db-connector 查询历史流量数据。"""

from __future__ import annotations

from typing import Any

import pandas as pd

from ..intent.param_extractor import ForecastParams
from .sql_template_builder import SqlTemplateBuilder


class DataFetcher:
    """数据获取器 — 通过 db-connector 查询历史数据。"""

    def __init__(self, db_connector, template_builder: SqlTemplateBuilder | None = None) -> None:
        self._db = db_connector
        self._builder = template_builder or SqlTemplateBuilder()

    def fetch_history(self, params: ForecastParams) -> pd.DataFrame:
        """获取历史流量数据。

        Args:
            params: 预测参数（含目标站点、历史窗口等）

        Returns:
            DataFrame，列：ts, flow
        """
        sql_template, sql_params = self._builder.build_history_query(params)
        result = self._db.query(sql_template, sql_params)

        if not result.rows:
            return pd.DataFrame(columns=["ts", "flow"])

        df = pd.DataFrame(result.rows, columns=result.columns)
        # 确保列名标准化
        if "ts" not in df.columns and len(df.columns) >= 2:
            df.columns = ["ts", "flow"]
        df["ts"] = pd.to_datetime(df["ts"])
        df["flow"] = pd.to_numeric(df["flow"], errors="coerce")
        return df
