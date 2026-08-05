"""Managed-document cancellation rollback behavior."""

import asyncio
import hashlib
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from evo_agent.adapter_client.types import ManagedDocSnapshot, TaskState, UpdateStarted
from evo_agent.api.app import app
from evo_agent.api.jobs import JobManager, JobStatus
from evo_agent.api.routes import optimize as optimize_routes
from evo_agent.cancellation import CancellationRequestedError, CancellationToken
from evo_agent.config import EvolveConfig
from evo_agent.errors import CancelRollbackError, ManagedDocApplyError
from evo_agent.optimizer_runner import (
    rollback_managed_doc,
    run_optimization_with_cancellation_recovery,
)
from evo_agent.types import OptimizeRequest


def test_rollback_restores_and_confirms_job_start_baseline() -> None:
    baseline = "# job-start baseline"
    revision = hashlib.sha256(baseline.encode()).hexdigest()

    class Adapter:
        def start_managed_doc_update_sync(
            self,
            kind: str,
            content: str,
            *,
            request_timeout: float | None = None,
        ) -> UpdateStarted:
            assert kind == "agent_rule"
            assert content == baseline
            return UpdateStarted(task_id="rollback-task")

        def get_managed_doc_task_sync(
            self, task_id: str, *, request_timeout: float | None = None
        ) -> TaskState:
            return TaskState(
                status="SUCCEEDED",
                task_id=task_id,
                revision=revision,
                pending_apply=False,
                last_error=None,
                attempts=1,
                down_seen=True,
                created_at=None,
                updated_at=None,
            )

        def get_managed_doc_sync(
            self, kind: str, *, request_timeout: float | None = None
        ) -> ManagedDocSnapshot:
            return ManagedDocSnapshot(
                content=baseline,
                file_revision=revision,
                applied_revision=revision,
                pending_apply=False,
                apply_mode="restart",
                max_task_seconds=60.0,
            )

    adapter = Adapter()
    phases: list[str] = []

    result = rollback_managed_doc(
        adapter_client=adapter,
        doc_kind="agent_rule",
        baseline_content=baseline,
        baseline_revision=revision,
        deadline=120.0,
        phase_callback=lambda _event, data: phases.append(data["phase"]),
    )

    assert result.task_id == "rollback-task"
    assert phases == ["rollback_started", "rollback_completed"]


def test_rollback_revision_mismatch_fails_with_stable_code() -> None:
    baseline = "# baseline"
    revision = hashlib.sha256(baseline.encode()).hexdigest()

    class Adapter:
        def start_managed_doc_update_sync(self, *_args: Any, **_kwargs: Any) -> UpdateStarted:
            return UpdateStarted(task_id="rollback-task")

        def get_managed_doc_task_sync(self, task_id: str, **_kwargs: Any) -> TaskState:
            return TaskState(
                status="SUCCEEDED",
                task_id=task_id,
                revision=revision,
                pending_apply=False,
                last_error=None,
                attempts=1,
                down_seen=True,
                created_at=None,
                updated_at=None,
            )

        def get_managed_doc_sync(self, *_args: Any, **_kwargs: Any) -> ManagedDocSnapshot:
            return ManagedDocSnapshot(
                content="# diverged",
                file_revision="other",
                applied_revision="other",
                pending_apply=False,
                apply_mode="restart",
                max_task_seconds=60.0,
            )

    with pytest.raises(CancelRollbackError) as exc_info:
        rollback_managed_doc(
            adapter_client=Adapter(),  # type: ignore[arg-type]
            doc_kind="agent_rule",
            baseline_content=baseline,
            baseline_revision=revision,
            deadline=120.0,
        )

    assert exc_info.value.code == "CANCEL_ROLLBACK_FAILED"


