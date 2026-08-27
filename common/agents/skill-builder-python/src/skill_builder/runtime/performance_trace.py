"""Private, metadata-only Skill Builder performance traces."""

from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


_MODEL_NUMERIC_FIELDS = (
    "temperature", "topP", "maxTokens", "requestBytes", "originalRequestBytes",
    "budgetBytes", "configuredMaxRequestBytes", "headroomRatio", "messageCount",
    "messageBytes", "toolCount", "toolSchemaBytes", "contextGrowthBytes",
    "contextGrowthMessages", "durationMs", "firstOutputMs", "providerLatencyMs",
    "responseBytes", "chunkCount", "inputTokens", "outputTokens", "totalTokens",
    "cacheTokens", "contentBytes", "reasoningBytes", "parserBytes", "toolCallBytes",
    "contentChunkCount", "reasoningChunkCount", "parserChunkCount", "toolCallChunkCount",
)


def _now_ms() -> int:
    return int(time.time() * 1000)


def _iso(value: int | None) -> str | None:
    if value is None:
        return None
    return datetime.fromtimestamp(value / 1000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _load(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _number(value: Any) -> int | float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return round(float(value), 3) if isinstance(value, float) else int(value)


class SkillBuilderPerformanceTrace:
    """Merge-safe trace shared by the host controller and one worker at a time."""

    def __init__(
        self, *, root: Path, workspace_id: str, run_id: str, phase: str,
        source_id: str, now_ms: Callable[[], int] = _now_ms,
    ) -> None:
        self.root = root.resolve()
        self.workspace_id = str(workspace_id)
        self.run_id = str(run_id or "unknown")
        self.phase = str(phase or "unknown")
        self.source_id = str(source_id or "unknown")[:120]
        self._clock = now_ms
        self.path = self.root / ".skill-builder" / "performance" / f"{self.run_id}.json"

    def _snapshot(self) -> dict[str, Any]:
        value = _load(self.path)
        if value.get("runId") != self.run_id:
            value = {}
        value.setdefault("schemaVersion", "skill-builder-performance/v1")
        value.setdefault("privacy", "metadata_only")
        value.setdefault("workspaceId", self.workspace_id)
        value.setdefault("runId", self.run_id)
        value.setdefault("createdAt", _iso(self._clock()))
        value.setdefault("modelRequests", [])
        value.setdefault("toolCalls", [])
        value.setdefault("sandboxes", [])
        value.setdefault("milestones", {})
        return value

    def _persist(self, value: dict[str, Any]) -> None:
        value["updatedAt"] = _iso(self._clock())
        value["phaseSummary"] = self._phase_summary(value)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + f".{self.source_id}.tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.path)

    @staticmethod
    def _phase_summary(value: dict[str, Any]) -> list[dict[str, Any]]:
        requests = [item for item in value.get("modelRequests") or [] if isinstance(item, dict)]
        tools = [item for item in value.get("toolCalls") or [] if isinstance(item, dict)]
        phases = sorted(
            {
                *(str(item.get("phase") or "unknown") for item in requests),
                *(str(item.get("phase") or "unknown") for item in tools),
            }
        )
        summaries: list[dict[str, Any]] = []
        for phase in phases:
            phase_requests = [item for item in requests if str(item.get("phase") or "unknown") == phase]
            phase_tools = [item for item in tools if str(item.get("phase") or "unknown") == phase]
            model_ms = round(sum(float(item.get("durationMs") or 0) for item in phase_requests), 3)
            first_output_ms = round(
                sum(
                    float(item.get("firstOutputMs"))
                    if item.get("firstOutputMs") is not None
                    else float(item.get("durationMs") or 0)
                    for item in phase_requests
                ),
                3,
            )
            summaries.append({
                "phase": phase,
                "modelRequestCount": len({(item.get("sourceId"), item.get("requestIndex")) for item in phase_requests}),
                "modelTransportAttemptCount": len(phase_requests),
                "modelRetryCount": sum(1 for item in phase_requests if int(item.get("transportAttempt") or 1) > 1),
                "modelTotalMs": model_ms,
                "modelWaitForFirstOutputMs": first_output_ms,
                "modelOutputAfterFirstMs": round(max(0.0, model_ms - first_output_ms), 3),
                "toolCallCount": len(phase_tools),
                "toolTotalMs": sum(int(item.get("durationMs") or 0) for item in phase_tools),
                "maxRequestBytes": max((int(item.get("requestBytes") or 0) for item in phase_requests), default=0),
                "inputTokens": sum(int(item.get("inputTokens") or 0) for item in phase_requests),
                "outputTokens": sum(int(item.get("outputTokens") or 0) for item in phase_requests),
            })
        return summaries

    def observe(self, event_type: str, payload: dict[str, Any] | None = None) -> None:
        """Record only allow-listed metadata; arbitrary event payload is discarded."""

        payload = payload if isinstance(payload, dict) else {}
        now = self._clock()
        value = self._snapshot()
        phase = str(payload.get("phase") or self.phase or "unknown")[:80]
        requests = value["modelRequests"]
        tools = value["toolCalls"]
        sandboxes = value["sandboxes"]

        if event_type == "internal.performance.llm_request_started":
            record: dict[str, Any] = {
                "sourceId": self.source_id, "phase": phase,
                "requestIndex": int(payload.get("requestIndex") or 0),
                "transportAttempt": int(payload.get("transportAttempt") or 1),
                "stream": bool(payload.get("stream")), "model": str(payload.get("model") or "")[:160],
                "startedAt": _iso(now),
            }
            if isinstance(payload.get("enableThinking"), bool):
                record["enableThinking"] = payload["enableThinking"]
            for key in _MODEL_NUMERIC_FIELDS:
                number = _number(payload.get(key))
                if number is not None:
                    record[key] = number
            reductions = payload.get("reductions")
            if isinstance(reductions, list):
                record["reductions"] = [str(item)[:120] for item in reductions[:20]]
            roles = payload.get("roles")
            if isinstance(roles, dict):
                record["roles"] = {
                    str(key)[:40]: int(item)
                    for key, item in roles.items()
                    if isinstance(item, int) and not isinstance(item, bool)
                }
            requests.append(record)
        elif event_type == "internal.performance.llm_request_completed":
            request_index = int(payload.get("requestIndex") or 0)
            transport_attempt = int(payload.get("transportAttempt") or 1)
            record = next(
                (
                    item
                    for item in reversed(requests)
                    if item.get("sourceId") == self.source_id
                    and item.get("requestIndex") == request_index
                    and item.get("transportAttempt") == transport_attempt
                    and item.get("finishedAt") is None
                ),
                None,
            )
            if record is None:
                record = {
                    "sourceId": self.source_id,
                    "phase": phase,
                    "requestIndex": request_index,
                    "transportAttempt": transport_attempt,
                    "startedAt": _iso(now),
                    "startEventMissing": True,
                }
                requests.append(record)
            record["finishedAt"] = _iso(now)
            record["outcome"] = str(payload.get("outcome") or "unknown")[:80]
            for key in _MODEL_NUMERIC_FIELDS:
                number = _number(payload.get(key))
                if number is not None:
                    record[key] = number
            if payload.get("finishReason") not in (None, ""):
                record["finishReason"] = str(payload.get("finishReason"))[:120]
            if payload.get("errorCategory") not in (None, ""):
                record["errorCategory"] = str(payload.get("errorCategory"))[:80]
        elif event_type == "tool.started":
            record = {
                "sourceId": self.source_id,
                "phase": phase,
                "tool": str(payload.get("tool") or "unknown")[:160],
                "startedAt": _iso(now),
                "startedAtMs": now,
            }
            for source, target in (("size_bytes", "reportedInputBytes"), ("file_count", "fileCount")):
                number = _number(payload.get(source))
                if number is not None:
                    record[target] = number
            tools.append(record)
        elif event_type == "tool.completed":
            tool_name = str(payload.get("tool") or "unknown")[:160]
            record = next(
                (
                    item
                    for item in reversed(tools)
                    if item.get("sourceId") == self.source_id
                    and item.get("tool") == tool_name
                    and item.get("finishedAt") is None
                ),
                None,
            )
            if record is None:
                record = {
                    "sourceId": self.source_id,
                    "phase": phase,
                    "tool": tool_name,
                    "startedAt": _iso(now),
                    "startedAtMs": now,
                    "startEventMissing": True,
                }
                tools.append(record)
            record["finishedAt"] = _iso(now)
            record["durationMs"] = max(0, now - int(record.pop("startedAtMs", now)))
            record["outcome"] = "failed" if payload.get("ok") is False else "completed"
            size_bytes = _number(payload.get("size_bytes"))
            if size_bytes is not None:
                record["reportedOutputBytes"] = size_bytes
            if payload.get("error") not in (None, ""):
                record["errorCode"] = str(payload.get("error"))[:120]
            if (
                value.get("firstArtifactWrite") is None
                and payload.get("ok") is not False
                and tool_name in {"write_skill_file", "write_skill_files"}
            ):
                value["firstArtifactWrite"] = {
                    "phase": phase,
                    "tool": tool_name,
                    "at": _iso(now),
                    "fileCount": int(payload.get("file_count") or 1),
                    "sizeBytes": int(payload.get("size_bytes") or 0),
                }
        elif event_type == "sandbox.created":
            sandboxes.append({"sourceId": self.source_id, "phase": phase, "startedAt": _iso(now), "startedAtMs": now})
        elif event_type == "sandbox.closed":
            record = next(
                (
                    item
                    for item in reversed(sandboxes)
                    if item.get("sourceId") == self.source_id and item.get("finishedAt") is None
                ),
                None,
            )
            if record is None:
                record = {
                    "sourceId": self.source_id,
                    "phase": phase,
                    "startedAt": _iso(now),
                    "startedAtMs": now,
                    "startEventMissing": True,
                }
                sandboxes.append(record)
            record["finishedAt"] = _iso(now)
            record["durationMs"] = max(0, now - int(record.pop("startedAtMs", now)))
        elif event_type == "agent.scenario_draft_persisted" and payload.get("ok") is not False:
            value["milestones"].setdefault("firstScenarioDraftWriteAt", _iso(now))
        else:
            return
        self._persist(value)


__all__ = ["SkillBuilderPerformanceTrace"]
