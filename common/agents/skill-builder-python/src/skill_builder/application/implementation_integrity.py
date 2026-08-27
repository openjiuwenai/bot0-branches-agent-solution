# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Deterministic integrity checks for generated implementation packages."""

from __future__ import annotations

import ast
import re
from pathlib import Path, PurePath
from typing import Any
from urllib.parse import urlsplit

from skill_builder.application.offline_validation import (
    is_platform_replay_plan_source,
)
from skill_builder.domain.candidate_contract import EXPORT_ALLOWED_ROOT_FILES


_MARKDOWN_LINK_RE = re.compile(r"\]\((?!https?://|mailto:)([^)#]+)")
_INLINE_CODE_RE = re.compile(r"`([^`\n]+)`")
_PACKAGE_PATH_RE = re.compile(
    r"(?<![\w.-])(?:scripts|references|fixtures|assets)/[A-Za-z0-9_./@+-]+"
    r"|(?<![\w.-])requirements(?:-[A-Za-z0-9_.-]+)?\.txt"
)
_DIAGNOSTIC_NAMES = frozenset(
    {"self_check.py", "run_offline_test.py", "offline_test.py"}
)
_SUBPROCESS_CALLS = frozenset(
    {
        "subprocess.call",
        "subprocess.check_call",
        "subprocess.check_output",
        "subprocess.Popen",
        "subprocess.run",
        "runpy.run_module",
        "runpy.run_path",
    }
)
_PORTABLE_DOCUMENT_SUFFIXES = frozenset(
    {".md", ".markdown", ".txt", ".yaml", ".yml"}
)
_SCRIPT_PATH_RE = re.compile(
    r"(?<![\w./-])(?:\./)?(scripts/[A-Za-z0-9_./-]+\.py)(?![\w./-])"
)
_CLI_COMMAND_RE = re.compile(
    r"(?:\b(?:python(?:3(?:\.\d+)*)?|pypy3?)\b(?:\s+-\S+)*\s+|\./)"
    r"(scripts/[A-Za-z0-9_./-]+\.py)(?![\w./-])",
    re.IGNORECASE,
)
_CLI_ARGUMENT_RE = re.compile(
    r"(?<![\w./-])(?:\./)?(scripts/[A-Za-z0-9_./-]+\.py)\s+--[A-Za-z0-9_-]+"
)
_PRIMARY_ENTRYPOINT_RE = re.compile(
    r"(?:主入口|主要入口|完整(?:流程|流水线)?入口|"
    r"(?:main|primary|workflow|pipeline)\s+entrypoint)",
    re.IGNORECASE,
)
_PACKAGE_ROOT_NAMES = frozenset({"scripts", "references", "fixtures", "assets"})
_URL_LITERAL_RE = re.compile(r"https?://[^\s'\"<>]+", re.IGNORECASE)
_RESERVED_EXAMPLE_HOSTS = frozenset({"example.com", "example.org", "example.net"})
_PLATFORM_ONLY_REFERENCE_RE = re.compile(
    r"(?<![\w.-])(?:"
    r"(?:inputs|validation|workspace|playwright)/[\w./@+-]+"
    r"|\.skill-builder/[\w./@+-]+"
    r"|generated-skill/[\w./@+-]+"
    r")"
)


def _strip_current_dir_prefix(value: str) -> str:
    normalized = value.strip()
    while normalized.startswith("./"):
        normalized = normalized[2:]
    return normalized


def missing_package_references(generated: Path) -> list[dict[str, Any]]:
    """Find missing package-local references in links and inline code.

    Resource tables conventionally use backticks instead of Markdown links.
    Treat only explicit package-root prefixes and requirements files as paths;
    free-form code examples and output placeholders remain outside this gate.
    """

    documents = sorted(
        path
        for path in generated.rglob("*")
        if path.is_file() and path.suffix.lower() in _PORTABLE_DOCUMENT_SUFFIXES
    )
    result: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for document in documents:
        try:
            text = document.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        candidates: list[tuple[str, int, str]] = []
        for match in _MARKDOWN_LINK_RE.finditer(text):
            candidates.append(
                (_strip_current_dir_prefix(match.group(1)), match.start(1), "markdown_link")
            )
        for code in _INLINE_CODE_RE.finditer(text):
            for match in _PACKAGE_PATH_RE.finditer(code.group(1)):
                candidates.append(
                    (
                        _strip_current_dir_prefix(match.group(0)),
                        code.start(1) + match.start(),
                        "inline_code",
                    )
                )
        document_relative = document.relative_to(generated).as_posix()
        for raw, offset, source in candidates:
            normalized = raw.split("#", 1)[0].rstrip(".,:;)]}")
            key = (document_relative, normalized)
            invalid_reference = not normalized or "{" in normalized or "}" in normalized or key in seen
            if invalid_reference:
                continue
            seen.add(key)
            pure = PurePath(normalized)
            base = (
                generated
                if (
                    pure.parts
                    and (
                        pure.parts[0] in _PACKAGE_ROOT_NAMES
                        or normalized in EXPORT_ALLOWED_ROOT_FILES
                    )
                )
                else document.parent
            )
            try:
                target = (base / normalized).resolve()
                target.relative_to(generated.resolve())
                exists_in_package = target.exists()
            except (OSError, ValueError):
                exists_in_package = False
            if not exists_in_package:
                result.append(
                    {
                        "document": document_relative,
                        "path": normalized,
                        "line": text.count("\n", 0, offset) + 1,
                        "source": source,
                    }
                )
    return result


