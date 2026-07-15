"""_llm_skill_view + LLM 注入点 frontmatter strip 单元测试 (F1+F2, 方案 A)。

覆盖 AC：
- preserve=True: _reflect/_aggregate/_select/run_slow_update/run_meta_skill 收到 body-only
- preserve=False: 上述参数为全文
- apply_patch_with_report 在两种开关下都收到全文 skill_content（写回不变式）
"""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from evo_agent.optimizer.skill_document import skill_document_optimizer as sdo_mod
from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)
from evo_agent.optimizer.skill_document.types import Edit, Patch, RawPatch

_SKILL_WITH_FM = "---\nname: demo\ndescription: x\n---\n\n# Title\nbody\n"
_BODY_ONLY = "\n# Title\nbody\n"
_FM_MARKER = "---\nname: demo"


def _new_optimizer(preserve: bool) -> SkillDocumentOptimizer:
    """__new__ 绕过 __init__，仅设包裹逻辑所需属性。"""
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)  # type: ignore[no-untyped-call]
    opt._preserve_frontmatter = preserve
    # logger.info 在编排方法中读 _current_epoch，spy 路径需提供。
    opt._current_epoch = 0
    # _run_slow_update / _run_meta_skill 把 self._llm / self._model 传给 LLM 函数
    # （已被 monkeypatch 为 AsyncMock），spy 路径需提供占位实例。
    opt._llm = MagicMock()
    opt._model = "m"
    return opt


# ── _llm_skill_view 纯函数 ──


class TestLlmSkillViewPure:
    def test_preserve_true_strips_frontmatter(self) -> None:
        opt = _new_optimizer(preserve=True)
        assert opt._llm_skill_view(_SKILL_WITH_FM) == _BODY_ONLY

    def test_preserve_false_returns_full(self) -> None:
        opt = _new_optimizer(preserve=False)
        assert opt._llm_skill_view(_SKILL_WITH_FM) == _SKILL_WITH_FM

    def test_no_frontmatter_passthrough(self) -> None:
        opt = _new_optimizer(preserve=True)
        content = "# No frontmatter\nbody\n"
        assert opt._llm_skill_view(content) == content

    def test_empty_content(self) -> None:
        opt = _new_optimizer(preserve=True)
        assert opt._llm_skill_view("") == ""


# ── __init__ 接线 ──


def _construct(preserve: bool) -> SkillDocumentOptimizer:
    """走真实 __init__，验证 preserve_frontmatter kwarg 接线到 self._preserve_frontmatter。"""
    train_cases = MagicMock()
    train_cases.get_cases.return_value = []
    return SkillDocumentOptimizer(
        agent=MagicMock(),
        evaluator=MagicMock(),
        llm=MagicMock(),
        model="m",
        train_cases=train_cases,
        preserve_frontmatter=preserve,
    )


def test_init_preserve_frontmatter_default_true() -> None:
    """__init__ 默认 preserve_frontmatter=True。"""
    train_cases = MagicMock()
    train_cases.get_cases.return_value = []
    opt = SkillDocumentOptimizer(
        agent=MagicMock(),
        evaluator=MagicMock(),
        llm=MagicMock(),
        model="m",
        train_cases=train_cases,
    )
    assert opt._preserve_frontmatter is True


def test_init_preserve_frontmatter_false_wired() -> None:
    """__init__ 接受 preserve_frontmatter=False 并存到 self._preserve_frontmatter。"""
    opt = _construct(preserve=False)
    assert opt._preserve_frontmatter is False


# ── reflect 调用点包裹 ──


def _reflect_spy_optimizer(preserve: bool) -> tuple[SkillDocumentOptimizer, AsyncMock]:
    opt = _new_optimizer(preserve=preserve)
    opt._operators = {"skill_a": MagicMock()}
    opt._current_skill_by_operator = {"skill_a": _SKILL_WITH_FM}
    opt._score_threshold = 0.5
    opt._format_batch = MagicMock(return_value="")
    opt._validate_raw_patch_operator_id = MagicMock(side_effect=lambda p, v: p)
    spy = AsyncMock(return_value=[])
    opt._reflect = spy  # type: ignore[method-assign]
    return opt, spy


