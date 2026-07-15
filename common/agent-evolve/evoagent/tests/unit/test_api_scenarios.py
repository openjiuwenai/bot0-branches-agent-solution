"""Scenario API route tests."""

from __future__ import annotations

from evo_agent.api.routes.scenarios import list_scenarios


async def test_list_scenarios_finds_edp_agent() -> None:
    """验证 GET /scenarios 返回 edp_agent 场景及其 optimizer 信息。"""
    scenarios = await list_scenarios()

    assert len(scenarios) >= 1
    edp = next(s for s in scenarios if s["name"] == "edp_agent")
    assert edp["optimizer_class"] == "optimizer.EDPAgentOptimizer"
    assert isinstance(edp["hyperparams"], dict)
    assert edp["hyperparams"]["batch_size"] == 8
