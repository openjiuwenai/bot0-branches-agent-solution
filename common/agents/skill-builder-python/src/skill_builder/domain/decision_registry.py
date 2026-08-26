# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Canonical identities for model-authored HITL decisions.

Decision fields are transport objects.  Their labels are presentation copy and
must not be reinterpreted by every validation layer.  This registry recognizes
stable concepts only from an explicit ``semanticConcept``, a registered field
identifier, or the semantic domain of the option values.  Unknown business
decisions remain opaque and keep a stable ``business:`` identity.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any, Iterable


def _token(value: Any) -> str:
    raw = "" if value is None else str(value)
    return re.sub(r"[^a-z0-9_.:-]+", "_", raw.strip().lower().replace("-", "_")).strip("_")


@dataclass(frozen=True, slots=True)
class DecisionConceptDefinition:
    name: str
    field_ids: frozenset[str] = frozenset()
    value_aliases: tuple[tuple[str, frozenset[str]], ...] = ()
    minimum_domain_matches: int = 0
    closed_values: bool = False

    def canonical_value(self, value: Any) -> str | None:
        if value is None:
            return None if self.closed_values and self.value_aliases else "null"
        if isinstance(value, bool):
            candidate = "true" if value else "false"
        elif isinstance(value, (dict, list)):
            # Registered decisions are scalar ontology values.  Lossless
            # structured business requirements belong in resolvedRequirements;
            # serializing them here polluted decisions/dataAuthority/outputMode
            # with JSON strings that no downstream consumer could interpret.
            return None
        else:
            candidate = _token(value)
        for canonical, aliases in self.value_aliases:
            if candidate == canonical or candidate in aliases:
                return canonical
        if (
            self.name in {"acquisition_mode", "runtime_collection_mode"}
            and candidate.startswith("from_")
            and candidate not in {"from_api", "from_browser", "from_file"}
        ):
            # A source-system export is a manual acquisition mode regardless of
            # the customer-specific system identifier following ``from_``.
            return "manual"
        if self.closed_values and self.value_aliases:
            return None
        return candidate or None

    def option_domain_matches(self, options: Iterable[Any]) -> set[str]:
        matches: set[str] = set()
        known_values = {
            value
            for canonical, aliases in self.value_aliases
            for value in (canonical, *aliases)
        }
        for option in options:
            if not isinstance(option, dict):
                raw_value = option
                explicit_value = None
            else:
                raw_value = option.get("value")
                explicit_value = option.get("semanticValue")
            for value in (explicit_value, raw_value):
                candidate = _token(value)
                if candidate in known_values:
                    canonical = self.canonical_value(candidate)
                    if canonical:
                        matches.add(canonical)
        return matches


@dataclass(frozen=True, slots=True)
class DecisionValueProjection:
    """Project one legacy machine value onto the current decision ontology.

    Some early HITL fields encoded more than one independent choice in one
    transport value (for example an authoritative source together with an API
    acquisition method).  Those values cannot be migrated by canonicalizing a
    single concept.  The migration is intentionally keyed by the persisted
    field id and machine value; presentation labels and descriptions are never
    interpreted.
    """

    field_ids: frozenset[str]
    values: frozenset[str]
    decisions: tuple[tuple[str, str], ...]

    def matches(self, field_id: Any, value: Any) -> bool:
        return _token(field_id) in self.field_ids and _token(value) in self.values