def platform_only_document_reference_source_signals(
    source: str,
    *,
    path: str,
) -> list[dict[str, Any]]:
    """Find workspace-only paths in one exported portable document."""

    return [
        {
            "path": path,
            "reference": match.group(0).rstrip(".,:;)"),
            "line": source.count("\n", 0, match.start()) + 1,
        }
        for match in _PLATFORM_ONLY_REFERENCE_RE.finditer(source)
    ]


def platform_only_document_references(generated: Path) -> list[dict[str, Any]]:
    """Find exported documents that still point at workspace-only paths."""

    result: list[dict[str, Any]] = []
    for document in sorted(path for path in generated.rglob("*") if path.is_file()):
        if document.suffix.lower() not in _PORTABLE_DOCUMENT_SUFFIXES:
            continue
        try:
            source = document.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        result.extend(
            platform_only_document_reference_source_signals(
                source,
                path=document.relative_to(generated).as_posix(),
            )
        )
    return result


def reserved_example_endpoint_signals(
    scripts: list[Path],
    generated: Path,
) -> list[dict[str, Any]]:
    """Find reserved example domains embedded in executable Python code."""

    result: list[dict[str, Any]] = []
    for script in scripts:
        if script.name in _DIAGNOSTIC_NAMES or script.name.startswith("test_"):
            continue
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
            tree = ast.parse(source)
        except (OSError, SyntaxError):
            continue
        docstrings = set()
        containers = (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)
        for node in ast.walk(tree):
            if not isinstance(node, containers) or not node.body:
                continue
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant):
                if isinstance(first.value.value, str):
                    docstrings.add(id(first.value))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Constant) or not isinstance(node.value, str):
                continue
            if id(node) in docstrings:
                continue
            for match in _URL_LITERAL_RE.finditer(node.value):
                url = match.group(0).rstrip(".,:;)]}")
                host = str(urlsplit(url).hostname or "").lower()
                if not any(host == reserved or host.endswith(f".{reserved}") for reserved in _RESERVED_EXAMPLE_HOSTS):
                    continue
                result.append(
                    {
                        "path": script.relative_to(generated).as_posix(),
                        "line": int(getattr(node, "lineno", 1)),
                        "host": host,
                        "url": url[:500],
                    }
                )
    return result


def empty_conditional_branch_source_signals(
    source: str,
    *,
    path: str,
) -> list[dict[str, Any]]:
    """Detect pass-only decision branches in one already decoded source file."""

    try:
        tree = ast.parse(source, filename=path)
    except SyntaxError:
        return []
    result: list[dict[str, Any]] = []
    for node in ast.walk(tree):
        if not isinstance(node, (ast.If, ast.For, ast.AsyncFor, ast.While)):
            continue
        # ``else: pass`` is semantically equivalent to omitting the else and
        # does not prove a promised branch is unimplemented. A pass-only body
        # does suppress the selected branch/loop and remains blocking.
        if len(node.body) == 1 and isinstance(node.body[0], ast.Pass):
            result.append(
                {
                    "path": path,
                    "line": int(getattr(node.body[0], "lineno", 1)),
                    "kind": "empty_conditional_branch",
                    "branch": "body",
                }
            )
    return result


