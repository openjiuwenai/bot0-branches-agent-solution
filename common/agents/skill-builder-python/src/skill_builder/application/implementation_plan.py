# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""One small controller-owned boundary before Author writes package files."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path, PurePosixPath
from typing import Any

from skill_builder.application.fixture_builder import platform_owned_fixture_paths
from skill_builder.application.input_contracts import scenario_structured_input_contracts
from skill_builder.application.implementation_integrity import (
    documented_cli_entrypoints,
)
from skill_builder.domain.candidate_contract import export_package_path_allowed


IMPLEMENTATION_PLAN_PATH = "validation/implementation_plan.json"
IMPLEMENTATION_PLAN_SCHEMA_VERSION = "skill-builder-implementation-plan/v2"
BEHAVIOR_SIGNATURE_SCHEMA_VERSION = "skill-builder-behavior-signature/v2"
RUNTIME_CAPABILITIES = frozenset(
    {"api_runtime", "browser_runtime", "external_runtime"}
)
EXECUTABLE_SOURCE_SUFFIXES = frozenset({".py", ".js", ".ts", ".mjs"})
_DIAGNOSTIC_SCRIPT_NAMES = frozenset(
    {"self_check.py", "run_offline_test.py", "offline_test.py"}
)
_OFFLINE_RECORD_PROCESSING_RE = re.compile(
    r"(?:筛选|过滤|匹配|关联|累计|汇总|统计|冲抵|先进先出|去重|排序)"
    r".{0,32}(?:数据|记录|字段|金额|流水|行|账户|贷款)"
    r"|(?:数据|记录|字段|金额|流水|行|账户|贷款)"
    r".{0,32}(?:筛选|过滤|匹配|关联|累计|汇总|统计|冲抵|先进先出|去重|排序)"
    r"|\b(?:filter|join|match|aggregate|calculate|deduplicate|sort|fifo)\b"
    r".{0,48}\b(?:data|record|row|field|amount|transaction)\b"
    r"|\b(?:data|record|row|field|amount|transaction)\b"
    r".{0,48}\b(?:filter|join|match|aggregate|calculate|deduplicate|sort|fifo)\b",
    re.IGNORECASE,
)
_MULTI_SOURCE_PROCESSING_RE = re.compile(
    r"(?:交叉核验|交叉校验|对账|勾稽|多表(?:关联|匹配)|"
    r"(?:数据源|表)\s*[A-Z甲乙一二1-2].{0,48}(?:数据源|表)\s*[A-Z甲乙一二1-2]"
    r"|按.{0,20}(?:账号|账户|编号|ID|主键|合同|借据|流水号).{0,20}(?:匹配|关联|合并))"
    r"|\b(?:cross[- ]check|reconcile|join\s+key|multi[- ]table)\b",
    re.IGNORECASE,
)
_EXECUTABLE_DELIVERY_RE = re.compile(
    r"Python\s*(?:CLI|脚本|程序|命令行)"
    r"|可(?:离线)?执行(?:的)?\s*(?:CLI\s*)?(?:脚本|工具|程序)"
    r"|(?:需要|要求|交付|提供|实现|生成).{0,24}"
    r"(?:CLI(?:脚本|工具)?|命令行入口|Python\s*(?:脚本|程序)|脚本(?:实现|处理|完成)?)"
    r"|(?:CLI|命令行)(?:入口|工具|脚本)"
    r"|scripts/[A-Za-z0-9_./-]+\.py"
    r"|\"cli\"\s*:",
    re.IGNORECASE,
)
_NEGATED_EXECUTABLE_DELIVERY_RE = re.compile(
    r"(?:未|不|无需|无须|没有|并非|禁止).{0,20}"
    r"(?:要求|需要|提供|实现|包含|涉及|使用|生成|交付)?.{0,20}"
    r"(?:CLI|脚本|命令行入口|Python\s*(?:脚本|程序)|可执行)"
    r"|(?:does\s+not|doesn't|not|required\s+no|without|no)"
    r".{0,24}(?:CLI|script|executable|command[- ]line)",
    re.IGNORECASE,
)


