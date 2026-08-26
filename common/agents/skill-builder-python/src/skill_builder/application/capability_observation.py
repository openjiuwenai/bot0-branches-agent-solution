# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Observe generated capability claims without changing acceptance status."""

from __future__ import annotations

import ast
import json
import re
from pathlib import Path, PurePath
from typing import Any, Iterable

from skill_builder.application.implementation_integrity import (
    analyze_self_check_production_usage,
)
from skill_builder.application.offline_validation import (
    is_platform_replay_plan_source,
)


_CLAIM_PATTERNS = (
    (
        "browser_automation",
        re.compile(r"浏览器自动化|\bplaywright\b|\bselenium\b|\bpuppeteer\b", re.IGNORECASE),
    ),
    (
        "external_data_access",
        re.compile(
            r"(?:自动|实时|在线).{0,24}(?:获取|查询|采集|检索|抓取)"
            r"|(?:官网|外部\s*API|API\s*接口).{0,32}(?:获取|查询|采集|检索|调用)",
            re.IGNORECASE,
        ),
    ),
)
_PLACEHOLDER_PATTERNS = (
    (
        "placeholder_text",
        re.compile(
            r"占位(?:逻辑|实现|状态|数据|结果|代码|分支)?|未实现|"
            r"not[_ -]?implemented|"
            r"placeholder\s+(?:implementation|logic|result|data|status|code)|"
            r"(?:implementation|logic|result|data|status|code)\s+placeholder",
            re.IGNORECASE,
        ),
    ),
    ("simulated_execution", re.compile(r"模拟(?:采集|查询|抓取|执行)|mock(?:ed)?\s+(?:collection|query|execution)", re.IGNORECASE)),
    ("disabled_branch", re.compile(r"\bif\s+False\s*:")),
    (
        "commented_external_call",
        re.compile(
            r"^\s*#\s*(?:await\s+)?(?:page|client|session)\."
            r"(?:goto|get|post|request|fill|click)\b",
            re.IGNORECASE | re.MULTILINE,
        ),
    ),
    (
        "incomplete_runtime_adaptation",
        re.compile(
            r"(?:仅|只).{0,12}(?:框架|骨架)(?:性)?实现"
            r"|(?:页面|接口|响应|结果)(?:结构)?(?:解析)?(?:仍|还)?(?:需要|需)(?:后续)?(?:适配|补充|实现)"
            r"|(?:尚未|未完成)(?:页面|接口|响应|结果|选择器).{0,12}(?:适配|解析|实现)",
            re.IGNORECASE,
        ),
    ),
    (
        "not_run_result",
        re.compile(r"['\"](?:collectionStatus|status)['\"]\s*:\s*['\"]not_run['\"]", re.IGNORECASE),
    ),
    (
        "semantic_empty_success_result",
        re.compile(
            r"['\"]status['\"]\s*:\s*['\"](?:queried|success|completed|ok)['\"]"
            r"(?=[\s\S]{0,1200}['\"](?:code|name|value|result)['\"]\s*:\s*['\"]\s*['\"])[\s\S]{0,1200}"
            r"['\"]note['\"]\s*:\s*['\"][^'\"]*(?:需|待)解析",
            re.IGNORECASE | re.DOTALL,
        ),
    ),
)
_HEURISTIC_PLACEHOLDER_KINDS = frozenset(
    {
        "placeholder_text",
        "simulated_execution",
        "incomplete_runtime_adaptation",
    }
)
_NEGATED_PLACEHOLDER_PREFIX_RE = re.compile(
    r"(?:非|不是|并非|不属于|not\s+(?:a\s+)?|non[-\s]*)$",
    re.IGNORECASE,
)
_DISCLOSURE_PATTERNS = (
    ("unverified", re.compile(r"未验证|无法验证|not_run|\bpartial\b", re.IGNORECASE)),
    (
        "delegated_or_manual",
        re.compile(r"外部\s*Agent|人工(?:执行|查询|补充|复核)|手工(?:执行|查询)|不包含.{0,20}脚本", re.IGNORECASE),
    ),
)
_EXPLICIT_RUNTIME_BOUNDARY_PATTERNS = (
    re.compile(r"外部\s*Agent|由(?:用户|人工|客户经理).{0,20}(?:执行|调用|查询|操作)", re.IGNORECASE),
    re.compile(
        r"(?:本包|本\s*Skill|当前\s*Skill|该\s*Skill|本工具|当前工具).{0,48}?"
        r"(?:不(?:包含|提供|具备|实现|支持|执行|操作|调用|采集|查询|获取|访问)|(?:无此要求|不适用|无需|不涉及)).{0,40}(?:浏览器|API|接口|自动化|系统对接|联网|在线|银行系统)"
        r"|(?:浏览器|API|接口|自动化|系统对接|联网).{0,20}(?:无此要求|不适用|无需|不涉及)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:浏览器|API|接口|自动化|系统对接|联网).{0,40}"
        r"(?:由|需由|必须由).{0,20}(?:人工|客户经理|外部系统|部署环境).{0,30}"
        r"(?:执行|调用|查询|操作|提供|实现)",
        re.IGNORECASE,
    ),
    re.compile(r"(?:人工|手工|外部系统|部署环境).{0,40}(?:能力边界|执行边界|系统边界)", re.IGNORECASE),
    re.compile(r"manual(?:ly)?\s+(?:run|invoke|query)|external\s+agent", re.IGNORECASE),
)
_RUNTIME_CAPABILITY_PATTERNS = (
    (
        "browser_runtime",
        re.compile(r"浏览器(?:运行时|自动化|交互|操作|截图)|\bplaywright\b|\bselenium\b|\bpuppeteer\b", re.IGNORECASE),
    ),
    (
        "api_runtime",
        re.compile(
            r"(?:外部系统|身份核验|征信|风控|审批|CRM).{0,32}(?:API|接口|数据对接)"
            r"|(?:API|接口).{0,32}(?:调用|可达性|响应格式|数据对接)"
            r"|与.{0,24}(?:系统).{0,24}(?:数据对接|接口集成)",
            re.IGNORECASE,
        ),
    ),
    (
        "external_runtime",
        re.compile(r"(?:外部系统|外部网站|目标网站|站点|arXiv).{0,32}(?:可达性|网络阻断|限流)", re.IGNORECASE),
    ),
)
_MARKDOWN_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
_UNVERIFIED_HEADING_RE = re.compile(r"未验证(?:能力|功能)|unverified\s+(?:capabilit|feature)", re.IGNORECASE)
_OFFLINE_DIAGNOSTIC_ENTRYPOINTS = frozenset(
    {"self_check.py", "run_offline_test.py", "offline_test.py"}
)


