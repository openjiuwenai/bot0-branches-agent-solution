"""ContentPolicy — managed-doc 内容归一化策略（spec F6）。

PreservingContentPolicy 保留 job-start baseline 的 frontmatter + 配置标记的
protected sections：marker 缺失/重复/交叉/嵌套在 init 阶段 fail-fast 抛
ContentPolicyError；candidate 必须保留每对 marker，normalize 用 baseline 区段
替换 candidate 对应区段。PassthroughPolicy 原样透传。默认 marker 列表为空。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


def split_frontmatter(content: str) -> tuple[str, str]:
    """Split a markdown document into YAML frontmatter and body.

    Returns ``(frontmatter, body)``. If the document has no leading YAML
    frontmatter, returns ``("", content)``. Defined in this leaf module so
    ``operator.py`` and ``content_policy.py`` can share it without a circular
    import (operator imports from content_policy, not the reverse).
    """
    lines = content.splitlines(keepends=True)
    if not lines or lines[0].strip() != "---":
        return "", content

    for index in range(1, len(lines)):
        if lines[index].strip() == "---":
            return "".join(lines[: index + 1]), "".join(lines[index + 1 :])

    return "", content


class ContentPolicyError(Exception):
    """protected section marker 校验失败（缺失/重复/交叉/嵌套）或 candidate
    丢失 marker pair。fail-fast，不静默修复。"""


@dataclass(frozen=True)
class ProtectedSection:
    """一对 protected section 边界 marker（adapter-client 侧 frozen DTO）。

    runner 把 config.py 的 ``ProtectedSectionConfig`` 映射为此类型后传给 factory。
    """

    start_marker: str
    end_marker: str


class ContentPolicy(ABC):
    """managed-doc 内容归一化策略。normalize 在 Applier.apply_and_wait 之前执行。"""

    @abstractmethod
    def normalize(self, content: str) -> str:
        """把 candidate 内容归一化（保留 baseline 必须不变的部分）。"""


class PassthroughPolicy(ContentPolicy):
    """原样透传，不做任何归一化。"""

    def normalize(self, content: str) -> str:
        return content


@dataclass(frozen=True)
class _CapturedSection:
    """baseline 中已校验的 protected section span（[start, end) 字节偏移）。"""

    start: int
    end: int
    text: str
    start_marker: str
    end_marker: str


class PreservingContentPolicy(ContentPolicy):
    """保留 baseline frontmatter + protected sections。

    init 时从 baseline 捕获 frontmatter 与每对 marker 圈定的完整原文，并校验
    marker 唯一/不交叉/不嵌套；违反立即抛 ``ContentPolicyError``。normalize 把
    candidate 的 frontmatter 与各 protected section 区段替换为 baseline 的版本，
    其余 body 允许 candidate 修改。
    """

    def __init__(
        self,
        baseline_content: str,
        protected_sections: tuple[ProtectedSection, ...] = (),
    ) -> None:
        base_frontmatter, base_body = split_frontmatter(baseline_content)
        self._frontmatter = base_frontmatter
        self._body = base_body
        self._sections = self._capture_sections(base_body, protected_sections)

    @staticmethod
    def _capture_sections(
        body: str,
        protected_sections: tuple[ProtectedSection, ...],
    ) -> tuple[_CapturedSection, ...]:
        """校验 marker 并捕获 baseline span。"""
        spans: list[_CapturedSection] = []
        for section in protected_sections:
            start_idx = body.find(section.start_marker)
            if start_idx == -1:
                raise ContentPolicyError(
                    f"start_marker not found in baseline: {section.start_marker!r}"
                )
            if body.count(section.start_marker) > 1:
                raise ContentPolicyError(
                    f"duplicate start_marker in baseline: {section.start_marker!r}"
                )
            end_idx = body.find(section.end_marker, start_idx)
            if end_idx == -1:
                raise ContentPolicyError(
                    f"end_marker not found after start in baseline: {section.end_marker!r}"
                )
            if body.count(section.end_marker) > 1:
                raise ContentPolicyError(
                    f"duplicate end_marker in baseline: {section.end_marker!r}"
                )
            span_end = end_idx + len(section.end_marker)
            spans.append(
                _CapturedSection(
                    start=start_idx,
                    end=span_end,
                    text=body[start_idx:span_end],
                    start_marker=section.start_marker,
                    end_marker=section.end_marker,
                )
            )
        # 校验无交叉/嵌套：按 start 排序后 end 不得越过下一个 start
        ordered = sorted(spans, key=lambda s: s.start)
        for prev, nxt in zip(ordered, ordered[1:]):
            if prev.end > nxt.start:
                raise ContentPolicyError("protected sections must not cross or nest")
        # 校验无重复 span（同区间捕获两次）
        if len({(s.start, s.end) for s in ordered}) != len(ordered):
            raise ContentPolicyError("duplicate protected section span")
        return tuple(ordered)

    def _replace_sections(self, body: str) -> str:
        """用 baseline 区段替换 candidate body 中对应 marker pair。"""
        # 从后往前替换，避免偏移漂移。
        result = body
        for section in sorted(self._sections, key=lambda s: s.start, reverse=True):
            start_idx = result.find(section.start_marker)
            if start_idx == -1:
                raise ContentPolicyError(f"candidate lost start_marker: {section.start_marker!r}")
            if result.count(section.start_marker) > 1:
                raise ContentPolicyError(
                    f"duplicate start_marker in candidate: {section.start_marker!r}"
                )
            end_idx = result.find(section.end_marker, start_idx)
            if end_idx == -1:
                raise ContentPolicyError(f"candidate lost end_marker: {section.end_marker!r}")
            if result.count(section.end_marker) > 1:
                raise ContentPolicyError(
                    f"duplicate end_marker in candidate: {section.end_marker!r}"
                )
            span_end = end_idx + len(section.end_marker)
            result = result[:start_idx] + section.text + result[span_end:]
        return result

    def normalize(self, content: str) -> str:
        # candidate frontmatter 一律丢弃，用 baseline frontmatter 重组。
        _, cand_body = split_frontmatter(content)
        normalized_body = self._replace_sections(cand_body)
        if not self._frontmatter:
            return normalized_body
        # 复用 split_frontmatter 的分隔约定：frontmatter 已含结尾 ---\n
        if self._frontmatter.endswith("\n"):
            return self._frontmatter + normalized_body
        return self._frontmatter + "\n" + normalized_body
