# -*- coding: utf-8 -*-
"""排厂任务管理路由。

迁移自 solve/web_app.py L3850-4049：
  - GET    /api/scheduling/tasks            任务列表
  - POST   /api/scheduling/tasks            新建任务
  - GET    /api/scheduling/tasks/<plan_id>  任务详情（含该月输入与计划明细）
  - PUT    /api/scheduling/tasks/<plan_id>  更新任务（status/locked）
  - DELETE /api/scheduling/tasks/<plan_id>  删除任务（已锁定则拒绝）
  - POST   /api/scheduling/tasks/<plan_id>/lock    锁定
  - POST   /api/scheduling/tasks/<plan_id>/unlock  解锁

原版每条路由各自实例化 SchedulingExcelManagerV2；本版统一从工厂取 repo。
原版 delete 手写读全表→改→写全表，此处改调 repo.delete_scheduling_task。
"""
from datetime import datetime

from flask import Blueprint, jsonify, request

from ..logger import get_logger
from ..data.scheduling_repo import SchedulingRepository
from ..models.scheduling import SchedulingTask


task_bp = Blueprint('task', __name__)
_logger = get_logger()


def _repo() -> SchedulingRepository:
    return SchedulingRepository()


@task_bp.route('/api/scheduling/tasks', methods=['GET'])
def get_scheduling_tasks():
    """获取所有排厂任务列表。"""
    try:
        tasks = _repo().load_scheduling_tasks()
        return jsonify({'success': True, 'tasks': [t.to_dict() for t in tasks]})
    except Exception as e:
        _logger.error(f"获取排厂任务列表失败: {e}")
        return jsonify({'success': False, 'message': str(e)})


@task_bp.route('/api/scheduling/tasks', methods=['POST'])
def create_scheduling_task():
    """新建排厂任务。请求体：{ planned_month }。"""
    try:
        data = request.get_json() or {}
        planned_month = data.get('planned_month')
        if not planned_month:
            return jsonify({'success': False, 'message': '缺少计划月份'})

        repo = _repo()
        if repo.get_task_by_month(planned_month):
            return jsonify({'success': False, 'message': f'{planned_month}月份的排厂任务已存在'})

        now = datetime.now().isoformat()
        task = SchedulingTask(
            plan_id=f"PLAN-{planned_month.replace('-', '')}",
            planned_month=planned_month,
            status='draft',
            locked=False,
            created_at=now,
            updated_at=now,
        )
        repo.save_scheduling_task(task)
        return jsonify({'success': True, 'task': task.to_dict(), 'message': '排厂任务创建成功'})
    except Exception as e:
        _logger.error(f"创建排厂任务失败: {e}")
        return jsonify({'success': False, 'message': str(e)})


@task_bp.route('/api/scheduling/tasks/<plan_id>', methods=['GET'])
def get_scheduling_task(plan_id):
    """获取单个排厂任务详情（含该月输入数据与计划明细）。"""
    try:
        repo = _repo()
        task = repo.get_task_by_id(plan_id)
        if not task:
            return jsonify({'success': False, 'message': '任务不存在'})

        input_data = repo.load_production_plans_input()
        details = repo.load_production_plan_details(plan_id)
        return jsonify({
            'success': True,
            'task': task.to_dict(),
            'input_data': [item.to_dict() for item in input_data
                           if item.planned_month == task.planned_month],
            'details': [d.to_dict() for d in details],
        })
    except Exception as e:
        _logger.error(f"获取排厂任务失败: {e}")
        return jsonify({'success': False, 'message': str(e)})


@task_bp.route('/api/scheduling/tasks/<plan_id>', methods=['PUT'])
def update_scheduling_task(plan_id):
    """更新排厂任务（status / locked）。已锁定则拒绝修改。"""
    try:
        data = request.get_json() or {}
        repo = _repo()
        task = repo.get_task_by_id(plan_id)
        if not task:
            return jsonify({'success': False, 'message': '任务不存在'})
        if task.locked:
            return jsonify({'success': False, 'message': '任务已锁定，无法修改'})

        if 'status' in data:
            task.status = data['status']
        if 'locked' in data:
            task.locked = data['locked']
        task.updated_at = datetime.now().isoformat()
        repo.save_scheduling_task(task)
        return jsonify({'success': True, 'task': task.to_dict(), 'message': '任务更新成功'})
    except Exception as e:
        _logger.error(f"更新排厂任务失败: {e}")
        return jsonify({'success': False, 'message': str(e)})


@task_bp.route('/api/scheduling/tasks/<plan_id>', methods=['DELETE'])
def delete_scheduling_task(plan_id):
    """删除排厂任务。已锁定则拒绝删除。"""
    try:
        repo = _repo()
        task = repo.get_task_by_id(plan_id)
        if not task:
            return jsonify({'success': False, 'message': '任务不存在'})
        if task.locked:
            return jsonify({'success': False, 'message': '任务已锁定，无法删除'})

        if repo.delete_scheduling_task(plan_id):
            return jsonify({'success': True, 'message': '任务删除成功'})
        return jsonify({'success': False, 'message': '任务删除失败'})
    except Exception as e:
        _logger.error(f"删除排厂任务失败: {e}")
        return jsonify({'success': False, 'message': str(e)})


@task_bp.route('/api/scheduling/tasks/<plan_id>/lock', methods=['POST'])
def lock_scheduling_task(plan_id):
    """锁定排厂任务。"""
    try:
        if _repo().update_task_status(plan_id, locked=True):
            return jsonify({'success': True, 'message': '任务已锁定'})
        return jsonify({'success': False, 'message': '锁定失败，任务不存在'})
    except Exception as e:
        _logger.error(f"锁定排厂任务失败: {e}")
        return jsonify({'success': False, 'message': str(e)})


@task_bp.route('/api/scheduling/tasks/<plan_id>/unlock', methods=['POST'])
def unlock_scheduling_task(plan_id):
    """解锁排厂任务。"""
    try:
        if _repo().update_task_status(plan_id, locked=False):
            return jsonify({'success': True, 'message': '任务已解锁'})
        return jsonify({'success': False, 'message': '解锁失败，任务不存在'})
    except Exception as e:
        _logger.error(f"解锁排厂任务失败: {e}")
        return jsonify({'success': False, 'message': str(e)})
