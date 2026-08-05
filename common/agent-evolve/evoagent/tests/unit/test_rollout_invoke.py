"""Tests for empty-extract retry during rollout invoke."""

from __future__ import annotations

import pytest

from evo_agent.evaluator.metrics.extract import AnswerFieldExtractConfig
from evo_agent.rollout_invoke import invoke_with_empty_extract_retry


@pytest.mark.asyncio
async def test_no_retry_without_extract_config() -> None:
    calls: list[str] = []

    async def invoke_once(cid: str) -> dict[str, str]:
        calls.append(cid)
        return {"answer": ""}

    answer, cid = await invoke_with_empty_extract_retry(
        invoke_once=invoke_once,
        new_conversation_id=lambda: f"c{len(calls)}",
        extract_cfg=None,
        max_attempts=3,
        backoff_secs=0.0,
        case_id="case-1",
        phase="val",
    )
    assert answer["answer"] == ""
    assert len(calls) == 1
    assert cid == "c0"


@pytest.mark.asyncio
async def test_retries_until_field_present() -> None:
    calls: list[str] = []
    cfg = AnswerFieldExtractConfig(fields=("responsibility",), prefer_values=("无责", "有责"))

    async def invoke_once(cid: str) -> dict[str, str]:
        calls.append(cid)
        if len(calls) < 3:
            return {"answer": "incomplete"}
        return {"answer": '<answer>{"responsibility": "无责"}</answer>'}

    n = {"i": 0}

    def new_cid() -> str:
        n["i"] += 1
        return f"cid-{n['i']}"

    answer, cid = await invoke_with_empty_extract_retry(
        invoke_once=invoke_once,
        new_conversation_id=new_cid,
        extract_cfg=cfg,
        max_attempts=3,
        backoff_secs=0.0,
        case_id="case-1",
        phase="train",
    )
    assert len(calls) == 3
    assert cid == "cid-3"
    assert "无责" in answer["answer"]


@pytest.mark.asyncio
async def test_exhausts_attempts_when_always_empty() -> None:
    cfg = AnswerFieldExtractConfig(fields=("responsibility",))
    calls: list[str] = []

    async def invoke_once(cid: str) -> dict[str, str]:
        calls.append(cid)
        return {"answer": ""}

    n = {"i": 0}

    def new_cid() -> str:
        n["i"] += 1
        return f"cid-{n['i']}"

    answer, cid = await invoke_with_empty_extract_retry(
        invoke_once=invoke_once,
        new_conversation_id=new_cid,
        extract_cfg=cfg,
        max_attempts=2,
        backoff_secs=0.0,
        case_id="case-1",
        phase="val",
    )
    assert len(calls) == 2
    assert cid == "cid-2"
    assert answer["answer"] == ""
