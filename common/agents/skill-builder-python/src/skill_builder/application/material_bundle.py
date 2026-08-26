# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Bounded, host-neutral material bundle loading for one Agent turn."""

from __future__ import annotations

import json
import re
from pathlib import PurePosixPath
from typing import Any


MATERIAL_BUNDLE_SCHEMA_VERSION = "skill-builder-material-bundle/v1"
# Keep the first Scenario turn below the request-body limits of compatible
# OpenAI gateways.  The full source files remain in inputs/ and can be read
# through the bounded follow-up rule; this budget only limits the aggregate
# prompt injected into the model conversation.
SCENARIO_MATERIAL_MAX_FILE_CHARS = 4_000
SCENARIO_MATERIAL_MAX_TOTAL_CHARS = 16_000
SCENARIO_MATERIAL_MAX_DIGEST_CHARS = 4_000
SCENARIO_MATERIAL_MAX_INDEX_CHARS = 4_000
SCENARIO_MATERIAL_FOLLOWUP_MAX_CHARS = 8_000
SCENARIO_RECORDING_DIGEST_MAX_CHARS = 10_000
_RECORDING_SOURCE_MAX_CHARS = 1_000_000
MATERIAL_TEXT_SUFFIXES = frozenset(
    {
        ".csv",
        ".html",
        ".ini",
        ".js",
        ".json",
        ".log",
        ".md",
        ".mjs",
        ".ps1",
        ".py",
        ".sh",
        ".sql",
        ".text",
        ".toml",
        ".ts",
        ".txt",
        ".xml",
        ".yaml",
        ".yml",
    }
)
_PARSED_SUFFIX = "_parsed.md"
_RECORDING_MARKERS = ("这是用户录制的网页操作证据", "## 录制步骤")
_RECORDING_STEP_HEADING = re.compile(
    r"^###\s*步骤\s*(?P<number>\d+)\s*[：:]\s*(?P<action>[^\n]+)$",
    re.MULTILINE,
)
_RECORDING_FIELD = re.compile(
    r"^-\s*(?P<name>URL|页面标题|操作目标|截图)\s*[：:]\s*(?P<value>.*)$",
    re.MULTILINE,
)
_RECORDING_METADATA_FIELDS = {
    "业务目标": "goal",
    "起始 URL": "startUrl",
    "当前 URL": "currentUrl",
    "状态": "status",
}


def material_path_is_model_readable_text(path: str) -> bool:
    """Return whether one uploaded material can safely enter a text tool result."""

    return PurePosixPath(str(path or "").replace("\\", "/")).suffix.lower() in MATERIAL_TEXT_SUFFIXES


def _bounded_text(value: Any, *, limit: int) -> str:
    return " ".join(str(value or "").split()).strip()[:limit]


def _evenly_select(values: list[Any], *, limit: int) -> list[Any]:
    """Select deterministic coverage across a long ordered recording."""

    if len(values) <= limit:
        return values
    if limit <= 1:
        return values[:1]
    indexes = {
        round(index * (len(values) - 1) / (limit - 1))
        for index in range(limit)
    }
    return [values[index] for index in sorted(indexes)]


def _recording_page_excerpt(block: str) -> str:
    marker = re.search(
        r"页面文本预览\s*[：:]\s*\n+```(?:text)?\s*\n(?P<text>.*?)\n```",
        block,
        re.DOTALL | re.IGNORECASE,
    )
    if marker is None:
        return ""
    text = _bounded_text(marker.group("text"), limit=4_000)
    if len(text) <= 700:
        return text
    return f"{text[:420].rstrip()} … {text[-260:].lstrip()}"


