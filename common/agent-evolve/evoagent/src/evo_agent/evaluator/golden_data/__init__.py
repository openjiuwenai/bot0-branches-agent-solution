"""golden_data 工具 —— expected_behavior（EB）生成 + global_understanding（GU）知识库。

替换（弃用但不删）``goal_generator``：optimizer 需要 agent 侧 EB（"应该/不应该 +
result/reason/scenario"），非用户侧 goal。方法论见记忆
[[expected-behavior-generator-methodology]] / [[eb-generator-alignment]]。

骨架阶段：模块文件、公开接口、GU 执行/存储链路、持久化格式定死；实现体留
``NotImplementedError``（builder phase1 / generator phase2），仅 ``gu_store`` 的
I/O + ``ensure_layout`` + ``route_skill`` 有实际逻辑。

存储链路（对齐 optimizer）：
- run workspace（中间结果）：``config.artifact_dir / gu_<run_id>/``（builder 建）。
- 持久化知识库（最终产物）：``config.golden_data_dir / global_understanding/``
  （``gu_store`` 读写）。
"""

from __future__ import annotations

from evo_agent.evaluator.golden_data.builder import GlobalUnderstandingBuilder
from evo_agent.evaluator.golden_data.generator import ExpectedBehaviorGenerator
from evo_agent.evaluator.golden_data.gu_store import (
    OUT_OF_SCOPE_SKILL,
    default_gu_root,
    ensure_layout,
    load_flat,
    load_index,
    load_out_of_scope,
    load_skill_doc,
    load_system_wide,
    route_skill,
    save_flat,
    save_index,
    save_out_of_scope,
    save_skill_doc,
    save_system_wide,
)
from evo_agent.evaluator.golden_data.models import (
    EBInput,
    EBResult,
    ExpectedBehaviorItem,
    ExpectedBehaviorOutput,
    GUIndex,
    GUMode,
    GUOutScope,
    GUSkillDoc,
    GUSlice,
    GUSystemWide,
)
from evo_agent.evaluator.golden_data.prompts import (
    SYSTEM_PROMPT_GLOBAL,
    SYSTEM_PROMPT_PHASE2,
    SYSTEM_PROMPT_SYSTEM_WIDE,
)
from evo_agent.evaluator.golden_data.skill_provider import (
    AdapterSkillProvider,
    LocalSkillProvider,
    SkillProvider,
    make_skill_provider,
)

__all__ = [
    # models
    "EBInput",
    "EBResult",
    "ExpectedBehaviorItem",
    "ExpectedBehaviorOutput",
    "GUMode",
    "GUIndex",
    "GUOutScope",
    "GUSkillDoc",
    "GUSlice",
    "GUSystemWide",
    # skill_provider
    "AdapterSkillProvider",
    "LocalSkillProvider",
    "SkillProvider",
    "make_skill_provider",
    # gu_store
    "OUT_OF_SCOPE_SKILL",
    "default_gu_root",
    "ensure_layout",
    "load_flat",
    "load_index",
    "load_out_of_scope",
    "load_skill_doc",
    "load_system_wide",
    "route_skill",
    "save_flat",
    "save_index",
    "save_out_of_scope",
    "save_skill_doc",
    "save_system_wide",
    # builder / generator
    "ExpectedBehaviorGenerator",
    "GlobalUnderstandingBuilder",
    # prompts
    "SYSTEM_PROMPT_GLOBAL",
    "SYSTEM_PROMPT_PHASE2",
    "SYSTEM_PROMPT_SYSTEM_WIDE",
]