def empty_conditional_branch_signals(
    scripts: list[Path],
    generated: Path,
) -> list[dict[str, Any]]:
    """Detect production decision branches whose only operation is ``pass``."""

    result: list[dict[str, Any]] = []
    for script in scripts:
        if script.name in _DIAGNOSTIC_NAMES:
            continue
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        result.extend(
            empty_conditional_branch_source_signals(
                source,
                path=script.relative_to(generated).as_posix(),
            )
        )
    return result


def unused_mapping_input_source_signals(
    source: str,
    *,
    path: str,
) -> list[dict[str, Any]]:
    """Find mapping inputs assigned to a local that is never consumed."""

    try:
        tree = ast.parse(source, filename=path)
    except SyntaxError:
        return []
    result: list[dict[str, Any]] = []
    for function in (
        node
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    ):
        loaded_names = {
            node.id
            for node in ast.walk(function)
            if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load)
        }
        for node in ast.walk(function):
            if not isinstance(node, (ast.Assign, ast.AnnAssign)):
                continue
            value = node.value
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            if not (
                isinstance(value, ast.Call)
                and isinstance(value.func, ast.Attribute)
                and value.func.attr == "get"
                and value.args
                and isinstance(value.args[0], ast.Constant)
                and isinstance(value.args[0].value, str)
            ):
                continue
            for target in targets:
                if isinstance(target, ast.Name) and target.id not in loaded_names:
                    result.append(
                        {
                            "path": path,
                            "line": int(getattr(node, "lineno", 1)),
                            "function": function.name,
                            "variable": target.id,
                            "inputKey": value.args[0].value,
                            "kind": "mapping_input_never_consumed",
                        }
                    )
    return result


def unused_mapping_input_signals(
    scripts: list[Path],
    generated: Path,
) -> list[dict[str, Any]]:
    """Find named mapping inputs that are read into a local but never consumed."""

    result: list[dict[str, Any]] = []
    for script in scripts:
        if script.name in _DIAGNOSTIC_NAMES:
            continue
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        result.extend(
            unused_mapping_input_source_signals(
                source,
                path=script.relative_to(generated).as_posix(),
            )
        )
    return result


def _production_modules(generated: Path) -> set[str]:
    scripts_dir = generated / "scripts"
    if not scripts_dir.is_dir():
        return set()
    return {
        ".".join(path.relative_to(scripts_dir).with_suffix("").parts)
        for path in scripts_dir.rglob("*.py")
        if path.name not in _DIAGNOSTIC_NAMES and path.name != "__init__.py"
    }


def _normalized_module_name(value: str) -> str:
    normalized = str(value or "").lstrip(".")
    return normalized.removeprefix("scripts.") if normalized != "scripts" else ""


def analyze_self_check_production_usage(
    generated: Path,
    target: Path,
) -> dict[str, Any]:
    """Return production symbols a diagnostic entrypoint actually uses."""

    scripts_dir = generated / "scripts"
    production = _production_modules(generated)
    if not production or not target.is_file():
        return {"usesProduction": False, "observations": [], "productionModules": sorted(production)}
    try:
        source = target.read_text(encoding="utf-8", errors="replace")
        tree = ast.parse(source)
    except (OSError, SyntaxError):
        return {"usesProduction": False, "observations": [], "productionModules": sorted(production)}

    imported_names: set[str] = set()
    imported_roots: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            module = _normalized_module_name(str(node.module or ""))
            if module in production:
                imported_names.update(
                    alias.asname or alias.name
                    for alias in node.names
                    if alias.name != "*"
                )
                continue
            for alias in node.names:
                candidate = ".".join(part for part in (module, alias.name) if part)
                if candidate in production:
                    imported_roots.add(alias.asname or alias.name)
        elif isinstance(node, ast.Import):
            for alias in node.names:
                module = _normalized_module_name(alias.name)
                if module not in production:
                    continue
                imported_roots.add(alias.asname or alias.name.split(".")[0])

    observed_names: set[str] = set()

    def observe_names(node: ast.AST) -> None:
        observed_names.update(
            item.id
            for item in ast.walk(node)
            if isinstance(item, ast.Name) and isinstance(item.ctx, ast.Load)
        )

    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            observe_names(node.func)
        elif isinstance(node, ast.Assert):
            observe_names(node)
        elif isinstance(node, (ast.If, ast.While)):
            observe_names(node.test)
        elif isinstance(node, ast.Raise) and node.exc is not None:
            observe_names(node.exc)

    used_imports = sorted((imported_names | imported_roots) & observed_names)
    invokes_script = any(
        re.search(
            rf"(?<![\w.-])(?:scripts/)?{re.escape(module.replace('.', '/'))}\.py(?![\w.-])",
            source,
        )
        for module in production
    ) and bool(
        re.search(
            r"subprocess\.(?:run|call|check_call|check_output|Popen)\s*\(",
            source,
        )
    )
    observations = [f"production:{name}" for name in used_imports]
    if invokes_script:
        observations.append("production:subprocess")
    return {
        "usesProduction": bool(observations),
        "observations": observations,
        "productionModules": sorted(production),
    }


