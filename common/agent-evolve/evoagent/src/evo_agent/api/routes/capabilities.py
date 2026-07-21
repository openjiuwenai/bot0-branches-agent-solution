"""Deployment capability discovery."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from evo_agent.api.jobs import job_manager

router = APIRouter(tags=["capabilities"])


class CapabilitiesResponse(BaseModel):
    managed_doc_optimization: bool
    managed_doc_epoch_contents: bool
    managed_doc_cooperative_cancellation: bool
    managed_doc_baseline_rollback: bool
    optimization_submit_idempotency: bool
    managed_doc_operation_idempotency: bool


@router.get("/capabilities", response_model=CapabilitiesResponse)
async def get_capabilities() -> CapabilitiesResponse:
    if not job_manager.durable_available:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "CAPABILITY_STORAGE_UNAVAILABLE",
                "message": "optimization control store is unavailable",
            },
        )
    return CapabilitiesResponse(
        managed_doc_optimization=True,
        managed_doc_epoch_contents=True,
        managed_doc_cooperative_cancellation=True,
        managed_doc_baseline_rollback=True,
        optimization_submit_idempotency=True,
        # Reserved compatibility field. Adapter durable operation receipts are
        # deliberately outside the current reviewed scope.
        managed_doc_operation_idempotency=False,
    )


__all__ = ["CapabilitiesResponse", "router"]
