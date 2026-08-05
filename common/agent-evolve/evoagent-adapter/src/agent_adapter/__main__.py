"""Entry point for `python -m agent_adapter`.

Loads config and starts uvicorn with the adapter FastAPI app.
By default, loads `<adapter_root>/agent_adapter_config.yaml` if it exists;
override path resolution with `--config` via the CLI (`python -m agent_adapter.cli start`).
"""

import faulthandler
from pathlib import Path

import uvicorn

from agent_adapter.api.app import create_app
from agent_adapter.config import load_config
from agent_adapter.logging import configure_logging

# Enable faulthandler to print traceback on SIGABRT/SIGSEGV
faulthandler.enable()

# Default YAML config location: <adapter_root>/agent_adapter_config.yaml
_DEFAULT_YAML = Path(__file__).resolve().parent.parent.parent / "agent_adapter_config.yaml"


if __name__ == "__main__":
    configure_logging()  # reads ADAPTER_LOG_LEVEL env var, defaults to INFO
    yaml_path = _DEFAULT_YAML if _DEFAULT_YAML.exists() else None
    cfg = load_config(yaml_path)
    app = create_app(cfg)
    uvicorn.run(app, host=cfg.host, port=cfg.port, loop="asyncio")