def test_rollback_snapshot_transport_failure_is_mapped_to_safe_stable_code() -> None:
    baseline = "# baseline"
    revision = hashlib.sha256(baseline.encode()).hexdigest()

    class Adapter:
        def start_managed_doc_update_sync(self, *_args: Any, **_kwargs: Any) -> UpdateStarted:
            return UpdateStarted(task_id="rollback-task")

        def get_managed_doc_task_sync(self, task_id: str, **_kwargs: Any) -> TaskState:
            return TaskState(
                status="SUCCEEDED",
                task_id=task_id,
                revision=revision,
                pending_apply=False,
                last_error=None,
                attempts=1,
                down_seen=True,
                created_at=None,
                updated_at=None,
            )

        def get_managed_doc_sync(self, *_args: Any, **_kwargs: Any) -> ManagedDocSnapshot:
            raise RuntimeError("SECRET_REMOTE_RESPONSE_BODY")

    with pytest.raises(CancelRollbackError) as exc_info:
        rollback_managed_doc(
            adapter_client=Adapter(),  # type: ignore[arg-type]
            doc_kind="agent_rule",
            baseline_content=baseline,
            baseline_revision=revision,
            deadline=120.0,
        )

    assert exc_info.value.code == "CANCEL_ROLLBACK_FAILED"
    assert "SECRET_REMOTE_RESPONSE_BODY" not in exc_info.value.diagnostics


def test_rollback_timeout_diagnostics_do_not_leak_adapter_error() -> None:
    apply_error = ManagedDocApplyError(
        agent_name="agent-a",
        doc_kind="agent_rule",
        task_id="task-1",
        phase="deadline",
        adapter_error="SECRET_ADAPTER_RESPONSE_BODY",
    )

    with (
        patch("evo_agent.optimizer_runner.ManagedDocApplier") as applier_cls,
        pytest.raises(CancelRollbackError) as exc_info,
    ):
        applier_cls.return_value.apply_and_wait.side_effect = apply_error
        rollback_managed_doc(
            adapter_client=MagicMock(),
            doc_kind="agent_rule",
            baseline_content="# baseline",
            baseline_revision="rev-1",
            deadline=120.0,
        )

    assert exc_info.value.code == "CANCEL_ROLLBACK_TIMEOUT"
    assert exc_info.value.diagnostics == "phase=deadline task_id=task-1 doc_kind=agent_rule"


def test_rollback_final_confirmation_respects_total_deadline() -> None:
    now = [10.0]
    adapter = MagicMock()

    def consume_budget(_content: str) -> MagicMock:
        now[0] = 41.0
        return MagicMock()

    with (
        patch("evo_agent.optimizer_runner.ManagedDocApplier") as applier_cls,
        pytest.raises(CancelRollbackError) as exc_info,
    ):
        applier_cls.return_value.apply_and_wait.side_effect = consume_budget
        rollback_managed_doc(
            adapter_client=adapter,
            doc_kind="agent_rule",
            baseline_content="# baseline",
            baseline_revision="rev-1",
            deadline=30.0,
            clock=lambda: now[0],
        )

    assert exc_info.value.code == "CANCEL_ROLLBACK_TIMEOUT"
    adapter.get_managed_doc_sync.assert_not_called()


