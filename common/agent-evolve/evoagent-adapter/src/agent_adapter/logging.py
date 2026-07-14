"""structlog JSON logging configuration."""

import logging
import os

import structlog


def configure_logging(log_level: str | None = None) -> None:
    """Configure structlog for JSON output.

    Output format includes: timestamp, level, event, and any bound key-value pairs.
    Must be called once at application startup before any logging calls.

    Args:
        log_level: Log level name (DEBUG/INFO/WARNING/ERROR).
                   If None, reads from ADAPTER_LOG_LEVEL env var, defaults to INFO.
    """
    if log_level is None:
        log_level = os.environ.get("ADAPTER_LOG_LEVEL", "INFO")
    logging.basicConfig(level=log_level.upper(), format="%(message)s")
    structlog.configure(
        processors=[
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            getattr(logging, log_level.upper(), logging.INFO),
        ),
        cache_logger_on_first_use=True,
    )
