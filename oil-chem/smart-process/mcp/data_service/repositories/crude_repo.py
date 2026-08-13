# -*- coding: utf-8 -*-
"""油种只读仓库（public.crude_types）。

全部使用 sqlalchemy.text() 裸 SQL，None 安全处理。
crude_types 建在 public schema，慧炼和 solve_v1 双侧共享。
"""
from typing import List, Optional

from sqlalchemy import text
from sqlalchemy.orm import Session


def _s(v) -> str | None:
    """安全转 str：None 保留为 None，其余转 str。"""
    return None if v is None else str(v)


def load_crudes(db: Session, active_only: bool = False) -> List[dict]:
    """查询 public.crude_types 全量油种。

    返回字段：crude_type_id, crude_name, crude_code, aliases,
    is_active, is_default, sort_order, note

    Args:
        active_only: 仅返回 is_active=true 的记录
    """
    sql = (
        "SELECT crude_type_id, crude_name, crude_code, aliases, "
        "is_active, is_default, sort_order, note "
        "FROM public.crude_types"
    )
    if active_only:
        sql += " WHERE is_active = true"
    sql += " ORDER BY sort_order, crude_name"

    rows = db.execute(text(sql)).mappings().all()
    return [
        {
            "crude_type_id": _s(r["crude_type_id"]),
            "crude_name": _s(r["crude_name"]),
            "crude_code": _s(r["crude_code"]) or "",
            "aliases": list(r["aliases"]) if r["aliases"] else [],
            "is_active": bool(r["is_active"]) if r["is_active"] is not None else True,
            "is_default": bool(r["is_default"]) if r["is_default"] is not None else False,
            "sort_order": int(r["sort_order"]) if r["sort_order"] is not None else 0,
            "note": _s(r["note"]),
        }
        for r in rows
    ]


def get_crude(db: Session, crude_type_id: str) -> Optional[dict]:
    """按 crude_type_id 精确查询单条油种。"""
    rows = db.execute(text(
        "SELECT crude_type_id, crude_name, crude_code, aliases, "
        "is_active, is_default, sort_order, note "
        "FROM public.crude_types WHERE crude_type_id = :id"
    ), {"id": crude_type_id}).mappings().all()
    if not rows:
        return None
    r = rows[0]
    return {
        "crude_type_id": _s(r["crude_type_id"]),
        "crude_name": _s(r["crude_name"]),
        "crude_code": _s(r["crude_code"]) or "",
        "aliases": list(r["aliases"]) if r["aliases"] else [],
        "is_active": bool(r["is_active"]) if r["is_active"] is not None else True,
        "is_default": bool(r["is_default"]) if r["is_default"] is not None else False,
        "sort_order": int(r["sort_order"]) if r["sort_order"] is not None else 0,
        "note": _s(r["note"]),
    }


def find_by_alias(db: Session, name: str) -> Optional[dict]:
    """按 crude_name 精确或 aliases 数组反查油种。

    aliases 是 TEXT[] 数组，用 = ANY 匹配。
    """
    if not name:
        return None
    rows = db.execute(text(
        "SELECT crude_type_id, crude_name, crude_code, aliases, "
        "is_active, is_default, sort_order, note "
        "FROM public.crude_types "
        "WHERE crude_name = :name OR :name = ANY(aliases) "
        "LIMIT 1"
    ), {"name": name}).mappings().all()
    if not rows:
        return None
    r = rows[0]
    return {
        "crude_type_id": _s(r["crude_type_id"]),
        "crude_name": _s(r["crude_name"]),
        "crude_code": _s(r["crude_code"]) or "",
        "aliases": list(r["aliases"]) if r["aliases"] else [],
        "is_active": bool(r["is_active"]) if r["is_active"] is not None else True,
        "is_default": bool(r["is_default"]) if r["is_default"] is not None else False,
        "sort_order": int(r["sort_order"]) if r["sort_order"] is not None else 0,
        "note": _s(r["note"]),
    }
