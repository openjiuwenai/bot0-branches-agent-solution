"""Deterministic, privacy-safe structured schema fixture generation.

These fixtures validate input shape and invalid-input behavior. They never
prove a business happy path, read uploaded data, or overwrite authored files.
"""

from __future__ import annotations

import csv
import io
import json
import hashlib
import re
import zipfile
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

from skill_builder.application.input_contracts import scenario_structured_input_contracts


_STRUCTURED_FIXTURE_SUFFIXES = frozenset({".csv", ".json", ".jsonl", ".xlsx"})
_SYNTHETIC_FIXTURE_MARKER = "synthetic-fixtures.json"


def _xml_text(value: str) -> str:
    return escape(
        "".join(
            character
            for character in value
            if character in "\t\n\r" or ord(character) >= 0x20
        )
    )


def _allowed_values(field: dict[str, Any]) -> tuple[str, ...]:
    raw = (
        field.get("allowedValues")
        or field.get("values")
        or field.get("enum")
        or field.get("options")
    )
    if not isinstance(raw, list):
        return ()
    values = []
    for item in raw:
        value = item.get("value") if isinstance(item, dict) else item
        if str(value or "").strip():
            values.append(str(value).strip())
    return tuple(dict.fromkeys(values))


def _descriptive_constraint_text(field: dict[str, Any]) -> str:
    return " ".join(
        str(field.get(key) or "").strip()
        for key in ("format", "description", "validation", "constraint", "constraints")
        if str(field.get(key) or "").strip()
    )


def _regex_sample(pattern: str) -> str | None:
    """Synthesize a value for bounded literal/class/repeat regexes."""

    source = str(pattern or "").strip()
    if not source:
        return None
    body = source.removeprefix("^").removesuffix("$")
    tokens: list[str] = []
    index = 0
    while index < len(body):
        character = body[index]
        if character in "()|":
            return None
        if character == "[":
            close = body.find("]", index + 1)
            if close < 0:
                return None
            choice = body[index + 1:close]
            if not choice or choice.startswith("^"):
                return None
            if "\\d" in choice or "0-9" in choice:
                token = "0"
            elif re.search(r"A-Z", choice):
                token = "A"
            elif re.search(r"a-z", choice):
                token = "a"
            else:
                token = choice[0]
            index = close + 1
        elif character == "\\":
            if index + 1 >= len(body):
                return None
            escaped = body[index + 1]
            token = {"d": "0", "w": "A", "s": " "}.get(escaped, escaped)
            index += 2
        elif character in ".*+?{}":
            return None
        else:
            token = character
            index += 1
        repeat = 1
        if index < len(body) and body[index] == "{":
            close = body.find("}", index + 1)
            if close < 0:
                return None
            count = body[index + 1:close].split(",", 1)[0].strip()
            if not count.isdigit():
                return None
            repeat = int(count)
            index = close + 1
        elif index < len(body) and body[index] == "+":
            index += 1
        elif index < len(body) and body[index] in "*?":
            index += 1
        tokens.append(token * repeat)
    candidate = "".join(tokens)
    try:
        return candidate if re.fullmatch(source, candidate) else None
    except re.error:
        return None


def _described_value(constraints: str) -> str | None:
    prefix_digits = re.search(
        r"(?:格式|pattern)?\s*([A-Za-z][A-Za-z0-9_-]*)\s*\+\s*(\d+)\s*位(?:数字|数)",
        constraints,
        re.IGNORECASE,
    )
    if prefix_digits:
        width = max(1, min(int(prefix_digits.group(2)), 32))
        return prefix_digits.group(1) + str(1).zfill(width)
    if re.search(r"ISO\s*4217", constraints, re.IGNORECASE):
        return "USD"
    url_template = re.search(r"https?://[^\s，。；;]+", constraints, re.IGNORECASE)
    if url_template:
        value = url_template.group(0).rstrip(".,:;)]）")
        return re.sub(r"\{[^{}]+\}", "example", value)
    ascii_subset = re.search(
        r"([A-Za-z0-9_-]+(?:\s*[/,，|]\s*[A-Za-z0-9_-]+)+)"
        r"\s*(?:的)?子集",
        constraints,
        re.IGNORECASE,
    )
    if ascii_subset:
        candidate = re.split(r"[/,，|]", ascii_subset.group(1), maxsplit=1)[0]
        if candidate.strip():
            return candidate.strip()
    bounded_enum = re.search(
        r"([A-Za-z0-9_\-\u4e00-\u9fff]+(?:\s*[/、,，|]\s*"
        r"[A-Za-z0-9_\-\u4e00-\u9fff]+)+)\s*(?:之一|(?:的)?子集|中选择)",
        constraints,
        re.IGNORECASE,
    )
    if bounded_enum:
        candidate = re.split(r"[/、,，|]", bounded_enum.group(1), maxsplit=1)[0]
        if candidate.strip():
            return candidate.strip()
    enum_sequence = re.search(
        r"(?:[:：]|例如|如|可选|之一|包括|包含|（|\()\s*"
        r"([A-Za-z0-9_\-\u4e00-\u9fff]+(?:\s*[/、,，|]\s*"
        r"[A-Za-z0-9_\-\u4e00-\u9fff]+)+)",
        constraints,
        re.IGNORECASE,
    )
    if enum_sequence:
        candidate = re.split(r"[/、,，|]", enum_sequence.group(1), maxsplit=1)[0]
        if candidate.strip():
            return candidate.strip()
    alternative = re.search(
        r"([\w\u4e00-\u9fff-]+)(?:（[^）]*）|\([^)]*\))?\s*(?:或|\bor\b)",
        constraints,
        re.IGNORECASE,
    )
    if alternative:
        return alternative.group(1)
    return None