def _recording_digest(content: str, *, path: str) -> dict[str, Any] | None:
    """Project a complete web recording into a bounded evidence digest.

    The model must not own byte offsets for long recordings.  Core scans the
    complete source once, keeps exact material excerpts, and exposes coverage
    across the beginning, middle and end of the interaction sequence.
    """

    if not all(marker in content for marker in _RECORDING_MARKERS):
        return None
    title_match = re.search(r"^#\s+(.+)$", content, re.MULTILINE)
    metadata: dict[str, str] = {}
    for source_name, target_name in _RECORDING_METADATA_FIELDS.items():
        match = re.search(
            rf"^-\s*{re.escape(source_name)}\s*[：:]\s*(.+)$",
            content,
            re.MULTILINE,
        )
        if match is not None:
            metadata[target_name] = _bounded_text(match.group(1), limit=800)

    matches = list(_RECORDING_STEP_HEADING.finditer(content))
    interactions: list[dict[str, Any]] = []
    observed_states: list[dict[str, Any]] = []
    seen_interactions: set[tuple[str, str, str]] = set()
    seen_states: set[str] = set()
    observed_urls: list[str] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(content)
        block = content[match.end() : end]
        fields = {
            item.group("name"): _bounded_text(item.group("value"), limit=800)
            for item in _RECORDING_FIELD.finditer(block)
        }
        step_number = int(match.group("number"))
        action = _bounded_text(match.group("action"), limit=80)
        url = fields.get("URL", "")
        target = fields.get("操作目标", "")
        interaction_key = (action.lower(), target, url)
        if interaction_key not in seen_interactions:
            seen_interactions.add(interaction_key)
            interaction = {
                "step": step_number,
                "action": action,
                "target": target,
                "url": url,
            }
            title = fields.get("页面标题", "")
            if title and title != "N/A":
                interaction["pageTitle"] = title
            interactions.append({key: value for key, value in interaction.items() if value not in (None, "")})
        if url and url not in observed_urls:
            observed_urls.append(url)
        excerpt = _recording_page_excerpt(block)
        if excerpt and excerpt not in seen_states:
            seen_states.add(excerpt)
            observed_states.append(
                {
                    "step": step_number,
                    "url": url,
                    "textExcerpt": excerpt,
                }
            )

    digest = {
        "schemaVersion": "skill-builder-web-recording-digest/v1",
        "evidenceRef": path,
        "title": _bounded_text(title_match.group(1), limit=300) if title_match else "",
        **metadata,
        "observedStepCount": len(matches),
        "observedUrls": _evenly_select(observed_urls, limit=16),
        "interactions": _evenly_select(interactions, limit=48),
        "observedStates": _evenly_select(observed_states, limit=14),
        "evidenceBoundary": (
            "仅证明录制中观察到的页面、操作和文本；不等同于已验证的可复用浏览器实现。"
        ),
    }
    # Keep the digest bounded even for unusually verbose recordings.  Remove
    # page states first, then interactions, while retaining whole-flow
    # sampling and the exact source path used by evidenceRefs.
    while len(json.dumps(digest, ensure_ascii=False)) > SCENARIO_RECORDING_DIGEST_MAX_CHARS:
        states = digest.get("observedStates") or []
        interactions_value = digest.get("interactions") or []
        if len(states) > 4:
            digest["observedStates"] = _evenly_select(states, limit=max(4, len(states) - 2))
        elif len(interactions_value) > 16:
            digest["interactions"] = _evenly_select(
                interactions_value,
                limit=max(16, len(interactions_value) - 4),
            )
        else:
            break
    return digest


def _fit_recording_digest(value: dict[str, Any], *, max_chars: int) -> dict[str, Any] | None:
    """Fit a recording digest inside the remaining aggregate prompt budget."""

    if max_chars < 800:
        return None
    digest = json.loads(json.dumps(value, ensure_ascii=False))
    while len(json.dumps(digest, ensure_ascii=False)) > max_chars:
        states = digest.get("observedStates") or []
        interactions = digest.get("interactions") or []
        urls = digest.get("observedUrls") or []
        if len(states) > 2:
            digest["observedStates"] = _evenly_select(states, limit=max(2, len(states) - 1))
        elif len(interactions) > 8:
            digest["interactions"] = _evenly_select(
                interactions,
                limit=max(8, len(interactions) - 2),
            )
        elif len(urls) > 4:
            digest["observedUrls"] = _evenly_select(urls, limit=max(4, len(urls) - 2))
        elif digest.get("observedStates"):
            digest["observedStates"] = []
        elif digest.get("interactions"):
            digest["interactions"] = []
        elif digest.get("observedUrls"):
            digest["observedUrls"] = []
        else:
            break
    return digest if len(json.dumps(digest, ensure_ascii=False)) <= max_chars else None


