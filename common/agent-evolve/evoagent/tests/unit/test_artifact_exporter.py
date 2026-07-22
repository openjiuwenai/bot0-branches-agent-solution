"""ArtifactExporter ensure_ascii=False 单元测试（A3, #12）。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.errors import ArtifactPersistenceError, ValidationCoverageError
from evo_agent.evaluator.batch_result import (
    EvaluationBatchResult,
    EvaluationFailure,
    EvaluationOutcome,
)
from evo_agent.optimizer.artifact_exporter import DictArtifactExporter
from evo_agent.optimizer.skill_document.artifact_exporter import ArtifactExporter
from evo_agent.optimizer.skill_document.types import (
    GateEvaluationRecord,
    ValidationCoverageFailureInput,
)


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
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.5)
    eval_results = [type("ER", (), {"case_id": "c1", "score": 0.2, "reason": "未命中期望"})()]
    cases = [type("Case", (), {"case_id": "c1", "inputs": {"question": "推荐基金"}})()]

    exporter.export_eval_results(epoch=0, step=0, eval_results=eval_results, cases=cases)

    raw = (tmp_path / "epoch_0" / "step_0" / "eval_results.json").read_text(encoding="utf-8")
    assert "未命中期望" in raw
    assert "\\u" not in raw


def test_epoch_directory_creation_failure_is_fatal(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Exporter 自己建立 epoch 目录失败时也必须升级为 fatal persistence error。"""
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.5)
    epoch_dir = tmp_path / "epoch_0"
    original_mkdir = Path.mkdir

    def fail_epoch(path: Path, *args: object, **kwargs: object) -> None:
        if path == epoch_dir:
            raise OSError("read-only filesystem")
        original_mkdir(path, *args, **kwargs)

    monkeypatch.setattr(Path, "mkdir", fail_epoch)

    with pytest.raises(ArtifactPersistenceError, match="epoch_0"):
        exporter.export_gate_result(0, base_score=0.5, candidate_score=0.6)


def test_export_eval_results_uses_configured_score_threshold(tmp_path: Path) -> None:
    """failure rate 口径是 score < 注入阈值，不得硬编码 0.5。"""
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.6)
    eval_results = [
        type("ER", (), {"case_id": "c1", "score": 0.55, "reason": "near"})(),
        type("ER", (), {"case_id": "c2", "score": 0.75, "reason": "ok"})(),
    ]
    cases = [type("Case", (), {"case_id": case_id})() for case_id in ("c1", "c2")]

    exporter.export_eval_results(0, 0, eval_results, cases)

    data = json.loads(
        (tmp_path / "epoch_0" / "step_0" / "eval_results.json").read_text(encoding="utf-8")
    )
    assert data["failure_rate"] == 0.5


def test_export_eval_results_records_training_failure_outcomes(tmp_path: Path) -> None:
    """训练 artifact 保留失败分类与诊断 identity，但不写 raw response。"""
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.5)
    success_case = Case(inputs={"question": "ok"}, label={"answer": "ok"}, case_id="c1")
    failed_case = Case(inputs={"question": "bad"}, label={"answer": "bad"}, case_id="c2")
    evaluated = EvaluatedCase(case=success_case, answer={}, score=0.8, reason="ok")
    batch = EvaluationBatchResult(
        (
            EvaluationOutcome(0, "c1", success_case, None, evaluated, None),
            EvaluationOutcome(
                1,
                "c2",
                failed_case,
                None,
                None,
                EvaluationFailure(
                    category="json_parse_error",
                    safe_message="invalid evaluator JSON",
                    invocation_id="inv-c2",
                    response_sha256="sha-c2",
                    response_chars=27,
                ),
            ),
        )
    )

    exporter.export_eval_results(
        0,
        0,
        [evaluated],
        [success_case],
        outcomes=batch.outcomes,
    )

    artifact = tmp_path / "epoch_0" / "step_0" / "eval_results.json"
    data = json.loads(artifact.read_text(encoding="utf-8"))
    assert data["attempted_count"] == 2
    assert data["evaluated_count"] == 1
    assert data["skipped_count"] == 1
    assert data["failures"] == [
        {
            "case_id": "c2",
            "category": "json_parse_error",
            "safe_message": "invalid evaluator JSON",
            "invocation_id": "inv-c2",
            "response_sha256": "sha-c2",
            "response_chars": 27,
        }
    ]


def test_export_trajectories_dict_preserves_chinese(tmp_path: Path) -> None:
    """DictArtifactExporter trajectories.jsonl 中文可读。"""
    exporter = DictArtifactExporter(str(tmp_path), score_threshold=0.5)
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
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.5)

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