def _read_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return {}
    return dict(value) if isinstance(value, dict) else {}


def _package_path(value: Any) -> str:
    raw = str(value or "").strip().replace("\\", "/")
    while raw.startswith("./"):
        raw = raw[2:]
    if raw.startswith("generated-skill/"):
        raw = raw.removeprefix("generated-skill/")
    path = PurePosixPath(raw)
    if (
        not raw
        or path.is_absolute()
        or ".." in path.parts
        or not export_package_path_allowed(raw)
    ):
        return ""
    return path.as_posix()


def _resolved_capabilities(root: Path) -> dict[str, Any]:
    manifest = _read_object(root / "validation" / "artifact_manifest.json")
    value = manifest.get("resolvedCapabilityContract")
    return dict(value) if isinstance(value, dict) else {}


def projected_scripts_required(root: Path) -> bool | None:
    """Read the controller-owned delivery direction before a plan exists."""

    manifest = _read_object(root / "validation" / "artifact_manifest.json")
    signature = manifest.get("behaviorSignature")
    value = signature.get("scriptsRequired") if isinstance(signature, dict) else None
    return value if isinstance(value, bool) else None


def _requires_offline_record_processing(
    scenario: dict[str, Any],
    structured_inputs: list[dict[str, Any]],
) -> bool:
    """Keep deterministic record transformations executable across retries."""

    typed_fields = {
        str(field.get("type") or "").strip().lower()
        for contract in structured_inputs
        for field in contract.get("fields") or []
        if isinstance(field, dict)
    }
    machine_types = {
        "bool", "boolean", "date", "datetime", "decimal", "float", "int",
        "integer", "number", "time", "timestamp", "布尔", "日期", "时间",
        "整数", "数字", "数值", "金额",
    }
    explicit_record_inputs = sum(
        1
        for contract in structured_inputs
        if len(
            [
                field
                for field in contract.get("fields") or []
                if isinstance(field, dict) and str(field.get("name") or "").strip()
            ]
        ) >= 2
        and re.search(
            r"(?:csv|xlsx?|excel|表格|信息表|数据表|流水表|清单|数据源)",
            " ".join(
                str(contract.get(key) or "")
                for key in ("format", "name", "description")
            ),
            re.IGNORECASE,
        )
    )
    typed_processing = len(typed_fields & machine_types) >= 2
    multi_source_processing = explicit_record_inputs >= 2
    if not typed_processing and not multi_source_processing:
        return False
    semantic_text = json.dumps(
        [
            scenario.get("purpose") or "",
            scenario.get("steps") or [],
            scenario.get("outputs") or [],
            scenario.get("businessRules") or [],
        ],
        ensure_ascii=False,
        default=str,
    )
    if not _OFFLINE_RECORD_PROCESSING_RE.search(semantic_text):
        return False
    return bool(
        typed_processing
        or (
            multi_source_processing
            and _MULTI_SOURCE_PROCESSING_RE.search(semantic_text)
        )
    )