def _field_value_satisfies(field: dict[str, Any], value: str) -> bool:
    allowed = _allowed_values(field)
    if allowed and value not in allowed:
        return False
    pattern = str(field.get("pattern") or field.get("regex") or "").strip()
    if pattern:
        try:
            if re.fullmatch(pattern, value) is None:
                return False
        except re.error:
            return False
    constraints = _descriptive_constraint_text(field)
    described = _described_value(constraints)
    explicit_values = {
        str(field.get(key)).strip()
        for key in ("example", "sample", "default", "defaultValue")
        if field.get(key) not in (None, "")
    }
    if described is not None and value != described and value not in explicit_values:
        return False
    minimum_length, maximum_length = _length_bounds(field)
    if minimum_length is not None and len(value) < minimum_length:
        return False
    if maximum_length is not None and len(value) > maximum_length:
        return False
    return True


def _length_bounds(field: dict[str, Any]) -> tuple[int | None, int | None]:
    def explicit(name: str) -> int | None:
        value = field.get(name)
        if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
            return value
        if isinstance(value, str) and value.strip().isdigit():
            return int(value.strip())
        return None

    constraints = _descriptive_constraint_text(field)
    minimum = explicit("minLength")
    maximum = explicit("maxLength")
    if minimum is None:
        match = re.search(
            r"(?:至少|不少于|minimum(?:\s+length)?|at\s+least)\s*"
            r"(\d+)\s*(?:个)?(?:字符|字|characters?|chars?)",
            constraints,
            re.IGNORECASE,
        )
        minimum = int(match.group(1)) if match else None
    if maximum is None:
        match = re.search(
            r"(?:最多|至多|不超过|maximum(?:\s+length)?|at\s+most)\s*"
            r"(\d+)\s*(?:个)?(?:字符|字|characters?|chars?)",
            constraints,
            re.IGNORECASE,
        )
        maximum = int(match.group(1)) if match else None
    return minimum, maximum


def _field_value(field: dict[str, Any], *, platform_values: tuple[str, ...] = ()) -> str:
    name = str(field.get("name") or "").strip().lower()
    declared = str(field.get("type") or "").strip().lower()
    description = str(field.get("description") or "").strip()
    allowed = _allowed_values(field)
    if allowed:
        return allowed[0]
    for key in ("example", "sample", "default", "defaultValue"):
        if str(field.get(key) or "").strip():
            return str(field[key]).strip()
    pattern = str(field.get("pattern") or field.get("regex") or "").strip()
    if pattern:
        sample = _regex_sample(pattern)
        if sample is not None:
            return sample
    constraints = _descriptive_constraint_text(field)
    described = _described_value(constraints)
    if described:
        return described
    minimum_length, _maximum_length = _length_bounds(field)
    if minimum_length is not None and minimum_length > len("示例值"):
        return "x" * minimum_length
    if "序号" in name or name in {"id", "index", "no", "number", "seq"}:
        return "1"
    if any(token in declared for token in ("布尔", "bool", "boolean")):
        return "true"
    if any(token in declared for token in ("整数", "integer", "int")):
        return "1"
    if any(token in declared for token in ("数值", "数字", "金额", "number", "decimal", "float")):
        return "1.00"
    if any(token in name for token in ("价格", "金额", "数量", "price", "amount", "quantity")):
        return "1.00"
    if any(token in name for token in ("平台", "渠道", "platform", "channel")):
        if platform_values:
            return platform_values[0]
        # Scenario descriptions commonly carry a compact business enum such
        # as "至少指定一个平台：京东、天猫、拼多多". Use the first allowed
        # value rather than inventing an unsupported placeholder.
        enum_match = re.search(r"(?:[:：]|包括|可选)\s*([^。；;]+)", description)
        if enum_match:
            candidate = re.split(r"[、,，/|]", enum_match.group(1), maxsplit=1)[0].strip()
            candidate = re.sub(r"^(?:至少(?:指定|选择)?一个|任选一个)", "", candidate).strip()
            if candidate:
                return candidate
        return "示例平台"
    if any(token in name for token in ("日期", "date", "时间", "time")):
        return "2026-01-01"
    if any(token in name for token in ("邮箱", "email")):
        return "example@example.com"
    if any(token in name for token in ("网址", "url", "地址")):
        return "https://example.invalid"
    return "示例值"


