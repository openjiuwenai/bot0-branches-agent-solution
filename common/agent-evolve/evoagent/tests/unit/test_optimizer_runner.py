"""optimizer_runner 单元测试 — Wave 3 重写。

Mock 策略：
- AdapterClient: MagicMock + AsyncMock（skill_content 为 AsyncMock）
- build_skill_document_operator: patch() 返回 MagicMock
- RemoteAgent: patch() 返回 MagicMock
- ScenarioRegistry.build_optimizer: patch() 返回 MagicMock
- Trainer.train: patch()
- load_dataset_manifest: patch() 返回 mock dataset
- ReportFormatter.format: patch() 返回 OptimizeReport
- _create_llm: patch() 返回 MagicMock
"""

from __future__ import annotations

import inspect
import json
import threading
from collections.abc import Callable
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from evo_agent.adapter_client.client import AdapterClient, AdapterError
from evo_agent.adapter_client.types import ManagedDocSnapshot
from evo_agent.cancellation import CancellationRequestedError, CancellationToken
from evo_agent.config import EvolveConfig
from evo_agent.optimizer_runner import _build_operators, run_optimization
from evo_agent.types import (
    ManagedDocEpochContent,
    OptimizeReport,
    OptimizeRequest,
    TrainResult,
    ValResult,
)

# ── Helpers ──


def _make_request(
    *,
    skills: list[str] | None = None,
    adapter_url: str = "http://adapter.test",
    agent_name: str = "test_agent",
    num_epochs: int = 2,
    managed_doc_kind: str | None = None,
    managed_doc_expected_revision: str | None = None,
) -> OptimizeRequest:
    return OptimizeRequest(
        scenario="edp_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url=adapter_url,
        skills=["skill_a"] if skills is None and managed_doc_kind is None else (skills or []),
        agent_name=agent_name,
        num_epochs=num_epochs,
        managed_doc_kind=managed_doc_kind,
        managed_doc_expected_revision=managed_doc_expected_revision,
    )


def _make_valid_snapshot(
    *,
    content: str = "# baseline agent rule",
    file_revision: str = "rev-1",
    applied_revision: str | None = "rev-1",
    pending_apply: bool = False,
    apply_mode: str = "restart",
    max_task_seconds: float = 60.0,
) -> ManagedDocSnapshot:
    return ManagedDocSnapshot(
        content=content,
        file_revision=file_revision,
        applied_revision=applied_revision,
        pending_apply=pending_apply,
        apply_mode=apply_mode,
        max_task_seconds=max_task_seconds,
    )


def _make_config(tmp_path: Path) -> EvolveConfig:
    return EvolveConfig(
        artifact_dir=tmp_path / "artifacts",
        remote_timeout=60.0,
        remote_max_retries=1,
    )


def _managed_doc_run_dir(tmp_path: Path, kind: str = "agent_rule") -> Path:
    """managed-doc run artifact 目录（canonical_id / run_id 嵌套隔离，单测仅一 run）。"""
    canonical = _make_config(tmp_path).artifact_dir / f"managed_doc:{kind}"
    run_dirs = [p for p in canonical.iterdir() if p.is_dir()]
    assert len(run_dirs) == 1, f"expected one run dir under {canonical}, got {run_dirs}"
    return run_dirs[0]


def _make_mock_dataset() -> SimpleNamespace:
    return SimpleNamespace(
        evaluator=MagicMock(),
        train_cases=("case_1", "case_2"),
        val_cases=("case_3",),
    )


def _make_mock_report(skills: tuple[str, ...]) -> OptimizeReport:
    return OptimizeReport(
        skills=skills,
        dataset="test_dataset",
        epochs_completed=2,
        edits_applied=3,
        train=TrainResult(
            score_before=0.5,
            score_after=0.8,
            improvement="+60%",
            pass_rate_before=0.5,
            pass_rate_after=0.8,
            num_cases=10,
        ),
        val=ValResult(
            final_score=0.75,
            best_score=0.8,
            per_epoch_scores=(0.7, 0.75),
            num_cases=5,
        ),
        gate_results=("accepted", "accepted"),
        artifact_dir=Path("/tmp/artifacts"),
    )


# ── Pipeline test harness ──


@pytest.fixture
def make_harness():
    """Create a test harness for run_optimization tests.

    Patches all optimizer_runner dependencies and returns a namespace
    with mock references. Use ``await h.run(request, config)`` to execute.
    Tests customize mock behavior between harness creation and ``run()``.
    """
    stack = ExitStack()

    def factory() -> SimpleNamespace:
        mock_adapter = AsyncMock(spec=AdapterClient)
        mock_adapter.__aenter__ = AsyncMock(return_value=mock_adapter)
        mock_adapter.__aexit__ = AsyncMock(return_value=None)
        dataset = _make_mock_dataset()
        report = _make_mock_report(("skill_a",))
        mock_remote_agent = MagicMock()
        mock_optimizer = MagicMock()
        mock_trainer = MagicMock()
        mock_trainer.evaluate.return_value = (0.0, [])
        mock_llm = MagicMock()

        ac_cls = stack.enter_context(
            patch("evo_agent.optimizer_runner.AdapterClient", return_value=mock_adapter),
        )
        bso = stack.enter_context(
            patch("evo_agent.optimizer_runner.build_skill_document_operator"),
        )
        # managed-doc operator factory mock（T11）。默认返回带空 records 的 applier。
        bmo = stack.enter_context(
            patch("evo_agent.optimizer_runner.build_managed_doc_operator"),
        )
        mock_md_op = MagicMock()
        mock_md_op.applier.records = ()
        mock_md_op.get_state.return_value = {"skill_content": "# baseline"}
        bmo.return_value = mock_md_op
        ra_cls = stack.enter_context(
            patch(
                "evo_agent.optimizer_runner.RemoteAgent",
                return_value=mock_remote_agent,
            ),
        )
        ldm = stack.enter_context(
            patch(
                "evo_agent.optimizer_runner.load_dataset_manifest",
                return_value=dataset,
            ),
        )
        cllm = stack.enter_context(
            patch("evo_agent.optimizer_runner._create_llm", return_value=mock_llm),
        )
        reg_cls = stack.enter_context(
            patch(
                "evo_agent.optimizer_runner.ScenarioRegistry",
                return_value=MagicMock(
                    build_optimizer=MagicMock(return_value=mock_optimizer),
                ),
            ),
        )
        sdu = stack.enter_context(
            patch("evo_agent.optimizer_runner.SingleDimUpdater"),
        )
        bcb = stack.enter_context(
            patch("evo_agent.optimizer_runner.build_callbacks"),
        )
        trainer_cls = stack.enter_context(
            patch(
                "evo_agent.optimizer_runner.EvoTrainer",
                return_value=mock_trainer,
            ),
        )
        rf_cls = stack.enter_context(
            patch("evo_agent.optimizer_runner.ReportFormatter"),
        )
        rf_cls.return_value.format.return_value = report

        async def run(
            request: OptimizeRequest | None = None,
            config: EvolveConfig | None = None,
            **kwargs: Any,
        ) -> OptimizeReport:
            req = request or _make_request()
            cfg = config or _make_config(Path("/tmp/test"))
            return await run_optimization(req, cfg, **kwargs)

        return SimpleNamespace(
            adapter=mock_adapter,
            ac_cls=ac_cls,
            bso=bso,
            bmo=bmo,
            md_op=mock_md_op,
            ra_cls=ra_cls,
            ldm=ldm,
            cllm=cllm,
            reg_cls=reg_cls,
            sdu=sdu,
            bcb=bcb,
            trainer_cls=trainer_cls,
            trainer=mock_trainer,
            rf_cls=rf_cls,
            remote_agent=mock_remote_agent,
            optimizer=mock_optimizer,
            llm=mock_llm,
            dataset=dataset,
            report=report,
            run=run,
        )

    yield factory
    stack.close()


