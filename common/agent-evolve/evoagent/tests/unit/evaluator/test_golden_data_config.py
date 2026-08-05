"""config 单测 —— golden_data_dir / llm_timeout 字段 + env 覆盖。

EvolveConfig 是 pydantic-settings，env 优先级高于 .env；用 monkeypatch.setenv + 新建实例
（不走 lru_cache）验证 env 覆盖生效。
"""

from __future__ import annotations

from pathlib import Path

from evo_agent.config import EvolveConfig


def test_golden_data_dir_field() -> None:
    assert isinstance(EvolveConfig().golden_data_dir, Path)


def test_llm_timeout_field_positive() -> None:
    assert EvolveConfig().llm_timeout > 0


def test_llm_timeout_env_override(monkeypatch) -> None:
    monkeypatch.setenv("EVO_LLM_TIMEOUT", "999")
    assert EvolveConfig().llm_timeout == 999.0


def test_golden_data_dir_env_override(monkeypatch) -> None:
    monkeypatch.setenv("EVO_GOLDEN_DATA_DIR", "/tmp/gd_test")
    assert EvolveConfig().golden_data_dir.as_posix() == "/tmp/gd_test"
