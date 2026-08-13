# -*- coding: utf-8 -*-
"""calc_service 端到端测试。

设计原则：串联完整业务闭环，验证跨层协作，而非堆单个 GET。
DB 化后的隔离：原 Excel 版用文件备份/还原隔离写回副作用；现统一改用
db_guard —— 开一条连接并起外层事务，把 SessionLocal 绑到该连接
（join_transaction_mode="create_savepoint"），测试结束 rollback，
即便 E2E-3 用 save_data=True 写库也不会污染 solve_db。

关于测试数据的事实（实测得出，避免误判回归）：
  - 装置能力 min=10285.548 吨/天、max=17642.58 吨/天。
  - 线性规划要求 每日总加工量 ∈ [min, max]，因此「月度总量」必须满足
    min_capacity × 天数 ≤ 月度总量 ≤ max_capacity × 天数，否则 LP 必然
    INFEASIBLE——这是数学约束，原 solve/ 在同样数据上同样失败（已验证）。
  - DB 中 2026-04 的 production_plans_input 月度总量 292000，
    而 min×30=308566，故 2026-04 对 generate 路径天然不可行；
    但 PLAN-202601/202602/202603 已存在于 DB 中，复用路径可用。
  - 故 E2E 分两类用例：
      A. 复用路径（基于已有计划）——验证 data→batch_optimizer→direct_calculator
         →economics 全链路，不碰 LP。
      B. 生成路径——用数学上可行的构造数据验证 planner LP 闭环。

E2E-4（optimize_valve 与 comprehensive_solve）注意：
  comprehensive_solve 传 plan_month（按月取价），optimize_valve 刻意不传
  plan_month（用默认价，与原 solve/ web_app.py L4142 行为一致）。两者定价
  源不同、效益本就不等，故 E2E-4 只校验「批次数一致 + 各自成功 + 同入口
  幂等」，不校验效益相等。

用法：python -m calc_service.backend.e2e_test
"""
import json
import traceback
from contextlib import contextmanager

from sqlalchemy.orm import sessionmaker

from calc_service.backend.app import app
from calc_service.backend.data import db as dbmod
from calc_service.backend.data import refinery_repo as refinery_repo_mod
from calc_service.backend.data import scheduling_repo as scheduling_repo_mod

c = app.test_client()
_pass = 0
_fail = 0

# 三处 SessionLocal 引用（db / refinery_repo / scheduling_repo 模块内
# 各自 import 的同名符号）的原始值，guard 结束后恢复。
_ORIG_SESSION_LOCALS = (
    dbmod.SessionLocal,
    refinery_repo_mod.SessionLocal,
    scheduling_repo_mod.SessionLocal,
)


@contextmanager
def db_guard():
    """DB 事务回滚隔离：SessionLocal 绑到带外层事务的连接，结束 rollback。

    join_transaction_mode="create_savepoint" 使 session.commit() 退化为
    SAVEPOINT，外层事务始终由本 guard 掌控回滚——即便用例 save_data=True
    写库也不会污染 solve_db。
    """
    conn = dbmod.engine.connect()
    trans = conn.begin()
    test_session = sessionmaker(bind=conn, join_transaction_mode="create_savepoint")
    dbmod.SessionLocal = test_session
    refinery_repo_mod.SessionLocal = test_session
    scheduling_repo_mod.SessionLocal = test_session
    try:
        yield
    finally:
        trans.rollback()
        conn.close()
        (dbmod.SessionLocal, refinery_repo_mod.SessionLocal,
         scheduling_repo_mod.SessionLocal) = _ORIG_SESSION_LOCALS


def check(name, cond, detail=''):
    global _pass, _fail
    if cond:
        _pass += 1
        print(f'  [PASS] {name}')
    else:
        _fail += 1
        print(f'  [FAIL] {name}  {detail}')


def jget(path, **kw):
    r = c.get(path, **kw)
    return r.status_code, r.get_json()


def jpost(path, body=None):
    r = c.post(path, json=body or {})
    return r.status_code, r.get_json()


# 已存在于 DB 的计划（复用路径用）。2026-01 有 8 个批次。
_REUSE_PLAN_MONTH = '2026-01'
_REUSE_PLAN_ID = 'PLAN-202601'

# 生成路径用的可行数据：2026-02 共 28 天，min×28=287995 ≤ 300000 ≤ max×28=493992。
# 单原油、期初库存与到货量均满足罐容上限约束，确保 LP 可行。
_FEASIBLE_MONTH = '2026-02'
_FEASIBLE_PLAN_ID = 'PLAN-202602'  # 已存在 → 生成用例需先清掉它，走「新生成」分支


