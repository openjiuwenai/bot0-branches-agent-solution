"""Optimizer stage schema policies and business validation tests."""

import pytest

from evo_agent.llm.structured_output import parse_structured_output
from evo_agent.optimizer.skill_document.structured_validators import (
    MERGE_FAILURE_POLICY,
    MERGE_FINAL_POLICY,
    MERGE_SUCCESS_POLICY,
    META_SKILL_POLICY,
    RANKING_POLICY,
    REFLECT_FAILURE_POLICY,
    REFLECT_SUCCESS_POLICY,
    SLOW_UPDATE_POLICY,
    valid_edit_items,
    valid_selected_indices,
    validate_merge_output,
    validate_meta_skill_output,
    validate_ranking_output,
    validate_reflect_output,
    validate_slow_update_output,
)


@pytest.mark.parametrize(
    "data",
    [
        {"patch": {"reasoning": "r", "edits": []}},
        {"reasoning": "r", "edits": []},
    ],
)
def test_reflect_accepts_wrapper_and_legacy_bare_patch(data: dict[str, object]) -> None:
    assert validate_reflect_output(data).ok


@pytest.mark.parametrize("data", [{"patch": []}, {"edits": {}}])
def test_reflect_rejects_invalid_patch_or_edits_container(data: dict[str, object]) -> None:
    result = validate_reflect_output(data)

    assert not result.ok
    assert result.category == "structure"


def test_merge_and_ranking_require_list_containers() -> None:
    assert validate_merge_output({"edits": []}).ok
    assert validate_ranking_output({"selected_indices": []}).ok
    assert validate_merge_output({"edits": {}}).category == "structure"
    assert validate_ranking_output({"selected_indices": {}}).category == "structure"


def test_slow_and_meta_accept_missing_content_but_reject_present_non_strings() -> None:
    assert validate_slow_update_output({}).ok
    assert validate_meta_skill_output({"meta_skill_content": ""}).ok
    assert validate_slow_update_output({"slow_update_content": None}).category == "field_type"
    assert validate_meta_skill_output({"reasoning": 1}).category == "field_type"


def test_item_level_helpers_filter_bad_items_without_rejecting_valid_items() -> None:
    edits = valid_edit_items(
        [
            {"op": "append", "content": "keep"},
            {"op": "unknown", "content": "drop"},
            "drop",
        ]
    )
    indices = valid_selected_indices(
        [True, 2, -1, 2, "0", 0, 9, 1],
        pool_size=3,
        budget=2,
    )

    assert edits == [{"op": "append", "content": "keep"}]
    assert indices == [2, 0]


@pytest.mark.parametrize(
    ("policy", "validator"),
    [
        (REFLECT_FAILURE_POLICY, validate_reflect_output),
        (REFLECT_SUCCESS_POLICY, validate_reflect_output),
        (MERGE_FAILURE_POLICY, validate_merge_output),
        (MERGE_SUCCESS_POLICY, validate_merge_output),
        (MERGE_FINAL_POLICY, validate_merge_output),
        (RANKING_POLICY, validate_ranking_output),
        (SLOW_UPDATE_POLICY, validate_slow_update_output),
        (META_SKILL_POLICY, validate_meta_skill_output),
    ],
)
def test_each_stage_policy_repairs_only_its_own_allowed_next_keys(
    policy: object,
    validator: object,
) -> None:
    own_raw = {
        "reflect_failure": '{"patch": {"reasoning": "r" "edits": []}}',
        "reflect_success": '{"patch": {"reasoning": "r" "edits": []}}',
        "merge_failure": '{"reasoning": "r" "edits": []}',
        "merge_success": '{"reasoning": "r" "edits": []}',
        "merge_final": '{"reasoning": "r" "edits": []}',
        "ranking": '{"reasoning": "r" "selected_indices": []}',
        "slow_update": '{"reasoning": "r" "slow_update_content": "x"}',
        "meta_skill": '{"reasoning": "r" "meta_skill_content": "x"}',
    }
    # Parameter types are deliberately generic above so this table catches accidental
    # policy/validator coupling; runtime objects expose the documented public interface.
    result = parse_structured_output(  # type: ignore[arg-type]
        own_raw[policy.schema_name],  # type: ignore[union-attr]
        policy=policy,
        validator=validator,
    )

    assert result.data is not None
    assert result.parse_mode == "deterministic_comma_repair"


def test_reflect_does_not_repair_merge_only_source_keys() -> None:
    raw = '{"patch":{"edits":[{"op":"append" "source_ids":["case-1"]}]}}'

    reflect = parse_structured_output(
        raw,
        policy=REFLECT_FAILURE_POLICY,
        validator=validate_reflect_output,
    )
    merge = parse_structured_output(
        '{"edits":[{"op":"append" "source_ids":["case-1"]}]}',
        policy=MERGE_FAILURE_POLICY,
        validator=validate_merge_output,
    )

    assert reflect.data is None
    assert reflect.error_category == "syntax"
    assert merge.data is not None
    assert merge.parse_mode == "deterministic_comma_repair"
