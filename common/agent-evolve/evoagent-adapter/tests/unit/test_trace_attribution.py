"""trace_attribution + spans_to_records + AttributionRunner 单测 (纯逻辑, 不依赖 DB)。"""

from __future__ import annotations

import pytest

from agent_adapter.attribution_runner import AttributionRunner
from agent_adapter.config import (
    AdapterConfig,
    AgentEntryConfig,
    AttributionConfig,
    ProseMatchingConfig,
    RecognizerConfig,
)
from agent_adapter.skill_store import SkillContent, SkillSummary
from agent_adapter.trace_attribution import (
    INGRESS_SKILL,
    SOURCE_ACTIVE_CONTEXT,
    SOURCE_INGRESS,
    SOURCE_PARENT_SKILL_SPAN,
    SOURCE_RESIDUAL,
    SOURCE_SKILL_SELECTION,
    SOURCE_TOOL_NAME_MATCH,
    Attribution,
    SkillAttributionMapper,
    SkillToolTable,
)
from agent_adapter.trace_source.spans_to_records import spans_to_records

_T0 = "2026-08-17T00:00:00+00:00"
_T1 = "2026-08-17T00:00:01+00:00"


def _span(
    span_id: str,
    name: str,
    parent: str = "",
    start: str = _T0,
    kind: str = "INTERNAL",
) -> dict:
    return {
        "trace_id": "tr1",
        "span_id": span_id,
        "parent_span_id": parent,
        "name": name,
        "kind": kind,
        "start_time": start,
        "end_time": None,
        "session_id": "sess1",
        "attributes": {},
    }


def _mapper(skill_names: set[str], table: SkillToolTable) -> SkillAttributionMapper:
    cfg = AttributionConfig(
        recognizers=[RecognizerConfig(kind="skill_span")],
        prose_matching=ProseMatchingConfig(enabled=True),
        fallback_skill="Agent.md",
    )
    return SkillAttributionMapper(cfg, skill_names, table)


# --- SkillToolTable.build: own/forbid/neutral 分类 ---


def test_skill_table_own_forbid_neutral():
    cfg = AttributionConfig(prose_matching=ProseMatchingConfig(enabled=True))
    docs = {
        "rec": "使用 sample_tool_1 查询产品。禁止使用 sample_tool_2。",
        "email": "调用 sample_tool_2 发邮件。sample_tool_3 的返回值包含字段。",
    }
    table = SkillToolTable.build(docs, ["sample_tool_1", "sample_tool_2", "sample_tool_3"], cfg)
    assert "sample_tool_1" in table.owns["rec"]
    assert "sample_tool_2" in table.forbids["rec"]
    assert "sample_tool_2" in table.owns["email"]
    assert table.owners_of("sample_tool_1") == ["rec"]
    assert table.owners_of("sample_tool_2") == ["email"]
    assert table.owners_of("sample_tool_3") == []


def test_skill_table_own_wins_over_forbid():
    """同 skill 既 own 句又 forbid 句 → 判 own (forbid 仅在无 own 时生效)。

    条件性告诫 (如 "禁止递归再次调用 X") 不应否决同 skill 对 X 的 own 句。
    真实数据 multi_skill 文档里 call_multiagent 曾有 10 句 own 被 1 句告诫误判
    forbid, 致 ATTR101 multi=0; 修法见 _classify_occurrences (own 优先于 forbid)。
    """
    cfg = AttributionConfig(prose_matching=ProseMatchingConfig(enabled=True))
    docs = {"s": "使用 tool_x。禁止使用 tool_x。"}
    table = SkillToolTable.build(docs, ["tool_x"], cfg)
    assert "tool_x" in table.owns["s"]
    assert "s" not in table.forbids
    # 纯 forbid (无 own 句) 仍判 forbid
    table2 = SkillToolTable.build({"s": "禁止使用 tool_y。"}, ["tool_y"], cfg)
    assert "tool_y" in table2.forbids["s"]
    assert "s" not in table2.owns


