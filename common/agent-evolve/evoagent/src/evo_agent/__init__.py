"""EvoAgent — 自进化元 Agent，封装 skill 文档自动优化能力。"""

from __future__ import annotations

import warnings

from evo_agent import llm  # noqa: F401 — import 副作用：注册 llm_CustomSSE provider
from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.remote_agent import RemoteAgent
from evo_agent.config import EvolveConfig
from evo_agent.scenario.registry import ScenarioRegistry
from evo_agent.types import OptimizeReport, OptimizeRequest

__all__ = [
    "AdapterClient",
    "EvolveConfig",
    "OptimizeReport",
    "OptimizeRequest",
    "RemoteAgent",
    "ScenarioRegistry",
    "create_evo_agent",
]


def __getattr__(name: str) -> object:
    if name == "create_evo_agent":
        warnings.warn(
            "create_evo_agent is deprecated since Wave 3 and will be removed in Wave 4.5. "
            "Use AdapterClient + RemoteAgent instead (see optimizer_runner.py).",
            DeprecationWarning,
            stacklevel=2,
        )
        from evo_agent.agent import create_evo_agent as _create_evo_agent

        return _create_evo_agent
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
