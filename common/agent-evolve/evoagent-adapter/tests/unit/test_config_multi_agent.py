"""Unit tests for multi-Agent config model (Issue #1)."""

import textwrap

import pytest

from agent_adapter.config import AdapterConfig, AgentEntryConfig, load_config


class TestAgentEntryConfigModel:
    """AgentEntryConfig has the correct fields and defaults."""

    def test_required_fields(self):
        """name and log_dir are required; all others have defaults."""
        entry = AgentEntryConfig(name="edp_agent", log_dir="/var/log/edp")
        assert entry.name == "edp_agent"
        assert entry.log_dir == "/var/log/edp"

    def test_default_values(self):
        """Optional fields have sensible defaults."""
        entry = AgentEntryConfig(name="edp_agent", log_dir="/var/log/edp")
        assert entry.log_pattern is None
        assert entry.output_dir is None
        assert entry.offset_file is None
        assert entry.agent_url is None
        assert entry.project_id is None
        assert entry.agent_id is None
        assert entry.timeout == 300

    def test_all_fields_set(self):
        """All fields can be explicitly set."""
        entry = AgentEntryConfig(
            name="edp_agent",
            log_dir="/var/log/edp",
            log_pattern="app_*.log",
            output_dir="/data/output/edp",
            offset_file="/data/offsets/edp.json",
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=600,
        )
        assert entry.name == "edp_agent"
        assert entry.log_pattern == "app_*.log"
        assert entry.agent_url == "http://localhost:8090"
        assert entry.project_id == "proj_001"
        assert entry.agent_id == "edp_agent"
        assert entry.timeout == 600


class TestAdapterConfigAgentsField:
    """AdapterConfig.agents is a list of AgentEntryConfig, defaulting to empty."""

    def test_agents_default_empty(self):
        """Without agents in config, the list is empty."""
        config = AdapterConfig()
        assert config.agents == []

    def test_agents_with_entries(self):
        """agents can be populated with AgentEntryConfig items."""
        config = AdapterConfig(
            agents=[
                AgentEntryConfig(name="edp_agent", log_dir="/var/log/edp"),
                AgentEntryConfig(name="other_agent", log_dir="/var/log/other"),
            ],
        )
        assert len(config.agents) == 2
        assert config.agents[0].name == "edp_agent"
        assert config.agents[1].name == "other_agent"


class TestLoadConfigWithAgents:
    """load_config parses agents list from YAML with defaults and path resolution."""

    def test_load_agents_from_yaml(self, tmp_path):
        """Agents list is parsed from YAML with all fields."""
        from agent_adapter.config import _ADAPTER_ROOT

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                poll_interval: 10
                agents:
                  - name: edp_agent
                    log_dir: /var/log/edp
                    agent_url: http://localhost:8090
                    project_id: proj_001
                    agent_id: edp_agent
                  - name: other_agent
                    log_dir: /var/log/other
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        assert len(config.agents) == 2
        assert config.agents[0].name == "edp_agent"
        assert config.agents[0].log_dir == "/var/log/edp"
        assert config.agents[0].agent_url == "http://localhost:8090"
        assert config.agents[0].project_id == "proj_001"
        assert config.agents[1].name == "other_agent"

    def test_agents_default_paths_generated(self, tmp_path):
        """When output_dir/offset_file are omitted, defaults are generated from agent name."""
        from agent_adapter.config import _ADAPTER_ROOT

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                agents:
                  - name: edp_agent
                    log_dir: /var/log/edp
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        agent = config.agents[0]
        # Default output_dir: data/output/{name} resolved to adapter root
        assert agent.output_dir == str((_ADAPTER_ROOT / "data" / "output" / "edp_agent").resolve())
        # Default offset_file: data/offsets/{name}.json resolved to adapter root
        assert agent.offset_file == str((_ADAPTER_ROOT / "data" / "offsets" / "edp_agent.json").resolve())

    def test_agents_log_pattern_inherits_top_level(self, tmp_path):
        """When log_pattern is omitted in agent entry, it inherits from top-level."""
        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                log_pattern: "app_*.log"
                agents:
                  - name: edp_agent
                    log_dir: /var/log/edp
                  - name: other_agent
                    log_dir: /var/log/other
                    log_pattern: "debug_*.log"
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        assert config.agents[0].log_pattern == "app_*.log"
        assert config.agents[1].log_pattern == "debug_*.log"

    def test_agents_relative_paths_resolved(self, tmp_path):
        """Relative log_dir in agent entry is resolved against adapter root."""
        from agent_adapter.config import _ADAPTER_ROOT

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                agents:
                  - name: edp_agent
                    log_dir: logs/edp
                    output_dir: data/output/edp
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        agent = config.agents[0]
        assert agent.log_dir == str((_ADAPTER_ROOT / "logs" / "edp").resolve())
        assert agent.output_dir == str((_ADAPTER_ROOT / "data" / "output" / "edp").resolve())

    def test_agents_explicit_paths_not_overridden(self, tmp_path):
        """Explicit output_dir/offset_file in YAML are not overridden by defaults."""
        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                agents:
                  - name: edp_agent
                    log_dir: /var/log/edp
                    output_dir: /custom/output
                    offset_file: /custom/offsets.json
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        agent = config.agents[0]
        assert agent.output_dir == "/custom/output"
        assert agent.offset_file == "/custom/offsets.json"


class TestBackwardCompatSingleAgent:
    """Without agents field, config falls back to single-agent v2 behavior."""

    def test_no_agents_field_creates_single_agent(self, tmp_path):
        """When no agents field in YAML, a single agent is created from top-level paths."""
        from agent_adapter.config import _ADAPTER_ROOT

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                log_dir: /var/log/edp
                log_pattern: "process_*.log"
                output_dir: /data/output
                offset_file: /data/offsets.json
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        # Single agent auto-created from top-level fields
        assert len(config.agents) == 1
        agent = config.agents[0]
        assert agent.name == "default"
        assert agent.log_dir == "/var/log/edp"
        assert agent.log_pattern == "process_*.log"
        assert agent.output_dir == "/data/output"
        assert agent.offset_file == "/data/offsets.json"

    def test_no_agents_no_yaml_creates_single_agent(self, tmp_path):
        """When no config file at all, single agent uses resolved defaults."""
        from agent_adapter.config import _ADAPTER_ROOT

        config = load_config(tmp_path / "nonexistent.yaml")

        assert len(config.agents) == 1
        agent = config.agents[0]
        assert agent.name == "default"
        assert agent.log_dir == str((_ADAPTER_ROOT / "logs").resolve())
        assert agent.output_dir == str((_ADAPTER_ROOT / "data" / "output").resolve())
        assert agent.offset_file == str((_ADAPTER_ROOT / "data" / "offsets.json").resolve())
