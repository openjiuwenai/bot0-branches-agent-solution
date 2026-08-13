# -*- coding: utf-8 -*-
"""MCP 读工具（15 个）—— 无副作用，可直接调用。

每个工具对应一个 data_service.repositories 函数，开箱即用。
所有工具返回 JSON 字符串（单 TextContent），避免 FastMCP 把 list 拆成多 block。
"""
import json

from data_service.connection import get_session
from data_service.repositories import (
    device_repo,
    side_line_repo,
    material_repo,
    mapping_repo,
    price_repo,
    flow_repo,
    crude_repo,
)


def _json(data) -> str:
    """序列化为 JSON 字符串，处理 Decimal/datetime 等非标准类型。"""
    return json.dumps(data, ensure_ascii=False, default=str)


def register_read_tools(mcp):
    """向 FastMCP 实例注册全部读工具。"""

    @mcp.tool()
    def list_units() -> str:
        """查询全部装置。返回 JSON 数组，每项含 device_id, name, type, max_capacity, safety_stock_thrd 等。"""
        with get_session() as db:
            return _json(device_repo.load_units(db))

    @mcp.tool()
    def list_tanks() -> str:
        """查询全部储罐（含关联物料 material_id / material_name）。"""
        with get_session() as db:
            return _json(device_repo.load_tanks(db))

    @mcp.tool()
    def list_side_lines(device_id: str = "") -> str:
        """查询侧线（可按装置过滤）。不传 device_id 返回全部。"""
        with get_session() as db:
            return _json(side_line_repo.load_side_lines(db, device_id or None))

    @mcp.tool()
    def get_yields(side_line_id: str = "", crude_type: str = "", limit: int = 0) -> str:
        """查询收率（可按侧线和油种过滤）。不传参数返回全部。

        Args:
            side_line_id: 侧线ID，空字符串取全部
            crude_type: 油种标识，空字符串取全部
            limit: 返回条数上限（0=不限）。建议传入避免全量返回过大。
        """
        with get_session() as db:
            rows = side_line_repo.load_yields(db, side_line_id or None, crude_type or None)
        if limit > 0:
            rows = rows[:limit]
        return _json(rows)

    @mcp.tool()
    def get_side_lines_with_yields(crude_type: str = "") -> str:
        """侧线+收率联合查询（含 default 回退逻辑）。可按油种过滤。"""
        with get_session() as db:
            return _json(side_line_repo.load_side_lines_with_yields(db, crude_type or None))

    @mcp.tool()
    def list_materials() -> str:
        """查询全部物料主数据（id, name, category, alias, remark）。"""
        with get_session() as db:
            return _json(material_repo.load_materials(db))

    @mcp.tool()
    def get_feed_ratio(device_id: str, crude_type: str) -> str:
        """查询装置进料配比。返回 {device_id, crude_type, feed_ratio}。"""
        with get_session() as db:
            ratio = mapping_repo.get_feed_ratio(db, device_id, crude_type)
        return _json({"device_id": device_id, "crude_type": crude_type, "feed_ratio": ratio})

    @mcp.tool()
    def get_material_mapping() -> str:
        """查询侧线到物料的映射（side_line_id to material_id）。"""
        with get_session() as db:
            return _json(mapping_repo.load_product_material_mapping(db))

    @mcp.tool()
    def get_prices(month: str = "", material_ids: str = "") -> str:
        """查询物料价格（含计算规则回退）。

        Args:
            month: 月份 YYYY-MM，空字符串取最新
            material_ids: 逗号分隔的物料 ID（如 "1,2,3"）。
                          空字符串时返回全部物料价格明细（含 default_price/is_overridden）。
        """
        ids = None
        if material_ids:
            ids = [int(x.strip()) for x in material_ids.split(",") if x.strip()]
        with get_session() as db:
            if ids:
                # 指定物料：返回 {material_id_str: price} 扁平 dict
                return _json(price_repo.resolve_prices_batch(db, month or None, ids))
            else:
                # 全量：返回明细列表 [{material_id, material_name, price, ...}]
                return _json(price_repo.load_material_prices(db, month or None))

    @mcp.tool()
    def get_side_line_prices(month: str = "") -> str:
        """查询侧线价格（侧线到物料到价格联合查询）。空月份取最新。"""
        with get_session() as db:
            return _json(price_repo.load_side_line_prices(db, month or None))

    @mcp.tool()
    def get_device_costs(month: str) -> str:
        """查询装置加工成本。返回 {device_id: cost_per_ton}。"""
        with get_session() as db:
            return _json(price_repo.load_device_costs(db, month))

    @mcp.tool()
    def get_price_months() -> str:
        """查询有价格数据的月份列表。"""
        with get_session() as db:
            return _json(price_repo.load_price_months(db))

    @mcp.tool()
    def list_flows(limit: int = 0) -> str:
        """查询全部物流边（material_flows）。返回 flow_id, source_device_id, target_device_id 等。

        Args:
            limit: 返回条数上限（0=不限）。
        """
        with get_session() as db:
            rows = flow_repo.load_flows(db)
        if limit > 0:
            rows = rows[:limit]
        return _json(rows)

    @mcp.tool()
    def list_crudes(active_only: bool = False) -> str:
        """查询全部油种（crude_types）。active_only=true 仅返回激活的。"""
        with get_session() as db:
            return _json(crude_repo.load_crudes(db, active_only))

    @mcp.tool()
    def find_crude(name: str) -> str:
        """按名称或别名查询油种。返回匹配的第一条，无匹配返回空 JSON 对象。"""
        with get_session() as db:
            result = crude_repo.find_by_alias(db, name)
        return _json(result or {})
