# -*- coding: utf-8 -*-
"""排产计划与优化求解路由（薄封装）。

业务编排与 helper（_load_scenario_fn / _extract_crude_costs /
_build_economic_explanation / _update_device_load_rate）已下沉到
service.SolveService，本层只做参数解析、调用与响应封装。

迁移自 solve/web_app.py：
  - generate_plan            (POST /api/scheduling/generate_plan)
  - generate_plan_status     (GET  /api/scheduling/generate_plan_status/<task_id>)
  - comprehensive_solve      (POST /api/scheduling/comprehensive_solve)
  - optimize_valve_switching (POST /api/scheduling/optimize_valve)
  - get_scheduling_plans     (GET  /api/scheduling/plans)
  - get_scheduling_plan_detail (GET  /api/scheduling/plan/<plan_id>)
  - init_scheduling_data     (POST /api/scheduling/init_data)

/api/solve 旧三模式接口按约定不迁移。
"""
import os
import threading
import uuid
from datetime import datetime

from flask import Blueprint, jsonify, request
from sqlalchemy import text

from ..logger import get_logger
from ..data.db import get_session
from ..data.scheduling_repo import SchedulingRepository
from ..service.solve_service import SolveService
# TODO: crude_scheduling 模块尚未提交到远端，临时注释以保持服务可启动
# from ..crude_scheduling import CrudeSchedulingService, bridge as _cs_bridge
try:
    from ..crude_scheduling import CrudeSchedulingService, bridge as _cs_bridge
except ImportError:
    CrudeSchedulingService = None
    _cs_bridge = None


plan_bp = Blueprint('plan', __name__)
_logger = get_logger()


# ── 异步任务管理（内存级，单进程内有效）──────────────────────────────────
# CP-SAT 求解耗时 1-7 分钟，同步 HTTP 请求会触发各层超时（BFF fetch / 浏览器）。
# 改为异步：POST 立即返回 task_id，前端轮询 GET task_status 取结果。
_tasks: dict = {}          # {task_id: {"status", "result", "error", "created_at", "plan_month"}}
_tasks_lock = threading.Lock()


def _run_cp_sat_async(task_id: str, template_path: str, plan_month: str,
                      solver: str = 'colleague'):
    """后台线程执行 CP-SAT 排产，结果写入 _tasks。"""
    # 进度回调：累积事件列表 + 记录最新事件，供前端轮询读取
    # 累积列表避免3秒轮询窗口内多个事件被覆盖（前端看不到中间阶段）
    def _progress_cb(event):
        with _tasks_lock:
            if task_id in _tasks:
                _tasks[task_id]["progress"] = event
                _tasks[task_id]["progress_events"].append(event)

    try:
        svc = CrudeSchedulingService()
        plan_result = svc.produce_plan(
            template_path=template_path,
            plan_month=plan_month,
            use_patched_solver=True,
            save_to_db=True,  # 存入 cp_sat_plan_details 独立表，不覆盖客户实际排产
            progress_callback=_progress_cb,
            solver=solver,
        )

        if not plan_result["success"]:
            with _tasks_lock:
                _tasks[task_id]["status"] = "failed"
                _tasks[task_id]["error"] = plan_result.get("message", "排产失败")
            return

        details_dict = [d.to_dict() for d in plan_result["details"]]
        result = {
            'success': True,
            'plan_id': details_dict[0]['plan_id'] if details_dict else f"PLAN-{plan_month.replace('-', '')}",
            'details': _clean_nan_values(details_dict),
            'message': plan_result["message"],
            'solver': solver,
            'viz_input': _clean_nan_values(plan_result.get("viz_input", {})),
            'viz_gantt': _clean_nan_values(plan_result.get("viz_gantt", [])),
            'viz_inventory': _clean_nan_values(plan_result.get("viz_inventory", {})),
        }
        warnings = plan_result.get("warnings", [])
        if warnings:
            result['extra_info'] = {
                'waiting_time': [],
                'total_waiting_days': 0,
                'logistics_warnings': warnings,
                'message': f'CP-SAT 物流仿真: {len(warnings)} 条告警',
            }

        with _tasks_lock:
            _tasks[task_id]["status"] = "done"
            _tasks[task_id]["result"] = result
    except Exception as e:
        _logger.error(f"CP-SAT 异步排产失败: {e}", exc_info=True)
        with _tasks_lock:
            _tasks[task_id]["status"] = "failed"
            _tasks[task_id]["error"] = str(e)


def _sched_repo() -> SchedulingRepository:
    return SchedulingRepository()


def _clean_nan_values(data):
    """递归清理 NaN → 0，保证 JSON 可序列化。"""
    if isinstance(data, dict):
        return {k: _clean_nan_values(v) for k, v in data.items()}
    if isinstance(data, list):
        return [_clean_nan_values(item) for item in data]
    if isinstance(data, float) and data != data:  # NaN
        return 0
    return data


