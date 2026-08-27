# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Stable contracts for workspace conversation planning and outcomes."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any


class ConversationGoal(str, Enum):
    ANSWER = "answer"
    INSPECT = "inspect"
    MUTATE = "mutate"
    REPAIR = "repair"
    EXTRACT = "extract"
    CONTINUE_EXTRACT = "continue_extract"
    REGENERATE = "regenerate"
    CLARIFY = "clarify"


class MutationPolicy(str, Enum):
    FORBIDDEN = "forbidden"
    ALLOWED = "allowed"
    REQUIRED = "required"


class TurnStatus(str, Enum):
    ANSWERED = "answered"
    NEEDS_INPUT = "needs_input"
    CHANGES_APPLIED = "changes_applied"
    ROLLED_BACK = "rolled_back"
    FAILED = "failed"


@dataclass(frozen=True, slots=True)
class ConversationIntent:
    goal: ConversationGoal
    mutation_policy: MutationPolicy
    clarification_required: bool = False
    conflicts: tuple[str, ...] = ()
    requested_action: str = "auto"
    source: str = "policy"

    @property
    def action(self) -> str:
        return {
            ConversationGoal.ANSWER: "chat",
            ConversationGoal.INSPECT: "inspect",
            ConversationGoal.MUTATE: "edit",
            ConversationGoal.REPAIR: "repair",
            ConversationGoal.EXTRACT: "extract",
            ConversationGoal.CONTINUE_EXTRACT: "continue_extract",
            ConversationGoal.REGENERATE: "regenerate",
            ConversationGoal.CLARIFY: "clarify",
        }[self.goal]

    def as_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["goal"] = self.goal.value
        value["mutation_policy"] = self.mutation_policy.value
        value["conflicts"] = list(self.conflicts)
        value["action"] = self.action
        return value


@dataclass(frozen=True, slots=True)
class TurnResult:
    status: TurnStatus
    answer: str
    pending_decisions: tuple[Any, ...] = ()
    changed_paths: tuple[str, ...] = ()
    suggested_next_message: str = ""
    metadata: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["status"] = self.status.value
        value["pending_decisions"] = list(self.pending_decisions)
        value["changed_paths"] = list(self.changed_paths)
        return value


_READ_ONLY_PATTERNS = (
    re.compile(r"(?:不要|无需|无须|禁止|不得|别)\s*(?:再|进行|做|执行|尝试)?\s*(?:任何)?\s*(?:修改|改动|写入|保存|更新|修复|运行命令|执行命令)", re.IGNORECASE),
    re.compile(r"(?:只读|仅(?:做)?检查|只(?:做)?检查|仅回答|只回答|不要改文件|不修改任何文件)", re.IGNORECASE),
)
_MUTATION_PATTERN = re.compile(
    r"(?:修改|改成|改为|改得|改一下|调整|补充|新增|添加|删除|移除|精简|重命名|修正|修复|优化|更新|替换)",
    re.IGNORECASE,
)
_MUTATION_REQUEST_PATTERN = re.compile(
    r"(?:请|帮我|麻烦|需要你|你来|直接|立即|现在就).{0,12}"
    r"(?:修改|改成|改为|改得|改一下|调整|补充|新增|添加|删除|移除|精简|重命名|修正|修复|优化|更新|替换)"
    r"|(?:^|[，。；;\s])(?:把|将).{0,80}"
    r"(?:改成|改为|改得|改一下|修改|调整|补充|新增|添加|删除|移除|精简|重命名|修正|修复|优化|更新|替换)",
    re.IGNORECASE,
)
_REPAIR_PATTERN = re.compile(r"(?:修复|解决|收敛|验收|失败|报错|错误|异常|阻断)", re.IGNORECASE)
_INSPECT_PATTERN = re.compile(r"(?:查看|检查|分析|解释|告诉我|当前|现在|实际|是否|为什么|如何|怎么|[？?])", re.IGNORECASE)
_GENERATION_PATTERN = re.compile(
    r"(?:生成|创建|构建|制作|抽取|提取).{0,24}(?:skill|技能包)|(?:skill|技能包).{0,24}(?:生成|创建|构建|制作|抽取|提取)",
    re.IGNORECASE,
)
_REGENERATION_PATTERN = re.compile(r"(?:重新|从头|全部重做|整体重做).{0,24}(?:生成|抽取|提取|构建|制作|skill|技能包)", re.IGNORECASE)
_CONTINUE_PATTERN = re.compile(
    r"(?:继续|接着|恢复|完成|补完).{0,20}(?:抽取|提取|生成|skill|技能包|上次|剩余)|^(?:继续|接着|完成它|继续完成)[。！!\s]*$",
    re.IGNORECASE,
)
_VAGUE_MUTATION_PATTERN = re.compile(r"^\s*(?:(?:请|帮我|麻烦)\s*)?(?:改一下|修改一下|优化一下|调整一下|完善一下)[。！!\s]*$", re.IGNORECASE)


def message_forbids_mutation(message: str) -> bool:
    text = str(message or "").strip()
    return any(pattern.search(text) for pattern in _READ_ONLY_PATTERNS)


