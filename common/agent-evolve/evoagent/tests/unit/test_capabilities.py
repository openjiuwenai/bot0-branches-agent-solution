"""Deployment capability endpoint behavior."""

import pytest
from httpx import ASGITransport, AsyncClient

from evo_agent.api.app import app
from evo_agent.api.routes import capabilities as capabilities_routes


@pytest.mark.asyncio
async def test_capabilities_report_receipt_free_prompt_support() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/capabilities")

    assert response.status_code == 200
    assert response.json() == {
        "managed_doc_optimization": True,
        "managed_doc_epoch_contents": True,
        "managed_doc_cooperative_cancellation": True,
        "managed_doc_baseline_rollback": True,
        "optimization_submit_idempotency": True,
        "managed_doc_operation_idempotency": False,
    }


@pytest.mark.asyncio
async def test_capabilities_fail_closed_when_control_store_is_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    unavailable = type("UnavailableManager", (), {"durable_available": False})()
    monkeypatch.setattr(capabilities_routes, "job_manager", unavailable)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/capabilities")

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "CAPABILITY_STORAGE_UNAVAILABLE"
