# -*- coding: utf-8 -*-
"""装置/储罐写仓库（devices_units + devices_tanks）。

装置和储罐已彻底分离，各自独立的 upsert/delete/replace 函数。
upsert 使用 ON CONFLICT (device_id) DO UPDATE，不覆盖 note（CRUD 不维护）。
事务由调用方控制（不在此提交）。
"""
from sqlalchemy import text
from sqlalchemy.orm import Session


def _f(v, default=0.0):
    """安全转 float；None → default。"""
    if v is None:
        return default
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


# ── 装置（devices_units）──


def upsert_unit(db: Session, data: dict):
    """upsert 装置到 solve_db.devices_units。

    字段：device_id, name, type, max_capacity, safety_stock_thrd,
    low_safety_thrd, current_capacity, refinery_unit_load_pct,
    backend_device_id, enabled
    不覆盖 note（CRUD 不维护）。
    """
    db.execute(text(
        "INSERT INTO solve_db.devices_units "
        "(device_id, name, type, max_capacity, safety_stock_thrd, "
        "low_safety_thrd, current_capacity, refinery_unit_load_pct, "
        "backend_device_id, enabled) "
        "VALUES (:device_id, :name, :type, :max_capacity, :safety_stock_thrd, "
        ":low_safety_thrd, :current_capacity, :refinery_unit_load_pct, "
        ":backend_device_id, :enabled) "
        "ON CONFLICT (device_id) DO UPDATE SET "
        "name=EXCLUDED.name, type=EXCLUDED.type, max_capacity=EXCLUDED.max_capacity, "
        "safety_stock_thrd=EXCLUDED.safety_stock_thrd, "
        "low_safety_thrd=EXCLUDED.low_safety_thrd, "
        "current_capacity=EXCLUDED.current_capacity, "
        "refinery_unit_load_pct=EXCLUDED.refinery_unit_load_pct, "
        "backend_device_id=EXCLUDED.backend_device_id, "
        "enabled=EXCLUDED.enabled"
    ), {
        "device_id": str(data["device_id"]),
        "name": str(data.get("name", "")),
        "type": str(data.get("type", "normal")),
        "max_capacity": _f(data.get("max_capacity")),
        "safety_stock_thrd": _f(data.get("safety_stock_thrd")),
        "low_safety_thrd": _f(data.get("low_safety_thrd")),
        "current_capacity": _f(data.get("current_capacity")),
        "refinery_unit_load_pct": _f(data.get("refinery_unit_load_pct"), 100),
        "backend_device_id": data.get("backend_device_id"),
        "enabled": bool(data.get("enabled", True)),
    })


def delete_unit(db: Session, device_id: str):
    """删除装置（devices_units）。"""
    db.execute(text(
        "DELETE FROM solve_db.devices_units WHERE device_id = :device_id"
    ), {"device_id": device_id})


def replace_units(db: Session, items: list[dict]):
    """全量替换装置：不在列表中的删除，存在的 upsert。返回写入数量。"""
    if not items:
        db.execute(text("TRUNCATE solve_db.devices_units"))
        return 0
    ids = [str(d["device_id"]) for d in items]
    db.execute(text(
        "DELETE FROM solve_db.devices_units WHERE NOT (device_id = ANY(:ids))"
    ), {"ids": ids})
    for d in items:
        upsert_unit(db, d)
    return len(items)


# ── 储罐（devices_tanks）──


def upsert_tank(db: Session, data: dict):
    """upsert 储罐到 solve_db.devices_tanks。

    字段：device_id, name, max_capacity, safety_stock_thrd,
    low_safety_thrd, current_capacity, refinery_unit_load_pct,
    tank_category, material_id, enabled
    不覆盖 note（CRUD 不维护）。
    """
    db.execute(text(
        "INSERT INTO solve_db.devices_tanks "
        "(device_id, name, max_capacity, safety_stock_thrd, "
        "low_safety_thrd, current_capacity, refinery_unit_load_pct, "
        "tank_category, material_id, enabled) "
        "VALUES (:device_id, :name, :max_capacity, :safety_stock_thrd, "
        ":low_safety_thrd, :current_capacity, :refinery_unit_load_pct, "
        ":tank_category, :material_id, :enabled) "
        "ON CONFLICT (device_id) DO UPDATE SET "
        "name=EXCLUDED.name, max_capacity=EXCLUDED.max_capacity, "
        "safety_stock_thrd=EXCLUDED.safety_stock_thrd, "
        "low_safety_thrd=EXCLUDED.low_safety_thrd, "
        "current_capacity=EXCLUDED.current_capacity, "
        "refinery_unit_load_pct=EXCLUDED.refinery_unit_load_pct, "
        "tank_category=EXCLUDED.tank_category, "
        "material_id=EXCLUDED.material_id, "
        "enabled=EXCLUDED.enabled"
    ), {
        "device_id": str(data["device_id"]),
        "name": str(data.get("name", "")),
        "max_capacity": _f(data.get("max_capacity")),
        "safety_stock_thrd": _f(data.get("safety_stock_thrd")),
        "low_safety_thrd": _f(data.get("low_safety_thrd")),
        "current_capacity": _f(data.get("current_capacity")),
        "refinery_unit_load_pct": _f(data.get("refinery_unit_load_pct"), 100),
        "tank_category": str(data.get("tank_category", "intermediate")),
        "material_id": data.get("material_id"),
        "enabled": bool(data.get("enabled", True)),
    })


def delete_tank(db: Session, device_id: str):
    """删除储罐（devices_tanks）。"""
    db.execute(text(
        "DELETE FROM solve_db.devices_tanks WHERE device_id = :device_id"
    ), {"device_id": device_id})


def replace_tanks(db: Session, items: list[dict]):
    """全量替换储罐：不在列表中的删除，存在的 upsert。返回写入数量。"""
    if not items:
        db.execute(text("TRUNCATE solve_db.devices_tanks"))
        return 0
    ids = [str(d["device_id"]) for d in items]
    db.execute(text(
        "DELETE FROM solve_db.devices_tanks WHERE NOT (device_id = ANY(:ids))"
    ), {"ids": ids})
    for d in items:
        upsert_tank(db, d)
    return len(items)
