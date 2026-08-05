"""ConversationIdFactory — 生成唯一的 Adapter conversation_id。"""

from __future__ import annotations

import itertools
import re

_UNSUPPORTED_CONVERSATION_ID_CHARACTER = re.compile(r"[^a-zA-Z0-9_-]")


class ConversationIdFactory:
    """生成唯一的 Adapter conversation_id。

    格式: ``{run_id}_{phase}_{counter}_{case_id}``。所有组成部分中的非
    ``[a-zA-Z0-9_-]`` 字符都会替换为下划线，以兼容 Adapter 的 ID 约束。

    Parameters
    ----------
    run_id:
        当前优化运行的唯一 ID。
    """

    def __init__(self, run_id: str) -> None:
        self._run_id = run_id
        self._counter = itertools.count(1)

    def new(self, *, phase: str, case_id: str) -> str:
        """生成一个新的 conversation_id。

        Parameters
        ----------
        phase:
            训练阶段标识：``"train"``、``"val"``、``"candidate"``。
        case_id:
            数据集样本 ID。
        """
        count = next(self._counter)
        conversation_id = f"{self._run_id}_{phase}_{count}_{case_id}"
        return _UNSUPPORTED_CONVERSATION_ID_CHARACTER.sub("_", conversation_id)
