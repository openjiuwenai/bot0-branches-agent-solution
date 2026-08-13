# -*- coding: utf-8 -*-
"""装置/储罐只读仓库（devices_units + devices_tanks）。

装置和储罐已彻底分离：
  - devices_units  —— 装置（type: normal/start，有 backend_device_id）
  - devices_tanks  —— 储罐（tank_category: intermediate/product/crude，有 material_id）

储罐不再出现在 side_lines 表中，物料关联直接挂在 devices_tanks.material_id 上。
"""
from decimal import Decimal
from typing import List

from sqlalchemy import text
from sqlalchemy.orm import Session


def _f(v, default=0.0) -> float:
    """安全转 float：None/NaN/非法值 → default；Decimal → float。"""
    if v is None:
        return default
    try:
        f = float(v)
    except (TypeError, ValueError):
        return default
    if f != f:  # NaN
        return default
    return f


def _s(v) -> str | None:
    """安全转 str：None 保留为 None，其余转 str。"""
    return None if v is None else str(v)


def _b(v, default=True) -> bool:
    """安全转 bool。"""
    if v is None:
        return default
    return bool(v)


def load_units(db: Session) -> List[dict]:
    """查询 solve_db.devices_units，返回装置列表。

    返回字段：device_id, name, type, max_capacity, safety_stock_thrd,
    low_safety_thrd, current_capacity, refinery_unit_load_pct,
    backend_device_id, note, enabled
    """
    rows = db.execute(text(
        "SELECT device_id, name, type, max_capacity, safety_stock_thrd, "
        "low_safety_thrd, current_capacity, refinery_unit_load_pct, "
        "backend_device_id, note, enabled "
        "FROM solve_db.devices_units ORDER BY device_id"
    )).mappings().all()
    return [
        {
            "device_id": _s(r["device_id"]),
            "name": _s(r["name"]),
            "type": _s(r["type"]),
            "max_capacity": _f(r["max_capacity"]),
            "safety_stock_thrd": _f(r["safety_stock_thrd"]),
            "low_safety_thrd": _f(r["low_safety_thrd"]),
            "current_capacity": _f(r["current_capacity"]),
            "refinery_unit_load_pct": _f(r["refinery_unit_load_pct"], 100),
            "backend_device_id": int(r["backend_device_id"]) if r["backend_device_id"] is not None else None,
            "note": _s(r["note"]),
            "enabled": _b(r["enabled"]),
        }
        for r in rows
    ]


def load_tanks(db: Session) -> List[dict]:
    """查询 solve_db.devices_tanks，返回储罐列表（含关联物料）。

    返回字段：device_id, name, max_capacity, safety_stock_thrd,
    low_safety_thrd, current_capacity, refinery_unit_load_pct,
    tank_category, material_id, material_name, note, enabled
    """
    rows = db.execute(text(
        "SELECT t.device_id, t.name, t.max_capacity, t.safety_stock_thrd, "
        "t.low_safety_thrd, t.current_capacity, t.refinery_unit_load_pct, "
        "t.tank_category, t.material_id, m.name AS material_name, "
        "t.note, t.enabled "
        "FROM solve_db.devices_tanks t "
        "LEFT JOIN public.md_material m ON t.material_id = m.id "
        "ORDER BY t.device_id"
    )).mappings().all()
    return [
        {
            "device_id": _s(r["device_id"]),
            "name": _s(r["name"]),
            "max_capacity": _f(r["max_capacity"]),
            "safety_stock_thrd": _f(r["safety_stock_thrd"]),
            "low_safety_thrd": _f(r["low_safety_thrd"]),
            "current_capacity": _f(r["current_capacity"]),
            "refinery_unit_load_pct": _f(r["refinery_unit_load_pct"], 100),
            "tank_category": _s(r["tank_category"]),
            "material_id": int(r["material_id"]) if r["material_id"] is not None else None,
            "material_name": _s(r["material_name"]),
            "note": _s(r["note"]),
            "enabled": _b(r["enabled"]),
        }
        for r in rows
    ]