def test_skill_table_recursion_caution_does_not_forbid():
    """ATTR101 回归: "禁止递归…不得再次调用 X" 这类条件告诫不应否决 own 句。

    multi 文档对 call_multiagent: 多句 "通过 call_multiagent 调用子 agent" (own)
    + 1 句 "禁止递归: 本 Skill 不得再次调用 call_multiagent" (条件告诫)。
    旧规则 (任一 forbid 句 → 整体 forbid) 否决所有 own, 致 multi 上不了位;
    新规则 own 优先 → call_multiagent 归 multi。
    """
    cfg = AttributionConfig(prose_matching=ProseMatchingConfig(enabled=True))
    docs = {"multi": (
        "通过 call_multiagent 调用子 agent 执行。"
        "识别多个业务时使用 call_multiagent 并行。"
        "禁止递归: 本 Skill 不得再次调用 call_multiagent。"
    )}
    table = SkillToolTable.build(docs, ["call_multiagent"], cfg)
    assert "call_multiagent" in table.owns["multi"]
    assert "multi" not in table.forbids


def test_skill_table_disabled_returns_empty():
    cfg = AttributionConfig(prose_matching=ProseMatchingConfig(enabled=False))
    table = SkillToolTable.build({"s": "使用 tool_x"}, ["tool_x"], cfg)
    assert table.owns == {}
    assert table.forbids == {}


# --- SkillAttributionMapper: L1-L4 优先级树 ---


def test_l1_parent_skill_span_child():
    mapper = _mapper({"rec"}, SkillToolTable(owns={}, forbids={}))
    spans = [
        _span("s1", "skill.rec", parent="", start=_T0),
        _span("s2", "tool.sample_tool_1", parent="s1", start=_T1),
    ]
    result = mapper.infer(spans)
    assert result["s2"].skill == "rec"
    assert result["s2"].source == SOURCE_PARENT_SKILL_SPAN
    assert result["s2"].confidence == pytest.approx(1.0)


def test_l1_skill_span_itself():
    mapper = _mapper({"rec"}, SkillToolTable(owns={}, forbids={}))
    spans = [_span("s1", "skill.rec")]
    result = mapper.infer(spans)
    assert result["s1"].skill == "rec"
    assert result["s1"].source == SOURCE_PARENT_SKILL_SPAN


def test_l2_active_context_sibling():
    mapper = _mapper({"rec"}, SkillToolTable(owns={}, forbids={}))
    spans = [
        _span("s1", "skill.rec", parent="root", start=_T0),
        _span("s2", "llm.chat", parent="root", start=_T1),
    ]
    result = mapper.infer(spans)
    assert result["s2"].skill == "rec"
    assert result["s2"].source == SOURCE_ACTIVE_CONTEXT
    assert result["s2"].confidence == pytest.approx(0.8)


def test_l2_multi_skill_last_wins():
    """多 skill 顺序激活, 后续 span 归最近激活的(last-wins), 不因多个就放弃。"""
    mapper = _mapper({"a", "b"}, SkillToolTable(owns={}, forbids={}))
    spans = [
        _span("s1", "skill.a", parent="root", start="2026-08-17T00:00:00+00:00"),
        _span("s2", "llm.chat", parent="root", start="2026-08-17T00:00:01+00:00"),
        _span("s3", "skill.b", parent="root", start="2026-08-17T00:00:02+00:00"),
        _span("s4", "llm.chat", parent="root", start="2026-08-17T00:00:03+00:00"),
    ]
    result = mapper.infer(spans)
    assert result["s2"].skill == "a"  # a 之后 b 之前 → a
    assert result["s4"].skill == "b"  # b 之后 → b (last-wins)
    assert result["s2"].source == SOURCE_ACTIVE_CONTEXT


