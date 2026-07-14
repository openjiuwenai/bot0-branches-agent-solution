"""Unit tests for HealthWatcher E3 state machine (T8)."""

from __future__ import annotations

from agent_adapter.managed_doc.health import HealthWatcher


class FakeResponse:
    def __init__(self, status_code: int) -> None:
        self.status_code = status_code


class FakeClient:
    """Scripted httpx-like client: yields status codes (or raises exceptions)."""

    def __init__(self, statuses: list[int | Exception]) -> None:
        self._statuses = statuses
        self._idx = 0
        self.calls = 0

    async def get(self, url: str, timeout: float | None = None) -> FakeResponse:
        self.calls += 1
        s = self._statuses[min(self._idx, len(self._statuses) - 1)]
        self._idx += 1
        if isinstance(s, Exception):
            raise s
        return FakeResponse(s)


class FakeClock:
    """Deterministic clock + sleep for E3 timeout tests."""

    def __init__(self) -> None:
        self._t = 0.0

    def now(self) -> float:
        return self._t

    async def sleep(self, seconds: float) -> None:
        self._t += seconds


def _watcher(
    client: FakeClient,
    *,
    down_timeout: float = 0.3,
    up_timeout: float = 0.3,
    up_consecutive: int = 2,
    poll_interval: float = 0.1,
    clock: FakeClock | None = None,
) -> HealthWatcher:
    c = clock or FakeClock()
    return HealthWatcher(
        client=client,
        health_url="http://localhost/health",
        down_timeout=down_timeout,
        up_timeout=up_timeout,
        up_consecutive=up_consecutive,
        poll_interval=poll_interval,
        now=c.now,
        sleep=c.sleep,
    )


# ── AC8.1 down→up → 成功，down_seen=True ────────────────────────────


async def test_down_then_up_succeeds_down_seen_true() -> None:
    client = FakeClient([503, 200, 200])
    watcher = _watcher(client, up_consecutive=2)
    ok, down_seen = await watcher.watch()
    assert ok is True
    assert down_seen is True


async def test_connection_refused_counts_as_down() -> None:
    import httpx

    client = FakeClient([httpx.ConnectError("refused"), 200, 200])
    watcher = _watcher(client, up_consecutive=2)
    ok, down_seen = await watcher.watch()
    assert ok is True
    assert down_seen is True


# ── AC8.2 恒 200（未采到 down）→ 降级 warn + down_seen=False + 通过 ────


async def test_always_200_degrades_down_seen_false() -> None:
    client = FakeClient([200])
    watcher = _watcher(client, up_consecutive=2, down_timeout=0.3, poll_interval=0.1)
    ok, down_seen = await watcher.watch()
    assert ok is True
    assert down_seen is False


# ── AC8.3 恒 503 → 阶段2 超时失败 ────────────────────────────────────


async def test_always_503_fails_after_up_timeout() -> None:
    client = FakeClient([503])
    watcher = _watcher(client, up_consecutive=2, up_timeout=0.3, poll_interval=0.1)
    ok, down_seen = await watcher.watch()
    assert ok is False
    assert down_seen is True


# ── 边界：up 稳定窗口在间歇 down 后重置 ─────────────────────────────


async def test_up_consecutive_resets_on_intermittent_down() -> None:
    # down→up→down→up,up：需 consecutive=2，中间 down 重置计数
    client = FakeClient([503, 200, 503, 200, 200])
    watcher = _watcher(client, up_consecutive=2, up_timeout=10.0)
    ok, down_seen = await watcher.watch()
    assert ok is True
    assert down_seen is True


async def test_up_timeout_during_intermittent_failure() -> None:
    # up 永远不稳定（200/503 交替）→ up_timeout 触发失败
    client = FakeClient([503, 200, 503, 200, 503, 200])
    watcher = _watcher(client, up_consecutive=3, up_timeout=0.3, poll_interval=0.1)
    ok, down_seen = await watcher.watch()
    assert ok is False
    assert down_seen is True


# ── down 阶段超时（恒 up 但未观察到 down）后仍须走 up 稳定窗口 ──────


async def test_down_phase_timeout_then_up_stable() -> None:
    # 恒 200：down 阶段超时→down_seen=False；up 阶段 2 连续→成功
    client = FakeClient([200])
    watcher = _watcher(
        client,
        up_consecutive=2,
        down_timeout=0.2,
        up_timeout=1.0,
        poll_interval=0.1,
    )
    ok, down_seen = await watcher.watch()
    assert ok is True
    assert down_seen is False
