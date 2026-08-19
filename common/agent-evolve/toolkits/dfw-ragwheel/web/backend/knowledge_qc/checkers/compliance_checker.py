from __future__ import annotations

import re
from typing import List

from backend.knowledge_qc.checkers.base import BaseChecker, CheckContext
from backend.knowledge_qc.checkers.compliance_pii import (
    AMOUNT_PATTERN,
    NAME_PATTERN,
    PII_PATTERNS,
    desensitize,
)
from backend.knowledge_qc.config import load_wordlist
from backend.knowledge_qc.models import Issue, QARecord

# 相似问质检：合规仅扫描相似问文本，不检测菜单描述（意图描述由「意图描述质检」负责）
_COMPLIANCE_FIELDS = (("question", "相似问"),)


class ComplianceChecker(BaseChecker):
    dimension = "合规安全"

    def check(self, record: QARecord, ctx: CheckContext) -> List[Issue]:
        issues: List[Issue] = []
        cfg = ctx.rules.get("compliance", {})

        for attr, label in _COMPLIANCE_FIELDS:
            text = getattr(record, attr, "") or ""
            if not text.strip():
                continue

            if cfg.get("enable_pii", True):
                issues.extend(self._check_pii(text, attr, label))

            if cfg.get("enable_regulatory", True):
                issues.extend(
                    self._check_wordlist(
                        text, attr, label, "regulatory", "COMPLIANCE_REG", ctx
                    )
                )

            if cfg.get("enable_prohibited", True):
                issues.extend(
                    self._check_wordlist(
                        text, attr, label, "prohibited", "COMPLIANCE_PROHIBITED", ctx
                    )
                )

        return issues

    def _check_pii(self, text: str, field: str, field_label: str) -> List[Issue]:
        issues: List[Issue] = []
        spans = []
        for rule_id, pattern, pii_label in PII_PATTERNS:
            for m in re.finditer(pattern, text):
                spans.append({"text": m.group(), "type": pii_label})
                fixed = desensitize(text)
                issues.append(
                    _make_field_issue(
                        rule_id=rule_id,
                        field=field,
                        field_label=field_label,
                        severity="high",
                        reason=f"{field_label}含{pii_label}：「{m.group()}」",
                        suggestion="脱敏为占位符，如 [卡号]、[手机号]",
                        auto_fixable=True,
                        fixed_text=fixed,
                        evidence={"spans": spans, "field": field},
                    )
                )

        if NAME_PATTERN.search(text) and AMOUNT_PATTERN.search(text):
            fixed = desensitize(text)
            if fixed != text:
                issues.append(
                    _make_field_issue(
                        rule_id="COMPLIANCE_PII_ENTITY",
                        field=field,
                        field_label=field_label,
                        severity="medium",
                        reason=f"{field_label}含具体人名/金额等可识别实体，不宜作为通用语料",
                        suggestion="改写为通用表述，如「给[收款人]转[金额]」",
                        auto_fixable=True,
                        fixed_text=fixed,
                        evidence={"original": text, "field": field},
                    )
                )
        return issues

    def _check_wordlist(
        self,
        text: str,
        field: str,
        field_label: str,
        list_name: str,
        prefix: str,
        ctx: CheckContext,
    ) -> List[Issue]:
        issues: List[Issue] = []
        words = ctx.wordlist_overrides.get(list_name)
        if words is None:
            words = load_wordlist(list_name)
        for word in words:
            if word in text:
                issues.append(
                    _make_field_issue(
                        rule_id=f"{prefix}_001",
                        field=field,
                        field_label=field_label,
                        severity="high",
                        reason=f"{field_label}含违规表述「{word}」",
                        suggestion="删除或替换为合规话术，不符合监管要求",
                        evidence={"matched_word": word, "field": field},
                    )
                )
        return issues


def _make_field_issue(
    rule_id: str,
    field: str,
    field_label: str,
    severity: str,
    reason: str,
    suggestion: str,
    auto_fixable: bool = False,
    fixed_text: str = None,
    evidence: dict = None,
) -> Issue:
    ev = dict(evidence or {})
    ev.setdefault("field", field)
    ev.setdefault("field_label", field_label)

    kw = dict(
        dimension="合规安全",
        rule_id=rule_id,
        severity=severity,
        reason=reason,
        suggestion=suggestion,
        auto_fixable=auto_fixable,
        evidence=ev,
    )
    if auto_fixable and fixed_text is not None:
        if field == "question":
            kw["fixed_question"] = fixed_text
        elif field == "intent_description":
            kw["fixed_intent_description"] = fixed_text
    return Issue(**kw)
