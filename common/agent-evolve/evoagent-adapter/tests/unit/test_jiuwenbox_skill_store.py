"""Unit tests for jiuwenbox-backed skill store (httpx mocked)."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from agent_adapter.jiuwenbox_client import JiuwenBoxClientError
from agent_adapter.jiuwenbox_skill_store import JiuwenBoxSkillStore
from agent_adapter.skill_store import SandboxUnavailableError, SkillNotFoundError


@pytest.fixture
def jb_store(tmp_path: Path) -> tuple[JiuwenBoxSkillStore, MagicMock]:
    client = MagicMock()
    client.list_sandboxes.return_value = [{"id": "sbx-demo01", "phase": "ready"}]
    store = JiuwenBoxSkillStore(
        agent_names={"edp_agent"},
        client=client,
        remote_skills_dir="/tmp/skills",
        local_meta_root=tmp_path / "meta",
        sandbox_id="sbx-demo01",
        sandbox_id_resolve="fixed",
    )
    return store, client


def test_list_skills_trusts_dirs(jb_store: tuple[JiuwenBoxSkillStore, MagicMock]) -> None:
    store, client = jb_store
    client.list_files.return_value = [
        {"name": "demo_skill", "is_directory": True},
        {"name": ".meta", "is_directory": True},
        {"name": "bad name", "is_directory": True},
    ]
    names = [s.name for s in store.list_skills("edp_agent")]
    assert names == ["demo_skill"]
    client.download_file.assert_not_called()


def test_read_and_update_single_download(
    jb_store: tuple[JiuwenBoxSkillStore, MagicMock], tmp_path: Path
) -> None:
    store, client = jb_store
    client.download_file.return_value = b"# Demo\n"
    client.upload_file.return_value = None

    doc = store.read_skill("edp_agent", "demo_skill")
    assert doc.content == "# Demo\n"
    assert client.download_file.call_count == 1

    result = store.update_skill("edp_agent", "demo_skill", "# Updated\n")
    assert result.success is True
    # update: one download for existence+snapshot, then upload (no second download)
    assert client.download_file.call_count == 2
    client.upload_file.assert_called()
    args = client.upload_file.call_args
    assert args.args[0] == "sbx-demo01"
    assert args.args[1] == "/tmp/skills/demo_skill/SKILL.md"
    assert args.args[2] == b"# Updated\n"

    snap = tmp_path / "meta" / "edp_agent" / ".meta" / "demo_skill.snapshot"
    assert snap.is_file()
    assert snap.read_text(encoding="utf-8") == "# Demo\n"


def test_update_missing_skill(jb_store: tuple[JiuwenBoxSkillStore, MagicMock]) -> None:
    store, client = jb_store
    client.download_file.side_effect = JiuwenBoxClientError("nope", status_code=404)
    with pytest.raises(SkillNotFoundError):
        store.update_skill("edp_agent", "missing", "# x\n")


def test_restore(jb_store: tuple[JiuwenBoxSkillStore, MagicMock]) -> None:
    store, client = jb_store
    client.download_file.return_value = b"# Demo\n"
    client.upload_file.return_value = None
    store.update_skill("edp_agent", "demo_skill", "# Updated\n")

    client.download_file.return_value = b"# Updated\n"
    results = store.restore_skills("edp_agent", ["demo_skill"])
    assert results[0].success is True
    uploaded = client.upload_file.call_args_list[-1].args[2]
    assert uploaded == b"# Demo\n"


def test_per_agent_sandbox_cache(tmp_path: Path) -> None:
    client = MagicMock()
    client.list_sandboxes.side_effect = [
        [{"id": "sbx-a", "phase": "ready"}],
        [{"id": "sbx-b", "phase": "ready"}],
    ]
    store = JiuwenBoxSkillStore(
        agent_names={"agent_a", "agent_b"},
        client=client,
        remote_skills_dir="/tmp/skills",
        local_meta_root=tmp_path / "meta",
        sandbox_id_resolve="list_ready",
        agent_resolve_modes={"agent_a": "list_ready", "agent_b": "list_ready"},
    )
    assert store._resolve("agent_a") == "sbx-a"
    assert store._resolve("agent_b") == "sbx-b"
    assert store._resolve("agent_a") == "sbx-a"
    assert client.list_sandboxes.call_count == 2


def test_stale_sandbox_retries_once(tmp_path: Path) -> None:
    client = MagicMock()
    # resolve → stale check → re-resolve
    client.list_sandboxes.side_effect = [
        [{"id": "old-sbx", "phase": "ready"}],
        [{"id": "old-sbx", "phase": "stopped"}, {"id": "new-sbx", "phase": "ready"}],
        [{"id": "new-sbx", "phase": "ready"}],
    ]

    def _download(sid: str, _path: str) -> bytes:
        if sid == "old-sbx":
            raise JiuwenBoxClientError("gone", status_code=404)
        return b"# ok\n"

    client.download_file.side_effect = _download
    store = JiuwenBoxSkillStore(
        agent_names={"edp_agent"},
        client=client,
        remote_skills_dir="/tmp/skills",
        local_meta_root=tmp_path / "meta",
        sandbox_id_resolve="list_ready",
    )
    doc = store.read_skill("edp_agent", "demo_skill")
    assert doc.content == "# ok\n"
    assert store._cached_sandbox_ids["edp_agent"] == "new-sbx"
    assert client.list_sandboxes.call_count == 3


def test_missing_skill_404_does_not_reresolve(
    jb_store: tuple[JiuwenBoxSkillStore, MagicMock],
) -> None:
    store, client = jb_store
    client.download_file.side_effect = JiuwenBoxClientError("no file", status_code=404)
    # fixed mode: no stale-retry path; availability check may list sandboxes once
    with pytest.raises(SkillNotFoundError):
        store.read_skill("edp_agent", "missing")
    assert client.download_file.call_count == 1
    # One list for availability (sandbox still ready) — must NOT re-download
    assert client.list_sandboxes.call_count == 1
    assert client.download_file.call_count == 1


def test_missing_skill_on_ready_sandbox_no_retry(tmp_path: Path) -> None:
    client = MagicMock()
    client.list_sandboxes.return_value = [{"id": "alive", "phase": "ready"}]
    client.download_file.side_effect = JiuwenBoxClientError("no file", status_code=404)
    store = JiuwenBoxSkillStore(
        agent_names={"edp_agent"},
        client=client,
        remote_skills_dir="/tmp/skills",
        local_meta_root=tmp_path / "meta",
        sandbox_id_resolve="list_ready",
    )
    with pytest.raises(SkillNotFoundError):
        store.read_skill("edp_agent", "missing")
    # resolve once + stale-check once + availability check; no second download
    assert client.list_sandboxes.call_count == 3
    assert client.download_file.call_count == 1


def test_deleted_sandbox_raises_unavailable_not_skill_not_found(tmp_path: Path) -> None:
    """R43: dead sandbox must not be reported as SKILL_NOT_FOUND."""
    client = MagicMock()
    # Cached id is dead; list has no ready sandboxes (stale check + resolve).
    client.list_sandboxes.return_value = []
    client.download_file.side_effect = JiuwenBoxClientError("gone", status_code=404)
    store = JiuwenBoxSkillStore(
        agent_names={"edp_agent"},
        client=client,
        remote_skills_dir="/tmp/skills",
        local_meta_root=tmp_path / "meta",
        sandbox_id_resolve="list_ready",
    )
    store._cached_sandbox_ids["edp_agent"] = "dead-sbx"
    with pytest.raises(SandboxUnavailableError):
        store.read_skill("edp_agent", "product_recommend_skill")


def test_no_ready_sandbox_on_resolve_raises_unavailable(tmp_path: Path) -> None:
    client = MagicMock()
    client.list_sandboxes.return_value = []
    store = JiuwenBoxSkillStore(
        agent_names={"edp_agent"},
        client=client,
        remote_skills_dir="/tmp/skills",
        local_meta_root=tmp_path / "meta",
        sandbox_id_resolve="list_ready",
    )
    with pytest.raises(SandboxUnavailableError):
        store.list_skills("edp_agent")
