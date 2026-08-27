# coding: utf-8

"""AgentHandler SPI 契约收口判据（Feat-Func-002b §2.3）。

权威五方法（对齐 agent-runtime-java `spec/spi/AgentHandler`）：
    query / stream_query / start / stop / clear_session
+ python 本地增补（非权威，entry_points 装配面需要）：agent_id / priority / is_healthy

**不得有**：
- `resume` —— 续接经"构造承载 ResumeInput 的 ServeRequest 重走 stream_query"
  （对齐 java `buildResumeRequest`；FEAT-008:151 禁止在 FEAT-008 名义下新增 SPI）；
- `result_adapter` / `StreamAdapter` —— 原生流→QueryChunk 翻译是 adapter **内部实现**，
  不进契约面（决策A）；
- `cancel` —— 取消经消费侧停止迭代 / `aclose()` 传导（002 §2.3）。

裸环境可跑（ports 洋葱内圈，零框架依赖）。
"""
from __future__ import annotations

import agent_runtime.ports.handler as handler_mod
from agent_runtime.ports.handler import AgentHandler


def _members() -> set[str]:
    return set(getattr(AgentHandler, "__annotations__", {})) | {
        n for n in dir(AgentHandler) if not n.startswith("_")
    }


def test_authoritative_five_methods_present():
    """权威五方法齐全（对齐 java spec/spi/AgentHandler）。"""
    for m in ("query", "stream_query", "start", "stop", "clear_session"):
        assert hasattr(AgentHandler, m), f"权威方法 {m} 缺失"


def test_python_local_additions_present():
    """python 本地增补：装配面需要（entry_points 发现 + priority 选举 + readiness）。"""
    members = _members()
    for m in ("agent_id", "priority", "is_healthy"):
        assert m in members, f"本地增补 {m} 缺失"


def test_no_resume_method():
    """续接不新增 SPI 方法（FEAT-008:151）——走 stream_query 路径。"""
    assert not hasattr(AgentHandler, "resume"), "resume 应删除：续接经 ServeRequest 重走 stream_query"


def test_no_result_adapter_and_no_stream_adapter_port():
    """翻译件不进契约面（决策A）：无 result_adapter，ports 不导出 StreamAdapter。"""
    assert not hasattr(AgentHandler, "result_adapter")
    assert not hasattr(handler_mod, "StreamAdapter"), "StreamAdapter 是 adapter 内部工具，非 port"


def test_no_cancel_method():
    """取消经消费侧停止迭代 / aclose() 传导（002 §2.3），不另设 cancel 方法。"""
    assert not hasattr(AgentHandler, "cancel")


def test_no_execute_method():
    """执行入口是 stream_query，不是旧 execute。"""
    assert not hasattr(AgentHandler, "execute")


def test_old_handler_name_gone():
    """旧名 `AgentRuntimeHandler` 已废（权威名 `AgentHandler`）。"""
    assert not hasattr(handler_mod, "AgentRuntime" + "Handler")
    assert hasattr(handler_mod, "AgentHandler")
