"""_normalize() 归一化函数单元测试。"""

from __future__ import annotations

import pytest

from evo_agent.api.routes.optimize import (
    EvaluatorTemplateRequest,
    OptimizeAPIRequest,
    OptimizerTemplateRequest,
    _normalize,
)
from evo_agent.config import EvolveConfig


def _make_api_request(
    *,
    hyperparams: dict | None = None,
    train_split: float = 0.8,
    val_split: float = 0.2,
    evaluator_prompt: str = "test prompt",
    task_name: str = "test-task",
    dataset_path: str = "/data/evo_agent/items.json",
) -> OptimizeAPIRequest:
    """构造测试用 OptimizeAPIRequest（跳过 dataset_path 校验）。"""
    return OptimizeAPIRequest.model_construct(
        task_name=task_name,
        agent_name="test_agent",
        optimizer_type="skill",
        skills=["skill_a"],
        dataset_path=dataset_path,
        optimizer_template=OptimizerTemplateRequest(
            name="手机银行优化器",
            scenario="edp_agent",
            hyperparams=hyperparams or {},
            train_split=train_split,
            val_split=val_split,
        ),
        evaluator_template=EvaluatorTemplateRequest(
            name="default_eval",
            scenario="金融客服",
            prompt=evaluator_prompt,
        ),
    )


def _make_config(**overrides: object) -> EvolveConfig:
    defaults: dict[str, object] = {
        "adapter_url": "http://config-adapter:9090",
        "default_epochs": 3,
        "default_batch_size": 4,
    }
    defaults.update(overrides)
    return EvolveConfig(**defaults)  # type: ignore[arg-type]


# ── 字段映射 ──


def test_normalize_basic_mapping() -> None:
    """optimizer_template.scenario → scenario；默认评估 type=metric。"""
    req = _make_api_request()
    config = _make_config()
    result = _normalize(req, config)
    assert result.scenario == "edp_agent"
    assert result.evaluator_prompt == "test prompt"
    assert result.agent_name == "test_agent"
    assert result.skills == ["skill_a"]
    assert result.evaluator_config == {
        "type": "metric",
        "metric": "exact_match",
        "aggregate": "mean",
    }


def test_normalize_metric_evaluator_config() -> None:
    """type=metric 时组装 metric/extract evaluator_config。"""
    req = _make_api_request()
    req.evaluator_template = EvaluatorTemplateRequest(
        name="exact",
        scenario="audit",
        type="metric",
        metric="exact_match",
        extract={
            "strategy": "answer_tag_json_field",
            "fields": ["responsibility", "responsibility_type"],
            "prefer_values": ["无责", "有责"],
        },
    )
    result = _normalize(req, _make_config())
    assert result.evaluator_config["type"] == "metric"
    assert result.evaluator_config["metric"] == "exact_match"
    assert result.evaluator_config["extract"]["fields"] == [
        "responsibility",
        "responsibility_type",
    ]
    assert "prompt_template" not in result.evaluator_config


def test_normalize_llm_evaluator_config() -> None:
    """显式 type=llm 时组装 prompt_template。"""
    req = _make_api_request()
    req.evaluator_template = EvaluatorTemplateRequest(
        name="sem",
        scenario="audit",
        type="llm",
        prompt="custom rubric",
    )
    result = _normalize(req, _make_config())
    assert result.evaluator_config == {
        "type": "llm",
        "prompt_template": "custom rubric",
    }


def test_normalize_llm_empty_prompt_omits_template() -> None:
    req = _make_api_request(evaluator_prompt="")
    req.evaluator_template = EvaluatorTemplateRequest(
        name="sem",
        scenario="audit",
        type="llm",
        prompt="",
    )
    result = _normalize(req, _make_config())
    assert result.evaluator_config == {"type": "llm"}


def test_tool_request_does_not_enter_managed_doc_branch() -> None:
    req = _make_api_request()
    req.optimizer_type = "tool"
    req.managed_doc_kind = "agent_rule"

    result = _normalize(req, _make_config())

    assert result.optimizer_type == "tool"
    assert result.skills == ["skill_a"]
    assert result.managed_doc_kind is None


def test_normalize_task_name() -> None:
    """task_name 直接映射。"""
    req = _make_api_request(task_name="my-task-001")
    result = _normalize(req, _make_config())
    assert result.task_name == "my-task-001"


def test_normalize_splits_passed_through() -> None:
    """train_split / val_split 直接映射。"""
    req = _make_api_request(train_split=0.7, val_split=0.3)
    result = _normalize(req, _make_config())
    assert result.train_split == 0.7
    assert result.val_split == 0.3


def test_normalize_dataset_manifest_none() -> None:
    """API 模式 dataset_manifest_path=None。"""
    req = _make_api_request()
    result = _normalize(req, _make_config())
    assert result.dataset_manifest_path is None


