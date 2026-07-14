"""Build SkillStore / CompositeSkillStore from AdapterConfig."""

from __future__ import annotations

from pathlib import Path

import structlog

from agent_adapter.config import AdapterConfig, AgentEntryConfig
from agent_adapter.jiuwenbox_client import JiuwenBoxClient
from agent_adapter.jiuwenbox_skill_store import JiuwenBoxSkillStore
from agent_adapter.skill_store import CompositeSkillStore, SkillStore, SkillStoreProtocol

logger = structlog.get_logger(__name__)


def build_skill_store(config: AdapterConfig) -> SkillStoreProtocol:
    """Create the skill store used by the HTTP API.

    - All agents on ``local`` backend → classic :class:`SkillStore`
    - Any agent on ``jiuwenbox`` → :class:`CompositeSkillStore` with per-agent backends
    """
    skills_root = Path(config.skills_root)
    agents = list(config.agents)
    if not agents:
        return SkillStore(skills_root=skills_root, agent_skills_dirs={})

    backends: dict[str, SkillStoreProtocol] = {}
    local_agents: list[AgentEntryConfig] = []
    jiuwenbox_agents: list[AgentEntryConfig] = []

    for agent in agents:
        backend = (agent.skill_backend or "local").lower()
        if backend == "jiuwenbox":
            jiuwenbox_agents.append(agent)
        else:
            local_agents.append(agent)

    if local_agents:
        local_store = SkillStore.from_agent_configs(
            skills_root=skills_root,
            agents=local_agents,
        )
        for agent in local_agents:
            # Wrap so Composite can dispatch; share one Local store instance.
            backends[agent.name] = _AgentScopedStore(local_store, agent.name)

    if jiuwenbox_agents:
        # Group by jiuwenbox_url so we can share HTTP clients.
        by_url: dict[str, list[AgentEntryConfig]] = {}
        for agent in jiuwenbox_agents:
            url = (agent.jiuwenbox_url or "").strip()
            if not url:
                raise ValueError(
                    f"agent '{agent.name}' skill_backend=jiuwenbox requires jiuwenbox_url"
                )
            by_url.setdefault(url, []).append(agent)

        for url, group in by_url.items():
            client = JiuwenBoxClient(url)
            names = {a.name for a in group}
            remote_dirs = {
                a.name: (a.remote_skills_dir or "/tmp/skills").rstrip("/")
                for a in group
            }
            meta_dirs = {
                a.name: Path(a.skills_dir or (skills_root / a.name))
                for a in group
            }
            log_dirs = {a.name: a.log_dir for a in group}
            sandbox_ids = {a.name: a.sandbox_id for a in group}
            resolve_modes = {
                a.name: (a.sandbox_id_resolve or "from_logs")  # type: ignore[misc]
                for a in group
            }
            # Default resolve/sandbox from first agent for shared store fields.
            first = group[0]
            jb_store = JiuwenBoxSkillStore(
                agent_names=names,
                client=client,
                remote_skills_dir=(first.remote_skills_dir or "/tmp/skills"),
                local_meta_root=skills_root,
                sandbox_id=first.sandbox_id,
                sandbox_id_resolve=first.sandbox_id_resolve or "from_logs",
                log_dir=first.log_dir,
                log_pattern=first.log_pattern or config.log_pattern,
                agent_remote_dirs=remote_dirs,
                agent_meta_dirs=meta_dirs,
                agent_log_dirs=log_dirs,
                agent_sandbox_ids=sandbox_ids,
                agent_resolve_modes=resolve_modes,
            )
            for agent in group:
                backends[agent.name] = _AgentScopedStore(jb_store, agent.name)
            logger.info(
                "jiuwenbox_skill_backend_enabled",
                jiuwenbox_url=url,
                agents=sorted(names),
            )

    if not jiuwenbox_agents:
        return SkillStore.from_agent_configs(skills_root=skills_root, agents=agents)

    return CompositeSkillStore(backends)


class _AgentScopedStore:
    """Expose a multi-agent store as if it only served one agent name.

    CompositeSkillStore keys by agent_name; local SkillStore already accepts
    agent_name as an argument, so this wrapper just validates the key matches.
    """

    def __init__(self, store: SkillStoreProtocol, agent_name: str) -> None:
        self._store = store
        self._agent_name = agent_name

    def list_skills(self, agent_name: str):
        self._check(agent_name)
        return self._store.list_skills(agent_name)

    def read_skill(self, agent_name: str, skill_name: str):
        self._check(agent_name)
        return self._store.read_skill(agent_name, skill_name)

    def update_skill(self, agent_name: str, skill_name: str, content: str):
        self._check(agent_name)
        return self._store.update_skill(agent_name, skill_name, content)

    def restore_skills(self, agent_name: str, skill_names: list[str]):
        self._check(agent_name)
        return self._store.restore_skills(agent_name, skill_names)

    def get_revision(self, agent_name: str, skill_name: str):
        self._check(agent_name)
        return self._store.get_revision(agent_name, skill_name)

    def _check(self, agent_name: str) -> None:
        if agent_name != self._agent_name:
            from agent_adapter.skill_store import AgentNotFoundError

            raise AgentNotFoundError(f"Agent '{agent_name}' not found")
