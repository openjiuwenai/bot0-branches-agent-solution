"""Unit tests for AdapterConfig loading and defaults."""

import textwrap

import pytest

from agent_adapter.config import AdapterConfig, load_config


class TestAdapterConfigDefaults:
    """AdapterConfig uses sensible defaults when no config file or env vars exist."""

    def test_default_values_when_no_yaml(self, tmp_path):
        config = AdapterConfig()
        assert config.log_dir == "logs"
        assert config.log_pattern == "process*.log"
        assert config.poll_interval == 60
        assert config.start_from == "tail"
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
        assert config.start_from == "tail"
        assert config.pair_timeout == 300
        assert config.port == 8900

    def test_missing_yaml_file_uses_defaults(self, tmp_path):
        """When config file doesn't exist, defaults are used but paths are still resolved."""
        from agent_adapter.config import _ADAPTER_ROOT

        nonexistent = tmp_path / "does_not_exist.yaml"
        config = load_config(nonexistent)
        # Paths are resolved to adapter root even when config file doesn't exist
        assert config.log_dir == str(_ADAPTER_ROOT / "logs")
        assert config.poll_interval == 60

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


class TestEnvRefInterpolation:
    """${VAR} / ${VAR:default} placeholders in YAML resolve against os.environ.

    This is the mechanism that makes ``agent_url: ${EDP_AGENT_URL:}`` work:
    yaml.safe_load stores the literal placeholder; load_config expands it.
    """

    def test_default_used_when_var_unset(self, tmp_path, monkeypatch):
        monkeypatch.delenv("EDP_AGENT_TIMEOUT", raising=False)
        yaml_path = tmp_path / "c.yaml"
        yaml_path.write_text("poll_interval: ${ADAPTER_POLL_INTERVAL:42}", encoding="utf-8")
        monkeypatch.delenv("ADAPTER_POLL_INTERVAL", raising=False)
        config = load_config(yaml_path)
        assert config.poll_interval == 42

    def test_env_value_used_when_var_set(self, tmp_path, monkeypatch):
        yaml_path = tmp_path / "c.yaml"
        yaml_path.write_text("poll_interval: ${ADAPTER_POLL_INTERVAL:42}", encoding="utf-8")
        monkeypatch.setenv("ADAPTER_POLL_INTERVAL", "7")
        config = load_config(yaml_path)
        assert config.poll_interval == 7

    def test_empty_env_falls_back_to_default(self, tmp_path, monkeypatch):
        """空串环境变量视为未设置 → 走默认（避免 int/float 空串强制转换报错）."""
        yaml_path = tmp_path / "c.yaml"
        yaml_path.write_text("poll_interval: ${ADAPTER_POLL_INTERVAL:42}", encoding="utf-8")
        monkeypatch.setenv("ADAPTER_POLL_INTERVAL", "")
        config = load_config(yaml_path)
        assert config.poll_interval == 42

    def test_empty_default_braces(self, tmp_path, monkeypatch):
        """${VAR:} 未设置 → 空串（兼容既有 agent_url 写法）."""
        from agent_adapter.config import _expand_env_refs

        monkeypatch.delenv("AGENT_URL", raising=False)
        assert _expand_env_refs("${AGENT_URL:}") == ""
        assert _expand_env_refs("${AGENT_URL}") == ""

    def test_nested_agent_fields_resolved(self, tmp_path, monkeypatch):
        """per-agent 占位在 agents 列表内递归展开."""
        yaml_path = tmp_path / "c.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                agents:
                  - name: edp_agent
                    agent_url: ${EDP_AGENT_URL:}
                    project_id: ${EDP_AGENT_PROJECT_ID:proj_001}
                    timeout: ${EDP_AGENT_TIMEOUT:300}
            """),
            encoding="utf-8",
        )
        monkeypatch.delenv("EDP_AGENT_URL", raising=False)
        monkeypatch.setenv("EDP_AGENT_PROJECT_ID", "proj_override")
        monkeypatch.delenv("EDP_AGENT_TIMEOUT", raising=False)
        config = load_config(yaml_path)
        agent = config.agents[0]
        assert agent.agent_url == ""      # empty default → log-only
        assert agent.project_id == "proj_override"
        assert agent.timeout == 300       # int coercion from default "300"

    def test_env_still_overrides_expanded_yaml_default(self, tmp_path, monkeypatch):
        """ADAPTER_* env 优先级高于 yaml 中的 ${ADAPTER_*:default} 占位."""
        yaml_path = tmp_path / "c.yaml"
        yaml_path.write_text("poll_interval: ${ADAPTER_POLL_INTERVAL:42}", encoding="utf-8")
        # Both the placeholder default and env point to ADAPTER_POLL_INTERVAL;
        # env must win (env_overridden_keys excludes the field → reads env).
        monkeypatch.setenv("ADAPTER_POLL_INTERVAL", "99")
        config = load_config(yaml_path)
        assert config.poll_interval == 99

    def test_managed_docs_per_agent_resolved(self, tmp_path, monkeypatch):
        """managed_docs (per-agent list[dict]) 占位由 _expand_env_refs 展开。

        覆盖：int 字段强制转换、Literal 字段、嵌套 dict/list 递归、默认值、env 覆盖。
        managed_docs 仍是 yaml 维护的列表结构（per-agent 唯一 env 通道）。
        """
        yaml_path = tmp_path / "c.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                agents:
                  - name: edp_agent
                    managed_docs:
                      - kind: ${EDP_AGENT_MDOC0_KIND:agent_rule}
                        path: ${EDP_AGENT_MDOC0_PATH:/data/agents/edp_agent/AgentRule.md}
                        max_content_bytes: ${EDP_AGENT_MDOC0_MAX_CONTENT_BYTES:262144}
                        apply: ${EDP_AGENT_MDOC0_APPLY:file_only}
            """),
            encoding="utf-8",
        )
        # 默认值路径（env 全不设）
        for v in ("EDP_AGENT_MDOC0_KIND", "EDP_AGENT_MDOC0_PATH",
                  "EDP_AGENT_MDOC0_MAX_CONTENT_BYTES", "EDP_AGENT_MDOC0_APPLY",
                  "EDP_AGENT_MDOC0_RESTART_CMD"):
            monkeypatch.delenv(v, raising=False)
        config = load_config(yaml_path)
        md = config.agents[0].managed_docs[0]
        assert md.kind == "agent_rule"
        assert md.path == "/data/agents/edp_agent/AgentRule.md"
        assert md.max_content_bytes == 262144  # int coercion from "262144"
        assert md.apply == "file_only"

        # env 覆盖（int + Literal）
        monkeypatch.setenv("EDP_AGENT_MDOC0_MAX_CONTENT_BYTES", "1024")
        monkeypatch.setenv("EDP_AGENT_MDOC0_APPLY", "restart")
        monkeypatch.setenv("EDP_AGENT_MDOC0_RESTART_CMD", "docker restart edp_agent")
        yaml_path.write_text(
            textwrap.dedent("""\
                agents:
                  - name: edp_agent
                    managed_docs:
                      - kind: ${EDP_AGENT_MDOC0_KIND:agent_rule}
                        path: ${EDP_AGENT_MDOC0_PATH:/data/agents/edp_agent/AgentRule.md}
                        max_content_bytes: ${EDP_AGENT_MDOC0_MAX_CONTENT_BYTES:262144}
                        apply: ${EDP_AGENT_MDOC0_APPLY:file_only}
                        restart_cmd: ${EDP_AGENT_MDOC0_RESTART_CMD:}
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)
        md = config.agents[0].managed_docs[0]
        assert md.max_content_bytes == 1024
        assert md.apply == "restart"
        assert md.restart_cmd == "docker restart edp_agent"

    def test_managed_doc_defaults_from_yaml_env_placeholder(self, tmp_path, monkeypatch):
        """managed_doc_defaults 在 yaml 中以 ${ADAPTER_MDD_*:default} 占位，
        由 _expand_env_refs 在加载期从 .env 展开。env 未设 → 占位默认。

        这是 'managed_doc_defaults 从 yaml 读取、值由 .env 提供' 的机制。
        """
        for v in ("ADAPTER_MDD_PROFILE", "ADAPTER_MDD_TASK_TTL_SECONDS",
                  "ADAPTER_MANAGED_DOC_DEFAULTS"):
            monkeypatch.delenv(v, raising=False)
        yaml_path = tmp_path / "c.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                managed_doc_defaults:
                  profile: ${ADAPTER_MDD_PROFILE:burst}
                  task_ttl_seconds: ${ADAPTER_MDD_TASK_TTL_SECONDS:600}
            """),
            encoding="utf-8",
        )
        # env 不设 → 占位默认
        config = load_config(yaml_path)
        assert config.managed_doc_defaults.profile == "burst"
        assert config.managed_doc_defaults.task_ttl_seconds == 600

        # env 覆盖（含 int 强制转换）
        monkeypatch.setenv("ADAPTER_MDD_PROFILE", "single")
        monkeypatch.setenv("ADAPTER_MDD_TASK_TTL_SECONDS", "120")
        config = load_config(yaml_path)
        assert config.managed_doc_defaults.profile == "single"
        assert config.managed_doc_defaults.task_ttl_seconds == 120


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
