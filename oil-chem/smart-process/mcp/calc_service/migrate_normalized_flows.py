"""归一化迁移：将 intermediate 行拆为 source_to_tank + tank_to_target 两条独立边。

迁移逻辑：
  intermediate 行 (src, prod, tank, tgt, var, split, role)
    → source_to_tank 行 (src, prod, tank, var)      — 按 (src, prod, tank) 去重
    → tank_to_target 行 (tank, tgt, split, role)     — 按 (tank, tgt) 去重

direct / final / input 行保持不变。
"""
import sys
from backend.data.refinery_repo import RefineryRepository

repo = RefineryRepository()
flows = repo.load_material_flows()

new_rows = []
src_tank_seen = set()   # (source_device_id, source_product_id, tank_id)
tank_target_seen = set() # (tank_id, target_device_id)

for f in flows.values():
    if f.flow_type == 'intermediate':
        # 第一段：source_to_tank
        src_key = (f.source_device_id, f.source_product_id, f.tank_id)
        if src_key not in src_tank_seen:
            src_tank_seen.add(src_key)
            st_id = f"{f.source_device_id}_{f.source_product_id}_{f.tank_id}"
            new_rows.append({
                'flow_id': st_id,
                'source_type': 'device',
                'source_device_id': f.source_device_id,
                'source_product_id': f.source_product_id,
                'source_name': None,
                'tank_id': f.tank_id,
                'target_device_id': None,
                'flow_type': 'source_to_tank',
                'material_role': None,
                'special_var': f.special_var,
                'priority': f.priority,
                'is_unique_target': f.is_unique_target,
                'split_ratio': 1.0,
            })

        # 第二段：tank_to_target
        tgt_key = (f.tank_id, f.target_device_id)
        if tgt_key not in tank_target_seen:
            tank_target_seen.add(tgt_key)
            tt_id = f"{f.tank_id}_{f.target_device_id}"
            new_rows.append({
                'flow_id': tt_id,
                'source_type': 'tank',
                'source_device_id': None,
                'source_product_id': None,
                'source_name': None,
                'tank_id': f.tank_id,
                'target_device_id': f.target_device_id,
                'flow_type': 'tank_to_target',
                'material_role': f.material_role,
                'special_var': None,
                'priority': f.priority,
                'is_unique_target': True,
                'split_ratio': f.split_ratio,
            })
    else:
        # direct / final / input 行保持不变
        new_rows.append({
            'flow_id': f.id,
            'source_type': f.source_type,
            'source_device_id': f.source_device_id,
            'source_product_id': f.source_product_id,
            'source_name': f.source_name,
            'tank_id': f.tank_id,
            'target_device_id': f.target_device_id,
            'flow_type': f.flow_type,
            'material_role': f.material_role,
            'special_var': f.special_var,
            'priority': f.priority,
            'is_unique_target': f.is_unique_target,
            'split_ratio': f.split_ratio,
        })

print(f"迁移前: {len(flows)} 行")
print(f"迁移后: {len(new_rows)} 行")
for r in new_rows:
    ft = r['flow_type']
    if ft == 'source_to_tank':
        print(f"  {r['flow_id']}: {ft} | {r['source_device_id']}.{r['source_product_id']} → {r['tank_id']} | var={r['special_var'] or '-'}")
    elif ft == 'tank_to_target':
        print(f"  {r['flow_id']}: {ft} | {r['tank_id']} → {r['target_device_id']} | split={r['split_ratio']} | role={r['material_role'] or '-'}")
    else:
        print(f"  {r['flow_id']}: {ft} | {r['source_device_id'] or r['source_name'] or '-'} → {r['target_device_id'] or r['tank_id'] or '-'}")

# 执行迁移
repo.save_material_flows(new_rows)
print(f"\n迁移完成，已写入 {len(new_rows)} 行")