DECISION_CONCEPTS: tuple[DecisionConceptDefinition, ...] = (
    DecisionConceptDefinition(
        "score_range_strategy",
        field_ids=frozenset({
            "score_range_strategy",
            "score_range_value_strategy",
            "scoring_within_range",
        }),
        value_aliases=(
            ("lower_bound", frozenset({"fixed_lower_bound"})),
            ("upper_bound", frozenset({"fixed_upper_bound"})),
            ("midpoint", frozenset({"fixed_midpoint", "mid_point"})),
            ("linear_interpolation", frozenset({"interpolation", "linear"})),
        ),
        minimum_domain_matches=2,
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "runtime_collection_mode",
        field_ids=frozenset({
            "runtime_collection_mode",
            "runtime_method",
            "runtime_approach",
            "collection_method",
            "collection_mode",
        }),
        value_aliases=(
            ("api", frozenset({"api_only", "api_first", "api_primary", "api_with_page_doc", "gitee_api", "http", "http_requests", "http_html"})),
            ("browser", frozenset({"browser_only", "browser_required", "playwright", "playwright_only", "playwright_page", "playwright_primary", "live_scrape"})),
            ("hybrid", frozenset({"api_and_browser", "browser_and_api", "combined"})),
            ("fixture", frozenset({"fixture_offline", "offline", "file", "sample", "cli_arg", "manual", "sop", "no_script"})),
        ),
        minimum_domain_matches=2,
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "data_authority",
        field_ids=frozenset({
            "data_authority",
            "authoritative_source",
            "source_of_truth",
            "exchange_rate_source",
            "rate_source",
            "official_data_source",
        }),
        value_aliases=(
            ("official_source", frozenset({"official", "authority", "authoritative"})),
            ("bank_of_china", frozenset({"boc", "boc_website", "bank_of_china_website"})),
            ("uploaded_materials", frozenset({"uploaded", "materials", "input_files"})),
            ("user_provided", frozenset({"user_input", "manual_input"})),
            ("fixture", frozenset({"sample", "test_fixture"})),
        ),
    ),
    DecisionConceptDefinition(
        "acquisition_mode",
        field_ids=frozenset({
            "acquisition_mode",
            "acquisition_method",
            "data_acquisition_mode",
            "data_collection_method",
        }),
        value_aliases=(
            ("api", frozenset({
                "api_only", "api_first", "api_primary", "api_with_page_doc",
                "api_integration", "gitee_api", "http", "http_requests", "http_parse", "http_html",
            })),
            ("browser", frozenset({"playwright", "playwright_only", "browser_only", "browser_automation", "playwright_page", "playwright_scrape"})),
            ("browser_with_manual_fallback", frozenset({
                "browser_manual_fallback",
                "browser_and_manual",
                "playwright_with_manual_fallback",
            })),
            ("hybrid", frozenset({
                "api_and_browser",
                "browser_and_api",
                "api_hybrid",
                "playwright_plus_api",
                "playwright_with_api_fallback",
                "browser_with_api_fallback",
                "combined",
            })),
            ("file", frozenset({
                "uploaded_file",
                "input_file",
                "cli_arg",
                "manual_provide",
                "user_provided_file",
            })),
            ("manual", frozenset({
                "manual_input",
                "manual_with_import",
                "manual_export",
                "manual_export_import",
                "from_internal_system",
                "human_export",
                "human_operated_import",
                "system_export",
                "sop",
            })),
            ("fixture", frozenset({"offline_fixture", "fixture_offline", "sample"})),
        ),
        minimum_domain_matches=2,
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "skill_delivery_mode",
        field_ids=frozenset({
            "skill",
            "skill_kind",
            "package_kind",
            "delivery_kind",
            "skill_delivery_mode",
        }),
        value_aliases=(
            ("knowledge", frozenset({"knowledge_only", "documentation", "sop"})),
            ("executable", frozenset({"script", "cli", "offline_script"})),
            ("browser", frozenset({"browser_automation", "browser_only"})),
            ("hybrid", frozenset({"mixed", "knowledge_and_browser"})),
        ),
        minimum_domain_matches=2,
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "external_system_access_mode",
        field_ids=frozenset({
            "external_system_access_mode",
            "external_system_access_method",
            "external_system_connection_mode",
            "system_access_mode",
        }),
        value_aliases=(
            ("api", frozenset({"api_only", "api_direct", "http"})),
            ("browser", frozenset({"browser_only", "browser_automation", "playwright"})),
            ("hybrid", frozenset({"api_and_browser", "browser_and_api", "combined"})),
            ("manual", frozenset({"manual_operation", "human_operated"})),
        ),
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "external_system_integration",
        field_ids=frozenset({
            "external_system_integration",
            "external_system_integration_scope",
            "external_system_scope",
            "integration_scope",
            "system_integration_scope",
        }),
        value_aliases=(
            ("none", frozenset({"no_integration", "disabled"})),
            ("crm_only", frozenset({"crm"})),
            ("limited", frozenset({"partial", "limited_integration"})),
            ("full", frozenset({"all", "full_integration"})),
        ),
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "runtime_mode",
        field_ids=frozenset({
            "runtime_mode",
            "script_mode",
            "execution_mode",
            "offline_script",
        }),
        value_aliases=(
            ("online", frozenset({"live", "realtime", "real_time"})),
            ("offline", frozenset({"file", "local", "offline_script", "fixture"})),
            ("hybrid", frozenset({"online_and_offline", "live_and_fixture"})),
            ("documentation_only", frozenset({"no_script", "manual_sop", "sop"})),
        ),
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "api_runtime",
        field_ids=frozenset({
            "api_runtime",
            "use_api_runtime",
            "export_api_runtime",
            "technology_stack",
        }),
        value_aliases=(
            ("enabled", frozenset({"true", "yes", "required", "api", "api_first", "api_only", "http", "http_requests", "http_html", "python_requests", "python_stdlib", "requests_bs4"})),
            ("disabled", frozenset({"false", "no", "none", "no_api", "offline", "file", "manual", "browser", "browser_only", "playwright_only", "no_script"})),
        ),
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "browser_runtime",
        field_ids=frozenset({
            "browser_runtime",
            "export_browser_runtime",
            "include_browser_runtime",
            "generate_runtime_playwright",
            "runtime_browser_requested",
        }),
        value_aliases=(
            ("enabled", frozenset({"true", "yes", "required", "browser", "browser_required", "browser_automation", "playwright", "playwright_only", "hybrid", "runtime_browser_automation"})),
            ("disabled", frozenset({"false", "no", "none", "disabled", "excluded", "no_browser", "not_exported", "api", "api_first", "api_only", "http", "file", "manual", "sop"})),
        ),
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "output_format",
        field_ids=frozenset({
            "output_format",
            "output_json_format",
            "output_type",
            "report_format",
            "json_output",
            "structured_json_output",
        }),
        value_aliases=(
            ("markdown", frozenset({"md", "markdown_only"})),
            ("json", frozenset({"json_only"})),
            ("json_and_markdown", frozenset({
                "markdown_and_json",
                "markdown_json",
                "json_markdown",
                "markdown_report_and_json",
                "both",
            })),
            ("pdf", frozenset({"pdf_only"})),
        ),
        minimum_domain_matches=2,
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "validation_input_mode",
        field_ids=frozenset({
            "validation_input_mode",
            "verification_input_mode",
            "test_input_mode",
        }),
        value_aliases=(
            ("fixture", frozenset({"offline", "sample", "file"})),
            ("live", frozenset({"online", "runtime"})),
            ("fixture_and_live", frozenset({"both", "hybrid"})),
        ),
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "validation_transport",
        field_ids=frozenset({
            "validation_transport",
            "verification_transport",
            "validation_json_transport",
            "result_transport",
        }),
        value_aliases=(
            ("stdout_json", frozenset({"stdout-json", "stdout", "json_stdout"})),
            ("file_json", frozenset({"file-json", "file", "json_file", "sidecar"})),
        ),
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "runtime_screenshot_output",
        field_ids=frozenset({
            "runtime_screenshot_output",
            "screenshot_runtime",
            "screenshot_output",
            "screenshot_output_policy",
            "screenshot_requirement",
            "screenshots",
            "screenshots_in_output",
            "generate_runtime_screenshots",
        }),
        value_aliases=(
            ("enabled", frozenset({"true", "yes", "required", "include", "included", "export", "runtime_output", "exported_output"})),
            ("disabled", frozenset({"false", "no", "none", "disabled", "excluded", "optional", "validation_only", "verification_only", "evidence_only", "platform_validation_only", "platform_evidence_only", "no_runtime_output", "no_screenshot_output", "not_exported"})),
        ),
        closed_values=True,
    ),
    DecisionConceptDefinition(
        "skill_name",
        field_ids=frozenset({
            "skill_name",
            "skill_title",
            "skill_slug",
            "skill_skill_kebab_case",
        }),
    ),
    DecisionConceptDefinition(
        "pdf_download",
        field_ids=frozenset({"pdf_download", "download_pdf"}),
    ),
    DecisionConceptDefinition(
        "result_limit",
        field_ids=frozenset({"result_limit", "max_results", "max_items"}),
    ),
    DecisionConceptDefinition(
        "search_field",
        field_ids=frozenset({"search_field", "search_scope"}),
    ),
    DecisionConceptDefinition(
        "report_output",
        field_ids=frozenset({"report_output", "include_report", "generate_report"}),
    ),
)