def _field_constraint_from_parent_description(
    field_name: str,
    description: str,
) -> str:
    """Return one named field's bounded parenthetical constraint."""

    name = str(field_name or "").strip()
    text = str(description or "").strip()
    if not name or not text:
        return ""
    match = re.search(
        re.escape(name) + r"\s*[（(]([^）)]{1,500})[）)]",
        text,
        re.IGNORECASE,
    )
    return match.group(1).strip() if match else ""


def _platform_values(scenario: dict[str, Any]) -> tuple[str, ...]:
    """Project named platform/channel dependencies as fixture enum hints."""

    ignored = {"通用风险", "common", "general", "notes", "说明", "风险"}
    values: list[str] = []
    for dependency in scenario.get("dependencies") or []:
        if not isinstance(dependency, dict):
            continue
        candidates = [
            str(name or "").strip()
            for name, detail in dependency.items()
            if isinstance(detail, dict)
            and str(name or "").strip()
            and str(name or "").strip().lower() not in ignored
        ]
        if len(candidates) >= 2:
            values.extend(candidates)
    return tuple(dict.fromkeys(values))


def _record(
    contract: dict[str, Any],
    *,
    platform_values: tuple[str, ...] = (),
) -> tuple[list[str], dict[str, str]]:
    fields = [item for item in contract.get("fields") or [] if isinstance(item, dict)]
    parent_description = str(contract.get("description") or "").strip()
    by_name: dict[str, dict[str, Any]] = {}
    for item in fields:
        name = str(item.get("name") or "").strip()
        if not name:
            continue
        normalized_item = dict(item)
        if not str(item.get("description") or "").strip():
            constraint = _field_constraint_from_parent_description(name, parent_description)
            if constraint:
                normalized_item["description"] = constraint
        by_name[name] = normalized_item
    names = list(by_name)
    values = {
        name: _field_value(by_name[name], platform_values=platform_values)
        for name in names
    }
    invalid = [
        name
        for name, value in values.items()
        if not _field_value_satisfies(by_name[name], value)
    ]
    if invalid:
        raise ValueError(
            "cannot synthesize contract-compliant fixture values for fields: "
            + ", ".join(invalid)
        )
    return names, values


def _invalid_field_priority(field: dict[str, Any]) -> int:
    if _allowed_values(field) or field.get("pattern") or field.get("regex"):
        return 3
    minimum_length, maximum_length = _length_bounds(field)
    if minimum_length is not None or maximum_length is not None:
        return 3
    if any(
        str(field.get(key) or "").strip()
        for key in ("validation", "constraint", "constraints")
    ):
        return 2
    declared = str(field.get("type") or "").lower()
    return 1 if any(
        token in declared
        for token in (
            "整数", "integer", "int", "数值", "数字", "number", "decimal",
            "float", "布尔", "boolean", "bool",
        )
    ) else 0


def _invalid_field_value(field: dict[str, Any]) -> str:
    minimum_length, maximum_length = _length_bounds(field)
    if minimum_length is not None and minimum_length > 0:
        return "x" * (minimum_length - 1)
    if maximum_length is not None:
        return "x" * min(maximum_length + 1, 1024)
    return "__invalid__"


