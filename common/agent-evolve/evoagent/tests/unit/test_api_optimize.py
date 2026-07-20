"""API 请求体校验单元测试 — Wave 8 嵌套模板结构。"""

from __future__ import annotations

from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from evo_agent.api.app import app
from evo_agent.api.jobs import JobManager, JobStatus, job_manager
from evo_agent.api.routes import optimize as optimize_routes
from evo_agent.config import EvolveConfig
from evo_agent.types import ManagedDocEpochContent, OptimizeReport, TrainResult, ValResult


@pytest.fixture
async def client() -> AsyncClient:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


def _make_api_request(
    *,
    tmp_path: Path,
    dataset_filename: str = "items.json",
    agent_name: str = "test_agent",
    scenario_name: str = "edp_agent",
    train_split: float = 0.8,
    val_split: float = 0.2,
    managed_doc_kind: str | None = None,
    skills: list[str] | None = None,
) -> dict[str, Any]:
    """构造合法的嵌套请求 dict。"""
    data_file = tmp_path / dataset_filename
    data_file.write_text("[]", encoding="utf-8")
    body: dict[str, Any] = {
        "task_name": "test-task",
        "agent_name": agent_name,
        "optimizer_type": "skill",
        "dataset_path": str(data_file),
        "skills": ["skill_a"] if skills is None else skills,
        "optimizer_template": {
            "name": scenario_name,
            "scenario": scenario_name,
            "hyperparams": {},
            "train_split": train_split,
            "val_split": val_split,
        },
        "evaluator_template": {
            "name": "default_eval",
            "scenario": "金融客服",
            "prompt": "评估回答质量",
        },
    }
    if managed_doc_kind is not None:
        body["managed_doc_kind"] = managed_doc_kind
    return body


@pytest.fixture
def config_with_adapter(tmp_path: Path) -> EvolveConfig:
    """配置包含 adapter_url 和 allowed_data_roots 的 EvolveConfig。"""
    return EvolveConfig(
        adapter_url="http://localhost:9090",
        allowed_data_roots=[tmp_path],
    )


# ── Valid request ──


