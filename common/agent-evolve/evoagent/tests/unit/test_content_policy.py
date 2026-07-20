"""ContentPolicy + ProtectedSection 测试（spec F6）。

PreservingContentPolicy：保留 job-start frontmatter + 配置标记的 protected
sections（复用 split_frontmatter）；marker 缺失/重复/交叉/嵌套在 init 阶段
fail-fast 抛 ContentPolicyError。PassthroughPolicy：原样透传。默认 marker 列表
为空。
"""

from __future__ import annotations

import pytest

from evo_agent.adapter_client.content_policy import (
    ContentPolicy,
    ContentPolicyError,
    PassthroughPolicy,
    PreservingContentPolicy,
    ProtectedSection,
)

# ── ProtectedSection ──


class TestProtectedSection:
    def test_frozen_dataclass(self) -> None:
        import dataclasses

        assert dataclasses.is_dataclass(ProtectedSection)
        assert getattr(ProtectedSection, "__dataclass_params__").frozen is True

    def test_fields(self) -> None:
        ps = ProtectedSection(start_marker="<!--A-->", end_marker="<!--/A-->")
        assert ps.start_marker == "<!--A-->"
        assert ps.end_marker == "<!--/A-->"

    def test_immutable(self) -> None:
        ps = ProtectedSection(start_marker="<a>", end_marker="</a>")
        with pytest.raises(Exception):
            ps.start_marker = "<b>"  # type: ignore[misc]


# ── PassthroughPolicy ──


class TestPassthroughPolicy:
    def test_normalize_returns_content_unchanged(self) -> None:
        policy = PassthroughPolicy()
        content = "# title\n\nbody\n"
        assert policy.normalize(content) == content

    def test_is_content_policy(self) -> None:
        assert isinstance(PassthroughPolicy(), ContentPolicy)


# ── PreservingContentPolicy ──


_BASELINE = """\
---
title: agent-rule
---
# Agent Rule

<!--PROTECTED-->
do not change me
<!--/PROTECTED-->

free text
"""

_CANDIDATE_FRONTMATTER_CHANGED = """\
---
title: CHANGED
---
# Agent Rule

<!--PROTECTED-->
LLM tried to overwrite
<!--/PROTECTED-->

free text edited
"""