DECISION_CONCEPT_BY_NAME = {item.name: item for item in DECISION_CONCEPTS}
DECISION_CONCEPT_BY_FIELD_ID = {
    field_id: item
    for item in DECISION_CONCEPTS
    for field_id in item.field_ids
}

# These concepts describe platform validation transport, not how the exported
# Skill behaves for its user, and must never become public Scenario HITL questions.
PLATFORM_MANAGED_DECISION_CONCEPTS = frozenset({
    "validation_input_mode",
    "validation_transport",
})


def _label_token(value: Any) -> str:
    """Normalize only presentation punctuation for exact catalog matching."""

    return re.sub(
        r"[\s+＋/&、，,：:（）()\[\]【】_-]+",
        "",
        str(value or "").strip().lower(),
    )


# Presentation labels are intentionally registered beside the machine-value
# ontology.  They are not a second natural-language parser: only exact catalog
# aliases (after harmless punctuation/spacing normalization) can correct a
# model-authored label/value mismatch at the HITL ingress boundary.
DECISION_OPTION_LABELS: dict[str, dict[str, tuple[str, frozenset[str]]]] = {
    "runtime_mode": {
        "online": ("在线运行", frozenset({"在线运行", "在线采集", "在线采集与处理"})),
        "offline": ("离线运行", frozenset({"离线运行", "离线文件模式", "使用导入文件离线运行"})),
        "hybrid": ("在线与离线混合运行", frozenset({"在线与离线混合运行", "混合运行"})),
        "documentation_only": ("仅文档/SOP", frozenset({"仅文档", "仅 SOP", "仅文档/SOP"})),
    },
    "acquisition_mode": {
        "api": ("API/HTTP 采集", frozenset({"API", "API/HTTP 采集", "HTTP 采集"})),
        "browser": ("浏览器自动化采集", frozenset({"浏览器采集", "浏览器自动化采集", "Playwright 采集"})),
        "browser_with_manual_fallback": (
            "浏览器优先，失败时人工处理",
            frozenset({"浏览器与人工混合", "浏览器优先并人工降级"}),
        ),
        "hybrid": ("API 与浏览器混合采集", frozenset({"API 与浏览器混合采集", "混合采集"})),
        "file": ("导入文件", frozenset({"导入文件", "离线导入文件", "文件导入"})),
        "manual": (
            "人工输入",
            frozenset({
                "人工输入",
                "手工录入",
                "人工操作",
                "用户手动导出",
                "用户手动操作导出",
                "人工前置采集",
            }),
        ),
        "fixture": ("离线样例", frozenset({"离线样例", "Fixture 样例"})),
    },
    "external_system_access_mode": {
        "api": ("API 直连", frozenset({"API 直连", "API 接入"})),
        "browser": ("浏览器操作", frozenset({"浏览器操作", "Playwright 操作"})),
        "hybrid": ("混合接入", frozenset({"混合接入", "混合模式"})),
        "manual": ("人工操作", frozenset({"人工操作", "人工办理"})),
    },
    "output_format": {
        "markdown": (
            "Markdown 报告",
            frozenset({"Markdown", "Markdown 报告", "仅 Markdown", "仅 Markdown 报告", "Markdown-only"}),
        ),
        "json": (
            "JSON 结构化数据",
            frozenset({"JSON", "JSON 结构化数据", "仅 JSON", "JSON-only"}),
        ),
        "json_and_markdown": (
            "Markdown 报告 + JSON 结构化数据",
            frozenset({
                "JSON 与 Markdown",
                "JSON + Markdown",
                "Markdown + JSON",
                "Markdown 与 JSON",
                "Markdown 报告 + JSON 结构化数据",
                "Markdown 报价单 + 结构化 JSON",
                "Markdown 报告和 JSON",
            }),
        ),
        "pdf": ("PDF 报告", frozenset({"PDF", "PDF 报告", "仅 PDF"})),
    },
    "validation_input_mode": {
        "fixture": ("离线 Fixture", frozenset({"Fixture", "离线 Fixture", "本地 Fixture 数据", "Fixture 离线汇率文件"})),
        "live": ("真实在线输入", frozenset({"真实在线输入", "在线验证"})),
        "fixture_and_live": ("Fixture 与在线输入", frozenset({"Fixture 与在线输入", "离线与在线验证"})),
    },
    "validation_transport": {
        "stdout_json": ("stdout JSON", frozenset({"stdout JSON", "stdout JSON 输出", "标准输出 JSON"})),
        "file_json": ("JSON 文件", frozenset({"JSON 文件", "file JSON", "JSON sidecar"})),
    },
}


