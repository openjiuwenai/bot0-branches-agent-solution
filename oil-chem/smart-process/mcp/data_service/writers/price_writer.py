# -*- coding: utf-8 -*-
"""物料价格写仓库（public.material_price）。

upsert 语义：按 (month, material_id) 判断存在性，存在则更新 price/source，否则插入。
material_name 为 NOT NULL，写入前从 public.md_material 查询补全。
source 字段记录写入来源（api/mcp/manual），跟踪谁写了这条价格。

注意：public.material_price 原表无 source 列，此处用 ADD COLUMN IF NOT EXISTS
幂等补列（对齐 solve_v1/backend/data/db.py:_migrate 的增量迁移模式），纯 PG 环境无副作用。

全部使用 sqlalchemy.text() 裸 SQL。事务由调用方控制（不在此提交）。
"""
from sqlalchemy import text
from sqlalchemy.orm import Session


def _ensure_source_column(db: Session):
    """幂等补 source 列（若已存在则跳过）。"""
    db.execute(text(
        "ALTER TABLE public.material_price "
        "ADD COLUMN IF NOT EXISTS source VARCHAR(16) DEFAULT 'api'"
    ))


def upsert_price(db: Session, month: str, material_id: int, price: float, source: str = "api"):
    """upsert 物料价格到 public.material_price。

    Args:
        month: 月份 YYYY-MM
        material_id: 物料ID（public.md_material.id）
        price: 价格（元/吨）
        source: 写入来源（api/mcp/manual），默认 'api'
    """
    _ensure_source_column(db)

    # 查 material_name（NOT NULL 列，需补全）
    mat_name = db.execute(text(
        "SELECT name FROM public.md_material WHERE id = :mid"
    ), {"mid": material_id}).scalar()
    if mat_name is None:
        raise ValueError(f"物料ID={material_id} 不存在于 public.md_material，无法写入价格")

    db.execute(text(
        "INSERT INTO public.material_price (month, material_id, material_name, price, source) "
        "VALUES (:month, :material_id, :material_name, :price, :source) "
        "ON CONFLICT (month, material_id) DO UPDATE SET "
        "material_name=EXCLUDED.material_name, price=EXCLUDED.price, source=EXCLUDED.source"
    ), {
        "month": month,
        "material_id": material_id,
        "material_name": mat_name,
        "price": price,
        "source": source,
    })
