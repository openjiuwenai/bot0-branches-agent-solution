"""SSE 格式化工具函数。"""

from __future__ import annotations

import json

from evo_agent.api.events import SSEEvent


def format_sse(event: SSEEvent) -> str:
    """将 SSEEvent 格式化为 SSE 文本。

    输出格式::

        id: 1
        event: progress
        data: {"epoch": 1}

    末尾以 ``\\n\\n`` 分隔。
    """
    data_json = json.dumps(event.data, ensure_ascii=False)
    return f"id: {event.id}\nevent: {event.event}\ndata: {data_json}\n\n"
