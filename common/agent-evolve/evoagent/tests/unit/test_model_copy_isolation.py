"""A6 (#10): model_copy deep=False — inputs 隔离性单元测试。

trainer.py 与 edp_agent/optimizer.py 在 rollout 时用 ``case.model_copy(
update={"inputs": {...**case.inputs...}}, deep=False)`` 构造评估用 case。
本测试固化 inputs 隔离契约：评估 case 的 inputs 是独立 dict，
写入 trajectory/skill_names 不污染原始 case.inputs。
"""

from __future__ import annotations

from openjiuwen.agent_evolving.dataset import Case


def _build_eval_case(case: Case, trajectory: dict, skill_names: list[str]) -> Case:
    """复刻 trainer/edp_agent 的 case copy 构造模式（deep=False）。"""
    return case.model_copy(
        update={
            "inputs": {
                **case.inputs,
                "trajectory": trajectory,
                "skill_names": skill_names,
            }
        },
        deep=False,
    )


def test_eval_case_inputs_is_separate_dict() -> None:
    """eval_case.inputs 与原 case.inputs 是不同对象。"""
    case = Case(inputs={"question": "推荐基金"}, label={"expected_result": None})
    eval_case = _build_eval_case(case, {"messages": []}, ["s1"])
    assert eval_case.inputs is not case.inputs


def test_trajectory_does_not_leak_into_original() -> None:
    """写入 trajectory 不出现在原始 case.inputs。"""
    case = Case(inputs={"question": "q"}, label={"expected_result": None})
    _build_eval_case(case, {"messages": [{"role": "assistant"}]}, ["s1"])
    assert "trajectory" not in case.inputs
    assert "skill_names" not in case.inputs


def test_mutating_eval_inputs_does_not_affect_original() -> None:
    """向 eval_case.inputs 写入新键不泄漏到原 case.inputs。"""
    case = Case(inputs={"question": "q"}, label={"expected_result": None})
    eval_case = _build_eval_case(case, {"messages": []}, ["s1"])
    eval_case.inputs["extra"] = "leaked"
    assert "extra" not in case.inputs


def test_eval_case_preserves_original_input_values() -> None:
    """deep=False 仍保留原 inputs 中的键值（浅引用）。"""
    case = Case(inputs={"question": "q", "shared": [1, 2]}, label={"expected_result": None})
    eval_case = _build_eval_case(case, {"messages": []}, ["s1"])
    assert eval_case.inputs["question"] == "q"
    assert eval_case.inputs["skill_names"] == ["s1"]
    assert "trajectory" in eval_case.inputs
