"""Scenario API route tests."""

from __future__ import annotations

from evo_agent.api.routes.scenarios import list_scenarios


async def test_list_scenarios_finds_skillopt_and_edp_agent() -> None:
    """验证 GET /scenarios 同时包含 skillopt 与 edp_agent。"""
    scenarios = await list_scenarios()

    assert len(scenarios) >= 2

    skillopt = next(s for s in scenarios if s["name"] == "skillopt")
    assert skillopt["optimizer_class"] == "optimizer.SkillOptOptimizer"
    assert isinstance(skillopt["hyperparams"], dict)
    assert skillopt["hyperparams"]["batch_size"] == 8

    edp = next(s for s in scenarios if s["name"] == "edp_agent")
    assert edp["optimizer_class"] == "optimizer.EDPAgentOptimizer"
    assert isinstance(edp["hyperparams"], dict)
    assert edp["hyperparams"]["batch_size"] == 8
