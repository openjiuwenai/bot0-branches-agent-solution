# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Deterministic projections and HITL handoff for ScenarioContract."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from skill_builder.application.hitl_form_contract import (
    _decision_form_conflicting_default_ids,
    _decision_form_default_object,
    _decision_form_field_errors,
    _normalize_decision_form_fields,
    public_decision_form_fields,
)
from skill_builder.application.implementation_plan import behavior_signature
from skill_builder.domain.decision_form import decision_form_integrity_issues
from skill_builder.domain.scenario_contract import (
    SCENARIO_CONTRACT_PATH,
    normalize_scenario_contract,
    scenario_decision_needs,
)
from skill_builder.domain.decision_registry import (
    decision_capabilities,
    normalize_decision_concept,
)


SCENARIO_PROJECTION_MARKER = "<!-- skill-builder:scenario-contract-projection -->"
AUTHOR_HANDOFF_SCHEMA_VERSION = "skill-builder-author-handoff/v1"
AUTHOR_HANDOFF_MAX_BYTES = 64 * 1024


def _confirmed_decision_values(
    confirmations: tuple[dict[str, Any], ...] | list[dict[str, Any]],
    *,
    scenario_contract_hash: str,
) -> dict[str, dict[str, Any]]:
    """Return only decisions bound to the current ScenarioContract hash."""

    result: dict[str, dict[str, Any]] = {}
    for contract in _confirmed_decision_contracts(
        confirmations,
        scenario_contract_hash=scenario_contract_hash,
    ):
        decisions = contract.get("decisions")
        if not isinstance(decisions, dict):
            continue
        for decision_id, value in decisions.items():
            if isinstance(value, dict):
                result[str(decision_id)] = dict(value)
    return result


def _confirmed_decision_contracts(
    confirmations: tuple[dict[str, Any], ...] | list[dict[str, Any]],
    *,
    scenario_contract_hash: str,
) -> list[dict[str, Any]]:
    """Return structured decision contracts bound to one Scenario contract."""

    result: list[dict[str, Any]] = []
    for confirmation in confirmations:
        if not isinstance(confirmation, dict):
            continue
        if str(confirmation.get("scenarioContractHash") or "").strip() != scenario_contract_hash:
            continue
        answer = confirmation.get("answer")
        contract = answer.get("decision_contract") if isinstance(answer, dict) else None
        if not isinstance(contract, dict):
            continue
        result.append(dict(contract))
    return result


