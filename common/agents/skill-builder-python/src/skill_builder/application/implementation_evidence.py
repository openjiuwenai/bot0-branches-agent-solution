# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Structured traceability between ScenarioContract obligations and a package."""

from __future__ import annotations

import ast
import hashlib
import json
import re
from pathlib import Path, PurePosixPath
from typing import Any


IMPLEMENTATION_EVIDENCE_PATH = "validation/diagnostics/implementation_evidence.json"
IMPLEMENTATION_EVIDENCE_SCHEMA_VERSION = "skill-builder-implementation-evidence/v1"
_IMPLEMENTATION_TYPES = {"code", "documentation", "manual_boundary"}
_RULE_LABEL_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9_-]{1,30}-\d{1,8}\b")
_MARKDOWN_FORMATTING_RE = re.compile(r"[`*_~#>|]")


def _text(value: Any, *, limit: int) -> str:
    return str(value or "").strip()[:limit]


def _package_relative_path(value: Any) -> str:
    raw = _text(value, limit=1000).replace("\\", "/")
    while raw.startswith("./"):
        raw = raw[2:]
    if not raw or raw.startswith("/") or re.match(r"^[A-Za-z]:/", raw):
        return ""
    path = PurePosixPath(raw)
    if path.as_posix() in {"", "."} or ".." in path.parts:
        return ""
    return path.as_posix()


def normalize_implementation_evidence(value: Any) -> list[dict[str, Any]]:
    """Normalize the small declaration surface accepted from Author/Repair."""

    result: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str, str, str]] = set()
    for raw in value if isinstance(value, list) else []:
        if not isinstance(raw, dict):
            continue
        contract_id = _text(
            raw.get("contractId")
            or raw.get("contract_id")
            or raw.get("requirementId")
            or raw.get("ruleId"),
            limit=160,
        )
        implementation_type = _text(
            raw.get("implementationType") or raw.get("implementation_type"),
            limit=40,
        ).lower()
        path = _package_relative_path(raw.get("path"))
        if not contract_id or implementation_type not in _IMPLEMENTATION_TYPES or not path:
            continue
        if path.startswith("generated-skill/"):
            path = path.removeprefix("generated-skill/")
        item: dict[str, Any] = {
            "contractId": contract_id,
            "implementationType": implementation_type,
            "path": path,
        }
        symbol = _text(raw.get("symbol"), limit=240)
        if symbol:
            item["symbol"] = symbol
        verification_path = _package_relative_path(
            raw.get("verificationPath") or raw.get("verification_path")
        )
        if verification_path.startswith("generated-skill/"):
            verification_path = verification_path.removeprefix("generated-skill/")
        if verification_path:
            item["verificationPath"] = verification_path
        assertion = _text(raw.get("assertion") or raw.get("message"), limit=1000)
        if assertion:
            item["assertion"] = assertion
        key = (
            contract_id,
            implementation_type,
            path,
            symbol,
            verification_path,
        )
        if key not in seen:
            result.append(item)
            seen.add(key)
        if len(result) >= 200:
            break
    return result


def persist_implementation_evidence(root: Path, value: Any) -> list[dict[str, Any]]:
    entries = infer_knowledge_documentation_evidence(
        root,
        merge_implementation_evidence(root, value),
    )
    target = root / IMPLEMENTATION_EVIDENCE_PATH
    temporary = target.with_name(f".{target.name}.tmp")
    payload = {
        "schemaVersion": IMPLEMENTATION_EVIDENCE_SCHEMA_VERSION,
        "entries": entries,
    }
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        temporary.replace(target)
    except OSError:
        temporary.unlink(missing_ok=True)
    return entries


def merge_implementation_evidence(root: Path, value: Any) -> list[dict[str, Any]]:
    """Project a partial Author/Repair submission onto the durable graph."""

    incoming = normalize_implementation_evidence(value)
    # Author starts without a file. Repair may submit only the mappings it
    # touched; replace those contract IDs while retaining the rest of the
    # previously validated traceability graph.
    replaced_ids = {str(item["contractId"]) for item in incoming}
    entries = [
        item
        for item in load_implementation_evidence(root)
        if str(item["contractId"]) not in replaced_ids
    ]
    entries.extend(incoming)
    return entries


