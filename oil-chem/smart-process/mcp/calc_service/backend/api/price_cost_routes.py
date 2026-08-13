# -*- coding: utf-8 -*-
"""价格成本路由（价格成本页专用）—— 直连 DB，通过 data_service 读写。

价格数据通过 data_service.repositories.price_repo（读）和
data_service.writers.price_writer（写）直接操作 PostgreSQL。
前端价格成本页原有交互不变。

本地保留：原油成本（production_plans_input.cost）+ 能耗系数（energy 表）。
"""
from flask import Blueprint, jsonify, request

from ..logger import get_logger
from ..data.scheduling_repo import SchedulingRepository


price_cost_bp = Blueprint('price_cost', __name__)
_logger = get_logger()


def _clean_num(v, default=0):
    """NaN/None → default；其余原样返回。"""
    if v is None:
        return default
    try:
        f = float(v)
    except (TypeError, ValueError):
        return default
    if f != f:  # NaN
        return default
    return f


# ── 产品价格（直连 DB）────────────────────────────────────────────────────

@price_cost_bp.route('/api/price_cost/products', methods=['GET'])
def get_price_cost_products():
    """获取产品价格列表（C 模式：直连 DB，不依赖 FastAPI 价格模块）。

    Query: month=YYYY-MM → 仅返回该月；无 month → 返回全部可用月份。
    返回 {success, data: [{price_month, product_id, product_name, price}], months: [...]}
    一条 SQL 完成侧线→物料→价格联合查询，替代原 HTTP 代理 + 多次 DB 查询。
    """
    try:
        from data_service.repositories import price_repo
        from ..data.db import SessionLocal

        month = (request.args.get('month') or '').strip() or None

        db = SessionLocal()
        try:
            rows = price_repo.load_side_line_prices(db, month=month)
        finally:
            db.close()

        if not rows:
            return jsonify({'success': True, 'data': [], 'months': [month] if month else []})

        # 拼装返回
        result = []
        months_set = set()
        for r in rows:
            result.append({
                'price_month': r['price_month'],
                'product_id': r['product_id'],
                'product_name': r['product_name'],
                'price': _clean_num(r['price']),
            })
            if r['price_month']:
                months_set.add(r['price_month'])

        result.sort(key=lambda x: (x['price_month'], x.get('price', 0) or 0), reverse=True)
        months = sorted(months_set) if not month else [month]

        return jsonify({'success': True, 'data': result, 'months': months})
    except Exception as e:
        _logger.error(f"获取产品价格失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)}), 500


@price_cost_bp.route('/api/price_cost/products', methods=['POST'])
def save_price_cost_products():
    """批量保存产品价格（直连 DB，通过 data_service.writers.price_writer）。

    Body: {month: 'YYYY-MM', items: [{product_id, price, product_name?}]}
    """
    try:
        from data_service.writers import price_writer
        from data_service.connection import get_session

        data = request.get_json() or {}
        month = str(data.get('month', '')).strip()
        items = data.get('items', [])
        if not month:
            return jsonify({'success': False, 'message': '缺少 month 参数'}), 400

        # 获取 product_id → material_id 映射
        from ..data.refinery_repo import RefineryRepository
        repo = RefineryRepository()
        mapping = repo.load_product_material_mapping()

        saved = 0
        errors = []

        db = get_session()
        try:
            for it in items:
                product_id = str(it.get('product_id', '')).strip()
                price = _clean_num(it.get('price'))
                if not product_id or product_id not in mapping:
                    errors.append(f"产品 {product_id} 无物料映射，跳过")
                    continue

                material_id = mapping[product_id]
                try:
                    price_writer.upsert_price(db, month, material_id, price, source="calc_service")
                    saved += 1
                except Exception as e:
                    errors.append(f"产品 {product_id}: {e}")
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

        _logger.info(f"产品价格已保存: month={month}, {saved} 条成功, {len(errors)} 条错误")
        return jsonify({
            'success': True,
            'message': f'已保存 {saved} 条产品价格',
            'count': saved,
            'errors': errors[:10],  # 最多返回前 10 条错误
        })
    except Exception as e:
        _logger.error(f"保存产品价格失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)}), 500


# ── 产品映射管理（product_material_mapping 增删改查）──────────────────────
# 展示 calc_service 所有产品与物料 ID 的绑定关系，支持手动配置。
# 包含全部 product_id（即使未绑定 material_id），方便用户补全映射。

@price_cost_bp.route('/api/price_cost/mapping', methods=['GET'])
def get_product_mapping():
    """获取所有产品的物料映射列表。

    调整2：从 side_lines 读取，material_id 直接挂在 side_lines 上（替代 product_material_mapping）。
    返回 solve_db 全部侧线与当前绑定信息，附带价格模块可用物料列表供下拉选择。
    """
    try:
        from ..data.db import SessionLocal
        from sqlalchemy import text

        db = SessionLocal()
        try:
            # side_lines LEFT JOIN md_material 获取物料名
            rows = db.execute(text("""
                SELECT sl.side_line_id AS product_id, sl.name,
                       sl.material_id, m.name AS material_name
                FROM solve_db.side_lines sl
                LEFT JOIN public.md_material m ON sl.material_id = m.id
                ORDER BY sl.side_line_id
            """)).mappings().all()
        finally:
            db.close()

        # 从价格模块获取可用物料列表（C 模式：直连 DB）
        try:
            from data_service.repositories import price_repo
            from ..data.db import SessionLocal as SolveSessionLocal

            db2 = SolveSessionLocal()
            try:
                materials_list = price_repo.load_all_materials(db2)
            finally:
                db2.close()
        except Exception as e:
            _logger.warning(f"获取物料列表失败: {e}")
            materials_list = []

        data = []
        for r in rows:
            data.append({
                'product_id': str(r['product_id']),
                'product_name': str(r['name'] or ''),
                'material_id': int(r['material_id']) if r['material_id'] is not None else None,
                'material_name': str(r['material_name'] or '') if r['material_name'] else None,
            })

        return jsonify({'success': True, 'data': data, 'materials': materials_list})
    except Exception as e:
        _logger.error(f"获取产品映射失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)}), 500


@price_cost_bp.route('/api/price_cost/mapping', methods=['POST'])
def save_product_mapping():
    """保存/更新单个侧线的物料映射。

    调整2：material_id 直接更新到 side_lines 表（替代 product_material_mapping）。
    Body: {product_id, material_id}  — material_id=null/0 表示解除绑定
    """
    try:
        body = request.get_json() or {}
        product_id = str(body.get('product_id', '')).strip()
        raw_mid = body.get('material_id')

        if not product_id:
            return jsonify({'success': False, 'message': '缺少 product_id'}), 400

        # material_id 可以是 null 或 0 → 解除绑定（设为 NULL）
        material_id = None
        if raw_mid is not None:
            try:
                mid = int(raw_mid)
                if mid > 0:
                    material_id = mid
            except (TypeError, ValueError):
                pass

        from data_service.connection import get_session
        from data_service.writers import side_line_writer

        msg = (f"已解除 {product_id} 的物料绑定"
               if material_id is None
               else f"侧线 {product_id} → 物料 {material_id} 绑定成功")

        with get_session() as db:
            side_line_writer.update_material_binding(db, product_id, material_id)
            db.commit()

        _logger.info(msg)
        return jsonify({'success': True, 'message': msg})
    except Exception as e:
        _logger.error(f"保存产品映射失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)}), 500


# ── 原油成本（production_plans_input.cost）──────────────────────────────

@price_cost_bp.route('/api/price_cost/crude', methods=['GET'])
def get_price_cost_crude():
    """获取原油成本列表。"""
    try:
        month = (request.args.get('month') or '').strip()
        plans = SchedulingRepository().load_production_plans_input()
        items = [{
            'planned_month': p.planned_month,
            'crude_type_id': p.crude_type_id,
            'crude_type_name': p.crude_type_name,
            'cost': p.cost,
        } for p in plans]
        if month:
            items = [it for it in items if it['planned_month'] == month]
        months = sorted({it['planned_month'] for it in items if it['planned_month']})
        return jsonify({'success': True, 'data': items, 'months': months})
    except Exception as e:
        _logger.error(f"获取原油成本失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)}), 500


@price_cost_bp.route('/api/price_cost/crude', methods=['POST'])
def save_price_cost_crude():
    """批量保存原油成本（upsert cost，不动其他字段）。"""
    try:
        data = request.get_json() or {}
        month = str(data.get('month', '')).strip()
        items = data.get('items', [])
        if not month:
            return jsonify({'success': False, 'message': '缺少 month 参数'}), 400
        rows = [{
            'crude_type_id': str(it.get('crude_type_id', '')).strip(),
            'cost': _clean_num(it.get('cost'), 1000.0),
            'crude_type_name': it.get('crude_type_name'),
        } for it in items if it.get('crude_type_id')]
        SchedulingRepository().save_production_plans_input_costs(month, rows)
        _logger.info(f"原油成本已保存: month={month}, {len(rows)} 条")
        return jsonify({'success': True, 'message': f'已保存 {len(rows)} 条原油成本', 'count': len(rows)})
    except Exception as e:
        _logger.error(f"保存原油成本失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)}), 500
