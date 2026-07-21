"""端到端优化流程集成测试 (mock 远程)。

验证 run_optimization() 完整 pipeline 的组装和错误传播。
所有外部依赖（AdapterClient、Trainer 等）均被 mock，
聚焦验证 pipeline 组装的正确性。
"""

from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from openjiuwen.agent_evolving.trainer.progress import Callbacks

from evo_agent.adapter_client.client import AdapterClient, AdapterError
from evo_agent.callbacks import ComposedCallbacks, SkillDocumentCallbacks, build_callbacks
from evo_agent.config import EvolveConfig
from evo_agent.optimizer_runner import run_optimization
from evo_agent.types import OptimizeReport, OptimizeRequest, TrainResult, ValResult


def _make_request(tmp_path: Path) -> OptimizeRequest:
    manifest = tmp_path / "dataset.yaml"
    manifest.write_text(
        """\
schema_version: "1.0"
name: integration_test
cases: items.json
train_split: 0.8
seed: 0
evaluator:
  dotted_path: openjiuwen.agent_evolving.evaluator.MetricEvaluator
  kwargs:
    metric: exact_match
""",
        encoding="utf-8",
    )
    return OptimizeRequest(
        scenario="integration_test",
        agent_name="integration_agent",
        dataset_manifest_path=manifest,
        adapter_url="http://adapter.test:8080",
        skills=["skill_a", "skill_b"],
        num_epochs=2,
    )


def _make_config(tmp_path: Path) -> EvolveConfig:
    return EvolveConfig(
        artifact_dir=tmp_path / "artifacts",
        remote_timeout=30.0,
        remote_max_retries=1,
    )


def _make_mock_adapter() -> AsyncMock:
    """创建模拟 AdapterClient（async context manager + skill_content）。"""
    mock = AsyncMock(spec=AdapterClient)
    mock.skill_content.side_effect = [
        "# Skill A content\nInitial version",
        "# Skill B content\nInitial version",
    ]
    mock.__aenter__ = AsyncMock(return_value=mock)
    mock.__aexit__ = AsyncMock(return_value=None)
    return mock


def _make_mock_dataset() -> SimpleNamespace:
    return SimpleNamespace(
        evaluator=MagicMock(),
        train_cases=[("case_1",), ("case_2",), ("case_3",)],
        val_cases=[("val_1",)],
    )


def _make_report(skills: tuple[str, ...], artifact_dir: Path) -> OptimizeReport:
    return OptimizeReport(
        skills=skills,
        dataset="integration_test",
        epochs_completed=2,
        edits_applied=4,
        train=TrainResult(
            score_before=0.5,
            score_after=0.85,
            improvement="+70%",
            pass_rate_before=0.5,
            pass_rate_after=0.85,
            num_cases=10,
        ),
        val=ValResult(
            final_score=0.80,
            best_score=0.85,
            per_epoch_scores=(0.75, 0.80),
            num_cases=5,
        ),
        gate_results=("accepted", "accepted"),
        artifact_dir=artifact_dir,
    )


async def test_full_optimize_flow(tmp_path: Path) -> None:
    """Mock 全部依赖 → run_optimization() 返回 OptimizeReport。"""
    request = _make_request(tmp_path)
    config = _make_config(tmp_path)
    dataset = _make_mock_dataset()
    report = _make_report(("skill_a", "skill_b"), config.artifact_dir / "skill_a")

    mock_adapter = _make_mock_adapter()
    mock_remote_agent = MagicMock()
    mock_op_a = MagicMock()
    mock_op_b = MagicMock()

    with (
        patch("evo_agent.optimizer_runner.AdapterClient", return_value=mock_adapter),
        patch(
            "evo_agent.optimizer_runner.build_skill_document_operator",
            side_effect=[mock_op_a, mock_op_b],
        ),
        patch(
            "evo_agent.optimizer_runner.RemoteAgent",
            return_value=mock_remote_agent,
        ),
        patch(
            "evo_agent.optimizer_runner.load_dataset_manifest",
            return_value=dataset,
        ),
        patch("evo_agent.optimizer_runner._create_llm"),
        patch("evo_agent.optimizer_runner.ScenarioRegistry") as mock_reg_cls,
        patch("evo_agent.optimizer_runner.SingleDimUpdater"),
        patch("evo_agent.optimizer_runner.build_callbacks"),
        patch("evo_agent.optimizer_runner.EvoTrainer") as mock_trainer_cls,
        patch(
            "evo_agent.optimizer_runner.ReportFormatter",
            return_value=MagicMock(format=MagicMock(return_value=report)),
        ),
    ):
        mock_reg_cls.return_value.build_optimizer.return_value = MagicMock()
        # C6 (#2): step 8.5 解包 trainer.evaluate(...) → 须返回 (score, evaluated)。
        mock_trainer_cls.return_value.evaluate.return_value = (0.0, [])
        result = await run_optimization(request, config)

    # Verify report returned
    assert isinstance(result, OptimizeReport)
    assert result.skills == ("skill_a", "skill_b")
    assert result.train.score_after > result.train.score_before

    # Verify pipeline was assembled
    mock_trainer_cls.assert_called_once()
    trainer = mock_trainer_cls.return_value
    trainer.train.assert_called_once()
    assert trainer.train.call_args.kwargs["agent"] is mock_remote_agent

    # Verify AdapterClient lifecycle
    mock_adapter.__aenter__.assert_awaited_once()
    mock_adapter.__aexit__.assert_awaited_once()


