# -*- coding: utf-8 -*-
"""流程图数据构建（从 solve_service.py 下移）。"""

from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL
from ..models.refinery import ProcessingUnit, Tank


def build_flow_diagram(scenario, device_util: dict, device_inputs: dict,
                       connection_flows: dict, monthly_load: dict = None) -> dict:
    """构建全装置流程图数据（数字孪生视图）。

    返回 {nodes:[...], edges:[...]}：
      nodes: 全部装置（含 tank），每个含 device_id/name/type/input（月度总进料）。
             加工装置额外含 monthly_util/is_overloaded（取自 monthly_load，含停工折算口径）。
      edges: 全部连接，每条含 from/to/product/special_var/flow/is_switched_off ——
             前端据此画带流向的连线、标注实际流量、虚线标注切走的支路
    无场景时返回空结构（降级，不阻断展示）。

    Scenario 依赖:
        - devices: 装置字典（节点构建，ProcessingUnit/Tank 类型判断）
        - material_flows: 物流拓扑（边构建，special_var 标注）
        - products: 产品字典（边上的产品名称）
    """
    if not scenario:
        return {'nodes': [], 'edges': []}
    # 月度负荷数据（含停工折算的正确口径）
    ml_devices = {}
    if monthly_load and monthly_load.get('devices'):
        for d in monthly_load['devices']:
            ml_devices[d['device_id']] = d
    nodes = []
    for did, dev in scenario.devices.items():
        u = device_util.get(did, {})
        inp = float(u.get('input', 0) or 0)
        if inp != inp:  # NaN
            inp = 0
        ml = ml_devices.get(did, {})
        node = {
            'device_id': did,
            'name': dev.name,
            'type': dev.type if isinstance(dev, ProcessingUnit) else 'tank',
            'input': round(inp, 1),
        }
        # 加工装置追加月度负荷字段（用于超容高亮+利用率展示）
        if isinstance(dev, ProcessingUnit) and dev.type in ('start', 'normal') and ml:
            node['monthly_util'] = ml.get('monthly_util', 0)
            node['monthly_capacity'] = ml.get('monthly_capacity', 0)
            node['is_overloaded'] = ml.get('is_overloaded', False)
        nodes.append(node)
    edges = []
    for fid, flow in scenario.material_flows.items():
        f = float(connection_flows.get(fid, 0) or 0)
        prod = scenario.products.get(flow.from_product_id)
        sv = flow.special_var
        edges.append({
            'conn_id': fid,
            'from_device_id': flow.from_device_id,
            'to_device_id': flow.to_device_id,
            'from_device_name': (scenario.devices.get(flow.from_device_id).name
                                 if scenario.devices.get(flow.from_device_id) else flow.from_device_id),
            'to_device_name': (scenario.devices.get(flow.to_device_id).name
                               if scenario.devices.get(flow.to_device_id) else flow.to_device_id),
            'product_name': prod.name if prod else flow.from_product_id,
            'special_var': sv,
            'flow': round(f, 1),
            # special_var 且流量≈0 → 阀门切走（X/Y 改道）
            'is_switched_off': (sv is not None and abs(f) < 1e-6),
        })
    return {'nodes': nodes, 'edges': edges}


def build_device_input_sources(scenario, device_id: str,
                               connection_flows: dict, special_vars: dict,
                               mode: str) -> list:
    """拆解某装置的进料来源（连接级流量），用于"为何超"计算链展示。

    对该装置的每条上游连接给出：来源装置/物料/收率/special_var/实际流量；
    若上游是 tank（如 HC罐），再下钻一层列出该罐的进料（减一线 Y / 减二三四线），
    让用户一眼看出：MODE_JIAN1_TO_DIESEL 时减一线去柴油(jian1_to_wax=0)，蜡加仍被减二三四线喂爆。
    无场景或无连接时返回空列表（降级，不阻断展示）。

    Scenario 依赖:
        - get_upstream_flows(): 上游物流查询
        - devices: 装置字典
        - products: 产品字典
    """
    if not scenario:
        return []
    upstream = scenario.get_upstream_flows(device_id)
    sources = []
    for flow in upstream:
        f = float(connection_flows.get(flow.id, 0) or 0)
        from_dev = scenario.devices.get(flow.from_device_id)
        prod = scenario.products.get(flow.from_product_id)
        yr = (prod.yield_rate if prod else 0) or 0
        # special_var 语义：jian1_to_diesel=减一线→柴加方向，jian1_to_wax=减一线→蜡加方向
        sv = flow.special_var
        sv_note = ''
        if sv == 'jian1_to_diesel':
            sv_note = '减一线→柴加'
        elif sv == 'jian1_to_wax':
            sv_note = '减一线→蜡加'
        src = {
            'conn_id': flow.id,
            'from_device_id': flow.from_device_id,
            'from_device_name': from_dev.name if from_dev else flow.from_device_id,
            'from_product_name': prod.name if prod else flow.from_product_id,
            'yield_rate': round(float(yr) * 100, 2),
            'special_var': sv,
            'special_var_note': sv_note,
            'flow': round(f, 1),
            'is_switched_off': (sv is not None and abs(f) < 1e-6),
            'sub_sources': [],
        }
        # tank 中转（如 HC罐）下钻一层：列出该罐的进料，暴露分流切换效果
        if from_dev and isinstance(from_dev, Tank):
            for sub in scenario.get_upstream_flows(flow.from_device_id):
                sub_flow = float(connection_flows.get(sub.id, 0) or 0)
                sub_from = scenario.devices.get(sub.from_device_id)
                sub_prod = scenario.products.get(sub.from_product_id)
                sub_yr = (sub_prod.yield_rate if sub_prod else 0) or 0
                sub_sv = sub.special_var
                sub_sv_note = ''
                if sub_sv == 'jian1_to_diesel':
                    sub_sv_note = '减一线→柴加'
                elif sub_sv == 'jian1_to_wax':
                    sub_sv_note = '减一线→蜡加'
                src['sub_sources'].append({
                    'conn_id': sub.id,
                    'from_device_name': sub_from.name if sub_from else sub.from_device_id,
                    'from_product_name': sub_prod.name if sub_prod else sub.from_product_id,
                    'yield_rate': round(float(sub_yr) * 100, 2),
                    'special_var': sub_sv,
                    'special_var_note': sub_sv_note,
                    'flow': round(sub_flow, 1),
                    'is_switched_off': (sub_sv is not None and abs(sub_flow) < 1e-6),
                })
        sources.append(src)
    # 流量大的排前
    sources.sort(key=lambda x: -x['flow'])
    return sources
