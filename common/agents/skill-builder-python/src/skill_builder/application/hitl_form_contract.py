"""Normalization and validation contract for Skill Builder HITL forms."""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any

from skill_builder.application.hitl_forms import (
    HITL_STRUCTURED_FRAGMENT_PATTERN,
    _decision_field_allows_identifier_label,
    _decision_option_display_label,
    _decision_option_is_machine_only,
    _decision_option_is_placeholder,
    _decision_option_transport_parts,
    _decision_value_label,
    _humanize_decision_id,
    _merge_duplicate_decision_fields,
    _readable_hitl_text,
    _structured_hitl_default_object,
)
from skill_builder.domain.contract_constants import KNOWN_DECISION_CAPABILITIES
from skill_builder.domain.contract_decisions import (
    build_decision_contract,
    decision_evidence_status,
    decision_semantic_identity,
    decision_semantic_value,
    decision_option_is_unknown,
    normalize_capability_condition,
)
from skill_builder.domain.decision_form import decision_form_integrity_issues
from skill_builder.domain.contract_constants import canonical_decision_capability
from skill_builder.domain.decision_registry import (
    canonical_decision_option_value,
    decision_capability_is_allowed,
    decision_option_catalog,
    decision_value_is_valid,
    projected_decision_capabilities,
)
from skill_builder.domain.decision_semantics import normalize_semantic_effects
from skill_builder.runtime.serialization import json_safe


class DecisionFormAnswerError(ValueError):
    """Raised when a structured HITL answer violates the form contract."""


def public_decision_form_fields(fields: list[Any] | None) -> list[dict[str, Any]]:
    """Project internal decision metadata onto the user-facing form contract.

    Semantic concepts, capabilities and evidence mechanics are
    platform compilation details.  Persisting them in HITL ``options`` leaked
    backend fields into host UIs and also let a later answer round-trip become
    a second semantic authority.  Hosts only need stable ids, readable labels,
    input types, defaults and option values; answer normalization deterministically
    derives the internal contract from that public surface again.
    """

    result: list[dict[str, Any]] = []
    for raw_field in fields or []:
        if not isinstance(raw_field, dict):
            continue
        field = {}
        for key in (
            "id",
            "label",
            "description",
            "type",
            "defaultValue",
            "requiresExplicitSelection",
        ):
            if raw_field.get(key) not in (None, ""):
                field[key] = raw_field.get(key)
        field_options = []
        for raw_option in raw_field.get("options") or []:
            if not isinstance(raw_option, dict):
                continue
            option = {}
            for key in ("value", "label", "description"):
                if raw_option.get(key) not in (None, ""):
                    option[key] = raw_option.get(key)
            field_options.append(option)
        field["options"] = field_options
        result.append(field)
    return result


