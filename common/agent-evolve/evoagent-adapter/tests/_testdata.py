"""Small synthetic OTel span fixtures shared by unit and integration tests."""

from __future__ import annotations

import json
from typing import Any


def otel_spans() -> list[dict[str, Any]]:
    """Return three generic trace trees without customer prompts or runtime data."""
    spans: list[dict[str, Any]] = []
    for index in range(3):
        trace_id = f"{index + 1:032x}"
        span_ids = [f"{index * 6 + offset:016x}" for offset in range(1, 7)]
        root_span_id, chain_span_id, llm1_span_id, tool_span_id, llm2_span_id, leaf_span_id = (
            span_ids
        )
        minute = f"{index:02d}"
        resource = {
            "telemetry.sdk.language": "python",
            "telemetry.sdk.name": "opentelemetry",
            "service.instance.id": f"synthetic-instance-{index + 1}",
            "service.name": "sample_agent",
            "service.version": "test",
        }
        spans.extend(
            [
                _span(
                    trace_id=trace_id,
                    span_id=root_span_id,
                    parent_span_id="",
                    name="http.request",
                    kind="SERVER",
                    start_time=f"2026-01-01T00:{minute}:00+00:00",
                    end_time=f"2026-01-01T00:{minute}:10+00:00",
                    duration_ns=10_000_000_000,
                    attributes={
                        "http.request.method": "POST",
                        "http.route": "/v1/test/agents/sample_agent/conversations/test",
                        "http.response.status_code": 200,
                        "session.id": f"test-conversation-{index + 1}",
                        "user.id": f"test-user-{index + 1}",
                        "openjiuwen.trace.id": f"synthetic-trace-{index + 1}",
                        "openjiuwen.http.request_body": json.dumps(
                            {
                                "conversation_id": f"test-conversation-{index + 1}",
                                "user_query": f"synthetic request {index + 1}",
                                "stream": True,
                            }
                        ),
                        "openjiuwen.http.response_summary": '{"status":"completed"}',
                    },
                    resource_attributes=resource,
                ),
                _span(
                    trace_id=trace_id,
                    span_id=chain_span_id,
                    parent_span_id=root_span_id,
                    name="chain.SampleAgent",
                    kind="INTERNAL",
                    start_time=f"2026-01-01T00:{minute}:01+00:00",
                    end_time=f"2026-01-01T00:{minute}:09+00:00",
                    duration_ns=8_000_000_000,
                    attributes={"component": "planner"},
                    resource_attributes=resource,
                ),
                _span(
                    trace_id=trace_id,
                    span_id=llm1_span_id,
                    parent_span_id=chain_span_id,
                    name="llm.Model",
                    kind="CLIENT",
                    start_time=f"2026-01-01T00:{minute}:02+00:00",
                    end_time=f"2026-01-01T00:{minute}:04+00:00",
                    duration_ns=2_000_000_000,
                    attributes=_llm_attributes(index, turn=1),
                    resource_attributes=resource,
                ),
                _span(
                    trace_id=trace_id,
                    span_id=tool_span_id,
                    parent_span_id=chain_span_id,
                    name="tool.synthetic_lookup",
                    kind="INTERNAL",
                    start_time=f"2026-01-01T00:{minute}:04+00:00",
                    end_time=f"2026-01-01T00:{minute}:05+00:00",
                    duration_ns=1_000_000_000,
                    attributes={"tool.name": "synthetic_lookup"},
                    resource_attributes=resource,
                ),
                _span(
                    trace_id=trace_id,
                    span_id=llm2_span_id,
                    parent_span_id=chain_span_id,
                    name="llm.Model",
                    kind="CLIENT",
                    start_time=f"2026-01-01T00:{minute}:05+00:00",
                    end_time=f"2026-01-01T00:{minute}:08+00:00",
                    duration_ns=3_000_000_000,
                    attributes=_llm_attributes(index, turn=2),
                    resource_attributes=resource,
                ),
                _span(
                    trace_id=trace_id,
                    span_id=leaf_span_id,
                    parent_span_id=llm2_span_id,
                    name="model.transport",
                    kind="CLIENT",
                    start_time=f"2026-01-01T00:{minute}:06+00:00",
                    end_time=f"2026-01-01T00:{minute}:07+00:00",
                    duration_ns=1_000_000_000,
                    attributes={"gen_ai.system": "test"},
                    resource_attributes=resource,
                ),
            ]
        )
    return spans


def _llm_attributes(index: int, *, turn: int) -> dict[str, Any]:
    return {
        "gen_ai.system": "test",
        "gen_ai.prompt": json.dumps(
            {
                "inputs": [
                    {
                        "role": "user",
                        "content": f"synthetic request {index + 1}",
                    }
                ]
            }
        ),
        "gen_ai.completion": json.dumps(
            {
                "output": {
                    "role": "assistant",
                    "content": f"synthetic response {index + 1}, turn {turn}",
                }
            }
        ),
    }


def _span(
    *,
    trace_id: str,
    span_id: str,
    parent_span_id: str,
    name: str,
    kind: str,
    start_time: str,
    end_time: str,
    duration_ns: int,
    attributes: dict[str, Any],
    resource_attributes: dict[str, Any],
) -> dict[str, Any]:
    return {
        "trace_id": trace_id,
        "span_id": span_id,
        "parent_span_id": parent_span_id,
        "name": name,
        "kind": kind,
        "start_time": start_time,
        "end_time": end_time,
        "duration_ns": duration_ns,
        "attributes": attributes,
        "resource_attributes": dict(resource_attributes),
        "scope_name": "openjiuwen.tracer.otel",
        "scope_version": "",
        "status_code": "OK",
        "status_message": "",
        "events": [],
        "links": [],
    }
