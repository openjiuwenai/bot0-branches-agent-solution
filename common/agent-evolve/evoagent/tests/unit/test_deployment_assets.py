"""Deployment asset contract tests."""

import os
from pathlib import Path

_DEPLOYMENT = Path(__file__).resolve().parents[2] / "deployment"


def test_documented_shell_entrypoints_are_executable() -> None:
    for name in ("build.sh", "run.sh", "stop.sh", "export-bundle.sh", "import-bundle.sh"):
        path = _DEPLOYMENT / name
        assert path.is_file(), path
        assert os.access(path, os.X_OK), f"{path} must be executable"