class TestPreservingContentPolicy:
    def _section(self) -> ProtectedSection:
        return ProtectedSection(start_marker="<!--PROTECTED-->", end_marker="<!--/PROTECTED-->")

    def test_preserves_frontmatter(self) -> None:
        """candidate 改了 frontmatter，normalize 后恢复 baseline frontmatter。"""
        policy = PreservingContentPolicy(_BASELINE, (self._section(),))
        normalized = policy.normalize(_CANDIDATE_FRONTMATTER_CHANGED)
        assert normalized.startswith("---\ntitle: agent-rule\n---")
        assert "title: CHANGED" not in normalized

    def test_preserves_protected_section(self) -> None:
        """candidate 改了 protected section 内容，normalize 后恢复 baseline 区段。"""
        policy = PreservingContentPolicy(_BASELINE, (self._section(),))
        normalized = policy.normalize(_CANDIDATE_FRONTMATTER_CHANGED)
        assert "do not change me" in normalized
        assert "LLM tried to overwrite" not in normalized

    def test_free_text_outside_section_can_change(self) -> None:
        """非 protected 区段允许 candidate 修改。"""
        policy = PreservingContentPolicy(_BASELINE, (self._section(),))
        normalized = policy.normalize(_CANDIDATE_FRONTMATTER_CHANGED)
        assert "free text edited" in normalized

    def test_candidate_duplicate_start_marker_raises(self) -> None:
        """candidate 含重复 start_marker → fail-fast（P2#10，与 baseline 侧对称）。"""
        policy = PreservingContentPolicy(_BASELINE, (self._section(),))
        section = self._section()
        candidate = (
            f"{section.start_marker}body1{section.end_marker}"
            f"{section.start_marker}body2{section.end_marker}"
        )
        with pytest.raises(ContentPolicyError, match="duplicate start_marker in candidate"):
            policy.normalize(candidate)

    def test_no_sections_preserves_frontmatter_only(self) -> None:
        """默认空 marker 列表：仅保留 frontmatter，body 原样透传。"""
        policy = PreservingContentPolicy(_BASELINE, ())
        candidate = "---\ntitle: X\n---\n# new body\n"
        normalized = policy.normalize(candidate)
        assert normalized.startswith("---\ntitle: agent-rule\n---")
        assert "# new body" in normalized

    def test_baseline_no_frontmatter(self) -> None:
        """baseline 无 frontmatter 时，normalize 不注入 frontmatter，仅处理 sections。"""
        baseline = "intro\n<!--P-->\nkeep\n<!--/P-->\nend\n"
        policy = PreservingContentPolicy(
            baseline, (ProtectedSection(start_marker="<!--P-->", end_marker="<!--/P-->"),)
        )
        candidate = "intro\n<!--P-->\nCHANGED\n<!--/P-->\nend\n"
        normalized = policy.normalize(candidate)
        assert normalized.startswith("intro\n")
        assert "keep" in normalized
        assert "CHANGED" not in normalized

    def test_is_content_policy(self) -> None:
        policy = PreservingContentPolicy(_BASELINE, ())
        assert isinstance(policy, ContentPolicy)

    # ── marker fail-fast at init ──

    def test_marker_missing_raises(self) -> None:
        """baseline 缺 end_marker → init 阶段 ContentPolicyError。"""
        baseline = "# no markers here\n"
        with pytest.raises(ContentPolicyError):
            PreservingContentPolicy(
                baseline, (ProtectedSection(start_marker="<!--P-->", end_marker="<!--/P-->"),)
            )

    def test_marker_duplicate_raises(self) -> None:
        """start_marker 出现两次 → ContentPolicyError。"""
        baseline = "<!--P-->\na\n<!--P-->\nb\n<!--/P-->\n"
        with pytest.raises(ContentPolicyError):
            PreservingContentPolicy(
                baseline, (ProtectedSection(start_marker="<!--P-->", end_marker="<!--/P-->"),)
            )

    def test_marker_crossing_raises(self) -> None:
        """两 section 交叉 → ContentPolicyError。"""
        baseline = (
            "<!--A-->\n"  # A start
            "<!--B-->\n"  # B start
            "<!--/A-->\n"  # A end (B still open) → 交叉
            "<!--/B-->\n"
        )
        with pytest.raises(ContentPolicyError):
            PreservingContentPolicy(
                baseline,
                (
                    ProtectedSection(start_marker="<!--A-->", end_marker="<!--/A-->"),
                    ProtectedSection(start_marker="<!--B-->", end_marker="<!--/B-->"),
                ),
            )

    def test_marker_nested_raises(self) -> None:
        """B 嵌套在 A 内 → ContentPolicyError。"""
        baseline = "<!--A-->\n<!--B-->\n<!--/B-->\n<!--/A-->\n"
        with pytest.raises(ContentPolicyError):
            PreservingContentPolicy(
                baseline,
                (
                    ProtectedSection(start_marker="<!--A-->", end_marker="<!--/A-->"),
                    ProtectedSection(start_marker="<!--B-->", end_marker="<!--/B-->"),
                ),
            )

    # ── candidate marker violation at normalize ──

    def test_candidate_missing_marker_pair_raises(self) -> None:
        """candidate 丢了 marker pair → normalize ContentPolicyError（必须保留每对 marker）。"""
        policy = PreservingContentPolicy(_BASELINE, (self._section(),))
        candidate = "---\ntitle: t\n---\n# no protected markers here\n"
        with pytest.raises(ContentPolicyError):
            policy.normalize(candidate)
