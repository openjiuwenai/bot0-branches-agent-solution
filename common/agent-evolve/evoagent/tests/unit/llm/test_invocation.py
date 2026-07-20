"""统一 LLM invocation Module 的行为测试。"""

import asyncio
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from openjiuwen.core.foundation.llm.schema.message import UserMessage

from evo_agent.llm.icbc_model_client import ICBCStreamIntegrityError
from evo_agent.llm.invocation import (
    LLMInvocation,
    LLMInvocationContext,
    LLMInvocationError,
    LLMInvocationRequest,
    LLMProviderCapabilities,
    LLMRetryPolicy,
    PromptBudgetExceededError,
)


async def test_prompt_budget_is_enforced_before_provider_call() -> None:
    """输入、输出预留和 safety margin 的总和超限时不得调用 provider。"""
    provider = AsyncMock()
    invocation = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(
            context_window_tokens=20,
            supports_max_output_tokens=False,
            supports_finish_reason=False,
            supports_usage=False,
            supports_json_mode=False,
            completion_signal="done",
        ),
        parallelism=2,
        safety_margin_tokens=4,
        chars_per_token=1.0,
    )
    request = LLMInvocationRequest(
        stage="evaluator",
        messages=(UserMessage(content="x" * 13),),
        context=LLMInvocationContext(run_id="run-1", case_id="case-1"),
        retry_policy=LLMRetryPolicy(1, 1.0, 1.0, 0.0, 0.0),
        reserved_output_tokens=4,
    )

    with pytest.raises(PromptBudgetExceededError):
        await invocation.invoke(request)

    provider.assert_not_awaited()


async def test_length_finish_is_not_returned_as_complete_structured_output() -> None:
    """模型明确因长度截断时，调用层必须先分类，不能交给 JSON repair。"""
    response = type(
        "Response",
        (),
        {"content": '{"score":', "finish_reason": "length", "metadata": {}},
    )()
    provider = MagicMock()
    provider.invoke = AsyncMock(return_value=response)
    invocation = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(
            context_window_tokens=100,
            supports_max_output_tokens=False,
            supports_finish_reason=True,
            supports_usage=False,
            supports_json_mode=False,
            completion_signal="either",
        ),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=10,
    )
    validator = MagicMock(return_value=True)
    classifier = MagicMock(return_value="syntax")
    request = LLMInvocationRequest(
        stage="evaluator",
        messages=(UserMessage(content="short"),),
        context=LLMInvocationContext(run_id="run-1"),
        retry_policy=LLMRetryPolicy(1, 1.0, 1.0, 0.0, 0.0),
        result_validator=validator,
        result_error_classifier=classifier,
    )

    with pytest.raises(LLMInvocationError) as exc_info:
        await invocation.invoke(request)

    assert exc_info.value.category == "finish_reason_length"
    validator.assert_not_called()
    classifier.assert_not_called()


async def test_stage_output_reserve_participates_in_preflight_budget() -> None:
    """未显式覆盖时，阶段 reserve 仍必须纳入最终 prompt 预算等式。"""
    provider = MagicMock()
    provider.invoke = AsyncMock()
    invocation = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(
            context_window_tokens=20,
            supports_max_output_tokens=False,
            supports_finish_reason=False,
            supports_usage=False,
            supports_json_mode=False,
            completion_signal="either",
        ),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=2,
        stage_output_reserve_tokens={"reflect": 10},
    )
    request = LLMInvocationRequest(
        stage="reflect",
        messages=(UserMessage(content="x" * 10),),
        context=LLMInvocationContext(run_id="run-1"),
        retry_policy=LLMRetryPolicy(1, 1.0, 1.0, 0.0, 0.0),
    )

    with pytest.raises(PromptBudgetExceededError):
        await invocation.invoke(request)

    provider.invoke.assert_not_awaited()


