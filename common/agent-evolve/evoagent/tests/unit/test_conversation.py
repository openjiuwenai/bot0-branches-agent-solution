"""ConversationIdFactory 单元测试。"""

from __future__ import annotations

import re

from evo_agent.conversation import ConversationIdFactory


def test_id_format() -> None:
    """生成的 ID 格式为 {run_id}_{phase}_{counter}_{case_id}。"""
    factory = ConversationIdFactory(run_id="abc123")
    conv_id = factory.new(phase="train", case_id="case-001")
    assert conv_id == "abc123_train_1_case-001"


def test_id_only_contains_customer_supported_characters() -> None:
    """外部 ID 中的空格、点号和非 ASCII 字符会被替换。"""
    factory = ConversationIdFactory(run_id="run.客户")

    conv_id = factory.new(phase="candidate phase", case_id="客户 case.001")

    assert conv_id == "run____candidate_phase_1____case_001"
    assert re.fullmatch(r"[a-zA-Z0-9_-]+", conv_id)


def test_counter_increments() -> None:
    """counter 在同一 factory 实例内递增。"""
    factory = ConversationIdFactory(run_id="run1")
    id1 = factory.new(phase="train", case_id="case-001")
    id2 = factory.new(phase="train", case_id="case-002")
    id3 = factory.new(phase="val", case_id="case-001")
    assert id1 == "run1_train_1_case-001"
    assert id2 == "run1_train_2_case-002"
    assert id3 == "run1_val_3_case-001"  # counter 跨 phase 递增


def test_uniqueness_across_phases() -> None:
    """不同 phase + 不同 case_id 产生的 ID 互不重复。"""
    factory = ConversationIdFactory(run_id="run1")
    ids: set[str] = set()
    for phase in ("train", "val", "candidate"):
        for case_id in ("case-001", "case-002", "case-003"):
            ids.add(factory.new(phase=phase, case_id=case_id))
    assert len(ids) == 9  # 3 phases × 3 cases = 9 unique IDs


def test_different_run_ids_produce_different_ids() -> None:
    """不同 run_id 的 factory 产生的 ID 不同。"""
    f1 = ConversationIdFactory(run_id="run1")
    f2 = ConversationIdFactory(run_id="run2")
    id1 = f1.new(phase="train", case_id="case-001")
    id2 = f2.new(phase="train", case_id="case-001")
    assert id1 != id2
    assert id1.startswith("run1_")
    assert id2.startswith("run2_")
