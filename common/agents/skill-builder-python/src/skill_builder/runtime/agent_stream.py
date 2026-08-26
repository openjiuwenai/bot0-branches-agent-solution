"""Agent stream parsing and presentation helpers for Skill Builder."""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
import logging
import re
from dataclasses import dataclass
from typing import Any

from skill_builder.runtime.serialization import json_safe

logger = logging.getLogger(__name__)

_STRUCTURED_TOOL_CALL_START = re.compile(
    r"<(?:\||｜)\s*DSML\s*(?:\||｜)\s*(?:tool_calls?|invoke)[^>]*>"
    r"|<tool_calls?(?:\s[^>]*)?>"
    r"|<(?:\||｜)tool_call(?:\||｜)>",
    re.IGNORECASE,
)


class IncrementalTextProjector:
    """Project cumulative or incremental provider text to true deltas."""

    def __init__(self) -> None:
        self._previous = ""
        self._structured_tool_payload = False

    def reset(self) -> None:
        self._previous = ""
        self._structured_tool_payload = False

    def project(self, current: str) -> str:
        text = str(current or "")
        if not text:
            return ""
        previous = self._previous
        self._previous = text
        if not previous:
            delta = text
        elif text == previous:
            delta = ""
        elif text.startswith(previous):
            delta = text[len(previous):]
        else:
            delta = text
        if not delta or self._structured_tool_payload:
            return ""
        marker = _STRUCTURED_TOOL_CALL_START.search(delta)
        if marker is None:
            return delta
        self._structured_tool_payload = True
        # Provider-native tool arguments belong to tool_call/tool_result
        # events.  Only natural-language text before the marker is user-visible.
        return delta[: marker.start()]


def _agent_stream_transient_error(error: Any) -> bool:
    """Classify one recoverable provider-stream interruption.

    Authentication, quota and explicit model-service failures remain terminal.
    This boundary is intentionally provider-neutral and permits one caller-
    controlled continuation with the same Agent session.
    """

    if error is None:
        return False
    class_name = type(error).__name__.lower()
    message = f"{type(error).__name__}: {error}".strip().lower()
    if any(
        marker in message
        for marker in (
            "invalid_api_key",
            "authentication",
            "unauthorized",
            "permission_denied",
            "insufficient_quota",
            "quota has been exhausted",
            "allocated quota exceeded",
        )
    ):
        return False
    if class_name in {
        "apiconnectionerror",
        "connectionerror",
        "connectionreseterror",
        "remoteprotocolerror",
        "readerror",
        "readtimeout",
        "serverdisconnectederror",
    }:
        return True
    return bool(
        re.search(
            r"\bconnection (?:error|reset|closed|lost|aborted)\b"
            r"|remote protocol error"
            r"|server disconnected"
            r"|incomplete (?:chunked )?read"
            r"|unexpected eof"
            r"|timed? out while (?:reading|receiving) (?:the )?(?:response|stream)",
            message,
            flags=re.IGNORECASE,
        )
    )


def _result_content(result: Any) -> str:
    if result is None:
        return ""
    if isinstance(result, str):
        return result
    for attr in ("content", "text", "output", "answer"):
        value = getattr(result, attr, None)
        if isinstance(value, str) and value.strip():
            return value
    if isinstance(result, dict):
        for key in ("content", "text", "output", "answer"):
            value = result.get(key)
            if isinstance(value, str) and value.strip():
                return value
    return str(result)


def _strip_markdown_fence(text: str) -> str:
    trimmed = text.strip()
    match = re.match(r"^```(?:json)?\s*(.*)\s*```$", trimmed, flags=re.IGNORECASE | re.DOTALL)
    return match.group(1).strip() if match else trimmed


def _line_starts_structural_markdown(text: str) -> bool:
    return bool(re.match(r"\s*(?:#{1,6}\s+|[-*]\s+|>\s+|\|)", text or ""))


def _short_fragment_line(text: str) -> bool:
    stripped = str(text or "").strip()
    if not stripped or _line_starts_structural_markdown(stripped):
        return False
    return len(stripped) <= 18


