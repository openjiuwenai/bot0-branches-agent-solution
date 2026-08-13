# -*- coding: utf-8 -*-
"""service 层冒烟测试。

直接调用 SolveService / YieldService（不经 HTTP），验证下沉后的编排逻辑：
  - YieldService.build_product_yields() 返回 crude_type→device→mode→products 层级。
  - SolveService.comprehensive_solve() reuse 路径产出非空批次/组合/正效益。

DB 化后的隔离：原 Excel 版用文件备份/恢复隔离写回副作用；现统一改用
db_guard —— 开一条连接并起外层事务，把 SessionLocal 绑到该连接
（join_transaction_mode="create_savepoint"），测试结束 rollback，
即便 save_data 误开也不会污染 solve_db。测试本身仍传 save_data=False。
"""
import pytest
from sqlalchemy.orm import sessionmaker

from calc_service.backend.data import db as dbmod
from calc_service.backend.data import refinery_repo as refinery_repo_mod
from calc_service.backend.data import scheduling_repo as scheduling_repo_mod
from calc_service.backend.service import SolveService, YieldService


@pytest.fixture
def db_guard(monkeypatch):
    """DB 事务回滚隔离：SessionLocal 绑到带外层事务的连接，结束 rollback。

    三处 SessionLocal 引用（db / refinery_repo / scheduling_repo 模块内
    各自 import 的同名符号）全部替换，确保 repo 内部自开的 session 也落入
    该事务。join_transaction_mode="create_savepoint" 使 session.commit()
    退化为 SAVEPOINT，外层事务始终由本 fixture 掌控回滚。
    """
    conn = dbmod.engine.connect()
    trans = conn.begin()
    test_session = sessionmaker(bind=conn, join_transaction_mode="create_savepoint")
    monkeypatch.setattr(dbmod, "SessionLocal", test_session)
    monkeypatch.setattr(refinery_repo_mod, "SessionLocal", test_session)
    monkeypatch.setattr(scheduling_repo_mod, "SessionLocal", test_session)
    try:
        yield
    finally:
        trans.rollback()
        conn.close()


def test_yield_service_build_product_yields():
    data = YieldService().build_product_yields()
    assert "crude_types" in data
    assert isinstance(data["crude_types"], list)
    assert len(data["crude_types"]) > 0

    for crude in data["crude_types"]:
        assert {"crude_type_id", "crude_type_name", "devices"} <= set(crude.keys())
        for device in crude["devices"]:
            assert {"device_id", "operation_modes"} <= set(device.keys())
            for mode in device["operation_modes"]:
                assert {"mode_id", "mode_name", "products"} <= set(mode.keys())
                for prod in mode["products"]:
                    # 收率为小数（<=1），且关键字段齐全
                    assert {"product_id", "product_name", "yield_rate",
                            "yield_rate_source", "price", "is_final"} <= set(prod.keys())
                    assert prod["yield_rate"] <= 1.0, (
                        f"{prod['product_id']} 收率 {prod['yield_rate']} 应为小数（<=1），"
                        "若 >1 说明单位约定未统一"
                    )


def test_solve_service_comprehensive_solve_reuse(db_guard):
    result = SolveService().comprehensive_solve(
        plan_month="2026-01",
        production_plans_input=[],
        monthly_crude_input=None,
        blend_mode=False,
        save_data=False,
        hangmei_target=None,
    )
    assert result["success"] is True
    assert result["batches"], "应识别到批次"
    assert result["total_combinations"] > 0
    assert result["optimal_combination"] is not None
    assert result["optimal_revenue"] > 0
    assert "经济效益分析说明" in result["economic_explanation"]


def test_solve_service_optimize_valve(db_guard):
    """optimize_valve 不 clean NaN、不写 load_rate，但仍需隔离（只读 reuse 计划）。"""
    result = SolveService().optimize_valve("PLAN-202601")
    assert result["success"] is True
    assert result["batches"], "应识别到批次"
    assert result["total_combinations"] > 0
    # optimize_valve 用默认价（plan_month=None），效益符号不与 comprehensive_solve 比较
    assert result["optimal_revenue"] != float("inf")


def test_solve_service_missing_plan_returns_failure():
    result = SolveService().optimize_valve("PLAN-NOTEXIST-9999")
    assert result["success"] is False
    assert "不存在" in result["message"]


def test_solve_service_comprehensive_solve_missing_input():
    """comprehensive_solve 无已有计划且缺 production_plans_input 时早退（_SolveAbort 路径）。

    选用不存在的计划月份强制走"生成"分支；production_plans_input 为空应直接返回失败，
    message 与原内联 return 一致。该路径在生成前早退，不触发 DB 写回，无需 db_guard。
    """
    result = SolveService().comprehensive_solve(
        plan_month="2099-12",
        production_plans_input=[],
        monthly_crude_input=None,
        blend_mode=False,
        save_data=False,
        hangmei_target=None,
    )
    assert result["success"] is False
    assert "production_plans_input" in result["message"]