def resolved_capability_contract(
    scenario_contract: dict[str, Any],
    *,
    confirmations: tuple[dict[str, Any], ...] | list[dict[str, Any]] = (),
) -> dict[str, Any]:
    """Compile Scenario requirements and confirmed HITL capabilities once.

    Authoring, preflight and delivery validation must consume the same
    platform-owned capability decision.  Keeping this projection in the
    artifact manifest avoids re-interpreting prose or reaching into private
    lifecycle state during acceptance.
    """

    scenario_hash = str(scenario_contract.get("semanticHash") or "").strip()
    required = {
        str(key): bool(value)
        for key, value in (scenario_contract.get("requiredCapabilities") or {}).items()
        if isinstance(value, bool)
    }
    raw_requirements = scenario_contract.get("capabilityRequirements")
    any_of = [
        sorted({str(item) for item in group if str(item).strip()})
        for group in (raw_requirements or {}).get("anyOf") or []
        if isinstance(group, list)
    ] if isinstance(raw_requirements, dict) else []
    capability_sources: dict[str, set[str]] = {
        key: {"scenario_contract"}
        for key, value in required.items()
        if value
    }
    confirmed_capabilities: dict[str, bool] = {}
    implementation_dependencies: list[str] = []
    conflicts: list[str] = []
    acquisition_overrides: set[str] = set()
    acquisition_capabilities: dict[str, bool] = {}
    delivery_overrides: set[str] = set()
    delivery_capabilities: dict[str, bool] = {}
    package_kinds: set[str] = set()
    for contract in _confirmed_decision_contracts(
        confirmations,
        scenario_contract_hash=scenario_hash,
    ):
        for decision in (contract.get("decisions") or {}).values():
            if not isinstance(decision, dict):
                continue
            concept = normalize_decision_concept(decision.get("semanticConcept"))
            if concept == "acquisition_mode":
                acquisition_overrides.update(
                    {
                        "api_runtime",
                        "browser_runtime",
                        "external_runtime",
                        "collection_script",
                    }
                )
                acquisition_capabilities.update(
                    {
                        str(key): bool(value)
                        for key, value in decision_capabilities(
                            concept,
                            decision.get("semanticValue", decision.get("value")),
                        ).items()
                        if isinstance(value, bool)
                    }
                )
            if concept == "skill_delivery_mode":
                semantic_value = str(
                    decision.get("semanticValue", decision.get("value")) or ""
                ).strip()
                package_kinds.add(
                    "knowledge" if semantic_value == "knowledge" else "executable"
                )
                delivery_overrides.update(
                    {
                        "api_runtime",
                        "browser_runtime",
                        "external_runtime",
                        "collection_script",
                    }
                )
                delivery_capabilities.update(
                    {
                        str(key): bool(value)
                        for key, value in decision_capabilities(
                            concept,
                            semantic_value,
                        ).items()
                        if isinstance(value, bool)
                    }
                )
        for key, value in (contract.get("capabilities") or {}).items():
            if not isinstance(value, bool):
                continue
            name = str(key)
            previous = confirmed_capabilities.get(name)
            if isinstance(previous, bool) and previous is not value:
                conflicts.append(f"能力 {name} 的 HITL 确认结果不一致。")
                continue
            confirmed_capabilities[name] = value
            for source in (contract.get("capabilitySources") or {}).get(name) or []:
                capability_sources.setdefault(name, set()).add(str(source))
            capability_sources.setdefault(name, set()).add("hitl_confirmation")
        implementation_dependencies.extend(
            str(item)
            for item in contract.get("implementationDependencies") or []
            if str(item).strip()
        )
        conflicts.extend(
            str(item)
            for item in contract.get("conflicts") or []
            if str(item).strip()
        )

    # A typed acquisition-mode selection resolves API/browser alternatives.
    # Other false values remain unable to disable deterministic hard facts.
    if len(package_kinds) > 1:
        conflicts.append("Skill 交付形态的 HITL 确认结果不一致。")
    confirmed_package_kind = next(iter(package_kinds)) if len(package_kinds) == 1 else None
    confirmed_capabilities.update(delivery_capabilities)
    confirmed_capabilities.update(acquisition_capabilities)
    runtime_overrides = delivery_overrides | acquisition_overrides
    for key, value in confirmed_capabilities.items():
        required[key] = (
            value
            if key in runtime_overrides
            else bool(required.get(key)) or value
        )

    if runtime_overrides:
        any_of = [
            group
            for group in any_of
            if not set(group).issubset(runtime_overrides)
        ]
    all_of = sorted(key for key, value in required.items() if value)

    return {
        "schemaVersion": "skill-builder-resolved-capability-contract/v1",
        "scenarioContractHash": scenario_hash,
        "requiredCapabilities": required,
        "capabilityRequirements": {
            "allOf": all_of,
            "anyOf": any_of,
        },
        "confirmedCapabilities": confirmed_capabilities,
        "packageKind": confirmed_package_kind,
        "packageKindSource": "hitl_confirmation" if confirmed_package_kind else None,
        "capabilitySources": {
            key: sorted(values)
            for key, values in sorted(capability_sources.items())
        },
        "implementationDependencies": list(dict.fromkeys(implementation_dependencies)),
        "conflicts": list(dict.fromkeys(conflicts)),
    }


