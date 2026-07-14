"""Unit tests for AdapterConfig loading and defaults."""

import textwrap

import pytest

from agent_adapter.config import AdapterConfig, load_config


class TestAdapterConfigDefaults:
    """AdapterConfig uses sensible defaults when no config file or env vars exist."""

    def test_default_values_when_no_yaml(self, tmp_path):
        config = AdapterConfig()
        assert config.log_dir == "logs"
        assert config.log_pattern == "process_*.log"
        assert config.poll_interval == 5
        assert config.start_from == "head"
        assert config.match_tags == [
            "TAG_HTTP_REQUEST_START",
            "TAG_HTTP_REQUEST_END",
            "TAG_LLM_CALL_START",
            "TAG_LLM_CALL_END",
            "TAG_PLANNING_DECISION",
            "TAG_TOOL_EXECUTE_START",
            "TAG_TOOL_EXECUTE_END",
            "TAG_SKILL_EXECUTE_START",
            "TAG_SKILL_EXECUTE_END",
            "TAG_VERSATILE_START",
            "TAG_VERSATILE_END",
        ]
        assert config.pair_timeout == 300
        assert config.output_dir == "data/output"
        assert config.offset_file == "data/offsets.json"
        assert config.output_retention_days == 30
        assert config.output_max_files == 2000
        assert config.output_max_file_size == "20MB"
        assert config.output_trim_target_ratio == 0.7
        assert config.host == "0.0.0.0"
        assert config.port == 8900


class TestAdapterConfigFromYaml:
    """AdapterConfig loads values from a YAML config file."""

    def test_load_full_config_from_yaml(self, tmp_path):
        yaml_path = tmp_path / "adapter.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                log_dir: /var/log/edpagent
                log_pattern: "process_*.log"
                poll_interval: 10
                start_from: head
                match_tags:
                  - TAG_LLM_CALL_START
                  - TAG_LLM_CALL_END
                  - TAG_TOOL_EXECUTE_START
                pair_timeout: 600
                output_dir: /data/adapter-output
                offset_file: .agent-adapter/offsets.json
                output_retention_days: 14
                output_max_files: 500
                output_max_file_size: 100MB
                output_trim_target_ratio: 0.5
                host: 127.0.0.1
                port: 9000
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)
        assert config.log_dir == "/var/log/edpagent"
        assert config.poll_interval == 10
        assert config.start_from == "head"
        assert config.match_tags == [
            "TAG_LLM_CALL_START",
            "TAG_LLM_CALL_END",
            "TAG_TOOL_EXECUTE_START",
        ]
        assert config.pair_timeout == 600
        assert config.output_dir == "/data/adapter-output"
        assert config.port == 9000

    def test_partial_yaml_uses_defaults_for_missing_fields(self, tmp_path):
        yaml_path = tmp_path / "partial.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                log_dir: /custom/logs
                poll_interval: 3
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)
        assert config.log_dir == "/custom/logs"
        assert config.poll_interval == 3
        # Missing fields fall back to defaults
        assert config.start_from == "head"
        assert config.pair_timeout == 300
        assert config.port == 8900

    def test_missing_yaml_file_uses_defaults(self, tmp_path):
        """When config file doesn't exist, defaults are used but paths are still resolved."""
        from agent_adapter.config import _ADAPTER_ROOT

        nonexistent = tmp_path / "does_not_exist.yaml"
        config = load_config(nonexistent)
        # Paths are resolved to adapter root even when config file doesn't exist
        assert config.log_dir == str(_ADAPTER_ROOT / "logs")
        assert config.poll_interval == 5

    def test_start_from_validates_values(self):
        with pytest.raises(ValueError):
            AdapterConfig(start_from="middle")


class TestAdapterConfigEnvOverride:
    """ADAPTER_* environment variables override YAML and defaults."""

    def test_env_overrides_default(self, monkeypatch):
        monkeypatch.setenv("ADAPTER_POLL_INTERVAL", "10")
        config = AdapterConfig()
        assert config.poll_interval == 10

    def test_env_overrides_yaml(self, tmp_path, monkeypatch):
        yaml_path = tmp_path / "adapter.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                poll_interval: 3
                port: 9000
            """),
            encoding="utf-8",
        )
        monkeypatch.setenv("ADAPTER_POLL_INTERVAL", "15")
        config = load_config(yaml_path)
        # Env overrides YAML
        assert config.poll_interval == 15
        # YAML value used when no env override
        assert config.port == 9000

    def test_match_tags_from_env(self, monkeypatch):
        monkeypatch.setenv("ADAPTER_MATCH_TAGS", '["TAG_LLM_CALL_START","TAG_LLM_CALL_END","TAG_TOOL_EXECUTE_START"]')
        config = AdapterConfig()
        assert config.match_tags == [
            "TAG_LLM_CALL_START",
            "TAG_LLM_CALL_END",
            "TAG_TOOL_EXECUTE_START",
        ]


class TestRelativePathResolution:
    """Relative paths are always resolved relative to adapter root directory."""

    def test_relative_paths_resolved_to_adapter_root(self, tmp_path):
        """Relative paths are resolved relative to adapter root, not config file location."""
        from agent_adapter.config import _ADAPTER_ROOT

        yaml_path = tmp_path / "subdir" / "config.yaml"
        yaml_path.parent.mkdir(parents=True, exist_ok=True)
        yaml_path.write_text(
            textwrap.dedent("""\
                log_dir: logs
                output_dir: output
                offset_file: .agent-adapter/offsets.json
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        # Paths should be resolved relative to adapter root, NOT config file directory
        assert config.log_dir == str(_ADAPTER_ROOT / "logs")
        assert config.output_dir == str(_ADAPTER_ROOT / "output")
        assert config.offset_file == str(_ADAPTER_ROOT / ".agent-adapter" / "offsets.json")


    def test_absolute_paths_remain_unchanged(self, tmp_path):
        """Absolute paths are not modified."""
        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                log_dir: /var/log/edpagent
                output_dir: /data/output
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        # Absolute paths should remain unchanged
        assert config.log_dir == "/var/log/edpagent"
        assert config.output_dir == "/data/output"

    def test_no_config_file_still_resolves_to_adapter_root(self, tmp_path, monkeypatch):
        """Even without a config file, relative paths are resolved to adapter root."""
        from agent_adapter.config import _ADAPTER_ROOT

        monkeypatch.chdir(tmp_path)
        config = load_config(None)

        # Without config file, paths are still resolved to adapter root
        assert config.log_dir == str(_ADAPTER_ROOT / "logs")
        assert config.output_dir == str(_ADAPTER_ROOT / "data" / "output")

    def test_env_var_paths_not_resolved(self, tmp_path, monkeypatch):
        """Paths set via environment variables are not resolved."""
        from agent_adapter.config import _ADAPTER_ROOT

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                log_dir: from_yaml
                output_dir: from_yaml_output
            """),
            encoding="utf-8",
        )
        monkeypatch.setenv("ADAPTER_LOG_DIR", "from_env")
        config = load_config(yaml_path)

        # Env var value should be used as-is (not resolved)
        assert config.log_dir == "from_env"
        # YAML value should be resolved to adapter root
        assert config.output_dir == str(_ADAPTER_ROOT / "from_yaml_output")
