"""golden_data builder phase1 单元测试 —— flat/progressive 产物落盘 + index + run workspace。

LLM 调用用 ``FakeModel`` + monkeypatch ``_llm_with_retry`` mock 掉，不连真模型。
"""

from __future__ import annotations

from pathlib import Path

import pytest

from evo_agent.evaluator.domain.models import StandardTrajectory, TrajectoryMessage
from evo_agent.evaluator.golden_data import gu_store
from evo_agent.evaluator.golden_data.builder import GlobalUnderstandingBuilder
from evo_agent.evaluator.golden_data.skill_provider import LocalSkillProvider


def _traj(content: str) -> StandardTrajectory:
    return StandardTrajectory(messages=[TrajectoryMessage(role="user", content=content)])


def _make_builder(monkeypatch, tmp_path: Path, *, flat_threshold: int = 30, skills: list[str]):
    """建一个 mock 掉 Model/LLM 的 builder，gu_root/artifact_root 落 tmp_path。"""
    import evo_agent.evaluator.golden_data.builder as bmod

    class FakeModel:
        def __init__(self, *args: object, **kwargs: object) -> None:
            pass

    monkeypatch.setattr(bmod, "Model", FakeModel)

    from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

    skill_root = tmp_path / "skills"
    for name in skills:
        (skill_root / name).mkdir(parents=True, exist_ok=True)
        (skill_root / name / "SKILL.md").write_text(f"# {name} skill", encoding="utf-8")
    sp = LocalSkillProvider(skill_root=skill_root)

    b = GlobalUnderstandingBuilder(
        ModelRequestConfig(model_name="dummy"),
        ModelClientConfig(
            client_provider="OpenAI", api_key="x", api_base="http://x", verify_ssl=False
        ),
        sp,
        flat_threshold=flat_threshold,
        gu_root=tmp_path / "gu",
        artifact_root=tmp_path / "art",
    )
    b._llm_with_retry = lambda system, user, label, **k: f"MOCK GU [{label}]"  # type: ignore[method-assign]
    return b


def test_build_flat(monkeypatch, tmp_path: Path) -> None:
    b = _make_builder(monkeypatch, tmp_path, flat_threshold=30, skills=["send_email"])
    traces = [_traj("请用 send_email 发邮件"), _traj("再 send_email 一次")]
    index = b.build(traces, ["send_email"], batch_size=1)

    assert index.mode == "flat"
    assert index.skills == ["send_email"]
    assert index.out_of_scope_count == 0
    gu_root = tmp_path / "gu"
    assert gu_store.load_flat(gu_root).startswith("MOCK GU")
    assert (gu_root / "index.md").is_file()

    # run workspace 中间结果
    runs = list((tmp_path / "art").glob("gu_*"))
    assert len(runs) == 1
    assert (runs[0] / "mode_decision.json").is_file()
    # batch_size=1 + 2 条 → 2 批，每批落 global_batch_<n>.txt
    assert (runs[0] / "global_batch_1.txt").is_file()
    assert (runs[0] / "global_batch_2.txt").is_file()


def test_build_progressive_with_oos(monkeypatch, tmp_path: Path) -> None:
    b = _make_builder(monkeypatch, tmp_path, flat_threshold=0, skills=["send_email", "query"])
    traces = [
        _traj("请用 send_email 发邮件"),
        _traj("用 query 查一下"),
        _traj("今天天气不错"),  # 无 skill 归属 → OOS
    ]
    index = b.build(traces, ["send_email", "query"], batch_size=10)

    assert index.mode == "progressive"
    gu_root = tmp_path / "gu"
    assert (gu_root / "per_skill" / "send_email.md").is_file()
    assert (gu_root / "per_skill" / "query.md").is_file()
    assert (gu_root / "per_skill" / "__out_of_scope__.md").is_file()
    assert (gu_root / "system_wide.md").is_file()
    assert index.out_of_scope_count == 1

    # run workspace 路由记录
    runs = list((tmp_path / "art").glob("gu_*"))
    assert (runs[0] / "trace_skill_routes.json").is_file()
    assert (runs[0] / "per_skill_draft").is_dir()


def test_build_empty_traces_flat(monkeypatch, tmp_path: Path) -> None:
    b = _make_builder(monkeypatch, tmp_path, flat_threshold=30, skills=["send_email"])
    index = b.build([], ["send_email"])
    assert index.mode == "flat"
    # 空轨迹 → flat GU 为空串但仍落盘 + index
    assert gu_store.load_flat(tmp_path / "gu") == ""


def test_load_skills_skips_missing(monkeypatch, tmp_path: Path) -> None:
    # skill_names 含一个 skill_root 里不存在的 skill → _load_skills 跳过
    b = _make_builder(monkeypatch, tmp_path, flat_threshold=30, skills=["send_email"])
    skills = b._load_skills(["send_email", "absent_skill"])
    assert list(skills.keys()) == ["send_email"]


def test_local_skill_provider(tmp_path: Path) -> None:
    skill_root = tmp_path / "skills"
    (skill_root / "send_email").mkdir(parents=True)
    (skill_root / "send_email" / "SKILL.md").write_text("# send_email", encoding="utf-8")
    (skill_root / "no_md").mkdir()  # 无 SKILL.md，不应被列出
    sp = LocalSkillProvider(skill_root=skill_root)
    assert sp.list_skills() == ["send_email"]
    assert sp.get_skill_content("send_email") == "# send_email"
    with pytest.raises(FileNotFoundError):
        sp.get_skill_content("absent")


def test_build_llm_failure_raises_evaluation_error(monkeypatch, tmp_path: Path) -> None:
    """去降级：LLM 重试 3 次全失败后抛 EvaluationError，不偷偷保留前序结果。"""
    import evo_agent.evaluator.golden_data.builder as bmod
    from evo_agent.evaluator.domain.scoring import EvaluationError

    b = _make_builder(monkeypatch, tmp_path, flat_threshold=30, skills=["send_email"])
    del b._llm_with_retry  # 还原类方法，走真重试逻辑（_make_builder 默认 mock 成 lambda）

    def _boom(*a: object, **k: object) -> None:
        raise RuntimeError("LLM down")

    monkeypatch.setattr(bmod, "_run_coroutine", _boom)
    with pytest.raises(EvaluationError):
        b.build([_traj("用 send_email")], ["send_email"], batch_size=1)
