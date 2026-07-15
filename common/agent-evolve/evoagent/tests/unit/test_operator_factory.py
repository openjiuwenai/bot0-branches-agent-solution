"""Operator 工厂单元测试 — build_skill_document_operator。"""

from __future__ import annotations

from unittest.mock import MagicMock

# 先加载 agent_evolving 以解析 vendor 循环 import
import evo_agent.optimizer.skill_document  # noqa: F401
from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.operator import (
    FrontmatterPreservingSkillDocumentOperator,
    build_skill_document_operator,
    split_frontmatter,
)


class TestBuildSkillDocumentOperator:
    def test_creates_operator(self) -> None:
        """返回 SkillDocumentOperator 实例。"""
        from evo_agent.operator.skill_document_operator import (
            SkillDocumentOperator,
        )

        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="test_skill",
            initial_content="# Test Skill",
            adapter_client=mock_client,
        )
        assert isinstance(op, SkillDocumentOperator)
        assert isinstance(op, FrontmatterPreservingSkillDocumentOperator)

    def test_callback_triggers_update_skill(self) -> None:
        """on_parameter_updated 回调触发 adapter_client.update_skill。

        Vendor SkillDocumentOperator.set_parameter / load_state 调用签名为
        ``on_parameter_updated(target, content)`` —— 第一个参数是 target 名称
        （固定为 "skill_content"），第二个参数是更新后的 Markdown 内容。
        """
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="my_skill",
            initial_content="initial",
            adapter_client=mock_client,
        )

        # 直接调用回调，模拟 vendor 真实调用顺序: (target, content)
        callback = op._on_parameter_updated
        assert callback is not None
        callback("skill_content", "# New Content")

        mock_client.update_skill.assert_called_once_with(
            skill_name="my_skill",
            skill_content="# New Content",
        )

    def test_callback_via_set_parameter(self) -> None:
        """通过 SkillDocumentOperator.set_parameter 端到端验证 callback 链路。

        直接调用 vendor API，确保 Trainer 真实路径下内容正确推送。
        """
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="my_skill",
            initial_content="initial",
            adapter_client=mock_client,
        )

        # 通过 vendor 的 set_parameter 触发 callback
        op.set_parameter("skill_content", "# Optimized Skill\n\nNew content.")

        mock_client.update_skill.assert_called_once_with(
            skill_name="my_skill",
            skill_content="# Optimized Skill\n\nNew content.",
        )

    def test_callback_via_load_state(self) -> None:
        """通过 SkillDocumentOperator.load_state 端到端验证 callback 链路。

        load_state 也会触发 on_parameter_updated，确保回滚路径下也能正确同步。
        """
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="my_skill",
            initial_content="initial",
            adapter_client=mock_client,
        )

        # 重置 mock 避免初始状态干扰
        mock_client.reset_mock()

        # 通过 vendor 的 load_state 触发 callback
        op.load_state({"skill_content": "# Rolled Back Content"})

        mock_client.update_skill.assert_called_once_with(
            skill_name="my_skill",
            skill_content="# Rolled Back Content",
        )

    def test_callback_is_sync(self) -> None:
        """callback 不涉及 coroutine（直接调用，无 await）。"""
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="test_skill",
            initial_content="content",
            adapter_client=mock_client,
        )

        callback = op._on_parameter_updated
        assert callback is not None
        # 调用不应返回 coroutine — vendor 签名: (target, content)
        result = callback("skill_content", "new content")
        assert not hasattr(result, "__await__")

    def test_operator_name(self) -> None:
        """operator._skill_name == skill_name。"""
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="product_recommend_skill",
            initial_content="content",
            adapter_client=mock_client,
        )
        assert op._skill_name == "product_recommend_skill"

    def test_preserves_frontmatter_on_set_parameter(self) -> None:
        """优化正文时，回写给 Adapter 的仍是完整文档。"""
        mock_client = MagicMock(spec=AdapterClient)
        initial = "---\nname: demo\ndescription: test\n---\n\n# Title\nold body\n"
        op = build_skill_document_operator(
            skill_name="demo_skill",
            initial_content=initial,
            adapter_client=mock_client,
        )

        op.set_parameter("skill_content", "# Title\nnew body\n")

        mock_client.update_skill.assert_called_once_with(
            skill_name="demo_skill",
            skill_content="---\nname: demo\ndescription: test\n---\n\n# Title\nnew body\n",
        )

    def test_get_state_returns_full_document(self) -> None:
        """快照/报告读取到的是完整文档，而不是仅正文。"""
        mock_client = MagicMock(spec=AdapterClient)
        initial = "---\nname: demo\n---\n\n# Title\nbody\n"
        op = build_skill_document_operator(
            skill_name="demo_skill",
            initial_content=initial,
            adapter_client=mock_client,
        )

        state = op.get_state()

        assert state["skill_content"] == initial

    def test_load_state_ignores_frontmatter_edits(self) -> None:
        """load_state 即使收到完整文档，也只采纳正文修改。"""
        mock_client = MagicMock(spec=AdapterClient)
        initial = "---\nname: demo\ndescription: keep\n---\n\n# Title\nbody\n"
        op = build_skill_document_operator(
            skill_name="demo_skill",
            initial_content=initial,
            adapter_client=mock_client,
        )
        mock_client.reset_mock()

        op.load_state(
            {
                "skill_content": (
                    "---\nname: changed\ndescription: changed\n---\n# Title\nnew body\n"
                )
            }
        )

        mock_client.update_skill.assert_called_once_with(
            skill_name="demo_skill",
            skill_content="---\nname: demo\ndescription: keep\n---\n\n# Title\nnew body\n",
        )