def _read_complete_recording_source(
    accessor: Any,
    *,
    path: str,
    first_result: dict[str, Any],
) -> tuple[str, bool]:
    """Read recording continuation inside Core, never through model cursors."""

    parts = [str(first_result.get("content") or "")]
    next_offset = int(first_result.get("next_offset") or len(parts[0]))
    truncated = bool(first_result.get("truncated"))
    while truncated and sum(len(part) for part in parts) < _RECORDING_SOURCE_MAX_CHARS:
        result = accessor.read_workspace_file(
            path=path,
            offset=next_offset,
            length=min(256_000, _RECORDING_SOURCE_MAX_CHARS - sum(len(part) for part in parts)),
        )
        if not result.get("ok"):
            return "".join(parts), False
        chunk = str(result.get("content") or "")
        if not chunk:
            return "".join(parts), False
        parts.append(chunk)
        previous_offset = next_offset
        next_offset = int(result.get("next_offset") or previous_offset + len(chunk))
        if next_offset <= previous_offset:
            return "".join(parts), False
        truncated = bool(result.get("truncated"))
    return "".join(parts)[:_RECORDING_SOURCE_MAX_CHARS], not truncated


def material_followup_read_error(
    path: str,
    *,
    bundle_loaded: bool,
    followup_paths: set[str] | frozenset[str],
    consumed_paths: set[str] | frozenset[str],
) -> str | None:
    """Return the Scenario material-read policy error for one direct read.

    Scenario must enter through the aggregate bundle.  Direct file reads are
    then limited to one pass over files explicitly marked truncated or omitted.
    This makes the bounded material context a runtime invariant rather than a
    prompt suggestion.
    """

    normalized = str(path or "").replace("\\", "/").lstrip("./")
    if not normalized.startswith("inputs/"):
        return None
    if not bundle_loaded:
        return "material_bundle_required_first"
    if normalized not in followup_paths or normalized in consumed_paths:
        return "material_bundle_already_contains_file"
    return None


def _candidate_paths(entries: list[dict[str, Any]]) -> list[str]:
    files = [
        str(item.get("path") or "").replace("\\", "/")
        for item in entries
        if isinstance(item, dict) and item.get("type") == "file"
    ]
    readable = [path for path in files if material_path_is_model_readable_text(path)]
    # Parsed Office/PDF copies contain the usable evidence. Put them before raw
    # text siblings so a bounded bundle never spends its budget on duplicates.
    return sorted(set(readable), key=lambda path: (not path.lower().endswith(_PARSED_SUFFIX), path.lower()))


