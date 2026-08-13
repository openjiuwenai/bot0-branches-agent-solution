# -*- coding: utf-8 -*-
"""MCP 写工具（13 个）—— 有副作用，需 MCP 客户端确认。

每个工具对应一个 data_service.writers 函数。
复杂输入（装置/储罐/侧线/收率/油种/物料）用 JSON 字符串参数，
简单输入（价格/绑定/删除）用原子参数。
所有工具返回 JSON 字符串（单 TextContent）。
"""
import json

from data_service.connection import get_session
from data_service.writers import (
    device_writer,
    side_line_writer,
    price_writer,
    crude_writer,
    material_writer,
)


def _json(data) -> str:
    """序列化为 JSON 字符串。"""
    return json.dumps(data, ensure_ascii=False, default=str)


def register_write_tools(mcp):
    """向 FastMCP 实例注册全部写工具。"""

    @mcp.tool()
    def upsert_unit(data: str) -> str:
        """新增/更新装置。

        Args:
            data: JSON 字符串，字段：device_id, name, type, max_capacity,
                  safety_stock_thrd, low_safety_thrd, current_capacity,
                  refinery_unit_load_pct, backend_device_id, note, enabled
        """
        parsed = json.loads(data)
        with get_session() as db:
            device_writer.upsert_unit(db, parsed)
            db.commit()
        return _json({"success": True, "device_id": parsed["device_id"]})

    @mcp.tool()
    def upsert_tank(data: str) -> str:
        """新增/更新储罐（含 material_id 关联物料）。

        Args:
            data: JSON 字符串，字段：device_id, name, max_capacity,
                  safety_stock_thrd, low_safety_thrd, current_capacity,
                  refinery_unit_load_pct, tank_category, material_id, note, enabled
        """
        parsed = json.loads(data)
        with get_session() as db:
            device_writer.upsert_tank(db, parsed)
            db.commit()
        return _json({"success": True, "device_id": parsed["device_id"]})

    @mcp.tool()
    def delete_device(device_id: str, device_type: str) -> str:
        """删除装置或储罐。

        Args:
            device_id: 装置/储罐 ID
            device_type: "unit" 或 "tank"
        """
        with get_session() as db:
            if device_type == "tank":
                device_writer.delete_tank(db, device_id)
            else:
                device_writer.delete_unit(db, device_id)
            db.commit()
        return _json({"success": True, "deleted": device_id})

    @mcp.tool()
    def upsert_side_line(data: str) -> str:
        """新增/更新侧线。

        Args:
            data: JSON 字符串，字段：side_line_id, source_device_id, name,
                  material_id (可选), flow_type, special_var, priority
        """
        parsed = json.loads(data)
        with get_session() as db:
            side_line_writer.upsert_side_line(db, parsed)
            db.commit()
        return _json({"success": True, "side_line_id": parsed["side_line_id"]})

    @mcp.tool()
    def upsert_yields(data: str) -> str:
        """批量新增/更新收率。

        Args:
            data: JSON 字符串，格式 {"items": [{side_line_id, crude_type,
                  yield_rate, yield_rate_2, yield_rate_3, yield_rate_4}]}
        """
        parsed = json.loads(data)
        items = parsed.get("items", parsed if isinstance(parsed, list) else [parsed])
        with get_session() as db:
            side_line_writer.upsert_yields(db, items)
            db.commit()
        return _json({"success": True, "count": len(items)})

    @mcp.tool()
    def delete_side_line(side_line_id: str) -> str:
        """删除侧线（级联删除关联收率）。

        Args:
            side_line_id: 侧线 ID
        """
        with get_session() as db:
            side_line_writer.delete_side_line(db, side_line_id)
            db.commit()
        return _json({"success": True, "deleted": side_line_id})

    @mcp.tool()
    def bind_material(side_line_id: str, material_id: int = 0) -> str:
        """绑定/解绑侧线与物料。

        Args:
            side_line_id: 侧线 ID
            material_id: 物料 ID，0 表示解除绑定
        """
        mid = material_id if material_id > 0 else None
        with get_session() as db:
            side_line_writer.update_material_binding(db, side_line_id, mid)
            db.commit()
        return _json({"success": True, "side_line_id": side_line_id, "material_id": mid})

    @mcp.tool()
    def upsert_price(month: str, material_id: int, price: float) -> str:
        """新增/更新物料价格。

        Args:
            month: 月份 YYYY-MM
            material_id: 物料 ID
            price: 价格（元/吨）
        """
        with get_session() as db:
            price_writer.upsert_price(db, month, material_id, price)
            db.commit()
        return _json({"success": True, "month": month, "material_id": material_id})

    @mcp.tool()
    def upsert_crude(data: str) -> str:
        """新增/更新油种。

        Args:
            data: JSON 字符串，字段：crude_type_id, crude_name, crude_code,
                  aliases (list 或逗号分隔字符串), is_active, is_default,
                  sort_order, note
        """
        parsed = json.loads(data)
        with get_session() as db:
            crude_writer.upsert_crude(db, parsed)
            db.commit()
        return _json({"success": True, "crude_type_id": parsed["crude_type_id"]})

    @mcp.tool()
    def delete_crude(crude_type_id: str) -> str:
        """删除油种。default 油种不可删。

        Args:
            crude_type_id: 油种 ID
        """
        if crude_type_id == "default":
            return _json({"success": False, "error": "default 油种不可删除"})
        with get_session() as db:
            crude_writer.delete_crude(db, crude_type_id)
            db.commit()
        return _json({"success": True, "deleted": crude_type_id})

    @mcp.tool()
    def toggle_crude_active(crude_type_id: str, is_active: bool) -> str:
        """切换油种激活状态。

        Args:
            crude_type_id: 油种 ID
            is_active: True=激活, False=停用
        """
        with get_session() as db:
            crude_writer.update_active(db, crude_type_id, is_active)
            db.commit()
        return _json({"success": True, "crude_type_id": crude_type_id, "is_active": is_active})

    @mcp.tool()
    def upsert_material(data: str) -> str:
        """新增/更新物料主数据。

        Args:
            data: JSON 字符串，字段：name, category, groms_alias, alias, remark
        """
        parsed = json.loads(data)
        with get_session() as db:
            material_writer.upsert_material(db, parsed)
            db.commit()
        return _json({"success": True, "name": parsed["name"]})

    @mcp.tool()
    def delete_material(material_id: int, force: bool = False) -> str:
        """删除物料。先检查依赖，有依赖时需 force=true 才能强制删除。

        Args:
            material_id: 物料 ID
            force: 是否强制删除（忽略依赖检查）
        """
        with get_session() as db:
            deps = material_writer.check_dependencies(db, material_id)
            if deps["total"] > 0 and not force:
                return _json({
                    "success": False,
                    "error": "存在依赖关系，无法删除",
                    "dependencies": deps,
                    "hint": "如需强制删除，请传 force=true",
                })
            material_writer.delete_material(db, material_id)
            db.commit()
        return _json({"success": True, "deleted": material_id, "dependencies": deps})
