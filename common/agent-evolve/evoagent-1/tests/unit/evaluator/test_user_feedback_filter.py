"""UserFeedbackFilter 单元测试 — 后续用户消息中的纠正/拒绝信号。"""

from __future__ import annotations

from typing import Any

import pytest

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.filters.user_feedback import UserFeedbackFilter


def _trajectory(*messages: dict[str, Any]) -> StandardTrajectory:
    return StandardTrajectory.model_validate({"messages": list(messages)})


def _user(content: Any) -> dict[str, Any]:
    return {"role": "user", "content": content}


class TestSkipInitial:
    """skip_initial_user_messages — 首条用户消息（原始任务）不参与检查。"""

    def test_default_skips_first_user_message(self) -> None:
        traj = _trajectory(
            _user("不对，这是错的"),  # 第 1 条 user，默认跳过
            _user("你应该重新做"),
        )
        matches = UserFeedbackFilter().inspect(traj)
        assert len(matches) == 1
        assert matches[0].message_index == 1  # 第二条 user 的整体索引

    def test_skip_zero_checks_all(self) -> None:
        traj = _trajectory(
            _user("不对"),
            _user("还是不行"),
        )
        matches = UserFeedbackFilter(skip_initial_user_messages=0).inspect(traj)
        assert len(matches) == 2

    def test_skip_larger_than_count_returns_empty(self) -> None:
        traj = _trajectory(_user("不对"), _user("错了"))
        matches = UserFeedbackFilter(skip_initial_user_messages=5).inspect(traj)
        assert matches == []

    def test_negative_skip_raises(self) -> None:
        with pytest.raises(ValueError, match="non-negative"):
            UserFeedbackFilter(skip_initial_user_messages=-1)


class TestDefaultPatterns:
    """默认中文纠正/拒绝模式匹配。"""

    def test_explicit_rejection(self) -> None:
        traj = _trajectory(_user("原始任务"), _user("不对，结果错了"))
        matches = UserFeedbackFilter().inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "explicit_rejection"

    def test_correction_instruction(self) -> None:
        traj = _trajectory(_user("任务"), _user("你应该先查询账户"))
        matches = UserFeedbackFilter().inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "correction_instruction"

    def test_unresolved_outcome(self) -> None:
        traj = _trajectory(_user("任务"), _user("还是没有解决"))
        matches = UserFeedbackFilter().inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "unresolved_outcome"

    def test_only_first_match_per_message(self) -> None:
        traj = _trajectory(_user("任务"), _user("不对，还是不行，你应该重新做"))
        matches = UserFeedbackFilter().inspect(traj)
        assert len(matches) == 1

    def test_clean_later_message_no_match(self) -> None:
        traj = _trajectory(_user("任务"), _user("谢谢，这样很好"))
        assert UserFeedbackFilter().inspect(traj) == []


class TestRoleAndEdgeCases:
    """仅 user 角色参与；assistant/tool 被跳过。"""

    def test_assistant_message_ignored(self) -> None:
        traj = _trajectory(
            _user("任务"),
            {"role": "assistant", "content": "不对，这是助手说的，不算反馈"},
        )
        assert UserFeedbackFilter().inspect(traj) == []

    def test_tool_message_ignored(self) -> None:
        traj = _trajectory(
            _user("任务"),
            {"role": "tool", "content": "不对"},
        )
        assert UserFeedbackFilter().inspect(traj) == []

    def test_no_user_messages(self) -> None:
        traj = _trajectory({"role": "assistant", "content": "hi"})
        assert UserFeedbackFilter().inspect(traj) == []

    def test_structured_content_stringified(self) -> None:
        """非字符串内容先转 JSON 串再匹配。"""
        traj = _trajectory(_user("任务"), _user({"detail": "不对，这里错了"}))
        matches = UserFeedbackFilter().inspect(traj)
        assert len(matches) == 1


class TestCustomPatterns:
    """自定义 pattern + replace_default_patterns。"""

    def test_custom_pattern_matched(self) -> None:
        traj = _trajectory(_user("任务"), _user("please retry now"))
        matches = UserFeedbackFilter(patterns=[r"please retry"]).inspect(traj)
        rule_ids = [m.rule_id for m in matches]
        assert "custom_1" in rule_ids

    def test_replace_defaults_only_custom(self) -> None:
        traj = _trajectory(_user("任务"), _user("不对"))
        # 默认 explicit_rejection 被丢弃，custom 不匹配
        matches = UserFeedbackFilter(
            patterns=[r"custom_signal"],
            replace_default_patterns=True,
        ).inspect(traj)
        assert matches == []

    def test_custom_pattern_case_insensitive(self) -> None:
        traj = _trajectory(_user("任务"), _user("PLEASE RETRY"))
        matches = UserFeedbackFilter(patterns=[r"please retry"]).inspect(traj)
        assert len(matches) == 1
