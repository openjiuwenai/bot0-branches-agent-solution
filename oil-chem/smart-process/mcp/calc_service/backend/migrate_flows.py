# -*- coding: utf-8 -*-
"""从 connections 表迁移数据到 material_flows 表（归一化模型）。

归一化规则：
  - 旧 intermediate 链路拆为 source_to_tank + tank_to_target 两行
  - source_to_tank: (source_device_id, source_product_id, tank_id, special_var)
  - tank_to_target: (tank_id, target_device_id, split_ratio, material_role) — 按 (tank, target) 去重
  - direct/final 行保持不变
"""
from calc_service.backend.data.db import SessionLocal
from sqlalchemy import text


def migrate():
    db = SessionLocal()
    count = db.execute(text('SELECT count(*) FROM material_flows')).scalar()
    print(f'material_flows 现有数据: {count} 条')

    if count > 0:
        print('material_flows 已有数据，跳过迁移')
        db.close()
        return

    rows = db.execute(text(
        'SELECT connection_id, from_device_id, from_product_id, to_device_id, '
        'priority, is_unique_target, special_var FROM connections ORDER BY connection_id'
    )).mappings().all()
    print(f'connections 有 {len(rows)} 条')

    dev_types = {}
    for r in db.execute(text('SELECT device_id, type FROM devices')).mappings():
        dev_types[r['device_id']] = r['type']

    # 罐→装置 的连接
    tank_to_unit = {}
    for r in rows:
        from_type = dev_types.get(r['from_device_id'], '')
        to_type = dev_types.get(r['to_device_id'], '')
        if from_type == 'tank' and to_type in ('normal', 'start'):
            tank_to_unit.setdefault(r['from_device_id'], []).append(r)

    # 归一化：先收集 source_to_tank 和 tank_to_target 边，去重后插入
    src_tank_seen = set()   # (source_device_id, source_product_id, tank_id)
    tank_target_seen = set() # (tank_id, target_device_id)
    inserted = 0

    def insert(flow_id, source_type, source_device_id, source_product_id,
               tank_id, target_device_id, flow_type, material_role,
               special_var, priority, is_unique_target, split_ratio=1.0):
        nonlocal inserted
        db.execute(text(
            'INSERT INTO material_flows (flow_id, source_type, source_device_id, '
            'source_product_id, tank_id, target_device_id, flow_type, material_role, '
            'special_var, priority, is_unique_target, split_ratio) VALUES ('
            ':flow_id, :source_type, :source_device_id, :source_product_id, '
            ':tank_id, :target_device_id, :flow_type, :material_role, '
            ':special_var, :priority, :is_unique_target, :split_ratio)'
        ), {
            'flow_id': flow_id,
            'source_type': source_type,
            'source_device_id': source_device_id,
            'source_product_id': source_product_id,
            'tank_id': tank_id,
            'target_device_id': target_device_id,
            'flow_type': flow_type,
            'material_role': material_role,
            'special_var': special_var,
            'priority': priority or 1,
            'is_unique_target': is_unique_target or False,
            'split_ratio': split_ratio,
        })
        inserted += 1

    for r in rows:
        from_type = dev_types.get(r['from_device_id'], '')
        to_type = dev_types.get(r['to_device_id'], '')

        if from_type == 'tank' and to_type in ('normal', 'start'):
            continue  # 罐→装置: 在下面的 source_to_tank 处理中生成

        if from_type in ('normal', 'start') and to_type == 'tank':
            tank_id = r['to_device_id']
            downstream = tank_to_unit.get(tank_id, [])

            if downstream:
                # source_to_tank 边（去重）
                src_key = (r['from_device_id'], r['from_product_id'], tank_id)
                if src_key not in src_tank_seen:
                    src_tank_seen.add(src_key)
                    st_id = f"{r['from_device_id']}_{r['from_product_id']}_{tank_id}"
                    insert(st_id, 'device', r['from_device_id'], r['from_product_id'],
                           tank_id, None, 'source_to_tank', None,
                           r['special_var'], r['priority'], r['is_unique_target'])

                # tank_to_target 边（按 tank+target 去重）
                for ds in downstream:
                    tgt_key = (tank_id, ds['to_device_id'])
                    if tgt_key not in tank_target_seen:
                        tank_target_seen.add(tgt_key)
                        product = db.execute(text(
                            'SELECT material_type FROM products WHERE product_id = :pid LIMIT 1'
                        ), {'pid': ds['from_product_id']}).scalar()
                        role = product or None
                        tt_id = f"{tank_id}_{ds['to_device_id']}"
                        insert(tt_id, 'tank', None, None,
                               tank_id, ds['to_device_id'], 'tank_to_target', role,
                               None, ds['priority'] or 1, True)
            else:
                # final: 装置→成品罐
                insert(f'mf_{inserted+1:03d}', 'device', r['from_device_id'], r['from_product_id'],
                       tank_id, None, 'final', None,
                       r['special_var'], r['priority'], r['is_unique_target'])

        elif from_type in ('normal', 'start') and to_type in ('normal', 'start'):
            # direct: 装置→装置
            product = db.execute(text(
                'SELECT material_type FROM products WHERE product_id = :pid LIMIT 1'
            ), {'pid': r['from_product_id']}).scalar()
            role = product or None
            insert(f'mf_{inserted+1:03d}', 'device', r['from_device_id'], r['from_product_id'],
                   None, r['to_device_id'], 'direct', role,
                   r['special_var'], r['priority'], r['is_unique_target'])

    db.commit()
    print(f'已迁移 {inserted} 条 material_flows（归一化模型）')

    # 验证
    rows = db.execute(text(
        'SELECT flow_id, source_type, source_device_id, tank_id, target_device_id, '
        'flow_type, material_role, special_var, split_ratio FROM material_flows ORDER BY flow_id'
    )).mappings().all()
    for r in rows:
        print(f"{r['flow_id']} | {r['source_type']:8s} | {str(r['source_device_id'] or ''):12s} | "
              f"{str(r['tank_id'] or ''):16s} | {str(r['target_device_id'] or ''):10s} | "
              f"{r['flow_type']:16s} | {str(r['material_role'] or ''):10s} | {str(r['special_var'] or '')} | "
              f"split={r['split_ratio']}")
    db.close()


if __name__ == '__main__':
    migrate()
