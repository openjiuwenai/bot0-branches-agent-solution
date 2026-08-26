# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Decision, path, browser, and API contracts."""

from __future__ import annotations

from pathlib import Path
import posixpath
import re
from typing import Any

from .contract_constants import (
    DECISION_CONTRACT_NORMALIZER_VERSION,
    DECISION_CONTRACT_SCHEMA_VERSION,
    SANDBOX_WORKSPACE_ROOT,
    canonical_decision_capability,
)
from .decision_registry import (
    canonical_decision_option_value,
    canonical_decision_value,
    canonical_evidence_status,
    decision_capabilities,
    decision_capability_is_allowed,
    decision_context_is_authentication,
    decision_option_catalog,
    decision_value_is_valid,
    infer_decision_concept,
    infer_decision_concept_from_options,
    infer_decision_concept_from_title,
    normalize_decision_concept,
    project_decision_value,
    projected_decision_capabilities,
)
from .decision_semantics import normalize_semantic_effects


def decision_semantic_concept(
    field_id: Any,
    label: Any = "",
    description: Any = "",
    options: Any = None,
) -> str | None:
    """Return a registered concept from field ids or option semantics.

    Presentation copy is not a general contract language.  It is used only by
    the narrow authentication-context guard, where option values such as
    ``browser``/``api`` are otherwise indistinguishable from acquisition.
    """

    safe_options = options if isinstance(options, list) else []
    title_concept = infer_decision_concept_from_title(label)
    if title_concept and (
        not safe_options
        or all(
            canonical_decision_option_value(title_concept, option)[0] is not None
            for option in safe_options
        )
    ):
        return title_concept
    option_domain = infer_decision_concept_from_options(safe_options)
    if option_domain and all(
        canonical_decision_option_value(option_domain, option)[0] is not None
        for option in safe_options
    ):
        return option_domain
    if decision_context_is_authentication(field_id, label, description):
        return None
    field_concept = infer_decision_concept(field_id)
    if field_concept and safe_options:
        allowed = decision_option_catalog(field_concept).get(field_concept, {}).get(
            "allowedValues",
            [],
        )
        if allowed and not all(
            canonical_decision_option_value(field_concept, option)[0] is not None
            for option in safe_options
        ):
            # A familiar field id with a novel option domain is an opaque
            # business decision.  The platform must not reinterpret it as a
            # closed capability enum merely because the names collide.
            return None
    return field_concept


def decision_semantic_identity(
    field_id: Any,
    label: Any = "",
    description: Any = "",
    options: Any = None,
    *,
    explicit: Any = None,
) -> str:
    """Return a stable semantic key for every persisted HITL decision."""

    # Platform-owned field ids and complete registered option domains are more
    # authoritative than a model-authored ``business:*`` declaration.  The
    # latter remains valid only for genuinely opaque decisions.  This closes
    # the HITL ingress boundary without turning labels into an open-ended
    # natural-language parser: option labels are resolved only through the
    # exact catalog in decision_registry.
    known = decision_semantic_concept(field_id, label, description, options)
    if known:
        return known
    declared = normalize_decision_concept(explicit)
    if declared:
        return declared
    explicit_value = str(explicit or "").strip().lower()
    if explicit_value.startswith("business:") and explicit_value.removeprefix("business:"):
        return explicit_value
    del label, description
    stable_id = str(field_id or "").strip().lower()
    slug = re.sub(r"[^a-z0-9\u4e00-\u9fff]+", "_", stable_id).strip("_")[:64]
    if slug:
        return f"business:{slug}"
    return "business:anonymous"


def decision_semantic_value(concept: Any, value: Any, display_value: Any = "") -> str | None:
    """Normalize selected values through the same canonical registry."""

    normalized = canonical_decision_value(concept, value)
    if normalized:
        return normalized
    return canonical_decision_value(concept, display_value)


def decision_evidence_status(value: Any, label: Any = "", description: Any = "") -> str | None:
    """Normalize EvidenceStatus without interpreting presentation prose."""

    del label, description
    return canonical_evidence_status(value)

