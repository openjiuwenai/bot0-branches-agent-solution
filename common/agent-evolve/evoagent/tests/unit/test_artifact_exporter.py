"""ArtifactExporter ensure_ascii=False 单元测试（A3, #12）。"""

from __future__ import annotations

import json
from pathlib import Path

from evo_agent.optimizer.artifact_exporter import DictArtifactExporter
from evo_agent.optimizer.skill_document.artifact_exporter import ArtifactExporter


def test_write_json_preserves_chinese(tmp_path: Path) -> None:
    """_write_json 用 ensure_ascii=False，中文不被转义为 \\uXXXX。"""
    out = tmp_path / "out.json"
    ArtifactExporter._write_json(out, {"reason": "失败原因：产品推荐错误"})

    raw = out.read_text(encoding="utf-8")
    assert "失败原因" in raw  # 直接可读，非 失败
    assert "\\u" not in raw
    assert json.loads(raw)["reason"] == "失败原因：产品推荐错误"


def test_export_eval_results_preserves_chinese(tmp_path: Path) -> None:
    """eval_results.json 中文可读。"""
    exporter = ArtifactExporter(str(tmp_path))
    eval_results = [type("ER", (), {"case_id": "c1", "score": 0.2, "reason": "未命中期望"})()]
    cases = [type("Case", (), {"case_id": "c1", "inputs": {"question": "推荐基金"}})()]

    exporter.export_eval_results(epoch=0, step=0, eval_results=eval_results, cases=cases)

    raw = (tmp_path / "epoch_0" / "step_0" / "eval_results.json").read_text(encoding="utf-8")
    assert "未命中期望" in raw
    assert "\\u" not in raw


def test_export_trajectories_dict_preserves_chinese(tmp_path: Path) -> None:
    """DictArtifactExporter trajectories.jsonl 中文可读。"""
    exporter = DictArtifactExporter(str(tmp_path))
    trajectories = [
        {
            "case_id": "c1",
            "messages": [{"role": "assistant", "content": "为您推荐稳健型基金"}],
        }
    ]
    eval_results = [type("ER", (), {"case_id": "c1", "score": 0.9})()]

    exporter.export_trajectories(
        epoch=0, step=0, trajectories=trajectories, eval_results=eval_results
    )

    raw = (tmp_path / "epoch_0" / "step_0" / "trajectories.jsonl").read_text(encoding="utf-8")
    assert "稳健型基金" in raw
    assert "\\u" not in raw


def test_export_trajectories_object_preserves_chinese(tmp_path: Path) -> None:
    """ArtifactExporter (对象 trajectory) trajectories.jsonl 中文可读。"""
    exporter = ArtifactExporter(str(tmp_path))

    class _Step:
        kind = "tool"
        detail = "调用 read_file 读取 SKILL.md"

    class _Traj:
        steps = [_Step()]

    eval_results = [type("ER", (), {"case_id": "c1", "score": 0.5})()]
    exporter.export_trajectories(epoch=0, step=0, trajectories=[_Traj()], eval_results=eval_results)

    raw = (tmp_path / "epoch_0" / "step_0" / "trajectories.jsonl").read_text(encoding="utf-8")
    assert "读取 SKILL.md" in raw
    assert "\\u" not in raw