async def test_retry_backoff_never_sleeps_past_total_budget() -> None:
    """失败调用耗尽 total budget 后不得再执行陈旧 remaining 值的 backoff。"""
    provider = MagicMock()
    provider.invoke = AsyncMock(side_effect=ConnectionError("reset"))
    invocation = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(100, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=5,
    )
    request = LLMInvocationRequest(
        stage="reflect",
        messages=(UserMessage(content="short"),),
        context=LLMInvocationContext(run_id="run-1"),
        retry_policy=LLMRetryPolicy(2, 10.0, 1.0, 1.0, 0.0),
    )

    with (
        patch(
            "evo_agent.llm.invocation.time",
            SimpleNamespace(monotonic=MagicMock(side_effect=[0.0, 0.0, 2.0, 2.0])),
        ),
        patch("evo_agent.llm.invocation.asyncio.sleep", new_callable=AsyncMock) as sleep,
        pytest.raises(LLMInvocationError, match="total budget"),
    ):
        await invocation.invoke(request)

    sleep.assert_not_awaited()


async def test_retry_messages_are_budgeted_and_used_after_retryable_failure() -> None:
    """阶段压缩 retry prompt 必须由同一 invocation Module 重新预算并发送。"""
    response = type("Response", (), {"content": "ok", "metadata": {}})()
    provider = MagicMock()
    provider.invoke = AsyncMock(side_effect=[ConnectionError("reset"), response])
    invocation = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(100, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=5,
    )
    request = LLMInvocationRequest(
        stage="reflect",
        messages=(UserMessage(content="primary prompt"),),
        retry_messages=(UserMessage(content="short retry"),),
        context=LLMInvocationContext(run_id="run-1"),
        retry_policy=LLMRetryPolicy(2, 1.0, 2.0, 0.0, 0.0),
    )

    result = await invocation.invoke(request)

    assert result.text == "ok"
    assert provider.invoke.await_args_list[0].args[0][0].content == "primary prompt"
    assert provider.invoke.await_args_list[1].args[0][0].content == "short retry"


async def test_provider_declared_stream_integrity_error_is_retried() -> None:
    """Adapter 声明的流完整性错误必须走统一 retry policy。"""

    class StreamIntegrityError(Exception):
        retryable = True

    provider = MagicMock()
    response = type("Response", (), {"content": "complete", "metadata": {}})()
    provider.invoke = AsyncMock(side_effect=[StreamIntegrityError("bad chunk"), response])
    invocation = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(100, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=5,
    )

    result = await invocation.invoke(
        LLMInvocationRequest(
            stage="evaluator",
            messages=(UserMessage(content="prompt"),),
            context=LLMInvocationContext(run_id="run-1"),
            retry_policy=LLMRetryPolicy(2, 1.0, 2.0, 0.0, 0.0),
        )
    )

    assert result.text == "complete"
    assert provider.invoke.await_count == 2


async def test_sync_and_async_callers_share_one_invocation_loop_and_limiter() -> None:
    """Evaluator worker 与 optimizer 协程必须汇入同一个 invocation loop。"""
    active = 0
    max_active = 0
    loop_ids: set[int] = set()
    lock = threading.Lock()

    class Provider:
        async def invoke(self, messages: object, **kwargs: object) -> object:
            nonlocal active, max_active
            del messages, kwargs
            with lock:
                active += 1
                max_active = max(max_active, active)
                loop_ids.add(id(asyncio.get_running_loop()))
            await asyncio.sleep(0.01)
            with lock:
                active -= 1
            return type("Response", (), {"content": "ok", "metadata": {}})()

    invocation = LLMInvocation(
        Provider(),
        capabilities=LLMProviderCapabilities(100, False, False, False, False, "either"),
        parallelism=2,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=5,
    )
    request = LLMInvocationRequest(
        stage="evaluator",
        messages=(UserMessage(content="prompt"),),
        context=LLMInvocationContext(run_id="run-1"),
        retry_policy=LLMRetryPolicy(1, 1.0, 2.0, 0.0, 0.0),
    )

    results = await asyncio.gather(
        invocation.invoke(request),
        *(asyncio.to_thread(invocation.invoke_sync, request) for _ in range(4)),
    )

    assert [result.text for result in results] == ["ok"] * 5
    assert len(loop_ids) == 1
    assert max_active == 2


