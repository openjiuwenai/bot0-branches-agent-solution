# -*- coding: utf-8 -*-
"""
装置进料量计算器（数据驱动版）。

从 scenario.material_flows 动态推导物料流拓扑，不再硬编码装置/产品 ID。
支持三种边类型：source_to_tank / tank_to_target / direct。

目的：给慧炼侧"收率预测"提供"每天各加工装置真实进料量"，
口径跟求解器 calculate_direct 算效益时**完全一致**。

物料流模型（由 material_flows 表动态推导）：
  CDU (input=T 原油)
    ├── source_to_tank: 侧线 → 中间罐（yield_rate，可选 special_var X/Y 分流）
    ├── tank_to_target: 中间罐 → 加工装置（split_ratio 分配）
    └── direct: 装置 → 装置直供（回流，yield_rate / yield_rate_2 按工况切换）

工况（对齐 yield_resolver）：
  MODE_JIAN1_TO_DIESEL(jian1_to_diesel=1, 减一全进柴加侧): 柴加侧装置收到 jian1 → 用 yield_rate_2
  MODE_JIAN1_TO_WAX(jian1_to_wax=1, 减一全进蜡加侧): 蜡加侧装置收到 jian1 → 用 yield_rate
"""
from typing import Dict, Optional, List

from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL
from ..models.refinery import RefineryScenario, Product


# ── 拓扑构建 ──────────────────────────────────────────────────────────────

def build_flow_topology(scenario: RefineryScenario) -> dict:
    """从 scenario.material_flows 构建物料流拓扑。

    Returns:
      {
        'cdu_id': str,
        'cdu_tank_edges': [{product_id, tank_id, special_var}],
        'tank_device_edges': [{tank_id, device_id, split_ratio}],
        'direct_edges': [{source_device, product_id, target_device}],
        'all_keys': set[str],  # 所有涉及的装置/罐 ID
      }
    """
    cdu_id = scenario.start_device_id

    # CDU → tank 边（source_to_tank）
    cdu_tank_edges: List[dict] = []
    for f in scenario.material_flows.values():
        if f.flow_type == 'source_to_tank' and f.source_device_id == cdu_id:
            cdu_tank_edges.append({
                'product_id': f.source_product_id,
                'tank_id': f.tank_id,
                'special_var': f.special_var,
            })

    # tank → device 边（tank_to_target）
    tank_device_edges: List[dict] = []
    for f in scenario.material_flows.values():
        if f.flow_type == 'tank_to_target':
            tank_device_edges.append({
                'tank_id': f.tank_id,
                'device_id': f.target_device_id,
                'split_ratio': f.split_ratio or 1.0,
            })

    # device → device 直供边（direct，含回流）
    direct_edges: List[dict] = []
    for f in scenario.material_flows.values():
        if f.flow_type == 'direct':
            direct_edges.append({
                'source_device': f.source_device_id,
                'product_id': f.source_product_id,
                'target_device': f.target_device_id,
            })

    # 所有涉及的装置和罐 ID
    all_keys: set = set()
    for e in tank_device_edges:
        all_keys.add(e['device_id'])
        all_keys.add(e['tank_id'])
    for e in direct_edges:
        all_keys.add(e['source_device'])
        all_keys.add(e['target_device'])

    return {
        'cdu_id': cdu_id,
        'cdu_tank_edges': cdu_tank_edges,
        'tank_device_edges': tank_device_edges,
        'direct_edges': direct_edges,
        'all_keys': all_keys,
    }


# ── Yield 表构建 ──────────────────────────────────────────────────────────

