"""Run-scoped, provider-neutral LLM invocation with local budget enforcement."""

from __future__ import annotations

import asyncio
import concurrent.futures
import logging
import math
import random
import threading
import time
import uuid
from collections.abc import Callable, Coroutine, Mapping
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Any, Literal

from openjiuwen.core.foundation.llm.schema.message import BaseMessage

logger = logging.getLogger(__name__)

LLMStage = Literal[
    "evaluator",
    "goal_generator",
    "reflect",
    "merge",
    "ranking",
    "slow_update",
    "meta_skill",
]


@dataclass(frozen=True)
class LLMInvocationContext:
    run_id: str
    case_id: str | None = None
    operator_id: str | None = None
    epoch: int | None = None
    step: int | None = None


@dataclass(frozen=True)
class LLMRetryPolicy:
    max_attempts: int
    attempt_timeout_secs: float
    total_budget_secs: float
    backoff_base_secs: float
    backoff_jitter_secs: float


@dataclass(frozen=True)
class LLMInvocationRequest:
    stage: LLMStage
    messages: tuple[BaseMessage, ...]
    context: LLMInvocationContext
    retry_policy: LLMRetryPolicy
    retry_messages: tuple[BaseMessage, ...] | None = None
    result_validator: Callable[[str], bool] | None = None
    result_error_classifier: Callable[[str], str | None] | None = None
    output_schema_name: str | None = None
    reserved_output_tokens: int | None = None


@dataclass(frozen=True)
class LLMProviderCapabilities:
    context_window_tokens: int
    supports_max_output_tokens: bool
    supports_finish_reason: bool
    supports_usage: bool
    supports_json_mode: bool
    completion_signal: Literal["done", "eof", "either"]


@dataclass(frozen=True)
class LLMInvocationResult:
    invocation_id: str
    text: str
    finish_reason: str | None
    input_tokens: int | None
    output_tokens: int | None
    transport_complete: bool
    metadata: Mapping[str, Any] = field(default_factory=lambda: MappingProxyType({}))


