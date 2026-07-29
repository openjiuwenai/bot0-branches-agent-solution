from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch


def _load_skillopt_wrapper_module():
    """从 examples/scenarios/skillopt/optimizer.py 动态加载模块。"""
    path = Path("examples/scenarios/skillopt/optimizer.py").resolve()
    spec = importlib.util.spec_from_file_location("_real_skillopt_optimizer", path)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules["_real_skillopt_optimizer"] = mod
    spec.loader.exec_module(mod)
    return mod


def test_skillopt_optimizer_uses_skillopt_prompts_namespace() -> None:
    """SkillOptOptimizer 构造分析 prompt 时应加载 skillopt/prompts。"""
    mod = _load_skillopt_wrapper_module()
    cls = mod.SkillOptOptimizer
    base_cls = cls.__mro__[1]
    base_mod = sys.modules[base_cls.__module__]

    opt = cls.__new__(cls)
    opt._operators = {"op1": MagicMock()}
    opt._scheduler = MagicMock()
    opt._scheduler.max_lr = 5

    with patch.object(base_mod, "load_prompt", return_value="SYSTEM") as mock_lp:
        prompt = opt._build_analyst_prompt(
            "reflect",
            "skill_content",
            "trajectories_text",
            "",
            "",
        )

    mock_lp.assert_called_once_with("reflect", "skillopt")
    assert "## Current Skill" in prompt

