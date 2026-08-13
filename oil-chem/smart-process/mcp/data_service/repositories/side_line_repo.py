# -*- coding: utf-8 -*-
"""侧线/收率只读仓库（side_lines + device_yields）。

迁移后 products 表已拆分为：
  - side_lines    —— 侧线主数据（material_id 直接关联 public.md_material，替代 product_material_mapping）
  - device_yields —— 收率（复合主键 side_line_id + crude_type）

全部使用 sqlalchemy.text() 裸 SQL，Decimal 转 float，None 安全处理。
SQL 使用完全限定表名（solve_db.xxx / public.xxx）。
"""
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


def load_side_lines(db: Session, device_id: str = None) -> List[dict]:
    """查询侧线主数据，LEFT JOIN public.md_material 取 material_name。

    可按 source_device_id 过滤。
    返回字段：side_line_id, name, source_device_id, material_type, is_final,
              material_id, material_name, note
    """
    sql = (
        "SELECT sl.side_line_id, sl.name, sl.source_device_id, sl.material_type, "
        "sl.is_final, sl.material_id, m.name AS material_name, sl.note "
        "FROM solve_db.side_lines sl "
        "LEFT JOIN public.md_material m ON sl.material_id = m.id"
    )
    params: dict = {}
    if device_id is not None:
        sql += " WHERE sl.source_device_id = :device_id"
        params["device_id"] = device_id
    sql += " ORDER BY sl.side_line_id"
    rows = db.execute(text(sql), params).mappings().all()
    return [
        {
            "side_line_id": _s(r["side_line_id"]),
            "name": _s(r["name"]),
            "source_device_id": _s(r["source_device_id"]),
            "material_type": _s(r["material_type"]),
            "is_final": bool(r["is_final"]) if r["is_final"] is not None else False,
            "material_id": int(r["material_id"]) if r["material_id"] is not None else None,
            "material_name": _s(r["material_name"]),
            "note": _s(r["note"]),
        }
        for r in rows
    ]


def load_yields(db: Session, side_line_id: str = None, crude_type: str = None) -> List[dict]:
    """查询收率，可按 side_line_id 和/或 crude_type 过滤。

    返回字段：side_line_id, crude_type, yield_rate, yield_rate_2, yield_rate_3, yield_rate_4
    """
    sql = (
        "SELECT side_line_id, crude_type, yield_rate, yield_rate_2, "
        "yield_rate_3, yield_rate_4 FROM solve_db.device_yields WHERE 1=1"
    )
    params: dict = {}
    if side_line_id is not None:
        sql += " AND side_line_id = :side_line_id"
        params["side_line_id"] = side_line_id
    if crude_type is not None:
        sql += " AND crude_type = :crude_type"
        params["crude_type"] = crude_type
    sql += " ORDER BY side_line_id, crude_type"
    rows = db.execute(text(sql), params).mappings().all()
    return [
        {
            "side_line_id": _s(r["side_line_id"]),
            "crude_type": _s(r["crude_type"]),
            "yield_rate": _f(r["yield_rate"]),
            "yield_rate_2": _f(r["yield_rate_2"]),
            "yield_rate_3": _f(r["yield_rate_3"]),
            "yield_rate_4": _f(r["yield_rate_4"]),
        }
        for r in rows
    ]


def load_side_lines_with_yields(db: Session, crude_type: str = None) -> List[dict]:
    """JOIN side_lines + device_yields，按 crude_type 过滤（含 'default' 回退）。

    回退逻辑（对齐 solve_v1 refinery_repo.load_products）：
    当 crude_type 非空时，先取该油种行，再用 crude_type='default' 的行补充缺失的
    side_line_id（与油种无关的装置只需配一行 default）。
    返回合并 dict：side_line_id, name, source_device_id, material_type, is_final,
                    crude_type, yield_rate, yield_rate_2, yield_rate_3, yield_rate_4,
                    material_id, material_name
    """
    sql = (
        "SELECT sl.side_line_id, sl.name, sl.source_device_id, sl.material_type, "
        "sl.is_final, sl.material_id, m.name AS material_name, "
        "y.crude_type, y.yield_rate, y.yield_rate_2, y.yield_rate_3, y.yield_rate_4 "
        "FROM solve_db.side_lines sl "
        "LEFT JOIN solve_db.device_yields y ON sl.side_line_id = y.side_line_id "
        "LEFT JOIN public.md_material m ON sl.material_id = m.id"
    )
    params: dict = {}
    if crude_type is not None:
        sql += " WHERE y.crude_type = :crude_type OR y.crude_type = 'default'"
        params["crude_type"] = crude_type
    sql += " ORDER BY sl.side_line_id, y.crude_type"
    rows = db.execute(text(sql), params).mappings().all()

    # crude_type 精确匹配优先于 default（同一 side_line_id 只保留一行）
    result: List[dict] = []
    seen: dict[str, dict] = {}
    for r in rows:
        sid = _s(r["side_line_id"])
        if not sid:
            continue
        row_ct = _s(r["crude_type"]) or ""
        if sid in seen and row_ct == "default":
            continue  # 已有精确匹配，跳过 default 行
        item = {
            "side_line_id": sid,
            "name": _s(r["name"]),
            "source_device_id": _s(r["source_device_id"]),
            "material_type": _s(r["material_type"]),
            "is_final": bool(r["is_final"]) if r["is_final"] is not None else False,
            "crude_type": row_ct,
            "yield_rate": _f(r["yield_rate"]),
            "yield_rate_2": _f(r["yield_rate_2"]),
            "yield_rate_3": _f(r["yield_rate_3"]),
            "yield_rate_4": _f(r["yield_rate_4"]),
            "material_id": int(r["material_id"]) if r["material_id"] is not None else None,
            "material_name": _s(r["material_name"]),
        }
        seen[sid] = item
    return list(seen.values())
