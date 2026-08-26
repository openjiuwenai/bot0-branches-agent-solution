from __future__ import annotations

import asyncio
import importlib.resources
import json
import os
import subprocess
import sys
from pathlib import Path

from skill_builder import (
    SkillBuilderClient,
    SkillBuilderInput,
    SkillBuilderOptions,
    SkillBuilderState,
)
from skill_builder.adapters import SubprocessAgentRunner
from skill_builder.domain.decision_registry import canonical_decision_value
from skill_builder.spi import JsonFileStateStore


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def test_public_import_does_not_load_host_frameworks() -> None:
    command = (
        "import json,sys,skill_builder; "
        "print(json.dumps(sorted(name for name in sys.modules "
        "if name.startswith(('plugins_market','fastapi','sqlalchemy')))))"
    )
    result = subprocess.run(
        [sys.executable, "-c", command],
        check=True,
        capture_output=True,
        env={
            **os.environ,
            "PYTHONPATH": str(PROJECT_ROOT / "src"),
        },
        text=True,
    )
    assert json.loads(result.stdout) == []


def test_internal_skill_resources_are_packaged() -> None:
    root = importlib.resources.files("skill_builder.resources")
    assert (
        root / "internal-skills" / "scenario-skill-builder" / "SKILL.md"
    ).is_file()
    assert (
        root / "internal-skills" / "skill-package-author" / "SKILL.md"
    ).is_file()


def test_json_state_store_round_trip(tmp_path: Path) -> None:
    builder_input = SkillBuilderInput(
        root=tmp_path / "workspace",
        workspace_id="workspace-1",
        skill_name="sample",
        display_name="Sample",
        description="Sample",
        version="0.1.0",
        user_message="build",
        materials_markdown="material",
    )
    state = SkillBuilderState(
        input=builder_input,
        options=SkillBuilderOptions(),
    )
    store = JsonFileStateStore(tmp_path / "state")
    asyncio.run(store.save(state))
    loaded = asyncio.run(store.load("workspace-1"))
    assert loaded is not None
    assert loaded.to_dict() == state.to_dict()


def test_client_is_the_lifecycle_facade() -> None:
    assert callable(SkillBuilderClient().build)
    assert SubprocessAgentRunner.__name__ == "SubprocessAgentRunner"


def test_internal_system_exports_use_generic_manual_acquisition() -> None:
    assert canonical_decision_value(
        "acquisition_mode",
        "from_internal_system",
    ) == "manual"