def _join_agent_text_fragment(previous: str, current: str) -> str:
    if not previous:
        return current
    if not current:
        return previous
    if re.match(r"^[，。！？；：、,.!?;:)\]）】}/%（(【\[]", current):
        return previous + current
    if re.search(r"[：:、，,(\[（【/+*-]$", previous):
        return previous + current
    if re.search(r"[A-Za-z]$", previous) and re.match(r"^[A-Za-z]", current):
        previous_word = re.search(r"([A-Za-z]+)$", previous)
        combined = (previous_word.group(1) if previous_word else previous) + current
        if combined.lower() in {
            "playwright",
            "javascript",
            "typescript",
            "workspace",
            "verification",
            "validation",
            "artifact",
            "manifest",
            "runtime",
            "screenshot",
            "screenshots",
            "markdown",
            "python",
            "openai",
            "gitee",
        }:
            if previous_word:
                return previous[: previous_word.start(1)] + combined
            return combined
        return previous + " " + current
    if re.search(r"[\u4e00-\u9fff]$", previous) and re.match(r"^[\u4e00-\u9fff]", current):
        return previous + current
    if previous.endswith("**") or current.startswith("**"):
        return previous + current
    return previous + " " + current


def _collapse_agent_text_fragment_block(lines: list[str]) -> str:
    joined = ""
    index = 0
    while index < len(lines):
        part = lines[index].strip()
        if re.fullmatch(r"\d+", part) and index + 1 < len(lines) and lines[index + 1].strip().startswith("."):
            part = part + lines[index + 1].strip()
            index += 1
        if re.fullmatch(r"\d+\.", part) and index + 1 < len(lines):
            part = part + " " + lines[index + 1].strip()
            index += 1
        joined = _join_agent_text_fragment(joined, part)
        index += 1
    if len(re.findall(r"(?:^|\s)\d+[.)]\s+", joined)) >= 2:
        joined = re.sub(r"\s+(?=\d+[.)]\s+)", "\n", joined)
    joined = re.sub(r"([。！？；])(?=[^\s\n])", r"\1 ", joined)
    return joined.strip()


def _repair_agent_display_token_seams(text: str) -> str:
    repaired = str(text or "")
    replacements = {
        "generated-s kill": "generated-skill",
        "generated -skill": "generated-skill",
        "HIT L": "HITL",
        "Play wright": "Playwright",
        "play wright": "playwright",
        "Java Script": "JavaScript",
        "Type Script": "TypeScript",
        "Open AI": "OpenAI",
    }
    for source, target in replacements.items():
        repaired = repaired.replace(source, target)
    repaired = re.sub(r"\b([A-Za-z][A-Za-z0-9]*)\s+_([A-Za-z0-9_]+(?:\.[A-Za-z0-9]+)?)\b", r"\1_\2", repaired)
    repaired = re.sub(r"\b([A-Za-z][A-Za-z0-9]*)\s+-([A-Za-z0-9_]+(?:\.[A-Za-z0-9]+)?)\b", r"\1-\2", repaired)
    repaired = re.sub(r"\b([A-Za-z0-9_]+)\s+\.(py|js|mjs|ts|json|yaml|yml|md|csv|txt)\b", r"\1.\2", repaired)
    repaired = re.sub(r"\b([A-Za-z0-9_.-]+)\s+/(generated-skill|scripts|fixtures|references|validation|workspace|playwright)\b", r"\1/\2", repaired)
    repaired = re.sub(r"\b(generated-skill|scripts|fixtures|references|validation|workspace|playwright)\s+/", r"\1/", repaired)
    return repaired


