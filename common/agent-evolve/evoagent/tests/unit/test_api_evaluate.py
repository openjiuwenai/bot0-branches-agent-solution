"""POST /evaluate 路由 API 单元测试 — 请求/响应映射 + 状态码 + 错误分支。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from unittest.mock import patch

import pytest
from httpx import ASGITransport, AsyncClient

from evo_agent.api.app import app
from evo_agent.evaluator.domain.result import EvaluationResult
from evo_agent.evaluator.domain.scoring import EvaluationError

_EVALUATE_PATCH = "evo_agent.api.routes.evaluate.LLMEvaluator"


@pytest.fixture
async def client() -> AsyncClient:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


def _trajectory_json(tmp_path: Path, *, messages: list[dict[str, Any]] | None = None) -> str:
    """写入合法轨迹 JSON 文件并返回路径。"""
    path = tmp_path / "traj.json"
    payload = {"messages": messages or [{"role": "user", "content": "hi"}]}
    path.write_text(json.dumps(payload), encoding="utf-8")
    return str(path)


def _request_body(
    trajectory_path: str,
    *,
    filters: dict[str, Any] | None = None,
    expected_result: dict[str, Any] | None = None,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "trajectory_path": trajectory_path,
        "prompt_template": "评估这条轨迹 {messages}",
        "llm_config": {
            "model_name": "test-model",
            "api_key": "key",
            "api_base": "http://localhost",
        },
        "skill_names": ["product_recommend_skill"],
    }
    if filters is not None:
        body["filters"] = filters
    if expected_result is not None:
        body["expected_result"] = expected_result
    return body


class _FakeLLMEvaluator:
    """桩件 LLMEvaluator — 避免 Model 真实初始化，evaluate/evaluate_input 返回可控结果。"""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        self.kwargs = kwargs

    def _result(self) -> EvaluationResult:
        result = getattr(_FakeLLMEvaluator, "_next_result", None)
        if isinstance(result, Exception):
            raise result
        return result  # type: ignore[return-value]

    def evaluate_input(self, value: Any) -> EvaluationResult:
        return self._result()

    def evaluate(self, case: Any, predict: Any) -> Any:
        # FilteringEvaluator 无匹配时会调用 delegate.evaluate —— 转成 EvaluatedCase 形态。
        from openjiuwen.agent_evolving.dataset import EvaluatedCase

        result = self._result()
        evaluated = EvaluatedCase(case=case, answer={"evaluation_source": "conversation_trajectory"})
        evaluated.score = result.score
        evaluated.per_metric = result.per_metric
        evaluated.reason = ""
        return evaluated


def _patch_llm(result: EvaluationResult | Exception) -> Any:
    """patch 路由模块中的 LLMEvaluator，并预设 evaluate_input 的返回/异常。"""
    _FakeLLMEvaluator._next_result = result  # type: ignore[attr-defined]
    return patch(_EVALUATE_PATCH, _FakeLLMEvaluator)


def _make_result(*, status: str = "evaluated", score: float = 0.8) -> EvaluationResult:
    return EvaluationResult(status=status, score=score, is_pass=True)


# ── 200 成功路径 ──


class TestSuccessResponse:
    async def test_returns_evaluated_result(self, client: AsyncClient, tmp_path: Path) -> None:
        body = _request_body(_trajectory_json(tmp_path))
        with _patch_llm(_make_result(score=0.85)):
            resp = await client.post("/evaluate", json=body)

        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "evaluated"
        assert data["score"] == 0.85
        assert data["is_pass"] is True
        assert data["attributed_skill"] == ""

    async def test_filtered_result_with_matches(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        """轨迹含失败 tool 消息 → ToolFailureFilter 命中 → status=filtered。"""
        traj_path = _trajectory_json(
            tmp_path,
            messages=[
                {"role": "user", "content": "任务"},
                {"role": "assistant", "content": ""},
                {"role": "tool", "tool_call_id": "c1", "content": "operation timeout"},
            ],
        )
        body = _request_body(
            traj_path,
            filters={"tool_failure": {"enabled": True}},
        )
        # 未匹配时会委托给 LLMEvaluator；这里用桩件兜底（本例会命中过滤，不会真正委托）
        with _patch_llm(_make_result()):
            resp = await client.post("/evaluate", json=body)

        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "filtered"
        assert data["score"] == 0.0
        assert data["is_pass"] is False
        assert len(data["filter_matches"]) == 1
        assert data["filter_matches"][0]["filter_type"] == "tool_failure"

    async def test_filters_present_but_none_enabled_uses_llm_directly(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        """filters 配置存在但无 enabled → 直接用 LLMEvaluator（仍返回 evaluated）。"""
        body = _request_body(
            _trajectory_json(tmp_path),
            filters={"tool_failure": {"enabled": False}, "user_feedback": {"enabled": False}},
        )
        with _patch_llm(_make_result()):
            resp = await client.post("/evaluate", json=body)
        assert resp.status_code == 200


# ── 404 ──


class TestNotFound:
    async def test_missing_trajectory_file_returns_404(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        body = _request_body(str(tmp_path / "missing.json"))
        resp = await client.post("/evaluate", json=body)
        assert resp.status_code == 404
        assert "not found" in resp.json()["detail"].lower()


# ── 422 校验/格式错误 ──


class TestValidationError:
    async def test_missing_required_field_returns_422(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        body = _request_body(_trajectory_json(tmp_path))
        del body["llm_config"]  # 缺少必填字段
        resp = await client.post("/evaluate", json=body)
        assert resp.status_code == 422

    async def test_missing_skill_names_returns_422(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        body = _request_body(_trajectory_json(tmp_path))
        del body["skill_names"]
        resp = await client.post("/evaluate", json=body)
        assert resp.status_code == 422

    async def test_invalid_trajectory_format_returns_422(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        path = tmp_path / "bad.json"
        path.write_text("{ not valid json", encoding="utf-8")
        body = _request_body(str(path))
        resp = await client.post("/evaluate", json=body)
        assert resp.status_code == 422
        assert "invalid trajectory format" in resp.json()["detail"].lower()

    async def test_trajectory_with_extra_fields_still_ok(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        """轨迹文件含未知字段仍可加载（_load_trajectory 仅取 messages/summary）。"""
        path = tmp_path / "extra.json"
        payload = {"messages": [{"role": "user", "content": "hi"}], "unknown_field": "x"}
        path.write_text(json.dumps(payload), encoding="utf-8")
        body = _request_body(str(path))
        with _patch_llm(_make_result()):
            resp = await client.post("/evaluate", json=body)
        assert resp.status_code == 200

    async def test_invalid_filter_pattern_returns_422(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        """非法正则 pattern → _build_filters 抛 re.error → 422。"""
        body = _request_body(
            _trajectory_json(tmp_path),
            filters={"tool_failure": {"enabled": True, "patterns": ["[unclosed"]}},
        )
        with _patch_llm(_make_result()):
            resp = await client.post("/evaluate", json=body)
        assert resp.status_code == 422
        assert "filter configuration" in resp.json()["detail"].lower()


# ── 500 评估失败 ──


class TestEvaluationFailure:
    async def test_evaluation_error_returns_500(
        self, client: AsyncClient, tmp_path: Path
    ) -> None:
        body = _request_body(_trajectory_json(tmp_path))
        with _patch_llm(EvaluationError("LLM down")):
            resp = await client.post("/evaluate", json=body)
        assert resp.status_code == 500
        assert "evaluation failed" in resp.json()["detail"].lower()
