"""Shared filesystem paths for repository-local assets."""

from __future__ import annotations

from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parent
SRC_ROOT = PACKAGE_ROOT.parent
PROJECT_ROOT = SRC_ROOT.parent
SCENARIOS_DIR = PROJECT_ROOT / "scenarios"
