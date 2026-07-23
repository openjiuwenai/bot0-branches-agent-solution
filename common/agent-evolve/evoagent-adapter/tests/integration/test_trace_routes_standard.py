"""standard 模式轨迹路由端到端测试 —— 真 PG + AsyncClient。

手动注入 app.state.repo / trace_source (bypass lifespan, 复用 conftest 的 repo),
验证 routes 经 DbTraceSource 读 PG: list/get records/cleaned-traces + complete 信号。
log 模式路由保真由 test_api.py / test_trace_cleaner.py 覆盖。
"""

from __future__ import annotations

import json
import textwrap
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from agent_adapter.api.app import create_app
from agent_adapter.config import load_config
from agent_adapter.repository.aggregation import compute_trace_summary
from agent_adapter.trace_source.db_source import DbTraceSource
from tests.integration import _pgutil

_by_trace = _pgutil.by_trace


def _write_yaml_config(tmp_path: Path, trace_wait_timeout: float = 2.0) -> Path:
    log_dir = tmp_path / "logs"
    log_dir.mkdir()
    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(textwrap.dedent(f"""\
        trace_wait_timeout: {trace_wait_timeout}
        agents:
          - name: edp_agent
            log_dir: {log_dir}
            output_dir: {tmp_path}/output/edp
            offset_file: {tmp_path}/offsets/edp.json
    """), encoding="utf-8")
    return yaml_path


@pytest.fixture
def standard_app(tmp_path):
    """建 standard 模式 app (log_dir 有效供 poll; repo/trace_source 由用例注入)。"""
    config = load_config(_write_yaml_config(tmp_path))
    app = create_app(config)
    return app


async def test_standard_list_and_get_records(standard_app, repo, jsonl_spans):
    app = standard_app
    app.state.repo = repo
    app.state.trace_source = DbTraceSource(repo)
    await repo.bulk_insert_spans(jsonl_spans)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 列全部会话
        r = await client.get("/api/v1/traces")
        assert r.status_code == 200
        convs = set(r.json()["conversation_ids"])
        # 期望 = 各 trace 经 compute_trace_summary 算出的 session_id, 过滤 None
        # (list_conversations 过滤无 session.id 的 trace; compute 用 _first_attr 取首个非空 session.id)
        expected = {compute_trace_summary(tid, tspans)["session_id"]
                    for tid, tspans in _by_trace(jsonl_spans).items()}
        expected.discard(None)
        assert convs == expected

        # 取某会话 records + complete 信号 (根 span 已在 → complete=True)
        conv = jsonl_spans[0]["session_id"]
        r = await client.get(f"/api/v1/traces/{conv}")
        data = r.json()
        assert data["conversation_id"] == conv
        assert data["complete"] is True
        assert data["total"] > 0
        # 至少含一个 GENERATION record
        assert any(rec.get("type") == "GENERATION" for rec in data["calls"])


async def test_standard_cleaned_traces_matches_user_query(standard_app, repo, jsonl_spans):
    app = standard_app
    app.state.repo = repo
    app.state.trace_source = DbTraceSource(repo)
    await repo.bulk_insert_spans(jsonl_spans)

    conv = jsonl_spans[0]["session_id"]
    root = next(s for s in jsonl_spans if s["session_id"] == conv
                and s.get("kind") == "SERVER" and not s.get("parent_span_id"))
    rb_raw = (root.get("attributes") or {}).get("openjiuwen.http.request_body")
    request_body = json.loads(rb_raw) if isinstance(rb_raw, str) else rb_raw
    user_query = request_body["input"]["query"]  # V3 真实: input.query (非 user_query)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get(f"/api/v1/agents/edp_agent/cleaned-traces/{conv}")
        assert r.status_code == 200
        cleaned = r.json()
        assert cleaned["task_input"] == user_query
        assert len(cleaned["messages"]) > 0


async def test_standard_complete_false_when_no_root(standard_app, repo, jsonl_spans):
    """无根 span (会话未结束/未上报) → complete=False (等待 trace_wait_timeout 后)。"""
    app = standard_app
    app.state.repo = repo
    app.state.trace_source = DbTraceSource(repo)
    # 仅插入一条非根 INTERNAL span (新会话, 无 SERVER 根)
    no_root = [{**jsonl_spans[0], "kind": "INTERNAL", "parent_span_id": "p0",
                "session_id": "no-root-conv", "trace_id": "no-root-trace",
                "span_id": "nr-s1"}]
    await repo.bulk_insert_spans(no_root)

    import time
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        t0 = time.monotonic()
        r = await client.get("/api/v1/traces/no-root-conv")
        elapsed = time.monotonic() - t0
        data = r.json()
        assert data["complete"] is False
        # 确实等待了 (≈ trace_wait_timeout=2s), 非立即返回
        assert elapsed >= 1.5


async def test_standard_agent_traces_404(standard_app, repo):
    """未知 agent → 404 (契约保持)。"""
    app = standard_app
    app.state.repo = repo
    app.state.trace_source = DbTraceSource(repo)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/api/v1/agents/nonexistent/traces")
        assert r.status_code == 404