class _InvocationEventLoop:
    """Process-wide event loop for every run-scoped invocation Module.

    Evaluators expose a synchronous interface and execute cases in worker
    threads. Routing both those workers and ordinary async callers through one
    loop keeps asyncio primitives and provider clients bound to a single loop.
    """

    def __init__(self) -> None:
        self._loop = asyncio.new_event_loop()
        self._started = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name="evo-llm-invocation",
            daemon=True,
        )
        self._thread.start()
        self._started.wait()

    def submit(
        self,
        coroutine: Coroutine[Any, Any, LLMInvocationResult],
    ) -> concurrent.futures.Future[LLMInvocationResult]:
        return asyncio.run_coroutine_threadsafe(coroutine, self._loop)

    def is_current(self) -> bool:
        try:
            return asyncio.get_running_loop() is self._loop
        except RuntimeError:
            return False

    def _run(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._started.set()
        self._loop.run_forever()


_INVOCATION_LOOP: _InvocationEventLoop | None = None
_INVOCATION_LOOP_LOCK = threading.Lock()


def _get_invocation_loop() -> _InvocationEventLoop:
    global _INVOCATION_LOOP
    if _INVOCATION_LOOP is None:
        with _INVOCATION_LOOP_LOCK:
            if _INVOCATION_LOOP is None:
                _INVOCATION_LOOP = _InvocationEventLoop()
    return _INVOCATION_LOOP


class LLMInvocationError(Exception):
    """Categorized invocation failure safe to propagate into an outcome."""

    def __init__(
        self,
        category: str,
        safe_message: str,
        *,
        result: LLMInvocationResult | None = None,
        output_error_category: str | None = None,
    ) -> None:
        super().__init__(safe_message)
        self.category = category
        self.safe_message = safe_message
        self.result = result
        self.output_error_category = output_error_category


class PromptBudgetExceededError(LLMInvocationError):
    def __init__(self, estimated: int, limit: int) -> None:
        super().__init__(
            "prompt_budget_exceeded",
            f"prompt budget exceeded: required={estimated} context_window={limit}",
        )
        self.estimated_tokens = estimated
        self.context_window_tokens = limit


class LLMInvocation:
    """Own prompt budgeting, retries, normalization, and the sole LLM limiter."""

    def __init__(
        self,
        provider: Any,
        *,
        capabilities: LLMProviderCapabilities,
        parallelism: int,
        safety_margin_tokens: int,
        chars_per_token: float,
        default_output_reserve_tokens: int = 2048,
        stage_output_reserve_tokens: Mapping[str, int] | None = None,
    ) -> None:
        if parallelism < 1:
            raise ValueError("parallelism must be positive")
        if chars_per_token <= 0:
            raise ValueError("chars_per_token must be positive")
        self._provider = provider
        self.capabilities = capabilities
        self._semaphore = asyncio.Semaphore(parallelism)
        self._safety_margin_tokens = safety_margin_tokens
        self._chars_per_token = chars_per_token
        self._default_output_reserve_tokens = default_output_reserve_tokens
        if stage_output_reserve_tokens is not None and not isinstance(
            stage_output_reserve_tokens, Mapping
        ):
            raise TypeError("stage_output_reserve_tokens must be a mapping")
        self._stage_output_reserve_tokens = dict(stage_output_reserve_tokens or {})
        if any(value < 1 for value in self._stage_output_reserve_tokens.values()):
            raise ValueError("stage output reserve tokens must be positive")

    async def invoke(self, request: LLMInvocationRequest) -> LLMInvocationResult:
        """Invoke from async code on the dedicated invocation event loop."""
        invocation_loop = _get_invocation_loop()
        if invocation_loop.is_current():
            return await self._invoke(request)
        future = invocation_loop.submit(self._invoke(request))
        try:
            return await asyncio.wrap_future(future)
        except asyncio.CancelledError:
            future.cancel()
            raise

    def invoke_sync(self, request: LLMInvocationRequest) -> LLMInvocationResult:
        """Invoke from synchronous evaluator workers on the shared event loop."""
        invocation_loop = _get_invocation_loop()
        if invocation_loop.is_current():
            raise RuntimeError("invoke_sync cannot block the invocation event loop")
        return invocation_loop.submit(self._invoke(request)).result()

    async def _invoke(self, request: LLMInvocationRequest) -> LLMInvocationResult:
        reserve = self.output_reserve_tokens(request.stage, request.reserved_output_tokens)
        input_tokens = self.estimate_messages(request.messages)
        required = input_tokens + reserve + self._safety_margin_tokens
        if required > self.capabilities.context_window_tokens:
            raise PromptBudgetExceededError(required, self.capabilities.context_window_tokens)
        if request.retry_policy.total_budget_secs <= 0:
            raise LLMInvocationError("total_budget_exceeded", "LLM total budget exhausted")

        invocation_id = str(uuid.uuid4())
        started = time.monotonic()
        last_error: Exception | None = None
        attempts = max(1, request.retry_policy.max_attempts)
        for attempt in range(1, attempts + 1):
            attempt_started = time.monotonic()
            current_messages = (
                request.retry_messages
                if attempt > 1 and request.retry_messages is not None
                else request.messages
            )
            current_input_tokens = self.estimate_messages(current_messages)
            current_required = current_input_tokens + reserve + self._safety_margin_tokens
            if current_required > self.capabilities.context_window_tokens:
                raise PromptBudgetExceededError(
                    current_required, self.capabilities.context_window_tokens
                )
            remaining = request.retry_policy.total_budget_secs - (time.monotonic() - started)
            if remaining <= 0:
                break
            try:
                async with self._semaphore:
                    async with asyncio.timeout(
                        min(request.retry_policy.attempt_timeout_secs, remaining)
                    ):
                        response = await self._invoke_provider(request, reserve, current_messages)
                result = self._normalize_response(
                    response,
                    invocation_id=invocation_id,
                    estimated_input_tokens=current_input_tokens,
                    attempt=attempt,
                    output_reserve_tokens=reserve,
                    stage=request.stage,
                )
                if not result.transport_complete:
                    raise LLMInvocationError(
                        "transport_incomplete",
                        "provider response was incomplete",
                        result=result,
                    )
                if result.finish_reason == "length":
                    raise LLMInvocationError(
                        "finish_reason_length",
                        "provider output reached its length limit",
                        result=result,
                    )
                if not result.text.strip():
                    raise LLMInvocationError(
                        "empty_response",
                        "provider returned empty output",
                        result=result,
                    )
                if request.result_validator is not None and not request.result_validator(
                    result.text
                ):
                    output_error_category = _classify_result_error(request, result.text)
                    raise LLMInvocationError(
                        "unusable_response",
                        "provider returned unusable structured output",
                        result=result,
                        output_error_category=output_error_category,
                    )
                logger.info(
                    "LLM attempt completed invocation_id=%s stage=%s attempt=%s "
                    "schema_name=%s latency_ms=%d finish_reason=%s transport_complete=%s",
                    invocation_id,
                    request.stage,
                    attempt,
                    request.output_schema_name or "none",
                    round((time.monotonic() - attempt_started) * 1000),
                    result.finish_reason or "unknown",
                    result.transport_complete,
                )
                return result
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                last_error = exc
                retryable = _is_retryable(exc) or (
                    request.retry_messages is not None
                    and isinstance(exc, LLMInvocationError)
                    and exc.category
                    in {"empty_response", "finish_reason_length", "unusable_response"}
                )
                logger.warning(
                    "LLM attempt failed invocation_id=%s stage=%s attempt=%s "
                    "schema_name=%s latency_ms=%d category=%s "
                    "output_error_category=%s finish_reason=%s transport_complete=%s retryable=%s",
                    invocation_id,
                    request.stage,
                    attempt,
                    request.output_schema_name or "none",
                    round((time.monotonic() - attempt_started) * 1000),
                    _declared_category(exc) or type(exc).__name__,
                    getattr(exc, "output_error_category", None) or "none",
                    (
                        exc.result.finish_reason or "unknown"
                        if isinstance(exc, LLMInvocationError) and exc.result is not None
                        else "unknown"
                    ),
                    (
                        exc.result.transport_complete
                        if isinstance(exc, LLMInvocationError) and exc.result is not None
                        else "unknown"
                    ),
                    retryable,
                )
                if attempt >= attempts or not retryable:
                    if isinstance(exc, LLMInvocationError):
                        raise
                    category = _declared_category(exc)
                    if category is not None:
                        raise LLMInvocationError(category, str(exc)) from exc
                    raise LLMInvocationError("llm_invoke_error", str(exc)) from exc
                delay = request.retry_policy.backoff_base_secs * (2 ** (attempt - 1))
                delay += random.uniform(0.0, request.retry_policy.backoff_jitter_secs)
                if delay > 0:
                    fresh_remaining = request.retry_policy.total_budget_secs - (
                        time.monotonic() - started
                    )
                    if fresh_remaining <= 0:
                        break
                    await asyncio.sleep(min(delay, fresh_remaining))
        raise LLMInvocationError(
            "total_budget_exceeded",
            f"LLM total budget exhausted after error: {type(last_error).__name__}",
        )

    def output_reserve_tokens(self, stage: LLMStage, explicit: int | None = None) -> int:
        """Resolve the local output reserve for one stage."""
        if explicit is not None:
            return explicit
        return self._stage_output_reserve_tokens.get(stage, self._default_output_reserve_tokens)

    def input_token_budget(self, stage: LLMStage, reserved_output_tokens: int | None = None) -> int:
        """Maximum estimated input tokens after output reserve and safety margin."""
        reserve = self.output_reserve_tokens(stage, reserved_output_tokens)
        return self.capabilities.context_window_tokens - reserve - self._safety_margin_tokens

    def estimate_messages(self, messages: tuple[BaseMessage, ...]) -> int:
        chars = 0
        for message in messages:
            chars += len(str(getattr(message, "role", "")))
            chars += _content_chars(getattr(message, "content", ""))
        return math.ceil(chars / self._chars_per_token)

    async def _invoke_provider(
        self,
        request: LLMInvocationRequest,
        reserve: int,
        messages: tuple[BaseMessage, ...],
    ) -> Any:
        kwargs: dict[str, Any] = {}
        if self.capabilities.supports_max_output_tokens:
            kwargs["max_tokens"] = reserve
        if hasattr(self._provider, "invoke"):
            return await self._provider.invoke(list(messages), **kwargs)
        return await self._provider(list(messages), **kwargs)

    @staticmethod
    def _normalize_response(
        response: Any,
        *,
        invocation_id: str,
        estimated_input_tokens: int,
        attempt: int,
        output_reserve_tokens: int,
        stage: LLMStage,
    ) -> LLMInvocationResult:
        text = (
            response.text
            if isinstance(response, LLMInvocationResult)
            else str(getattr(response, "content", "") or "")
        )
        metadata_value = getattr(response, "metadata", {})
        metadata = dict(metadata_value) if isinstance(metadata_value, Mapping) else {}
        finish_reason = getattr(response, "finish_reason", None) or metadata.get("finish_reason")
        usage = getattr(response, "usage_metadata", None)
        input_tokens = (
            response.input_tokens
            if isinstance(response, LLMInvocationResult)
            else _usage_value(usage, "input_tokens")
        )
        output_tokens = (
            response.output_tokens
            if isinstance(response, LLMInvocationResult)
            else _usage_value(usage, "output_tokens")
        )
        transport_complete = (
            response.transport_complete
            if isinstance(response, LLMInvocationResult)
            else bool(metadata.get("transport_complete", True))
        )
        metadata.update(
            {
                "attempt": attempt,
                "estimated_input_tokens": estimated_input_tokens,
                "estimated": input_tokens is None,
                "output_reserve_tokens": output_reserve_tokens,
                "stage": stage,
            }
        )
        return LLMInvocationResult(
            invocation_id=(
                response.invocation_id
                if isinstance(response, LLMInvocationResult)
                else invocation_id
            ),
            text=text,
            finish_reason=str(finish_reason) if finish_reason is not None else None,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            transport_complete=transport_complete,
            metadata=MappingProxyType(metadata),
        )


def _content_chars(content: Any) -> int:
    if isinstance(content, str):
        return len(content)
    if isinstance(content, list):
        return sum(_content_chars(item) for item in content)
    if isinstance(content, Mapping):
        return sum(len(str(key)) + _content_chars(value) for key, value in content.items())
    return len(str(content))


def _usage_value(usage: Any, key: str) -> int | None:
    if isinstance(usage, Mapping):
        value = usage.get(key)
        return int(value) if isinstance(value, int) else None
    value = getattr(usage, key, None)
    return int(value) if isinstance(value, int) else None


def _is_retryable(exc: Exception) -> bool:
    if getattr(exc, "retryable", False) is True:
        return True
    if isinstance(exc, (TimeoutError, ConnectionError)):
        return True
    if isinstance(exc, LLMInvocationError):
        return exc.category == "transport_incomplete"
    status = getattr(exc, "status_code", None)
    return status == 429 or isinstance(status, int) and status >= 500


def _declared_category(exc: Exception) -> str | None:
    category = getattr(exc, "category", None)
    return category if isinstance(category, str) and category else None


def _classify_result_error(request: LLMInvocationRequest, text: str) -> str | None:
    if request.result_error_classifier is None:
        return None
    try:
        return request.result_error_classifier(text)
    except Exception:
        logger.warning(
            "LLM result classifier failed stage=%s schema_name=%s",
            request.stage,
            request.output_schema_name or "none",
            exc_info=True,
        )
        return None


__all__ = [
    "LLMInvocation",
    "LLMInvocationContext",
    "LLMInvocationError",
    "LLMInvocationRequest",
    "LLMInvocationResult",
    "LLMProviderCapabilities",
    "LLMRetryPolicy",
    "LLMStage",
    "PromptBudgetExceededError",
]
