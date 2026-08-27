# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Generated Skill metadata inference and normalization."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from skill_builder.application.package_builder import (
    DEFAULT_SKILL_DISPLAY_NAME,
    normalize_skill_slug,
)
from skill_builder.application.file_helpers import (
    _contains_cjk,
    _frontmatter_block,
    _generated_openai_metadata,
    _generated_skill_heading,
    _read_text_artifact,
)


_WORKSPACE_EVALUATION_SUFFIX_RE = re.compile(
    r"(?:\s+(?:20\d{6}\s*)?(?:·|—|-|/)\s*"
    r"(?:批量评测|回归修复验证|收敛复测|干净补测|smoke(?:回归|测试))).*$",
    flags=re.IGNORECASE,
)


def clean_workspace_evaluation_suffix(value: str | None) -> str:
    """Remove batch-evaluation labels from a user-facing workspace title."""
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    if not text:
        return ""
    return _WORKSPACE_EVALUATION_SUFFIX_RE.sub("", text, count=1).strip(" `*_，。；;、:：/|\\+_-—–·（）()【】[]")


def _clean_workspace_title_candidate(value: str | None) -> str:
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    if not text:
        return ""
    text = re.sub(r"^#+\s*", "", text).strip()
    text = re.sub(r"^\d+(?:\.\d+)*[.)、]?\s*", "", text).strip()
    text = text.strip("`*_ \t\r\n")
    text = re.sub(r"^验证报告\s*[—:-]\s*", "", text, flags=re.IGNORECASE).strip()
    text = re.sub(r"^场景理解(?:清单|确认)?\s*[—:-]\s*", "", text, flags=re.IGNORECASE).strip()
    text = re.sub(r"^Skill\s*(?:名称|名)?\s*[：:]\s*", "", text, flags=re.IGNORECASE).strip()
    text = re.sub(r"^(?:interface\.)?display_name\s*[：:]\s*", "", text, flags=re.IGNORECASE).strip()
    text = re.sub(r"^(?:description|short_description|简介|描述|说明)\s*[：:]\s*", "", text, flags=re.IGNORECASE).strip()
    return text.strip("`*_ \t\r\n")[:128]


def _is_default_skill_display_name(value: str | None) -> bool:
    compact = re.sub(r"\s+", "", str(value or "")).lower()
    return not compact or compact in {
        re.sub(r"\s+", "", DEFAULT_SKILL_DISPLAY_NAME).lower(),
        "skill抽取工作区",
        "skill抽取工作台",
        "generatedskill",
        "skillworkspace",
        "skillbuilder",
        "材料流程抽取skill",
        "材料流程抽取工作区",
    }


def _looks_like_workspace_title_sentence(value: str | None) -> bool:
    text = _clean_workspace_title_candidate(value)
    compact = re.sub(r"\s+", "", text)
    return bool(
        len(compact) > 48
        or re.search(r"[。；;、，,]", text)
        or (
            len(compact) >= 16
            and re.search(
                r"^(?:根据|基于|用于|面向|通过|结合|在|按).*(?:生成|提供|完成|执行|输出|支持|实现|沉淀|整理|抽取)",
                text,
            )
        )
    )


def _is_generic_workspace_title_candidate(value: str | None) -> bool:
    normalized = re.sub(r"\s+", "", _clean_workspace_title_candidate(value)).lower()
    if not normalized:
        return True
    return normalized in {
        "验收报告", "验证报告", "场景理解清单", "场景理解", "skill目标", "目标",
        "触发/不触发边界", "触发不触发边界", "输入", "输入要求", "输出", "输出要求",
        "流程", "业务规则", "材料覆盖", "材料覆盖度", "抽取阶段状态", "人工确认记录",
        "检查项", "能力覆盖", "未验证边界", "风险", "skill抽取草稿", "抽取草稿",
        "skill抽取工作区", "skill抽取工作台", "材料流程抽取skill",
    } or bool(re.fullmatch(r"(?:skill)?(?:目标|输入|输出|流程|规则|风险|边界|检查项|能力覆盖)", normalized, flags=re.IGNORECASE))


