"""HITL form transport normalization and user-facing presentation helpers."""

from __future__ import annotations

import json
import re
from typing import Any

from skill_builder.domain.contract_decisions import decision_semantic_identity


DECISION_FIELD_LABELS = {
    "skill_name": "Skill 发布名称",
    "display_name": "显示名称",
    "collection_method": "数据采集方式",
    "scoring_within_range": "评分区间内具体分值策略",
    "generate_runtime_playwright": "导出运行时 Playwright 能力",
    "runtime_browser_requested": "导出浏览器自动化能力",
    "real_projects_verification": "验证真实项目",
    "na_weight_handling": "缺失维度权重处理",
    "report_language": "报告语言",
    "city_scope": "城市范围",
    "output_format": "输出格式",
    "screenshot_requirement": "截图要求",
    "score_range_value_strategy": "评分区间具体取值",
    "number_parsing": "数字后缀解析精度",
}


DECISION_VALUE_LABELS = {
    "api_first": "优先使用 API/HTTP 方式采集",
    "auto_file": "自动生成结果文件",
    "playwright": "Playwright 浏览器自动化",
    "playwright_only": "仅使用浏览器自动化采集",
    "browser": "浏览器自动化",
    "browser_required": "需要浏览器自动化",
    "cli_arg": "运行时通过参数指定",
    "env": "从环境配置读取",
    "fixture_offline": "仅使用离线样例数据",
    "fixed_lower_bound": "固定取区间下界值",
    "fixed_midpoint": "固定取区间中值",
    "fixed_upper_bound": "固定取区间上界值",
    "http_html": "通过 HTTP 读取并解析页面",
    "http_requests": "HTTP 请求",
    "http": "HTTP 请求",
    "api": "API 接口",
    "hybrid": "混合采集",
    "linear_interpolation": "按区间线性计算具体分值",
    "live_scrape": "运行时实时采集网页数据",
    "no_browser": "不使用浏览器自动化",
    "prompt": "运行时交互输入",
    "runtime_current": "使用运行时当前值",
    "runtime_output": "作为 Skill 运行时输出",
    "stdout_only": "仅输出到标准输出",
    "validation_only": "仅作为平台验证证据",
    "default_only": "仅默认范围",
    "markdown": "Markdown 报告",
    "json": "JSON",
    "json_and_markdown": "JSON 与 Markdown",
    "zh-cn": "中文",
    "proportional_redistribution": "其余维度按比例重新分配",
    "required": "必需",
    "optional": "可选",
    "lower_bound": "每档取区间下界值（保守）",
    "midpoint": "每档取区间中值",
    "upper_bound": "每档取区间上界值",
}


MACHINE_DECISION_TOKEN_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_.-]{0,95}$")
PLACEHOLDER_DECISION_TOKEN_PATTERN = re.compile(
    r"^(?:definition|mapping|option|choice|variant|scheme|plan|strategy|answer|value)[_-]?(?:[a-z]|\d+)$",
    re.IGNORECASE,
)
PREFIXED_DECISION_OPTION_PATTERN = re.compile(
    r"^([A-Za-z][A-Za-z0-9_.-]{0,95})\s*[:：]\s*(\S[\s\S]*)$"
)


def _structured_hitl_default_object(value: str | None) -> dict[str, Any] | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return None
    if not isinstance(parsed, dict) or not parsed:
        return None
    return {str(key).strip(): item for key, item in parsed.items() if str(key).strip()}


def _humanize_decision_id(value: str) -> str:
    normalized = str(value or "").strip()
    if normalized in DECISION_FIELD_LABELS:
        return DECISION_FIELD_LABELS[normalized]
    return normalized.replace("_", " ").replace("-", " ").strip() or "待确认项"


def _decision_value_label(value: Any) -> str:
    if isinstance(value, bool):
        return "是" if value else "否"
    if value is None:
        return "未设置"
    normalized = str(value).strip()
    return DECISION_VALUE_LABELS.get(normalized.lower(), normalized)


def _decision_field_allows_identifier_label(*, field_id: Any = "", field_label: Any = "") -> bool:
    subject = f"{field_id or ''} {field_label or ''}"
    return bool(
        re.search(
            r"(?:skill[_ -]?(?:name|slug|kebab)|skill\s*(?:发布)?名称|发布标识|英文名称)",
            subject,
            re.IGNORECASE,
        )
    )


