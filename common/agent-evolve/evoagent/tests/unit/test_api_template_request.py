"""外部 API 模板请求模型单元测试。"""

from __future__ import annotations

import os
from pathlib import Path
from unittest.mock import patch

import pytest
from pydantic import ValidationError

from evo_agent.api.routes.optimize import (
    EvaluatorTemplateRequest,
    OptimizeAPIRequest,
    OptimizerTemplateRequest,
)


def _make_valid_request(
    *,
    dataset_path: str = "/data/evo_agent/items.json",
    train_split: float = 0.8,
    val_split: float = 0.2,
) -> dict:
    """构造合法的请求 dict。"""
    return {
        "task_name": "test-task",
        "agent_name": "test_agent",
        "optimizer_type": "skill",
        "dataset_path": dataset_path,
        "skills": ["skill_a"],
        "optimizer_template": {
            "name": "edp_agent",
            "scenario": "金融客服",
            "hyperparams": {"num_epochs": 5},
            "train_split": train_split,
            "val_split": val_split,
        },
        "evaluator_template": {
            "name": "default_eval",
            "scenario": "金融客服",
            "prompt": "评估回答质量",
        },
    }


# ── OptimizerTemplateRequest ──


def test_optimizer_template_request_defaults() -> None:
    """默认 train_split=0.8, val_split=0.2, hyperparams={}。"""
    tpl = OptimizerTemplateRequest(name="test", scenario="场景")
    assert tpl.train_split == 0.8
    assert tpl.val_split == 0.2
    assert tpl.hyperparams == {}


# ── EvaluatorTemplateRequest ──


def test_evaluator_template_request_defaults() -> None:
    """默认 type=metric、prompt=''、metric=exact_match。"""
    tpl = EvaluatorTemplateRequest(name="test", scenario="场景")
    assert tpl.prompt == ""
    assert tpl.type == "metric"
    assert tpl.metric == "exact_match"
    assert tpl.extract is None


def test_evaluator_template_metric_with_extract() -> None:
    tpl = EvaluatorTemplateRequest(
        name="exact",
        scenario="audit",
        type="metric",
        metric="exact_match",
        extract={
            "strategy": "answer_tag_json_field",
            "fields": ["responsibility"],
        },
    )
    assert tpl.type == "metric"
    assert tpl.extract is not None


def test_evaluator_template_rejects_unknown_type() -> None:
    with pytest.raises(ValidationError):
        EvaluatorTemplateRequest(name="t", scenario="s", type="filtered")  # type: ignore[arg-type]


def test_evaluator_template_rejects_batch_metrics_without_score() -> None:
    with pytest.raises(ValidationError, match="batch_metrics and batch_score"):
        EvaluatorTemplateRequest(
            name="t",
            scenario="s",
            type="metric",
            batch_metrics=["set_overlap"],
        )


def test_evaluator_template_rejects_extract_on_llm() -> None:
    with pytest.raises(ValidationError, match="extract is only valid"):
        EvaluatorTemplateRequest(
            name="t",
            scenario="s",
            type="llm",
            extract={"strategy": "answer_tag_json_field", "fields": ["x"]},
        )


def test_evaluator_template_rejects_extract_with_contains() -> None:
    with pytest.raises(ValidationError, match="exact_match"):
        EvaluatorTemplateRequest(
            name="t",
            scenario="s",
            type="metric",
            metric="contains",
            extract={"strategy": "answer_tag_json_field", "fields": ["x"]},
        )


# ── OptimizeAPIRequest — split validation ──


def test_api_request_valid(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """合法请求体通过校验。"""
    # 创建临时数据文件
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")

    data = _make_valid_request(dataset_path=str(data_file))
    req = OptimizeAPIRequest.model_validate(data)
    assert req.task_name == "test-task"


@pytest.mark.parametrize("optimizer_type", ["unknown", "managed_doc", "PROMPT"])
def test_api_request_rejects_unknown_optimizer_type(tmp_path: Path, optimizer_type: str) -> None:
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")
    data = _make_valid_request(dataset_path=str(data_file))
    data["optimizer_type"] = optimizer_type

    with pytest.raises(ValidationError):
        OptimizeAPIRequest.model_validate(data)


def test_api_request_rejects_skill_with_managed_doc(tmp_path: Path) -> None:
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")
    data = _make_valid_request(dataset_path=str(data_file))
    data.update(skills=[], managed_doc_kind="agent_rule")

    with pytest.raises(ValidationError, match="skill optimization requires skills"):
        OptimizeAPIRequest.model_validate(data)


def test_api_request_rejects_prompt_with_skills(tmp_path: Path) -> None:
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")
    data = _make_valid_request(dataset_path=str(data_file))
    data.update(
        optimizer_type="prompt",
        skills=["skill_a"],
        managed_doc_kind=None,
        client_task_id="studio-task-1",
        managed_doc_expected_revision="rev-1",
    )

    with pytest.raises(ValidationError, match="prompt optimization requires managed_doc_kind"):
        OptimizeAPIRequest.model_validate(data)


def test_tool_request_ignores_prompt_control_fields(tmp_path: Path) -> None:
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")
    data = _make_valid_request(dataset_path=str(data_file))
    data.update(
        optimizer_type="tool",
        managed_doc_kind="agent_rule",
        client_task_id="studio-task-1",
        managed_doc_expected_revision="rev-1",
    )

    request = OptimizeAPIRequest.model_validate(data)

    assert request.skills == ["skill_a"]
    assert request.managed_doc_kind is None
    assert request.client_task_id is None
    assert request.managed_doc_expected_revision is None


def test_api_request_splits_not_sum_to_one(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """train_split + val_split != 1.0 → ValidationError。"""
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")
    data = _make_valid_request(dataset_path=str(data_file), train_split=0.7, val_split=0.2)
    with pytest.raises(ValidationError):
        OptimizeAPIRequest.model_validate(data)


def test_api_request_split_zero(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """train_split=0 → ValidationError。"""
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")
    data = _make_valid_request(dataset_path=str(data_file), train_split=0.0, val_split=1.0)
    with pytest.raises(ValidationError):
        OptimizeAPIRequest.model_validate(data)


# ── dataset_path validation ──


def test_api_request_dataset_not_found(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """不存在的文件路径 → ValidationError。"""
    data = _make_valid_request(dataset_path=str(tmp_path / "nonexistent.json"))
    with pytest.raises(ValidationError):
        OptimizeAPIRequest.model_validate(data)


def test_api_request_dataset_not_a_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """目录路径 → ValidationError。"""
    subdir = tmp_path / "subdir"
    subdir.mkdir()
    data = _make_valid_request(dataset_path=str(subdir))
    with pytest.raises(ValidationError):
        OptimizeAPIRequest.model_validate(data)


def test_api_request_dataset_too_large(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """超过 500MB → ValidationError（mock stat）。"""
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")
    data = _make_valid_request(dataset_path=str(data_file))

    # Mock Path.stat to return a large file size
    original_stat = Path.stat

    def mock_stat(self: Path, **kwargs: object) -> os.stat_result:
        real = original_stat(self)  # 获取真实的 stat 结果以保留 st_mode
        from types import SimpleNamespace

        return SimpleNamespace(  # type: ignore[return-value]
            st_size=600 * 1024 * 1024,
            st_mode=real.st_mode,
        )

    with patch.object(Path, "stat", mock_stat):
        with pytest.raises(ValidationError):
            OptimizeAPIRequest.model_validate(data)