def _is_high_confidence_workspace_title_candidate(value: str | None) -> bool:
    text = _clean_workspace_title_candidate(value)
    compact = re.sub(r"\s+", "", text)
    invalid_candidate = (
        not compact
        or not _contains_cjk(text)
        or _is_default_skill_display_name(text)
        or _is_generic_workspace_title_candidate(text)
        or _looks_like_workspace_title_sentence(text)
    )
    if invalid_candidate:
        return False
    return bool(
        re.search(
            r"(?:Skill|助手|工具|报告|分析|提醒|检索|归类|报价|查询|核算|跟踪|巡检|校验|复核|审核|评估|监控|采集|比价|建议|指引)$",
            compact,
            flags=re.IGNORECASE,
        )
        or 6 <= len(compact) <= 24
    )


def _chinese_display_title(value: str | None) -> str:
    text = _clean_workspace_title_candidate(value)
    if not text:
        return ""
    text = text.strip(" `*_，。；;、:：/|\\+_-—–·（）()【】[]")
    return text[:128] if _contains_cjk(text) else ""


def generated_skill_frontmatter(root: Path) -> dict[str, Any]:
    skill_md = root / "generated-skill" / "SKILL.md"
    if not skill_md.is_file():
        return {}
    return _frontmatter_block(skill_md.read_text(encoding="utf-8", errors="replace"))


def is_low_confidence_workspace_title(value: str | None) -> bool:
    text = _clean_workspace_title_candidate(value)
    compact = re.sub(r"\s+", "", text)
    if not compact or not _contains_cjk(text):
        return True
    if _is_generic_workspace_title_candidate(text):
        return True
    if len(compact) > 24 or _looks_like_workspace_title_sentence(text):
        return True
    if _is_high_confidence_workspace_title_candidate(text):
        return False
    return len(compact) <= 8


def workspace_title_candidates_from_text(text: str) -> list[str]:
    candidates: list[str] = []
    title_suffix = r"(?:Skill|助手|工具|报告|分析|提醒|检索|归类|报价|查询|核算|跟踪|巡检|校验|建议|复核|审核|评估|管理|监控|采集|比价|指引)"
    title_boundary = r"(?=$|[，。；:：\s])"
    for raw_line in str(text or "").splitlines():
        line = re.sub(r"[*_`>#|]", " ", raw_line).strip()
        line = re.sub(r"\s+", " ", line)
        if not line or not _contains_cjk(line):
            continue
        match = re.search(
            rf"(?:验证报告|验收报告)?\s*[—:-]\s*(?P<title>[\u3400-\u9fffA-Za-z0-9/、 +_-]{{3,80}}?Skill){title_boundary}",
            line,
            flags=re.IGNORECASE,
        )
        if match:
            candidates.append(match.group("title").strip())
        match = re.search(
            rf"(?:Skill\s*目标|Skill\s*名称|展示名称|显示名称|目标|名称|标题)[：:]\s*"
            rf"(?P<title>[^，。；\n]{{2,80}}?{title_suffix}){title_boundary}",
            line,
            flags=re.IGNORECASE,
        )
        if match:
            candidates.append(match.group("title").strip())
        match = re.search(
            rf"(?:生成|提供|完成|输出|构建|沉淀|支持|执行|进行|抽取)"
            rf"(?P<title>[\u3400-\u9fffA-Za-z0-9/、 +_-]{{3,60}}?{title_suffix}){title_boundary}",
            line,
            flags=re.IGNORECASE,
        )
        if match:
            candidates.append(match.group("title").strip())
        title_matches = re.finditer(
            rf"(?P<title>[\u3400-\u9fffA-Za-z0-9/、 +_-]{{3,60}}?{title_suffix}){title_boundary}",
            line,
            flags=re.IGNORECASE,
        )
        for title_match in title_matches:
            candidates.append(title_match.group("title").strip())
    result: list[str] = []
    seen: set[str] = set()
    for candidate in candidates:
        title = re.sub(r"\s+", " ", _clean_workspace_title_candidate(candidate)).strip(" ，。；:：-—")
        invalid_candidate = (
            not title
            or _is_default_skill_display_name(title)
            or _is_generic_workspace_title_candidate(title)
            or _looks_like_workspace_title_sentence(title)
            or not _contains_cjk(title)
        )
        if invalid_candidate:
            continue
        key = title.lower()
        if key not in seen:
            seen.add(key)
            result.append(title[:128])
    return result


