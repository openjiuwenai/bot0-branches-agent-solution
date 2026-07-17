"""SSEEvent + Job event buffer 单元测试。"""

from __future__ import annotations

import pytest

from evo_agent.api.events import SSEEvent
from evo_agent.api.jobs import Job

# ── SSEEvent dataclass ──


def test_sse_event_fields() -> None:
    """SSEEvent 包含 id, event, data, timestamp。"""
    e = SSEEvent(id=1, event="progress", data={"epoch": 1}, timestamp=1000.0)
    assert e.id == 1
    assert e.event == "progress"
    assert e.data == {"epoch": 1}
    assert e.timestamp == 1000.0


def test_sse_event_frozen() -> None:
    """SSEEvent 是 frozen dataclass。"""
    e = SSEEvent(id=1, event="progress", data={}, timestamp=0.0)
    with pytest.raises(AttributeError):
        e.id = 2  # type: ignore[misc]


def test_sse_event_timestamp_auto() -> None:
    """timestamp 自动填充（默认 0.0，允许调用方指定）。"""
    e = SSEEvent(id=1, event="progress", data={})
    assert isinstance(e.timestamp, float)


# ── Job event buffer ──


def test_job_push_event() -> None:
    """事件追加到 buffer，id 自增。"""
    job = Job(job_id="test")
    job.push_event("progress", {"epoch": 1})
    job.push_event("progress", {"epoch": 2})

    assert len(job.event_buffer) == 2
    assert job.event_buffer[0].id == 1
    assert job.event_buffer[0].event == "progress"
    assert job.event_buffer[1].id == 2


def test_job_event_buffer_maxlen() -> None:
    """buffer 满（5000）时旧事件被丢弃。"""
    job = Job(job_id="test")
    for i in range(5050):
        job.push_event("progress", {"i": i})

    assert len(job.event_buffer) == 5000
    # oldest event should be #51 (0-indexed: pushed 5050, discarded first 50)
    assert job.event_buffer[0].id == 51


def test_get_events_since_zero() -> None:
    """last_event_id=0 返回全部事件。"""
    job = Job(job_id="test")
    job.push_event("progress", {"epoch": 1})
    job.push_event("progress", {"epoch": 2})
    job.push_event("progress", {"epoch": 3})

    events = job.get_events_since(0)
    assert len(events) == 3


def test_get_events_since_mid() -> None:
    """last_event_id=5 返回 id > 5 的事件。"""
    job = Job(job_id="test")
    for i in range(10):
        job.push_event("progress", {"i": i})

    events = job.get_events_since(5)
    assert len(events) == 5
    assert events[0].id == 6


def test_get_events_since_all_seen() -> None:
    """last_event_id >= 最大 id 时返回空列表。"""
    job = Job(job_id="test")
    job.push_event("progress", {"epoch": 1})
    job.push_event("progress", {"epoch": 2})

    events = job.get_events_since(2)
    assert events == []


# ── W10.6: EventType + PipelinePhase ──


def test_log_event_type_constant() -> None:
    """EventType.LOG == 'log'。"""
    from evo_agent.api.events import EventType

    assert EventType.LOG == "log"
    assert EventType.PROGRESS == "progress"
    assert EventType.COMPLETED == "completed"
    assert EventType.ERROR == "error"


def test_pipeline_phase_values() -> None:
    """PipelinePhase 包含所有 14 个阶段。"""
    from evo_agent.api.events import PipelinePhase

    assert PipelinePhase.ROLLOUT == "rollout"
    assert PipelinePhase.EVALUATE == "evaluate"
    assert PipelinePhase.ATTRIBUTE == "attribute"
    assert PipelinePhase.REFLECT == "reflect"
    assert PipelinePhase.AGGREGATE == "aggregate"
    assert PipelinePhase.SELECT == "select"
    assert PipelinePhase.APPLY == "apply"
    assert PipelinePhase.VALIDATION == "validation"
    assert PipelinePhase.EPOCH_BEGIN == "epoch_begin"
    assert PipelinePhase.EPOCH_END == "epoch_end"
    assert PipelinePhase.TRAIN_BEGIN == "train_begin"
    assert PipelinePhase.TRAIN_END == "train_end"
    assert PipelinePhase.SKILL_SYNC == "skill_sync"
    assert PipelinePhase.ROLLOUT_DONE == "rollout_done"
    assert len(PipelinePhase) == 14


def test_sse_event_accepts_log_event() -> None:
    """SSEEvent 可承载 log 事件类型。"""
    from evo_agent.api.events import EventType

    e = SSEEvent(id=1, event=EventType.LOG, data={"level": "info", "message": "x"})
    assert e.event == "log"