def _normalize_agent_display_text(text: str) -> str:
    raw = str(text or "")
    if "\n" not in raw:
        return _repair_agent_display_token_seams(raw.strip())
    output: list[str] = []
    buffer: list[str] = []
    in_fence = False

    def flush() -> None:
        if not buffer:
            return
        short_count = sum(1 for line in buffer if _short_fragment_line(line))
        has_broken_numbering = any(re.fullmatch(r"\s*\d+\s*", line or "") for line in buffer) or any(
            re.fullmatch(r"\s*\d+\.\s*", line or "") for line in buffer
        )
        if len(buffer) >= 3 and (short_count / max(len(buffer), 1) >= 0.55 or has_broken_numbering):
            output.append(_collapse_agent_text_fragment_block(buffer))
        else:
            output.append("\n".join(buffer).rstrip())
        buffer.clear()

    for line in raw.splitlines():
        if re.match(r"\s*```", line):
            flush()
            in_fence = not in_fence
            output.append(line)
            continue
        if in_fence or not line.strip():
            flush()
            output.append(line)
            continue
        if _line_starts_structural_markdown(line) and not re.match(r"\s*\d+[.)]\s*$", line):
            flush()
            output.append(line.rstrip())
            continue
        buffer.append(line)
    flush()
    return _repair_agent_display_token_seams(re.sub(r"\n{3,}", "\n\n", "\n".join(output)).strip())


def _agent_response_score(value: dict[str, Any]) -> int:
    keys = set(value.keys())
    score = 0
    if "status" in keys:
        score += 4
    if "summary" in keys or "suggested_next_message" in keys:
        score += 4
    # Prefer the enclosing Author response over its nested self-check object.
    # Both contain status/summary, but only the envelope carries the report as
    # an explicit field that the controller can bind to the candidate.
    if isinstance(value.get("agent_self_check"), dict):
        score += 8
    for key in ("files", "pending_decisions", "blockers", "unverified_inputs", "unverified_capabilities"):
        if key in keys:
            score += 1
    return score


def _parse_agent_json(text: str) -> dict[str, Any] | None:
    candidates: list[dict[str, Any]] = []
    decoder = json.JSONDecoder()

    def try_parse(candidate: str) -> None:
        candidate = candidate.strip()
        if not candidate:
            return
        try:
            parsed = json.loads(_strip_markdown_fence(candidate))
        except json.JSONDecodeError:
            return
        if isinstance(parsed, dict):
            candidates.append(parsed)

    try_parse(text)
    for match in re.finditer(r"```(?:json)?\s*(.*?)```", text, flags=re.IGNORECASE | re.DOTALL):
        try_parse(match.group(1))
    for match in re.finditer(r"\{", text):
        try:
            parsed, _end = decoder.raw_decode(text[match.start() :])
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            candidates.append(parsed)
    if not candidates:
        return None
    scored = sorted(((_agent_response_score(item), index, item) for index, item in enumerate(candidates)), reverse=True)
    best_score, _index, best = scored[0]
    return best if best_score > 0 else None


def _looks_mostly_english(text: str) -> bool:
    text = re.sub(r"`[^`]*`|https?://\S+", " ", text)
    chinese_chars = len(re.findall(r"[\u3400-\u9fff]", text))
    english_words = len(re.findall(r"\b[A-Za-z][A-Za-z_-]{2,}\b", text))
    return english_words >= 5 and english_words > chinese_chars * 1.5


def _agent_human_summary(value: dict[str, Any]) -> str:
    parts: list[str] = []
    summary = str(value.get("summary") or value.get("suggested_next_message") or "").strip()
    status = str(value.get("status") or "").strip()
    pending = value.get("pending_decisions")
    blockers = value.get("blockers")
    unverified = value.get("unverified_capabilities")
    unverified_inputs = value.get("unverified_inputs")
    files = value.get("files")
    if summary and not _looks_mostly_english(summary):
        parts.append(summary)
    elif status:
        status_label = {"draft_ready": "草稿已完成", "ready": "草稿已完成", "failed": "失败"}.get(status, status)
        parts.append(f"Agent 完成状态：{status_label}。")
    if isinstance(files, list) and files:
        parts.append(f"已记录 {len(files)} 个生成文件。")
    if isinstance(pending, list) and pending:
        parts.append(f"待确认 {len(pending)} 项。")
    hints: list[Any] = []
    if isinstance(blockers, list):
        hints.extend(blockers)
    if isinstance(unverified, list):
        hints.extend(unverified)
    if isinstance(unverified_inputs, list):
        hints.extend(unverified_inputs)
    if hints:
        parts.append(f"提示 {len({str(item).strip() for item in hints if str(item).strip()})} 项。")
    return " ".join(parts).strip() or "Agent 已完成当前阶段。"