def self_check_production_usage_signals(generated: Path) -> list[dict[str, Any]]:
    """Require an explicit self-check to use production package code."""

    scripts_dir = generated / "scripts"
    production = _production_modules(generated)
    if not production:
        return []
    result: list[dict[str, Any]] = []
    for name in sorted(_DIAGNOSTIC_NAMES):
        target = scripts_dir / name
        if not target.is_file():
            continue
        try:
            source = target.read_text(encoding="utf-8", errors="replace")
        except OSError:
            source = ""
        if is_platform_replay_plan_source(source):
            continue
        usage = analyze_self_check_production_usage(generated, target)
        if not usage["usesProduction"]:
            result.append(
                {
                    "path": target.relative_to(generated).as_posix(),
                    "line": 1,
                    "kind": "self_check_does_not_invoke_production_code",
                    "productionModules": usage["productionModules"],
                }
            )
    return result


def _call_name(node: ast.AST) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        prefix = _call_name(node.value)
        return ".".join(part for part in (prefix, node.attr) if part)
    return ""


def _is_cli_entrypoint(tree: ast.AST) -> bool:
    """Return whether a production script exposes a command-line boundary."""

    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and _call_name(node.func).endswith(".parse_args"):
            return True
        if isinstance(node, ast.Attribute) and node.attr == "argv":
            if isinstance(node.value, ast.Name) and node.value.id == "sys":
                return True
        if isinstance(node, ast.Name) and node.id == "argv":
            return True
    return False


def _assigned_values(tree: ast.AST) -> dict[str, ast.AST]:
    values: dict[str, ast.AST] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    values[target.id] = node.value
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.value is not None:
            values[node.target.id] = node.value
    return values


def _literal_strings(
    node: ast.AST,
    assignments: dict[str, ast.AST],
    *,
    seen: set[str] | None = None,
) -> set[str]:
    seen = set() if seen is None else seen
    if isinstance(node, ast.Name):
        if node.id not in assignments or node.id in seen:
            return set()
        return _literal_strings(
            assignments[node.id],
            assignments,
            seen={*seen, node.id},
        )
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return {str(node.value)}

    result: set[str] = set()
    for child in ast.iter_child_nodes(node):
        result.update(_literal_strings(child, assignments, seen=seen))
    return result


def _referenced_names(
    node: ast.AST,
    assignments: dict[str, ast.AST],
    *,
    seen: set[str] | None = None,
) -> set[str]:
    """Return names that contribute to an expression through local aliases."""

    seen = set() if seen is None else seen
    if isinstance(node, ast.Name):
        result = {node.id}
        if node.id in assignments and node.id not in seen:
            result.update(
                _referenced_names(
                    assignments[node.id],
                    assignments,
                    seen={*seen, node.id},
                )
            )
        return result

    result: set[str] = set()
    for child in ast.iter_child_nodes(node):
        result.update(_referenced_names(child, assignments, seen=seen))
    return result


def _function_parameters(
    function: ast.FunctionDef | ast.AsyncFunctionDef,
) -> list[str]:
    arguments = (
        *function.args.posonlyargs,
        *function.args.args,
        *function.args.kwonlyargs,
    )
    return [argument.arg for argument in arguments]


def _call_argument(
    call: ast.Call,
    function: ast.FunctionDef | ast.AsyncFunctionDef,
    parameter: str,
) -> ast.AST | None:
    parameters = _function_parameters(function)
    try:
        index = parameters.index(parameter)
    except ValueError:
        return None
    positional_count = len(function.args.posonlyargs) + len(function.args.args)
    if index < positional_count and index < len(call.args):
        return call.args[index]
    return next(
        (
            keyword.value
            for keyword in call.keywords
            if keyword.arg == parameter
        ),
        None,
    )