def author_handoff_contract(
    scenario_contract: dict[str, Any],
    *,
    confirmations: tuple[dict[str, Any], ...] | list[dict[str, Any]] = (),
) -> dict[str, Any]:
    """Project the full audit contract into the smaller Author input surface."""

    scenario_hash = str(scenario_contract.get("semanticHash") or "").strip()
    confirmed = _confirmed_decision_values(
        confirmations,
        scenario_contract_hash=scenario_hash,
    )
    pending = scenario_decision_needs(scenario_contract)
    resolved_capabilities = resolved_capability_contract(
        scenario_contract,
        confirmations=confirmations,
    )
    return {
        "schemaVersion": AUTHOR_HANDOFF_SCHEMA_VERSION,
        "scenarioContractHash": scenario_hash,
        "skillName": scenario_contract.get("skillName"),
        "displayName": scenario_contract.get("displayName"),
        "purpose": scenario_contract.get("purpose"),
        "triggers": scenario_contract.get("triggers") or [],
        "nonTriggers": scenario_contract.get("nonTriggers") or [],
        "inputs": scenario_contract.get("inputs") or [],
        "outputs": scenario_contract.get("outputs") or [],
        "steps": scenario_contract.get("steps") or [],
        "dependencies": scenario_contract.get("dependencies") or [],
        "scriptRequirements": scenario_contract.get("scriptRequirements") or [],
        "acceptanceCriteria": scenario_contract.get("acceptanceCriteria") or [],
        "resolvedRequirements": [
            {
                key: item.get(key)
                for key in (
                    "requirementId",
                    "concept",
                    "value",
                    "evidenceRefs",
                )
                if item.get(key) not in (None, "", [], {})
            }
            for item in scenario_contract.get("resolvedRequirements") or []
            if isinstance(item, dict)
        ],
        "businessRules": [
            {
                key: item.get(key)
                for key in ("ruleId", "kind", "definition", "evidenceRefs")
                if item.get(key) not in (None, "", [], {})
            }
            for item in scenario_contract.get("businessRules") or []
            if isinstance(item, dict)
        ],
        "resolvedCapabilityContract": resolved_capabilities,
        "behaviorSignature": behavior_signature(
            scenario_contract,
            resolved_capabilities,
        ),
        "confirmedDecisions": [
            {
                "decisionId": item.get("decisionId"),
                "title": item.get("title"),
                "value": confirmed[str(item.get("decisionId"))].get("value"),
                "displayValue": confirmed[str(item.get("decisionId"))].get(
                    "displayValue"
                ),
                "semanticConcept": confirmed[str(item.get("decisionId"))].get(
                    "semanticConcept"
                ),
                "semanticValue": confirmed[str(item.get("decisionId"))].get(
                    "semanticValue"
                ),
            }
            for item in pending
            if str(item.get("decisionId") or "") in confirmed
        ],
        "evidenceRefs": scenario_contract.get("evidenceRefs") or [],
        "fullContractPath": "validation/scenario_contract.json",
    }