def test_l2_read_file_skill_selection_then_commit():
    """read_file 读 <skill>/SKILL.md 选型, 自身归 Agent.md + candidates。

    首个执行动作 commit, 该 span 及之后走 L2 active_context。
    """
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(kind="skill_span"),
            RecognizerConfig(
                kind="read_file_skill",
                tool_name="read_file",
                path_field="openjiuwen.agent.inputs",
            ),
        ],
        prose_matching=ProseMatchingConfig(enabled=True),
        fallback_skill="Agent.md",
    )
    mapper = SkillAttributionMapper(cfg, {"rec_skill"}, SkillToolTable(owns={}, forbids={}))
    rf = {
        "trace_id": "tr1",
        "span_id": "s1",
        "parent_span_id": "root",
        "name": "tool.read_file",
        "kind": "INTERNAL",
        "start_time": _T0,
        "end_time": None,
        "session_id": "sess1",
        "attributes": {
            "openjiuwen.agent.inputs": (
                "{'inputs': {'path': '/app/a2a_service/agents/EDPAgent/skills/rec_skill/SKILL.md'}}"
            )
        },
    }
    spans = [rf, _span("s2", "tool.call_versatile", parent="root", start=_T1)]
    result = mapper.infer(spans)
    # read_file 自身: 选型 → fallback + candidates, 不归被读 skill
    assert result["s1"].skill == "Agent.md"
    assert result["s1"].source == SOURCE_SKILL_SELECTION
    assert result["s1"].candidates == ["rec_skill"]
    # s2 是首个执行动作 → commit rec_skill, s2 起走 L2
    assert result["s2"].skill == "rec_skill"
    assert result["s2"].source == SOURCE_ACTIVE_CONTEXT


def test_l2_owned_tool_anchors_active_skill():
    """在岗 skill 自己的工具出现, 锚定不动, 不把无关候选扶正 (ATTR103 子 trace 实证)。

    call_multiversatile 被 sub own, 不应让候选里的 confirm 接班。
    """
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(kind="read_file_skill", tool_name="read_file", path_field="path"),
        ],
        prose_matching=ProseMatchingConfig(enabled=True),
        fallback_skill="Agent.md",
    )
    table = SkillToolTable(
        owns={
            "sub_skill": frozenset({"call_versatile", "call_multiversatile"}),
            "customer_confirm_skill": frozenset({"ask_user"}),
        },
        forbids={},
    )
    mapper = SkillAttributionMapper(cfg, {"sub_skill", "customer_confirm_skill"}, table)

    def rf(span_id: str, skill: str, start: str) -> dict:
        return {
            "trace_id": "tr1",
            "span_id": span_id,
            "parent_span_id": "root",
            "name": "tool.read_file",
            "kind": "INTERNAL",
            "start_time": start,
            "end_time": None,
            "session_id": "sess1",
            "attributes": {"path": f"/app/skills/{skill}/SKILL.md"},
        }

    spans = [
        rf("s1", "sub_skill", "2026-08-17T00:00:00+00:00"),
        rf("s2", "customer_confirm_skill", "2026-08-17T00:00:01+00:00"),
        _span("s3", "tool.call_versatile", parent="root", start="2026-08-17T00:00:02+00:00"),
        _span("s4", "tool.call_multiversatile", parent="root", start="2026-08-17T00:00:03+00:00"),
        _span("s5", "tool.lite_todo_write", parent="root", start="2026-08-17T00:00:04+00:00"),
    ]
    result = mapper.infer(spans)
    assert result["s3"].skill == "sub_skill"  # 认领者上岗
    assert result["s4"].skill == "sub_skill"  # 在岗 skill 的工具 → 锚定, confirm 不接班
    assert result["s5"].skill == "sub_skill"  # 锚定已消耗接班机会, 通用动作不再换人


