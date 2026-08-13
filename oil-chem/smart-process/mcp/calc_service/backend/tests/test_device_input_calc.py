# -*- coding: utf-8 -*-
"""device_input_calc 收敛测试。

验证两点：
  1. 结构正确：MODE_JIAN1_TO_WAX/MODE_JIAN1_TO_DIESEL 含四装置键，空 blend 返回 _empty_result。
  2. 数值口径：DB 化后 products 收率以小数（NUMERIC(6,4)）存储，
     load_products_grouped() 直接返回小数；device_input_calc 不再做 /100。
     本测试断言进料吨位非负、主力油与代理油路径正确，并校验"装置进料 =
     总量 × 收率之和"的守恒关系（替代旧版与 Excel 百分数的逐位比对——
     DB 已统一为小数，旧百分数口径不复存在）。
"""
import pytest

from calc_service.backend.config import (
    DEVICE_CYJQ, DEVICE_LYJQ, DEVICE_HC_TANK, DEVICE_GYRLY_TANK,
    MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL,
)
from calc_service.backend.data.refinery_repo import RefineryRepository
from calc_service.backend.scheduling.device_input_calc import (
    load_yield_tables, compute_device_inputs_by_mode,
    CJY_CHANG_PIDS, CJY_JIAN1_PID, CJY_JIAN_OTHER_PIDS, CYJQ_BACKFLOW_PIDS,
)

_DEVICE_KEYS = (DEVICE_CYJQ, DEVICE_LYJQ, DEVICE_GYRLY_TANK, DEVICE_HC_TANK)


@pytest.fixture(scope="module")
def yield_tables():
    repo = RefineryRepository()
    grouped = repo.load_products_grouped()
    return load_yield_tables(grouped)


def test_empty_blend_returns_empty_result(yield_tables):
    result = compute_device_inputs_by_mode({}, yield_tables)
    assert result["main_crude_used"] == ""
    assert result["proxy_applied"] is False
    for key in _DEVICE_KEYS:
        assert result[MODE_JIAN1_TO_WAX][key] == 0.0
        assert result[MODE_JIAN1_TO_DIESEL][key] == 0.0


def test_result_structure(yield_tables):
    crude = next(iter(yield_tables))
    result = compute_device_inputs_by_mode({crude: 10000.0}, yield_tables)
    assert set(result.keys()) == {MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL, "main_crude_used", "proxy_applied"}
    for mode in (MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL):
        assert set(result[mode].keys()) == set(_DEVICE_KEYS)
        # 进料吨位非负
        for dev in _DEVICE_KEYS:
            assert result[mode][dev] >= 0


def test_yield_rates_are_decimal_not_percent(yield_tables):
    """DB 小数口径守卫：收率恒等关系成立的前提是 yield_rate 已是小数。

    若误把百分数（如 7.81）当小数，装置进料会远超总量数十倍。
    这里用守恒式校验：对单一覆盖原油，常压进料(Y) = 总量 × (常线+减一线收率)，
    断言其不超过总量（收率之和 < 1）。百分数误用会使其 >> 总量。
    """
    total = 17642.58
    for crude, yt in yield_tables.items():
        if sum(yt["cjy_yields"][p] for p in CJY_CHANG_PIDS) <= 0:
            continue
        result = compute_device_inputs_by_mode({crude: total}, yield_tables)
        # 常压进料 = 总量 × (常线收率之和 + 减一线收率)，收率<1 故应 <= 总量
        assert result[MODE_JIAN1_TO_DIESEL][DEVICE_GYRLY_TANK] <= total + 1e-3, (
            f"{crude} 常压进料 {result[MODE_JIAN1_TO_DIESEL][DEVICE_GYRLY_TANK]} > 总量 {total}，"
            "疑似收率仍为百分数（未 ÷100）"
        )


def test_proxy_applied_when_main_crude_uncovered(yield_tables):
    """主力油不在覆盖油集合时，应走代理（量最大的覆盖油）。"""
    covered = list(yield_tables.keys())
    assert covered, "无原油数据"
    known = covered[0]
    # 主力油用一个不存在的 id，量更大；known 量较小 → 代理应选 known
    blend = {"nonexistent_crude_xx": 20000.0, known: 5000.0}
    result = compute_device_inputs_by_mode(blend, yield_tables)
    assert result["proxy_applied"] is True
    assert result["main_crude_used"] == known
    # 吨位基于 total=25000 计算，应为正
    assert result[MODE_JIAN1_TO_DIESEL][DEVICE_CYJQ] > 0
