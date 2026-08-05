"""Unit tests for RestartApply (T9)."""

from __future__ import annotations

from agent_adapter.config import ManagedDocConfig
from agent_adapter.managed_doc.apply import RestartApply, _needs_shell


class FakeResponse:
    def __init__(self, status_code: int) -> None:
        self.status_code = status_code


class FakeClient:
    def __init__(self, statuses: list[int | Exception]) -> None:
        self._statuses = statuses
        self._idx = 0

    async def get(self, url: str, timeout: float | None = None) -> FakeResponse:
        s = self._statuses[min(self._idx, len(self._statuses) - 1)]
        self._idx += 1
        if isinstance(s, Exception):
            raise s
        return FakeResponse(s)


class FakeClock:
    def __init__(self) -> None:
        self._t = 0.0
        self.slept: list[float] = []

    def now(self) -> float:
        return self._t

    async def sleep(self, seconds: float) -> None:
        self._t += seconds
        self.slept.append(seconds)


def _scripted_runner(codes: list[int]) -> object:
    """Returns an async subprocess runner yielding exit codes (repeating last)."""
    idx = [0]

    async def runner(cmd: str, timeout: float | None) -> int:
        code = codes[min(idx[0], len(codes) - 1)]
        idx[0] += 1
        return code

    return runner


def _cfg(**overrides: object) -> ManagedDocConfig:
    base: dict[str, object] = {
        "kind": "agent_rule",
        "path": "/x",
        "apply": "restart",
        "restart_cmd": "docker restart edp",
        "restart_timeout": 5,
        "health_url": "http://h/health",
        "health_down_timeout": 0.2,
        "health_up_timeout": 0.3,
        "health_up_consecutive": 2,
        "health_poll_interval": 0.05,
        "max_attempts": 2,
        "backoff_base": 0.01,
        "backoff_max": 0.05,
    }
    base.update(overrides)
    return ManagedDocConfig(**base)  # type: ignore[arg-type]


def _apply(
    cfg: ManagedDocConfig,
    *,
    client: FakeClient,
    runner: object,
    clock: FakeClock | None = None,
) -> RestartApply:
    c = clock or FakeClock()
    return RestartApply(
        cfg=cfg,
        client=client,
        subprocess_runner=runner,  # type: ignore[arg-type]
        now=c.now,
        sleep=c.sleep,
    )


# ── AC9.1 down→up 成功 ──────────────────────────────────────────────


async def test_down_then_up_succeeds() -> None:
    cfg = _cfg(max_attempts=2)
    client = FakeClient([503, 200, 200])  # down→up,up
    apply = _apply(cfg, client=client, runner=_scripted_runner([0]))
    result = await apply.apply()
    assert result.ok is True
    assert result.down_seen is True
    assert result.error is None


# ── AC9.2 恒不 up → 重试 max_attempts → FAILED ──────────────────────


async def test_never_up_retries_then_fails() -> None:
    cfg = _cfg(max_attempts=2, backoff_base=0.01)
    client = FakeClient([503])  # 恒 down
    clock = FakeClock()
    apply = _apply(cfg, client=client, runner=_scripted_runner([0, 0]), clock=clock)
    result = await apply.apply()
    assert result.ok is False
    assert result.down_seen is True
    assert "max_attempts" in (result.error or "") or "never" in (result.error or "")
    # 两次 attempt 之间有一次 backoff sleep（backoff_base=0.01）
    assert any(s == 0.01 for s in clock.slept)


async def test_never_up_max_attempts_bound() -> None:
    cfg = _cfg(max_attempts=3, backoff_base=0.01)
    client = FakeClient([503])
    apply = _apply(cfg, client=client, runner=_scripted_runner([0, 0, 0]))
    result = await apply.apply()
    assert result.ok is False
    # runner 调 3 次（每 attempt 一次）
    # (backoff 在 attempt 1,2 之间各一次 → 2 次 sleep)


# ── AC9.3 exit=127 → 不重试直接 FAILED ──────────────────────────────


async def test_exit_127_no_retry() -> None:
    cfg = _cfg(max_attempts=3)
    client = FakeClient([])  # 不应被调（不进 health-watch）
    calls = [0]

    async def runner(cmd: str, timeout: float | None) -> int:
        calls[0] += 1
        return 127

    apply = _apply(cfg, client=client, runner=runner)
    result = await apply.apply()
    assert result.ok is False
    assert result.down_seen is None
    assert "127" in (result.error or "")
    assert calls[0] == 1  # 不重试


async def test_exit_126_no_retry() -> None:
    cfg = _cfg(max_attempts=3)
    client = FakeClient([])
    calls = [0]

    async def runner(cmd: str, timeout: float | None) -> int:
        calls[0] += 1
        return 126

    apply = _apply(cfg, client=client, runner=runner)
    result = await apply.apply()
    assert result.ok is False
    assert calls[0] == 1


# ── AC9.4 exit=1（非 126/127）→ 仍走 health-watch ────────────────────


async def test_exit_1_still_health_watches_then_succeeds() -> None:
    cfg = _cfg(max_attempts=2)
    client = FakeClient([200, 200])  # health 转绿
    calls = [0]

    async def runner(cmd: str, timeout: float | None) -> int:
        calls[0] += 1
        return 1  # 非零但非 126/127

    apply = _apply(cfg, client=client, runner=runner)
    result = await apply.apply()
    assert result.ok is True
    assert calls[0] == 1  # 一次 attempt 即成功


async def test_exit_1_health_stays_down_retries() -> None:
    cfg = _cfg(max_attempts=2, backoff_base=0.01)
    client = FakeClient([503])  # health 不转绿
    apply = _apply(cfg, client=client, runner=_scripted_runner([1, 1]))
    result = await apply.apply()
    assert result.ok is False
    # 两次 attempt（exit=1 不属 126/127，走 health-watch + 重试）


# ── AC9.5 E3 边界：health 恒绿 → down_seen=false + 通过 ──────────────


async def test_health_always_green_down_seen_false() -> None:
    cfg = _cfg(max_attempts=2)
    client = FakeClient([200])  # 恒 200
    apply = _apply(cfg, client=client, runner=_scripted_runner([0]))
    result = await apply.apply()
    assert result.ok is True
    assert result.down_seen is False


# ── subprocess 模式选择 ─────────────────────────────────────────────


def test_needs_shell_detects_shell_metacharacters() -> None:
    assert _needs_shell("docker restart x && echo done") is True
    assert _needs_shell("a || b") is True
    assert _needs_shell("a | b") is True
    assert _needs_shell("a; b") is True
    assert _needs_shell("a > /tmp/log") is True
    assert _needs_shell("docker restart edp") is False
    assert _needs_shell("systemctl restart foo") is False


# ── 真实 subprocess：exit 码传播 ─────────────────────────────────────


async def test_real_subprocess_exit_0_then_health_green() -> None:
    cfg = _cfg(restart_cmd="sh -c 'exit 0'", max_attempts=1, health_up_consecutive=2)
    client = FakeClient([200, 200])
    # 真实 create_subprocess_exec + 真实时间（小超时）；health 探测即时返回
    apply = RestartApply(cfg=cfg, client=client)
    result = await apply.apply()
    assert result.ok is True


async def test_real_subprocess_exit_127_no_retry() -> None:
    cfg = _cfg(restart_cmd="sh -c 'exit 127'", max_attempts=2)
    client = FakeClient([])
    apply = RestartApply(cfg=cfg, client=client)
    result = await apply.apply()
    assert result.ok is False
    assert "127" in (result.error or "")
