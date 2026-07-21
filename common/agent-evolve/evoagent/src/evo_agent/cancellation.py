"""Thread-safe cooperative cancellation primitive."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable


class CancellationRequestedError(Exception):
    """Raised only at a cooperative cancellation safe point."""


class CancellationToken:
    """A request latch shared by the API event loop and worker threads."""

    def __init__(self, *, clock: Callable[[], float] = time.monotonic) -> None:
        self._requested = threading.Event()
        self._clock = clock
        self._lock = threading.Lock()
        self._requested_at: float | None = None

    @property
    def is_requested(self) -> bool:
        return self._requested.is_set()

    def request(self) -> None:
        with self._lock:
            if self._requested_at is None:
                self._requested_at = self._clock()
                self._requested.set()

    def remaining_seconds(self, total_deadline: float) -> float:
        """Return the cancellation-recovery budget from the first request."""
        with self._lock:
            requested_at = self._requested_at
        if requested_at is None:
            return max(0.0, total_deadline)
        return max(0.0, total_deadline - (self._clock() - requested_at))

    def raise_if_requested(self) -> None:
        if self._requested.is_set():
            raise CancellationRequestedError("optimization cancellation requested")


__all__ = ["CancellationRequestedError", "CancellationToken"]
