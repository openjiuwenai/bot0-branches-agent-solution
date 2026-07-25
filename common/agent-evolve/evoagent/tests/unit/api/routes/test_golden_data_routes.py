"""golden_data 路由单元测试 —— helper + 在线 EB 路由 + 离线建 GU job。

用独立小 app（只挂 golden_data router）避开 optimize router 链 optimizer 的 PEP 695
语法（3.12+）。generator/builder 用 Fake 类 mock 掉，不连真 LLM。
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from evo_agent.api.jobs import job_manager
from evo_agent.api.routes import golden_data as gd
from evo_agent.evaluator.golden_data.models import (
    ExpectedBehaviorItem,
    ExpectedBehaviorOutput,
    GUIndex,
)


def _app() -> FastAPI:
    app = FastAPI()
    app.include_router(gd.router)
    return app


def _llm_config() -> dict:
    return {"api_key": "x", "api_base": "http://x", "model_name": "dummy"}


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def test_load_trajectories_json_array() -> None:
    data = json.dumps(
        [
            {"messages": [{"role": "user", "content": "a"}]},
            {"messages": [{"role": "user", "content": "b"}]},
        ]
    ).encode()
    trajs = gd._load_trajectories(data, "traces.json")
    assert len(trajs) == 2
    assert trajs[0].messages[0].content == "a"


def test_load_trajectories_jsonl() -> None:
    data = (
        b'{"messages":[{"role":"user","content":"a"}]}\n'
        b'{"messages":[{"role":"user","content":"b"}]}\n'
    )
    trajs = gd._load_trajectories(data, "traces.jsonl")
    assert len(trajs) == 2


def test_load_trajectories_single_object() -> None:
    data = json.dumps({"messages": [{"role": "user", "content": "a"}]}).encode()
    trajs = gd._load_trajectories(data, "traces.json")
    assert len(trajs) == 1


def test_load_trajectories_invalid() -> None:
    with pytest.raises(ValueError):
        gd._load_trajectories(b"not json", "traces.json")


def test_build_trajectory_from_messages_missing_role() -> None:
    with pytest.raises(Exception):  # HTTPException
        gd._build_trajectory_from_messages([{"content": "no role"}])


def test_build_trajectory_from_messages_empty() -> None:
    with pytest.raises(Exception):
        gd._build_trajectory_from_messages([])


def test_build_skill_provider_local(tmp_path: Path) -> None:
    sp = gd._build_skill_provider(
        gd.BuildGUConfig(
            source="local",
            skill_root=str(tmp_path / "skills"),
            llm_config=gd.LLMConfig(**_llm_config()),
        )
    )
    assert sp.__class__.__name__ == "LocalSkillProvider"


def test_build_skill_provider_local_missing_root() -> None:
    with pytest.raises(Exception):  # HTTPException 422
        gd._build_skill_provider(
            gd.BuildGUConfig(
                source="local",
                skill_root="",
                llm_config=gd.LLMConfig(**_llm_config()),
            )
        )


def test_build_skill_provider_adapter_unsupported() -> None:
    with pytest.raises(Exception):  # HTTPException 501
        gd._build_skill_provider(
            gd.BuildGUConfig(
                source="adapter",
                adapter_url="http://adapter",
                agent_name="a",
                llm_config=gd.LLMConfig(**_llm_config()),
            )
        )


# ---------------------------------------------------------------------------
# 在线 EB 路由
# ---------------------------------------------------------------------------


def test_expected_behavior_route(monkeypatch) -> None:
    class FakeGen:
        def __init__(self, mc, cc) -> None:
            pass

        def generate(self, eb_input) -> ExpectedBehaviorOutput:
            return ExpectedBehaviorOutput(
                items=[
                    ExpectedBehaviorItem(
                        id="eb_1",
                        inputs="i",
                        expected_behavior="eb text",
                        result="通过",
                        reason="r",
                        scenario="s",
                    )
                ],
                metadata={"scenario": "s"},
            )

    monkeypatch.setattr(gd, "ExpectedBehaviorGenerator", FakeGen)
    client = TestClient(_app())
    resp = client.post(
        "/golden_data/expected-behavior",
        json={
            "messages": [{"role": "user", "content": "hi"}],
            "llm_config": _llm_config(),
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "generated"
    assert body["items"][0]["expected_behavior"] == "eb text"
    assert set(body["items"][0].keys()) == {"id", "inputs", "expected_behavior"}


def test_expected_behavior_route_empty_messages(monkeypatch) -> None:
    class FakeGen:
        def __init__(self, mc, cc) -> None:
            pass

        def generate(self, eb_input) -> ExpectedBehaviorOutput:
            return ExpectedBehaviorOutput()

    monkeypatch.setattr(gd, "ExpectedBehaviorGenerator", FakeGen)
    client = TestClient(_app())
    resp = client.post(
        "/golden_data/expected-behavior",
        json={"messages": [], "llm_config": _llm_config()},
    )
    assert resp.status_code == 422


# ---------------------------------------------------------------------------
# 离线建 GU 路由
# ---------------------------------------------------------------------------


def test_build_gu_route(monkeypatch, tmp_path: Path) -> None:
    class FakeBuilder:
        def __init__(self, mc, cc, sp, *, flat_threshold: int = 30) -> None:
            pass

        def build(self, traces, skill_names, batch_size: int = 10) -> GUIndex:
            return GUIndex(
                skills=["send_email"],
                mode="flat",
                last_run_id="run1",
                out_of_scope_count=0,
            )

    monkeypatch.setattr(gd, "GlobalUnderstandingBuilder", FakeBuilder)
    trace_data = json.dumps([{"messages": [{"role": "user", "content": "用 send_email"}]}]).encode()
    config = {
        "source": "local",
        "skill_root": str(tmp_path / "skills"),
        "llm_config": _llm_config(),
        "skill_names": ["send_email"],
    }

    with TestClient(_app()) as client:
        resp = client.post(
            "/golden_data/global-understanding",
            data={"config": json.dumps(config)},
            files={"file": ("traces.json", trace_data, "application/json")},
        )
        assert resp.status_code == 200, resp.text
        job_id = resp.json()["job_id"]

        # 轮询 job 完成（FakeBuilder.build sync，后台 to_thread 应很快完成）
        job = job_manager.get(job_id)
        for _ in range(50):
            if job.status.value in ("completed", "failed"):
                break
            import time as _t

            _t.sleep(0.1)

    assert job.status.value == "completed", job.error
    assert job.result["mode"] == "flat"
    assert job.result["skills"] == ["send_email"]


def test_build_gu_route_invalid_config(monkeypatch) -> None:
    class FakeBuilder:
        def __init__(self, mc, cc, sp, *, flat_threshold: int = 30) -> None:
            pass

        def build(self, traces, skill_names, batch_size: int = 10) -> GUIndex:
            return GUIndex()

    monkeypatch.setattr(gd, "GlobalUnderstandingBuilder", FakeBuilder)
    client = TestClient(_app())
    resp = client.post(
        "/golden_data/global-understanding",
        data={"config": "not json"},
        files={"file": ("t.json", b"[]", "application/json")},
    )
    assert resp.status_code == 422
