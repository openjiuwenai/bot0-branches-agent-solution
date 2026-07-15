"""Callbacks for EvoAgent training lifecycle.

Re-exports callback classes and provides the ``build_callbacks`` builder.

Callback order constraint:
1. SkillDocumentCallbacks — runs ``run_epoch_end()`` (slow_update + meta_skill)
2. ProgressCallback — collects progress

If the order is wrong, progress reports arrive before skill updates.
"""

from __future__ import annotations

from typing import Any

from openjiuwen.agent_evolving.trainer.progress import Callbacks

from evo_agent.callbacks.composed_callbacks import ComposedCallbacks
from evo_agent.callbacks.console_progress_callback import ConsoleProgressCallback
from evo_agent.callbacks.remote_skill_sync_callback import RemoteSkillSyncCallback
from evo_agent.callbacks.skill_document_callbacks import SkillDocumentCallbacks

__all__ = [
    "ComposedCallbacks",
    "ConsoleProgressCallback",
    "RemoteSkillSyncCallback",
    "SkillDocumentCallbacks",
    "build_callbacks",
]


def build_callbacks(
    optimizer: Any,
    *,
    progress_callback: Callbacks | None = None,
) -> ComposedCallbacks:
    """构建 Trainer 回调，固定顺序为正确性约束。

    Parameters
    ----------
    optimizer:
        SkillDocumentOptimizer 实例，用于构建 SkillDocumentCallbacks。
    progress_callback:
        可选的进度采集回调（如 ProgressCallback）。

    Returns
    -------
    ComposedCallbacks
        组合后的回调，顺序保证：SkillDocumentCallbacks → progress。
    """
    cbs: list[Callbacks] = [SkillDocumentCallbacks(optimizer)]
    if progress_callback is not None:
        cbs.append(progress_callback)
    return ComposedCallbacks(*cbs)
