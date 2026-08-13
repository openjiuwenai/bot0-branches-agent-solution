# -*- coding: utf-8 -*-
"""ValveSwitchPlanner 单元测试（从 planner.py 拆出后）。

验证拆分后行为不变：
  - enumerate_valve_switching 返回结构齐全（success/batches/combinations/...）
  - 批次数 ≥ 1、组合数 = 2 × 批次数（n 切换位置 × 2 初始模式）
  - 幂等：两次调用批次数与组合数一致
  - 空输入返回 success=False
"""
from calc_service.backend.data.scheduling_repo import SchedulingRepository
from calc_service.backend.scheduling.switch_planner import ValveSwitchPlanner


def _load_reuse_details():
    """加载 DB 中已存在的 PLAN-202601 明细（2026-01，8 批次）。"""
    repo = SchedulingRepository()
    details = repo.load_production_plan_details("PLAN-202601")
    assert details, "PLAN-202601 明细为空，测试前置失败"
    return details


def test_enumerate_structure():
    details = _load_reuse_details()
    result = ValveSwitchPlanner().enumerate_valve_switching(details)

    assert result['success'] is True
    assert {'success', 'batches', 'combinations', 'total_combinations', 'message'} <= set(result.keys())
    assert isinstance(result['batches'], list)
    assert isinstance(result['combinations'], list)


def test_combination_count_is_2x_batches():
    """规则：n 批次 → n 切换位置 × 2 初始模式 = 2n 组合。"""
    details = _load_reuse_details()
    result = ValveSwitchPlanner().enumerate_valve_switching(details)

    n_batches = len(result['batches'])
    n_combos = len(result['combinations'])
    assert n_batches >= 1
    assert n_combos == 2 * n_batches, f"期望 2×{n_batches}={2*n_batches} 组合, 实际 {n_combos}"
    assert result['total_combinations'] == n_combos


def test_idempotent():
    """两次调用结果一致（批次数 + 组合数）。"""
    details = _load_reuse_details()
    sp = ValveSwitchPlanner()
    r1 = sp.enumerate_valve_switching(details)
    r2 = sp.enumerate_valve_switching(details)

    assert len(r1['batches']) == len(r2['batches'])
    assert len(r1['combinations']) == len(r2['combinations'])
    # batch_id 序列一致
    assert [b['batch_id'] for b in r1['batches']] == [b['batch_id'] for b in r2['batches']]


def test_empty_details_returns_failure():
    result = ValveSwitchPlanner().enumerate_valve_switching([])
    assert result['success'] is False


def test_batch_dict_keys():
    """批次 dict 含识别所需字段。"""
    details = _load_reuse_details()
    result = ValveSwitchPlanner().enumerate_valve_switching(details)
    for batch in result['batches']:
        assert {'batch_id', 'start_day', 'end_day', 'crude_type',
                'total_input', 'daily_inputs', 'days'} <= set(batch.keys())
        assert batch['start_day'] <= batch['end_day']
        assert batch['total_input'] > 0
