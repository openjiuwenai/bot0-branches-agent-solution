"""Unit tests for TF-GRPO experience library."""

from __future__ import annotations

from evo_agent.optimizer.tf_grpo.experience_library import (
    ExperienceLibrary,
    LibraryOperation,
)


def test_add_and_prompt_context() -> None:
    lib = ExperienceLibrary(max_experiences=5)
    lib.add("Add concrete usage examples")
    ctx = lib.to_prompt_context()
    assert "已学习经验" in ctx
    assert "Add concrete usage examples" in ctx


def test_trim_keeps_max_experiences() -> None:
    lib = ExperienceLibrary(max_experiences=2)
    lib.add("a", confidence=0.5)
    lib.add("b", confidence=0.9)
    lib.add("c", confidence=0.1)
    assert len(lib.experiences) == 2
    contents = {e.content for e in lib.experiences}
    assert "b" in contents


def test_apply_operations_add_modify_delete() -> None:
    lib = ExperienceLibrary()
    lib.add("vague tip")
    lib.add("keep me")
    log = lib.apply_operations(
        [
            LibraryOperation(operation="Delete", index=0),
            LibraryOperation(operation="Add", content="Add edge-case section"),
            LibraryOperation(operation="Modify", index=0, content="Keep me, but clearer"),
        ]
    )
    assert any("Deleted" in line for line in log)
    assert any("Added" in line for line in log)
    assert len(lib.experiences) >= 1