def load_persisted_scenario_contract(root: Path) -> tuple[dict[str, Any], list[str]]:
    """Load only a canonical ScenarioContract whose semantic hash still matches."""

    try:
        raw = json.loads((root / SCENARIO_CONTRACT_PATH).read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        return {}, ["validation/scenario_contract.json 不存在或不是有效 JSON"]
    if not isinstance(raw, dict):
        return {}, ["validation/scenario_contract.json 根节点必须是对象"]
    normalized, issues = normalize_scenario_contract(raw)
    if issues:
        return {}, issues
    if str(raw.get("semanticHash") or "") != str(normalized.get("semanticHash") or ""):
        return {}, ["ScenarioContract semanticHash 与规范化内容不一致"]
    return normalized, []


def _item_text(value: Any) -> str:
    if isinstance(value, str):
        return value.strip()
    if not isinstance(value, dict):
        return str(value or "").strip()
    title = next(
        (
            str(value.get(key) or "").strip()
            for key in ("title", "name", "label", "description", "id")
            if str(value.get(key) or "").strip()
        ),
        "",
    )
    details = [
        f"{key}={item}"
        for key, item in value.items()
        if key not in {"title", "name", "label", "description", "id"}
        and item not in (None, "", [], {})
    ]
    if title and details:
        return f"{title}（{'；'.join(details)}）"
    return title or json.dumps(value, ensure_ascii=False, sort_keys=True)


def _section(title: str, values: Any, *, empty: str = "无") -> list[str]:
    items = [_item_text(item) for item in values or []]
    items = [item for item in items if item]
    return [f"## {title}", "", *([f"- {item}" for item in items] if items else [f"- {empty}"]), ""]


def _scenario_decision_form_contract(
    contract: Any,
) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, Any], set[str], list[str]]:
    """Compile one ScenarioContract into its authoritative decision fields."""

    normalized, contract_issues = normalize_scenario_contract(contract)
    if contract_issues:
        return {}, [], {}, set(), contract_issues
    needs = scenario_decision_needs(normalized)
    if not needs:
        return normalized, [], {}, set(), []

    declared_fields: list[dict[str, Any]] = []
    declared_defaults: dict[str, Any] = {}
    for need in needs:
        decision_id = str(need.get("decisionId") or "").strip()
        if not decision_id:
            continue
        field = {
            "id": decision_id,
            "label": need.get("title") or decision_id,
            "description": need.get("description") or need.get("title") or decision_id,
            "type": need.get("type") or "text",
            "defaultValue": need.get("defaultValue"),
            "requiresExplicitSelection": need.get("requiresExplicitSelection") is True,
            "options": need.get("options") if isinstance(need.get("options"), list) else [],
            "semanticConcept": need.get("semanticConcept"),
        }
        declared_fields.append(field)
        if field.get("defaultValue") not in {None, ""}:
            declared_defaults[decision_id] = field["defaultValue"]

    default_value = json.dumps(declared_defaults, ensure_ascii=False) if declared_defaults else None
    fields = _normalize_decision_form_fields(declared_fields, default_value)
    defaults = _decision_form_default_object(fields, default_value)
    explicit_decision_ids = _decision_form_conflicting_default_ids(fields, defaults) | {
        str(field.get("id") or "")
        for field in fields
        if field.get("requiresExplicitSelection") is True
        and str(field.get("id") or "").strip()
    }
    if explicit_decision_ids:
        defaults = {
            field_id: value
            for field_id, value in defaults.items()
            if field_id not in explicit_decision_ids
        }
        fields = [
            {
                key: value
                for key, value in field.items()
                if not (
                    str(field.get("id") or "") in explicit_decision_ids
                    and key in {"defaultValue", "defaultLabel"}
                )
            }
            for field in fields
        ]
    issues = [
        *_decision_form_field_errors(
            fields,
            defaults,
            explicit_decision_ids=explicit_decision_ids,
        ),
        *decision_form_integrity_issues(
            message="请在下方表单逐项确认后继续。",
            fields=fields,
            default_value=defaults,
        ),
    ]
    issues = list(dict.fromkeys(str(item) for item in issues if str(item).strip()))[:20]
    return normalized, fields, defaults, explicit_decision_ids, issues


def _decision_field_transport_signature(fields: list[Any] | None) -> list[tuple[str, str, tuple[str, ...]]]:
    result: list[tuple[str, str, tuple[str, ...]]] = []
    for field in fields or []:
        if not isinstance(field, dict):
            continue
        field_id = str(field.get("id") or field.get("decisionId") or "").strip()
        if not field_id:
            continue
        option_values = tuple(
            json.dumps(
                option.get("value"),
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                default=str,
            )
            for option in field.get("options") or []
            if isinstance(option, dict)
        )
        result.append((field_id, str(field.get("type") or "text"), option_values))
    return result


