# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

import json
import re
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


RUNNING_STATUS = "running"
WAITING_FOR_USER_STATUS = "waiting_for_user"
HIGH_FREQUENCY_STREAM_EVENT_TYPES = frozenset({"assistant.delta"})


def _system_now_ms() -> int:
    return int(time.time() * 1000)


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def _load_json_object(path: Path) -> dict[str, Any]:
    try:
        parsed = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _iso_from_ms(value: int | None) -> str | None:
    if value is None:
        return None
    return datetime.fromtimestamp(value / 1000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _iso_to_ms(value: Any) -> int | None:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except (TypeError, ValueError):
        return None


class SkillBuilderRunTiming:
    """Durable per-generation phase and milestone telemetry.

    This intentionally lives in the Skill Builder workspace rather than a new
    database table so interrupted runs retain the last observed phase even when
    the backend process dies before its final status transaction.
    """

    def __init__(self, *, root: Path, workspace_id: str, now_ms: Callable[[], int] = _system_now_ms):
        self._clock = now_ms
        self.root = root.resolve()
        self.workspace_id = workspace_id
        self.run_id = uuid.uuid4().hex
        self.started_ms = self._clock()
        self.completed_ms: int | None = None
        self.status = "running"
        self.phases: dict[str, dict[str, Any]] = {}
        self.milestones: dict[str, int] = {}
        self.last_activity_ms = self.started_ms
        self.event_count = 0
        self.queued_ms: int | None = None
        self.queue_wait_ms = 0
        self.current_agent_phase = ""
        self.performance_model_requests: list[dict[str, Any]] = []
        self.performance_tool_calls: list[dict[str, Any]] = []
        self.performance_sandboxes: list[dict[str, Any]] = []
        self.performance_first_artifact_write: dict[str, Any] | None = None
        self.start_phase("prepare")
        marker = self.root / ".skill-builder" / "current-generation.json"
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text(
            _json_text(
                {
                    "schemaVersion": "skill-builder-current-generation/v1",
                    "workspaceId": workspace_id,
                    "runId": self.run_id,
                    "startedAt": _iso_from_ms(self.started_ms),
                    "status": "running",
                }
            ),
            encoding="utf-8",
        )
        self.persist()

    @staticmethod
    def _phase_key(name: str, attempt: Any = None) -> str:
        normalized = re.sub(r"[^a-z0-9_-]+", "_", str(name or "unknown").strip().lower()) or "unknown"
        if attempt in (None, "", 0, "0"):
            return normalized
        return f"{normalized}_{attempt}"

    def start_phase(self, name: str, *, attempt: Any = None) -> str:
        now = self._clock()
        key = self._phase_key(name, attempt)
        phase = self.phases.setdefault(
            key,
            {
                "name": str(name or "unknown"),
                "attempt": attempt,
                "startedAtMs": now,
                "finishedAtMs": None,
                "lastActivityAtMs": now,
                "eventCount": 0,
            },
        )
        if phase.get("finishedAtMs") is not None:
            # Repeated deterministic checks and repair passes receive a fresh
            # segment instead of erasing the earlier duration.
            suffix = 2
            base = key
            while f"{base}_{suffix}" in self.phases:
                suffix += 1
            key = f"{base}_{suffix}"
            self.phases[key] = {
                "name": str(name or "unknown"),
                "attempt": attempt,
                "startedAtMs": now,
                "finishedAtMs": None,
                "lastActivityAtMs": now,
                "eventCount": 0,
            }
        self.last_activity_ms = now
        return key

    def finish_phase(self, name: str, *, attempt: Any = None, status: str = "completed") -> None:
        key_prefix = self._phase_key(name, attempt)
        candidates = [
            (key, value) for key, value in self.phases.items()
            if (key == key_prefix or key.startswith(f"{key_prefix}_")) and value.get("finishedAtMs") is None
        ]
        if not candidates:
            return
        key, phase = candidates[-1]
        now = self._clock()
        phase["finishedAtMs"] = now
        phase["lastActivityAtMs"] = now
        phase["status"] = status
        self.last_activity_ms = now

    def milestone(self, name: str, *, replace: bool = False) -> None:
        now = self._clock()
        if replace or name not in self.milestones:
            self.milestones[name] = now
        self.last_activity_ms = now

    @staticmethod
    def _numeric(value: Any, *, integer: bool = False) -> int | float | None:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return None
        return int(value) if integer else round(float(value), 3)

    def observe_performance(self, event_type: str, payload: dict[str, Any]) -> None:
        """Record model transport telemetry that must not enter workspace events."""

        now = self._clock()
        request_index = self._numeric(payload.get("requestIndex"), integer=True)
        transport_attempt = self._numeric(payload.get("transportAttempt"), integer=True)
        phase = str(payload.get("phase") or self.current_agent_phase or "unknown")[:80]
        if event_type == "internal.performance.llm_request_started":
            record: dict[str, Any] = {
                "phase": phase,
                "requestIndex": request_index,
                "transportAttempt": transport_attempt,
                "stream": bool(payload.get("stream")),
                "model": str(payload.get("model") or "")[:160],
                "startedAtMs": now,
            }
            if isinstance(payload.get("enableThinking"), bool):
                record["enableThinking"] = payload["enableThinking"]
            for key in (
                "temperature",
                "topP",
                "headroomRatio",
            ):
                value = self._numeric(payload.get(key))
                if value is not None:
                    record[key] = value
            for key in (
                "maxTokens",
                "requestBytes",
                "originalRequestBytes",
                "budgetBytes",
                "configuredMaxRequestBytes",
                "messageCount",
                "messageBytes",
                "toolCount",
                "toolSchemaBytes",
                "contextGrowthBytes",
                "contextGrowthMessages",
            ):
                value = self._numeric(payload.get(key), integer=True)
                if value is not None:
                    record[key] = value
            reductions = payload.get("reductions")
            if isinstance(reductions, list):
                record["reductions"] = [str(item)[:120] for item in reductions[:20]]
            roles = payload.get("roles")
            if isinstance(roles, dict):
                record["roles"] = {
                    str(key)[:40]: int(value)
                    for key, value in roles.items()
                    if isinstance(value, int) and not isinstance(value, bool)
                }
            self.performance_model_requests.append(record)
        elif event_type == "internal.performance.llm_request_completed":
            record = next(
                (
                    item
                    for item in reversed(self.performance_model_requests)
                    if item.get("requestIndex") == request_index
                    and item.get("transportAttempt") == transport_attempt
                    and item.get("finishedAtMs") is None
                ),
                None,
            )
            if record is None:
                record = {
                    "phase": phase,
                    "requestIndex": request_index,
                    "transportAttempt": transport_attempt,
                    "startedAtMs": now,
                }
                self.performance_model_requests.append(record)
            record["finishedAtMs"] = now
            record["outcome"] = str(payload.get("outcome") or "unknown")[:80]
            for key in (
                "durationMs",
                "firstOutputMs",
                "providerLatencyMs",
            ):
                value = self._numeric(payload.get(key))
                if value is not None:
                    record[key] = value
            for key in (
                "responseBytes",
                "chunkCount",
                "inputTokens",
                "outputTokens",
                "totalTokens",
                "cacheTokens",
                "contentBytes",
                "reasoningBytes",
                "parserBytes",
                "toolCallBytes",
                "contentChunkCount",
                "reasoningChunkCount",
                "parserChunkCount",
                "toolCallChunkCount",
            ):
                value = self._numeric(payload.get(key), integer=True)
                if value is not None:
                    record[key] = value
            if payload.get("finishReason") not in (None, ""):
                record["finishReason"] = str(payload.get("finishReason"))[:120]
            if payload.get("errorCategory") not in (None, ""):
                record["errorCategory"] = str(payload.get("errorCategory"))[:80]
        self.last_activity_ms = now

    def _observe_runtime_performance(self, event_type: str, payload: dict[str, Any], now: int) -> None:
        if event_type == "tool.started":
            record: dict[str, Any] = {
                "phase": self.current_agent_phase or str(payload.get("phase") or "unknown")[:80],
                "tool": str(payload.get("tool") or "unknown")[:160],
                "startedAtMs": now,
            }
            reported_bytes = self._numeric(payload.get("size_bytes"), integer=True)
            if reported_bytes is not None:
                record["reportedInputBytes"] = reported_bytes
            self.performance_tool_calls.append(record)
        elif event_type == "tool.completed":
            tool_name = str(payload.get("tool") or "unknown")[:160]
            record = next(
                (
                    item
                    for item in reversed(self.performance_tool_calls)
                    if item.get("tool") == tool_name and item.get("finishedAtMs") is None
                ),
                None,
            )
            if record is None:
                record = {
                    "phase": self.current_agent_phase or str(payload.get("phase") or "unknown")[:80],
                    "tool": tool_name,
                    "startedAtMs": now,
                    "startEventMissing": True,
                }
                self.performance_tool_calls.append(record)
            record["finishedAtMs"] = now
            record["durationMs"] = max(0, now - int(record.get("startedAtMs") or now))
            record["outcome"] = "failed" if payload.get("ok") is False else "completed"
            reported_bytes = self._numeric(payload.get("size_bytes"), integer=True)
            if reported_bytes is not None:
                record["reportedOutputBytes"] = reported_bytes
            if payload.get("error") not in (None, ""):
                record["errorCode"] = str(payload.get("error"))[:120]
            if (
                self.performance_first_artifact_write is None
                and payload.get("ok") is not False
                and tool_name in {"write_skill_file", "write_skill_files"}
            ):
                self.performance_first_artifact_write = {
                    "phase": record.get("phase"),
                    "tool": tool_name,
                    "atMs": now,
                    "fileCount": int(payload.get("file_count") or 1),
                    "sizeBytes": int(payload.get("size_bytes") or 0),
                }
        elif event_type == "sandbox.created":
            self.performance_sandboxes.append(
                {
                    "phase": self.current_agent_phase or str(payload.get("phase") or "unknown")[:80],
                    "startedAtMs": now,
                }
            )
        elif event_type == "sandbox.closed":
            phase = str(payload.get("phase") or self.current_agent_phase or "unknown")[:80]
            record = next(
                (item for item in reversed(self.performance_sandboxes) if item.get("finishedAtMs") is None),
                None,
            )
            if record is None:
                record = {"phase": phase, "startedAtMs": now, "startEventMissing": True}
                self.performance_sandboxes.append(record)
            if record.get("phase") == "unknown":
                record["phase"] = phase
            record["finishedAtMs"] = now
            record["durationMs"] = max(0, now - int(record.get("startedAtMs") or now))

        if event_type == "agent.scenario_draft_persisted" and payload.get("ok") is not False:
            self.milestone("firstScenarioDraftWriteAt")

    def observe(self, event_type: str, payload: dict[str, Any]) -> None:
        now = self._clock()
        self.event_count += 1
        self.last_activity_ms = now
        phase_name = str(payload.get("phase") or "").strip()
        attempt = payload.get("attempt")
        if event_type == "agent.started" and phase_name:
            self.current_agent_phase = phase_name
            self.finish_phase("prepare")
            self.start_phase(phase_name, attempt=attempt)
        elif event_type == "agent.error" and (phase_name or self.current_agent_phase):
            failed_phase = phase_name or self.current_agent_phase
            self.finish_phase(failed_phase, attempt=attempt, status="failed")
            if self.current_agent_phase == failed_phase:
                self.current_agent_phase = ""
        elif event_type == "sandbox.closed" and phase_name:
            self.finish_phase(phase_name, attempt=attempt)
            if self.current_agent_phase == phase_name:
                self.current_agent_phase = ""
        elif event_type == "hitl.requested":
            self.start_phase("hitl_wait")
            self.milestone("firstHitlRequestedAt")
        elif event_type in {"hitl.answered", "hitl.timeout"}:
            self.finish_phase("hitl_wait")
            self.milestone("lastHitlFinishedAt", replace=True)

        if phase_name:
            prefix = self._phase_key(phase_name, attempt)
            for key, phase in reversed(list(self.phases.items())):
                if key == prefix or key.startswith(f"{prefix}_"):
                    phase["lastActivityAtMs"] = now
                    phase["eventCount"] = int(phase.get("eventCount") or 0) + 1
                    break

        self._observe_runtime_performance(event_type, payload, now)

        if event_type == "tool.completed":
            tool_name = str(payload.get("tool") or "")
            path_value = str(payload.get("path") or "").replace("\\", "/")
            if payload.get("ok") is not False:
                if tool_name == "read_workspace_file" and path_value.startswith("inputs/"):
                    self.milestone("firstMaterialReadAt")
                write_paths = [path_value] if path_value else []
                if tool_name in {"write_skill_file", "write_skill_files"}:
                    self.milestone("firstArtifactWriteAt")
                    self.milestone("lastArtifactWriteAt", replace=True)
                    if any(value.endswith("SKILL.md") for value in write_paths):
                        self.milestone("firstSkillHeadingWriteAt")
                    if any(
                        "/scripts/" in f"/{value}" or value.startswith("scripts/")
                        for value in write_paths
                    ):
                        self.milestone("firstScriptWriteAt")

    def absorb_persisted_events(self, rows: list[Any]) -> None:
        """Recover exact worker-process tool timestamps from durable events."""

        hitl_started: dict[str, int] = {}
        hitl_finished: dict[str, int] = {}
        persisted_event_count = 0
        persisted_max_seq = 0
        persisted_agent_phase = ""
        queue_started_candidates = [
            int(getattr(row, "create_time", 0) or 0)
            for row in rows
            if getattr(row, "event_type", None) == "agent.queue_started"
            and int(getattr(row, "create_time", 0) or 0) <= self.started_ms + 60_000
        ]
        if queue_started_candidates:
            queue_started = max(queue_started_candidates)
            queued_candidates = [
                int(getattr(row, "create_time", 0) or 0)
                for row in rows
                if getattr(row, "event_type", None) == "agent.queued"
                and int(getattr(row, "create_time", 0) or 0) <= queue_started
            ]
            if queued_candidates:
                self.queued_ms = max(queued_candidates)
                self.queue_wait_ms = max(0, queue_started - self.queued_ms)
        for row in rows:
            created_ms = int(getattr(row, "create_time", 0) or 0)
            if created_ms < self.started_ms:
                continue
            event_type = str(getattr(row, "event_type", "") or "")
            persisted_max_seq = max(persisted_max_seq, int(getattr(row, "seq", 0) or 0))
            if event_type in HIGH_FREQUENCY_STREAM_EVENT_TYPES:
                continue
            persisted_event_count += 1
            payload = getattr(row, "payload_json", None)
            payload = payload if isinstance(payload, dict) else {}
            tool_name = str(payload.get("tool") or "")
            path_value = str(payload.get("path") or "").replace("\\", "/")

            phase_name = str(payload.get("phase") or "").strip()
            if event_type == "agent.started" and phase_name:
                persisted_agent_phase = phase_name
            elif event_type == "sandbox.closed" and phase_name and persisted_agent_phase == phase_name:
                persisted_agent_phase = ""

            def set_first(name: str) -> None:
                if name not in self.milestones or created_ms < self.milestones[name]:
                    self.milestones[name] = created_ms

            def set_last(name: str) -> None:
                if name not in self.milestones or created_ms > self.milestones[name]:
                    self.milestones[name] = created_ms

            if event_type == "tool.completed" and payload.get("ok") is not False:
                if tool_name == "read_workspace_file" and path_value.startswith("inputs/"):
                    set_first("firstMaterialReadAt")
                write_paths = [path_value] if path_value else []
                if tool_name in {"write_skill_file", "write_skill_files"}:
                    set_first("firstArtifactWriteAt")
                    set_last("lastArtifactWriteAt")
                    if any(value.endswith("SKILL.md") for value in write_paths):
                        set_first("firstSkillHeadingWriteAt")
                    if any(
                        "/scripts/" in f"/{value}" or value.startswith("scripts/")
                        for value in write_paths
                    ):
                        set_first("firstScriptWriteAt")
            request_id = str(payload.get("request_id") or "").strip()
            if event_type == "hitl.waiting" and request_id:
                hitl_started[request_id] = min(hitl_started.get(request_id, created_ms), created_ms)
                set_first("firstHitlRequestedAt")
            elif event_type in {"hitl.answer.submitted", "hitl.timeout", "hitl.expired"} and request_id:
                hitl_finished[request_id] = max(hitl_finished.get(request_id, created_ms), created_ms)
                set_last("lastHitlFinishedAt")

        for request_id, started_ms in hitl_started.items():
            key = self._phase_key("hitl_wait", request_id[:12])
            finished_ms = hitl_finished.get(request_id)
            self.phases[key] = {
                "name": "hitl_wait",
                "attempt": request_id,
                "startedAtMs": started_ms,
                "finishedAtMs": finished_ms,
                "lastActivityAtMs": finished_ms or started_ms,
                "eventCount": 2 if finished_ms else 1,
            }
        self.event_count = max(self.event_count, persisted_max_seq or persisted_event_count)
        if rows:
            self.last_activity_ms = max(self.last_activity_ms, max(int(getattr(row, "create_time", 0) or 0) for row in rows))

    def complete(self, status: str) -> None:
        now = self._clock()
        for phase in self.phases.values():
            if phase.get("finishedAtMs") is None:
                phase["finishedAtMs"] = now
                phase["lastActivityAtMs"] = now
        self.completed_ms = now
        self.status = status
        marker = self.root / ".skill-builder" / "current-generation.json"
        marker.write_text(
            _json_text(
                {
                    "schemaVersion": "skill-builder-current-generation/v1",
                    "workspaceId": self.workspace_id,
                    "runId": self.run_id,
                    "queuedAt": _iso_from_ms(self.queued_ms),
                    "startedAt": _iso_from_ms(self.started_ms),
                    "finishedAt": _iso_from_ms(now),
                    "status": status,
                }
            ),
            encoding="utf-8",
        )
        self.persist()

    @staticmethod
    def _public_performance_record(record: dict[str, Any]) -> dict[str, Any]:
        result = {
            key: value
            for key, value in record.items()
            if key not in {"startedAtMs", "finishedAtMs"}
        }
        result["startedAt"] = _iso_from_ms(int(record.get("startedAtMs"))) if record.get("startedAtMs") is not None else None
        result["finishedAt"] = _iso_from_ms(int(record.get("finishedAtMs"))) if record.get("finishedAtMs") is not None else None
        return result

    def _performance_snapshot(self, phases: list[dict[str, Any]]) -> dict[str, Any]:
        persisted = _load_json_object(
            self.root / ".skill-builder" / "performance" / f"{self.run_id}.json"
        )
        persisted_matches_run = persisted.get("runId") == self.run_id
        model_requests = (
            [item for item in persisted.get("modelRequests") or [] if isinstance(item, dict)]
            if persisted_matches_run
            else self.performance_model_requests
        )
        tool_calls = (
            [item for item in persisted.get("toolCalls") or [] if isinstance(item, dict)]
            if persisted_matches_run
            else self.performance_tool_calls
        )
        sandboxes = (
            [item for item in persisted.get("sandboxes") or [] if isinstance(item, dict)]
            if persisted_matches_run
            else self.performance_sandboxes
        )
        first_artifact_write = (
            persisted.get("firstArtifactWrite")
            if persisted_matches_run
            else self.performance_first_artifact_write
        )
        phase_durations: dict[str, int] = {}
        for phase in phases:
            name = str(phase.get("name") or "unknown")
            phase_durations[name] = phase_durations.get(name, 0) + int(phase.get("durationMs") or 0)
        phase_names = sorted(
            {
                *phase_durations,
                *(str(item.get("phase") or "unknown") for item in model_requests),
                *(str(item.get("phase") or "unknown") for item in tool_calls),
            }
        )
        summaries: list[dict[str, Any]] = []
        for phase in phase_names:
            requests = [item for item in model_requests if str(item.get("phase") or "unknown") == phase]
            tools = [item for item in tool_calls if str(item.get("phase") or "unknown") == phase]
            model_ms = round(sum(float(item.get("durationMs") or 0) for item in requests), 3)
            model_wait_ms = round(
                sum(
                    float(item.get("firstOutputMs"))
                    if item.get("firstOutputMs") is not None
                    else float(item.get("durationMs") or 0)
                    for item in requests
                ),
                3,
            )
            tool_ms = sum(int(item.get("durationMs") or 0) for item in tools)
            phase_ms = phase_durations.get(phase, 0)
            summaries.append(
                {
                    "phase": phase,
                    "phaseDurationMs": phase_ms,
                    "modelRequestCount": len(
                        {(item.get("sourceId"), item.get("requestIndex")) for item in requests}
                    ),
                    "modelTransportAttemptCount": len(requests),
                    "modelRetryCount": sum(1 for item in requests if int(item.get("transportAttempt") or 1) > 1),
                    "modelTotalMs": model_ms,
                    "modelWaitForFirstOutputMs": model_wait_ms,
                    "modelOutputAfterFirstMs": round(max(0.0, model_ms - model_wait_ms), 3),
                    "toolCallCount": len(tools),
                    "toolTotalMs": tool_ms,
                    "unattributedRuntimeMs": max(0, int(round(phase_ms - model_ms - tool_ms))),
                    "maxRequestBytes": max((int(item.get("requestBytes") or 0) for item in requests), default=0),
                    "inputTokens": sum(int(item.get("inputTokens") or 0) for item in requests),
                    "outputTokens": sum(int(item.get("outputTokens") or 0) for item in requests),
                }
            )
        return {
            "schemaVersion": "skill-builder-performance/v1",
            "privacy": "metadata_only",
            "modelRequests": [self._public_performance_record(item) for item in model_requests],
            "toolCalls": [self._public_performance_record(item) for item in tool_calls],
            "sandboxes": [self._public_performance_record(item) for item in sandboxes],
            "firstArtifactWrite": (
                first_artifact_write
                if persisted_matches_run and isinstance(first_artifact_write, dict)
                else
                {
                    **{
                        key: value
                        for key, value in first_artifact_write.items()
                        if key != "atMs"
                    },
                    "at": _iso_from_ms(int(first_artifact_write["atMs"])),
                }
                if first_artifact_write is not None
                else None
            ),
            "phaseSummary": summaries,
        }

    def snapshot(self) -> dict[str, Any]:
        now = self.completed_ms or self._clock()
        phases: list[dict[str, Any]] = []
        for key, phase in self.phases.items():
            started = int(phase.get("startedAtMs") or self.started_ms)
            finished = int(phase.get("finishedAtMs")) if phase.get("finishedAtMs") is not None else None
            effective_end = finished or now
            phases.append(
                {
                    "id": key,
                    "name": phase.get("name"),
                    "attempt": phase.get("attempt"),
                    "status": phase.get("status") or ("completed" if finished is not None else "running"),
                    "startedAt": _iso_from_ms(started),
                    "finishedAt": _iso_from_ms(finished),
                    "durationMs": max(0, effective_end - started),
                    "lastActivityAt": _iso_from_ms(int(phase.get("lastActivityAtMs") or started)),
                    "eventCount": int(phase.get("eventCount") or 0),
                }
            )
        phases.sort(key=lambda item: str(item.get("startedAt") or ""))
        longest = max(phases, key=lambda item: int(item.get("durationMs") or 0), default=None)
        hitl_intervals = sorted(
            (
                int(self.phases[key].get("startedAtMs") or self.started_ms),
                int(self.phases[key].get("finishedAtMs") or now),
            )
            for key in self.phases
            if self.phases[key].get("name") == "hitl_wait"
        )
        merged_hitl: list[tuple[int, int]] = []
        for started, finished in hitl_intervals:
            finished = max(started, finished)
            if merged_hitl and started <= merged_hitl[-1][1]:
                merged_hitl[-1] = (merged_hitl[-1][0], max(merged_hitl[-1][1], finished))
            else:
                merged_hitl.append((started, finished))
        user_wait_ms = sum(finished - started for started, finished in merged_hitl)
        wall_duration_ms = max(0, now - self.started_ms)
        queue_wait_ms = max(0, self.queue_wait_ms)
        total_duration_ms = wall_duration_ms + queue_wait_ms
        return {
            "schemaVersion": "skill-builder-run-timing/v1",
            "workspaceId": self.workspace_id,
            "runId": self.run_id,
            "status": self.status,
            "queuedAt": _iso_from_ms(self.queued_ms),
            "startedAt": _iso_from_ms(self.started_ms),
            "updatedAt": _iso_from_ms(now),
            "finishedAt": _iso_from_ms(self.completed_ms),
            "totalDurationMs": total_duration_ms,
            "wallDurationMs": wall_duration_ms,
            "userWaitMs": user_wait_ms,
            "activeDurationMs": max(0, wall_duration_ms - user_wait_ms),
            "queueWaitMs": queue_wait_ms,
            "lastActivityAt": _iso_from_ms(self.last_activity_ms),
            "stalledForMs": 0 if self.status == WAITING_FOR_USER_STATUS else max(0, now - self.last_activity_ms),
            "eventCount": self.event_count,
            "activePhases": [item["id"] for item in phases if item["status"] == "running"],
            "longestPhase": longest,
            "phases": phases,
            "milestones": {key: _iso_from_ms(value) for key, value in self.milestones.items()},
            "performance": self._performance_snapshot(phases),
        }

    def persist(self) -> dict[str, Any]:
        snapshot = self.snapshot()
        history_path = self.root / ".skill-builder" / "run-timings" / f"{self.run_id}.json"
        history_path.parent.mkdir(parents=True, exist_ok=True)
        history_path.write_text(_json_text(snapshot), encoding="utf-8")
        return snapshot


def finalize_interrupted_run_timing(
    root: Path,
    *,
    status: str = "failed",
    error: str | None = None,
    now_ms: Callable[[], int] = _system_now_ms,
) -> dict[str, Any] | None:
    """Close the current live timing snapshot after interruption or recovery."""

    root = root.resolve()
    marker_path = root / ".skill-builder" / "current-generation.json"
    try:
        marker = json.loads(marker_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        marker = {}
    marker = marker if isinstance(marker, dict) else {}
    marker_run_id = str(marker.get("runId") or "").strip()

    history_path = root / ".skill-builder" / "run-timings" / f"{marker_run_id}.json" if marker_run_id else None
    try:
        snapshot = json.loads(history_path.read_text(encoding="utf-8")) if history_path is not None else None
    except (OSError, ValueError, TypeError):
        snapshot = None
    if not isinstance(snapshot, dict) or snapshot.get("status") != "running":
        return snapshot if isinstance(snapshot, dict) else None

    now_ms = now_ms()
    finished_at = _iso_from_ms(now_ms)
    final_status = str(status or "failed").strip() or "failed"

    def parse_iso_ms(value: Any) -> int | None:
        try:
            parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return int(parsed.timestamp() * 1000)
        except (TypeError, ValueError):
            return None

    started_ms = parse_iso_ms(snapshot.get("startedAt"))
    if started_ms is not None:
        wall_duration_ms = max(0, now_ms - started_ms)
        queue_wait_ms = max(0, int(snapshot.get("queueWaitMs") or 0))
        snapshot["wallDurationMs"] = wall_duration_ms
        snapshot["totalDurationMs"] = wall_duration_ms + queue_wait_ms
        user_wait_ms = int(snapshot.get("userWaitMs") or 0)
        pause_started_ms = _iso_to_ms(snapshot.get("hitlPauseStartedAt"))
        if pause_started_ms is not None:
            user_wait_ms += max(0, now_ms - pause_started_ms)
        snapshot["userWaitMs"] = user_wait_ms
        snapshot["activeDurationMs"] = max(0, wall_duration_ms - user_wait_ms)
    snapshot["status"] = final_status
    snapshot["updatedAt"] = finished_at
    snapshot["finishedAt"] = finished_at
    snapshot["activePhases"] = []
    if error:
        snapshot["terminalError"] = str(error)[:1000]

    phases = snapshot.get("phases")
    phases = phases if isinstance(phases, list) else []
    for phase in phases:
        if not isinstance(phase, dict) or phase.get("status") != "running":
            continue
        phase_started_ms = parse_iso_ms(phase.get("startedAt"))
        if phase_started_ms is not None:
            phase["durationMs"] = max(0, now_ms - phase_started_ms)
        phase["status"] = "interrupted"
        phase["finishedAt"] = finished_at
    snapshot["longestPhase"] = max(
        (phase for phase in phases if isinstance(phase, dict)),
        key=lambda phase: int(phase.get("durationMs") or 0),
        default=None,
    )

    run_id = str(snapshot.get("runId") or "").strip()
    if run_id:
        history_path = root / ".skill-builder" / "run-timings" / f"{run_id}.json"
        history_path.parent.mkdir(parents=True, exist_ok=True)
        history_path.write_text(_json_text(snapshot), encoding="utf-8")
    marker_payload = marker
    marker_payload.update(
        {
            "schemaVersion": "skill-builder-current-generation/v1",
            "workspaceId": snapshot.get("workspaceId"),
            "runId": snapshot.get("runId"),
            "startedAt": snapshot.get("startedAt"),
            "finishedAt": finished_at,
            "status": final_status,
        }
    )
    marker_path.parent.mkdir(parents=True, exist_ok=True)
    marker_path.write_text(_json_text(marker_payload), encoding="utf-8")
    return snapshot


def set_run_timing_hitl_pause(
    root: Path,
    *,
    paused: bool,
    now_ms: Callable[[], int] = _system_now_ms,
) -> dict[str, Any] | None:
    """Pause or resume a live timing snapshot without closing the generation."""

    root = root.resolve()
    marker_path = root / ".skill-builder" / "current-generation.json"
    marker = _load_json_object(marker_path)
    run_id = str(marker.get("runId") or "").strip()
    timing_path = root / ".skill-builder" / "run-timings" / f"{run_id}.json" if run_id else None
    snapshot = _load_json_object(timing_path) if timing_path is not None else {}
    if not snapshot:
        return None
    now_ms = now_ms()
    if paused:
        if snapshot.get("status") != "running":
            return snapshot
        status_value = WAITING_FOR_USER_STATUS
        snapshot["pausedForHitl"] = True
        snapshot["hitlPauseStartedAt"] = snapshot.get("hitlPauseStartedAt") or _iso_from_ms(now_ms)
    else:
        if snapshot.get("status") != WAITING_FOR_USER_STATUS or snapshot.get("pausedForHitl") is not True:
            return snapshot
        status_value = RUNNING_STATUS
        snapshot.pop("pausedForHitl", None)
        pause_started_ms = _iso_to_ms(snapshot.pop("hitlPauseStartedAt", None))
        if pause_started_ms is not None:
            snapshot["userWaitMs"] = int(snapshot.get("userWaitMs") or 0) + max(0, now_ms - pause_started_ms)
    started_ms = _iso_to_ms(snapshot.get("startedAt"))
    if started_ms is not None:
        wall_duration_ms = max(0, now_ms - started_ms)
        queue_wait_ms = max(0, int(snapshot.get("queueWaitMs") or 0))
        current_wait_ms = int(snapshot.get("userWaitMs") or 0)
        if paused:
            pause_started_ms = _iso_to_ms(snapshot.get("hitlPauseStartedAt"))
            if pause_started_ms is not None:
                current_wait_ms += max(0, now_ms - pause_started_ms)
        snapshot["totalDurationMs"] = wall_duration_ms + queue_wait_ms
        snapshot["wallDurationMs"] = wall_duration_ms
        snapshot["activeDurationMs"] = max(0, wall_duration_ms - current_wait_ms)
    snapshot["status"] = status_value
    snapshot["updatedAt"] = _iso_from_ms(now_ms)
    snapshot["lastActivityAt"] = _iso_from_ms(now_ms)
    snapshot["stalledForMs"] = 0
    snapshot["finishedAt"] = None
    timing_path.parent.mkdir(parents=True, exist_ok=True)
    timing_path.write_text(_json_text(snapshot), encoding="utf-8")
    marker.update(
        {
            "schemaVersion": "skill-builder-current-generation/v1",
            "workspaceId": snapshot.get("workspaceId"),
            "runId": snapshot.get("runId"),
            "startedAt": snapshot.get("startedAt"),
            "status": status_value,
        }
    )
    marker.pop("finishedAt", None)
    marker_path.parent.mkdir(parents=True, exist_ok=True)
    marker_path.write_text(_json_text(marker), encoding="utf-8")
    return snapshot


def refresh_running_run_timing(
    root: Path,
    *,
    persisted_events: list[Any] | None = None,
    now_ms: Callable[[], int] = _system_now_ms,
) -> dict[str, Any] | None:
    """Refresh live phase durations so a quiet run remains diagnosable."""

    root = root.resolve()
    marker = _load_json_object(root / ".skill-builder" / "current-generation.json")
    run_id = str(marker.get("runId") or "").strip()
    timing_path = root / ".skill-builder" / "run-timings" / f"{run_id}.json" if run_id else None
    try:
        snapshot = json.loads(timing_path.read_text(encoding="utf-8")) if timing_path is not None else None
    except (OSError, ValueError, TypeError):
        return None
    if not isinstance(snapshot, dict) or snapshot.get("status") not in {"running", WAITING_FOR_USER_STATUS}:
        return snapshot if isinstance(snapshot, dict) else None
    if snapshot.get("status") == WAITING_FOR_USER_STATUS and snapshot.get("pausedForHitl") is not True:
        return snapshot if isinstance(snapshot, dict) else None

    now_ms = now_ms()
    started_ms = _iso_to_ms(snapshot.get("startedAt"))
    if persisted_events and started_ms is not None:
        relevant_events = [
            row for row in persisted_events
            if int(getattr(row, "create_time", 0) or 0) >= started_ms
            and str(getattr(row, "event_type", "") or "") not in HIGH_FREQUENCY_STREAM_EVENT_TYPES
        ]
        if relevant_events:
            hitl_started: dict[str, int] = {}
            hitl_finished: dict[str, int] = {}
            persisted_max_seq = max(
                int(getattr(row, "seq", 0) or 0)
                for row in relevant_events
            )
            snapshot["eventCount"] = max(
                int(snapshot.get("eventCount") or 0),
                persisted_max_seq or len(relevant_events),
            )
            latest_event_ms = max(int(getattr(row, "create_time", 0) or 0) for row in relevant_events)
            existing_activity_ms = _iso_to_ms(snapshot.get("lastActivityAt")) or started_ms
            snapshot["lastActivityAt"] = _iso_from_ms(max(existing_activity_ms, latest_event_ms))
            milestones = snapshot.get("milestones") if isinstance(snapshot.get("milestones"), dict) else {}

            def set_first(name: str, created_ms: int) -> None:
                current = _iso_to_ms(milestones.get(name))
                if current is None or created_ms < current:
                    milestones[name] = _iso_from_ms(created_ms)

            def set_last(name: str, created_ms: int) -> None:
                current = _iso_to_ms(milestones.get(name))
                if current is None or created_ms > current:
                    milestones[name] = _iso_from_ms(created_ms)

            for row in relevant_events:
                created_ms = int(getattr(row, "create_time", 0) or 0)
                event_type = str(getattr(row, "event_type", "") or "")
                payload = getattr(row, "payload_json", None)
                payload = payload if isinstance(payload, dict) else {}
                tool_name = str(payload.get("tool") or "")
                path_value = str(payload.get("path") or "").replace("\\", "/")
                if event_type == "tool.completed" and payload.get("ok") is not False:
                    if tool_name == "read_workspace_file" and path_value.startswith("inputs/"):
                        set_first("firstMaterialReadAt", created_ms)
                    write_paths = [path_value] if path_value else []
                    if tool_name in {"write_skill_file", "write_skill_files"}:
                        set_first("firstArtifactWriteAt", created_ms)
                        set_last("lastArtifactWriteAt", created_ms)
                        if any(value.endswith("SKILL.md") for value in write_paths):
                            set_first("firstSkillHeadingWriteAt", created_ms)
                        if any(
                            "/scripts/" in f"/{value}" or value.startswith("scripts/")
                            for value in write_paths
                        ):
                            set_first("firstScriptWriteAt", created_ms)
                if event_type in {"hitl.waiting", "hitl.requested"}:
                    set_first("firstHitlRequestedAt", created_ms)
                    request_id = str(payload.get("request_id") or payload.get("requestId") or "unknown").strip() or "unknown"
                    hitl_started[request_id] = min(hitl_started.get(request_id, created_ms), created_ms)
                elif event_type in {"hitl.answer.submitted", "hitl.answered", "hitl.timeout", "hitl.expired"}:
                    set_last("lastHitlFinishedAt", created_ms)
                    request_id = str(payload.get("request_id") or payload.get("requestId") or "unknown").strip() or "unknown"
                    hitl_finished[request_id] = max(hitl_finished.get(request_id, created_ms), created_ms)
            snapshot["milestones"] = milestones
            hitl_intervals = sorted(
                (interval_start, max(interval_start, hitl_finished.get(request_id, now_ms)))
                for request_id, interval_start in hitl_started.items()
            )
            merged_intervals: list[tuple[int, int]] = []
            for interval_start, interval_end in hitl_intervals:
                if merged_intervals and interval_start <= merged_intervals[-1][1]:
                    merged_intervals[-1] = (
                        merged_intervals[-1][0],
                        max(merged_intervals[-1][1], interval_end),
                    )
                else:
                    merged_intervals.append((interval_start, interval_end))
            reconstructed_wait_ms = sum(end - start for start, end in merged_intervals)
            snapshot["userWaitMs"] = max(int(snapshot.get("userWaitMs") or 0), reconstructed_wait_ms)

            phases = snapshot.get("phases") if isinstance(snapshot.get("phases"), list) else []
            existing_phases = {
                str(phase.get("id") or ""): phase
                for phase in phases
                if isinstance(phase, dict) and str(phase.get("id") or "")
            }
            for request_id, interval_start in hitl_started.items():
                phase_id = f"hitl_wait_{request_id[:12]}"
                interval_end = hitl_finished.get(request_id)
                phase = existing_phases.get(phase_id)
                if phase is None:
                    phase = {
                        "id": phase_id,
                        "name": "hitl_wait",
                        "attempt": request_id,
                    }
                    phases.append(phase)
                    existing_phases[phase_id] = phase
                existing_start = _iso_to_ms(phase.get("startedAt"))
                effective_start = min(existing_start, interval_start) if existing_start is not None else interval_start
                phase.update(
                    {
                        "status": "completed" if interval_end is not None else "running",
                        "startedAt": _iso_from_ms(effective_start),
                        "finishedAt": _iso_from_ms(interval_end),
                        "durationMs": max(0, (interval_end or now_ms) - effective_start),
                        "lastActivityAt": _iso_from_ms(interval_end or interval_start),
                        "eventCount": max(int(phase.get("eventCount") or 0), 2 if interval_end is not None else 1),
                    }
                )
            snapshot["phases"] = phases
    last_activity_ms = _iso_to_ms(snapshot.get("lastActivityAt"))
    if started_ms is not None:
        wall_duration_ms = max(0, now_ms - started_ms)
        queue_wait_ms = max(0, int(snapshot.get("queueWaitMs") or 0))
        snapshot["totalDurationMs"] = wall_duration_ms + queue_wait_ms
        snapshot["wallDurationMs"] = wall_duration_ms
        user_wait_ms = int(snapshot.get("userWaitMs") or 0)
        pause_started_ms = _iso_to_ms(snapshot.get("hitlPauseStartedAt"))
        effective_user_wait_ms = user_wait_ms + (max(0, now_ms - pause_started_ms) if pause_started_ms is not None else 0)
        snapshot["activeDurationMs"] = max(0, wall_duration_ms - effective_user_wait_ms)
    if last_activity_ms is not None:
        snapshot["stalledForMs"] = (
            0
            if snapshot.get("status") == WAITING_FOR_USER_STATUS
            else max(0, now_ms - last_activity_ms)
        )
    snapshot["updatedAt"] = _iso_from_ms(now_ms)

    phases = snapshot.get("phases")
    phases = phases if isinstance(phases, list) else []
    for phase in phases:
        if not isinstance(phase, dict) or phase.get("status") != "running":
            continue
        phase_started_ms = _iso_to_ms(phase.get("startedAt"))
        if phase_started_ms is not None:
            phase["durationMs"] = max(0, now_ms - phase_started_ms)
    snapshot["longestPhase"] = max(
        (phase for phase in phases if isinstance(phase, dict)),
        key=lambda phase: int(phase.get("durationMs") or 0),
        default=None,
    )

    run_id = str(snapshot.get("runId") or "").strip()
    if run_id:
        history_path = root / ".skill-builder" / "run-timings" / f"{run_id}.json"
        history_path.parent.mkdir(parents=True, exist_ok=True)
        history_path.write_text(_json_text(snapshot), encoding="utf-8")
    return snapshot

__all__ = [
    "HIGH_FREQUENCY_STREAM_EVENT_TYPES",
    "SkillBuilderRunTiming",
    "finalize_interrupted_run_timing",
    "refresh_running_run_timing",
    "set_run_timing_hitl_pause",
]
