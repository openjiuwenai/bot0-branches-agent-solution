"""Unit tests for ManagedDocRegistry (T3)."""

import pytest

from agent_adapter.config import AgentEntryConfig, ManagedDocConfig, ManagedDocDefaults
from agent_adapter.managed_doc.registry import (
    AgentNotFoundError,
    DocNotFoundError,
    ManagedDocRegistry,
    ManagedDocRegistryError,
)


def _agent(
    name: str = "edp",
    *,
    agent_url: str | None = "http://localhost:8090",
    project_id: str | None = "p",
    agent_id: str | None = "a",
    docs: list[ManagedDocConfig] | None = None,
) -> AgentEntryConfig:
    return AgentEntryConfig(
        name=name,
        agent_url=agent_url,
        project_id=project_id,
        agent_id=agent_id,
        managed_docs=docs or [],
    )


# ── 基本注册与 get ───────────────────────────────────────────────────


def test_get_returns_config_for_registered_doc() -> None:
    doc = ManagedDocConfig(
        kind="agent_rule",
        path="/host/edp/AgentRule.md",
        apply="file_only",
    )
    reg = ManagedDocRegistry(agents=[_agent(docs=[doc])], defaults=ManagedDocDefaults())
    got = reg.get("edp", "agent_rule")
    assert got.kind == "agent_rule"
    assert got.apply == "file_only"


def test_get_unknown_agent_raises() -> None:
    reg = ManagedDocRegistry(agents=[_agent(docs=[])], defaults=ManagedDocDefaults())
    with pytest.raises(AgentNotFoundError):
        reg.get("missing", "agent_rule")


def test_get_unknown_doc_kind_raises() -> None:
    doc = ManagedDocConfig(kind="agent_rule", path="/x", apply="file_only")
    reg = ManagedDocRegistry(agents=[_agent(docs=[doc])], defaults=ManagedDocDefaults())
    with pytest.raises(DocNotFoundError):
        reg.get("edp", "no_such_kind")


# ── AC3.1 restart 无 agent_url 且无 health_url → 启动报错 ─────────────


def test_restart_without_agent_url_and_health_url_raises() -> None:
    doc = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
        health_url="http://localhost:9999/health",  # 显式给 → 不应报错
    )
    # 无 agent_url 但有显式 health_url → OK
    reg = ManagedDocRegistry(
        agents=[_agent(agent_url=None, project_id=None, agent_id=None, docs=[doc])],
        defaults=ManagedDocDefaults(),
    )
    assert reg.get("edp", "agent_rule").health_url == "http://localhost:9999/health"

    # 无 agent_url 且无 health_url → 报错
    bad = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
    )
    with pytest.raises(ManagedDocRegistryError):
        ManagedDocRegistry(
            agents=[_agent(agent_url=None, project_id=None, agent_id=None, docs=[bad])],
            defaults=ManagedDocDefaults(),
        )


# ── health_url 派生 ──────────────────────────────────────────────────


def test_restart_health_url_derived_from_agent_url() -> None:
    doc = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
    )
    reg = ManagedDocRegistry(
        agents=[_agent(agent_url="http://localhost:8090", docs=[doc])],
        defaults=ManagedDocDefaults(),
    )
    assert reg.get("edp", "agent_rule").health_url == "http://localhost:8090/health"


def test_restart_explicit_health_url_beats_agent_url() -> None:
    doc = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
        health_url="http://explicit/health",
    )
    reg = ManagedDocRegistry(
        agents=[_agent(agent_url="http://localhost:8090", docs=[doc])],
        defaults=ManagedDocDefaults(),
    )
    assert reg.get("edp", "agent_rule").health_url == "http://explicit/health"


# ── AC3.2 缺 project_id/agent_id 仍可注册 restart doc ────────────────


def test_agent_missing_project_id_agent_id_still_registers_restart() -> None:
    doc = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
    )
    # agent_url 有（可派生 health_url），但 project_id/agent_id 缺失 → 仍注册
    reg = ManagedDocRegistry(
        agents=[
            _agent(
                agent_url="http://localhost:8090",
                project_id=None,
                agent_id=None,
                docs=[doc],
            )
        ],
        defaults=ManagedDocDefaults(),
    )
    got = reg.get("edp", "agent_rule")
    assert got.apply == "restart"
    assert got.health_url == "http://localhost:8090/health"


# ── profile 默认值解析 ───────────────────────────────────────────────


def test_burst_profile_defaults_applied_to_none_fields() -> None:
    doc = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
    )
    reg = ManagedDocRegistry(
        agents=[_agent(agent_url="http://localhost:8090", docs=[doc])],
        defaults=ManagedDocDefaults(profile="burst"),
    )
    got = reg.get("edp", "agent_rule")
    assert got.max_attempts == 2
    assert got.backoff_base == 3.0
    assert got.health_up_timeout == 60.0


def test_single_profile_defaults_applied() -> None:
    doc = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
    )
    reg = ManagedDocRegistry(
        agents=[_agent(agent_url="http://localhost:8090", docs=[doc])],
        defaults=ManagedDocDefaults(profile="single"),
    )
    got = reg.get("edp", "agent_rule")
    assert got.max_attempts == 3
    assert got.health_up_timeout == 90.0


def test_explicit_doc_override_beats_defaults() -> None:
    doc = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
        max_attempts=7,
    )
    reg = ManagedDocRegistry(
        agents=[_agent(agent_url="http://localhost:8090", docs=[doc])],
        defaults=ManagedDocDefaults(profile="burst", max_attempts=5),
    )
    got = reg.get("edp", "agent_rule")
    assert got.max_attempts == 7  # doc 显式 > defaults 显式 > profile 基线


# 注：apply=restart 无 restart_cmd 的拦截由 ManagedDocConfig 自身 validator
# 兜底（T1 AC1.1），defaults.restart_cmd 无法补——doc 级即报错，registry 不可能
# 收到缺 restart_cmd 的 restart doc。故此处不再测该路径。


# ── 从 AdapterConfig 构建 ────────────────────────────────────────────


def test_from_config_builds_registry(tmp_path) -> None:
    from agent_adapter.config import load_config

    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(
        "agents:\n"
        "  - name: edp\n"
        "    agent_url: http://localhost:8090\n"
        "    managed_docs:\n"
        "      - kind: agent_rule\n"
        "        path: /host/edp/AgentRule.md\n"
        "        apply: restart\n"
        "        restart_cmd: docker restart edp\n",
        encoding="utf-8",
    )
    config = load_config(yaml_path)
    reg = ManagedDocRegistry.from_config(config)
    got = reg.get("edp", "agent_rule")
    assert got.apply == "restart"
    assert got.health_url == "http://localhost:8090/health"