@pytest.mark.asyncio
async def test_reflect_call_site_strips_when_preserve_true() -> None:
    opt, spy = _reflect_spy_optimizer(preserve=True)
    await opt._reflect_all_operators({}, step=0, accumulation=0)
    spy.assert_awaited_once()
    assert spy.call_args.kwargs["skill_content"] == _BODY_ONLY


@pytest.mark.asyncio
async def test_reflect_call_site_keeps_full_when_preserve_false() -> None:
    opt, spy = _reflect_spy_optimizer(preserve=False)
    await opt._reflect_all_operators({}, step=0, accumulation=0)
    spy.assert_awaited_once()
    assert spy.call_args.kwargs["skill_content"] == _SKILL_WITH_FM


# ── aggregate / select 调用点包裹 + apply 全文不变式 ──


def _agg_select_spy_optimizer(
    preserve: bool,
) -> tuple[SkillDocumentOptimizer, AsyncMock, AsyncMock]:
    opt = _new_optimizer(preserve=preserve)
    opt._operators = {"skill_a": MagicMock()}
    opt._current_skill_by_operator = {"skill_a": _SKILL_WITH_FM}
    opt._semaphore = asyncio.Semaphore(4)
    opt._artifact_exporter = MagicMock()
    opt._ranked_patch_by_operator = {}
    opt._extract_rejected_edits = MagicMock(return_value=[])
    agg_spy = AsyncMock(return_value=Patch(edits=[], reasoning=""))
    sel_spy = AsyncMock(return_value=[])  # edits=[] → apply 分支不触发
    opt._aggregate = agg_spy  # type: ignore[method-assign]
    opt._select = sel_spy  # type: ignore[method-assign]
    return opt, agg_spy, sel_spy


@pytest.mark.asyncio
async def test_aggregate_select_call_site_strips_when_preserve_true() -> None:
    opt, agg_spy, sel_spy = _agg_select_spy_optimizer(preserve=True)
    raw = RawPatch(patch=Patch(edits=[], reasoning=""), source_type="failure")
    await opt._aggregate_select_apply_all_operators(
        {"skill_a": [raw]}, budget=5, step=0, artifact_epoch=0
    )
    assert agg_spy.call_args.kwargs["skill_content"] == _BODY_ONLY
    assert sel_spy.call_args.kwargs["skill_content"] == _BODY_ONLY


@pytest.mark.asyncio
async def test_aggregate_select_call_site_keeps_full_when_preserve_false() -> None:
    opt, agg_spy, sel_spy = _agg_select_spy_optimizer(preserve=False)
    raw = RawPatch(patch=Patch(edits=[], reasoning=""), source_type="failure")
    await opt._aggregate_select_apply_all_operators(
        {"skill_a": [raw]}, budget=5, step=0, artifact_epoch=0
    )
    assert agg_spy.call_args.kwargs["skill_content"] == _SKILL_WITH_FM
    assert sel_spy.call_args.kwargs["skill_content"] == _SKILL_WITH_FM


@pytest.mark.asyncio
async def test_apply_receives_full_skill_content_in_both_modes() -> None:
    """apply_patch_with_report 收到全文 skill_content（写回不变式），两种开关都成立。"""
    applied: list[str] = []

    def _fake_apply(skill: str, patch: Any) -> tuple[str, list[dict[str, Any]]]:
        applied.append(skill)
        return skill, []

    for preserve in (True, False):
        opt = _new_optimizer(preserve=preserve)
        opt._operators = {"skill_a": MagicMock()}
        opt._current_skill_by_operator = {"skill_a": _SKILL_WITH_FM}
        opt._semaphore = asyncio.Semaphore(4)
        opt._artifact_exporter = MagicMock()
        opt._ranked_patch_by_operator = {}
        opt._extract_rejected_edits = MagicMock(return_value=[])
        opt._aggregate = AsyncMock(  # type: ignore[method-assign]
            return_value=Patch(edits=[Edit(op="append", content="x", target="t")], reasoning="")
        )
        opt._select = AsyncMock(  # type: ignore[method-assign]
            return_value=[Edit(op="append", content="x", target="t")]
        )
        monkeypatch_target = f"{sdo_mod.__name__}.apply_patch_with_report"
        import unittest.mock as _um

        with _um.patch(monkeypatch_target, side_effect=_fake_apply):
            raw = RawPatch(patch=Patch(edits=[], reasoning=""), source_type="failure")
            await opt._aggregate_select_apply_all_operators(
                {"skill_a": [raw]}, budget=5, step=0, artifact_epoch=0
            )
    assert applied == [_SKILL_WITH_FM, _SKILL_WITH_FM]


