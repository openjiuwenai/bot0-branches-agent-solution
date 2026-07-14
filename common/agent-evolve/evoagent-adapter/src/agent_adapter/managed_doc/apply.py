"""ApplyStrategy seam for managed-doc apply (T7 seam; T9 fills RestartApply).

The strategy is the polymorphism point (spec D7): ``file_only`` docs use
``FileOnlyApply`` (placeholder, not yet in use), ``restart`` docs use
``RestartApply``. The service injects a factory so tests can substitute a fake
strategy and verify the orchestration chain without a real subprocess or health
endpoint.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import shlex
import signal
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Protocol

import httpx
import structlog

from agent_adapter.config import ManagedDocConfig
from agent_adapter.managed_doc.health import HealthWatcher

logger = structlog.get_logger(__name__)

# Shell metacharacters that force create_subprocess_shell (spec D2/§8.2).
_SHELL_CHARS: tuple[str, ...] = ("&&", "||", "|", ";", ">", "<", "&")


@dataclass(frozen=True)
class ApplyResult:
    """Outcome of one ApplyStrategy.apply() call."""

    ok: bool
    down_seen: bool | None = None
    error: str | None = None


class ApplyStrategy(Protocol):
    """Async apply: write-induced effect (file-only / restart / future flavors)."""

    async def apply(self) -> ApplyResult:
        ...


class FileOnlyApply:
    """placeholder, not yet in use (spec D7). File writes are already atomic; no
    further effect needed. Returns success immediately."""

    async def apply(self) -> ApplyResult:
        return ApplyResult(ok=True, down_seen=None, error=None)


def _needs_shell(cmd: str) -> bool:
    """True if ``restart_cmd`` uses shell metacharacters (→ create_subprocess_shell)."""
    return any(ch in cmd for ch in _SHELL_CHARS)


class RestartApply:
    """Restart the agent process and watch health (spec §6.1 / D2).

    ATTEMPT loop (``max_attempts``): run ``restart_cmd`` via asyncio subprocess,
    classify exit code, then E3 health-watch. ``exit=126/127`` → deterministic
    failure, no retry. Other non-zero exits still enter health-watch (the
    container may have restarted). On health success → SUCCEEDED; on failure →
    backoff (``backoff_base→×2→cap``) and retry. Fully async — never blocks the
    event loop (spec D-impl).
    """

    def __init__(
        self,
        *,
        cfg: ManagedDocConfig,
        client: httpx.AsyncClient,
        subprocess_runner: Callable[[str, float | None], Awaitable[int]] | None = None,
        now: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._cfg = cfg
        self._client = client
        self._run_subprocess = subprocess_runner or self._default_subprocess
        self._now = now
        self._sleep = sleep

    async def apply(self) -> ApplyResult:
        last_down_seen: bool | None = None
        max_attempts = self._cfg.max_attempts or 1
        for attempt in range(1, max_attempts + 1):
            exit_code = await self._run_subprocess(
                self._cfg.restart_cmd or "",
                self._cfg.restart_timeout,
            )
            if exit_code in (126, 127):
                return ApplyResult(
                    ok=False,
                    down_seen=None,
                    error=f"restart_cmd exited {exit_code} (deterministic failure, no retry)",
                )
            # exit=其他非零 仍进 health-watch：容器可能实际已重启（D2）
            watcher = HealthWatcher(
                client=self._client,
                health_url=self._cfg.health_url or "",
                down_timeout=self._cfg.health_down_timeout or 0.0,
                up_timeout=self._cfg.health_up_timeout or 0.0,
                up_consecutive=self._cfg.health_up_consecutive or 1,
                poll_interval=self._cfg.health_poll_interval or 0.5,
                now=self._now,
                sleep=self._sleep,
            )
            ok, down_seen = await watcher.watch()
            last_down_seen = down_seen
            if ok:
                return ApplyResult(ok=True, down_seen=down_seen, error=None)
            if attempt < max_attempts:
                await self._sleep(self._backoff(attempt))
        return ApplyResult(
            ok=False,
            down_seen=last_down_seen,
            error=f"health never turned green after {max_attempts} attempts",
        )

    def _backoff(self, attempt: int) -> float:
        base = float(self._cfg.backoff_base or 0.0)
        cap = float(self._cfg.backoff_max or base)
        delay = min(base * (2 ** (attempt - 1)), cap)
        return float(delay)

    async def _default_subprocess(self, cmd: str, timeout: float | None) -> int:
        """Run restart_cmd async; shell=True if it contains shell metacharacters.

        ``start_new_session=True`` makes the child a session leader so a timeout/
        cancel can kill the whole process group (S6) — otherwise ``sh -c``'s
        children (e.g. ``docker restart``) could be orphaned by a bare SIGKILL to
        the shell.

        Exit-code reliability (spec D2): in exec mode ``126/127`` reliably means
        command-not-found / permission-denied. In shell mode ``sh -c`` masks the
        real command's exit code (``sh -c "nonexistent"`` → 127, but ``sh -c
        "docker restart bad"`` → docker's own code), so 126/127 there only catches
        command-not-found; other non-zero exits fall through to health-watch.
        """
        if _needs_shell(cmd):
            proc = await asyncio.create_subprocess_shell(
                cmd,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
                start_new_session=True,
            )
        else:
            proc = await asyncio.create_subprocess_exec(
                *shlex.split(cmd),
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
                start_new_session=True,
            )
        try:
            await asyncio.wait_for(proc.wait(), timeout=timeout)
        except TimeoutError:
            self._kill_group(proc)
            await proc.wait()
        except asyncio.CancelledError:
            # Adapter shutdown mid-restart: kill the process group so it exits
            # within grace, then let the cancellation propagate.
            self._kill_group(proc)
            with contextlib.suppress(Exception):
                await proc.wait()
            raise
        rc = proc.returncode
        assert rc is not None  # set after wait()/kill() in every path above
        return rc

    @staticmethod
    def _kill_group(proc: asyncio.subprocess.Process) -> None:
        """SIGKILL the subprocess's whole session (start_new_session=True →
        proc.pid is the pgid). Suppress ProcessLookupError if already gone."""
        with contextlib.suppress(ProcessLookupError):
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