def _agent_runtime_failure_message(text: str) -> str | None:
    raw = str(text or "").strip()
    if not raw:
        return None
    lowered = raw.lower()
    if _agent_runtime_failure_code(raw) == "request_too_large":
        return (
            "模型调用失败：最终请求体在仅压缩可重建历史后仍超过安全预算，"
            "平台已停止本轮生成，未使用截断材料继续生成（request_too_large）。"
        )
    if _agent_runtime_failure_code(raw) == "output_truncated":
        return (
            "模型调用失败：模型输出达到当前阶段 token 上限，平台已停止本轮生成，"
            "未使用截断的正文或工具参数继续写入（output_truncated）。"
        )
    if (
        "insufficient_quota" in lowered
        or "quota has been exhausted" in lowered
        or "allocated quota exceeded" in lowered
        or ("ratelimiterror" in lowered and "429" in lowered)
        or "token-plan quota" in lowered
    ):
        return "模型调用失败：当前 Skill 抽取模型额度已耗尽（429 insufficient_quota）。请更换可用的 SKILL_BUILDER_LLM_API_KEY 或补充模型额度后重新生成。"
    if (
        "invalid_api_key" in lowered
        or "incorrect api key" in lowered
        or "unauthorized" in lowered
        or "authentication" in lowered
        or re.search(r"\b401\b", lowered)
    ):
        return "模型调用失败：Skill 抽取模型认证失败，请检查 SKILL_BUILDER_LLM_API_KEY 和 SKILL_BUILDER_LLM_BASE_URL 后重新生成。"
    if (
        "permission_denied" in lowered
        or "access denied" in lowered
        or "forbidden" in lowered
        or re.search(r"\b403\b", lowered)
    ):
        return "模型调用失败：当前模型账号没有访问该模型或接口的权限，请检查模型授权、模型名和 API Key。"
    if (
        "receive batching backend response failed" in lowered
        or "model serving" in lowered
        or "model service unavailable" in lowered
    ):
        return "模型调用失败：模型服务暂时不可用或批处理后端响应失败。客户端已完成内置重试，请稍后重新生成。"
    return None


def _agent_runtime_failure_code(text: str) -> str | None:
    lowered = str(text or "").strip().lower()
    if not lowered:
        return None
    if (
        "requesttoolarge" in lowered
        or "request_too_large" in lowered
        or "payload too large" in lowered
        or "request body size exceeds" in lowered
        or ("413" in lowered and ("request" in lowered or "payload" in lowered))
    ):
        return "request_too_large"
    if "output_truncated" in lowered or "finish_reason=length" in lowered:
        return "output_truncated"
    return None


def _agent_core_cleanup_compatibility_error(text: str) -> bool:
    return bool(
        re.search(
            r"ContextVar name='root_task_group'|created in a different Context|asyncio\.exceptions\.CancelledError",
            str(text or ""),
            flags=re.IGNORECASE,
        )
    )


@dataclass(frozen=True, slots=True)
class OwnedAgentStreamTerminal:
    error: BaseException | None = None
    cleanup_error: BaseException | None = None


@dataclass(slots=True)
class OwnedAgentStream:
    """One task that creates, consumes, and closes an async generator."""

    queue: asyncio.Queue[tuple[str, Any]]
    task: asyncio.Task[None]

    async def next_event(self, *, timeout: float) -> tuple[str, Any]:
        return await asyncio.wait_for(self.queue.get(), timeout=max(0.01, float(timeout)))

    async def close(self, *, timeout: float = 5.0) -> None:
        if not self.task.done():
            self.task.cancel()
        try:
            await asyncio.wait_for(asyncio.shield(self.task), timeout=max(0.1, float(timeout)))
        except (asyncio.CancelledError, asyncio.TimeoutError):
            if not self.task.done():
                self.task.cancel()
            with contextlib.suppress(BaseException):
                await self.task
        except BaseException:
            # The owner reports primary and cleanup failures through its
            # terminal event. Closing it must never replace the caller's error.
            return