def test_l2_generic_actions_no_rotation():
    """通用动作上岗后, 后续通用动作不再把剩余候选转正 (ATTR103: call_multiagent 不反超)。"""
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(kind="read_file_skill", tool_name="read_file", path_field="path"),
        ],
        prose_matching=ProseMatchingConfig(enabled=True),
        fallback_skill="Agent.md",
    )
    table = SkillToolTable(owns={"customer_confirm_skill": frozenset({"ask_user"})}, forbids={})
    mapper = SkillAttributionMapper(cfg, {"multi_skill", "customer_confirm_skill"}, table)

    def rf(span_id: str, skill: str, start: str) -> dict:
        return {
            "trace_id": "tr1",
            "span_id": span_id,
            "parent_span_id": "root",
            "name": "tool.read_file",
            "kind": "INTERNAL",
            "start_time": start,
            "end_time": None,
            "session_id": "sess1",
            "attributes": {"path": f"/app/skills/{skill}/SKILL.md"},
        }

    spans = [
        rf("s1", "multi_skill", "2026-08-17T00:00:00+00:00"),
        rf("s2", "customer_confirm_skill", "2026-08-17T00:00:01+00:00"),
        _span("s3", "tool.lite_todo_write", parent="root", start="2026-08-17T00:00:02+00:00"),
        _span("s4", "tool.call_multiagent", parent="root", start="2026-08-17T00:00:03+00:00"),
        _span("s5", "tool.lite_todo_write", parent="root", start="2026-08-17T00:00:04+00:00"),
    ]
    result = mapper.infer(spans)
    # 无人在岗 → 最早读的 multi 转正
    assert result["s3"].skill == "multi_skill"
    # call_multiagent / 后续: 通用动作不再换人, confirm 永不反超
    assert result["s4"].skill == "multi_skill"
    assert result["s5"].skill == "multi_skill"
    assert all(r.skill != "customer_confirm_skill" for r in result.values())


def test_l2_owned_commit_allows_generic_succession():
    """认领动作换岗后, 允许一个通用动作接班下一个候选 (ATTR101: ask_user→confirm 后通用动作扶正 multi)。"""
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(kind="read_file_skill", tool_name="read_file", path_field="path"),
        ],
        prose_matching=ProseMatchingConfig(enabled=True),
        fallback_skill="Agent.md",
    )
    table = SkillToolTable(owns={"customer_confirm_skill": frozenset({"ask_user"})}, forbids={})
    mapper = SkillAttributionMapper(cfg, {"multi_skill", "customer_confirm_skill"}, table)

    def rf(span_id: str, skill: str, start: str) -> dict:
        return {
            "trace_id": "tr1",
            "span_id": span_id,
            "parent_span_id": "root",
            "name": "tool.read_file",
            "kind": "INTERNAL",
            "start_time": start,
            "end_time": None,
            "session_id": "sess1",
            "attributes": {"path": f"/app/skills/{skill}/SKILL.md"},
        }

    spans = [
        rf("s1", "customer_confirm_skill", "2026-08-17T00:00:00+00:00"),
        rf("s2", "multi_skill", "2026-08-17T00:00:01+00:00"),
        _span("s3", "tool.ask_user", parent="root", start="2026-08-17T00:00:02+00:00"),
        _span("s4", "tool.lite_todo_write", parent="root", start="2026-08-17T00:00:03+00:00"),
        _span("s5", "tool.call_multiagent", parent="root", start="2026-08-17T00:00:04+00:00"),
    ]
    result = mapper.infer(spans)
    assert result["s3"].skill == "customer_confirm_skill"  # 认领者换岗
    assert result["s4"].skill == "multi_skill"  # 接班: 下一个未转正候选
    assert result["s5"].skill == "multi_skill"  # 之后不再换人


def test_l2_read_file_commit_read_order_and_unused_clean():
    """读多个候选后: 通用执行动作按读序 commit 未转正候选; 读了未用的 skill 不错染。"""
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(
                kind="read_file_skill",
                tool_name="read_file",
                path_field="path",
            ),
        ],
        prose_matching=ProseMatchingConfig(enabled=False),
        fallback_skill="Agent.md",
    )
    mapper = SkillAttributionMapper(
        cfg, {"a_skill", "b_skill"}, SkillToolTable(owns={}, forbids={})
    )

    def rf(span_id: str, skill: str, start: str) -> dict:
        return {
            "trace_id": "tr1",
            "span_id": span_id,
            "parent_span_id": "root",
            "name": "tool.read_file",
            "kind": "INTERNAL",
            "start_time": start,
            "end_time": None,
            "session_id": "sess1",
            "attributes": {"path": f"/app/skills/{skill}/SKILL.md"},
        }

    spans = [
        rf("s1", "a_skill", "2026-08-17T00:00:00+00:00"),
        _span("s2", "llm.chat", parent="root", start="2026-08-17T00:00:01+00:00"),
        rf("s3", "b_skill", "2026-08-17T00:00:02+00:00"),
        _span("s4", "tool.call_versatile", parent="root", start="2026-08-17T00:00:03+00:00"),
        _span("s5", "llm.chat", parent="root", start="2026-08-17T00:00:04+00:00"),
    ]
    result = mapper.infer(spans)
    # 审视 (llm) 不触发 commit
    assert result["s2"].skill == "Agent.md"
    assert result["s2"].source == SOURCE_RESIDUAL
    # 通用动作 commit 最早读的候选 a_skill; 之后 last-wins 持续
    assert result["s4"].skill == "a_skill"
    assert result["s4"].source == SOURCE_ACTIVE_CONTEXT
    assert result["s5"].skill == "a_skill"
    # b_skill 读了没等到自己的执行动作 → 全程无归属 span
    assert all(r.skill != "b_skill" for r in result.values())


