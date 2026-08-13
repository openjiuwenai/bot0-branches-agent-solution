# -*- coding: utf-8 -*-
"""价格读直连仓库 —— C 混合模式：读直连 DB，写走 API。

封装 public.material_price / material_price_default + solve_db.side_lines 的联合查询，
替代原来 solve_v1 通过 HTTP 代理 FastAPI /api/v1/price/material 的读路径。

关键收益：
- 消除 HTTP 网络往返（solve_v1 可独立运行，不依赖 FastAPI :8000 在线）
- 一条 SQL 完成价格 + 侧线映射 + 产品名拼装
"""
from typing import List, Optional
from decimal import Decimal
from sqlalchemy import text
from sqlalchemy.orm import Session

# 跨场景缓存：{(month, frozenset(material_ids)): {material_id_str: price}}
# 价格只依赖 (month, material_id)，与场景无关，同一进程内复用
_batch_price_cache: dict = {}
_device_cost_cache: dict = {}


def _f(v, default=None):
    """Decimal/None → float，None 保留。"""
    if v is None:
        return default
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def load_material_prices(db: Session, month: Optional[str] = None) -> List[dict]:
    """查询物料价格（生效价 = 当月价 ?? 默认价）。

    对标 FastAPI /api/v1/price/material GET 的 COALESCE 逻辑。
    返回: [{month, material_id, material_name, price, default_price, is_overridden}]
    """
    sql = text("""
        SELECT
            mp.id                                          AS id,
            COALESCE(mp.month, mpd.month)                  AS month,
            COALESCE(mp.material_id, mpd.material_id)      AS material_id,
            COALESCE(mp.material_name, mpd.material_name)  AS material_name,
            mp.price                                       AS override_price,
            mpd.price                                      AS default_price
        FROM public.material_price_default mpd
        FULL OUTER JOIN public.material_price mp
            ON mp.material_id = mpd.material_id AND mp.month = mpd.month
        WHERE (:month IS NULL OR COALESCE(mp.month, mpd.month) = :month)
        ORDER BY COALESCE(mp.material_id, mpd.material_id)
    """)
    rows = db.execute(sql, {"month": month}).mappings().all()
    result = []
    for r in rows:
        override = _f(r["override_price"])
        default = _f(r["default_price"])
        result.append({
            "id": r["id"],
            "month": r["month"],
            "material_id": int(r["material_id"]) if r["material_id"] else None,
            "material_name": str(r["material_name"] or ""),
            "price": override if override is not None else default,
            "default_price": default,
            "is_overridden": override is not None,
        })
    return result


def load_side_line_prices(db: Session, month: Optional[str] = None) -> List[dict]:
    """一条 SQL 完成侧线→物料→价格联合查询。

    替代原 price_cost_routes.get_price_cost_products 的三段式拼装
    （HTTP 取价格 + DB 取映射 + DB 取产品名）。
    只返回已绑定 material_id 的侧线。
    返回: [{price_month, product_id, product_name, material_id, material_name, price, is_overridden}]
    """
    sql = text("""
        SELECT
            COALESCE(mp.month, mpd.month)                  AS price_month,
            sl.side_line_id                                AS product_id,
            sl.name                                        AS product_name,
            m.id                                           AS material_id,
            m.name                                         AS material_name,
            COALESCE(mp.price, mpd.price)                  AS price,
            mp.price IS NOT NULL                           AS is_overridden
        FROM solve_db.side_lines sl
        JOIN public.md_material m ON sl.material_id = m.id
        LEFT JOIN public.material_price mp
            ON mp.material_id = m.id AND (:month IS NOT NULL AND mp.month = :month)
        LEFT JOIN public.material_price_default mpd
            ON mpd.material_id = m.id AND (:month IS NOT NULL AND mpd.month = :month)
        WHERE sl.material_id IS NOT NULL
          AND (:month IS NULL OR COALESCE(mp.month, mpd.month) = :month)
        ORDER BY sl.side_line_id
    """)
    rows = db.execute(sql, {"month": month}).mappings().all()
    result = []
    for r in rows:
        result.append({
            "price_month": str(r["price_month"]) if r["price_month"] else (month or ""),
            "product_id": str(r["product_id"]),
            "product_name": str(r["product_name"] or ""),
            "material_id": int(r["material_id"]) if r["material_id"] else None,
            "material_name": str(r["material_name"] or ""),
            "price": _f(r["price"], 0),
            "is_overridden": bool(r["is_overridden"]),
        })
    return result


def load_all_materials(db: Session) -> List[dict]:
    """查询全部物料主数据（供映射页下拉选择）。

    替代原 price_cost_routes.get_product_mapping 中的 HTTP 调用。
    返回: [{material_id, material_name}]
    """
    sql = text("SELECT id, name FROM public.md_material ORDER BY id")
    rows = db.execute(sql).mappings().all()
    return [{"material_id": int(r["id"]), "material_name": str(r["name"] or "")}
            for r in rows]


def load_price_months(db: Session) -> List[str]:
    """查询所有有价格数据的月份（默认表 + 当月表并集）。"""
    sql = text("""
        SELECT month FROM public.material_price_default
        UNION SELECT month FROM public.material_price
        ORDER BY month DESC
    """)
    return [str(r[0]) for r in db.execute(sql).all()]


# ── 求解器核心：物料价格解析（含计算规则回退）──────────────────────────


