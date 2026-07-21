"""B4 (#16): LLM 重试传入精简 retry_prompt — 单元测试。"""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from evo_agent.llm.invocation import (
    LLMInvocation,
    LLMInvocationContext,
    LLMProviderCapabilities,
)
from evo_agent.optimizer.llm_resilience import (
    LLMInvokePolicy,
    invoke_text_with_retry,
)
from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)
from evo_agent.optimizer.skill_document.types import Edit

# ── slim prompt 构造 ──


def _make_optimizer_for_prompt() -> SkillDocumentOptimizer:
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)  # type: ignore[no-untyped-call]
    opt._step_buffer = [  # type: ignore[attr-defined]
        {"summary": "step-buffer-should-be-omitted-in-slim", "score": 0.5}
    ]
    opt._meta_skill_context = "meta-memory-should-be-omitted-in-slim"  # type: ignore[attr-defined]
    opt._scheduler = MagicMock()  # type: ignore[attr-defined]
    opt._scheduler.max_lr = 5
    return opt


def test_build_analyst_prompt_slim_omits_step_buffer_and_meta() -> None:
    """slim retry prompt 去掉 step_buffer 与 meta_skill，只留核心。"""
    opt = _make_optimizer_for_prompt()
    step_buffer_ctx = "## step-buffer-content-should-be-omitted"
    meta_ctx = "meta-memory-should-be-omitted-in-slim"
    full = opt._build_analyst_prompt(  # type: ignore[attr-defined]
        "analyst_error",
        "SKILL CONTENT",
        "TRAJECTORIES",
        step_buffer_ctx,
        meta_ctx,
    )
    slim = opt._build_analyst_prompt(  # type: ignore[attr-defined]
        "analyst_error",
        "SKILL CONTENT",
        "TRAJECTORIES",
        step_buffer_ctx,
        meta_ctx,
        slim=True,
    )
    assert "step-buffer-content-should-be-omitted" not in slim
    assert "meta-memory-should-be-omitted-in-slim" not in slim
    assert "Previous Steps in This Epoch" not in slim
    assert "Optimizer Memory" not in slim
    assert "SKILL CONTENT" in slim
    assert "TRAJECTORIES" in slim
    assert len(slim) < len(full)


# ── retry 机制使用 retry_prompt ──


def _invocation(provider: MagicMock) -> LLMInvocation:
    return LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(32768, False, True, True, True, "either"),
        parallelism=1,
        safety_margin_tokens=512,
        chars_per_token=2.0,
    )


@pytest.mark.asyncio
async def test_invoke_uses_retry_prompt_on_timeout() -> None:
    """首次 attempt 超时后，retry 使用精简 retry_prompt。"""
    llm = MagicMock()
    full_prompt = "x" * 5000
    slim_prompt = "y" * 100

    call_args: list[str] = []

    async def _invoke(messages: list[object], **kwargs: object) -> MagicMock:
        del kwargs
        call_args.append(str(getattr(messages[0], "content")))
        if len(call_args) == 1:
            raise TimeoutError("simulated timeout")
        return MagicMock(content='{"edits":[]}')

    llm.invoke = _invoke
    invocation = _invocation(llm)
    policy = LLMInvokePolicy(
        attempt_timeout_secs=90, total_budget_secs=300, max_attempts=2, backoff_base_secs=0
    )
    raw = await invoke_text_with_retry(
        invocation, "model", full_prompt, policy=policy, retry_prompt=slim_prompt
    )
    assert raw == '{"edits":[]}'
    assert len(call_args) == 2
    assert call_args[0] == full_prompt  # 首次用全量
    assert call_args[1] == slim_prompt  # 重试用精简


@pytest.mark.asyncio
async def test_invoke_without_retry_prompt_retries_same_budgeted_prompt() -> None:
    """没有压缩 prompt 时仍按统一 policy 重试，但不绕过 total budget。"""
    llm = MagicMock()
    full_prompt = "x" * 5000
    call_count = 0

    async def _invoke(messages: list[object], **kwargs: object) -> MagicMock:
        del messages, kwargs
        nonlocal call_count
        call_count += 1
        raise TimeoutError("timeout")

    llm.invoke = _invoke
    invocation = _invocation(llm)
    policy = LLMInvokePolicy(
        attempt_timeout_secs=90, total_budget_secs=300, max_attempts=2, backoff_base_secs=0
    )
    with pytest.raises(Exception):  # noqa: PT011 — build_error 抛业务异常
        await invoke_text_with_retry(invocation, "model", full_prompt, policy=policy)
    assert call_count == 2


@pytest.mark.asyncio
async def test_optimizer_helper_forwards_structured_output_contract() -> None:
    """Optimizer helper forwards validator, classifier, schema and invocation context."""
    llm = MagicMock()
    llm.invoke = AsyncMock(
        side_effect=[
            MagicMock(content='{"edits":'),
            MagicMock(content='{"edits": []}'),
        ]
    )
    invocation = _invocation(llm)
    policy = LLMInvokePolicy(
        attempt_timeout_secs=90,
        total_budget_secs=300,
        max_attempts=2,
        backoff_base_secs=0,
    )
    classifications: list[str] = []

    raw = await invoke_text_with_retry(
        invocation,
        "model",
        "full",
        policy=policy,
        retry_prompt="return edits JSON",
        result_validator=lambda text: text == '{"edits": []}',
        result_error_classifier=lambda text: classifications.append(text) or "syntax",
        output_schema_name="merge_final",
        context=LLMInvocationContext(run_id="run-1", operator_id="op-1"),
    )

    assert raw == '{"edits": []}'
    assert classifications == ['{"edits":']


@pytest.mark.asyncio
async def test_select_passes_slim_retry_prompt_without_meta() -> None:
    """_select (ranking) 传精简 retry_prompt，去掉 Optimizer Memory (I-5)。

    与 _aggregate 一致：超时重试时用去掉 meta_ctx 的精简 prompt，降低 retry
    延迟与 token 成本。
    """
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)  # type: ignore[no-untyped-call]
    opt._llm = MagicMock()  # type: ignore[attr-defined]
    opt._model = "model"  # type: ignore[attr-defined]
    opt._semaphore = asyncio.Semaphore(1)  # type: ignore[attr-defined]
    opt._format_meta_skill_context = lambda: "META-CTX-MUST-BE-OMITTED-IN-RETRY"  # type: ignore[method-assign]

    edits = [Edit(op="replace", content=f"c{i}", target="SKILL.md") for i in range(3)]
    captured: dict[str, str] = {}

    async def _fake_invoke(llm: object, model: object, prompt: str, **kw: object) -> object:
        captured["prompt"] = prompt
        captured["retry_prompt"] = str(kw.get("retry_prompt") or "")
        return MagicMock(
            text='{"selected_indices": [0]}',
            invocation_id="inv-test",
            metadata={},
            finish_reason=None,
        )

    with patch(
        "evo_agent.optimizer.skill_document.skill_document_optimizer.invoke_with_retry",
        _fake_invoke,
    ):
        selected = await opt._select(edits=edits, budget=1, skill_content="SKILL CONTENT")  # type: ignore[attr-defined]

    assert len(selected) == 1
    retry_prompt = captured["retry_prompt"]
    assert "META-CTX-MUST-BE-OMITTED-IN-RETRY" in captured["prompt"]
    assert "META-CTX-MUST-BE-OMITTED-IN-RETRY" not in retry_prompt
    assert "Optimizer Memory" not in retry_prompt
    assert "SKILL CONTENT" in retry_prompt  # 核心内容保留
    assert len(retry_prompt) < len(captured["prompt"])
