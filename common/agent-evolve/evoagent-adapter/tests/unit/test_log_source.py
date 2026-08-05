"""LogTraceSource 单测 —— 读 output_dir JSONL 归档 (现 routes 逻辑迁入后的行为)。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from agent_adapter.trace_source.log_source import LogTraceSource


def _write_archive(output_dir: Path, conv: str, records: list[dict]) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"{conv}.jsonl"
    path.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in records) + "\n",
                    encoding="utf-8")
    return path


@pytest.fixture
def two_agent_dirs(tmp_path: Path) -> dict[str, Path]:
    return {
        "edp": tmp_path / "output_edp",
        "fund": tmp_path / "output_fund",
    }


async def test_list_conversations_single_agent(two_agent_dirs):
    _write_archive(two_agent_dirs["edp"], "conv1", [{"type": "GENERATION", "id": "g1"}])
    _write_archive(two_agent_dirs["edp"], "conv2", [{"type": "TOOL", "id": "t1"}])
    _write_archive(two_agent_dirs["fund"], "conv3", [{"type": "GENERATION", "id": "g2"}])
    src = LogTraceSource(two_agent_dirs)
    assert await src.list_conversations("edp") == ["conv1", "conv2"]


async def test_list_conversations_all_agents(two_agent_dirs):
    _write_archive(two_agent_dirs["edp"], "conv1", [{"type": "GENERATION"}])
    _write_archive(two_agent_dirs["fund"], "conv3", [{"type": "GENERATION"}])
    src = LogTraceSource(two_agent_dirs)
    assert await src.list_conversations() == ["conv1", "conv3"]


async def test_get_records_reads_archive(two_agent_dirs):
    recs = [
        {"type": "GENERATION", "id": "g1", "input": {"messages": []}, "output": {"role": "assistant"}},
        {"type": "TOOL", "id": "t1"},
    ]
    _write_archive(two_agent_dirs["edp"], "conv1", recs)
    src = LogTraceSource(two_agent_dirs)
    got = await src.get_records("edp", "conv1")
    assert got == recs


async def test_get_records_not_found_returns_empty(two_agent_dirs):
    src = LogTraceSource(two_agent_dirs)
    assert await src.get_records("edp", "missing") == []


async def test_get_records_all_agents_search(two_agent_dirs):
    """agent_name=None 时跨所有 agent 目录查找会话归档。"""
    _write_archive(two_agent_dirs["fund"], "conv3", [{"type": "GENERATION", "id": "g"}])
    src = LogTraceSource(two_agent_dirs)
    got = await src.get_records(None, "conv3")
    assert len(got) == 1 and got[0]["id"] == "g"


async def test_get_records_skips_invalid_lines(two_agent_dirs):
    """空行/非法 JSON 行跳过, 不中断解析。"""
    d = two_agent_dirs["edp"]
    d.mkdir(parents=True, exist_ok=True)
    (d / "conv1.jsonl").write_text(
        json.dumps({"type": "GENERATION", "id": "g1"}) + "\n\nnot json\n"
        + json.dumps({"type": "TOOL", "id": "t1"}) + "\n",
        encoding="utf-8",
    )
    src = LogTraceSource(two_agent_dirs)
    got = await src.get_records("edp", "conv1")
    assert [r["id"] for r in got] == ["g1", "t1"]
