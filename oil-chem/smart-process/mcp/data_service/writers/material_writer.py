# -*- coding: utf-8 -*-
"""物料写仓库（public.md_material）。

upsert 以 name 为唯一键（UNIQUE 约束），不传 id 让序列自增。
删除前必须检查外键依赖（3 张显式 FK + 2 张逻辑 FK）。

全部使用 sqlalchemy.text() 裸 SQL。事务由调用方控制（不在此提交）。
"""
from sqlalchemy import text
from sqlalchemy.orm import Session


def check_name_unique(db: Session, name: str, exclude_id: int | None = None) -> bool:
    """检查 name 是否唯一。返回 True 表示唯一（可用）。

    Args:
        name: 物料名称
        exclude_id: 排除指定 id（更新时排除自身）
    """
    if not name:
        return False
    sql = "SELECT 1 FROM public.md_material WHERE name = :name"
    params = {"name": name}
    if exclude_id is not None:
        sql += " AND id <> :exclude_id"
        params["exclude_id"] = exclude_id
    result = db.execute(text(sql), params).first()
    return result is None


def check_dependencies(db: Session, material_id: int) -> dict:
    """检查物料被哪些表引用，返回各表依赖计数。

    返回示例：
        {"material_price": 3, "material_price_default": 1,
         "price_calc_rule": 0, "side_lines": 2, "devices_tanks": 1,
         "total": 7}
    """
    deps = {}
    # public 显式 FK
    for table in ("material_price", "material_price_default", "price_calc_rule"):
        row = db.execute(text(
            f"SELECT COUNT(*) AS cnt FROM public.{table} WHERE material_id = :mid"
        ), {"mid": material_id}).first()
        deps[table] = int(row.cnt) if row else 0

    # solve_db 逻辑 FK（无 DB 级 FK 约束）
    for table in ("side_lines", "devices_tanks"):
        row = db.execute(text(
            f"SELECT COUNT(*) AS cnt FROM solve_db.{table} WHERE material_id = :mid"
        ), {"mid": material_id}).first()
        deps[table] = int(row.cnt) if row else 0

    deps["total"] = sum(deps.values())
    return deps


def upsert_material(db: Session, data: dict):
    """upsert 物料到 public.md_material。

    以 name 为唯一键冲突判断，不传 id（让序列自增）。
    字段：name, category, groms_alias, alias, remark
    """
    name = str(data.get("name", "")).strip()
    if not name:
        raise ValueError("name 不能为空")

    category = str(data.get("category", "")).strip() or None
    groms_alias = str(data.get("groms_alias", "")).strip() or None
    alias = str(data.get("alias", "")).strip() or None
    remark = str(data.get("remark", "")).strip() or None

    db.execute(text(
        "INSERT INTO public.md_material "
        "(name, category, groms_alias, alias, remark) "
        "VALUES (:name, :category, :groms_alias, :alias, :remark) "
        "ON CONFLICT (name) DO UPDATE SET "
        "category=EXCLUDED.category, groms_alias=EXCLUDED.groms_alias, "
        "alias=EXCLUDED.alias, remark=EXCLUDED.remark"
    ), {
        "name": name,
        "category": category,
        "groms_alias": groms_alias,
        "alias": alias,
        "remark": remark,
    })


def update_material(db: Session, material_id: int, data: dict):
    """按主键更新物料，仅更新非 None 字段。"""
    sets = []
    params = {"id": material_id}

    for field in ("name", "category", "groms_alias", "alias", "remark"):
        if field in data and data[field] is not None:
            val = str(data[field]).strip() or None
            sets.append(f"{field} = :{field}")
            params[field] = val

    if not sets:
        raise ValueError("没有需要更新的字段")

    db.execute(text(
        f"UPDATE public.md_material SET {', '.join(sets)} WHERE id = :id"
    ), params)


def delete_material(db: Session, material_id: int):
    """删除物料。调用方应先调 check_dependencies 确认无依赖。

    注意：material_price 等有显式 FK，直接删除会抛 ForeignKeyViolation。
    side_lines/devices_tanks 无 DB FK，删除后会留孤儿 material_id。
    """
    db.execute(text(
        "DELETE FROM public.md_material WHERE id = :id"
    ), {"id": material_id})