def start_owned_agent_stream(factory: Any, *, queue_size: int = 32) -> OwnedAgentStream:
    """Start one stream owner so ContextVar creation and reset share a task.

    ``factory`` is intentionally invoked inside the owner task. OpenJiuwen
    installs session/root-task-group ContextVars when its async generator first
    executes; creating or closing that generator from the controller task can
    otherwise reset tokens in a different Context after 413/timeout/cancel.
    """

    queue: asyncio.Queue[tuple[str, Any]] = asyncio.Queue(maxsize=max(1, queue_size))

    async def run() -> None:
        stream: Any = None
        primary_error: BaseException | None = None
        cleanup_error: BaseException | None = None
        cancelled = False
        try:
            stream = factory()
            stream_iter = stream.__aiter__() if hasattr(stream, "__aiter__") else stream
            async for chunk in stream_iter:
                await queue.put(("chunk", chunk))
        except asyncio.CancelledError:
            cancelled = True
        except BaseException as exc:  # noqa: BLE001 - terminal envelope preserves provider failures
            primary_error = exc
        finally:
            aclose = getattr(stream, "aclose", None)
            if callable(aclose):
                try:
                    await aclose()
                except BaseException as exc:  # noqa: BLE001 - report without replacing the primary error
                    cleanup_error = exc
            if not cancelled:
                await queue.put(
                    (
                        "terminal",
                        OwnedAgentStreamTerminal(
                            error=primary_error,
                            cleanup_error=cleanup_error,
                        ),
                    )
                )

    return OwnedAgentStream(queue=queue, task=asyncio.create_task(run()))


def _chunk_attr(chunk: Any, name: str, default: Any = None) -> Any:
    if isinstance(chunk, dict):
        return chunk.get(name, default)
    return getattr(chunk, name, default)


