"""structlog JSON 日志配置。"""

from __future__ import annotations

import logging
import sys

import structlog


def configure_logging() -> None:
    """配置 structlog JSON 输出。

    输出格式包含 timestamp, level, event 字段。
    通过 contextvars 注入的 run_id 等字段会自动出现在日志中。
    """
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(file=sys.stderr),
        cache_logger_on_first_use=True,
    )
