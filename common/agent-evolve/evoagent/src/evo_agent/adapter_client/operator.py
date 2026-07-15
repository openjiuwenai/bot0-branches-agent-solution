"""Operator 工厂 — 创建 SkillDocumentOperator 并绑定回写 callback。

设计决策：D5, D7, D13
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

# 先加载 agent_evolving 以解析 openjiuwen PyPI 0.1.13 循环 import
# core.operator → skill_call.base → agent_evolving.protocols
#   → agent_evolving → trainer → core.single_agent → core.operator
import openjiuwen.agent_evolving  # noqa: F401

from evo_agent.adapter_client.client import AdapterClient
from evo_agent.operator.skill_document_operator import SkillDocumentOperator
from evo_agent.protocols import SKILL_CONTENT_TARGET

if TYPE_CHECKING:
    pass


def split_frontmatter(content: str) -> tuple[str, str]:
    """Split a markdown document into YAML frontmatter and body.

    Returns ``(frontmatter, body)``. If the document has no leading YAML
    frontmatter, returns ``("", content)``.
    """
    lines = content.splitlines(keepends=True)
    if not lines or lines[0].strip() != "---":
        return "", content

    for index in range(1, len(lines)):
        if lines[index].strip() == "---":
            return "".join(lines[: index + 1]), "".join(lines[index + 1 :])

    return "", content


class FrontmatterPreservingSkillDocumentOperator(SkillDocumentOperator):
    """Optimize only markdown body while preserving YAML frontmatter on sync."""

    def __init__(
        self,
        skill_name: str,
        initial_content: str = "",
        on_parameter_updated: Any | None = None,
    ) -> None:
        frontmatter, body = split_frontmatter(initial_content)
        self._frontmatter = frontmatter
        self._external_on_parameter_updated = on_parameter_updated
        super().__init__(
            skill_name=skill_name,
            initial_content=body,
            on_parameter_updated=self._forward_parameter_updated,
        )

    def _assemble(self, body: str) -> str:
        if not self._frontmatter:
            return body
        separator = "\n" if body and not body.startswith("\n") else ""
        return f"{self._frontmatter}{separator}{body}"

    def _strip_to_body(self, content: Any) -> str:
        _, body = split_frontmatter(str(content) if content is not None else "")
        return body

    def _forward_parameter_updated(self, target: str, new_body: Any) -> None:
        if self._external_on_parameter_updated is None:
            return
        self._external_on_parameter_updated(target, self._assemble(str(new_body)))

    def set_parameter(self, target: str, value: Any) -> None:
        if target != SKILL_CONTENT_TARGET:
            return
        self._skill_content = self._strip_to_body(value)
        self._forward_parameter_updated(target, self._skill_content)

    def get_state(self) -> dict[str, Any]:
        return {"skill_content": self._assemble(self._skill_content)}

    def load_state(self, state: dict[str, Any]) -> None:
        self._skill_content = self._strip_to_body(state.get("skill_content", ""))
        self._forward_parameter_updated(SKILL_CONTENT_TARGET, self._skill_content)


def build_skill_document_operator(
    skill_name: str,
    initial_content: str,
    adapter_client: AdapterClient,
    *,
    preserve_frontmatter: bool = True,
) -> SkillDocumentOperator:
    """创建一个 SkillDocumentOperator，绑定 skill 回写 callback。

    Parameters
    ----------
    skill_name:
        Skill 名称（如 "product_recommend_skill"）。
    initial_content:
        Skill 文档的初始 Markdown 内容。
        由调用方通过 AdapterClient.skill_content() 获取（D7）。
    adapter_client:
        AdapterClient 实例，用于 callback 中回写更新后的 skill。
    preserve_frontmatter:
        True（默认）：返回 ``FrontmatterPreservingSkillDocumentOperator``——
        写回时冻结原始 frontmatter，仅同步正文。
        False：返回普通 ``SkillDocumentOperator``——frontmatter 全程参与，
        可被 edit 改动并随回写同步。

    Returns
    -------
    SkillDocumentOperator
        可直接传给 Trainer 使用的 operator 实例。

    Notes
    -----
    on_parameter_updated callback 签名是 ``(target: str, content: Any) -> None``：
    SkillDocumentOperator.set_parameter / load_state 调用时第一个参数是 target 名称
    （固定为 "skill_content"），第二个参数是更新后的文档内容。
    Trainer 更新链路是同步的（Trainer → Updater → Operator → callback），
    不能修改 agent-core 的调用链，因此 update_skill 必须用同步 httpx。
    """

    def _on_parameter_updated(target: str, new_content: Any) -> None:
        """skill 文档更新后的回调 — 同步推送给 Adapter。

        Parameters
        ----------
        target:
            被更新的参数名（SkillDocumentOperator 固定传 "skill_content"）。
        new_content:
            更新后的 skill 文档 Markdown 内容。
        """
        adapter_client.update_skill(
            skill_name=skill_name,
            skill_content=str(new_content),
        )

    if preserve_frontmatter:
        return FrontmatterPreservingSkillDocumentOperator(
            skill_name=skill_name,
            initial_content=initial_content,
            on_parameter_updated=_on_parameter_updated,
        )
    return SkillDocumentOperator(
        skill_name=skill_name,
        initial_content=initial_content,
        on_parameter_updated=_on_parameter_updated,
    )
