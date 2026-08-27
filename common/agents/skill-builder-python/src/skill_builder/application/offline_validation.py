# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Deterministic, package-owned offline validation protocol.

Generated Skills describe a small set of offline cases.  Core, rather than the
generated self-check, replays the commands and evaluates their assertions.  A
package therefore cannot become ready merely by printing success or returning
zero from ``self_check.py``.
"""

from __future__ import annotations

import csv
import codecs
import json
import re
import shutil
import zipfile
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from email.message import Message
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from typing import Any
from xml.etree import ElementTree

from skill_builder.ports import ExecutionRequest, SkillBuilderExecutionPort
from skill_builder.application.input_contracts import scenario_structured_input_contracts


SELF_CHECK_SCHEMA_VERSION = "skill-builder-self-check/v1"
SELF_CHECK_PLANNED_STATUS = "planned"
SELF_CHECK_CASE_KINDS = frozenset(
    {
        "happy_path",
        "invalid_input",
        "business_rule",
        "file_handoff",
        "external_offline",
    }
)
SELF_CHECK_ASSERTION_OPERATORS = frozenset(
    {
        "contains",
        "equals",
        "exists",
        "in",
        "keys_equal",
        "length_equals",
        "max_items",
        "maximum",
        "min_items",
        "minimum",
        "not_empty",
        "not_equals",
        "sum_equals",
        "type",
    }
)
MAX_SELF_CHECK_CASES = 24
MAX_COMMANDS_PER_CASE = 6
MAX_ASSERTIONS_PER_CASE = 32
_OUTPUT_PLACEHOLDER = "${outputDir}"
_PACKAGE_PATH_ROOTS = frozenset(
    {"scripts", "fixtures", "references", "assets", "requirements.txt", "pyproject.toml"}
)
_XML_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
_STRUCTURED_ASSERTION_SUFFIXES = frozenset({".json", ".jsonl"})
_TEXT_CONTENT_PATH_ALIASES = frozenset({"", "$", ".", "content"})
_TRACEBACK_FILE_RE = re.compile(r"\bFile\s+['\"](?P<path>[^'\"\r\n]+)['\"]")


@dataclass(frozen=True, slots=True)
class SelfCheckProtocolValidation:
    ok: bool
    issues: tuple[dict[str, Any], ...]
    cases: tuple[dict[str, Any], ...]
    summary: dict[str, Any]


@dataclass(frozen=True, slots=True)
class SelfCheckReplayResult:
    ok: bool
    issues: tuple[dict[str, Any], ...]
    checks: tuple[dict[str, Any], ...]


class _HTMLCharsetDeclarationParser(HTMLParser):
    """Collect charset declarations from structured HTML ``meta`` elements."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.charsets: list[str] = []

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        if tag.casefold() != "meta":
            return
        values = {
            str(name or "").casefold(): str(value or "").strip()
            for name, value in attrs
        }
        direct = values.get("charset", "")
        if direct:
            self.charsets.append(direct)
        if values.get("http-equiv", "").casefold() != "content-type":
            return
        content = values.get("content", "")
        if not content:
            return
        message = Message()
        message["content-type"] = content
        declared = message.get_content_charset()
        if declared:
            self.charsets.append(declared)

    handle_startendtag = handle_starttag


def is_platform_replay_plan_source(source: str) -> bool:
    """Return whether a diagnostic script emits a platform-owned replay plan."""

    return (
        SELF_CHECK_SCHEMA_VERSION in source
        and bool(re.search(r"['\"]status['\"]\s*:\s*['\"]planned['\"]", source))
    )


def compile_self_check_plan_script(cases: tuple[dict[str, Any], ...]) -> str:
    """Compile validated replay cases into the only supported plan emitter."""

    payload = {
        "schemaVersion": SELF_CHECK_SCHEMA_VERSION,
        "status": SELF_CHECK_PLANNED_STATUS,
        "checks": list(cases),
    }
    plan_json = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return "\n".join(
        (
            "#!/usr/bin/env python3",
            '"""Controller-generated SkillBuilder offline replay plan."""',
            "",
            "import argparse",
            "from pathlib import Path",
            "",
            f"PLAN_JSON = {plan_json!r}",
            "",
            "",
            "def main() -> int:",
            "    parser = argparse.ArgumentParser()",
            "    parser.add_argument('--output-dir', required=True)",
            "    args = parser.parse_args()",
            "    output_dir = Path(args.output_dir)",
            "    output_dir.mkdir(parents=True, exist_ok=True)",
            "    (output_dir / 'self_check_summary.json').write_text(",
            "        PLAN_JSON + '\\n', encoding='utf-8'",
            "    )",
            "    return 0",
            "",
            "",
            "if __name__ == '__main__':",
            "    raise SystemExit(main())",
            "",
        )
    )


def _issue(issue_id: str, message: str, **details: Any) -> dict[str, Any]:
    value: dict[str, Any] = {"id": issue_id, "message": message}
    value.update({key: item for key, item in details.items() if item not in (None, "", [], {})})
    return value


def _package_relative_path(value: Any) -> str | None:
    raw = str(value or "").strip().replace("\\", "/")
    if not raw or raw.startswith(("/", "~")) or "\x00" in raw:
        return None
    normalized = PurePosixPath(raw)
    if any(part in {"", ".", ".."} for part in normalized.parts):
        return None
    first = normalized.parts[0]
    if first not in _PACKAGE_PATH_ROOTS:
        return None
    return normalized.as_posix()


def _output_relative_path(value: Any) -> str | None:
    raw = str(value or "").strip().replace("\\", "/")
    if not raw or raw.startswith(("/", "~")) or "\x00" in raw:
        return None
    normalized = PurePosixPath(raw)
    if any(part in {"", ".", ".."} for part in normalized.parts):
        return None
    return normalized.as_posix()


def _command_script(command: list[str]) -> str | None:
    for token in command[1:]:
        normalized = str(token or "").replace("\\", "/").removeprefix("./")
        if normalized.startswith("scripts/") and normalized.endswith(".py"):
            return normalized
    return None


def _normalized_command(value: Any) -> list[str] | None:
    if not isinstance(value, list) or not value:
        return None
    command = [str(part) for part in value]
    if any(not part.strip() or any(character in part for character in "\r\n\x00") for part in command):
        return None
    binary = Path(command[0]).name.lower()
    if binary not in {"python", "python3"}:
        return None
    script = _command_script(command)
    if script is None:
        return None
    for part in command[1:]:
        if _OUTPUT_PLACEHOLDER in part:
            suffix = part.replace(_OUTPUT_PLACEHOLDER, "", 1).lstrip("/")
            if suffix and _output_relative_path(suffix) is None:
                return None
            continue
        if part.startswith(("-", "http://", "https://")):
            continue
        # Absolute paths and shell control syntax never belong in the compact
        # protocol. Numeric/enum argument values are allowed.
        if part.startswith(("/", "~")) or re.search(r"(?:&&|\|\||[|<>;`]|\$\()", part):
            return None
        path_parts = PurePosixPath(part.replace("\\", "/")).parts
        if ".." in path_parts:
            return None
        if "/" in part and _package_relative_path(part) is None:
            return None
    return command


