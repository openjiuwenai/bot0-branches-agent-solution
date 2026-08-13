# -*- coding: utf-8 -*-
"""CRUD 路由（装置/产品/连接/能耗/原油品种）。

原 solve/web_app.py 中 devices/products/connections/energy 各有 4 个
CRUD 路由（GET/POST/PUT/DELETE），共 16 个，逻辑高度重复：都是"加载全表
→ 追加/替换/过滤 → 全量回写"。其中 add_energy_consumption 与
delete_energy_consumption 还有 NameError（用了未定义的 loader）。

本文件用配置驱动的 CRUD 工厂消灭这 16x 重复：每个实体只需声明
  - sheet 名与主键列
  - 加载/回写方法名（RefineryRepository 上）
  - 行序列化函数（模型对象 → dict）
  - 默认值表（新增时缺省字段）

工厂内部统一处理：加载全量 → 追加/替换/过滤 → repo.save → 返回。
原 NameError 在此自然消失（工厂统一持有 repo 实例）。
"""
import math
from typing import Callable, Dict, List

from flask import Blueprint, jsonify, request

from ..logger import get_logger
from ..data.refinery_repo import RefineryRepository


crud_bp = Blueprint('crud', __name__)
_logger = get_logger()


def _repo() -> RefineryRepository:
    return RefineryRepository()


def _clean_num(v, default=0):
    """NaN/None → default；其余原样返回。"""
    if v is None:
        return default
    try:
        f = float(v)
    except (TypeError, ValueError):
        return default
    if math.isnan(f):
        return default
    return f


# ── 行序列化：模型对象 → 前端 dict ───────────────────────────────────────

def _device_row(d) -> dict:
    return {
        'id': d.id, 'name': d.name, 'type': 'tank' if d.is_tank else getattr(d, 'type', 'normal'),
        'safety_stock_thrd': _clean_num(d.safety_stock_thrd),
        'low_safety_thrd': _clean_num(d.low_safety_thrd),
        'current_capacity': _clean_num(d.current_capacity),
        'refinery_unit_load_percent': _clean_num(d.refinery_unit_load_percent, 100),
        'effective_capacity': _clean_num(d.effective_capacity),
        'backend_device_id': getattr(d, 'backend_device_id', None),
        'tank_category': getattr(d, 'tank_category', None),
        'material_id': getattr(d, 'material_id', None),
        'material_name': getattr(d, 'material_name', None),
        'enabled': getattr(d, 'enabled', True),
    }


def _product_composite_id(product_id: str, crude_type: str) -> str:
    """生成 URL 安全的复合 ID：product_id~crude_type（/ → __）。"""
    return f"{product_id}~{crude_type.replace('/', '__')}"


def _product_parse_id(cid: str) -> tuple:
    """解析复合 ID → (product_id, crude_type)。无 ~ 则返回 (cid, None)。"""
    if '~' in cid:
        pid, ct = cid.split('~', 1)
        return pid, ct.replace('__', '/')
    return cid, None


def _product_row(p) -> dict:
    return {
        'id': _product_composite_id(p.id, p.crude_type),
        'name': p.name, 'source_device_id': p.source_device_id,
        'yield_rate': round(p.yield_rate * 100, 2),
        'yield_rate_2': round(p.yield_rate_2 * 100, 2),
        'yield_rate_3': round(p.yield_rate_3 * 100, 2),
        'yield_rate_4': round(p.yield_rate_4 * 100, 2),
        'is_final': p.is_final,
        'crude_type': p.crude_type,
        'material_type': getattr(p, 'material_type', 'product'),
    }


def _connection_row(c) -> dict:
    return {
        'id': c.id, 'from_device_id': c.from_device_id,
        'from_product_id': c.from_product_id, 'to_device_id': c.to_device_id,
        'priority': c.priority, 'is_unique_target': c.is_unique_target,
        'special_var': c.special_var,
    }


def _material_flow_row(f) -> dict:
    return {
        'id': f.id,
        'source_type': f.source_type,
        'source_device_id': f.source_device_id or '',
        'source_product_id': f.source_product_id or '',
        'source_name': f.source_name or '',
        'tank_id': f.tank_id or '',
        'target_device_id': f.target_device_id or '',
        'target_product_id': f.target_product_id or '',
        'flow_type': f.flow_type,
        'special_var': f.special_var or '',
        'priority': f.priority,
        'is_unique_target': f.is_unique_target,
        'split_ratio': _clean_num(f.split_ratio, 1.0),
    }


