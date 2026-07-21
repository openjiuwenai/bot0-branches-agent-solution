"""golden_data gu_store 单元测试 —— 持久化目录读写 + ensure_layout + route_skill。

在装好 openjiuwen / pydantic 依赖的 dev 环境用 ``make test-unit`` 跑。
"""

from __future__ import annotations

from pathlib import Path

from evo_agent.evaluator.domain.models import StandardTrajectory, TrajectoryMessage
from evo_agent.evaluator.golden_data import gu_store
from evo_agent.evaluator.golden_data.models import GUIndex


def _traj(content: str) -> StandardTrajectory:
    return StandardTrajectory(messages=[TrajectoryMessage(role="user", content=content)])


def test_ensure_layout_progressive(tmp_path: Path) -> None:
    root = tmp_path / "gu"
    gu_store.ensure_layout(root, mode="progressive")
    assert (root / "per_skill").is_dir()
    assert (root / "index.md").is_file()
    assert gu_store.load_index(root).mode == "progressive"


def test_ensure_layout_flat(tmp_path: Path) -> None:
    root = tmp_path / "gu"
    gu_store.ensure_layout(root, mode="flat")
    assert (root / "global_understanding.md").is_file()
    assert not (root / "per_skill").exists()
    assert gu_store.load_index(root).mode == "flat"


def test_index_round_trip(tmp_path: Path) -> None:
    root = tmp_path / "gu"
    idx = GUIndex(
        mode="progressive",
        skills=["send_email", "query"],
        last_run_id="abc123def456",
        out_of_scope_count=3,
    )
    gu_store.save_index(root, idx)
    loaded = gu_store.load_index(root)
    assert loaded.mode == "progressive"
    assert loaded.skills == ["send_email", "query"]
    assert loaded.last_run_id == "abc123def456"
    assert loaded.out_of_scope_count == 3


def test_load_index_missing_returns_default(tmp_path: Path) -> None:
    loaded = gu_store.load_index(tmp_path / "empty")
    assert loaded.skills == []
    assert loaded.mode == "progressive"
    assert loaded.last_run_id == ""


def test_skill_doc_round_trip(tmp_path: Path) -> None:
    root = tmp_path / "gu"
    gu_store.save_skill_doc(root, "send_email", "# send_email\n...")
    assert gu_store.load_skill_doc(root, "send_email") == "# send_email\n..."
    assert gu_store.load_skill_doc(root, "absent") == ""


def test_out_of_scope_round_trip(tmp_path: Path) -> None:
    root = tmp_path / "gu"
    gu_store.save_out_of_scope(root, "越界内容")
    assert gu_store.load_out_of_scope(root) == "越界内容"
    assert (root / "per_skill" / "__out_of_scope__.md").is_file()


def test_route_skill_attributed_signal_wins() -> None:
    idx = GUIndex(skills=["send_email", "send"])
    traj = _traj("文本里没有任何 skill 名")
    assert gu_store.route_skill(traj, idx, attributed_skill="send_email") == "send_email"


def test_route_skill_long_name_preferred() -> None:
    idx = GUIndex(skills=["send", "send_email"])
    traj = _traj("请用 send_email 把邮件发出去")
    # 长度降序匹配：send_email 先于 send 命中，避免短名子串误匹配。
    assert gu_store.route_skill(traj, idx) == "send_email"


def test_route_skill_no_match_returns_none() -> None:
    idx = GUIndex(skills=["send_email", "query"])
    traj = _traj("今天天气不错")
    assert gu_store.route_skill(traj, idx) is None