def _payload_dict(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    if value is None:
        return {}
    return {"content": str(value)}


def _chunk_text(chunk: Any, payload: dict[str, Any]) -> str:
    candidates = (
        _chunk_attr(chunk, "content"),
        _chunk_attr(chunk, "text"),
        _chunk_attr(chunk, "output"),
        payload.get("content"),
        payload.get("text"),
        payload.get("output"),
        payload.get("message"),
        payload.get("reasoning_content"),
    )
    return "\n".join(str(item) for item in candidates if item not in (None, ""))


def _body_descriptor(value: str) -> dict[str, Any]:
    encoded = value.encode("utf-8")
    return {
        "sha256": hashlib.sha256(encoded).hexdigest(),
        "sizeBytes": len(encoded),
    }


def _compact_tool_payload_value(value: Any, *, parent_key: str = "") -> Any:
    """Remove generated file bodies while retaining actionable diagnostics."""

    if isinstance(value, list):
        if parent_key == "replacements":
            return [
                {
                    **(
                        {"old": _body_descriptor(str(item.get("old") or ""))}
                        if isinstance(item, dict) and "old" in item
                        else {}
                    ),
                    **(
                        {"new": _body_descriptor(str(item.get("new") or ""))}
                        if isinstance(item, dict) and "new" in item
                        else {}
                    ),
                    **(
                        {"expected_count": item.get("expected_count")}
                        if isinstance(item, dict) and item.get("expected_count") is not None
                        else {}
                    ),
                }
                for item in value
            ]
        return [
            _compact_tool_payload_value(item, parent_key=parent_key)
            for item in value
        ]
    if not isinstance(value, dict):
        return value

    has_file_path = any(
        isinstance(value.get(key), str) and str(value.get(key) or "").strip()
        for key in ("path", "file_path", "relative_path")
    )
    compacted: dict[str, Any] = {}
    for raw_key, item in value.items():
        key = str(raw_key)
        if key == "content" and has_file_path and isinstance(item, str):
            compacted["contentSummary"] = _body_descriptor(item)
            continue
        if key == "content" and isinstance(item, str) and item.lstrip().startswith(("{", "[")):
            try:
                decoded_content = json.loads(item)
            except (TypeError, ValueError, json.JSONDecodeError):
                pass
            else:
                compacted[key] = _compact_tool_payload_value(
                    decoded_content,
                    parent_key=key,
                )
                continue
        if key in {"arguments", "args", "input", "tool_input"} and isinstance(item, str):
            try:
                decoded = json.loads(item)
            except (TypeError, ValueError, json.JSONDecodeError):
                compacted[key] = item
            else:
                compacted[key] = _compact_tool_payload_value(decoded, parent_key=key)
            continue
        compacted[key] = _compact_tool_payload_value(item, parent_key=key)
    return compacted


def _stream_chunk_events(
    chunk: Any,
    *,
    text_projector: IncrementalTextProjector | None = None,
) -> list[dict[str, Any]]:
    chunk_type = str(_chunk_attr(chunk, "type") or _chunk_attr(chunk, "event_type") or "").strip()
    payload = _payload_dict(_chunk_attr(chunk, "payload"))
    text = _chunk_text(chunk, payload)
    if chunk_type == "llm_reasoning":
        return []
    if chunk_type == "llm_output":
        if not text:
            return []
        if text_projector is not None:
            text = text_projector.project(text)
            if not text:
                return []
        return [{"event_type": "assistant.delta", "summary": text[:200], "payload": {"content": text, "chunk_type": chunk_type}}]
    if text_projector is not None:
        text_projector.reset()
    compact_payload = json_safe(
        _compact_tool_payload_value(payload),
        max_text_length=1000,
    )
    if chunk_type == "answer":
        result_type = payload.get("result_type")
        final_text = text or str(payload.get("output") or payload.get("content") or "")
        if result_type == "error":
            # ReAct reports its bounded iteration ceiling through the generic
            # answer-error channel.  The runtime owns this lifecycle boundary
            # and may still commit a complete durable candidate, so do not
            # publish a terminal error before that controller check runs.
            if "max iterations reached without completion" in final_text.lower():
                return []
            user_message = _agent_runtime_failure_message(final_text) or (f"Agent 执行失败：{final_text[:500]}" if final_text else "Agent 执行失败。")
            return [{"event_type": "agent.error", "summary": user_message[:500], "payload": {"message": user_message, "raw_message": final_text[:4000]}}]
        return []
    if chunk_type == "tool_call":
        tool_name = payload.get("tool_name") or payload.get("name") or "tool"
        return [{"event_type": "tool.call.stream", "summary": f"Agent 调用工具：{tool_name}", "payload": compact_payload}]
    if chunk_type == "tool_result":
        return [{"event_type": "tool.result.stream", "summary": "Agent 收到工具结果。", "payload": compact_payload}]
    if chunk_type in {"tool_error", "tool_failed", "tool_failure"}:
        tool_name = payload.get("tool_name") or payload.get("name") or "tool"
        return [{
            "event_type": "tool.error.stream",
            "summary": f"Agent 工具执行失败：{tool_name}",
            "payload": compact_payload,
        }]
    if chunk_type == "tracer_agent":
        status_value = str(payload.get("status") or "").strip().lower()
        tool_name = payload.get("name") or "tool"
        if status_value == "start":
            return [{"event_type": "tool.call.stream", "summary": f"Agent 调用工具：{tool_name}", "payload": compact_payload}]
        if status_value in {"end", "finish"}:
            return [{"event_type": "tool.result.stream", "summary": f"Agent 工具完成：{tool_name}", "payload": compact_payload}]
        if status_value in {"error", "failed", "failure"}:
            return [{
                "event_type": "tool.error.stream",
                "summary": f"Agent 工具执行失败：{tool_name}",
                "payload": compact_payload,
            }]
    return []


__all__ = [
    "IncrementalTextProjector",
    "OwnedAgentStream",
    "OwnedAgentStreamTerminal",
    "_agent_core_cleanup_compatibility_error",
    "_agent_human_summary",
    "_agent_runtime_failure_code",
    "_agent_runtime_failure_message",
    "_agent_stream_transient_error",
    "_chunk_attr",
    "_chunk_text",
    "_normalize_agent_display_text",
    "_parse_agent_json",
    "_payload_dict",
    "_result_content",
    "_stream_chunk_events",
    "_strip_markdown_fence",
    "start_owned_agent_stream",
]
