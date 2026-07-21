"""JobManager 任务取消单元测试 + API 端点测试。"""

from __future__ import annotations

import asyncio

import pytest
from httpx import ASGITransport, AsyncClient

from evo_agent.api.app import app
from evo_agent.api.jobs import JobManager, JobStatus, job_manager

# ── JobStatus.CANCELLED ──


def test_cancelled_status_exists() -> None:
    """JobStatus 包含 CANCELLED 状态。"""
    assert JobStatus.CANCELLED == "cancelled"


# ── JobManager.cancel() ──


def test_cancel_running_job() -> None:
    """running job 只锁存取消，不能强杀 in-flight task。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING
    # 模拟一个 background_task
    loop = asyncio.new_event_loop()
    task = loop.create_task(asyncio.sleep(10))
    job.background_task = task

    result = mgr.cancel(job.job_id)

    assert result is True
    assert job.status == JobStatus.RUNNING
    assert job.cancellation_token.is_requested
    assert task.cancelling() == 0
    # 清理测试创建的 task；生产 cancel() 本身不会走这条强杀路径。
    task.cancel()
    loop.run_until_complete(asyncio.sleep(0))
    loop.close()


def test_cancel_queued_job() -> None:
    """cancel 一个 queued 状态的 job → 状态变为 cancelled，返回 True。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    assert job.status == JobStatus.QUEUED

    result = mgr.cancel(job.job_id)

    assert result is True
    assert job.status == JobStatus.CANCELLED


def test_cancel_nonexistent_job() -> None:
    """cancel 不存在的 job → 返回 False。"""
    mgr = JobManager()
    result = mgr.cancel("nonexistent")
    assert result is False


def test_cancel_completed_job() -> None:
    """cancel 已完成的 job → 返回 False，状态不变。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.COMPLETED

    result = mgr.cancel(job.job_id)

    assert result is False
    assert job.status == JobStatus.COMPLETED


def test_cancel_failed_job() -> None:
    """cancel 已失败的 job → 返回 False，状态不变。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.FAILED

    result = mgr.cancel(job.job_id)

    assert result is False
    assert job.status == JobStatus.FAILED


def test_cancel_already_cancelled_job() -> None:
    """cancel 已取消的 job → 返回 False，状态不变。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.CANCELLED

    result = mgr.cancel(job.job_id)

    assert result is False
    assert job.status == JobStatus.CANCELLED


def test_cancel_pushes_event() -> None:
    """cancel 只使用既有 log event + cancel_requested phase。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING

    mgr.cancel(job.job_id)

    events = [e for e in job.event_buffer if e.data.get("phase") == "cancel_requested"]
    assert len(events) == 1
    assert events[0].event == "log"


def test_cancel_sets_error_message() -> None:
    """running cancel 尚未终态时不得提前写 cancelled error。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING

    mgr.cancel(job.job_id)

    assert job.error is None


# ── API endpoint: POST /optimize/{job_id}/cancel ──


@pytest.fixture
async def api_client() -> AsyncClient:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


@pytest.mark.asyncio
async def test_cancel_endpoint_running_job(api_client: AsyncClient) -> None:
    """POST /optimize/{job_id}/cancel → 202 + status=running。"""
    job = job_manager.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING

    resp = await api_client.post(f"/optimize/{job.job_id}/cancel")

    assert resp.status_code == 202
    data = resp.json()
    assert data["job_id"] == job.job_id
    assert data["status"] == "running"
    assert data["cancellation_requested"] is True


@pytest.mark.asyncio
async def test_cancel_endpoint_nonexistent_job(api_client: AsyncClient) -> None:
    """POST /optimize/{job_id}/cancel 不存在的 job → 404。"""
    resp = await api_client.post("/optimize/nonexistent_job/cancel")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_cancel_endpoint_completed_job(api_client: AsyncClient) -> None:
    """POST /optimize/{job_id}/cancel 已完成的 job → 409 Conflict。"""
    job = job_manager.submit({"scenario": "test"})
    job.status = JobStatus.COMPLETED

    resp = await api_client.post(f"/optimize/{job.job_id}/cancel")

    assert resp.status_code == 409
    assert "terminal" in resp.json()["detail"].lower() or "cannot" in resp.json()["detail"].lower()


@pytest.mark.asyncio
async def test_cancel_endpoint_failed_job(api_client: AsyncClient) -> None:
    """POST /optimize/{job_id}/cancel 已失败的 job → 409 Conflict。"""
    job = job_manager.submit({"scenario": "test"})
    job.status = JobStatus.FAILED

    resp = await api_client.post(f"/optimize/{job.job_id}/cancel")

    assert resp.status_code == 409


@pytest.mark.asyncio
async def test_cancel_endpoint_queued_job(api_client: AsyncClient) -> None:
    """POST /optimize/{job_id}/cancel queued 的 job → 202 + status=cancelled。"""
    job = job_manager.submit({"scenario": "test"})
    # status is QUEUED by default

    resp = await api_client.post(f"/optimize/{job.job_id}/cancel")

    assert resp.status_code == 202
    assert resp.json()["status"] == "cancelled"


# ── _run_with_progress handles CancelledError ──


@pytest.mark.asyncio
async def test_run_with_progress_handles_cancellation() -> None:
    """重复 running cancel 幂等，且不产生第二个 rollback 请求事件。"""
    job = job_manager.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING

    assert job_manager.cancel(job.job_id)
    assert job_manager.cancel(job.job_id)

    assert job.status == JobStatus.RUNNING
    assert sum(e.data.get("phase") == "cancel_requested" for e in job.event_buffer) == 1


@pytest.mark.asyncio
async def test_sse_stream_ends_on_cancelled_job() -> None:
    """SSE stream 对 cancelled 状态的 job 重放历史后立即结束。"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        job = job_manager.submit({"scenario": "test"})
        job.push_event("progress", {"epoch": 1})
        job.status = JobStatus.CANCELLED

        async with client.stream("GET", f"/optimize/{job.job_id}/stream") as resp:
            assert resp.status_code == 200
            lines: list[str] = []
            async for line in resp.aiter_lines():
                lines.append(line)

        # Should contain the progress event and then close
        assert any("id: 1" in line for line in lines)
