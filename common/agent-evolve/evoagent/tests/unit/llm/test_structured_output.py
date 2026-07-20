"""Unified structured LLM output behavior tests."""

import pytest

from evo_agent.llm.structured_output import (
    StructuredOutputPolicy,
    ValidationResult,
    parse_structured_output,
)


def test_exact_object_is_returned_without_repair() -> None:
    result = parse_structured_output(
        '{"goal": "ship it"}',
        policy=StructuredOutputPolicy(
            schema_name="goal_generation",
            required_keys=frozenset({"goal"}),
            allowed_comma_next_keys=frozenset({"goal"}),
        ),
        validator=lambda _data: ValidationResult(ok=True),
    )

    assert result.data == {"goal": "ship it"}
    assert result.error == ""
    assert result.error_category is None
    assert result.parse_mode == "exact"
    assert result.repair_operations == ()
    assert result.repaired_text is None


def test_code_fence_and_one_missing_object_comma_are_repaired_together() -> None:
    result = parse_structured_output(
        '```json\n{"score": 0.8 "reason": "ok"}\n```',
        policy=StructuredOutputPolicy(
            schema_name="llm_evaluation",
            required_keys=frozenset({"reason"}),
            allowed_comma_next_keys=frozenset({"reason"}),
        ),
        validator=lambda _data: ValidationResult(ok=True),
    )

    assert result.data == {"score": 0.8, "reason": "ok"}
    assert result.parse_mode == "deterministic_comma_repair"
    assert [operation.op for operation in result.repair_operations] == [
        "strip_code_fence",
        "insert_comma",
    ]
    assert result.repaired_text == '{"score": 0.8, "reason": "ok"}'


def test_trailing_comma_and_missing_comma_are_repaired_together() -> None:
    result = parse_structured_output(
        '{"score": 0.8 "reason": "ok",}',
        policy=StructuredOutputPolicy(
            schema_name="llm_evaluation",
            required_keys=frozenset({"reason"}),
            allowed_comma_next_keys=frozenset({"reason"}),
        ),
        validator=lambda _data: ValidationResult(ok=True),
    )

    assert result.data == {"score": 0.8, "reason": "ok"}
    assert [operation.op for operation in result.repair_operations] == [
        "remove_trailing_comma",
        "insert_comma",
    ]


@pytest.mark.parametrize(
    ("raw", "category"),
    [
        ('{"a": 1 "reason": "x" "goal": "y"}', "syntax"),
        ('{"items": [1 2], "reason": "x"}', "syntax"),
        ('{"score": 1 "unknown": "x", "reason": "ok"}', "syntax"),
        ('{"reason": "first", "reason": "second"}', "duplicate_key"),
        ('{"score": 1}', "required_key"),
        ('["reason"]', "top_level_type"),
    ],
)
def test_unsafe_or_schema_invalid_outputs_are_rejected(raw: str, category: str) -> None:
    result = parse_structured_output(
        raw,
        policy=StructuredOutputPolicy(
            schema_name="test",
            required_keys=frozenset({"reason"}),
            allowed_comma_next_keys=frozenset({"reason", "goal"}),
        ),
        validator=lambda _data: ValidationResult(ok=True),
    )

    assert result.data is None
    assert result.error_category == category
    assert result.parse_mode == "failed"


def test_stage_validator_failure_is_stable_and_deterministic() -> None:
    policy = StructuredOutputPolicy(schema_name="goal_generation")

    def validator(_data: dict[str, object]) -> ValidationResult:
        return ValidationResult(False, "field_type", "goal must be a string")

    first = parse_structured_output('{"goal": 42}', policy=policy, validator=validator)
    second = parse_structured_output('{"goal": 42}', policy=policy, validator=validator)

    assert first == second
    assert first.error_category == "field_type"
    assert first.error == "goal must be a string"


def test_failed_validation_preserves_all_applied_repair_provenance() -> None:
    result = parse_structured_output(
        '```json\n{"goal": 42 "reason": "evidence"}\n```',
        policy=StructuredOutputPolicy(
            schema_name="goal_generation",
            allowed_comma_next_keys=frozenset({"reason"}),
        ),
        validator=lambda _data: ValidationResult(
            False,
            "field_type",
            "goal must be a string",
        ),
    )

    assert result.data is None
    assert result.error_category == "field_type"
    assert result.parse_mode == "failed"
    assert [operation.op for operation in result.repair_operations] == [
        "strip_code_fence",
        "insert_comma",
    ]
    assert result.repaired_text == '{"goal": 42, "reason": "evidence"}'


def test_failed_parse_preserves_repairs_before_unrepairable_syntax() -> None:
    result = parse_structured_output(
        '```json\n{"goal": "x", "unknown": [}\n```',
        policy=StructuredOutputPolicy(schema_name="goal_generation"),
        validator=lambda _data: ValidationResult(ok=True),
    )

    assert result.data is None
    assert result.error_category == "syntax"
    assert [operation.op for operation in result.repair_operations] == ["strip_code_fence"]
    assert result.repaired_text == '{"goal": "x", "unknown": [}'


def test_string_content_is_preserved_during_comment_repair() -> None:
    raw = '{"url":"https://example.test/a//b",// note\n"reason":"a,b \\"quoted\\""}'
    result = parse_structured_output(
        raw,
        policy=StructuredOutputPolicy(schema_name="test"),
        validator=lambda _data: ValidationResult(ok=True),
    )

    assert result.data == {
        "url": "https://example.test/a//b",
        "reason": 'a,b "quoted"',
    }


@pytest.mark.parametrize("constant", ["NaN", "Infinity", "-Infinity"])
def test_non_standard_numeric_constants_are_rejected(constant: str) -> None:
    result = parse_structured_output(
        f'{{"score": {constant}}}',
        policy=StructuredOutputPolicy(schema_name="test"),
        validator=lambda _data: ValidationResult(ok=True),
    )

    assert result.data is None
    assert result.error_category == "syntax"