def decision_option_catalog(concept: Any = None) -> dict[str, Any]:
    """Expose canonical values and preferred labels from the one registry."""

    requested = normalize_decision_concept(concept) if concept is not None else None
    definitions = (
        [DECISION_CONCEPT_BY_NAME[requested]]
        if requested in DECISION_CONCEPT_BY_NAME
        else list(DECISION_CONCEPTS)
    )
    catalog: dict[str, Any] = {}
    for definition in definitions:
        if not definition.value_aliases:
            continue
        labels = DECISION_OPTION_LABELS.get(definition.name, {})
        catalog[definition.name] = {
            "allowedValues": [canonical for canonical, _aliases in definition.value_aliases],
            "options": [
                {
                    "value": canonical,
                    "label": labels.get(canonical, (canonical, frozenset()))[0],
                }
                for canonical, _aliases in definition.value_aliases
            ],
        }
    return catalog


def canonical_value_from_presented_label(concept: Any, label: Any) -> str | None:
    """Resolve a label only when it is an exact registered presentation alias."""

    normalized_concept = normalize_decision_concept(concept)
    candidate = _label_token(label)
    if not normalized_concept or not candidate:
        return None
    matches = [
        canonical
        for canonical, (preferred, aliases) in DECISION_OPTION_LABELS.get(normalized_concept, {}).items()
        if candidate in {_label_token(preferred), *(_label_token(item) for item in aliases)}
    ]
    return matches[0] if len(matches) == 1 else None


_DECISION_CONCEPT_TITLE_ALIASES = {
    _label_token(title): concept
    for concept, titles in {
        "external_system_integration": {
            "外部系统集成范围",
            "外部系统集成方式",
            "External system integration scope",
        },
        "external_system_access_mode": {
            "外部系统接入方式",
            "外部系统访问方式",
            "外部系统连接方式",
            "External system access mode",
            "External system connection mode",
        },
        "acquisition_mode": {
            "采集方式",
            "数据采集方式",
            "数据获取方式",
            "外部查询采集方式",
            "Data acquisition mode",
            "Data collection mode",
        },
    }.items()
    for title in titles
}


def infer_decision_concept_from_title(title: Any) -> str | None:
    """Resolve registered titles and bounded external-system name variants."""

    exact = _DECISION_CONCEPT_TITLE_ALIASES.get(_label_token(title))
    if exact is not None:
        return exact
    raw = str(title or "").strip()
    if re.search(
        r"(?:采集方式|数据获取方式|(?:数据源[^，。；;]{0,24}|[^，。；;]{0,24}流水)(?:的)?获取方式|查询入口(?:的)?访问方式)$",
        raw,
        re.IGNORECASE,
    ):
        return "acquisition_mode"
    if re.search(
        r"(?:外部系统[^，。；;]{0,32}|[^，。；;]{1,24}系统)(?:的)?"
        r"(?:接入|访问|连接)方式$",
        raw,
        re.IGNORECASE,
    ):
        return "external_system_access_mode"
    return None


