"""EvoCase 数据适配层单元测试。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

# ── EvoCase dataclass ──


def test_evo_case_fields() -> None:
    """EvoCase 包含 case_id, queries, extra_data, expected_behavior。"""
    from evo_agent.dataset.case import EvoCase

    c = EvoCase(
        case_id="case-001",
        queries=["你好", "推荐一个产品"],
        extra_data={"role_id": "1"},
        expected_behavior="推荐热销产品",
    )
    assert c.case_id == "case-001"
    assert c.queries == ("你好", "推荐一个产品")
    assert c.extra_data == {"role_id": "1"}
    assert c.expected_behavior == "推荐热销产品"


def test_evo_case_frozen() -> None:
    """EvoCase 是 frozen dataclass。"""
    from evo_agent.dataset.case import EvoCase

    c = EvoCase(case_id="case-001", queries=["q1"])
    with pytest.raises(AttributeError):
        c.case_id = "other"  # type: ignore[misc]


def test_evo_case_defaults() -> None:
    """extra_data 默认 {}，expected_behavior 默认空字符串。"""
    from evo_agent.dataset.case import EvoCase

    c = EvoCase(case_id="case-001", queries=["q1"])
    assert c.extra_data == {}
    assert c.expected_behavior == ""


# ── parse_evo_cases() ──


def test_parse_evo_cases_basic() -> None:
    """标准 JSON → EvoCase 列表。"""
    from evo_agent.dataset.case import parse_evo_cases

    raw = [
        {
            "id": "case-001",
            "inputs": [{"role": "user", "content": "你好"}],
            "expected_behavior": "打招呼",
        },
        {
            "id": "case-002",
            "inputs": [{"role": "user", "content": "推荐"}],
            "expected_behavior": "推荐产品",
        },
    ]
    cases = parse_evo_cases(raw)
    assert len(cases) == 2
    assert cases[0].case_id == "case-001"
    assert cases[1].case_id == "case-002"


def test_parse_evo_cases_skips_empty_inputs() -> None:
    """无 inputs 的 case 被跳过。"""
    from evo_agent.dataset.case import parse_evo_cases

    raw = [
        {"id": "case-001", "inputs": [], "expected_behavior": "skip"},
        {"id": "case-002", "inputs": [{"role": "user", "content": "hi"}]},
    ]
    cases = parse_evo_cases(raw)
    assert len(cases) == 1
    assert cases[0].case_id == "case-002"


def test_parse_evo_cases_ignores_extra_fields() -> None:
    """type, source_trajectory 被忽略。"""
    from evo_agent.dataset.case import parse_evo_cases

    raw = [
        {
            "id": "case-001",
            "type": "conversation",
            "source_trajectory": [{"role": "user", "content": "hi"}],
            "inputs": [{"role": "user", "content": "hi"}],
            "expected_behavior": "hello",
        },
    ]
    cases = parse_evo_cases(raw)
    assert len(cases) == 1
    assert cases[0].case_id == "case-001"


def test_parse_evo_cases_multi_query() -> None:
    """inputs 含多个 query 时全部保留。"""
    from evo_agent.dataset.case import parse_evo_cases

    raw = [
        {
            "id": "case-001",
            "inputs": [
                {"role": "user", "content": "你好"},
                {"role": "user", "content": "推荐一个产品"},
            ],
        },
    ]
    cases = parse_evo_cases(raw)
    assert len(cases[0].queries) == 2
    assert cases[0].queries[0] == "你好"
    assert cases[0].queries[1] == "推荐一个产品"


def test_parse_evo_cases_string_inputs() -> None:
    """inputs 为字符串数组时直接作为 queries。"""
    from evo_agent.dataset.case import parse_evo_cases

    raw = [
        {
            "id": "case-001",
            "inputs": ["帮我推荐理财产品", "查看基金收益"],
            "expected_behavior": "推荐产品",
        },
    ]
    cases = parse_evo_cases(raw)
    assert len(cases) == 1
    assert cases[0].case_id == "case-001"
    assert cases[0].queries == ("帮我推荐理财产品", "查看基金收益")
    assert cases[0].expected_behavior == "推荐产品"


def test_parse_evo_cases_string_inputs_empty_skipped() -> None:
    """字符串 inputs 中空字符串被跳过。"""
    from evo_agent.dataset.case import parse_evo_cases

    raw = [
        {"id": "case-001", "inputs": ["", "有效输入"]},
        {"id": "case-002", "inputs": [""]},
    ]
    cases = parse_evo_cases(raw)
    assert len(cases) == 1
    assert cases[0].queries == ("有效输入",)


# ── evo_case_to_case() ──


def test_evo_case_to_case_mapping() -> None:
    """case_id, query(→inputs["query"]), queries(→inputs["queries"]),
    expected_behavior(→label["expected_result"]) 正确映射。"""
    from evo_agent.dataset.case import EvoCase, evo_case_to_case

    evo = EvoCase(
        case_id="case-001",
        queries=["你好", "推荐"],
        expected_behavior="热情回复",
    )
    case = evo_case_to_case(evo)
    assert case.case_id == "case-001"
    assert case.inputs["query"] == "你好"
    assert case.inputs["queries"] == ["你好", "推荐"]
    assert case.label["expected_result"] == "热情回复"


def test_evo_case_to_case_single_query() -> None:
    """单 query 时 inputs["query"] == inputs["queries"][0]。"""
    from evo_agent.dataset.case import EvoCase, evo_case_to_case

    evo = EvoCase(case_id="case-001", queries=["你好"])
    case = evo_case_to_case(evo)
    assert case.inputs["query"] == "你好"
    assert case.inputs["queries"] == ["你好"]


# ── merge_extra_data() ──


def test_merge_extra_data_case_overrides() -> None:
    """case 级 extra_data 覆盖 base。"""
    from evo_agent.dataset.case import merge_extra_data

    base = {"role_id": "1", "role_name": "mobile-bank"}
    case_level = {"role_id": "2", "extra_key": "val"}
    merged = merge_extra_data(base, case_level)
    assert merged == {"role_id": "2", "role_name": "mobile-bank", "extra_key": "val"}


def test_merge_extra_data_empty() -> None:
    """两个空 dict 合并返回空 dict。"""
    from evo_agent.dataset.case import merge_extra_data

    assert merge_extra_data({}, {}) == {}


def test_merge_extra_data_base_only() -> None:
    """case_level 为空时返回 base。"""
    from evo_agent.dataset.case import merge_extra_data

    base = {"role_id": "1"}
    assert merge_extra_data(base, {}) == {"role_id": "1"}


# ── _load_cases backward compat ──


def test_load_cases_backward_compat(tmp_path: Path) -> None:
    """agent-core Case 格式 JSON（含 case_id + inputs: dict）仍可直接加载。"""
    from evo_agent.dataset.manifest import _load_cases

    data = [
        {
            "case_id": "case-001",
            "inputs": {"query": "你好"},
            "label": {"expected_behavior": "打招呼"},
        },
    ]
    f = tmp_path / "cases.json"
    f.write_text(json.dumps(data), encoding="utf-8")
    cases = _load_cases(f)
    assert len(cases) == 1
    assert cases[0].case_id == "case-001"
    assert cases[0].inputs["query"] == "你好"


def test_load_cases_evo_format(tmp_path: Path) -> None:
    """EvoCase 格式 JSON（含 id + inputs: list）经适配层转换。"""
    from evo_agent.dataset.manifest import _load_cases

    data = [
        {
            "id": "evo-001",
            "inputs": [{"role": "user", "content": "推荐"}],
            "expected_behavior": "推荐产品",
        },
    ]
    f = tmp_path / "cases.json"
    f.write_text(json.dumps(data), encoding="utf-8")
    cases = _load_cases(f)
    assert len(cases) == 1
    assert cases[0].case_id == "evo-001"
    assert cases[0].inputs["query"] == "推荐"
    assert cases[0].label["expected_result"] == "推荐产品"
