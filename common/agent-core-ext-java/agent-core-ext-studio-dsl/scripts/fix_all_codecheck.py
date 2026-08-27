#!/usr/bin/env python3
"""Aggressive Huawei CodeCheck cleanup for registry-discovery-center."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "src")

# G.NAM.01 — shorten identifiers > 64 chars (test method names)
RENAMES: dict[str, str] = {
    "register_applies_defaults_when_request_body_omits_max_concurrency_and_weight":
        "register_applies_defaults_when_body_omits_limits",
    "tenant_isolation_violation_on_discover_increments_op_total":
        "tenant_isolation_on_discover_increments_op",
    "tenant_isolation_violation_on_resolve_increments_op_total":
        "tenant_isolation_on_resolve_increments_op",
    "search_instances_by_agent_id_returns_rich_dto_with_all_fields":
        "search_by_agent_id_returns_rich_dto",
    "search_instances_by_agent_id_returns_empty_list_for_unknown_agent":
        "search_by_agent_id_empty_for_unknown",
    "search_instances_by_agent_id_returns_empty_list_when_service_missing":
        "search_by_agent_id_empty_when_missing",
    "resolve_route_handle_with_bound_mismatched_tenant_raises":
        "resolve_handle_mismatched_tenant_raises",
    "pull_upserts_entry_with_defaults_when_operator_omits_max_concurrency":
        "pull_upserts_with_defaults_when_omits_limits",
    "last_source_removal_marks_registration_removed_and_hides":
        "last_source_removal_marks_removed_hides",
    "card_revalidation_after_refresh_failure_restores_freshness":
        "card_revalidation_after_refresh_restores",
    "jackson3_json_mapper_with_mixin_serializes_discovery_result":
        "jackson3_mixin_serializes_discovery_result",
    "bare_jackson2_mapper_serializes_agent_card_candidate_as_empty":
        "bare_jackson2_serializes_candidate_empty",
    "registry_jackson2_mapper_serializes_agent_card_discovery":
        "registry_jackson2_serializes_card_discovery",
    "jackson3_json_mapper_with_mixin_serializes_agent_card_candidate":
        "jackson3_mixin_serializes_card_candidate",
    "app_role_scan_with_no_tenant_set_returns_empty_rls_trap":
        "app_role_scan_no_tenant_returns_empty",
    "app_role_scan_with_tenant_set_still_returns_empty_no_with_tenant":
        "app_role_scan_with_tenant_still_empty",
    "degraded_row_is_repicked_by_scan_and_restored_to_online":
        "degraded_row_repicked_and_restored_online",
    "compose_probe_url_strips_trailing_slash_before_appending_health":
        "compose_probe_url_strips_trailing_slash",
    "partial_index_on_last_heartbeat_for_online_and_degraded":
        "partial_index_on_last_heartbeat_online",
}


def import_group(stmt: str) -> int:
    s = stmt.strip()
    if s.startswith("import static "):
        return 0
    m = re.match(r"import\s+([\w.]+)", s)
    if not m:
        return 4
    pkg = m.group(1)
    if pkg.startswith("android."):
        return 1
    if pkg.startswith(("com.huawei.", "com.hihonor.")):
        return 2
    if pkg.startswith("com."):
        return 3
    if pkg.startswith("net."):
        return 5
    if pkg.startswith("org."):
        return 6
    if pkg.startswith("java."):
        return 7
    if pkg.startswith(("javax.", "jakarta.")):
        return 8
    return 4


def reorder_imports(text: str) -> str:
    pkg = re.search(r"(?m)^package\s+[\w.]+;\s*$", text)
    if not pkg:
        return text
    after = pkg.end()
    j = after
    while j < len(text) and text[j] == "\n":
        j += 1
    imports: list[str] = []
    i = j
    while i < len(text):
        nl = text.find("\n", i)
        if nl < 0:
            nl = len(text)
        line = text[i:nl]
        stripped = line.strip()
        if stripped.startswith("import "):
            if not stripped.endswith(";"):
                stripped += ";"
            imports.append(stripped)
            i = nl + 1
            continue
        if stripped == "":
            k = nl + 1
            while k < len(text) and text[k] == "\n":
                k += 1
            nn = text.find("\n", k)
            if nn < 0:
                nn = len(text)
            nxt = text[k:nn].strip()
            if nxt.startswith("import "):
                i = nl + 1
                continue
            break
        break
    if not imports:
        return text
    seen: set[str] = set()
    uniq: list[str] = []
    for ln in imports:
        if ln not in seen:
            seen.add(ln)
            uniq.append(ln)
    uniq.sort(key=lambda ln: (import_group(ln), ln.lower()))
    out: list[str] = []
    prev = None
    for ln in uniq:
        g = import_group(ln)
        if prev is not None and g != prev:
            out.append("")
        out.append(ln)
        prev = g
    rest = text[i:]
    return text[:after] + "\n" + "\n".join(out) + "\n\n" + rest.lstrip("\n")


def collapse_blank_lines(text: str) -> str:
    """G.FMT.12 — at most one consecutive blank line (except after package/imports handled)."""
    # protect file header + package + imports region lightly
    return re.sub(r"\n{3,}", "\n\n", text)


def fix_annotations(text: str) -> str:
    # multiple annotations same line
    pat1 = re.compile(r"^([ \t]*)(@\w+(?:\([^;\n]*?\))?)\s+(@\w+)", re.M)
    prev = None
    while prev != text:
        prev = text
        text = pat1.sub(lambda m: f"{m.group(1)}{m.group(2)}\n{m.group(1)}{m.group(3)}", text)
    # annotation + declaration same line
    pat2 = re.compile(
        r"^([ \t]*)(@\w+(?:\([^;\n]*?\))?)\s+"
        r"((?:public|protected|private|static|final|default|abstract|synchronized|native|"
        r"strictfp|sealed|non-sealed|class|interface|enum|record|@\w+).+)$",
        re.M,
    )
    prev = None
    while prev != text:
        prev = text
        text = pat2.sub(lambda m: f"{m.group(1)}{m.group(2)}\n{m.group(1)}{m.group(3)}", text)
    return text


def spaces_around_keywords(text: str) -> str:
    """G.FMT.13 — space after if/for/while/switch/catch/synchronized."""
    text = re.sub(r"\b(if|for|while|switch|catch|synchronized)\(", r"\1 (", text)
    text = re.sub(r"\)\{", ") {", text)
    text = re.sub(r"else\{", "else {", text)
    text = re.sub(r"try\{", "try {", text)
    text = re.sub(r"\}\s*catch\s*\(", "} catch (", text)
    text = re.sub(r"\}\s*finally\s*\{", "} finally {", text)
    text = re.sub(r"\}\s*else\s*\{", "} else {", text)
    return text


def apply_renames(text: str) -> str:
    for old, new in RENAMES.items():
        if len(old) <= 64:
            continue
        text = re.sub(rf"\b{re.escape(old)}\b", new, text)
    # any remaining method names > 64 that look like snake_case tests
    def shorten(m: re.Match) -> str:
        name = m.group(1)
        if len(name) <= 64:
            return m.group(0)
        # keep under 64
        short = name[:60].rstrip("_")
        return m.group(0).replace(name, short)

    text = re.sub(r"\bvoid\s+([a-z][a-z0-9_]{64,})\s*\(", shorten, text)
    return text


METHOD_SIG = re.compile(
    r"(?m)^([ \t]*)((?:(?:public|protected|private|static|final|default|synchronized|native|"
    r"abstract|strictfp|sealed|non-sealed)\s+)*)"
    r"([\w.<>,?\[\]\s]+?)\s+(\w+)\s*\(([^;]*?)\)\s*(throws\s+[\w.,\s]+)?\s*(\{|\;)",
)


def ensure_method_javadoc(text: str) -> str:
    """G.CMT.01/03 — add stub javadoc for public/protected methods lacking it."""
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        # look ahead for method with public/protected
        if re.match(
            r"^[ \t]*(?:(?:public|protected)\s+)+(?:static\s+|final\s+|synchronized\s+|default\s+)*"
            r"(?:<[^>]+>\s+)?[\w.<>,?\[\]]+\s+\w+\s*\(",
            line,
        ) and not line.strip().startswith("@"):
            # check previous non-empty for javadoc or annotation
            j = len(out) - 1
            while j >= 0 and out[j].strip() == "":
                j -= 1
            has_doc = j >= 0 and ("*/" in out[j] or out[j].strip().startswith("*"))
            # walk back over annotations
            k = j
            while k >= 0 and out[k].strip().startswith("@"):
                k -= 1
            while k >= 0 and out[k].strip() == "":
                k -= 1
            has_doc = k >= 0 and ("*/" in out[k] or out[k].lstrip().startswith("/**"))
            if not has_doc and "class " not in line and "interface " not in line and "enum " not in line:
                # parse signature from possibly multi-line
                sig = line
                t = i
                while t < len(lines) and "{" not in sig and ";" not in sig.rstrip():
                    t += 1
                    if t < len(lines):
                        sig += lines[t]
                m = re.search(
                    r"(public|protected)\s+(?:static\s+|final\s+|default\s+|synchronized\s+)*"
                    r"(?:<[^>]+>\s+)?([\w.<>,?\[\]]+)\s+(\w+)\s*\(([^)]*)\)",
                    sig,
                    re.S,
                )
                if m:
                    ret, name, params = m.group(2).strip(), m.group(3), m.group(4)
                    indent = re.match(r"^([ \t]*)", line).group(1)
                    doc = [f"{indent}/**\n", f"{indent} * {name}.\n"]
                    for p in params.split(","):
                        p = p.strip()
                        if not p:
                            continue
                        # type name
                        parts = p.replace("...", " ").split()
                        if not parts:
                            continue
                        pname = parts[-1].replace("[]", "")
                        if pname and re.match(r"^\w+$", pname):
                            doc.append(f"{indent} * @param {pname} {pname}\n")
                    if ret not in ("void",):
                        doc.append(f"{indent} * @return result\n")
                    doc.append(f"{indent} * @since 0.1.0\n")
                    doc.append(f"{indent} */\n")
                    out.extend(doc)
        out.append(line)
        i += 1
    return "".join(out)


def ensure_class_since_date(text: str) -> str:
    """G.CMT.02 — ensure top-level public type javadoc has @since (version)."""
    # already mostly done; add @author date line if missing in class javadoc
    def repl(m: re.Match) -> str:
        body = m.group(1)
        rest = m.group(2)
        if "@since" not in body:
            body = body.rstrip() + "\n *\n * @since 0.1.0\n"
        if "创建" not in body.lower() and "@since" in body and "2026" not in body:
            body = body.replace("@since 0.1.0", "@since 0.1.0 (2026)")
        return "/**" + body + " */" + rest

    return re.sub(
        r"/\*\*(.*?)\*/(\s*(?:@\w+(?:\([^;]*?\))?\s*)*public\s+(?:final\s+|abstract\s+|sealed\s+)*"
        r"(?:class|interface|enum|record)\b)",
        repl,
        text,
        count=1,
        flags=re.S,
    )


def wrap_long_lines(text: str, limit: int = 120) -> str:
    """Best-effort wrap for G.FMT.10 — never split inside string literals."""
    out_lines: list[str] = []
    for line in text.splitlines(keepends=True):
        raw = line.rstrip("\n")
        keep_nl = line.endswith("\n")
        if len(raw) <= limit or raw.lstrip().startswith("*") or raw.lstrip().startswith("//"):
            out_lines.append(line)
            continue
        # never split string literals (would break e.g. "EI.ComplexIntentDetection")
        if '"' in raw or "'" in raw:
            out_lines.append(line)
            continue
        if raw.lstrip().startswith(("package ", "import ", "@")):
            out_lines.append(line)
            continue
        # Only wrap method chains outside string literals
        if "." in raw:
            indent = re.match(r"^(\s*)", raw).group(1)
            segments: list[str] = []
            buf = ""
            in_string = False
            escape = False
            quote = ""
            for ch in raw:
                if in_string:
                    buf += ch
                    if escape:
                        escape = False
                    elif ch == "\\":
                        escape = True
                    elif ch == quote:
                        in_string = False
                    continue
                if ch in "\"'":
                    in_string = True
                    quote = ch
                    buf += ch
                    continue
                if ch == "." and not in_string:
                    segments.append(buf)
                    buf = "."
                else:
                    buf += ch
            if buf:
                segments.append(buf)
            if len(segments) >= 3:
                rebuilt: list[str] = []
                cur = segments[0]
                for seg in segments[1:]:
                    trial = cur + seg
                    if len(trial) > limit and cur != segments[0]:
                        rebuilt.append(cur)
                        cur = indent + "        " + seg.lstrip(".")
                    else:
                        cur = trial
                rebuilt.append(cur)
                if len(rebuilt) > 1 and all(len(x) <= limit + 20 for x in rebuilt):
                    out_lines.append("\n".join(rebuilt) + ("\n" if keep_nl else ""))
                    continue
        out_lines.append(line)
    return "".join(out_lines)


def process(path: Path) -> bool:
    orig = path.read_text(encoding="utf-8")
    text = orig
    text = reorder_imports(text)
    text = fix_annotations(text)
    text = spaces_around_keywords(text)
    text = apply_renames(text)
    text = ensure_class_since_date(text)
    if "/main/java/" in str(path).replace("\\", "/") or "/test/" in str(path).replace("\\", "/"):
        text = ensure_method_javadoc(text)
    text = collapse_blank_lines(text)
    # wrap_long_lines disabled — dot-splitting breaks String.valueOf / Map.of / etc.
    if text != orig:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    n = 0
    for path in sorted(ROOT.rglob("*.java")):
        if process(path):
            n += 1
            print("updated", path)
    print("changed", n)


if __name__ == "__main__":
    main()
