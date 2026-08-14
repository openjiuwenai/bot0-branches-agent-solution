"""evaluate_agent_judge 路由集成测试 — 提交 / 轮询 / SSE / 校验 / 错误事件。

stub create_evaluator 返回固定 EvaluatedCase，断言响应体暴露复数归因
（skill_attributions / attribution_status / dimensions），证明 route 直读
reason JSON 而非 lossy 的 from_evaluated_case 路径。
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.api.app import create_app
from evo_agent.api.jobs import job_manager
from evo_agent.evaluator.domain.scoring import EvaluationError


def _trajectory_file(tmp_path: Path) -> str:
    path = tmp_path / "traj.json"
    path.write_text(
        json.dumps(
            {
                "messages": [
                    {"role": "user", "content": "hi"},
                    {"role": "assistant", "content": "hello"},
                ]
            }
        ),
        encoding="utf-8",
    )
    return str(path)


def _build_evaluated() -> EvaluatedCase:
    case = Case(inputs={"x": 1}, label={"y": 2})
    ev = EvaluatedCase(case=case, answer={})
    ev.score = 0.8
    ev.per_metric = {"task_completion": 0.8, "safety": 0.9}
    ev.reason = json.dumps(
        {
            "reason": "agent-as-judge: 2 dimensions, overall=0.800, status=completed",
            "is_pass": True,
            "attributed_skill": "alpha_skill",
            "repaired": False,
            "parse_mode": "exact",
            "repair_operations": [],
            "dimensions": {"task_completion": 0.8, "safety": 0.9},
            "skill_attributions": [
                {
                    "skill_name": "alpha_skill",
                    "usage_status": "executed",
                    "impact": "positive",
                    "reason": "skill used correctly",
                }
            ],
            "attribution_status": "completed",
            "attribution_error": None,
        },
        ensure_ascii=False,
    )
    return ev


class _FakeAgentEvaluator:
    """假 AgentEvaluator — 返回固定 EvaluatedCase（或抛 EvaluationError）。"""

    def __init__(self, evaluated: EvaluatedCase, *, raise_exc: BaseException | None = None) -> None:
        self._evaluated = evaluated
        self._raise = raise_exc
        self.progress_callback: Any = None
        self.calls: list[tuple[Any, Any]] = []

    def evaluate(self, case: Any, predict: Any) -> EvaluatedCase:
        self.calls.append((case, predict))
        if self._raise is not None:
            raise self._raise
        return self._evaluated


def _patch_create(monkeypatch: pytest.MonkeyPatch, fake: _FakeAgentEvaluator) -> None:
    import evo_agent.api.routes.evaluate_agent_judge as route_mod

    monkeypatch.setattr(route_mod, "create_evaluator", lambda _cfg: fake)


def _submit(
    client: TestClient, traj_path: str, *, preset: str = "default", **overrides: Any
) -> Any:
    body: dict[str, Any] = {
        "trajectory_path": traj_path,
        "preset": preset,
        "skill_names": ["alpha_skill"],
        "llm_config": {
            "model_name": "m",
            "api_key": "k",
            "api_base": "http://x",
            "client_provider": "OpenAI",
        },
    }
    body.update(overrides)
    resp = client.post("/evaluate/agent-judge", json=body)
    return resp


def _wait_terminal(client: TestClient, job_id: str, timeout: float = 10.0) -> dict[str, Any]:
    elapsed = 0.0
    while elapsed < timeout:
        resp = client.get(f"/evaluate/agent-judge/jobs/{job_id}")
        assert resp.status_code == 200
        body = resp.json()
        if body["status"] in ("completed", "failed", "cancelled"):
            return body
        time.sleep(0.02)
        elapsed += 0.02
    raise AssertionError(f"job {job_id} timed out")


@pytest.fixture(autouse=True)
def _reset_job_store() -> Any:
    job_manager._jobs.clear()
    yield


# ---------------------------------------------------------------------------
# 成功路径
# ---------------------------------------------------------------------------


def test_submit_poll_completed(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    fake = _FakeAgentEvaluator(_build_evaluated())
    _patch_create(monkeypatch, fake)
    client = TestClient(create_app())
    traj = _trajectory_file(tmp_path)

    submit = _submit(client, traj)
    assert submit.status_code == 200, submit.text
    body = submit.json()
    assert body["status"] == "queued"
    assert body["job_id"]

    result_body = _wait_terminal(client, body["job_id"])
    assert result_body["status"] == "completed"
    result = result_body["result"]
    assert result is not None
    # route reads reason JSON directly → plural attribution exposed
    assert result["score"] == 0.8
    assert result["is_pass"] is True
    assert result["attribution_status"] == "completed"
    assert result["attributed_skill"] == "alpha_skill"
    assert result["dimensions"] == {"task_completion": 0.8, "safety": 0.9}
    assert result["skill_attributions"][0]["skill_name"] == "alpha_skill"
    assert result["skill_attributions"][0]["impact"] == "positive"
    # fake evaluator was actually invoked once with (case, placeholder)
    assert len(fake.calls) == 1


def test_sse_replays_progress_and_completed(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = _FakeAgentEvaluator(_build_evaluated())
    _patch_create(monkeypatch, fake)
    client = TestClient(create_app())
    traj = _trajectory_file(tmp_path)

    submit = _submit(client, traj)
    job_id = submit.json()["job_id"]
    _wait_terminal(client, job_id)  # ensure terminal before streaming

    resp = client.get(f"/evaluate/agent-judge/jobs/{job_id}/stream")
    assert resp.status_code == 200
    text = resp.text
    assert "event: progress" in text
    assert "event: completed" in text
    assert "alpha_skill" in text  # completed event carries the rich result body


def test_progress_callback_pushes_judge_progress(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """evaluator.progress_callback 被设置 + 转成 SSE progress 事件。"""
    fake = _FakeAgentEvaluator(_build_evaluated())
    _patch_create(monkeypatch, fake)
    client = TestClient(create_app())
    traj = _trajectory_file(tmp_path)

    submit = _submit(client, traj)
    job_id = submit.json()["job_id"]
    body = _wait_terminal(client, job_id)
    assert body["status"] == "completed"
    # the route installs a progress_callback on the fake evaluator
    assert fake.progress_callback is not None
    # initial "running" progress event was pushed
    resp = client.get(f"/evaluate/agent-judge/jobs/{job_id}")
    progress = resp.json()["progress"]
    assert progress is not None
    assert progress["phase"] == "judge"


# ---------------------------------------------------------------------------
# 错误路径
# ---------------------------------------------------------------------------


def test_evaluation_error_event_failed(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    fake = _FakeAgentEvaluator(
        _build_evaluated(),
        raise_exc=EvaluationError(category="attribution_unknown_skill", safe_message="ghost skill"),
    )
    _patch_create(monkeypatch, fake)
    client = TestClient(create_app())
    traj = _trajectory_file(tmp_path)

    submit = _submit(client, traj)
    body = _wait_terminal(client, submit.json()["job_id"])
    assert body["status"] == "failed"
    assert "ghost skill" in body["error"]

    resp = client.get(f"/evaluate/agent-judge/jobs/{submit.json()['job_id']}/stream")
    assert "event: error" in resp.text
    assert "attribution_unknown_skill" in resp.text


# ---------------------------------------------------------------------------
# 校验：422 / 404
# ---------------------------------------------------------------------------


def test_missing_trajectory_file_422(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _patch_create(monkeypatch, _FakeAgentEvaluator(_build_evaluated()))
    client = TestClient(create_app())
    resp = _submit(client, str(tmp_path / "missing.json"))
    assert resp.status_code == 422
    assert "not found" in resp.text.lower()


def test_unknown_preset_422(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _patch_create(monkeypatch, _FakeAgentEvaluator(_build_evaluated()))
    client = TestClient(create_app())
    traj = _trajectory_file(tmp_path)
    resp = _submit(client, traj, preset="definitely_not_a_preset")
    assert resp.status_code == 422
    assert "Unknown judge preset" in resp.text


def test_empty_skill_names_422(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _patch_create(monkeypatch, _FakeAgentEvaluator(_build_evaluated()))
    client = TestClient(create_app())
    traj = _trajectory_file(tmp_path)
    resp = _submit(client, traj, skill_names=[])
    assert resp.status_code == 422


def test_unknown_client_provider_422(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _patch_create(monkeypatch, _FakeAgentEvaluator(_build_evaluated()))
    client = TestClient(create_app())
    traj = _trajectory_file(tmp_path)
    resp = _submit(
        client,
        traj,
        llm_config={
            "model_name": "m",
            "api_key": "k",
            "api_base": "http://x",
            "client_provider": "UnknownProvider",
        },
    )
    assert resp.status_code == 422
    assert "UnknownProvider" in resp.text


def test_get_job_not_found_404() -> None:
    client = TestClient(create_app())
    resp = client.get("/evaluate/agent-judge/jobs/missing")
    assert resp.status_code == 404