def _energy_row(ec) -> dict:
    return {
        'id': ec.id, 'device_id': ec.device_id,
        'consumption_per_ton': _clean_num(ec.consumption_per_ton),
        'price_per_unit': _clean_num(ec.price_per_unit),
        'energy_type': ec.energy_type,
    }


# ── 请求体 → 存储行（字段名=DB 列名；收率前端百分比 → DB 小数 ÷100）──────

def _device_payload(data: dict, item_id: str) -> dict:
    bid = data.get('backend_device_id')
    tc = data.get('tank_category')
    mid = data.get('material_id')
    return {
        'device_id': data.get('id', item_id),
        'name': data.get('name', ''), 'type': data.get('type', ''),
        'safety_stock_thrd': data.get('safety_stock_thrd', 0),
        'low_safety_thrd': data.get('low_safety_thrd', 0),
        'current_capacity': data.get('current_capacity', 0),
        'refinery_unit_load_pct': data.get('refinery_unit_load_percent', 100),
        'backend_device_id': int(bid) if bid not in (None, '', 0) else None,
        'tank_category': tc if tc in ('intermediate', 'product', 'crude') else None,
        'material_id': int(mid) if mid not in (None, '', 0) else None,
        'enabled': bool(data.get('enabled', True)),
    }


def _device_payload_to_dict(d) -> dict:
    """Device 模型对象 → 存储行 dict（用于 units/tanks 合并保存时保留另一类）。"""
    return _device_payload(_device_row(d), d.id)


def _product_payload(data: dict, item_id: str) -> dict:
    # 解析复合 ID（product_id~crude_type）或从请求体取 crude_type
    raw_id = data.get('id', item_id)
    product_id, parsed_ct = _product_parse_id(raw_id)
    crude_type = parsed_ct or data.get('crude_type', 'BZ')
    return {
        'product_id': product_id,
        'name': data.get('name', ''),
        'source_device_id': data.get('source_device_id', ''),
        # 前端传百分比，DB 存小数 → ÷100
        'yield_rate': float(data.get('yield_rate', 0) or 0) / 100.0,
        'yield_rate_2': float(data.get('yield_rate_2', 0) or 0) / 100.0,
        'yield_rate_3': float(data.get('yield_rate_3', 0) or 0) / 100.0,
        'yield_rate_4': float(data.get('yield_rate_4', 0) or 0) / 100.0,
        'is_final': data.get('is_final', False),
        'crude_type': crude_type,
        'material_type': data.get('material_type', 'product'),
    }


def _connection_payload(data: dict, item_id: str) -> dict:
    priority = data.get('priority', 0)
    if isinstance(priority, str):
        priority = int(priority.replace('📝 ', '').strip())
    elif not isinstance(priority, int):
        priority = int(priority)
    special_var = data.get('special_var')
    if isinstance(special_var, str):
        special_var = special_var.replace('📝 ', '').strip() or None
    return {
        'connection_id': data.get('id', item_id),
        'from_device_id': data.get('from_device_id', ''),
        'from_product_id': data.get('from_product_id', ''),
        'to_device_id': data.get('to_device_id', ''),
        'priority': priority,
        'is_unique_target': data.get('is_unique_target', False),
        'special_var': special_var,
    }


def _material_flow_payload(data: dict, item_id: str) -> dict:
    special_var = data.get('special_var')
    if isinstance(special_var, str):
        special_var = special_var.strip() or None
    sr = data.get('split_ratio', 1.0)
    return {
        'flow_id': data.get('id', item_id),
        'source_type': data.get('source_type', 'device'),
        'source_device_id': data.get('source_device_id') or None,
        'source_product_id': data.get('source_product_id') or None,
        'source_name': data.get('source_name') or None,
        'tank_id': data.get('tank_id') or None,
        'target_device_id': data.get('target_device_id') or None,
        'target_product_id': data.get('target_product_id') or None,
        'flow_type': data.get('flow_type', 'source_to_tank'),
        'special_var': special_var,
        'priority': int(data.get('priority', 1)) if data.get('priority') is not None else 1,
        'is_unique_target': data.get('is_unique_target', False),
        'split_ratio': float(sr) if sr is not None else 1.0,
    }


