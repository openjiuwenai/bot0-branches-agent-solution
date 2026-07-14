"""Integration tests for the agent-adapter CLI entry point."""

import textwrap
from unittest.mock import patch

from typer.testing import CliRunner

from agent_adapter.cli import app

runner = CliRunner()


class TestCLIEntryPoint:
    """agent-adapter CLI loads config and prints summary."""

    @patch("agent_adapter.cli._run_server")
    def test_config_flag_loads_yaml(self, mock_run, tmp_path):
        yaml_path = tmp_path / "test_config.yaml"
        yaml_path.write_text(
            textwrap.dedent("""\
                log_dir: /custom/logs
                poll_interval: 10
                port: 9000
            """),
            encoding="utf-8",
        )
        result = runner.invoke(app, ["--config", str(yaml_path)])
        assert result.exit_code == 0
        # Summary should reflect loaded config
        assert "/custom/logs" in result.output
        assert "10" in result.output
        assert "9000" in result.output

    @patch("agent_adapter.cli._run_server")
    def test_no_config_uses_defaults(self, mock_run):
        result = runner.invoke(app, ["--config", "/nonexistent/path.yaml"])
        assert result.exit_code == 0
        # Defaults should appear in summary
        assert "logs" in result.output
        assert "8900" in result.output

    def test_help_shows_config_option(self):
        result = runner.invoke(app, ["--help"])
        assert result.exit_code == 0
        assert "--config" in result.output
