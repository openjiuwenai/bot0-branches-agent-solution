"""从 trace_profiles.yaml 加载 profiles，按 service.name / profile name 建 registry。"""

from __future__ import annotations

from pathlib import Path

import yaml

from agent_adapter.trace_profile.models import TraceProfile


class ProfileRegistry:
    """按 service.name / profile name 查找 TraceProfile。

    两个索引：
    - _by_name: profile name → TraceProfile（用于 AgentEntryConfig.trace_profile 引用）
    - _by_service: service.name → TraceProfile（用于 Kafka 消息自动路由）
    """

    def __init__(self, profiles: dict[str, TraceProfile]) -> None:
        self._by_name = profiles
        self._by_service: dict[str, TraceProfile] = {
            p.service_name: p for p in profiles.values()
        }

    def get_by_service_name(self, service_name: str) -> TraceProfile | None:
        return self._by_service.get(service_name)

    def get_by_profile_name(self, name: str) -> TraceProfile | None:
        return self._by_name.get(name)

    def get_by_agent_name(self, agent_name: str, agent_configs: list) -> TraceProfile | None:
        """通过 agent name → AgentEntryConfig.trace_profile → profile name 查找。"""
        for a in agent_configs:
            if a.name == agent_name and getattr(a, "trace_profile", None):
                return self._by_name.get(a.trace_profile)
        return None


def load_profiles(yaml_path: Path) -> ProfileRegistry:
    """从 YAML 文件加载所有 profiles，构建 registry。

    配置文件不存在或为空 → 返回空 registry（无 profiles，走 legacy 逻辑）。
    """
    if not yaml_path.exists():
        return ProfileRegistry({})
    with open(yaml_path, encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}
    profiles: dict[str, TraceProfile] = {}
    for name, cfg in data.get("profiles", {}).items():
        cfg["name"] = name
        profiles[name] = TraceProfile(**cfg)
    return ProfileRegistry(profiles)