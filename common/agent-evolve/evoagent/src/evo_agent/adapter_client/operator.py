"""Operator 工厂 — 创建 SkillDocumentOperator 并绑定回写 callback。

设计决策：D5, D7, D13
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

# 先加载 agent_evolving 以解析 openjiuwen PyPI 0.1.13 循环 import
# core.operator → skill_call.base → agent_evolving.protocols
#   → agent_evolving → trainer → core.single_agent → core.operator
import openjiuwen.agent_evolving  # noqa: F401

from evo_agent.adapter_client.applier import ManagedDocApplier
from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.content_policy import (
    ContentPolicy,
    PassthroughPolicy,
    split_frontmatter,
)
from evo_agent.operator.skill_document_operator import SkillDocumentOperator
from evo_agent.protocols import SKILL_CONTENT_TARGET

if TYPE_CHECKING:
    pass


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


class ManagedDocOperator(SkillDocumentOperator):
    """managed-doc apply operator — 组合 ManagedDocApplier + ContentPolicy（不堆叠继承）。

    set_parameter / load_state 使用相同顺序（ADR §4）：
    ContentPolicy.normalize(content) → Applier.apply_and_wait() → 远程 task 成功
    → 提交 operator 本地状态。apply 失败 → 本地状态保持旧值，向上抛
    ManagedDocApplyError（fatal，终止 job，不跳过 candidate）。
    """

    def __init__(
        self,
        *,
        skill_name: str,
        initial_content: str,
        applier: ManagedDocApplier,
        content_policy: ContentPolicy,
        on_parameter_updated: Any | None = None,
    ) -> None:
        self._applier = applier
        self._policy = content_policy
        self._external_on_parameter_updated = on_parameter_updated
        # set_parameter / load_state 完全覆写，base 的 on_parameter_updated 不会被触发；
        # 远程成功后由 _commit 直接 forward 给外部 callback。
        super().__init__(
            skill_name=skill_name,
            initial_content=initial_content,
            on_parameter_updated=None,
        )

    @property
    def operator_id(self) -> str:
        # canonical id：managed_doc:{kind} 三处一致（operators key / operator id / artifact 目录）
        return self._skill_name

    @property
    def applier(self) -> ManagedDocApplier:
        """暴露 applier 供 runner 读取 records / last_success_hash（baseline 初始化等）。"""
        return self._applier

    def _commit(self, content: str) -> None:
        """远程成功后提交本地状态，并 forward 给外部 callback（optimizer cache 回填源）。"""
        self._skill_content = content
        if self._external_on_parameter_updated is not None:
            self._external_on_parameter_updated(SKILL_CONTENT_TARGET, content)

    def _apply_and_commit(self, raw_content: str) -> None:
        """normalize → apply_and_wait → 成功后提交。失败抛 ManagedDocApplyError，本地不变。"""
        normalized = self._policy.normalize(raw_content)
        # apply 失败在此抛出，_commit 不会被调用 → 本地状态保持旧值
        self._applier.apply_and_wait(normalized)
        self._commit(normalized)

    def set_parameter(self, target: str, value: Any) -> None:
        if target != SKILL_CONTENT_TARGET:
            return
        self._apply_and_commit(str(value) if value is not None else "")

    def get_state(self) -> dict[str, Any]:
        return {"skill_content": self._skill_content}

    def load_state(self, state: dict[str, Any]) -> None:
        self._apply_and_commit(state.get("skill_content", ""))


def build_managed_doc_operator(
    *,
    doc_kind: str,
    initial_content: str,
    adapter_client: AdapterClient,
    content_policy: ContentPolicy | None = None,
    deadline: float = 600.0,
    poll_interval: float = 2.0,
    last_success_hash: str | None = None,
    cancellation_token: Any | None = None,
    phase_callback: Any | None = None,
) -> ManagedDocOperator:
    """创建 ManagedDocOperator（组合 Applier + ContentPolicy，不堆叠继承）。

    Parameters
    ----------
    doc_kind:
        精确 managed-doc kind。canonical id ``managed_doc:{doc_kind}`` 三处一致。
    initial_content:
        job-start baseline 文档内容（用于 operator 初始本地状态）。
    adapter_client:
        AdapterClient（transport 三方法经其 _sync_http）。
    content_policy:
        ContentPolicy（PreservingContentPolicy / PassthroughPolicy）。None 默认 Passthrough。
    deadline / poll_interval / last_success_hash:
        Applier 参数。last_success_hash 用 baseline applied_revision 初始化，
        使 baseline 内容首次 set_parameter 时 hash 命中 no-op。
    """
    applier = ManagedDocApplier(
        adapter_client=adapter_client,
        doc_kind=doc_kind,
        last_success_hash=last_success_hash,
        poll_interval=poll_interval,
        deadline=deadline,
        cancellation_token=cancellation_token,
        phase_callback=phase_callback,
    )
    return ManagedDocOperator(
        skill_name=f"managed_doc:{doc_kind}",
        initial_content=initial_content,
        applier=applier,
        content_policy=content_policy or PassthroughPolicy(),
    )


__all__ = [
    "FrontmatterPreservingSkillDocumentOperator",
    "ManagedDocOperator",
    "build_managed_doc_operator",
    "build_skill_document_operator",
    "split_frontmatter",
]