async def test_remote_communication_failure(tmp_path: Path) -> None:
    """AdapterClient 连接失败 → 异常传播到 run_optimization()。"""
    request = _make_request(tmp_path)
    config = _make_config(tmp_path)

    # AdapterClient raises on skill_content
    mock_adapter = AsyncMock(spec=AdapterClient)
    mock_adapter.skill_content.side_effect = AdapterError("Connection refused", status_code=0)
    mock_adapter.__aenter__ = AsyncMock(return_value=mock_adapter)
    mock_adapter.__aexit__ = AsyncMock(return_value=None)

    # resolve() 先于 adapter 块调用 registry.load_scenario_config(request.scenario)，
    # 需 patch ScenarioRegistry 以拿到 mock scenario config，流程方能走到 _build_operators
    # → skill_content → AdapterError。
    with (
        patch("evo_agent.optimizer_runner.AdapterClient", return_value=mock_adapter),
        patch("evo_agent.optimizer_runner.ScenarioRegistry"),
    ):
        with pytest.raises(AdapterError, match="Connection refused"):
            await run_optimization(request, config)

    # Verify cleanup still happens
    mock_adapter.__aexit__.assert_awaited_once()


async def test_report_propagated_from_formatter(tmp_path: Path) -> None:
    """run_optimization 返回 ReportFormatter.format() 的结果，不做额外加工。

    验证 pipeline 末端的 report 传递路径：ReportFormatter → format() → 返回值。
    注意：部分 case 失败的处理在 Trainer 内部（agent-core），不在 run_optimization 中。
    """
    request = _make_request(tmp_path)
    config = _make_config(tmp_path)
    dataset = _make_mock_dataset()
    report = _make_report(("skill_a", "skill_b"), config.artifact_dir / "skill_a")

    mock_adapter = _make_mock_adapter()
    mock_remote_agent = MagicMock()
    mock_trainer = MagicMock()
    # C6 (#2): optimizer_runner.step 8.5 调 trainer.evaluate 并解包 (score, evaluated)，
    # mock 须返回 2 元组，否则 "not enough values to unpack"。
    mock_trainer.evaluate.return_value = (0.0, [])

    with (
        patch("evo_agent.optimizer_runner.AdapterClient", return_value=mock_adapter),
        patch("evo_agent.optimizer_runner.build_skill_document_operator"),
        patch(
            "evo_agent.optimizer_runner.RemoteAgent",
            return_value=mock_remote_agent,
        ),
        patch(
            "evo_agent.optimizer_runner.load_dataset_manifest",
            return_value=dataset,
        ),
        patch("evo_agent.optimizer_runner._create_llm"),
        patch("evo_agent.optimizer_runner.ScenarioRegistry") as mock_reg_cls,
        patch("evo_agent.optimizer_runner.SingleDimUpdater"),
        patch("evo_agent.optimizer_runner.build_callbacks"),
        patch("evo_agent.optimizer_runner.EvoTrainer", return_value=mock_trainer),
        patch(
            "evo_agent.optimizer_runner.ReportFormatter",
            return_value=MagicMock(format=MagicMock(return_value=report)),
        ),
    ):
        mock_reg_cls.return_value.build_optimizer.return_value = MagicMock()
        result = await run_optimization(request, config)

    # ReportFormatter.format() result is returned as-is
    mock_trainer.train.assert_called_once()
    assert result is report
    assert result.skills == ("skill_a", "skill_b")
    assert result.train.score_after == 0.85


async def test_callback_composition() -> None:
    """SkillDocumentCallbacks + ProgressCallback 正确组合。

    验证 build_callbacks() 在 Wave 3 简化后只组合两步回调，
    且 SkillDocumentCallbacks 始终在第一位。
    """
    optimizer = MagicMock()
    progress_cb = MagicMock(spec=Callbacks)

    result = build_callbacks(optimizer, progress_callback=progress_cb)

    assert isinstance(result, ComposedCallbacks)
    assert len(result._callbacks) == 2
    assert isinstance(result._callbacks[0], SkillDocumentCallbacks)
    assert result._callbacks[1] is progress_cb