def generated_chinese_workspace_title(root: Path, display_name: str | None) -> str:
    candidates: list[str] = []
    fallback_candidates: list[str] = []
    generated_display_name, generated_short_description = _generated_openai_metadata(root)
    if generated_display_name:
        candidates.append(generated_display_name)
    if generated_short_description:
        candidates.extend(workspace_title_candidates_from_text(generated_short_description))
    generated_heading = _generated_skill_heading(root)
    if generated_heading:
        candidates.append(generated_heading)
    if display_name:
        candidates.append(display_name)
    for relative_path in ("validation/scenario_understanding.md", "validation/scenario_summary.md"):
        candidates.extend(workspace_title_candidates_from_text(_read_text_artifact(root / relative_path)))
    description = generated_skill_frontmatter(root).get("description")
    if isinstance(description, str):
        candidates.extend(workspace_title_candidates_from_text(description))
        first_clause = re.split(r"[，。；:：\n]", description.strip(), maxsplit=1)[0].strip()
        if first_clause and _contains_cjk(first_clause):
            fallback_candidates.append(first_clause)
    for candidate in [*candidates, *fallback_candidates]:
        title = _chinese_display_title(candidate)
        if _is_high_confidence_workspace_title_candidate(title):
            return title
    return "业务处理助手"


def workspace_title_should_be_localized(title: str | None) -> bool:
    normalized = re.sub(r"\s+", " ", str(title or "")).strip()
    if not normalized or _is_default_skill_display_name(normalized):
        return True
    # Evaluation/retry labels are test metadata, not a user-facing business
    # workspace name. Let the host replace them after package submission.
    if _WORKSPACE_EVALUATION_SUFFIX_RE.search(normalized):
        return True
    if normalized.lower().startswith(("skill-extract-", "generated-skill", "skill builder", "skill workspace")):
        return True
    return is_low_confidence_workspace_title(normalized)


def display_name_should_be_localized(value: str | None) -> bool:
    text = _clean_workspace_title_candidate(value)
    if _is_default_skill_display_name(text) or not _contains_cjk(text):
        return True
    if _WORKSPACE_EVALUATION_SUFFIX_RE.search(text):
        return True
    return is_low_confidence_workspace_title(text)


def fallback_material_metadata(
    *,
    workspace_title: str,
    workspace_goal: str,
    uses_default_skill_name: bool,
    skill_name: str,
    display_name: str,
    description: str,
    default_description: str,
    entries: list[dict[str, str]],
) -> tuple[str, str, str]:
    material_text = "\n".join(
        [
            workspace_title,
            workspace_goal,
            *[item.get("title", "") for item in entries],
            *[item.get("preview", "") for item in entries],
        ]
    )
    inferred_display = ""
    for candidate in workspace_title_candidates_from_text(material_text):
        localized = _chinese_display_title(candidate)
        if _is_high_confidence_workspace_title_candidate(localized):
            inferred_display = localized
            break
    if not inferred_display:
        inferred_display = display_name if not _is_default_skill_display_name(display_name) else "业务处理助手"
    ascii_words = re.findall(r"[A-Za-z][A-Za-z0-9]{2,}", " ".join(item.get("title", "") for item in entries))
    inferred_name = normalize_skill_slug("-".join(ascii_words[:5])) if ascii_words else "material-workflow-extraction"
    inferred_description = (
        description
        if description != default_description
        else "根据上传材料沉淀可复用业务流程、输入输出和未验证边界。"
    )
    if uses_default_skill_name:
        skill_name = inferred_name
    if _is_default_skill_display_name(display_name):
        display_name = inferred_display
    if description == default_description:
        description = inferred_description
    return normalize_skill_slug(skill_name), display_name[:128], description[:4096]


