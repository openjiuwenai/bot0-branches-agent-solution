"""Durable optimization submission behavior tests."""

import sqlite3
from pathlib import Path
from unittest.mock import patch

import pytest
from httpx import ASGITransport, AsyncClient

from evo_agent.api.app import app
from evo_agent.api.jobs import JobManager, JobStatus
from evo_agent.api.routes import optimize as optimize_routes
from evo_agent.config import EvolveConfig
from evo_agent.control_store import (
    SubmissionConflictError,
    SubmissionControlStore,
    SubmissionStatus,
    canonical_request_hash,
)


def test_canonical_request_hash_v1_is_stable_for_unicode_and_key_order() -> None:
    first = {
        "managed_doc_kind": "AgentRule.md",
        "client_task_id": "ignored-a",
        "nested": {"z": 2, "a": 1},
        "agent_name": "智能体",
    }
    reordered = {
        "agent_name": "智能体",
        "nested": {"a": 1, "z": 2},
        "client_task_id": "ignored-b",
        "managed_doc_kind": "AgentRule.md",
    }

    assert canonical_request_hash(first) == canonical_request_hash(reordered)
    assert canonical_request_hash(first) == (
        "30976d42d649acfb254d95f8bfc2627a21c5b9801b2dfb4a9e6c0a05b04560dc"
    )


def test_same_client_task_id_and_request_returns_original_job(tmp_path: Path) -> None:
    manager = JobManager(control_db_path=tmp_path / "control.db")
    request = {"agent_name": "agent-a", "managed_doc_kind": "agent_rule"}

    first = manager.submit(request, client_task_id="studio-task-1")
    replay = manager.submit(request, client_task_id="studio-task-1")

    assert replay.job_id == first.job_id
    assert len(manager.list_jobs()) == 1


def test_unavailable_control_store_keeps_manager_fail_closed(tmp_path: Path) -> None:
    manager = JobManager(control_db_path=tmp_path)

    assert manager.durable_available is False
    with pytest.raises(RuntimeError, match="control store"):
        manager.submit({"agent_name": "agent-a"}, client_task_id="studio-task-unavailable")


def test_control_store_availability_requires_write_access(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    store = SubmissionControlStore(tmp_path / "control.db")
    connection = sqlite3.connect(store.path)
    connection.execute("PRAGMA query_only = ON")
    monkeypatch.setattr(store, "_connect", lambda: connection)

    try:
        assert store.is_available() is False
    finally:
        connection.close()


@pytest.mark.asyncio
async def test_prompt_submit_returns_503_when_control_store_is_unavailable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    dataset = tmp_path / "items.json"
    dataset.write_text("[]", encoding="utf-8")
    manager = JobManager(control_db_path=tmp_path)
    monkeypatch.setattr(optimize_routes, "job_manager", manager)
    body = {
        "task_name": "prompt-storage-unavailable",
        "agent_name": "agent-a",
        "optimizer_type": "prompt",
        "dataset_path": str(dataset),
        "skills": [],
        "managed_doc_kind": "agent_rule",
        "managed_doc_expected_revision": "rev-1",
        "client_task_id": "studio-task-unavailable",
        "optimizer_template": {
            "name": "edp_agent",
            "scenario": "edp_agent",
            "hyperparams": {},
        },
        "evaluator_template": {"name": "eval", "scenario": "test", "prompt": ""},
    }
    config = EvolveConfig(adapter_url="http://adapter", allowed_data_roots=[tmp_path])

    with patch("evo_agent.api.routes.optimize.EvolveConfig.get", return_value=config):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            response = await client.post("/optimize", json=body)

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "CAPABILITY_STORAGE_UNAVAILABLE"


@pytest.mark.asyncio
async def test_submission_receipt_can_be_queried_over_http(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manager = JobManager(control_db_path=tmp_path / "control.db")
    job = manager.submit(
        {"agent_name": "agent-a", "managed_doc_kind": "agent_rule"},
        client_task_id="studio-task-query",
    )
    monkeypatch.setattr(optimize_routes, "job_manager", manager)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/optimize/submissions/studio-task-query")

    assert response.status_code == 200
    assert response.json() == {
        "client_task_id": "studio-task-query",
        "job_id": job.job_id,
        "status": "RECEIVED",
        "request_hash_version": "v1",
        "request_hash": manager.get_submission("studio-task-query").request_hash,
        "cancellation_requested": False,
    }


@pytest.mark.asyncio
async def test_unknown_submission_receipt_returns_404(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        optimize_routes,
        "job_manager",
        JobManager(control_db_path=tmp_path / "control.db"),
    )

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/optimize/submissions/missing-task")

    assert response.status_code == 404


def test_job_status_changes_are_persisted_in_submission_receipt(tmp_path: Path) -> None:
    manager = JobManager(control_db_path=tmp_path / "control.db")
    job = manager.submit(
        {"agent_name": "agent-a", "managed_doc_kind": "agent_rule"},
        client_task_id="studio-task-status",
    )

    manager.set_status(job, JobStatus.RUNNING)

    receipt = manager.get_submission("studio-task-status")
    assert receipt is not None
    assert receipt.status == SubmissionStatus.RUNNING


def test_restart_marks_unfinished_submission_lost(tmp_path: Path) -> None:
    db_path = tmp_path / "control.db"
    first_process = JobManager(control_db_path=db_path)
    first_process.submit(
        {"agent_name": "agent-a", "managed_doc_kind": "agent_rule"},
        client_task_id="studio-task-1",
    )

    restarted = JobManager(control_db_path=db_path)

    receipt = restarted.get_submission("studio-task-1")
    assert receipt is not None
    assert receipt.status == SubmissionStatus.LOST


def test_same_client_task_id_with_different_request_is_rejected(tmp_path: Path) -> None:
    manager = JobManager(control_db_path=tmp_path / "control.db")
    manager.submit(
        {"agent_name": "agent-a", "managed_doc_kind": "agent_rule"},
        client_task_id="studio-task-1",
    )

    with pytest.raises(SubmissionConflictError):
        manager.submit(
            {"agent_name": "agent-a", "managed_doc_kind": "AGENTS.md"},
            client_task_id="studio-task-1",
        )

    assert len(manager.list_jobs()) == 1
