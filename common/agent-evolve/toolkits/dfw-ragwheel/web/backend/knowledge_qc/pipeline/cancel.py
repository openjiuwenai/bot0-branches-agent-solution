from __future__ import annotations

from typing import Callable, Optional

ShouldCancel = Optional[Callable[[], bool]]


def is_cancelled(should_cancel: ShouldCancel) -> bool:
    return bool(should_cancel and should_cancel())
