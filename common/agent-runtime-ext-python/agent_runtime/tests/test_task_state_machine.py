# coding: utf-8

"""Feat-Func-008b 编排层 Task 状态守卫（纯 domain，裸环境）。

状态机守卫是「单等待点推进一次」的落地机制（§4.2.3）——不另设去重键。
"""
from __future__ import annotations

from agent_runtime.domain.task.state_machine import TaskState, check_transition, is_terminal


def test_terminal_states():
    """等待输入是**非终态**，可长时间挂起（权威 `FEAT-008:42` MUST）。

    权威同条另明确本特性不为该状态设置存活时间——超时与清理归任务状态缓存特性。
    本判据锁的是前半句：它一旦被划进终态，长时挂起、等待期间查询与后续续接
    三件事会同时失效，而失效方式是「任务看起来已经结束」。
    """
    for s in (TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELED, TaskState.REJECTED):
        assert is_terminal(s)
    for s in (TaskState.SUBMITTED, TaskState.WORKING, TaskState.INPUT_REQUIRED, TaskState.AUTH_REQUIRED):
        assert not is_terminal(s)


def test_suspend_and_resume_round_trip_legal():
    """同一任务支持多轮「执行中↔等待输入」往返（权威 `FEAT-008:41` MUST）。

    权威要求同一任务须支持顺序发生多轮往返，每一轮是否再次等待由智能体决定。
    状态机守卫是这条的落地机制——它单向放行时，第二轮中断会被判为非法转移。
    """
    # 多轮 WORKING↔INPUT_REQUIRED（FEAT-008 §4.2）
    assert check_transition(TaskState.WORKING, TaskState.INPUT_REQUIRED)
    assert check_transition(TaskState.INPUT_REQUIRED, TaskState.WORKING)


def test_non_terminal_can_enter_terminal():
    assert check_transition(TaskState.WORKING, TaskState.COMPLETED)
    assert check_transition(TaskState.WORKING, TaskState.FAILED)
    assert check_transition(TaskState.INPUT_REQUIRED, TaskState.CANCELED)
    assert check_transition(TaskState.SUBMITTED, TaskState.REJECTED)


def test_terminal_cannot_transition_out():
    # 终态不可外出：幂等/防抢占基石（§4.3）
    assert not check_transition(TaskState.COMPLETED, TaskState.WORKING)
    assert not check_transition(TaskState.CANCELED, TaskState.WORKING)
    assert not check_transition(TaskState.FAILED, TaskState.INPUT_REQUIRED)


def test_input_required_cannot_directly_resuspend():
    # INPUT_REQUIRED 须先回 WORKING 再挂起，不能直接再挂起
    assert not check_transition(TaskState.INPUT_REQUIRED, TaskState.INPUT_REQUIRED)
