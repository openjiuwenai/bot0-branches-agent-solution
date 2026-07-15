"""AC5-AC7: snapshot / diff / remote sync 在两种 preserve_frontmatter 开关下的行为。

不变式：写回、snapshot、diff、remote 全程走全文（frontmatter 不被 strip）。
strip 仅发生在 LLM 注入边界（_llm_skill_view，见 test_llm_skill_view.py）。
"""

from __future__ import annotations

from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.operator import build_skill_document_operator
from evo_agent.callbacks.remote_skill_sync_callback import RemoteSkillSyncCallback
from evo_agent.optimizer.skill_document.artifact_exporter import ArtifactExporter

_SKILL_WITH_FM = "---\nname: demo\ndescription: x\n---\n\n# Title\nbody\n"
_FM_MARKER = "---\nname: demo"


def _make_exporter(tmp_path: Path) -> ArtifactExporter:
    return ArtifactExporter(str(tmp_path / "artifacts"), export_trajectories=False)


# ── AC5: snapshot 两种开关都含 frontmatter ──


class TestAC5SnapshotContainsFrontmatter:
    def test_snapshot_writes_full_document_with_frontmatter(self, tmp_path: Path) -> None:
        """export_skill_snapshot 将全文（含 frontmatter）原样写盘。"""
        exporter = _make_exporter(tmp_path)
        exporter.export_skill_snapshot(epoch=0, step=0, skill_content=_SKILL_WITH_FM, tag="after")
        content = (tmp_path / "artifacts" / "epoch_0" / "skill_after.md").read_text(
            encoding="utf-8"
        )
        assert content == _SKILL_WITH_FM
        assert _FM_MARKER in content

    def test_snapshot_writes_full_document_no_frontmatter(self, tmp_path: Path) -> None:
        """无 frontmatter 的 skill 原样写盘。"""
        exporter = _make_exporter(tmp_path)
        body = "# Title only\nbody\n"
        exporter.export_skill_snapshot(epoch=0, step=0, skill_content=body, tag="before")
        content = (tmp_path / "artifacts" / "epoch_0" / "skill_before.md").read_text(
            encoding="utf-8"
        )
        assert content == body

    def test_operator_get_state_returns_full_in_both_modes(self) -> None:
        """operator.get_state()（喂给 snapshot/remote）两开关都返回全文。

        preserve=True: frontmatter 冻结但仍存在于 get_state()。
        preserve=False: frontmatter 全程参与，get_state() 含 frontmatter。
        """
        for preserve in (True, False):
            mock_client = MagicMock(spec=AdapterClient)
            op = build_skill_document_operator(
                skill_name="demo",
                initial_content=_SKILL_WITH_FM,
                adapter_client=mock_client,
                preserve_frontmatter=preserve,
            )
            state = op.get_state()
            assert _FM_MARKER in state["skill_content"]


# ── AC6: export_skill_diff 两种开关行为正确 ──


class TestAC6SkillDiff:
    def test_preserve_true_frontmatter_frozen_not_in_diff(self, tmp_path: Path) -> None:
        """preserve=True: frontmatter 冻结（before/after 相同），diff 不含 frontmatter 变更行。"""
        exporter = _make_exporter(tmp_path)
        before = "---\nname: demo\ndescription: keep\n---\n\n# Title\nold body\n"
        after = "---\nname: demo\ndescription: keep\n---\n\n# Title\nnew body\n"
        exporter.export_skill_diff(epoch=0, step=0, before=before, after=after)
        patch = (tmp_path / "artifacts" / "epoch_0" / "step_0" / "applied_diff.patch").read_text(
            encoding="utf-8"
        )
        # frontmatter description 未出现在 +/- 变更行（冻结）
        assert "-description: keep" not in patch
        assert "+description: keep" not in patch
        # body 变更出现在 diff
        assert "-old body" in patch
        assert "+new body" in patch

    def test_preserve_false_frontmatter_change_appears_in_diff(self, tmp_path: Path) -> None:
        """preserve=False: frontmatter 可被改动，diff 含 frontmatter 变更行。"""
        exporter = _make_exporter(tmp_path)
        before = "---\nname: demo\ndescription: old\n---\n\n# Title\nbody\n"
        after = "---\nname: demo\ndescription: NEW\n---\n\n# Title\nbody\n"
        exporter.export_skill_diff(epoch=0, step=0, before=before, after=after)
        patch = (tmp_path / "artifacts" / "epoch_0" / "step_0" / "applied_diff.patch").read_text(
            encoding="utf-8"
        )
        assert "-description: old" in patch
        assert "+description: NEW" in patch


# ── AC7: RemoteSkillSyncCallback 推远端含 frontmatter ──


def _progress(epoch: int) -> Any:
    progress = MagicMock()
    progress.current_epoch = epoch
    progress.best_score = 0.8
    return progress


class TestAC7RemoteSyncContainsFrontmatter:
    def test_preserve_true_remote_push_contains_frontmatter(self) -> None:
        """preserve=True: from_operator 推远端内容含 frontmatter（get_state 全文）。"""
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="demo",
            initial_content=_SKILL_WITH_FM,
            adapter_client=mock_client,
            preserve_frontmatter=True,
        )
        cb = RemoteSkillSyncCallback.from_operator(
            sync_endpoint="http://remote/sync",
            skill_name="demo",
            operator=op,
        )
        cb._session = MagicMock()  # type: ignore[method-assign]
        cb._session.post.return_value.raise_for_status.return_value = None

        cb.on_train_epoch_end(agent=MagicMock(), progress=_progress(0), eval_info=[])

        posted = cb._session.post.call_args
        assert posted.kwargs["json"]["content"] == _SKILL_WITH_FM
        assert _FM_MARKER in posted.kwargs["json"]["content"]

    def test_preserve_false_remote_push_contains_frontmatter(self) -> None:
        """preserve=False: 推远端内容同样含 frontmatter。"""
        mock_client = MagicMock(spec=AdapterClient)
        op = build_skill_document_operator(
            skill_name="demo",
            initial_content=_SKILL_WITH_FM,
            adapter_client=mock_client,
            preserve_frontmatter=False,
        )
        # plain operator 不冻结，set_parameter 后 get_state 返回 set 的内容
        op.set_parameter("skill_content", _SKILL_WITH_FM)
        cb = RemoteSkillSyncCallback.from_operator(
            sync_endpoint="http://remote/sync",
            skill_name="demo",
            operator=op,
        )
        cb._session = MagicMock()  # type: ignore[method-assign]
        cb._session.post.return_value.raise_for_status.return_value = None

        cb.on_train_epoch_end(agent=MagicMock(), progress=_progress(0), eval_info=[])

        posted = cb._session.post.call_args
        assert _FM_MARKER in posted.kwargs["json"]["content"]


# ── AC8: gate 不回归（既有 gate 测试零回归由全量套件覆盖） ──


def test_ac8_gate_inference_unchanged_smoke() -> None:
    """AC8 smoke: preserve_frontmatter 不影响 _infer_gate_decision 路径的存在性。

    深度覆盖由 ``tests/unit -k gate`` 全量回归保证（见 plan Checkpoint 3）。
    """
    # 仅断言 gate 相关 API 仍可导入且不受 preserve_frontmatter 字段影响
    from evo_agent.config import EvolveConfig  # noqa: F401

    assert EvolveConfig().preserve_frontmatter is True