def _acquisition_value_from_presentation(option: dict[str, Any]) -> str | None:
    """Resolve bounded acquisition wording after the field concept is known."""

    text = " ".join(
        str(option.get(key) or "").strip()
        for key in ("label", "description")
        if str(option.get(key) or "").strip()
    )
    if not text:
        return None
    if re.search(
        r"(?:用户|人工|手动).{0,20}(?:提供|上传).{0,16}(?:文件|数据源|数据|xlsx|csv)"
        r"|(?:文件|数据源|xlsx|csv).{0,16}(?:由用户|人工提供|手动提供|导入)",
        text,
        re.IGNORECASE,
    ):
        return "file"
    if re.search(r"(?:\bapi\b|接口对接|接口获取|http)", text, re.IGNORECASE):
        return "api"
    if re.search(
        r"(?:浏览器|playwright|selenium|skill自动|自动登录|自动采集|自动获取|自动导出)",
        text,
        re.IGNORECASE,
    ):
        if re.search(r"(?:失败|阻断|验证码).{0,16}(?:人工|手动)", text, re.IGNORECASE):
            return "browser_with_manual_fallback"
        return "browser"
    if re.search(
        r"(?:用户|人工|手动).{0,20}(?:导出|操作|查询|采集|录入)",
        text,
        re.IGNORECASE,
    ):
        return "manual"
    if re.search(r"(?:离线样例|fixture|测试样例)", text, re.IGNORECASE):
        return "fixture"
    return None


def canonical_decision_option_value(
    concept: Any,
    option: Any,
) -> tuple[str | None, str | None]:
    """Resolve one option through the single machine-first precedence rule."""

    if not isinstance(option, dict):
        canonical = canonical_decision_value(concept, option)
        return canonical, "value" if canonical is not None else None
    for source, raw in (
        ("value", option.get("value")),
        ("semanticValue", option.get("semanticValue")),
    ):
        canonical = canonical_decision_value(concept, raw)
        if canonical is not None:
            return canonical, source
    canonical = canonical_value_from_presented_label(concept, option.get("label"))
    if canonical is not None:
        return canonical, "label"
    if normalize_decision_concept(concept) == "acquisition_mode":
        canonical = _acquisition_value_from_presentation(option)
        if canonical is not None:
            return canonical, "presentation"
    return None, None


def decision_option_catalog_for_fields(fields: Iterable[Any]) -> dict[str, Any]:
    """Return the canonical enum catalog relevant to one submitted form."""

    result: dict[str, Any] = {}
    for field in fields:
        if not isinstance(field, dict):
            continue
        field_id = str(field.get("id") or field.get("decisionId") or "").strip()
        declared = str(field.get("semanticConcept") or "").strip().lower()
        if declared.startswith("business:") and declared.removeprefix("business:"):
            continue
        concept = normalize_decision_concept(declared) or infer_decision_concept(
            field_id, field.get("options")
        )
        definition = DECISION_CONCEPT_BY_NAME.get(concept or "")
        if not field_id or definition is None or not definition.closed_values:
            continue
        result[field_id] = {
            "semanticConcept": concept,
            **decision_option_catalog(concept).get(concept or "", {}),
        }
    return result


# Complete migrations for legacy composite values.  Each entry describes the
# full meaning of that old value, so callers must not also retain the legacy
# one-concept interpretation when an entry matches.
DECISION_VALUE_PROJECTIONS: tuple[DecisionValueProjection, ...] = (
    DecisionValueProjection(
        field_ids=frozenset({
            "collection_method",
            "collection_mode",
            "data_collection_method",
            "runtime_collection_mode",
            "runtime_method",
            "runtime_approach",
        }),
        values=frozenset({"gitee_api_v5"}),
        decisions=(
            ("data_authority", "gitee"),
            ("acquisition_mode", "api"),
        ),
    ),
    DecisionValueProjection(
        field_ids=frozenset({
            "exchange_rate_source",
            "rate_source",
            "official_data_source",
        }),
        values=frozenset({"boc_website", "bank_of_china_website"}),
        decisions=(("data_authority", "bank_of_china"),),
    ),
    DecisionValueProjection(
        field_ids=frozenset({"script_mode", "execution_mode", "offline_script"}),
        values=frozenset({"offline_script"}),
        decisions=(("runtime_mode", "offline"),),
    ),
    DecisionValueProjection(
        field_ids=frozenset({"json_output", "structured_json_output"}),
        values=frozenset({"true", "yes", "enabled"}),
        decisions=(("output_format", "json_and_markdown"),),
    ),
    DecisionValueProjection(
        field_ids=frozenset({"json_output", "structured_json_output"}),
        values=frozenset({"false", "no", "disabled"}),
        decisions=(("output_format", "markdown"),),
    ),
)

