"""POST /evaluate/generate-goal API tests."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from evo_agent.api.app import app
from evo_agent.evaluator.domain.models import GoalGenerationOutput
from evo_agent.evaluator.domain.scoring import EvaluationError


@pytest.fixture
async def client() -> AsyncClient:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


def _payload(messages: list[dict[str, object]]) -> dict[str, object]:
    return {
        "messages": messages,
        "llm_config": {
            "model_name": "qwen-plus",
            "api_key": "sk-test",
            "api_base": "https://example.test/v1",
            "client_provider": "OpenAI",
            "temperature": 0.1,
            "max_tokens": 2048,
            "verify_ssl": False,
        },
    }


@pytest.mark.asyncio
async def test_generate_goal_success(client: AsyncClient) -> None:
    with patch("evo_agent.api.routes.evaluate.TrajectoryGoalGenerator") as generator_cls:
        generator = MagicMock()
        generator.generate.return_value = GoalGenerationOutput(
            goal="用户期望查询余额前先确认卡号。",
            metadata={"reason": "用户纠正了查询流程。", "confidence": 0.9},
        )
        generator_cls.return_value = generator

        resp = await client.post(
            "/evaluate/generate-goal",
            json=_payload([{"role": "user", "content": "查询余额。"}]),
        )

    assert resp.status_code == 200
    assert resp.json() == {
        "status": "generated",
        "goal": "用户期望查询余额前先确认卡号。",
        "metadata": {"reason": "用户纠正了查询流程。", "confidence": 0.9},
    }


@pytest.mark.asyncio
async def test_generate_goal_empty_messages_returns_422(client: AsyncClient) -> None:
    resp = await client.post("/evaluate/generate-goal", json=_payload([]))

    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_generate_goal_message_missing_role_returns_422(client: AsyncClient) -> None:
    resp = await client.post(
        "/evaluate/generate-goal",
        json=_payload([{"content": "查询余额。"}]),
    )

    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_generate_goal_ignores_unknown_message_fields(client: AsyncClient) -> None:
    with patch("evo_agent.api.routes.evaluate.TrajectoryGoalGenerator") as generator_cls:
        generator = MagicMock()
        generator.generate.return_value = GoalGenerationOutput(goal="用户期望查询余额。")
        generator_cls.return_value = generator

        resp = await client.post(
            "/evaluate/generate-goal",
            json=_payload(
                [
                    {
                        "role": "user",
                        "content": "查询余额。",
                        "finish_reason": "extra",
                    }
                ]
            ),
        )

    assert resp.status_code == 200
    value = generator.generate.call_args.args[0]
    assert value.trajectory.messages[0].role == "user"
    assert not hasattr(value.trajectory.messages[0], "finish_reason")


@pytest.mark.asyncio
async def test_generate_goal_protocol_error_returns_500(client: AsyncClient) -> None:
    with patch("evo_agent.api.routes.evaluate.TrajectoryGoalGenerator") as generator_cls:
        generator = MagicMock()
        generator.generate.side_effect = EvaluationError("missing valid 'goal'")
        generator_cls.return_value = generator

        resp = await client.post(
            "/evaluate/generate-goal",
            json=_payload([{"role": "user", "content": "查询余额。"}]),
        )

    assert resp.status_code == 500
    assert "Goal generation failed" in resp.json()["detail"]
