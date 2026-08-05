"""A7 (#6): comparison_text 复用 — 单元测试。

run_epoch_end 应只调用一次 build_comparison_text(prev, curr)，
复用结果传给 _run_slow_update + _run_meta_skill（原来各调一次 = 2 次）。
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)


def _make_optimizer() -> SkillDocumentOptimizer:
    """绕过 __init__，构造最小 run_epoch_end 可用实例。"""
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)  # type: ignore[no-untyped-call]
    opt._operators = {"op1": MagicMock()}
    opt._use_slow_update = True
    opt._use_meta_skill = True
    opt._prev_epoch_comparison = [{"case_id": "c1", "prev": "a", "curr": "b"}]
    opt._curr_epoch_comparison = []
    opt._prev_epoch_skill_by_operator = {"op1": "prev skill"}
    opt._current_skill_by_operator = {"op1": "curr skill"}
    opt._current_skill_content = "curr"
    opt._prev_epoch_skill = "prev"
    opt._step_buffer = []
    opt._artifact_exporter = MagicMock()
    opt._llm = MagicMock()
    opt._model = "m"
    opt._meta_skill_context = ""
    # 内部辅助方法替换为 mock
    opt._read_skills_from_operators = MagicMock(return_value={"op1": "curr skill"})  # type: ignore[method-assign]
    opt._mean_eval_score = MagicMock(return_value=0.5)  # type: ignore[method-assign]
    opt._infer_gate_decision = MagicMock(return_value="candidate")  # type: ignore[method-assign]
    opt._sync_skill_to_operator = MagicMock()  # type: ignore[method-assign]
    opt._sync_skill_to_operator_by_id = MagicMock()  # type: ignore[method-assign]
    opt._format_operator_skills = MagicMock(return_value="fmt")  # type: ignore[method-assign]
    return opt


def _patches() -> tuple:
    return (
        patch(
            "evo_agent.optimizer.skill_document.slow_update.build_comparison_text",
            return_value="CMP",
        ),
        patch(
            "evo_agent.optimizer.skill_document.slow_update.run_slow_update",
            new_callable=AsyncMock,
            return_value=MagicMock(slow_update_content=""),
        ),
        patch(
            "evo_agent.optimizer.skill_document.meta_skill.run_meta_skill",
            new_callable=AsyncMock,
            return_value="",
        ),
        patch(
            "evo_agent.optimizer.skill_document.edit_apply.extract_slow_update_content",
            return_value="",
        ),
    )


async def test_run_epoch_end_builds_comparison_text_once() -> None:
    """两阶段都开启时，build_comparison_text 每 epoch 仅调用一次。"""
    opt = _make_optimizer()
    p_cmp, p_slow, p_meta, p_extract = _patches()
    with p_cmp as cmp, p_slow, p_meta, p_extract:
        await opt.run_epoch_end(trainer_epoch=1, val_results=[])
    assert cmp.call_count == 1


async def test_run_epoch_end_no_comparison_at_epoch_zero() -> None:
    """epoch 0 不触发 slow/meta，build_comparison_text 不被调用。"""
    opt = _make_optimizer()
    p_cmp, p_slow, p_meta, p_extract = _patches()
    with p_cmp as cmp, p_slow, p_meta, p_extract:
        await opt.run_epoch_end(trainer_epoch=0, val_results=[])
    assert cmp.call_count == 0


async def test_run_epoch_end_only_slow_update_still_one_call() -> None:
    """仅 slow_update 开启时也只调用一次。"""
    opt = _make_optimizer()
    opt._use_meta_skill = False
    p_cmp, p_slow, p_meta, p_extract = _patches()
    with p_cmp as cmp, p_slow, p_meta, p_extract:
        await opt.run_epoch_end(trainer_epoch=1, val_results=[])
    assert cmp.call_count == 1


async def test_run_epoch_end_only_meta_skill_still_one_call() -> None:
    """仅 meta_skill 开启时也只调用一次。"""
    opt = _make_optimizer()
    opt._use_slow_update = False
    p_cmp, p_slow, p_meta, p_extract = _patches()
    with p_cmp as cmp, p_slow, p_meta, p_extract:
        await opt.run_epoch_end(trainer_epoch=1, val_results=[])
    assert cmp.call_count == 1