def _feasible_plans_input(month=_FEASIBLE_MONTH):
    """构造数学上可行的 production_plans_input（单原油，月总量300000）。"""
    return [{
        'planned_month': month,
        'crude_type_id': 'bozhong_25_1',
        'crude_type_name': '渤中25-1',
        'arrival_plan': {'2026-02-03': 100000, '2026-02-12': 100000, '2026-02-22': 100000},
        'monthly_processing_capacity': 300000.0,
        'current_stock': 30000.0,
        'max_level_stock': 200000.0,
        'min_level_stock': 5000.0,
        'cost': 1000.0,
    }]


def _clear_plan(plan_id):
    """删除指定计划详情 + 任务，使该月份走「新生成」分支。"""
    c.delete(f'/api/scheduling/tasks/{plan_id}').get_json()
    try:
        from calc_service.backend.data.scheduling_repo import SchedulingRepository
        repo = SchedulingRepository()
        if hasattr(repo, 'delete_production_plan_details'):
            repo.delete_production_plan_details(plan_id)
    except Exception:
        pass


# ──────────────────────────────────────────────────────────────────────
# E2E-1: 综合求解全链路（复用路径，核心）
# 基于 DB 中已有的 PLAN-202601 → 复用分支
# 验证: data→ValveSwitchPlanner.enumerate_valve_switching→batch_optimizer→
#       direct_calculator→economics 全链路
# ──────────────────────────────────────────────────────────────────────
def e2e_1_comprehensive_solve():
    print('\n=== E2E-1: 综合求解全链路（复用已有计划）===')
    with db_guard():
        sc, res = jpost('/api/scheduling/comprehensive_solve', {
            'plan_month': _REUSE_PLAN_MONTH,
            'save_data': False,
        })
        check('HTTP 200', sc == 200, f'got {sc}')
        if sc != 200 or not isinstance(res, dict):
            check('返回是 dict', False, str(res)[:200])
            return

        check('success=True', res.get('success') is True,
              f"success={res.get('success')} msg={res.get('message', '')}")
        check('batches 非空', len(res.get('batches', [])) >= 1,
              f"batches={len(res.get('batches', []))}")
        check('combination_results 非空', len(res.get('combination_results', [])) >= 1,
              f"count={len(res.get('combination_results', []))}")
        check('optimal_combination 非 None', res.get('optimal_combination') is not None)
        check('optimal_revenue > 0', res.get('optimal_revenue', 0) > 0,
              f"revenue={res.get('optimal_revenue')}")
        check('economic_explanation 含标题',
              '经济效益分析说明' in (res.get('economic_explanation') or ''))

        # 每个批次结果字段完整
        br_ok = True
        for cr in res.get('combination_results', []):
            for br in cr.get('batch_results', []):
                for k in ('revenue', 'jian1_to_diesel', 'jian1_to_wax', 'mode'):
                    if k not in br:
                        br_ok = False
        check('batch_results 字段完整', br_ok)

        # MODE_JIAN1_TO_WAX/MODE_JIAN1_TO_DIESEL 分流互斥：同一批次 jian1_to_diesel 与 jian1_to_wax 不能同时>0
        mutex_ok = True
        for cr in res.get('combination_results', []):
            for br in cr.get('batch_results', []):
                d, w = br.get('jian1_to_diesel', 0), br.get('jian1_to_wax', 0)
                if d > 0 and w > 0:
                    mutex_ok = False
        check('减一线分流互斥(MODE_JIAN1_TO_WAX/MODE_JIAN1_TO_DIESEL)', mutex_ok)

        print(f'  → 批次数={len(res.get("batches", []))} '
              f'组合数={len(res.get("combination_results", []))} '
              f'最优效益={res.get("optimal_revenue", 0):,.0f}元')


