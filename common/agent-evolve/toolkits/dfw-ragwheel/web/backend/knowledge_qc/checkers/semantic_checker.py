from __future__ import annotations

import re
from typing import List

from backend.knowledge_qc.checkers.base import BaseChecker, CheckContext
from backend.knowledge_qc.checkers.llm_guard import call_llm
from backend.knowledge_qc.models import Issue, QARecord


class SemanticChecker(BaseChecker):
    dimension = "语义质量"

    def check(self, record: QARecord, ctx: CheckContext) -> List[Issue]:
        issues: List[Issue] = []
        cfg = ctx.rules.get("semantic", {})
        patterns = cfg.get("vague_patterns", [])
        q = record.question.strip()

        for pat in patterns:
            if re.match(pat, q, re.IGNORECASE):
                issues.append(
                    Issue(
                        dimension=self.dimension,
                        rule_id="SEMANTIC_VAGUE_001",
                        severity="high",
                        reason=f"相似问过于模糊，无法表达明确意图：「{q}」",
                        suggestion="删除或改写为具体业务意图问法",
                    )
                )
                return issues

        if not ctx.rules.get("checkers", {}).get("llm_semantic") or not ctx.llm:
            return issues

        if ctx.skip_llm_semantic:
            return issues

        issues.extend(self._llm_check(record, ctx))
        return issues

    def _llm_check(self, record: QARecord, ctx: CheckContext) -> List[Issue]:
        rid = record.record_id
        with ctx.cache_lock:
            cached = ctx.llm_semantic_cache.get(rid)
        if cached is not None:
            result = cached
        else:
            result = call_llm(
                lambda: ctx.llm.judge_semantic_quality(
                    record.question,
                    record.intent_name,
                    record.intent_description,
                )
            )
            with ctx.cache_lock:
                result = ctx.llm_semantic_cache.setdefault(rid, result)

        issues: List[Issue] = []
        if not result.get("pass", True):
            issues.append(
                Issue(
                    dimension=self.dimension,
                    rule_id="SEMANTIC_LLM_001",
                    severity="high",
                    reason=result.get("reason") or "LLM 判定语义质量不足",
                    suggestion=result.get("suggestion") or "请改写相似问",
                    evidence=result,
                )
            )
        return issues