def _normalized_assertion(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    source = str(value.get("source") or "").strip().replace("\\", "/")
    if source.startswith(f"{_OUTPUT_PLACEHOLDER}/"):
        # Commands address the output directory through a placeholder, while
        # assertions are resolved relative to that same directory.  Accepting
        # the equivalent placeholder-prefixed spelling keeps the protocol
        # deterministic without allowing a second path root.
        source = source[len(_OUTPUT_PLACEHOLDER) + 1:]
    elif _OUTPUT_PLACEHOLDER in source:
        return None
    path = str(value.get("path") or "$").strip()
    if path.startswith("$["):
        path = path[1:]
    assertion_alias = value.get("assertion")
    operator = str(value.get("operator") or assertion_alias or "").strip().lower()
    expected_present = "expected" in value
    expected = value.get("expected")
    if (
        not expected_present
        and value.get("operator")
        and assertion_alias not in (None, "")
        and str(assertion_alias).strip().lower()
        not in SELF_CHECK_ASSERTION_OPERATORS
    ):
        expected_present = True
        expected = assertion_alias
    # Normalize the common file-existence spelling emitted by models.  It is
    # semantically unambiguous and equivalent to the canonical
    # ``path=$, operator=exists`` form; accepting it here keeps one protocol
    # boundary instead of spending a Repair turn on syntax alone.
    if (
        source != "$command"
        and path == "exists"
        and operator == "equals"
        and isinstance(value.get("expected"), bool)
    ):
        path = "$"
        operator = "exists"
    if (
        source != "$command"
        and path == "not_empty"
        and operator == "equals"
        and value.get("expected") is True
    ):
        path = "$"
        operator = "not_empty"
    if source == "$command":
        if path not in {"exitCode", "stdout", "stderr"}:
            return None
    elif _output_relative_path(source) is None:
        return None
    elif PurePosixPath(source).suffix.lower() not in _STRUCTURED_ASSERTION_SUFFIXES:
        text_path_aliases = set(_TEXT_CONTENT_PATH_ALIASES)
        if operator in {"exists", "not_empty"}:
            text_path_aliases.add(operator)
        if path not in text_path_aliases:
            return None
        path = "$"
    if not path or operator not in SELF_CHECK_ASSERTION_OPERATORS:
        return None
    if operator == "exists" and not expected_present:
        expected_present = True
        expected = True
    if operator == "exists" and not isinstance(expected, bool):
        return None
    if operator not in {"exists", "not_empty"} and not expected_present:
        return None
    return {
        "source": source,
        "path": path,
        "operator": operator,
        **({"expected": expected} if expected_present else {}),
        **(
            {"commandIndex": int(value.get("commandIndex"))}
            if isinstance(value.get("commandIndex"), int)
            else {}
        ),
        **(
            {"message": str(value.get("message"))[:500]}
            if str(value.get("message") or "").strip()
            else {}
        ),
    }


def _case_has_business_output_assertion(case: dict[str, Any]) -> bool:
    return any(
        assertion.get("source") != "$command"
        and assertion.get("operator")
        not in {"exists", "not_empty"}
        for assertion in case.get("assertions") or []
    )


def _case_has_business_value_assertion(case: dict[str, Any]) -> bool:
    """Reject label-only happy paths for offline structured-data tools."""

    generic_paths = {"", "status", "state", "success", "message", "error", "title"}
    for assertion in case.get("assertions") or []:
        if assertion.get("source") == "$command":
            continue
        operator = str(assertion.get("operator") or "")
        path = str(assertion.get("path") or "").strip("$.")
        leaf = path.split(".")[-1].lower()
        if (
            leaf not in generic_paths
            and operator
            in {
                "equals", "in", "length_equals", "maximum", "minimum",
                "not_empty", "not_equals", "sum_equals",
            }
        ):
            return True
        if operator == "contains" and re.search(
            r"\d",
            str(assertion.get("expected") or ""),
        ):
            return True
    return False


def _case_has_external_boundary_assertion(case: dict[str, Any]) -> bool:
    """Return whether an external-offline case proves a stable failure boundary."""

    return any(
        assertion.get("source") == "$command"
        and assertion.get("path") in {"stdout", "stderr"}
        and assertion.get("operator") in {"contains", "equals"}
        and str(assertion.get("expected") or "").strip()
        for assertion in case.get("assertions") or []
    )


def _assertion_allows_blocked(assertion: dict[str, Any]) -> bool:
    path = str(assertion.get("path") or "").strip("$.").split(".")[-1]
    if path not in {"status", "collectionStatus", "collection_status"}:
        return False
    operator = str(assertion.get("operator") or "")
    expected = assertion.get("expected")
    if operator == "equals":
        return str(expected or "").strip().lower() == "blocked"
    if operator == "in" and isinstance(expected, list):
        return "blocked" in {str(item or "").strip().lower() for item in expected}
    return False


def _section_coverage_parts(value: Any) -> tuple[str, tuple[str, ...]]:
    """Return the visible label and concrete fields of one section contract.

    Scenario authors often describe a document header as a semantic group,
    for example ``Header (date, operator, count)``.  The rendered document is
    allowed to use those concrete fields without printing the abstract group
    label verbatim.  Keeping both forms makes the coverage gate satisfiable
    while every accepted proof remains a platform-replayed ``contains``
    assertion.
    """

    raw = str(value or "").strip()
    if not raw:
        return "", ()
    match = re.match(r"^(?P<label>.*?)[（(](?P<details>.*)[）)]\s*$", raw)
    if match is None:
        return raw, ()
    label = str(match.group("label") or "").strip()
    details = tuple(
        dict.fromkeys(
            part.strip()
            for part in re.split(r"[、,，/;；]", str(match.group("details") or ""))
            if part.strip()
        )
    )
    return label or raw, details


def _section_is_covered(
    section: str,
    *,
    contains_expectations: tuple[str, ...],
) -> bool:
    label, detail_parts = _section_coverage_parts(section)
    if label and any(label in expected for expected in contains_expectations):
        return True
    return bool(
        detail_parts
        and all(
            any(part in expected for expected in contains_expectations)
            for part in detail_parts
        )
    )


def validate_self_check_summary(
    payload: Any,
    *,
    documented_entrypoints: set[str],
    required_contract_ids: set[str],
    require_invalid_input: bool,
    require_file_handoff: bool,
    external_entrypoints: set[str],
    require_invalid_nonzero_exit: bool = False,
    require_business_value_assertion: bool = False,
    required_output_fields: dict[str, str] | None = None,
    required_output_sections: set[str] | None = None,
) -> SelfCheckProtocolValidation:
    """Validate the bounded protocol before executing any declared command."""

    issues: list[dict[str, Any]] = []
    source = dict(payload) if isinstance(payload, dict) else {}
    if not source:
        return SelfCheckProtocolValidation(
            ok=False,
            issues=(
                _issue("self_check_summary_invalid", "self_check_summary.json 顶层必须是对象。"),
            ),
            cases=(),
            summary={},
        )
    if source.get("schemaVersion") != SELF_CHECK_SCHEMA_VERSION:
        issues.append(
            _issue(
                "self_check_schema_unsupported",
                f"self_check_summary.json 必须声明 schemaVersion={SELF_CHECK_SCHEMA_VERSION}。",
            )
        )
    status = str(source.get("status") or "").strip().lower()
    all_passed = source.get("all_passed")
    passed = source.get("passed")
    failed = source.get("failed")
    errors = source.get("errors")
    declaration_only = status == SELF_CHECK_PLANNED_STATUS
    if status not in {"pass", "fail", SELF_CHECK_PLANNED_STATUS}:
        issues.append(
            _issue("self_check_status_invalid", "status 必须是 planned、pass 或 fail。")
        )
    if not declaration_only:
        if not isinstance(all_passed, bool):
            issues.append(_issue("self_check_all_passed_invalid", "all_passed 必须是布尔值。"))
        if not isinstance(passed, int) or isinstance(passed, bool) or passed < 0:
            issues.append(_issue("self_check_passed_invalid", "passed 必须是非负整数。"))
        if not isinstance(failed, int) or isinstance(failed, bool) or failed < 0:
            issues.append(_issue("self_check_failed_invalid", "failed 必须是非负整数。"))
        if not isinstance(errors, list) or any(not isinstance(item, str) for item in errors):
            issues.append(_issue("self_check_errors_invalid", "errors 必须是字符串数组。"))
            errors = []

    raw_cases = source.get("checks")
    normalized_cases: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    if not isinstance(raw_cases, list) or not raw_cases:
        issues.append(_issue("self_check_cases_missing", "checks 必须包含至少一个可重放用例。"))
        raw_cases = []
    elif len(raw_cases) > MAX_SELF_CHECK_CASES:
        issues.append(
            _issue(
                "self_check_cases_excessive",
                f"checks 最多允许 {MAX_SELF_CHECK_CASES} 项。",
            )
        )
    for index, raw in enumerate(raw_cases[:MAX_SELF_CHECK_CASES]):
        if not isinstance(raw, dict):
            issues.append(_issue("self_check_case_invalid", f"checks[{index}] 必须是对象。"))
            continue
        case_id = str(raw.get("id") or "").strip()
        kind = str(raw.get("kind") or "").strip().lower()
        case_status = str(raw.get("status") or "").strip().lower()
        if declaration_only and not case_status:
            case_status = SELF_CHECK_PLANNED_STATUS
        covers = [str(item).strip() for item in raw.get("covers") or [] if str(item).strip()]
        if not case_id or case_id in seen_ids:
            issues.append(_issue("self_check_case_id_invalid", f"checks[{index}].id 缺失或重复。"))
            continue
        seen_ids.add(case_id)
        if kind not in SELF_CHECK_CASE_KINDS:
            issues.append(
                _issue("self_check_case_kind_invalid", f"用例 {case_id} 的 kind 不受支持。")
            )
        supported_case_statuses = {"pass", "fail"}
        if declaration_only:
            supported_case_statuses.add(SELF_CHECK_PLANNED_STATUS)
        if case_status not in supported_case_statuses:
            issues.append(
                _issue(
                    "self_check_case_status_invalid",
                    f"用例 {case_id} 的 status 无效。",
                )
            )
        raw_commands = raw.get("commands")
        commands: list[dict[str, Any]] = []
        if not isinstance(raw_commands, list) or not raw_commands:
            issues.append(_issue("self_check_commands_missing", f"用例 {case_id} 没有命令。"))
            raw_commands = []
        elif len(raw_commands) > MAX_COMMANDS_PER_CASE:
            issues.append(
                _issue(
                    "self_check_commands_excessive",
                    f"用例 {case_id} 最多允许 {MAX_COMMANDS_PER_CASE} 个命令。",
                )
            )
        for command_index, raw_command in enumerate(raw_commands[:MAX_COMMANDS_PER_CASE]):
            command_object = (
                raw_command
                if isinstance(raw_command, dict)
                else {"command": raw_command}
                if isinstance(raw_command, list)
                else {}
            )
            command = _normalized_command(command_object.get("command"))
            expected_exit_codes = command_object.get("expectedExitCodes", [0])
            if command is None:
                issues.append(
                    _issue(
                        "self_check_command_invalid",
                        (
                            f"用例 {case_id} 的 commands[{command_index}] 不是安全的 Python 包内命令；"
                            "使用 {\"command\":[\"python\",\"scripts/run.py\",...],"
                            "\"expectedExitCodes\":[0]} 或等价命令数组。"
                        ),
                    )
                )
                continue
            if not isinstance(expected_exit_codes, list) or not expected_exit_codes or any(
                not isinstance(code, int) or isinstance(code, bool) for code in expected_exit_codes
            ):
                issues.append(
                    _issue(
                        "self_check_expected_exit_invalid",
                        f"用例 {case_id} 的 commands[{command_index}].expectedExitCodes 无效。",
                    )
                )
                continue
            commands.append(
                {
                    "command": command,
                    "expectedExitCodes": list(dict.fromkeys(expected_exit_codes))[:8],
                }
            )

        raw_assertions = raw.get("assertions")
        assertions: list[dict[str, Any]] = []
        if not isinstance(raw_assertions, list) or not raw_assertions:
            issues.append(_issue("self_check_assertions_missing", f"用例 {case_id} 没有确定性断言。"))
            raw_assertions = []
        elif len(raw_assertions) > MAX_ASSERTIONS_PER_CASE:
            issues.append(
                _issue(
                    "self_check_assertions_excessive",
                    f"用例 {case_id} 最多允许 {MAX_ASSERTIONS_PER_CASE} 条断言。",
                )
            )
        for assertion_index, raw_assertion in enumerate(raw_assertions[:MAX_ASSERTIONS_PER_CASE]):
            assertion = _normalized_assertion(raw_assertion)
            if assertion is None:
                issues.append(
                    _issue(
                        "self_check_assertion_invalid",
                        (
                            f"用例 {case_id} 的 assertions[{assertion_index}] 无效；"
                            "必须提供 source、path、operator 和该 operator 所需的 expected。"
                        ),
                    )
                )
            else:
                assertions.append(assertion)
        normalized_case = {
            "id": case_id,
            "kind": kind,
            "status": case_status,
            "covers": list(dict.fromkeys(covers)),
            "commands": commands,
            "assertions": assertions,
        }
        if kind == "happy_path":
            invalid_commands = [
                index
                for index, command in enumerate(commands)
                if set(command.get("expectedExitCodes") or []) != {0}
            ]
            if invalid_commands:
                issues.append(
                    _issue(
                        "self_check_happy_path_exit_codes_invalid",
                        f"用例 {case_id} 的 happy_path 只能接受退出码 0。",
                        commandIndexes=invalid_commands,
                    )
                )
            blocked_assertions = [
                index
                for index, assertion in enumerate(assertions)
                if _assertion_allows_blocked(assertion)
            ]
            if blocked_assertions:
                issues.append(
                    _issue(
                        "self_check_happy_path_allows_blocked",
                        f"用例 {case_id} 的 happy_path 不能把 blocked 当作成功结果。",
                        assertionIndexes=blocked_assertions,
                    )
                )
            if (
                require_business_value_assertion
                and not _case_has_business_value_assertion(normalized_case)
            ):
                issues.append(
                    _issue(
                        "self_check_business_value_assertion_missing",
                        (
                            f"用例 {case_id} 只断言了标题、章节或通用状态；"
                            "离线结构化处理必须断言 fixture 中的实际金额、数量、"
                            "实体名称或其他业务字段；Markdown contains 优先使用带数字的金额/数量。"
                        ),
                    )
                )
        if kind == "invalid_input" and require_invalid_nonzero_exit:
            zero_exit_commands = [
                index
                for index, command in enumerate(commands)
                if 0 in set(command.get("expectedExitCodes") or [])
            ]
            if zero_exit_commands:
                issues.append(
                    _issue(
                        "self_check_invalid_input_exit_codes_invalid",
                        f"用例 {case_id} 的 invalid_input 不能接受退出码 0。",
                        commandIndexes=zero_exit_commands,
                    )
                )
        if (
            kind != "invalid_input"
            and assertions
            and not _case_has_business_output_assertion(normalized_case)
            and not (
                kind == "external_offline"
                and _case_has_external_boundary_assertion(normalized_case)
            )
        ):
            issues.append(
                _issue(
                    "self_check_output_assertion_missing",
                    f"用例 {case_id} 只检查命令状态或文件存在，没有检查真实业务输出。",
                )
            )
        normalized_cases.append(normalized_case)

    passed_cases = sum(case.get("status") == "pass" for case in normalized_cases)
    failed_cases = sum(case.get("status") == "fail" for case in normalized_cases)
    if not declaration_only:
        if isinstance(passed, int) and not isinstance(passed, bool) and passed != passed_cases:
            issues.append(_issue("self_check_count_inconsistent", "passed 与 checks 中的通过数不一致。"))
        if isinstance(failed, int) and not isinstance(failed, bool) and failed != failed_cases:
            issues.append(_issue("self_check_count_inconsistent", "failed 与 checks 中的失败数不一致。"))
        claimed_pass = status == "pass" and all_passed is True and failed_cases == 0 and not errors
        claimed_fail = status == "fail" and all_passed is False and (failed_cases > 0 or bool(errors))
        if not (claimed_pass or claimed_fail):
            issues.append(
                _issue(
                    "self_check_result_inconsistent",
                    "status、all_passed、failed、errors 与 checks 结论不一致。",
                )
            )

    kinds = {str(case.get("kind")) for case in normalized_cases}
    if documented_entrypoints and "happy_path" not in kinds:
        issues.append(_issue("self_check_happy_path_missing", "公开 CLI 缺少 happy_path 重放用例。"))
    if require_invalid_input and "invalid_input" not in kinds:
        issues.append(
            _issue("self_check_invalid_input_missing", "结构化输入缺少 invalid_input 重放用例。")
        )
    if require_file_handoff and "file_handoff" not in kinds:
        issues.append(
            _issue("self_check_file_handoff_missing", "多 CLI 流水线缺少 file_handoff 重放用例。")
        )
    if external_entrypoints and "external_offline" not in kinds:
        issues.append(
            _issue(
                "self_check_external_offline_missing",
                "外部采集 CLI 缺少基于本地响应 fixture 的 external_offline 用例。",
            )
        )
    covered_entrypoints = {
        script
        for case in normalized_cases
        for command in case.get("commands") or []
        if (script := _command_script(command.get("command") or []))
    }
    missing_entrypoints = sorted(documented_entrypoints - covered_entrypoints)
    if missing_entrypoints:
        issues.append(
            _issue(
                "self_check_cli_coverage_incomplete",
                "结构化用例没有覆盖全部文档化 CLI。",
                missing=missing_entrypoints,
            )
        )
    covered_contracts = {
        contract_id
        for case in normalized_cases
        for contract_id in case.get("covers") or []
    }
    missing_contracts = sorted(required_contract_ids - covered_contracts)
    if missing_contracts:
        issues.append(
            _issue(
                "self_check_contract_coverage_incomplete",
                "结构化用例没有覆盖全部可执行 ScenarioContract 规则。",
                missingContractIds=missing_contracts,
            )
        )
    output_assertions = [
        assertion
        for case in normalized_cases
        for assertion in case.get("assertions") or []
        if assertion.get("source") != "$command"
    ]
    output_fields = dict(required_output_fields or {})
    missing_output_fields = sorted(
        field
        for field in output_fields
        if not any(
            str(assertion.get("path") or "").strip("$.").split(".")[-1] == field
            for assertion in output_assertions
        )
    )
    if missing_output_fields:
        issues.append(
            _issue(
                "self_check_output_field_coverage_incomplete",
                "结构化输出字段没有平台可重算断言。",
                missingFields=missing_output_fields,
            )
        )
    missing_type_assertions = sorted(
        field
        for field, declared_type in output_fields.items()
        if declared_type
        and not any(
            str(assertion.get("path") or "").strip("$.").split(".")[-1] == field
            and assertion.get("operator") == "type"
            for assertion in output_assertions
        )
    )
    if missing_type_assertions:
        issues.append(
            _issue(
                "self_check_output_type_coverage_incomplete",
                "结构化输出字段缺少 type 断言。",
                missingFields=missing_type_assertions,
            )
        )
    contains_expectations = tuple(
        str(assertion.get("expected") or "")
        for assertion in output_assertions
        if assertion.get("operator") == "contains"
    )
    missing_sections = sorted(
        {
            label
            for section in (required_output_sections or set())
            if not _section_is_covered(
                section,
                contains_expectations=contains_expectations,
            )
            for label, _details in [_section_coverage_parts(section)]
            if label
        }
    )
    if missing_sections:
        issues.append(
            _issue(
                "self_check_output_section_coverage_incomplete",
                "Markdown 输出章节没有平台可重算 contains 断言。",
                missingSections=missing_sections,
            )
        )
    return SelfCheckProtocolValidation(
        ok=not issues,
        issues=tuple(issues),
        cases=tuple(normalized_cases),
        summary=source,
    )


def self_check_package_input_issues(
    generated: Path,
    cases: tuple[dict[str, Any], ...],
) -> tuple[dict[str, Any], ...]:
    """Return package-local command inputs missing from the exported artifact."""

    issues: list[dict[str, Any]] = []
    seen: set[tuple[str, int, str]] = set()
    for case in cases:
        case_id = str(case.get("id") or "case")
        for command_index, command_spec in enumerate(case.get("commands") or []):
            command = command_spec.get("command") or []
            for raw_part in command[1:]:
                part = str(raw_part or "").strip().replace("\\", "/")
                if not part or _OUTPUT_PLACEHOLDER in part:
                    continue
                if part.startswith(("http://", "https://")):
                    continue
                if part.startswith("-"):
                    if "=" not in part:
                        continue
                    part = part.split("=", 1)[1]
                package_path = _package_relative_path(part)
                if package_path is None:
                    continue
                identity = (case_id, command_index, package_path)
                if identity in seen:
                    continue
                seen.add(identity)
                if (generated / package_path).exists():
                    continue
                issues.append(
                    _issue(
                        "self_check_package_input_missing",
                        f"用例 {case_id} 引用的包内输入文件不存在。",
                        caseId=case_id,
                        commandIndex=command_index,
                        path=package_path,
                    )
                )
    return tuple(issues)


def _json_path(value: Any, path: str) -> tuple[bool, Any]:
    if path in {"", "$"}:
        return True, value
    normalized = path.removeprefix("$.").replace("[", ".").replace("]", "")
    current = value
    for part in (token for token in normalized.split(".") if token):
        if isinstance(current, dict) and part in current:
            current = current[part]
            continue
        if isinstance(current, list) and part.isdigit() and int(part) < len(current):
            current = current[int(part)]
            continue
        return False, None
    return True, current


def _assertion_passes(*, exists: bool, actual: Any, operator: str, expected: Any) -> bool:
    if operator == "exists":
        return exists is bool(expected) if isinstance(expected, bool) else exists
    if operator == "not_empty":
        return exists and actual not in (None, "", [], {})
    if not exists:
        return False
    if operator == "equals":
        return actual == expected
    if operator == "not_equals":
        return actual != expected
    if operator == "in":
        return isinstance(expected, list) and actual in expected
    if operator == "contains":
        try:
            return expected in actual
        except (TypeError, ValueError):
            return False
    if operator in {"minimum", "maximum"}:
        try:
            actual_number = Decimal(str(actual))
            expected_number = Decimal(str(expected))
        except (InvalidOperation, TypeError, ValueError):
            return False
        return actual_number >= expected_number if operator == "minimum" else actual_number <= expected_number
    if operator in {"min_items", "max_items"}:
        try:
            length = len(actual)
            expected_length = int(expected)
        except (TypeError, ValueError):
            return False
        return length >= expected_length if operator == "min_items" else length <= expected_length
    if operator == "length_equals":
        try:
            return len(actual) == int(expected)
        except (TypeError, ValueError):
            return False
    if operator == "keys_equal":
        return isinstance(actual, dict) and isinstance(expected, list) and set(actual) == set(expected)
    if operator == "sum_equals":
        values = actual.values() if isinstance(actual, dict) else actual
        if not isinstance(values, (list, tuple, set)) and not isinstance(actual, dict):
            return False
        try:
            return sum((Decimal(str(item)) for item in values), Decimal("0")) == Decimal(str(expected))
        except (InvalidOperation, TypeError, ValueError):
            return False
    if operator == "type":
        expected_type = str(expected or "").strip().lower()
        predicates = {
            "array": lambda item: isinstance(item, list),
            "boolean": lambda item: isinstance(item, bool),
            "integer": lambda item: isinstance(item, int) and not isinstance(item, bool),
            "null": lambda item: item is None,
            "number": lambda item: isinstance(item, (int, float)) and not isinstance(item, bool),
            "object": lambda item: isinstance(item, dict),
            "string": lambda item: isinstance(item, str),
        }
        predicate = predicates.get(expected_type)
        return bool(predicate and predicate(actual))
    return False


def _load_assertion_source(path: Path) -> tuple[bool, Any, str | None]:
    if not path.is_file():
        return False, None, "missing"
    try:
        if path.suffix.lower() in _STRUCTURED_ASSERTION_SUFFIXES:
            if path.suffix.lower() == ".jsonl":
                value = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
            else:
                value = json.loads(path.read_text(encoding="utf-8"))
            return True, value, None
        return True, path.read_text(encoding="utf-8", errors="replace"), None
    except (OSError, TypeError, ValueError) as exc:
        return True, None, str(exc)[:500]


def _expanded_command(command: list[str], output_dir_arg: str) -> list[str]:
    return [
        part.replace(_OUTPUT_PLACEHOLDER, output_dir_arg)
        for part in command
    ]


def validation_output_directories(
    command: list[str],
    *,
    workdir: str,
    verify_root: str,
) -> tuple[str, ...]:
    """Return bounded sandbox directories required by declared output flags."""

    verify = Path(verify_root).resolve()
    directories = {verify.as_posix()}
    for index, flag in enumerate(command[:-1]):
        if flag not in {"--output-dir", "--output", "-o"}:
            continue
        target = str(command[index + 1] or "").strip()
        if not target:
            continue
        candidate = Path(workdir, target).resolve()
        if not candidate.is_relative_to(verify):
            continue
        directories.add(
            candidate.as_posix()
            if flag == "--output-dir"
            else candidate.parent.as_posix()
        )
    return tuple(sorted(directories))


def _traceback_producer_paths(stderr: str, generated: Path) -> set[str]:
    """Resolve package Python files named by a runtime traceback."""

    producers: set[str] = set()
    for match in _TRACEBACK_FILE_RE.finditer(str(stderr or "")):
        raw = str(match.group("path") or "").replace("\\", "/")
        marker = "/generated-skill/"
        if marker in raw:
            relative = raw.rsplit(marker, 1)[1]
        elif "/scripts/" in raw:
            relative = "scripts/" + raw.rsplit("/scripts/", 1)[1]
        else:
            continue
        package_path = _package_relative_path(relative)
        if (
            package_path is not None
            and package_path.startswith("scripts/")
            and package_path.endswith(".py")
            and (generated / package_path).is_file()
        ):
            producers.add(package_path)
    return producers


async def replay_self_check_cases(
    *,
    root: Path,
    generated: Path,
    cases: tuple[dict[str, Any], ...],
    execution_port: SkillBuilderExecutionPort,
    timeout_seconds: int,
) -> SelfCheckReplayResult:
    """Replay every declared command and evaluate outputs from Core."""

    replay_dir = root / "workspace" / "verify" / ".platform-replay"
    shutil.rmtree(replay_dir, ignore_errors=True)
    replay_dir.mkdir(parents=True, exist_ok=True)
    output_dir_arg = "../workspace/verify/.platform-replay"
    checks: list[dict[str, Any]] = []
    issues: list[dict[str, Any]] = []
    for case in cases:
        case_id = str(case.get("id") or "case")
        producer_paths = {
                script
                for command_spec in case.get("commands") or []
                if (
                    script := _command_script(
                        [str(part) for part in command_spec.get("command") or []]
                    )
                )
                is not None
            }
        command_results: list[dict[str, Any]] = []
        case_failed = False
        for command_index, command_spec in enumerate(case.get("commands") or []):
            command = _expanded_command(command_spec["command"], output_dir_arg)
            result = await execution_port.run(
                ExecutionRequest(
                    command=tuple(command),
                    cwd=generated,
                    timeout_seconds=max(1, int(timeout_seconds)),
                    # ``python scripts/entry.py`` sets sys.path[0] to the
                    # scripts directory.  Explicitly expose the exported
                    # package root so sibling package imports behave the same
                    # in local, sandbox and post-export execution.
                    env={"PYTHONPATH": "."},
                )
            )
            command_result = {
                "command": command,
                "exitCode": result.exit_code,
                "timedOut": result.timed_out,
                "stdout": (result.stdout or "")[-2000:],
                "stderr": (result.stderr or "")[-2000:],
            }
            command_producer_paths = {
                script
                for script in (
                    _command_script([str(part) for part in command_spec.get("command") or []]),
                )
                if script is not None
            } | _traceback_producer_paths(result.stderr or "", generated)
            producer_paths.update(command_producer_paths)
            command_result["producerPaths"] = sorted(command_producer_paths)
            command_results.append(command_result)
            expected_exit_codes = command_spec.get("expectedExitCodes") or [0]
            if result.timed_out or result.exit_code not in expected_exit_codes:
                case_failed = True
                issues.append(
                    _issue(
                        "self_check_replay_command_failed",
                        f"用例 {case_id} 的命令 {command_index + 1} 未得到预期退出码。",
                        caseId=case_id,
                        command=command,
                        expectedExitCodes=expected_exit_codes,
                        actualExitCode=result.exit_code,
                        timedOut=result.timed_out,
                        stderr=(result.stderr or "")[-1000:],
                        producerPaths=sorted(command_producer_paths),
                    )
                )

        assertion_results: list[dict[str, Any]] = []
        for assertion in case.get("assertions") or []:
            source = assertion["source"]
            path = assertion["path"]
            if source == "$command":
                command_index = int(assertion.get("commandIndex", len(command_results) - 1))
                if 0 <= command_index < len(command_results):
                    command_result = command_results[command_index]
                    exists, actual = _json_path(command_result, path)
                    source_error = None
                else:
                    exists, actual, source_error = False, None, "command index out of range"
            else:
                target = replay_dir / source
                try:
                    target.resolve().relative_to(replay_dir.resolve())
                except (OSError, ValueError):
                    exists, actual, source_error = False, None, "output path escaped replay directory"
                else:
                    source_exists, source_value, source_error = _load_assertion_source(target)
                    exists, actual = (
                        _json_path(source_value, path) if source_exists and source_error is None else (False, None)
                    )
            passed = source_error is None and _assertion_passes(
                exists=exists,
                actual=actual,
                operator=assertion["operator"],
                expected=assertion.get("expected"),
            )
            assertion_result = {
                **assertion,
                "passed": passed,
                "actual": actual,
                **({"error": source_error} if source_error else {}),
            }
            assertion_results.append(assertion_result)
            if not passed:
                case_failed = True
                issues.append(
                    _issue(
                        "self_check_replay_assertion_failed",
                        str(assertion.get("message") or f"用例 {case_id} 的确定性断言未通过。"),
                        caseId=case_id,
                        assertion=assertion,
                        producerPaths=sorted(producer_paths),
                        actual=actual,
                        sourceError=source_error,
                    )
                )
        replay_status = "fail" if case_failed else "pass"
        claimed_status = str(case.get("status") or "")
        if claimed_status in {"pass", "fail"} and replay_status != claimed_status:
            issues.append(
                _issue(
                    "self_check_claim_mismatch",
                    f"用例 {case_id} 的包内结论与平台重放结论不一致。",
                    caseId=case_id,
                    claimedStatus=claimed_status,
                    replayStatus=replay_status,
                )
            )
        checks.append(
            {
                "id": case_id,
                "kind": case.get("kind"),
                "status": replay_status,
                "commands": command_results,
                "assertions": assertion_results,
                "covers": case.get("covers") or [],
            }
        )
    return SelfCheckReplayResult(ok=not issues, issues=tuple(issues), checks=tuple(checks))


def _scenario(root: Path) -> dict[str, Any]:
    try:
        value = json.loads((root / "validation" / "scenario_contract.json").read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


_CONTRACT_ALIAS_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9_-]{1,30}-\d{1,8}\b")


def scenario_contract_id_aliases(root: Path) -> dict[str, str]:
    """Map unique material rule labels such as DQ-001 to platform rule IDs."""

    candidates: dict[str, set[str]] = {}
    for item in _scenario(root).get("businessRules") or []:
        if not isinstance(item, dict):
            continue
        contract_id = str(item.get("ruleId") or "").strip()
        if not contract_id:
            continue
        aliases = {contract_id}
        definition = item.get("definition")
        if isinstance(definition, dict):
            aliases.update(
                str(definition.get(key) or "").strip()
                for key in ("id", "ruleId", "code", "name")
            )
        encoded = json.dumps(definition, ensure_ascii=False, default=str)
        aliases.update(_CONTRACT_ALIAS_RE.findall(encoded))
        for alias in aliases:
            if alias:
                candidates.setdefault(alias.casefold(), set()).add(contract_id)
    return {
        alias: next(iter(contract_ids))
        for alias, contract_ids in candidates.items()
        if len(contract_ids) == 1
    }


def normalize_self_check_contract_covers(
    root: Path,
    checks: Any,
) -> list[Any]:
    """Normalize covers aliases without changing commands or assertions."""

    aliases = scenario_contract_id_aliases(root)
    normalized: list[Any] = []
    for raw in checks if isinstance(checks, list) else []:
        if not isinstance(raw, dict):
            normalized.append(raw)
            continue
        covers = [
            aliases.get(str(value or "").strip().casefold(), str(value or "").strip())
            for value in raw.get("covers") or []
            if str(value or "").strip()
        ]
        normalized.append({**raw, "covers": list(dict.fromkeys(covers))})
    return normalized


def scenario_required_contract_ids(root: Path) -> set[str]:
    scenario = _scenario(root)
    result = {
        str(item.get("ruleId") or "").strip()
        for item in scenario.get("businessRules") or []
        if isinstance(item, dict) and str(item.get("ruleId") or "").strip()
    }
    return result


def scenario_has_structured_inputs(root: Path) -> bool:
    return bool(scenario_structured_input_contracts(_scenario(root)))


def scenario_invalid_input_requires_nonzero_exit(root: Path) -> bool:
    scenario = _scenario(root)
    text = json.dumps(
        [
            scenario.get("steps") or [],
            scenario.get("acceptanceCriteria") or [],
            scenario.get("resolvedRequirements") or [],
            scenario.get("businessRules") or [],
        ],
        ensure_ascii=False,
        default=str,
    )
    return bool(
        re.search(
            r"(?:非零|非\s*0).{0,12}退出码|non[- ]?zero.{0,12}exit",
            text,
            re.IGNORECASE,
        )
    )


def _declared_json_type(value: Any) -> str:
    normalized = str(value or "").strip().lower()
    if any(token in normalized for token in ("数组", "列表", "array", "list")):
        return "array"
    if any(token in normalized for token in ("对象", "映射", "object", "dict", "map")):
        return "object"
    if any(token in normalized for token in ("布尔", "boolean", "bool")):
        return "boolean"
    if any(token in normalized for token in ("整数", "integer", "int")):
        return "integer"
    if any(token in normalized for token in ("数值", "数字", "金额", "number", "decimal", "float")):
        return "number"
    if any(token in normalized for token in ("文本", "字符", "string", "str")):
        return "string"
    return ""


def scenario_output_invariants(root: Path) -> tuple[dict[str, str], set[str]]:
    """Project explicit output fields and Markdown sections from Scenario."""

    scenario = _scenario(root)
    fields: dict[str, str] = {}
    sections: set[str] = set()
    for output in scenario.get("outputs") or []:
        if not isinstance(output, dict):
            continue
        for field in output.get("fields") or []:
            if not isinstance(field, dict):
                continue
            name = str(field.get("name") or "").strip()
            if name:
                fields[name] = _declared_json_type(field.get("type"))
        format_text = str(output.get("format") or "").lower()
        if "markdown" not in format_text and "md" not in format_text:
            continue
        for raw in output.get("sections") or []:
            section = str(raw or "").strip()
            if section:
                sections.add(section)
    return fields, sections


def _type_matches(value: Any, declared_type: str) -> bool:
    normalized = declared_type.lower()
    if not normalized or any(token in normalized for token in ("文本", "string", "str", "字符")):
        return str(value).strip() != ""
    if any(token in normalized for token in ("数值", "数字", "金额", "number", "decimal", "float")):
        try:
            number = Decimal(str(value).replace(",", "").strip())
        except (InvalidOperation, TypeError, ValueError):
            return False
        return number.is_finite()
    if any(token in normalized for token in ("整数", "integer", "int")):
        try:
            return Decimal(str(value).strip()) % 1 == 0
        except (InvalidOperation, TypeError, ValueError):
            return False
    if any(token in normalized for token in ("布尔", "boolean", "bool")):
        return value in {True, False, 0, 1, "0", "1", "true", "false", "True", "False"}
    return True


def _field_constraints_match(value: Any, field: dict[str, Any]) -> bool:
    """Check explicit machine-readable field constraints on a happy fixture."""

    raw_allowed = (
        field.get("allowedValues")
        or field.get("values")
        or field.get("enum")
        or field.get("options")
    )
    if isinstance(raw_allowed, list) and raw_allowed:
        allowed = {
            str(item.get("value") if isinstance(item, dict) else item).strip()
            for item in raw_allowed
        }
        if str(value).strip() not in allowed:
            return False
    pattern = str(field.get("pattern") or field.get("regex") or "").strip()
    if pattern:
        try:
            if re.fullmatch(pattern, str(value)) is None:
                return False
        except re.error:
            return False
    length = len(str(value))
    for key, comparison in (
        ("minLength", lambda bound: length >= bound),
        ("maxLength", lambda bound: length <= bound),
    ):
        raw_bound = field.get(key)
        try:
            bound = int(raw_bound)
        except (TypeError, ValueError):
            continue
        if bound >= 0 and not comparison(bound):
            return False
    return True


def _csv_records(path: Path) -> tuple[list[str], list[dict[str, Any]], list[dict[str, Any]]]:
    issues: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.reader(handle))
    if not rows:
        return [], [], [_issue("input_fixture_empty", "CSV fixture 为空。", path=path.as_posix())]
    headers = rows[0]
    records: list[dict[str, Any]] = []
    for index, row in enumerate(rows[1:], start=2):
        if len(row) != len(headers):
            issues.append(
                _issue(
                    "input_fixture_column_width_invalid",
                    "CSV fixture 存在列宽与表头不一致的记录。",
                    path=path.as_posix(),
                    row=index,
                    expectedColumns=len(headers),
                    actualColumns=len(row),
                )
            )
            continue
        records.append(dict(zip(headers, row, strict=True)))
    return headers, records, issues


