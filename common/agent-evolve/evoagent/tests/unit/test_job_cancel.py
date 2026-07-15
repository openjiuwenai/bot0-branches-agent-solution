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
    """cancel 一个 running 状态的 job → 状态变为 cancelled，返回 True。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING
    # 模拟一个 background_task
    loop = asyncio.new_event_loop()
    task = loop.create_task(asyncio.sleep(10))
    job.background_task = task

    result = mgr.cancel(job.job_id)

    assert result is True
    assert job.status == JobStatus.CANCELLED
    # cancel() 是异步请求，需要让 loop 跑一下才能真正完成
    # 但 cancelling() 立即生效（Python 3.9+）
    assert task.cancelling() > 0 or task.done()
    # 清理：让 task 完成取消
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
    """cancel 后 job 的 event_buffer 中有 cancelled 事件。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING

    mgr.cancel(job.job_id)

    cancelled_events = [e for e in job.event_buffer if e.event == "cancelled"]
    assert len(cancelled_events) == 1
    assert cancelled_events[0].data["status"] == "cancelled"


def test_cancel_sets_error_message() -> None:
    """cancel 后 job.error 包含取消信息。"""
    mgr = JobManager()
    job = mgr.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING

    mgr.cancel(job.job_id)

    assert job.error is not None
    assert "cancel" in job.error.lower()


# ── API endpoint: POST /optimize/{job_id}/cancel ──


@pytest.fixture
async def api_client() -> AsyncClient:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


@pytest.mark.asyncio
async def test_cancel_endpoint_running_job(api_client: AsyncClient) -> None:
    """POST /optimize/{job_id}/cancel → 200 + status=cancelled。"""
    job = job_manager.submit({"scenario": "test"})
    job.status = JobStatus.RUNNING

    resp = await api_client.post(f"/optimize/{job.job_id}/cancel")

    assert resp.status_code == 200
    data = resp.json()
    assert data["job_id"] == job.job_id
    assert data["status"] == "cancelled"


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
    """POST /optimize/{job_id}/cancel queued 的 job → 200 + status=cancelled。"""
    job = job_manager.submit({"scenario": "test"})
    # status is QUEUED by default

    resp = await api_client.post(f"/optimize/{job.job_id}/cancel")

    assert resp.status_code == 200
    assert resp.json()["status"] == "cancelled"


# ── _run_with_progress handles CancelledError ──


@pytest.mark.asyncio
async def test_run_with_progress_handles_cancellation() -> None:
    """当 background task 被 cancel 时，_run_with_progress 正确处理 CancelledError。

    CancelledError 不应被 except Exception 吞掉，也不应将状态改为 FAILED。
    """
    job = job_manager.submit({"scenario": "test"})

    async def slow_optimization(*args: object, **kwargs: object) -> None:
        await asyncio.sleep(100)  # 模拟长时间运行的优化

    # 模拟一个简化的 _run_with_progress
    async def _run_with_progress() -> None:
        job.status = JobStatus.RUNNING
        try:
            await slow_optimization()
            job.status = JobStatus.COMPLETED
        except asyncio.CancelledError:
            # 状态已由 JobManager.cancel() 设置，这里只做日志
            raise
        except Exception:
            job.status = JobStatus.FAILED

    task = asyncio.create_task(_run_with_progress())
    job.background_task = task
    job.status = JobStatus.RUNNING

    # 等 task 启动
    await asyncio.sleep(0.01)

    # 取消
    job_manager.cancel(job.job_id)

    # 等待 task 处理取消
    with pytest.raises(asyncio.CancelledError):
        await task

    # 状态应该是 CANCELLED，不是 FAILED
    assert job.status == JobStatus.CANCELLED
    # 应该有 cancelled 事件
    assert any(e.event == "cancelled" for e in job.event_buffer)


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
