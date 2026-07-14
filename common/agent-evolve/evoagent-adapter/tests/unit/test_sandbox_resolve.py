"""Unit tests for sandbox_id resolution."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from agent_adapter.sandbox_resolve import SandboxResolveError, resolve_sandbox_id


def test_fixed_requires_id() -> None:
    with pytest.raises(SandboxResolveError):
        resolve_sandbox_id(client=MagicMock(), mode="fixed", sandbox_id=None)


def test_fixed_returns_id() -> None:
    assert (
        resolve_sandbox_id(client=MagicMock(), mode="fixed", sandbox_id="abc123def456")
        == "abc123def456"
    )


def test_list_ready_unique() -> None:
    client = MagicMock()
    client.list_sandboxes.return_value = [
        {"id": "only-one", "phase": "ready"},
        {"id": "stopped", "phase": "stopped"},
    ]
    assert resolve_sandbox_id(client=client, mode="list_ready") == "only-one"


def test_list_ready_multiple_fails() -> None:
    client = MagicMock()
    client.list_sandboxes.return_value = [
        {"id": "a", "phase": "ready"},
        {"id": "b", "phase": "ready"},
    ]
    with pytest.raises(SandboxResolveError, match="multiple"):
        resolve_sandbox_id(client=client, mode="list_ready")


def test_from_logs(tmp_path: Path) -> None:
    log_dir = tmp_path / "logs"
    log_dir.mkdir()
    (log_dir / "process_1.log").write_text(
        "[DPA] SysOperationCard 已注册：id=x, sandbox_id=cafe1234abcd\n",
        encoding="utf-8",
    )
    client = MagicMock()
    assert (
        resolve_sandbox_id(
            client=client,
            mode="from_logs",
            log_dir=log_dir,
            log_pattern="process_*.log",
        )
        == "cafe1234abcd"
    )
    client.list_sandboxes.assert_not_called()