def _energy_payload(data: dict, item_id: str) -> dict:
    return {
        'id': data.get('id', item_id),
        'device_id': data.get('device_id', ''),
        'consumption_per_ton': data.get('consumption_per_ton', 0),
        'price_per_unit': data.get('price_per_unit', 0),
        'energy_type': data.get('energy_type', 'electricity'),
    }


# ── CRUD 工厂 ────────────────────────────────────────────────────────────

def _build_crud(
    entity: str,                # 实体名（日志/消息用）
    list_key: str,              # GET 响应中的列表键
    load_fn: Callable,          # repo.load_xxx() → 可迭代模型对象
    save_fn: Callable,          # repo.save_xxx(rows)
    to_row: Callable,           # 模型对象 → dict
    to_payload: Callable,       # 请求数据 → 存储行
    default_id_prefix: str = None,  # 新增时缺省 ID 前缀（energy 用）
    id_generator: Callable = None,  # 自定义 ID 生成器：(data, existing_ids) → new_id
):
    """为一个实体生成 GET/POST/PUT/DELETE 四个路由处理函数。

    返回 (get_handler, add_handler, update_handler, delete_handler)。
    """

    def _to_storage_row(o) -> dict:
        """模型对象 → 存储行：经 to_row（前端格式）再 to_payload（存储格式）转换。

        必须走这条组合而非直接取模型字段：to_row 与 to_payload 是新增/更新行
        字段映射的唯一来源，未改动行复用同一映射可保证存储格式完全一致
        （产品收率统一以百分比落盘——load_products 读取时再除以 100）。
        """
        fe = to_row(o)
        return to_payload(fe, fe.get('id', ''))

    def get_handler():
        try:
            repo = _repo()
            items = [to_row(o) for o in load_fn(repo)]
            return jsonify({list_key: items})
        except Exception as e:
            _logger.error(f"获取{entity}失败: {e}")
            return jsonify({'success': False, 'message': str(e)}), 500

    def add_handler():
        try:
            data = request.get_json()
            repo = _repo()
            loaded = list(load_fn(repo))
            rows = [_to_storage_row(o) for o in loaded]
            # 生成新 ID（需前缀时自动递增，避免冲突）
            # existing_ids 从 to_row（前端格式，恒有 'id'）取，
            # 不能从 rows（存储格式，键名因实体而异）取。
            if default_id_prefix:
                existing_ids = {to_row(o)['id'] for o in loaded}
                idx = len(loaded) + 1
                new_id = f"{default_id_prefix}_{idx}"
                while new_id in existing_ids:
                    idx += 1
                    new_id = f"{default_id_prefix}_{idx}"
                # setdefault 不覆盖已有的 key，空字符串也需要替换
                if not data.get('id'):
                    data['id'] = new_id
            elif id_generator:
                existing_ids = {to_row(o)['id'] for o in loaded}
                if not data.get('id'):
                    data['id'] = id_generator(data, existing_ids)
            rows.append(to_payload(data, data.get('id', '')))
            save_fn(repo, rows)
            return jsonify({'success': True, 'message': f'{entity}已添加'})
        except Exception as e:
            _logger.error(f"添加{entity}失败: {e}")
            return jsonify({'success': False, 'message': str(e)}), 500

    def update_handler(item_id):
        try:
            data = request.get_json()
            repo = _repo()
            rows = []
            found = False
            for o in load_fn(repo):
                current_id = to_row(o)['id']
                if current_id == item_id:
                    # 命中：用请求体替换该行（字段名/格式转换由 to_payload 完成）
                    rows.append(to_payload(data, item_id))
                    found = True
                else:
                    # 未命中：保留原行。to_row 输出前端字段名，to_payload 再映射为
                    # 存储字段名（device_id/SafetyStockThrd_Tons 等），收率保持百分比。
                    rows.append(_to_storage_row(o))
            if not found:
                return jsonify({'success': False, 'message': f'{entity}不存在: {item_id}'})
            save_fn(repo, rows)
            return jsonify({'success': True, 'message': f'{entity}已更新'})
        except Exception as e:
            _logger.error(f"更新{entity}失败: {e}")
            return jsonify({'success': False, 'message': str(e)})

    def delete_handler(item_id):
        try:
            repo = _repo()
            # 保留未删除行：to_row→to_payload 统一映射为存储字段名（收率保持百分比）
            rows = [_to_storage_row(o) for o in load_fn(repo)
                    if to_row(o)['id'] != item_id]
            save_fn(repo, rows)
            return jsonify({'success': True, 'message': f'{entity}已删除'})
        except Exception as e:
            _logger.error(f"删除{entity}失败: {e}")
            return jsonify({'success': False, 'message': str(e)})

    # Flask 用函数 __name__ 作 endpoint，工厂生成的同名函数会冲突，
    # 故按 list_key（每实体唯一）重命名，避免 "overwriting existing endpoint"。
    for _h in (get_handler, add_handler, update_handler, delete_handler):
        _h.__name__ = f"{list_key}_{_h.__name__}"

    return get_handler, add_handler, update_handler, delete_handler


