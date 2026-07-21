"""CLI entry point for agent-adapter."""

from pathlib import Path

import structlog
import typer
from rich.console import Console
from rich.table import Table

from agent_adapter.config import load_config
from agent_adapter.logging import configure_logging

app = typer.Typer(help="Agent Adapter — EDPAgent log collection and structured extraction.")
console = Console()

logger = structlog.get_logger(__name__)


@app.command()
def start(
    config: Path | None = typer.Option(
        None,
        "--config",
        help="Path to YAML configuration file.",
    ),
) -> None:
    """Start the agent-adapter service (HTTP API + background poll loop)."""
    configure_logging()  # reads ADAPTER_LOG_LEVEL env var, defaults to INFO
    cfg = load_config(config)

    logger.info(
        "adapter_config_loaded",
        log_dir=cfg.log_dir,
        poll_interval=cfg.poll_interval,
        host=cfg.host,
        port=cfg.port,
    )

    table = Table(show_header=False, title="Agent Adapter Configuration")
    table.add_row("log_dir", cfg.log_dir)
    table.add_row("log_pattern", cfg.log_pattern)
    table.add_row("poll_interval", str(cfg.poll_interval))
    table.add_row("start_from", cfg.start_from)
    table.add_row("match_tags", ", ".join(cfg.match_tags))
    table.add_row("pair_timeout", str(cfg.pair_timeout))
    table.add_row("output_dir", cfg.output_dir)
    table.add_row("host", cfg.host)
    table.add_row("port", str(cfg.port))
    console.print(table)

    _run_server(cfg)


def _run_server(cfg: "AdapterConfig") -> None:  # noqa: F821
    """Create the FastAPI app and start uvicorn.

    Separated for testability — tests can mock this function to
    prevent the blocking uvicorn.run() call.
    """
    import uvicorn

    from agent_adapter.api.app import create_app

    api_app = create_app(cfg)
    uvicorn.run(api_app, host=cfg.host, port=cfg.port, loop="asyncio")


if __name__ == "__main__":
    app()
