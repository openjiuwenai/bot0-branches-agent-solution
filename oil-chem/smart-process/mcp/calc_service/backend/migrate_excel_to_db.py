# -*- coding: utf-8 -*-
"""一次性迁移脚本：refinery_data.xlsx → PostgreSQL solve_db schema。

读 Excel 全 8 sheet，做单位/类型转换后插入 solve_db 表。幂等：先 TRUNCATE 再插。

转换规则（与 config/models 约定一致）：
  - 收率 yield_rate*：Excel 百分比(7.81) → DB 小数(0.0781)，÷100
  - is_final：0/1 int → BOOLEAN
  - arrival_plan/blend_detail/crude_stock_status：JSON 字符串 → JSONB（传 dict）
  - plan_date：'YYYY-MM-DD' 文本 → DATE
  - created_at/updated_at/generated_at：ISO 文本 → TIMESTAMPTZ
  - crude_type_name 乱码(??25-1)：用 crude_type_id 兜底（避免乱码入库）
  - 冗余/杂列丢弃：energy.energy_id(=id)、energy.note(空)、devices.Unnamed:9/10(空)

运行：python -m calc_service.backend.migrate_excel_to_db
"""
import json
from datetime import datetime

import pandas as pd
from sqlalchemy import text
from sqlalchemy.dialects.postgresql import JSONB

from .config import EXCEL_PATH
from .data.db import engine, init_db, DB_SCHEMA


def _json(v):
    """dict/str → psycopg2 Json 适配器（JSONB 列写入必需）。"""
    from psycopg2.extras import Json
    if isinstance(v, str):
        try:
            v = json.loads(v)
        except Exception:
            v = {}
    return Json(v if isinstance(v, dict) else {})


def _f(v, default=0.0):
    if v is None or (isinstance(v, float) and v != v):
        return default
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def _safe_str(v, default=''):
    if v is None or (isinstance(v, float) and v != v):
        return default
    return str(v).strip()


def _parse_json(v):
    """Excel JSON 字符串 → dict；空/失败 → {}。"""
    if v is None or (isinstance(v, float) and v != v):
        return {}
    if isinstance(v, dict):
        return v
    if isinstance(v, str):
        try:
            parsed = json.loads(v)
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            return {}
    return {}


# ── 各表迁移 ──────────────────────────────────────────────────────────────

def _migrate_devices(xls):
    df = xls.parse('devices')
    rows = []
    for _, r in df.iterrows():
        rows.append({
            'device_id': _safe_str(r.get('device_id')),
            'name': _safe_str(r.get('name', r.get('device_name')), 'Unknown'),
            'type': _safe_str(r.get('type', 'normal'), 'normal').lower(),
            'max_capacity': _f(r.get('max_capacity', r.get('capacity'))),
            'safety_stock_thrd': _f(r.get('SafetyStockThrd_Tons')),
            'low_safety_thrd': _f(r.get('LowSafetyThrd_Tons')),
            'current_capacity': _f(r.get('CurrentCapacity_Tons')),
            'refinery_unit_load_pct': _f(r.get('RefineryUnit_Load_percent'), 100),
            'device_id_2': _safe_str(r.get('device_id_2')) or None,
            'note': _safe_str(r.get('note')) or None,
        })
    return rows


def _migrate_products(xls):
    df = xls.parse('products')
    rows = []
    for _, r in df.iterrows():
        pid = _safe_str(r.get('product_id', r.get('id')))
        if not pid:
            continue
        rows.append({
            'product_id': pid,
            'name': _safe_str(r.get('name')),
            'source_device_id': _safe_str(r.get('source_device_id')),
            'yield_rate': _f(r.get('yield_rate')) / 100.0,        # 百分比 → 小数
            'yield_rate_2': _f(r.get('yield_rate_2')) / 100.0,
            'yield_rate_3': _f(r.get('yield_rate_3')) / 100.0,
            'yield_rate_4': _f(r.get('yield_rate_4')) / 100.0,
            'is_final': bool(int(r.get('is_final', 0) or 0)),
            'note': _safe_str(r.get('note')) or None,
            'crude_type': _safe_str(r.get('crude_type'), 'BZ'),
        })
    return rows


