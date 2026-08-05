"""Resolve the sandbox_id used by EDPAgent without modifying EDPAgent code.

Strategies (recommended order: from_logs > fixed > list_ready):
- from_logs: parse EDPAgent init log line containing ``sandbox_id=...``;
  falls back to list_ready when logs miss the id
- fixed: use configured sandbox_id
- list_ready: pick the single ready sandbox from jiuwenbox (or match sandbox_id);
  fails when multiple ready sandboxes exist
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Literal

import structlog

from agent_adapter.jiuwenbox_client import JiuwenBoxClient, JiuwenBoxClientError

logger = structlog.get_logger(__name__)

SandboxResolveMode = Literal["fixed", "list_ready", "from_logs"]

_SANDBOX_ID_RE = re.compile(
    r"sandbox_id[=:\s]+([0-9a-z][0-9a-z_-]{2,15})",
    re.IGNORECASE,
)


class SandboxResolveError(Exception):
    """Could not determine a sandbox_id."""


def resolve_sandbox_id(
    *,
    client: JiuwenBoxClient,
    mode: SandboxResolveMode,
    sandbox_id: str | None = None,
    log_dir: str | Path | None = None,
    log_pattern: str = "process_*.log",
) -> str:
    """Resolve sandbox_id according to ``mode``."""
    if mode == "fixed":
        if not sandbox_id:
            raise SandboxResolveError("skill_backend=jiuwenbox requires sandbox_id when resolve=fixed")
        return sandbox_id

    if mode == "from_logs":
        found = _find_sandbox_id_in_logs(log_dir, log_pattern)
        if found:
            logger.info("sandbox_id_resolved_from_logs", sandbox_id=found)
            return found
        # Fall through to list_ready as a secondary strategy.
        logger.warning("sandbox_id_not_in_logs_fallback_list_ready")

    return _pick_ready_sandbox(client, preferred=sandbox_id)


def _pick_ready_sandbox(client: JiuwenBoxClient, *, preferred: str | None) -> str:
    try:
        sandboxes = client.list_sandboxes()
    except JiuwenBoxClientError as exc:
        raise SandboxResolveError(str(exc)) from exc

    ready = [
        s for s in sandboxes
        if isinstance(s, dict) and str(s.get("phase", "")).lower() == "ready"
    ]
    if preferred:
        for item in ready:
            if item.get("id") == preferred:
                return preferred
        raise SandboxResolveError(
            f"configured sandbox_id={preferred!r} not found among ready sandboxes"
        )

    if len(ready) == 1:
        sid = str(ready[0]["id"])
        logger.info("sandbox_id_resolved_list_ready", sandbox_id=sid)
        return sid

    if not ready:
        raise SandboxResolveError("no ready sandbox found on jiuwenbox")

    ids = [str(s.get("id")) for s in ready]
    raise SandboxResolveError(
        f"multiple ready sandboxes {ids}; set agents[].sandbox_id or sandbox_id_resolve=from_logs"
    )


def _find_sandbox_id_in_logs(log_dir: str | Path | None, log_pattern: str) -> str | None:
    if not log_dir:
        return None
    root = Path(log_dir)
    if not root.is_dir():
        return None

    # Prefer newest matching log files.
    files = sorted(root.glob(log_pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    for path in files[:5]:
        try:
            # Read trailing chunk — sandbox_id is logged during init.
            data = path.read_bytes()
            text = data[-512_000:].decode("utf-8", errors="ignore")
        except OSError:
            continue
        matches = _SANDBOX_ID_RE.findall(text)
        if matches:
            return matches[-1]
    return None
