# -*- coding: utf-8 -*-
"""侧线/收率写仓库（side_lines + device_yields）。

upsert 使用 ON CONFLICT DO UPDATE：
  - side_lines   ON CONFLICT (side_line_id)
  - device_yields ON CONFLICT (side_line_id, crude_type)
side_lines 删除时 device_yields 由外键 ON DELETE CASCADE 自动清理。

全部使用 sqlalchemy.text() 裸 SQL。事务由调用方控制（不在此提交）。
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


def upsert_side_line(db: Session, data: dict):
    """upsert 侧线主数据到 solve_db.side_lines。

    字段：side_line_id, name, source_device_id, material_type, is_final, material_id
    """
    db.execute(text(
        "INSERT INTO solve_db.side_lines "
        "(side_line_id, name, source_device_id, material_type, is_final, material_id) "
        "VALUES (:side_line_id, :name, :source_device_id, :material_type, :is_final, :material_id) "
        "ON CONFLICT (side_line_id) DO UPDATE SET "
        "name=EXCLUDED.name, source_device_id=EXCLUDED.source_device_id, "
        "material_type=EXCLUDED.material_type, is_final=EXCLUDED.is_final, "
        "material_id=EXCLUDED.material_id"
    ), {
        "side_line_id": str(data["side_line_id"]),
        "name": str(data.get("name", "")),
        "source_device_id": str(data.get("source_device_id", "")),
        "material_type": str(data.get("material_type", "product")),
        "is_final": bool(data.get("is_final", False)),
        "material_id": data.get("material_id"),
    })


def upsert_yields(db: Session, items: list[dict]):
    """批量 upsert 收率到 solve_db.device_yields。

    每个 item：side_line_id, crude_type, yield_rate, yield_rate_2, yield_rate_3, yield_rate_4
    复合主键 (side_line_id, crude_type) 冲突时更新。
    """
    for item in items:
        db.execute(text(
            "INSERT INTO solve_db.device_yields "
            "(side_line_id, crude_type, yield_rate, yield_rate_2, yield_rate_3, yield_rate_4) "
            "VALUES (:side_line_id, :crude_type, :yield_rate, :yield_rate_2, "
            ":yield_rate_3, :yield_rate_4) "
            "ON CONFLICT (side_line_id, crude_type) DO UPDATE SET "
            "yield_rate=EXCLUDED.yield_rate, yield_rate_2=EXCLUDED.yield_rate_2, "
            "yield_rate_3=EXCLUDED.yield_rate_3, yield_rate_4=EXCLUDED.yield_rate_4"
        ), {
            "side_line_id": str(item["side_line_id"]),
            "crude_type": str(item.get("crude_type", "default")),
            "yield_rate": _f(item.get("yield_rate")),
            "yield_rate_2": _f(item.get("yield_rate_2")),
            "yield_rate_3": _f(item.get("yield_rate_3")),
            "yield_rate_4": _f(item.get("yield_rate_4")),
        })


def delete_side_line(db: Session, side_line_id: str):
    """删除侧线（side_lines）。device_yields 由外键 ON DELETE CASCADE 自动清理。"""
    db.execute(text(
        "DELETE FROM solve_db.side_lines WHERE side_line_id = :side_line_id"
    ), {"side_line_id": side_line_id})


def update_material_binding(db: Session, side_line_id: str, material_id: int | None):
    """更新侧线的物料绑定（material_id）。传 None 解绑。"""
    db.execute(text(
        "UPDATE solve_db.side_lines SET material_id = :material_id "
        "WHERE side_line_id = :side_line_id"
    ), {"side_line_id": side_line_id, "material_id": material_id})


# ── 批量替换（全量 replace 语义）── 供 refinery_repo.save_products 调用


def replace_side_lines(db: Session, items: list[dict]):
    """全量替换 side_lines：不在列表中的删除，存在的 upsert。

    入参 items：[{side_line_id, name, source_device_id, material_type, is_final}, ...]
    按 side_line_id 去重 upsert（同一 side_line_id 只写一次）。
    注意：不覆盖 material_id（由 update_material_binding 单独管理）。
    """
    if not items:
        db.execute(text("TRUNCATE solve_db.side_lines"))
        return

    # 收集所有 side_line_id，删除不在列表中的
    sl_ids = list({str(it["side_line_id"]) for it in items})
    db.execute(text(
        "DELETE FROM solve_db.side_lines WHERE NOT (side_line_id = ANY(:ids))"
    ), {"ids": sl_ids})

    # 去重 upsert
    written = set()
    for it in items:
        sid = str(it["side_line_id"])
        if sid in written:
            continue
        written.add(sid)
        db.execute(text(
            "INSERT INTO solve_db.side_lines "
            "(side_line_id, name, source_device_id, material_type, is_final) "
            "VALUES (:side_line_id, :name, :source_device_id, :material_type, :is_final) "
            "ON CONFLICT (side_line_id) DO UPDATE SET "
            "name=EXCLUDED.name, source_device_id=EXCLUDED.source_device_id, "
            "material_type=EXCLUDED.material_type, is_final=EXCLUDED.is_final"
        ), {
            "side_line_id": sid,
            "name": str(it.get("name", "")),
            "source_device_id": str(it.get("source_device_id", "")),
            "material_type": str(it.get("material_type", "product")),
            "is_final": bool(it.get("is_final", False)),
        })


def replace_yields(db: Session, items: list[dict]):
    """全量替换 device_yields：不在列表中的 (side_line_id, crude_type) 组合删除，存在的 upsert。

    入参 items：[{side_line_id, crude_type, yield_rate, yield_rate_2, yield_rate_3, yield_rate_4}, ...]
    """
    if not items:
        db.execute(text("TRUNCATE solve_db.device_yields"))
        return

    # 构建 (side_line_id, crude_type) 对集合，删除不在列表中的组合
    pairs = [(str(it["side_line_id"]), str(it.get("crude_type", "BZ"))) for it in items]
    cond_parts = []
    params = {}
    for i, (pid, ct) in enumerate(pairs):
        params[f"p{i}"] = pid
        params[f"c{i}"] = ct
        cond_parts.append(f"(:p{i}, :c{i})")
    db.execute(text(
        "DELETE FROM solve_db.device_yields "
        f"WHERE (side_line_id, crude_type) NOT IN ({', '.join(cond_parts)})"
    ), params)

    # upsert 收率
    upsert_yields(db, items)