def load_yield_tables(products_by_crude: Dict[str, Dict[str, Product]],
                      topology: dict) -> dict:
    """从产品数据和拓扑构建 yield 表，按 crude_type 索引。

    Args:
      products_by_crude: RefineryRepository.load_products_grouped() 的返回值
      topology: build_flow_topology() 的返回值

    返回结构：
      {
        crude_type_id: {
          'tank_yields': { tank_id: {'fixed': float, 'jian1_to_diesel': float, 'jian1_to_wax': float} },
          'backflow_yields': { source_device: {'with_jian1': float, 'without_jian1': float} },
        }
      }
    """
    out: dict = {}
    for crude_type, products in products_by_crude.items():
        # 按 tank 分组 CDU 产品 yield，区分 special_var
        tank_yields: dict = {}
        for edge in topology['cdu_tank_edges']:
            tank = edge['tank_id']
            if tank not in tank_yields:
                tank_yields[tank] = {'fixed': 0.0, 'jian1_to_diesel': 0.0, 'jian1_to_wax': 0.0}
            prod = products.get(edge['product_id'])
            yval = prod.yield_rate if prod else 0.0
            sv = edge['special_var']
            if sv == 'jian1_to_diesel':
                tank_yields[tank]['jian1_to_diesel'] += yval
            elif sv == 'jian1_to_wax':
                tank_yields[tank]['jian1_to_wax'] += yval
            else:
                tank_yields[tank]['fixed'] += yval

        # 回流 yield（按 source_device 分组，区分 with/without jian1）
        backflow_yields: dict = {}
        for edge in topology['direct_edges']:
            src = edge['source_device']
            if src not in backflow_yields:
                backflow_yields[src] = {'with_jian1': 0.0, 'without_jian1': 0.0}
            prod = products.get(edge['product_id'])
            if prod:
                backflow_yields[src]['without_jian1'] += prod.yield_rate
                backflow_yields[src]['with_jian1'] += prod.yield_rate_2

        out[str(crude_type)] = {
            'tank_yields': tank_yields,
            'backflow_yields': backflow_yields,
        }
    return out


# ── 辅助函数 ──────────────────────────────────────────────────────────────

def _pick_proxy_crude(blend_detail: dict, known_crudes: set) -> Optional[str]:
    """未覆盖油的代理选择规则：blend_detail 里量最大的「已覆盖油」；没有则返回 None。"""
    covered = {k: float(v) for k, v in (blend_detail or {}).items()
               if k in known_crudes and isinstance(v, (int, float)) and float(v) > 0}
    if not covered:
        return None
    return max(covered, key=covered.get)


def _empty_result(topology: dict) -> dict:
    """空结果，key 集合来自拓扑。"""
    z = {k: 0.0 for k in topology.get('all_keys', set())}
    return {MODE_JIAN1_TO_WAX: dict(z), MODE_JIAN1_TO_DIESEL: dict(z), "main_crude_used": "", "proxy_applied": False}


def _device_gets_jian1(device_id: str, topology: dict, tank_yields: dict, mode: str) -> bool:
    """判断装置在指定 mode 下是否收到 jian1（减一线）。

    逻辑：
      - 找到该装置的上游罐（tank_to_target 边）
      - MODE_JIAN1_TO_DIESEL mode: 上游罐有 jian1_to_diesel 组 yield > 0 → jian1 走柴加侧 → 该装置收到 jian1
      - MODE_JIAN1_TO_WAX mode: 上游罐有 jian1_to_wax 组 yield > 0 → jian1 走蜡加侧 → 该装置收到 jian1
    """
    # 找该装置的上游罐
    upstream_tanks = {e['tank_id'] for e in topology['tank_device_edges']
                      if e['device_id'] == device_id}
    for tank_id in upstream_tanks:
        yld = tank_yields.get(tank_id)
        if not yld:
            continue
        if mode == MODE_JIAN1_TO_DIESEL and yld['jian1_to_diesel'] > 0:
            return True
        if mode == MODE_JIAN1_TO_WAX and yld['jian1_to_wax'] > 0:
            return True
    return False


# ── 核心计算 ──────────────────────────────────────────────────────────────

