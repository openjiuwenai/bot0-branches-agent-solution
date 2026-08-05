"""DictArtifactExporter — 支持 dict trajectory 格式的 artifact 导出器。

上游 ArtifactExporter 仅支持 Trajectory 对象导出，
EvoAgent 的 trajectory 为 dict 格式（``{"case_id", "messages": [...]}``），
本类覆写 ``export_trajectories()`` 同时支持两种格式。
"""

from __future__ import annotations

import json
from typing import Any

from evo_agent.optimizer.skill_document.artifact_exporter import ArtifactExporter


class DictArtifactExporter(ArtifactExporter):
    """支持 dict + Trajectory 双格式导出的 artifact exporter。

    仅覆写 ``export_trajectories()``，其余方法继承上游。
    """

    def export_trajectories(
        self,
        epoch: int,
        step: int,
        trajectories: list[Any],
        eval_results: list[Any],
    ) -> None:
        """写入 trajectories JSONL（每行一个 case）。

        同时支持:
        - dict 格式: ``{"case_id": str, "messages": [...]}``
        - Trajectory 对象: 具有 ``.steps`` 属性
        """
        if not self._export_trajectories:
            return
        step_dir = self._step_dir(epoch, step)
        if not step_dir:
            return

        with open(step_dir / "trajectories.jsonl", "w", encoding="utf-8") as f:
            for traj, eval_result in zip(trajectories, eval_results):
                if isinstance(traj, dict):
                    entry = {
                        "case_id": traj.get("case_id", getattr(eval_result, "case_id", "")),
                        "score": getattr(eval_result, "score", 0.0),
                        "messages": traj.get("messages", []),
                    }
                else:
                    entry = {
                        "case_id": getattr(eval_result, "case_id", ""),
                        "score": getattr(eval_result, "score", 0.0),
                        "steps": [
                            {
                                "kind": getattr(s, "kind", ""),
                                "detail": str(getattr(s, "detail", ""))[:500],
                            }
                            for s in getattr(traj, "steps", [])
                        ],
                    }
                f.write(json.dumps(entry, ensure_ascii=False, default=str) + "\n")
