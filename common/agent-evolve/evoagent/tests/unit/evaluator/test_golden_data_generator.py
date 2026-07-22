"""golden_data generator phase2 单元测试 —— slice 载入 + EB 解析 + to_external。

LLM 调用用 ``FakeModel`` + monkeypatch ``_llm_with_retry`` mock 掉，不连真模型。
GU 种子用 ``gu_store`` 直接写（不依赖 builder）。
"""

from __future__ import annotations

from pathlib import Path

import pytest
from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

from evo_agent.evaluator.domain.models import StandardTrajectory, TrajectoryMessage
from evo_agent.evaluator.golden_data import gu_store
from evo_agent.evaluator.golden_data.generator import ExpectedBehaviorGenerator
from evo_agent.evaluator.golden_data.models import EBInput, GUIndex, GUSlice

_EB_LLM_OUTPUT = (
    "scenario：发邮件\n"
    "expected_behavior：在用户要求发邮件时，应忠实原文发送，不应篡改内容\n"
    "result：通过\n"
    "reason：agent 忠实发送"
)


def _traj(content: str) -> StandardTrajectory:
    return StandardTrajectory(messages=[TrajectoryMessage(role="user", content=content)])


def _make_generator(monkeypatch, tmp_path: Path) -> ExpectedBehaviorGenerator:
    import evo_agent.evaluator.golden_data.generator as gmod

    class FakeModel:
        def __init__(self, *args: object, **kwargs: object) -> None:
            pass

    monkeypatch.setattr(gmod, "Model", FakeModel)
    g = ExpectedBehaviorGenerator(
        ModelRequestConfig(model_name="dummy"),
        ModelClientConfig(
            client_provider="OpenAI", api_key="x", api_base="http://x", verify_ssl=False
        ),
        gu_root=tmp_path / "gu",
    )
    g._llm_with_retry = lambda system, user, label, **k: _EB_LLM_OUTPUT  # type: ignore[method-assign]
    return g


def test_generate_flat(monkeypatch, tmp_path: Path) -> None:
    g = _make_generator(monkeypatch, tmp_path)
    gu_root = tmp_path / "gu"
    gu_store.save_flat(gu_root, "系统概况：邮件系统")
    gu_store.save_index(gu_root, GUIndex(skills=["send_email"], mode="flat"))

    out = g.generate(EBInput(trajectory=_traj("请发邮件"), gu_slice=GUSlice()))
    assert len(out.items) == 1
    item = out.items[0]
    assert item.expected_behavior.startswith("在用户要求发邮件")
    assert item.result == "通过"
    assert item.scenario == "发邮件"
    assert item.reason == "agent 忠实发送"
    assert item.inputs == "请发邮件"

    ext = out.to_external()
    assert len(ext) == 1
    assert set(ext[0].keys()) == {"id", "inputs", "expected_behavior"}
    assert ext[0]["inputs"] == "请发邮件"


def test_generate_progressive_routes_skill(monkeypatch, tmp_path: Path) -> None:
    g = _make_generator(monkeypatch, tmp_path)
    gu_root = tmp_path / "gu"
    gu_store.save_system_wide(gu_root, "跨 skill 共性")
    gu_store.save_skill_doc(gu_root, "send_email", "send_email 局部理解")
    gu_store.save_index(gu_root, GUIndex(skills=["send_email"], mode="progressive"))

    # gu_slice 空 → generator 内部 route_skill 命中 send_email
    out = g.generate(EBInput(trajectory=_traj("请用 send_email 发邮件"), gu_slice=GUSlice()))
    assert len(out.items) == 1
    assert out.items[0].result == "通过"


def test_generate_oos(monkeypatch, tmp_path: Path) -> None:
    g = _make_generator(monkeypatch, tmp_path)
    gu_root = tmp_path / "gu"
    gu_store.save_system_wide(gu_root, "系统共性")
    gu_store.save_out_of_scope(gu_root, "越界共性")
    gu_store.save_index(gu_root, GUIndex(skills=["send_email"], mode="progressive"))

    out = g.generate(EBInput(trajectory=_traj("今天天气不错"), gu_slice=GUSlice()))
    assert len(out.items) == 1
    assert out.metadata["attributed_skill"] == ""


def test_generate_uses_prefilled_slice(monkeypatch, tmp_path: Path) -> None:
    # gu_slice 已填（外部预路由）→ 不走 _load_slice（不读 GU 目录）
    g = _make_generator(monkeypatch, tmp_path)
    slice_ = GUSlice(system_wide="预填 system", per_skill={"send_email": "预填 sub"})
    out = g.generate(EBInput(trajectory=_traj("无 skill 名"), gu_slice=slice_))
    assert len(out.items) == 1


def test_generate_no_user_input(monkeypatch, tmp_path: Path) -> None:
    g = _make_generator(monkeypatch, tmp_path)
    gu_store.save_flat(tmp_path / "gu", "GU")
    gu_store.save_index(tmp_path / "gu", GUIndex(skills=["send_email"], mode="flat"))
    traj = StandardTrajectory(messages=[TrajectoryMessage(role="assistant", content="hi")])
    out = g.generate(EBInput(trajectory=traj, gu_slice=GUSlice()))
    assert out.items == []
    assert out.metadata["reason"] == "未找到顾客输入"


def test_parse_eb_result_fallback_na(monkeypatch, tmp_path: Path) -> None:
    # result 行缺失且 raw 无 通过/失败 关键词 → NA
    g = _make_generator(monkeypatch, tmp_path)
    gu_store.save_flat(tmp_path / "gu", "GU")
    gu_store.save_index(tmp_path / "gu", GUIndex(skills=["send_email"], mode="flat"))
    g._llm_with_retry = lambda s, u, label, **k: (  # type: ignore[method-assign]
        "scenario：x\nexpected_behavior：应该Y，不应该Z\nreason：r"
    )
    out = g.generate(EBInput(trajectory=_traj("请发邮件"), gu_slice=GUSlice()))
    assert out.items[0].result == "NA"


def test_parse_eb_result_fallback_fail(monkeypatch, tmp_path: Path) -> None:
    # result 行缺失但 raw 含"失败" → 失败
    g = _make_generator(monkeypatch, tmp_path)
    gu_store.save_flat(tmp_path / "gu", "GU")
    gu_store.save_index(tmp_path / "gu", GUIndex(skills=["send_email"], mode="flat"))
    g._llm_with_retry = lambda s, u, label, **k: (  # type: ignore[method-assign]
        "expected_behavior：应该确认，不应该裸回复。判失败。\nreason：r"
    )
    out = g.generate(EBInput(trajectory=_traj("请发邮件"), gu_slice=GUSlice()))
    assert out.items[0].result == "失败"


def test_generate_llm_failure_raises_evaluation_error(monkeypatch, tmp_path: Path) -> None:
    """去降级：LLM 重试 3 次全失败后抛 EvaluationError，不返回空 items。"""
    import evo_agent.evaluator.golden_data.generator as gmod
    from evo_agent.evaluator.domain.scoring import EvaluationError

    g = _make_generator(monkeypatch, tmp_path)
    del g._llm_with_retry  # 还原类方法，走真重试逻辑
    gu_store.save_flat(tmp_path / "gu", "GU")
    gu_store.save_index(tmp_path / "gu", GUIndex(skills=["send_email"], mode="flat"))

    def _boom(*a: object, **k: object) -> None:
        raise RuntimeError("LLM down")

    monkeypatch.setattr(gmod, "_run_coroutine", _boom)
    with pytest.raises(EvaluationError):
        g.generate(EBInput(trajectory=_traj("请发邮件"), gu_slice=GUSlice()))
