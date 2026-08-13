# -*- coding: utf-8 -*-
"""物流边只读仓库（solve_db.material_flows）。

全部使用 sqlalchemy.text() 裸 SQL，None 安全处理。
material_flows 建在 solve_db schema，仅 solve_v1 使用。
"""
from typing import List, Optional

from sqlalchemy import text
from sqlalchemy.orm import Session


def _s(v) -> str | None:
    """安全转 str：None 保留为 None，其余转 str。"""
    return None if v is None else str(v)


def load_flows(db: Session) -> List[dict]:
    """查询 solve_db.material_flows 全量物流边。

    返回字段：flow_id, source_type, source_device_id, source_product_id,
    source_name, tank_id, target_device_id, target_product_id, flow_type,
    special_var, priority, is_unique_target, split_ratio

    注意：material_role 已废弃，不再查询。
    """
    rows = db.execute(text(
        "SELECT flow_id, source_type, source_device_id, source_product_id, "
        "source_name, tank_id, target_device_id, target_product_id, flow_type, "
        "special_var, priority, is_unique_target, split_ratio "
        "FROM solve_db.material_flows ORDER BY flow_id"
    )).mappings().all()
    return [
        {
            "flow_id": _s(r["flow_id"]),
            "source_type": _s(r["source_type"]) or "device",
            "source_device_id": _s(r["source_device_id"]),
            "source_product_id": _s(r["source_product_id"]),
            "source_name": _s(r["source_name"]),
            "tank_id": _s(r["tank_id"]),
            "target_device_id": _s(r["target_device_id"]),
            "target_product_id": _s(r["target_product_id"]),
            "flow_type": _s(r["flow_type"]) or "source_to_tank",
            "special_var": _s(r["special_var"]),
            "priority": int(r["priority"]) if r["priority"] is not None else 1,
            "is_unique_target": bool(r["is_unique_target"]) if r["is_unique_target"] is not None else False,
            "split_ratio": float(r["split_ratio"]) if r["split_ratio"] is not None else 1.0,
        }
        for r in rows
    ]


def get_flow(db: Session, flow_id: str) -> Optional[dict]:
    """按 flow_id 精确查询单条物流边。"""
    rows = db.execute(text(
        "SELECT flow_id, source_type, source_device_id, source_product_id, "
        "source_name, tank_id, target_device_id, target_product_id, flow_type, "
        "special_var, priority, is_unique_target, split_ratio "
        "FROM solve_db.material_flows WHERE flow_id = :id"
    ), {"id": flow_id}).mappings().all()
    if not rows:
        return None
    r = rows[0]
    return {
        "flow_id": _s(r["flow_id"]),
        "source_type": _s(r["source_type"]) or "device",
        "source_device_id": _s(r["source_device_id"]),
        "source_product_id": _s(r["source_product_id"]),
        "source_name": _s(r["source_name"]),
        "tank_id": _s(r["tank_id"]),
        "target_device_id": _s(r["target_device_id"]),
        "target_product_id": _s(r["target_product_id"]),
        "flow_type": _s(r["flow_type"]) or "source_to_tank",
        "special_var": _s(r["special_var"]),
        "priority": int(r["priority"]) if r["priority"] is not None else 1,
        "is_unique_target": bool(r["is_unique_target"]) if r["is_unique_target"] is not None else False,
        "split_ratio": float(r["split_ratio"]) if r["split_ratio"] is not None else 1.0,
    }