def _migrate_connections(xls):
    df = xls.parse('connections')
    rows = []
    for _, r in df.iterrows():
        sv = r.get('special_var')
        sv = None if (sv is None or (isinstance(sv, float) and sv != sv)) else _safe_str(sv)
        rows.append({
            'connection_id': _safe_str(r.get('connection_id', r.get('id'))),
            'from_device_id': _safe_str(r.get('from_device_id')),
            'from_product_id': _safe_str(r.get('from_product_id', r.get('product_id'))),
            'to_device_id': _safe_str(r.get('to_device_id')),
            'priority': int(_f(r.get('priority'), 1)),
            'is_unique_target': bool(r.get('is_unique_target', False)),
            'special_var': sv,
        })
    return rows


def _migrate_energy(xls):
    df = xls.parse('energy')
    rows = []
    for i, r in df.iterrows():
        ec_id = _safe_str(r.get('id', r.get('energy_id')))
        if not ec_id:
            ec_id = f"ec_{i}"
        rows.append({
            'id': ec_id,
            'device_id': _safe_str(r.get('device_id')),
            'consumption_per_ton': _f(r.get('consumption_per_ton')),
            'price_per_unit': _f(r.get('price_per_unit')),
            'energy_type': _safe_str(r.get('energy_type'), 'electricity'),
        })
    return rows


def _migrate_plans_input(xls):
    df = xls.parse('production_plans_input')
    rows = []
    for _, r in df.iterrows():
        ct_id = _safe_str(r.get('crude_type_id'))
        ct_name = _safe_str(r.get('crude_type_name'))
        # crude_type_name 乱码(??25-1/???)兜底用 crude_type_id
        if not ct_name or '?' in ct_name:
            ct_name = ct_id
        rows.append({
            'planned_month': _safe_str(r.get('planned_month')),
            'crude_type_id': ct_id,
            'crude_type_name': ct_name,
            'arrival_plan': _json(r.get('arrival_plan')),
            'monthly_processing_capacity': _f(r.get('monthly_processing_capacity')),
            'current_stock': _f(r.get('current_stock')),
            'max_level_stock': _f(r.get('max_level_stock')),
            'min_level_stock': _f(r.get('min_level_stock')),
            'cost': _f(r.get('cost'), 1000.0),
        })
    return rows


def _migrate_plan_details(xls):
    df = xls.parse('production_plan_details')
    rows = []
    for _, r in df.iterrows():
        plan_id = _safe_str(r.get('plan_id'))
        day = int(_f(r.get('day_of_month'), 1))
        # plan_date 文本 → 'YYYY-MM-DD'
        pd_raw = r.get('plan_date', '')
        if hasattr(pd_raw, 'isoformat'):
            plan_date_str = pd_raw.isoformat()[:10]
        else:
            plan_date_str = _safe_str(pd_raw).split(' ')[0]
        detail_id = _safe_str(r.get('id'))
        if not detail_id:
            detail_id = f"DETAIL-{plan_id}-{day}-{_f(r.get('hours'), 24)}"
        rows.append({
            'id': detail_id,
            'plan_id': plan_id,
            'plan_date': plan_date_str,
            'day_of_month': day,
            'daily_input': _f(r.get('daily_input')),
            'blend_detail': _json(r.get('blend_detail')),
            'crude_stock_status': _json(r.get('crude_stock_status', r.get('tank_status'))),
            'device_load_rate': _f(r.get('device_load_rate')),
            'hours': _f(r.get('hours'), 24.0),
        })
    return rows


def _migrate_tasks(xls):
    df = xls.parse('scheduling_tasks')
    rows = []
    for _, r in df.iterrows():
        def _ts(v):
            s = _safe_str(v)
            return s if s else None
        rows.append({
            'plan_id': _safe_str(r.get('plan_id')),
            'planned_month': _safe_str(r.get('planned_month')),
            'status': _safe_str(r.get('status'), 'draft'),
            'locked': bool(r.get('locked', False)),
            'created_at': _ts(r.get('created_at')),
            'updated_at': _ts(r.get('updated_at')),
            'generated_at': _ts(r.get('generated_at')),
        })
    return rows


# ── 写入（TRUNCATE + INSERT，单事务）──

def _bulk_insert(db, table, cols, rows, conflict=''):
    """批量 INSERT，带 ON CONFLICT 子句。"""
    if not rows:
        return 0
    col_list = ', '.join(cols)
    ph_list = ', '.join(f':{c}' for c in cols)
    sql = f"INSERT INTO {table} ({col_list}) VALUES ({ph_list}){conflict}"
    db.execute(text(sql), rows)
    return len(rows)


