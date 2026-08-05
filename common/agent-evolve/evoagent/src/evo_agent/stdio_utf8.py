"""Windows / 管道环境下的 stdout UTF-8 修复。

Cursor / 多数现代终端按 UTF-8 解码；Windows 默认 Python ``stdout.encoding``
常为 ``gbk``，导致 ``ConsoleProgressCallback`` 等中文 print 显示为乱码。
"""

from __future__ import annotations

import os
import sys

_configured = False


def ensure_utf8_stdio() -> None:
    """将 stdout/stderr 切到 UTF-8（幂等）。

    同时默认开启 ``TQDM_ASCII=1``，避免进度条 Unicode 块在窄编码下花屏。
    """
    global _configured
    if _configured:
        return
    _configured = True

    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    os.environ.setdefault("TQDM_ASCII", "1")

    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if not callable(reconfigure):
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            # 已关闭 / 不支持 reconfigure 的流忽略
            pass
