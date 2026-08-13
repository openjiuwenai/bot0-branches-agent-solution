# -*- coding: utf-8 -*-
"""批量导入储罐主数据到 solve_db.devices（增量 upsert，不删除已有装置）。

来源：罐区库存报表（行标签=罐名，安高库存=高位阈值，低位库存=低位阈值）。
设计要点：
  - type 固定 'tank'；tank_category 按物料语义分 crude/product/intermediate
  - current_capacity 默认 0（报表未给当前存量）
  - refinery_unit_load_pct 默认 100
  - backend_device_id 默认 NULL（罐不参与加工成本计算）
  - enabled 默认 FALSE（先入库停用，待人工接入物流拓扑后再启用）
  - device_id 用描述性缩写 + _tank_01，与现有 12 个种子罐不冲突
  - 使用 INSERT ... ON CONFLICT (device_id) DO UPDATE，仅 upsert 本批 54 行，
    不触碰其他装置/罐（区别于 save_devices 的全量替换语义）

运行（在仓库根目录）：
    python calc_service/solve_db_init/seed_tanks_batch.py

幂等：可重复执行。
"""
import sys
from pathlib import Path

_REPO_ROOT = str(Path(__file__).resolve().parent.parent.parent)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from sqlalchemy import text  # noqa: E402

from calc_service.backend.data.db import engine  # noqa: E402

# (device_id, name, tank_category, safety_stock_thrd, low_safety_thrd)
# tank_category: crude=原油罐 / product=成品罐 / intermediate=中间罐
TANKS = [
    # ── crude 原油罐（4）──
    ('bz_tank_01',          'BZ',                  'crude',       171751.803, 20453.45),
    ('esposepia_tank_01',   'ESPO/SEPIA',          'crude',        83692.587, 9127.631),
    ('qhdnp_tank_01',       'QHD/NP',              'crude',       266038.246, 29371.66),
    ('sepiatupi_tank_01',   'SEPIA/TUPI',          'crude',       127743.676, 14918.041),

    # ── product 成品罐 ──
    ('dxb1_tank_01',        '1-丁烯',              'product',      4232.147, 111.22),
    ('rly250a_tank_01',     '250#燃料油',          'product',    100750.324, 4072.003),
    ('mtbe_tank_01',        'MTBE',                'product',     11302.381, 1698.569),
    ('ben_tank_01',         '苯',                  'product',     12767.549, 1961.155),
    ('byx_tank_01',         '苯乙烯',              'product',     19801.475, 1080.242),
    ('byxjy_tank_01',       '苯乙烯焦油',          'product',      2941.903, 175.202),
    ('bx_tank_01',          '丙烯',                'product',      7273.564, 597.61),
    ('cycy_tank_01',        '车用柴油',            'product',     82239.592, 9128.285),
    ('cyqy_tank_01',        '车用汽油',            'product',     38952.673, 4445.031),
    ('pxcp_tank_01',        '对二甲苯',            'product',     61601.764, 9557.733),
    ('hangmei_tank_01',     '航煤',                'product',     52054.885, 5303.128),
    ('jb_tank_01',          '甲苯',                'product',      8383.614, 1287.353),
    ('jc_tank_01',          '甲醇',                'product',      7657.183, 1177.815),
    ('rlydmb2_tank_01',     '燃料油DMB',           'product',     14576.051, 590.992),
    ('rlyfd1_tank_01',      '燃料油F-D1',          'product',     14181.599, 546.703),
    ('wtc5_tank_01',        '戊烷(C5)',            'product',      3053.399, 135.189),
    ('wtcw_tank_01',        '戊烷（碳五）',        'product',      1572.165, 69.479),
    ('wtcwxt_tank_01',      '戊烷（碳五烯烃）',    'product',      1525.354, 66.119),
    ('wtfpj_tank_01',       '戊烷发泡剂',          'product',      1426.092, 64.265),
    ('yhsyqgy_tank_01',     '液化石油气（工业用）', 'product',      7101.488, 317.604),
    ('yhsyqhc4_tank_01',    '液化石油气（混合C4）', 'product',      1425.004, 81.404),

    # ── intermediate 中间罐 ──
    ('atapu_tank_01',       'ATAPU',               'intermediate', 173254.559, 17638.639),
    ('c10cfx_tank_01',      'C10粗芳烃',           'intermediate',  5729.215, 844.042),
    ('c6c8fx_tank_01',      'C6-C8芳烃',           'intermediate',  8641.354, 1248.656),
    ('dccyl_tank_01',       'DCC原料',             'intermediate', 24065.581, 1128.656),
    ('hcyl_tank_01',        'HC原料',              'intermediate', 70595.452, 8009.574),
    ('ldlh_tank_01',        'LD/LH',               'intermediate', 43477.147, 5303.445),
    ('bhlpg_tank_01',       '饱和LPG',             'intermediate',  9569.482, 757.113),
    ('cyzy_tank_01',        '常压渣油',            'intermediate',  8735.933, 428.741),
    ('gyrlyjy_tank_01',     '工业燃料油加氢原料',  'intermediate', 81294.92,  10493.984),
    ('gyc9fx_tank_01',      '工业用碳九芳烃',      'intermediate', 17821.959, 2630.457),
    ('jqwyou_tank_01',      '加氢尾油',            'intermediate', 16837.406, 865.93),
    ('jx_tank_01',          '检修',                'intermediate',  8810.174, 1341.251),
    ('jzsny_tank_01',       '精制石脑油',          'intermediate',  3877.375, 564.046),
    ('ljsny_tank_01',       '裂解石脑油',          'intermediate',  4673.417, 776.406),
    ('qfyhq_tank_01',       '气分液化气',          'intermediate',  1179.49,  103.762),
    ('qwy_tank_01',         '轻污油',              'intermediate',  6770.278, 423.215),
    ('rdyl_tank_01',        '溶脱原料',            'intermediate', 27636.158, 867.27),
    ('snyhgl_tank_01',      '石脑油（互供料）',    'intermediate', 14062.389, 1991.14),
    ('snyqyl_tank_01',      '石脑油（汽油料）',    'intermediate',  6872.734, 1048.136),
    ('snyyxl_tank_01',      '石脑油（乙烯料）',    'intermediate', 19927.103, 3043.813),
    ('hxxpx_tank_01',       '石油混二甲苯（PX装置用）', 'intermediate', 17074.544, 2617.088),
    ('thy_tank_01',         '烃化液',              'intermediate',  4208.648, 648.658),
    ('tqy_tank_01',         '脱氢液',              'intermediate',  6605.0,   800.018),
    ('wy_tank_01',          '污油',                'intermediate', 33349.411, 4477.244),
    ('yb_tank_01',          '乙苯',                'intermediate',  9720.409, 1336.237),
    ('yjqyl_tank_01',       '预加氢进料',          'intermediate', 29017.942, 4179.816),
    ('zwy_tank_01',         '重污油',              'intermediate',  9140.833, 626.298),
    ('zzscy_tank_01',       '重整生成油',          'intermediate', 16877.673, 2261.47),
    ('zyrly_tank_01',       '自用燃料油',          'intermediate',   865.928, 52.882),
]