def test_l2_commit_owned_tool_picks_owner():
    """区分性动作 (工具被候选文档 own 认领) → commit 认领者而非最早读的 (ask_user→confirm)。"""
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(
                kind="read_file_skill",
                tool_name="read_file",
                path_field="path",
            ),
        ],
        prose_matching=ProseMatchingConfig(enabled=True),
        fallback_skill="Agent.md",
    )
    # multi 先读, confirm 后读; confirm 文档 own ask_user
    table = SkillToolTable(owns={"customer_confirm_skill": frozenset({"ask_user"})}, forbids={})
    mapper = SkillAttributionMapper(cfg, {"multi_skill", "customer_confirm_skill"}, table)

    def rf(span_id: str, skill: str, start: str) -> dict:
        return {
            "trace_id": "tr1",
            "span_id": span_id,
            "parent_span_id": "root",
            "name": "tool.read_file",
            "kind": "INTERNAL",
            "start_time": start,
            "end_time": None,
            "session_id": "sess1",
            "attributes": {"path": f"/app/skills/{skill}/SKILL.md"},
        }

    spans = [
        rf("s1", "multi_skill", "2026-08-17T00:00:00+00:00"),
        rf("s2", "customer_confirm_skill", "2026-08-17T00:00:01+00:00"),
        _span("s3", "tool.ask_user", parent="root", start="2026-08-17T00:00:02+00:00"),
        _span("s4", "tool.lite_todo_write", parent="root", start="2026-08-17T00:00:03+00:00"),
        _span("s5", "llm.chat", parent="root", start="2026-08-17T00:00:04+00:00"),
    ]
    result = mapper.infer(spans)
    # ask_user 被 confirm 认领 → commit confirm (尽管 multi 先读)
    assert result["s3"].skill == "customer_confirm_skill"
    assert result["s3"].source == SOURCE_ACTIVE_CONTEXT
    # 之后的通用动作 → 下一个未转正候选 multi
    assert result["s4"].skill == "multi_skill"
    assert result["s4"].source == SOURCE_ACTIVE_CONTEXT
    assert result["s5"].skill == "multi_skill"


def test_read_file_path_prefix_agnostic():
    """read_file 路径前缀无关: 不同布局都能从 <skill>/SKILL.md 提 skill 名 (进选型 candidates)。"""
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(
                kind="read_file_skill",
                tool_name="read_file",
                path_field="path",
            ),
        ],
        prose_matching=ProseMatchingConfig(enabled=False),
        fallback_skill="Agent.md",
    )
    mapper = SkillAttributionMapper(cfg, {"my_skill"}, SkillToolTable(owns={}, forbids={}))
    # 裸串路径, 不同前缀
    rf = {
        "trace_id": "tr1",
        "span_id": "s1",
        "parent_span_id": "root",
        "name": "tool.read_file",
        "kind": "INTERNAL",
        "start_time": _T0,
        "end_time": None,
        "session_id": "sess1",
        "attributes": {"path": "/totally/different/root/my_skill/SKILL.md"},
    }
    spans = [
        rf,
        _span("s2", "llm.chat", parent="root", start=_T1),
        _span("s3", "tool.execute_cmd", parent="root", start="2026-08-17T00:00:02+00:00"),
    ]
    result = mapper.infer(spans)
    assert result["s1"].source == SOURCE_SKILL_SELECTION
    assert result["s1"].candidates == ["my_skill"]
    # llm 审视不触发 commit; 首个 tool 执行 (s3) 才激活
    assert result["s2"].skill == "Agent.md"
    assert result["s3"].skill == "my_skill"
    assert result["s3"].source == SOURCE_ACTIVE_CONTEXT


