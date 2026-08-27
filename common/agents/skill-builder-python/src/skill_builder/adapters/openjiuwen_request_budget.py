"""Bound and diagnose OpenAI-compatible requests made by Skill Builder.

The OpenJiuwen context engine owns conversational semantics, while this
adapter owns the final HTTP transport boundary.  Character and token limits
inside individual tools are insufficient because the serialized request also
contains the system prompt, tool schemas, prior tool calls, and provider
options.  This wrapper measures the final request shape, applies only
deterministic history compaction, and fails closed before the gateway returns
an opaque 413.
"""

from __future__ import annotations

import asyncio
import copy
import json
import logging
import time
from dataclasses import dataclass
from typing import Any, AsyncIterator, Awaitable, Callable


RequestEventEmitter = Callable[[str, str, dict[str, Any] | None], Awaitable[None]]

_LOGGER = logging.getLogger(__name__)

_TRANSIENT_MESSAGE_FIELDS = frozenset(
    {
        "reasoning_content",
        "parser_content",
        "prompt_token_ids",
        "completion_token_ids",
        "logprobs",
        "usage_metadata",
    }
)
_PERSISTED_WRITE_TOOL_PREFIXES = (
    "skill_builder_write_skill_file_",
    "skill_builder_write_skill_files_",
    "skill_builder_write_tabular_fixture_",
    "skill_builder_write_self_check_plan_",
)
_UNPERSISTED_TOOL_ARGUMENT_MAX_BYTES = 8 * 1024
_TOOL_CALL_ASSISTANT_CONTENT_MAX_BYTES = 8 * 1024


class SkillBuilderLLMRequestTooLargeError(RuntimeError):
    """Raised when a model request cannot fit without losing current evidence."""

    code = "request_too_large"

    def __init__(
        self,
        *,
        request_bytes: int,
        budget_bytes: int,
        original_request_bytes: int | None = None,
        upstream_413: bool = False,
    ) -> None:
        self.request_bytes = max(0, int(request_bytes))
        self.budget_bytes = max(1, int(budget_bytes))
        self.original_request_bytes = max(
            self.request_bytes,
            int(original_request_bytes or self.request_bytes),
        )
        self.upstream_413 = bool(upstream_413)
        source = "模型网关返回 413，且" if upstream_413 else ""
        super().__init__(
            "request_too_large: "
            f"{source}最终请求在无损压缩后仍为 {self.request_bytes} bytes，"
            f"超过 SkillBuilder 安全预算 {self.budget_bytes} bytes。"
        )


class SkillBuilderLLMOutputTruncatedError(RuntimeError):
    """Raised when the provider stops before a complete model response."""

    code = "output_truncated"

    def __init__(self, *, finish_reason: str, max_tokens: int | None) -> None:
        self.finish_reason = str(finish_reason or "length")
        self.max_tokens = int(max_tokens) if max_tokens is not None else None
        super().__init__(
            "output_truncated: 模型输出达到阶段 token 上限，"
            "平台未使用截断的正文或工具参数继续生成"
            f"（finish_reason={self.finish_reason}, max_tokens={self.max_tokens}）。"
        )


@dataclass(frozen=True, slots=True)
class PreparedModelRequest:
    messages: list[dict[str, Any]]
    tools: list[dict[str, Any]] | None
    request_bytes: int
    original_request_bytes: int
    budget_bytes: int
    reductions: tuple[str, ...]
    diagnostics: dict[str, Any]


def _json_bytes(value: Any) -> int:
    return len(
        json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
            default=str,
        ).encode("utf-8")
    )


def _request_body(params: dict[str, Any]) -> dict[str, Any]:
    body = {
        key: value
        for key, value in params.items()
        if key not in {"extra_headers", "custom_headers", "tracer_record_data"}
    }
    return body


def _request_enable_thinking(params: dict[str, Any]) -> bool | None:
    direct = params.get("enable_thinking")
    if isinstance(direct, bool):
        return direct
    extra_body = params.get("extra_body")
    nested = extra_body.get("enable_thinking") if isinstance(extra_body, dict) else None
    return nested if isinstance(nested, bool) else None


def _message_diagnostics(messages: list[dict[str, Any]]) -> dict[str, Any]:
    sizes = [
        {
            "index": index,
            "role": str(message.get("role") or ""),
            "bytes": _json_bytes(message),
        }
        for index, message in enumerate(messages)
    ]
    return {
        "messageCount": len(messages),
        "messageBytes": sum(int(item["bytes"]) for item in sizes),
        "roles": {
            role: sum(1 for item in sizes if item["role"] == role)
            for role in sorted({str(item["role"]) for item in sizes})
        },
        "largestMessages": sorted(
            sizes,
            key=lambda item: int(item["bytes"]),
            reverse=True,
        )[:5],
    }


def _field(value: Any, name: str) -> Any:
    if isinstance(value, dict):
        return value.get(name)
    return getattr(value, name, None)


