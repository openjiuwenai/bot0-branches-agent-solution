"""AgentClient — SSE consumer for calling business Agent APIs (Issue #3).

Encapsulates HTTP interaction with EDPAgent's SSE streaming endpoint:
  POST /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}

Consumes the SSE event stream, assembles the final answer, detects
VA delegate interrupts, collects simplified event summaries, and
returns a structured AgentCallResponse.
"""

from __future__ import annotations

import copy
import json
import os
import re
from collections.abc import AsyncIterator
from typing import Any

import httpx
import structlog

from agent_adapter.schemas import AgentCallResponse, EventSummary

logger = structlog.get_logger(__name__)

# 匹配 ${ENV_VAR} 语法，用于 extra_headers 值从环境变量读取（如 token）。
_ENV_VAR_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _expand_env_vars(value: str) -> str:
    """展开字符串中的 ${ENV_VAR} 占位为环境变量值，未设置则替换为空串。"""
    return _ENV_VAR_PATTERN.sub(lambda m: os.environ.get(m.group(1), ""), value)


class AgentClient:
    """HTTP client for calling a business Agent's SSE streaming endpoint.

    Args:
        agent_url: Base URL of the Agent service (e.g. http://localhost:8090)
        project_id: URL path segment for project
        agent_id: URL path segment for agent
        timeout: Request timeout in seconds
        request_template: 稳定 body 字段底模，深合并到最终请求体；调用方 extra_data
            仍合并进 custom_data.inputs。用于透传 role_id/role_name/custom_data.user_profile
            等客户环境绑定字段。
        extra_headers: 稳定请求头。值支持 ${ENV_VAR} 语法从环境变量读取。
        url_query_params: URL query 参数（拼到 conversation URL 后）。
    """

    def __init__(
        self,
        agent_url: str,
        project_id: str,
        agent_id: str,
        timeout: int = 300,
        request_template: dict[str, Any] | None = None,
        extra_headers: dict[str, str] | None = None,
        url_query_params: dict[str, str] | None = None,
    ) -> None:
        self._agent_url = agent_url.rstrip("/")
        self._project_id = project_id
        self._agent_id = agent_id
        self._timeout = timeout
        self._request_template = request_template or {}
        self._extra_headers = extra_headers or {}
        self._url_query_params = url_query_params or {}

    def _build_url(self, conversation_id: str) -> str:
        """Build the full URL for the Agent call endpoint, with optional query params."""
        url = (
            f"{self._agent_url}/v1/{self._project_id}"
            f"/agents/{self._agent_id}/conversations/{conversation_id}"
        )
        if self._url_query_params:
            pairs = [f"{k}={v}" for k, v in self._url_query_params.items()]
            url = f"{url}?{'&'.join(pairs)}"
        return url

    def _build_headers(self) -> dict[str, str]:
        """Build request headers from extra_headers, expanding ${ENV_VAR} placeholders."""
        return {
            k: _expand_env_vars(v) if isinstance(v, str) else str(v)
            for k, v in self._extra_headers.items()
        }

    def _build_request_body(
        self,
        query: str,
        conversation_id: str,
        extra_data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Assemble the JSON request body for EDPAgent.

        以 request_template 为底深拷贝，补齐 agent_id/conversation_id/stream/input.query，
        再将 extra_data 合并进 custom_data.inputs。template 里的 custom_data.inputs 不
        会被 query 覆盖（仅 update），允许 template 预置稳定 inputs 字段。
        """
        body = copy.deepcopy(self._request_template) if self._request_template else {}

        # 补齐 adapter 必需字段（template 未提供时填默认）
        body.setdefault("agent_id", self._agent_id)
        body.setdefault("conversation_id", conversation_id)
        body.setdefault("stream", True)

        # input.query：template 可预置 input 其他字段，仅补 query
        input_obj = body.setdefault("input", {})
        if not isinstance(input_obj, dict):
            input_obj = {}
            body["input"] = input_obj
        input_obj.setdefault("query", query)

        # custom_data.inputs：合并 extra_data（调用方动态字段，如 ZRTtype/run_id）
        custom_data = body.setdefault("custom_data", {})
        if not isinstance(custom_data, dict):
            custom_data = {}
            body["custom_data"] = custom_data
        inputs = custom_data.setdefault("inputs", {})
        if not isinstance(inputs, dict):
            inputs = {}
            custom_data["inputs"] = inputs
        # adapter 历史行为：inputs 里带一份 query（与 input.query 对齐）
        inputs.setdefault("query", query)
        if extra_data:
            inputs.update(extra_data)

        return body

    async def call(
        self,
        conversation_id: str,
        query: str,
        extra_data: dict[str, Any] | None = None,
    ) -> AgentCallResponse:
        """Call the business Agent and return the assembled response.

        Consumes the SSE stream, assembles answer, detects interrupts,
        and returns a structured AgentCallResponse.
        """
        url = self._build_url(conversation_id)
        body = self._build_request_body(query, conversation_id, extra_data)
        headers = self._build_headers()

        try:
            parsed_events: list[dict] = []
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(self._timeout, read=None),
            ) as client:
                async with client.stream("POST", url, json=body, headers=headers) as response:
                    response.raise_for_status()
                    async for line in response.aiter_lines():
                        frame = _parse_sse_frame(line)
                        if frame is not None:
                            parsed_events.append(frame)

            return _consume_sse_stream(conversation_id, parsed_events)

        except httpx.ConnectError as exc:
            logger.error("agent_client_connect_error", url=url, error=str(exc))
            return AgentCallResponse(
                success=False,
                conversation_id=conversation_id,
                error=f"无法连接 Agent 服务: {exc}",
            )
        except httpx.TimeoutException as exc:
            logger.error("agent_client_timeout", url=url, timeout=self._timeout, error=str(exc))
            return AgentCallResponse(
                success=False,
                conversation_id=conversation_id,
                error=f"Agent 调用超时 ({self._timeout}s): {exc}",
            )
        except httpx.HTTPStatusError as exc:
            logger.error(
                "agent_client_http_error", url=url, status=exc.response.status_code, error=str(exc)
            )
            return AgentCallResponse(
                success=False,
                conversation_id=conversation_id,
                error=f"Agent 返回错误 (HTTP {exc.response.status_code}): {exc}",
            )
        except Exception as exc:
            logger.exception("agent_client_unexpected_error", url=url)
            return AgentCallResponse(
                success=False,
                conversation_id=conversation_id,
                error=f"Agent 调用异常: {exc}",
            )

    async def iter_sse_stream(
        self,
        conversation_id: str,
        query: str,
        extra_data: dict[str, Any] | None = None,
    ) -> AsyncIterator[str]:
        """Yield raw SSE lines from the business Agent for streaming passthrough.

        与 call() 的聚合模式互补：不消费/聚合事件，把 edp_agent 的 SSE 行原样
        转发给客户端（每行末补 ``\\n`` 以满足 SSE 帧换行要求）。用于客户端
        期望实时流式事件（``Accept: text/event-stream``）的场景——避免 VA 慢
        时聚合模式阻塞导致客户端收不到任何字节而断连。

        异常不抛出，而是以 SSE error 事件形式 yield，避免 StreamingResponse
        在响应头已发出后中途抛异常导致连接异常关闭。
        """
        url = self._build_url(conversation_id)
        body = self._build_request_body(query, conversation_id, extra_data)
        headers = self._build_headers()
        try:
            async with (
                httpx.AsyncClient(
                    timeout=httpx.Timeout(self._timeout, read=None),
                ) as client,
                client.stream("POST", url, json=body, headers=headers) as response,
            ):
                response.raise_for_status()
                async for line in response.aiter_lines():
                    yield line + "\n"
        except httpx.HTTPStatusError as exc:
            logger.error("agent_stream_http_error", url=url, status=exc.response.status_code)
            yield self._error_frame(f"Agent 返回错误 (HTTP {exc.response.status_code}): {exc}")
        except httpx.ConnectError as exc:
            logger.error("agent_stream_connect_error", url=url, error=str(exc))
            yield self._error_frame(f"无法连接 Agent 服务: {exc}")
        except httpx.TimeoutException as exc:
            logger.error("agent_stream_timeout", url=url, timeout=self._timeout, error=str(exc))
            yield self._error_frame(f"Agent 调用超时 ({self._timeout}s): {exc}")
        except Exception as exc:
            logger.exception("agent_stream_unexpected_error", url=url)
            yield self._error_frame(f"Agent 调用异常: {exc}")

    @staticmethod
    def _error_frame(message: str) -> str:
        """Build an SSE error frame string (data line + blank line)."""
        payload = json.dumps({"error": message, "success": False}, ensure_ascii=False)
        return f"data: {payload}\n\n"


def _parse_sse_frame(line: str) -> dict | None:
    """Parse a single SSE line into a JSON dict.

    Returns None for:
      - Empty lines or non-data lines
      - The [DONE] signal
      - Lines with invalid JSON
    """
    line = line.strip()
    if not line or not line.startswith("data: "):
        return None

    data_str = line[len("data: ") :]
    if data_str.strip() == "[DONE]":
        return None

    try:
        parsed = json.loads(data_str)
        return parsed if isinstance(parsed, dict) else None
    except (json.JSONDecodeError, ValueError):
        return None


def _consume_sse_stream(
    conversation_id: str,
    events: list[dict],
) -> AgentCallResponse:
    """Process a list of parsed SSE events into a structured response.

    This is a pure function suitable for unit testing without HTTP.
    It handles:
      - Answer assembly from summary + final_answer_chunk events
      - Interrupt detection from interrupt_start events
      - Event collection into simplified EventSummary list
    """
    answer_parts: list[str] = []
    final_answer_chunk: str | None = None
    interrupted = False
    interrupt_intent: str | None = None
    interrupt_description: str | None = None
    event_summaries: list[EventSummary] = []

    for raw_event in events:
        custom_rsp = raw_event.get("custom_rsp_data")
        if not isinstance(custom_rsp, dict):
            continue

        event_type = custom_rsp.get("event", "")
        content = custom_rsp.get("content", "")
        plugin = custom_rsp.get("plugin")

        # Answer assembly: summary is streaming fragments, final_answer_chunk is
        # a one-shot full text.  Prefer the authoritative final_answer_chunk;
        # fall back to concatenated summary fragments if no chunk arrives.
        if event_type == "summary":
            answer_parts.append(content)
        elif event_type == "final_answer_chunk":
            final_answer_chunk = content if isinstance(content, str) else str(content)

        # Interrupt detection
        if event_type == "interrupt_start":
            interrupted = True
            interrupt_intent = custom_rsp.get("interrupt_intent") or custom_rsp.get("intent")
            interrupt_description = custom_rsp.get("interrupt_description") or custom_rsp.get(
                "task_description"
            )

        # Event collection
        event_summaries.append(
            EventSummary(
                type=event_type,
                content=content if isinstance(content, str) else str(content),
                plugin=plugin if isinstance(plugin, str) else None,
            )
        )

    # Prefer authoritative full-text chunk; fall back to streaming fragments
    final_answer = final_answer_chunk if final_answer_chunk is not None else "".join(answer_parts)

    return AgentCallResponse(
        success=True,
        conversation_id=conversation_id,
        answer=final_answer,
        interrupted=interrupted,
        interrupt_intent=interrupt_intent,
        interrupt_description=interrupt_description,
        events=event_summaries if event_summaries else None,
    )
