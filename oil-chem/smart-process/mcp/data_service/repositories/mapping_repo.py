# -*- coding: utf-8 -*-
"""物料映射与进料配比只读仓库。

封装 side_lines.material_id 相关的映射查询，以及 main_feed 类型的收率查询。
替代 refinery_repo 中的内联 SQL。
"""
from typing import Dict, List
from sqlalchemy import text
from sqlalchemy.orm import Session


def load_product_material_mapping(db: Session) -> Dict[str, int]:
    """加载侧线→物料映射 {side_line_id: material_id}。

    替代 refinery_repo.load_product_material_mapping。
    """
    rows = db.execute(text(
        "SELECT side_line_id, material_id FROM solve_db.side_lines "
        "WHERE material_id IS NOT NULL"
    )).mappings().all()
    return {str(r["side_line_id"]): int(r["material_id"]) for r in rows}


def load_material_name_map(db: Session) -> Dict[str, int]:
    """加载物料名称→ID映射 {material_name: material_id}。

    从 side_lines JOIN public.md_material 读取。
    替代 refinery_repo.load_material_name_map。
    """
    rows = db.execute(text(
        "SELECT DISTINCT m.name AS material_name, m.id AS material_id "
        "FROM solve_db.side_lines sl "
        "JOIN public.md_material m ON sl.material_id = m.id "
        "WHERE sl.material_id IS NOT NULL"
    )).mappings().all()
    return {str(r["material_name"]): int(r["material_id"]) for r in rows}


def get_feed_ratio(db: Session, device_id: str, crude_type: str) -> float:
    """查询装置进料配比（main_feed 类型的 yield_rate）。

    替代 refinery_repo.get_feed_ratio 模块级函数。
    返回进料配比，未找到返回 1.0。
    """
    result = db.execute(text(
        "SELECT y.yield_rate "
        "FROM solve_db.side_lines sl "
        "JOIN solve_db.device_yields y ON sl.side_line_id = y.side_line_id "
        "WHERE sl.source_device_id = :device_id "
        "  AND sl.material_type = 'main_feed' "
        "  AND y.crude_type = :crude_type "
        "  AND y.yield_rate > 0 "
        "LIMIT 1"
    ), {"device_id": device_id, "crude_type": crude_type}).scalar()
    return float(result) if result else 1.0


def get_main_feed_products(db: Session, device_id: str, crude_type: str) -> List[dict]:
    """查询装置的所有主料信息。

    替代 refinery_repo.get_main_feed_products 模块级函数。
    返回 [{product_id, yield_rate}, ...]
    """
    rows = db.execute(text(
        "SELECT sl.side_line_id AS product_id, y.yield_rate "
        "FROM solve_db.side_lines sl "
        "JOIN solve_db.device_yields y ON sl.side_line_id = y.side_line_id "
        "WHERE sl.source_device_id = :device_id "
        "  AND sl.material_type = 'main_feed' "
        "  AND y.crude_type = :crude_type "
        "  AND y.yield_rate > 0"
    ), {"device_id": device_id, "crude_type": crude_type}).mappings().all()
    return [{"product_id": str(r["product_id"]), "yield_rate": float(r["yield_rate"])}
            for r in rows]
