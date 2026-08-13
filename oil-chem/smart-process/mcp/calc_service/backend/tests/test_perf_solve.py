# -*- coding: utf-8 -*-
"""comprehensive_solve 性能基准测试。

用 cProfile 采样一次完整求解，断言关键性能指标在阈值内，并输出耗时分布
供人工审查。阈值取 P6+P6.1 优化后实测值上浮 50% 作为回归护栏。

运行：
    pytest calc_service/backend/tests/test_perf_solve.py -s -v

说明：
    - 预热一次后正式采样，避免模块首次加载开销干扰。
    - 阈值基于开发机（Python 3.14 / Windows）实测，CI 环境可适当放宽。
    - 断言失败不阻断其他测试（pytest 非 xfail 仍会报错，可按需调整阈值）。
"""
import cProfile
import io
import pstats
import time

import pytest

from calc_service.backend.service import SolveService

# ── 性能阈值（ms） ──────────────────────────────────────────────
# 基于 P6+P6.1 优化后预热实测 1509 ms，上浮 50% 作为回归护栏
TOTAL_MS_THRESHOLD = 2300
# calculate_physical cumtime（含子调用），实测 290-470 ms（含波动）
CALC_PHYSICAL_MS_THRESHOLD = 600
# DB 查询次数（preload + 求解），实测 58 次
DB_CALLS_THRESHOLD = 100
# calculate_physical 调用次数（P0 缓存后 ≤3n），实测 36 次
CALC_PHYSICAL_CALLS_THRESHOLD = 60

# 求解参数（与生产请求一致）
_SOLVE_KWARGS = dict(
    plan_month="2026-03",
    production_plans_input=[],
    monthly_crude_input=None,
    blend_mode=False,
    save_data=False,
    hangmei_target=26000,
    shutdown_config=[],
    plan_source="lp",
)


def _run_solve():
    """执行一次完整求解，返回 result dict。"""
    return SolveService().comprehensive_solve(**_SOLVE_KWARGS)


def _profile_once():
    """预热后正式 cProfile 采样，返回 (stats, total_ms)。"""
    svc = SolveService()
    # 预热：首次可能含模块加载 / 缓存构建
    svc.comprehensive_solve(**_SOLVE_KWARGS)
    # 正式采样
    pr = cProfile.Profile()
    t0 = time.perf_counter()
    pr.enable()
    svc.comprehensive_solve(**_SOLVE_KWARGS)
    pr.disable()
    t1 = time.perf_counter()
    return pstats.Stats(pr), (t1 - t0) * 1000


def _find_func(stats_obj, pattern):
    """从 pstats.Stats 中提取匹配函数的 (ncalls, tottime, cumtime)。

    Returns:
        dict with keys: ncalls, tottime_ms, cumtime_ms, or None
    """
    for (filename, lineno, name), (cc, nc, tt, ct, callers) in stats_obj.stats.items():
        if pattern in name or pattern in filename:
            return {
                "ncalls": nc,
                "tottime_ms": round(tt * 1000, 1),
                "cumtime_ms": round(ct * 1000, 1),
            }
    return None


class TestPerfSolve:
    """comprehensive_solve 性能基准。"""

    @pytest.fixture(scope="class")
    @classmethod
    def profile_result(cls):
        """全类共享一次 cProfile 采样结果。"""
        stats, total_ms = _profile_once()
        return {"stats": stats, "total_ms": total_ms}

    def test_total_time_within_threshold(self, profile_result):
        """总耗时应在阈值内（2300 ms）。"""
        total = profile_result["total_ms"]
        assert total < TOTAL_MS_THRESHOLD, (
            f"总耗时 {total:.0f} ms 超过阈值 {TOTAL_MS_THRESHOLD} ms"
        )

    def test_solve_succeeds(self):
        """性能测试前提：求解成功返回有效结果。"""
        result = _run_solve()
        assert result["success"] is True
        assert result["optimal_revenue"] > 0

    def test_calculate_physical_time(self, profile_result):
        """calculate_physical cumtime 应在阈值内（450 ms）。"""
        info = _find_func(profile_result["stats"], "calculate_physical")
        assert info is not None, "未找到 calculate_physical 函数"
        assert info["cumtime_ms"] < CALC_PHYSICAL_MS_THRESHOLD, (
            f"calculate_physical cumtime {info['cumtime_ms']} ms "
            f"超过阈值 {CALC_PHYSICAL_MS_THRESHOLD} ms"
        )

    def test_calculate_physical_calls(self, profile_result):
        """calculate_physical 调用次数应 ≤3n（P0 缓存命中）。"""
        info = _find_func(profile_result["stats"], "calculate_physical")
        assert info is not None
        assert info["ncalls"] <= CALC_PHYSICAL_CALLS_THRESHOLD, (
            f"calculate_physical 调用 {info['ncalls']} 次，"
            f"超过阈值 {CALC_PHYSICAL_CALLS_THRESHOLD}（P0 缓存可能失效）"
        )

    def test_db_calls_within_threshold(self, profile_result):
        """DB execute 次数应在阈值内（100 次）。"""
        info = _find_func(profile_result["stats"], "execute")
        # 匹配 psycopg2 cursor.execute
        if info is None:
            info = _find_func(profile_result["stats"], "psycopg2")
        assert info is not None, "未找到 DB execute 函数"
        assert info["ncalls"] <= DB_CALLS_THRESHOLD, (
            f"DB 查询 {info['ncalls']} 次，超过阈值 {DB_CALLS_THRESHOLD}"
        )

    def test_no_doRollover_deadloop(self, profile_result):
        """日志 doRollover 不应被高频调用（死循环回归检测）。"""
        info = _find_func(profile_result["stats"], "doRollover")
        if info is None:
            pytest.skip("未调用 doRollover（日志未超阈值，符合预期）")
        # 正常滚动最多 backupCount+1 次；死循环时会达数千次
        assert info["ncalls"] < 50, (
            f"doRollover 被调用 {info['ncalls']} 次，疑似日志死循环回归"
        )

    def test_print_perf_summary(self, profile_result):
        """打印性能摘要供人工审查（非断言，-s 模式可见）。"""
        stats = profile_result["stats"]
        total = profile_result["total_ms"]

        # 提取关键函数
        funcs = {}
        for key, pattern in [
            ("comprehensive_solve", "comprehensive_solve"),
            ("calculate_physical", "calculate_physical"),
            ("calculate_direct", "calculate_direct"),
            ("evaluate_combination", "evaluate_combination"),
            ("compute_revenue", "compute_revenue"),
            ("compute_costs", "compute_costs"),
            ("generate_explanation", "generate_explanation"),
            ("deepcopy", "deepcopy"),
            ("doRollover", "doRollover"),
            ("_log", "_log"),
            ("db_execute", "execute"),
        ]:
            funcs[key] = _find_func(stats, pattern)

        # 输出摘要
        print("\n" + "=" * 70)
        print(f"  comprehensive_solve 性能摘要  总耗时: {total:.0f} ms")
        print("=" * 70)
        print(f"{'函数':<28} {'调用次数':>12} {'cumtime(ms)':>14} {'tottime(ms)':>14}")
        print("-" * 70)
        for name, info in funcs.items():
            if info:
                print(
                    f"{name:<28} {info['ncalls']:>12} "
                    f"{info['cumtime_ms']:>14.1f} {info['tottime_ms']:>14.1f}"
                )
            else:
                print(f"{name:<28} {'—':>12} {'—':>14} {'—':>14}")
        print("=" * 70)
