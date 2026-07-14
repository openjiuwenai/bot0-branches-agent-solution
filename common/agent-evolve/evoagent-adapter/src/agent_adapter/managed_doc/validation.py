"""V2 write-time validation for managed-doc content (T5).

Lightweight structural check (spec §10 / D9): frontmatter closed + valid YAML +
non-empty body. Does NOT vendor the EDPAgent schema. Called before any write;
failure → 400 with no file change (the service layer never writes on raise).
"""

from __future__ import annotations

import re

import yaml

from agent_adapter.managed_doc.storage import DocStorageError

# Mirrors EDPAgent's _FRONTMATTER_PATTERN: leading ``---`` fence, a YAML block,
# a closing ``---`` fence, then the markdown body. DOTALL so the YAML block may
# span lines; \A/\Z anchor the whole document.
_FRONTMATTER_RE = re.compile(r"\A---\s*\n(.*?)\n---\s*\n(.*)\Z", re.DOTALL)


class InvalidDocContentError(DocStorageError):
    """content failed V2 structural validation (frontmatter/YAML/body)."""


def validate(content: str) -> None:
    """Raise ``InvalidDocContentError`` if content is not a valid doc body.

    1. frontmatter closed (opening + closing ``---`` fences)
    2. frontmatter block parses as YAML (schema-agnostic)
    3. body (after the closing fence) is non-empty
    """
    match = _FRONTMATTER_RE.match(content)
    if not match:
        raise InvalidDocContentError(
            "frontmatter malformed or missing (expecting a leading '---\\n...\\n---\\n' block)"
        )
    fm_yaml, body = match.group(1), match.group(2)
    try:
        yaml.safe_load(fm_yaml)
    except yaml.YAMLError as exc:
        raise InvalidDocContentError(f"frontmatter is not valid YAML: {exc}") from exc
    if not body.strip():
        raise InvalidDocContentError("body is empty")