@pytest.mark.asyncio
async def test_start_optimize_valid_request(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """合法嵌套请求返回 200 + job_id。"""
    body = _make_api_request(tmp_path=tmp_path)
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 200
    data = resp.json()
    assert "job_id" in data
    assert data["status"] == "queued"


# ── Required fields ──


@pytest.mark.asyncio
async def test_start_optimize_missing_agent_name(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """缺少 agent_name → 422。"""
    body = _make_api_request(tmp_path=tmp_path)
    del body["agent_name"]
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_start_optimize_missing_dataset_path(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """缺少 dataset_path → 422。"""
    body = _make_api_request(tmp_path=tmp_path)
    del body["dataset_path"]
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_start_optimize_pure_whitespace_managed_doc_kind_is_both_absent(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """纯空白 managed_doc_kind + 空 skills → 422 both-absent（P2#5）。

    (x or "").strip() or None 对纯空白 "   " → None，路由 XOR 校验视为未提供，
    与空 skills 一同触发 both-absent 422，避免穿透触发无目标 eval-only 路径。
    """
    body = _make_api_request(tmp_path=tmp_path, managed_doc_kind="   ", skills=[])
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 422


# ── Scenario validation ──


@pytest.mark.asyncio
async def test_start_optimize_invalid_scenario(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """scenario 不存在 → 200（非阻塞，optimizer 执行时 fallback 到默认场景）。"""
    body = _make_api_request(tmp_path=tmp_path, scenario_name="nonexistent_scenario")
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 200
    assert resp.json()["status"] in ("pending", "queued")


# ── Split validation ──


@pytest.mark.asyncio
async def test_start_optimize_invalid_splits(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """train_split + val_split != 1.0 → 422。"""
    body = _make_api_request(tmp_path=tmp_path, train_split=0.7, val_split=0.2)
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 422


# ── Config adapter_url not set ──


@pytest.mark.asyncio
async def test_start_optimize_no_adapter_url_config(client: AsyncClient, tmp_path: Path) -> None:
    """EVO_ADAPTER_URL 未配置 → 500。"""
    body = _make_api_request(tmp_path=tmp_path)
    config_without_adapter = EvolveConfig(adapter_url="")
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_without_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 500


# ── Dataset path validation ──


@pytest.mark.asyncio
async def test_start_optimize_dataset_not_found(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """dataset_path 不存在 → 422。"""
    body = _make_api_request(tmp_path=tmp_path)
    body["dataset_path"] = str(tmp_path / "nonexistent.json")
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_start_optimize_dataset_outside_allowed_roots(
    client: AsyncClient, tmp_path: Path
) -> None:
    """dataset_path 不在 allowed_data_roots 下 → 422（handler 层校验）。"""
    # 创建文件在一个不被允许的目录
    outside_dir = tmp_path / "outside"
    outside_dir.mkdir()
    data_file = outside_dir / "items.json"
    data_file.write_text("[]", encoding="utf-8")

    # config 只允许 /completely/different
    config = EvolveConfig(
        adapter_url="http://localhost:9090",
        allowed_data_roots=[Path("/completely/different")],
    )

    body = _make_api_request(tmp_path=outside_dir)
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 422


# ── edits_applied 来源：gate-aware 计数器，非 report ──


@pytest.mark.asyncio
async def test_result_edits_applied_uses_counter_not_report(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """完成卡片的 edits_applied 取 gate-aware 的 live 计数器，而非 report.edits_applied。

    report.edits_applied 由 formatter rglob 扫 selected_edits.json 得来，含被 gate
    拒绝（operator 已回滚到 base）的编辑，会过计；job.progress.edits_applied 由
    _phase_callback 累计 + on_train_epoch_end 在拒绝轮回滚，只计 gate 接受的编辑。
    job.result["edits_applied"] 须取后者，否则完成卡片"编辑次数"偏多。
    """
    over_counted_report = OptimizeReport(
        skills=("skill_a",),
        dataset="test",
        epochs_completed=1,
        edits_applied=999,  # formatter 的过计值
        train=TrainResult(
            score_before=0.5,
            score_after=0.6,
            improvement="+20%",
            pass_rate_before=0.5,
            pass_rate_after=0.6,
            num_cases=1,
        ),
        val=ValResult(
            score_before=0.5,
            final_score=0.6,
            best_score=0.6,
            per_epoch_scores=(0.6,),
            num_cases=1,
        ),
        gate_results=(),
        artifact_dir=tmp_path,
    )

    body = _make_api_request(tmp_path=tmp_path)
    with (
        patch(
            "evo_agent.api.routes.optimize.EvolveConfig.get",
            return_value=config_with_adapter,
        ),
        patch(
            "evo_agent.api.routes.optimize.run_optimization_with_cancellation_recovery",
            new=AsyncMock(return_value=over_counted_report),
        ),
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 200
    job_id = resp.json()["job_id"]

    job = job_manager.get(job_id)
    assert job is not None
    assert job.background_task is not None
    await job.background_task  # 等后台任务跑完

    assert job.status == JobStatus.COMPLETED
    assert job.result is not None
    # mock 的 run_optimization 未驱动 _phase_callback → 计数器为 0；report.edits_applied=999。
    # result 取计数器 → 0，证明完成卡片不用 report 的过计值。
    assert job.progress.edits_applied == 0
    assert job.result["edits_applied"] == 0
    assert over_counted_report.edits_applied == 999  # report 仍是过计值，但未被采用


# ── A6: result["val"] 含 improvement / pass_rate_before / pass_rate_after ──


@pytest.mark.asyncio
async def test_result_val_includes_card_fields(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """完成卡片 result["val"] 显式包含 improvement/pass_rate_before/pass_rate_after。

    该 dict 逐字段构造（非 asdict），须显式加键，否则 studio 验证集卡片拿不到
    三字段。旧报告（字段 None）序列化不报错。
    """
    report = OptimizeReport(
        skills=("skill_a",),
        dataset="test",
        epochs_completed=1,
        edits_applied=0,
        train=TrainResult(
            score_before=0.5,
            score_after=0.6,
            improvement="+20%",
            pass_rate_before=0.5,
            pass_rate_after=0.6,
            num_cases=1,
        ),
        val=ValResult(
            score_before=0.5,
            final_score=0.7,
            best_score=0.7,
            per_epoch_scores=(0.7,),
            num_cases=2,
            improvement="+40%",
            pass_rate_before=0.4,
            pass_rate_after=0.8,
        ),
        gate_results=(),
        artifact_dir=tmp_path,
    )

    body = _make_api_request(tmp_path=tmp_path)
    with (
        patch(
            "evo_agent.api.routes.optimize.EvolveConfig.get",
            return_value=config_with_adapter,
        ),
        patch(
            "evo_agent.api.routes.optimize.run_optimization_with_cancellation_recovery",
            new=AsyncMock(return_value=report),
        ),
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 200
    job_id = resp.json()["job_id"]

    job = job_manager.get(job_id)
    assert job is not None
    assert job.background_task is not None
    await job.background_task

    assert job.status == JobStatus.COMPLETED
    assert job.result is not None
    val = job.result["val"]
    assert val["improvement"] == "+40%"
    assert val["pass_rate_before"] == 0.4
    assert val["pass_rate_after"] == 0.8


@pytest.mark.asyncio
async def test_result_val_card_fields_none_for_old_report(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """旧报告（val 三字段 None）序列化不报错，键存在且值为 None。"""
    report = OptimizeReport(
        skills=("skill_a",),
        dataset="test",
        epochs_completed=1,
        edits_applied=0,
        train=TrainResult(
            score_before=0.5,
            score_after=0.6,
            improvement="+20%",
            pass_rate_before=0.5,
            pass_rate_after=0.6,
            num_cases=1,
        ),
        val=ValResult(
            score_before=0.5,
            final_score=0.6,
            best_score=0.6,
            per_epoch_scores=(0.6,),
            num_cases=1,
            # improvement/pass_rate_before/pass_rate_after 缺省 None
        ),
        gate_results=(),
        artifact_dir=tmp_path,
    )

    body = _make_api_request(tmp_path=tmp_path)
    with (
        patch(
            "evo_agent.api.routes.optimize.EvolveConfig.get",
            return_value=config_with_adapter,
        ),
        patch(
            "evo_agent.api.routes.optimize.run_optimization_with_cancellation_recovery",
            new=AsyncMock(return_value=report),
        ),
    ):
        resp = await client.post("/optimize", json=body)
    job_id = resp.json()["job_id"]
    job = job_manager.get(job_id)
    assert job is not None and job.background_task is not None
    await job.background_task

    assert job.result is not None
    val = job.result["val"]
    assert val["improvement"] is None
    assert val["pass_rate_before"] is None
    assert val["pass_rate_after"] is None


# ── ResourceResolver 废弃 ──


def test_local_resolver_deprecation_warning() -> None:
    """实例化 LocalResolver 触发 DeprecationWarning。"""
    import warnings

    from evo_agent.api.resources import LocalResolver

    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        LocalResolver()
        assert len(w) == 1
        assert issubclass(w[0].category, DeprecationWarning)
        assert "deprecated" in str(w[0].message).lower()
        assert "ADR-0005" in str(w[0].message)


def test_resource_resolver_is_protocol() -> None:
    """ResourceResolver 仍为 runtime_checkable Protocol。"""
    from typing import Protocol

    from evo_agent.api.resources import ResourceResolver

    # runtime_checkable 的 Protocol 可以用 isinstance 检查
    assert hasattr(ResourceResolver, "__protocol_attrs__") or issubclass(
        type(ResourceResolver), type(Protocol)
    )


# ── managed-doc API（spec F3）──


def _make_managed_doc_api_request(*, tmp_path: Path, scenario_name: str = "edp_agent") -> dict:
    """构造 managed-doc 请求：managed_doc_kind + skills=[]（走 F7 builder 分支）。"""
    data_file = tmp_path / "items.json"
    data_file.write_text("[]", encoding="utf-8")
    return {
        "task_name": "test-task",
        "agent_name": "test_agent",
        "optimizer_type": "prompt",
        "dataset_path": str(data_file),
        "skills": [],
        "managed_doc_kind": "agent_rule",
        "client_task_id": f"studio-{tmp_path.parent.name}-{tmp_path.name}",
        "managed_doc_expected_revision": "rev-1",
        "optimizer_template": {
            "name": scenario_name,
            "scenario": scenario_name,
            "hyperparams": {},
            "train_split": 0.8,
            "val_split": 0.2,
        },
        "evaluator_template": {
            "name": "default_eval",
            "scenario": "金融客服",
            "prompt": "评估回答质量",
        },
    }


@pytest.mark.asyncio
async def test_start_optimize_rejects_skills_and_managed_doc_kind_both_present(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """路由层 XOR 双保险：skills + managed_doc_kind 同时存在 → 422。"""
    body = _make_api_request(tmp_path=tmp_path)
    body["managed_doc_kind"] = "agent_rule"  # 同时给 skills=["skill_a"]
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 422
    assert resp.json()["detail"]["code"] == "OPTIMIZATION_TARGET_INVALID"


@pytest.mark.asyncio
async def test_start_optimize_rejects_both_skills_and_managed_doc_kind_absent(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """入口层收口无目标：skills + managed_doc_kind 同时缺失 → 422。"""
    body = _make_api_request(tmp_path=tmp_path)
    body["skills"] = []  # 无 managed_doc_kind + 空 skills
    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 422
    assert resp.json()["detail"]["code"] == "OPTIMIZATION_TARGET_INVALID"


@pytest.mark.asyncio
async def test_prompt_submission_requires_client_task_id(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    body = _make_managed_doc_api_request(tmp_path=tmp_path)
    body.pop("client_task_id")
    jobs_before = len(job_manager.list_jobs())

    with (
        patch(
            "evo_agent.api.routes.optimize.EvolveConfig.get",
            return_value=config_with_adapter,
        ),
        patch(
            "evo_agent.api.routes.optimize.run_optimization_with_cancellation_recovery",
            new=AsyncMock(side_effect=AssertionError("invalid request started a job")),
        ),
    ):
        response = await client.post("/optimize", json=body)

    assert response.status_code == 422
    assert len(job_manager.list_jobs()) == jobs_before


@pytest.mark.asyncio
async def test_prompt_submission_requires_expected_revision(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    body = _make_managed_doc_api_request(tmp_path=tmp_path)
    body.pop("managed_doc_expected_revision")
    jobs_before = len(job_manager.list_jobs())

    with patch(
        "evo_agent.api.routes.optimize.EvolveConfig.get",
        return_value=config_with_adapter,
    ):
        response = await client.post("/optimize", json=body)

    assert response.status_code == 422
    assert len(job_manager.list_jobs()) == jobs_before


@pytest.mark.asyncio
async def test_prompt_submission_replay_returns_same_job_and_starts_once(
    client: AsyncClient,
    tmp_path: Path,
    config_with_adapter: EvolveConfig,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manager = JobManager(control_db_path=tmp_path / "control.db")
    monkeypatch.setattr(optimize_routes, "job_manager", manager)
    body = _make_managed_doc_api_request(tmp_path=tmp_path)
    body.update(
        optimizer_type="prompt",
        client_task_id="studio-task-replay",
        managed_doc_expected_revision="rev-1",
    )
    report = OptimizeReport(
        skills=("managed_doc:agent_rule",),
        dataset="dataset",
        epochs_completed=0,
        edits_applied=0,
        train=TrainResult(0.0, 0.0, "+0%", 0.0, 0.0, 0),
        val=ValResult(0.0, 0.0, (), 0),
        gate_results=(),
        artifact_dir=tmp_path,
        managed_doc_kind="agent_rule",
        managed_doc_content_before="# baseline",
        managed_doc_content_after="# baseline",
    )
    run_mock = AsyncMock(return_value=report)

    with (
        patch(
            "evo_agent.api.routes.optimize.EvolveConfig.get",
            return_value=config_with_adapter,
        ),
        patch(
            "evo_agent.api.routes.optimize.run_optimization_with_cancellation_recovery",
            new=run_mock,
        ),
    ):
        first = await client.post("/optimize", json=body)
        replay = await client.post("/optimize", json=body)
        for job in manager.list_jobs():
            if job.background_task is not None:
                await job.background_task

    assert first.status_code == 200
    assert replay.status_code == 200
    assert replay.json()["job_id"] == first.json()["job_id"]
    assert run_mock.await_count == 1


@pytest.mark.asyncio
async def test_start_optimize_managed_doc_response_includes_before_after_task_ids(
    client: AsyncClient, tmp_path: Path, config_with_adapter: EvolveConfig
) -> None:
    """managed-doc job 完成后 response 含 before/after/task_ids 四字段。"""
    md_report = OptimizeReport(
        skills=("managed_doc:agent_rule",),
        dataset="agent_rule_dataset",
        epochs_completed=1,
        edits_applied=0,
        train=TrainResult(
            score_before=0.4,
            score_after=0.5,
            improvement="+25%",
            pass_rate_before=0.4,
            pass_rate_after=0.5,
            num_cases=1,
        ),
        val=ValResult(
            score_before=0.4,
            final_score=0.5,
            best_score=0.5,
            per_epoch_scores=(0.5,),
            num_cases=1,
        ),
        gate_results=(),
        artifact_dir=tmp_path,
        managed_doc_kind="agent_rule",
        managed_doc_content_before="# rule v1",
        managed_doc_content_after="# rule v2",
        managed_doc_epoch_contents=(
            ManagedDocEpochContent(round=1, content="# rejected candidate"),
        ),
        managed_doc_task_ids=("task-1", "task-2"),
    )
    body = _make_managed_doc_api_request(tmp_path=tmp_path)
    with (
        patch(
            "evo_agent.api.routes.optimize.EvolveConfig.get",
            return_value=config_with_adapter,
        ),
        patch(
            "evo_agent.api.routes.optimize.run_optimization_with_cancellation_recovery",
            new=AsyncMock(return_value=md_report),
        ),
    ):
        resp = await client.post("/optimize", json=body)
    assert resp.status_code == 200
    job_id = resp.json()["job_id"]
    job = job_manager.get(job_id)
    assert job is not None
    assert job.managed_doc_kind == "agent_rule"
    assert job.background_task is not None
    await job.background_task
    assert job.status == JobStatus.COMPLETED
    assert job.result is not None
    assert job.result["managed_doc_kind"] == "agent_rule"
    assert job.result["managed_doc_content_before"] == "# rule v1"
    assert job.result["managed_doc_content_after"] == "# rule v2"
    assert job.result["managed_doc_epoch_contents"] == [
        {"round": 1, "content": "# rejected candidate"}
    ]
    assert job.result["managed_doc_task_ids"] == ["task-1", "task-2"]


@pytest.mark.asyncio
async def test_running_managed_doc_cancel_returns_202_and_keeps_running(
    client: AsyncClient,
) -> None:
    """RUNNING Prompt latches cancellation but remains active until rollback."""

    job = job_manager.submit({"task_name": "md-cancel-test"})
    job.managed_doc_kind = "agent_rule"
    job.status = JobStatus.RUNNING
    resp = await client.post(f"/optimize/{job.job_id}/cancel")
    assert resp.status_code == 202
    assert resp.json()["status"] == "running"
    assert job_manager.get(job.job_id).status == JobStatus.RUNNING
    assert job.cancellation_token.is_requested


@pytest.mark.asyncio
async def test_queued_managed_doc_job_can_be_cancelled(client: AsyncClient) -> None:
    """QUEUED 阶段（训练未开始）managed-doc job 可接受取消并立即终态。"""

    job = job_manager.submit({"task_name": "md-queued-cancel-test"})
    job.managed_doc_kind = "agent_rule"
    # 保持 QUEUED（submit 默认状态）
    assert job.status == JobStatus.QUEUED
    resp = await client.post(f"/optimize/{job.job_id}/cancel")
    assert resp.status_code == 202
    assert job_manager.get(job.job_id).status == JobStatus.CANCELLED
