# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Structured scenario handoff from material understanding to package authoring."""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any

from .decision_registry import (
    canonical_decision_option_value,
    decision_capabilities,
    decision_concept_is_platform_managed,
    decision_context_is_authentication,
    infer_decision_concept,
    infer_decision_concept_from_options,
    infer_decision_concept_from_title,
    normalize_decision_concept,
    project_decision_value,
)
from .contract_decisions import decision_semantic_concept
from .schema_validation import json_schema_issues


SCENARIO_CONTRACT_SCHEMA_VERSION = "skill-builder-scenario-contract/v1"
SCENARIO_CONTRACT_PATH = "validation/scenario_contract.json"
SCENARIO_DRAFT_MAX_BYTES = 32 * 1024
SCENARIO_FACT_VALUE_MAX_BYTES = 4 * 1024
SCENARIO_PURPOSE_MAX_CHARS = 600
SCENARIO_MATERIAL_FACT_MAX_ITEMS = 36
SCENARIO_PENDING_DECISION_MAX_ITEMS = 8
SCENARIO_DECISION_OPTION_MAX_ITEMS = 6
SCENARIO_LIST_LIMITS = {
    "triggers": (8, 300),
    "nonTriggers": (8, 300),
    "inputs": (10, 400),
    "outputs": (10, 400),
    "steps": (12, 400),
    "dependencies": (12, 400),
    "scriptRequirements": (12, 500),
    "acceptanceCriteria": (12, 500),
    "evidenceRefs": (24, 300),
}
_LIST_FIELDS = tuple(SCENARIO_LIST_LIMITS)
_DECISION_TYPES = {"select", "boolean", "text"}
_MATERIAL_FACT_TYPES = {"requirement", "rule"}
_STRUCTURED_BROWSER_ACTIONS = frozenset(
    {
        "navigate",
        "goto",
        "click",
        "input",
        "fill",
        "type",
        "select",
        "check",
        "submit",
        "download",
        "extract",
        "scroll",
        "hover",
        "press",
        "导航",
        "打开",
        "访问",
        "点击",
        "输入",
        "填写",
        "选择",
        "勾选",
        "提交",
        "下载",
        "提取",
        "滚动",
        "查询",
    }
)
_STRUCTURED_BROWSER_TARGET_RE = re.compile(
    r"https?://|(?:^|[\s\"'(])(?:#|\.|//|\[)|"
    r"\b(?:input|select|button|textarea|form|a)\s*(?:\[|#|\.)|"
    r"\b(?:xpath|css|role|text)\s*=",
    re.IGNORECASE,
)
_SCENARIO_FACT_KINDS = frozenset({
    "purpose",
    "trigger",
    "non_trigger",
    "input",
    "output",
    "step",
    "dependency",
    "script_requirement",
    "acceptance",
    "requirement",
    "rule",
})


def _has_structured_browser_workflow(value: Any) -> bool:
    """Recognize recorded browser steps without interpreting business prose."""

    if isinstance(value, list):
        return any(_has_structured_browser_workflow(item) for item in value)
    if not isinstance(value, dict):
        return False
    action = str(value.get("action") or "").strip().lower()
    if action in _STRUCTURED_BROWSER_ACTIONS:
        targets = " ".join(
            str(value.get(key) or "").strip()
            for key in ("target", "url", "selector", "locator")
        )
        if _STRUCTURED_BROWSER_TARGET_RE.search(targets):
            return True
    return any(
        _has_structured_browser_workflow(item)
        for item in value.values()
        if isinstance(item, (dict, list))
    )
_SCENARIO_NAVIGATION_FACT_KINDS = frozenset({
    "purpose",
    "trigger",
    "non_trigger",
    "input",
    "output",
    "step",
    "dependency",
    "script_requirement",
    "acceptance",
})
_SCENARIO_DIAGNOSTIC_KEYS = frozenset(
    {
        "semanticHash",
        "semanticValueNormalizedFrom",
        "semanticValueSource",
        # Required capabilities are a deterministic projection of the
        # already signed dependencies/scriptRequirements/outputs fields.  They
        # must not change a persisted v1 Scenario semantic hash during rolling
        # upgrades or invalidate an HITL answer bound to that hash.
        "requiredCapabilities",
        "capabilityRequirements",
    }
)


def _text(value: Any, *, limit: int = 2000) -> str:
    return " ".join(str(value or "").split()).strip()[:limit].strip()


def _is_internal_skill_evidence_ref(value: Any) -> bool:
    if isinstance(value, dict):
        value = value.get("path") or value.get("ref") or value.get("id")
    ref = str(value or "").replace("\\", "/").strip().strip("/")
    return ref == ".skill-builder/skills" or ref.startswith(
        ".skill-builder/skills/"
    )