def authoritative_decision_form_fields(
    root: Path,
    *,
    scenario_contract_hash: str,
    public_fields: list[Any] | None,
) -> tuple[list[dict[str, Any]], list[str]]:
    """Restore private decision semantics from the hash-bound ScenarioContract."""

    contract, load_issues = load_persisted_scenario_contract(root)
    if load_issues:
        return [], load_issues
    expected_hash = str(scenario_contract_hash or "").strip()
    actual_hash = str(contract.get("semanticHash") or "").strip()
    if not expected_hash or expected_hash != actual_hash:
        return [], ["HITL 回答未绑定到当前 ScenarioContract semanticHash"]
    _normalized, fields, _defaults, _explicit, compile_issues = (
        _scenario_decision_form_contract(contract)
    )
    if compile_issues:
        return [], compile_issues
    if _decision_field_transport_signature(public_fields) != _decision_field_transport_signature(
        public_decision_form_fields(fields)
    ):
        return [], ["HITL 公开字段与当前 ScenarioContract 的 decisionId/option value 域不一致"]
    return fields, []


def scenario_contract_hitl_request(contract: Any) -> tuple[dict[str, Any], list[str]]:
    """Compile the authoritative ScenarioContract into one host-neutral form."""

    normalized, fields, defaults, explicit_decision_ids, issues = (
        _scenario_decision_form_contract(contract)
    )
    if issues:
        return {}, issues
    if not fields:
        return {}, []
    scenario_contract_hash = str(normalized.get("semanticHash") or "").strip()
    public_fields = public_decision_form_fields(fields)
    return {
        "kind": "decision_form",
        "title": "场景理解待确认",
        "message": (
            "部分建议默认值存在能力冲突，请显式选择相关业务口径；其余项目确认后进入 Skill 写包阶段。"
            if explicit_decision_ids
            else "以下业务口径会影响生成结果，请在同一表单中逐项确认后进入 Skill 写包阶段。"
        ),
        "options": public_fields,
        "default_value": json.dumps(defaults, ensure_ascii=False),
        "decision_ids": [str(field.get("id") or "") for field in public_fields],
        "explicit_decision_ids": sorted(explicit_decision_ids),
        "scenario_contract_hash": scenario_contract_hash,
    }, []


