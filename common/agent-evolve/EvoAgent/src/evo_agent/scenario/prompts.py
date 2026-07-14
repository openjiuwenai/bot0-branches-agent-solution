"""Prompt 加载 — 两级查找机制。

查找顺序：
1. ``scenarios/<scenario_name>/prompts/<name>.md`` — 场景特定覆盖
2. agent-core ``templates/<name>.md`` — 通用 fallback
"""

from __future__ import annotations

from pathlib import Path

from evo_agent.paths import SCENARIOS_DIR


def load_prompt(
    name: str,
    scenario_name: str,
    scenarios_dir: Path | None = None,
) -> str:
    """加载 prompt 内容。

    Parameters
    ----------
    name:
        Prompt 文件名（不含 ``.md`` 后缀），如 ``analyst_error``。
    scenario_name:
        场景名，用于定位 ``scenarios/<name>/prompts/`` 目录。
    scenarios_dir:
        场景根目录。默认为项目根下的 ``scenarios/``。

    Returns
    -------
    str
        Prompt 文件内容。

    Raises
    ------
    FileNotFoundError
        场景目录和 agent-core 都找不到该 prompt。
    """
    scenarios_root = scenarios_dir or SCENARIOS_DIR

    # 1. 场景特定覆盖
    scenario_prompt = scenarios_root / scenario_name / "prompts" / f"{name}.md"
    if scenario_prompt.exists():
        return scenario_prompt.read_text(encoding="utf-8")

    # 2. agent-core fallback
    try:
        from evo_agent.optimizer.skill_document.prompts import (
            load_skill_opt_prompt,
        )

        return load_skill_opt_prompt(name)
    except (ImportError, FileNotFoundError):
        pass

    msg = f"Prompt not found: {name!r} (looked in {scenario_prompt} and agent-core templates)"
    raise FileNotFoundError(msg)