def _json_records(path: Path) -> tuple[list[str], list[dict[str, Any]], list[dict[str, Any]]]:
    if path.suffix.lower() == ".jsonl":
        value: Any = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    else:
        value = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(value, dict):
        candidates = [item for item in value.values() if isinstance(item, list)]
        records_value = next(
            (item for item in candidates if item and all(isinstance(row, dict) for row in item)),
            [value],
        )
    else:
        records_value = value
    records = [dict(item) for item in records_value or [] if isinstance(item, dict)]
    headers = list(dict.fromkeys(key for record in records for key in record))
    return headers, records, []


def _xlsx_records(path: Path) -> tuple[list[str], list[dict[str, Any]], list[dict[str, Any]]]:
    with zipfile.ZipFile(path) as archive:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
            shared = ["".join(node.itertext()) for node in root.findall(f"{_XML_NS}si")]
        workbook = ElementTree.fromstring(archive.read("xl/workbook.xml"))
        relationships = ElementTree.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        relation_targets = {
            node.attrib["Id"]: node.attrib["Target"]
            for node in relationships
        }
        sheet = workbook.find(f"{_XML_NS}sheets/{_XML_NS}sheet")
        if sheet is None:
            return [], [], [_issue("input_fixture_empty", "Excel fixture 没有工作表。", path=path.as_posix())]
        relation_id = sheet.attrib.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id", "")
        target = relation_targets.get(relation_id, "worksheets/sheet1.xml").lstrip("/")
        sheet_path = target if target.startswith("xl/") else f"xl/{target}"
        xml = ElementTree.fromstring(archive.read(sheet_path))
        rows: list[list[str]] = []
        for row in xml.findall(f".//{_XML_NS}row"):
            values: list[str] = []
            for cell in row.findall(f"{_XML_NS}c"):
                raw = cell.find(f"{_XML_NS}v")
                value = raw.text if raw is not None and raw.text is not None else ""
                if cell.attrib.get("t") == "s" and value.isdigit() and int(value) < len(shared):
                    value = shared[int(value)]
                elif cell.attrib.get("t") == "inlineStr":
                    inline = cell.find(f"{_XML_NS}is")
                    value = "".join(inline.itertext()) if inline is not None else ""
                values.append(value)
            rows.append(values)
    if not rows:
        return [], [], [_issue("input_fixture_empty", "Excel fixture 为空。", path=path.as_posix())]
    headers = rows[0]
    records = [
        dict(zip(headers, [*row, *([""] * max(0, len(headers) - len(row)))][: len(headers)], strict=True))
        for row in rows[1:]
    ]
    return headers, records, []


