"""golden_data prompt 模板。

- ``phase1_gu``：GU 归纳（phase1，离线建）—— system role 常量。
- ``phase2_eb``：EB 生成（phase2，在线用）—— system role 常量。
"""

from evo_agent.evaluator.golden_data.prompts.phase1_gu import (
    SYSTEM_PROMPT_GLOBAL,
    SYSTEM_PROMPT_SYSTEM_WIDE,
)
from evo_agent.evaluator.golden_data.prompts.phase2_eb import SYSTEM_PROMPT_PHASE2

__all__ = [
    "SYSTEM_PROMPT_GLOBAL",
    "SYSTEM_PROMPT_PHASE2",
    "SYSTEM_PROMPT_SYSTEM_WIDE",
]
