"""G2.2: 保守最坏任务时长上界（content 暴露给消费方做 deadline 校验）。

纯函数：输入 resolved ``ManagedDocConfig``，输出 int 秒上界。
``file_only`` → 0（无 restart/health 开销）。

公式（spec G2.2，固定）::

    N = max_attempts
    B = Σ(i=1..N-1) min(backoff_base · 2^(i-1), backoff_max)
    max_task_seconds = ceil(N · (restart_timeout
                                + health_down_timeout
                                + health_up_timeout
                                + 4 · health_poll_interval) + B)

每 attempt 的 ``4 · health_poll_interval`` 覆盖两阶段（down→up）probe/sleep
的边界超时。结果向上取整为 int（保守上界，绝不低估）。

输入字段由 ``registry._validate_restart_finite`` 保证 finite（T4）；本函数对
None 字段做防御性 0 兜底以便独立单测与未 resolved cfg 调用时不崩。
"""

from __future__ import annotations

import math

from agent_adapter.config import ManagedDocConfig


def compute_max_task_seconds(cfg: ManagedDocConfig) -> int:
    """Return the conservative worst-case task duration upper bound in seconds.

    ``file_only`` docs return 0 (no restart/health work). For ``restart`` docs
    the bound is the fixed spec G2.2 formula, ceilinged to an int.
    """
    if cfg.apply != "restart":
        return 0

    n = cfg.max_attempts or 0
    if n <= 0:
        return 0

    base = cfg.backoff_base or 0.0
    cap = cfg.backoff_max or 0.0
    # B = Σ(i=1..N-1) min(backoff_base · 2^(i-1), backoff_max)；N=1 → 空和 = 0。
    backoff_sum = 0.0
    for i in range(1, n):
        backoff_sum += min(base * (2 ** (i - 1)), cap)

    per_attempt = (
        (cfg.restart_timeout or 0)
        + (cfg.health_down_timeout or 0.0)
        + (cfg.health_up_timeout or 0.0)
        + 4 * (cfg.health_poll_interval or 0.0)
    )
    return int(math.ceil(n * per_attempt + backoff_sum))
