"""EvoAgent 扩展协议常量 — 补充 openjiuwen PyPI 0.1.13 缺失的协议字面量。

PyPI 版本的 ``openjiuwen.agent_evolving.protocols`` 不包含
``SKILL_CONTENT_TARGET`` 和 ``SKILL_DOCUMENT_DOMAIN``，
这两个常量由 dev_enterprise_evolution 分支引入但未合入 main。

本模块提供这些缺失常量，供 EvoAgent 内部使用。
"""

from __future__ import annotations

from typing import Final, Literal

# --- PyPI 0.1.13 已有的常量（从 openjiuwen 重导出，保持 import 路径一致） ---
from openjiuwen.agent_evolving.protocols import (
    APPEND_MODE,
    APPROVE_ACTION,
    CONVERSATION_REVIEW_SIGNAL,
    EXECUTION_FAILURE_SIGNAL,
    EXPERIENCE_ENTRY,
    EXPERIENCES_TARGET,
    LOCAL_APPLY_COMPLETED,
    MERGE_MODE,
    PENDING_CHANGE_EFFECT,
    REJECT_ACTION,
    REPLACE_MODE,
    RETRY_ACTION,
    SKILL_EXPERIENCE_ENTRY,
    STATE_EFFECT,
    TOOL_FAILURE_SIGNAL,
    TRAJECTORY_ISSUE_SIGNAL,
    USER_INTENT_SIGNAL,
    VALID_PATCH_ACTIONS,
    VALID_SECTIONS,
)

# --- PyPI 0.1.13 缺失的常量（feature 分支新增） ---
SKILL_CONTENT_TARGET: Final[Literal["skill_content"]] = "skill_content"
SKILL_DOCUMENT_DOMAIN: Final[Literal["skill_document"]] = "skill_document"

__all__ = [
    "APPROVE_ACTION",
    "APPEND_MODE",
    "CONVERSATION_REVIEW_SIGNAL",
    "EXECUTION_FAILURE_SIGNAL",
    "EXPERIENCE_ENTRY",
    "EXPERIENCES_TARGET",
    "LOCAL_APPLY_COMPLETED",
    "MERGE_MODE",
    "PENDING_CHANGE_EFFECT",
    "REJECT_ACTION",
    "REPLACE_MODE",
    "RETRY_ACTION",
    "SKILL_CONTENT_TARGET",
    "SKILL_DOCUMENT_DOMAIN",
    "SKILL_EXPERIENCE_ENTRY",
    "STATE_EFFECT",
    "TOOL_FAILURE_SIGNAL",
    "TRAJECTORY_ISSUE_SIGNAL",
    "USER_INTENT_SIGNAL",
    "VALID_PATCH_ACTIONS",
    "VALID_SECTIONS",
]
