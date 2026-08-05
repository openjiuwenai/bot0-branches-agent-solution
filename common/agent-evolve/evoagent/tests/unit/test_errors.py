"""FatalOptimizationError marker + ManagedDocApplyError 结构化异常测试。"""

from __future__ import annotations

import pytest

from evo_agent.errors import FatalOptimizationError, ManagedDocApplyError


class TestFatalOptimizationError:
    def test_is_exception_subclass(self) -> None:
        """FatalOptimizationError 是通用 fatal marker，继承 Exception。"""
        assert issubclass(FatalOptimizationError, Exception)

    def test_raisable_and_catchable_as_marker(self) -> None:
        """fatal marker 可被 except FatalOptimizationError 捕获（ComposedCallbacks 据此穿透）。"""
        with pytest.raises(FatalOptimizationError):
            raise FatalOptimizationError("boom")


class TestManagedDocApplyError:
    def test_inherits_fatal_marker(self) -> None:
        """ManagedDocApplyError 继承 FatalOptimizationError——
        ComposedCallbacks 对该 marker 重新抛出。"""
        assert issubclass(ManagedDocApplyError, FatalOptimizationError)
        assert issubclass(ManagedDocApplyError, Exception)

    def test_catchable_as_fatal_marker(self) -> None:
        """managed-doc apply 失败可经 except FatalOptimizationError 捕获（marker 穿透）。"""
        with pytest.raises(FatalOptimizationError):
            raise ManagedDocApplyError(
                agent_name="agent",
                doc_kind="agent_rule",
                task_id=None,
                phase="post",
                adapter_error="connection refused",
            )

    def test_structured_fields_populated(self) -> None:
        """携带 agent_name/doc_kind/task_id/phase/adapter_error 结构化字段。"""
        err = ManagedDocApplyError(
            agent_name="edp_agent",
            doc_kind="agent_rule",
            task_id="task-123",
            phase="poll",
            adapter_error="task FAILED: OOM",
        )
        assert err.agent_name == "edp_agent"
        assert err.doc_kind == "agent_rule"
        assert err.task_id == "task-123"
        assert err.phase == "poll"
        assert err.adapter_error == "task FAILED: OOM"

    def test_task_id_optional(self) -> None:
        """POST 阶段失败（尚未拿到 task_id）时 task_id 为 None。"""
        err = ManagedDocApplyError(
            agent_name="edp_agent",
            doc_kind="agent_rule",
            task_id=None,
            phase="post",
            adapter_error="HTTP 502",
        )
        assert err.task_id is None

    def test_str_contains_structured_fields(self) -> None:
        """str(err) 含结构化字段，便于日志/artifact 记录（不裸抛字符串）。"""
        err = ManagedDocApplyError(
            agent_name="edp_agent",
            doc_kind="agent_rule",
            task_id="task-123",
            phase="poll",
            adapter_error="task FAILED: OOM",
        )
        msg = str(err)
        assert "edp_agent" in msg
        assert "agent_rule" in msg
        assert "task-123" in msg
        assert "poll" in msg
        assert "task FAILED: OOM" in msg

    def test_raise_propagates_through_callback_boundary(self) -> None:
        """模拟 ComposedCallbacks 行为：对 FatalOptimizationError 子类必须重新抛出。"""

        def callback() -> None:
            raise ManagedDocApplyError(
                agent_name="a",
                doc_kind="k",
                task_id=None,
                phase="post",
                adapter_error="err",
            )

        # ComposedCallbacks：普通异常吞 + log；fatal marker 重新抛出。
        with pytest.raises(ManagedDocApplyError):
            try:
                callback()
            except FatalOptimizationError:
                # fatal marker 穿透：重新抛出，不吞
                raise
            except Exception:
                # 普通异常 best-effort 吞掉（此处不应进入）
                pass
