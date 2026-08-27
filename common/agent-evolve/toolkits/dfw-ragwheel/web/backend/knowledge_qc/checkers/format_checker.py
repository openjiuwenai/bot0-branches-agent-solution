from __future__ import annotations

from typing import List

from backend.knowledge_qc.checkers.base import BaseChecker, CheckContext
from backend.knowledge_qc.models import Issue, QARecord


class FormatChecker(BaseChecker):
    dimension = "格式规范"

    def check(self, record: QARecord, ctx: CheckContext) -> List[Issue]:
        issues: List[Issue] = []
        length_cfg = ctx.rules.get("length", {})
        min_c = length_cfg.get("min_chars", 2)
        max_c = length_cfg.get("max_chars", 50)

        if not record.question.strip():
            issues.append(
                Issue(
                    dimension=self.dimension,
                    rule_id="FORMAT_001",
                    severity="high",
                    reason="用户问题为空",
                    suggestion="补充相似问文本",
                )
            )
        if not record.intent_name.strip():
            issues.append(
                Issue(
                    dimension=self.dimension,
                    rule_id="FORMAT_002",
                    severity="high",
                    reason="意图名称为空",
                    suggestion="补充目标意图名称",
                )
            )
        if not record.intent_description.strip():
            issues.append(
                Issue(
                    dimension=self.dimension,
                    rule_id="FORMAT_003",
                    severity="medium",
                    reason="意图描述为空",
                    suggestion="补充意图功能描述，便于运营审核与后续扩展",
                )
            )

        q_len = len(record.question.strip())
        if record.question.strip() and (q_len < min_c or q_len > max_c):
            issues.append(
                Issue(
                    dimension=self.dimension,
                    rule_id="FORMAT_LENGTH_001",
                    severity="high",
                    reason=f"相似问长度 {q_len} 字，要求 [{min_c}, {max_c}]",
                    suggestion=f"将相似问调整到 {min_c}~{max_c} 字",
                    evidence={"length": q_len},
                )
            )

        return issues
