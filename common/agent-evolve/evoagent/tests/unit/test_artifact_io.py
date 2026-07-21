"""Artifact 原子写入契约测试。"""

import hashlib
import json
import os
from pathlib import Path

import pytest

from evo_agent.errors import ArtifactPersistenceError
from evo_agent.optimizer.artifact_io import (
    atomic_write_bytes,
    atomic_write_json,
    atomic_write_jsonl,
    atomic_write_marker,
    sha256_file,
)


def _write_artifact(kind: str, target: Path) -> None:
    if kind == "bytes":
        atomic_write_bytes(target, b"payload")
    elif kind == "json":
        atomic_write_json(target, {"status": "complete"})
    elif kind == "jsonl":
        atomic_write_jsonl(target, [{"row": 1}])
    else:
        atomic_write_marker(target)


def test_atomic_write_json_publishes_complete_file_without_temp_residue(tmp_path: Path) -> None:
    """读者只能看到完整目标文件，同目录临时文件会被替换。"""
    target = tmp_path / "result.json"

    atomic_write_json(target, {"状态": "完成", "items": [1, 2, 3]})

    assert json.loads(target.read_text(encoding="utf-8")) == {
        "状态": "完成",
        "items": [1, 2, 3],
    }
    assert list(tmp_path.iterdir()) == [target]


@pytest.mark.parametrize("kind", ["bytes", "json", "jsonl", "marker"])
def test_atomic_write_replace_failure_is_fatal_and_leaves_no_partial_target(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, kind: str
) -> None:
    """replace 失败不能把临时/半 JSON 暴露为已提交 artifact。"""
    target = tmp_path / "result.json"

    def fail_replace(_source: Path, _target: Path) -> None:
        raise OSError("disk full")

    monkeypatch.setattr(os, "replace", fail_replace)

    with pytest.raises(ArtifactPersistenceError, match="artifact persistence failed"):
        _write_artifact(kind, target)

    assert not target.exists()
    assert list(tmp_path.iterdir()) == []


def test_atomic_write_directory_creation_failure_is_wrapped(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """连临时目录都无法建立时也必须使用统一 fatal error 类型。"""
    target = tmp_path / "missing" / "result.json"
    original_mkdir = Path.mkdir

    def fail_target_mkdir(path: Path, *args: object, **kwargs: object) -> None:
        if path == target.parent:
            raise OSError("read-only filesystem")
        original_mkdir(path, *args, **kwargs)

    monkeypatch.setattr(Path, "mkdir", fail_target_mkdir)

    with pytest.raises(ArtifactPersistenceError, match="result.json"):
        atomic_write_json(target, {"status": "invalid"})


def test_atomic_write_jsonl_preserves_rows_and_unicode(tmp_path: Path) -> None:
    target = tmp_path / "rows.jsonl"

    atomic_write_jsonl(target, [{"序号": 1}, {"序号": 2}])

    assert [json.loads(line) for line in target.read_text(encoding="utf-8").splitlines()] == [
        {"序号": 1},
        {"序号": 2},
    ]


def test_atomic_write_marker_and_bytes_publish_exact_payloads(tmp_path: Path) -> None:
    marker = tmp_path / "_SUCCESS"
    payload = tmp_path / "payload.bin"

    atomic_write_marker(marker)
    atomic_write_bytes(payload, b"\x00complete\xff")

    assert marker.read_bytes() == b""
    assert payload.read_bytes() == b"\x00complete\xff"


def test_sha256_file_returns_digest_for_complete_file(tmp_path: Path) -> None:
    target = tmp_path / "results.json"
    payload = b'{"status":"valid"}'
    target.write_bytes(payload)

    assert sha256_file(target) == hashlib.sha256(payload).hexdigest()


def test_checksum_read_failure_is_fatal(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """checksum 阶段的权限/I/O 错误不能被 callback 当作普通异常吞掉。"""
    target = tmp_path / "results.json"
    target.write_text('{"status":"valid"}', encoding="utf-8")
    original_open = Path.open

    def fail_checksum_open(path: Path, *args: object, **kwargs: object) -> object:
        if path == target:
            raise OSError("checksum read denied")
        return original_open(path, *args, **kwargs)

    monkeypatch.setattr(Path, "open", fail_checksum_open)

    with pytest.raises(ArtifactPersistenceError, match="results.json"):
        sha256_file(target)