def _normalize_decision_form_fields(
    fields: list[Any] | None,
    default_value: str | None,
) -> list[dict[str, Any]]:
    defaults = _structured_hitl_default_object(default_value) or {}
    supplied: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(fields or [], start=1):
        if not isinstance(item, dict):
            continue
        field_id = str(item.get("id") or item.get("decisionId") or item.get("key") or "").strip()
        if not field_id:
            identity_text = " ".join(
                str(item.get(key) or "").strip()
                for key in ("label", "title", "description", "message")
            ).strip() or f"decision-{index}"
            ascii_slug = re.sub(r"[^a-z0-9]+", "_", identity_text.lower()).strip("_")[:40]
            field_id = ascii_slug or f"decision_{hashlib.sha256(identity_text.encode('utf-8')).hexdigest()[:12]}"
        base_id = field_id
        suffix = 2
        while field_id in supplied:
            field_id = f"{base_id}_{suffix}"
            suffix += 1
        supplied[field_id] = {**item, "id": field_id}

    ordered_ids = [field_id for field_id in defaults if field_id in supplied]
    ordered_ids.extend(field_id for field_id in supplied if field_id not in defaults)
    result: list[dict[str, Any]] = []
    for field_id in ordered_ids:
        item = supplied.get(field_id, {})
        default = defaults.get(field_id, item.get("defaultValue", item.get("default")))
        raw_options = item.get("options") if isinstance(item.get("options"), list) else []
        identity_options: list[dict[str, Any]] = []
        for raw_option in raw_options:
            option_mapping = raw_option if isinstance(raw_option, dict) else {}
            raw_value = (
                option_mapping.get("value", option_mapping.get("label"))
                if option_mapping
                else raw_option
            )
            transport_value, transport_label, _transport_description = (
                _decision_option_transport_parts(
                    raw_value,
                    option_mapping.get("label") if option_mapping else raw_option,
                )
            )
            identity_options.append({
                "value": transport_value,
                "label": transport_label,
                **(
                    {"semanticValue": option_mapping.get("semanticValue")}
                    if option_mapping.get("semanticValue") not in (None, "")
                    else {}
                ),
            })
        semantic_concept = decision_semantic_identity(
            field_id,
            item.get("label") or item.get("title"),
            item.get("description") or item.get("message"),
            identity_options,
            explicit=item.get("semanticConcept"),
        )
        normalized_options: list[dict[str, Any]] = []
        default_aliases: dict[str, Any] = {}
        for option in raw_options:
            if isinstance(option, dict):
                option_value = option.get("value", option.get("label"))
                transport_value, transport_label, transport_description = _decision_option_transport_parts(
                    option_value,
                    option.get("label"),
                )
                display_label = _decision_option_display_label(
                    transport_value,
                    transport_label,
                    field_id=field_id,
                    field_label=item.get("label") or item.get("title"),
                )
                option_transport_corrupted = bool(
                    HITL_STRUCTURED_FRAGMENT_PATTERN.search(
                        f"{option.get('label') or ''} {option.get('description') or ''}"
                    )
                )
                normalized_option = {
                    "value": transport_value,
                    "label": _readable_hitl_text(
                        display_label,
                        fallback=_decision_value_label(transport_value),
                        limit=180,
                    ),
                    "description": _readable_hitl_text(
                        option.get("description")
                        or (
                            transport_description
                            if transport_description and transport_description != display_label
                            else ""
                        ),
                        limit=500,
                    ),
                }
                semantic_value, semantic_source = canonical_decision_option_value(
                    semantic_concept,
                    {
                        **option,
                        "value": transport_value,
                        "label": normalized_option["label"],
                    },
                )
                normalized_option["semanticValue"] = semantic_value or decision_semantic_value(
                    semantic_concept,
                    transport_value,
                    display_label,
                )
                if semantic_source:
                    normalized_option["semanticValueSource"] = semantic_source
                normalized_evidence_status = str(
                    option.get("evidenceStatus") or ""
                ).strip() or decision_evidence_status(
                    transport_value,
                    display_label,
                    normalized_option["description"],
                )
                if normalized_evidence_status:
                    normalized_option["evidenceStatus"] = normalized_evidence_status
                implementation_dependencies = option.get("implementationDependencies")
                if not isinstance(implementation_dependencies, list):
                    implementation_dependencies = option.get("implementation_dependencies")
                normalized_dependencies: list[str] = []
                if isinstance(implementation_dependencies, list):
                    normalized_dependencies = list(
                        dict.fromkeys(
                            str(value).strip()
                            for value in implementation_dependencies
                            if str(value).strip()
                        )
                    )
                    if normalized_dependencies:
                        normalized_option["implementationDependencies"] = normalized_dependencies
                semantic_effects, _effect_issues = normalize_semantic_effects(
                    option.get("semanticEffects")
                )
                if semantic_effects:
                    normalized_option["semanticEffects"] = semantic_effects
                if isinstance(option.get("capabilityValue"), bool):
                    normalized_option["capabilityValue"] = option["capabilityValue"]
                capability_condition = normalize_capability_condition(option.get("capabilityCondition"))
                if capability_condition is not None:
                    normalized_option["capabilityCondition"] = capability_condition
                if isinstance(option.get("capabilityDecisions"), dict):
                    normalized_option["capabilityDecisions"] = {}
                    for key, value in option["capabilityDecisions"].items():
                        if not isinstance(value, bool):
                            continue
                        canonical = canonical_decision_capability(key)
                        if canonical and not decision_capability_is_allowed(
                            field_id,
                            semantic_concept,
                            canonical,
                        ):
                            continue
                        normalized_option["capabilityDecisions"][canonical or str(key).strip()] = value
                    if not normalized_option["capabilityDecisions"]:
                        normalized_option.pop("capabilityDecisions")
                if isinstance(option.get("capabilityConditions"), dict):
                    normalized_conditions: dict[str, Any] = {}
                    for key, value in option["capabilityConditions"].items():
                        canonical = canonical_decision_capability(key)
                        condition = normalize_capability_condition(value)
                        if canonical:
                            if condition is not None and decision_capability_is_allowed(
                                field_id,
                                semantic_concept,
                                canonical,
                            ):
                                normalized_conditions[canonical] = condition
                        elif str(key).strip():
                            # Preserve invalid declarations so validation can
                            # reject them instead of silently erasing intent.
                            normalized_conditions[str(key).strip()] = value
                    if normalized_conditions:
                        normalized_option["capabilityConditions"] = normalized_conditions
                registry_capabilities = projected_decision_capabilities(
                    field_id,
                    semantic_concept,
                    normalized_option["semanticValue"],
                )
                if registry_capabilities:
                    normalized_option["capabilityDecisions"] = {
                        **(
                            normalized_option.get("capabilityDecisions")
                            if isinstance(normalized_option.get("capabilityDecisions"), dict)
                            else {}
                        ),
                        **registry_capabilities,
                    }
                if option_transport_corrupted:
                    normalized_option["_transportCorrupted"] = True
                normalized_options.append(normalized_option)
                for alias in (option_value, option.get("label")):
                    if isinstance(alias, str) and alias.strip() and alias != transport_value:
                        default_aliases[alias.strip()] = transport_value
            else:
                transport_value, transport_label, transport_description = _decision_option_transport_parts(
                    option, option
                )
                display_label = _decision_option_display_label(
                    transport_value,
                    transport_label,
                    field_id=field_id,
                    field_label=item.get("label") or item.get("title"),
                )
                normalized_option = {
                    "value": transport_value,
                    "label": display_label or _decision_value_label(transport_value),
                    "description": transport_description if transport_description != display_label else "",
                }
                semantic_value, semantic_source = canonical_decision_option_value(
                    semantic_concept,
                    normalized_option,
                )
                semantic_value = semantic_value or decision_semantic_value(
                    semantic_concept,
                    transport_value,
                    display_label,
                )
                normalized_option["semanticValue"] = semantic_value
                if semantic_source:
                    normalized_option["semanticValueSource"] = semantic_source
                registry_capabilities = projected_decision_capabilities(
                    field_id,
                    semantic_concept,
                    semantic_value,
                )
                if registry_capabilities:
                    normalized_option["capabilityDecisions"] = registry_capabilities
                normalized_options.append(normalized_option)
                if isinstance(option, str) and option.strip() and option != transport_value:
                    default_aliases[option.strip()] = transport_value
        if isinstance(default, str) and default.strip() in default_aliases:
            default = default_aliases[default.strip()]
        field_type = str(item.get("type") or "").strip().lower()
        if field_type not in {"select", "boolean", "text"}:
            field_type = "boolean" if isinstance(default, bool) else "select" if normalized_options else "text"
        if field_type == "boolean" and normalized_options and not all(
            isinstance(option.get("value"), bool) for option in normalized_options
        ):
            field_type = "select"
        if field_type == "boolean" and default is None:
            default = False
        if field_type == "select" and len(normalized_options) < 2:
            if (
                not normalized_options
                and default is not None
                and not (isinstance(default, str) and not default.strip())
            ):
                semantic_value = decision_semantic_value(semantic_concept, default)
                default_option = {
                    "value": default,
                    "label": str(item.get("defaultLabel") or _decision_value_label(default)),
                    "description": "",
                    "semanticValue": semantic_value,
                }
                registry_capabilities = projected_decision_capabilities(
                    field_id,
                    semantic_concept,
                    semantic_value,
                )
                if registry_capabilities:
                    default_option["capabilityDecisions"] = registry_capabilities
                normalized_options.append(default_option)
            fallback_value = "not_verified"
            existing_values = {option.get("value") for option in normalized_options}
            suffix = 2
            while fallback_value in existing_values:
                fallback_value = f"not_verified_{suffix}"
                suffix += 1
            normalized_options.append(
                {
                    "value": fallback_value,
                    "label": "暂不确认，按未验证处理",
                    "description": "不阻断本次生成；相关能力或业务结论在产物中标记为 not_verified。",
                    "semanticValue": decision_semantic_value(semantic_concept, fallback_value),
                    "evidenceStatus": "not_verified",
                }
            )
        requires_explicit_selection = item.get("requiresExplicitSelection") is True
        should_infer_default = (
            not requires_explicit_selection
            and (default is None or isinstance(default, str) and not default.strip())
        )
        if should_infer_default:
            eligible_options = normalized_options if field_type == "select" else [
                option for option in normalized_options if isinstance(option.get("value"), bool)
            ]
            if eligible_options:
                preferred = eligible_options[0]
                for option in eligible_options:
                    option_text = f"{option.get('label') or ''} {option.get('description') or ''}"
                    if re.search(r"(?:推荐|默认|recommended|default)", option_text, re.IGNORECASE):
                        preferred = option
                        break
                default = preferred.get("value")
        should_offer_confirmation_options = (
            field_type == "text"
            and (default is None or isinstance(default, str) and not default.strip())
            and not normalized_options
            and re.search(
                r"(?:确认|同意|是否|范围|口径|边界|approve|confirm)",
                f"{item.get('label') or ''} {item.get('title') or ''} {item.get('description') or ''}",
                re.IGNORECASE,
            )
        )
        if should_offer_confirmation_options:
            field_type = "select"
            normalized_options = [
                {"value": "confirmed", "label": "确认，按当前理解继续", "description": ""},
                {"value": "needs_adjustment", "label": "需要调整范围或口径", "description": ""},
            ]
            default = "confirmed"
        needs_text_fallback = field_type == "text" and (
            default is None or isinstance(default, str) and not default.strip()
        )
        if needs_text_fallback:
            default = "暂不明确，按未验证处理"
        matched_default_option = next(
            (option for option in normalized_options if option.get("value") == default),
            None,
        )
        field_transport_corrupted = bool(
            HITL_STRUCTURED_FRAGMENT_PATTERN.search(
                f"{item.get('label') or item.get('title') or ''} "
                f"{item.get('description') or item.get('message') or ''}"
            )
        )
        normalized_field = {
            "id": field_id,
            "label": _readable_hitl_text(
                item.get("label") or item.get("title"),
                fallback=_humanize_decision_id(field_id),
                limit=180,
            ),
            "description": _readable_hitl_text(
                item.get("description") or item.get("message"),
                limit=500,
            ),
            "type": field_type,
            "defaultValue": default,
            "defaultLabel": str(
                item.get("defaultLabel")
                or (matched_default_option or {}).get("label")
                or _decision_value_label(default)
            ),
            "options": normalized_options,
        }
        if requires_explicit_selection:
            normalized_field["requiresExplicitSelection"] = True
        normalized_field["semanticConcept"] = semantic_concept
        if field_transport_corrupted:
            normalized_field["_transportCorrupted"] = True
        raw_capability = str(item.get("capability") or "").strip()
        capability = canonical_decision_capability(raw_capability)
        if capability and decision_capability_is_allowed(field_id, semantic_concept, capability):
            normalized_field["capability"] = capability
        elif raw_capability and capability is None:
            normalized_field["capability"] = raw_capability
        result.append(normalized_field)

    return _merge_duplicate_decision_fields(result)


