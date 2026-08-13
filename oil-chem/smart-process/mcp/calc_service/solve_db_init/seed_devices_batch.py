# -*- coding: utf-8 -*-
"""批量导入未映射的 md_device 装置到 solve_db.devices（增量 upsert）。

来源：public.md_device 中尚未在 solve_db.devices 建立映射的装置（31 个）。
设计要点：
  - type 固定 'normal'（加工装置，非罐、非起点）
  - safety_stock_thrd = max_load × 10000 ÷ 350（万吨/年 → 吨/天，350 运转日）
  - low_safety_thrd = safety_stock_thrd × 0.6（低限 = 高限 60%，与种子数据一致）
  - current_capacity = 0
  - refinery_unit_load_pct = 100
  - backend_device_id = md_device.id（本次核心：建立映射）
  - tank_category = NULL（非罐）
  - enabled = FALSE（先入库停用，接入物流拓扑后手动启用）
  - INSERT ... ON CONFLICT (device_id) DO UPDATE，仅 upsert 本批，不触碰已有数据

运行（在仓库根目录）：
    python calc_service/solve_db_init/seed_devices_batch.py

幂等：可重复执行。
"""
import sys
from pathlib import Path

_REPO_ROOT = str(Path(__file__).resolve().parent.parent.parent)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from sqlalchemy import text  # noqa: E402

from calc_service.backend.data.db import engine  # noqa: E402

# (md_device_id, name, device_id, max_load_万吨每年)
# device_id 按拼音缩写 + 序号，沿用现有惯例（cjy_01/cyjq_01/lyjq_01/dcc_01/lt_01）
UNMAPPED_DEVICES = [
    (4,  'Ⅲ常减压',       'cdy_02',    600),
    (7,  '2#蜡加',        'lyjq_02',   220),
    (9,  '2#DCC',         'dcc_02',    320),
    (10, '1#石脑油加氢',   'snyjy_01',   60),
    (11, '2#石脑油加氢',   'snyjy_02',  100),
    (12, '裂解柴油加氢',   'ljcyjy_01',  70),
    (13, '航煤加氢',       'hmjy_01',    60),
    (14, '1#预加氢',      'yjq_01',    130),
    (15, '2#预加氢',      'yjq_02',    100),
    (16, '1#重整',        'cz_01',     150),
    (17, '2#重整',        'cz_02',     150),
    (18, '1#抽提',        'ct_01',      55),
    (19, '2#抽提',        'ct_02',      40),
    (20, '3#抽提',        'ct_03',      80),
    (21, '4#抽提',        'ct_04',      45),
    (22, '芳构化',         'fgh_01',     50),
    (23, '歧化',           'qh_01',     340),
    (24, '芳烃',           'ft_01',     160),
    (25, '乙苯',           'yb_01',      30),
    (26, '苯乙烯',         'byx_01',     28),
    (27, '精制',           'jz_01',     128),
    (28, '气分',           'qf_01',     100),
    (29, 'MTBE',          'mtbe_01',    15),
    (30, '1-丁烯',         'dxe_01',      6),
    (31, '1#聚丙烯',       'jbx_01',     30),
    (32, '2#聚丙烯',       'jbx_02',     45),
    (33, '轻烃回收',       'qths_01',   160),
    (34, '烯烃分离',       'xtfl_01',    25),
    (35, '1#PSA',         'psa_01',      8),
    (36, '2#PSA',         'psa_02',     10),
    (37, '制氢',           'zq_01',       6),
]

# 万吨/年 → 吨/天（350 运转日）；低限 = 高限 × 0.6
DAYS_PER_YEAR = 350
LOW_RATIO = 0.6

_UPSERT_SQL = text(
    "INSERT INTO devices (device_id, name, type, safety_stock_thrd, "
    "low_safety_thrd, current_capacity, refinery_unit_load_pct, backend_device_id, "
    "tank_category, enabled) "
    "VALUES (:device_id, :name, 'normal', :safety_stock_thrd, :low_safety_thrd, "
    "0, 100, :backend_device_id, NULL, FALSE) "
    "ON CONFLICT (device_id) DO UPDATE SET "
    "name=EXCLUDED.name, type=EXCLUDED.type, "
    "safety_stock_thrd=EXCLUDED.safety_stock_thrd, "
    "low_safety_thrd=EXCLUDED.low_safety_thrd, "
    "current_capacity=EXCLUDED.current_capacity, "
    "refinery_unit_load_pct=EXCLUDED.refinery_unit_load_pct, "
    "backend_device_id=EXCLUDED.backend_device_id, "
    "tank_category=EXCLUDED.tank_category, "
    "enabled=EXCLUDED.enabled"
)


def main():
    rows = []
    for md_id, name, device_id, max_load in UNMAPPED_DEVICES:
        safety = round(max_load * 10000 / DAYS_PER_YEAR, 3)
        low = round(safety * LOW_RATIO, 3)
        rows.append({
            'device_id': device_id,
            'name': name,
            'safety_stock_thrd': safety,
            'low_safety_thrd': low,
            'backend_device_id': md_id,
        })

    print("=" * 60)
    print(f"批量导入未映射装置：{len(rows)} 个（enabled=FALSE）")
    print("=" * 60)
    print(f"  阈值换算：safety = max_load×10000÷{DAYS_PER_YEAR}，low = safety×{LOW_RATIO}")
    print()

    with engine.begin() as conn:
        for r in rows:
            conn.execute(_UPSERT_SQL, r)

        # 校验
        ids = [r['device_id'] for r in rows]
        result = conn.execute(
            text("SELECT device_id, name, type, backend_device_id, "
                 "safety_stock_thrd, low_safety_thrd, enabled "
                 "FROM devices WHERE device_id = ANY(:ids) ORDER BY backend_device_id"),
            {'ids': ids}
        ).fetchall()

    print(f"已 upsert {len(result)} 行。抽样校验（前 5 / 后 3）：")
    for r in result[:5] + result[-3:]:
        print(f"  bid={r[3]:3d}  {r[0]:14s}  {r[1]:14s}  type={r[2]:8s}  "
              f"safety={r[4]:>10.3f}  low={r[5]:>10.3f}  enabled={r[6]}")

    missing = set(ids) - {r[0] for r in result}
    if missing:
        print(f"\n⚠️ 缺失 {len(missing)} 个：{sorted(missing)}")
        sys.exit(1)

    # 统计 md_device 映射覆盖率
    with engine.connect() as conn:
        total_md = conn.execute(text("SELECT count(*) FROM public.md_device")).scalar()
        mapped = conn.execute(text(
            "SELECT count(*) FROM devices WHERE backend_device_id IS NOT NULL")).scalar()
    print(f"\nmd_device 映射覆盖：{mapped}/{total_md}（{mapped*100//total_md}%）")
    print("\n完成 ✅  所有装置 enabled=FALSE，需在配置页接入物流拓扑后手动启用。")


if __name__ == '__main__':
    main()
