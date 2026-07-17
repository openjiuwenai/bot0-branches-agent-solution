"""A2 (#11): 移除生产路径 DEBUG print — 单元测试。"""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from openjiuwen.agent_evolving.dataset import Case

from evo_agent.evaluator.evaluators.llm import LLMEvaluator

_REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
_SCAN_DIRS = ("src", "scenarios")
_PRINT_DEBUG_RE = re.compile(r"print\s*\([^)]*DEBUG", re.IGNORECASE)


def test_no_print_debug_in_production_paths() -> None:
    """A2 验收：src/ 与 scenarios/ 下不存在 print(...DEBUG...) 生产残留。"""
    offenders: list[str] = []
    for sub in _SCAN_DIRS:
        root = _REPOSITORY_ROOT / sub
        if not root.is_dir():
            continue
        for path in root.rglob("*.py"):
            for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if _PRINT_DEBUG_RE.search(line):
                    offenders.append(
                        f"{path.relative_to(_REPOSITORY_ROOT)}:{lineno}: {line.strip()}"
                    )
    assert not offenders, "生产路径残留 print(...DEBUG...):\n" + "\n".join(offenders)


_MODEL_PATCH = "evo_agent.evaluator.evaluators.llm.Model"


def _make_evaluator() -> LLMEvaluator:
    with patch(_MODEL_PATCH) as mock_model_cls:
        mock_model_cls.return_value = MagicMock()
        return LLMEvaluator(MagicMock(), MagicMock())


def _make_case() -> Case:
    inputs: dict[str, Any] = {
        "trajectory": {"messages": [{"role": "assistant", "content": "answer"}]},
        "skill_names": ["product_recommend_skill"],
    }
    return Case(inputs=inputs, label={"expected_result": None})


def _run_evaluate(evaluator: LLMEvaluator, llm_response: str) -> None:
    mock_response = type("Response", (), {"content": llm_response})()
    with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        evaluator.evaluate(_make_case(), {"answer": "42"})


# ── llm.py DEBUG print / /tmp 文件清理 ──