# ── 注册路由 ─────────────────────────────────────────────────────────────

# 装置
_d_get, _d_add, _d_upd, _d_del = _build_crud(
    '装置', 'devices',
    lambda r: r.load_devices().values(),
    lambda r, rows: r.save_devices(rows),
    _device_row, _device_payload,
)
crud_bp.add_url_rule('/api/devices', view_func=_d_get, methods=['GET'])
crud_bp.add_url_rule('/api/devices', view_func=_d_add, methods=['POST'])
crud_bp.add_url_rule('/api/devices/<item_id>', view_func=_d_upd, methods=['PUT'])
crud_bp.add_url_rule('/api/devices/<item_id>', view_func=_d_del, methods=['DELETE'])

# 产品（复合主键 product_id+crude_type，load_products_grouped 展开返回全部行）
def _product_id_generator(data: dict, existing_ids: set) -> str:
    """产品 ID 为空时自动生成：{source_device_id}_p{N}。

    N 为该装置下已有 product_id 的最大序号 +1，避免冲突。
    """
    dev = data.get('source_device_id', '') or 'dev'
    # 收集该装置下已有 _pN 的最大序号
    max_n = 0
    prefix = f"{dev}_p"
    for eid in existing_ids:
        # eid 可能是复合 ID（product_id~crude_type），取前半段
        pid = eid.split('~')[0]
        if pid.startswith(prefix):
            try:
                n = int(pid[len(prefix):])
                if n > max_n:
                    max_n = n
            except ValueError:
                pass
    new_id = f"{prefix}{max_n + 1}"
    # 确保不与已有序号冲突（含复合 ID 的情况）
    while new_id in {eid.split('~')[0] for eid in existing_ids}:
        max_n += 1
        new_id = f"{prefix}{max_n + 1}"
    return new_id

_p_get, _p_add, _p_upd, _p_del = _build_crud(
    '产品', 'products',
    lambda r: [p for ct_map in r.load_products_grouped().values() for p in ct_map.values()],
    lambda r, rows: r.save_products(rows),
    _product_row, _product_payload,
    id_generator=_product_id_generator,
)
crud_bp.add_url_rule('/api/products', view_func=_p_get, methods=['GET'])
crud_bp.add_url_rule('/api/products', view_func=_p_add, methods=['POST'])
crud_bp.add_url_rule('/api/products/<item_id>', view_func=_p_upd, methods=['PUT'])
crud_bp.add_url_rule('/api/products/<item_id>', view_func=_p_del, methods=['DELETE'])

# 连接（已废弃 — 由 material_flows 替代，保留路由供前端过渡期兼容）
_c_get, _c_add, _c_upd, _c_del = _build_crud(
    '连接', 'connections',
    lambda r: [],
    lambda r, rows: r.save_connections(rows),
    _connection_row, _connection_payload,
)
crud_bp.add_url_rule('/api/connections', view_func=_c_get, methods=['GET'])
crud_bp.add_url_rule('/api/connections', view_func=_c_add, methods=['POST'])
crud_bp.add_url_rule('/api/connections/<item_id>', view_func=_c_upd, methods=['PUT'])
crud_bp.add_url_rule('/api/connections/<item_id>', view_func=_c_del, methods=['DELETE'])

# 物流边（material_flows）
_mf_get_factory, _mf_add, _mf_upd, _mf_del = _build_crud(
    '物流边', 'material_flows',
    lambda r: r.load_material_flows().values(),
    lambda r, rows: r.save_material_flows(rows),
    _material_flow_row, _material_flow_payload,
    default_id_prefix='mf',
)