def _subprocess_wrapper_parameters(
    tree: ast.AST,
) -> tuple[
    dict[str, ast.FunctionDef | ast.AsyncFunctionDef],
    dict[str, set[str]],
]:
    """Summarize local function parameters that flow into process execution.

    Generated self-checks commonly centralize subprocess handling in a helper
    such as ``run_script(script_path, args)``.  The script path is visible only
    at the helper call site, so direct literal inspection of ``subprocess.run``
    loses the execution edge unless the parameter flow is retained.
    """

    functions = {
        node.name: node
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    }
    summaries: dict[str, set[str]] = {name: set() for name in functions}

    for name, function in functions.items():
        parameters = set(_function_parameters(function))
        assignments = _assigned_values(function)
        for node in ast.walk(function):
            if not isinstance(node, ast.Call) or _call_name(node.func) not in _SUBPROCESS_CALLS:
                continue
            for value in (*node.args, *(item.value for item in node.keywords)):
                summaries[name].update(
                    parameters & _referenced_names(value, assignments)
                )

    changed = True
    while changed:
        changed = False
        for name, function in functions.items():
            parameters = set(_function_parameters(function))
            assignments = _assigned_values(function)
            for node in ast.walk(function):
                if not isinstance(node, ast.Call):
                    continue
                callee_name = _call_name(node.func)
                callee = functions.get(callee_name)
                if callee is None:
                    continue
                for callee_parameter in summaries[callee_name]:
                    argument = _call_argument(node, callee, callee_parameter)
                    if argument is None:
                        continue
                    propagated = parameters & _referenced_names(argument, assignments)
                    if not propagated.issubset(summaries[name]):
                        summaries[name].update(propagated)
                        changed = True
    return functions, summaries


def _module_token(value: str) -> str:
    normalized = str(value or "").strip().replace("\\", "/")
    normalized = normalized.removesuffix(".py").replace("/", ".")
    return normalized.removeprefix("scripts.").lstrip(".")