def resolve_price(db: Session, month: str, material_id: int) -> Optional[float]:
    """获取物料生效价：当月表优先，回退默认表。

    对标 FastAPI resolve_price()。
    返回价格 float，或 None（无数据）。
    """
    sql = text("""
        SELECT COALESCE(mp.price, mpd.price) AS price
        FROM public.material_price_default mpd
        LEFT JOIN public.material_price mp
            ON mp.material_id = mpd.material_id AND mp.month = mpd.month
        WHERE mpd.month = :month AND mpd.material_id = :mid
        UNION ALL
        SELECT mp.price AS price
        FROM public.material_price mp
        WHERE mp.month = :month AND mp.material_id = :mid
          AND NOT EXISTS (
            SELECT 1 FROM public.material_price_default mpd
            WHERE mpd.month = :month AND mpd.material_id = :mid
          )
        LIMIT 1
    """)
    result = db.execute(sql, {"month": month, "mid": material_id}).scalar()
    return float(result) if result is not None else None


def resolve_price_or_calc(db: Session, month: str, material_id: int) -> Optional[float]:
    """获取物料生效价，无则按计算规则试算。

    对标 FastAPI _resolve_price_or_calc()。
    1. 当月价 ?? 默认价
    2. 无价格 → PriceCalcRule + RefPriceBenchmarkDefault 计算规则
    返回价格 float，或 None（无数据且无计算规则）。
    """
    # 1. 直接价格
    price = resolve_price(db, month, material_id)
    if price is not None:
        return price

    # 2. 计算规则回退
    rule_row = db.execute(text(
        "SELECT ref_product_id, price_coefficient, price_offset, "
        "tax_rate, excise_deduction "
        "FROM public.price_calc_rule WHERE material_id = :mid LIMIT 1"
    ), {"mid": material_id}).mappings().first()
    if not rule_row:
        return None

    bench_row = db.execute(text(
        "SELECT price FROM public.ref_price_benchmark_default "
        "WHERE month = :month AND ref_product_id = :rpid LIMIT 1"
    ), {"month": month, "rpid": rule_row["ref_product_id"]}).mappings().first()
    if not bench_row or bench_row["price"] is None:
        return None

    coeff = float(rule_row["price_coefficient"] or 1.0)
    offset = float(rule_row["price_offset"] or 0.0)
    tax = float(rule_row["tax_rate"] or 0.0)
    excise = float(rule_row["excise_deduction"] or 0.0)
    bench_price = float(bench_row["price"])
    return (bench_price * coeff + offset) / (1 + tax) - excise * 1.1356


def resolve_prices_batch(db: Session, month: str, material_ids: list[int]) -> dict[str, float]:
    """批量获取物料价格（含计算规则解析），返回 {material_id_str: price}。

    对标 FastAPI /api/v1/price/material/batch GET。
    一次 DB 查询取所有直接价格，缺失的逐个走计算规则。
    跨场景缓存：同一 (month, material_ids) 只查询一次。
    """
    if not material_ids:
        return {}

    # 跨场景缓存：价格只依赖 (month, material_id)，与场景无关
    cache_key = (month, frozenset(material_ids))
    if cache_key in _batch_price_cache:
        return _batch_price_cache[cache_key]

    # 批量取直接价格（COALESCE）
    rows = db.execute(text("""
        SELECT material_id, price FROM (
            SELECT mpd.material_id, COALESCE(mp.price, mpd.price) AS price
            FROM public.material_price_default mpd
            LEFT JOIN public.material_price mp
                ON mp.material_id = mpd.material_id AND mp.month = mpd.month
            WHERE mpd.month = :month AND mpd.material_id = ANY(:ids)
            UNION ALL
            SELECT mp.material_id, mp.price
            FROM public.material_price mp
            WHERE mp.month = :month AND mp.material_id = ANY(:ids)
              AND NOT EXISTS (
                SELECT 1 FROM public.material_price_default mpd
                WHERE mpd.month = :month AND mpd.material_id = mp.material_id
              )
        ) t
    """), {"month": month, "ids": material_ids}).mappings().all()

    direct_map = {}
    for r in rows:
        if r["price"] is not None:
            direct_map[int(r["material_id"])] = float(r["price"])

    # 缺失的走计算规则
    result = {}
    for mid in material_ids:
        if mid in direct_map:
            result[str(mid)] = direct_map[mid]
        else:
            calc_price = resolve_price_or_calc(db, month, mid)
            if calc_price is not None:
                result[str(mid)] = calc_price

    _batch_price_cache[cache_key] = result
    return result


# ── 装置加工成本 ─────────────────────────────────────────────────────


def load_device_costs(db: Session, month: str) -> dict[int, float]:
    """获取所有装置月度加工成本（元/吨）。

    对标 FastAPI /api/v1/device/cost/list GET。
    优先级：device_cost(当月) ?? device_cost_default(默认) ?? md_device.default_cost
    返回 {backend_device_id: unit_cost}
    跨场景缓存：同一 month 只查询一次。
    """
    if month in _device_cost_cache:
        return _device_cost_cache[month]

    sql = text("""
        SELECT
            COALESCE(dc.device_id, dcd.device_id, md.id) AS device_id,
            COALESCE(dc.unit_cost, dcd.unit_cost, md.default_cost) AS unit_cost
        FROM public.md_device md
        LEFT JOIN public.device_cost dc
            ON dc.device_id = md.id AND dc.month = :month
        LEFT JOIN public.device_cost_default dcd
            ON dcd.device_id = md.id AND dcd.month = :month
        WHERE COALESCE(dc.unit_cost, dcd.unit_cost, md.default_cost) IS NOT NULL
    """)
    rows = db.execute(sql, {"month": month}).mappings().all()
    result = {int(r["device_id"]): float(r["unit_cost"]) for r in rows
              if r["unit_cost"] is not None}
    _device_cost_cache[month] = result
    return result
