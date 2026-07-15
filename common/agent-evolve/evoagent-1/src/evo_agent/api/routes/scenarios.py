"""场景列表路由。"""

from __future__ import annotations

from typing import Any

import yaml
from fastapi import APIRouter

from evo_agent.paths import SCENARIOS_DIR

router = APIRouter(prefix="/scenarios", tags=["scenarios"])


@router.get("")
async def list_scenarios() -> list[dict[str, Any]]:
    """列出所有可用场景。

    扫描 ``scenarios/*/scenario.yaml``，返回场景名、optimizer 类路径和超参数。
    """
    scenarios_dir = SCENARIOS_DIR
    result: list[dict[str, Any]] = []

    if not scenarios_dir.exists():
        return result

    for entry in sorted(scenarios_dir.iterdir()):
        yaml_path = entry / "scenario.yaml"
        if not entry.is_dir() or not yaml_path.exists():
            continue

        config = _read_yaml(yaml_path)
        result.append(
            {
                "name": entry.name,
                "optimizer_class": config.get("optimizer_class", ""),
                "hyperparams": config.get("hyperparams", {}),
            }
        )

    return result


def _read_yaml(path: Any) -> dict[str, Any]:
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f)  # type: ignore[no-any-return]