def fallback_material_display_path(path: str) -> str:
    normalized = str(path or "").replace("\\", "/").strip("/")
    return normalized.rsplit("/", 1)[-1] if normalized else "材料"


def fallback_materials_markdown(markdown: str) -> str:
    def replace_backtick(match: re.Match[str]) -> str:
        value = match.group(1)
        if re.match(r"^(?:inputs|generated-skill|validation|workspace|playwright)/", value):
            return f"`{fallback_material_display_path(value)}`"
        return match.group(0)

    text = re.sub(r"`([^`]+)`", replace_backtick, str(markdown or ""))
    return re.sub(
        r"(->\s*)`?(?:inputs|generated-skill|validation|workspace|playwright)/([^`\s)]+)`?",
        lambda match: f"{match.group(1)}`{fallback_material_display_path(match.group(2))}`",
        text,
    )


def adopt_generated_metadata(
    *,
    root: Path,
    skill_name: str,
    display_name: str,
    description: str,
    tags: list[str],
    default_skill_name: str,
    default_tags: list[str],
    explicit_skill_name: bool,
    explicit_display_name: bool,
    explicit_description: bool,
) -> tuple[str, str, str, list[str], dict[str, Any]]:
    original = {
        "skill_name": skill_name,
        "display_name": display_name,
        "description": description,
        "tags": list(tags),
    }
    skill_frontmatter = generated_skill_frontmatter(root)
    generated_name = skill_frontmatter.get("name")
    if not explicit_skill_name and isinstance(generated_name, str) and generated_name.strip():
        normalized = normalize_skill_slug(generated_name)
        if normalized and normalized != default_skill_name:
            skill_name = normalized
    generated_description = skill_frontmatter.get("description")
    if not explicit_description and isinstance(generated_description, str) and generated_description.strip():
        description = generated_description.strip()[:4096]
    generated_display_name, generated_short_description = _generated_openai_metadata(root)
    generated_display_title = _chinese_display_title(generated_display_name)
    generated_title_is_usable = (
        not explicit_display_name
        and generated_display_title
        and not _is_default_skill_display_name(generated_display_title)
        and _is_high_confidence_workspace_title_candidate(generated_display_title)
    )
    if generated_title_is_usable:
        display_name = generated_display_title[:128]
    elif not explicit_display_name:
        generated_heading_title = _chinese_display_title(_generated_skill_heading(root))
        if (
            generated_heading_title
            and not _is_default_skill_display_name(generated_heading_title)
            and _is_high_confidence_workspace_title_candidate(generated_heading_title)
        ):
            display_name = generated_heading_title
    if display_name_should_be_localized(display_name):
        chinese_display = generated_chinese_workspace_title(root, display_name).strip()
        localized_title_is_usable = (
            chinese_display
            and _contains_cjk(chinese_display)
            and not _is_default_skill_display_name(chinese_display)
            and _is_high_confidence_workspace_title_candidate(chinese_display)
        )
        if localized_title_is_usable:
            display_name = chinese_display[:128]
    display_name = _chinese_display_title(display_name) or "业务处理助手"
    if not explicit_description and not generated_description and generated_short_description:
        description = generated_short_description[:4096]
    if not tags:
        tags = list(default_tags)
    changed = {}
    projected_values = {
        "skill_name": skill_name,
        "display_name": display_name,
        "description": description,
        "tags": tags,
    }
    for key, value in projected_values.items():
        if value != original.get(key):
            changed[key] = {"from": original.get(key), "to": value}
    return skill_name, display_name, description, tags, changed


__all__ = [
    "adopt_generated_metadata",
    "clean_workspace_evaluation_suffix",
    "display_name_should_be_localized",
    "fallback_material_display_path",
    "fallback_material_metadata",
    "fallback_materials_markdown",
    "generated_chinese_workspace_title",
    "generated_skill_frontmatter",
    "is_low_confidence_workspace_title",
    "workspace_title_candidates_from_text",
    "workspace_title_should_be_localized",
]
