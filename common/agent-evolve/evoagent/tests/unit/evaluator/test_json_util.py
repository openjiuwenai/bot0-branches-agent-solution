"""共享 JSON 提取与确定性修复行为测试。"""

from evo_agent.evaluator.json_util import JsonRepairPolicy, extract_json
from evo_agent.llm.structured_output import StructuredOutputPolicy


def test_legacy_policy_name_reexports_the_unified_policy() -> None:
    assert JsonRepairPolicy is StructuredOutputPolicy


def test_extract_json_repairs_code_fence_and_trailing_comma() -> None:
    """完整响应可做不改变字段值的确定性语法修复。"""
    result = extract_json('```json\n{"score": 0.8,}\n```', policy=JsonRepairPolicy())

    assert result.data == {"score": 0.8}
    assert result.parse_mode == "deterministic_repair"
    assert [operation.op for operation in result.repair_operations] == [
        "strip_code_fence",
        "remove_trailing_comma",
    ]


def test_extract_json_repairs_one_allowed_missing_comma() -> None:
    """Evaluator 可在唯一 allowlist key 前插入一个逗号。"""
    raw = '{"score": 0.8 "reason": "ok"}'
    result = extract_json(
        raw,
        policy=JsonRepairPolicy(
            allow_single_missing_comma=True,
            allowed_comma_next_keys=frozenset({"reason"}),
            required_keys=frozenset({"score", "reason"}),
        ),
    )

    assert result.data == {"score": 0.8, "reason": "ok"}
    assert result.parse_mode == "deterministic_comma_repair"
    assert result.repair_operations == (
        type(result.repair_operations[0])("insert_comma", offset=13, next_key="reason"),
    )


def test_extract_json_removes_comments_only_outside_strings() -> None:
    """注释可移除，但 URL 中的双斜线必须逐字保留。"""
    raw = '{"url":"https://example.test/a",// evaluator note\n"score":1}'

    result = extract_json(raw, policy=JsonRepairPolicy())

    assert result.data == {"url": "https://example.test/a", "score": 1}
    assert [operation.op for operation in result.repair_operations] == ["remove_comment"]


def test_extract_json_appends_uniquely_determined_closing_delimiters() -> None:
    """括号栈唯一且字符串完整时，可以补齐尾部闭合符。"""
    result = extract_json('{"items":[{"score":1}', policy=JsonRepairPolicy())

    assert result.data == {"items": [{"score": 1}]}
    assert [operation.op for operation in result.repair_operations] == ["append_closing_delimiter"]


def test_extract_json_escapes_control_characters_inside_strings() -> None:
    """字符串中的裸控制字符只做等价 JSON 转义。"""
    result = extract_json('{"reason":"line 1\nline 2"}', policy=JsonRepairPolicy())

    assert result.data == {"reason": "line 1\nline 2"}
    assert [operation.op for operation in result.repair_operations] == ["escape_control_character"]


def test_extract_json_normalizes_unambiguous_single_quoted_strings() -> None:
    """边界唯一且内容无内嵌引号时可规范化单引号。"""
    result = extract_json("{'score':1,'reason':'ok'}", policy=JsonRepairPolicy())

    assert result.data == {"score": 1, "reason": "ok"}
    assert [operation.op for operation in result.repair_operations] == ["normalize_quote"]