@pytest.mark.asyncio
async def test_cancelled_inflight_failure_still_rolls_back_with_remaining_budget() -> None:
    now = [10.0]
    token = CancellationToken(clock=lambda: now[0])
    baseline = ManagedDocSnapshot(
        content="# baseline",
        file_revision="rev-1",
        applied_revision="rev-1",
        pending_apply=False,
        apply_mode="restart",
        max_task_seconds=60.0,
    )

    async def fail_after_verified_baseline(*_args: Any, **kwargs: Any) -> Any:
        kwargs["managed_doc_baseline_callback"](baseline)
        token.request()
        now[0] = 25.0
        raise RuntimeError("in-flight apply failed")

    adapter = AsyncMock()
    adapter.__aenter__.return_value = adapter
    adapter.__aexit__.return_value = None
    rollback = MagicMock()
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent-a",
        skills=[],
        optimizer_type="prompt",
        managed_doc_kind="agent_rule",
        managed_doc_expected_revision="rev-1",
        adapter_url="http://adapter",
    )
    config = EvolveConfig(
        managed_doc_apply_deadline=20.0,
        managed_doc_cancel_rollback_deadline=30.0,
    )

    with (
        patch(
            "evo_agent.optimizer_runner.run_optimization",
            side_effect=fail_after_verified_baseline,
        ),
        patch("evo_agent.optimizer_runner.AdapterClient", return_value=adapter),
        patch("evo_agent.optimizer_runner.rollback_managed_doc", rollback),
        pytest.raises(CancellationRequestedError),
    ):
        await run_optimization_with_cancellation_recovery(
            request,
            config,
            cancellation_token=token,
        )

    assert rollback.call_args.kwargs["baseline_content"] == "# baseline"
    assert rollback.call_args.kwargs["deadline"] == pytest.approx(15.0)


@pytest.mark.asyncio
async def test_cancellation_requested_as_runner_returns_still_triggers_recovery() -> None:
    token = CancellationToken()
    baseline = ManagedDocSnapshot(
        content="# baseline",
        file_revision="rev-1",
        applied_revision="rev-1",
        pending_apply=False,
        apply_mode="restart",
        max_task_seconds=60.0,
    )

    async def finish_after_cancel(*_args: Any, **kwargs: Any) -> Any:
        kwargs["managed_doc_baseline_callback"](baseline)
        token.request()
        return MagicMock()

    adapter = AsyncMock()
    adapter.__aenter__.return_value = adapter
    adapter.__aexit__.return_value = None
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent-a",
        skills=[],
        optimizer_type="prompt",
        managed_doc_kind="agent_rule",
        managed_doc_expected_revision="rev-1",
        adapter_url="http://adapter",
    )

    with (
        patch("evo_agent.optimizer_runner.run_optimization", side_effect=finish_after_cancel),
        patch("evo_agent.optimizer_runner.AdapterClient", return_value=adapter),
        patch("evo_agent.optimizer_runner.rollback_managed_doc"),
        pytest.raises(CancellationRequestedError),
    ):
        await run_optimization_with_cancellation_recovery(
            request,
            EvolveConfig(),
            cancellation_token=token,
        )


@pytest.mark.asyncio
async def test_cancellation_wrapper_returns_successful_report_unchanged() -> None:
    report = MagicMock()

    with patch(
        "evo_agent.optimizer_runner.run_optimization",
        new=AsyncMock(return_value=report),
    ):
        result = await run_optimization_with_cancellation_recovery(
            OptimizeRequest(scenario="edp_agent", agent_name="agent-a", skills=["skill-a"]),
            EvolveConfig(),
        )

    assert result is report


@pytest.mark.asyncio
async def test_early_prompt_cancel_confirms_expected_revision_before_success() -> None:
    token = CancellationToken()
    token.request()
    snapshot = ManagedDocSnapshot(
        content="# baseline",
        file_revision="rev-1",
        applied_revision="rev-1",
        pending_apply=False,
        apply_mode="restart",
        max_task_seconds=60.0,
    )
    adapter = AsyncMock()
    adapter.__aenter__.return_value = adapter
    adapter.__aexit__.return_value = None
    adapter.get_managed_doc_sync = MagicMock(return_value=snapshot)
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent-a",
        optimizer_type="prompt",
        managed_doc_kind="agent_rule",
        managed_doc_expected_revision="rev-1",
        adapter_url="http://adapter",
    )

    with (
        patch(
            "evo_agent.optimizer_runner.run_optimization",
            new=AsyncMock(side_effect=CancellationRequestedError()),
        ),
        patch("evo_agent.optimizer_runner.AdapterClient", return_value=adapter),
        pytest.raises(CancellationRequestedError),
    ):
        await run_optimization_with_cancellation_recovery(
            request,
            EvolveConfig(),
            cancellation_token=token,
        )

    adapter.get_managed_doc_sync.assert_called_once()


