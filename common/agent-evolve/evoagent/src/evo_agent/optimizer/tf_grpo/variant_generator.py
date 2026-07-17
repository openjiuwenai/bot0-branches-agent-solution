"""SKILL.md variant generation for TF-GRPO."""

from __future__ import annotations

import re

_FENCE_RE = re.compile(
    r"^```(?:markdown|md)?\s*\n(?P<body>.*?)\n```\s*$",
    re.DOTALL | re.IGNORECASE,
)
_FRONTMATTER_RE = re.compile(r"\A---\s*\n.*?\n---\s*\n?", re.DOTALL)
_ABRUPT_LINE_RE = re.compile(r"""(?:":\s*|,\s*|[\{\[\(]|:\s*)$""")
_SECTION_MARKERS = ("输出契约", "<answer>", "## 依赖")


def skill_document_incompleteness_reason(
    text: str,
    *,
    baseline: str | None = None,
) -> str | None:
    """Return a short reason when SKILL.md looks truncated; otherwise None."""
    raw = (text or "").strip()
    if not raw:
        return "empty"

    fence_count = sum(1 for line in raw.splitlines() if line.strip().startswith("```"))
    if fence_count % 2 != 0:
        return "unclosed_code_fence"

    if raw.count("<answer>") != raw.count("</answer>"):
        return "unclosed_answer_tag"

    last = next((line.rstrip() for line in reversed(raw.splitlines()) if line.strip()), "")
    if last and _ABRUPT_LINE_RE.search(last):
        return "abrupt_line_ending"

    if baseline:
        base = baseline.strip()
        if len(base) >= 500 and len(raw) < int(0.4 * len(base)):
            return "too_short_vs_baseline"
        for marker in _SECTION_MARKERS:
            if marker in base and marker not in raw:
                return f"missing_section:{marker}"
    return None


def is_complete_skill_document(text: str, *, baseline: str | None = None) -> bool:
    return skill_document_incompleteness_reason(text, baseline=baseline) is None


def strip_code_fence(text: str) -> str:
    raw = (text or "").strip()
    match = _FENCE_RE.match(raw)
    if match:
        return match.group("body").strip()
    if raw.startswith("```"):
        lines = raw.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        return "\n".join(lines).strip()
    return raw


def split_frontmatter(content: str) -> tuple[str, str]:
    """Return (frontmatter_with_delimiters_or_empty, body)."""
    match = _FRONTMATTER_RE.match(content or "")
    if not match:
        return "", content or ""
    return match.group(0), content[match.end() :]


def restore_frontmatter(original: str, generated: str, *, preserve: bool) -> str:
    """Keep original YAML frontmatter when preserve is True."""
    generated_clean = strip_code_fence(generated)
    if not preserve:
        return generated_clean
    fm, _ = split_frontmatter(original)
    _, body = split_frontmatter(generated_clean)
    if not fm:
        return generated_clean
    body = body.lstrip("\n")
    return f"{fm}{body}" if body else fm.rstrip() + "\n"


def build_variant_prompt(
    *,
    current_best: str,
    experience_context: str,
    epoch: int,
    max_content_chars: int = 15000,
) -> str:
    content = current_best
    if len(content) > max_content_chars:
        content = content[:max_content_chars] + "\n\n[... 内容已截断 ...]"

    experience_raw = (experience_context or "").strip()
    experience_empty = not experience_raw

    if experience_empty:
        experience_block = """**经验库状态：** 空（尚无历史经验）。

**本轮探索策略（经验为空时强制执行）：**
- 允许**自由探索**：不必保守微调，可对规则/条款做增、删、改、重排。
- 鼓励与当前版本形成**可辨识差异**（不要只改措辞或同义改写）。
- 可尝试的改动类型（本变体至少落实一类，最好两类）：
  1. **新增条款**：补判定门槛、例外、反例、自检步骤、速查表行等
  2. **删除/收紧**：去掉空泛套话、互相打架的规则、易诱发误判的表述
  3. **改写关键路径**：调整责任判定优先级、超时与有责的耦合方式、证据门槛
  4. **结构调整**：合并重复节、把易错点提前、改输出自检清单
- 探索仍须保持文档可执行：保留输出契约与依赖；不要把文档改成空壳或不可用。"""
    else:
        experience_block = f"""**已学习经验（请优先吸收，但仍须产出与其它变体不同的改法）：**

{experience_raw}

**在有经验时：**
- 落实经验中的可执行建议，同时允许补充、删改冲突条款以消除歧义。
- 禁止仅做同义改写；每个变体应有独立的主改进轴。"""

    return f"""请对这份技能文档（SKILL.md）生成一个**多样化**的改进变体。

{experience_block}

**当前最优版本：**
{content}

**多样性要求（所有变体必须遵守）：**
- 相对当前版本，必须有**实质性**差异（规则内容或判定流程变化），禁止原样复制。
- 同一轮多个变体应走**不同改进轴**（例如：判定门槛、流程易错点、输出自检/结构）。
- 允许增、删、改条款；不要只做同义改写或重复堆叠口号式原则。
- 优先写可执行规则与步骤，少写空泛原则。

**其它要求：**
1. **Frontmatter**：若文档以 YAML frontmatter（--- ... ---）开头，**保持其内容不变**
2. 需要时补充清晰示例或边界情况，但不要无谓灌水把全文拉长
3. 只输出完整的 SKILL.md 正文（不要前言、不要解释）
4. 不要用 markdown 代码围栏包裹整份文档
5. 禁止截断：闭合所有代码围栏 / JSON 块 / `<answer>` 标签；若当前版本含「输出契约」「依赖」等章节，必须保留
6. 轮次提示：第 {epoch} 轮

优化后的 SKILL.md：
"""
