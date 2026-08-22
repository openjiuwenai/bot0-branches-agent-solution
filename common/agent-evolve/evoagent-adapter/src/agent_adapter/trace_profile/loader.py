"""从 trace_profiles.yaml 加载 profiles，按 service.name / profile name 建 registry。"""

from __future__ import annotations

from pathlib import Path

import yaml

from agent_adapter.trace_profile.models import TraceProfile


class ProfileRegistry:
    """按 service.name / profile name 查找 TraceProfile。

    两个索引：
    - _by_name: profile name → TraceProfile（用于 AgentEntryConfig.trace_profile 引用）
    - _by_service: service.name → list[TraceProfile]（用于 Kafka 消息自动路由；
      同名撞名时由 get_by_service_name 按 telemetry.sdk.language 消歧）
    """

    def __init__(self, profiles: dict[str, TraceProfile]) -> None:
        self._by_name = profiles
        self._by_service: dict[str, list[TraceProfile]] = {}
        for p in profiles.values():
            self._by_service.setdefault(p.service_name, []).append(p)

    def get_by_service_name(
        self, service_name: str, language: str | None = None
    ) -> TraceProfile | None:
        """按 service.name 查找 profile；language 非空时做 tiebreaker。

        - 无候选 → None。
        - 给定 language：优先精确匹配 service_language；无精确匹配则回退语言无关
          (service_language=='') 的候选；都没有 → None（语言不符，不误路由到别的语言）。
        - 未给 language：仅一个候选 → 返回；多候选（撞名且无语言区分）→ None（歧义，不猜）。
        """
        candidates = self._by_service.get(service_name)
        if not candidates:
            return None
        if language:
            for p in candidates:
                if p.service_language and p.service_language == language:
                    return p
            for p in candidates:
                if not p.service_language:
                    return p
            return None
        if len(candidates) == 1:
            return candidates[0]
        return None

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