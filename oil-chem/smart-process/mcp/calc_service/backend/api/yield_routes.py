# -*- coding: utf-8 -*-
"""收率与排厂基础数据路由（薄封装）。

业务编排（_build_product_yields / _fallback_plans_from_details /
get_scheduling_data 整形）已下沉到 service.YieldService，本层只做调用与响应封装。

迁移自 solve/web_app.py：
  - get_product_yields_core (L2612-2795) → GET /api/scheduling/product_yields
  - get_scheduling_data    (L2474-2609) → GET /api/scheduling/data

/api/scheduling/data 仍返回旧前端依赖的 storage_tanks/arrival_plans 形状
（纯展示转换，无数据丢失），同时附上统一的 production_plans_input 列表。
"""
from flask import Blueprint, jsonify

from ..logger import get_logger
from ..service.yield_service import YieldService


yield_bp = Blueprint('yield', __name__)
_logger = get_logger()


# ── GET /api/scheduling/product_yields ───────────────────────────────────

@yield_bp.route('/api/scheduling/product_yields', methods=['GET'])
def get_product_yields():
    """获取所有产品全集的收率数据（crude_type→device→operation_mode→products）。"""
    try:
        result_data = YieldService().build_product_yields()
        _logger.info(f"产品收率加载完成: {len(result_data['crude_types'])} 种原油类型")
        return jsonify({
            'success': True,
            'message': '获取产品收率数据成功',
            'data': result_data
        })
    except Exception as e:
        _logger.error(f"获取产品收率数据失败: {e}", exc_info=True)
        return jsonify({
            'success': False,
            'message': f'获取产品收率数据失败: {e}',
            'data': None
        })


# ── GET /api/scheduling/data ─────────────────────────────────────────────

@yield_bp.route('/api/scheduling/data', methods=['GET'])
def get_scheduling_data():
    """获取排厂基础数据。"""
    try:
        data = YieldService().get_scheduling_data()
        return jsonify({'success': True, 'data': data})
    except Exception as e:
        _logger.error(f"获取排厂数据失败: {e}")
        return jsonify({'success': False, 'message': str(e)})