def _decision_form_default_object(fields: list[dict[str, Any]], default_value: str | None) -> dict[str, Any]:
    declared_ids = {
        str(field.get("id") or "").strip()
        for field in fields
        if str(field.get("id") or "").strip()
    }
    defaults = {
        key: value
        for key, value in (_structured_hitl_default_object(default_value) or {}).items()
        if key in declared_ids
    }
    for field in fields:
        field_id = str(field.get("id") or "").strip()
        if not field_id or field_id in defaults:
            continue
        default = field.get("defaultValue")
        if default is not None and not (isinstance(default, str) and not default.strip()):
            defaults[field_id] = default
    return defaults


def _decision_form_conflicting_default_ids(
    fields: list[dict[str, Any]],
    defaults: dict[str, Any],
) -> set[str]:
    """Return defaults that assert opposite values for one capability.

    Scenario-authored defaults are UI suggestions, not user confirmations.
    Build every proposed selection independently so the form can withhold all
    defaults that participate in a cross-field capability contradiction while
    preserving the fields and their options for explicit confirmation.
    """

    assignments: dict[str, dict[bool, set[str]]] = {}
    for field_id, value in defaults.items():
        contract = build_decision_contract(
            {field_id: value},
            fields=fields,
        )
        if contract.get("conflicts"):
            # An internally invalid field remains a normal form error; only
            # cross-field contradictions are deferred to user confirmation.
            continue
        for capability, enabled in (contract.get("capabilities") or {}).items():
            if not isinstance(enabled, bool):
                continue
            assignments.setdefault(str(capability), {True: set(), False: set()})[
                enabled
            ].add(field_id)
    result = set()
    for values in assignments.values():
        if not values[True] or not values[False]:
            continue
        result.update(values[True])
        result.update(values[False])
    return result


