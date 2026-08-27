# coding: utf-8

"""远端批次三级准入的判据。

## 它锁什么

`Feat-Func-004b` §4.5 定的三级：并发上限 → 队列上限 → 跳过清单，逐级下落。
可同时在途的上限是**并发预算加队列上限**，超出者截断进跳过清单。

这三级的对外可见后果是**结果集的形状**：存量给前 N 个结果加跳过项，若本版给全部
结果，调用方拿到的成员数就不一样。兼容面清单把它登记为对外面。

## 为什么单独立一份

三级准入曾在兼容面清单里被登记为「实现有偏离：只有一个信号量，无队列上限、
无跳过清单、无截断」。实现后来补齐了，而那条登记停在旧状态——**没有判据锁住它，
文档与实现就会各自漂**。本文件补上那道锁。

## 边界取值逐个验

准入的临界点是 `budget + queue_limit`。**恰好等于上限、比上限多一个、队列关闭**
三种取值各自验一次——只验中间值验不到边界，而边界正是这类判定最容易写错的地方。
"""
from __future__ import annotations

import pytest

from agent_runtime.application.remote_batch import RemoteBatchAdmission


def _members(count: int) -> list[str]:
    return [f"m{i}" for i in range(count)]


def test_all_admitted_when_within_concurrency() -> None:
    """成员数不超并发预算时全部受理，跳过清单为空。"""
    accepted, skipped = RemoteBatchAdmission.admit(_members(3), budget=5, queue_limit=0)
    assert accepted == ["m0", "m1", "m2"]
    assert skipped == []


def test_queue_extends_the_admitted_window() -> None:
    """并发满了但队列未满时仍受理——排队是发起与跳过之间新增的那一层。"""
    accepted, skipped = RemoteBatchAdmission.admit(_members(6), budget=2, queue_limit=4)
    assert len(accepted) == 6, "并发 2 加队列 4 应受理 6 个"
    assert skipped == []


def test_beyond_queue_falls_into_the_skip_list() -> None:
    """超出并发与队列之和的成员进跳过清单，**且保持原有顺序**。

    顺序要紧：存量给的是「前 N 个」，若本版取的不是前 N 个，同一批输入两侧受理
    的成员就不是同一批。
    """
    accepted, skipped = RemoteBatchAdmission.admit(_members(10), budget=2, queue_limit=3)
    assert accepted == ["m0", "m1", "m2", "m3", "m4"]
    assert skipped == ["m5", "m6", "m7", "m8", "m9"]


def test_exactly_at_the_limit_admits_all() -> None:
    """成员数恰好等于上限时全部受理——**边界不该少收一个**。"""
    accepted, skipped = RemoteBatchAdmission.admit(_members(5), budget=2, queue_limit=3)
    assert len(accepted) == 5
    assert skipped == []


def test_one_over_the_limit_skips_exactly_one() -> None:
    """比上限多一个时恰好跳过一个——**边界不该多收也不该多跳**。"""
    accepted, skipped = RemoteBatchAdmission.admit(_members(6), budget=2, queue_limit=3)
    assert len(accepted) == 5
    assert len(skipped) == 1


def test_queue_limit_zero_closes_the_queue_tier() -> None:
    """队列上限配 0 时排队层整体关闭，退回「并发上限 + 跳过清单」两级。

    这是详设明写的形态，也是与存量对齐的那一档：存量没有排队层。
    """
    accepted, skipped = RemoteBatchAdmission.admit(_members(5), budget=2, queue_limit=0)
    assert accepted == ["m0", "m1"]
    assert skipped == ["m2", "m3", "m4"]


def test_empty_batch_yields_two_empty_lists() -> None:
    assert RemoteBatchAdmission.admit([], budget=3, queue_limit=3) == ([], [])


@pytest.mark.parametrize("budget,queue_limit", [(0, 0), (0, 3)])
def test_zero_budget_admits_only_what_the_queue_allows(budget: int, queue_limit: int) -> None:
    """并发预算为 0 时，受理数只由队列上限决定。

    这不是一个会配的取值，但判定式不该在它上面出意外——`budget + queue_limit`
    是唯一的判据，两个分项各自为 0 都应落在同一条式子上。
    """
    accepted, skipped = RemoteBatchAdmission.admit(_members(4), budget=budget, queue_limit=queue_limit)
    assert len(accepted) == min(4, budget + queue_limit)
    assert len(accepted) + len(skipped) == 4, "受理与跳过之和必须等于成员总数，一个都不能丢"


def test_no_member_is_lost() -> None:
    """受理与跳过之和恒等于输入成员数。

    **这条是三级准入最要紧的不变量**：丢一个成员，调用方既拿不到它的结果、也拿不到
    它的跳过项，那个成员就静默消失了。
    """
    for total in (0, 1, 7, 20):
        for budget in (0, 1, 5):
            for queue_limit in (0, 2, 9):
                accepted, skipped = RemoteBatchAdmission.admit(
                    _members(total), budget=budget, queue_limit=queue_limit
                )
                assert len(accepted) + len(skipped) == total, (
                    f"成员丢失：total={total} budget={budget} queue={queue_limit}"
                )