# ── _build_operators ──


async def test_runner_stops_at_safe_point_when_cancellation_was_requested(
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    h = make_harness()
    token = CancellationToken()
    token.request()

    with pytest.raises(CancellationRequestedError):
        await h.run(cancellation_token=token)

    h.ac_cls.assert_not_called()


async def test_build_operators_fetches_content() -> None:
    """每个 skill 调用 skill_content() + build_skill_document_operator()。"""
    adapter_client = AsyncMock(spec=AdapterClient)
    adapter_client.skill_content.side_effect = ["# content_a", "# content_b"]

    mock_op_a = MagicMock()
    mock_op_b = MagicMock()

    with patch(
        "evo_agent.optimizer_runner.build_skill_document_operator",
        side_effect=[mock_op_a, mock_op_b],
    ) as mock_factory:
        result = await _build_operators(["skill_a", "skill_b"], adapter_client)

    assert adapter_client.skill_content.await_count == 2
    adapter_client.skill_content.assert_any_await("skill_a")
    adapter_client.skill_content.assert_any_await("skill_b")

    assert mock_factory.call_count == 2
    mock_factory.assert_any_call(
        "skill_a", "# content_a", adapter_client, preserve_frontmatter=True
    )
    mock_factory.assert_any_call(
        "skill_b", "# content_b", adapter_client, preserve_frontmatter=True
    )

    assert result == {"skill_a": mock_op_a, "skill_b": mock_op_b}


@pytest.mark.asyncio
async def test_build_operators_passes_preserve_frontmatter_false_to_factory() -> None:
    """preserve_frontmatter=False 透传给 build_skill_document_operator。"""
    adapter_client = AsyncMock(spec=AdapterClient)
    adapter_client.skill_content.return_value = "# content"

    mock_op = MagicMock()
    with patch(
        "evo_agent.optimizer_runner.build_skill_document_operator",
        return_value=mock_op,
    ) as mock_factory:
        await _build_operators(["skill_a"], adapter_client, preserve_frontmatter=False)

    mock_factory.assert_called_once_with(
        "skill_a", "# content", adapter_client, preserve_frontmatter=False
    )


async def test_build_operators_empty_skills() -> None:
    """skills 为空时返回空 dict。"""
    adapter_client = AsyncMock(spec=AdapterClient)

    result = await _build_operators([], adapter_client)

    assert result == {}
    adapter_client.skill_content.assert_not_awaited()


async def test_skill_content_error_propagates() -> None:
    """skill_content() 抛出 AdapterError 时不被吞掉。"""
    adapter_client = AsyncMock(spec=AdapterClient)
    adapter_client.skill_content.side_effect = AdapterError("skill not found", status_code=404)

    with pytest.raises(AdapterError, match="skill not found"):
        await _build_operators(["missing_skill"], adapter_client)


# ── run_optimization ──


async def test_run_optimization_passes_config_preserve_frontmatter_to_build_operators(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """config.preserve_frontmatter 透传到 build_skill_document_operator 工厂调用。"""
    h = make_harness()
    config = _make_config(tmp_path)
    config.preserve_frontmatter = False

    await h.run(_make_request(), config)

    assert h.bso.called, "build_skill_document_operator was not called"
    for call in h.bso.call_args_list:
        assert call.kwargs.get("preserve_frontmatter") is False


async def test_run_optimization_defaults_preserve_frontmatter_true(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """config 默认 preserve_frontmatter=True 透传到工厂调用。"""
    h = make_harness()
    await h.run(_make_request(), _make_config(tmp_path))

    assert h.bso.called
    for call in h.bso.call_args_list:
        assert call.kwargs.get("preserve_frontmatter") is True


async def test_run_optimization_assembles_pipeline(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """端到端：AdapterClient → operators → RemoteAgent → Trainer.train。"""
    h = make_harness()
    h.adapter.skill_content.side_effect = ["# content_a", "# content_b"]

    request = _make_request(skills=["skill_a", "skill_b"])
    config = _make_config(tmp_path)
    h.rf_cls.return_value.format.return_value = _make_mock_report(("skill_a", "skill_b"))

    result = await h.run(request, config)

    assert result.skills == ("skill_a", "skill_b")
    h.ra_cls.assert_called_once()
    h.trainer.train.assert_called_once()


async def test_adapter_client_lifecycle(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """async with 确保 close() 被调用。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    await h.run(_make_request(), _make_config(tmp_path))

    h.adapter.__aenter__.assert_awaited_once()
    h.adapter.__aexit__.assert_awaited_once()


async def test_remote_agent_receives_correct_card(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """AgentCard.name == request.agent_name。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    captured_card: dict[str, Any] = {}

    def capture_remote_agent(*args: Any, **kwargs: Any) -> MagicMock:
        captured_card["card"] = kwargs.get("card", args[0] if args else None)
        return MagicMock()

    h.ra_cls.side_effect = capture_remote_agent

    await h.run(
        _make_request(agent_name="my_business_agent"),
        _make_config(tmp_path),
    )

    card = captured_card["card"]
    assert card is not None
    assert card.name == "my_business_agent"


async def test_dependencies_include_adapter_client(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """dependencies dict 包含 adapter_client 和 operators。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    mock_op = MagicMock()
    h.bso.return_value = mock_op

    captured_deps: dict[str, Any] = {}

    def capture_build_optimizer(req: Any, dependencies: dict[str, Any]) -> MagicMock:
        captured_deps.update(dependencies)
        return MagicMock()

    h.reg_cls.return_value.build_optimizer.side_effect = capture_build_optimizer

    await h.run(_make_request(skills=["skill_a"]), _make_config(tmp_path))

    assert "adapter_client" in captured_deps
    assert captured_deps["adapter_client"] is h.adapter
    assert "operators" in captured_deps
    assert captured_deps["operators"] == {"skill_a": mock_op}


async def test_adapter_client_uses_request_params(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """adapter_url, agent_name, timeout, retries 来自 request/config。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    request = _make_request(
        adapter_url="http://custom-adapter:8080",
        agent_name="custom_agent",
    )
    config = EvolveConfig(
        artifact_dir=tmp_path / "artifacts",
        remote_timeout=120.0,
        remote_max_retries=3,
    )

    await h.run(request, config)

    h.ac_cls.assert_called_once_with(
        "http://custom-adapter:8080",
        agent_name="custom_agent",
        timeout=120.0,
        max_retries=3,
    )


async def test_trainer_receives_remote_agent(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """Trainer.train(agent=remote_agent) 而非旧 agent。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    await h.run(_make_request(), _make_config(tmp_path))

    h.trainer.train.assert_called_once()
    call_kwargs = h.trainer.train.call_args.kwargs
    assert call_kwargs["agent"] is h.remote_agent


async def test_report_uses_run_id_artifact_dir(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """artifact_dir = config.artifact_dir / run_id。"""
    h = make_harness()
    h.adapter.skill_content.side_effect = ["# a", "# b"]

    request = _make_request(skills=["primary_skill", "secondary_skill"])
    config = _make_config(tmp_path)
    h.rf_cls.return_value.format.return_value = _make_mock_report(
        ("primary_skill", "secondary_skill"),
    )

    await h.run(request, config)

    h.rf_cls.assert_called_once()
    call_args = h.rf_cls.call_args
    # artifact_dir 应在 run_id 子目录下
    artifact_dir = call_args.args[0]
    assert artifact_dir.parent == tmp_path / "artifacts"
    assert len(artifact_dir.name) == 12  # uuid hex[:12]
    assert call_args.kwargs["skills"] == ("primary_skill", "secondary_skill")


# ── W10.4: val_per_epoch_scores passthrough ──


async def test_runner_passes_val_scores_to_formatter(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """progress_callback.candidate_per_epoch_scores 透传到 ReportFormatter。

    runner 把候选 fresh eval 分数（趋势图数据源）作为 val_per_epoch_scores 传给
    formatter，而非门控赢家分（val_per_epoch_scores，仅供 improved 判定）。
    """
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    mock_progress = MagicMock()
    mock_progress.candidate_per_epoch_scores = [0.6, 0.7, 0.8]

    await h.run(_make_request(), _make_config(tmp_path), progress_callback=mock_progress)

    h.rf_cls.assert_called_once()
    call_kwargs = h.rf_cls.call_args.kwargs
    assert call_kwargs["val_per_epoch_scores"] == (0.6, 0.7, 0.8)


async def test_runner_passes_num_val_cases_to_formatter(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """dataset.val_cases 长度透传到 ReportFormatter。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    # _make_mock_dataset has val_cases=("case_3",) → len=1
    await h.run(_make_request(), _make_config(tmp_path))

    h.rf_cls.assert_called_once()
    call_kwargs = h.rf_cls.call_args.kwargs
    assert call_kwargs["num_val_cases"] == 1


async def test_runner_no_progress_callback_empty_val_scores(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """progress_callback=None 时 val_per_epoch_scores 为空 tuple。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    await h.run(_make_request(), _make_config(tmp_path))

    h.rf_cls.assert_called_once()
    call_kwargs = h.rf_cls.call_args.kwargs
    assert call_kwargs["val_per_epoch_scores"] == ()


async def test_runner_console_style_callback_falls_back_to_trainer_gate_scores(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """CLI callback may print progress without exposing API trend attributes."""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    h.trainer.gate_epoch_scores = [
        {"base_score": 0.8, "candidate_score": 0.7},
    ]
    console_style_callback = SimpleNamespace()

    await h.run(
        _make_request(),
        _make_config(tmp_path),
        progress_callback=console_style_callback,
    )

    call_kwargs = h.rf_cls.call_args.kwargs
    assert call_kwargs["val_per_epoch_scores"] == (0.7,)
    assert call_kwargs["val_per_epoch_case_scores"] == []


# ── A5: val per-case 透传（baseline + per-epoch）──


async def test_runner_passes_val_per_case_to_formatter(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """baseline per-case + per-epoch per-case 透传到 ReportFormatter。

    baseline per-case 在 baseline 评估点（trainer.evaluate 返回的 evaluated）
    捕获——不读 trainer._cached_base_val_evaluated：后者在每轮
    _select_best_candidate_on_val 末尾被 record_validation_baseline 覆盖为该轮
    winner，训练结束后是末轮 winner 而非 baseline。
    """
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    # baseline 评估返回 2 个 case（per-case 分数 0.3 / 0.5）
    h.trainer.evaluate.return_value = (
        0.4,
        [MagicMock(score=0.3), MagicMock(score=0.5)],
    )
    mock_progress = MagicMock()
    mock_progress.candidate_per_epoch_scores = [0.45, 0.7]
    mock_progress.val_per_epoch_case_scores = [[0.4, 0.5], [0.7, 0.7]]

    await h.run(_make_request(), _make_config(tmp_path), progress_callback=mock_progress)

    h.rf_cls.assert_called_once()
    call_kwargs = h.rf_cls.call_args.kwargs
    # baseline per-case 从 evaluate 返回值捕获（非 _cached_base_val_evaluated）
    assert call_kwargs["val_baseline_case_scores"] == [0.3, 0.5]
    # per-epoch per-case 从 progress_callback 留存读取
    assert call_kwargs["val_per_epoch_case_scores"] == [[0.4, 0.5], [0.7, 0.7]]


async def test_runner_no_progress_callback_empty_val_per_case(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """progress_callback=None 时 val per-case 为空列表（formatter 退为 0.0）。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    h.trainer.evaluate.return_value = (0.4, [MagicMock(score=0.3)])

    await h.run(_make_request(), _make_config(tmp_path), progress_callback=None)

    h.rf_cls.assert_called_once()
    call_kwargs = h.rf_cls.call_args.kwargs
    # 无 callback → per-epoch per-case 为空；baseline per-case 仍从 evaluate 捕获
    assert call_kwargs["val_per_epoch_case_scores"] == []
    assert call_kwargs["val_baseline_case_scores"] == [0.3]


async def test_baseline_recorded_without_progress_callback(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """C6: CLI/无 callback 模式也预热 baseline + record_validation_baseline。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    h.trainer.evaluate.return_value = (0.42, [])

    await h.run(_make_request(), _make_config(tmp_path), progress_callback=None)

    h.trainer.record_validation_baseline.assert_called_once()
    args = h.trainer.record_validation_baseline.call_args.args
    assert args[0] == 0.42


async def test_baseline_recorded_with_progress_callback(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """有 callback 时同样预热 baseline，且 val_score_before 被回填。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    h.trainer.evaluate.return_value = (0.55, [])
    mock_cb = MagicMock()
    mock_cb.val_per_epoch_scores = []

    await h.run(_make_request(), _make_config(tmp_path), progress_callback=mock_cb)

    h.trainer.record_validation_baseline.assert_called_once()
    assert mock_cb.val_score_before == 0.55


async def test_baseline_eval_call_count_no_callback_le_callback(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """C6: 无 callback 模式 base validation 调用次数 ≤ 有 callback 模式。"""
    # 无 callback
    h1 = make_harness()
    h1.adapter.skill_content.return_value = "# content"
    h1.trainer.evaluate.return_value = (0.1, [])
    await h1.run(_make_request(), _make_config(tmp_path), progress_callback=None)
    no_cb_eval_calls = h1.trainer.evaluate.call_count

    # 有 callback
    h2 = make_harness()
    h2.adapter.skill_content.return_value = "# content"
    h2.trainer.evaluate.return_value = (0.1, [])
    cb = MagicMock()
    cb.val_per_epoch_scores = []
    await h2.run(_make_request(), _make_config(tmp_path), progress_callback=cb)
    cb_eval_calls = h2.trainer.evaluate.call_count

    assert no_cb_eval_calls <= cb_eval_calls


def test_run_optimization_signature_exposes_cooperative_cancellation_context() -> None:
    """runner 接收 token 与只写 baseline observer，旧 callback 参数保持兼容。"""
    sig = inspect.signature(run_optimization)
    params = list(sig.parameters.keys())
    assert params == [
        "request",
        "config",
        "progress_callback",
        "phase_callback",
        "cancellation_token",
        "managed_doc_baseline_callback",
    ]
    assert sig.parameters["progress_callback"].kind == inspect.Parameter.KEYWORD_ONLY
    assert sig.parameters["phase_callback"].kind == inspect.Parameter.KEYWORD_ONLY
    assert sig.parameters["cancellation_token"].kind == inspect.Parameter.KEYWORD_ONLY


# ── W10.7: phase_callback injection ──


async def test_phase_callback_injected_into_dependencies(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """phase_callback 参数注入到 dependencies dict。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    captured_deps: dict[str, Any] = {}

    def capture_build_optimizer(req: Any, dependencies: dict[str, Any]) -> MagicMock:
        captured_deps.update(dependencies)
        return MagicMock()

    mock_scenario_config = MagicMock()
    mock_scenario_config.rollout = {"extra_data": {}}
    h.reg_cls.return_value.load_scenario_config.return_value = mock_scenario_config
    h.reg_cls.return_value.build_optimizer.side_effect = capture_build_optimizer

    mock_phase_cb = MagicMock()
    await h.run(
        _make_request(),
        _make_config(tmp_path),
        phase_callback=mock_phase_cb,
    )

    assert "phase_callback" in captured_deps
    assert captured_deps["phase_callback"] is mock_phase_cb


async def test_phase_callback_default_noop(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """phase_callback=None 时注入 no-op lambda。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    captured_deps: dict[str, Any] = {}

    def capture_build_optimizer(req: Any, dependencies: dict[str, Any]) -> MagicMock:
        captured_deps.update(dependencies)
        return MagicMock()

    mock_scenario_config = MagicMock()
    mock_scenario_config.rollout = {"extra_data": {}}
    h.reg_cls.return_value.load_scenario_config.return_value = mock_scenario_config
    h.reg_cls.return_value.build_optimizer.side_effect = capture_build_optimizer

    await h.run(_make_request(), _make_config(tmp_path))

    assert "phase_callback" in captured_deps
    # Should be callable and not raise
    captured_deps["phase_callback"]("log", {"test": True})


# ── Coverage gap tests (Wave 3 补充) ──


async def test_trainer_train_runs_in_thread(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """Trainer.train 是同步方法，必须通过 asyncio.to_thread 调用。

    如果直接调用会阻塞 event loop。此测试验证 trainer.train
    在线程中被调用（通过 asyncio.to_thread 实现）。
    """
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    train_thread_id: list[int] = []
    main_thread_id = threading.current_thread().ident

    def mock_train(**kwargs: Any) -> None:
        train_thread_id.append(threading.current_thread().ident or 0)

    h.trainer.train.side_effect = mock_train

    await h.run(_make_request(), _make_config(tmp_path))

    assert len(train_thread_id) == 1
    assert train_thread_id[0] != main_thread_id, (
        "Trainer.train must run in a separate thread (asyncio.to_thread)"
    )


async def test_progress_callback_forwarded(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """progress_callback 参数传递给 build_callbacks()。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    mock_progress = MagicMock()

    await h.run(
        _make_request(),
        _make_config(tmp_path),
        progress_callback=mock_progress,
    )

    h.bcb.assert_called_once()
    call_kwargs = h.bcb.call_args.kwargs
    assert call_kwargs["progress_callback"] is mock_progress


async def test_empty_skills_uses_run_id_artifact_dir(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """skills 为空时，artifact_dir 仍然使用 run_id 子目录。"""
    h = make_harness()
    h.rf_cls.return_value.format.return_value = _make_mock_report(())

    request = _make_request(skills=[])
    config = _make_config(tmp_path)

    await h.run(request, config)

    call_args = h.rf_cls.call_args
    artifact_dir = call_args.args[0]
    assert artifact_dir.parent == tmp_path / "artifacts"
    assert len(artifact_dir.name) == 12  # uuid hex[:12]
    assert call_args.kwargs["skills"] == ()

    # _build_operators was not called (no skills)
    h.adapter.skill_content.assert_not_awaited()


async def test_trainer_train_receives_correct_args(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """Trainer.train 传入正确的 agent/train_cases/val_cases/num_iterations。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    request = _make_request(skills=["skill_a"], num_epochs=5)
    config = _make_config(tmp_path)

    await h.run(request, config)

    h.trainer.train.assert_called_once()
    call_kwargs = h.trainer.train.call_args.kwargs
    assert call_kwargs["agent"] is h.remote_agent
    assert call_kwargs["train_cases"] == h.dataset.train_cases
    assert call_kwargs["val_cases"] == h.dataset.val_cases
    assert call_kwargs["num_iterations"] == 5


async def test_trainer_train_error_still_cleans_up_adapter(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """Trainer.train() 抛出异常时，AdapterClient.__aexit__ 仍被调用（资源清理）。

    async with AdapterClient 必须保证在训练异常时也能正确关闭 HTTP clients。
    """
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    h.trainer.train.side_effect = RuntimeError("training exploded")

    with pytest.raises(RuntimeError, match="training exploded"):
        await h.run(_make_request(), _make_config(tmp_path))

    # AdapterClient cleanup must happen even on error
    h.adapter.__aenter__.assert_awaited_once()
    h.adapter.__aexit__.assert_awaited_once()


# ── W7.6: eval_runtime + evaluator_prompt ──


async def test_run_optimization_builds_eval_runtime(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """run_optimization() 构建 eval_runtime 并传入 load_dataset_manifest。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    await h.run(_make_request(), _make_config(tmp_path))

    # load_dataset_manifest 被调用时带有 eval_runtime 参数
    h.ldm.assert_called_once()
    call_kwargs = h.ldm.call_args.kwargs
    assert "eval_runtime" in call_kwargs
    eval_runtime = call_kwargs["eval_runtime"]
    assert "model_config" in eval_runtime
    assert "model_client_config" in eval_runtime


async def test_run_optimization_evaluator_prompt_injection(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """evaluator_prompt 非空时注入 eval_runtime['prompt_template']。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://adapter.test",
        skills=["skill_a"],
        evaluator_prompt="custom evaluation prompt",
    )
    await h.run(request, _make_config(tmp_path))

    call_kwargs = h.ldm.call_args.kwargs
    eval_runtime = call_kwargs["eval_runtime"]
    assert eval_runtime["prompt_template"] == "custom evaluation prompt"


async def test_run_optimization_evaluator_prompt_empty(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """evaluator_prompt 为空时不注入 prompt_template（使用 dataset.yaml 的值）。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    request = _make_request()  # evaluator_prompt defaults to ""
    await h.run(request, _make_config(tmp_path))

    call_kwargs = h.ldm.call_args.kwargs
    eval_runtime = call_kwargs["eval_runtime"]
    assert "prompt_template" not in eval_runtime


# ── W7.7: EvoTrainer + ConversationIdFactory ──


async def test_run_optimization_uses_evo_trainer(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """run_optimization() 使用 EvoTrainer 替代 Trainer。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    await h.run(_make_request(), _make_config(tmp_path))

    # EvoTrainer was instantiated (patched as trainer_cls)
    h.trainer_cls.assert_called_once()
    call_kwargs = h.trainer_cls.call_args.kwargs
    # EvoTrainer receives adapter_client and conversation_id_factory
    assert call_kwargs["adapter_client"] is h.adapter
    assert "conversation_id_factory" in call_kwargs


async def test_run_optimization_passes_scenario_num_parallel_to_evo_trainer(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """Validation rollout uses scenario num_parallel instead of Trainer default."""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    mock_scenario_config = MagicMock()
    mock_scenario_config.rollout = {"extra_data": {}}
    mock_scenario_config.hyperparams = {"num_parallel": 8}
    h.reg_cls.return_value.load_scenario_config.return_value = mock_scenario_config

    await h.run(_make_request(), _make_config(tmp_path))

    assert h.trainer_cls.call_args.kwargs["num_parallel"] == 8


async def test_run_optimization_request_num_parallel_overrides_scenario_for_evo_trainer(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """Request hyperparams win over scenario defaults for validation rollout."""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"
    mock_scenario_config = MagicMock()
    mock_scenario_config.rollout = {"extra_data": {}}
    mock_scenario_config.hyperparams = {"num_parallel": 8}
    h.reg_cls.return_value.load_scenario_config.return_value = mock_scenario_config

    request = _make_request()
    request.hyperparams["num_parallel"] = 12
    await h.run(request, _make_config(tmp_path))

    assert h.trainer_cls.call_args.kwargs["num_parallel"] == 12


async def test_run_optimization_injects_conversation_id_factory(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """ConversationIdFactory 实例化并注入 dependencies。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    captured_deps: dict[str, Any] = {}

    def capture_build_optimizer(req: Any, dependencies: dict[str, Any]) -> MagicMock:
        captured_deps.update(dependencies)
        return MagicMock()

    h.reg_cls.return_value.build_optimizer.side_effect = capture_build_optimizer

    await h.run(_make_request(), _make_config(tmp_path))

    assert "conversation_id_factory" in captured_deps
    from evo_agent.conversation import ConversationIdFactory

    assert isinstance(captured_deps["conversation_id_factory"], ConversationIdFactory)


# --- W8.6: 双轨分支 + hyperparams 注入 ---


@pytest.mark.asyncio
async def test_runner_api_mode_uses_build_dataset(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """dataset_manifest_path=None 时调用 build_dataset_from_request。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        adapter_url="http://adapter.test",
        skills=["skill_a"],
        dataset_manifest_path=None,  # API 模式
        dataset_path="/data/evo_agent/items.json",
        evaluator_prompt="test prompt",
    )

    # Mock build_dataset_from_request
    with patch(
        "evo_agent.optimizer_runner.build_dataset_from_request",
        return_value=h.dataset,
    ) as mock_build:
        await h.run(request, _make_config(tmp_path))
        mock_build.assert_called_once()
        call_kwargs = mock_build.call_args.kwargs
        assert call_kwargs["evaluator_prompt"] == "test prompt"
        assert call_kwargs["train_split"] == 0.8
        assert call_kwargs["val_split"] == 0.2
        assert call_kwargs["evaluator_config"] == {"type": "metric"}


@pytest.mark.asyncio
async def test_runner_cli_mode_uses_manifest(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """dataset_manifest_path 存在时调用 load_dataset_manifest（现有行为）。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    request = _make_request()  # dataset_manifest_path=Path("/tmp/dataset.yaml")
    await h.run(request, _make_config(tmp_path))

    h.ldm.assert_called_once()  # load_dataset_manifest 被调用


@pytest.mark.asyncio
async def test_runner_hyperparams_injected_to_dependencies(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """request.hyperparams 中的键出现在 dependencies 中。"""
    h = make_harness()
    h.adapter.skill_content.return_value = "# content"

    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        adapter_url="http://adapter.test",
        skills=["skill_a"],
        dataset_manifest_path=Path("/tmp/dataset.yaml"),  # CLI mode
        hyperparams={"custom_param": 42, "temperature": 0.7},
    )

    captured_deps: dict[str, Any] = {}

    def capture_build_optimizer(req: Any, dependencies: dict[str, Any]) -> MagicMock:
        captured_deps.update(dependencies)
        return MagicMock()

    # Mock load_scenario_config to return a proper mock config
    mock_scenario_config = MagicMock()
    mock_scenario_config.rollout = {"extra_data": {}}
    h.reg_cls.return_value.load_scenario_config.return_value = mock_scenario_config
    h.reg_cls.return_value.build_optimizer.side_effect = capture_build_optimizer

    await h.run(request, _make_config(tmp_path))

    assert captured_deps.get("custom_param") == 42
    assert captured_deps.get("temperature") == 0.7


# ── T4: _build_model_client_config helper ──


class TestBuildContextModelClientConfig:
    """_build_model_client_config 按 llm_provider 分派 OpenAI/ICBC。"""

    def test_openai_default_returns_openai_config(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """默认 OpenAI → client_provider=='OpenAI'，行为不回归。"""
        from evo_agent.optimizer_runner import _build_model_client_config

        for k in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(k, raising=False)
        config = EvolveConfig(
            _env_file=None,
            llm_api_key="sk-xxx",
            llm_base_url="https://api.openai.com/v1",
        )
        cfg = _build_model_client_config(config)
        assert cfg.client_provider == "OpenAI"
        assert cfg.api_key == "sk-xxx"
        assert cfg.api_base == "https://api.openai.com/v1"

    def test_icbc_returns_icbc_config_with_credential_mapping(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """ICBC → client_provider=='ICBC'，凭证映射 token→api_key 等。"""
        from evo_agent.optimizer_runner import _build_model_client_config

        for k in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(k, raising=False)
        config = EvolveConfig(
            _env_file=None,
            llm_provider="ICBC",
            icbc_token="the-token",
            icbc_user_id="the-user",
            icbc_endpoint="http://icbc/svc.htm",
            icbc_context_window_tokens=32768,
        )
        cfg = _build_model_client_config(config)
        assert cfg.client_provider == "ICBC"
        assert cfg.api_key == "the-token"
        assert cfg.api_base == "http://icbc/svc.htm"
        assert getattr(cfg, "user_id") == "the-user"
        # ICBC 内网 http，verify_ssl=False（_validate_config 不要求 ssl_cert）
        assert cfg.verify_ssl is False

    def test_evaluator_and_optimizer_share_provider(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """同一 config 产出同一 provider（评估器与优化器路径不割裂）。"""
        from evo_agent.optimizer_runner import _build_model_client_config

        for k in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(k, raising=False)
        config = EvolveConfig(
            _env_file=None,
            llm_provider="ICBC",
            icbc_token="t",
            icbc_user_id="u",
            icbc_endpoint="http://icbc/svc.htm",
            icbc_context_window_tokens=32768,
        )
        # helper 是纯函数；评估器与优化器两条路径调同一 helper → provider 一致
        cfg1 = _build_model_client_config(config)
        cfg2 = _build_model_client_config(config)
        assert cfg1.client_provider == cfg2.client_provider == "ICBC"

    def test_openai_path_does_not_require_icbc_fields(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """OpenAI 路径 ICBC 字段全空也能构造（不回归）。"""
        from evo_agent.optimizer_runner import _build_model_client_config

        for k in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(k, raising=False)
        config = EvolveConfig(_env_file=None, llm_provider="OpenAI", llm_api_key="sk")
        cfg = _build_model_client_config(config)
        assert cfg.client_provider == "OpenAI"


# ── managed-doc runner 分支（spec F7）──


def _md_harness_with_snapshot(
    make_harness: Callable[[], SimpleNamespace],
    snapshot: ManagedDocSnapshot | None,
) -> SimpleNamespace:
    """构造 managed-doc harness：get_managed_doc_sync 返回给定 snapshot。

    AsyncMock(spec=AdapterClient) 对 sync 方法的行为不稳定，故显式用 MagicMock
    覆盖 ``get_managed_doc_sync``，保证 ``asyncio.to_thread`` 调用返回 snapshot。
    """
    h = make_harness()
    h.adapter.get_managed_doc_sync = MagicMock(
        return_value=snapshot if snapshot is not None else _make_valid_snapshot()
    )
    return h


async def test_runner_managed_doc_canonical_id_used_as_operators_key_and_artifact_dir(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """canonical id 三处一致：operators key / artifact 目录 / skill_names（归因）。"""
    snap = _make_valid_snapshot(content="# agent rule baseline")
    h = _md_harness_with_snapshot(make_harness, snap)
    request = _make_request(managed_doc_kind="agent_rule")
    await h.run(request, _make_config(tmp_path))

    # 1) 不调全局 restore（不变量 4：第二次优化不回滚上次发布版本）
    h.adapter.restore_skill.assert_not_called()
    # 2) 读 baseline snapshot
    h.adapter.get_managed_doc_sync.assert_called_with("agent_rule")
    # 3) factory 用 doc_kind=agent_rule（内部 skill_name=managed_doc:agent_rule）
    assert h.bmo.call_args.kwargs["doc_kind"] == "agent_rule"
    # 4) operators dict key == canonical id
    operators = h.ra_cls.call_args.kwargs["operators"]
    assert set(operators.keys()) == {"managed_doc:agent_rule"}
    # 5) artifact 目录：canonical id 在父层、run_id 在叶层（嵌套隔离）
    run_artifact_dir = h.rf_cls.call_args.args[0]
    assert run_artifact_dir.parent.name == "managed_doc:agent_rule"
    assert run_artifact_dir.parent.parent == _make_config(tmp_path).artifact_dir
    # 6) trainer skill_names == [canonical id]（评估器经此归因）
    assert h.trainer_cls.call_args.kwargs["skill_names"] == ["managed_doc:agent_rule"]


async def test_runner_passes_captured_managed_doc_candidates_to_report(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    h = _md_harness_with_snapshot(make_harness, _make_valid_snapshot())
    h.trainer.managed_doc_epoch_contents.return_value = (
        "# candidate 1",
        "# rejected candidate 2",
    )
    h.trainer.gate_epoch_scores = [
        {"base_score": 0.1, "candidate_score": 0.2},
        {"base_score": 0.2, "candidate_score": 0.15},
    ]

    await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))

    assert h.rf_cls.call_args.kwargs["managed_doc_epoch_contents"] == (
        ManagedDocEpochContent(round=1, content="# candidate 1"),
        ManagedDocEpochContent(round=2, content="# rejected candidate 2"),
    )
    assert h.rf_cls.call_args.kwargs["val_per_epoch_scores"] == (0.2, 0.15)


async def test_runner_managed_doc_same_kind_two_runs_isolate_artifact_dirs(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """同 kind 二次优化隔离：canonical_id 父层下两个不同 run_id 子目录，互不残留。

    P1#3：避免旧 run 的 epoch_N/eval_results.json 残留污染新报告、
    以及二次 baseline 失败时旧 managed_doc_before.md 残留误导（违反 F8 AC）。
    """
    snap = _make_valid_snapshot(content="# agent rule baseline")
    request = _make_request(managed_doc_kind="agent_rule")
    config = _make_config(tmp_path)

    h1 = _md_harness_with_snapshot(make_harness, snap)
    await h1.run(request, config)
    h2 = _md_harness_with_snapshot(make_harness, snap)
    await h2.run(request, config)

    canonical = config.artifact_dir / "managed_doc:agent_rule"
    run_dirs = sorted(p.name for p in canonical.iterdir() if p.is_dir())
    assert len(run_dirs) == 2
    assert run_dirs[0] != run_dirs[1]


async def test_runner_finally_refreshes_diagnostics_after_apply(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """P2#8：finally 刷新 managed_doc_diagnostics.json 反映 apply 后状态（≠ job-start）。"""
    snap_start = _make_valid_snapshot(content="# rule v1", file_revision="rev-1")
    snap_after = _make_valid_snapshot(
        content="# rule v1", file_revision="rev-2", applied_revision="rev-2"
    )
    h = _md_harness_with_snapshot(make_harness, snap_start)
    h.adapter.get_managed_doc_sync = MagicMock(side_effect=[snap_start, snap_after])
    await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))

    art_dir = _managed_doc_run_dir(tmp_path)
    diag = json.loads((art_dir / "managed_doc_diagnostics.json").read_text(encoding="utf-8"))
    assert diag["file_revision"] == "rev-2"  # apply 后状态，非 job-start rev-1
    assert h.adapter.get_managed_doc_sync.call_count == 2  # job-start baseline + finally refresh


async def test_runner_finally_refresh_failure_keeps_job_start_diag(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """P2#8：finally 刷新失败时保留 job-start diag，不掩盖主流程结果。"""
    snap_start = _make_valid_snapshot(content="# rule v1", file_revision="rev-1")
    h = _md_harness_with_snapshot(make_harness, snap_start)
    h.adapter.get_managed_doc_sync = MagicMock(
        side_effect=[snap_start, RuntimeError("refresh boom")]
    )
    # 容错吞 refresh 异常，主流程不抛
    await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))

    art_dir = _managed_doc_run_dir(tmp_path)
    diag = json.loads((art_dir / "managed_doc_diagnostics.json").read_text(encoding="utf-8"))
    assert diag["file_revision"] == "rev-1"  # job-start 值保留，未被失败刷新清空


async def test_runner_job_start_accepts_only_fully_applied_snapshot_no_global_restore(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """valid baseline → 不调 restore，直接进入 baseline rollout。"""
    snap = _make_valid_snapshot()
    h = _md_harness_with_snapshot(make_harness, snap)
    await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))

    h.adapter.restore_skill.assert_not_called()
    h.adapter.get_managed_doc_sync.assert_called_with("agent_rule")
    # baseline 内容固化为 managed_doc_before.md
    before = _managed_doc_run_dir(tmp_path) / "managed_doc_before.md"
    assert before.read_text(encoding="utf-8") == snap.content


async def test_runner_rejects_changed_expected_revision_before_first_candidate_apply(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    from evo_agent.errors import ManagedDocBaselineChangedError

    job_start = _make_valid_snapshot(file_revision="rev-1", applied_revision="rev-1")
    changed = _make_valid_snapshot(file_revision="rev-2", applied_revision="rev-2")
    h = _md_harness_with_snapshot(make_harness, job_start)
    h.adapter.get_managed_doc_sync = MagicMock(side_effect=[job_start, changed])

    with pytest.raises(ManagedDocBaselineChangedError) as exc_info:
        await h.run(
            _make_request(
                managed_doc_kind="agent_rule",
                managed_doc_expected_revision="rev-1",
            ),
            _make_config(tmp_path),
        )

    assert exc_info.value.code == "MANAGED_DOC_BASELINE_CHANGED"
    h.trainer.train.assert_not_called()


async def test_runner_does_not_publish_unverified_job_start_baseline(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    from evo_agent.errors import ManagedDocBaselineChangedError

    unexpected = _make_valid_snapshot(file_revision="rev-2", applied_revision="rev-2")
    h = _md_harness_with_snapshot(make_harness, unexpected)
    observer = MagicMock()

    with pytest.raises(ManagedDocBaselineChangedError):
        await h.run(
            _make_request(
                managed_doc_kind="agent_rule",
                managed_doc_expected_revision="rev-1",
            ),
            _make_config(tmp_path),
            managed_doc_baseline_callback=observer,
        )

    observer.assert_not_called()
    h.trainer.evaluate.assert_not_called()
    h.trainer.train.assert_not_called()


async def test_runner_job_start_rejects_pending_apply_before_rollout(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """pending_apply=true → 不变量 5 违例，抛 ManagedDocBaselineError 不启动 rollout。"""
    from evo_agent.errors import ManagedDocBaselineError

    snap = _make_valid_snapshot(pending_apply=True)
    h = _md_harness_with_snapshot(make_harness, snap)
    with pytest.raises(ManagedDocBaselineError) as exc_info:
        await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))
    assert exc_info.value.reason == "pending_apply"
    # 校验失败 → 不生成 before.md，但 observed.md + diagnostics 已落盘
    art_dir = _managed_doc_run_dir(tmp_path)
    assert not (art_dir / "managed_doc_before.md").exists()
    assert (art_dir / "managed_doc_observed.md").exists()
    assert (art_dir / "managed_doc_diagnostics.json").exists()
    # baseline 校验失败前不构建 operator、不训练
    h.bmo.assert_not_called()
    h.trainer.train.assert_not_called()


async def test_runner_job_start_rejects_file_only_apply_mode(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """apply_mode != restart → baseline 不变量违例（不能在 file-only 模式做 restart 优化）。"""
    from evo_agent.errors import ManagedDocBaselineError

    snap = _make_valid_snapshot(apply_mode="file")
    h = _md_harness_with_snapshot(make_harness, snap)
    with pytest.raises(ManagedDocBaselineError) as exc_info:
        await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))
    assert exc_info.value.reason == "apply_mode"


async def test_runner_job_start_rejects_nan_max_task_seconds(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """P2#9：max_task_seconds=NaN 使 `<` 恒 False 绕过门（IEEE 754），math.isnan 守卫拒绝。"""
    from evo_agent.errors import ManagedDocBaselineError

    snap = _make_valid_snapshot(max_task_seconds=float("nan"))
    h = _md_harness_with_snapshot(make_harness, snap)
    with pytest.raises(ManagedDocBaselineError) as exc_info:
        await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))
    assert exc_info.value.reason == "deadline"


async def test_runner_job_start_rejects_deadline_below_max_task_seconds_plus_margin(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """deadline < max_task_seconds + 10s → 部署 deadline 不足，拒绝 baseline。"""
    from evo_agent.errors import ManagedDocBaselineError

    # config 默认 deadline=600s；max_task_seconds=601 → 600 < 611
    snap = _make_valid_snapshot(max_task_seconds=601.0)
    h = _md_harness_with_snapshot(make_harness, snap)
    with pytest.raises(ManagedDocBaselineError) as exc_info:
        await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))
    assert exc_info.value.reason == "deadline"


async def test_runner_second_run_does_not_rollback_prior_published_version(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """第二次优化：用当前已发布内容做 baseline，不调 restore，applier hash 命中 baseline no-op。"""
    snap = _make_valid_snapshot(
        content="# published v2",
        file_revision="rev-published",
        applied_revision="rev-published",
    )
    h = _md_harness_with_snapshot(make_harness, snap)
    await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))

    # 不调全局 restore（不变量 4）
    h.adapter.restore_skill.assert_not_called()
    # factory 用 snapshot 内容作 initial_content、applied_revision 作 last_success_hash
    # （baseline 内容首次 set_parameter 时 hash 命中 no-op，不重复 POST/restore）
    assert h.bmo.call_args.kwargs["initial_content"] == snap.content
    assert h.bmo.call_args.kwargs["last_success_hash"] == snap.applied_revision


async def test_runner_passes_canonical_id_to_evaluator_via_skill_names(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """评估器经 skill_names 收到 canonical id，单 operator short-circuit 精确归因。"""
    snap = _make_valid_snapshot()
    h = _md_harness_with_snapshot(make_harness, snap)
    await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))
    # trainer 持有 skill_names，rollout 时注入 case.inputs.skill_names 供评估器归因
    assert h.trainer_cls.call_args.kwargs["skill_names"] == ["managed_doc:agent_rule"]
    # RemoteAgent operators 唯一 key 即 canonical id（单 operator short-circuit 命中）
    operators = h.ra_cls.call_args.kwargs["operators"]
    assert list(operators.keys()) == ["managed_doc:agent_rule"]


# ── managed-doc runner 失败路径 artifact（spec F8）──


def _md_record(*, task_id: str | None = None, phase: str = "failed_poll") -> Any:
    """构造一条 ManagedDocApplyRecord（finally tasks.json ledger 数据源）。"""
    from evo_agent.adapter_client.applier import ManagedDocApplyRecord

    return ManagedDocApplyRecord(
        phase=phase,
        content_hash="abc123",
        task_id=task_id,
        status="FAILED",
        noop=False,
        recovered=False,
        error="failed_poll: task FAILED",
        adapter_error="adapter task failed",
        post_time=0.1,
        poll_time=1.5,
        total_time=1.6,
    )


async def test_runner_failure_after_valid_baseline_writes_before_and_task_ledger(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """有效 baseline 建立后 apply 失败：before.md + tasks.json 落盘（finally）。"""
    from evo_agent.errors import ManagedDocApplyError

    snap = _make_valid_snapshot()
    h = _md_harness_with_snapshot(make_harness, snap)
    # applier 已有一条失败 record（含 task_id）→ finally tasks.json 应含该 task_id
    h.md_op.applier.records = (_md_record(task_id="task-failed"),)
    # 训练阶段 apply 失败 → fatal 抛出
    h.trainer_cls.return_value.train.side_effect = ManagedDocApplyError(
        agent_name="test_agent",
        doc_kind="agent_rule",
        task_id="task-failed",
        phase="failed_poll",
        adapter_error="adapter task failed",
    )
    with pytest.raises(ManagedDocApplyError):
        await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))
    art_dir = _managed_doc_run_dir(tmp_path)
    # 有效 baseline 已固化为 before.md
    assert (art_dir / "managed_doc_before.md").read_text(encoding="utf-8") == snap.content
    # finally 刷新 tasks.json，含失败 task_id
    import json as _json

    ledger = _json.loads((art_dir / "managed_doc_tasks.json").read_text(encoding="utf-8"))
    assert ledger["task_ids"] == ["task-failed"]
    assert any(r["task_id"] == "task-failed" for r in ledger["doc_kind_records"])


async def test_runner_pending_baseline_writes_observed_diagnostics_not_before(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
) -> None:
    """baseline 校验失败（pending_apply）：写 observed + diagnostics，不生成误导性 before。"""
    from evo_agent.errors import ManagedDocBaselineError

    snap = _make_valid_snapshot(pending_apply=True)
    h = _md_harness_with_snapshot(make_harness, snap)
    with pytest.raises(ManagedDocBaselineError):
        await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))
    art_dir = _managed_doc_run_dir(tmp_path)
    assert (art_dir / "managed_doc_observed.md").exists()
    diag_path = art_dir / "managed_doc_diagnostics.json"
    assert diag_path.exists()
    import json as _json

    diag = _json.loads(diag_path.read_text(encoding="utf-8"))
    assert diag["pending_apply"] is True
    assert diag["doc_kind"] == "agent_rule"
    assert "content_hash" in diag and "content_length" in diag
    # 不生成误导性的 before
    assert not (art_dir / "managed_doc_before.md").exists()


async def test_runner_diagnostic_failure_does_not_mask_fatal_apply_error(
    tmp_path: Path,
    make_harness: Callable[[], SimpleNamespace],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """finally 中 tasks.json 写盘失败（OSError）只追加 suppressed diagnostic，不覆盖原始 fatal。"""
    import evo_agent.optimizer_runner as runner_mod
    from evo_agent.errors import ManagedDocApplyError

    snap = _make_valid_snapshot()
    h = _md_harness_with_snapshot(make_harness, snap)
    h.md_op.applier.records = (_md_record(task_id="task-failed"),)
    h.trainer_cls.return_value.train.side_effect = ManagedDocApplyError(
        agent_name="test_agent",
        doc_kind="agent_rule",
        task_id="task-failed",
        phase="failed_poll",
        adapter_error="adapter task failed",
    )
    orig_write = runner_mod._write_atomic_text

    def failing_write(path: Path, content: str) -> None:
        # tasks.json 写盘失败（模拟磁盘故障）；其他 artifact 正常写
        if path.name == "managed_doc_tasks.json":
            raise OSError("disk full")
        return orig_write(path, content)

    monkeypatch.setattr(runner_mod, "_write_atomic_text", failing_write)
    # 原始 ManagedDocApplyError 必须穿透（不被 OSError 覆盖）
    with pytest.raises(ManagedDocApplyError):
        await h.run(_make_request(managed_doc_kind="agent_rule"), _make_config(tmp_path))