def test_export_validation_publishes_manifest_then_success_marker(tmp_path: Path) -> None:
    """胜出 batch 的结果、可读轨迹和 checksum 形成一个完整提交。"""
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.6)
    outcomes = []
    for index, score in enumerate((0.55, 0.8)):
        case = Case(
            inputs={
                "trajectory": {
                    "messages": [
                        {"role": "user", "content": f"CUSTOMER-CANARY-{index}"},
                        {"role": "assistant", "content": "answer"},
                    ]
                }
            },
            label={"expected_result": None},
        )
        evaluated = EvaluatedCase(case=case, answer={"answer": "ok"})
        evaluated.score = score
        evaluated.reason = json.dumps(
            {"reason": "ok", "repaired": False, "parse_mode": "exact", "repair_operations": []}
        )
        outcomes.append(
            EvaluationOutcome(
                index=index,
                case_id=case.case_id,
                case=case,
                trajectory=case.inputs["trajectory"],
                evaluated=evaluated,
                failure=None,
            )
        )
    batch = EvaluationBatchResult(tuple(outcomes))
    gate = GateEvaluationRecord(0.5, 0.675, "candidate")
    exporter.export_gate_result(0, gate=gate, selected_batch=batch)

    exporter.export_validation(0, batch, gate)

    validation = tmp_path / "epoch_0" / "validation"
    assert (validation / "_SUCCESS").read_bytes() == b""
    manifest = json.loads((validation / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["complete"] is True
    assert set(manifest["files"]) == {
        "results.json",
        "failures.jsonl",
        "trajectories.jsonl",
        "../gate_result.json",
    }
    trajectories_text = (validation / "trajectories.jsonl").read_text(encoding="utf-8")
    assert "CUSTOMER-CANARY" in trajectories_text
    assert "ARTIFACT_REDACTED" not in trajectories_text
    results = json.loads((validation / "results.json").read_text(encoding="utf-8"))
    assert results["selected_failure_rate"] == 0.5
    assert {row["case_id"] for row in results["results"]} == {
        outcome.case_id for outcome in batch.outcomes
    }


def test_export_noop_validation_has_explicit_unchanged_semantics(tmp_path: Path) -> None:
    """no-op epoch 不能伪造 candidate 分数，但仍发布完整 validation artifact。"""
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.6)
    batch = EvaluationBatchResult(())
    gate = GateEvaluationRecord(
        base_score=0.7,
        candidate_score=None,
        decision="unchanged",
        kind="no_op",
        reason="no_selected_edits",
    )

    exporter.export_gate_result(2, gate=gate, selected_batch=batch)
    exporter.export_validation(2, batch, gate)

    epoch = tmp_path / "epoch_2"
    gate_data = json.loads((epoch / "gate_result.json").read_text(encoding="utf-8"))
    assert gate_data["kind"] == "no_op"
    assert gate_data["decision"] == "unchanged"
    assert gate_data["reason"] == "no_selected_edits"
    assert gate_data["base_score"] == 0.7
    assert gate_data["candidate_score"] is None
    assert gate_data["improvement"] is None

    validation = epoch / "validation"
    assert (validation / "_SUCCESS").exists()
    manifest = json.loads((validation / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["kind"] == "no_op"
    assert manifest["decision"] == "unchanged"


def test_export_validation_failure_is_invalid_and_never_publishes_success(
    tmp_path: Path,
) -> None:
    """coverage 失败只发布安全诊断，不能生成幸存者均分或完成标记。"""
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.6)
    case = Case(
        inputs={"trajectory": {"messages": [{"role": "user", "content": "RAW-CANARY"}]}},
        label={"expected_result": None},
    )
    batch = EvaluationBatchResult(
        (
            EvaluationOutcome(
                index=0,
                case_id=case.case_id,
                case=case,
                trajectory=case.inputs["trajectory"],
                evaluated=None,
                failure=EvaluationFailure(
                    "json_parse_error",
                    "invalid evaluator JSON",
                    response_sha256="abc",
                    response_chars=10,
                ),
            ),
        )
    )
    failure = ValidationCoverageFailureInput(
        base_batch=batch,
        candidate_batches=(batch,),
        error=ValidationCoverageError(
            attempted_count=1,
            evaluated_count=0,
            min_success_ratio=1.0,
            reason="candidate_coverage_below_minimum",
        ),
    )

    exporter.export_validation_failure(0, failure)

    epoch = tmp_path / "epoch_0"
    gate = json.loads((epoch / "gate_result.json").read_text(encoding="utf-8"))
    assert gate["status"] == "invalid"
    assert gate["decision"] == "invalid"
    assert gate["retained_state"] == "base"
    assert gate["selected_failure_rate"] is None
    validation = epoch / "validation"
    assert not (validation / "_SUCCESS").exists()
    manifest = json.loads((validation / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["complete"] is False


def test_success_marker_cleanup_failure_is_fatal(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """旧 _SUCCESS 无法删除时必须中止发布，不能由 callback 吞掉。"""
    exporter = ArtifactExporter(str(tmp_path), score_threshold=0.5)
    original_unlink = Path.unlink

    def fail_success_unlink(path: Path, *args: object, **kwargs: object) -> None:
        if path.name == "_SUCCESS":
            raise OSError("permission denied")
        original_unlink(path, *args, **kwargs)

    monkeypatch.setattr(Path, "unlink", fail_success_unlink)

    with pytest.raises(ArtifactPersistenceError, match="_SUCCESS"):
        exporter.export_validation(
            0,
            EvaluationBatchResult(()),
            GateEvaluationRecord(0.5, 0.6, "candidate"),
        )