def _decision_form_field_errors(
    fields: list[dict[str, Any]],
    defaults: dict[str, Any],
    *,
    explicit_decision_ids: set[str] | frozenset[str] = frozenset(),
) -> list[str]:
    errors: list[str] = []
    seen: set[str] = set()
    for index, field in enumerate(fields):
        field_id = str(field.get("id") or "").strip()
        label = str(field.get("label") or field_id or f"第 {index + 1} 项").strip()
        semantic_conflict = str(field.get("_semanticConflict") or "").strip()
        if semantic_conflict:
            errors.append(f"{label}: {semantic_conflict}")
        if not field_id:
            errors.append(f"{label}: 缺少稳定 decisionId")
            continue
        if field_id in seen:
            errors.append(f"{label}: decisionId 重复")
        seen.add(field_id)
        if field.get("_transportCorrupted") is True or any(
            item.get("_transportCorrupted") is True
            for item in field.get("options") or []
            if isinstance(item, dict)
        ):
            errors.append(f"{label}: 可见文案混入了结构化参数")
        description = str(field.get("description") or "")
        if re.search(
            r"(?:\\?\"|')(?:decisionId|default|options|type|value|label)(?:\\?\"|')\s*[:：]"
            r"|\}\s*,\s*\{",
            description,
            re.IGNORECASE,
        ):
            errors.append(f"{label}: 字段说明包含残缺的结构化参数")
        field_type = str(field.get("type") or "text")
        options = [item for item in field.get("options") or [] if isinstance(item, dict)]
        if field_type == "select":
            if len(options) < 2:
                errors.append(f"{label}: 选择项少于 2 个")
            option_values = [item.get("value") for item in options]
            serialized_option_values = [
                json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
                for value in option_values
            ]
            if len(set(serialized_option_values)) != len(serialized_option_values):
                errors.append(f"{label}: 选择项 value 重复")
            for option in options:
                option_value = option.get("value")
                option_label = option.get("label")
                if (
                    _decision_option_is_placeholder(option_value)
                    and _decision_option_is_placeholder(option_label)
                ):
                    errors.append(f"{label}: 选择项 {option_value} 是占位枚举，缺少真实业务含义")
                elif (
                    _decision_option_is_machine_only(option_value, option_label)
                    and not _decision_field_allows_identifier_label(field_id=field_id, field_label=label)
                ):
                    errors.append(f"{label}: 选择项 {option_value} 只有后台枚举，没有用户可读文案")
            if field_id in defaults and defaults[field_id] not in option_values:
                errors.append(f"{label}: 默认值不在可选项中")
        elif field_type == "boolean" and field_id in defaults and not isinstance(defaults[field_id], bool):
            errors.append(f"{label}: 布尔字段默认值不是 true/false")
        if field_id not in defaults and field_id not in explicit_decision_ids:
            errors.append(f"{label}: 缺少可读默认值")
        raw_field_capability = str(field.get("capability") or "").strip()
        field_capability = canonical_decision_capability(raw_field_capability)
        if raw_field_capability and field_capability is None:
            errors.append(f"{label}: 未知 capability {raw_field_capability}")
        for option in options:
            if not decision_option_is_unknown(option) and not decision_value_is_valid(
                field_id,
                field.get("semanticConcept"),
                option.get("semanticValue"),
            ):
                concept = str(field.get("semanticConcept") or "").strip()
                allowed = decision_option_catalog(concept).get(concept, {}).get("allowedValues", [])
                errors.append(
                    f"{label}: 选项 {option.get('value')} 的 semanticValue "
                    f"{option.get('semanticValue')} 不属于已注册契约值"
                    + (f"；允许值：{', '.join(allowed)}" if allowed else "")
                )
            for mapping_name in ("capabilityDecisions", "capabilityConditions"):
                mapping = option.get(mapping_name)
                if not isinstance(mapping, dict):
                    continue
                for raw_capability in mapping:
                    if canonical_decision_capability(raw_capability) is None:
                        errors.append(f"{label}: 未知 capability {raw_capability}")
            condition_values = []
            if option.get("capabilityCondition") is not None:
                condition_values.append(option.get("capabilityCondition"))
            if isinstance(option.get("capabilityConditions"), dict):
                condition_values.extend(option["capabilityConditions"].values())
            for condition in condition_values:
                if not isinstance(condition, dict) or not isinstance(condition.get("when"), dict):
                    continue
                dependency = condition["when"].get("capability")
                if dependency and canonical_decision_capability(dependency) is None:
                    errors.append(f"{label}: capabilityCondition 引用了未知 capability {dependency}")
    for field_id in defaults:
        if field_id not in seen:
            errors.append(f"{field_id}: 默认值没有对应的表单字段")
    default_contract = build_decision_contract(
        defaults,
        fields=fields,
    )
    errors.extend(str(value) for value in default_contract.get("conflicts") or [])
    return list(dict.fromkeys(errors))[:20]


