"""Unit tests for managed-doc V2 write-time validation (T5)."""

import pytest

from agent_adapter.managed_doc.storage import DocStorageError
from agent_adapter.managed_doc.validation import (
    InvalidDocContentError,
    validate,
)


def test_valid_frontmatter_and_body_passes() -> None:
    content = "---\nauthor: x\nversion: 1\n---\n# Agent Rule\n\nDo the thing.\n"
    validate(content)  # 不抛即通过


def test_frontmatter_with_complex_yaml_passes() -> None:
    content = "---\ntags:\n  - a\n  - b\nmeta:\n  k: v\n---\nbody line\n"
    validate(content)


# ── AC5.1 坏 frontmatter / 空 body / 非法 YAML → 拒绝 ────────────────


def test_missing_frontmatter_rejected() -> None:
    with pytest.raises(InvalidDocContentError):
        validate("# just a body\nno frontmatter here\n")


def test_unclosed_frontmatter_rejected() -> None:
    with pytest.raises(InvalidDocContentError):
        validate("---\nauthor: x\nversion: 1\n# no closing fence\nbody\n")


def test_empty_body_rejected() -> None:
    with pytest.raises(InvalidDocContentError):
        validate("---\nauthor: x\n---\n")


def test_whitespace_only_body_rejected() -> None:
    with pytest.raises(InvalidDocContentError):
        validate("---\nauthor: x\n---\n   \n\n  \n")


def test_invalid_yaml_rejected() -> None:
    # 非法 YAML：未闭合的 flow 标量
    with pytest.raises(InvalidDocContentError):
        validate("---\nbad: [unclosed\n---\nbody\n")


def test_invalid_yaml_mapping_rejected() -> None:
    # 非法 YAML：重复键在 strict 下不报，但结构性错误会报
    with pytest.raises(InvalidDocContentError):
        validate("---\n  - a\n  b: c\n---\nbody\n")


# ── 异常体系 ─────────────────────────────────────────────────────────


def test_invalid_doc_content_is_doc_storage_error() -> None:
    assert issubclass(InvalidDocContentError, DocStorageError)
