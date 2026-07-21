"""dataset manifest 解析单元测试。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from openjiuwen.agent_evolving.dataset import Case

from evo_agent.dataset.manifest import DatasetSpec, load_dataset_manifest


@pytest.fixture
def dataset_with_cases(tmp_path: Path) -> Path:
    """创建一个完整的 dataset 目录（dataset.yaml + items.json）。"""
    items = [
        {
            "inputs": {"question": f"Q{i}"},
            "label": {"answer": f"A{i}"},
            "case_id": f"case-{i:03d}",
        }
        for i in range(10)
    ]
    (tmp_path / "items.json").write_text(json.dumps(items), encoding="utf-8")

    yaml_content = """\
schema_version: "1.0"
name: test_dataset
cases: items.json
train_split: 0.8
seed: 42

evaluator:
  dotted_path: unittest.mock.MagicMock
  kwargs: {}
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")
    return yaml_path


def test_load_valid_manifest(dataset_with_cases: Path) -> None:
    """验证 YAML 解析 + CaseLoader 划分 + evaluator 实例化。"""
    spec = load_dataset_manifest(dataset_with_cases)

    assert isinstance(spec, DatasetSpec)
    assert spec.name == "test_dataset"
    assert spec.cases_path.name == "items.json"

    # 10 cases, 80/20 split
    assert len(spec.train_cases) == 8
    assert len(spec.val_cases) == 2

    # evaluator 已实例化
    assert spec.evaluator is not None


def test_missing_required_fields(tmp_path: Path) -> None:
    """验证缺失字段时报错。"""
    # 缺少 name
    yaml_path = tmp_path / "bad.yaml"
    yaml_path.write_text(
        "cases: items.json\nevaluator:\n  dotted_path: mock.Mock\n", encoding="utf-8"
    )
    with pytest.raises(ValueError, match="name"):
        load_dataset_manifest(yaml_path)

    # 缺少 cases
    yaml_path.write_text("name: test\nevaluator:\n  dotted_path: mock.Mock\n", encoding="utf-8")
    with pytest.raises(ValueError, match="cases"):
        load_dataset_manifest(yaml_path)

    # 缺少 evaluator
    yaml_path.write_text("name: test\ncases: items.json\n", encoding="utf-8")
    with pytest.raises(ValueError, match="evaluator"):
        load_dataset_manifest(yaml_path)


def test_file_not_found(tmp_path: Path) -> None:
    """验证 dataset.yaml 不存在时抛出 FileNotFoundError。"""
    with pytest.raises(FileNotFoundError):
        load_dataset_manifest(tmp_path / "nonexistent.yaml")


def test_train_split_is_reproducible(dataset_with_cases: Path) -> None:
    """验证相同 seed 产生相同的 train/val 划分。"""
    spec1 = load_dataset_manifest(dataset_with_cases)
    spec2 = load_dataset_manifest(dataset_with_cases)

    train1_ids = [c.case_id for c in spec1.train_cases]
    train2_ids = [c.case_id for c in spec2.train_cases]
    assert train1_ids == train2_ids


def test_evaluator_dotted_path_loading(dataset_with_cases: Path) -> None:
    """验证 Evaluator 通过 dotted path 正确实例化。"""
    spec = load_dataset_manifest(dataset_with_cases)
    # unittest.mock.MagicMock 接受任意 kwargs
    assert spec.evaluator is not None


# ── W7.1: type 分发 + eval_runtime 注入 ──


def _write_items(tmp_path: Path) -> None:
    """Helper: write a minimal items.json."""
    items = [{"inputs": {"q": "Q"}, "label": {"a": "A"}, "case_id": "c-001"}]
    (tmp_path / "items.json").write_text(json.dumps(items), encoding="utf-8")


def test_build_evaluator_type_llm_with_runtime(tmp_path: Path) -> None:
    """type: llm 从 eval_runtime 注入 model_config 和 model_client_config。"""
    from openjiuwen.core.foundation.llm.schema.config import ModelClientConfig, ModelRequestConfig

    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: llm
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    model_config = ModelRequestConfig(model_name="test-model")
    model_client_config = ModelClientConfig(
        client_provider="OpenAI", api_key="test-key", api_base="http://test"
    )
    runtime = {
        "model_config": model_config,
        "model_client_config": model_client_config,
    }

    with patch("evo_agent.dataset.manifest.LLMEvaluator") as mock_cls:
        mock_cls.return_value = MagicMock()
        load_dataset_manifest(yaml_path, eval_runtime=runtime)
        mock_cls.assert_called_once()
        call_kwargs = mock_cls.call_args.kwargs
        assert call_kwargs["model_config"] is model_config
        assert call_kwargs["model_client_config"] is model_client_config


def test_build_evaluator_type_llm_missing_runtime_raises(tmp_path: Path) -> None:
    """type: llm 缺少 eval_runtime 时抛出 ValueError。"""
    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: llm
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    with pytest.raises(ValueError, match="model_config"):
        load_dataset_manifest(yaml_path)


