"""ConversationIdFactory — 生成唯一的 Adapter conversation_id。"""

from __future__ import annotations

import itertools


class ConversationIdFactory:
    """生成唯一的 Adapter conversation_id。

    格式: ``{run_id}:{phase}:{counter}:{case_id}``

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
        return f"{self._run_id}:{phase}:{count}:{case_id}"