_UPSERT_SQL = text(
    "INSERT INTO devices (device_id, name, type, safety_stock_thrd, "
    "low_safety_thrd, current_capacity, refinery_unit_load_pct, backend_device_id, "
    "tank_category, enabled) "
    "VALUES (:device_id, :name, 'tank', :safety_stock_thrd, :low_safety_thrd, "
    "0, 100, NULL, :tank_category, FALSE) "
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
    print("=" * 60)
    print(f"批量导入储罐：{len(TANKS)} 个（enabled=FALSE）")
    print("=" * 60)
    by_cat = {}
    for _, _, cat, _, _ in TANKS:
        by_cat[cat] = by_cat.get(cat, 0) + 1
    for cat in ('crude', 'product', 'intermediate'):
        print(f"  {cat:14s} {by_cat.get(cat, 0):3d} 个")

    with engine.begin() as conn:
        for device_id, name, cat, hi, lo in TANKS:
            conn.execute(_UPSERT_SQL, {
                'device_id': device_id,
                'name': name,
                'safety_stock_thrd': hi,
                'low_safety_thrd': lo,
                'tank_category': cat,
            })
        # 校验：本批 device_id 是否都存在且为 tank
        ids = [t[0] for t in TANKS]
        rows = conn.execute(
            text("SELECT device_id, name, tank_category, enabled, type "
                 "FROM devices WHERE device_id = ANY(:ids) ORDER BY device_id"),
            {'ids': ids}
        ).fetchall()
    print(f"\n已 upsert {len(rows)} 行。抽样校验（前 5 / 后 3）：")
    for r in rows[:5] + rows[-3:]:
        print(f"  {r[0]:22s} {r[1]:24s} cat={r[2]:12s} enabled={r[3]} type={r[4]}")
    missing = set(ids) - {r[0] for r in rows}
    if missing:
        print(f"\n⚠️ 缺失 {len(missing)} 个：{sorted(missing)}")
        sys.exit(1)
    print("\n完成 ✅  所有罐 enabled=FALSE，需在配置页接入物流拓扑后手动启用。")


if __name__ == '__main__':
    main()
