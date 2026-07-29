"""
SkillOptOptimizer — skillopt 场景 optimizer 子类。

当前代码库里 SkillOpt 的核心 rollout/attribute 实现仍位于
`examples/scenarios/edp_agent/optimizer.py`（历史命名来源）。

为避免大段复制，这里通过动态加载复用该实现，并仅覆盖
`_SCENARIO_NAME`，让 prompt 覆盖路径落到 `skillopt/prompts/`。
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


_CURRENT_DIR = Path(__file__).resolve().parent
_EDP_OPTIMIZER_PATH = _CURRENT_DIR.parent / "edp_agent" / "optimizer.py"

if not _EDP_OPTIMIZER_PATH.exists():
    raise FileNotFoundError(f"Missing edp_agent optimizer: {_EDP_OPTIMIZER_PATH}")

_SPEC_NAME = "_evo_agent_scenario_edp_agent_optimizer_skillopt_wrapper"
_spec = importlib.util.spec_from_file_location(_SPEC_NAME, _EDP_OPTIMIZER_PATH)
if _spec is None or _spec.loader is None:  # pragma: no cover
    raise ImportError(f"Cannot import edp_agent optimizer from {_EDP_OPTIMIZER_PATH}")

_module = importlib.util.module_from_spec(_spec)
sys.modules[_SPEC_NAME] = _module  # 确保 __module__ 可反查、便于测试/patch
_spec.loader.exec_module(_module)


_BaseOpt = getattr(_module, "EDPAgentOptimizer")


class SkillOptOptimizer(_BaseOpt):
    """SkillOptOptimizer: 复用 edp_agent 逻辑，并把提示词来源切到 skillopt。"""

    _SCENARIO_NAME = "skillopt"


__all__ = ["SkillOptOptimizer"]

