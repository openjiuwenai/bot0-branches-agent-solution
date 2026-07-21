"""ToolFailureFilter 单元测试 — 结构化 + 关键词失败检测。"""

from __future__ import annotations

from typing import Any

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.filters.tool_failure import (
    ToolFailureFilter,
    _parse_structured,
    _structured_failure_state,
)


def _trajectory(*messages: dict[str, Any]) -> StandardTrajectory:
    return StandardTrajectory.model_validate({"messages": list(messages)})


def _msg(content: Any, role: str = "tool") -> dict[str, Any]:
    return {"role": role, "content": content}


class TestParseStructured:
    def test_dict_passthrough(self) -> None:
        d = {"code": 1}
        assert _parse_structured(d) is d

    def test_list_passthrough(self) -> None:
        lst = [{"code": 1}]
        assert _parse_structured(lst) is lst

    def test_json_string_parsed(self) -> None:
        assert _parse_structured('{"code": 1}') == {"code": 1}

    def test_non_json_string_returns_none(self) -> None:
        assert _parse_structured("not json") is None

    def test_none_returns_none(self) -> None:
        assert _parse_structured(None) is None


class TestStructuredFailureState:
    """_structured_failure_state — code/status/success/error/exception 优先级。"""

    def test_non_dict_is_unknown(self) -> None:
        assert _structured_failure_state("string") is None
        assert _structured_failure_state(None) is None
        assert _structured_failure_state([1, 2]) is None

    def test_nonzero_code_is_failure(self) -> None:
        assert _structured_failure_state({"code": 1}) is True

    def test_zero_code_is_success(self) -> None:
        assert _structured_failure_state({"code": 0}) is False

    def test_bool_code_is_skipped(self) -> None:
        """布尔不被当作 code（避免 True==1 误判）。"""
        assert _structured_failure_state({"code": True}) is None

    def test_failure_status_string(self) -> None:
        for s in ["failed", "FAILURE", "Error", "timeout "]:
            assert _structured_failure_state({"status": s}) is True

    def test_success_status_string(self) -> None:
        for s in ["success", "OK", "completed", "Succeeded"]:
            assert _structured_failure_state({"status": s}) is False

    def test_success_false_is_failure(self) -> None:
        assert _structured_failure_state({"success": False}) is True

    def test_success_true_is_success(self) -> None:
        assert _structured_failure_state({"success": True}) is False

    def test_error_field_is_failure(self) -> None:
        assert _structured_failure_state({"error": "boom"}) is True

    def test_empty_error_is_unknown(self) -> None:
        assert _structured_failure_state({"error": ""}) is None
        assert _structured_failure_state({"error": None}) is None
        assert _structured_failure_state({"error": 0}) is None

    def test_exception_field_is_failure(self) -> None:
        assert _structured_failure_state({"exception": "ValueError"}) is True

    def test_unknown_dict_is_none(self) -> None:
        assert _structured_failure_state({"foo": "bar"}) is None

    def test_priority_code_over_status(self) -> None:
        """code 优先于 status。"""
        assert _structured_failure_state({"code": 1, "status": "success"}) is True


class TestStructuredInspection:
    """ToolFailureFilter.inspect — 结构化失败/成功/未知分支。"""

    def test_structured_failure_dict_match(self) -> None:
        traj = _trajectory(_msg({"code": 500}))
        matches = ToolFailureFilter().inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "structured_failure"
        assert matches[0].filter_type == "tool_failure"
        assert matches[0].message_index == 0
        assert matches[0].metadata == {"code": 500}

    def test_structured_failure_json_string(self) -> None:
        traj = _trajectory(_msg('{"status": "failed"}'))
        matches = ToolFailureFilter().inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "structured_failure"

    def test_structured_success_skipped(self) -> None:
        traj = _trajectory(_msg({"code": 0}))
        assert ToolFailureFilter().inspect(traj) == []

    def test_list_structured_content_is_unknown(self) -> None:
        """list 内容直接传入 _parse_structured，非 dict → 状态未知 → 落到关键词匹配。

        list 的状态判定无法触发（_structured_failure_state 仅识别 dict），
        因此 list 被当作普通字符串做关键词匹配。
        """
        traj = _trajectory(_msg([{"code": 1}]))
        matches = ToolFailureFilter().inspect(traj)
        # list 的 JSON 串不含失败关键词，且 list 不被当作结构化失败
        assert matches == []


class TestKeywordInspection:
    """结构未知时落到关键词正则匹配。"""

    def test_keyword_timeout_match(self) -> None:
        traj = _trajectory(_msg("the request timed out"))
        matches = ToolFailureFilter().inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "timeout"
        assert matches[0].pattern is not None

    def test_keyword_failure_match_chinese(self) -> None:
        traj = _trajectory(_msg("连接失败"))
        matches = ToolFailureFilter().inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "failure"

    def test_only_one_match_per_message(self) -> None:
        """同一条消息同时命中 timeout 与 error，只返回首个匹配。"""
        traj = _trajectory(_msg("timeout and error"))
        matches = ToolFailureFilter().inspect(traj)
        assert len(matches) == 1

    def test_multiple_messages_multiple_matches(self) -> None:
        traj = _trajectory(
            _msg("timeout"),
            _msg("all good"),  # 无匹配
            _msg("exception occurred"),
        )
        matches = ToolFailureFilter().inspect(traj)
        assert len(matches) == 2
        assert [m.message_index for m in matches] == [0, 2]

    def test_case_insensitive_keyword(self) -> None:
        traj = _trajectory(_msg("TIMEOUT"))
        matches = ToolFailureFilter().inspect(traj)
        assert len(matches) == 1


class TestRoleAndEdgeCases:
    """非 tool 角色 + 边界。"""

    def test_non_tool_messages_skipped(self) -> None:
        traj = _trajectory(
            _msg("timeout", role="user"),
            _msg("timeout", role="assistant"),
        )
        assert ToolFailureFilter().inspect(traj) == []

    def test_empty_trajectory(self) -> None:
        assert ToolFailureFilter().inspect(StandardTrajectory(messages=[])) == []

    def test_clean_trajectory_no_match(self) -> None:
        traj = _trajectory(_msg({"result": "completed successfully"}))
        assert ToolFailureFilter().inspect(traj) == []


class TestCustomPatterns:
    """自定义 pattern + replace_default_patterns。"""

    def test_custom_pattern_appended(self) -> None:
        traj = _trajectory(_msg("quota exceeded"))
        matches = ToolFailureFilter(patterns=[r"quota exceeded"]).inspect(traj)
        rule_ids = [m.rule_id for m in matches]
        assert "custom_1" in rule_ids

    def test_replace_defaults_uses_only_custom(self) -> None:
        traj = _trajectory(_msg("timeout"))
        matches = ToolFailureFilter(
            patterns=[r"custom_signal"],
            replace_default_patterns=True,
        ).inspect(traj)
        # 默认 timeout 被丢弃，custom 不匹配 "timeout"
        assert matches == []

    def test_replace_defaults_custom_matches(self) -> None:
        traj = _trajectory(_msg("timeout"))
        matches = ToolFailureFilter(
            patterns=[r"timeout"],
            replace_default_patterns=True,
        ).inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "custom_1"
