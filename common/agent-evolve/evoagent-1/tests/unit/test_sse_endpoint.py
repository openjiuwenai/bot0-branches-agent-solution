"""ProgressCallback SSE 事件推送 + SSE 端点单元测试。"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest
from httpx import ASGITransport, AsyncClient

from evo_agent.api.app import app
from evo_agent.api.jobs import Job, JobStatus
from evo_agent.api.progress import ProgressCallback
from evo_agent.api.sse import format_sse

# ── ProgressCallback writes to event buffer ──


def _make_progress(max_epoch: int = 3, current_epoch: int = 1) -> MagicMock:
    progress = MagicMock()
    progress.max_epoch = max_epoch
    progress.current_epoch = current_epoch
    progress.current_epoch_score = 0.7
    progress.best_score = 0.8
    return progress


def test_progress_callback_pushes_train_begin() -> None:
    """on_train_begin 推送一个 progress 事件。"""
    job = Job(job_id="test")
    cb = ProgressCallback(job)
    cb.on_train_begin(MagicMock(), _make_progress(), [])

    # At least one progress event with phase=train_begin
    progress_events = [e for e in job.event_buffer if e.event == "progress"]
    assert any(e.data.get("phase") == "train_begin" for e in progress_events)


def test_progress_callback_pushes_epoch_end() -> None:
    """on_train_epoch_end 推送 progress + log + validation 事件。"""
    job = Job(job_id="test")
    cb = ProgressCallback(job)
    cb.on_train_epoch_end(MagicMock(), _make_progress(current_epoch=2), [])

    # At least one progress event with phase=epoch_end
    progress_events = [e for e in job.event_buffer if e.event == "progress"]
    assert any(e.data.get("phase") == "epoch_end" for e in progress_events)
    epoch_end = [e for e in progress_events if e.data.get("phase") == "epoch_end"][0]
    assert epoch_end.data["epoch"] == 2


def test_progress_callback_pushes_train_end() -> None:
    """on_train_end 推送 progress + log 事件，但不推 completed。

    completed 事件由 routes 层 _run_with_progress 在 format() 成功后统一推送，
    避免 format() 失败时状态/事件不一致（Bug 2）。
    """
    job = Job(job_id="test")
    cb = ProgressCallback(job)
    cb.on_train_end(MagicMock(), _make_progress(), [])

    # train_end progress + log 都在
    progress_events = [e for e in job.event_buffer if e.event == "progress"]
    assert any(e.data.get("phase") == "train_end" for e in progress_events)
    log_events = [e for e in job.event_buffer if e.event == "log"]
    assert any(e.data.get("phase") == "train_end" for e in log_events)
    # 不再由 on_train_end 推 completed
    assert not any(e.event == "completed" for e in job.event_buffer)


def test_progress_callback_updates_job_progress() -> None:
    """每个回调同时更新 job.progress。"""
    job = Job(job_id="test")
    cb = ProgressCallback(job)
    cb.on_train_begin(MagicMock(), _make_progress(max_epoch=5), [])

    assert job.progress.total_epochs == 5
    assert job.status == JobStatus.RUNNING


def test_progress_callback_event_data_fields() -> None:
    """epoch_end 事件包含 epoch, val_score, best_score, train_score, gate_decision."""
    job = Job(job_id="test")
    cb = ProgressCallback(job)
    mock_case = MagicMock()
    mock_case.score = 0.7
    cb.on_train_epoch_end(MagicMock(), _make_progress(current_epoch=3), [mock_case])

    data = job.event_buffer[0].data
    assert data["epoch"] == 3
    assert data["val_score"] == 0.7
    assert data["best_score"] == 0.8
    assert "train_score" in data
    assert "gate_decision" in data
    assert data["gate_decision"] in ("accepted", "rejected")
    assert "edits_applied" in data
    assert "epoch_edits" in data  # Bug 4: 本 epoch 增量
    assert data["epoch_edits"] == data["edits_applied"] - 0


# ── SSE format function ──


def test_sse_format_event() -> None:
    """_format_sse() 输出 id: 1\\nevent: progress\\ndata: {...}\\n\\n。"""
    from evo_agent.api.events import SSEEvent

    event = SSEEvent(id=1, event="progress", data={"epoch": 1}, timestamp=0.0)
    text = format_sse(event)

    assert "id: 1" in text
    assert "event: progress" in text
    assert '"epoch": 1' in text
    assert text.endswith("\n\n")


def test_sse_format_event_unicode() -> None:
    """data 含中文时 ensure_ascii=False 正确输出。"""
    from evo_agent.api.events import SSEEvent

    event = SSEEvent(id=2, event="progress", data={"msg": "训练完成"}, timestamp=0.0)
    text = format_sse(event)

    assert "训练完成" in text
    assert "\\u" not in text


# ── SSE endpoint ──


@pytest.fixture
async def client() -> AsyncClient:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


@pytest.mark.asyncio
async def test_sse_endpoint_returns_stream(client: AsyncClient) -> None:
    """响应 Content-Type 为 text/event-stream。"""
    from evo_agent.api.jobs import job_manager

    job = job_manager.submit({"scenario": "test"})
    job.push_event("progress", {"test": True})
    job.status = JobStatus.COMPLETED

    async with client.stream("GET", f"/optimize/{job.job_id}/stream") as resp:
        assert resp.status_code == 200
        assert "text/event-stream" in resp.headers.get("content-type", "")
        # Read first chunk
        async for _ in resp.aiter_lines():
            break


@pytest.mark.asyncio
async def test_sse_replays_history(client: AsyncClient) -> None:
    """Last-Event-ID header 正确重放历史事件。"""
    from evo_agent.api.jobs import job_manager

    job = job_manager.submit({"scenario": "test"})
    job.push_event("progress", {"epoch": 1})
    job.push_event("progress", {"epoch": 2})
    job.push_event("progress", {"epoch": 3})
    job.status = JobStatus.COMPLETED

    async with client.stream(
        "GET",
        f"/optimize/{job.job_id}/stream",
        headers={"Last-Event-ID": "1"},
    ) as resp:
        assert resp.status_code == 200
        body = ""
        async for line in resp.aiter_lines():
            body += line + "\n"

    # Should replay events 2 and 3 (id > 1)
    assert "id: 2" in body
    assert "id: 3" in body
    assert "id: 1" not in body


@pytest.mark.asyncio
async def test_sse_job_not_found(client: AsyncClient) -> None:
    """不存在的 job_id → 404。"""
    resp = await client.get("/optimize/nonexistent_job/stream")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_sse_completed_job(client: AsyncClient) -> None:
    """已完成 job 推送历史事件后立即结束（不阻塞）。"""
    from evo_agent.api.jobs import job_manager

    job = job_manager.submit({"scenario": "test"})
    job.push_event("progress", {"epoch": 1})
    job.status = JobStatus.COMPLETED

    async with client.stream("GET", f"/optimize/{job.job_id}/stream") as resp:
        assert resp.status_code == 200
        lines: list[str] = []
        async for line in resp.aiter_lines():
            lines.append(line)

    # Should contain the event data
    assert any("id: 1" in line for line in lines)
