from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class Verdict(str, Enum):
    PASS = "通过"
    FAIL = "不通过"
    ERROR = "质检异常"


class QCTaskType(str, Enum):
    QUESTION = "question"
    INTENT = "intent"


class QARecord(BaseModel):
    record_id: str
    question: str
    intent_name: str
    intent_description: str
    row_index: int = 0


class IntentRecord(BaseModel):
    record_id: str
    intent_name: str
    intent_description: str
    row_index: int = 0


class Issue(BaseModel):
    dimension: str
    rule_id: str
    severity: str  # high | medium | low
    passed: bool = False
    reason: str
    suggestion: str
    auto_fixable: bool = False
    fixed_question: Optional[str] = None
    fixed_intent_description: Optional[str] = None
    evidence: Optional[Dict[str, Any]] = None
    corpus_scope: Optional[str] = None  # "batch" | "production"


class CheckResult(BaseModel):
    record: QARecord
    verdict: Verdict
    issues: List[Issue] = Field(default_factory=list)
    final_action: str = ""
    dimension_statuses: Dict[str, str] = Field(default_factory=dict)

    def blocking_issues(self) -> List[Issue]:
        return [i for i in self.issues if not i.passed and i.severity == "high"]


class IntentCheckResult(BaseModel):
    record: IntentRecord
    verdict: Verdict
    reason: str = ""
    dimension_statuses: Dict[str, str] = Field(default_factory=dict)


class BatchReport(BaseModel):
    total: int
    passed: int
    failed: int
    errors: int = 0
    results: List[CheckResult]
    similarity_hits_by_record_id: Dict[str, List[Dict[str, Any]]] = Field(
        default_factory=dict
    )


class IntentBatchReport(BaseModel):
    total: int
    passed: int
    failed: int
    errors: int = 0
    results: List[IntentCheckResult]