def scenario_contract_artifacts(
    contract: Any,
    *,
    confirmations: tuple[dict[str, Any], ...] | list[dict[str, Any]] = (),
) -> tuple[dict[str, str], list[str]]:
    """Render platform-owned scenario projections without another model round."""

    normalized, issues = normalize_scenario_contract(contract)
    if issues:
        return {}, issues
    hitl_request, hitl_issues = scenario_contract_hitl_request(normalized)
    if hitl_issues:
        return {}, hitl_issues

    pending = scenario_decision_needs(normalized)
    confirmed_values = _confirmed_decision_values(
        confirmations,
        scenario_contract_hash=str(normalized.get("semanticHash") or ""),
    )
    unresolved = [
        item
        for item in pending
        if str(item.get("decisionId") or "") not in confirmed_values
    ]
    confirmed_lines = [
        f"- `{item['decisionId']}` {item.get('title') or item['decisionId']}："
        "已确认为「{}」".format(
            confirmed_values[item["decisionId"]].get("displayValue")
            or confirmed_values[item["decisionId"]].get("value")
            or "已选择"
        )
        for item in pending
        if str(item.get("decisionId") or "") in confirmed_values
    ]
    pending_lines = [
        f"- `{item['decisionId']}` {item.get('title') or item['decisionId']}："
        f"{item.get('description') or '需要用户确认'}"
        for item in unresolved
    ] or ["- 无结构化业务待决策项；直接进入 Author 写包阶段。"]
    contract_ref = (
        f"schemaVersion={normalized.get('schemaVersion')}，"
        f"semanticHash={normalized.get('semanticHash')}"
    )
    understanding_lines = [
        SCENARIO_PROJECTION_MARKER,
        "# 场景理解",
        "",
        "> 本文件由平台从 ScenarioContract 确定性生成；业务语义以 `validation/scenario_contract.json` 为准。",
        "",
        "## 目标",
        "",
        str(normalized.get("purpose") or ""),
        "",
        *_section("触发条件", normalized.get("triggers")),
        *_section("不触发边界", normalized.get("nonTriggers")),
        *_section("输入", normalized.get("inputs")),
        *_section("输出", normalized.get("outputs")),
        *_section("业务步骤", normalized.get("steps")),
        *_section("依赖", normalized.get("dependencies")),
        *_section("脚本需求", normalized.get("scriptRequirements")),
        *_section("材料已决要求", normalized.get("resolvedRequirements")),
        *_section("结构化业务规则", normalized.get("businessRules")),
        *_section("验收要求", normalized.get("acceptanceCriteria")),
        "## 已确认事项",
        "",
        *(confirmed_lines or ["- 无当前 ScenarioContract 的 HITL 确认。"]),
        "",
        "## 待确认事项",
        "",
        *pending_lines,
        "",
        *_section("证据引用", normalized.get("evidenceRefs")),
        "## 契约标识",
        "",
        f"- {contract_ref}",
        "",
    ]
    summary_lines = [
        SCENARIO_PROJECTION_MARKER,
        "# 场景摘要",
        "",
        "> 本文件是 ScenarioContract 的平台可读投影，不是第二套业务规则来源。",
        "",
        "## 场景概览",
        "",
        f"- 场景目标：{normalized.get('purpose') or ''}",
        f"- 触发条件数量：{len(normalized.get('triggers') or [])}",
        f"- 输入数量：{len(normalized.get('inputs') or [])}",
        f"- 输出数量：{len(normalized.get('outputs') or [])}",
        f"- 业务步骤数量：{len(normalized.get('steps') or [])}",
        f"- 材料已决要求数量：{len(normalized.get('resolvedRequirements') or [])}",
        f"- 结构化业务规则数量：{len(normalized.get('businessRules') or [])}",
        f"- {contract_ref}",
        "",
        "## 人工确认边界",
        "",
        *(confirmed_lines or ["- 无当前 ScenarioContract 的 HITL 确认。"]),
        "",
        "## 待确认事项",
        "",
        *pending_lines,
        "",
        "## 阶段结论",
        "",
        (
            f"- 已生成一个包含 {len(unresolved)} 个未确认字段的集中确认表单；确认后交给 Author 作为上下文。"
            if unresolved
            else "- 没有独立业务待决策项；无需 HITL，直接进入 Author。"
        ),
        "- 本阶段不生成最终 Skill 包，不执行浏览器探测，也不进入修复。",
        "- 后续 Author 消费材料、Scenario 摘要和 HITL 答案；真正冲突不得自行猜测。",
        "",
    ]
    resolved_capabilities = resolved_capability_contract(
        normalized,
        confirmations=confirmations,
    )
    manifest = {
        "version": 1,
        "stage": "extract",
        "skillName": str(normalized.get("skillName") or ""),
        "files": [],
        "pendingDecisions": [
            {
                "decisionId": item["decisionId"],
                "title": item.get("title") or item["decisionId"],
                "status": "pending"
                if str(item.get("decisionId") or "") not in confirmed_values
                else "confirmed",
                "source": "scenario_contract",
            }
            for item in pending
        ],
        "confirmedDecisions": [
            {
                "decisionId": item["decisionId"],
                "title": item.get("title") or item["decisionId"],
                "status": "confirmed",
                "value": confirmed_values[item["decisionId"]].get("value"),
                "displayValue": confirmed_values[item["decisionId"]].get("displayValue")
                or confirmed_values[item["decisionId"]].get("value"),
                "semanticConcept": confirmed_values[item["decisionId"]].get(
                    "semanticConcept"
                ),
                "semanticValue": confirmed_values[item["decisionId"]].get(
                    "semanticValue"
                ),
                "source": "hitl_confirmation",
            }
            for item in pending
            if str(item.get("decisionId") or "") in confirmed_values
        ],
        "unverifiedInputs": [],
        "blockers": [],
        "unverifiedCapabilities": [],
        "scenarioContract": {
            "schemaVersion": normalized.get("schemaVersion"),
            "semanticHash": normalized.get("semanticHash"),
        },
        "resolvedCapabilityContract": resolved_capabilities,
        "behaviorSignature": behavior_signature(normalized, resolved_capabilities),
        "scenarioHitl": {
            "kind": hitl_request.get("kind") if hitl_request else None,
            "decisionIds": hitl_request.get("decision_ids") or [],
        },
    }
    author_handoff = author_handoff_contract(
        normalized,
        confirmations=confirmations,
    )
    author_handoff_text = json.dumps(
        author_handoff,
        ensure_ascii=False,
        separators=(",", ":"),
    ) + "\n"
    author_handoff_bytes = len(author_handoff_text.encode("utf-8"))
    if author_handoff_bytes > AUTHOR_HANDOFF_MAX_BYTES:
        return {}, [
            "validation/author_handoff.json exceeds the deterministic Author "
            f"handoff budget ({author_handoff_bytes}>{AUTHOR_HANDOFF_MAX_BYTES} bytes)"
        ]
    return {
        "scenario_understanding.md": "\n".join(understanding_lines).rstrip() + "\n",
        "scenario_summary.md": "\n".join(summary_lines).rstrip() + "\n",
        "artifact_manifest.json": json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        "author_handoff.json": author_handoff_text,
    }, []