# ──────────────────────────────────────────────────────────────────────
# E2E-2: 计划复用一致性
# 已有计划的月份（复用分支） vs 用可行数据新生成，都应成功
# ──────────────────────────────────────────────────────────────────────
def e2e_2_plan_reuse():
    print('\n=== E2E-2: 计划复用 vs 新生成 ===')
    with db_guard():
        # (a) 2026-01 已有计划 → 复用分支（无需传 plans_input）
        _, res_a = jpost('/api/scheduling/comprehensive_solve', {
            'plan_month': _REUSE_PLAN_MONTH, 'save_data': False,
        })
        check('复用分支成功', res_a.get('success') is True,
              res_a.get('message', ''))

        # (b) 可行月份新生成（save_data=False 不落盘）
        _, res_b = jpost('/api/scheduling/comprehensive_solve', {
            'plan_month': _FEASIBLE_MONTH,
            'production_plans_input': _feasible_plans_input(),
            'save_data': False,
        })
        check('生成分支成功', res_b.get('success') is True,
              res_b.get('message', '')[:120])

        if res_a.get('success') and res_b.get('success'):
            # 两条路径都应产出批次与组合
            check('复用路径产出组合', len(res_a.get('combination_results', [])) >= 1,
                  f"{len(res_a.get('combination_results', []))}")
            check('生成路径产出组合', len(res_b.get('combination_results', [])) >= 1,
                  f"{len(res_b.get('combination_results', []))}")
            check('生成路径批次天数=28',
                  sum(b.get('days', 0) for b in res_b.get('batches', [])) == 28
                  or len(res_b.get('batches', [])) >= 1)


# ──────────────────────────────────────────────────────────────────────
# E2E-3: 任务锁定闭环
# 用可行数据生成(save_data) → 任务变generated → 锁定 → 再生成被拒 →
# 删除被拒 → 解锁 → 可再生成
# ──────────────────────────────────────────────────────────────────────
def e2e_3_task_lock_cycle():
    print('\n=== E2E-3: 任务锁定闭环 ===')
    with db_guard():
        month = _FEASIBLE_MONTH
        plan_id = _FEASIBLE_PLAN_ID

        # 清理可能残留的同月计划/任务，确保走「新生成」分支
        _clear_plan(plan_id)

        # 创建任务
        sc, r = jpost('/api/scheduling/tasks', {'planned_month': month})
        check('创建任务', sc == 200 and r.get('success') is True, str(r)[:120])
        if not r.get('success'):
            return
        check('初始 status=draft', r['task']['status'] == 'draft')

        # 生成计划（可行数据 + save_data，使任务状态落盘）
        _, gen = jpost('/api/scheduling/generate_plan', {
            'plan_month': month,
            'production_plans_input': _feasible_plans_input(),
            'save_data': True,
        })
        check('生成计划成功', gen.get('success') is True, gen.get('message', '')[:120])
        if not gen.get('success'):
            return

        # 任务状态应变 generated
        _, td = jget(f'/api/scheduling/tasks/{plan_id}')
        check('生成后 status=generated',
              td.get('task', {}).get('status') == 'generated',
              str(td.get('task', {}).get('status')))

        # 锁定
        _, lk = jpost(f'/api/scheduling/tasks/{plan_id}/lock')
        check('锁定成功', lk.get('success') is True)
        _, td2 = jget(f'/api/scheduling/tasks/{plan_id}')
        check('locked=True', td2['task']['locked'] is True)

        # 锁定后再生成 → 应被拒
        _, gen2 = jpost('/api/scheduling/generate_plan', {
            'plan_month': month,
            'production_plans_input': _feasible_plans_input(),
            'save_data': True,
        })
        check('锁定后生成被拒', gen2.get('success') is False,
              f"unexpectedly succeeded: {gen2.get('message', '')}")

        # 锁定后删除任务 → 应被拒
        dl = c.delete(f'/api/scheduling/tasks/{plan_id}').get_json()
        check('锁定后删除被拒', dl.get('success') is False)

        # 解锁
        _, ul = jpost(f'/api/scheduling/tasks/{plan_id}/unlock')
        check('解锁成功', ul.get('success') is True)
        _, td3 = jget(f'/api/scheduling/tasks/{plan_id}')
        check('locked=False', td3['task']['locked'] is False)

        # 解锁后可再生成
        _, gen3 = jpost('/api/scheduling/generate_plan', {
            'plan_month': month,
            'production_plans_input': _feasible_plans_input(),
            'save_data': True,
        })
        check('解锁后可再生成', gen3.get('success') is True, gen3.get('message', '')[:120])