@pytest.mark.asyncio
async def test_total_cancellation_deadline_exhaustion_skips_new_rollback_apply() -> None:
    now = [10.0]
    token = CancellationToken(clock=lambda: now[0])
    token.request()
    baseline = ManagedDocSnapshot(
        content="# baseline",
        file_revision="rev-1",
        applied_revision="rev-1",
        pending_apply=False,
        apply_mode="restart",
        max_task_seconds=60.0,
    )

    async def finish_after_deadline(*_args: Any, **kwargs: Any) -> Any:
        kwargs["managed_doc_baseline_callback"](baseline)
        now[0] = 41.0
        raise CancellationRequestedError()

    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent-a",
        skills=[],
        optimizer_type="prompt",
        managed_doc_kind="agent_rule",
        managed_doc_expected_revision="rev-1",
        adapter_url="http://adapter",
    )
    config = EvolveConfig(
        managed_doc_apply_deadline=20.0,
        managed_doc_cancel_rollback_deadline=30.0,
    )
    phases: list[str] = []

    with (
        patch(
            "evo_agent.optimizer_runner.run_optimization",
            side_effect=finish_after_deadline,
        ),
        patch("evo_agent.optimizer_runner.AdapterClient") as adapter_cls,
        pytest.raises(CancelRollbackError) as exc_info,
    ):
        await run_optimization_with_cancellation_recovery(
            request,
            config,
            cancellation_token=token,
            phase_callback=lambda _event, data: phases.append(data["phase"]),
        )

    assert exc_info.value.code == "CANCEL_ROLLBACK_TIMEOUT"
    adapter_cls.assert_not_called()
    assert phases == ["rollback_failed"]


@pytest.mark.asyncio
async def test_running_prompt_becomes_cancelled_only_after_baseline_rollback(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    dataset = tmp_path / "items.json"
    dataset.write_text("[]", encoding="utf-8")
    body = {
        "task_name": "prompt-cancel",
        "agent_name": "agent-a",
        "optimizer_type": "prompt",
        "dataset_path": str(dataset),
        "skills": [],
        "managed_doc_kind": "agent_rule",
        "managed_doc_expected_revision": "rev-1",
        "client_task_id": "studio-cancel-1",
        "optimizer_template": {
            "name": "edp_agent",
            "scenario": "edp_agent",
            "hyperparams": {},
        },
        "evaluator_template": {"name": "eval", "scenario": "test", "prompt": ""},
    }
    manager = JobManager(control_db_path=tmp_path / "control.db")
    monkeypatch.setattr(optimize_routes, "job_manager", manager)

    async def wait_for_cancel(*_args: Any, **kwargs: Any) -> Any:
        token = kwargs["cancellation_token"]
        while not token.is_requested:
            await asyncio.sleep(0)
        raise CancellationRequestedError()

    config = EvolveConfig(adapter_url="http://adapter", allowed_data_roots=[tmp_path])

    with (
        patch("evo_agent.api.routes.optimize.EvolveConfig.get", return_value=config),
        patch(
            "evo_agent.api.routes.optimize.run_optimization_with_cancellation_recovery",
            side_effect=wait_for_cancel,
        ),
    ):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            created = await client.post("/optimize", json=body)
            job = manager.get(created.json()["job_id"])
            assert job is not None
            while job.status != JobStatus.RUNNING:
                await asyncio.sleep(0)
            accepted = await client.post(f"/optimize/{job.job_id}/cancel")
            assert accepted.status_code == 202
            assert accepted.json()["status"] == "running"
            assert job.background_task is not None
            await job.background_task

    assert job.status == JobStatus.CANCELLED
    assert [event.event for event in job.event_buffer][-1] == "completed"
    assert job.event_buffer[-1].data["status"] == "cancelled"