class TestPreserveFrontmatterAcceptanceCriteria:
    """AC3/AC4: 写回路径在两种开关下的 frontmatter 行为。

    AC1/AC2（LLM ``## Current Skill`` prompt 含/不含 frontmatter）属 optimizer
    层 ``_llm_skill_view`` 行为，见 ``tests/unit/test_llm_skill_view.py`` 的
    reflect/aggregate/select spy 用例。
    """

    def test_ac3_preserve_true_writeback_keeps_frontmatter_and_optimized_body(self) -> None:
        """AC3: preserve=True 回写 Adapter 含原始 frontmatter + 优化后 body。"""
        mock_client = MagicMock(spec=AdapterClient)
        initial = "---\nname: demo\ndescription: keep\n---\n\n# Title\nold body\n"
        op = build_skill_document_operator(
            skill_name="demo_skill",
            initial_content=initial,
            adapter_client=mock_client,
            preserve_frontmatter=True,
        )

        # LLM 仅产出优化后 body（frontmatter 对 LLM 不可见，不会出现在 edit）
        op.set_parameter("skill_content", "# Title\nnew body\n")

        mock_client.update_skill.assert_called_once_with(
            skill_name="demo_skill",
            # 原始 frontmatter 冻结 + 优化后 body
            skill_content="---\nname: demo\ndescription: keep\n---\n\n# Title\nnew body\n",
        )

    def test_ac4_preserve_false_frontmatter_editable_and_written_back(self) -> None:
        """AC4: preserve=False 时 frontmatter 可被 edit 改动并随回写同步。"""
        mock_client = MagicMock(spec=AdapterClient)
        initial = "---\nname: demo\ndescription: old\n---\n\n# Title\nold body\n"
        op = build_skill_document_operator(
            skill_name="demo_skill",
            initial_content=initial,
            adapter_client=mock_client,
            preserve_frontmatter=False,
        )

        # LLM 可见 frontmatter，可直接 edit frontmatter 字段 + body
        op.set_parameter(
            "skill_content",
            "---\nname: demo\ndescription: NEW\n---\n\n# Title\nnew body\n",
        )

        mock_client.update_skill.assert_called_once_with(
            skill_name="demo_skill",
            skill_content="---\nname: demo\ndescription: NEW\n---\n\n# Title\nnew body\n",
        )

    def test_ac3_preserve_true_frontmatter_absent_passthrough(self) -> None:
        """AC3 边界: preserve=True 但 skill 无 frontmatter 时回写 body 原样。"""
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="demo_skill",
            initial_content="# Title\nold body\n",
            adapter_client=mock_client,
            preserve_frontmatter=True,
        )
        op.set_parameter("skill_content", "# Title\nnew body\n")
        mock_client.update_skill.assert_called_once_with(
            skill_name="demo_skill",
            skill_content="# Title\nnew body\n",
        )


class TestSplitFrontmatterExport:
    def test_split_frontmatter_separates_yaml_and_body(self) -> None:
        """split_frontmatter 可从包内导入，正确切分 frontmatter / body。"""
        content = "---\nname: demo\n---\n\n# Title\nbody\n"
        frontmatter, body = split_frontmatter(content)
        assert frontmatter == "---\nname: demo\n---\n"
        assert body == "\n# Title\nbody\n"

    def test_split_frontmatter_no_frontmatter(self) -> None:
        """无 frontmatter 时返回空串 + 原文。"""
        frontmatter, body = split_frontmatter("# Title only\n")
        assert frontmatter == ""
        assert body == "# Title only\n"


class TestPreserveFrontmatterSwitch:
    def test_default_preserve_true_returns_frontmatter_preserving(self) -> None:
        """preserve_frontmatter 默认 True → FrontmatterPreserving 子类。"""
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="s",
            initial_content="# x",
            adapter_client=mock_client,
        )
        assert isinstance(op, FrontmatterPreservingSkillDocumentOperator)

    def test_preserve_false_returns_plain_operator(self) -> None:
        """preserve_frontmatter=False → 普通 SkillDocumentOperator（不 strip）。"""
        from evo_agent.operator.skill_document_operator import SkillDocumentOperator

        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="s",
            initial_content="---\nname: demo\n---\n# x",
            adapter_client=mock_client,
            preserve_frontmatter=False,
        )
        assert type(op) is SkillDocumentOperator
        assert not isinstance(op, FrontmatterPreservingSkillDocumentOperator)

    def test_preserve_false_set_parameter_does_not_strip(self) -> None:
        """preserve=False 时 set_parameter 回写完整内容（含 frontmatter），不 strip。"""
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="s",
            initial_content="---\nname: demo\n---\n# old",
            adapter_client=mock_client,
            preserve_frontmatter=False,
        )
        op.set_parameter(
            "skill_content",
            "---\nname: changed\n---\n# new body\n",
        )
        mock_client.update_skill.assert_called_once_with(
            skill_name="s",
            skill_content="---\nname: changed\n---\n# new body\n",
        )