# ── CP-SAT 排产模板路径查找 ──────────────────────────────────────────────
# 同事的 Excel 模板按月命名：原油加工月计划模板.xlsx（2月）、
# 原油加工月计划模板_01.xlsx（1月）、_03.xlsx（3月）。
# 优先精确匹配，找不到则回退到默认模板。

_CRUDE_DIR = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "原油加工月计划预排产")
)


def _find_template(plan_month: str) -> str:
    """根据 plan_month 查找对应的 Excel 模板路径。"""
    # plan_month 格式 "YYYY-MM" → 月份号 "01"/"02"/"03"
    mm = plan_month.split("-")[-1] if plan_month else "02"
    # 1) 月度专属模板
    month_specific = os.path.join(_CRUDE_DIR, f"原油加工月计划模板_{mm}.xlsx")
    if os.path.exists(month_specific):
        return month_specific
    # 2) 默认模板（2月）
    default = os.path.join(_CRUDE_DIR, "原油加工月计划模板.xlsx")
    return default


# ── POST /api/scheduling/generate_plan ───────────────────────────────────

@plan_bp.route('/api/scheduling/generate_plan', methods=['POST'])
def generate_plan():
    """启动 CP-SAT 排产（异步）。

    请求体：plan_month（必填）。
    CP-SAT 求解耗时 1-7 分钟，此处启动后台线程后立即返回 task_id。
    前端轮询 GET /api/scheduling/generate_plan_status/<task_id> 获取结果。
    """
    data = request.get_json() or {}
    plan_month = data.get('plan_month')
    if not plan_month:
        return jsonify({'success': False, 'message': '缺少计划月份参数'})

    solver = data.get('solver', 'colleague')  # 'colleague' | 'mine'
    template_path = _find_template(plan_month)
    _logger.info(f"CP-SAT排产(异步): plan_month={plan_month}, solver={solver}, template={template_path}")

    task_id = f"cpsat-{plan_month.replace('-', '')}-{uuid.uuid4().hex[:8]}"
    with _tasks_lock:
        # 防并发：同月份已有任务在跑时，拒绝新任务（避免多线程抢CPU互相拖慢）
        for tid, t in _tasks.items():
            if t["status"] == "running" and t.get("plan_month") == plan_month:
                return jsonify({
                    'success': False,
                    'message': f'{plan_month} 排产任务正在运行中（task_id={tid}），请等待完成后再试',
                    'task_id': tid,
                })
        _tasks[task_id] = {
            "status": "running",
            "result": None,
            "error": None,
            "progress": None,
            "progress_events": [],
            "created_at": datetime.now().isoformat(),
            "plan_month": plan_month,
            "solver": solver,
        }

    thread = threading.Thread(
        target=_run_cp_sat_async,
        args=(task_id, template_path, plan_month, solver),
        daemon=True,
    )
    thread.start()

    return jsonify({
        'success': True,
        'task_id': task_id,
        'status': 'running',
        'solver': solver,
        'message': f'CP-SAT 排产已启动（算法={solver}），请轮询状态接口获取结果',
    })


# ── GET /api/scheduling/generate_plan_status/<task_id> ───────────────────

@plan_bp.route('/api/scheduling/generate_plan_status/<task_id>', methods=['GET'])
def generate_plan_status(task_id):
    """轮询 CP-SAT 排产任务状态。

    返回：
      running → {"status": "running"}
      done    → {"status": "done", "result": {...generate_plan完整响应...}}
      failed  → {"status": "failed", "error": "..."}
    """
    with _tasks_lock:
        task = _tasks.get(task_id)
        if not task:
            return jsonify({'success': False, 'message': f'任务 {task_id} 不存在'}), 404
        status = task["status"]
        if status == "running":
            progress = task.get("progress")
            progress_events = task.get("progress_events", [])
            return jsonify({
                'success': True, 'status': 'running',
                'progress': progress,
                'progress_events': progress_events,
            })
        if status == "done":
            result = task["result"]
            # 取完即删，避免内存泄漏
            del _tasks[task_id]
            return jsonify({'success': True, 'status': 'done', 'result': result})
        if status == "failed":
            error = task["error"]
            del _tasks[task_id]
            return jsonify({'success': True, 'status': 'failed', 'error': error})


# ── POST /api/scheduling/comprehensive_solve ─────────────────────────────

