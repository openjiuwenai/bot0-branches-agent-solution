"""build_dataset_from_request() 单元测试。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from evo_agent.dataset.manifest import build_dataset_from_request


def _make_cases_json(n: int = 10) -> list[dict[str, Any]]:
    """生成 n 个 agent-core 格式的 case。"""
    return [
        {
            "case_id": f"case-{i:03d}",
            "inputs": {"question": f"Q{i}"},
            "label": {"answer": f"A{i}"},
        }
        for i in range(n)
    ]


@pytest.fixture
def cases_file(tmp_path: Path) -> Path:
    """创建包含 10 个 case 的 JSON 文件。"""
    p = tmp_path / "items.json"
    p.write_text(json.dumps(_make_cases_json(10)), encoding="utf-8")
    return p


def _mock_eval_runtime() -> dict[str, Any]:
    """构造 mock eval_runtime。"""
    return {
        "model_config": MagicMock(),
        "model_client_config": MagicMock(),
    }


def test_build_dataset_basic(cases_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """正确加载 JSON 文件并切分。"""
    runtime = _mock_eval_runtime()
    with patch("evo_agent.dataset.manifest._build_evaluator") as mock_build:
        mock_build.return_value = MagicMock()
        spec = build_dataset_from_request(
            data_path=cases_file,
            evaluator_prompt="test prompt",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=runtime,
        )
    assert spec.name == "items"  # 文件名 stem
    assert spec.cases_path == cases_file
    assert len(spec.train_cases.get_cases()) == 8
    assert len(spec.val_cases.get_cases()) == 2
    mock_build.assert_called_once()


def test_build_dataset_empty_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """空 JSON array → ValueError。"""
    p = tmp_path / "empty.json"
    p.write_text("[]", encoding="utf-8")
    with pytest.raises(ValueError, match="empty"):
        build_dataset_from_request(
            data_path=p,
            evaluator_prompt="",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=_mock_eval_runtime(),
        )


def test_build_dataset_too_few_cases(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """切分后 train 或 val 为空 → ValueError。"""
    p = tmp_path / "small.json"
    # 只有 1 个 case，0.8/0.2 切分后 val 可能为空
    p.write_text(json.dumps(_make_cases_json(1)), encoding="utf-8")
    with pytest.raises(ValueError, match="(empty|Increase)"):
        build_dataset_from_request(
            data_path=p,
            evaluator_prompt="",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=_mock_eval_runtime(),
        )


def test_build_dataset_evaluator_prompt(cases_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """显式 llm + evaluator_prompt 传递给 _build_evaluator。"""
    runtime = _mock_eval_runtime()
    with patch("evo_agent.dataset.manifest._build_evaluator") as mock_build:
        mock_build.return_value = MagicMock()
        build_dataset_from_request(
            data_path=cases_file,
            evaluator_prompt="custom prompt",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=runtime,
            evaluator_config={"type": "llm"},
        )
    call_args = mock_build.call_args
    evaluator_config = call_args.args[0]
    assert evaluator_config["type"] == "llm"
    assert evaluator_config["prompt_template"] == "custom prompt"


def test_build_dataset_no_prompt(cases_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """默认 metric：不传 evaluator_config 时 type=metric。"""
    runtime = _mock_eval_runtime()
    with patch("evo_agent.dataset.manifest._build_evaluator") as mock_build:
        mock_build.return_value = MagicMock()
        build_dataset_from_request(
            data_path=cases_file,
            evaluator_prompt="",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=runtime,
        )
    call_args = mock_build.call_args
    evaluator_config = call_args.args[0]
    assert evaluator_config["type"] == "metric"
    assert "prompt_template" not in evaluator_config


def test_build_dataset_llm_empty_prompt_omits_template(
    cases_file: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """type=llm 且 prompt 为空时不传 prompt_template。"""
    runtime = _mock_eval_runtime()
    with patch("evo_agent.dataset.manifest._build_evaluator") as mock_build:
        mock_build.return_value = MagicMock()
        build_dataset_from_request(
            data_path=cases_file,
            evaluator_prompt="",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=runtime,
            evaluator_config={"type": "llm"},
        )
    evaluator_config = mock_build.call_args.args[0]
    assert evaluator_config["type"] == "llm"
    assert "prompt_template" not in evaluator_config


def test_build_dataset_metric_with_extract(
    cases_file: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """API metric + extract 配置完整传给 _build_evaluator。"""
    runtime = _mock_eval_runtime()
    extract = {
        "strategy": "answer_tag_json_field",
        "source": "answer",
        "fields": ["responsibility"],
        "prefer_values": ["无责", "有责"],
    }
    with patch("evo_agent.dataset.manifest._build_evaluator") as mock_build:
        mock_build.return_value = MagicMock()
        build_dataset_from_request(
            data_path=cases_file,
            evaluator_prompt="",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=runtime,
            evaluator_config={
                "type": "metric",
                "metric": "exact_match",
                "aggregate": "mean",
                "extract": extract,
            },
        )
    evaluator_config = mock_build.call_args.args[0]
    assert evaluator_config["type"] == "metric"
    assert evaluator_config["metric"] == "exact_match"
    assert evaluator_config["extract"] == extract


def test_build_dataset_rejects_unsupported_type(
    cases_file: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    with pytest.raises(ValueError, match="Unsupported evaluator type"):
        build_dataset_from_request(
            data_path=cases_file,
            evaluator_prompt="",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=_mock_eval_runtime(),
            evaluator_config={"type": "filtered"},
        )


def test_build_dataset_evo_format(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """EvoCase 格式自动检测并转换。"""
    evo_cases = [
        {
            "id": f"evo-{i}",
            "inputs": [{"role": "user", "content": f"Q{i}"}],
            "expected_output": f"A{i}",
        }
        for i in range(10)
    ]
    p = tmp_path / "evo_items.json"
    p.write_text(json.dumps(evo_cases), encoding="utf-8")
    runtime = _mock_eval_runtime()
    with patch("evo_agent.dataset.manifest._build_evaluator") as mock_build:
        mock_build.return_value = MagicMock()
        spec = build_dataset_from_request(
            data_path=p,
            evaluator_prompt="",
            train_split=0.8,
            val_split=0.2,
            eval_runtime=runtime,
        )
    assert spec.name == "evo_items"
    assert len(spec.train_cases.get_cases()) + len(spec.val_cases.get_cases()) == 10
