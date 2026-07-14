"""Unit tests for structlog JSON logging setup."""

import json

from agent_adapter.logging import configure_logging


class TestStructlogSetup:
    """structlog outputs valid JSON with required fields."""

    def test_structlog_outputs_valid_json(self, capfd):
        configure_logging()
        import structlog

        logger = structlog.get_logger("test_logger")
        logger.info("adapter_started", poll_interval=5)
        captured = capfd.readouterr()
        output_line = captured.out.strip().split("\n")[-1]
        parsed = json.loads(output_line)
        assert isinstance(parsed, dict)

    def test_structlog_includes_timestamp(self, capfd):
        configure_logging()
        import structlog

        logger = structlog.get_logger("test_logger")
        logger.info("test_event")
        captured = capfd.readouterr()
        output_line = captured.out.strip().split("\n")[-1]
        parsed = json.loads(output_line)
        assert "timestamp" in parsed

    def test_structlog_includes_event(self, capfd):
        configure_logging()
        import structlog

        logger = structlog.get_logger("test_logger")
        logger.info("config_loaded", port=8900)
        captured = capfd.readouterr()
        output_line = captured.out.strip().split("\n")[-1]
        parsed = json.loads(output_line)
        assert parsed["event"] == "config_loaded"
        assert parsed["port"] == 8900
