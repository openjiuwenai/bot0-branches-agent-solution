"""Unit tests for compute_max_task_seconds (G2.2 deadline upper bound)."""

from agent_adapter.config import ManagedDocConfig
from agent_adapter.managed_doc.deadline import compute_max_task_seconds


def _restart(**kw) -> ManagedDocConfig:
    """合法 restart doc 基线（burst 默认 + 显式 restart_timeout）；覆盖单项测公式。"""
    base: dict[str, object] = dict(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
        health_url="http://localhost:9999/health",
        restart_timeout=30,
        max_attempts=2,
        backoff_base=3.0,
        backoff_max=30.0,
        health_down_timeout=15.0,
        health_up_timeout=60.0,
        health_poll_interval=0.5,
        health_up_consecutive=2,
    )
    base.update(kw)
    return ManagedDocConfig(**base)  # type: ignore[arg-type]


def test_burst_defaults_concrete_upper_bound() -> None:
    # per_attempt = 30+15+60+4·0.5 = 107; N=2 → 214; B=min(3,30)=3 → 217
    assert compute_max_task_seconds(_restart()) == 217


def test_single_profile_concrete_upper_bound() -> None:
    # per_attempt = 30+30+90+4·1 = 154; N=3 → 462; B=min(5,60)+min(10,60)=15 → 477
    cfg = _restart(
        max_attempts=3,
        backoff_base=5.0,
        backoff_max=60.0,
        health_down_timeout=30.0,
        health_up_timeout=90.0,
        health_poll_interval=1.0,
    )
    assert compute_max_task_seconds(cfg) == 477


def test_backoff_cap_truncates() -> None:
    # base=10, max=15, N=4: B=min(10,15)+min(20,15)+min(40,15)=10+15+15=40
    # per_attempt=107; N=4 → 428; +40 = 468
    cfg = _restart(max_attempts=4, backoff_base=10.0, backoff_max=15.0)
    assert compute_max_task_seconds(cfg) == 468


def test_n_equals_1_backoff_zero() -> None:
    # N=1 → range(1,1) 空 → B=0; per_attempt=107; 1·107=107
    cfg = _restart(max_attempts=1, backoff_base=5.0, backoff_max=60.0)
    assert compute_max_task_seconds(cfg) == 107


def test_backoff_base_zero_backoff_zero() -> None:
    # backoff_base=0 → 每项 min(0, cap)=0 → B=0; per_attempt=107; N=3 → 321
    cfg = _restart(max_attempts=3, backoff_base=0.0, backoff_max=60.0)
    assert compute_max_task_seconds(cfg) == 321


def test_file_only_returns_zero() -> None:
    cfg = ManagedDocConfig(kind="agent_rule", path="/x", apply="file_only")
    assert compute_max_task_seconds(cfg) == 0


def test_fractional_ceil_upper_bound() -> None:
    # health_poll_interval=0.3 → 4·0.3=1.2; per_attempt=30+15+60+1.2=106.2
    # N=2 → 212.4; B=min(3,30)=3 → 215.4 → ceil 216（保守上界向上取整）
    cfg = _restart(health_poll_interval=0.3)
    assert compute_max_task_seconds(cfg) == 216