def read_material_bundle(
    accessor: Any,
    *,
    materials_markdown: str = "",
    max_files: int = 24,
    max_file_chars: int = 24_000,
    max_total_chars: int = 96_000,
    max_digest_chars: int = 24_000,
    max_index_chars: int = 16_000,
) -> dict[str, Any]:
    """Load a deterministic material index and bounded readable previews.

    The accessor is supplied by the host (local workspace or Jiuwenbox), so
    this function does not bypass sandbox boundaries. One Agent tool call can
    now replace repeated list/read planning without changing evidence scope.
    """

    listing = accessor.list_workspace_files(path="inputs", recursive=True, max_depth=8)
    if not listing.get("ok"):
        return {
            "ok": False,
            "schemaVersion": MATERIAL_BUNDLE_SCHEMA_VERSION,
            "error": str(listing.get("error") or "material_listing_failed"),
            "message": str(listing.get("message") or "无法列出 inputs/ 材料。"),
        }

    digest_result = accessor.read_workspace_file(path="workspace/material_digest.md")
    digest = str(digest_result.get("content") or "") if digest_result.get("ok") else ""
    files: list[dict[str, Any]] = []
    consumed = len(digest) + len(materials_markdown)
    omitted: list[str] = []
    for path in _candidate_paths(listing.get("entries") or []):
        if len(files) >= max(1, max_files) or consumed >= max_total_chars:
            omitted.append(path)
            continue
        result = accessor.read_workspace_file(path=path)
        if not result.get("ok"):
            files.append({"path": path, "readable": False, "error": result.get("error")})
            continue
        content = str(result.get("content") or "")
        source_complete = not bool(result.get("truncated"))
        if any(marker in content for marker in _RECORDING_MARKERS):
            content, source_complete = _read_complete_recording_source(
                accessor,
                path=path,
                first_result=result,
            )
        remaining = max(0, max_total_chars - consumed)
        recording_digest = _recording_digest(content, path=path)
        if recording_digest is not None:
            recording_digest = _fit_recording_digest(
                recording_digest,
                max_chars=remaining,
            )
        if any(marker in content for marker in _RECORDING_MARKERS) and recording_digest is None:
            omitted.append(path)
            continue
        recording_digest_size = (
            len(json.dumps(recording_digest, ensure_ascii=False))
            if recording_digest is not None
            else 0
        )
        limit = min(max_file_chars, max(0, remaining - recording_digest_size))
        preview = content[:limit]
        consumed += len(preview) + recording_digest_size
        preview_truncated = len(preview) < len(content)
        item = {
            "path": path,
            "readable": True,
            "sizeBytes": int(result.get("size_bytes") or 0),
            "truncated": (not source_complete) if recording_digest is not None else preview_truncated,
            "content": preview,
        }
        if recording_digest is not None:
            item.update(
                {
                    "previewTruncated": preview_truncated,
                    "coverageComplete": source_complete,
                    "recordingDigest": recording_digest,
                    "message": (
                        "控制器已扫描完整录屏并生成确定性摘要；不要再按 offset 读取该文件。"
                        if source_complete
                        else "录屏超过控制器摘要上限；仅在确有必要时补读一次。"
                    ),
                }
            )
        files.append(item)

    all_entries = listing.get("entries") or []
    return {
        "ok": True,
        "schemaVersion": MATERIAL_BUNDLE_SCHEMA_VERSION,
        "materialIndex": str(materials_markdown or "")[:max(1, max_index_chars)],
        "materialDigest": digest[:max(1, max_digest_chars)],
        "files": files,
        "allEntries": [
            {
                "path": str(item.get("path") or ""),
                "type": str(item.get("type") or ""),
                "sizeBytes": int(item.get("size_bytes") or 0),
            }
            for item in all_entries[:500]
            if isinstance(item, dict)
        ],
        "omittedReadableFiles": omitted,
        "truncated": bool(omitted or listing.get("truncated")),
    }


__all__ = [
    "MATERIAL_TEXT_SUFFIXES",
    "MATERIAL_BUNDLE_SCHEMA_VERSION",
    "SCENARIO_MATERIAL_FOLLOWUP_MAX_CHARS",
    "SCENARIO_MATERIAL_MAX_DIGEST_CHARS",
    "SCENARIO_MATERIAL_MAX_FILE_CHARS",
    "SCENARIO_MATERIAL_MAX_INDEX_CHARS",
    "SCENARIO_MATERIAL_MAX_TOTAL_CHARS",
    "SCENARIO_RECORDING_DIGEST_MAX_CHARS",
    "material_followup_read_error",
    "material_path_is_model_readable_text",
    "read_material_bundle",
]