def compute_device_inputs_by_mode(
    blend_detail: dict,
    yield_tables: dict,
    topology: dict,
    fallback_crude: Optional[str] = None,
) -> dict:
    """计算某天的"两种工况"装置进料量。

    Args:
      blend_detail: {crude_type_id: tons}，当天原油混合明细
      yield_tables: load_yield_tables() 的返回值（收率均为小数）
      topology: build_flow_topology() 的返回值
      fallback_crude: 当 blend_detail 主力油和混合料都没有覆盖油时，用此 crude_type 兜底

    Returns:
      {
        MODE_JIAN1_TO_WAX: { device_id: tons, ... },
        MODE_JIAN1_TO_DIESEL: { device_id: tons, ... },
        'main_crude_used': str,
        'proxy_applied': bool,
      }
    """
    known = set(yield_tables.keys())
    blend = {k: float(v) for k, v in (blend_detail or {}).items()
             if isinstance(v, (int, float)) and float(v) > 0}
    total = sum(blend.values())
    if total < 1e-3:
        return _empty_result(topology)

    # 选物料流计算用的 crude_type
    main_crude = max(blend, key=blend.get)
    proxy_applied = False
    if main_crude not in known:
        proxy = _pick_proxy_crude(blend, known)
        if proxy:
            main_crude = proxy
            proxy_applied = True
        elif fallback_crude and fallback_crude in known:
            main_crude = fallback_crude
            proxy_applied = True
        else:
            return _empty_result(topology)

    yt = yield_tables[main_crude]
    tank_yields = yt['tank_yields']
    backflow_yields = yt['backflow_yields']

    # ① 计算各罐在两种 mode 下的输入
    tank_inputs: dict = {}  # tank_id → {MODE_JIAN1_TO_DIESEL: float, MODE_JIAN1_TO_WAX: float}
    for tank_id, yld in tank_yields.items():
        # MODE_JIAN1_TO_DIESEL: jian1_to_diesel=1, jian1_to_wax=0 → fixed + 柴加组
        # MODE_JIAN1_TO_WAX: jian1_to_wax=1, jian1_to_diesel=0 → fixed + 蜡加组
        tank_inputs[tank_id] = {
            MODE_JIAN1_TO_DIESEL: total * (yld['fixed'] + yld['jian1_to_diesel']),
            MODE_JIAN1_TO_WAX: total * (yld['fixed'] + yld['jian1_to_wax']),
        }

    # ② 计算各装置从罐获得的进料
    device_inputs: dict = {}  # device_id → {MODE_JIAN1_TO_DIESEL: float, MODE_JIAN1_TO_WAX: float}
    for key in topology['all_keys']:
        device_inputs[key] = {MODE_JIAN1_TO_DIESEL: 0.0, MODE_JIAN1_TO_WAX: 0.0}

    for edge in topology['tank_device_edges']:
        tank = edge['tank_id']
        dev = edge['device_id']
        sr = edge['split_ratio']
        if tank in tank_inputs:
            device_inputs[dev][MODE_JIAN1_TO_DIESEL] += tank_inputs[tank][MODE_JIAN1_TO_DIESEL] * sr
            device_inputs[dev][MODE_JIAN1_TO_WAX] += tank_inputs[tank][MODE_JIAN1_TO_WAX] * sr

    # ③ 加上 direct 边的回流进料
    # 回流量 = 源装置进料 × 回流 yield
    # 回流 yield 按 mode 选择：源装置收到 jian1 → yield_rate_2，否则 → yield_rate
    for edge in topology['direct_edges']:
        src = edge['source_device']
        tgt = edge['target_device']
        if src not in device_inputs or src not in backflow_yields:
            continue
        bf = backflow_yields[src]
        # 判断源装置在两种 mode 下是否收到 jian1
        gets_jian1_Y = _device_gets_jian1(src, topology, tank_yields, MODE_JIAN1_TO_DIESEL)
        gets_jian1_X = _device_gets_jian1(src, topology, tank_yields, MODE_JIAN1_TO_WAX)

        bf_Y = bf['with_jian1'] if gets_jian1_Y else bf['without_jian1']
        bf_X = bf['with_jian1'] if gets_jian1_X else bf['without_jian1']

        device_inputs[tgt][MODE_JIAN1_TO_DIESEL] += device_inputs[src][MODE_JIAN1_TO_DIESEL] * bf_Y
        device_inputs[tgt][MODE_JIAN1_TO_WAX] += device_inputs[src][MODE_JIAN1_TO_WAX] * bf_X

    return {
        MODE_JIAN1_TO_WAX: {k: round(v[MODE_JIAN1_TO_WAX], 3) for k, v in device_inputs.items()},
        MODE_JIAN1_TO_DIESEL: {k: round(v[MODE_JIAN1_TO_DIESEL], 3) for k, v in device_inputs.items()},
        "main_crude_used": main_crude,
        "proxy_applied": proxy_applied,
    }
