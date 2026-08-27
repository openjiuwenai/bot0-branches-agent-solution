# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Deterministic undefined-name checks for generated Python entry points."""

from __future__ import annotations

import ast
import builtins
import symtable
import sys
from pathlib import Path
from typing import Any


_IMPLICIT_MODULE_GLOBALS = frozenset(
    {
        "__annotations__",
        "__builtins__",
        "__cached__",
        "__doc__",
        "__file__",
        "__loader__",
        "__name__",
        "__package__",
        "__spec__",
    }
)
_BUILTIN_NAMES = frozenset(dir(builtins))
_STDLIB_MODULE_NAMES = frozenset(sys.stdlib_module_names)
_REFERENCE_SUFFIXES = frozenset({".md", ".markdown", ".txt", ".json", ".yaml", ".yml", ".py"})


def _has_star_import(tree: ast.AST) -> bool:
    return any(
        isinstance(node, ast.ImportFrom)
        and any(alias.name == "*" for alias in node.names)
        for node in ast.walk(tree)
    )


def _first_load_lines(tree: ast.AST) -> dict[str, int]:
    lines: dict[str, int] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
            lines[node.id] = min(lines.get(node.id, node.lineno), node.lineno)
    return lines


def analyze_undefined_python_names(
    source: str,
    *,
    filename: str,
) -> dict[str, Any]:
    """Return F821-like undefined globals without requiring a linter package.

    The acceptance caller parses syntax before invoking this helper.  A star
    import makes global-name ownership unknowable with the standard library,
    so the analysis is explicitly reported as skipped instead of risking a
    false blocking result.
    """

    tree = ast.parse(source, filename=filename)
    if _has_star_import(tree):
        return {
            "undefinedNames": [],
            "skippedReason": "star_import",
        }

    table = symtable.symtable(source, filename, "exec")
    allowed = _BUILTIN_NAMES | _IMPLICIT_MODULE_GLOBALS
    module_bindings = set()
    for name in table.get_identifiers():
        symbol = table.lookup(name)
        if symbol.is_assigned() or symbol.is_imported() or symbol.is_namespace():
            module_bindings.add(name)
    unresolved: set[str] = set()

    for name in table.get_identifiers():
        symbol = table.lookup(name)
        if symbol.is_referenced() and name not in module_bindings and name not in allowed:
            unresolved.add(name)

    pending = list(table.get_children())
    while pending:
        child = pending.pop()
        pending.extend(child.get_children())
        for name in child.get_identifiers():
            symbol = child.lookup(name)
            unresolved_global = (
                symbol.is_referenced()
                and symbol.is_global()
                and name not in module_bindings
                and name not in allowed
            )
            if unresolved_global:
                unresolved.add(name)

    first_lines = _first_load_lines(tree)
    return {
        "undefinedNames": [
            {
                "name": name,
                "line": first_lines.get(name),
            }
            for name in sorted(unresolved)
        ],
        "skippedReason": None,
    }


def analyze_python_module_name_conflicts(
    generated: Path,
    scripts: list[Path],
) -> list[dict[str, Any]]:
    """Find exported script/package names that shadow Python's standard library."""

    scripts_root = generated / "scripts"
    conflicts: dict[tuple[str, str], dict[str, Any]] = {}
    for script in scripts:
        try:
            relative = script.relative_to(scripts_root)
        except ValueError:
            continue
        if not relative.parts:
            continue
        top_level = relative.parts[0]
        module_name = Path(top_level).stem if len(relative.parts) == 1 else top_level
        if module_name == "__init__" or module_name not in _STDLIB_MODULE_NAMES:
            continue
        path = f"scripts/{relative.as_posix()}"
        replacement_root = f"{module_name}_cli" if len(relative.parts) == 1 else f"{module_name}_skill"
        replacement = (
            f"scripts/{replacement_root}{script.suffix}"
            if len(relative.parts) == 1
            else f"scripts/{replacement_root}/{'/'.join(relative.parts[1:])}"
        )
        conflicts[(module_name, path)] = {
            "path": path,
            "moduleName": module_name,
            "replacementPath": replacement,
            "referencePaths": [],
        }

    if not conflicts:
        return []
    documents = [
        path
        for path in generated.rglob("*")
        if path.is_file() and path.suffix.lower() in _REFERENCE_SUFFIXES
    ]
    for issue in conflicts.values():
        referenced_by = []
        needle = str(issue["path"])
        for document in documents:
            if document.as_posix().endswith(needle):
                continue
            try:
                text = document.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if needle in text:
                referenced_by.append(document.relative_to(generated).as_posix())
        issue["referencePaths"] = sorted(set(referenced_by))
    return [conflicts[key] for key in sorted(conflicts)]


__all__ = [
    "analyze_python_module_name_conflicts",
    "analyze_undefined_python_names",
]
