"""Cooperative cancellation behavior."""

import threading
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from evo_agent.api.jobs import JobManager, JobStatus
from evo_agent.cancellation import CancellationRequestedError, CancellationToken


def test_raise_if_requested_is_a_noop_before_cancellation() -> None:
    CancellationToken().raise_if_requested()


def test_cancellation_requested_in_api_thread_is_visible_in_worker_thread() -> None:
    token = CancellationToken()
    observed: list[bool] = []
    ready = threading.Event()

    def worker() -> None:
        ready.wait(timeout=1)
        with pytest.raises(CancellationRequestedError):
            token.raise_if_requested()
        observed.append(token.is_requested)

    thread = threading.Thread(target=worker)
    thread.start()
    token.request()
    ready.set()
    thread.join(timeout=1)

    assert observed == [True]


def test_remaining_seconds_uses_first_request_time_as_total_deadline() -> None:
    now = [10.0]
    token = CancellationToken(clock=lambda: now[0])
    token.request()
    now[0] = 22.5

    assert token.remaining_seconds(30.0) == pytest.approx(17.5)

    token.request()
    now[0] = 45.0
    assert token.remaining_seconds(30.0) == 0.0


def test_running_cancel_latches_request_without_cancelling_inflight_task(
    tmp_path: Path,
) -> None:
    manager = JobManager(control_db_path=tmp_path / "control.db")
    job = manager.submit(
        {"agent_name": "agent-a", "managed_doc_kind": "agent_rule"},
        client_task_id="studio-task-cancel",
    )
    manager.set_status(job, JobStatus.RUNNING)
    task = MagicMock()
    task.done.return_value = False
    job.background_task = task

    assert manager.cancel(job.job_id) is True

    assert job.status == JobStatus.RUNNING
    assert job.cancellation_token.is_requested is True
    task.cancel.assert_not_called()
    receipt = manager.get_submission("studio-task-cancel")
    assert receipt is not None and receipt.cancellation_requested is True
    assert any(
        event.event == "log" and event.data.get("phase") == "cancel_requested"
        for event in job.event_buffer
    )
