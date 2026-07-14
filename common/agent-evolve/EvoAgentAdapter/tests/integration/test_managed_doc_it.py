"""Managed-doc integration test (T15, Phase F).

Gated by ``ADAPTER_MANAGED_DOC_IT=1``: runs a real ``docker restart`` (or the
configured ``restart_cmd``) against a real EDPAgent canary and observes the
down→up health transition to calibrate §8.4 defaults. **Default: skipped, not
run in CI.**

Run manually:
    ADAPTER_MANAGED_DOC_IT=1 \\
    EDP_CANARY_AGENT_URL=http://localhost:8090 \\
    EDP_CANARY_RESTART_CMD="docker restart edpagent-canary" \\
    EDP_CANARY_RULE_PATH=/host/edp-canary/AgentRule.md \\
    .venv/bin/python -m pytest tests/integration/test_managed_doc_it.py -s
"""

from __future__ import annotations

import os
import uuid

import pytest

_IT = os.environ.get("ADAPTER_MANAGED_DOC_IT", "") == "1"
_AGENT_URL = os.environ.get("EDP_CANARY_AGENT_URL", "")
_RESTART_CMD = os.environ.get("EDP_CANARY_RESTART_CMD", "")
_RULE_PATH = os.environ.get("EDP_CANARY_RULE_PATH", "")

pytestmark = pytest.mark.skipif(
    not _IT or not _AGENT_URL or not _RESTART_CMD or not _RULE_PATH,
    reason="set ADAPTER_MANAGED_DOC_IT=1 + EDP_CANARY_* env to run real canary integration",
)


def test_restart_apply_real_canary_down_up() -> None:
    """AC15.1 联调：真 restart_cmd + 真 EDPAgent canary，采 down→up 真实时长。

    验证 RestartApply 在真实 docker 重启下能观察到 health down→up 跃变并
    SUCCEEDED；采集的时长用于回填 spec §8.4 默认值（§14 Q1）。
    """
    import httpx

    from agent_adapter.config import ManagedDocConfig
    from agent_adapter.managed_doc.apply import RestartApply

    cfg = ManagedDocConfig(
        kind="agent_rule",
        path=_RULE_PATH,
        apply="restart",
        restart_cmd=_RESTART_CMD,
        health_url=f"{_AGENT_URL}/health",
        # 联调用宽松超时，便于观察真实时长
        health_down_timeout=60.0,
        health_up_timeout=180.0,
        health_up_consecutive=3,
        health_poll_interval=0.5,
        restart_timeout=60,
        max_attempts=2,
        backoff_base=5.0,
        backoff_max=30.0,
    )
    # 用唯一标记内容写文件，确保重启后生效的是新版本
    marker = f"# canary-it-{uuid.uuid4().hex[:8]}\n"
    import pathlib

    pathlib.Path(_RULE_PATH).write_text(
        f"---\nauthor: it\n---\n{marker}", encoding="utf-8"
    )

    import asyncio

    async def _run() -> None:
        async with httpx.AsyncClient() as client:
            apply = RestartApply(cfg=cfg, client=client)
            result = await apply.apply()
        assert result.ok is True, f"restart apply failed: {result.error}"
        assert result.down_seen is True, (
            "未观察到 down 跃变（restart_cmd 可能 no-op，或 health 恒绿）"
        )

    asyncio.run(_run())
