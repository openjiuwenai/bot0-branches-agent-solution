"""Extract a comparable scalar from Agent answers for deterministic metrics.

Only regex-search for patterns like ``"field": "value"`` in the raw answer
text. No ``<answer>`` stripping, code-fence handling, or JSON parsing.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class AnswerFieldExtractConfig:
    """Config for pulling one field out of a predict payload before exact match.

    YAML example::

        extract:
          strategy: answer_tag_json_field
          source: answer
          field: responsibility
          # or: fields: [responsibility, responsibility_type]
          prefer_values: ["无责", "有责"]
    """

    strategy: str = "answer_tag_json_field"
    source: str = "answer"
    fields: tuple[str, ...] = ("responsibility",)
    prefer_values: tuple[str, ...] = ()


def parse_extract_config(raw: dict[str, Any] | None) -> AnswerFieldExtractConfig | None:
    """Parse evaluator.extract from dataset.yaml / factory config."""
    if not raw:
        return None
    if not isinstance(raw, dict):
        raise TypeError("'extract' must be a dict")

    strategy = str(raw.get("strategy", "answer_tag_json_field")).strip()
    if strategy != "answer_tag_json_field":
        raise ValueError(
            f"Unsupported extract.strategy: {strategy!r}. "
            "Supported: 'answer_tag_json_field'."
        )

    source = str(raw.get("source", "answer")).strip() or "answer"

    fields_raw = raw.get("fields")
    field_raw = raw.get("field")
    fields: list[str] = []
    if isinstance(fields_raw, list):
        fields.extend(str(x).strip() for x in fields_raw if str(x).strip())
    if field_raw is not None and str(field_raw).strip():
        name = str(field_raw).strip()
        if name not in fields:
            fields.insert(0, name)
    if not fields:
        raise ValueError("extract requires 'field' or non-empty 'fields'")

    prefer_raw = raw.get("prefer_values") or []
    if not isinstance(prefer_raw, list):
        raise TypeError("'extract.prefer_values' must be a list")
    prefer = tuple(str(x).strip() for x in prefer_raw if str(x).strip())

    return AnswerFieldExtractConfig(
        strategy=strategy,
        source=source,
        fields=tuple(fields),
        prefer_values=prefer,
    )


def extract_prediction_field(
    prediction: Any,
    config: AnswerFieldExtractConfig,
) -> str:
    """Return the extracted field value, or empty string on failure."""
    text = _resolve_source_text(prediction, config.source)
    if not text:
        return ""

    if config.strategy == "answer_tag_json_field":
        values = _regex_extract_quoted_field_values(text, config.fields)
        return _pick_preferred(values, config.prefer_values)
    return ""


def extract_config_from_evaluator(evaluator: Any) -> AnswerFieldExtractConfig | None:
    """Pull ``AnswerFieldExtractConfig`` from a metric evaluator, if configured."""
    metrics = getattr(evaluator, "_metrics", None)
    if metrics is None:
        metrics = getattr(evaluator, "metrics", None)
    if metrics is None:
        return None
    if not isinstance(metrics, (list, tuple)):
        metrics = [metrics]
    for metric in metrics:
        cfg = getattr(metric, "_extract", None)
        if isinstance(cfg, AnswerFieldExtractConfig):
            return cfg
    return None


def is_extracted_field_missing(
    prediction: Any,
    config: AnswerFieldExtractConfig | None,
) -> bool:
    """True when extract is configured and the target field value is empty."""
    if config is None:
        return False
    return not bool(extract_prediction_field(prediction, config).strip())


def _resolve_source_text(prediction: Any, source: str) -> str:
    """Take the answer string as-is; do not strip tags or parse structure."""
    if isinstance(prediction, dict):
        if source in prediction and prediction[source] is not None:
            return str(prediction[source])
        for key in ("answer", "content", "text"):
            if key in prediction and prediction[key] is not None:
                return str(prediction[key])
        return ""
    if prediction is None:
        return ""
    return str(prediction)


def _regex_extract_quoted_field_values(text: str, fields: tuple[str, ...]) -> list[str]:
    """Search for ``"field": "value"`` (double-quoted key and value) only."""
    values: list[str] = []
    for field in fields:
        pattern = rf'"{re.escape(field)}"\s*:\s*"([^"]*)"'
        for match in re.finditer(pattern, text):
            value = match.group(1).strip()
            if value:
                values.append(value)
    return values


def _pick_preferred(values: list[str], prefer_values: tuple[str, ...]) -> str:
    if not values:
        return ""
    for preferred in prefer_values:
        if preferred in values:
            return preferred
    return values[0]