def _usage_metrics(value: Any) -> dict[str, int | float]:
    """Extract numeric provider usage without retaining prompts or content."""

    usage = _field(value, "usage_metadata") or _field(value, "usage")
    if usage is None:
        return {}
    aliases = {
        "inputTokens": ("input_tokens", "prompt_tokens"),
        "outputTokens": ("output_tokens", "completion_tokens"),
        "totalTokens": ("total_tokens",),
        "cacheTokens": ("cache_tokens", "cached_tokens"),
        "providerLatencyMs": ("total_latency",),
    }
    metrics: dict[str, int | float] = {}
    for target, names in aliases.items():
        raw = next((_field(usage, name) for name in names if _field(usage, name) is not None), None)
        if isinstance(raw, bool) or not isinstance(raw, (int, float)):
            continue
        if target == "providerLatencyMs":
            # OpenJiuwen reports total_latency in seconds.
            metrics[target] = round(max(0.0, float(raw)) * 1000, 3)
        else:
            metrics[target] = max(0, int(raw))
    return metrics


def _finish_reason(value: Any) -> str | None:
    direct = _field(value, "finish_reason")
    if direct not in (None, ""):
        normalized = str(direct).strip()
        return None if normalized.lower() in {"none", "null"} else normalized[:120]
    choices = _field(value, "choices")
    if isinstance(choices, list) and choices:
        reason = _field(choices[0], "finish_reason")
        if reason not in (None, ""):
            normalized = str(reason).strip()
            return None if normalized.lower() in {"none", "null"} else normalized[:120]
    return None


def _finish_reason_is_truncated(value: str | None) -> bool:
    return str(value or "").strip().lower() in {
        "length",
        "max_tokens",
        "max_output_tokens",
    }


def _output_truncation_reason(
    *,
    finish_reason: str | None,
    usage: dict[str, int | float],
    max_tokens: int | None,
) -> str | None:
    """Recognize token exhaustion even when a compatible gateway omits it.

    Some OpenAI-compatible gateways return a null finish reason for streamed
    tool calls while still reporting exact output usage. A response at the
    configured ceiling is incomplete unless the provider supplied an explicit
    non-truncating terminal reason such as ``stop`` or ``tool_calls``.
    """

    if _finish_reason_is_truncated(finish_reason):
        return str(finish_reason)
    if finish_reason is not None or max_tokens is None or int(max_tokens) <= 0:
        return None
    output_tokens = usage.get("outputTokens")
    if isinstance(output_tokens, bool) or not isinstance(output_tokens, (int, float)):
        return None
    return "usage_limit" if int(output_tokens) >= int(max_tokens) else None


_MODEL_OUTPUT_FIELD_METRICS = {
    "content": ("contentBytes", "contentChunkCount"),
    "reasoning_content": ("reasoningBytes", "reasoningChunkCount"),
    "parser_content": ("parserBytes", "parserChunkCount"),
    "tool_calls": ("toolCallBytes", "toolCallChunkCount"),
}


def _model_output_metrics(value: Any) -> dict[str, int]:
    """Count model output kinds without retaining any generated content."""

    metrics = {"responseBytes": 0}
    for byte_key, chunk_key in _MODEL_OUTPUT_FIELD_METRICS.values():
        metrics[byte_key] = 0
        metrics[chunk_key] = 0
    for field, (byte_key, chunk_key) in _MODEL_OUTPUT_FIELD_METRICS.items():
        part = _field(value, field)
        if part in (None, "", [], {}):
            continue
        size = _json_bytes(part)
        metrics[byte_key] += size
        metrics[chunk_key] += 1
        metrics["responseBytes"] += size
    choices = _field(value, "choices")
    if isinstance(choices, list):
        for choice in choices:
            delta = _field(choice, "delta") or _field(choice, "message")
            if delta is None:
                continue
            nested = _model_output_metrics(delta)
            for key, amount in nested.items():
                metrics[key] += amount
    return metrics


def _merge_output_metrics(target: dict[str, int], source: dict[str, int]) -> None:
    for key, amount in source.items():
        target[key] = int(target.get(key) or 0) + max(0, int(amount or 0))


def _error_category(error: BaseException) -> str:
    code = str(getattr(error, "code", "") or "").strip().lower()
    if code:
        return code[:80]
    text = f"{type(error).__name__}: {error}".lower()
    if _is_request_too_large_error(error):
        return "request_too_large"
    if "insufficient_quota" in text or "quota" in text:
        return "quota"
    if "auth" in text or "api key" in text or "unauthorized" in text:
        return "authentication"
    if "timeout" in text or "timed out" in text:
        return "timeout"
    if "connection" in text or "disconnected" in text or "unexpected eof" in text:
        return "connection"
    return "provider_error"


def _stream_termination_outcome(error: BaseException) -> str:
    """Distinguish consumer aclose() from cancellation of the owning task."""

    current_task = asyncio.current_task()
    task_is_cancelling = bool(
        current_task is not None and current_task.cancelling()
    )
    if isinstance(error, GeneratorExit) or (
        isinstance(error, asyncio.CancelledError) and not task_is_cancelling
    ):
        return "consumer_closed"
    return "cancelled"


def _parse_json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    if not isinstance(value, str):
        return {}
    try:
        parsed = json.loads(value)
    except (TypeError, ValueError):
        return {}
    return dict(parsed) if isinstance(parsed, dict) else {}


def _remove_transient_message_fields(messages: list[dict[str, Any]]) -> int:
    removed = 0
    for message in messages:
        for field in _TRANSIENT_MESSAGE_FIELDS:
            if field in message:
                message.pop(field, None)
                removed += 1
    return removed