def _boolean_capability_map(value: Any) -> dict[str, bool]:
    if not isinstance(value, dict):
        return {}
    result: dict[str, bool] = {}
    for key, item in value.items():
        canonical = canonical_decision_capability(key)
        if canonical and isinstance(item, bool):
            result[canonical] = item
    return result


def normalize_capability_condition(value: Any) -> dict[str, Any] | None:
    """Normalize one simple capability dependency expression.

    The deliberately small expression language is stable across UI, storage,
    CLI, and future hosts. It supports the current need without evaluating
    arbitrary code or natural language downstream.
    """

    if not isinstance(value, dict):
        return None
    when = value.get("when")
    if not isinstance(when, dict):
        return None
    dependency = canonical_decision_capability(when.get("capability"))
    expected = when.get("equals", True)
    then_value = value.get("then", True)
    else_value = value.get("else", False)
    if (
        dependency is None
        or not isinstance(expected, bool)
        or not isinstance(then_value, bool)
        or not isinstance(else_value, bool)
    ):
        return None
    return {
        "when": {"capability": dependency, "equals": expected},
        "then": then_value,
        "else": else_value,
    }


def _conditional_capability_map(value: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(value, dict):
        return {}
    result: dict[str, dict[str, Any]] = {}
    for key, item in value.items():
        name = canonical_decision_capability(key)
        condition = normalize_capability_condition(item)
        if name and condition is not None:
            result[name] = condition
    return result


def resolve_capability_conditions(
    capabilities: dict[str, bool],
    conditions: dict[str, dict[str, Any]],
) -> dict[str, bool]:
    resolved = dict(capabilities)
    changed = True
    while changed:
        changed = False
        for name, condition in conditions.items():
            dependency = str(condition["when"]["capability"])
            dependency_value = resolved.get(dependency)
            if not isinstance(dependency_value, bool):
                continue
            next_value = condition["then"] if dependency_value is condition["when"]["equals"] else condition["else"]
            if name not in resolved:
                resolved[name] = bool(next_value)
                changed = True
    return resolved


def capability_condition_conflicts(
    capabilities: dict[str, bool],
    conditions: dict[str, dict[str, Any]],
) -> list[str]:
    """Validate conditional capabilities before materializing derived facts."""

    conflicts: list[str] = []
    for name, condition in conditions.items():
        dependency = str(condition["when"]["capability"])
        if dependency == name:
            conflicts.append(f"能力 {name} 不能依赖自身。")
            continue
        dependency_value = capabilities.get(dependency)
        explicit_value = capabilities.get(name)
        if isinstance(dependency_value, bool) and isinstance(explicit_value, bool):
            expected = condition["then"] if dependency_value is condition["when"]["equals"] else condition["else"]
            if explicit_value is not expected:
                conflicts.append(
                    f"能力 {name} 的显式值 {str(explicit_value).lower()} 与条件依赖 {dependency} "
                    f"推导值 {str(expected).lower()} 冲突。"
                )

    def visit(name: str, path: tuple[str, ...]) -> None:
        condition = conditions.get(name)
        if condition is None:
            return
        dependency = str(condition["when"]["capability"])
        if dependency in path:
            cycle = " -> ".join((*path[path.index(dependency) :], dependency))
            conflicts.append(f"能力条件依赖不能形成循环：{cycle}。")
            return
        visit(dependency, (*path, dependency))

    for capability in conditions:
        visit(capability, (capability,))
    return list(dict.fromkeys(conflicts))


def build_decision_contract(
    decisions: dict[str, Any],
    *,
    labels: dict[str, Any] | None = None,
    display_values: dict[str, Any] | None = None,
    fields: list[Any] | None = None,
    fallback_capabilities: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Materialize one authoritative contract from the submitted form schema.

    Platform-defined strong option values are authoritative.  Model-authored
    capability metadata remains authoritative only for opaque option values.
    The optional fallback is evaluated by the ingress normalizer only and is
    stored with provenance; downstream consumers never reinterpret prose.
    """

    labels = labels or {}
    display_values = display_values or {}
    field_map = {
        str(item.get("id") or item.get("decisionId") or item.get("key") or "").strip(): item
        for item in fields or []
        if isinstance(item, dict) and str(item.get("id") or item.get("decisionId") or item.get("key") or "").strip()
    }
    capabilities: dict[str, bool] = {}
    conditional_capabilities: dict[str, dict[str, Any]] = {}
    capability_sources: dict[str, list[str]] = {}
    implementation_dependencies: list[str] = []
    conflicts: list[str] = []
    normalized_decisions: dict[str, dict[str, Any]] = {}
    semantic_decisions: dict[str, tuple[str, str | None]] = {}

    def add_capability(name: Any, value: Any, source: str) -> None:
        key = canonical_decision_capability(name)
        if key is None or not isinstance(value, bool):
            return
        previous = capabilities.get(key)
        if isinstance(previous, bool) and previous is not value:
            conflicts.append(f"能力 {key} 同时被确认成 true 和 false。")
            return
        capabilities[key] = value
        capability_sources.setdefault(key, []).append(source)

    def add_condition(name: Any, value: Any, source: str) -> None:
        key = canonical_decision_capability(name)
        condition = normalize_capability_condition(value)
        if key is None or condition is None:
            return
        previous = conditional_capabilities.get(key)
        if previous is not None and previous != condition:
            conflicts.append(f"能力 {key} 同时声明了不同条件依赖。")
            return
        conditional_capabilities[key] = condition
        capability_sources.setdefault(key, []).append(source)

    for raw_key, value in decisions.items():
        key = str(raw_key or "").strip()
        if not key:
            continue
        field = field_map.get(key) if isinstance(field_map.get(key), dict) else {}
        selected_option: dict[str, Any] = {}
        for option in field.get("options") or []:
            if isinstance(option, dict) and option.get("value") == value:
                selected_option = option
                break
        selected_capabilities = _boolean_capability_map(selected_option.get("capabilityDecisions"))
        selected_conditions = _conditional_capability_map(selected_option.get("capabilityConditions"))
        field_capability = canonical_decision_capability(field.get("capability"))
        selected_capabilities = {
            name: capability_value
            for name, capability_value in selected_capabilities.items()
            if decision_capability_is_allowed(key, field.get("semanticConcept"), name)
        }
        selected_conditions = {
            name: condition
            for name, condition in selected_conditions.items()
            if decision_capability_is_allowed(key, field.get("semanticConcept"), name)
        }
        if field_capability and not decision_capability_is_allowed(
            key,
            field.get("semanticConcept"),
            field_capability,
        ):
            field_capability = None
        capability_value = selected_option.get("capabilityValue")
        capability_condition = selected_option.get("capabilityCondition")
        if field_capability and isinstance(capability_value, bool):
            selected_capabilities[field_capability] = capability_value
        elif field_capability and isinstance(value, bool):
            selected_capabilities[field_capability] = value
        if field_capability and normalize_capability_condition(capability_condition):
            selected_conditions[field_capability] = normalize_capability_condition(capability_condition) or {}
        selected_dependencies = list(
            dict.fromkeys(
                str(item).strip()
                for item in selected_option.get("implementationDependencies") or []
                if str(item).strip()
            )
        ) if isinstance(selected_option.get("implementationDependencies"), list) else []
        for dependency in selected_dependencies:
            if dependency not in implementation_dependencies:
                implementation_dependencies.append(dependency)
        selected_effects, effect_issues = normalize_semantic_effects(
            selected_option.get("semanticEffects")
        )
        conflicts.extend(f"决策 {key}: {message}" for message in effect_issues)
        evidence_status = str(selected_option.get("evidenceStatus") or "").strip() or decision_evidence_status(
            value,
            display_values.get(key) or selected_option.get("label"),
            selected_option.get("description"),
        )
        semantic_concept = decision_semantic_identity(
            key,
            labels.get(key) or field.get("label") or field.get("title"),
            field.get("description") or field.get("message"),
            field.get("options"),
            explicit=field.get("semanticConcept"),
        )
        semantic_capability = canonical_decision_capability(semantic_concept)
        capability_identity = semantic_capability or field_capability
        capability_only = bool(
            capability_identity
            and (
                capability_identity in selected_capabilities
                or capability_identity in selected_conditions
            )
        )
        canonical_option_value, canonical_option_source = canonical_decision_option_value(
            semantic_concept,
            {
                **selected_option,
                "value": value,
                "label": display_values.get(key) or selected_option.get("label"),
            },
        )
        semantic_value = None if capability_only else (
            canonical_option_value
            or decision_semantic_value(
                semantic_concept,
                value,
                display_values.get(key) or selected_option.get("label"),
            )
        )
        canonical_semantic_value = canonical_decision_value(semantic_concept, semantic_value)
        if not capability_only and canonical_semantic_value is not None:
            semantic_value = canonical_semantic_value
        if canonical_option_source:
            selected_option["semanticValueSource"] = canonical_option_source
        if (
            not capability_only
            and not decision_option_is_unknown(selected_option)
            and not decision_value_is_valid(key, semantic_concept, semantic_value)
        ):
            invalid_value = semantic_value if semantic_value is not None else value
            conflicts.append(
                f"决策 {key} 的 semanticValue={invalid_value} 不属于平台概念 {semantic_concept} 的注册值。"
            )
        selected_capabilities.update(
            projected_decision_capabilities(key, semantic_concept, semantic_value)
        )
        for capability_name, capability_value in selected_capabilities.items():
            add_capability(capability_name, capability_value, f"decision:{key}")
        for capability_name, condition in selected_conditions.items():
            add_condition(capability_name, condition, f"decision:{key}")
        semantic_projection = project_decision_value(key, semantic_concept, semantic_value)
        if not semantic_projection and semantic_concept and semantic_value:
            semantic_projection = {semantic_concept: semantic_value}
        for effect_concept, effect_value in selected_effects.items():
            previous_effect = semantic_projection.get(effect_concept)
            if previous_effect is not None and previous_effect != effect_value:
                conflicts.append(
                    f"决策 {key} 对 {effect_concept} 同时投影为 {previous_effect} 和 {effect_value}。"
                )
                continue
            semantic_projection[effect_concept] = effect_value
        # Multi-concept effects are first-class typed decisions.  Project any
        # runtime capabilities implied by those effects at the confirmation
        # boundary, so every downstream consumer sees the same API/browser/
        # screenshot contract without re-reading option labels.
        for projected_concept, projected_value in semantic_projection.items():
            for capability_name, capability_value in decision_capabilities(
                projected_concept,
                projected_value,
            ).items():
                add_capability(capability_name, capability_value, f"decision:{key}:semantic_effect")
        for projected_concept, projected_value in semantic_projection.items():
            previous = semantic_decisions.get(projected_concept)
            if previous and previous[1] and previous[1] != projected_value:
                conflicts.append(
                    f"同一决策语义 {projected_concept} 出现冲突："
                    f"{previous[0]}={previous[1]}，{key}={projected_value}。"
                    "请只保留一个用户可见字段并统一选择。"
                )
            else:
                semantic_decisions.setdefault(projected_concept, (key, projected_value))
        normalized_decisions[key] = {
            "value": value,
            "label": str(labels.get(key) or field.get("label") or field.get("title") or key),
            "displayValue": str(display_values.get(key) or selected_option.get("label") or value),
            "semanticConcept": semantic_concept,
            "semanticValue": semantic_value,
            "evidenceStatus": evidence_status or None,
            "capabilities": selected_capabilities,
            "conditionalCapabilities": selected_conditions,
            "implementationDependencies": selected_dependencies,
            "semanticEffects": selected_effects,
        }

    for capability_name, capability_value in _boolean_capability_map(fallback_capabilities).items():
        if capability_name not in capabilities:
            add_capability(capability_name, capability_value, "normalized_ingress")

    conflicts.extend(capability_condition_conflicts(capabilities, conditional_capabilities))
    resolved_capabilities = resolve_capability_conditions(capabilities, conditional_capabilities)
    for capability_name, capability_value in resolved_capabilities.items():
        if capability_name not in capabilities:
            add_capability(capability_name, capability_value, f"condition:{capability_name}")

    return {
        "schemaVersion": DECISION_CONTRACT_SCHEMA_VERSION,
        "normalizerVersion": DECISION_CONTRACT_NORMALIZER_VERSION,
        "decisions": normalized_decisions,
        "capabilities": capabilities,
        "conditionalCapabilities": conditional_capabilities,
        "capabilitySources": {
            key: sorted(set(values)) for key, values in capability_sources.items()
        },
        "implementationDependencies": implementation_dependencies,
        "conflicts": list(dict.fromkeys(conflicts)),
    }


def canonical_workspace_relative_path(value: Any, *, workspace_root: Path | str | None = None) -> str:
    """Normalize host, sandbox and contract paths to one workspace-relative path.

    ``/workspace`` is the mounted Jiuwenbox root, not a real directory inside
    the exported workspace.  Treating it with a simple ``lstrip('/')`` turns
    ``/workspace/inputs/a.json`` into the incorrect ``workspace/inputs/a.json``.
    All Skill Builder readers, writers and verification contracts use this
    helper so absolute sandbox paths and package-relative paths resolve equally.
    Other absolute paths and parent traversal are rejected.
    """

    raw = str(value or "").strip().strip("'\"").replace("\\", "/")
    if raw == SANDBOX_WORKSPACE_ROOT:
        raw = "."
    elif raw.startswith(f"{SANDBOX_WORKSPACE_ROOT}/"):
        raw = raw[len(SANDBOX_WORKSPACE_ROOT) + 1 :]
    elif raw.startswith("/") or re.match(r"^[A-Za-z]:/", raw):
        # Agent runtimes should normally only see /workspace paths.  If an
        # adapter or diagnostic nevertheless returns the exact host workspace
        # path, accept it only when the caller supplied that trusted root and
        # convert it at the shared boundary.  Arbitrary host paths remain
        # rejected; string-prefix checks are deliberately avoided.
        if workspace_root is None or re.match(r"^[A-Za-z]:/", raw):
            raise ValueError("absolute path is outside the Skill Builder workspace")
        root_resolved = Path(workspace_root).resolve()
        candidate = Path(raw).resolve()
        if candidate != root_resolved and not candidate.is_relative_to(root_resolved):
            raise ValueError("absolute path is outside the Skill Builder workspace")
        raw = candidate.relative_to(root_resolved).as_posix() if candidate != root_resolved else "."

    normalized = posixpath.normpath(raw or ".")
    if normalized in {"", "."}:
        return "."
    if normalized == ".." or normalized.startswith("../"):
        raise ValueError("path escapes workspace")
    return normalized


def decision_option_is_unknown(option: dict[str, Any]) -> bool:
    """Return whether the option's choice itself is deliberately unknown.

    Evidence status is orthogonal to the selected machine value.  A concrete
    choice remains subject to its closed enum even when it has not been observed
    in the current environment.
    """

    value = str(option.get("value") or "").strip().lower().replace("-", "_").replace(" ", "_")
    return bool(
        value.startswith("not_verified")
        or value in {"unknown", "unspecified", "undecided", "pending"}
    )



__all__ = [
    "_boolean_capability_map",
    "normalize_capability_condition",
    "resolve_capability_conditions",
    "build_decision_contract",
    "capability_condition_conflicts",
    "canonical_workspace_relative_path",
    "decision_option_is_unknown",
]