def test_invocation_limiter_survives_repeated_asyncio_run_loops() -> None:
    """训练/回调反复创建 event loop 时不得复用已绑定到旧 loop 的 limiter。"""

    class Provider:
        async def invoke(self, messages: object, **kwargs: object) -> object:
            del messages, kwargs
            await asyncio.sleep(0.01)
            return type("Response", (), {"content": "ok", "metadata": {}})()

    invocation = LLMInvocation(
        Provider(),
        capabilities=LLMProviderCapabilities(100, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=5,
    )
    request = LLMInvocationRequest(
        stage="evaluator",
        messages=(UserMessage(content="prompt"),),
        context=LLMInvocationContext(run_id="run-1"),
        retry_policy=LLMRetryPolicy(1, 1.0, 2.0, 0.0, 0.0),
    )

    async def run_accumulation_round() -> list[str]:
        results = await asyncio.gather(invocation.invoke(request), invocation.invoke(request))
        return [result.text for result in results]

    for _step in range(3):
        for _accumulation in range(2):
            assert asyncio.run(run_accumulation_round()) == ["ok", "ok"]


async def test_icbc_stream_integrity_failure_keeps_transport_category() -> None:
    """ICBC 坏流重试耗尽后不能降级为普通 llm_invoke_error。"""
    provider = MagicMock()
    provider.invoke = AsyncMock(
        side_effect=[
            ICBCStreamIntegrityError(chunk_index=1, raw_payload="bad-1"),
            ICBCStreamIntegrityError(chunk_index=2, raw_payload="bad-2"),
        ]
    )
    invocation = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(100, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=5,
    )

    with pytest.raises(LLMInvocationError) as exc_info:
        await invocation.invoke(
            LLMInvocationRequest(
                stage="evaluator",
                messages=(UserMessage(content="prompt"),),
                context=LLMInvocationContext(run_id="run-1"),
                retry_policy=LLMRetryPolicy(2, 1.0, 2.0, 0.0, 0.0),
            )
        )

    assert exc_info.value.category == "transport_incomplete"


async def test_invalid_structured_output_retries_with_schema_diagnostics(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """完整但无效的结构化输出共用 invocation retry，并返回成功 attempt。"""
    invalid = type("Response", (), {"content": '{"goal":', "metadata": {}})()
    valid = type("Response", (), {"content": '{"goal": "done"}', "metadata": {}})()
    provider = MagicMock()
    provider.invoke = AsyncMock(side_effect=[invalid, valid])
    invocation = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(100, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=5,
    )
    classifier_calls: list[str] = []

    def classify(text: str) -> str:
        classifier_calls.append(text)
        return "syntax"

    with caplog.at_level("WARNING", logger="evo_agent.llm.invocation"):
        result = await invocation.invoke(
            LLMInvocationRequest(
                stage="goal_generator",
                messages=(UserMessage(content="full prompt"),),
                retry_messages=(UserMessage(content="return goal JSON"),),
                result_validator=lambda text: text == '{"goal": "done"}',
                result_error_classifier=classify,
                output_schema_name="goal_generation",
                context=LLMInvocationContext(run_id="run-1"),
                retry_policy=LLMRetryPolicy(2, 1.0, 2.0, 0.0, 0.0),
            )
        )

    assert result.text == '{"goal": "done"}'
    assert result.metadata["attempt"] == 2
    assert provider.invoke.await_count == 2
    assert classifier_calls == ['{"goal":']
    assert "schema_name=goal_generation" in caplog.text
    assert "output_error_category=syntax" in caplog.text
