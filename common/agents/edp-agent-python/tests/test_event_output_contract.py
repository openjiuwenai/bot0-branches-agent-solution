import pytest

from EDPAgent import agent as agent_mod
from EDPAgent.events import (
    DelegateRequest,
    SubAgentDispatchRequest,
    SubAgentSpec,
)


@pytest.mark.asyncio
async def test_agent_stream_public_contract_yields_dict_events(monkeypatch):
    async def fake_event_stream(**kwargs):  # noqa: ARG001
        yield DelegateRequest(intent="buy", task_description="delegate task")
        yield SubAgentDispatchRequest(
            specs=[
                SubAgentSpec(
                    entity_id="entity-1",
                    entity_name="Entity One",
                    query="inspect entity",
                    url="https://agent.example/a2a/",
                )
            ]
        )

    monkeypatch.setattr(agent_mod, "_agent_event_stream", fake_event_stream)

    events = [
        event
        async for event in agent_mod.agent_stream(query="q", conv_id="conv-1")
    ]

    assert events == [
        {
            "type": "delegate",
            "data": {
                "intent": "buy",
                "task_description": "delegate task",
            },
        },
        {
            "type": "sub_agent_dispatch",
            "data": {
                "specs": [
                    {
                        "entity_id": "entity-1",
                        "entity_name": "Entity One",
                        "query": "inspect entity",
                        "url": "https://agent.example/a2a/",
                    }
                ]
            },
        },
    ]
