"""Example: optimize a skill with the example scenario.

Usage:
  python examples/optimize_example.py
"""

from __future__ import annotations

import asyncio
from pathlib import Path

from evo_agent.config import EvolveConfig
from evo_agent.optimizer_runner import run_optimization
from evo_agent.types import OptimizeRequest


async def main() -> None:
    config = EvolveConfig.get()

    request = OptimizeRequest(
        dataset_manifest_path=Path("./datasets/example/dataset.yaml"),
        adapter_url="http://localhost:9090",
        skills=["example_skill"],
        scenario="example",
        num_epochs=3,
        batch_size=4,
    )

    report = await run_optimization(request, config)
    print(f"Optimization completed: {report.skills}")
    print(f"  Score: {report.score_before} -> {report.score_after} ({report.improvement})")
    print(f"  Edits: {report.edits_applied}")
    print(f"  Artifacts: {report.artifact_dir}")


if __name__ == "__main__":
    asyncio.run(main())