def test_read_file_non_skill_path_ignored():
    """read_file 读的不是 SKILL.md(或 skill 名不在已知集) → 不激活, 落 L4。"""
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(
                kind="read_file_skill",
                tool_name="read_file",
                path_field="path",
            ),
        ],
        prose_matching=ProseMatchingConfig(enabled=False),
        fallback_skill="Agent.md",
    )
    mapper = SkillAttributionMapper(cfg, {"my_skill"}, SkillToolTable(owns={}, forbids={}))
    rf = {
        "trace_id": "tr1",
        "span_id": "s1",
        "parent_span_id": "root",
        "name": "tool.read_file",
        "kind": "INTERNAL",
        "start_time": _T0,
        "end_time": None,
        "session_id": "sess1",
        "attributes": {"path": "/etc/hosts"},  # 非 SKILL.md
    }
    spans = [rf, _span("s2", "llm.chat", parent="root", start=_T1)]
    result = mapper.infer(spans)
    assert result["s2"].skill == "Agent.md"
    assert result["s2"].source == SOURCE_RESIDUAL


def test_l3_tool_name_match_single_owner():
    table = SkillToolTable(owns={"rec": frozenset({"sample_tool_1"})}, forbids={})
    mapper = _mapper({"rec"}, table)
    spans = [_span("s1", "tool.sample_tool_1")]
    result = mapper.infer(spans)
    assert result["s1"].skill == "rec"
    assert result["s1"].source == SOURCE_TOOL_NAME_MATCH
    assert result["s1"].confidence == pytest.approx(0.7)


def test_l3_tool_name_match_multiple_owners_candidates():
    table = SkillToolTable(
        owns={"a": frozenset({"tool_x"}), "b": frozenset({"tool_x"})}, forbids={}
    )
    mapper = _mapper({"a", "b"}, table)
    spans = [_span("s1", "tool.tool_x")]
    result = mapper.infer(spans)
    assert result["s1"].skill == ""
    assert result["s1"].candidates == ["a", "b"]
    assert result["s1"].source == SOURCE_TOOL_NAME_MATCH


def test_l4_residual_fallback():
    mapper = _mapper(set(), SkillToolTable(owns={}, forbids={}))
    spans = [_span("s1", "llm.chat")]
    result = mapper.infer(spans)
    assert result["s1"].skill == "Agent.md"
    assert result["s1"].source == SOURCE_RESIDUAL


# --- L0 ingress: 传输层/入口 + 空名 tool. 空壳 ---


def test_l0_ingress_http_request():
    mapper = _mapper({"rec"}, SkillToolTable(owns={}, forbids={}))
    spans = [
        _span("s1", "http.request", parent="", start=_T0),
        _span("s2", "skill.rec", parent="s1", start=_T1),
    ]
    result = mapper.infer(spans)
    assert result["s1"].skill == INGRESS_SKILL
    assert result["s1"].source == SOURCE_INGRESS
    assert result["s1"].confidence == pytest.approx(1.0)


def test_l0_ingress_empty_tool_shell():
    """空名 tool. (EDPAgent 空壳 span) → ingress, 不落 residual。"""
    mapper = _mapper(set(), SkillToolTable(owns={}, forbids={}))
    spans = [_span("s1", "tool.")]
    result = mapper.infer(spans)
    assert result["s1"].skill == INGRESS_SKILL
    assert result["s1"].source == SOURCE_INGRESS