def _decision_option_transport_parts(value: Any, label: Any) -> tuple[Any, str, str]:
    """Split ``machine_token: readable text`` without leaking transport text.

    Models frequently serialize a stable enum and its explanation into both
    ``value`` and ``label``.  The machine token remains the persisted value;
    the readable suffix becomes presentation copy.  A known enum registry may
    provide a shorter primary label while the original suffix is retained as
    the option description.
    """

    raw_value = str(value).strip() if isinstance(value, str) else value
    raw_label = str(label).strip() if label is not None else ""
    candidate = raw_value if isinstance(raw_value, str) else raw_label
    match = PREFIXED_DECISION_OPTION_PATTERN.fullmatch(candidate) if isinstance(candidate, str) else None
    if match is None and raw_label:
        match = PREFIXED_DECISION_OPTION_PATTERN.fullmatch(raw_label)
    if match is None:
        return value, raw_label, ""
    token, readable = match.group(1), match.group(2).strip()
    registry_label = DECISION_VALUE_LABELS.get(token.lower())
    return token, registry_label or readable, readable


def _decision_option_display_label(
    value: Any,
    label: Any,
    *,
    field_id: Any = "",
    field_label: Any = "",
) -> str:
    """Return user-facing option copy while preserving the machine value."""

    _transport_value, transport_label, _transport_description = _decision_option_transport_parts(value, label)
    raw_label = str(transport_label or label or value or "").strip()
    raw_value = str(value or "").strip()
    if raw_label and not MACHINE_DECISION_TOKEN_PATTERN.fullmatch(raw_label):
        return raw_label
    token = raw_value if MACHINE_DECISION_TOKEN_PATTERN.fullmatch(raw_value) else raw_label
    if token:
        registered = DECISION_VALUE_LABELS.get(token.lower())
        if registered:
            return registered
        if _decision_field_allows_identifier_label(field_id=field_id, field_label=field_label):
            return f"发布标识：{token}"
    return raw_label


def _decision_option_is_machine_only(value: Any, label: Any) -> bool:
    raw_value = str(value or "").strip()
    raw_label = str(label or "").strip()
    return bool(
        raw_value
        and raw_label
        and raw_value == raw_label
        and MACHINE_DECISION_TOKEN_PATTERN.fullmatch(raw_label)
    )


def _decision_option_is_placeholder(value: Any) -> bool:
    return bool(PLACEHOLDER_DECISION_TOKEN_PATTERN.fullmatch(str(value or "").strip()))


HITL_STRUCTURED_FRAGMENT_PATTERN = re.compile(
    r"(?:\\?[\"']\s*,\s*)?\\?[\"']?(?:decisionId|decision_id|defaultValue|default_value|"
    r"options|type|value|label|description)\\?[\"']?\s*[:：]|\}\s*,\s*\{",
    re.IGNORECASE,
)


def _trim_hitl_visible_text(value: Any) -> str:
    """Trim transport punctuation without deleting unmatched business quotes."""

    text = re.sub(r"\s+", " ", str(value or "")).strip(" \t\r\n,，;；:：")
    if len(text) >= 2 and text[0] == text[-1] and text[0] in {'"', "'"}:
        text = text[1:-1].strip()
    return text


def _readable_hitl_text(value: Any, *, fallback: str = "", limit: int = 500) -> str:
    """Keep UI copy readable when a model splices tool JSON into a label.

    Structured data remains available in the form fields themselves. Visible
    labels/descriptions are bounded plain text and never expose the tail of a
    malformed JSON payload to the user.
    """

    text = str(value or "").replace("\x00", " ")
    match = HITL_STRUCTURED_FRAGMENT_PATTERN.search(text)
    if match:
        text = text[: match.start()]
    text = re.sub(r"[\x01-\x08\x0b\x0c\x0e-\x1f\x7f]", " ", text)
    text = _trim_hitl_visible_text(text)
    if not text:
        text = str(fallback or "").strip()
    return text[:limit].rstrip()


def _decision_field_semantic_key(field: dict[str, Any]) -> str:
    """Return the canonical registry identity for one user decision."""

    field_id = str(field.get("id") or field.get("decisionId") or field.get("key") or "").strip().lower()
    label = str(field.get("label") or field.get("title") or "").strip().lower()
    description = str(field.get("description") or field.get("message") or "").strip().lower()

    concept = decision_semantic_identity(
        field_id,
        label,
        description,
        field.get("options"),
        explicit=field.get("semanticConcept"),
    )
    # Open business decisions are not a platform ontology.  Two different
    # decisionIds may discuss the same broad concept while asking for different
    # values (for example score precision and boundary inclusion).  Merging on
    # ``business:*`` can silently drop one field from HITL. Preserve identity
    # by decisionId.
    if concept.startswith("business:"):
        stable_id = field_id or concept.removeprefix("business:")
        return f"business-field:{stable_id}"
    return f"concept:{concept}"