EVIDENCE_STATUS_VALUE_ALIASES: tuple[tuple[str, frozenset[str]], ...] = (
    ("observed", frozenset({"verified", "available"})),
    ("not_verified", frozenset({"unverified", "unknown"})),
    ("not_applicable", frozenset({"na", "n_a", "excluded"})),
    ("confirmed_absent", frozenset({"absent", "confirmed_missing"})),
)


def normalize_decision_concept(value: Any) -> str | None:
    candidate = _token(value)
    if candidate == "runtime_collection_mode":
        return "acquisition_mode"
    return candidate if candidate in DECISION_CONCEPT_BY_NAME else None


def infer_decision_concept(field_id: Any, options: Any = None) -> str | None:
    """Infer a registered concept without interpreting user-visible prose."""

    token = _token(field_id)
    candidates = [token]
    if token.startswith("decision_"):
        candidates.append(token.removeprefix("decision_"))
    for candidate in tuple(candidates):
        for suffix in ("_preference", "_choice", "_selection"):
            if candidate.endswith(suffix):
                candidates.append(candidate.removesuffix(suffix))
    for candidate in dict.fromkeys(candidates):
        registered = DECISION_CONCEPT_BY_FIELD_ID.get(candidate)
        if registered is not None:
            return normalize_decision_concept(registered.name)
    # Material fact ids commonly qualify a registered field with its resolved
    # enum value (for example ``output-format-markdown``).  Accept that shape
    # only when the suffix is an exact value in the same closed registry.  This
    # recovers a stable identity without interpreting titles or prose.
    qualified_concepts: set[str] = set()
    for registered_id, definition in DECISION_CONCEPT_BY_FIELD_ID.items():
        prefix = f"{registered_id}_"
        if not token.startswith(prefix) or not definition.value_aliases:
            continue
        qualifier = token.removeprefix(prefix)
        if definition.canonical_value(qualifier) is None:
            continue
        normalized = normalize_decision_concept(definition.name)
        if normalized:
            qualified_concepts.add(normalized)
    if len(qualified_concepts) == 1:
        return next(iter(qualified_concepts))
    return infer_decision_concept_from_options(options)


def decision_context_is_authentication(
    field_id: Any = "",
    label: Any = "",
    description: Any = "",
) -> bool:
    """Return whether a decision is explicitly about authentication.

    ``browser``/``api`` are also valid acquisition values, but the same
    values can describe how a user signs in.  Option-domain inference cannot
    distinguish those meanings.  A narrow, machine-independent context guard
    keeps authentication choices business-owned without interpreting general
    prose or account-number inputs.
    """

    identity = " ".join(
        str(value or "").strip().lower()
        for value in (field_id, label)
    )
    auth_subject = (
        r"(?:登录|登陆|认证|凭据|密码|login|log[ -]?in|"
        r"auth(?:entication)?|credential|password|sign[ -]?in)"
    )
    if re.search(auth_subject, identity, re.IGNORECASE):
        return True
    detail = str(description or "").strip().lower()
    detail = re.sub(
        r"(?:无需|不需|不需要|不用|免)(?:进行)?\s*"
        + auth_subject,
        "",
        detail,
        flags=re.IGNORECASE,
    )
    return bool(
        re.search(
            auth_subject + r".{0,12}(?:方式|方法|来源|提供|获取|输入|选择|配置)"
            r"|(?:方式|方法|来源).{0,12}" + auth_subject,
            detail,
            re.IGNORECASE,
        )
    )


def decision_concept_is_platform_managed(
    field_id: Any,
    concept: Any = None,
    options: Any = None,
) -> bool:
    """Return whether a decision belongs to internal validation policy."""

    declared = str(concept or "").strip().lower()
    if declared.startswith("business:") and declared.removeprefix("business:"):
        return False
    normalized = normalize_decision_concept(concept) or infer_decision_concept(
        field_id,
        options,
    )
    return normalized in PLATFORM_MANAGED_DECISION_CONCEPTS


def infer_decision_concept_from_options(options: Any) -> str | None:
    """Infer one concept from a complete option domain, ignoring field names.

    The retired ``runtime_collection_mode`` and current ``acquisition_mode``
    share a value ontology.  Collapse both onto the current concept before
    checking uniqueness so a model-authored field id cannot override a clear
    machine-value domain such as ``playwright/api_only``.  Values shared with
    ordinary business decisions, such as ``manual/file/api``, are insufficient
    on their own.  A hybrid value is unambiguous, while a browser value is only
    sufficient when paired with API.  This keeps domains such as
    ``browser/manual`` (for example, login methods) business-owned.
    """

    safe_options = options if isinstance(options, list) else []
    candidate_domains: dict[str, set[str]] = {}
    for definition in DECISION_CONCEPTS:
        if definition.minimum_domain_matches <= 0:
            continue
        machine_matches = definition.option_domain_matches(safe_options)
        label_matches = {
            canonical
            for option in safe_options
            if isinstance(option, dict)
            if (
                canonical := canonical_value_from_presented_label(
                    definition.name,
                    option.get("label"),
                )
            )
        }
        if len(machine_matches | label_matches) < definition.minimum_domain_matches:
            continue
        concept = (
            "acquisition_mode"
            if definition.name == "runtime_collection_mode"
            else definition.name
        )
        candidate_domains.setdefault(concept, set()).update(
            machine_matches | label_matches
        )
    acquisition_domain = candidate_domains.get("acquisition_mode", set())
    acquisition_is_distinct = (
        "hybrid" in acquisition_domain
        or "browser_with_manual_fallback" in acquisition_domain
        or {"api", "browser"}.issubset(acquisition_domain)
        or {"file", "browser"}.issubset(acquisition_domain)
    )
    if acquisition_domain and not acquisition_is_distinct:
        candidate_domains.pop("acquisition_mode", None)
    candidates = set(candidate_domains)
    return next(iter(candidates)) if len(candidates) == 1 else None


