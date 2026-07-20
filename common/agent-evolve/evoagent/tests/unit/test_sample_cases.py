"""A5 (#8): _sample_cases 用 random.sample — 单元测试。"""

from __future__ import annotations

from openjiuwen.agent_evolving.dataset import Case

from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)


class _FakeCaseLoader:
    """最小 CaseLoader 替身，返回固定 case 列表。"""

    def __init__(self, n: int) -> None:
        self._cases = [Case(inputs={"i": i}, label={"expected_result": None}) for i in range(n)]

    def get_cases(self) -> list[Case]:
        return list(self._cases)


def _make_optimizer(num_cases: int) -> SkillDocumentOptimizer:
    """绕过重 __init__，仅设置 _train_cases 以测试 _sample_cases。"""
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)  # type: ignore[no-untyped-call]
    opt._train_cases = _FakeCaseLoader(num_cases)  # type: ignore[attr-defined]
    return opt


def _case_ids(cases: list[Case]) -> list[int]:
    return [c.inputs["i"] for c in cases]  # type: ignore[index]


def test_sample_cases_reproducible_with_same_seed() -> None:
    """固定 seed 两次采样结果一致。"""
    opt = _make_optimizer(50)
    a = _sample_cases_via_method(opt, 10, seed=7)
    b = _sample_cases_via_method(opt, 10, seed=7)
    assert _case_ids(a) == _case_ids(b)


def test_sample_cases_different_seed_different_sample() -> None:
    """不同 seed 采样结果（大概率）不同。"""
    opt = _make_optimizer(50)
    a = _sample_cases_via_method(opt, 10, seed=1)
    b = _sample_cases_via_method(opt, 10, seed=2)
    assert _case_ids(a) != _case_ids(b)


def test_sample_cases_returns_n_or_fewer() -> None:
    """采样数量不超过请求数与全集大小。"""
    opt = _make_optimizer(20)
    assert len(_sample_cases_via_method(opt, 8, seed=0)) == 8
    # 请求超过全集 → 返回全集
    assert len(_sample_cases_via_method(opt, 30, seed=0)) == 20


def test_sample_cases_does_not_mutate_source() -> None:
    """采样不改变底层 case 顺序（random.sample 不原地 shuffle）。"""
    opt = _make_optimizer(30)
    _ = _sample_cases_via_method(opt, 5, seed=3)
    again = opt._train_cases.get_cases()  # type: ignore[attr-defined]
    assert _case_ids(again) == list(range(30))


def _sample_cases_via_method(opt: SkillDocumentOptimizer, n: int, *, seed: int) -> list[Case]:
    """调用 _sample_cases，兼容签名变更。"""
    return opt._sample_cases(n, seed=seed)  # type: ignore[attr-defined]