class TestEvalPromptDebugGated:
    """eval prompt debug 输出由 EVO_DEBUG_EVAL_PROMPT 控制，默认静默。"""

    def test_default_no_debug_print_no_tmpfile(
        self,
        capsys: pytest.CaptureFixture[str],
        caplog: pytest.LogCaptureFixture,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """默认（无 env）不写 stdout、不写 /tmp、不记 debug 日志。"""
        monkeypatch.delenv("EVO_DEBUG_EVAL_PROMPT", raising=False)
        evaluator = _make_evaluator()
        # 清除上一次 evaluate 可能设置的标志
        evaluator._debug_prompt_logged = False  # type: ignore[attr-defined]
        with patch("builtins.open", autospec=True) as mock_open:
            _run_evaluate(evaluator, '{"score": 0.8, "is_pass": true}')
        out = capsys.readouterr()
        assert "[DEBUG]" not in out.out
        # 不应打开 /tmp/eval_prompt_sample.txt
        open_paths = [
            call.args[0] if call.args else call.kwargs.get("file")
            for call in mock_open.call_args_list
        ]
        assert not any("eval_prompt_sample" in str(p) for p in open_paths)
        assert not any(
            rec.levelno == logging.DEBUG and "eval prompt" in rec.getMessage()
            for rec in caplog.records
        )

    def test_env_enabled_emits_debug_log(
        self,
        caplog: pytest.LogCaptureFixture,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """EVO_DEBUG_EVAL_PROMPT=1 时通过 logger.debug 输出占位符状态。"""
        monkeypatch.setenv("EVO_DEBUG_EVAL_PROMPT", "1")
        caplog.set_level(logging.DEBUG, logger="evo_agent.evaluator.evaluators.llm")
        evaluator = _make_evaluator()
        evaluator._debug_prompt_logged = False  # type: ignore[attr-defined]
        _run_evaluate(evaluator, '{"score": 0.8, "is_pass": true}')
        debug_msgs = [rec.getMessage() for rec in caplog.records if rec.levelno == logging.DEBUG]
        assert any("eval prompt" in m for m in debug_msgs)
        # 仅记录一次
        assert sum("eval prompt" in m for m in debug_msgs) == 1

    def test_env_enabled_no_stdout_print(
        self,
        capsys: pytest.CaptureFixture[str],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """即使开启 debug，也走 logger 而非 print，stdout 无 [DEBUG]。"""
        monkeypatch.setenv("EVO_DEBUG_EVAL_PROMPT", "1")
        evaluator = _make_evaluator()
        evaluator._debug_prompt_logged = False  # type: ignore[attr-defined]
        _run_evaluate(evaluator, '{"score": 0.8, "is_pass": true}')
        out = capsys.readouterr()
        assert "[DEBUG]" not in out.out


# ── edp_agent _push_phase print → logger.debug ──


class _ListHandler(logging.Handler):
    """捕获日志记录到列表，绕开 openjiuwen 对 root logger 的重配置。"""

    def __init__(self) -> None:
        super().__init__(level=logging.DEBUG)
        self.records: list[logging.LogRecord] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.records.append(record)


@pytest.fixture
def attach_edp_handler() -> Any:
    """返回一个 attach(optimizer) 函数：把 _ListHandler 挂到 optimizer 所在 logger。

    registry 通过 spec_from_file_location 加载场景模块，logger 名为
    ``_evo_agent_scenario_<name>_<module>``，因此从实例的 ``__module__`` 取 logger。
    """
    attached: list[tuple[logging.Logger, _ListHandler]] = []

    def _attach(optimizer: Any) -> _ListHandler:
        lg = logging.getLogger(type(optimizer).__module__)
        handler = _ListHandler()
        lg.addHandler(handler)
        prev_level = lg.level
        lg.setLevel(logging.DEBUG)
        attached.append((lg, handler, prev_level))  # type: ignore[arg-type]
        return handler

    yield _attach
    for lg, handler, prev_level in attached:  # type: ignore[misc]
        lg.removeHandler(handler)
        lg.setLevel(prev_level)


def _build_edp_optimizer() -> Any:
    from evo_agent.scenario.registry import ScenarioRegistry
    from evo_agent.types import OptimizeRequest

    registry = ScenarioRegistry()
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
    )
    return registry.build_optimizer(
        request,
        dependencies={
            "agent": MagicMock(),
            "evaluator": MagicMock(),
            "llm": MagicMock(),
            "model": "test",
            "train_cases": MagicMock(),
        },
    )


class TestPushPhaseNoPrint:
    """_push_phase 使用 logger.debug 而非 print。"""

    def test_no_callback_uses_logger_not_print(
        self,
        capsys: pytest.CaptureFixture[str],
        attach_edp_handler: Any,
    ) -> None:
        optimizer = _build_edp_optimizer()
        handler = attach_edp_handler(optimizer)
        optimizer._push_phase("log", {"message": "hi"})
        out = capsys.readouterr()
        assert "[DEBUG]" not in out.out
        assert any("skipping" in rec.getMessage() for rec in handler.records)

    def test_with_callback_uses_logger_not_print(
        self,
        capsys: pytest.CaptureFixture[str],
        attach_edp_handler: Any,
    ) -> None:
        from evo_agent.scenario.registry import ScenarioRegistry
        from evo_agent.types import OptimizeRequest

        events: list[tuple[str, dict]] = []
        registry = ScenarioRegistry()
        request = OptimizeRequest(
            scenario="edp_agent",
            agent_name="test_agent",
            dataset_manifest_path=Path("/tmp/dataset.yaml"),
            adapter_url="http://localhost:9090",
        )
        optimizer = registry.build_optimizer(
            request,
            dependencies={
                "agent": MagicMock(),
                "evaluator": MagicMock(),
                "llm": MagicMock(),
                "model": "test",
                "train_cases": MagicMock(),
                "phase_callback": lambda e, d: events.append((e, d)),
            },
        )
        handler = attach_edp_handler(optimizer)
        optimizer._push_phase("log", {"message": "hi"})
        out = capsys.readouterr()
        assert "[DEBUG]" not in out.out
        assert events == [("log", {"message": "hi"})]
        assert any("pushing phase" in rec.getMessage() for rec in handler.records)
