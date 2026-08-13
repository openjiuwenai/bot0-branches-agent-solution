# -*- coding: utf-8 -*-
"""修正 material_flows 的 material_role 字段。"""
from calc_service.backend.data.db import SessionLocal
from sqlalchemy import text

db = SessionLocal()

# intermediate: tank->device 一律设为 main_feed
db.execute(text("UPDATE material_flows SET material_role='main_feed' WHERE flow_type='intermediate'"))

# final: material_role = NULL
db.execute(text("UPDATE material_flows SET material_role=NULL WHERE flow_type='final'"))

# direct: cyjq_01->lyjq_01 的回流物料，根据产品名判断
direct_rows = db.execute(text(
    "SELECT flow_id, source_product_id FROM material_flows WHERE flow_type='direct'"
)).mappings().all()

for r in direct_rows:
    pid = r['source_product_id']
    prod = db.execute(text(
        'SELECT name FROM products WHERE product_id=:pid LIMIT 1'
    ), {'pid': pid}).scalar()
    # 回流低分气 = auxiliary, 其余 = main_feed
    if prod and '低分' in prod:
        role = 'auxiliary'
    else:
        role = 'main_feed'
    db.execute(text(
        'UPDATE material_flows SET material_role=:role WHERE flow_id=:fid'
    ), {'role': role, 'fid': r['flow_id']})
    print(f"{r['flow_id']}: {pid} -> {prod} -> {role}")

db.commit()
print('material_role 已修正')

# 验证
rows = db.execute(text(
    'SELECT flow_id, source_device_id, tank_id, target_device_id, '
    'flow_type, material_role, special_var FROM material_flows ORDER BY flow_id'
)).mappings().all()
for r in rows:
    print(f"{r['flow_id']} | {str(r['source_device_id'] or ''):12s} | "
          f"{str(r['tank_id'] or ''):16s} | {str(r['target_device_id'] or ''):10s} | "
          f"{r['flow_type']:12s} | {str(r['material_role'] or ''):10s} | "
          f"{str(r['special_var'] or '')}")
db.close()