def _stable_generated_id(prefix: str, value: Any) -> str:
    """Return a platform-owned stable id for one model-authored fact.

    Scenario Agents describe business facts and conflicts; they do not own
    machine identifiers.  Hashing the normalized semantic value keeps ids
    stable across retries without making wording-only array position part of
    the contract.
    """

    encoded = json.dumps(
        _normalize_semantic_payload(value),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return f"{prefix}-{hashlib.sha256(encoded).hexdigest()[:12]}"


def _compact_scenario_source(value: dict[str, Any]) -> dict[str, Any]:
    """Compile the small facts/conflicts surface into the canonical v1 IR."""

    value = normalize_scenario_draft_surface(value)
    result: dict[str, Any] = {
        "skillName": value.get("skillName"),
        "displayName": value.get("displayName"),
        "purpose": "",
        **{field: [] for field in _LIST_FIELDS},
        "capabilityEvidence": [],
        "materialFacts": [],
        "pendingDecisions": [],
    }
    list_targets = {
        "trigger": "triggers",
        "non_trigger": "nonTriggers",
        "input": "inputs",
        "output": "outputs",
        "step": "steps",
        "dependency": "dependencies",
        "script_requirement": "scriptRequirements",
        "acceptance": "acceptanceCriteria",
    }
    facts = value.get("facts") if isinstance(value.get("facts"), list) else []
    for raw in facts:
        if not isinstance(raw, dict):
            continue
        kind = str(raw.get("kind") or "").strip().lower().replace("-", "_")
        if kind not in _SCENARIO_FACT_KINDS:
            continue
        # Keep provenance for every fact, including navigation facts.  The
        # canonical requirement/rule records retain their own refs below;
        # this top-level projection prevents trigger/input/step evidence from
        # disappearing during compact-facts compilation.
        result["evidenceRefs"].extend(_structured_evidence_refs(raw.get("evidenceRefs")))
        fact_value = raw.get("value")
        if fact_value in (None, "", [], {}):
            continue
        if kind == "purpose":
            if not result["purpose"]:
                result["purpose"] = _text(fact_value, limit=SCENARIO_PURPOSE_MAX_CHARS)
            continue
        target = list_targets.get(kind)
        if target:
            result[target].append(fact_value)
            if kind in {"step", "dependency", "script_requirement"}:
                source_quote = _text(raw.get("sourceQuote"), limit=300)
                result["capabilityEvidence"].append(
                    {
                        "kind": kind,
                        "evidenceRefs": _structured_evidence_refs(
                            raw.get("evidenceRefs")
                        ),
                        "sourceQuote": source_quote,
                        "evidenceStatus": "quoted" if source_quote else "reference_only",
                    }
                )
            continue
        label = _text(raw.get("label") or raw.get("concept"), limit=128)
        stable_id = _stable_generated_id(
            "rule" if kind == "rule" else "requirement",
            {
                "kind": kind,
                "label": label,
                "value": fact_value,
                "evidenceRefs": raw.get("evidenceRefs"),
            },
        )
        result["materialFacts"].append(
            {
                "factId": stable_id,
                "factType": kind,
                "concept": label or ("业务规则" if kind == "rule" else "业务要求"),
                "value": fact_value,
                "ruleKind": _text(raw.get("ruleKind") or label or "policy", limit=64),
                "description": _text(raw.get("description") or label, limit=300),
                "evidenceRefs": raw.get("evidenceRefs"),
                # Do not manufacture a source quote from the normalized value.
                # A rule/requirement without a material quote must fail the
                # canonical evidence boundary instead of looking evidenced.
                "sourceQuote": _text(raw.get("sourceQuote"), limit=300),
                "overrideAllowed": raw.get("overrideAllowed") is True,
            }
        )

    # Navigation fields are useful to Author but are no longer an independent
    # model contract.  Deterministic fallbacks prevent a missing summary list
    # from blocking Scenario when the lossless requirement/rule facts exist.
    first_fact_text = next(
        (
            _text(raw.get("value"), limit=SCENARIO_PURPOSE_MAX_CHARS)
            for raw in facts
            if isinstance(raw, dict) and raw.get("value") not in (None, "", [], {})
        ),
        "",
    )
    result["purpose"] = result["purpose"] or first_fact_text or "按已确认材料生成可复用 Skill。"
    result["triggers"] = result["triggers"] or ["用户请求执行该业务场景时"]
    result["inputs"] = result["inputs"] or ["按已确认材料和调用参数提供业务输入"]
    result["outputs"] = result["outputs"] or ["生成材料要求的业务结果"]
    result["steps"] = result["steps"] or [
        "读取并校验业务输入",
        "执行已确认的业务规则并生成结果",
    ]

    conflicts = value.get("conflicts") if isinstance(value.get("conflicts"), list) else []
    result["evidenceRefs"] = list(dict.fromkeys(result["evidenceRefs"]))
    result["pendingDecisions"] = [dict(item) for item in conflicts if isinstance(item, dict)]
    return result


def normalize_scenario_draft_surface(value: Any) -> dict[str, Any]:
    """Normalize transport aliases without inventing Scenario business facts.

    ``default``/``defaultValue`` and string/object option forms describe the
    same compact user choice. Treating those presentation differences as a
    lifecycle blocker forced an otherwise deterministic repair worker to ask
    the model to rewrite the whole draft. The platform also bounds presentation
    text and infers a missing control type only from unambiguous options or a
    boolean default. Unknown fields and invalid business facts still fail the
    public ScenarioDraft schema.
    """

    source = dict(value) if isinstance(value, dict) else {}
    # Keep the model-facing draft bounded before the strict tool-schema check.
    # A source quote is still required for rule/requirement facts, but a model
    # occasionally returns the full paragraph/URL example instead of the
    # contract's 300-character excerpt.  Preserve the evidenced prefix rather
    # than rejecting an otherwise lossless fact at the transport boundary.
    raw_facts = source.get("facts")
    if isinstance(raw_facts, list):
        facts: list[Any] = []
        for raw in raw_facts:
            if not isinstance(raw, dict):
                facts.append(raw)
                continue
            fact = dict(raw)
            kind = str(fact.get("kind") or "").strip().lower().replace("-", "_")
            fact_value = fact.get("value")
            if (
                kind in _MATERIAL_FACT_TYPES
                and isinstance(fact_value, list)
                and not str(fact.get("sourceQuote") or "").strip()
                and fact_value
                and all(
                    isinstance(item, dict)
                    and str(item.get("sourceQuote") or "").strip()
                    for item in fact_value
                )
            ):
                for item in fact_value:
                    normalized_value = dict(item)
                    nested_source_quote = normalized_value.pop("sourceQuote")
                    nested_evidence_refs = normalized_value.pop("evidenceRefs", None)
                    split_fact = {
                        **fact,
                        "value": normalized_value,
                        "sourceQuote": _text(nested_source_quote, limit=300),
                    }
                    if isinstance(nested_evidence_refs, list):
                        split_fact["evidenceRefs"] = nested_evidence_refs
                    facts.append(split_fact)
                continue
            if kind in _MATERIAL_FACT_TYPES and isinstance(fact_value, dict):
                normalized_value = dict(fact_value)
                nested_source_quote = normalized_value.pop("sourceQuote", None)
                if not str(fact.get("sourceQuote") or "").strip() and str(
                    nested_source_quote or ""
                ).strip():
                    fact["sourceQuote"] = _text(nested_source_quote, limit=300)
                fact["value"] = normalized_value
            if isinstance(fact.get("sourceQuote"), str):
                fact["sourceQuote"] = _text(fact.get("sourceQuote"), limit=300)
            if isinstance(fact.get("evidenceRefs"), list):
                raw_evidence_refs = fact["evidenceRefs"]
                fact["evidenceRefs"] = [
                    _evidence_ref_text(item) or item
                    for item in raw_evidence_refs
                    if not _is_internal_skill_evidence_ref(item)
                ]
                if raw_evidence_refs and not fact["evidenceRefs"]:
                    continue
            facts.append(fact)
        source["facts"] = facts

    raw_conflicts = source.get("conflicts")
    if not isinstance(raw_conflicts, list):
        return source
    conflicts: list[Any] = []
    for raw in raw_conflicts:
        if not isinstance(raw, dict):
            conflicts.append(raw)
            continue
        conflict = dict(raw)
        if "defaultValue" not in conflict and "default" in conflict:
            conflict["defaultValue"] = conflict.get("default")
        conflict.pop("default", None)
        if isinstance(conflict.get("description"), str):
            conflict["description"] = _text(conflict.get("description"), limit=300)
        if isinstance(conflict.get("evidenceRefs"), list):
            conflict["evidenceRefs"] = [
                _evidence_ref_text(item) or item
                for item in conflict["evidenceRefs"]
            ]
        raw_options = conflict.get("options")
        if isinstance(raw_options, list):
            conflict["options"] = [
                {"value": option, "label": _text(option, limit=180)}
                if isinstance(option, (str, int, float, bool))
                else option
                for option in raw_options
            ]
        if not str(conflict.get("type") or "").strip():
            normalized_options = conflict.get("options")
            default = conflict.get("defaultValue")
            if isinstance(normalized_options, list) and len(normalized_options) >= 2:
                conflict["type"] = "select"
            elif isinstance(default, bool):
                conflict["type"] = "boolean"
        conflicts.append(conflict)
    source["conflicts"] = conflicts
    return source


def _normalize_item(
    value: Any,
    *,
    text_limit: int = 500,
    depth: int = 0,
) -> dict[str, Any] | str | None:
    if isinstance(value, str):
        return _text(value, limit=text_limit)
    if depth >= 4:
        return None
    if isinstance(value, list):
        normalized_list = [
            normalized
            for part in value[:16]
            if (
                normalized := _normalize_item(
                    part,
                    text_limit=text_limit,
                    depth=depth + 1,
                )
            ) not in (None, "", {}, [])
        ]
        return normalized_list or None
    if not isinstance(value, dict):
        return None
    result: dict[str, Any] = {}
    for key, item in list(value.items())[:16]:
        normalized_key = _text(key, limit=100)
        if not normalized_key:
            continue
        if isinstance(item, bool) or item is None or isinstance(item, (int, float)):
            result[normalized_key] = item
        elif isinstance(item, str):
            result[normalized_key] = _text(item, limit=text_limit)
        elif isinstance(item, list):
            result[normalized_key] = [
                normalized
                for part in item[:16]
                if (
                    normalized := _normalize_item(
                        part,
                        text_limit=text_limit,
                        depth=depth + 1,
                    )
                ) not in (None, "", {}, [])
            ]
        elif isinstance(item, dict):
            normalized = _normalize_item(
                item,
                text_limit=text_limit,
                depth=depth + 1,
            )
            if normalized not in (None, "", {}, []):
                result[normalized_key] = normalized
    return result or None


def _normalize_semantic_payload(value: Any, *, depth: int = 0) -> Any:
    """Preserve bounded nested business semantics without flattening them."""

    if depth > 8:
        return None
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        return _text(value, limit=4000)
    if isinstance(value, list):
        return [
            normalized
            for item in value[:100]
            if (normalized := _normalize_semantic_payload(item, depth=depth + 1))
            not in (None, "", [], {})
        ]
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for raw_key, item in list(value.items())[:100]:
            key = _text(raw_key, limit=120)
            if not key:
                continue
            normalized = _normalize_semantic_payload(item, depth=depth + 1)
            if normalized not in (None, "", [], {}):
                result[key] = normalized
        return result
    return _text(value, limit=4000)


def _evidence_ref_text(value: Any) -> str:
    if isinstance(value, dict):
        value = value.get("path") or value.get("ref") or value.get("id")
    ref = str(value or "").replace("\\", "/").strip().strip("/")
    if not ref or any(character in ref for character in "\r\n\t"):
        return ""
    return ref if ref.startswith(("inputs/", "material:", "platform:")) else ""


def _structured_evidence_refs(value: Any) -> list[str]:
    refs: list[str] = []
    for raw in value if isinstance(value, list) else []:
        ref = _evidence_ref_text(raw)
        if ref:
            refs.append(ref)
    return list(dict.fromkeys(refs))


def _invalid_evidence_refs(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    valid = set(_structured_evidence_refs(value))
    invalid: list[str] = []
    for raw in value:
        ref = _evidence_ref_text(raw)
        if not ref:
            invalid.append(str(raw)[:300])
    return list(dict.fromkeys(invalid))


def _normalize_resolved_requirement(value: Any, *, index: int) -> tuple[dict[str, Any] | None, list[str]]:
    if not isinstance(value, dict):
        return None, [f"resolvedRequirements[{index}] must be an object"]
    concept = _text(value.get("concept") or value.get("semanticConcept"), limit=128)
    requirement_id = _text(
        value.get("requirementId") or value.get("id") or concept or value.get("title"),
        limit=128,
    )
    evidence_refs = _structured_evidence_refs(value.get("evidenceRefs"))
    invalid_evidence_refs = _invalid_evidence_refs(value.get("evidenceRefs"))
    source_quote = _text(value.get("sourceQuote"), limit=2000)
    issues: list[str] = []
    if not requirement_id:
        issues.append(f"resolvedRequirements[{index}] requires requirementId")
    if not concept:
        issues.append(f"resolvedRequirements[{index}] requires concept")
    if "value" not in value:
        issues.append(f"resolvedRequirements[{index}] requires value")
    if not evidence_refs:
        issues.append(f"resolvedRequirements[{index}] requires typed material evidenceRefs")
    if invalid_evidence_refs:
        issues.append(
            f"resolvedRequirements[{index}].evidenceRefs contains invalid references: "
            + ", ".join(invalid_evidence_refs[:5])
            + "; use inputs/<path>, material:<id>, or platform:<id>"
        )
    if issues:
        return None, issues
    result = {
        "requirementId": requirement_id,
        "concept": concept,
        "value": _normalize_semantic_payload(value.get("value")),
        "description": _text(value.get("description") or value.get("title"), limit=1000),
        "evidenceRefs": evidence_refs,
        "sourceQuote": source_quote,
        "evidenceStatus": "quoted" if source_quote else "reference_only",
        "overrideAllowed": value.get("overrideAllowed") is True,
    }
    return {key: item for key, item in result.items() if item not in (None, "", [], {})}, []


def _normalize_business_rule(value: Any, *, index: int) -> tuple[dict[str, Any] | None, list[str]]:
    if not isinstance(value, dict):
        return None, [f"businessRules[{index}] must be an object"]
    rule_id = _text(value.get("ruleId") or value.get("id") or value.get("title"), limit=128)
    kind = _text(value.get("kind") or "policy", limit=64).lower()
    evidence_refs = _structured_evidence_refs(value.get("evidenceRefs"))
    invalid_evidence_refs = _invalid_evidence_refs(value.get("evidenceRefs"))
    source_quote = _text(value.get("sourceQuote"), limit=2000)
    definition = value.get("definition")
    if definition in (None, "", [], {}):
        definition = {
            key: item
            for key, item in value.items()
            if key not in {
                "ruleId", "id", "title", "kind", "description",
                "evidenceRefs", "sourceQuote",
            }
        }
    normalized_definition = _normalize_semantic_payload(definition)
    issues: list[str] = []
    if not rule_id:
        issues.append(f"businessRules[{index}] requires ruleId")
    if normalized_definition in (None, "", [], {}):
        issues.append(f"businessRules[{index}] requires a structured definition")
    if not evidence_refs:
        issues.append(f"businessRules[{index}] requires typed material evidenceRefs")
    if invalid_evidence_refs:
        issues.append(
            f"businessRules[{index}].evidenceRefs contains invalid references: "
            + ", ".join(invalid_evidence_refs[:5])
            + "; use inputs/<path>, material:<id>, or platform:<id>"
        )
    if not source_quote:
        issues.append(f"businessRules[{index}] requires sourceQuote")
    if issues:
        return None, issues
    result = {
        "ruleId": rule_id,
        "kind": kind,
        "description": _text(value.get("description") or value.get("title"), limit=1000),
        "definition": normalized_definition,
        "evidenceRefs": evidence_refs,
        "sourceQuote": source_quote,
    }
    return {key: item for key, item in result.items() if item not in (None, "", [], {})}, []


def _normalize_material_fact(
    value: Any,
    *,
    index: int,
) -> tuple[str | None, dict[str, Any] | None, list[str]]:
    """Compile one non-duplicated ScenarioDraft fact into the canonical IR."""

    if not isinstance(value, dict):
        return None, None, [f"materialFacts[{index}] must be an object"]
    fact_type = _text(value.get("factType") or value.get("type"), limit=32).lower()
    if fact_type not in _MATERIAL_FACT_TYPES:
        return None, None, [
            f"materialFacts[{index}].factType must be requirement or rule"
        ]
    fact_id = _text(
        value.get("factId") or value.get("id") or value.get("concept"),
        limit=128,
    )
    common = {
        "description": value.get("description"),
        "evidenceRefs": value.get("evidenceRefs"),
        "sourceQuote": value.get("sourceQuote"),
    }
    if fact_type == "rule":
        normalized, issues = _normalize_business_rule(
            {
                "ruleId": fact_id,
                "kind": value.get("ruleKind") or value.get("concept") or "policy",
                "definition": value.get("value"),
                **common,
            },
            index=index,
        )
        return "businessRules", normalized, [
            issue.replace(f"businessRules[{index}]", f"materialFacts[{index}]")
            for issue in issues
        ]
    normalized, issues = _normalize_resolved_requirement(
        {
            "requirementId": fact_id,
            "concept": value.get("concept"),
            "value": value.get("value"),
            "overrideAllowed": value.get("overrideAllowed"),
            **common,
        },
        index=index,
    )
    return "resolvedRequirements", normalized, [
        issue.replace(f"resolvedRequirements[{index}]", f"materialFacts[{index}]")
        for issue in issues
    ]


_EXECUTABLE_BUSINESS_RULE_PATTERN = re.compile(
    r"(?:formula|rate|ratio|weight|score|threshold|grade|classification|"
    r"precision|rounding|interval|boundary|percent).{0,20}"
    r"(?:rule|policy|mapping|formula|equals?|must|condition)|"
    r"(?:rule|policy|mapping|formula|calculate|classify).{0,20}"
    r"(?:rate|ratio|weight|score|threshold|grade|classification|precision|rounding|interval|boundary|percent)|"
    r"(?:公式|费率|汇率|比例|权重|评分|分数|阈值|等级|分类|精度|取整|区间|边界|百分比)"
    r".{0,12}(?:规则|口径|映射|公式|等于|必须|条件|计算方式)|"
    r"(?:按照|按|依据|根据).{0,20}(?:公式|费率|比例|权重|评分|阈值|分类规则|分类口径|精度|取整|区间|边界)",
    re.IGNORECASE,
)
_EXECUTABLE_BUSINESS_RULE_KEYS = frozenset(
    {
        "formula", "rate", "ratio", "weight", "weights", "score", "threshold",
        "classificationrule", "classificationmapping", "precision", "rounding",
        "interval", "boundary", "percent", "公式", "费率", "比例", "权重", "阈值",
        "分类规则", "分类映射", "精度", "取整", "区间", "边界", "百分比",
    }
)


def _contains_structured_business_rule(value: Any) -> bool:
    if isinstance(value, list):
        return any(_contains_structured_business_rule(item) for item in value)
    if not isinstance(value, dict):
        return False
    for raw_key, item in value.items():
        key = re.sub(r"[^a-z0-9\u4e00-\u9fff]", "", str(raw_key).lower())
        if key in _EXECUTABLE_BUSINESS_RULE_KEYS:
            return True
        if _contains_structured_business_rule(item):
            return True
    return False


def scenario_contract_requires_business_rules(value: Any) -> bool:
    """Detect resolved rule semantics that cannot survive a prose-only handoff.

    Workflow verbs and domain nouns such as ``calculate fee`` or ``amount`` do
    not establish a formula. They may describe the Skill's purpose while the
    actual basis remains a pending HITL decision. Requiring material-backed
    ``businessRules`` for those phrases forced Scenario to invent provenance.
    Only explicit rate, weighting, scoring, threshold, precision, interval or
    classification semantics cross this lossless-rule boundary.
    """

    source = value if isinstance(value, dict) else {}
    semantic_surface = {
        key: source.get(key)
        for key in (
            "inputs", "outputs", "steps", "scriptRequirements", "acceptanceCriteria",
        )
    }
    if _contains_structured_business_rule(semantic_surface):
        return True
    encoded = json.dumps(semantic_surface, ensure_ascii=False, sort_keys=True, default=str)
    return bool(_EXECUTABLE_BUSINESS_RULE_PATTERN.search(encoded))


def _decision_id(value: str) -> str:
    normalized = "_".join(
        part for part in "".join(
            character.lower() if character.isascii() and character.isalnum() else " "
            for character in value
        ).split()
        if part
    )[:64]
    if normalized and normalized[0].isalpha():
        return normalized
    return f"decision_{hashlib.sha256(value.encode('utf-8')).hexdigest()[:12]}"


def _normalize_decision_option(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    label = _text(value.get("label"), limit=180)
    option_value = value.get("value")
    if not label or option_value is None or isinstance(option_value, (dict, list)):
        return None
    result: dict[str, Any] = {"value": option_value, "label": label}
    description = _text(value.get("description"), limit=240)
    if description:
        result["description"] = description
    return result


def _scenario_semantic_payload(value: Any) -> Any:
    """Return the business-semantic projection used by ``semanticHash``.

    Canonicalization diagnostics explain how a model-authored option was mapped
    to the platform registry.  They are useful for audit output, but they are
    not business facts and may be added or preserved across serialization.  A
    persisted ScenarioContract must therefore keep the same hash when it is
    normalized again by the runtime, the host, or a standalone caller.
    """

    if isinstance(value, dict):
        return {
            key: _scenario_semantic_payload(item)
            for key, item in value.items()
            if key not in _SCENARIO_DIAGNOSTIC_KEYS
        }
    if isinstance(value, list):
        return [_scenario_semantic_payload(item) for item in value]
    return value


def _scenario_capability_profile(
    value: Any,
) -> tuple[dict[str, bool], set[str], bool]:
    """Return hard, optional and ambiguous runtime capabilities."""
    source = value if isinstance(value, dict) else {}
    implementation_values = [
        source.get("purpose") or "",
        *(source.get("dependencies") or []),
        *(source.get("scriptRequirements") or []),
        *(source.get("steps") or []),
        *(source.get("outputs") or []),
        *(source.get("acceptanceCriteria") or []),
    ]
    implementation_text = json.dumps(
        implementation_values,
        ensure_ascii=False,
        sort_keys=True,
    )
    runtime_signal_text = re.sub(
        r"(?:不依赖|无需|无须|不需要|不使用|禁止|不包含|不涉及)"
        r"(?=[^。；;\"\]]{0,120}(?:playwright|selenium|puppeteer|浏览器|"
        r"(?<![A-Za-z0-9_])api(?![A-Za-z0-9_])|接口|外部系统|外部运行))"
        r"[^。；;\"\]]{0,120}",
        "",
        implementation_text,
        flags=re.IGNORECASE,
    )
    # A clearly manual boundary must remain a manual boundary.  This guard is
    # intentionally conservative: a generic SOP mentioning a system is not an
    # executable capability claim unless it also contains an automation signal.
    explicit_manual_boundary = bool(
        re.search(
            r"(?:本\s*(?:包|skill)|当前\s*skill).{0,48}(?:不包含|无需|不适用|不涉及|无此要求)"
            r"|(?:本\s*(?:包|skill)|当前\s*skill).{0,32}(?:纯离线|仅人工|纯人工)"
            r"|纯离线(?:分析|处理|报告)"
            r"|(?:不依赖|无需|无须|不需要|不使用).{0,48}(?:外部\s*API|浏览器自动化|外部系统)",
            implementation_text,
            re.IGNORECASE,
        )
    )
    capability_evidence_aware = "capabilityEvidence" in source
    trusted_capability_values = [
        item.get("sourceQuote")
        for item in source.get("capabilityEvidence") or []
        if isinstance(item, dict)
        and str(item.get("evidenceStatus") or "").strip() == "quoted"
        and str(item.get("sourceQuote") or "").strip()
    ]
    hard_runtime_signal_text = (
        json.dumps(
            trusted_capability_values,
            ensure_ascii=False,
            sort_keys=True,
        )
        if capability_evidence_aware
        else runtime_signal_text
    )
    explicit_browser_runtime = bool(
        re.search(
            r"(?:\bplaywright\b|\bselenium\b|\bpuppeteer\b|浏览器(?:自动化|采集|运行时|交互|操作)"
            r"|浏览器.{0,16}(?:打开|访问|导航|点击|填写|下载|截图)"
            r"|(?:打开|访问|导航|点击|填写|下载|截图).{0,16}浏览器"
            r"|(?:选择器|selector|#[-_a-zA-Z0-9]+|\[role=))",
            hard_runtime_signal_text,
            re.IGNORECASE,
        )
    )
    page_interaction = bool(
        re.search(
            r"(?:导航|点击|填写|选择|下载).{0,32}(?:网页|网站|官网|页面|标签|按钮|选择器)"
            r"|(?:网页|网站|官网|页面).{0,32}(?:点击|填写|选择|下载)",
            hard_runtime_signal_text,
            re.IGNORECASE,
        )
    )
    manual_page_interaction = bool(
        re.search(
            r"(?:用户|人工|手动).{0,24}(?:点击|打开|访问|查看|复核)"
            r"|(?:点击|打开|访问|查看).{0,24}(?:后由用户|人工|手动|人工复核)",
            hard_runtime_signal_text,
            re.IGNORECASE,
        )
    )
    structured_browser_workflow = _has_structured_browser_workflow(
        source.get("steps")
    )
    browser_runtime = explicit_browser_runtime or structured_browser_workflow or (
        not capability_evidence_aware
        and page_interaction
        and not manual_page_interaction
    )
    api_runtime = bool(
        re.search(
            r"(?:\brequests\b|\bhttpx\b|\baiohttp\b|"
            r"\b(?:GET|POST|PUT|PATCH|DELETE)\s+https?://|"
            r"(?<![A-Za-z0-9_])(?:api|http)(?![A-Za-z0-9_]).{0,16}"
            r"(?:采集|获取|请求|调用|接口|响应|collection|fetch|request|response))",
            hard_runtime_signal_text,
            re.IGNORECASE,
        )
    )
    external_runtime = bool(
        re.search(
            r"(?:在|从|访问|打开).{0,16}(?:网页|官网|网站|公开查询入口|公开入口|外部数据源)"
            r".{0,32}(?:查询|检索|获取|提取|采集|抓取)"
            r"|(?:查询|检索|获取|提取|采集|抓取).{0,32}(?:网页|官网|网站|公开查询入口|公开入口|外部数据源)",
            runtime_signal_text,
            re.IGNORECASE,
        )
        or re.search(
            r"https?://[^\s\"'，。；;]{1,180}.{0,32}(?:查询|检索|获取|提取|采集|抓取)"
            r"|(?:查询|检索|获取|提取|采集|抓取).{0,32}https?://[^\s\"'，。；;]{1,180}",
            runtime_signal_text,
            re.IGNORECASE,
        )
    )
    if capability_evidence_aware and not (browser_runtime or api_runtime):
        external_runtime = external_runtime or bool(
            re.search(
                r"(?:\bplaywright\b|\bselenium\b|\bpuppeteer\b|浏览器(?:自动化|采集|交互|操作)"
                r"|\brequests\b|\bhttpx\b|\baiohttp\b|(?<![A-Za-z0-9_])api(?![A-Za-z0-9_]))",
                runtime_signal_text,
                re.IGNORECASE,
            )
        )
    # Explicit automation terms win over a generic manual wording, while a
    # pure manual/SOP contract never acquires a runtime merely from domain
    # nouns such as CRM or bank.
    if explicit_manual_boundary and not re.search(
        r"(?:playwright|selenium|puppeteer|选择器|selector|自动(?:从|化)|实时(?:从|在线))",
        runtime_signal_text,
        re.IGNORECASE,
    ):
        browser_runtime = False
        api_runtime = False
        external_runtime = False
    optional: set[str] = set()
    both_explicit = browser_runtime and api_runtime
    api_fallback_signal = bool(
        re.search(
            r"(?:API|接口)[^，。；;\"\]]{0,24}(?:备选|备用|降级|fallback)"
            r"|(?:备选|备用|降级|fallback)[^，。；;\"\]]{0,24}(?:API|接口)",
            runtime_signal_text,
            re.IGNORECASE,
        )
    )
    browser_fallback_signal = bool(
        re.search(
            r"(?:浏览器|Playwright)[^，。；;\"\]]{0,24}(?:备选|备用|降级|fallback)"
            r"|(?:备选|备用|降级|fallback)[^，。；;\"\]]{0,24}(?:浏览器|Playwright)",
            runtime_signal_text,
            re.IGNORECASE,
        )
    )
    # A fallback mention is optional capability evidence even when it does not
    # use a hard-runtime verb such as "call API". Require the opposite primary
    # runtime so a standalone mention cannot invent an executable capability.
    api_is_fallback = api_fallback_signal and browser_runtime
    browser_is_fallback = browser_fallback_signal and api_runtime
    explicitly_both = both_explicit and bool(
        re.search(
            r"(?:浏览器|Playwright)[^，。；;\"\]]{0,32}(?:与|和|以及|同时)[^，。；;\"\]]{0,32}(?:API|接口)"
            r"|(?:API|接口)[^，。；;\"\]]{0,32}(?:与|和|以及|同时)[^，。；;\"\]]{0,32}(?:浏览器|Playwright)"
            r"|(?:两者|二者|均|都|both)[^，。；;\"\]]{0,24}(?:必须|需要|required)",
            runtime_signal_text,
            re.IGNORECASE,
        )
    )
    if api_is_fallback and not browser_is_fallback:
        api_runtime = False
        optional.add("api_runtime")
    elif browser_is_fallback and not api_is_fallback:
        browser_runtime = False
        optional.add("browser_runtime")

    ambiguous = bool(
        browser_runtime
        and api_runtime
        and not explicitly_both
        and not api_is_fallback
        and not browser_is_fallback
    )
    capabilities: dict[str, bool] = {}
    if browser_runtime:
        capabilities["browser_runtime"] = True
    if api_runtime:
        capabilities["api_runtime"] = True
    if external_runtime and not (browser_runtime or api_runtime):
        capabilities["external_runtime"] = True
    if browser_runtime or api_runtime or external_runtime:
        capabilities["collection_script"] = True
    if scenario_contract_requires_runtime_screenshot_output(source):
        capabilities["runtime_screenshot_output"] = True
    return capabilities, optional, ambiguous


def scenario_required_capabilities(value: Any) -> dict[str, bool]:
    """Compile only material-explicit hard runtime capabilities."""

    return _scenario_capability_profile(value)[0]


def scenario_capability_requirements(
    required_capabilities: Any,
    pending_decisions: Any,
    *,
    optional_capabilities: set[str] | None = None,
) -> tuple[dict[str, bool], dict[str, Any]]:
    """Compile hard, alternative and optional capability requirements.

    Prose may mention several supported acquisition mechanisms while a typed
    HITL field asks the user to choose the actual one.  Treating the union of
    those mechanisms as an AND requirement made ``playwright_only`` require an
    unrelated API entrypoint.  This compiler uses the registered option
    ontology, never presentation prose, to distinguish capabilities shared by
    every option from alternatives and optional capabilities.
    """

    required = {
        str(key): bool(value)
        for key, value in (required_capabilities or {}).items()
        if isinstance(value, bool) and value
    } if isinstance(required_capabilities, dict) else {}
    hard = {key for key, enabled in required.items() if enabled}
    optional: set[str] = set(optional_capabilities or ())
    any_of: list[list[str]] = []
    controlled_names = {
        "browser_runtime",
        "api_runtime",
        "external_runtime",
        "collection_script",
    }

    for decision in pending_decisions if isinstance(pending_decisions, list) else []:
        if not isinstance(decision, dict):
            continue
        concept = normalize_decision_concept(decision.get("semanticConcept"))
        if concept not in {"acquisition_mode", "external_system_access_mode"}:
            continue
        option_capabilities: list[dict[str, bool]] = []
        for option in decision.get("options") or []:
            if not isinstance(option, dict):
                continue
            semantic_value = option.get("semanticValue", option.get("value"))
            option_capabilities.append(
                decision_capabilities(concept, semantic_value)
            )
        if len(option_capabilities) < 2:
            continue

        controlled = {
            name
            for capabilities in option_capabilities
            for name in capabilities
            if name in controlled_names
        }
        for name in controlled:
            values = [bool(item.get(name)) for item in option_capabilities]
            if all(values):
                hard.add(name)
                optional.discard(name)
            else:
                hard.discard(name)
                if any(values):
                    optional.add(name)

        runtime_sets = [
            {
                name
                for name in ("browser_runtime", "api_runtime")
                if capabilities.get(name) is True
            }
            for capabilities in option_capabilities
        ]
        if runtime_sets and all(runtime_sets) and not set.intersection(*runtime_sets):
            group = sorted(set.union(*runtime_sets))
            if len(group) > 1 and group not in any_of:
                any_of.append(group)

    normalized_required = {name: True for name in sorted(hard)}
    requirements = {
        "allOf": sorted(hard),
        "anyOf": any_of,
        "optional": sorted(
            optional
            - hard
            - {capability for group in any_of for capability in group}
        ),
    }
    return normalized_required, requirements


def _compile_decision_need(
    value: Any,
    *,
    index: int = 1,
) -> tuple[dict[str, Any] | None, list[str]]:
    """Compile one public Scenario conflict into a platform-owned decision."""

    if not isinstance(value, dict):
        return None, []
    title = _text(value.get("title"), limit=300)
    if not title:
        return None, []
    declared_decision_id = _text(
        value.get("decisionId") or value.get("id"),
        limit=128,
    )
    decision_id = declared_decision_id or _decision_id(title)
    raw_options = value.get("options") if isinstance(value.get("options"), list) else []
    options = [
        option
        for raw in raw_options[:SCENARIO_DECISION_OPTION_MAX_ITEMS]
        if (option := _normalize_decision_option(raw)) is not None
    ]
    field_type = _text(value.get("type"), limit=32).lower()
    if field_type not in _DECISION_TYPES:
        field_type = "select" if len(options) >= 2 else "text"
    if field_type == "boolean" and options:
        boolean_values = {
            "true": True,
            "false": False,
        }
        normalized_values = [
            boolean_values.get(str(option.get("value")).strip().lower())
            if not isinstance(option.get("value"), bool)
            else option.get("value")
            for option in options
        ]
        if all(isinstance(item, bool) for item in normalized_values):
            options = [
                {**option, "value": normalized_value}
                for option, normalized_value in zip(options, normalized_values)
            ]
    if field_type == "select" and len(options) < 2:
        field_type = "text"
        options = []
    requires_explicit_selection = value.get("requiresExplicitSelection") is True
    default = value.get("defaultValue")
    if field_type == "boolean" and not isinstance(default, bool):
        default = {
            "true": True,
            "false": False,
        }.get(str(default).strip().lower(), default)
    if field_type == "select" and requires_explicit_selection:
        default = None
    elif field_type == "select" and default not in {item.get("value") for item in options}:
        default = options[0]["value"]
    elif field_type == "boolean" and not isinstance(default, bool):
        default = False
    elif field_type == "text" and not isinstance(default, str):
        default = ""

    result: dict[str, Any] = {
        "decisionId": decision_id,
        "title": title,
        "description": _text(value.get("description") or title, limit=600),
        "type": field_type,
        "defaultValue": default,
        "options": options,
        "source": "scenario_contract",
    }
    if requires_explicit_selection:
        result["requiresExplicitSelection"] = True
    issues: list[str] = []
    evidence_refs = _structured_evidence_refs(value.get("evidenceRefs"))
    invalid_evidence_refs = _invalid_evidence_refs(value.get("evidenceRefs"))
    if invalid_evidence_refs:
        issues.append(
            f"pendingDecisions[{index}].evidenceRefs contains invalid references: "
            + ", ".join(invalid_evidence_refs[:5])
            + "; use inputs/<path>, material:<id>, or platform:<id>"
        )
    if evidence_refs:
        result["evidenceRefs"] = evidence_refs
    source_quote = _text(value.get("sourceQuote"), limit=2000)
    if source_quote:
        result["sourceQuote"] = source_quote
    if len(raw_options) > SCENARIO_DECISION_OPTION_MAX_ITEMS:
        issues.append(
            f"pendingDecisions[{index}] options exceeds "
            f"{SCENARIO_DECISION_OPTION_MAX_ITEMS} items"
        )

    # A registered machine field id is stronger than an option-domain guess.
    # Only fall back to the conservative option inference for model-authored
    # fields whose identity is opaque; this preserves explicit platform
    # semantics while keeping manual/file/api source choices business-owned.
    declared_semantic_concept = _text(value.get("semanticConcept"), limit=128)
    declared_business_concept = (
        declared_semantic_concept
        if declared_semantic_concept.startswith("business:")
        and declared_semantic_concept.removeprefix("business:")
        else ""
    )
    declared_concept = normalize_decision_concept(declared_semantic_concept)
    concept = declared_concept or decision_semantic_concept(
        decision_id,
        title,
        result.get("description"),
        options,
    )
    if concept:
        if concept in {"acquisition_mode", "external_system_access_mode"}:
            canonical_domain = {
                canonical
                for option in options
                if (
                    canonical := canonical_decision_option_value(concept, option)[0]
                )
            }
            if (
                {"browser", "manual", "hybrid"}.issubset(canonical_domain)
                and "api" not in canonical_domain
            ):
                for option in options:
                    canonical, _source = canonical_decision_option_value(
                        concept,
                        option,
                    )
                    if canonical != "hybrid":
                        continue
                    previous_value = option.get("value")
                    option["value"] = "browser_with_manual_fallback"
                    if result.get("defaultValue") == previous_value:
                        result["defaultValue"] = option["value"]
        canonical_options: list[tuple[dict[str, Any], str, str | None]] = []
        for option in options:
            canonical, source = canonical_decision_option_value(concept, option)
            if canonical is None:
                canonical_options = []
                break
            canonical_options.append((option, canonical, source))
        if canonical_options:
            result["semanticConcept"] = concept
            for option, canonical, source in canonical_options:
                option["semanticValue"] = canonical
                if source:
                    option["semanticValueSource"] = source
        else:
            concept = None
    if not concept:
        result["semanticConcept"] = declared_business_concept or f"business:{decision_id}"
    return result, issues


def _promote_single_acquisition_decision(
    decisions: list[dict[str, Any]],
) -> bool:
    """Promote one opaque acquisition field only inside an external-data context."""

    candidates: list[tuple[dict[str, Any], list[tuple[dict[str, Any], str, str | None]]]] = []
    for decision in decisions:
        if normalize_decision_concept(decision.get("semanticConcept")) is not None:
            continue
        if decision_context_is_authentication(
            decision.get("decisionId"),
            decision.get("title"),
            "",
        ):
            continue
        options = [
            option
            for option in decision.get("options") or []
            if isinstance(option, dict)
        ]
        canonical_options: list[tuple[dict[str, Any], str, str | None]] = []
        for option in options:
            canonical, source = canonical_decision_option_value(
                "acquisition_mode",
                option,
            )
            if canonical is None:
                canonical_options = []
                break
            canonical_options.append((option, canonical, source))
        domain = {canonical for _option, canonical, _source in canonical_options}
        if len(canonical_options) >= 2 and domain & {"api", "browser", "hybrid"}:
            candidates.append((decision, canonical_options))
    if len(candidates) != 1:
        return False

    decision, canonical_options = candidates[0]
    domain = {canonical for _option, canonical, _source in canonical_options}
    if {"browser", "manual", "hybrid"}.issubset(domain) and "api" not in domain:
        normalized_options: list[tuple[dict[str, Any], str, str | None]] = []
        for option, canonical, source in canonical_options:
            if canonical == "hybrid":
                previous_value = option.get("value")
                option["value"] = "browser_with_manual_fallback"
                if decision.get("defaultValue") == previous_value:
                    decision["defaultValue"] = option["value"]
                canonical = "browser_with_manual_fallback"
                source = "platform:fallback-normalization"
            normalized_options.append((option, canonical, source))
        canonical_options = normalized_options
    decision["semanticConcept"] = "acquisition_mode"
    for option, canonical, source in canonical_options:
        option["semanticValue"] = canonical
        if source:
            option["semanticValueSource"] = source
    return True


def normalize_decision_need(value: Any, *, index: int = 1) -> dict[str, Any] | None:
    """Normalize one DecisionNeed; ScenarioContract compilation owns errors."""

    return _compile_decision_need(value, index=index)[0]


def scenario_decision_needs(value: Any) -> list[dict[str, Any]]:
    source = value if isinstance(value, dict) else {}
    raw_values = source.get("pendingDecisions")
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw in enumerate(raw_values if isinstance(raw_values, list) else [], start=1):
        item = normalize_decision_need(raw, index=index)
        if item is None or item["decisionId"] in seen:
            continue
        seen.add(item["decisionId"])
        result.append(item)
    return _filter_scenario_decision_needs(
        result,
        [
            item
            for item in source.get("resolvedRequirements") or []
            if isinstance(item, dict)
        ],
    )


def _resolved_requirement_decision_overlaps(
    requirement: dict[str, Any],
    decision: dict[str, Any],
) -> bool:
    """Return whether one material fact already owns a pending identity."""

    if requirement.get("overrideAllowed") is True:
        return False
    requirement_id = str(requirement.get("requirementId") or "").strip()
    requirement_concept = str(requirement.get("concept") or "").strip()
    requirement_projection = project_decision_value(
        requirement_id or requirement_concept,
        requirement_concept,
        requirement.get("value"),
    )
    requirement_concepts = set(requirement_projection)
    normalized_requirement_concept = (
        normalize_decision_concept(requirement_concept)
        or infer_decision_concept(requirement_id)
    )
    if normalized_requirement_concept:
        requirement_concepts.add(normalized_requirement_concept)

    decision_id = str(decision.get("decisionId") or "").strip()
    decision_concept = str(decision.get("semanticConcept") or "").strip()
    normalized_decision_concept = (
        normalize_decision_concept(decision_concept)
        or infer_decision_concept(decision_id, decision.get("options"))
    )
    return bool(
        (requirement_id and requirement_id == decision_id)
        or (
            normalized_decision_concept
            and normalized_decision_concept in requirement_concepts
        )
    )


def _filter_scenario_decision_needs(
    decisions: list[dict[str, Any]],
    requirements: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Keep only unresolved user-facing decisions.

    Scenario output is model-authored candidate data.  Redundant questions and
    platform validation policies are deterministic normalization concerns, not
    reasons to reject an otherwise valid ScenarioContract and spend its only
    correction attempt.
    """

    result: list[dict[str, Any]] = []
    for decision in decisions:
        if decision_concept_is_platform_managed(
            decision.get("decisionId"),
            decision.get("semanticConcept"),
            decision.get("options"),
        ):
            continue
        if any(
            _resolved_requirement_decision_overlaps(requirement, decision)
            for requirement in requirements
        ):
            continue
        result.append(decision)
    return result


def normalize_scenario_contract(value: Any) -> tuple[dict[str, Any], list[str]]:
    """Compile model output into a bounded, versioned scenario checkpoint."""

    source = value if isinstance(value, dict) else {}
    if "facts" in source or "conflicts" in source:
        source = _compact_scenario_source(source)
    issues: list[str] = []
    raw_purpose = str(source.get("purpose") or "")
    purpose = _text(raw_purpose, limit=SCENARIO_PURPOSE_MAX_CHARS)
    if not purpose:
        issues.append("purpose is required")
    elif len(raw_purpose) > SCENARIO_PURPOSE_MAX_CHARS:
        issues.append(f"purpose exceeds {SCENARIO_PURPOSE_MAX_CHARS} characters")
    result: dict[str, Any] = {
        "schemaVersion": SCENARIO_CONTRACT_SCHEMA_VERSION,
        "skillName": _text(source.get("skillName"), limit=128),
        "displayName": _text(source.get("displayName"), limit=128),
        "purpose": purpose,
    }
    if "capabilityEvidence" in source:
        result["capabilityEvidence"] = [
            {
                "kind": _text(item.get("kind"), limit=64),
                "evidenceRefs": _structured_evidence_refs(
                    item.get("evidenceRefs")
                ),
                "sourceQuote": _text(item.get("sourceQuote"), limit=300),
                "evidenceStatus": (
                    "quoted"
                    if str(item.get("sourceQuote") or "").strip()
                    else "reference_only"
                ),
            }
            for item in source.get("capabilityEvidence") or []
            if isinstance(item, dict)
        ]
    for field in _LIST_FIELDS:
        values = source.get(field)
        max_items, text_limit = SCENARIO_LIST_LIMITS[field]
        if isinstance(values, list) and len(values) > max_items:
            issues.append(f"{field} exceeds {max_items} items")
        normalized = [
            item
            for raw in values[:max_items] if isinstance(values, list)
            if (
                item := _normalize_item(
                    raw,
                    text_limit=text_limit,
                )
            ) not in (None, "", {}, [])
        ] if isinstance(values, list) else []
        result[field] = normalized
    capability_source = {
        **source,
        **{field: result[field] for field in _LIST_FIELDS},
    }
    (
        result["requiredCapabilities"],
        optional_capabilities,
        ambiguous_acquisition,
    ) = _scenario_capability_profile(capability_source)
    result["resolvedRequirements"] = []
    seen_requirements: set[str] = set()
    result["businessRules"] = []
    seen_rules: set[str] = set()
    material_facts = (
        source.get("materialFacts")
        if isinstance(source.get("materialFacts"), list)
        else []
    )
    if len(material_facts) > SCENARIO_MATERIAL_FACT_MAX_ITEMS:
        issues.append(
            f"materialFacts exceeds {SCENARIO_MATERIAL_FACT_MAX_ITEMS} items"
        )
    for index, raw in enumerate(
        material_facts[:SCENARIO_MATERIAL_FACT_MAX_ITEMS],
        start=1,
    ):
        target, fact, fact_issues = _normalize_material_fact(raw, index=index)
        issues.extend(fact_issues)
        if fact is None:
            continue
        if target == "resolvedRequirements":
            fact_id = str(fact.get("requirementId") or "")
            if fact_id in seen_requirements:
                issues.append(f"materialFacts repeats factId {fact_id}")
                continue
            seen_requirements.add(fact_id)
            result["resolvedRequirements"].append(fact)
        elif target == "businessRules":
            fact_id = str(fact.get("ruleId") or "")
            if fact_id in seen_rules:
                issues.append(f"materialFacts repeats factId {fact_id}")
                continue
            seen_rules.add(fact_id)
            result["businessRules"].append(fact)
    for index, raw in enumerate(
        source.get("resolvedRequirements")
        if isinstance(source.get("resolvedRequirements"), list)
        else [],
        start=1,
    ):
        requirement, requirement_issues = _normalize_resolved_requirement(raw, index=index)
        issues.extend(requirement_issues)
        if requirement is None:
            continue
        requirement_id = str(requirement.get("requirementId") or "")
        if requirement_id in seen_requirements:
            issues.append(f"resolvedRequirements repeats requirementId {requirement_id}")
            continue
        seen_requirements.add(requirement_id)
        result["resolvedRequirements"].append(requirement)
    for index, raw in enumerate(
        source.get("businessRules")
        if isinstance(source.get("businessRules"), list)
        else [],
        start=1,
    ):
        rule, rule_issues = _normalize_business_rule(raw, index=index)
        issues.extend(rule_issues)
        if rule is None:
            continue
        rule_id = str(rule.get("ruleId") or "")
        if rule_id in seen_rules:
            issues.append(f"businessRules repeats ruleId {rule_id}")
            continue
        seen_rules.add(rule_id)
        result["businessRules"].append(rule)
    result["pendingDecisions"] = []
    seen_decisions: set[str] = set()
    pending_values = (
        source.get("pendingDecisions")
        if isinstance(source.get("pendingDecisions"), list)
        else []
    )
    if len(pending_values) > SCENARIO_PENDING_DECISION_MAX_ITEMS:
        issues.append(
            f"pendingDecisions exceeds {SCENARIO_PENDING_DECISION_MAX_ITEMS} items"
        )
    for index, raw in enumerate(
        pending_values[:SCENARIO_PENDING_DECISION_MAX_ITEMS],
        start=1,
    ):
        decision, decision_issues = _compile_decision_need(raw, index=index)
        issues.extend(decision_issues)
        if decision is None or decision["decisionId"] in seen_decisions:
            continue
        seen_decisions.add(decision["decisionId"])
        result["pendingDecisions"].append(decision)
    has_runtime_capability_evidence = bool(
        {
            name
            for name, enabled in result["requiredCapabilities"].items()
            if enabled is True
        }
        & {
            "api_runtime",
            "browser_runtime",
            "external_runtime",
        }
    ) or bool(optional_capabilities) or ambiguous_acquisition
    if not has_runtime_capability_evidence:
        result["pendingDecisions"] = [
            item
            for item in result["pendingDecisions"]
            if normalize_decision_concept(item.get("semanticConcept"))
            not in {"acquisition_mode", "external_system_access_mode"}
        ]
    generic_external_acquisition = bool(
        result["requiredCapabilities"].get("external_runtime")
        and not result["requiredCapabilities"].get("browser_runtime")
        and not result["requiredCapabilities"].get("api_runtime")
    )
    if (ambiguous_acquisition or generic_external_acquisition) and not any(
        normalize_decision_concept(item.get("semanticConcept")) == "acquisition_mode"
        for item in result["pendingDecisions"]
        if isinstance(item, dict)
    ):
        _promote_single_acquisition_decision(result["pendingDecisions"])
    if (ambiguous_acquisition or generic_external_acquisition) and not any(
        normalize_decision_concept(item.get("semanticConcept")) == "acquisition_mode"
        for item in result["pendingDecisions"]
        if isinstance(item, dict)
    ):
        if ambiguous_acquisition:
            acquisition_description = (
                "材料明确提到 API/HTTP 与浏览器两种自动采集方式，但未说明实际实现选择；"
                "请选择一种或确认两种都实现。"
            )
            acquisition_default = None
            acquisition_options = [
                {"value": "api", "label": "API/HTTP 采集"},
                {"value": "browser", "label": "浏览器自动化"},
                {"value": "hybrid", "label": "两种方式都实现"},
            ]
        else:
            acquisition_description = (
                "材料要求从外部来源获取数据，但没有明确要求 Skill 自动对接；"
                "请选择由人工/文件提供结果，或显式启用 API/browser runtime。"
            )
            acquisition_default = "manual"
            acquisition_options = [
                {"value": "manual", "label": "人工提供外部结果"},
                {"value": "file", "label": "文件导入外部结果"},
                {"value": "api", "label": "API/HTTP 采集"},
                {"value": "browser", "label": "浏览器自动化"},
                {"value": "hybrid", "label": "两种方式都实现"},
            ]
        automatic, automatic_issues = _compile_decision_need(
            {
                "decisionId": "acquisition_mode",
                "semanticConcept": "acquisition_mode",
                "title": "外部数据采集方式",
                "description": acquisition_description,
                "type": "select",
                "defaultValue": acquisition_default,
                "requiresExplicitSelection": ambiguous_acquisition,
                "options": acquisition_options,
                "evidenceRefs": ["platform:capability-ambiguity"],
            },
            index=len(result["pendingDecisions"]) + 1,
        )
        issues.extend(automatic_issues)
        if automatic is not None:
            result["pendingDecisions"].append(automatic)
    result["pendingDecisions"] = _filter_scenario_decision_needs(
        result["pendingDecisions"],
        result["resolvedRequirements"],
    )
    (
        result["requiredCapabilities"],
        result["capabilityRequirements"],
    ) = scenario_capability_requirements(
        result["requiredCapabilities"],
        result["pendingDecisions"],
        optional_capabilities=optional_capabilities,
    )
    if not result["triggers"]:
        issues.append("at least one trigger is required")
    if not result["inputs"]:
        issues.append("at least one input is required")
    if not result["outputs"]:
        issues.append("at least one output is required")
    if not result["steps"]:
        issues.append("at least one workflow step is required")
    pending_decision_text = json.dumps(
        result["pendingDecisions"], ensure_ascii=False, sort_keys=True, default=str
    )
    has_rule_decision = bool(
        _EXECUTABLE_BUSINESS_RULE_PATTERN.search(pending_decision_text)
    )
    if (
        scenario_contract_requires_business_rules(result)
        and not result["businessRules"]
        and not has_rule_decision
    ):
        issues.append(
            "executable numeric/classification semantics require either lossless businessRules with evidenceRefs/sourceQuote or a behavior-level conflict"
        )
    semantic = json.dumps(
        _scenario_semantic_payload(result),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    result["semanticHash"] = hashlib.sha256(semantic.encode("utf-8")).hexdigest()
    return result, issues


def scenario_draft_shape_issues(value: Any) -> list[str]:
    """Validate the compact ScenarioDraft beyond JSON-string transport."""

    source = normalize_scenario_draft_surface(value)
    raw_facts = source.get("facts")
    precise_issues: list[str] = []
    if isinstance(raw_facts, list):
        allowed_kinds = ", ".join(sorted(_SCENARIO_FACT_KINDS))
        for index, item in enumerate(raw_facts):
            if not isinstance(item, dict):
                precise_issues.append(
                    f"ScenarioDraft.facts[{index}] must be a JSON object"
                )
                continue
            kind = str(item.get("kind") or "").strip().lower().replace("-", "_")
            if kind and kind not in _SCENARIO_FACT_KINDS:
                suffix = (
                    "; express external-system URLs, login and network requirements as kind=dependency"
                    if kind == "external_system"
                    else ""
                )
                precise_issues.append(
                    f"ScenarioDraft.facts[{index}].kind {kind!r} is unsupported; "
                    f"allowed kinds: {allowed_kinds}{suffix}"
                )
            if not item.get("evidenceRefs"):
                precise_issues.append(
                    f"ScenarioDraft.facts[{index}].evidenceRefs must contain at least one typed material reference"
                )
            if kind == "rule" and not str(item.get("sourceQuote") or "").strip():
                precise_issues.append(
                    f"ScenarioDraft.facts[{index}] with kind={kind} requires sourceQuote"
                )
            fact_value = item.get("value")
            fact_value_bytes = len(
                json.dumps(
                    fact_value,
                    ensure_ascii=False,
                    separators=(",", ":"),
                    default=str,
                ).encode("utf-8")
            )
            if fact_value_bytes > SCENARIO_FACT_VALUE_MAX_BYTES:
                precise_issues.append(
                    f"ScenarioDraft.facts[{index}].value is {fact_value_bytes} bytes; "
                    f"keep one reusable fact within {SCENARIO_FACT_VALUE_MAX_BYTES} bytes"
                )
            if (
                isinstance(fact_value, str)
                and '"kind"' in fact_value
                and ('"evidenceRefs"' in fact_value or "'evidenceRefs'" in fact_value)
            ):
                precise_issues.append(
                    f"ScenarioDraft.facts[{index}].value appears to contain serialized fact objects; "
                    "put every fact in its own facts[] JSON object instead of concatenating JSON into value"
                )

    raw_conflicts = source.get("conflicts")
    if isinstance(raw_conflicts, list):
        for index, item in enumerate(raw_conflicts):
            if not isinstance(item, dict):
                continue
            description = item.get("description")
            if (
                isinstance(description, str)
                and '"conflicts"' in description
                and ('"facts"' in description or '"evidenceRefs"' in description)
            ):
                precise_issues.append(
                    f"ScenarioDraft.conflicts[{index}].description appears to contain serialized ScenarioDraft objects; "
                    "keep each conflict as its own conflicts[] JSON object"
                )

    issues = json_schema_issues(
        source,
        scenario_contract_tool_schema(),
        label="ScenarioDraft",
    )
    facts = source.get("facts") if isinstance(source, dict) else None
    kinds = {
        str(item.get("kind") or "").strip().lower().replace("-", "_")
        for item in facts or []
        if isinstance(item, dict)
    }
    required_kinds = ("purpose", "trigger", "input", "output", "step")
    missing = [kind for kind in required_kinds if kind not in kinds]
    if missing:
        issues.append(
            "facts must include at least one of each required kind: "
            + ", ".join(missing)
        )
    return list(dict.fromkeys([*precise_issues, *issues]))


def scenario_contract_tool_schema() -> dict[str, Any]:
    evidence_refs = {
        "type": "array",
        "minItems": 1,
        "maxItems": 6,
        "uniqueItems": True,
        "items": {
            "type": "string",
            "pattern": r"^(?:inputs/[^\s]+|material:[^\s]+|platform:[^\s]+)$",
        },
    }
    decision_option = {
        "type": "object",
        "properties": {
            "value": {
                "oneOf": [
                    {"type": "string", "maxLength": 100},
                    {"type": "number"},
                    {"type": "boolean"},
                ]
            },
            "label": {"type": "string", "minLength": 1, "maxLength": 180},
            "description": {"type": "string", "maxLength": 240},
        },
        "required": ["value", "label"],
        "additionalProperties": False,
    }
    decision_need = {
        "type": "object",
        "properties": {
            "title": {"type": "string", "minLength": 1, "maxLength": 180},
            "description": {"type": "string", "maxLength": 300},
            "type": {"type": "string", "enum": sorted(_DECISION_TYPES)},
            "defaultValue": {
                "oneOf": [
                    {"type": "string", "maxLength": 300},
                    {"type": "number"},
                    {"type": "boolean"},
                ]
            },
            "options": {
                "type": "array",
                "maxItems": SCENARIO_DECISION_OPTION_MAX_ITEMS,
                "items": decision_option,
            },
            # Optional provenance explains why a user choice is unresolved;
            # it is not part of the HITL answer itself.
            "evidenceRefs": evidence_refs,
            "sourceQuote": {"type": "string", "maxLength": 2000},
        },
        "required": ["title", "type"],
        "additionalProperties": False,
    }
    fact_properties = {
        "kind": {"type": "string", "enum": sorted(_SCENARIO_NAVIGATION_FACT_KINDS)},
        "label": {"type": "string", "maxLength": 128},
        "value": {},
        "ruleKind": {"type": "string", "maxLength": 64},
        "description": {"type": "string", "maxLength": 300},
        "evidenceRefs": evidence_refs,
        "sourceQuote": {"type": "string", "maxLength": 300},
        "overrideAllowed": {"type": "boolean"},
    }
    navigation_fact = {
        "type": "object",
        "properties": fact_properties,
        "required": ["kind", "value", "evidenceRefs"],
        "additionalProperties": False,
    }
    requirement_fact = {
        "type": "object",
        "properties": {
            **fact_properties,
            "kind": {"type": "string", "enum": ["requirement"]},
        },
        "required": ["kind", "value", "evidenceRefs"],
        "additionalProperties": False,
    }
    rule_fact = {
        "type": "object",
        "properties": {
            **fact_properties,
            "kind": {"type": "string", "enum": ["rule"]},
        },
        "required": ["kind", "value", "evidenceRefs", "sourceQuote"],
        "additionalProperties": False,
    }
    scenario_fact = {
        "oneOf": [navigation_fact, requirement_fact, rule_fact],
    }
    return {
        "type": "object",
        "properties": {
            "skillName": {"type": "string", "maxLength": 128},
            "displayName": {"type": "string", "maxLength": 128},
            "facts": {
                "type": "array",
                "minItems": 1,
                "maxItems": 80,
                "items": scenario_fact,
            },
            "conflicts": {
                "type": "array",
                "maxItems": SCENARIO_PENDING_DECISION_MAX_ITEMS,
                "items": decision_need,
            },
        },
        "required": ["facts"],
        "additionalProperties": False,
    }


def scenario_contract_requires_dimension_runtime(value: Any) -> bool:
    """Return whether the declared work owns scoring/dimension semantics."""

    contract, issues = normalize_scenario_contract(value)
    if issues:
        return False
    semantic_text = json.dumps(
        {
            key: contract.get(key)
            for key in (
                "purpose", "outputs", "steps", "scriptRequirements", "acceptanceCriteria", "businessRules",
            )
        },
        ensure_ascii=False,
    ).lower()
    return any(
        marker in semantic_text
        for marker in (
            "评分", "得分", "评级", "权重", "维度",
            "score", "scoring", "rating", "weighted", "dimension",
        )
    )


def _scenario_output_text(value: Any) -> str:
    source = value if isinstance(value, dict) else {}
    outputs = source.get("outputs") if isinstance(source.get("outputs"), list) else []
    rules = source.get("businessRules") if isinstance(source.get("businessRules"), list) else []
    return json.dumps([*outputs, *rules], ensure_ascii=False, sort_keys=True).lower()


def scenario_contract_requires_runtime_screenshot_output(value: Any) -> bool:
    """Infer a screenshot-file requirement only from structured outputs."""

    text = _scenario_output_text(value)
    screenshot = bool(re.search(r"(?:截图|截屏|screen\s*shot|screenshot)", text, re.IGNORECASE))
    explicitly_excluded = bool(
        re.search(
            r"(?:不|无需|禁止)(?:生成|导出|输出|保存)?.{0,8}(?:截图|截屏|screen\s*shot|screenshot)|"
            r"(?:截图|截屏|screen\s*shot|screenshot).{0,8}(?:仅用于验证|不保存|不输出|不导出)",
            text,
            re.IGNORECASE,
        )
    )
    # The source is the typed ``outputs`` list, not arbitrary workflow prose.
    # Declaring screenshots there already means they are delivered runtime
    # artifacts; requiring another word such as "file" lost concise outputs
    # like "每个项目 4 张页面截图".
    return screenshot and not explicitly_excluded


def scenario_contract_output_mode(value: Any) -> str | None:
    """Project supported formal output modes from structured outputs."""

    source = value if isinstance(value, dict) else {}
    outputs = source.get("outputs") if isinstance(source.get("outputs"), list) else []
    has_markdown = False
    has_json = False
    auxiliary_json_pattern = re.compile(
        r"(?:校验|验证|诊断|日志|调试|中间|内部|缓存|快照|原始|采集)"
        r"|(?:validation|verification|diagnostic|debug|log|intermediate|internal|cache|snapshot|raw|collection)",
        re.IGNORECASE,
    )
    for output in outputs:
        if isinstance(output, dict):
            explicit_format = " ".join(
                str(output.get(key) or "")
                for key in ("format", "mediaType", "mimeType", "type")
            ).lower()
            text = json.dumps(output, ensure_ascii=False, sort_keys=True).lower()
            explicit_json = bool(re.search(r"(?:\bjsonl?\b|\.jsonl?\b|application/json)", explicit_format))
            explicit_markdown = bool(re.search(r"(?:markdown|\.md\b|text/markdown)", explicit_format))
        else:
            text = str(output or "").lower()
            explicit_json = False
            explicit_markdown = False
        has_markdown = has_markdown or explicit_markdown or bool(
            re.search(r"(?:markdown|\.md\b)", text, re.IGNORECASE)
        )
        json_marker = explicit_json or bool(
            re.search(r"(?:\bjsonl?\b|\.jsonl?\b)", text, re.IGNORECASE)
        )
        # JSON used for validation transport, raw collection snapshots or
        # diagnostics is not a user-selected formal delivery format.  Explicit
        # structured ``format: JSON`` declarations remain authoritative.
        if json_marker and (explicit_json or not auxiliary_json_pattern.search(text)):
            has_json = True
    if has_markdown and has_json:
        return "json_and_markdown"
    if has_markdown:
        return "markdown"
    if has_json:
        return "json"
    output_text = json.dumps(outputs, ensure_ascii=False, sort_keys=True).lower()
    if re.search(r"(?:\bpdf\b|\.pdf\b)", output_text, re.IGNORECASE):
        return "pdf"
    return None


__all__ = [
    "SCENARIO_CONTRACT_PATH",
    "SCENARIO_CONTRACT_SCHEMA_VERSION",
    "SCENARIO_DRAFT_MAX_BYTES",
    "SCENARIO_FACT_VALUE_MAX_BYTES",
    "normalize_scenario_contract",
    "normalize_decision_need",
    "scenario_draft_shape_issues",
    "scenario_decision_needs",
    "scenario_contract_requires_dimension_runtime",
    "scenario_contract_requires_runtime_screenshot_output",
    "scenario_contract_output_mode",
    "scenario_contract_requires_business_rules",
    "scenario_required_capabilities",
    "scenario_contract_tool_schema",
]