def test_normalize_adapter_url_from_config() -> None:
    """adapter_url 来自 config.adapter_url。"""
    config = _make_config(adapter_url="http://my-adapter:8080")
    result = _normalize(_make_api_request(), config)
    assert result.adapter_url == "http://my-adapter:8080"


# ── hyperparams 提取 ──


def test_normalize_extracts_num_epochs() -> None:
    """hyperparams['num_epochs']=10 → num_epochs=10，且从 dict 中移除。"""
    req = _make_api_request(hyperparams={"num_epochs": 10, "other": "value"})
    result = _normalize(req, _make_config())
    assert result.num_epochs == 10
    assert "num_epochs" not in result.hyperparams
    assert result.hyperparams == {"other": "value"}


def test_normalize_extracts_batch_size() -> None:
    """hyperparams['batch_size']=16 → batch_size=16，且从 dict 中移除。"""
    req = _make_api_request(hyperparams={"batch_size": 16})
    result = _normalize(req, _make_config())
    assert result.batch_size == 16
    assert "batch_size" not in result.hyperparams


def test_normalize_default_epochs() -> None:
    """hyperparams 无 num_epochs → 留给 ConfigResolver 处理。"""
    config = _make_config(default_epochs=7)
    result = _normalize(_make_api_request(), config)
    assert result.num_epochs is None


def test_normalize_default_batch_size() -> None:
    """hyperparams 无 batch_size → 留给 ConfigResolver 处理。"""
    config = _make_config(default_batch_size=16)
    result = _normalize(_make_api_request(), config)
    assert result.batch_size is None


def test_normalize_remaining_hyperparams() -> None:
    """非 typed 超参保留在 hyperparams dict 中。"""
    req = _make_api_request(hyperparams={"learning_rate": 0.01, "temperature": 0.7})
    result = _normalize(req, _make_config())
    assert result.hyperparams == {"learning_rate": 0.01, "temperature": 0.7}


def test_normalize_rollout_extra_data() -> None:
    """optimizer_template.rollout.extra_data → OptimizeRequest.rollout_extra_data。"""
    req = _make_api_request()
    req.optimizer_template.rollout.extra_data = {"role_id": "1", "role_name": "mobile-bank"}

    result = _normalize(req, _make_config())

    assert result.rollout_extra_data == {"role_id": "1", "role_name": "mobile-bank"}


# ── 范围校验 ──


def test_normalize_invalid_epochs_zero() -> None:
    """num_epochs=0 → ValueError。"""
    req = _make_api_request(hyperparams={"num_epochs": 0})
    with pytest.raises(ValueError, match="num_epochs"):
        _normalize(req, _make_config())


def test_normalize_invalid_epochs_over_100() -> None:
    """num_epochs=101 → ValueError。"""
    req = _make_api_request(hyperparams={"num_epochs": 101})
    with pytest.raises(ValueError, match="num_epochs"):
        _normalize(req, _make_config())


def test_normalize_invalid_batch_size() -> None:
    """batch_size=100 → ValueError。"""
    req = _make_api_request(hyperparams={"batch_size": 100})
    with pytest.raises(ValueError, match="batch_size"):
        _normalize(req, _make_config())


def test_normalize_non_numeric_epochs() -> None:
    """num_epochs='abc' → ValueError with clear message。"""
    req = _make_api_request(hyperparams={"num_epochs": "abc"})
    with pytest.raises(ValueError, match="numeric"):
        _normalize(req, _make_config())


def test_normalize_fractional_epochs_rejected() -> None:
    """num_epochs=1.9 → ValueError（拒绝静默截断）。"""
    req = _make_api_request(hyperparams={"num_epochs": 1.9})
    with pytest.raises(ValueError, match="integer"):
        _normalize(req, _make_config())


def test_normalize_fractional_batch_rejected() -> None:
    """batch_size=4.8 → ValueError（拒绝静默截断）。"""
    req = _make_api_request(hyperparams={"batch_size": 4.8})
    with pytest.raises(ValueError, match="integer"):
        _normalize(req, _make_config())


def test_normalize_integer_float_accepted() -> None:
    """num_epochs=5.0 (整数值 float) → 通过。"""
    req = _make_api_request(hyperparams={"num_epochs": 5.0})
    result = _normalize(req, _make_config())
    assert result.num_epochs == 5


# ── 不可变性 ──


def test_normalize_does_not_mutate_input() -> None:
    """_normalize() 不修改原始请求的 hyperparams dict。"""
    original_hp = {"num_epochs": 5, "other": "value"}
    req = _make_api_request(hyperparams=original_hp)
    _normalize(req, _make_config())
    # 原始 hyperparams 不应被修改
    assert "num_epochs" in original_hp
    assert original_hp == {"num_epochs": 5, "other": "value"}