# JSONB / TIMESTAMPTZ 列需要 CAST
_CASTS = {
    'arrival_plan': 'CAST(:arrival_plan AS jsonb)',
    'blend_detail': 'CAST(:blend_detail AS jsonb)',
    'crude_stock_status': 'CAST(:crude_stock_status AS jsonb)',
    'created_at': 'CAST(:created_at AS timestamptz)',
    'updated_at': 'CAST(:updated_at AS timestamptz)',
    'generated_at': 'CAST(:generated_at AS timestamptz)',
}


def _bulk_insert_cast(db, table, cols, rows):
    """带 CAST 的批量 INSERT（JSONB/TIMESTAMPTZ 列）。ON CONFLICT DO NOTHING 保幂等。"""
    if not rows:
        return 0
    col_list = ', '.join(cols)
    ph_list = ', '.join(_CASTS.get(c, f':{c}') for c in cols)
    sql = f"INSERT INTO {table} ({col_list}) VALUES ({ph_list}) ON CONFLICT DO NOTHING"
    db.execute(text(sql), rows)
    return len(rows)


def migrate():
    """执行迁移。返回 {table: row_count}。"""
    print(f"[migrate] 读取 Excel: {EXCEL_PATH}")
    xls = pd.ExcelFile(EXCEL_PATH)
    print(f"[migrate] sheets: {xls.sheet_names}")

    datasets = {
        'devices': (_migrate_devices(xls), None),
        'products': (_migrate_products(xls), None),
        'connections': (_migrate_connections(xls), None),
        'energy': (_migrate_energy(xls), None),
        'production_plans_input': (_migrate_plans_input(xls), None),
        'production_plan_details': (_migrate_plan_details(xls), None),
        'scheduling_tasks': (_migrate_tasks(xls), None),
    }

    # 先建 schema+表（幂等）
    init_db()

    counts = {}
    with engine.begin() as db:
        # 清空（幂等，可重复运行）
        for t in datasets:
            db.execute(text(f"TRUNCATE {t}"))
        print("[migrate] 已清空 7 张表")

        # devices / connections / energy：普通列
        for t in ('devices', 'connections', 'energy'):
            rows = datasets[t][0]
            cols = list(rows[0].keys()) if rows else []
            counts[t] = _bulk_insert(db, t, cols, rows, " ON CONFLICT DO NOTHING")

        # products：复合主键，普通列
        rows = datasets['products'][0]
        cols = list(rows[0].keys()) if rows else []
        counts['products'] = _bulk_insert(db, 'products', cols, rows, " ON CONFLICT DO NOTHING")

        # 含 JSONB / TIMESTAMPTZ 的表
        for t in ('production_plans_input', 'production_plan_details', 'scheduling_tasks'):
            rows = datasets[t][0]
            cols = list(rows[0].keys()) if rows else []
            counts[t] = _bulk_insert_cast(db, t, cols, rows)

    return counts


def verify(counts):
    """行数与锚点校验。"""
    print("\n[migrate] 迁移结果：")
    expected = {
        'devices': 15, 'products': 116, 'connections': 27, 'energy': 72,
        'production_plans_input': 3,
        'production_plan_details': 108, 'scheduling_tasks': 1,
    }
    ok = True
    with engine.connect() as conn:
        for t, exp in expected.items():
            actual = conn.execute(text(f"SELECT COUNT(*) FROM {t}")).scalar()
            status = 'OK' if actual == exp else 'MISMATCH'
            if actual != exp:
                ok = False
            print(f"  {t:30s} 期望 {exp:4d}  实际 {actual:4d}  [{status}]")

        # 收率小数校验（抽 products 一行）
        sample = conn.execute(text(
            "SELECT yield_rate FROM products WHERE product_id LIKE 'cjy_01%' LIMIT 1"
        )).scalar()
        print(f"  收率样例（应为小数 0~1）: {sample}  [{'OK' if 0 <= float(sample) <= 1 else 'BAD'}]")
        if not (0 <= float(sample) <= 1):
            ok = False

        # 原油品种校验
        n_crude = conn.execute(text("SELECT COUNT(DISTINCT crude_type) FROM products")).scalar()
        print(f"  products 原油品种数（期望 4）: {n_crude}  [{'OK' if n_crude == 4 else 'BAD'}]")
        if n_crude != 4:
            ok = False
    return ok


if __name__ == "__main__":
    counts = migrate()
    for t, n in counts.items():
        print(f"  插入 {t}: {n} 行")
    success = verify(counts)
    print("\n[migrate] " + ("完成，校验通过 ✅" if success else "完成，但有校验不通过 ❌ 请检查"))