def test_l0_ingress_span_names_configurable():
    """ingress_span_names 可 per-agent 覆写。"""
    cfg = AttributionConfig(ingress_span_names=["gateway.entry"])
    mapper = SkillAttributionMapper(cfg, set(), SkillToolTable(owns={}, forbids={}))
    spans = [_span("s1", "gateway.entry"), _span("s2", "http.request", start=_T1)]
    result = mapper.infer(spans)
    assert result["s1"].source == SOURCE_INGRESS
    assert result["s2"].source == SOURCE_RESIDUAL  # 覆写后 http.request 不再是 ingress


# --- session 级激活携带 (context_spans) ---


def test_session_context_carries_activation():
    """前轮 trace 的 read_file+commit 经 context_spans 携带 → 本 trace 无激活事件也归该 skill。"""
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(kind="read_file_skill", tool_name="read_file", path_field="path")
        ],
        prose_matching=ProseMatchingConfig(enabled=False),
        fallback_skill="Agent.md",
    )
    mapper = SkillAttributionMapper(cfg, {"rec_skill"}, SkillToolTable(owns={}, forbids={}))
    ctx = [
        {
            "trace_id": "tr0",
            "span_id": "c1",
            "parent_span_id": "",
            "name": "tool.read_file",
            "kind": "INTERNAL",
            "start_time": "2026-08-17T00:00:00+00:00",
            "end_time": None,
            "session_id": "sess1",
            "attributes": {"path": "/app/skills/rec_skill/SKILL.md"},
        },
        {  # 前轮 commit 动作
            "trace_id": "tr0",
            "span_id": "c2",
            "parent_span_id": "",
            "name": "tool.call_versatile",
            "kind": "INTERNAL",
            "start_time": "2026-08-17T00:00:01+00:00",
            "end_time": None,
            "session_id": "sess1",
            "attributes": {},
        },
    ]
    spans = [_span("s1", "llm.chat", parent="", start="2026-08-17T00:01:00+00:00")]
    result = mapper.infer(spans, context_spans=ctx)
    assert result["s1"].skill == "rec_skill"
    assert result["s1"].source == SOURCE_ACTIVE_CONTEXT
    # context spans 自身不产出归属
    assert set(result) == {"s1"}


def test_session_context_without_commit_no_carry():
    """前轮只读未执行 (选型未 commit) → 不携带, 本 trace 落 residual。"""
    cfg = AttributionConfig(
        recognizers=[
            RecognizerConfig(kind="read_file_skill", tool_name="read_file", path_field="path")
        ],
        prose_matching=ProseMatchingConfig(enabled=False),
        fallback_skill="Agent.md",
    )
    mapper = SkillAttributionMapper(cfg, {"rec_skill"}, SkillToolTable(owns={}, forbids={}))
    ctx = [
        {
            "trace_id": "tr0",
            "span_id": "c1",
            "parent_span_id": "",
            "name": "tool.read_file",
            "kind": "INTERNAL",
            "start_time": "2026-08-17T00:00:00+00:00",
            "end_time": None,
            "session_id": "sess1",
            "attributes": {"path": "/app/skills/rec_skill/SKILL.md"},
        }
    ]
    spans = [_span("s1", "llm.chat", parent="", start="2026-08-17T00:01:00+00:00")]
    result = mapper.infer(spans, context_spans=ctx)
    assert result["s1"].skill == "Agent.md"
    assert result["s1"].source == SOURCE_RESIDUAL


def test_infer_empty_returns_empty():
    mapper = _mapper(set(), SkillToolTable(owns={}, forbids={}))
    assert mapper.infer([]) == {}


def test_attribution_to_dict_shape():
    attr = Attribution(skill="rec", source="parent_skill_span", confidence=1.0)
    assert attr.to_dict() == {
        "skill": "rec",
        "source": "parent_skill_span",
        "confidence": 1.0,
        "candidates": [],
        "misuse": False,
    }


# --- spans_to_records 透传 attribution + parent_span_id ---


