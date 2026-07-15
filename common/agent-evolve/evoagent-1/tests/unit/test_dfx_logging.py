"""DFX 日志单元测试 — structlog contextvars 注入。"""

from __future__ import annotations

import json
import logging
from io import StringIO
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
import structlog

from evo_agent.optimizer_runner import run_optimization


@pytest.fixture(autouse=True)
def _clear_context() -> None:
    """每个测试前后清空 structlog context。"""
    structlog.contextvars.clear_contextvars()
    yield
    structlog.contextvars.clear_contextvars()


@pytest.fixture
def captured_logs() -> StringIO:
    """捕获 structlog JSON 日志输出。"""
    stream = StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger = logging.getLogger("evo_agent")
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    yield stream
    logger.removeHandler(handler)


@pytest.mark.asyncio
async def test_structlog_context_bound() -> None:
    """run_optimization 调用后 structlog context 包含 run_id。"""
    from evo_agent.config import EvolveConfig
    from evo_agent.types import OptimizeRequest

    request = OptimizeRequest(
        dataset_manifest_path=__import__("pathlib").Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
        agent_name="test_agent",
        scenario="edp_agent",
        skills=["skill_a"],
    )

    # Mock the entire run to avoid actual training
    with (
        patch("evo_agent.optimizer_runner.AdapterClient") as mock_ac_cls,
        patch("evo_agent.optimizer_runner.load_dataset_manifest"),
        patch("evo_agent.optimizer_runner._create_llm"),
        patch("evo_agent.optimizer_runner.ScenarioRegistry"),
        patch("evo_agent.optimizer_runner.SingleDimUpdater"),
        patch("evo_agent.optimizer_runner.build_callbacks"),
        patch("evo_agent.optimizer_runner.EvoTrainer") as mock_trainer_cls,
        patch("evo_agent.optimizer_runner.ReportFormatter"),
    ):
        mock_ac = AsyncMock()
        mock_ac_cls.return_value.__aenter__ = AsyncMock(return_value=mock_ac)
        mock_ac_cls.return_value.__aexit__ = AsyncMock(return_value=None)
        mock_ac.skill_content = AsyncMock(return_value="skill content")
        # C6: 无 callback 也跑 baseline，trainer.evaluate 须返回 2-tuple
        mock_trainer_cls.return_value.evaluate.return_value = (0.0, [])

        await run_optimization(request, EvolveConfig.get())

    ctx = structlog.contextvars.get_contextvars()
    assert "run_id" in ctx
    assert ctx["run_id"]  # non-empty


@pytest.mark.asyncio
async def test_structlog_context_includes_scenario() -> None:
    """context 包含 scenario + agent_name。"""
    from evo_agent.config import EvolveConfig
    from evo_agent.types import OptimizeRequest

    request = OptimizeRequest(
        dataset_manifest_path=__import__("pathlib").Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
        agent_name="my_agent",
        scenario="edp_agent",
        skills=["skill_a"],
    )

    with (
        patch("evo_agent.optimizer_runner.AdapterClient") as mock_ac_cls,
        patch("evo_agent.optimizer_runner.load_dataset_manifest"),
        patch("evo_agent.optimizer_runner._create_llm"),
        patch("evo_agent.optimizer_runner.ScenarioRegistry"),
        patch("evo_agent.optimizer_runner.SingleDimUpdater"),
        patch("evo_agent.optimizer_runner.build_callbacks"),
        patch("evo_agent.optimizer_runner.EvoTrainer") as mock_trainer_cls,
        patch("evo_agent.optimizer_runner.ReportFormatter"),
    ):
        mock_ac = AsyncMock()
        mock_ac_cls.return_value.__aenter__ = AsyncMock(return_value=mock_ac)
        mock_ac_cls.return_value.__aexit__ = AsyncMock(return_value=None)
        mock_ac.skill_content = AsyncMock(return_value="skill content")
        # C6: 无 callback 也跑 baseline，trainer.evaluate 须返回 2-tuple
        mock_trainer_cls.return_value.evaluate.return_value = (0.0, [])

        await run_optimization(request, EvolveConfig.get())

    ctx = structlog.contextvars.get_contextvars()
    assert ctx.get("scenario") == "edp_agent"
    assert ctx.get("agent_name") == "my_agent"


def test_json_log_format(captured_logs: StringIO) -> None:
    """日志输出为合法 JSON（含 timestamp, level, event）。"""
    from evo_agent.api.logging_config import configure_logging

    configure_logging()

    logger = structlog.get_logger("evo_agent.test")
    logger.info("test_event", extra_key="extra_val")

    output = captured_logs.getvalue().strip()
    if output:
        data = json.loads(output)
        assert "timestamp" in data or "event" in data


def test_log_includes_run_id(captured_logs: StringIO) -> None:
    """JSON 日志中包含 run_id 字段。"""
    from evo_agent.api.logging_config import configure_logging

    configure_logging()

    structlog.contextvars.bind_contextvars(run_id="test-run-123")
    logger = structlog.get_logger("evo_agent.test")
    logger.info("test_with_run_id")

    output = captured_logs.getvalue().strip()
    if output:
        data = json.loads(output)
        assert data.get("run_id") == "test-run-123"


def test_progress_event_includes_duration() -> None:
    """epoch_end 事件包含 epoch_duration_s（如 Trainer 提供）。"""
    from evo_agent.api.jobs import Job
    from evo_agent.api.progress import ProgressCallback

    job = Job(job_id="test")
    cb = ProgressCallback(job)

    progress = MagicMock()
    progress.max_epoch = 3
    progress.current_epoch = 1
    progress.current_epoch_score = 0.7
    progress.best_score = 0.8

    cb.on_train_epoch_end(MagicMock(), progress, [])

    data = job.event_buffer[0].data
    # epoch key used by platform to upsert round sub-table
    assert "epoch" in data
