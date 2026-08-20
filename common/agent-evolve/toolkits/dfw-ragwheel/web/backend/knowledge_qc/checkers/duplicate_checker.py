from __future__ import annotations

from typing import List, Set

from backend.knowledge_qc.checkers.base import BaseChecker, CheckContext
from backend.knowledge_qc.checkers.corpus_scope import (
    duplicate_reason,
    duplicate_rule_id,
    duplicate_suggestion,
    enrich_hit,
)
from backend.knowledge_qc.checkers.llm_hit_verify import (
    llm_dup_conflict_enabled,
    llm_filter_confirmed_hits,
    resolve_dup_conflict_issue_reason,
)
from backend.knowledge_qc.models import Issue, QARecord


class DuplicateChecker(BaseChecker):
    dimension = "重复检测"

    def check(self, record: QARecord, ctx: CheckContext) -> List[Issue]:
        issues: List[Issue] = []
        seen_hit_ids: Set[str] = set()
        sim_cfg = ctx.rules.get("similarity", {})
        threshold = sim_cfg.get("duplicate_threshold", 0.92)
        hits = ctx.get_similarity_hits(record)

        candidates: List[dict] = []
        for hit in hits:
            if hit["similarity"] < threshold:
                continue
            if _norm_intent(hit["intent_name"]) != _norm_intent(record.intent_name):
                continue
            hit_id = hit.get("id") or ""
            if hit_id and hit_id in seen_hit_ids:
                continue
            if hit_id:
                seen_hit_ids.add(hit_id)
            candidates.append(hit)

        if llm_dup_conflict_enabled(ctx):
            candidates = llm_filter_confirmed_hits(
                record, candidates, "duplicate", ctx
            )

        for hit in candidates:
            scope = hit.get("corpus_scope", "")
            issues.append(
                Issue(
                    dimension=self.dimension,
                    rule_id=duplicate_rule_id(scope),
                    severity="high",
                    reason=resolve_dup_conflict_issue_reason(
                        duplicate_reason(hit),
                        record,
                        hit,
                        "duplicate",
                        ctx,
                    ),
                    suggestion=duplicate_suggestion(scope),
                    evidence=enrich_hit(hit),
                    corpus_scope=scope,
                )
            )
        return issues


def _norm_intent(name: str) -> str:
    return (name or "").strip()