def canonical_decision_value(concept: Any, value: Any) -> str | None:
    definition = DECISION_CONCEPT_BY_NAME.get(_token(concept))
    if definition is None:
        if value is None:
            return "null"
        if isinstance(value, bool):
            return "true" if value else "false"
        if isinstance(value, (dict, list)):
            return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return _token(value) or None
    return definition.canonical_value(value)


def project_decision_value(field_id: Any, concept: Any, value: Any) -> dict[str, str]:
    """Return the current typed decisions represented by one persisted value.

    Exact legacy projections take precedence because they describe the full
    meaning of a former composite value. Otherwise the registered concept is
    canonicalized normally. The retired ``runtime_collection_mode`` concept is
    projected onto ``acquisition_mode`` at this single boundary.
    """

    declared = str(concept or "").strip().lower()
    if declared.startswith("business:") and declared.removeprefix("business:"):
        canonical = canonical_decision_value(declared, value)
        return {declared: canonical} if canonical else {}

    for projection in DECISION_VALUE_PROJECTIONS:
        if not projection.matches(field_id, value):
            continue
        result: dict[str, str] = {}
        for target_concept, target_value in projection.decisions:
            normalized_concept = normalize_decision_concept(target_concept)
            canonical = canonical_decision_value(normalized_concept, target_value)
            if normalized_concept and canonical:
                result[normalized_concept] = canonical
        return result

    normalized_concept = normalize_decision_concept(concept)
    if normalized_concept is None:
        normalized_concept = infer_decision_concept(field_id)
    if normalized_concept is None:
        return {}
    canonical = canonical_decision_value(normalized_concept, value)
    if not canonical:
        return {}
    return {normalized_concept: canonical} if canonical else {}


def decision_value_is_valid(field_id: Any, concept: Any, value: Any) -> bool:
    """Return whether a machine value belongs to its registered ontology.

    Opaque business concepts and open-domain concepts remain extensible.  A
    platform concept with a closed enum must either match a canonical value or
    an explicit legacy projection.
    """

    declared = str(concept or "").strip().lower()
    if declared.startswith("business:") and declared.removeprefix("business:"):
        return True
    if any(projection.matches(field_id, value) for projection in DECISION_VALUE_PROJECTIONS):
        return True
    normalized_concept = normalize_decision_concept(concept)
    if normalized_concept is None:
        normalized_concept = infer_decision_concept(field_id)
    if normalized_concept is None:
        return True
    definition = DECISION_CONCEPT_BY_NAME[normalized_concept]
    return not definition.closed_values or definition.canonical_value(value) is not None


def projected_decision_capabilities(field_id: Any, concept: Any, value: Any) -> dict[str, bool]:
    """Materialize capabilities from the same projections used by contracts."""

    result: dict[str, bool] = {}
    for projected_concept, projected_value in project_decision_value(field_id, concept, value).items():
        result.update(decision_capabilities(projected_concept, projected_value))
    return result


DECISION_CAPABILITY_SCOPES: dict[str, frozenset[str]] = {
    "acquisition_mode": frozenset({
        "api_runtime",
        "browser_runtime",
        "external_runtime",
        "collection_script",
    }),
    "external_system_access_mode": frozenset({
        "api_runtime",
        "browser_runtime",
        "external_runtime",
        "collection_script",
    }),
    "output_format": frozenset({"structured_output"}),
    "external_system_integration": frozenset({"api_runtime"}),
    "api_runtime": frozenset({"api_runtime"}),
    "browser_runtime": frozenset({"browser_runtime"}),
    "runtime_screenshot_output": frozenset({"runtime_screenshot_output"}),
    "skill_delivery_mode": frozenset({
        "api_runtime",
        "browser_runtime",
        "external_runtime",
        "collection_script",
    }),
}


def decision_capability_is_allowed(field_id: Any, concept: Any, capability: Any) -> bool:
    """Return whether one decision concept owns an explicit capability fact.

    Registered platform concepts are closed capability namespaces.  Unknown
    business decisions remain extensible so hosts can attach typed capability
    metadata without teaching the platform their business ontology.
    """

    if str(concept or "").strip().lower().startswith("business:"):
        return True
    normalized_concept = normalize_decision_concept(concept)
    if normalized_concept is None:
        normalized_concept = infer_decision_concept(field_id)
    if normalized_concept is None:
        return True
    return _token(capability) in DECISION_CAPABILITY_SCOPES.get(normalized_concept, frozenset())


