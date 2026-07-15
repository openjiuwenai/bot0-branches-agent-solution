"""B3 (#5): 消除 trajectory 序列化往返 — 单元测试。

simplify_trajectory 接受 raw dict，跳过 StandardTrajectory.model_validate
的 dict→object→dict 往返。
"""

from __future__ import annotations

from typing import Any
from unittest.mock import patch

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.evaluators.llm import _simplify_for_prompt
from evo_agent.evaluator.trajectory.simplify import simplify_trajectory


def _trajectory_dict() -> dict[str, Any]:
    """构造一个带 read_file 工具调用的 trajectory dict。"""
    return {
        "summary": {"summary": "ok", "total_messages": 3},
        "messages": [
            {"role": "user", "content": "推荐基金"},
            {
                "role": "assistant",
                "content": "  ",
                "tool_calls": [
                    {
                        "id": "read-1",
                        "function": {"name": "read_file", "arguments": '{"path": "/a/SKILL.md"}'},
                    }
                ],
            },
            {
                "role": "tool",
                "tool_call_id": "read-1",
                "content": "FULL SKILL DOCUMENT",
            },
        ],
    }


def test_simplify_dict_matches_simplify_object() -> None:
    """dict 输入与 StandardTrajectory 输入产出等价 steps（字段对齐）。"""
    data = _trajectory_dict()
    obj = StandardTrajectory.model_validate(data)
    from_dict = simplify_trajectory(data)
    from_obj = simplify_trajectory(obj)
    assert from_dict.model_dump() == from_obj.model_dump()


def test_simplify_dict_tolerates_extra_fields() -> None:
    """dict 含 extra 字段（StandardTrajectory extra=forbid 会拒绝）仍可简化。"""
    data = _trajectory_dict()
    data["extra_top"] = "ignored"
    data["messages"][0]["extra_msg"] = "ignored"
    # StandardTrajectory.model_validate 会因 extra 失败，但 dict 路径不抛
    steps = simplify_trajectory(data).steps
    assert len(steps) >= 3


def test_simplify_for_prompt_skips_model_validate() -> None:
    """_simplify_for_prompt 不再调用 StandardTrajectory.model_validate。"""
    data = _trajectory_dict()
    with patch.object(StandardTrajectory, "model_validate") as mock_validate:
        prompt_str, warnings = _simplify_for_prompt(data)
    assert mock_validate.call_count == 0
    assert "推荐基金" in prompt_str or "read_file" in prompt_str
    assert isinstance(warnings, list)


def test_simplify_for_prompt_dict_and_object_prompt_equivalent() -> None:
    """dict 与 object 路径产出的 prompt 字符串等价。"""
    data = _trajectory_dict()
    obj = StandardTrajectory.model_validate(data)
    dict_str, _ = _simplify_for_prompt(data)
    # object 路径仍可用（向后兼容）
    obj_str, _ = _simplify_for_prompt(obj)
    assert dict_str == obj_str


def test_simplify_rejects_unexpected_type() -> None:
    """Type guard: a non-dict/non-StandardTrajectory raises TypeError.

    B3 removed the model_validate schema guard; without this check a stray
    string/object would silently yield empty steps. The guard restores
    fail-fast on structural mismatch.
    """
    import pytest

    with pytest.raises(TypeError, match="unexpected trajectory type"):
        simplify_trajectory("not a trajectory")  # type: ignore[arg-type]