def test_build_evaluator_type_metric(tmp_path: Path) -> None:
    """type: metric 不需要 runtime 注入。"""
    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: metric
  metric: exact_match
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    spec = load_dataset_manifest(yaml_path)
    assert spec.evaluator is not None


def test_build_evaluator_type_metric_with_extract(tmp_path: Path) -> None:
    """extract 配置接通后，从 <answer> JSON 字段 exact_match。"""
    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: metric
  metric: exact_match
  extract:
    strategy: answer_tag_json_field
    source: answer
    field: responsibility
    prefer_values:
      - "无责"
      - "有责"
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    spec = load_dataset_manifest(yaml_path)
    case = Case(
        case_id="x",
        inputs={"query": "q"},
        label={"expected_result": "有责"},
    )
    evaluated = spec.evaluator.evaluate(
        case,
        {"answer": '<answer>{"responsibility": "有责"}</answer>'},
    )
    assert evaluated.score == 1.0


def test_build_evaluator_type_custom(tmp_path: Path) -> None:
    """type: custom 通过 dotted_path 加载。"""
    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: custom
  dotted_path: unittest.mock.MagicMock
  kwargs: {}
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    spec = load_dataset_manifest(yaml_path)
    assert spec.evaluator is not None


def test_build_evaluator_no_type_backward_compat(tmp_path: Path) -> None:
    """无 type 字段时向后兼容旧 dotted_path 格式。"""
    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  dotted_path: unittest.mock.MagicMock
  kwargs: {}
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    spec = load_dataset_manifest(yaml_path)
    assert spec.evaluator is not None


def test_build_evaluator_unknown_type_raises(tmp_path: Path) -> None:
    """未知 type 抛出 ValueError。"""
    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: unknown_type
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    with pytest.raises(ValueError, match="Unknown evaluator type"):
        load_dataset_manifest(yaml_path)


def test_build_evaluator_type_llm_prompt_priority(tmp_path: Path) -> None:
    """prompt_template 优先级: runtime > dataset.yaml > None。"""
    from openjiuwen.core.foundation.llm.schema.config import ModelClientConfig, ModelRequestConfig

    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: llm
  prompt_template: "yaml prompt"
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    model_config = ModelRequestConfig(model_name="test-model")
    model_client_config = ModelClientConfig(
        client_provider="OpenAI", api_key="test-key", api_base="http://test"
    )

    # 场景 1: runtime 有 prompt_template → 使用 runtime 的
    runtime = {
        "model_config": model_config,
        "model_client_config": model_client_config,
        "prompt_template": "runtime prompt",
    }
    with patch("evo_agent.dataset.manifest.LLMEvaluator") as mock_cls:
        mock_cls.return_value = MagicMock()
        load_dataset_manifest(yaml_path, eval_runtime=runtime)
        assert mock_cls.call_args.kwargs["prompt_template"] == "runtime prompt"

    # 场景 2: runtime 无 prompt_template → 使用 yaml 的
    runtime_no_prompt: dict[str, Any] = {
        "model_config": model_config,
        "model_client_config": model_client_config,
    }
    with patch("evo_agent.dataset.manifest.LLMEvaluator") as mock_cls:
        mock_cls.return_value = MagicMock()
        load_dataset_manifest(yaml_path, eval_runtime=runtime_no_prompt)
        assert mock_cls.call_args.kwargs["prompt_template"] == "yaml prompt"


def test_build_evaluator_type_custom_missing_dotted_path(tmp_path: Path) -> None:
    """type: custom 缺少 dotted_path 时抛出 ValueError。"""
    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: custom
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    with pytest.raises(ValueError, match="dotted_path"):
        load_dataset_manifest(yaml_path)


def test_build_evaluator_type_custom_receives_runtime(tmp_path: Path) -> None:
    """type: custom 接收 eval_runtime 中的 model_config 等运行时参数。"""
    from unittest.mock import MagicMock

    from openjiuwen.core.foundation.llm.schema.config import ModelClientConfig, ModelRequestConfig

    _write_items(tmp_path)

    yaml_content = """\
schema_version: "1.0"
name: test
cases: items.json
evaluator:
  type: custom
  dotted_path: unittest.mock.MagicMock
  kwargs:
    custom_param: "from_config"
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")

    model_config = ModelRequestConfig(model_name="test-model")
    model_client_config = ModelClientConfig(
        client_provider="OpenAI", api_key="test-key", api_base="http://test"
    )
    runtime: dict[str, Any] = {
        "model_config": model_config,
        "model_client_config": model_client_config,
    }

    with patch("evo_agent.dataset.manifest.importlib.import_module") as mock_import:
        mock_cls = MagicMock()
        mock_module = MagicMock()
        mock_module.MagicMock = mock_cls
        mock_import.return_value = mock_module

        load_dataset_manifest(yaml_path, eval_runtime=runtime)

        # Custom evaluator should receive runtime kwargs merged with config kwargs
        call_kwargs = mock_cls.call_args.kwargs
        assert call_kwargs["custom_param"] == "from_config"
        assert call_kwargs["model_config"] is model_config
        assert call_kwargs["model_client_config"] is model_client_config