def build_tabular_xlsx_bytes(
    headers: list[str],
    records: list[dict[str, str]],
) -> bytes:
    """Build a deterministic dependency-free XLSX workbook."""

    def cell(column: int, row: int, value: str) -> str:
        # Inline strings keep this writer dependency-free and are supported by
        # the platform's deterministic XLSX reader.
        ref = ""
        number = column
        while number:
            number, remainder = divmod(number - 1, 26)
            ref = chr(65 + remainder) + ref
        return f'<c r="{ref}{row}" t="inlineStr"><is><t>{_xml_text(value)}</t></is></c>'

    header_cells = "".join(cell(index, 1, value) for index, value in enumerate(headers, 1))
    value_rows = "".join(
        f'<row r="{row_index}">'
        + "".join(
            cell(column_index, row_index, record.get(header, ""))
            for column_index, header in enumerate(headers, 1)
        )
        + "</row>"
        for row_index, record in enumerate(records, 2)
    )
    sheet = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f'<sheetData><row r="1">{header_cells}</row>'
        f'{value_rows}</sheetData></worksheet>'
    )
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>'
    )
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        "</Types>"
    )
    rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="xl/workbook.xml"/>'
        "</Relationships>"
    )
    workbook_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
        'Target="worksheets/sheet1.xml"/>'
        "</Relationships>"
    )
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in (
            ("[Content_Types].xml", content_types),
            ("_rels/.rels", rels),
            ("xl/workbook.xml", workbook),
            ("xl/_rels/workbook.xml.rels", workbook_rels),
            ("xl/worksheets/sheet1.xml", sheet),
        ):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o600 << 16
            archive.writestr(entry, content.encode("utf-8"))
    return output.getvalue()


