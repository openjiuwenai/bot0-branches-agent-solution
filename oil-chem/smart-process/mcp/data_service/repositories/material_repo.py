# -*- coding: utf-8 -*-
"""物料只读仓库（public.md_material）。

全部使用 sqlalchemy.text() 裸 SQL，Decimal 转 float，None 安全处理。
"""
from typing import List

from sqlalchemy import text
from sqlalchemy.orm import Session


def _s(v) -> str | None:
    """安全转 str：None 保留为 None，其余转 str。"""
    return None if v is None else str(v)


def load_materials(db: Session) -> List[dict]:
    """查询 public.md_material 全量物料。

    返回字段：id, name, category, groms_alias, alias, remark
    """
    rows = db.execute(text(
        "SELECT id, name, category, groms_alias, alias, remark "
        "FROM public.md_material ORDER BY id"
    )).mappings().all()
    return [
        {
            "id": int(r["id"]),
            "name": _s(r["name"]),
            "category": _s(r["category"]),
            "groms_alias": _s(r["groms_alias"]),
            "alias": _s(r["alias"]),
            "remark": _s(r["remark"]),
        }
        for r in rows
    ]