def _text_statements(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [
            statement
            for item in value
            for statement in _text_statements(item)
        ]
    if isinstance(value, dict):
        return [
            statement
            for item in value.values()
            for statement in _text_statements(item)
        ]
    return []


def _declares_executable_delivery(scenario: dict[str, Any]) -> bool:
    """Return only positive CLI/script delivery requirements."""

    raw_requirements = scenario.get("scriptRequirements") or []
    requirements = (
        raw_requirements if isinstance(raw_requirements, list) else [raw_requirements]
    )
    for requirement in requirements:
        if isinstance(requirement, dict):
            if requirement.get("required") is False:
                continue
            requirement_text = " ".join(_text_statements(requirement))
            if requirement and not (
                requirement_text
                and _NEGATED_EXECUTABLE_DELIVERY_RE.search(requirement_text)
                and not _EXECUTABLE_DELIVERY_RE.search(requirement_text)
            ):
                return True

    for evidence in scenario.get("capabilityEvidence") or []:
        if not isinstance(evidence, dict) or str(
            evidence.get("kind") or ""
        ).strip() != "script_requirement":
            continue
        quote = str(evidence.get("sourceQuote") or "").strip()
        if not quote or not (
            _NEGATED_EXECUTABLE_DELIVERY_RE.search(quote)
            and not _EXECUTABLE_DELIVERY_RE.search(quote)
        ):
            return True

    statements = [
        statement
        for field in (
            "scriptRequirements",
            "resolvedRequirements",
            "triggers",
            "inputs",
            "steps",
        )
        for statement in _text_statements(scenario.get(field) or [])
    ]
    return any(
        _EXECUTABLE_DELIVERY_RE.search(statement)
        and not _NEGATED_EXECUTABLE_DELIVERY_RE.search(statement)
        for statement in statements
    )


def _is_production_script_path(path: str) -> bool:
    candidate = PurePosixPath(path)
    return bool(
        path.startswith("scripts/")
        and candidate.suffix.lower() in EXECUTABLE_SOURCE_SUFFIXES
        and candidate.name not in _DIAGNOSTIC_SCRIPT_NAMES
    )


_LEGACY_PLATFORM_FIXTURE_RE = re.compile(
    r"^(?:sample-input|invalid)(?:-\d+)?\.(?:csv|json|jsonl|xlsx)$",
    re.IGNORECASE,
)


def _is_business_fixture_path(
    path: str,
    *,
    platform_owned_paths: set[str],
    expected_suffixes: set[str] | None = None,
) -> bool:
    candidate = PurePosixPath(path)
    if not path.startswith("fixtures/") or not candidate.name:
        return False
    if expected_suffixes and candidate.suffix.lower() not in expected_suffixes:
        return False
    if platform_owned_paths:
        return path not in platform_owned_paths
    # Compatibility for workspaces created before Core persisted fixture
    # ownership. Match only the exact historical names; Author fixtures such
    # as sample-input-a.xlsx remain business evidence.
    return _LEGACY_PLATFORM_FIXTURE_RE.fullmatch(candidate.name) is None


def _structured_input_fixture_suffixes(plan: dict[str, Any]) -> set[str]:
    signature = plan.get("behaviorSignature")
    inputs = signature.get("inputs") if isinstance(signature, dict) else []
    suffixes: set[str] = set()
    mappings = {
        "csv": {".csv"},
        "excel": {".xlsx"},
        "json": {".json"},
        "jsonl": {".jsonl"},
        "xlsx": {".xlsx"},
    }
    for item in inputs or []:
        if not isinstance(item, dict):
            continue
        format_name = str(item.get("format") or "").strip().lower()
        suffixes.update(mappings.get(format_name, set()))
    return suffixes


def _requires_business_fixture(plan: dict[str, Any]) -> bool:
    signature = plan.get("behaviorSignature")
    return bool(
        plan.get("scriptsRequired") is True
        and (
            (isinstance(signature, dict) and signature.get("inputs"))
            or plan.get("capabilityEntrypoints")
        )
    )


def behavior_signature(
    scenario: dict[str, Any],
    resolved: dict[str, Any],
) -> dict[str, Any]:
    """Compare behavior across runs without comparing prose or implementation."""

    structured_inputs = scenario_structured_input_contracts(scenario)
    inputs: list[dict[str, Any]] = []
    for contract in structured_inputs:
        inputs.append(
            {
                "format": str(contract.get("format") or "").strip().lower(),
                "fields": sorted(
                    [
                        {
                            "name": str(field.get("name") or "").strip(),
                            "type": str(field.get("type") or "").strip().lower(),
                            "required": field.get("required") is True,
                        }
                        for field in contract.get("fields") or []
                        if isinstance(field, dict)
                        and str(field.get("name") or "").strip()
                    ],
                    key=lambda item: item["name"],
                ),
            }
        )
    if not inputs:
        if any(
            item not in (None, "", [], {})
            for item in scenario.get("inputs") or []
        ):
            inputs = [{"kind": "unstructured"}]
    inputs = list(
        {
            json.dumps(item, ensure_ascii=False, sort_keys=True): item
            for item in inputs
        }.values()
    )
    outputs: list[dict[str, Any]] = []
    for output in scenario.get("outputs") or []:
        if isinstance(output, dict):
            output_format = str(output.get("format") or "").strip().lower()
            fields = sorted(
                str(field.get("name") or "").strip()
                for field in output.get("fields") or []
                if isinstance(field, dict)
                and str(field.get("name") or "").strip()
            )
            outputs.append(
                {"format": output_format, "fields": fields}
                if output_format or fields
                else {"kind": "unstructured"}
            )
        else:
            outputs.append({"kind": "unstructured"})
    outputs = list(
        {
            json.dumps(item, ensure_ascii=False, sort_keys=True): item
            for item in outputs
        }.values()
    )
    requirements = resolved.get("capabilityRequirements") or {}
    executable_capabilities = set(requirements.get("allOf") or []) & (
        RUNTIME_CAPABILITIES
        | {"collection_script", "runtime_screenshot_output"}
    )
    executable_alternative = any(
        set(group) & RUNTIME_CAPABILITIES
        for group in requirements.get("anyOf") or []
        if isinstance(group, list)
    )
    declares_executable = _declares_executable_delivery(scenario)
    requires_offline_processing = _requires_offline_record_processing(
        scenario,
        structured_inputs,
    )
    resolved_package_kind = str(resolved.get("packageKind") or "").strip().lower()
    scripts_required = bool(
        executable_capabilities
        or executable_alternative
        or declares_executable
        or requires_offline_processing
    )
    if resolved_package_kind in {"knowledge", "executable"}:
        scripts_required = resolved_package_kind == "executable"
    inferred_package_kind = (
        "executable"
        if scripts_required
        else "knowledge"
    )
    payload = {
        "schemaVersion": BEHAVIOR_SIGNATURE_SCHEMA_VERSION,
        "scriptsRequired": scripts_required,
        "skillKind": inferred_package_kind,
        "inputs": inputs,
        "outputs": outputs,
        "capabilities": {
            "allOf": sorted(requirements.get("allOf") or []),
            "anyOf": sorted(
                sorted(set(group))
                for group in requirements.get("anyOf") or []
                if isinstance(group, list)
            ),
        },
    }
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return {**payload, "sha256": hashlib.sha256(encoded).hexdigest()}


def normalize_implementation_plan(
    root: Path,
    value: Any,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    source = value if isinstance(value, dict) else {}
    issues: list[dict[str, Any]] = []
    files_set: set[str] = set()
    for index, item in enumerate(source.get("files") or []):
        path = _package_path(item)
        if not path:
            issues.append(
                {"id": "implementation_plan_file_invalid", "index": index}
            )
            continue
        files_set.add(path)
    files = sorted(files_set)
    if "SKILL.md" not in files:
        issues.append(
            {"id": "implementation_plan_skill_entry_missing", "path": "SKILL.md"}
        )
    for path in files:
        if (
            PurePosixPath(path).suffix.lower() in EXECUTABLE_SOURCE_SUFFIXES
            and not path.startswith("scripts/")
        ):
            issues.append(
                {
                    "id": "implementation_plan_executable_path_invalid",
                    "path": path,
                }
            )

    raw_entrypoints = source.get("capabilityEntrypoints")
    entrypoints: dict[str, str] = {}
    if isinstance(raw_entrypoints, dict):
        for capability, raw_path in raw_entrypoints.items():
            name = str(capability or "").strip()
            path = _package_path(raw_path)
            if name not in RUNTIME_CAPABILITIES:
                continue
            if (
                not _is_production_script_path(path)
                or path not in files
            ):
                issues.append(
                    {
                        "id": "implementation_plan_entrypoint_invalid",
                        "capability": name,
                        "path": str(raw_path or ""),
                    }
                )
                continue
            entrypoints[name] = path
    else:
        issues.append({"id": "implementation_plan_entrypoints_invalid"})

    resolved = _resolved_capabilities(root)
    scenario = _read_object(root / "validation" / "scenario_contract.json")
    signature = behavior_signature(scenario, resolved)
    scripts_required = signature["scriptsRequired"] is True
    package_kind = "executable" if scripts_required else "knowledge"
    required = resolved.get("requiredCapabilities") or {}

    def mapped(capability: str) -> bool:
        if capability in entrypoints:
            return True
        return capability == "external_runtime" and bool(entrypoints)

    for capability in sorted(RUNTIME_CAPABILITIES):
        if required.get(capability) is True and not mapped(capability):
            issues.append(
                {
                    "id": "implementation_plan_required_capability_unmapped",
                    "capability": capability,
                }
            )
    requirements = resolved.get("capabilityRequirements") or {}
    for group in requirements.get("anyOf") or []:
        runtime_group = sorted(set(group) & RUNTIME_CAPABILITIES)
        if runtime_group and not any(mapped(item) for item in runtime_group):
            issues.append(
                {
                    "id": "implementation_plan_capability_alternative_unmapped",
                    "requiredAnyOf": runtime_group,
                }
            )
    planned_scripts = [path for path in files if path.startswith("scripts/")]
    planned_production_scripts = [
        path for path in files if _is_production_script_path(path)
    ]
    if not scripts_required and (entrypoints or planned_scripts):
        issues.append({"id": "implementation_plan_scripts_not_required"})
    if scripts_required and not planned_production_scripts:
        issues.append({"id": "implementation_plan_executable_entry_missing"})

    dependencies = list(
        dict.fromkeys(
            str(item).strip()[:300]
            for item in source.get("dependencies") or []
            if str(item or "").strip()
        )
    )
    plan = {
        "schemaVersion": IMPLEMENTATION_PLAN_SCHEMA_VERSION,
        "scriptsRequired": scripts_required,
        "packageKind": package_kind,
        "behaviorSignature": signature,
        "files": files,
        "capabilityEntrypoints": dict(sorted(entrypoints.items())),
        "dependencies": dependencies,
    }
    platform_fixtures = platform_owned_fixture_paths(
        root,
        root / "generated-skill",
    )
    expected_fixture_suffixes = _structured_input_fixture_suffixes(plan)
    if _requires_business_fixture(plan) and not any(
        _is_business_fixture_path(
            path,
            platform_owned_paths=platform_fixtures,
            expected_suffixes=expected_fixture_suffixes,
        )
        for path in files
    ):
        issues.append({"id": "implementation_plan_business_fixture_missing"})
    return plan, issues


def persist_implementation_plan(root: Path, value: Any) -> dict[str, Any]:
    plan, issues = normalize_implementation_plan(root, value)
    if issues:
        return {
            "ok": False,
            "error": "implementation_plan_invalid",
            "issues": issues[:40],
        }
    target = root / IMPLEMENTATION_PLAN_PATH
    temporary = target.with_name(f".{target.name}.tmp")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary.write_text(
        json.dumps(plan, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(target)
    return {"ok": True, "path": IMPLEMENTATION_PLAN_PATH, "plan": plan}


def synthesize_implementation_plan(root: Path) -> dict[str, Any]:
    """Project the final plan from controller direction and actual package files."""

    generated = root / "generated-skill"
    files = sorted(
        path.relative_to(generated).as_posix()
        for path in generated.rglob("*")
        if path.is_file()
        and export_package_path_allowed(path.relative_to(generated).as_posix())
        and path.name not in _DIAGNOSTIC_SCRIPT_NAMES
    ) if generated.is_dir() else []
    production_scripts = [path for path in files if _is_production_script_path(path)]
    documented = [
        path
        for path in documented_cli_entrypoints(generated)
        if path in production_scripts
    ]
    candidates = documented or production_scripts
    sources: dict[str, str] = {}
    for path in candidates:
        try:
            sources[path] = (generated / path).read_text(
                encoding="utf-8",
                errors="replace",
            )
        except OSError:
            sources[path] = ""

    def choose(capability: str) -> str:
        patterns = {
            "browser_runtime": r"\b(?:playwright|selenium|puppeteer)\b",
            "api_runtime": r"\b(?:requests|httpx|aiohttp|urllib\.request)\b",
            "external_runtime": r"\b(?:playwright|selenium|puppeteer|requests|httpx|aiohttp|urllib\.request)\b",
        }
        pattern = patterns.get(capability, "")
        return next(
            (
                path
                for path in candidates
                if pattern and re.search(pattern, sources.get(path, ""), re.IGNORECASE)
            ),
            candidates[0] if candidates else "",
        )

    resolved = _resolved_capabilities(root)
    required = resolved.get("requiredCapabilities") or {}
    entrypoints: dict[str, str] = {}
    for capability in sorted(RUNTIME_CAPABILITIES):
        if required.get(capability) is True and (path := choose(capability)):
            entrypoints[capability] = path
    requirements = resolved.get("capabilityRequirements") or {}
    for group in requirements.get("anyOf") or []:
        runtime_group = sorted(set(group or []) & RUNTIME_CAPABILITIES)
        if not runtime_group or any(item in entrypoints for item in runtime_group):
            continue
        selected = next(
            (
                (capability, choose(capability))
                for capability in runtime_group
                if choose(capability)
            ),
            None,
        )
        if selected is not None:
            entrypoints[selected[0]] = selected[1]

    dependencies: list[str] = []
    requirements_path = generated / "requirements.txt"
    if requirements_path.is_file():
        try:
            dependencies = [
                line.strip()
                for line in requirements_path.read_text(
                    encoding="utf-8",
                    errors="replace",
                ).splitlines()
                if line.strip() and not line.lstrip().startswith("#")
            ][:40]
        except OSError:
            dependencies = []
    return persist_implementation_plan(
        root,
        {
            "files": files,
            "capabilityEntrypoints": entrypoints,
            "dependencies": dependencies,
        },
    )


def load_implementation_plan(root: Path) -> dict[str, Any] | None:
    value = _read_object(root / IMPLEMENTATION_PLAN_PATH)
    if not value:
        return None
    plan, issues = normalize_implementation_plan(root, value)
    if issues or value.get("behaviorSignature") != plan.get("behaviorSignature"):
        return None
    return plan


def missing_required_plan_paths(
    root: Path,
    plan: dict[str, Any],
) -> list[str]:
    """Return missing package entries that the plan marks as operationally required.

    ``files`` is the Author's allowed write scope, not a promise that every planned
    support or test artifact remains in the final package.  The Skill entry, one
    production script for an executable package, and explicit capability entrypoints
    are the final materialization invariants owned by the plan; package validation
    and acceptance own all other requirements.
    """

    required = {
        "SKILL.md",
        *(
            str(path)
            for path in (plan.get("capabilityEntrypoints") or {}).values()
            if str(path or "").strip()
        ),
    }
    generated = root / "generated-skill"
    platform_fixtures = platform_owned_fixture_paths(root, generated)
    expected_fixture_suffixes = _structured_input_fixture_suffixes(plan)
    if plan.get("scriptsRequired") is True:
        production_scripts = [
            str(path)
            for path in plan.get("files") or []
            if _is_production_script_path(str(path))
        ]
        if production_scripts and not any(
            (generated / path).is_file() for path in production_scripts
        ):
            required.add(production_scripts[0])
        if _requires_business_fixture(plan):
            business_fixtures = [
                str(path)
                for path in plan.get("files") or []
                if _is_business_fixture_path(
                    str(path),
                    platform_owned_paths=platform_fixtures,
                    expected_suffixes=expected_fixture_suffixes,
                )
            ]
            if business_fixtures and not any(
                (generated / path).is_file() for path in business_fixtures
            ):
                required.add(business_fixtures[0])
    return sorted(path for path in required if not (generated / path).is_file())


__all__ = [
    "BEHAVIOR_SIGNATURE_SCHEMA_VERSION",
    "IMPLEMENTATION_PLAN_PATH",
    "IMPLEMENTATION_PLAN_SCHEMA_VERSION",
    "RUNTIME_CAPABILITIES",
    "behavior_signature",
    "load_implementation_plan",
    "missing_required_plan_paths",
    "normalize_implementation_plan",
    "persist_implementation_plan",
    "projected_scripts_required",
    "synthesize_implementation_plan",
]