def normalize_decision_form_answer(
    *,
    answer: dict[str, Any],
    fields: list[dict[str, Any]],
    message: str | None = None,
    default_value: str | None = None,
) -> dict[str, Any]:
    normalized = dict(answer or {})
    decisions = normalized.get("decisions")
    if not isinstance(decisions, dict) and isinstance(normalized.get("value"), str):
        try:
            parsed = json.loads(str(normalized["value"]))
        except json.JSONDecodeError:
            parsed = None
        if isinstance(parsed, dict):
            decisions = parsed
    if not isinstance(decisions, dict) or not decisions:
        raise DecisionFormAnswerError("请逐项完成决策表单后再提交")

    contract_defaults = _decision_form_default_object(fields, default_value)
    presentation_issues = []
    presentation_markers = ("占位枚举", "后台枚举", "value 重复", "结构化参数", "损坏")
    for issue in _decision_form_field_errors(fields, contract_defaults):
        if any(marker in issue for marker in presentation_markers):
            presentation_issues.append(issue)
    integrity_issues = [
        *presentation_issues,
        *decision_form_integrity_issues(
            message=message,
            fields=fields,
            default_value=contract_defaults,
        ),
    ]
    integrity_issues = list(dict.fromkeys(integrity_issues))
    if integrity_issues:
        raise DecisionFormAnswerError("决策表单协议不完整，不能确认：" + "；".join(integrity_issues))
    allowed_ids = {
        str(item.get("id") or item.get("decisionId") or item.get("key") or "").strip()
        for item in fields
        if isinstance(item, dict)
    }
    allowed_ids.discard("")
    if allowed_ids:
        unknown = sorted(str(key) for key in decisions if str(key) not in allowed_ids)
        missing = sorted(field_id for field_id in allowed_ids if field_id not in decisions)
        details: list[str] = []
        if missing:
            details.append("缺少：" + "、".join(missing))
        if unknown:
            details.append("未知字段：" + "、".join(unknown))
        if details:
            raise DecisionFormAnswerError("；".join(details))

    labels: dict[str, str] = {}
    display_values: dict[str, str] = {}
    for field in fields:
        if not isinstance(field, dict):
            continue
        field_id = str(field.get("id") or field.get("decisionId") or field.get("key") or "").strip()
        if not field_id or field_id not in decisions:
            continue
        labels[field_id] = str(field.get("label") or field.get("title") or field_id)
        value = decisions[field_id]
        display = "是" if value is True else "否" if value is False else str(value)
        for option in field.get("options") or []:
            if isinstance(option, dict) and option.get("value") == value:
                display = str(option.get("label") or display)
                break
        display_values[field_id] = display

    decision_contract = build_decision_contract(
        decisions,
        labels=labels,
        display_values=display_values,
        fields=fields,
    )
    if decision_contract.get("conflicts"):
        raise DecisionFormAnswerError("；".join(str(value) for value in decision_contract["conflicts"]))
    return {
        "decisions": json_safe(decisions, max_text_length=6000),
        "decision_labels": labels,
        "display_values": display_values,
        "capability_decisions": {
            str(key): value
            for key, value in decision_contract.get("capabilities", {}).items()
            if isinstance(value, bool)
        },
        "decision_contract": decision_contract,
    }