def _fixture_records(path: Path) -> tuple[list[str], list[dict[str, Any]], list[dict[str, Any]]]:
    if path.suffix.lower() == ".csv":
        return _csv_records(path)
    if path.suffix.lower() in {".json", ".jsonl"}:
        return _json_records(path)
    if path.suffix.lower() == ".xlsx":
        return _xlsx_records(path)
    return [], [], []


def _is_utf8_charset(value: str) -> bool:
    try:
        return codecs.lookup(str(value or "").strip()).name == "utf-8"
    except LookupError:
        return False


def html_fixture_encoding_issues(generated: Path) -> list[dict[str, Any]]:
    """Require portable UTF-8 declarations for non-ASCII HTML fixtures.

    HTML parsers may otherwise reinterpret UTF-8 bytes using a legacy default,
    corrupting business labels while the fixture itself still looks valid to a
    text editor. ASCII-only fixtures are intentionally exempt because their
    decoded value is charset-independent.
    """

    fixtures_root = generated / "fixtures"
    if not fixtures_root.is_dir():
        return []
    issues: list[dict[str, Any]] = []
    paths = sorted(
        path
        for path in fixtures_root.rglob("*")
        if path.is_file() and path.suffix.casefold() in {".html", ".htm"}
    )
    for path in paths:
        relative = path.relative_to(generated).as_posix()
        try:
            content = path.read_bytes()
        except OSError as exc:
            issues.append(
                _issue(
                    "html_fixture_unreadable",
                    "HTML fixture 无法读取，不能验证编码契约。",
                    path=relative,
                    error=str(exc)[:500],
                )
            )
            continue
        if not any(byte >= 0x80 for byte in content):
            continue
        try:
            text = content.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            issues.append(
                _issue(
                    "html_fixture_not_utf8",
                    "含非 ASCII 字节的 HTML fixture 必须使用 UTF-8 存储。",
                    path=relative,
                    actualEncoding="not-utf8",
                    error=str(exc)[:500],
                )
            )
            continue
        parser = _HTMLCharsetDeclarationParser()
        parser.feed(text)
        parser.close()
        declared = list(dict.fromkeys(parser.charsets))
        if not declared:
            issues.append(
                _issue(
                    "html_fixture_charset_missing",
                    "含非 ASCII 文本的 HTML fixture 缺少 UTF-8 meta 声明，解析器可能错误解码业务字段。",
                    path=relative,
                    actualEncoding="utf-8",
                )
            )
            continue
        incompatible = [value for value in declared if not _is_utf8_charset(value)]
        if incompatible:
            issues.append(
                _issue(
                    "html_fixture_charset_mismatch",
                    "HTML fixture 的 meta charset 与实际 UTF-8 内容不一致。",
                    path=relative,
                    actualEncoding="utf-8",
                    declaredCharsets=declared,
                )
            )
    return issues