def _script_execution_edges(
    tree: ast.AST,
    *,
    modules: dict[str, str],
) -> set[str]:
    """Return package scripts whose CLI boundary is invoked by this script."""

    assignments = _assigned_values(tree)
    wrapper_functions, wrapper_parameters = _subprocess_wrapper_parameters(tree)
    imported_main: dict[str, str] = {}
    imported_module: dict[str, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            module = _module_token(str(node.module or ""))
            target = modules.get(module)
            if target:
                for alias in node.names:
                    if alias.name == "main":
                        imported_main[alias.asname or alias.name] = target
        elif isinstance(node, ast.Import):
            for alias in node.names:
                module = _module_token(alias.name)
                target = modules.get(module)
                if target:
                    imported_module[alias.asname or alias.name.split(".")[-1]] = target

    result: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        call_name = _call_name(node.func)
        if call_name in imported_main:
            result.add(imported_main[call_name])
            continue
        if isinstance(node.func, ast.Attribute) and node.func.attr == "main":
            owner = _call_name(node.func.value)
            if owner in imported_module:
                result.add(imported_module[owner])
                continue
        wrapper = wrapper_functions.get(call_name)
        if wrapper is not None:
            strings: set[str] = set()
            for parameter in wrapper_parameters[call_name]:
                argument = _call_argument(node, wrapper, parameter)
                if argument is not None:
                    strings.update(_literal_strings(argument, assignments))
            for module, target in modules.items():
                script_name = PurePath(target).name
                if module in {_module_token(value) for value in strings} or any(
                    value.replace("\\", "/").endswith((f"/{script_name}", script_name))
                    for value in strings
                ):
                    result.add(target)
        if call_name not in _SUBPROCESS_CALLS:
            continue
        strings: set[str] = set()
        for value in (*node.args, *(item.value for item in node.keywords)):
            strings.update(_literal_strings(value, assignments))
        tokens = {_module_token(value) for value in strings}
        for module, target in modules.items():
            script_name = PurePath(target).name
            if module in tokens or any(
                value.replace("\\", "/").endswith((f"/{script_name}", script_name))
                for value in strings
            ):
                result.add(target)
    return result


def documented_cli_entrypoints(generated: Path) -> set[str]:
    """Return scripts promised as executable public/workflow boundaries.

    A resource inventory may list many implementation modules, including
    modules that retain an argparse block for local diagnostics.  Requiring
    all such modules to be launched again by the package self-check creates
    duplicate work without proving an exported workflow.  Treat a script as
    public only when portable package documentation shows an executable
    command/arguments or explicitly labels it as the primary entrypoint.
    """

    result: set[str] = set()
    for document in sorted(
        path for path in generated.rglob("*") if path.is_file()
    ):
        if document.suffix.lower() not in _PORTABLE_DOCUMENT_SUFFIXES:
            continue
        try:
            text = document.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            result.update(match.group(1) for match in _CLI_COMMAND_RE.finditer(line))
            result.update(match.group(1) for match in _CLI_ARGUMENT_RE.finditer(line))
            if _PRIMARY_ENTRYPOINT_RE.search(line):
                result.update(match.group(1) for match in _SCRIPT_PATH_RE.finditer(line))
    # The platform executes the package self-check separately.  Documentation
    # naturally shows that command, but it is a diagnostic harness rather than
    # a business CLI and cannot be required to cover itself recursively.
    return {
        relative
        for relative in result
        if PurePath(relative).name not in _DIAGNOSTIC_NAMES
    }


def offline_cli_coverage_signals(
    generated: Path,
    *,
    excluded_paths: set[str] | None = None,
) -> list[dict[str, Any]]:
    """Require package self-checks to cross documented offline CLI boundaries.

    Calling an internal helper is not equivalent to running a documented CLI:
    argument parsing, field adapters, file handoffs and output serialization
    live at that boundary.  A small package-local execution graph accepts
    direct or transitive subprocess/runpy/main calls.  Resource-list-only
    helper CLIs are not public boundaries.  Network/browser entrypoints and
    orchestrators that transitively invoke them remain explicitly unverified.
    """

    scripts_dir = generated / "scripts"
    if not scripts_dir.is_dir():
        return []
    sources: dict[str, tuple[str, ast.AST]] = {}
    for path in sorted(scripts_dir.rglob("*.py")):
        relative = path.relative_to(generated).as_posix()
        try:
            source = path.read_text(encoding="utf-8", errors="replace")
            tree = ast.parse(source)
        except (OSError, SyntaxError):
            continue
        sources[relative] = (source, tree)

    modules = {
        ".".join(PurePath(relative).with_suffix("").parts[1:]): relative
        for relative in sources
    }
    excluded = {
        str(value or "").replace("\\", "/")
        for value in (excluded_paths or set())
    }

    def is_excluded(relative: str) -> bool:
        return any(
            value == relative or value.endswith(f"/{relative}")
            for value in excluded
        )

    graph = {
        relative: _script_execution_edges(tree, modules=modules)
        for relative, (_source, tree) in sources.items()
    }
    external = {
        relative
        for relative in sources
        if is_excluded(relative)
    }
    while True:
        callers = {
            relative
            for relative, targets in graph.items()
            if targets & external
        }
        expanded = external | callers
        if expanded == external:
            break
        external = expanded

    documented = documented_cli_entrypoints(generated)
    entrypoints = set()
    for relative, (_source, tree) in sources.items():
        if PurePath(relative).name in _DIAGNOSTIC_NAMES:
            continue
        if relative in documented and relative not in external and _is_cli_entrypoint(tree):
            entrypoints.add(relative)
    if not entrypoints:
        return []

    diagnostics = {
        relative
        for relative in sources
        if PurePath(relative).name in _DIAGNOSTIC_NAMES
    }
    if not diagnostics:
        if len(entrypoints) <= 1:
            return []
        return [
            {
                "path": relative,
                "kind": "offline_pipeline_self_check_missing",
                "reason": "multiple_offline_cli_entrypoints_without_pipeline_check",
                "offlineCliEntrypoints": sorted(entrypoints),
            }
            for relative in sorted(entrypoints)
        ]
    if any(
        is_platform_replay_plan_source(sources[relative][0])
        for relative in diagnostics
    ):
        # The emitted v1 protocol is validated against every documented CLI
        # and replayed later by acceptance.  Requiring this small plan emitter
        # to also invoke subprocess would duplicate the platform executor.
        return []

    reachable = set(diagnostics)
    pending = list(diagnostics)
    while pending:
        current = pending.pop()
        for target in graph.get(current, set()):
            if target not in reachable:
                reachable.add(target)
                pending.append(target)

    return [
        {
            "path": relative,
            "kind": "offline_cli_entrypoint_not_exercised",
            "reason": "self_check_bypasses_public_cli_boundary",
            "selfCheckPaths": sorted(diagnostics),
        }
        for relative in sorted(entrypoints - reachable)
    ]


__all__ = [
    "empty_conditional_branch_signals",
    "analyze_self_check_production_usage",
    "documented_cli_entrypoints",
    "missing_package_references",
    "offline_cli_coverage_signals",
    "reserved_example_endpoint_signals",
    "self_check_production_usage_signals",
    "unused_mapping_input_signals",
]