def format_hitl_answer_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return ""
        try:
            parsed = json.loads(stripped)
        except (TypeError, ValueError):
            return stripped
        return format_hitl_answer_text(parsed) or stripped
    if isinstance(value, dict):
        decisions = value.get("decisions")
        if isinstance(decisions, dict):
            labels = value.get("decision_labels") if isinstance(value.get("decision_labels"), dict) else {}
            display_values = value.get("display_values") if isinstance(value.get("display_values"), dict) else {}
            readable: list[str] = []
            for key, item in decisions.items():
                label = str(labels.get(key) or key)
                display = display_values.get(key)
                if display is None:
                    display = "是" if item is True else "否" if item is False else item
                readable.append(f"{label}：{display}")
            if readable:
                return "；".join(readable)
        parts: list[str] = []
        for key in ("label", "value", "text", "message", "description"):
            item = value.get(key)
            if isinstance(item, str) and item.strip():
                parts.append(item.strip())
        option = value.get("option")
        if isinstance(option, dict):
            for key in ("label", "value", "description"):
                item = option.get(key)
                if isinstance(item, str) and item.strip():
                    parts.append(item.strip())
        if parts:
            return "；".join(dict.fromkeys(parts))
        return json.dumps(json_safe(value, max_text_length=1000), ensure_ascii=False)
    if isinstance(value, list):
        return "；".join(item for item in (format_hitl_answer_text(item) for item in value) if item)
    return str(value).strip()


__all__ = [
    "DecisionFormAnswerError",
    "_decision_form_conflicting_default_ids",
    "_decision_form_default_object",
    "_decision_form_field_errors",
    "_normalize_decision_form_fields",
    "format_hitl_answer_text",
    "normalize_decision_form_answer",
    "public_decision_form_fields",
]
