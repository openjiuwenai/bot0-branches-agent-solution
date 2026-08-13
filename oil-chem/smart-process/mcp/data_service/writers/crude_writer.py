# -*- coding: utf-8 -*-
"""油种写仓库（public.crude_types）。

upsert 使用 ON CONFLICT (crude_type_id) DO UPDATE。
default 油种不可删除的校验由 API 路由层负责。

全部使用 sqlalchemy.text() 裸 SQL。事务由调用方控制（不在此提交）。
"""
from sqlalchemy import text
from sqlalchemy.orm import Session


def upsert_crude(db: Session, data: dict):
    """upsert 油种到 public.crude_types。

    字段：crude_type_id, crude_name, crude_code, aliases,
          is_active, is_default, sort_order, note

    aliases 接受 list 或逗号分隔字符串，统一转为 list。
    """
    ct_id = str(data.get("crude_type_id", "")).strip()
    if not ct_id:
        raise ValueError("crude_type_id 不能为空")

    crude_name = str(data.get("crude_name", ct_id)).strip()
    crude_code = str(data.get("crude_code", "")).strip() or None
    aliases = data.get("aliases", [])
    if isinstance(aliases, str):
        aliases = [a.strip() for a in aliases.split(",") if a.strip()]
    is_active = bool(data.get("is_active", True))
    is_default = bool(data.get("is_default", False)) or ct_id == "default"
    sort_order = int(data.get("sort_order", 99))
    note = str(data.get("note", "")).strip() or None

    db.execute(text(
        "INSERT INTO public.crude_types "
        "(crude_type_id, crude_name, crude_code, aliases, "
        "is_active, is_default, sort_order, note) VALUES "
        "(:id, :name, :code, :aliases, :active, :default, :sort, :note) "
        "ON CONFLICT (crude_type_id) DO UPDATE SET "
        "crude_name=EXCLUDED.crude_name, crude_code=EXCLUDED.crude_code, "
        "aliases=EXCLUDED.aliases, is_active=EXCLUDED.is_active, "
        "is_default=EXCLUDED.is_default, sort_order=EXCLUDED.sort_order, "
        "note=EXCLUDED.note"
    ), {
        "id": ct_id,
        "name": crude_name,
        "code": crude_code,
        "aliases": aliases,
        "active": is_active,
        "default": is_default,
        "sort": sort_order,
        "note": note,
    })


def delete_crude(db: Session, crude_type_id: str):
    """删除油种。default 油种不可删（由路由层校验）。"""
    db.execute(text(
        "DELETE FROM public.crude_types WHERE crude_type_id = :id"
    ), {"id": crude_type_id})


def update_active(db: Session, crude_type_id: str, is_active: bool):
    """仅切换油种激活状态。"""
    db.execute(text(
        "UPDATE public.crude_types SET is_active = :active "
        "WHERE crude_type_id = :id"
    ), {"id": crude_type_id, "active": is_active})
