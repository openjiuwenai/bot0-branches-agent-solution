"""Prompt 模板 lru_cache 单元测试（任务 A1, #4）。"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path
from unittest.mock import patch

import pytest

import evo_agent.optimizer.skill_document.prompts as prompts_mod
from evo_agent.optimizer.skill_document.prompts import load_skill_opt_prompt


@pytest.fixture(autouse=True)
def _clear_prompt_cache() -> Iterator[None]:
    """每个用例前后清空 lru_cache，避免跨用例污染。"""
    prompts_mod._cached_load.cache_clear()
    yield
    prompts_mod._cached_load.cache_clear()


def test_load_skill_opt_prompt_reads_disk_once_per_name() -> None:
    """缓存开启时，同名模板每进程只读一次磁盘。"""
    with patch.object(Path, "read_text", return_value="TEMPLATE") as spy:
        load_skill_opt_prompt("ranking")
        load_skill_opt_prompt("ranking")
        load_skill_opt_prompt("ranking")
    assert spy.call_count == 1


def test_load_skill_opt_prompt_cache_returns_stable_content() -> None:
    """缓存命中时返回内容与首次读取一致。"""
    first = load_skill_opt_prompt("ranking")
    second = load_skill_opt_prompt("ranking")
    assert first == second
    assert "ranking" in first.lower() or len(first) > 0


def test_load_skill_opt_prompt_env_disables_cache(monkeypatch: pytest.MonkeyPatch) -> None:
    """EVO_DISABLE_PROMPT_CACHE=1 时每次调用都读磁盘。"""
    monkeypatch.setenv("EVO_DISABLE_PROMPT_CACHE", "1")
    with patch.object(Path, "read_text", return_value="TEMPLATE") as spy:
        load_skill_opt_prompt("ranking")
        load_skill_opt_prompt("ranking")
    assert spy.call_count == 2


def test_load_skill_opt_prompt_env_disabled_does_not_pollute_cache(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """关闭缓存路径不写入缓存，重新开启后仍触发一次磁盘读取。"""
    monkeypatch.setenv("EVO_DISABLE_PROMPT_CACHE", "1")
    with patch.object(Path, "read_text", return_value="TEMPLATE"):
        load_skill_opt_prompt("ranking")  # 走无缓存路径
    monkeypatch.delenv("EVO_DISABLE_PROMPT_CACHE", raising=False)
    with patch.object(Path, "read_text", return_value="TEMPLATE") as spy:
        load_skill_opt_prompt("ranking")  # 缓存为空，应读一次
    assert spy.call_count == 1


def test_load_skill_opt_prompt_raises_when_not_found() -> None:
    """模板不存在时仍抛 FileNotFoundError（缓存不影响错误路径）。"""
    with pytest.raises(FileNotFoundError):
        load_skill_opt_prompt("definitely_not_a_template")