@plan_bp.route('/api/scheduling/comprehensive_solve', methods=['POST'])
def comprehensive_solve():
    """综合求解：生成/复用计划 → 优化阀门切换 → 返回最优效益与说明。

    请求体：plan_month / production_plans_input / monthly_crude_input /
    blend_mode / save_data / hangmei_target / plan_source。
    plan_source: 'lp'(默认,读 production_plan_details) | 'cp_sat'(读 cp_sat_plan_details)。

    双价格月口径（选方案/算效益解耦，无需前端传参，由 plan_month 自动推导）：
      - 选方案：用 plan_month 上个月价格/成本评估所有减一线切换组合，挑利润最高（selection_revenue）。
      - 算效益：把选中组合用 plan_month 本月价格/成本全量重算，核实实际效益（optimal_revenue）。
      排产始终用 plan_month。响应新增 selection_price_month/final_price_month/selection_revenue。
    """
    try:
        data = request.get_json() or {}
        plan_month = data.get('plan_month')
        if not plan_month:
            return jsonify({'success': False, 'message': '缺少计划月份参数'})

        result = SolveService().comprehensive_solve(
            plan_month=plan_month,
            production_plans_input=data.get('production_plans_input', []),
            monthly_crude_input=data.get('monthly_crude_input'),
            blend_mode=data.get('blend_mode', False),
            save_data=data.get('save_data', False),
            hangmei_target=data.get('hangmei_target'),
            shutdown_config=data.get('shutdown_config'),
            plan_source=data.get('plan_source', 'lp'),
            simplified=data.get('simplified', False),
            feasibility_rules=data.get('feasibility_rules'),
            selection_strategy=data.get('selection_strategy'),
        )
        return jsonify(_clean_nan_values(result))
    except Exception as e:
        _logger.error(f"综合求解失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)})


# ── GET /api/scheduling/hangmei_target ────────────────────────────────────
#
# 航煤目标产量来源：慧炼 plan_product 表 3号喷气燃料（内贸+出口）month_output 合计。
# calc_service 与慧炼共用同一 PostgreSQL 业务库（search_path=solve_db,public），可直接查
# public.plan_product。供 calc_service 前端选月份时自动拉取目标产量、预填航煤工况面板。

@plan_bp.route('/api/scheduling/hangmei_target', methods=['GET'])
def get_hangmei_target():
    """读取指定月份的航煤目标产量（吨）。

    查询参数：month=YYYY-MM。scenario 优先 draft，无则 actual（对齐慧炼 _scenario_of）。
    返回 {success, month, scenario, target_tons, details}：
      - target_tons: 内贸+出口 month_output 合计（万吨→吨）；无数据为 null
      - details: [{note, month_output_wan, target_tons}] 明细
    """
    month = request.args.get('month')
    if not month:
        return jsonify({'success': False, 'message': '缺少计划月份参数'})

    try:
        with get_session() as db:
            # scenario 优先 draft，无则 actual
            n = db.execute(text(
                "SELECT count(*) FROM public.plan_product "
                "WHERE plan_month=:m AND scenario='draft'"
            ), {"m": month}).scalar()
            scenario = 'draft' if n else 'actual'

            rows = db.execute(text(
                "SELECT note, month_output FROM public.plan_product "
                "WHERE plan_month=:m AND scenario=:s AND product_name='3号喷气燃料' "
                "ORDER BY seq"
            ), {"m": month, "s": scenario}).fetchall()

        if not rows:
            return jsonify({
                'success': True, 'month': month, 'scenario': scenario,
                'target_tons': None, 'details': [],
                'message': f'{month} 无 3号喷气燃料 计划数据',
            })

        details = []
        total_wan = 0.0
        for note, month_output in rows:
            wan = float(month_output or 0)
            total_wan += wan
            details.append({
                'note': note or '',
                'month_output_wan': round(wan, 4),
                'target_tons': round(wan * 10000, 0),
            })

        return jsonify(_clean_nan_values({
            'success': True, 'month': month, 'scenario': scenario,
            'target_tons': round(total_wan * 10000, 0),
            'details': details,
        }))
    except Exception as e:
        _logger.error(f"读取航煤目标产量失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)})


# ── POST /api/scheduling/optimize_valve ──────────────────────────────────

@plan_bp.route('/api/scheduling/optimize_valve', methods=['POST'])
def optimize_valve():
    """优化减一线阀门切换位置（基于已存在的计划，不重新生成、不启用航煤工况）。"""
    try:
        data = request.get_json() or {}
        plan_id = data.get('plan_id')
        if not plan_id:
            return jsonify({'success': False, 'message': '缺少计划ID'})

        result = SolveService().optimize_valve(
            plan_id, simplified=data.get('simplified', False),
            feasibility_rules=data.get('feasibility_rules'),
            selection_strategy=data.get('selection_strategy'))
        return jsonify(result)
    except Exception as e:
        _logger.error(f"优化阀门切换失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)})


# ── POST /api/scheduling/enumerate_switches ───────────────────────────────

@plan_bp.route('/api/scheduling/enumerate_switches', methods=['POST'])
def enumerate_switches():
    """仅流程②：批次划分 + 减一线切换组合识别（不评估效益）。

    请求体：plan_month。基于该月已落盘的排产明细做批次识别与组合枚举，
    供「批次划分与切换组合识别」页展示求解器流程②的能力。
    """
    try:
        data = request.get_json() or {}
        plan_month = data.get('plan_month')
        if not plan_month:
            return jsonify({'success': False, 'message': '缺少计划月份参数'})

        result = SolveService().enumerate_switches(
            plan_month, shutdown_config=data.get('shutdown_config'))
        return jsonify(_clean_nan_values(result))
    except Exception as e:
        _logger.error(f"批次划分与切换组合识别失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)})


# ── POST /api/scheduling/build_inputs ─────────────────────────────────────
#
# 排产求解页的输入数据源：复用慧炼 backend solver_api._build_inputs，
# 从 plan_crude_processing + plan_crude_purchase（慧炼效益预测页「原油加工计划/
# 原油到港计划」两个 tab 的源表）实时派生 production_plans_input，保证 calc_service
# 排产输入与慧炼计划数据同源（油种映射/万吨→吨/到货按月/缺口补齐/罐容兜底），
# 而非 calc_service 自建的脏 production_plans_input 表。
# 跨包依赖：calc_service 与慧炼 backend 共用同一 PostgreSQL 业务库，import 路径经
# 仓库根的 backend/app 提供。

@plan_bp.route('/api/scheduling/build_inputs', methods=['POST'])
def build_inputs():
    """从慧炼计划库表实时构造排产输入（production_plans_input）。

    请求体：plan_month（YYYY-MM）。返回 {success, inputs, skipped, source}：
      - inputs: 求解器 production_plans_input 行列表（油种已合并、单位为吨）
      - skipped: 求解器收率库未覆盖的慧炼油种（前端醒目提示）
      - source: 'scheduling'(客户排产表) | 'auto'(加工计划+到港自动构造)
    """
    try:
        data = request.get_json() or {}
        plan_month = data.get('plan_month')
        if not plan_month:
            return jsonify({'success': False, 'message': '缺少计划月份参数'})

        # 慧炼 backend 以 backend/ 为包根，需把仓库根下的 backend/ 纳入 sys.path
        # 本文件位于 <repo>/calc_service/backend/api/plan_routes.py，向上 3 级到仓库根
        import os
        import sys
        _repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
        _huilian_backend = os.path.join(_repo_root, 'backend')
        if _huilian_backend not in sys.path:
            sys.path.insert(0, _huilian_backend)

        from app.db.database import SessionLocal
        from app.api.solver_api import _build_inputs

        db = SessionLocal()
        try:
            inputs, skipped, source = _build_inputs(db, plan_month)
        finally:
            db.close()

        return jsonify(_clean_nan_values({
            'success': True,
            'inputs': inputs,
            'skipped': skipped,
            'source': source,
        }))
    except Exception as e:
        _logger.error(f"构造排产输入失败: {e}", exc_info=True)
        return jsonify({'success': False, 'message': str(e)})


# ── GET /api/scheduling/plans ────────────────────────────────────────────

@plan_bp.route('/api/scheduling/plans', methods=['GET'])
def get_scheduling_plans():
    """获取所有排厂计划（历史）。"""
    try:
        history = _sched_repo().load_history_plans()
        return jsonify({'success': True, 'plans': history})
    except Exception as e:
        _logger.error(f"获取排厂计划列表失败: {e}")
        return jsonify({'success': False, 'message': str(e)})


# ── GET /api/scheduling/plan/<plan_id> ───────────────────────────────────

@plan_bp.route('/api/scheduling/plan/<plan_id>', methods=['GET'])
def get_scheduling_plan_detail(plan_id):
    """获取单个排厂计划的明细。"""
    try:
        details = _sched_repo().load_production_plan_details(plan_id)
        return jsonify({'success': True, 'details': [d.to_dict() for d in details]})
    except Exception as e:
        _logger.error(f"获取排厂计划明细失败: {e}")
        return jsonify({'success': False, 'message': str(e)})


# ── POST /api/scheduling/init_data ───────────────────────────────────────

@plan_bp.route('/api/scheduling/init_data', methods=['POST'])
def init_scheduling_data():
    """初始化排厂数据（生成模拟数据并落盘）。

    原 solve/ 调用 generate_mock_data + SchedulingExcelManager.save_all_data。
    calc_service 不内置 mock 生成器；此接口保留为兼容入口，未实现时返回提示。
    """
    return jsonify({
        'success': False,
        'message': 'calc_service 未内置 mock 数据生成器，请通过 /api/scheduling/data 导入真实数据',
    })
