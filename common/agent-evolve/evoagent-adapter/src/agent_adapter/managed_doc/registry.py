"""Build-time registry for managed-docs (T3).

Builds ``agent_name → {doc_kind → ManagedDocConfig}`` from ``AdapterConfig.agents``
and validates each restart-class doc at construction time:

* ``restart_cmd`` required (doc > defaults; mirrors ManagedDocConfig's validator
  but accounts for defaults supplying the cmd).
* ``health_url`` resolvable, **independent of the ``agent_clients`` dict** —
  derived as ``doc.health_url or defaults.health_url or f"{agent_url}/health"``
  (spec D4). An agent without ``agent_url`` and without an explicit
  ``health_url`` cannot use a restart-class doc.

None restart-param fields are resolved against ``ManagedDocDefaults``: precedence
is ``doc`` (explicit) > ``defaults`` (explicit) > profile base (§8.4 table).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from agent_adapter.config import (
    AdapterConfig,
    AgentEntryConfig,
    ManagedDocConfig,
    ManagedDocDefaults,
)
from agent_adapter.managed_doc.storage import (
    AgentNotFoundError,
    DocNotFoundError,
    DocStorageError,
)

# Restart-param fields backed by the §8.4 profile default table.
_PROFILE_FIELDS: tuple[str, ...] = (
    "max_attempts",
    "backoff_base",
    "backoff_max",
    "health_down_timeout",
    "health_up_timeout",
    "health_up_consecutive",
    "health_poll_interval",
)
# Restart-only fields with no profile base (doc > defaults, else None).
_NON_PROFILE_RESTART_FIELDS: tuple[str, ...] = ("restart_timeout",)


class ManagedDocRegistryError(DocStorageError):
    """A managed-doc config is unresolvable at build time."""


class ManagedDocRegistry:
    """agent_name → {doc_kind → resolved ManagedDocConfig}."""

    def __init__(
        self,
        *,
        agents: list[AgentEntryConfig],
        defaults: ManagedDocDefaults,
    ) -> None:
        self._map: dict[str, dict[str, ManagedDocConfig]] = {}
        # Guard against silent cross-agent .meta/snapshot collision (I2): two
        # docs sharing (parent_dir, kind) would share .meta/{kind}.* and step on
        # each other. Fail at build time instead.
        seen: set[tuple[str, str]] = set()
        for agent in agents:
            by_kind: dict[str, ManagedDocConfig] = {}
            for doc in agent.managed_docs:
                resolved = self._resolve(agent, doc, defaults)
                key = (str(Path(doc.path).resolve().parent), doc.kind)
                if key in seen:
                    raise ManagedDocRegistryError(
                        f"duplicate managed-doc (parent dir, kind) {key!r}: "
                        "two docs share a .meta directory — reconfigure paths"
                    )
                seen.add(key)
                by_kind[doc.kind] = resolved
            self._map[agent.name] = by_kind

    @classmethod
    def from_config(cls, config: AdapterConfig) -> ManagedDocRegistry:
        return cls(agents=config.agents, defaults=config.managed_doc_defaults)

    def get(self, agent_name: str, doc_kind: str) -> ManagedDocConfig:
        by_kind = self._map.get(agent_name)
        if by_kind is None:
            raise AgentNotFoundError(f"Agent '{agent_name}' not found")
        doc = by_kind.get(doc_kind)
        if doc is None:
            raise DocNotFoundError(
                f"doc_kind '{doc_kind}' not registered for agent '{agent_name}'"
            )
        return doc

    def agents(self) -> list[str]:
        return list(self._map.keys())

    @staticmethod
    def _resolve(
        agent: AgentEntryConfig,
        doc: ManagedDocConfig,
        defaults: ManagedDocDefaults,
    ) -> ManagedDocConfig:
        if doc.apply != "restart":
            # file_only 不需要 restart 参数解析；原样返回。
            return doc

        eff = defaults.effective_defaults()
        fields: dict[str, Any] = {
            "kind": doc.kind,
            "path": doc.path,
            "allow_root": doc.allow_root,
            "apply": "restart",
        }

        # profile 背书的参数：doc > defaults 显式 > profile 基线
        for f in _PROFILE_FIELDS:
            val: Any = getattr(doc, f)
            if val is None:
                val = getattr(defaults, f)
            if val is None:
                val = eff[f]
            fields[f] = val

        # 无 profile 基线的 restart 字段：doc > defaults（仍 None 则留 None）
        for f in _NON_PROFILE_RESTART_FIELDS:
            v: Any = getattr(doc, f)
            if v is None:
                v = getattr(defaults, f)
            fields[f] = v

        # restart_cmd：doc > defaults（二者皆空 → 构造时 validator 报错，转译为 registry 错误）
        fields["restart_cmd"] = doc.restart_cmd or defaults.restart_cmd

        # health_url：doc > defaults > f"{agent_url}/health"（独立于 agent_clients）
        health_url = doc.health_url or defaults.health_url
        if not health_url:
            if not agent.agent_url:
                raise ManagedDocRegistryError(
                    f"restart doc '{doc.kind}' for agent '{agent.name}' requires "
                    "health_url or agent_url to derive it"
                )
            health_url = f"{agent.agent_url}/health"
        fields["health_url"] = health_url

        try:
            return ManagedDocConfig(**fields)
        except ValueError as exc:
            # apply=restart but restart_cmd unresolved → validator 触发
            raise ManagedDocRegistryError(
                f"restart doc '{doc.kind}' for agent '{agent.name}': {exc}"
            ) from exc