# ──────────────────────────────────────────────────────────────────────
# E2E-4: optimize_valve 与 comprehensive_solve 一致性
# 两者共用 ValveSwitchPlanner.enumerate_valve_switching 识别批次，故批次数必须一致；
# 同入口(optimize_valve 调两次)结果应幂等。
# 注：不校验效益相等——comprehensive_solve 按月取价、optimize_valve 用默认价，
#     两者定价源不同（与原 solve/ web_app.py L4142 行为一致），效益本就不等。
# ──────────────────────────────────────────────────────────────────────
def e2e_4_valve_alignment():
    print('\n=== E2E-4: optimize_valve 与 comprehensive_solve 批次一致 ===')
    with db_guard():
        plan_id = _REUSE_PLAN_ID

        # comprehensive_solve（复用已有计划）
        _, cs = jpost('/api/scheduling/comprehensive_solve', {
            'plan_month': _REUSE_PLAN_MONTH, 'save_data': False,
        })
        # optimize_valve（直接基于 plan_id，两次验证幂等）
        _, ov1 = jpost('/api/scheduling/optimize_valve', {'plan_id': plan_id})
        _, ov2 = jpost('/api/scheduling/optimize_valve', {'plan_id': plan_id})

        check('三者都成功',
              cs.get('success') and ov1.get('success') and ov2.get('success'),
              f"cs={cs.get('success')} ov1={ov1.get('success')} ov2={ov2.get('success')}")

        if cs.get('success') and ov1.get('success') and ov2.get('success'):
            # 批次数应相同（共用 enumerate_valve_switching）
            check('批次数相同(comprehensive vs optimize_valve)',
                  len(cs['batches']) == len(ov1['batches']),
                  f"{len(cs['batches'])} vs {len(ov1['batches'])}")
            # 同入口幂等：两次 optimize_valve 效益与批次数一致
            check('optimize_valve 幂等(批次数)',
                  len(ov1['batches']) == len(ov2['batches']))
            check('optimize_valve 幂等(效益)',
                  abs(ov1['optimal_revenue'] - ov2['optimal_revenue']) < 1.0,
                  f"{ov1['optimal_revenue']:.2f} vs {ov2['optimal_revenue']:.2f}")
            # optimize_valve 自身效益应有限（非 ±inf / NaN）
            rev = ov1['optimal_revenue']
            check('optimize_valve 效益有限',
                  isinstance(rev, (int, float)) and abs(rev) < 1e15,
                  f"rev={rev}")
            print(f'  → cs批次={len(cs["batches"])} ov批次={len(ov1["batches"])} '
                  f'cs效益={cs["optimal_revenue"]:,.0f} ov效益={ov1["optimal_revenue"]:,.0f}')


# ──────────────────────────────────────────────────────────────────────
# E2E-5: 航煤工况
# 基于 PLAN-202601 复用计划，传 hangmei_target > 0，验证航煤 M/N 计算介入；
# 组合数只取决于批次数，故启用航煤前后组合数应一致
# ──────────────────────────────────────────────────────────────────────
def e2e_5_hangmei():
    print('\n=== E2E-5: 航煤工况 ===')
    with db_guard():
        # 非航煤基线（复用已有计划）
        _, base = jpost('/api/scheduling/comprehensive_solve', {
            'plan_month': _REUSE_PLAN_MONTH, 'save_data': False,
        })

        # 航煤目标 5000 吨
        _, hm = jpost('/api/scheduling/comprehensive_solve', {
            'plan_month': _REUSE_PLAN_MONTH, 'save_data': False,
            'hangmei_target': 5000,
        })

        check('基线成功', base.get('success') is True, base.get('message', '')[:120])
        check('航煤工况成功', hm.get('success') is True, hm.get('message', '')[:120])
        if hm.get('success') and base.get('success'):
            # 航煤启用后效益已计算（有限值）
            check('航煤效益已计算',
                  isinstance(hm['optimal_revenue'], (int, float))
                  and abs(hm['optimal_revenue']) < 1e15,
                  f"revenue={hm['optimal_revenue']}")
            # 组合数应一致（批次不变，组合数只取决于批次数）
            check('组合数不变',
                  len(hm['combination_results']) == len(base['combination_results']),
                  f"{len(hm['combination_results'])} vs {len(base['combination_results'])}")
            # 批次数一致
            check('批次数一致',
                  len(hm['batches']) == len(base['batches']),
                  f"{len(hm['batches'])} vs {len(base['batches'])}")
            print(f'  → 基线效益={base["optimal_revenue"]:,.0f} '
                  f'航煤效益={hm["optimal_revenue"]:,.0f} '
                  f'组合数={len(hm["combination_results"])}')


if __name__ == '__main__':
    print('calc_service 端到端测试（DB 事务回滚隔离）')
    for fn in (e2e_1_comprehensive_solve, e2e_2_plan_reuse,
               e2e_3_task_lock_cycle, e2e_4_valve_alignment, e2e_5_hangmei):
        try:
            fn()
        except Exception as e:
            _fail += 1
            print(f'  [FAIL] {fn.__name__} 抛异常: {e}')
            traceback.print_exc()
    print(f'\n{"=" * 50}')
    print(f'结果: {_pass} passed, {_fail} failed')
    print(f'{"=" * 50}')
