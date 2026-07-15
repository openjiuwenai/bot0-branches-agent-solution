"""Callback 组合顺序正确性测试。

验证 build_callbacks() 的两步顺序约束：
1. SkillDocumentCallbacks — 执行 run_epoch_end()（slow_update + meta_skill）
2. ProgressCallback — 采集进度

skill_sync_callback 已在 Wave 3 中移除（由 operator factory 的
on_parameter_updated callback 替代）。
"""

from __future__ import annotations

import inspect
from unittest.mock import MagicMock

from openjiuwen.agent_evolving.trainer.progress import Callbacks

from evo_agent.callbacks import ComposedCallbacks, SkillDocumentCallbacks, build_callbacks


def test_build_callbacks_two_steps() -> None:
    """ComposedCallbacks 包含 SkillDocumentCallbacks + ProgressCallback。"""
    optimizer = MagicMock()
    progress_cb = MagicMock(spec=Callbacks)

    result = build_callbacks(optimizer, progress_callback=progress_cb)

    assert isinstance(result, ComposedCallbacks)
    assert len(result._callbacks) == 2
    assert isinstance(result._callbacks[0], SkillDocumentCallbacks)
    assert result._callbacks[1] is progress_cb


def test_build_callbacks_no_progress() -> None:
    """无 progress_callback 时仅包含 SkillDocumentCallbacks。"""
    optimizer = MagicMock()

    result = build_callbacks(optimizer)

    assert isinstance(result, ComposedCallbacks)
    assert len(result._callbacks) == 1
    assert isinstance(result._callbacks[0], SkillDocumentCallbacks)


def test_skill_document_callbacks_always_first() -> None:
    """SkillDocumentCallbacks 始终在第一位。"""
    optimizer = MagicMock()
    progress_cb = MagicMock(spec=Callbacks)

    result = build_callbacks(optimizer, progress_callback=progress_cb)

    assert isinstance(result._callbacks[0], SkillDocumentCallbacks)


def test_build_callbacks_no_skill_sync_param() -> None:
    """build_callbacks() 签名中不存在 skill_sync_callback 参数。

    Wave 3 中 skill 回写由 operator factory 的 on_parameter_updated
    callback (AdapterClient.update_skill) 承担，不再需要独立的
    skill_sync_callback。
    """
    sig = inspect.signature(build_callbacks)
    assert "skill_sync_callback" not in sig.parameters
