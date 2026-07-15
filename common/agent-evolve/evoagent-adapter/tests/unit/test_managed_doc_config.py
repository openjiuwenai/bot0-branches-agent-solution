"""Unit tests for managed-doc config layer (T1)."""

import pytest
from pydantic import ValidationError

from agent_adapter.config import (
    AdapterConfig,
    AgentEntryConfig,
    ManagedDocConfig,
    ManagedDocDefaults,
)

# ── AC1.1 apply=restart 无 restart_cmd → ValidationError ──────────────


def test_managed_doc_config_file_only_default() -> None:
    cfg = ManagedDocConfig(kind="agent_rule", path="/host/edp/AgentRule.md")
    assert cfg.apply == "file_only"
    assert cfg.restart_cmd is None


def test_restart_requires_cmd() -> None:
    with pytest.raises(ValidationError) as exc:
        ManagedDocConfig(kind="agent_rule", path="/x", apply="restart")
    assert "restart_cmd" in str(exc.value)


def test_restart_with_cmd_ok() -> None:
    cfg = ManagedDocConfig(
        kind="agent_rule",
        path="/x",
        apply="restart",
        restart_cmd="docker restart edp",
    )
    assert cfg.apply == "restart"
    assert cfg.restart_cmd == "docker restart edp"


# ── AC1.2 managed_doc_defaults 键拼错 → 报错（typed，extra forbid） ─────


def test_defaults_typo_key_rejected() -> None:
    with pytest.raises(ValidationError):
        ManagedDocDefaults(health_url_typo=1)  # type: ignore[call-arg]


def test_managed_doc_config_unknown_field_rejected() -> None:
    with pytest.raises(ValidationError):
        ManagedDocConfig(kind="agent_rule", path="/x", unknown_field=1)  # type: ignore[call-arg]


def test_adapter_config_managed_doc_defaults_typo_rejected() -> None:
    with pytest.raises(ValidationError):
        AdapterConfig(managed_doc_defaults={"profile": "burst", "health_url_typo": 1})  # type: ignore[arg-type]


# ── AC1.3 profile 默认 burst + burst/single 字段缺省取对应默认 ─────────


def test_defaults_profile_default_burst() -> None:
    d = ManagedDocDefaults()
    assert d.profile == "burst"
    assert d.task_ttl_seconds == 600
    assert d.shutdown_grace_timeout == 10.0


def test_defaults_effective_burst() -> None:
    d = ManagedDocDefaults()
    eff = d.effective_defaults()
    assert eff["max_attempts"] == 2
    assert eff["backoff_base"] == 3.0
    assert eff["backoff_max"] == 30.0
    assert eff["health_down_timeout"] == 15.0
    assert eff["health_up_timeout"] == 60.0
    assert eff["health_up_consecutive"] == 2
    assert eff["health_poll_interval"] == 0.5


def test_defaults_effective_single() -> None:
    d = ManagedDocDefaults(profile="single")
    eff = d.effective_defaults()
    assert eff["max_attempts"] == 3
    assert eff["backoff_base"] == 5.0
    assert eff["backoff_max"] == 60.0
    assert eff["health_up_timeout"] == 90.0
    assert eff["health_poll_interval"] == 1.0


def test_defaults_explicit_override_kept() -> None:
    d = ManagedDocDefaults(profile="burst", max_attempts=5)
    assert d.max_attempts == 5
    # effective_defaults 仍给 burst 基线（显式值在 registry 解析时才覆写基线）
    assert d.effective_defaults()["max_attempts"] == 2


# ── 挂载点 ───────────────────────────────────────────────────────────


def test_agent_entry_managed_docs_default_empty() -> None:
    agent = AgentEntryConfig(name="edp")
    assert agent.managed_docs == []


def test_agent_entry_managed_docs_populated() -> None:
    agent = AgentEntryConfig(
        name="edp",
        managed_docs=[
            ManagedDocConfig(
                kind="agent_rule",
                path="/host/edp/AgentRule.md",
                apply="restart",
                restart_cmd="docker restart edp",
            )
        ],
    )
    assert len(agent.managed_docs) == 1
    assert agent.managed_docs[0].kind == "agent_rule"


def test_adapter_config_managed_doc_defaults_typed_default() -> None:
    cfg = AdapterConfig()
    assert isinstance(cfg.managed_doc_defaults, ManagedDocDefaults)
    assert cfg.managed_doc_defaults.profile == "burst"


def test_load_config_managed_doc_defaults_typo_rejected(tmp_path) -> None:
    """AC1.2 端到端：YAML 里 managed_doc_defaults 拼错键 → load_config 报错。"""
    from agent_adapter.config import load_config

    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(
        "managed_doc_defaults:\n"
        "  profile: burst\n"
        "  health_url_typo: 1\n",
        encoding="utf-8",
    )
    with pytest.raises(ValidationError):
        load_config(yaml_path)
