"""E2E 全量优化测试 — 通过 HTTP API 驱动完整 Pipeline。

运行方式：
    cd /path/to/EvoAgent
    uv run python tests/e2e/test_full_optimize.py

前置条件：
    1. Adapter sidecar 运行中（通过 smoke test 验证）
    2. tests/e2e/.env 配置正确（LLM credentials, adapter_url）
    3. 测试数据集已就位（/tmp/evo_agent/e2e_test/golden.jsonl）

验证内容：
    - restore_skill 恢复 Skill 到初始状态
    - POST /optimize 提交优化任务
    - GET /optimize/{job_id} 轮询任务状态
    - Response 结构完整性（无 null 值）
    - SSE 事件序列正确性
    - 文件产物正确性
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path
from typing import Any

# ── 环境设置（必须在 import evo_agent 之前） ──

ENV_PATH = Path(__file__).parent / ".env"
if ENV_PATH.exists():
    for line in ENV_PATH.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            os.environ[key.strip()] = value.strip()

# 将 adapter 地址加入 no_proxy（绕过本地代理，避免 502）
_adapter_url = os.environ.get("EVO_ADAPTER_URL", "")
if _adapter_url:
    from urllib.parse import urlparse  # noqa: E402

    _host = urlparse(_adapter_url).hostname or ""
    if _host:
        for var in ("no_proxy", "NO_PROXY"):
            existing = os.environ.get(var, "")
            if _host not in existing:
                os.environ[var] = f"{existing},{_host}" if existing else _host

# 清除 EvolveConfig 的 lru_cache（确保读取新的 env）
from evo_agent.config import EvolveConfig  # noqa: E402

EvolveConfig.get.cache_clear()

import httpx  # noqa: E402
from starlette.testclient import TestClient  # noqa: E402

from evo_agent.api.app import app  # noqa: E402
from evo_agent.api.jobs import job_manager  # noqa: E402

# ── 测试常量 ──

ADAPTER_URL = os.environ.get("EVO_ADAPTER_URL", "http://124.71.234.237:8900")
AGENT_NAME = "edp_agent"
DATASET_PATH = "/tmp/evo_agent/e2e_test/golden.jsonl"
SKILLS = ["product_recommend_skill", "interact_finance_rec_skill"]
NUM_EPOCHS = 2
BATCH_SIZE = 2
POLL_INTERVAL = 5  # seconds
MAX_WAIT = 1800  # 30 minutes

EVALUATOR_PROMPT = (
    "你是一个专业的金融领域任务评估专家。请根据对话过程与预期行为，"
    "评估 Agent 的任务完成质量，返回 0.0 到 1.0 之间的分数。"
)


# ── 请求构建 ──


def build_request_body() -> dict[str, Any]:
    """构建 POST /optimize 请求体。"""
    return {
        "task_name": "e2e_api_test",
        "agent_name": AGENT_NAME,
        "optimizer_type": "skill",
        "optimizer_template": {
            "name": "edp_agent",
            "scenario": "edp_agent",
            "hyperparams": {
                "num_epochs": NUM_EPOCHS,
                "batch_size": BATCH_SIZE,
            },
            "train_split": 0.8,
            "val_split": 0.2,
        },
        "evaluator_template": {
            "name": "llm_evaluator",
            "scenario": "edp_agent",
            "prompt": EVALUATOR_PROMPT,
        },
        "skills": SKILLS,
        "dataset_path": DATASET_PATH,
    }


# ── Step 1: Restore Skills ──


def step_restore_skills() -> bool:
    """调用 restore_skill 恢复 Skill 到初始状态（同步）。

    幂等设计：
    - Adapter 不支持 restore_skill 时打印警告并继续
    - 部分 Skill 恢复失败时也继续
    """
    print("── Step 1: Restore Skills ──")

    body = {
        "agent_name": AGENT_NAME,
        "action": "restore_skill",
        "skill_names": SKILLS,
    }

    try:
        resp = httpx.post(
            f"{ADAPTER_URL}/api/v1/skills",
            json=body,
            timeout=30.0,
        )
        if resp.status_code >= 400:
            data = resp.json()
            print(f"  ⚠ restore_skill not supported: {data}")
            print("  Continuing without restore.\n")
            return True

        results = resp.json().get("restored", [])
    except Exception as e:
        print(f"  ⚠ restore_skill failed: {e}")
        print("  Continuing without restore.\n")
        return True

    all_ok = True
    for r in results:
        name = r.get("skill_name", "?")
        ok = r.get("success", False)
        msg = r.get("message", "")
        status = "✅" if ok else "⚠"
        print(f"  {status} {name}" + (f" ({msg})" if msg and not ok else ""))
        if not ok:
            all_ok = False

    if not all_ok:
        print("  ⚠ Some skills not restored. Continuing.\n")
    else:
        print(f"  All {len(SKILLS)} skills restored.\n")

    return True


# ── Step 2: Submit Optimization ──


def step_submit_optimize(
    client: TestClient,
) -> tuple[str, str]:
    """POST /optimize 提交优化任务。返回 (job_id, initial_status)。"""
    print("── Step 2: Submit Optimization ──")

    body = build_request_body()
    print(f"  Skills:  {SKILLS}")
    print(f"  Epochs:  {NUM_EPOCHS}")
    print(f"  Batch:   {BATCH_SIZE}")
    print(f"  Dataset: {DATASET_PATH}")
    print()
    print("  Submitting POST /optimize ...")

    start = time.monotonic()
    response = client.post("/optimize", json=body)
    elapsed = time.monotonic() - start

    if response.status_code != 200:
        print(f"  ERROR: HTTP {response.status_code} — {response.text}")
        sys.exit(1)

    data = response.json()
    job_id = data["job_id"]
    status = data["status"]
    print(f"  Job ID:  {job_id}")
    print(f"  Status:  {status}")
    print(f"  Submit took: {elapsed:.1f}s")
    print()
    return job_id, status


# ── Step 3: Poll for Completion ──


def step_poll_job(
    client: TestClient,
    job_id: str,
    initial_status: str,
) -> dict[str, Any]:
    """GET /optimize/{job_id} 轮询直到 completed/failed。返回最终响应。"""
    status = initial_status

    if status in ("completed", "failed"):
        response = client.get(f"/optimize/{job_id}")
        return response.json()

    print(f"── Step 3: Polling (every {POLL_INTERVAL}s, max {MAX_WAIT}s) ──")
    start = time.monotonic()

    while status not in ("completed", "failed"):
        elapsed = time.monotonic() - start
        if elapsed > MAX_WAIT:
            print(f"  TIMEOUT after {elapsed:.0f}s!")
            sys.exit(1)

        time.sleep(POLL_INTERVAL)
        response = client.get(f"/optimize/{job_id}")
        data = response.json()
        status = data["status"]
        progress = data.get("progress") or {}
        epoch = progress.get("current_epoch", 0)
        total = progress.get("total_epochs", "?")
        val = progress.get("val_score")
        val_str = f"{val:.3f}" if val is not None else "—"
        print(f"  [{elapsed:6.0f}s] status={status}  epoch={epoch}/{total}  val={val_str}")

    elapsed = time.monotonic() - start
    print(f"  Finished in {elapsed:.0f}s with status: {status}")
    print()
    return data


# ── Validation ──


def validate_response(data: dict[str, Any]) -> list[str]:
    """验证 API 响应结构完整性（无 null 值）。"""
    errors: list[str] = []
    status = data.get("status", "")

    if status != "completed":
        errors.append(f"Expected status='completed', got '{status}'")
        if data.get("error"):
            errors.append(f"Error: {data['error']}")
        return errors

    r = data.get("result")
    if r is None:
        errors.append("result is null")
        return errors

    # Top-level fields
    for field in ("skills", "epochs_completed", "edits_applied"):
        if field not in r:
            errors.append(f"Missing top-level field: {field}")

    # train
    train = r.get("train")
    if train is None:
        errors.append("train is null")
    else:
        for field in (
            "score_before",
            "score_after",
            "improvement",
            "pass_rate_before",
            "pass_rate_after",
            "num_cases",
        ):
            if field not in train:
                errors.append(f"Missing train.{field}")
            elif train[field] is None:
                errors.append(f"train.{field} is null")
        if train.get("num_cases", 0) < 1:
            print("    ⚠ train.num_cases=0 (traces may be unavailable)")

    # val
    val = r.get("val")
    if val is None:
        errors.append("val is null")
    else:
        for field in ("final_score", "best_score", "per_epoch_scores", "num_cases"):
            if field not in val:
                errors.append(f"Missing val.{field}")
            elif val[field] is None:
                errors.append(f"val.{field} is null")
        pes = val.get("per_epoch_scores", [])
        if not isinstance(pes, list) or len(pes) < 1:
            errors.append(f"val.per_epoch_scores invalid: {pes!r}")

    # skill_scores
    ss = r.get("skill_scores")
    if not isinstance(ss, list) or len(ss) < 1:
        errors.append(f"skill_scores empty or missing: {ss!r}")
    else:
        for i, s in enumerate(ss):
            for field in (
                "name",
                "score_before",
                "score_after",
                "score_delta",
                "edits_applied",
                "pass_rate_before",
                "pass_rate_after",
            ):
                if field not in s:
                    errors.append(f"skill_scores[{i}] missing {field}")
                elif s[field] is None:
                    errors.append(f"skill_scores[{i}].{field} is null")

    # skill_contents
    sc = r.get("skill_contents")
    if not isinstance(sc, list) or len(sc) < 1:
        errors.append(f"skill_contents empty or missing: {sc!r}")
    else:
        for i, c in enumerate(sc):
            for field in ("name", "content_before", "epoch_contents"):
                if field not in c:
                    errors.append(f"skill_contents[{i}] missing {field}")
                elif c[field] is None:
                    errors.append(f"skill_contents[{i}].{field} is null")

    # gate_results
    gr = r.get("gate_results")
    if not isinstance(gr, list):
        errors.append(f"gate_results not a list: {gr!r}")

    # epochs_completed
    ec = r.get("epochs_completed", 0)
    if ec < 1:
        errors.append(f"epochs_completed should be >= 1, got {ec}")

    # Cross-check: gate_results count should match epochs
    if isinstance(gr, list) and isinstance(val, dict):
        pes = val.get("per_epoch_scores", [])
        if len(gr) != len(pes) and len(gr) > 0:
            errors.append(
                f"gate_results length ({len(gr)}) != per_epoch_scores length ({len(pes)})"
            )

    return errors


def validate_artifacts(result: dict[str, Any]) -> list[str]:
    """验证文件产物正确性。"""
    errors: list[str] = []

    config = EvolveConfig.get()
    artifact_root = config.artifact_dir

    if not artifact_root.exists():
        errors.append(f"Artifact root not found: {artifact_root}")
        return errors

    # 找最新的 run 目录（按修改时间排序）
    run_dirs = sorted(artifact_root.iterdir(), key=lambda p: p.stat().st_mtime, reverse=True)
    if not run_dirs:
        errors.append(f"No run directories found in {artifact_root}")
        return errors

    run_dir = run_dirs[0]
    print(f"  Artifact dir: {run_dir}")

    # 检查 epoch 目录
    epoch_dirs = sorted(run_dir.glob("epoch_*"))
    epochs_completed = result.get("epochs_completed", 0)
    if len(epoch_dirs) < epochs_completed:
        errors.append(f"Expected >= {epochs_completed} epoch dirs, found {len(epoch_dirs)}")

    for epoch_dir in epoch_dirs:
        gate = epoch_dir / "gate_result.json"
        if not gate.exists():
            print(f"    ⚠ Missing gate_result.json in {epoch_dir.name} (may be skipped)")

    # 检查 per-skill 子目录（多 skill 时 EDPAgentOptimizer 会创建）
    for skill_name in SKILLS:
        skill_dir = run_dir / skill_name
        if skill_dir.exists():
            skill_epochs = sorted(skill_dir.glob("epoch_*"))
            if not skill_epochs:
                errors.append(f"Skill dir {skill_name} has no epoch subdirs")

    return errors


def validate_sse_events(job_id: str) -> list[str]:
    """验证 SSE 事件序列正确性。"""
    errors: list[str] = []
    job = job_manager.get(job_id)
    if job is None:
        errors.append(f"Job {job_id} not found in job_manager")
        return errors

    events = list(job.event_buffer)
    if not events:
        errors.append("Event buffer is empty")
        return errors

    event_types = {e.event for e in events}
    if "progress" not in event_types:
        errors.append("No 'progress' events found")
    if "log" not in event_types:
        errors.append("No 'log' events found")
    if "completed" not in event_types:
        errors.append("No 'completed' event found")

    # 检查 progress phase 顺序
    progress_phases = [e.data.get("phase") for e in events if e.event == "progress"]
    expected_order = ["train_begin", "epoch_begin", "epoch_end", "train_end"]
    for phase in expected_order:
        if phase not in progress_phases:
            errors.append(f"Missing progress phase: {phase}")

    # train_begin 应在最前
    if progress_phases and progress_phases[0] != "train_begin":
        errors.append(f"First phase should be train_begin, got {progress_phases[0]}")

    # train_end 应在最后（progress 事件中）
    if progress_phases and progress_phases[-1] != "train_end":
        errors.append(f"Last progress phase should be train_end, got {progress_phases[-1]}")

    # 检查 completed 事件
    completed = [e for e in events if e.event == "completed"]
    if completed:
        last = completed[-1]
        if last.data.get("status") != "completed":
            errors.append(f"completed event status should be 'completed', got {last.data}")

    # 每个 epoch 应有 validation phase log
    validation_logs = [
        e for e in events if e.event == "log" and e.data.get("phase") == "validation"
    ]
    epoch_ends = [e for e in events if e.event == "progress" and e.data.get("phase") == "epoch_end"]
    if len(validation_logs) < len(epoch_ends):
        errors.append(f"Expected >= {len(epoch_ends)} validation logs, got {len(validation_logs)}")

    # 打印事件摘要
    print(f"  Total events: {len(events)}")
    print(f"  Progress phases: {progress_phases}")

    phase_logs = [e.data.get("phase") for e in events if e.event == "log"]
    unique_phases = list(dict.fromkeys(phase_logs))  # dedup, preserve order
    print(f"  Log phases: {unique_phases}")

    return errors


def print_result_summary(result: dict[str, Any]) -> None:
    """打印优化结果摘要。"""
    train = result.get("train", {})
    val = result.get("val", {})
    print(f"  Epochs:        {result.get('epochs_completed')}")
    print(f"  Score before:  {train.get('score_before', 0):.3f}")
    print(f"  Score after:   {train.get('score_after', 0):.3f}")
    print(f"  Improvement:   {train.get('improvement', '?')}")
    print(f"  Val score:     {val.get('final_score', 0):.3f}")
    print(f"  Val best:      {val.get('best_score', 0):.3f}")
    print(f"  Edits applied: {result.get('edits_applied', 0)}")
    print(f"  Gate results:  {result.get('gate_results', [])}")

    for s in result.get("skill_scores", []):
        print(
            f"  - {s['name']}: {s['score_before']:.3f} → {s['score_after']:.3f} "
            f"(Δ{s['score_delta']:+.3f}, {s['edits_applied']} edits)"
        )


# ── Main ──


def main() -> None:
    print("=" * 60)
    print("  E2E Full Optimization Test (HTTP API)")
    print("=" * 60)
    print(f"  Adapter:  {ADAPTER_URL}")
    print(f"  LLM:      {os.environ.get('EVO_OPTIMIZER_MODEL', '?')}")
    print(f"  Dataset:  {DATASET_PATH}")
    print(f"  Scenario: {AGENT_NAME}")
    print(f"  Skills:   {SKILLS}")
    print()

    # Pre-flight checks
    if not os.environ.get("EVO_LLM_API_KEY"):
        print("ERROR: EVO_LLM_API_KEY not set!")
        sys.exit(1)
    if not Path(DATASET_PATH).exists():
        print(f"ERROR: Dataset not found: {DATASET_PATH}")
        sys.exit(1)

    # Step 1: Restore skills (best-effort)
    if not step_restore_skills():
        sys.exit(1)

    # Step 2 + 3: Submit and poll via TestClient
    # TestClient 维护持久事件循环，避免 ASGITransport 的 "Event loop is closed" 问题
    with TestClient(app) as client:
        # Submit
        job_id, status = step_submit_optimize(client)

        # Poll
        final_data = step_poll_job(client, job_id, status)

    # Step 4: Validate response
    print("── Validation ──")

    print("\n  [Response Structure]")
    resp_errors = validate_response(final_data)
    for e in resp_errors:
        print(f"    ❌ {e}")

    result = final_data.get("result") or {}
    if not resp_errors and result:
        print_result_summary(result)

    # Step 5: Validate SSE events
    print("\n  [SSE Events]")
    sse_errors = validate_sse_events(job_id)
    for e in sse_errors:
        print(f"    ❌ {e}")

    # Step 6: Validate artifacts
    print("\n  [File Artifacts]")
    artifact_errors = validate_artifacts(result)
    for e in artifact_errors:
        print(f"    ❌ {e}")

    # Summary
    all_errors = resp_errors + sse_errors + artifact_errors
    print()
    if all_errors:
        print(f"=== FAILED ({len(all_errors)} errors) ===")
        for e in all_errors:
            print(f"  ❌ {e}")
        sys.exit(1)
    else:
        print("=== E2E TEST PASSED ===")


if __name__ == "__main__":
    main()
