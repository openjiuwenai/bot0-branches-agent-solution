# -*- coding: utf-8 -*-
"""侧线配置路由——直接操作 solve_db.side_lines 表。

与旧 /api/products 路由的区别：
  - 旧路由操作 side_lines + device_yields 联合视图，保存时全量替换两表
  - 本路由仅操作 side_lines 主数据表，不触碰 device_yields
  - 收率数据由 /api/yields 路由或 /api/products 路由管理

物料绑定（material_id）直接在 upsert 中处理，无需单独调用 mapping 接口。
"""
from flask import Blueprint, jsonify, request
from sqlalchemy import text

from ..data.db import SessionLocal
from ..logger import get_logger

side_line_bp = Blueprint('side_line', __name__)
_logger = get_logger()


@side_line_bp.route('/api/side_lines', methods=['GET'])
def get_side_lines():
    """获取全部侧线 + 物料列表（供下拉选择）。"""
    from data_service.repositories import side_line_repo, material_repo

    db = SessionLocal()
    try:
        rows = side_line_repo.load_side_lines(db)
        materials = material_repo.load_materials(db)
        return jsonify({
            'side_lines': rows,
            'materials': materials,
        })
    except Exception as e:
        _logger.error(f"获取侧线列表失败: {e}", exc_info=True)
        return jsonify({'message': str(e)}), 500
    finally:
        db.close()


@side_line_bp.route('/api/materials', methods=['GET'])
def get_materials():
    """获取全部物料列表（供储罐管理等下拉选择）。"""
    from data_service.repositories import material_repo

    db = SessionLocal()
    try:
        materials = material_repo.load_materials(db)
        return jsonify({'materials': materials})
    except Exception as e:
        _logger.error(f"获取物料列表失败: {e}", exc_info=True)
        return jsonify({'message': str(e)}), 500
    finally:
        db.close()


@side_line_bp.route('/api/side_lines', methods=['POST'])
def create_side_line():
    """新增侧线。

    Body: {side_line_id, name, source_device_id, material_type, is_final, material_id?}
    """
    from data_service.writers import side_line_writer

    data = request.json or {}
    side_line_id = data.get('side_line_id', '').strip()
    if not side_line_id:
        return jsonify({'message': 'side_line_id 不能为空'}), 400

    db = SessionLocal()
    try:
        side_line_writer.upsert_side_line(db, {
            'side_line_id': side_line_id,
            'name': data.get('name', ''),
            'source_device_id': data.get('source_device_id', ''),
            'material_type': data.get('material_type', 'product'),
            'is_final': bool(data.get('is_final', False)),
            'material_id': data.get('material_id'),
        })
        db.commit()
        _logger.info(f"新增侧线: {side_line_id}")
        return jsonify({'success': True, 'message': f'侧线 {side_line_id} 已创建'}), 201
    except Exception as e:
        db.rollback()
        _logger.error(f"新增侧线失败: {e}", exc_info=True)
        return jsonify({'message': str(e)}), 500
    finally:
        db.close()


@side_line_bp.route('/api/side_lines/<side_line_id>', methods=['PUT'])
def update_side_line(side_line_id: str):
    """更新侧线。

    Body: {name, source_device_id, material_type, is_final, material_id?}
    side_line_id 从 URL 取，不信任 body 中的值。
    """
    from data_service.writers import side_line_writer

    data = request.json or {}
    db = SessionLocal()
    try:
        side_line_writer.upsert_side_line(db, {
            'side_line_id': side_line_id,
            'name': data.get('name', ''),
            'source_device_id': data.get('source_device_id', ''),
            'material_type': data.get('material_type', 'product'),
            'is_final': bool(data.get('is_final', False)),
            'material_id': data.get('material_id'),
        })
        db.commit()
        _logger.info(f"更新侧线: {side_line_id}")
        return jsonify({'success': True, 'message': f'侧线 {side_line_id} 已更新'})
    except Exception as e:
        db.rollback()
        _logger.error(f"更新侧线失败: {e}", exc_info=True)
        return jsonify({'message': str(e)}), 500
    finally:
        db.close()


@side_line_bp.route('/api/side_lines/<side_line_id>', methods=['DELETE'])
def delete_side_line(side_line_id: str):
    """删除侧线。device_yields 由外键 ON DELETE CASCADE 自动清理。"""
    from data_service.writers import side_line_writer

    db = SessionLocal()
    try:
        side_line_writer.delete_side_line(db, side_line_id)
        db.commit()
        _logger.info(f"删除侧线: {side_line_id}（收率已级联删除）")
        return jsonify({'success': True, 'message': f'侧线 {side_line_id} 已删除'})
    except Exception as e:
        db.rollback()
        _logger.error(f"删除侧线失败: {e}", exc_info=True)
        return jsonify({'message': str(e)}), 500
    finally:
        db.close()


# ── 收率路由（仅操作 device_yields 表）──────────────────────────────────

@side_line_bp.route('/api/yields', methods=['POST'])
def upsert_yield():
    """新增/更新单条收率。

    Body: {side_line_id, crude_type, yield_rate, yield_rate_2, yield_rate_3, yield_rate_4}
    """
    from data_service.writers import side_line_writer

    data = request.json or {}
    side_line_id = data.get('side_line_id', '').strip()
    crude_type = data.get('crude_type', 'default').strip()
    if not side_line_id:
        return jsonify({'message': 'side_line_id 不能为空'}), 400

    db = SessionLocal()
    try:
        side_line_writer.upsert_yields(db, [{
            'side_line_id': side_line_id,
            'crude_type': crude_type,
            'yield_rate': float(data.get('yield_rate', 0)),
            'yield_rate_2': float(data.get('yield_rate_2', 0)),
            'yield_rate_3': float(data.get('yield_rate_3', 0)),
            'yield_rate_4': float(data.get('yield_rate_4', 0)),
        }])
        db.commit()
        return jsonify({'success': True}), 201
    except Exception as e:
        db.rollback()
        _logger.error(f"保存收率失败: {e}", exc_info=True)
        return jsonify({'message': str(e)}), 500
    finally:
        db.close()


@side_line_bp.route('/api/yields/<side_line_id>/<crude_type>', methods=['DELETE'])
def delete_yield(side_line_id: str, crude_type: str):
    """删除单条收率。"""
    db = SessionLocal()
    try:
        db.execute(text(
            "DELETE FROM solve_db.device_yields "
            "WHERE side_line_id = :sid AND crude_type = :ct"
        ), {'sid': side_line_id, 'ct': crude_type})
        db.commit()
        return jsonify({'success': True})
    except Exception as e:
        db.rollback()
        _logger.error(f"删除收率失败: {e}", exc_info=True)
        return jsonify({'message': str(e)}), 500
    finally:
        db.close()
