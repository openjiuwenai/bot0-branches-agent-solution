"""SQL 模板生成 — 生成参数化 SQL 模板交由 db-connector 执行。

标识符（表名/列名）取自配置白名单，不接受用户输入的原始表名/列名。
所有业务参数使用 %s 占位符，由 db-connector 参数化执行。
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from ..intent.param_extractor import ForecastParams


class SqlTemplateBuilder:
    """SQL 模板构建器。"""

    def __init__(self, mapping_path: str | Path | None = None) -> None:
        self._mapping = self._load_mapping(mapping_path)

    def _load_mapping(self, path: str | Path | None) -> dict[str, Any]:
        if path is None:
            return {}
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
        return data.get("traffic-forecast-qa", {}).get("flow-table", {})

    def build_history_query(self, params: ForecastParams) -> tuple[str, list[Any]]:
        """构建历史流量查询 SQL 模板。

        Returns:
            (sql_template, params) — 模板含 %s 占位符，params 为有序参数
        """
        table = self._mapping.get("name", "traffic_flow")
        cols = self._mapping.get("columns", {})
        ts_col = cols.get("timestamp", "ts")
        flow_col = cols.get("flow", "flow")
        station_col = cols.get("station-id", "station_id")
        granularity_col = cols.get("granularity", "granularity")

        # 标识符已在配置中固定，不接受用户输入
        sql = (
            f"SELECT {ts_col}, {flow_col} "
            f"FROM {table} "
            f"WHERE {station_col} = %s "
            f"AND {ts_col} BETWEEN %s AND %s "
            f"AND {granularity_col} = %s "
            f"ORDER BY {ts_col}"
        )

        # 计算历史窗口起点
        from datetime import datetime, timedelta
        now = datetime.now()
        hist_start = (now - timedelta(days=params.historical_window_days)).isoformat()

        values = [
            params.target_id,
            hist_start,
            now.isoformat(),
            params.granularity,
        ]
        return sql, values