def _write_xlsx(path: Path, headers: list[str], record: dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(build_tabular_xlsx_bytes(headers, [record]))


def _write_fixture(path: Path, format_text: str, headers: list[str], values: dict[str, str]) -> None:
    if "excel" in format_text or "xlsx" in format_text:
        _write_xlsx(path, headers, values)
    elif "jsonl" in format_text:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(values, ensure_ascii=False) + "\n", encoding="utf-8")
    elif "json" in format_text:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps([values], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        output = io.StringIO(newline="")
        writer = csv.DictWriter(output, fieldnames=headers)
        writer.writeheader()
        writer.writerow(values)
        path.write_text(output.getvalue(), encoding="utf-8")


def _fixture_format(contract: dict[str, Any]) -> tuple[str, str]:
    format_text = " ".join(
        str(contract.get(key) or "")
        for key in ("format", "file", "filename", "description")
    ).lower()
    suffix = ".xlsx" if "excel" in format_text or "xlsx" in format_text else (
        ".jsonl" if "jsonl" in format_text else ".json" if "json" in format_text else ".csv"
    )
    return format_text, suffix


def ensure_synthetic_input_fixtures(root: Path, generated: Path) -> dict[str, Any]:
    """Create deterministic schema/invalid fixtures without source data."""
    try:
        scenario = json.loads((root / "validation" / "scenario_contract.json").read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return {"created": False, "reason": "scenario_contract_unavailable"}
    if not isinstance(scenario, dict):
        return {"created": False, "reason": "scenario_contract_unavailable"}
    contracts = scenario_structured_input_contracts(scenario)
    if not contracts:
        return {"created": False, "reason": "no_structured_input"}
    fixtures = generated / "fixtures"
    if fixtures.is_symlink():
        return {"created": False, "reason": "fixture_directory_is_symlink"}
    existing = [
        path
        for path in fixtures.rglob("*")
        if path.is_file() and path.suffix.lower() in _STRUCTURED_FIXTURE_SUFFIXES
    ] if fixtures.is_dir() else []
    marker = root / ".skill-builder" / _SYNTHETIC_FIXTURE_MARKER
    try:
        previous = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        previous = {}
    previous_paths = previous.get("paths") if isinstance(previous, dict) else None
    previous_paths = previous_paths if isinstance(previous_paths, dict) else {}
    if existing and not previous_paths:
        return {
            "created": False,
            "reason": "fixture_exists",
            "paths": [path.relative_to(generated).as_posix() for path in existing],
        }

    multiple = len(contracts) > 1
    created: list[str] = []
    contract_fixtures: list[dict[str, Any]] = []
    for index, input_contract in enumerate(contracts, start=1):
        headers, values = _record(
            input_contract,
            platform_values=_platform_values(scenario),
        )
        if not headers:
            return {
                "created": False,
                "reason": "input_fields_empty",
                "contractIndex": index,
            }
        format_text, suffix = _fixture_format(input_contract)
        discriminator = f"-{index}" if multiple else ""
        sample = fixtures / f"sample-input{discriminator}{suffix}"
        invalid = fixtures / f"invalid{discriminator}{suffix}"
        if not sample.exists():
            _write_fixture(sample, format_text, headers, values)
            created.append(sample.relative_to(generated).as_posix())

        # Invalid-input replay is also platform-owned. Keep the complete schema
        # and invalidate one constrained required field per named input.
        required_fields = [
            field
            for field in input_contract.get("fields") or []
            if isinstance(field, dict)
            and field.get("required") is True
            and str(field.get("name") or "").strip()
        ]
        invalid_headers = list(headers)
        invalid_values = dict(values)
        if required_fields:
            target_field = max(required_fields, key=_invalid_field_priority)
            invalid_values[str(target_field["name"])] = _invalid_field_value(
                target_field
            )
        else:
            invalid_headers = ["__invalid_input__"]
            invalid_values = {"__invalid_input__": "invalid"}
        if not invalid.exists():
            _write_fixture(invalid, format_text, invalid_headers, invalid_values)
            created.append(invalid.relative_to(generated).as_posix())
        contract_fixtures.append(
            {
                "index": index,
                "name": str(input_contract.get("name") or f"input-{index}"),
                "format": str(input_contract.get("format") or ""),
                "samplePath": sample.relative_to(generated).as_posix(),
                "invalidPath": invalid.relative_to(generated).as_posix(),
            }
        )
    if created:
        marker.parent.mkdir(parents=True, exist_ok=True)
        owned = {
            path: hashlib.sha256((generated / path).read_bytes()).hexdigest()
            for path in created
        }
        for path, digest in previous_paths.items():
            target = generated / str(path)
            if (
                target.is_file()
                and isinstance(digest, str)
                and hashlib.sha256(target.read_bytes()).hexdigest() == digest
            ):
                owned.setdefault(str(path), digest)
        marker.write_text(json.dumps({"paths": owned}, sort_keys=True) + "\n", encoding="utf-8")
        return {
            "created": True,
            "paths": created,
            "contracts": contract_fixtures,
            "synthetic": True,
        }
    if existing:
        return {
            "created": False,
            "reason": "fixture_exists",
            "paths": [path.relative_to(generated).as_posix() for path in existing],
            "contracts": contract_fixtures,
        }
    return {"created": False, "reason": "fixture_exists"}


def platform_owned_fixture_paths(root: Path, generated: Path) -> set[str]:
    """Return synthetic fixture paths still owned by Core, by digest."""
    marker = root / ".skill-builder" / _SYNTHETIC_FIXTURE_MARKER
    try:
        value = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        return set()
    paths = value.get("paths") if isinstance(value, dict) else None
    if not isinstance(paths, dict):
        return set()
    owned: set[str] = set()
    for raw_path, digest in paths.items():
        path = str(raw_path or "").replace("\\", "/")
        target = generated / path
        if (
            path
            and target.is_file()
            and isinstance(digest, str)
            and hashlib.sha256(target.read_bytes()).hexdigest() == digest
        ):
            owned.add(path)
    return owned


def platform_fixture_business_replay_issues(
    root: Path,
    generated: Path,
    cases: tuple[dict[str, Any], ...],
) -> tuple[dict[str, Any], ...]:
    """Reject schema-only fixtures as proof of a successful business path."""

    owned = platform_owned_fixture_paths(root, generated)
    if not owned:
        return ()
    issues: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for case in cases:
        kind = str(case.get("kind") or "").strip()
        if kind not in {"happy_path", "business_rule", "file_handoff"}:
            continue
        case_id = str(case.get("id") or "case")
        for command_spec in case.get("commands") or []:
            for raw_token in command_spec.get("command") or []:
                token = str(raw_token or "").strip().replace("\\", "/")
                if token.startswith("-") and "=" in token:
                    token = token.split("=", 1)[1]
                token = token.removeprefix("generated-skill/").lstrip("./")
                if token not in owned or (case_id, token) in seen:
                    continue
                seen.add((case_id, token))
                issues.append(
                    {
                        "id": "self_check_platform_fixture_not_business_evidence",
                        "message": (
                            f"用例 {case_id} 使用平台 schema fixture {token} 作为业务成功证据；"
                            "请根据材料另建可验证真实业务结果的 fixture。"
                        ),
                        "caseId": case_id,
                        "path": token,
                    }
                )
    return tuple(issues)


def ensure_synthetic_input_fixture(root: Path, generated: Path) -> dict[str, Any]:
    """Backward-compatible singular projection of synthetic fixture creation."""
    result = ensure_synthetic_input_fixtures(root, generated)
    paths = list(result.get("paths") or [])
    if result.get("created") and paths:
        return {**result, "path": paths[0]}
    return result


__all__ = [
    "build_tabular_xlsx_bytes",
    "ensure_synthetic_input_fixture",
    "ensure_synthetic_input_fixtures",
    "platform_fixture_business_replay_issues",
    "platform_owned_fixture_paths",
]