# ── slow_update 调用点包裹（curr_skill= 参数；extract/replace/写回用全文） ──


def _slow_update_spy_optimizer(preserve: bool, monkeypatch: pytest.MonkeyPatch) -> AsyncMock:
    opt = _new_optimizer(preserve=preserve)
    opt._prev_epoch_comparison = [{}]  # 非空，越过早退
    opt._current_skill_by_operator = {"skill_a": _SKILL_WITH_FM}
    opt._current_skill_content = ""
    opt._prev_epoch_skill_by_operator = {"skill_a": _SKILL_WITH_FM}
    opt._sync_skill_to_operator_by_id = MagicMock()
    spy = AsyncMock(return_value=MagicMock(slow_update_content=""))
    monkeypatch.setattr("evo_agent.optimizer.skill_document.slow_update.run_slow_update", spy)
    # 注入 opt 到测试——返回 spy，调用方持 opt 引用需另行保存
    _slow_update_spy_optimizer._last_opt = opt  # type: ignore[attr-defined]
    return spy


@pytest.mark.asyncio
async def test_slow_update_call_site_strips_curr_skill_when_preserve_true(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    spy = _slow_update_spy_optimizer(preserve=True, monkeypatch=monkeypatch)
    opt = _slow_update_spy_optimizer._last_opt  # type: ignore[attr-defined]
    await opt._run_slow_update(epoch=1, comparison_text="cmp")
    spy.assert_awaited_once()
    assert spy.call_args.kwargs["curr_skill"] == _BODY_ONLY
    assert spy.call_args.kwargs["prev_skill"] == _BODY_ONLY


@pytest.mark.asyncio
async def test_slow_update_call_site_keeps_curr_skill_when_preserve_false(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    spy = _slow_update_spy_optimizer(preserve=False, monkeypatch=monkeypatch)
    opt = _slow_update_spy_optimizer._last_opt  # type: ignore[attr-defined]
    await opt._run_slow_update(epoch=1, comparison_text="cmp")
    spy.assert_awaited_once()
    assert spy.call_args.kwargs["curr_skill"] == _SKILL_WITH_FM
    assert spy.call_args.kwargs["prev_skill"] == _SKILL_WITH_FM


# ── meta_skill 调用点包裹（_format_operator_skills 输入） ──


def _meta_skill_spy_optimizer(
    preserve: bool, monkeypatch: pytest.MonkeyPatch
) -> tuple[SkillDocumentOptimizer, AsyncMock]:
    opt = _new_optimizer(preserve=preserve)
    opt._prev_epoch_skill_by_operator = {"skill_a": _SKILL_WITH_FM}
    opt._current_skill_by_operator = {"skill_a": _SKILL_WITH_FM}
    opt._current_skill_content = ""
    opt._meta_skill_context = ""
    spy = AsyncMock(return_value="")
    monkeypatch.setattr("evo_agent.optimizer.skill_document.meta_skill.run_meta_skill", spy)
    return opt, spy


@pytest.mark.asyncio
async def test_meta_skill_call_site_strips_when_preserve_true(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    opt, spy = _meta_skill_spy_optimizer(preserve=True, monkeypatch=monkeypatch)
    await opt._run_meta_skill(epoch=1, comparison_text="cmp")
    spy.assert_awaited_once()
    prev = spy.call_args.kwargs["prev_skill"]
    curr = spy.call_args.kwargs["curr_skill"]
    assert _FM_MARKER not in prev and "Title" in prev
    assert _FM_MARKER not in curr and "Title" in curr


@pytest.mark.asyncio
async def test_meta_skill_call_site_keeps_full_when_preserve_false(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    opt, spy = _meta_skill_spy_optimizer(preserve=False, monkeypatch=monkeypatch)
    await opt._run_meta_skill(epoch=1, comparison_text="cmp")
    spy.assert_awaited_once()
    assert _FM_MARKER in spy.call_args.kwargs["prev_skill"]
    assert _FM_MARKER in spy.call_args.kwargs["curr_skill"]