def _line_number(text: str, start: int) -> int:
    return text.count("\n", 0, start) + 1


def _signals(text: str, patterns: Iterable[tuple[str, re.Pattern[str]]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for kind, pattern in patterns:
        match = pattern.search(text)
        if match is None:
            continue
        result.append(
            {
                "kind": kind,
                "line": _line_number(text, match.start()),
                "text": match.group(0)[:200],
            }
        )
    return result


def _placeholder_text_signals(source: str) -> list[dict[str, Any]]:
    """Detect positive placeholder language without matching explicit negation.

    This remains a heuristic signal.  Executable stub checks below are
    syntax/behavior based and continue to run independently.
    """

    result: list[dict[str, Any]] = []
    for index, (kind, pattern) in enumerate(_PLACEHOLDER_PATTERNS):
        if kind == "semantic_empty_success_result":
            continue
        for match in pattern.finditer(source):
            # Only free-form placeholder language needs negation handling.
            # Structural signals remain deterministic blockers.
            if index == 0:
                prefix = source[max(0, match.start() - 24) : match.start()]
                if _NEGATED_PLACEHOLDER_PREFIX_RE.search(prefix):
                    continue
            result.append(
                {
                    "kind": kind,
                    "line": _line_number(source, match.start()),
                    "text": match.group(0)[:200],
                }
            )
    return result


def _empty_python_body_signals(source: str) -> list[dict[str, Any]]:
    """Detect explicit pass/ellipsis/NotImplemented core stubs."""

    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    result: list[dict[str, Any]] = []
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        body = list(node.body)
        if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) and isinstance(body[0].value.value, str):
            body = body[1:]
        kind = ""
        if len(body) == 1 and isinstance(body[0], ast.Pass):
            kind = "pass_only_function"
        elif (
            len(body) == 1
            and isinstance(body[0], ast.Expr)
            and isinstance(body[0].value, ast.Constant)
            and body[0].value.value is Ellipsis
        ):
            kind = "ellipsis_only_function"
        elif (
            len(body) == 1
            and isinstance(body[0], ast.Raise)
            and isinstance(body[0].exc, (ast.Call, ast.Name))
            and (
                isinstance(body[0].exc, ast.Name)
                and body[0].exc.id == "NotImplementedError"
                or isinstance(body[0].exc, ast.Call)
                and isinstance(body[0].exc.func, ast.Name)
                and body[0].exc.func.id == "NotImplementedError"
            )
        ):
            kind = "not_implemented_function"
        if kind:
            result.append(
                {
                    "kind": kind,
                    "line": int(getattr(node, "lineno", 1)),
                    "text": node.name,
                }
            )
    return result


def _semantic_empty_success_result_signals(source: str) -> list[dict[str, Any]]:
    """Detect a success status paired with empty business fields and an
    explicit unfinished-language marker."""

    result: list[dict[str, Any]] = []
    pattern = _PLACEHOLDER_PATTERNS[-1][1]
    for match in pattern.finditer(source):
        result.append(
            {
                "kind": "semantic_empty_success_result",
                "line": _line_number(source, match.start()),
                "text": match.group(0)[:300],
            }
        )
    return result


def _fixed_simulated_success_result_signals(source: str) -> list[dict[str, Any]]:
    """Block simulated functions that return a fixed successful business value."""

    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    simulated_pattern = dict(_PLACEHOLDER_PATTERNS)["simulated_execution"]
    success_states = {"success", "queried", "completed", "ok"}
    control_keys = {"status", "reason", "message", "error", "errors", "note", "notes"}
    result: list[dict[str, Any]] = []
    for function in (
        node
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    ):
        segment = ast.get_source_segment(source, function) or ""
        if not simulated_pattern.search(segment):
            continue
        for returned in (node for node in ast.walk(function) if isinstance(node, ast.Return)):
            value = returned.value
            if not isinstance(value, ast.Dict):
                continue
            fields = {
                str(key.value): item
                for key, item in zip(value.keys, value.values)
                if isinstance(key, ast.Constant) and isinstance(key.value, str)
            }
            status = fields.get("status")
            if not (
                isinstance(status, ast.Constant)
                and str(status.value or "").strip().lower() in success_states
            ):
                continue
            fixed_business_values = []
            for key, item in fields.items():
                if key in control_keys:
                    continue
                try:
                    literal = ast.literal_eval(item)
                except (ValueError, TypeError):
                    continue
                if literal not in (None, "", [], {}):
                    fixed_business_values.append(key)
            if fixed_business_values:
                result.append(
                    {
                        "kind": "fixed_simulated_success_result",
                        "line": int(getattr(returned, "lineno", getattr(function, "lineno", 1))),
                        "text": function.name,
                        "fields": sorted(fixed_business_values),
                    }
                )
                break
    return result


def _external_runtime_blocked_stub_signals(source: str) -> list[dict[str, Any]]:
    """Detect an external-capability function that never calls its runtime.

    A real implementation may return ``blocked`` on an exception, but it must
    contain at least one concrete browser/API operation on the same function
    path.  This catches imports followed by an unconditional blocked result,
    which otherwise looks like an implemented browser capability.
    """

    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []

    function_nodes = {
        node.name: node
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    }
    module_aliases: dict[str, str] = {}
    symbol_aliases: dict[str, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                bound = alias.asname or alias.name.split(".", 1)[0]
                # ``import urllib.request`` binds ``urllib`` while the source
                # keeps the full qualified name. Multiple urllib submodule
                # imports must therefore share the root instead of replacing
                # each other in the alias table.
                module_aliases[bound] = alias.name if alias.asname else bound
        elif isinstance(node, ast.ImportFrom) and node.module:
            for alias in node.names:
                if alias.name == "*":
                    continue
                symbol_aliases[alias.asname or alias.name] = f"{node.module}.{alias.name}"

    def raw_qualified_name(value: ast.AST) -> str:
        if isinstance(value, ast.Name):
            return value.id
        if isinstance(value, ast.Attribute):
            prefix = raw_qualified_name(value.value)
            return f"{prefix}.{value.attr}" if prefix else value.attr
        return ""

    def qualified_name(value: ast.AST) -> str:
        raw = raw_qualified_name(value)
        if not raw:
            return ""
        first, separator, remainder = raw.partition(".")
        if first in symbol_aliases and not separator:
            return symbol_aliases[first]
        if first in module_aliases:
            module = module_aliases[first]
            if raw == module or raw.startswith(f"{module}."):
                return raw
            return f"{module}.{remainder}" if separator else module
        return raw

    def call_capabilities(node: ast.AST) -> tuple[bool, bool]:
        browser = False
        api = False
        for call in (item for item in ast.walk(node) if isinstance(item, ast.Call)):
            name = qualified_name(call.func).lower()
            if re.search(
                r"(?:chromium|firefox|webkit)\.launch$|webdriver\.(?:chrome|firefox|edge)$|"
                r"(?:page|context|browser)\.(?:goto|new_page|locator|fill|click|screenshot)$|"
                r"puppeteer\.launch$",
                name,
            ):
                browser = True
            if re.search(
                r"(?:requests|httpx)\.(?:get|post|put|patch|delete|request)$|"
                r"urllib\.request\.urlopen$|aiohttp\.clientsession$",
                name,
            ):
                api = True
        return browser, api

    function_runtime: dict[str, tuple[bool, bool]] = {}
    function_calls: dict[str, set[str]] = {}
    for name, node in function_nodes.items():
        function_runtime[name] = call_capabilities(node)
        function_calls[name] = {
            call.func.id
            for call in ast.walk(node)
            if isinstance(call, ast.Call)
            and isinstance(call.func, ast.Name)
            and call.func.id in function_nodes
            and call.func.id != name
        }

    def reaches_runtime(name: str, runtime_index: int, seen: set[str] | None = None) -> bool:
        visited = set(seen or ())
        if name in visited:
            return False
        visited.add(name)
        runtime = function_runtime.get(name, (False, False))
        if runtime[runtime_index]:
            return True
        return any(
            reaches_runtime(callee, runtime_index, visited)
            for callee in function_calls.get(name, set())
        )

    def reaches_function(
        caller: str,
        target: str,
        seen: set[str] | None = None,
    ) -> bool:
        visited = set(seen or ())
        if caller in visited:
            return False
        visited.add(caller)
        callees = function_calls.get(caller, set())
        return target in callees or any(
            reaches_function(callee, target, visited)
            for callee in callees
        )

    result: list[dict[str, Any]] = []
    for node in function_nodes.values():
        segment = ast.get_source_segment(source, node) or ""
        lowered = segment.lower()
        browser_runtime = bool(re.search(r"playwright|selenium|puppeteer", lowered))
        api_runtime = bool(re.search(r"requests|httpx|aiohttp|urllib", lowered))
        if not browser_runtime and not api_runtime:
            continue
        has_blocked_return = bool(re.search(r"['\"]status['\"]\s*:\s*['\"]blocked['\"]", segment, re.IGNORECASE))
        has_browser_call = reaches_runtime(node.name, 0)
        has_api_call = reaches_runtime(node.name, 1)
        has_runtime_call = (browser_runtime and has_browser_call) or (api_runtime and has_api_call)
        runtime_index = 0 if browser_runtime else 1
        used_by_runtime_flow = any(
            caller != node.name
            and reaches_function(caller, node.name)
            and reaches_runtime(caller, runtime_index)
            for caller in function_nodes
        )
        if has_blocked_return and not has_runtime_call and not used_by_runtime_flow:
            result.append(
                {
                    "kind": "external_runtime_blocked_stub",
                    "line": int(getattr(node, "lineno", 1)),
                    "text": node.name,
                }
            )
    return result


def _explicit_runtime_boundary(text: str) -> bool:
    return any(pattern.search(text) for pattern in _EXPLICIT_RUNTIME_BOUNDARY_PATTERNS)


def _runtime_claim_signals(text: str) -> list[dict[str, Any]]:
    """Return positive runtime claims while ignoring same-line boundaries."""

    lines = text.splitlines()
    result: list[dict[str, Any]] = []
    for kind, pattern in _CLAIM_PATTERNS:
        for match in pattern.finditer(text):
            line = _line_number(text, match.start())
            context = lines[line - 1].strip() if 0 < line <= len(lines) else match.group(0)
            if _explicit_runtime_boundary(context):
                continue
            result.append(
                {
                    "kind": kind,
                    "line": line,
                    "text": match.group(0)[:200],
                }
            )
            break
    return result


def _unverified_runtime_declarations(text: str, *, source: str) -> list[dict[str, Any]]:
    """Extract runtime-like bullets only from an explicit unverified section."""

    lines = text.splitlines()
    active_level: int | None = None
    result: list[dict[str, Any]] = []
    for index, line in enumerate(lines, start=1):
        heading = _MARKDOWN_HEADING_RE.match(line.strip())
        if heading:
            level = len(heading.group(1))
            title = heading.group(2).strip()
            if active_level is not None and level <= active_level:
                active_level = None
            if _UNVERIFIED_HEADING_RE.search(title):
                active_level = level
            continue
        if active_level is None:
            continue
        stripped = re.sub(r"^\s*(?:[-*+]\s+|\d+[.)]\s+)", "", line).strip()
        if not stripped:
            continue
        for capability, pattern in _RUNTIME_CAPABILITY_PATTERNS:
            if not pattern.search(stripped):
                continue
            result.append(
                {
                    "source": source,
                    "capability": capability,
                    "line": index,
                    "text": stripped[:500],
                    "explicitBoundary": _explicit_runtime_boundary(stripped),
                }
            )
            break
    return result


def _agent_runtime_declarations(agent_self_check: dict[str, Any] | None) -> list[dict[str, Any]]:
    if not isinstance(agent_self_check, dict):
        return []
    result: list[dict[str, Any]] = []
    for index, value in enumerate(agent_self_check.get("unverified") or [], start=1):
        text = str(value or "").strip()
        if not text:
            continue
        for capability, pattern in _RUNTIME_CAPABILITY_PATTERNS:
            if pattern.search(text):
                result.append(
                    {
                        "source": "agent_self_check",
                        "capability": capability,
                        "line": index,
                        "text": text[:500],
                        "explicitBoundary": _explicit_runtime_boundary(text),
                    }
                )
                break
    return result


def _capability_any_of(value: Any) -> list[list[str]]:
    if not isinstance(value, dict):
        return []
    result: list[list[str]] = []
    for raw_group in value.get("anyOf") or []:
        if not isinstance(raw_group, list):
            continue
        group = sorted({str(item).strip() for item in raw_group if str(item).strip()})
        if group and group not in result:
            result.append(group)
    return result


def _scenario_capability_contract(
    generated: Path,
) -> tuple[dict[str, bool], list[list[str]]]:
    validation = generated.parent / "validation"
    target = validation / "scenario_contract.json"
    try:
        payload = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        payload = {}
    raw = payload.get("requiredCapabilities") if isinstance(payload, dict) else None
    result = (
        {str(key): bool(value) for key, value in raw.items() if isinstance(value, bool)}
        if isinstance(raw, dict)
        else {}
    )
    any_of = _capability_any_of(
        payload.get("capabilityRequirements") if isinstance(payload, dict) else None
    )
    try:
        manifest = json.loads((validation / "artifact_manifest.json").read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        manifest = {}
    resolved = manifest.get("resolvedCapabilityContract") if isinstance(manifest, dict) else None
    resolved_hash = str((resolved or {}).get("scenarioContractHash") or "").strip()
    scenario_hash = str((manifest.get("scenarioContract") or {}).get("semanticHash") or "").strip()
    if isinstance(resolved, dict) and resolved_hash and resolved_hash == scenario_hash:
        for key, value in (resolved.get("requiredCapabilities") or {}).items():
            if isinstance(value, bool):
                result[str(key)] = value
        if isinstance(resolved.get("capabilityRequirements"), dict):
            any_of = _capability_any_of(resolved.get("capabilityRequirements"))
    return result, any_of


def _scenario_required_capabilities(generated: Path) -> dict[str, bool]:
    return _scenario_capability_contract(generated)[0]


def _scenario_requires_runtime(capability: str, required: dict[str, bool]) -> bool:
    tokens = {
        str(key).strip().lower()
        for key, enabled in required.items()
        if enabled
    }
    if capability == "browser_runtime":
        return any(
            marker in token
            for token in tokens
            for marker in ("browser", "playwright", "screenshot", "recording")
        )
    if capability == "api_runtime":
        return any(
            marker in token
            for token in tokens
            for marker in ("api", "http")
        )
    if capability == "external_runtime":
        return any(
            marker in token
            for token in tokens
            for marker in ("network", "external_runtime", "external_system")
        )
    return False


def _runtime_requirement_sources(capability: str, required: dict[str, bool]) -> list[str]:
    return sorted(
        str(key)
        for key, enabled in required.items()
        if enabled and _scenario_requires_runtime(capability, {str(key): True})
    )


def _missing_required_runtime_capabilities(
    required: dict[str, bool],
    runtime_entrypoints: dict[str, list[str]],
    *,
    any_of: list[list[str]] | None = None,
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for capability in ("browser_runtime", "api_runtime", "external_runtime"):
        sources = _runtime_requirement_sources(capability, required)
        if not sources or runtime_entrypoints.get(capability):
            continue
        result.append(
            {
                "capability": capability,
                "requiredBy": sources,
                "implementedEntrypoints": [],
            }
        )
    runtime_names = {"browser_runtime", "api_runtime", "external_runtime"}
    for group in any_of or []:
        runtime_group = sorted(set(group) & runtime_names)
        if not runtime_group or any(
            runtime_entrypoints.get(capability) for capability in runtime_group
        ):
            continue
        result.append(
            {
                "capability": "runtime_alternative",
                "requiredAnyOf": runtime_group,
                "implementedEntrypoints": {
                    capability: list(runtime_entrypoints.get(capability) or [])
                    for capability in runtime_group
                },
            }
        )
    return result


def _runtime_entrypoint_capabilities(
    generated: Path,
    scripts: list[Path],
    external_runtime_scripts: set[str],
) -> dict[str, list[str]]:
    result = {"browser_runtime": [], "api_runtime": [], "external_runtime": []}
    for script in scripts:
        if script.as_posix() not in external_runtime_scripts or script.name in _OFFLINE_DIAGNOSTIC_ENTRYPOINTS:
            continue
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        relative = script.relative_to(generated).as_posix()
        capabilities = python_runtime_call_capabilities(source)
        if not capabilities:
            continue
        result["external_runtime"].append(relative)
        if "browser_runtime" in capabilities:
            result["browser_runtime"].append(relative)
        if "api_runtime" in capabilities:
            result["api_runtime"].append(relative)
    return result


def python_runtime_call_capabilities(source: str) -> set[str]:
    """Return external capabilities proven by imports and executable calls."""

    try:
        tree = ast.parse(source)
    except SyntaxError:
        return set()
    imported_modules: set[str] = set()
    module_aliases: dict[str, str] = {}
    symbol_aliases: dict[str, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                imported_modules.add(alias.name.lower())
                module_aliases[alias.asname or alias.name.split(".", 1)[0]] = (
                    alias.name
                )
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported_modules.add(node.module.lower())
            for alias in node.names:
                if alias.name != "*":
                    symbol_aliases[alias.asname or alias.name] = (
                        f"{node.module}.{alias.name}"
                    )

    def qualified_name(value: ast.AST) -> str:
        parts: list[str] = []
        current = value
        while isinstance(current, ast.Attribute):
            parts.append(current.attr)
            current = current.value
        if isinstance(current, ast.Name):
            parts.append(current.id)
        raw = ".".join(reversed(parts))
        if not raw:
            return ""
        first, separator, remainder = raw.partition(".")
        if first in symbol_aliases and not separator:
            return symbol_aliases[first]
        if first in module_aliases:
            module = module_aliases[first]
            if raw == module or raw.startswith(f"{module}."):
                return raw
            return f"{module}.{remainder}" if separator else module
        return raw

    browser_dependency = any(
        module == name or module.startswith(f"{name}.")
        for module in imported_modules
        for name in ("playwright", "selenium", "puppeteer")
    )
    api_dependency = any(
        module == name or module.startswith(f"{name}.")
        for module in imported_modules
        for name in ("requests", "httpx", "aiohttp", "urllib.request")
    )
    browser_call = False
    api_call = False
    for call in (node for node in ast.walk(tree) if isinstance(node, ast.Call)):
        name = qualified_name(call.func).lower()
        if re.search(
            r"(?:chromium|firefox|webkit)\.launch$|"
            r"webdriver\.(?:chrome|firefox|edge)$|"
            r"(?:page|context|browser)\."
            r"(?:goto|new_page|locator|fill|click|screenshot)$|"
            r"puppeteer\.launch$",
            name,
        ):
            browser_call = True
        if re.search(
            r"(?:requests|httpx)\.(?:get|post|put|patch|delete|request)$|"
            r"urllib\.request\.urlopen$|aiohttp\.clientsession$|"
            r"(?:client|session)\.(?:get|post|put|patch|delete|request)$",
            name,
        ):
            api_call = True
    result: set[str] = set()
    if browser_dependency and browser_call:
        result.add("browser_runtime")
    if api_dependency and api_call:
        result.add("api_runtime")
    return result


def _is_main_guard(node: ast.If) -> bool:
    try:
        return bool(
            isinstance(node.test, ast.Compare)
            and isinstance(node.test.left, ast.Name)
            and node.test.left.id == "__name__"
            and len(node.test.ops) == 1
            and isinstance(node.test.ops[0], ast.Eq)
            and len(node.test.comparators) == 1
            and isinstance(node.test.comparators[0], ast.Constant)
            and node.test.comparators[0].value == "__main__"
        )
    except (AttributeError, TypeError):
        return False


def _call_name(node: ast.Call) -> str:
    parts: list[str] = []
    current: ast.AST | None = node.func
    while isinstance(current, ast.Attribute):
        parts.append(current.attr)
        current = current.value
    if isinstance(current, ast.Name):
        parts.append(current.id)
    return ".".join(reversed(parts))


def _offline_self_check_noop_signal(
    script: Path,
    source: str,
    *,
    production_observations: list[str] | None = None,
) -> dict[str, Any] | None:
    """Reject diagnostic entrypoints that can succeed without checking inputs."""

    if script.name not in _OFFLINE_DIAGNOSTIC_ENTRYPOINTS:
        return None
    if is_platform_replay_plan_source(source):
        return None
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return None

    observations: list[str] = list(production_observations or [])
    decisions: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Assert):
            decisions.append("assert")
        elif isinstance(node, ast.If) and not _is_main_guard(node):
            decisions.append("conditional")
        elif isinstance(node, ast.Raise):
            decisions.append("raise")
        elif isinstance(node, ast.Call):
            name = _call_name(node).lower()
            leaf = name.rsplit(".", 1)[-1]
            if name.endswith((".read_text", ".read_bytes", ".exists", ".is_file", ".is_dir", ".open")):
                observations.append(name)
            elif name in {
                "open",
                "json.load",
                "csv.reader",
                "csv.dictreader",
                "runpy.run_path",
                "pandas.read_csv",
                "pandas.read_json",
                "openpyxl.load_workbook",
                "yaml.safe_load",
                "tomllib.load",
            }:
                observations.append(name)
            elif name.startswith("subprocess.") and name.split(".")[-1] in {
                "run",
                "call",
                "check_call",
                "check_output",
                "popen",
            }:
                observations.append(name)
                if any(keyword.arg == "check" and isinstance(keyword.value, ast.Constant) and keyword.value.value is True for keyword in node.keywords):
                    decisions.append("subprocess_check")
            if leaf.startswith("assert") or name in {
                "pytest.fail",
                "pytest.raises",
                "pytest.main",
                "unittest.main",
            }:
                decisions.append("test_framework")
            elif name == "sys.exit" and (
                node.args
                and not (
                    isinstance(node.args[0], ast.Constant)
                    and node.args[0].value in {None, 0}
                )
            ):
                decisions.append("exit_status")

    if observations and decisions:
        return None
    return {
        "path": script.name,
        "kind": "no_op_offline_self_check",
        "line": 1,
        "text": (
            "显式离线自检入口没有同时观察包内输入/脚本并执行断言或失败分支；"
            "仅打印成功、写固定成功 JSON 或返回 0 不能作为自检。"
        ),
        "observations": sorted(set(observations))[:20],
        "decisions": sorted(set(decisions))[:20],
    }


def observe_capability_relationships(
    generated: Path,
    *,
    scripts: list[Path],
    external_runtime_scripts: set[str],
    agent_self_check: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Return capability evidence and deterministic declaration guards."""

    skill_entry = generated / "SKILL.md"
    try:
        skill_text = skill_entry.read_text(encoding="utf-8", errors="replace")
    except OSError:
        skill_text = ""

    claim_signals = _runtime_claim_signals(skill_text)
    disclosure_signals = _signals(skill_text, _DISCLOSURE_PATTERNS)
    entrypoints = [path.relative_to(generated).as_posix() for path in scripts]
    external_entrypoints = [
        path.relative_to(generated).as_posix()
        for path in scripts
        if path.as_posix() in external_runtime_scripts
        and path.name not in _OFFLINE_DIAGNOSTIC_ENTRYPOINTS
    ]
    placeholder_signals: list[dict[str, Any]] = []
    no_op_self_check_signals: list[dict[str, Any]] = []
    for script in scripts:
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for signal in _placeholder_text_signals(source):
            placeholder_signals.append(
                {
                    "path": script.relative_to(generated).as_posix(),
                    **signal,
                }
            )
        for signal in _empty_python_body_signals(source):
            placeholder_signals.append(
                {
                    "path": script.relative_to(generated).as_posix(),
                    **signal,
                }
            )
        for signal in _semantic_empty_success_result_signals(source):
            placeholder_signals.append(
                {
                    "path": script.relative_to(generated).as_posix(),
                    **signal,
                }
            )
        for signal in _fixed_simulated_success_result_signals(source):
            placeholder_signals.append(
                {
                    "path": script.relative_to(generated).as_posix(),
                    **signal,
                }
            )
        for signal in _external_runtime_blocked_stub_signals(source):
            placeholder_signals.append(
                {
                    "path": script.relative_to(generated).as_posix(),
                    **signal,
                }
            )
        production_usage = analyze_self_check_production_usage(generated, script)
        no_op_signal = _offline_self_check_noop_signal(
            script,
            source,
            production_observations=list(production_usage["observations"]),
        )
        if no_op_signal is not None:
            no_op_self_check_signals.append(
                {
                    **no_op_signal,
                    "path": script.relative_to(generated).as_posix(),
                }
            )

    blocking_placeholder_signals = [
        signal
        for signal in placeholder_signals
        if PurePath(str(signal.get("path") or "")).name not in _OFFLINE_DIAGNOSTIC_ENTRYPOINTS
        and str(signal.get("kind") or "") not in _HEURISTIC_PLACEHOLDER_KINDS
    ]

    runtime_entrypoints = _runtime_entrypoint_capabilities(
        generated,
        scripts,
        external_runtime_scripts,
    )
    required_capabilities, required_capability_any_of = _scenario_capability_contract(
        generated
    )
    missing_required_runtime_capabilities = _missing_required_runtime_capabilities(
        required_capabilities,
        runtime_entrypoints,
        any_of=required_capability_any_of,
    )
    runtime_declarations = [
        *_unverified_runtime_declarations(skill_text, source="SKILL.md"),
        *_agent_runtime_declarations(agent_self_check),
    ]
    for declaration in runtime_declarations:
        declaration["scenarioRequired"] = _scenario_requires_runtime(
            str(declaration.get("capability") or ""),
            required_capabilities,
        )
        declaration["implementedEntrypoints"] = list(
            runtime_entrypoints.get(str(declaration.get("capability") or "")) or []
        )
    unbacked_claim_signals = (
        []
        if external_entrypoints or _explicit_runtime_boundary(skill_text)
        else claim_signals
    )
    unbacked_runtime_declarations = [
        item
        for item in runtime_declarations
        if item.get("source") != "agent_self_check"
        and not item.get("explicitBoundary")
        and not runtime_entrypoints.get(str(item.get("capability") or ""))
    ]
    agent_runtime_unverified = [
        item
        for item in runtime_declarations
        if item.get("source") == "agent_self_check"
        and runtime_entrypoints.get(str(item.get("capability") or ""))
    ]
    agent_status = str((agent_self_check or {}).get("status") or "").strip().lower()
    agent_status_inconsistencies = (
        agent_runtime_unverified if agent_status == "pass" else []
    )

    observations: list[dict[str, str]] = []
    if claim_signals and not external_entrypoints:
        if _explicit_runtime_boundary(skill_text):
            observations.append(
                {
                    "id": "instruction_only_external_capability",
                    "message": "SKILL.md 将外部能力明确限定为人工或外部 Agent 边界。",
                }
            )
        else:
            observations.append(
                {
                    "id": "external_claim_without_detected_runtime_entry",
                    "message": "SKILL.md 出现外部采集/自动化能力信号，但未检测到依赖网络或浏览器的 Python 入口。",
                }
            )
    if placeholder_signals:
        observations.append(
            {
                "id": "placeholder_implementation_signal",
                "message": "脚本中检测到占位、模拟、未完成运行时适配、禁用分支或 not_run 输出信号。",
            }
        )
    if disclosure_signals:
        observations.append(
            {
                "id": "capability_boundary_disclosed",
                "message": "SKILL.md 已披露未验证、人工或外部 Agent 执行边界。",
            }
        )
    if unbacked_runtime_declarations:
        observations.append(
            {
                "id": "unbacked_runtime_capability_declaration",
                "message": "未验证能力列表声明了包内未实现、且未明确标为人工/外部边界的浏览器或 API 能力。",
            }
        )
    if missing_required_runtime_capabilities:
        observations.append(
            {
                "id": "required_runtime_capability_missing",
                "message": "Scenario/HITL 已确认运行能力，但包内没有对应可执行入口。",
            }
        )
    if unbacked_claim_signals:
        observations.append(
            {
                "id": "implemented_capability_without_runtime_entry",
                "message": "SKILL.md 将外部采集或浏览器能力描述为已实现，但包内没有对应运行入口。",
            }
        )
    if no_op_self_check_signals:
        observations.append(
            {
                "id": "no_op_offline_self_check",
                "message": "显式离线自检入口缺少可观察输入和确定性失败条件。",
            }
        )
    if agent_status_inconsistencies:
        observations.append(
            {
                "id": "agent_self_check_runtime_status_inconsistent",
                "message": "Agent 将自验证标为 pass，但实际外部运行入口仍列为未验证。",
            }
        )

    return {
        "schemaVersion": "skill-builder-capability-observation/v1",
        "mode": "acceptance_guard",
        "affectsAcceptance": bool(
            blocking_placeholder_signals
            or no_op_self_check_signals
            or missing_required_runtime_capabilities
            or unbacked_claim_signals
            or unbacked_runtime_declarations
            or agent_status_inconsistencies
        ),
        "claimSignals": claim_signals,
        "entrypoints": entrypoints,
        "externalRuntimeEntrypoints": external_entrypoints,
        "runtimeEntrypointsByCapability": runtime_entrypoints,
        "scenarioRequiredCapabilities": required_capabilities,
        "scenarioRequiredCapabilityAnyOf": required_capability_any_of,
        "missingRequiredRuntimeCapabilities": missing_required_runtime_capabilities,
        "placeholderSignals": placeholder_signals,
        "heuristicPlaceholderSignals": [
            signal
            for signal in placeholder_signals
            if str(signal.get("kind") or "") in _HEURISTIC_PLACEHOLDER_KINDS
        ],
        "blockingPlaceholderSignals": blocking_placeholder_signals,
        "noOpSelfCheckSignals": no_op_self_check_signals,
        "runtimeDeclarations": runtime_declarations,
        "unbackedClaimSignals": unbacked_claim_signals,
        "unbackedRuntimeDeclarations": unbacked_runtime_declarations,
        "agentStatusInconsistencies": agent_status_inconsistencies,
        "disclosureSignals": disclosure_signals,
        "observations": observations,
    }


__all__ = ["observe_capability_relationships"]