def project_persisted_scenario_contract(
    root: Path,
    *,
    confirmations: tuple[dict[str, Any], ...] | list[dict[str, Any]] = (),
) -> tuple[list[str], list[str]]:
    """Repair missing/stale projections from the persisted authoritative contract."""

    normalized, issues = load_persisted_scenario_contract(root)
    if issues:
        return [], issues
    artifacts, artifact_issues = scenario_contract_artifacts(
        normalized,
        confirmations=confirmations,
    )
    if artifact_issues:
        return [], artifact_issues
    changed: list[str] = []
    validation = root / "validation"
    validation.mkdir(parents=True, exist_ok=True)
    for relative, content in artifacts.items():
        path = validation / relative
        original = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else None
        if original == content:
            continue
        path.write_text(content, encoding="utf-8")
        changed.append(f"validation/{relative}")
    return changed, []


def scenario_projection_matches(root: Path, contract: Any) -> bool:
    normalized, issues = normalize_scenario_contract(contract)
    if issues:
        return False
    semantic_hash = str(normalized.get("semanticHash") or "")
    for relative in ("scenario_understanding.md", "scenario_summary.md"):
        path = root / "validation" / relative
        text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
        if SCENARIO_PROJECTION_MARKER not in text or semantic_hash not in text:
            return False
    try:
        manifest = json.loads((root / "validation" / "artifact_manifest.json").read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        return False
    if str((manifest.get("scenarioContract") or {}).get("semanticHash") or "") != semantic_hash:
        return False
    handoff_path = root / "validation" / "author_handoff.json"
    try:
        handoff_bytes = handoff_path.read_bytes()
        handoff = json.loads(handoff_bytes.decode("utf-8"))
    except (OSError, TypeError, ValueError):
        return False
    return bool(
        len(handoff_bytes) <= AUTHOR_HANDOFF_MAX_BYTES
        and isinstance(handoff, dict)
        and handoff.get("schemaVersion") == AUTHOR_HANDOFF_SCHEMA_VERSION
        and str(handoff.get("scenarioContractHash") or "") == semantic_hash
    )


__all__ = [
    "AUTHOR_HANDOFF_SCHEMA_VERSION",
    "AUTHOR_HANDOFF_MAX_BYTES",
    "authoritative_decision_form_fields",
    "author_handoff_contract",
    "SCENARIO_PROJECTION_MARKER",
    "load_persisted_scenario_contract",
    "project_persisted_scenario_contract",
    "resolved_capability_contract",
    "scenario_contract_artifacts",
    "scenario_contract_hitl_request",
    "scenario_projection_matches",
]
