# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Prompt template loader for skill document optimizer."""

from __future__ import annotations

import functools
import os
from collections.abc import Callable
from pathlib import Path

_TEMPLATES_DIR = Path(__file__).parent / "templates"


def _load_uncached(name: str) -> str:
    """Read a prompt template from disk (uncached)."""
    path = _TEMPLATES_DIR / f"{name}.md"
    if not path.is_file():
        available = sorted(p.stem for p in _TEMPLATES_DIR.glob("*.md"))
        raise FileNotFoundError(f"Prompt template '{name}' not found. Available: {available}")
    return path.read_text(encoding="utf-8")


# 进程级 LRU 缓存：模板文件不可变，每文件每进程最多读一次磁盘。
_cached_load: Callable[[str], str] = functools.lru_cache(maxsize=None)(_load_uncached)


def load_skill_opt_prompt(name: str) -> str:
    """Load a prompt template by name (without .md extension).

    模板内容在进程内不可变，使用 LRU 缓存避免重复磁盘读取。开发期若需
    热加载模板（例如在 REPL 中改写 ``templates/*.md``），设置环境变量
    ``EVO_DISABLE_PROMPT_CACHE=1`` 可绕过缓存，每次调用都读磁盘。

    Parameters
    ----------
    name : str
        Template name, e.g. ``"analyst_error"`` or ``"merge_final"``.

    Returns
    -------
    str
        The raw template content.

    Raises
    ------
    FileNotFoundError
        If the template does not exist.
    """
    if os.environ.get("EVO_DISABLE_PROMPT_CACHE") == "1":
        return _load_uncached(name)
    return _cached_load(name)