def canonical_evidence_status(value: Any) -> str | None:
    """Return an evidence status only from stable machine values.

    User-visible labels and descriptions are presentation copy.  Callers that
    need a status for a custom option must declare ``evidenceStatus`` on that
    option instead of teaching each validation layer another phrase.
    """

    candidate = _token(value)
    for canonical, aliases in EVIDENCE_STATUS_VALUE_ALIASES:
        if candidate == canonical or candidate in aliases:
            return canonical
    return None


def decision_capabilities(concept: Any, value: Any) -> dict[str, bool]:
    """Materialize capability facts from the canonical decision registry."""

    normalized_concept = normalize_decision_concept(concept)
    semantic_value = canonical_decision_value(normalized_concept, value)
    if normalized_concept in {"acquisition_mode", "external_system_access_mode"}:
        return {
            "api": {
                "api_runtime": True,
                "browser_runtime": False,
                "external_runtime": False,
                "collection_script": True,
            },
            "browser": {
                "api_runtime": False,
                "browser_runtime": True,
                "external_runtime": False,
                "collection_script": True,
            },
            "browser_with_manual_fallback": {
                "api_runtime": False,
                "browser_runtime": True,
                "external_runtime": False,
                "collection_script": True,
            },
            "hybrid": {
                "api_runtime": True,
                "browser_runtime": True,
                "external_runtime": False,
                "collection_script": True,
            },
            "fixture": {"api_runtime": False, "browser_runtime": False, "external_runtime": False, "collection_script": False},
            "file": {"api_runtime": False, "browser_runtime": False, "external_runtime": False, "collection_script": False},
            "manual": {"api_runtime": False, "browser_runtime": False, "external_runtime": False, "collection_script": False},
        }.get(semantic_value or "", {})
    if normalized_concept == "output_format":
        return {
            "markdown": {"structured_output": False},
            "pdf": {"structured_output": False},
            "json": {"structured_output": True},
            "json_and_markdown": {"structured_output": True},
        }.get(semantic_value or "", {})
    if normalized_concept == "skill_delivery_mode":
        return {
            "knowledge": {
                "api_runtime": False,
                "browser_runtime": False,
                "external_runtime": False,
                "collection_script": False,
            },
            "executable": {
                "api_runtime": False,
                "browser_runtime": False,
                "external_runtime": False,
                "collection_script": True,
            },
            "browser": {
                "api_runtime": False,
                "browser_runtime": True,
                "external_runtime": False,
                "collection_script": True,
            },
            "hybrid": {
                "api_runtime": False,
                "browser_runtime": True,
                "external_runtime": False,
                "collection_script": True,
            },
        }.get(semantic_value or "", {})
    if normalized_concept == "external_system_integration":
        return {
            "none": {"api_runtime": False},
            "crm_only": {"api_runtime": True},
            "limited": {"api_runtime": True},
            "full": {"api_runtime": True},
        }.get(semantic_value or "", {})
    if normalized_concept in {"api_runtime", "browser_runtime", "runtime_screenshot_output"}:
        if semantic_value == "enabled":
            return {normalized_concept: True}
        if semantic_value == "disabled":
            return {normalized_concept: False}
    return {}


def resolve_capability_dependencies(
    capabilities: dict[str, bool],
) -> tuple[dict[str, bool], list[str]]:
    """Resolve the one cross-capability runtime invariant."""

    resolved = dict(capabilities)
    conflicts: list[str] = []
    if resolved.get("runtime_screenshot_output") is True:
        if resolved.get("browser_runtime") is False:
            conflicts.append(
                "运行时截图输出需要浏览器运行时，不能同时选择不导出浏览器运行时。"
            )
        elif "browser_runtime" not in resolved:
            resolved["browser_runtime"] = True
    return resolved, conflicts


__all__ = [
    "DECISION_CONCEPTS",
    "DECISION_VALUE_PROJECTIONS",
    "EVIDENCE_STATUS_VALUE_ALIASES",
    "DecisionConceptDefinition",
    "DecisionValueProjection",
    "DECISION_OPTION_LABELS",
    "PLATFORM_MANAGED_DECISION_CONCEPTS",
    "canonical_decision_value",
    "canonical_decision_option_value",
    "canonical_evidence_status",
    "canonical_value_from_presented_label",
    "decision_capability_is_allowed",
    "decision_capabilities",
    "decision_concept_is_platform_managed",
    "decision_option_catalog",
    "decision_option_catalog_for_fields",
    "decision_value_is_valid",
    "infer_decision_concept",
    "decision_context_is_authentication",
    "infer_decision_concept_from_options",
    "infer_decision_concept_from_title",
    "normalize_decision_concept",
    "project_decision_value",
    "projected_decision_capabilities",
    "resolve_capability_dependencies",
]