def _successful_tool_results(messages: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    results: dict[str, dict[str, Any]] = {}
    for message in messages:
        if message.get("role") != "tool":
            continue
        tool_call_id = str(message.get("tool_call_id") or "")
        if not tool_call_id:
            continue
        parsed = _parse_json_object(message.get("content"))
        if parsed.get("ok") is True and parsed.get("persisted") is True:
            results[tool_call_id] = parsed
    return results


def _compact_persisted_write_arguments(messages: list[dict[str, Any]]) -> int:
    completed = _successful_tool_results(messages)
    if not completed:
        return 0
    compacted_count = 0
    for message in messages:
        if message.get("role") != "assistant":
            continue
        tool_calls = message.get("tool_calls")
        if not isinstance(tool_calls, list):
            continue
        for tool_call in tool_calls:
            if not isinstance(tool_call, dict):
                continue
            tool_call_id = str(tool_call.get("id") or "")
            result = completed.get(tool_call_id)
            function = tool_call.get("function")
            if result is None or not isinstance(function, dict):
                continue
            name = str(function.get("name") or "")
            if not name.startswith(_PERSISTED_WRITE_TOOL_PREFIXES):
                continue
            arguments = _parse_json_object(function.get("arguments"))
            if not any(key in arguments for key in ("content", "replacements")):
                continue
            draft = result.get("draft") if isinstance(result.get("draft"), dict) else {}
            compacted = {
                "path": result.get("path") or draft.get("path") or arguments.get("path"),
                "persisted": True,
                "sha256": result.get("sha256") or draft.get("sha256"),
                "sizeBytes": result.get("sizeBytes") or result.get("size_bytes"),
            }
            function["arguments"] = json.dumps(
                {key: value for key, value in compacted.items() if value not in (None, "")},
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            compacted_count += 1
    return compacted_count


def _compact_unpersisted_tool_arguments(messages: list[dict[str, Any]]) -> int:
    """Bound rejected/generated tool-call arguments that were never persisted.

    A provider schema error can leave a very large assistant tool call in the
    conversation without a successful tool result.  It cannot be handled by
    the persisted-write compactor above, but it is generated model output, not
    user evidence, so replacing it with a deterministic diagnostic is safe.
    """
    completed = _successful_tool_results(messages)
    compacted_count = 0
    for message in messages:
        if message.get("role") != "assistant":
            continue
        tool_calls = message.get("tool_calls")
        if not isinstance(tool_calls, list):
            continue
        for tool_call in tool_calls:
            if not isinstance(tool_call, dict):
                continue
            tool_call_id = str(tool_call.get("id") or "")
            if tool_call_id in completed:
                continue
            function = tool_call.get("function")
            if not isinstance(function, dict):
                continue
            arguments = function.get("arguments")
            if not isinstance(arguments, str):
                continue
            argument_bytes = len(arguments.encode("utf-8"))
            if argument_bytes <= _UNPERSISTED_TOOL_ARGUMENT_MAX_BYTES:
                continue
            function["arguments"] = json.dumps(
                {
                    "compacted": True,
                    "reason": "unpersisted_tool_call",
                    "originalBytes": argument_bytes,
                },
                ensure_ascii=False,
                separators=(",", ":"),
            )
            compacted_count += 1
    return compacted_count


def _compact_tool_call_assistant_content(messages: list[dict[str, Any]]) -> int:
    """Drop oversized generated text when structured tool calls are canonical.

    Some OpenAI-compatible streaming responses expose both parsed ``tool_calls``
    and an accumulated textual copy of the same call.  Provider/SDK chunk
    merging can multiply that generated text into megabytes even though the
    actual tool arguments and result remain small.  Once structured tool calls
    are present, the assistant text is not user evidence and is not needed to
    replay the call, so retain only a deterministic diagnostic marker.
    """

    compacted_count = 0
    for message in messages:
        if message.get("role") != "assistant":
            continue
        tool_calls = message.get("tool_calls")
        if not isinstance(tool_calls, list) or not tool_calls:
            continue
        content = message.get("content")
        if content in (None, ""):
            continue
        content_bytes = _json_bytes(content)
        if content_bytes <= _TOOL_CALL_ASSISTANT_CONTENT_MAX_BYTES:
            continue
        message["content"] = json.dumps(
            {
                "compacted": True,
                "reason": "structured_tool_calls_retained",
                "originalBytes": content_bytes,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        compacted_count += 1
    return compacted_count


def _compact_duplicate_tool_results(messages: list[dict[str, Any]]) -> int:
    newest_by_content: dict[str, int] = {}
    for index, message in enumerate(messages):
        if message.get("role") != "tool":
            continue
        content = message.get("content")
        if isinstance(content, str) and content:
            newest_by_content[content] = index

    compacted_count = 0
    for index, message in enumerate(messages):
        if message.get("role") != "tool":
            continue
        content = message.get("content")
        if not isinstance(content, str) or not content:
            continue
        newest_index = newest_by_content.get(content)
        if newest_index is None or newest_index == index:
            continue
        message["content"] = json.dumps(
            {
                "ok": True,
                "compacted": True,
                "duplicateOfMessageIndex": newest_index,
                "originalBytes": len(content.encode("utf-8")),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        compacted_count += 1
    return compacted_count


def _compact_completed_write_results(messages: list[dict[str, Any]]) -> int:
    compacted_count = 0
    for message in messages:
        if message.get("role") != "tool":
            continue
        content = message.get("content")
        parsed = _parse_json_object(content)
        if not (
            parsed.get("ok") is True
            and parsed.get("persisted") is True
            and isinstance(content, str)
            and len(content.encode("utf-8")) > 2_000
        ):
            continue
        draft = parsed.get("draft") if isinstance(parsed.get("draft"), dict) else {}
        message["content"] = json.dumps(
            {
                "ok": True,
                "persisted": True,
                "path": parsed.get("path") or draft.get("path"),
                "sha256": parsed.get("sha256") or draft.get("sha256"),
                "sizeBytes": parsed.get("sizeBytes") or parsed.get("size_bytes"),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        compacted_count += 1
    return compacted_count


def _apply_deterministic_compaction(
    params: dict[str, Any],
    *,
    pressure: bool,
    aggressive: bool,
) -> tuple[dict[str, Any], tuple[str, ...]]:
    updated = copy.deepcopy(params)
    messages = updated.get("messages")
    if not isinstance(messages, list):
        return updated, ()
    normalized_messages = [
        dict(message) if isinstance(message, dict) else {"role": "user", "content": str(message)}
        for message in messages
    ]
    updated["messages"] = normalized_messages
    reductions: list[str] = []

    transient_count = _remove_transient_message_fields(normalized_messages)
    if transient_count:
        reductions.append(f"transient_fields:{transient_count}")

    write_argument_count = _compact_persisted_write_arguments(normalized_messages)
    if write_argument_count:
        reductions.append(f"persisted_write_arguments:{write_argument_count}")

    # Rejected arguments, generated assistant text and repeated tool results
    # can help the model diagnose a previous failed turn. Keep them during
    # normal requests and compact them only when the complete request is over
    # budget (or the upstream gateway has already returned 413).
    if pressure:
        unpersisted_argument_count = _compact_unpersisted_tool_arguments(normalized_messages)
        if unpersisted_argument_count:
            reductions.append(f"unpersisted_tool_arguments:{unpersisted_argument_count}")

        tool_call_content_count = _compact_tool_call_assistant_content(normalized_messages)
        if tool_call_content_count:
            reductions.append(f"tool_call_assistant_content:{tool_call_content_count}")

        duplicate_count = _compact_duplicate_tool_results(normalized_messages)
        if duplicate_count:
            reductions.append(f"duplicate_tool_results:{duplicate_count}")

    if aggressive:
        write_result_count = _compact_completed_write_results(normalized_messages)
        if write_result_count:
            reductions.append(f"persisted_write_results:{write_result_count}")

    return updated, tuple(reductions)


def _is_request_too_large_error(error: BaseException) -> bool:
    if isinstance(error, SkillBuilderLLMRequestTooLargeError):
        return True
    text = f"{type(error).__name__}: {error}".lower()
    return bool(
        "requesttoolarge" in text
        or "request_too_large" in text
        or "payload too large" in text
        or "request body size exceeds" in text
        or ("413" in text and "request" in text)
    )


class SkillBuilderBudgetedModel:
    """Duck-typed OpenJiuwen Model wrapper with a final request byte budget."""

    def __init__(
        self,
        delegate: Any,
        *,
        max_request_bytes: int,
        headroom_ratio: float,
        configured_max_tokens: int | None = None,
        emit_event: RequestEventEmitter | None = None,
        run_phase: str = "unknown",
        tool_choice_resolver: Callable[[], str | None] | None = None,
    ) -> None:
        self._delegate = delegate
        self._max_request_bytes = max(64 * 1024, int(max_request_bytes))
        self._headroom_ratio = max(0.25, min(float(headroom_ratio), 1.0))
        self._configured_max_tokens = (
            max(1, int(configured_max_tokens))
            if configured_max_tokens is not None
            else None
        )
        self._emit_event = emit_event
        self._run_phase = str(run_phase or "unknown")
        self._tool_choice_resolver = tool_choice_resolver
        self._request_index = 0
        self._previous_request_bytes: int | None = None
        self._previous_message_count: int | None = None
        # One ReAct phase is sequential. Serializing the wrapper makes that
        # invariant explicit even if an SDK continuation outlives its stream
        # consumer, preventing duplicate same-session model calls.
        self._request_lock = asyncio.Lock()

    def __getattr__(self, name: str) -> Any:
        return getattr(self._delegate, name)

    @property
    def _effective_budget_bytes(self) -> int:
        return max(32 * 1024, int(self._max_request_bytes * self._headroom_ratio))

    def _effective_max_tokens(self, requested: int | None) -> int | None:
        return (
            max(1, int(requested))
            if requested is not None
            else self._configured_max_tokens
        )

    def _resolved_request_kwargs(self, kwargs: dict[str, Any]) -> dict[str, Any]:
        resolved = dict(kwargs)
        if callable(self._tool_choice_resolver):
            tool_choice = self._tool_choice_resolver()
            if tool_choice:
                resolved["tool_choice"] = tool_choice
            else:
                resolved.pop("tool_choice", None)
        return resolved

    def _build_request_params(
        self,
        *,
        messages: Any,
        tools: Any,
        stream: bool,
        model: str | None,
        temperature: float | None,
        top_p: float | None,
        max_tokens: int | None,
        stop: str | None,
        kwargs: dict[str, Any],
    ) -> dict[str, Any]:
        client = getattr(self._delegate, "_client", None)
        builder = getattr(client, "_build_request_params", None)
        if not callable(builder):
            raise RuntimeError("SkillBuilder request budget requires an OpenJiuwen model client")
        build_kwargs = {
            key: value
            for key, value in kwargs.items()
            if key not in {"tracer_record_data", "custom_headers", "output_parser", "parser"}
        }
        params = builder(
            messages=messages,
            tools=tools,
            temperature=temperature,
            top_p=top_p,
            model=model,
            stop=stop,
            max_tokens=max_tokens,
            stream=stream,
            **build_kwargs,
        )
        if stream:
            stream_options = params.get("stream_options")
            if isinstance(stream_options, dict):
                stream_options.setdefault("include_usage", True)
            elif stream_options is None:
                params["stream_options"] = {"include_usage": True}
        if "return_token_ids" in params:
            extra_body = dict(params.get("extra_body") or {})
            extra_body["return_token_ids"] = params.pop("return_token_ids")
            params["extra_body"] = extra_body
        return params

    async def _emit_performance(self, name: str, payload: dict[str, Any]) -> None:
        """Best-effort private telemetry; it must never change generation."""

        if self._emit_event is None:
            return
        try:
            await self._emit_event(
                f"internal.performance.{name}",
                "SkillBuilder internal performance telemetry.",
                payload,
            )
        except Exception:
            _LOGGER.debug("Failed to emit private Skill Builder performance telemetry.", exc_info=True)

    def _next_request_index(self) -> int:
        self._request_index += 1
        return self._request_index

    def _request_metrics(
        self,
        prepared: PreparedModelRequest,
        *,
        request_index: int,
        transport_attempt: int,
        stream: bool,
        model: str | None,
        temperature: float | None,
        top_p: float | None,
        max_tokens: int | None,
    ) -> dict[str, Any]:
        message_count = int(prepared.diagnostics.get("messageCount") or 0)
        payload = {
            "phase": self._run_phase,
            "requestIndex": request_index,
            "transportAttempt": transport_attempt,
            "stream": stream,
            "model": str(model or "")[:160],
            "temperature": temperature,
            "topP": top_p,
            "maxTokens": max_tokens,
            "requestBytes": prepared.request_bytes,
            "originalRequestBytes": prepared.original_request_bytes,
            "budgetBytes": prepared.budget_bytes,
            "configuredMaxRequestBytes": self._max_request_bytes,
            "headroomRatio": self._headroom_ratio,
            "reductions": list(prepared.reductions),
            "contextGrowthBytes": (
                None
                if self._previous_request_bytes is None or transport_attempt > 1
                else prepared.request_bytes - self._previous_request_bytes
            ),
            "contextGrowthMessages": (
                None
                if self._previous_message_count is None or transport_attempt > 1
                else message_count - self._previous_message_count
            ),
            **prepared.diagnostics,
        }
        if transport_attempt == 1:
            self._previous_request_bytes = prepared.request_bytes
            self._previous_message_count = message_count
        return payload

    async def _emit_attempt_started(
        self,
        prepared: PreparedModelRequest,
        *,
        request_index: int,
        transport_attempt: int,
        stream: bool,
        model: str | None,
        temperature: float | None,
        top_p: float | None,
        max_tokens: int | None,
    ) -> float:
        started = time.monotonic()
        await self._emit_performance(
            "llm_request_started",
            self._request_metrics(
                prepared,
                request_index=request_index,
                transport_attempt=transport_attempt,
                stream=stream,
                model=model,
                temperature=temperature,
                top_p=top_p,
                max_tokens=max_tokens,
            ),
        )
        return started

    async def _emit_attempt_completed(
        self,
        *,
        request_index: int,
        transport_attempt: int,
        started: float,
        outcome: str,
        response: Any = None,
        output_metrics: dict[str, int] | None = None,
        chunk_count: int = 0,
        first_output_ms: float | None = None,
        error: BaseException | None = None,
        usage: dict[str, int | float] | None = None,
        finish_reason: str | None = None,
    ) -> None:
        payload: dict[str, Any] = {
            "phase": self._run_phase,
            "requestIndex": request_index,
            "transportAttempt": transport_attempt,
            "outcome": outcome,
            "durationMs": round(max(0.0, time.monotonic() - started) * 1000, 3),
            "firstOutputMs": first_output_ms,
            "chunkCount": max(0, int(chunk_count)),
        }
        payload.update(
            {
                key: max(0, int(value or 0))
                for key, value in (output_metrics or {}).items()
            }
        )
        payload.setdefault("responseBytes", 0)
        payload.update(usage or _usage_metrics(response))
        reason = finish_reason or _finish_reason(response)
        if reason:
            payload["finishReason"] = reason
        if error is not None:
            payload["errorCategory"] = _error_category(error)
        await self._emit_performance("llm_request_completed", payload)

    async def _prepare(
        self,
        *,
        messages: Any,
        tools: Any,
        stream: bool,
        model: str | None,
        temperature: float | None,
        top_p: float | None,
        max_tokens: int | None,
        stop: str | None,
        kwargs: dict[str, Any],
        budget_bytes: int,
        aggressive: bool,
        upstream_413: bool = False,
    ) -> PreparedModelRequest:
        params = self._build_request_params(
            messages=messages,
            tools=tools,
            stream=stream,
            model=model,
            temperature=temperature,
            top_p=top_p,
            max_tokens=max_tokens,
            stop=stop,
            kwargs=kwargs,
        )
        original_request_bytes = _json_bytes(_request_body(params))
        # Provider-only response state and bodies of successfully persisted
        # writes are not conversational evidence. Compact those fields on
        # every turn so a long Author session does not replay each completed
        # file body until it happens to cross the hard request budget.
        compacted, reductions = _apply_deterministic_compaction(
            params,
            pressure=(
                original_request_bytes > budget_bytes
                or aggressive
                or upstream_413
            ),
            aggressive=aggressive,
        )
        request_bytes = _json_bytes(_request_body(compacted))
        messages_value = compacted.get("messages")
        tools_value = compacted.get("tools")
        prepared = PreparedModelRequest(
            messages=list(messages_value) if isinstance(messages_value, list) else [],
            tools=list(tools_value) if isinstance(tools_value, list) else None,
            request_bytes=request_bytes,
            original_request_bytes=original_request_bytes,
            budget_bytes=budget_bytes,
            reductions=reductions,
            diagnostics={
                **_message_diagnostics(
                    list(messages_value) if isinstance(messages_value, list) else []
                ),
                "toolCount": len(tools_value) if isinstance(tools_value, list) else 0,
                "toolSchemaBytes": _json_bytes(tools_value) if isinstance(tools_value, list) else 0,
                **(
                    {"enableThinking": _request_enable_thinking(compacted)}
                    if _request_enable_thinking(compacted) is not None
                    else {}
                ),
            },
        )
        if request_bytes > budget_bytes:
            raise SkillBuilderLLMRequestTooLargeError(
                request_bytes=request_bytes,
                original_request_bytes=original_request_bytes,
                budget_bytes=budget_bytes,
                upstream_413=upstream_413,
            )
        return prepared

    async def invoke(
        self,
        messages: Any,
        *,
        tools: Any = None,
        temperature: float | None = None,
        top_p: float | None = None,
        max_tokens: int | None = None,
        stop: str | None = None,
        model: str | None = None,
        output_parser: Any = None,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> Any:
        async with self._request_lock:
            return await self._invoke_unlocked(
                messages,
                tools=tools,
                temperature=temperature,
                top_p=top_p,
                max_tokens=max_tokens,
                stop=stop,
                model=model,
                output_parser=output_parser,
                timeout=timeout,
                **kwargs,
            )

    async def _invoke_unlocked(
        self,
        messages: Any,
        *,
        tools: Any = None,
        temperature: float | None = None,
        top_p: float | None = None,
        max_tokens: int | None = None,
        stop: str | None = None,
        model: str | None = None,
        output_parser: Any = None,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> Any:
        request_kwargs = self._resolved_request_kwargs(kwargs)
        effective_max_tokens = self._effective_max_tokens(max_tokens)
        request_index = self._next_request_index()
        prepared = await self._prepare(
            messages=messages,
            tools=tools,
            stream=False,
            model=model,
            temperature=temperature,
            top_p=top_p,
            max_tokens=effective_max_tokens,
            stop=stop,
            kwargs=request_kwargs,
            budget_bytes=self._effective_budget_bytes,
            aggressive=False,
        )
        started = await self._emit_attempt_started(
            prepared,
            request_index=request_index,
            transport_attempt=1,
            stream=False,
            model=model,
            temperature=temperature,
            top_p=top_p,
            max_tokens=effective_max_tokens,
        )
        try:
            response = await self._delegate.invoke(
                messages=prepared.messages,
                tools=prepared.tools,
                temperature=temperature,
                top_p=top_p,
                max_tokens=max_tokens,
                stop=stop,
                model=model,
                output_parser=output_parser,
                timeout=timeout,
                **request_kwargs,
            )
            finish_reason = _finish_reason(response)
            usage = _usage_metrics(response)
            truncation_reason = _output_truncation_reason(
                finish_reason=finish_reason,
                usage=usage,
                max_tokens=effective_max_tokens,
            )
            truncated_error = (
                SkillBuilderLLMOutputTruncatedError(
                    finish_reason=truncation_reason,
                    max_tokens=effective_max_tokens,
                )
                if truncation_reason is not None
                else None
            )
            await self._emit_attempt_completed(
                request_index=request_index,
                transport_attempt=1,
                started=started,
                outcome="output_truncated" if truncated_error else "completed",
                response=response,
                output_metrics=_model_output_metrics(response),
                error=truncated_error,
                usage=usage,
                finish_reason=finish_reason,
            )
            if truncated_error is not None:
                raise truncated_error
            return response
        except Exception as exc:
            if isinstance(exc, SkillBuilderLLMOutputTruncatedError):
                raise
            if not _is_request_too_large_error(exc):
                await self._emit_attempt_completed(
                    request_index=request_index,
                    transport_attempt=1,
                    started=started,
                    outcome="failed",
                    error=exc,
                )
                raise
            await self._emit_attempt_completed(
                request_index=request_index,
                transport_attempt=1,
                started=started,
                outcome="retryable_failure",
                error=exc,
            )
            retry_budget = max(32 * 1024, self._effective_budget_bytes // 2)
            retry = await self._prepare(
                messages=messages,
                tools=tools,
                stream=False,
                model=model,
                temperature=temperature,
                top_p=top_p,
                max_tokens=effective_max_tokens,
                stop=stop,
                kwargs=request_kwargs,
                budget_bytes=retry_budget,
                aggressive=True,
                upstream_413=True,
            )
            retry_started = await self._emit_attempt_started(
                retry,
                request_index=request_index,
                transport_attempt=2,
                stream=False,
                model=model,
                temperature=temperature,
                top_p=top_p,
                max_tokens=effective_max_tokens,
            )
            try:
                response = await self._delegate.invoke(
                    messages=retry.messages,
                    tools=retry.tools,
                    temperature=temperature,
                    top_p=top_p,
                    max_tokens=max_tokens,
                    stop=stop,
                    model=model,
                    output_parser=output_parser,
                    timeout=timeout,
                    **request_kwargs,
                )
                finish_reason = _finish_reason(response)
                usage = _usage_metrics(response)
                truncation_reason = _output_truncation_reason(
                    finish_reason=finish_reason,
                    usage=usage,
                    max_tokens=effective_max_tokens,
                )
                truncated_error = (
                    SkillBuilderLLMOutputTruncatedError(
                        finish_reason=truncation_reason,
                        max_tokens=effective_max_tokens,
                    )
                    if truncation_reason is not None
                    else None
                )
                await self._emit_attempt_completed(
                    request_index=request_index,
                    transport_attempt=2,
                    started=retry_started,
                    outcome="output_truncated" if truncated_error else "completed",
                    response=response,
                    output_metrics=_model_output_metrics(response),
                    error=truncated_error,
                    usage=usage,
                    finish_reason=finish_reason,
                )
                if truncated_error is not None:
                    raise truncated_error
                return response
            except Exception as retry_exc:
                if isinstance(retry_exc, SkillBuilderLLMOutputTruncatedError):
                    raise
                await self._emit_attempt_completed(
                    request_index=request_index,
                    transport_attempt=2,
                    started=retry_started,
                    outcome="failed",
                    error=retry_exc,
                )
                if _is_request_too_large_error(retry_exc):
                    raise SkillBuilderLLMRequestTooLargeError(
                        request_bytes=retry.request_bytes,
                        original_request_bytes=retry.original_request_bytes,
                        budget_bytes=retry_budget,
                        upstream_413=True,
                    ) from retry_exc
                raise

    async def stream(
        self,
        messages: Any,
        *,
        tools: Any = None,
        temperature: float | None = None,
        top_p: float | None = None,
        max_tokens: int | None = None,
        stop: str | None = None,
        model: str | None = None,
        output_parser: Any = None,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> AsyncIterator[Any]:
        async with self._request_lock:
            iterator = self._stream_unlocked(
                messages,
                tools=tools,
                temperature=temperature,
                top_p=top_p,
                max_tokens=max_tokens,
                stop=stop,
                model=model,
                output_parser=output_parser,
                timeout=timeout,
                **kwargs,
            )
            try:
                while True:
                    try:
                        chunk = await anext(iterator)
                    except StopAsyncIteration:
                        break
                    yield chunk
            finally:
                # Explicitly close the inner provider iterator before releasing
                # phase ownership. ``async for`` finalization may otherwise
                # surface a caller aclose() as task cancellation on Python 3.13.
                await iterator.aclose()

    async def _stream_unlocked(
        self,
        messages: Any,
        *,
        tools: Any = None,
        temperature: float | None = None,
        top_p: float | None = None,
        max_tokens: int | None = None,
        stop: str | None = None,
        model: str | None = None,
        output_parser: Any = None,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> AsyncIterator[Any]:
        request_kwargs = self._resolved_request_kwargs(kwargs)
        effective_max_tokens = self._effective_max_tokens(max_tokens)
        request_index = self._next_request_index()
        prepared = await self._prepare(
            messages=messages,
            tools=tools,
            stream=True,
            model=model,
            temperature=temperature,
            top_p=top_p,
            max_tokens=effective_max_tokens,
            stop=stop,
            kwargs=request_kwargs,
            budget_bytes=self._effective_budget_bytes,
            aggressive=False,
        )
        started = await self._emit_attempt_started(
            prepared,
            request_index=request_index,
            transport_attempt=1,
            stream=True,
            model=model,
            temperature=temperature,
            top_p=top_p,
            max_tokens=effective_max_tokens,
        )
        chunk_count = 0
        output_metrics: dict[str, int] = {}
        first_output_ms: float | None = None
        usage: dict[str, int | float] = {}
        finish_reason: str | None = None
        try:
            async for chunk in self._delegate.stream(
                messages=prepared.messages,
                tools=prepared.tools,
                temperature=temperature,
                top_p=top_p,
                max_tokens=max_tokens,
                stop=stop,
                model=model,
                output_parser=output_parser,
                timeout=timeout,
                **request_kwargs,
            ):
                chunk_count += 1
                chunk_metrics = _model_output_metrics(chunk)
                _merge_output_metrics(output_metrics, chunk_metrics)
                chunk_bytes = int(chunk_metrics.get("responseBytes") or 0)
                if first_output_ms is None and chunk_bytes > 0:
                    first_output_ms = round(max(0.0, time.monotonic() - started) * 1000, 3)
                usage.update(_usage_metrics(chunk))
                finish_reason = _finish_reason(chunk) or finish_reason
                yield chunk
            truncation_reason = _output_truncation_reason(
                finish_reason=finish_reason,
                usage=usage,
                max_tokens=effective_max_tokens,
            )
            truncated_error = (
                SkillBuilderLLMOutputTruncatedError(
                    finish_reason=truncation_reason,
                    max_tokens=effective_max_tokens,
                )
                if truncation_reason is not None
                else None
            )
            await self._emit_attempt_completed(
                request_index=request_index,
                transport_attempt=1,
                started=started,
                outcome="output_truncated" if truncated_error else "completed",
                output_metrics=output_metrics,
                chunk_count=chunk_count,
                first_output_ms=first_output_ms,
                error=truncated_error,
                usage=usage,
                finish_reason=finish_reason,
            )
            if truncated_error is not None:
                raise truncated_error
            return
        except Exception as exc:
            if isinstance(exc, SkillBuilderLLMOutputTruncatedError):
                raise
            if not _is_request_too_large_error(exc):
                await self._emit_attempt_completed(
                    request_index=request_index,
                    transport_attempt=1,
                    started=started,
                    outcome="failed",
                    output_metrics=output_metrics,
                    chunk_count=chunk_count,
                    first_output_ms=first_output_ms,
                    error=exc,
                    usage=usage,
                    finish_reason=finish_reason,
                )
                raise
            await self._emit_attempt_completed(
                request_index=request_index,
                transport_attempt=1,
                started=started,
                outcome="retryable_failure",
                output_metrics=output_metrics,
                chunk_count=chunk_count,
                first_output_ms=first_output_ms,
                error=exc,
                usage=usage,
                finish_reason=finish_reason,
            )
        except BaseException as exc:
            # ReAct may stop consuming as soon as a terminal tool succeeds.
            # Closing the provider iterator is a normal transport outcome and
            # must still close the timing record instead of looking like a
            # permanently in-flight request.
            await self._emit_attempt_completed(
                request_index=request_index,
                transport_attempt=1,
                started=started,
                outcome=_stream_termination_outcome(exc),
                output_metrics=output_metrics,
                chunk_count=chunk_count,
                first_output_ms=first_output_ms,
                usage=usage,
                finish_reason=finish_reason,
            )
            raise

        retry_budget = max(32 * 1024, self._effective_budget_bytes // 2)
        retry = await self._prepare(
            messages=messages,
            tools=tools,
            stream=True,
            model=model,
            temperature=temperature,
            top_p=top_p,
            max_tokens=effective_max_tokens,
            stop=stop,
            kwargs=request_kwargs,
            budget_bytes=retry_budget,
            aggressive=True,
            upstream_413=True,
        )
        retry_started = await self._emit_attempt_started(
            retry,
            request_index=request_index,
            transport_attempt=2,
            stream=True,
            model=model,
            temperature=temperature,
            top_p=top_p,
            max_tokens=effective_max_tokens,
        )
        chunk_count = 0
        output_metrics = {}
        first_output_ms = None
        usage = {}
        finish_reason = None
        try:
            async for chunk in self._delegate.stream(
                messages=retry.messages,
                tools=retry.tools,
                temperature=temperature,
                top_p=top_p,
                max_tokens=max_tokens,
                stop=stop,
                model=model,
                output_parser=output_parser,
                timeout=timeout,
                **request_kwargs,
            ):
                chunk_count += 1
                chunk_metrics = _model_output_metrics(chunk)
                _merge_output_metrics(output_metrics, chunk_metrics)
                chunk_bytes = int(chunk_metrics.get("responseBytes") or 0)
                if first_output_ms is None and chunk_bytes > 0:
                    first_output_ms = round(max(0.0, time.monotonic() - retry_started) * 1000, 3)
                usage.update(_usage_metrics(chunk))
                finish_reason = _finish_reason(chunk) or finish_reason
                yield chunk
            truncation_reason = _output_truncation_reason(
                finish_reason=finish_reason,
                usage=usage,
                max_tokens=effective_max_tokens,
            )
            truncated_error = (
                SkillBuilderLLMOutputTruncatedError(
                    finish_reason=truncation_reason,
                    max_tokens=effective_max_tokens,
                )
                if truncation_reason is not None
                else None
            )
            await self._emit_attempt_completed(
                request_index=request_index,
                transport_attempt=2,
                started=retry_started,
                outcome="output_truncated" if truncated_error else "completed",
                output_metrics=output_metrics,
                chunk_count=chunk_count,
                first_output_ms=first_output_ms,
                error=truncated_error,
                usage=usage,
                finish_reason=finish_reason,
            )
            if truncated_error is not None:
                raise truncated_error
        except Exception as retry_exc:
            if isinstance(retry_exc, SkillBuilderLLMOutputTruncatedError):
                raise
            await self._emit_attempt_completed(
                request_index=request_index,
                transport_attempt=2,
                started=retry_started,
                outcome="failed",
                output_metrics=output_metrics,
                chunk_count=chunk_count,
                first_output_ms=first_output_ms,
                error=retry_exc,
                usage=usage,
                finish_reason=finish_reason,
            )
            if _is_request_too_large_error(retry_exc):
                raise SkillBuilderLLMRequestTooLargeError(
                    request_bytes=retry.request_bytes,
                    original_request_bytes=retry.original_request_bytes,
                    budget_bytes=retry_budget,
                    upstream_413=True,
                ) from retry_exc
            raise
        except BaseException as retry_exc:
            await self._emit_attempt_completed(
                request_index=request_index,
                transport_attempt=2,
                started=retry_started,
                outcome=_stream_termination_outcome(retry_exc),
                output_metrics=output_metrics,
                chunk_count=chunk_count,
                first_output_ms=first_output_ms,
                usage=usage,
                finish_reason=finish_reason,
            )
            raise


__all__ = [
    "PreparedModelRequest",
    "SkillBuilderBudgetedModel",
    "SkillBuilderLLMOutputTruncatedError",
    "SkillBuilderLLMRequestTooLargeError",
]
