"""Unit tests for managed-doc DocStorage (T2)."""

from pathlib import Path

import pytest

from agent_adapter.managed_doc.storage import (
    DocNotFoundError,
    DocStorage,
    DocStorageError,
    InvalidDocPathError,
)


def _make_store(
    tmp_path: Path,
    *,
    path: str | None = None,
    allow_root: Path | None = None,
) -> DocStorage:
    root = allow_root or (tmp_path / "host" / "edp")
    root.mkdir(parents=True, exist_ok=True)
    file_path = path or str(root / "AgentRule.md")
    store = DocStorage(kind="agent_rule", path=file_path, allow_root=root)
    # seed an initial file
    (root / "AgentRule.md").write_text("---\nauthor: x\n---\n# v1\n", encoding="utf-8")
    return store


# ── AC2.4 read_meta 不存在 → None ─────────────────────────────────────


def test_read_meta_missing_returns_none(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    assert store.read_meta() is None


def test_read_meta_after_write(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    store.write_meta(revision="abc123")
    meta = store.read_meta()
    assert meta is not None
    assert meta["revision"] == "abc123"
    assert "updated_at" in meta


# ── 基本读写 + sha ────────────────────────────────────────────────────


def test_read_file_and_sha(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    content = store.read_file()
    assert content.startswith("---\nauthor: x\n---\n")
    sha = DocStorage.sha256(content)
    assert len(sha) == 64


def test_write_file_atomic_updates_content(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    new = "---\nauthor: x\n---\n# v2\n"
    store.write_file_atomic(new)
    assert store.read_file() == new


def test_read_file_missing_raises(tmp_path: Path) -> None:
    root = tmp_path / "host" / "edp"
    root.mkdir(parents=True)
    store = DocStorage(kind="agent_rule", path=str(root / "missing.md"), allow_root=root)
    with pytest.raises(DocNotFoundError):
        store.read_file()


# ── AC2.1 原子写中断：原文件不变 + tmp 清理 ───────────────────────────


def test_atomic_write_interrupted_original_unchanged(monkeypatch, tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    original = store.read_file()

    import agent_adapter.managed_doc.storage as storage_mod

    def _boom(src, dst):  # noqa: ANN001
        raise OSError("simulated replace failure")

    monkeypatch.setattr(storage_mod.os, "replace", _boom)

    with pytest.raises(OSError):
        store.write_file_atomic("---\nauthor: x\n---\n# should-not-land\n")

    # 原文件不变
    assert store.read_file() == original
    # tmp 残留清理
    root = tmp_path / "host" / "edp"
    tmp_leftovers = [p for p in root.iterdir() if ".tmp" in p.name or p.suffix == ".tmp"]
    assert tmp_leftovers == []


# ── AC2.2 snapshot 幂等：重复 update 只首次写 ─────────────────────────


def test_ensure_snapshot_idempotent(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    # 首次：当前文件 = v1 → 写 snapshot
    store.ensure_snapshot()
    snapshot_path = (tmp_path / "host" / "edp" / ".meta" / "agent_rule.snapshot")
    first_snapshot = snapshot_path.read_text(encoding="utf-8")
    assert first_snapshot.startswith("---\nauthor: x\n---\n# v1")

    # 改文件后再 ensure_snapshot：snapshot 不应被覆写
    store.write_file_atomic("---\nauthor: x\n---\n# v2\n")
    store.ensure_snapshot()
    assert snapshot_path.read_text(encoding="utf-8") == first_snapshot


def test_read_snapshot_missing_raises(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    with pytest.raises(DocNotFoundError):
        store.read_snapshot()


def test_read_snapshot_after_ensure(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    store.ensure_snapshot()
    snap = store.read_snapshot()
    assert snap.startswith("---\nauthor: x\n---\n# v1")


# ── AC2.3 路径穿越 → InvalidDocPathError ──────────────────────────────


def test_path_traversal_rejected(tmp_path: Path) -> None:
    root = tmp_path / "host" / "edp"
    root.mkdir(parents=True)
    with pytest.raises(InvalidDocPathError):
        DocStorage(kind="agent_rule", path="../../etc/passwd", allow_root=root)


def test_path_within_root_ok(tmp_path: Path) -> None:
    root = tmp_path / "host" / "edp"
    root.mkdir(parents=True)
    file_path = root / "AgentRule.md"
    file_path.write_text("ok", encoding="utf-8")
    store = DocStorage(kind="agent_rule", path=str(file_path), allow_root=root)
    assert store.read_file() == "ok"


def test_absolute_path_outside_root_rejected(tmp_path: Path) -> None:
    root = tmp_path / "host" / "edp"
    root.mkdir(parents=True)
    outside = tmp_path / "secret" / "passwd"
    outside.parent.mkdir(parents=True)
    outside.write_text("secret", encoding="utf-8")
    with pytest.raises(InvalidDocPathError):
        DocStorage(kind="agent_rule", path=str(outside), allow_root=root)


# ── 异常体系 ─────────────────────────────────────────────────────────


def test_exception_hierarchy() -> None:
    assert issubclass(DocNotFoundError, DocStorageError)
    assert issubclass(InvalidDocPathError, DocStorageError)
