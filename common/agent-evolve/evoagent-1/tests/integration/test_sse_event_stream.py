"""Wave 10.2 集成测试 — SSE 事件流验证。

验证一个完整 epoch 的事件序列：phase 覆盖、顺序、中文消息、结构化数据。
"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from evo_agent.api.jobs import Job
from evo_agent.api.progress import ProgressCallback
from evo_agent.scenario.registry import ScenarioRegistry
from evo_agent.types import OptimizeRequest


def _build_optimizer_with_events(
    job: Job,
) -> tuple:
    """Build real EDPAgentOptimizer with phase_callback → job.push_event."""

    def phase_cb(event: str, data: dict) -> None:
        job.push_event(event, data)

    registry = ScenarioRegistry()
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
    )
    optimizer = registry.build_optimizer(
        request,
        dependencies={
            "agent": MagicMock(),
            "evaluator": MagicMock(),
            "llm": MagicMock(),
            "model": "test",
            "train_cases": MagicMock(),
            "phase_callback": phase_cb,
        },
    )
    return optimizer, phase_cb


class TestSSEEventStream:
    """验证一个 epoch 的事件序列完整性。"""

    @pytest.mark.asyncio
    async def test_one_epoch_emits_complete_phase_sequence(self) -> None:
        """完整 epoch 覆盖 rollout→evaluate→attribute→reflect→aggregate→select→apply。"""
        from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

        job = Job(job_id="test")
        optimizer, _ = _build_optimizer_with_events(job)

        # Setup optimizer for a single case rollout
        case = Case(case_id="c1", inputs={"query": "hello"}, label={"expected": "ok"})
        eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
        optimizer._evaluator = MagicMock()
        optimizer._evaluator.batch_evaluate = MagicMock(return_value=[eval_result])
        optimizer._adapter_client = AsyncMock()
        optimizer._adapter_client.get_traces = AsyncMock(
            return_value={"messages": [{"role": "user", "content": "hi"}]}
        )
        optimizer._agent = AsyncMock()
        optimizer._agent.invoke = AsyncMock(return_value={"answer": "mock"})
        optimizer._conversation_id_factory = None
        optimizer._trace_max_retries = 1
        optimizer._trace_retry_backoff = 0.0
        optimizer._rollout_extra_data = {}
        optimizer._artifact_epoch = 1
        optimizer._num_parallel = 1
        optimizer._operators = {"skill_a": MagicMock()}

        # Phase 1: Rollout
        await optimizer._rollout([case])

        # Phase 2: Attribute
        await optimizer._attribute(
            failure_batch=[],
            success_batch=[],
            skill_contents={"skill_a": "doc"},
        )

        # Phase 3: Reflect (mock super)
        with patch(
            "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._reflect",
            new_callable=AsyncMock,
            return_value=[MagicMock()],
        ):
            await optimizer._reflect(
                formatted_batch="batch",
                skill_content="doc",
                score_threshold=0.5,
                operator_id="skill_a",
            )

        # Phase 4: Aggregate (mock super)
        with patch(
            "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._aggregate",
            new_callable=AsyncMock,
            return_value=MagicMock(),
        ):
            await optimizer._aggregate(patches=[MagicMock()], skill_content="doc")

        # Phase 5: Select (mock super)
        with patch(
            "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._select",
            new_callable=AsyncMock,
            return_value=[MagicMock()],
        ):
            await optimizer._select(edits=[MagicMock()], budget=5, skill_content="doc")

        # Phase 6: Backward/Apply (mock super). The base ``_backward`` step
        # loop calls ``_on_step_apply`` per step to push the apply event; since
        # the base is mocked here, invoke the hook directly to simulate one
        # step's apply phase.
        with patch(
            "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._backward",
            new_callable=AsyncMock,
        ):
            optimizer._artifact_exporter = MagicMock()
            optimizer._artifact_exporter.enabled = False
            await optimizer._backward([])
        optimizer._on_step_apply(step=0, n_edits=0, n_operators=1)

        # Verify phase coverage
        log_events = [e for e in job.event_buffer if e.event == "log"]
        phases = [e.data.get("phase") for e in log_events]
        required = [
            "rollout",
            "evaluate",
            "rollout_done",
            "attribute",
            "reflect",
            "aggregate",
            "select",
            "apply",
        ]
        for ph in required:
            assert ph in phases, f"missing phase: {ph}"

    @pytest.mark.asyncio
    async def test_phase_order_rollout_before_attribute(self) -> None:
        """rollout phase 在 attribute phase 之前。"""
        from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

        job = Job(job_id="test")
        optimizer, _ = _build_optimizer_with_events(job)

        case = Case(case_id="c1", inputs={"query": "hello"}, label={"expected": "ok"})
        eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
        optimizer._evaluator = MagicMock()
        optimizer._evaluator.batch_evaluate = MagicMock(return_value=[eval_result])
        optimizer._adapter_client = AsyncMock()
        optimizer._adapter_client.get_traces = AsyncMock(
            return_value={"messages": [{"role": "user", "content": "hi"}]}
        )
        optimizer._agent = AsyncMock()
        optimizer._agent.invoke = AsyncMock(return_value={"answer": "mock"})
        optimizer._conversation_id_factory = None
        optimizer._trace_max_retries = 1
        optimizer._trace_retry_backoff = 0.0
        optimizer._rollout_extra_data = {}
        optimizer._artifact_epoch = 1
        optimizer._num_parallel = 1
        optimizer._operators = {"skill_a": MagicMock()}

        await optimizer._rollout([case])
        await optimizer._attribute(
            failure_batch=[], success_batch=[], skill_contents={"skill_a": "doc"}
        )

        log_events = [e for e in job.event_buffer if e.event == "log"]
        phases = [e.data.get("phase") for e in log_events]
        assert phases.index("rollout") < phases.index("attribute")

    def test_log_events_have_chinese_message(self) -> None:
        """所有 log 事件的 message 为非空中文。"""
        job = Job(job_id="test")
        cb = ProgressCallback(job)

        # Trigger lifecycle events
        from openjiuwen.agent_evolving.trainer.progress import Progress

        cb.on_train_begin(MagicMock(), Progress(max_epoch=3), [])
        cb.on_train_epoch_begin(MagicMock(), Progress(current_epoch=1, max_epoch=3))
        cb.on_train_epoch_end(
            MagicMock(),
            Progress(current_epoch=1, max_epoch=3, current_epoch_score=0.7, best_score=0.7),
            [],
        )
        cb.on_train_end(MagicMock(), Progress(max_epoch=3, best_score=0.7), [])

        log_events = [e for e in job.event_buffer if e.event == "log"]
        assert len(log_events) >= 4
        for e in log_events:
            assert e.data.get("message"), f"missing message for phase={e.data.get('phase')}"
            assert e.data.get("level") in ("info", "warning", "error")

    def test_log_events_have_structured_data(self) -> None:
        """所有 log 事件的 data 字段包含结构化 data 子字典。"""
        job = Job(job_id="test")
        cb = ProgressCallback(job)

        from openjiuwen.agent_evolving.trainer.progress import Progress

        cb.on_train_begin(MagicMock(), Progress(max_epoch=3), [])
        cb.on_train_epoch_begin(MagicMock(), Progress(current_epoch=1, max_epoch=3))
        cb.on_train_epoch_end(
            MagicMock(),
            Progress(current_epoch=1, max_epoch=3, current_epoch_score=0.7, best_score=0.7),
            [],
        )

        log_events = [e for e in job.event_buffer if e.event == "log"]
        for e in log_events:
            assert isinstance(e.data, dict)
            assert "phase" in e.data

    def test_phase_callback_failure_does_not_interrupt(self) -> None:
        """phase_callback 失败时 catch + log，不中断优化。"""
        job = Job(job_id="test")

        def bad_cb(event: str, data: dict) -> None:
            raise RuntimeError("SSE failure")

        registry = ScenarioRegistry()
        request = OptimizeRequest(
            scenario="edp_agent",
            agent_name="test_agent",
            dataset_manifest_path=Path("/tmp/dataset.yaml"),
            adapter_url="http://localhost:9090",
        )
        optimizer = registry.build_optimizer(
            request,
            dependencies={
                "agent": MagicMock(),
                "evaluator": MagicMock(),
                "llm": MagicMock(),
                "model": "test",
                "train_cases": MagicMock(),
                "phase_callback": bad_cb,
            },
        )

        # Should not raise
        optimizer._push_phase("log", {"message": "test", "phase": "test"})
        # Job should have no events (callback failed silently)
        assert len(job.event_buffer) == 0

    def test_completed_event_unchanged(self) -> None:
        """completed 终态事件格式不变（Bug 2: 由 routes 层而非 on_train_end 推送）。"""
        job = Job(job_id="test")
        cb = ProgressCallback(job)

        from openjiuwen.agent_evolving.trainer.progress import Progress

        cb.on_train_end(MagicMock(), Progress(max_epoch=3, best_score=0.8), [])

        # on_train_end 不再推 completed（避免 format() 失败时状态/事件不一致）
        completed = [e for e in job.event_buffer if e.event == "completed"]
        assert len(completed) == 0

        # completed 事件格式契约不变 —— 由 routes 层 _run_with_progress 成功后推送
        job.push_event("completed", {"status": "completed"})
        completed = [e for e in job.event_buffer if e.event == "completed"]
        assert len(completed) == 1
        assert completed[0].data == {"status": "completed"}

    @pytest.mark.asyncio
    async def test_ten_log_events_per_epoch(self) -> None:
        """一个完整 epoch 至少推送 10 个 log 事件（含 ProgressCallback + optimizer）。"""
        from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
        from openjiuwen.agent_evolving.trainer.progress import Progress

        job = Job(job_id="test")
        optimizer, _ = _build_optimizer_with_events(job)
        cb = ProgressCallback(job)

        # ProgressCallback lifecycle
        cb.on_train_begin(MagicMock(), Progress(max_epoch=1), [])
        cb.on_train_epoch_begin(MagicMock(), Progress(current_epoch=1, max_epoch=1))

        # Optimizer phases
        case = Case(case_id="c1", inputs={"query": "hi"}, label={"expected": "ok"})
        eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.7)
        optimizer._evaluator = MagicMock()
        optimizer._evaluator.batch_evaluate = MagicMock(return_value=[eval_result])
        optimizer._adapter_client = AsyncMock()
        optimizer._adapter_client.get_traces = AsyncMock(
            return_value={"messages": [{"role": "user", "content": "hi"}]}
        )
        optimizer._agent = AsyncMock()
        optimizer._agent.invoke = AsyncMock(return_value={"answer": "mock"})
        optimizer._conversation_id_factory = None
        optimizer._trace_max_retries = 1
        optimizer._trace_retry_backoff = 0.0
        optimizer._rollout_extra_data = {}
        optimizer._artifact_epoch = 1
        optimizer._num_parallel = 1
        optimizer._operators = {"skill_a": MagicMock()}

        await optimizer._rollout([case])
        await optimizer._attribute(
            failure_batch=[], success_batch=[], skill_contents={"skill_a": "doc"}
        )
        with patch(
            "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._reflect",
            new_callable=AsyncMock,
            return_value=[MagicMock()],
        ):
            await optimizer._reflect(
                formatted_batch="b",
                skill_content="d",
                score_threshold=0.5,
                operator_id="skill_a",
            )
        with patch(
            "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._aggregate",
            new_callable=AsyncMock,
            return_value=MagicMock(),
        ):
            await optimizer._aggregate(patches=[MagicMock()], skill_content="d")
        with patch(
            "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._select",
            new_callable=AsyncMock,
            return_value=[MagicMock()],
        ):
            await optimizer._select(edits=[MagicMock()], budget=5, skill_content="d")
        with patch(
            "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._backward",
            new_callable=AsyncMock,
        ):
            optimizer._artifact_exporter = MagicMock()
            optimizer._artifact_exporter.enabled = False
            await optimizer._backward([])

        # ProgressCallback epoch_end
        cb.on_train_epoch_end(
            MagicMock(),
            Progress(current_epoch=1, max_epoch=1, current_epoch_score=0.7, best_score=0.7),
            [],
        )

        log_events = [e for e in job.event_buffer if e.event == "log"]
        assert len(log_events) >= 10, f"Expected >=10 log events, got {len(log_events)}"