def _decision_field_merge_signature(field: dict[str, Any]) -> str:
    """Return the exact interaction domain required for safe concept merging."""

    options = []
    for option in field.get("options") or []:
        if not isinstance(option, dict):
            continue
        options.append({
            key: option.get(key)
            for key in (
                "value",
                "semanticValue",
                "evidenceStatus",
                "capabilityDecisions",
                "capabilityCondition",
                "capabilityConditions",
                "semanticEffects",
                "implementationDependencies",
            )
            if option.get(key) not in (None, "", [], {})
        })
    payload = {
        "id": str(field.get("id") or field.get("decisionId") or field.get("key") or "").strip(),
        "type": str(field.get("type") or "").strip().lower(),
        "options": options,
        "capability": field.get("capability"),
        "capabilityCondition": field.get("capabilityCondition"),
        "capabilityConditions": field.get("capabilityConditions"),
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)


def _decision_field_preference(field: dict[str, Any]) -> tuple[int, int, int, int]:
    field_type = str(field.get("type") or "").strip().lower()
    options = [item for item in field.get("options") or [] if isinstance(item, dict)]
    return (
        2 if field_type == "select" else 1 if field_type == "boolean" else 0,
        len(options),
        1 if str(field.get("description") or "").strip() else 0,
        1 if str(field.get("capability") or "").strip() else 0,
    )


def _merge_duplicate_decision_fields(fields: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    order: list[str] = []
    for field in fields:
        key = _decision_field_semantic_key(field)
        existing = merged.get(key)
        if existing is None:
            merged[key] = field
            order.append(key)
            continue
        if _decision_field_merge_signature(existing) != _decision_field_merge_signature(field):
            # Registered concepts may be deduplicated only when the decision id,
            # option value domain, and conditional semantics are identical.  A
            # mismatch is surfaced to the form validator instead of selecting a
            # preferred field and losing user choice.
            conflict_key = f"{key}:conflict:{len(order)}"
            existing = dict(existing)
            field = dict(field)
            message = "同一平台语义对应了不同 decisionId、选项域或条件依赖，不能静默合并"
            existing["_semanticConflict"] = message
            field["_semanticConflict"] = message
            merged[key] = existing
            merged[conflict_key] = field
            order.append(conflict_key)
            continue
        preferred, secondary = (
            (field, existing)
            if _decision_field_preference(field) > _decision_field_preference(existing)
            else (existing, field)
        )
        next_field = dict(preferred)
        if not str(next_field.get("description") or "").strip() and str(secondary.get("description") or "").strip():
            next_field["description"] = secondary["description"]
        if not str(next_field.get("label") or "").strip() and str(secondary.get("label") or "").strip():
            next_field["label"] = secondary["label"]
        preferred_default = next_field.get("defaultValue")
        secondary_default = secondary.get("defaultValue")
        if (
            (preferred_default is None or isinstance(preferred_default, str) and not preferred_default.strip())
            and secondary_default is not None
            and not (isinstance(secondary_default, str) and not secondary_default.strip())
        ):
            next_field["defaultValue"] = secondary["defaultValue"]
            next_field["defaultLabel"] = secondary.get("defaultLabel") or _decision_value_label(
                secondary["defaultValue"]
            )
        merged[key] = next_field
    return [merged[key] for key in order]


__all__ = [
    "HITL_STRUCTURED_FRAGMENT_PATTERN",
    "MACHINE_DECISION_TOKEN_PATTERN",
    "PLACEHOLDER_DECISION_TOKEN_PATTERN",
    "_decision_field_semantic_key",
    "_decision_field_allows_identifier_label",
    "_decision_option_display_label",
    "_decision_option_is_machine_only",
    "_decision_option_is_placeholder",
    "_decision_option_transport_parts",
    "_decision_value_label",
    "_humanize_decision_id",
    "_merge_duplicate_decision_fields",
    "_readable_hitl_text",
    "_structured_hitl_default_object",
]
