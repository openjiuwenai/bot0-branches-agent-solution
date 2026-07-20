"""stdio_utf8.ensure_utf8_stdio 单元测试。"""

from __future__ import annotations

import sys

from evo_agent import stdio_utf8


def test_ensure_utf8_stdio_sets_encoding(monkeypatch: object) -> None:
    """调用后 stdout/stderr encoding 应为 utf-8（支持 reconfigure 时）。"""
    # 允许重复配置，便于本测独立
    monkeypatch.setattr(stdio_utf8, "_configured", False)
    stdio_utf8.ensure_utf8_stdio()
    assert sys.stdout.encoding.lower().replace("-", "") in {"utf8", "utf_8"}
    assert sys.stderr.encoding.lower().replace("-", "") in {"utf8", "utf_8"}


def test_ensure_utf8_stdio_idempotent(monkeypatch: object) -> None:
    monkeypatch.setattr(stdio_utf8, "_configured", False)
    stdio_utf8.ensure_utf8_stdio()
    stdio_utf8.ensure_utf8_stdio()
    assert stdio_utf8._configured is True


def test_console_callback_triggers_utf8() -> None:
    from evo_agent.callbacks.console_progress_callback import ConsoleProgressCallback

    ConsoleProgressCallback()
    assert sys.stdout.encoding.lower().replace("-", "") in {"utf8", "utf_8"}