def classify_conversation_intent(
    *,
    message: str,
    requested_action: str = "auto",
    has_package: bool,
    has_completed_package: bool,
    has_progress: bool,
    has_validation_failure: bool,
) -> ConversationIntent:
    """Classify one turn while keeping authority separate from the goal.

    Explicit read-only language is an execution constraint and therefore wins
    over both mutation keywords and a stale UI action.  Explicit host actions
    remain useful routing hints, but cannot broaden authority declared by the
    user in the same message.
    """

    text = str(message or "").strip()
    requested = str(requested_action or "auto").strip().lower()
    read_only = message_forbids_mutation(text) or requested in {"chat", "inspect"}
    if read_only:
        goal = ConversationGoal.INSPECT if has_progress or has_package else ConversationGoal.ANSWER
        return ConversationIntent(
            goal=goal,
            mutation_policy=MutationPolicy.FORBIDDEN,
            requested_action=requested,
            source="explicit_read_only",
        )

    if requested in {"edit", "repair"}:
        return ConversationIntent(
            goal=ConversationGoal.REPAIR if requested == "repair" else ConversationGoal.MUTATE,
            mutation_policy=MutationPolicy.REQUIRED,
            requested_action=requested,
            source="explicit_action",
        )
    if requested in {"continue_extract", "regenerate", "clarify"}:
        goal = {
            "continue_extract": ConversationGoal.CONTINUE_EXTRACT,
            "regenerate": ConversationGoal.REGENERATE,
            "clarify": ConversationGoal.CLARIFY,
        }[requested]
        return ConversationIntent(
            goal=goal,
            mutation_policy=MutationPolicy.REQUIRED if goal != ConversationGoal.CLARIFY else MutationPolicy.FORBIDDEN,
            clarification_required=goal == ConversationGoal.CLARIFY,
            requested_action=requested,
            source="explicit_action",
        )
    if requested == "extract":
        goal = ConversationGoal.REGENERATE if has_completed_package else ConversationGoal.EXTRACT
        return ConversationIntent(
            goal=goal,
            mutation_policy=MutationPolicy.REQUIRED,
            clarification_required=has_completed_package,
            requested_action=requested,
            source="explicit_action",
        )

    if not has_package:
        if has_progress and _CONTINUE_PATTERN.search(text):
            goal = ConversationGoal.CONTINUE_EXTRACT
        elif _GENERATION_PATTERN.search(text):
            goal = ConversationGoal.EXTRACT
        else:
            goal = (
                ConversationGoal.INSPECT if has_progress and _INSPECT_PATTERN.search(text) else ConversationGoal.ANSWER
            )
        mutating = goal in {ConversationGoal.EXTRACT, ConversationGoal.CONTINUE_EXTRACT}
        return ConversationIntent(
            goal=goal,
            mutation_policy=MutationPolicy.REQUIRED if mutating else MutationPolicy.FORBIDDEN,
            requested_action=requested,
        )

    if _REGENERATION_PATTERN.search(text):
        return ConversationIntent(
            goal=ConversationGoal.REGENERATE,
            mutation_policy=MutationPolicy.REQUIRED,
            clarification_required=has_completed_package,
            requested_action=requested,
        )
    if not has_completed_package and (_CONTINUE_PATTERN.search(text) or _GENERATION_PATTERN.search(text)):
        return ConversationIntent(
            goal=ConversationGoal.CONTINUE_EXTRACT,
            mutation_policy=MutationPolicy.REQUIRED,
            requested_action=requested,
        )
    if _VAGUE_MUTATION_PATTERN.search(text):
        return ConversationIntent(
            goal=ConversationGoal.CLARIFY,
            mutation_policy=MutationPolicy.FORBIDDEN,
            clarification_required=True,
            requested_action=requested,
        )
    # A mutation word inside a question describes the subject of the answer,
    # not permission to write.  Only an imperative request grants mutation in
    # auto mode.  Explicit UI actions were already handled above.
    mutation_mentioned = bool(_MUTATION_PATTERN.search(text))
    mutation_requested = bool(_MUTATION_REQUEST_PATTERN.search(text)) or (
        mutation_mentioned and not _INSPECT_PATTERN.search(text)
    )
    if mutation_requested:
        goal = (
            ConversationGoal.REPAIR
            if has_validation_failure and _REPAIR_PATTERN.search(text)
            else ConversationGoal.MUTATE
        )
        return ConversationIntent(
            goal=goal,
            mutation_policy=MutationPolicy.REQUIRED,
            requested_action=requested,
        )
    if mutation_mentioned:
        return ConversationIntent(
            goal=ConversationGoal.ANSWER,
            mutation_policy=MutationPolicy.FORBIDDEN,
            requested_action=requested,
            source="mutation_advice_question",
        )
    return ConversationIntent(
        goal=ConversationGoal.INSPECT if _INSPECT_PATTERN.search(text) else ConversationGoal.ANSWER,
        mutation_policy=MutationPolicy.FORBIDDEN,
        requested_action=requested,
    )


__all__ = [
    "ConversationGoal",
    "ConversationIntent",
    "MutationPolicy",
    "TurnResult",
    "TurnStatus",
    "classify_conversation_intent",
    "message_forbids_mutation",
]