@crud_bp.route('/api/material_flows', methods=['GET'])
def _mf_get_enriched():
    """获取物流边列表，附带设备名/产品名/罐名以提升前端可读性。"""
    try:
        repo = _repo()
        flows = [_material_flow_row(f) for f in repo.load_material_flows().values()]
        # 构建名称查找表
        devices = repo.load_devices()
        dev_names = {did: d.name for did, d in devices.items()}
        products = repo.load_products()
        prod_names = {p.id: p.name for p in products.values()}  # noqa: product_id 可能跨原油品种重复，名称一致
        # material_role 已废弃，不再返回
        for f in flows:
            f['source_device_name'] = dev_names.get(f.get('source_device_id', ''), '')
            f['source_product_name'] = prod_names.get(f.get('source_product_id', ''), '')
            f['tank_name'] = dev_names.get(f.get('tank_id', ''), '')
            f['target_device_name'] = dev_names.get(f.get('target_device_id', ''), '')
            f['target_product_name'] = prod_names.get(f.get('target_product_id', ''), '')
        return jsonify({'flows': flows})
    except Exception as e:
        _logger.error(f"获取物流边失败: {e}")
        return jsonify({'success': False, 'message': str(e)}), 500

crud_bp.add_url_rule('/api/material_flows', view_func=_mf_add, methods=['POST'])
crud_bp.add_url_rule('/api/material_flows/<item_id>', view_func=_mf_upd, methods=['PUT'])
crud_bp.add_url_rule('/api/material_flows/<item_id>', view_func=_mf_del, methods=['DELETE'])

# 装置（type != tank，复用 devices 表）
_u_get, _u_add, _u_upd, _u_del = _build_crud(
    '装置', 'units',
    lambda r: [d for d in r.load_devices().values() if not d.is_tank],
    lambda r, rows: r.save_devices(rows + [_device_payload_to_dict(d) for d in r.load_devices().values() if d.is_tank]),
    _device_row, _device_payload,
)
crud_bp.add_url_rule('/api/units', view_func=_u_get, methods=['GET'])
crud_bp.add_url_rule('/api/units', view_func=_u_add, methods=['POST'])
crud_bp.add_url_rule('/api/units/<item_id>', view_func=_u_upd, methods=['PUT'])
crud_bp.add_url_rule('/api/units/<item_id>', view_func=_u_del, methods=['DELETE'])

# 储罐（type == tank，复用 devices 表）
_t_get, _t_add, _t_upd, _t_del = _build_crud(
    '储罐', 'tanks',
    lambda r: [d for d in r.load_devices().values() if d.is_tank],
    lambda r, rows: r.save_devices(rows + [_device_payload_to_dict(d) for d in r.load_devices().values() if not d.is_tank]),
    _device_row, _device_payload,
)
crud_bp.add_url_rule('/api/tanks', view_func=_t_get, methods=['GET'])
crud_bp.add_url_rule('/api/tanks', view_func=_t_add, methods=['POST'])
crud_bp.add_url_rule('/api/tanks/<item_id>', view_func=_t_upd, methods=['PUT'])
crud_bp.add_url_rule('/api/tanks/<item_id>', view_func=_t_del, methods=['DELETE'])

# 能耗（原 add/delete 有 NameError，工厂统一持有 repo 后消失）
_e_get, _e_add, _e_upd, _e_del = _build_crud(
    '能耗记录', 'energy_consumptions',
    lambda r: r.load_energy_consumptions(),
    lambda r, rows: r.save_energy_consumptions(rows),
    _energy_row, _energy_payload,
    default_id_prefix='energy',
)
crud_bp.add_url_rule('/api/energy_consumptions', view_func=_e_get, methods=['GET'])
crud_bp.add_url_rule('/api/energy_consumptions', view_func=_e_add, methods=['POST'])
crud_bp.add_url_rule('/api/energy_consumptions/<item_id>', view_func=_e_upd, methods=['PUT'])
crud_bp.add_url_rule('/api/energy_consumptions/<item_id>', view_func=_e_del, methods=['DELETE'])


# ── 原油品种主数据（CRUD，来自 public.crude_types 表）─────────────────

@crud_bp.route('/api/crude_types', methods=['GET'])
def get_crude_types():
    """获取所有油种列表。"""
    try:
        repo = _repo()
        crude_list = repo.load_crude_type_list()
        return jsonify({'success': True, 'data': crude_list})
    except Exception as e:
        _logger.error(f"获取油种列表失败: {e}")
        return jsonify({'success': False, 'data': [], 'message': str(e)}), 500


