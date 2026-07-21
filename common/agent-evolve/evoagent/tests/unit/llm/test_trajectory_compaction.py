"""共享 trajectory 压缩引擎行为测试。"""

import json

import pytest
from openjiuwen.agent_evolving.trajectory import (
    ToolCallDetail,
    Trajectory,
    TrajectoryStep,
)

from evo_agent.llm.trajectory_compaction import (
    TrajectoryCompactionContext,
    TrajectoryCompactionError,
    TrajectoryCompactionPolicy,
    compact_trajectory,
)


def test_long_tool_result_is_deterministic_and_preserves_arguments() -> None:
    """result 可裁剪，但 tool arguments 的结构和值不可改变。"""
    arguments = '{"path":"/tmp/a.json","limit":42}'
    trajectory = {
        "messages": [
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "call-1",
                        "function": {"name": "read_file", "arguments": arguments},
                    }
                ],
            },
            {"role": "tool", "tool_call_id": "call-1", "content": "a" * 2000 + "TAIL"},
            {"role": "assistant", "content": "final answer"},
        ]
    }
    policy = TrajectoryCompactionPolicy(stage="evaluator")
    context = TrajectoryCompactionContext(task_goal="inspect file")

    first = compact_trajectory(trajectory, policy=policy, context=context, token_budget=5000)
    second = compact_trajectory(trajectory, policy=policy, context=context, token_budget=5000)

    assert first == second
    compacted = json.loads(first.text)
    assert compacted["messages"][0]["tool_calls"][0]["function"]["arguments"] == arguments
    assert "TAIL" in first.text
    assert "original_chars=2004" in first.text
    assert first.metadata["tool_results_truncated"] == 1


def test_orphan_tool_result_is_rejected_instead_of_emitted() -> None:
    """压缩视图不能制造或保留无法关联调用的 tool result。"""
    trajectory = {
        "messages": [
            {
                "role": "assistant",
                "tool_calls": [{"id": "call-1", "function": {"name": "search", "arguments": "{}"}}],
            },
            {"role": "tool", "tool_call_id": "different-call", "content": "result"},
        ]
    }

    with pytest.raises(TrajectoryCompactionError, match="orphan tool result"):
        compact_trajectory(
            trajectory,
            policy=TrajectoryCompactionPolicy(stage="reflect"),
            context=TrajectoryCompactionContext(),
            token_budget=1000,
        )


def test_unpaired_tool_call_does_not_create_a_missing_error_result() -> None:
    """配对信息不完整时保留原始证据，不得伪造 tool error。"""
    trajectory = {
        "messages": [
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "call-1",
                        "function": {"name": "search", "arguments": "{}"},
                    }
                ],
            },
            {"role": "tool", "name": "search", "content": "REAL RESULT"},
        ]
    }

    result = compact_trajectory(
        trajectory,
        policy=TrajectoryCompactionPolicy(stage="evaluator"),
        context=TrajectoryCompactionContext(),
        token_budget=1000,
    )

    messages = json.loads(result.text)["messages"]
    assert messages == trajectory["messages"]
    assert "TOOL_RESULT_MISSING" not in result.text


def test_optimizer_trajectory_adapts_to_the_same_paired_event_view() -> None:
    """optimizer Trajectory 也必须进入共享 call/result 保真视图。"""
    arguments = '{"query":"exact value"}'
    trajectory = Trajectory(
        execution_id="exec-1",
        steps=[
            TrajectoryStep(
                kind="tool",
                detail=ToolCallDetail(
                    tool_name="search",
                    call_args=arguments,
                    call_result="result",
                ),
            )
        ],
    )

    result = compact_trajectory(
        trajectory,
        policy=TrajectoryCompactionPolicy(stage="reflect"),
        context=TrajectoryCompactionContext(),
        token_budget=1000,
    )

    messages = json.loads(result.text)["messages"]
    assert messages[0]["tool_calls"][0]["function"] == {
        "name": "search",
        "arguments": arguments,
    }
    assert messages[1]["tool_call_id"] == messages[0]["tool_calls"][0]["id"]


def test_reflect_policy_preserves_evaluation_evidence_not_sent_to_evaluator() -> None:
    """同一引擎按阶段 policy 选择证据，reflect 额外携带评分理由。"""
    trajectory = {"messages": [{"role": "assistant", "content": "answer"}]}
    context = TrajectoryCompactionContext(
        evaluation_score=0.2,
        evaluation_reason="wrong tool was selected",
        target_skills=("search_skill",),
    )

    evaluator = compact_trajectory(
        trajectory,
        policy=TrajectoryCompactionPolicy(stage="evaluator"),
        context=context,
        token_budget=1000,
    )
    reflect = compact_trajectory(
        trajectory,
        policy=TrajectoryCompactionPolicy(
            stage="reflect",
            preserve_evaluation_reason=True,
            prioritize_skill_related=True,
        ),
        context=context,
        token_budget=1000,
    )

    assert "wrong tool was selected" not in evaluator.text
    assert "wrong tool was selected" in reflect.text
    assert "search_skill" in reflect.text
