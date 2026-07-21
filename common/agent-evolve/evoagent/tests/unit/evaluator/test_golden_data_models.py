"""golden_data models 单测 —— EB/GU 模型字段 + to_external 对外口径裁剪。"""

from __future__ import annotations

from evo_agent.evaluator.domain.models import StandardTrajectory, TrajectoryMessage
from evo_agent.evaluator.golden_data.models import (
    EBInput,
    ExpectedBehaviorItem,
    ExpectedBehaviorOutput,
    GUIndex,
    GUOutScope,
    GUSkillDoc,
    GUSlice,
    GUSystemWide,
)


def test_to_external_trims_to_three_fields() -> None:
    out = ExpectedBehaviorOutput(
        items=[
            ExpectedBehaviorItem(
                id="eb_1",
                inputs="i",
                expected_behavior="eb",
                result="通过",
                reason="r",
                scenario="s",
                score=0.9,
                scope="x",
                should=["a"],
                should_not=["b"],
                reference=["c"],
            )
        ],
        metadata={"k": "v"},
    )
    ext = out.to_external()
    assert len(ext) == 1
    assert set(ext[0].keys()) == {"id", "inputs", "expected_behavior"}
    assert ext[0] == {"id": "eb_1", "inputs": "i", "expected_behavior": "eb"}
    # 备查字段（result/reason/scenario/score）不进对外口径
    assert "result" not in ext[0] and "score" not in ext[0]


def test_item_defaults() -> None:
    it = ExpectedBehaviorItem(id="x", inputs="i", expected_behavior="eb")
    assert it.result == "NA"
    assert it.score is None
    assert it.should == [] and it.should_not == [] and it.reference == []
    assert it.scope == "" and it.reason == "" and it.scenario == ""


def test_guindex_defaults() -> None:
    idx = GUIndex()
    assert idx.mode == "progressive"
    assert idx.skills == [] and idx.last_run_id == "" and idx.out_of_scope_count == 0


def test_guindex_mode_literal() -> None:
    assert GUIndex(mode="flat").mode == "flat"
    assert GUIndex(mode="progressive").mode == "progressive"


def test_guslice_fields() -> None:
    s = GUSlice(system_wide="sw", per_skill={"a": "doc"}, is_out_of_scope=True)
    assert s.system_wide == "sw" and s.per_skill == {"a": "doc"} and s.is_out_of_scope


def test_ebinput() -> None:
    traj = StandardTrajectory(messages=[TrajectoryMessage(role="user", content="hi")])
    inp = EBInput(trajectory=traj, gu_slice=GUSlice(), attributed_skill="x")
    assert inp.attributed_skill == "x"
    assert inp.gu_slice.system_wide == ""


def test_gu_doc_carriers() -> None:
    assert GUSkillDoc(content="x").content == "x"
    assert GUSystemWide(content="y").content == "y"
    assert GUOutScope(content="z").content == "z"