def test_spans_to_records_passes_attribution_and_parent():
    spans = [
        {
            "trace_id": "tr1",
            "span_id": "s1",
            "parent_span_id": "",
            "name": "http.request",
            "kind": "SERVER",
            "start_time": _T0,
            "end_time": None,
            "session_id": "sess1",
            "attributes": {},
            "attribution": {"skill": "Agent.md", "source": "residual", "confidence": 0.5},
        },
        {
            "trace_id": "tr1",
            "span_id": "s2",
            "parent_span_id": "s1",
            "name": "tool.sample_tool_1",
            "kind": "INTERNAL",
            "start_time": _T1,
            "end_time": None,
            "session_id": "sess1",
            "attributes": {},
            "attribution": {"skill": "rec", "source": "parent_skill_span", "confidence": 1.0},
        },
    ]
    records = spans_to_records(spans)
    trace_rec = next(r for r in records if r.get("id") == "tr1")
    assert trace_rec["parent_span_id"] == ""
    assert trace_rec["attribution"]["skill"] == "Agent.md"
    tool_rec = next(r for r in records if r.get("type") == "TOOL")
    assert tool_rec["parent_span_id"] == "s1"
    assert tool_rec["attribution"]["skill"] == "rec"


def test_spans_to_records_null_attribution_carried():
    spans = [
        {
            "trace_id": "tr1",
            "span_id": "s1",
            "parent_span_id": "",
            "name": "tool.x",
            "kind": "INTERNAL",
            "start_time": _T0,
            "end_time": None,
            "session_id": "sess1",
            "attributes": {},
            "attribution": None,
        }
    ]
    records = spans_to_records(spans)
    assert records[0]["attribution"] is None
    assert records[0]["parent_span_id"] == ""


# --- AttributionRunner (fake repo + skill_store, 不依赖 asyncpg/kafka) ---


class _FakeRepo:
    def __init__(self, pending, spans_by_trace):
        self._pending = pending
        self._spans = spans_by_trace
        self.updated: dict[str, dict] = {}

    async def list_unattributed_completed_traces(self):
        return self._pending

    async def get_spans_by_trace(self, trace_id):
        return self._spans.get(trace_id, [])

    async def get_spans_by_session(self, session_id):
        return [s for spans in self._spans.values() for s in spans]

    async def update_span_attribution(self, trace_id, attributions):
        self.updated[trace_id] = attributions
        return len(attributions)


class _FakeSkillStore:
    def __init__(self, docs):
        self._docs = docs

    def list_skills(self, agent_name):
        return [SkillSummary(name=n) for n in self._docs]

    def read_skill(self, agent_name, skill_name):
        return SkillContent(skill_name=skill_name, content=self._docs[skill_name], revision="r")


async def test_runner_attributes_trace_writes_back():
    cfg = AdapterConfig(
        attribution_runner_enabled=True,
        attribution_poll_interval=0.01,
        agents=[
            AgentEntryConfig(
                name="edp",
                attribution=AttributionConfig(
                    recognizers=[RecognizerConfig(kind="skill_span")],
                    prose_matching=ProseMatchingConfig(enabled=True),
                    fallback_skill="Agent.md",
                ),
            )
        ],
    )
    spans = [
        _span("s1", "skill.rec", parent="", start=_T0),
        _span("s2", "tool.sample_tool_1", parent="s1", start=_T1),
    ]
    repo = _FakeRepo(
        pending=[{"trace_id": "tr1", "session_id": "sess1", "service_name": "edp"}],
        spans_by_trace={"tr1": spans},
    )
    store = _FakeSkillStore({"rec": "使用 sample_tool_1。"})
    runner = AttributionRunner(repo, store, cfg)
    await runner.attribute_trace({"trace_id": "tr1", "session_id": "sess1", "service_name": "edp"})
    assert "tr1" in repo.updated
    assert repo.updated["tr1"]["s2"]["skill"] == "rec"
    assert repo.updated["tr1"]["s2"]["source"] == SOURCE_PARENT_SKILL_SPAN


async def test_runner_skips_unknown_agent():
    cfg = AdapterConfig(attribution_runner_enabled=True, agents=[])
    repo = _FakeRepo(pending=[], spans_by_trace={})
    runner = AttributionRunner(repo, _FakeSkillStore({}), cfg)
    await runner.attribute_trace(
        {"trace_id": "tr1", "session_id": "sess1", "service_name": "ghost"}
    )
    assert repo.updated == {}