def scenario_input_fixture_issues(root: Path, generated: Path) -> list[dict[str, Any]]:
    """Validate one happy fixture for each structured Scenario input."""

    scenario = _scenario(root)
    contracts = scenario_structured_input_contracts(scenario)
    if not contracts:
        return []
    fixtures_root = generated / "fixtures"
    fixtures = [
        path
        for path in fixtures_root.rglob("*")
        if path.is_file()
        and path.suffix.lower() in {".csv", ".json", ".jsonl", ".xlsx"}
        and not any(token in path.stem.lower() for token in ("invalid", "error", "malformed", "empty"))
    ] if fixtures_root.is_dir() else []
    issues: list[dict[str, Any]] = []
    parsed: dict[Path, tuple[list[str], list[dict[str, Any]], list[dict[str, Any]]]] = {}
    for path in fixtures:
        try:
            parsed[path] = _fixture_records(path)
        except (
            OSError,
            TypeError,
            ValueError,
            zipfile.BadZipFile,
            ElementTree.ParseError,
        ) as exc:
            issues.append(
                _issue(
                    "input_fixture_unreadable",
                    "结构化输入 fixture 无法解析。",
                    path=path.relative_to(generated).as_posix(),
                    error=str(exc)[:500],
                )
            )
    for index, contract in enumerate(contracts):
        fields = [field for field in contract.get("fields") or [] if isinstance(field, dict)]
        required = [str(field.get("name") or "").strip() for field in fields if field.get("required") is True]
        all_names = [str(field.get("name") or "").strip() for field in fields if str(field.get("name") or "").strip()]
        if not all_names:
            continue
        format_text = str(contract.get("format") or "").lower()
        allowed_suffixes = {
            suffix
            for token, suffix in (
                ("csv", ".csv"),
                ("jsonl", ".jsonl"),
                ("json", ".json"),
                ("excel", ".xlsx"),
                ("xlsx", ".xlsx"),
            )
            if token in format_text
        }
        candidates = {
            path: value
            for path, value in parsed.items()
            if not allowed_suffixes or path.suffix.lower() in allowed_suffixes
        }
        ranked = sorted(
            candidates.items(),
            key=lambda item: (
                len(set(all_names) & set(item[1][0])),
                "sample" in item[0].stem.lower(),
                -len(item[0].name),
            ),
            reverse=True,
        )
        if not ranked or not set(required).issubset(set(ranked[0][1][0])):
            issues.append(
                _issue(
                    "input_fixture_contract_missing",
                    "没有 fixture 完整覆盖 ScenarioContract 的必需输入字段。",
                    inputIndex=index,
                    requiredFields=required,
                    expectedFormats=sorted(
                        suffix.removeprefix(".") for suffix in allowed_suffixes
                    ),
                    availableFixtures=[
                        path.relative_to(generated).as_posix()
                        for path in sorted(fixtures)
                    ],
                )
            )
            continue
        path, (headers, records, parse_issues) = ranked[0]
        issues.extend(
            {**item, "path": path.relative_to(generated).as_posix()}
            for item in parse_issues
        )
        if not records:
            issues.append(
                _issue(
                    "input_fixture_no_valid_record",
                    "结构化输入 fixture 没有可用于 happy path 的记录。",
                    path=path.relative_to(generated).as_posix(),
                )
            )
            continue
        valid_record_found = False
        for record in records:
            if any(str(record.get(name, "")).strip() == "" for name in required):
                continue
            type_valid = True
            for field in fields:
                name = str(field.get("name") or "").strip()
                value = record.get(name)
                if (
                    name
                    and value not in (None, "")
                    and (
                        not _type_matches(value, str(field.get("type") or ""))
                        or not _field_constraints_match(value, field)
                    )
                ):
                    type_valid = False
                    break
            if type_valid:
                valid_record_found = True
                break
        if not valid_record_found:
            issues.append(
                _issue(
                    "input_fixture_no_valid_record",
                    "fixture 中没有同时满足必填和基础类型约束的 happy path 记录。",
                    path=path.relative_to(generated).as_posix(),
                    requiredFields=required,
                    headers=headers,
                )
            )
    return issues


__all__ = [
    "compile_self_check_plan_script",
    "SELF_CHECK_PLANNED_STATUS",
    "SELF_CHECK_SCHEMA_VERSION",
    "SelfCheckProtocolValidation",
    "SelfCheckReplayResult",
    "html_fixture_encoding_issues",
    "is_platform_replay_plan_source",
    "replay_self_check_cases",
    "scenario_has_structured_inputs",
    "scenario_contract_id_aliases",
    "normalize_self_check_contract_covers",
    "scenario_invalid_input_requires_nonzero_exit",
    "scenario_input_fixture_issues",
    "scenario_output_invariants",
    "scenario_required_contract_ids",
    "self_check_package_input_issues",
    "validation_output_directories",
    "validate_self_check_summary",
]