@crud_bp.route('/api/crude_types', methods=['POST'])
def add_crude_type():
    """新增油种。"""
    try:
        data = request.get_json()
        repo = _repo()
        repo.save_crude_type(data)
        return jsonify({'success': True, 'message': '油种已新增'})
    except Exception as e:
        _logger.error(f"新增油种失败: {e}")
        return jsonify({'success': False, 'message': str(e)}), 400


@crud_bp.route('/api/crude_types/<item_id>', methods=['PUT'])
def update_crude_type(item_id):
    """更新油种。"""
    try:
        data = request.get_json()
        data['crude_type_id'] = item_id
        repo = _repo()
        repo.save_crude_type(data, is_update=True)
        return jsonify({'success': True, 'message': '油种已更新'})
    except Exception as e:
        _logger.error(f"更新油种失败: {e}")
        return jsonify({'success': False, 'message': str(e)}), 400


@crud_bp.route('/api/crude_types/<item_id>', methods=['DELETE'])
def delete_crude_type(item_id):
    """删除油种（default 不可删除）。"""
    try:
        if item_id == 'default':
            return jsonify({'success': False, 'message': 'default 油种不可删除'}), 400
        repo = _repo()
        repo.delete_crude_type(item_id)
        return jsonify({'success': True, 'message': '油种已删除'})
    except Exception as e:
        _logger.error(f"删除油种失败: {e}")
        return jsonify({'success': False, 'message': str(e)}), 400


# ── 装置主数据（来自 public.md_device，供前端关联下拉）─────────────────

@crud_bp.route('/api/md_devices', methods=['GET'])
def get_md_devices():
    """获取慧炼主数据装置列表（id/name/alias），用于 solve_db.devices.backend_device_id 关联。"""
    try:
        from ..data.db import engine
        from sqlalchemy import text
        with engine.connect() as conn:
            rows = conn.execute(text(
                "SELECT id, name, alias FROM public.md_device ORDER BY id"
            )).mappings().all()
        return jsonify({'success': True, 'data': [
            {'id': r['id'], 'name': r['name'],
             'alias': r['alias'] if r['alias'] is not None else ''}
            for r in rows
        ]})
    except Exception as e:
        _logger.error(f"获取 md_device 列表失败: {e}")
        return jsonify({'success': False, 'data': [], 'message': str(e)}), 500


# ── 中间罐月初容量 ──────────────────────────────────────────────────────

@crud_bp.route('/api/tank_monthly_initial', methods=['GET'])
def get_tank_monthly_initial():
    """获取指定月份的中间罐月初容量。?year_month=2026-07"""
    try:
        ym = request.args.get('year_month', '')
        if not ym:
            return jsonify({'success': False, 'message': '缺少 year_month 参数'}), 400
        repo = _repo()
        rows = repo.load_tank_monthly_initial(ym)
        return jsonify({'success': True, 'data': rows})
    except Exception as e:
        _logger.error(f"获取月初容量失败: {e}")
        return jsonify({'success': False, 'data': [], 'message': str(e)}), 500

@crud_bp.route('/api/tank_monthly_initial', methods=['PUT'])
def save_tank_monthly_initial():
    """批量保存中间罐月初容量。body: {year_month, rows:[{tank_id, initial_capacity}]}"""
    try:
        data = request.get_json(force=True)
        ym = data.get('year_month', '')
        rows = data.get('rows', [])
        if not ym:
            return jsonify({'success': False, 'message': '缺少 year_month'}), 400
        payload = [{'tank_id': r['tank_id'], 'year_month': ym,
                    'initial_capacity': r.get('initial_capacity', 0)} for r in rows]
        repo = _repo()
        repo.save_tank_monthly_initial(payload)
        return jsonify({'success': True, 'message': f'已保存 {len(payload)} 条月初容量'})
    except Exception as e:
        _logger.error(f"保存月初容量失败: {e}")
        return jsonify({'success': False, 'message': str(e)}), 400


# ── 日志配置 ─────────────────────────────────────────────────────────────

@crud_bp.route('/api/log-config', methods=['POST'])
def set_log_config():
    try:
        data = request.get_json()
        verbose = data.get('verbose', True)
        get_logger().set_verbose(verbose)
        return jsonify({'success': True, 'verbose': verbose,
                        'message': f'日志详细程度已设置为: {verbose}'})
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)})
