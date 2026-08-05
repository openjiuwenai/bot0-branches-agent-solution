"""ManagedDocOperator + factory 测试（spec F6 提交时序）。

组合 ManagedDocApplier + ContentPolicy（不堆叠继承）。set_parameter / load_state
顺序：ContentPolicy.normalize → Applier.apply_and_wait → 远程成功 → 提交本地状态。
apply 失败 → 本地状态不变 + 抛 ManagedDocApplyError。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import pytest

from evo_agent.adapter_client.applier import AppliedDocument
from evo_agent.adapter_client.content_policy import (
    PassthroughPolicy,
    PreservingContentPolicy,
    ProtectedSection,
)
from evo_agent.adapter_client.operator import build_managed_doc_operator
from evo_agent.errors import ManagedDocApplyError
from evo_agent.protocols import SKILL_CONTENT_TARGET


@dataclass
class FakeApplier:
    """脚本化 Applier：记录传入内容，按队列返回 AppliedDocument 或抛异常。"""

    apply_results: list[Any] = field(default_factory=list)
    calls: list[str] = field(default_factory=list)
    last_success_hash: str | None = None

    def apply_and_wait(self, content: str) -> AppliedDocument:
        self.calls.append(content)
        if not self.apply_results:
            # 默认成功
            return AppliedDocument(
                content_hash="h",
                task_id=None,
                noop=False,
                final_status="SUCCEEDED",
                recovered=False,
                post_time=0.0,
                poll_time=0.0,
                total_time=0.0,
            )
        r = self.apply_results.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


def _make_operator(
    initial_content: str,
    applier: FakeApplier,
    *,
    policy: Any | None = None,
) -> Any:
    """构造 ManagedDocOperator（直接实例化以注入 FakeApplier）。"""
    from evo_agent.adapter_client.operator import ManagedDocOperator

    return ManagedDocOperator(
        skill_name="managed_doc:agent_rule",
        initial_content=initial_content,
        applier=applier,
        content_policy=policy or PassthroughPolicy(),
    )


# ── commit timing ──


def test_operator_commits_local_state_only_after_remote_success() -> None:
    """远程成功后才提交本地状态：get_state 反映已生效内容。"""
    applier = FakeApplier()
    op = _make_operator("old content", applier)
    assert op.get_state()["skill_content"] == "old content"

    op.set_parameter(SKILL_CONTENT_TARGET, "new content")
    # apply_and_wait 被调一次，传入 new content
    assert applier.calls == ["new content"]
    # 远程成功后本地状态已提交
    assert op.get_state()["skill_content"] == "new content"


def test_operator_keeps_old_state_and_raises_on_apply_failure() -> None:
    """apply 失败 → 本地状态保持旧值 + 抛 ManagedDocApplyError。"""
    applier = FakeApplier(
        apply_results=[
            ManagedDocApplyError(
                agent_name="a",
                doc_kind="agent_rule",
                task_id="t1",
                phase="poll",
                adapter_error="task FAILED",
            )
        ]
    )
    op = _make_operator("old content", applier)
    with pytest.raises(ManagedDocApplyError):
        op.set_parameter(SKILL_CONTENT_TARGET, "new content")
    # 本地状态不变
    assert op.get_state()["skill_content"] == "old content"
    assert applier.calls == ["new content"]


# ── policy normalize before apply ──


def test_passthrough_policy_sends_content_as_is() -> None:
    """PassthroughPolicy：content 原样传给 applier。"""
    applier = FakeApplier()
    op = _make_operator("init", applier, policy=PassthroughPolicy())
    op.set_parameter(SKILL_CONTENT_TARGET, "# raw content\n")
    assert applier.calls == ["# raw content\n"]
    assert op.get_state()["skill_content"] == "# raw content\n"


def test_preserving_policy_normalizes_before_apply() -> None:
    """PreservingContentPolicy：candidate 经 normalize（恢复 baseline frontmatter +
    protected section）后再传给 applier。"""
    baseline = "---\ntitle: keep\n---\n# rule\n<!--P-->\nprotected\n<!--/P-->\nfree\n"
    applier = FakeApplier()
    policy = PreservingContentPolicy(
        baseline, (ProtectedSection(start_marker="<!--P-->", end_marker="<!--/P-->"),)
    )
    op = _make_operator(baseline, applier, policy=policy)
    # candidate 改了 frontmatter + protected section
    candidate = "---\ntitle: CHANGED\n---\n# rule\n<!--P-->\nEDITED\n<!--/P-->\nfree\n"
    op.set_parameter(SKILL_CONTENT_TARGET, candidate)
    # applier 收到的是 normalized 内容（frontmatter 恢复 + protected 恢复）
    sent = applier.calls[0]
    assert "title: keep" in sent
    assert "protected" in sent
    assert "title: CHANGED" not in sent
    assert "EDITED" not in sent
    # operator 本地状态 == normalized（与 cache/operator/remote 一致）
    assert op.get_state()["skill_content"] == sent


# ── no-op（hash 命中不重复 POST）──


def test_operator_noop_when_applier_returns_noop() -> None:
    """applier hash 命中返回 noop → operator 仍提交本地状态为传入内容（已生效）。"""
    applier = FakeApplier(
        apply_results=[
            AppliedDocument(
                content_hash="h",
                task_id=None,
                noop=True,
                final_status=None,
                recovered=False,
                post_time=0.0,
                poll_time=0.0,
                total_time=0.0,
            )
        ]
    )
    op = _make_operator("baseline", applier)
    op.set_parameter(SKILL_CONTENT_TARGET, "baseline")  # same hash → noop
    assert applier.calls == ["baseline"]
    assert op.get_state()["skill_content"] == "baseline"


# ── load_state ──


def test_operator_load_state_applies_and_commits() -> None:
    """load_state 走相同顺序：normalize → apply → commit。"""
    applier = FakeApplier()
    op = _make_operator("init", applier)
    op.load_state({"skill_content": "loaded state"})
    assert applier.calls == ["loaded state"]
    assert op.get_state()["skill_content"] == "loaded state"


def test_operator_load_state_failure_keeps_old() -> None:
    """load_state apply 失败 → 本地状态不变 + 抛错。"""
    applier = FakeApplier(
        apply_results=[
            ManagedDocApplyError(
                agent_name="a",
                doc_kind="agent_rule",
                task_id=None,
                phase="post",
                adapter_error="conn refused",
            )
        ]
    )
    op = _make_operator("init", applier)
    with pytest.raises(ManagedDocApplyError):
        op.load_state({"skill_content": "loaded"})
    assert op.get_state()["skill_content"] == "init"


# ── set_parameter ignores non-skill targets ──


def test_operator_set_parameter_ignores_other_targets() -> None:
    """非 skill_content target 被忽略（与 base 行为一致）。"""
    applier = FakeApplier()
    op = _make_operator("init", applier)
    op.set_parameter("other_target", "x")
    assert applier.calls == []  # 未触发 apply


# ── factory ──


def test_factory_returns_managed_doc_operator_with_canonical_id() -> None:
    """build_managed_doc_operator 返回 ManagedDocOperator，operator_id 为 managed_doc:{kind}。"""
    from evo_agent.adapter_client.operator import ManagedDocOperator

    # 用一个 stub adapter_client（factory 只把它传给 applier，不立即调用）
    class _StubClient:
        _agent_name = "edp_agent"

    op = build_managed_doc_operator(
        doc_kind="agent_rule",
        initial_content="# rule",
        adapter_client=_StubClient(),  # type: ignore[arg-type]
        content_policy=PassthroughPolicy(),
        deadline=600.0,
        poll_interval=2.0,
    )
    assert isinstance(op, ManagedDocOperator)
    assert op.operator_id == "managed_doc:agent_rule"