def load_implementation_evidence(root: Path) -> list[dict[str, Any]]:
    try:
        payload = json.loads((root / IMPLEMENTATION_EVIDENCE_PATH).read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return []
    return normalize_implementation_evidence(
        payload.get("entries") if isinstance(payload, dict) else payload
    )


def _canonical_obligation_text(value: Any) -> str:
    """Normalize formatting only; never rewrite obligation semantics."""

    text = str(value or "")
    text = re.sub(r"<!--[\s\S]*?-->", "", text)
    text = _MARKDOWN_FORMATTING_RE.sub("", text)
    return re.sub(r"\s+", "", text).casefold()


def _exact_document_label(content: str, label: str) -> bool:
    return bool(
        label
        and re.search(
            rf"(?<![\w-]){re.escape(label)}(?![\w-])",
            content,
            re.IGNORECASE,
        )
    )


def infer_knowledge_documentation_evidence(
    root: Path,
    evidence: Any,
) -> list[dict[str, Any]]:
    """Bind material rule labels to knowledge-package documents.

    This is deliberately restricted to controller-classified knowledge
    packages. Executable business rules still require code/replay evidence.
    """

    entries = normalize_implementation_evidence(evidence)
    try:
        plan = json.loads(
            (root / "validation" / "implementation_plan.json").read_text(
                encoding="utf-8"
            )
        )
        scenario = json.loads(
            (root / "validation" / "scenario_contract.json").read_text(
                encoding="utf-8"
            )
        )
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return entries
    if not isinstance(plan, dict) or plan.get("scriptsRequired") is not False:
        return entries
    generated = root / "generated-skill"
    documents: list[tuple[str, str, str]] = []
    for path in sorted(
        item
        for item in generated.rglob("*")
        if item.is_file()
        and (
            item.name == "SKILL.md"
            or (
                item.relative_to(generated).as_posix().startswith("references/")
                and item.suffix.lower() in {".md", ".markdown", ".txt"}
            )
        )
    ):
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
            documents.append(
                (
                    path.relative_to(generated).as_posix(),
                    content,
                    _canonical_obligation_text(content),
                )
            )
        except OSError:
            continue
    mapped_ids = {str(item["contractId"]) for item in entries}
    inferred: list[dict[str, Any]] = []
    obligations: list[tuple[str, list[str], list[str]]] = []
    for requirement in scenario.get("resolvedRequirements") or []:
        if not isinstance(requirement, dict):
            continue
        contract_id = str(requirement.get("requirementId") or "").strip()
        obligations.append(
            (
                contract_id,
                [contract_id],
                [
                    str(requirement.get(key) or "").strip()
                    for key in ("value", "sourceQuote")
                ],
            )
        )
    for rule in scenario.get("businessRules") or []:
        if not isinstance(rule, dict):
            continue
        contract_id = str(rule.get("ruleId") or "").strip()
        definition = rule.get("definition")
        aliases: list[str] = [contract_id]
        exact_texts: list[str] = [str(rule.get("sourceQuote") or "").strip()]
        if isinstance(definition, str):
            exact_texts.append(definition.strip())
        elif isinstance(definition, dict):
            aliases.extend(
                str(definition.get(key) or "").strip()
                for key in ("id", "ruleId", "code")
            )
            exact_texts.extend(
                str(definition.get(key) or "").strip()
                for key in ("description", "requirement", "summary")
            )
        aliases.extend(
            _RULE_LABEL_RE.findall(
                json.dumps(definition, ensure_ascii=False, default=str)
            )
        )
        obligations.append((contract_id, aliases, exact_texts))

    for contract_id, raw_aliases, raw_texts in obligations:
        if not contract_id or contract_id in mapped_ids:
            continue
        aliases = list(dict.fromkeys(alias for alias in raw_aliases if alias))
        exact_texts = list(
            dict.fromkeys(
                canonical
                for text in raw_texts
                if len(canonical := _canonical_obligation_text(text)) >= 12
            )
        )
        label_match = next(
            (
                (path, alias)
                for path, content, _canonical in documents
                for alias in aliases
                if _exact_document_label(content, alias)
            ),
            None,
        )
        text_match = next(
            (
                (path, contract_id)
                for path, _content, canonical_content in documents
                for exact_text in exact_texts
                if exact_text in canonical_content
            ),
            None,
        )
        match = label_match or text_match
        if match is None:
            continue
        path, symbol = match
        inferred.append(
            {
                "contractId": contract_id,
                "implementationType": "documentation",
                "path": path,
                "symbol": symbol,
                "assertion": (
                    "Package documentation contains the exact contract or rule label."
                    if label_match is not None
                    else "Package documentation contains the exact normalized obligation text."
                ),
            }
        )
        mapped_ids.add(contract_id)
    return normalize_implementation_evidence([*entries, *inferred])


def implementation_evidence_sha256(root: Path) -> str | None:
    """Return a stable digest for diagnostic evidence change tracking."""

    entries = load_implementation_evidence(root)
    if not entries:
        return None
    encoded = json.dumps(
        entries,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _contract_obligations(root: Path) -> dict[str, str]:
    try:
        scenario = json.loads(
            (root / "validation" / "scenario_contract.json").read_text(encoding="utf-8")
        )
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return {}
    obligations: dict[str, str] = {}
    for item in scenario.get("resolvedRequirements") or []:
        if isinstance(item, dict) and str(item.get("requirementId") or "").strip():
            obligations[str(item["requirementId"])] = "requirement"
    for item in scenario.get("businessRules") or []:
        if isinstance(item, dict) and str(item.get("ruleId") or "").strip():
            obligations[str(item["ruleId"])] = "business_rule"

    return obligations


def _assignment_names(target: ast.AST) -> set[str]:
    if isinstance(target, ast.Name):
        return {target.id}
    if isinstance(target, (ast.Tuple, ast.List)):
        return {
            name
            for item in target.elts
            for name in _assignment_names(item)
        }
    if isinstance(target, ast.Starred):
        return _assignment_names(target.value)
    return set()


def _statement_blocks(node: ast.stmt) -> list[list[ast.stmt]]:
    if isinstance(node, (ast.If, ast.For, ast.AsyncFor, ast.While)):
        return [node.body, node.orelse]
    if isinstance(node, (ast.With, ast.AsyncWith)):
        return [node.body]
    if isinstance(node, (ast.Try, getattr(ast, "TryStar", ast.Try))):
        return [
            node.body,
            *(handler.body for handler in node.handlers),
            node.orelse,
            node.finalbody,
        ]
    if isinstance(node, ast.Match):
        return [case.body for case in node.cases]
    return []


def _python_symbol_names(path: Path) -> tuple[set[str], set[str]]:
    """Return Python definitions and stable module/class bindings.

    Authors commonly identify a method as ``Class.method``.  ``ast`` stores
    that method as a node named only ``method``, so comparing the raw node name
    with the submitted string rejects valid class-backed implementations.  A
    business rule may also be implemented by a module or class constant, so
    assignments at those scopes are first-class evidence symbols as well.

    Function-local variables remain excluded: they are implementation details,
    not stable symbols an Author can bind in the evidence contract.
    """

    try:
        tree = ast.parse(path.read_text(encoding="utf-8", errors="replace"))
    except (OSError, SyntaxError):
        return set(), set()

    short_names: set[str] = set()
    qualified_names: set[str] = set()

    def add(name: str, prefix: tuple[str, ...]) -> None:
        short_names.add(name)
        qualified_names.add(".".join((*prefix, name)))

    def visit(
        nodes: list[ast.stmt],
        prefix: tuple[str, ...] = (),
        *,
        record_assignments: bool = True,
    ) -> None:
        for node in nodes:
            if isinstance(node, ast.ClassDef):
                add(node.name, prefix)
                visit(node.body, (*prefix, node.name), record_assignments=True)
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                add(node.name, prefix)
                # Preserve nested definition discovery without treating local
                # variables as package evidence.
                visit(
                    node.body,
                    (*prefix, node.name),
                    record_assignments=False,
                )
            elif record_assignments and isinstance(node, ast.Assign):
                for target in node.targets:
                    for name in _assignment_names(target):
                        add(name, prefix)
            elif record_assignments and isinstance(node, (ast.AnnAssign, ast.AugAssign)):
                for name in _assignment_names(node.target):
                    add(name, prefix)
            for block in _statement_blocks(node):
                visit(block, prefix, record_assignments=record_assignments)

    visit(tree.body)
    return short_names, qualified_names


def _python_symbol_exists(path: Path, symbol: str) -> bool:
    short_names, qualified_names = _python_symbol_names(path)
    if "." in symbol:
        return symbol in qualified_names
    return symbol in short_names


def implementation_evidence_issues(
    root: Path,
    *,
    evidence: Any = None,
) -> list[dict[str, Any]]:
    """Validate traceability without trusting the Agent's semantic claim."""

    obligations = _contract_obligations(root)
    if not obligations:
        return []
    entries = (
        normalize_implementation_evidence(evidence)
        if evidence is not None
        else load_implementation_evidence(root)
    )
    by_contract: dict[str, list[dict[str, Any]]] = {}
    for item in entries:
        by_contract.setdefault(str(item["contractId"]), []).append(item)
    issues: list[dict[str, Any]] = []
    generated = root / "generated-skill"
    for contract_id, obligation_kind in sorted(obligations.items()):
        mapped = by_contract.get(contract_id) or []
        if not mapped:
            issues.append(
                {
                    "kind": "contract_obligation_unmapped",
                    "contractId": contract_id,
                    "obligationKind": obligation_kind,
                }
            )
            continue
        valid = False
        entry_issues: list[dict[str, Any]] = []
        for item in mapped:
            implementation_type = str(item["implementationType"])
            path = generated / str(item["path"])
            if not path.is_file():
                entry_issues.append({**item, "reason": "implementation_path_missing"})
                continue
            if implementation_type == "code":
                if path.suffix != ".py":
                    entry_issues.append({**item, "reason": "code_evidence_not_python"})
                    continue
                symbol = str(item.get("symbol") or "").strip()
                if not symbol or not _python_symbol_exists(path, symbol):
                    entry_issues.append({**item, "reason": "implementation_symbol_missing"})
                    continue
                verification_path = str(item.get("verificationPath") or "").strip()
                if obligation_kind == "business_rule":
                    if not verification_path or not (generated / verification_path).is_file():
                        entry_issues.append({**item, "reason": "verification_path_missing"})
                        continue
            valid = True
            break
        if not valid:
            issues.append(
                {
                    "kind": "contract_obligation_evidence_invalid",
                    "contractId": contract_id,
                    "obligationKind": obligation_kind,
                    "entries": entry_issues[:10],
                }
            )
    # Agent submissions may include traceability for confirmed decisions or
    # capability entrypoints in addition to the required requirement/rule
    # mappings. Those entries do not weaken coverage and are deliberately
    # outside this gate's contract, so they must not turn a complete package
    # into a repair loop.
    return issues


__all__ = [
    "IMPLEMENTATION_EVIDENCE_PATH",
    "IMPLEMENTATION_EVIDENCE_SCHEMA_VERSION",
    "implementation_evidence_issues",
    "implementation_evidence_sha256",
    "infer_knowledge_documentation_evidence",
    "load_implementation_evidence",
    "merge_implementation_evidence",
    "normalize_implementation_evidence",
    "persist_implementation_evidence",
]
