"""最小编排入口 — OptimizeRequest → 一次可运行的优化任务。

该模块是 EvoAgent 中唯一允许知道 Trainer、Operator、Optimizer、Callbacks
如何组装的地方。
"""

from __future__ import annotations

import asyncio
import dataclasses
import hashlib
import json
import math
import os
import time
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any, cast

import structlog
from openjiuwen.agent_evolving.updater.single_dim import SingleDimUpdater
from openjiuwen.core.foundation.llm.model import Model
from openjiuwen.core.foundation.llm.schema.config import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.single_agent import AgentCard

from evo_agent.adapter_client.applier import AppliedDocument, ManagedDocApplier
from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.content_policy import (
    ContentPolicy,
    PassthroughPolicy,
    PreservingContentPolicy,
    ProtectedSection,
)
from evo_agent.adapter_client.operator import (
    build_managed_doc_operator,
    build_skill_document_operator,
)
from evo_agent.adapter_client.remote_agent import RemoteAgent
from evo_agent.adapter_client.types import ManagedDocSnapshot
from evo_agent.callbacks import build_callbacks
from evo_agent.cancellation import CancellationRequestedError, CancellationToken
from evo_agent.config import EvolveConfig
from evo_agent.conversation import ConversationIdFactory
from evo_agent.dataset.manifest import build_dataset_from_request, load_dataset_manifest
from evo_agent.errors import (
    ArtifactConsistencyError,
    CancelRollbackError,
    ManagedDocApplyError,
    ManagedDocBaselineChangedError,
    ManagedDocBaselineError,
)
from evo_agent.llm.invocation import LLMInvocation, LLMProviderCapabilities
from evo_agent.optimizer.concurrency import gather_with_semaphore
from evo_agent.reporter.formatter import ReportFormatter
from evo_agent.runtime_config import OptimizationConfigResolver
from evo_agent.scenario.registry import ScenarioRegistry
from evo_agent.trainer import EvoTrainer
from evo_agent.types import ManagedDocEpochContent, OptimizeReport, OptimizeRequest

# ── managed-doc job-start baseline + artifacts (spec F7 / F8) ──────────────


def _write_atomic_text(path: Path, content: str) -> None:
    """原子写文本：写 .tmp 后 os.replace，避免崩溃留下半截 artifact。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(content, encoding="utf-8")
    os.replace(tmp, path)


def _managed_doc_revision_diagnostics(
    snapshot: ManagedDocSnapshot,
    *,
    doc_kind: str,
    deadline: float,
    agent_name: str,
) -> dict[str, Any]:
    """job-start snapshot 的 revision 诊断（无全文，仅元数据 + 长度）。

    落盘到 ``managed_doc_diagnostics.json``，baseline 校验失败/apply 异常时
    供人工恢复核对。日志只记 hash + 长度，不记全文（ADR §10）。
    """
    content_hash = hashlib.sha256(snapshot.content.encode("utf-8")).hexdigest()
    return {
        "agent_name": agent_name,
        "doc_kind": doc_kind,
        "file_revision": snapshot.file_revision,
        "applied_revision": snapshot.applied_revision,
        "pending_apply": snapshot.pending_apply,
        "apply_mode": snapshot.apply_mode,
        "max_task_seconds": snapshot.max_task_seconds,
        "deadline": deadline,
        "content_hash": content_hash,
        "content_length": len(snapshot.content),
    }


def _validate_managed_doc_baseline(
    snapshot: ManagedDocSnapshot,
    *,
    doc_kind: str,
    deadline: float,
    agent_name: str,
) -> None:
    """job-start baseline 不变量校验（spec F7 §2 / 不变量 5）。

    必须满足：(1) ``apply_mode == "restart"``；(2) ``pending_apply == false``；
    (3) ``file_revision == applied_revision``（已实际生效，无未 apply 的悬空版本）；
    (4) ``deadline >= max_task_seconds + 10s``（部署 deadline 大于 adapter 最坏 task
    时长 + 网络余量）。任一不满足抛 ``ManagedDocBaselineError``，不启动 rollout。
    """
    diag = _managed_doc_revision_diagnostics(
        snapshot, doc_kind=doc_kind, deadline=deadline, agent_name=agent_name
    )
    if snapshot.apply_mode != "restart":
        raise ManagedDocBaselineError(
            agent_name=agent_name,
            doc_kind=doc_kind,
            reason="apply_mode",
            diagnostics=f"apply_mode={snapshot.apply_mode!r} (require 'restart'); "
            f"file_revision={snapshot.file_revision}, applied_revision="
            f"{snapshot.applied_revision}, pending_apply={snapshot.pending_apply}",
        )
    if snapshot.pending_apply:
        raise ManagedDocBaselineError(
            agent_name=agent_name,
            doc_kind=doc_kind,
            reason="pending_apply",
            diagnostics=f"pending_apply=true (a prior update is still pending restart); "
            f"file_revision={snapshot.file_revision}, applied_revision="
            f"{snapshot.applied_revision}, apply_mode={snapshot.apply_mode}",
        )
    if (
        snapshot.file_revision is None
        or snapshot.applied_revision is None
        or snapshot.file_revision != snapshot.applied_revision
    ):
        raise ManagedDocBaselineError(
            agent_name=agent_name,
            doc_kind=doc_kind,
            reason="revision_mismatch",
            diagnostics=f"file_revision={snapshot.file_revision} != applied_revision="
            f"{snapshot.applied_revision} (baseline must rest on an already-applied "
            f"revision); pending_apply={snapshot.pending_apply}",
        )
    margin = 10.0
    mts = snapshot.max_task_seconds
    # NaN max_task_seconds/deadline 使 `<` 恒 False 绕过门（IEEE 754），需显式守卫。
    if math.isnan(mts) or math.isnan(deadline) or deadline < mts + margin:
        raise ManagedDocBaselineError(
            agent_name=agent_name,
            doc_kind=doc_kind,
            reason="deadline",
            diagnostics=f"deadline={deadline}s < max_task_seconds+{margin}s="
            f"{mts + margin}s (deploy deadline must exceed adapter worst-case task "
            f"duration); max_task_seconds={mts} (NaN gate guard)",
        )
    # diagnostics 仅在失败路径使用，成功路径静默；保留 diag 计算避免 unused。
    _ = diag


def _validate_expected_managed_doc_revision(
    snapshot: ManagedDocSnapshot,
    *,
    agent_name: str,
    doc_kind: str,
    expected_revision: str,
) -> None:
    """Prove an Adapter snapshot is the Studio-persisted baseline revision."""
    if (
        snapshot.pending_apply
        or snapshot.file_revision != expected_revision
        or snapshot.applied_revision != expected_revision
    ):
        raise ManagedDocBaselineChangedError(
            agent_name=agent_name,
            doc_kind=doc_kind,
            expected_revision=expected_revision,
            file_revision=snapshot.file_revision,
            applied_revision=snapshot.applied_revision,
            pending_apply=snapshot.pending_apply,
        )


def _build_managed_doc_content_policy(
    config: EvolveConfig,
    *,
    doc_kind: str,
    baseline_content: str,
) -> ContentPolicy:
    """按 ``EvolveConfig.managed_doc_content_policies`` 选 policy（缺省 preserving）。

    runner 把叶子 ``config.py`` 的 ``ProtectedSectionConfig`` 映射为 adapter-client
    侧 frozen ``ProtectedSection``，保持 config 层不反向依赖 transport。
    """
    policy_name = config.managed_doc_content_policies.get(doc_kind, "preserving")
    if policy_name == "passthrough":
        return PassthroughPolicy()
    protected = tuple(
        ProtectedSection(start_marker=ps.start_marker, end_marker=ps.end_marker)
        for ps in config.managed_doc_protected_sections.get(doc_kind, [])
    )
    return PreservingContentPolicy(
        baseline_content=baseline_content,
        protected_sections=protected,
    )


def _serialize_managed_doc_records(
    records: tuple[Any, ...],
) -> tuple[list[dict[str, Any]], tuple[str, ...]]:
    """把 Applier records 序列化为 JSON-safe ledger + 非空 task_id 列表。

    日志/artifact 只记 hash + 长度 + task_id，不记全文（ADR §10）。
    """
    ledger: list[dict[str, Any]] = []
    task_ids: list[str] = []
    for rec in records:
        # ManagedDocApplyRecord 是 frozen dataclass；dataclasses.asdict 取全字段。
        rec_dict = dataclasses.asdict(rec)
        # content_hash 已是 sha256 hex（非全文），保留；无 content 字段需剔除。
        ledger.append(rec_dict)
        if rec.task_id:
            task_ids.append(rec.task_id)
    return ledger, tuple(task_ids)


def _write_managed_doc_tasks_json(
    artifact_dir: Path,
    records: tuple[Any, ...],
) -> tuple[str, ...]:
    """finally 刷新 managed_doc_tasks.json（applier records ledger）。

    apply 异常时也保证落盘（失败路径诊断数据源）。返回非空 task_id 列表供
    report 回填。写盘失败只追加 suppressed diagnostic，不覆盖原始 fatal 异常。
    """
    ledger, task_ids = _serialize_managed_doc_records(records)
    payload = {
        "doc_kind_records": ledger,
        "task_ids": list(task_ids),
    }
    try:
        _write_atomic_text(
            artifact_dir / "managed_doc_tasks.json",
            json.dumps(payload, ensure_ascii=False, indent=2),
        )
    except OSError:
        structlog.get_logger().warning(
            "managed_doc_tasks.json write suppressed (fatal error path)",
            doc_kind_records=len(ledger),
            error="OSError",
        )
    return task_ids


def rollback_managed_doc(
    *,
    adapter_client: AdapterClient,
    doc_kind: str,
    baseline_content: str,
    baseline_revision: str,
    deadline: float,
    phase_callback: Any | None = None,
    clock: Callable[[], float] = time.monotonic,
) -> AppliedDocument:
    """Restore and re-confirm the immutable job-start baseline."""
    started_at = clock()
    if phase_callback is not None:
        phase_callback(
            "log",
            {
                "level": "info",
                "message": f"Restoring managed-doc baseline: doc_kind={doc_kind}",
                "phase": "rollback_started",
            },
        )
    applier = ManagedDocApplier(
        adapter_client=adapter_client,
        doc_kind=doc_kind,
        deadline=deadline,
    )
    try:
        result = applier.apply_and_wait(baseline_content)
        remaining = deadline - (clock() - started_at)
        if remaining <= 0:
            raise CancelRollbackError(
                code="CANCEL_ROLLBACK_TIMEOUT",
                diagnostics="total cancellation recovery deadline exhausted before confirmation",
            )
        snapshot = adapter_client.get_managed_doc_sync(doc_kind, request_timeout=remaining)
        if clock() - started_at > deadline:
            raise CancelRollbackError(
                code="CANCEL_ROLLBACK_TIMEOUT",
                diagnostics="total cancellation recovery deadline exhausted during confirmation",
            )
        if (
            snapshot.pending_apply
            or snapshot.file_revision != baseline_revision
            or snapshot.applied_revision != baseline_revision
        ):
            raise CancelRollbackError(
                code="CANCEL_ROLLBACK_FAILED",
                diagnostics=(
                    f"pending_apply={snapshot.pending_apply} "
                    f"file_revision={snapshot.file_revision} "
                    f"applied_revision={snapshot.applied_revision} "
                    f"expected_revision={baseline_revision}"
                ),
            )
    except CancelRollbackError:
        raise
    except ManagedDocApplyError as exc:
        code = "CANCEL_ROLLBACK_TIMEOUT" if exc.phase == "deadline" else "CANCEL_ROLLBACK_FAILED"
        raise CancelRollbackError(
            code=code,
            diagnostics=(f"phase={exc.phase} task_id={exc.task_id} doc_kind={exc.doc_kind}"),
        ) from exc
    except Exception as exc:
        # Verification/transport failures are terminal too. Keep diagnostics
        # type-only so an Adapter response cannot leak managed-document text.
        raise CancelRollbackError(
            code="CANCEL_ROLLBACK_FAILED",
            diagnostics=f"{type(exc).__name__}: rollback verification unavailable",
        ) from exc
    if phase_callback is not None:
        phase_callback(
            "log",
            {
                "level": "info",
                "message": f"Managed-doc baseline restored: doc_kind={doc_kind}",
                "phase": "rollback_completed",
            },
        )
    return result


async def _setup_managed_doc_baseline(
    *,
    adapter_client: Any,
    doc_kind: str,
    agent_name: str,
    deadline: float,
    run_artifact_dir: Path,
    config: EvolveConfig,
    phase_callback: Any | None,
    cancellation_token: CancellationToken | None = None,
) -> tuple[ManagedDocSnapshot, Any]:
    """job-start baseline：读 snapshot → 写 observed/diagnostics → 校验 → 写 before → 建 operator。

    spec F7 §2 / 不变量 4-5：不调 adapter 全局 restore；用当前已发布内容做
    baseline。同步 GET 经 ``asyncio.to_thread`` 避免阻塞 runner event loop。
    校验失败抛 ``ManagedDocBaselineError``，不启动 rollout；此时 observed +
    diagnostics 已落盘供人工核对，但 before.md 未生成（不产生误导性 baseline）。
    """
    if phase_callback is not None:
        phase_callback(
            "log",
            {
                "level": "info",
                "message": f"读取 managed-doc baseline：doc_kind={doc_kind}",
                "phase": "managed_doc_baseline",
            },
        )
    # 同步 transport（_sync_http）经 to_thread 调用，避免阻塞 event loop。
    snapshot = await asyncio.to_thread(adapter_client.get_managed_doc_sync, doc_kind)
    # observed.md + diagnostics：无条件落盘（baseline 校验失败也保留，供人工核对）。
    _write_atomic_text(run_artifact_dir / "managed_doc_observed.md", snapshot.content)
    diag = _managed_doc_revision_diagnostics(
        snapshot, doc_kind=doc_kind, deadline=deadline, agent_name=agent_name
    )
    _write_atomic_text(
        run_artifact_dir / "managed_doc_diagnostics.json",
        json.dumps(diag, ensure_ascii=False, indent=2),
    )
    structlog.get_logger().info(
        "managed-doc baseline snapshot read",
        doc_kind=doc_kind,
        content_hash=diag["content_hash"],
        content_length=diag["content_length"],
        file_revision=snapshot.file_revision,
        applied_revision=snapshot.applied_revision,
        pending_apply=snapshot.pending_apply,
    )
    # 校验 baseline 不变量（失败抛 ManagedDocBaselineError，before.md 不生成）。
    _validate_managed_doc_baseline(
        snapshot, doc_kind=doc_kind, deadline=deadline, agent_name=agent_name
    )
    # 校验通过 → 固化为 before.md（与 observed 同内容，但语义为「已确认 baseline」）。
    _write_atomic_text(run_artifact_dir / "managed_doc_before.md", snapshot.content)
    content_policy = _build_managed_doc_content_policy(
        config, doc_kind=doc_kind, baseline_content=snapshot.content
    )
    operator = build_managed_doc_operator(
        doc_kind=doc_kind,
        initial_content=snapshot.content,
        adapter_client=adapter_client,
        content_policy=content_policy,
        deadline=deadline,
        # applied_revision 是 adapter 侧 content sha256（_snapshot_confirmed 校验
        # file/applied revision 都等于期望 content hash），用它初始化 Applier
        # last_success_hash → baseline 内容首次 set_parameter 时 hash 命中 no-op。
        last_success_hash=snapshot.applied_revision,
        cancellation_token=cancellation_token,
        phase_callback=phase_callback,
    )
    return snapshot, operator


async def run_optimization(
    request: OptimizeRequest,
    config: EvolveConfig,
    *,
    progress_callback: Any | None = None,
    phase_callback: Any | None = None,
    cancellation_token: CancellationToken | None = None,
    managed_doc_baseline_callback: Callable[[ManagedDocSnapshot], None] | None = None,
) -> OptimizeReport:
    """运行一次远程 skill 文档优化。

    职责边界：
    - 构建 AdapterClient + 获取 skill 初始内容
    - 构建 operators + RemoteAgent
    - 读取 dataset manifest，得到 CaseLoader + Evaluator
    - 通过 ScenarioRegistry 构建场景 optimizer 子类
    - 组装 Trainer/Updater/Callbacks
    - 调用 trainer.train()
    - 返回 ReportFormatter 汇总结果
    """
    token = cancellation_token or CancellationToken()
    token.raise_if_requested()
    structlog.get_logger().debug(
        "run_optimization started",
        scenario=request.scenario,
        skills=request.skills,
    )
    registry = ScenarioRegistry()
    resolved = OptimizationConfigResolver(config, registry=registry).resolve(request)
    # managed-doc 模式判定（spec F7）：managed_doc_kind 非空 → managed-doc 单文档
    # 优化路径，canonical id ``managed_doc:{kind}`` 三处一致（operators key /
    # operator id / artifact 目录）。否则走现有 Skill 路径。
    managed_doc_kind = resolved.managed_doc_kind
    is_managed_doc = managed_doc_kind is not None
    canonical_id: str | None = f"managed_doc:{managed_doc_kind}" if is_managed_doc else None
    # 0. 注入 structlog contextvars
    run_id = uuid.uuid4().hex[:12]
    structlog.contextvars.bind_contextvars(
        run_id=run_id,
        scenario=resolved.scenario,
        agent_name=resolved.agent_name,
    )

    # 0.0 推送 pipeline 开始事件（让前端立即看到活动）
    if phase_callback is not None:
        target_label = (
            f"managed_doc_kind={managed_doc_kind}"
            if is_managed_doc
            else f"skills={list(resolved.skills)}"
        )
        phase_callback(
            "log",
            {
                "level": "info",
                "message": f"优化 Pipeline 启动：scenario={resolved.scenario}, {target_label}",
                "phase": "pipeline_start",
            },
        )

    # 0.1 构建 ConversationIdFactory
    conversation_id_factory = ConversationIdFactory(run_id=run_id)

    # artifact 目录：managed-doc 模式按 canonical id 分组、run_id 隔离每次 run。
    # 同 kind 二次优化若复用同一目录，旧 epoch_N/eval_results.json 残留污染新报告，
    # 且 baseline 失败时旧 managed_doc_before.md 残留误导（违反 F8 AC）；嵌套 run_id 隔离。
    if is_managed_doc and canonical_id is not None:
        run_artifact_dir = config.artifact_dir / canonical_id / run_id
    else:
        run_artifact_dir = config.artifact_dir / run_id

    # 1. 构建 AdapterClient
    async with AdapterClient(
        resolved.adapter_url,
        agent_name=resolved.agent_name,
        timeout=config.remote_timeout,
        max_retries=config.remote_max_retries,
    ) as adapter_client:
        # 1.5 baseline：managed-doc 模式读 job-start snapshot（不调全局 restore）；
        #     Skill 模式恢复 skill 初始状态（幂等，未修改过的 skill 不受影响）。
        if is_managed_doc and managed_doc_kind is not None and canonical_id is not None:
            managed_snapshot, managed_operator = await _setup_managed_doc_baseline(
                adapter_client=adapter_client,
                doc_kind=managed_doc_kind,
                agent_name=resolved.agent_name,
                deadline=config.managed_doc_apply_deadline,
                run_artifact_dir=run_artifact_dir,
                config=config,
                phase_callback=phase_callback,
                cancellation_token=token,
            )
            if resolved.managed_doc_expected_revision is not None:
                _validate_expected_managed_doc_revision(
                    managed_snapshot,
                    agent_name=resolved.agent_name,
                    doc_kind=managed_doc_kind,
                    expected_revision=resolved.managed_doc_expected_revision,
                )
            if managed_doc_baseline_callback is not None:
                managed_doc_baseline_callback(managed_snapshot)
            operators = {canonical_id: managed_operator}
        else:
            if phase_callback is not None:
                phase_callback(
                    "log",
                    {
                        "level": "info",
                        "message": f"恢复 Skill 初始状态：{list(resolved.skills)}",
                        "phase": "restore_skill",
                    },
                )
            try:
                restored = await adapter_client.restore_skill(list(resolved.skills))
                structlog.get_logger().info(
                    "restore_skill completed",
                    skills=list(resolved.skills),
                    restored=[r.get("skill_name") for r in restored],
                )
            except Exception as exc:
                structlog.get_logger().warning(
                    "restore_skill failed (continuing with current state)",
                    skills=list(resolved.skills),
                    error=str(exc),
                )

            # 2. 获取 skill 初始内容并构建 operators
            #    注意：此处会触发 _async_client 在 main thread 的 event loop 中创建。
            #    setup 完成后必须关闭，避免 worker thread 复用跨 loop 的 client。
            if phase_callback is not None:
                phase_callback(
                    "log",
                    {
                        "level": "info",
                        "message": "构建 operators...",
                        "phase": "setup",
                    },
                )
            operators = await _build_operators(
                list(resolved.skills),
                adapter_client,
                preserve_frontmatter=config.preserve_frontmatter,
            )

            managed_snapshot = None
            managed_operator = None

        # 关闭 setup 阶段创建的 async client（绑定到 main thread event loop），
        # 让 worker thread 的 _async_client property 在自己的 event loop 中重建。
        _setup_client = getattr(adapter_client, "_async_http", None)
        if _setup_client is not None:
            await _setup_client.aclose()
            adapter_client._async_http = None
            adapter_client._async_http_loop = None

        # 3. 构建 RemoteAgent
        card = AgentCard(name=resolved.agent_name)
        remote_agent = RemoteAgent(
            card=card,
            adapter_client=adapter_client,
            operators=operators,
        )

        # 4. 构建 eval_runtime 并读取 dataset manifest
        eval_runtime: dict[str, Any] = {
            "model_config": ModelRequestConfig(model_name=config.optimizer_model),
            "model_client_config": _build_model_client_config(config),
        }
        if resolved.evaluator_prompt:
            eval_runtime["prompt_template"] = resolved.evaluator_prompt

        if resolved.dataset_manifest_path is not None:
            # CLI 模式：从 dataset.yaml 加载
            dataset = load_dataset_manifest(
                resolved.dataset_manifest_path,
                eval_runtime=eval_runtime,
            )
        else:
            # API 模式：从原始数据文件 + 切分比例构建
            dataset = build_dataset_from_request(
                data_path=Path(resolved.dataset_path),
                evaluator_prompt=resolved.evaluator_prompt,
                train_split=resolved.train_split,
                val_split=resolved.val_split,
                eval_runtime=eval_runtime,
            )

        # 5. 构建 LLM
        llm = _create_llm(config)
        context_window_tokens = (
            config.icbc_context_window_tokens
            if config.llm_provider == "ICBC"
            else resolved.llm_context_window_tokens
        )
        if context_window_tokens is None:
            raise ValueError("ICBC context window must be explicitly configured")
        llm_invocation = LLMInvocation(
            llm,
            capabilities=LLMProviderCapabilities(
                context_window_tokens=context_window_tokens,
                supports_max_output_tokens=config.llm_provider != "ICBC",
                supports_finish_reason=config.llm_provider != "ICBC",
                supports_usage=config.llm_provider != "ICBC",
                supports_json_mode=config.llm_provider != "ICBC",
                completion_signal=(
                    config.icbc_completion_signal if config.llm_provider == "ICBC" else "either"
                ),
            ),
            parallelism=resolved.parallelism,
            safety_margin_tokens=resolved.llm_safety_margin_tokens,
            chars_per_token=(
                config.icbc_chars_per_token
                if config.llm_provider == "ICBC"
                else resolved.llm_chars_per_token
            ),
            default_output_reserve_tokens=resolved.llm_output_reserve_tokens,
            stage_output_reserve_tokens=resolved.llm_stage_output_reserve_tokens,
        )
        _bind_evaluator_invocation(dataset.evaluator, llm_invocation)

        # 6. 构建依赖（run_artifact_dir 在进入 AdapterClient 前按模式计算：
        # managed-doc 用 canonical id 作目录名，Skill 用 run_id）。
        dependencies: dict[str, Any] = {
            "agent": remote_agent,
            "evaluator": dataset.evaluator,
            "llm": llm,
            "llm_invocation": llm_invocation,
            "model": config.optimizer_model,
            "train_cases": dataset.train_cases,
            **resolved.optimizer_runtime_dependencies(),
            "artifact_dir": str(run_artifact_dir),
            # Wave 3 新增：注入 AdapterClient 和 operators
            "adapter_client": adapter_client,
            "operators": operators,
            # Wave 7 新增：注入 ConversationIdFactory
            "conversation_id_factory": conversation_id_factory,
            # Trace 重试配置（Adapter 日志采集 + cleaned-traces 清洗可能超过 50s）
            # Wave 10 新增：注入 phase_callback（SSE 阶段事件推送）
            "phase_callback": phase_callback or (lambda *a, **kw: None),
            "cancellation_token": token,
        }

        # 7. 通过 ScenarioRegistry 构建场景 optimizer
        optimizer = registry.build_optimizer(request, dependencies)

        # 8. 组装 EvoTrainer pipeline
        updater = SingleDimUpdater(optimizer=optimizer)
        callbacks = build_callbacks(optimizer, progress_callback=progress_callback)
        trainer = EvoTrainer(
            adapter_client=adapter_client,
            conversation_id_factory=conversation_id_factory,
            # managed-doc 模式传 canonical id（评估器经 skill_names 归因到
            # managed_doc:{kind}，单 operator short-circuit 精确匹配，spec F7 §5）。
            skill_names=[canonical_id]
            if is_managed_doc and canonical_id
            else list(resolved.skills),
            rollout_extra_data=resolved.rollout_extra_data,
            updater=updater,
            evaluator=dataset.evaluator,
            callbacks=callbacks,
            num_parallel=resolved.num_parallel,
            trace_max_retries=resolved.trace_max_retries,
            trace_retry_backoff=resolved.trace_retry_backoff,
            tie_reval_eps=resolved.tie_reval_eps,
            validation_max_case_attempts=resolved.validation_max_case_attempts,
            validation_min_success_ratio=resolved.validation_min_success_ratio,
            validation_require_same_case_set=resolved.validation_require_same_case_set,
            early_stop_score=1.01,  # prevent early stop at perfect 1.0
            cancellation_token=token,
        )

        # 更新 ProgressCallback 的 case 数量（dataset 在此处才可用）
        if progress_callback is not None:

            def _case_count(cases: Any) -> int:
                if cases is None:
                    return 0
                if hasattr(cases, "get_cases"):
                    return len(cases.get_cases())
                if isinstance(cases, (list, tuple)):
                    return len(cases)
                return 0

            progress_callback._num_train_cases = _case_count(dataset.train_cases)
            progress_callback._num_val_cases = _case_count(dataset.val_cases)

        # 8.5 手动跑验证集基线评估（vendor Trainer 对 SkillDocumentOptimizer 跳过此步骤）
        # C6 (#2): 只要有 val_cases 即预热 baseline + record_validation_baseline，
        # 不再依赖 progress_callback，CLI/无 callback 模式也复用 baseline 缓存，
        # 避免每 epoch 重复跑 base validation。
        val_baseline_case_scores: list[float] = []
        if dataset.val_cases is not None:
            structlog.get_logger().debug(
                "Starting baseline evaluation", val_cases_count=len(dataset.val_cases)
            )
            baseline_score, baseline_evaluated = await asyncio.to_thread(
                trainer.evaluate, remote_agent, dataset.val_cases
            )
            structlog.get_logger().debug("Baseline evaluation completed", score=baseline_score)
            # 在 baseline 评估点捕获 per-case（true baseline）。不读
            # trainer._cached_base_val_evaluated：后者在每轮 _select_best_candidate_on_val
            # 末尾被 record_validation_baseline 覆盖为该轮 winner，训练结束后是末轮
            # winner 而非 baseline。in-memory 传参避开 _artifact_epoch off-by-one。
            val_baseline_case_scores = [ec.score for ec in baseline_evaluated]
            if progress_callback is not None:
                progress_callback.val_score_before = baseline_score
            trainer.record_validation_baseline(baseline_score, baseline_evaluated)

            # evaluate 在 worker thread 的 event loop 中创建了 httpx AsyncClient，
            # asyncio.run() 返回后该 loop 已关闭，但 client 仍挂在 _async_http 上
            # 且 is_closed == False。若不重置，train 的 worker thread 会复用这个
            # 绑定到已关闭 loop 的 client，导致 RuntimeError: Event loop is closed。
            adapter_client._async_http = None
            adapter_client._async_http_loop = None

        # 9. 训练（Trainer.train 是同步的，在独立线程中运行）
        # Prompt 的 expected revision 来自 Studio 持久化 baseline。baseline eval 可能
        # 耗时较长，因此在任何 candidate apply 可能发生前再次读取并 fail-closed。
        if managed_doc_kind is not None and resolved.managed_doc_expected_revision is not None:
            pre_apply_snapshot = await asyncio.to_thread(
                adapter_client.get_managed_doc_sync, managed_doc_kind
            )
            _validate_expected_managed_doc_revision(
                pre_apply_snapshot,
                agent_name=resolved.agent_name,
                doc_kind=managed_doc_kind,
                expected_revision=resolved.managed_doc_expected_revision,
            )

        # managed-doc 模式：try/finally 在 apply 异常时也刷新 managed_doc_tasks.json
        # （applier records ledger，失败路径诊断数据源）；finally 写盘失败只追加
        # suppressed diagnostic，不覆盖原始 fatal 异常（spec F8）。
        try:
            await asyncio.to_thread(
                trainer.train,
                agent=remote_agent,
                train_cases=dataset.train_cases,
                val_cases=dataset.val_cases,
                num_iterations=resolved.num_epochs,
            )
        finally:
            if managed_doc_kind is not None and managed_operator is not None:
                _write_managed_doc_tasks_json(run_artifact_dir, managed_operator.applier.records)
                # 刷新 diagnostics 反映 apply 后状态（file_revision/applied_revision）；
                # 失败容错：不掩盖原始 fatal 异常、不覆盖 job-start 有效 diag（spec F8）。
                try:
                    snap = await asyncio.to_thread(
                        adapter_client.get_managed_doc_sync, managed_doc_kind
                    )
                    diag = _managed_doc_revision_diagnostics(
                        snap,
                        doc_kind=managed_doc_kind,
                        deadline=config.managed_doc_apply_deadline,
                        agent_name=resolved.agent_name,
                    )
                    _write_atomic_text(
                        run_artifact_dir / "managed_doc_diagnostics.json",
                        json.dumps(diag, ensure_ascii=False, indent=2),
                    )
                except Exception:  # noqa: BLE001 - 容错刷新，不得掩盖主异常
                    structlog.get_logger().warning(
                        "managed-doc diagnostics refresh failed; keeping job-start snapshot",
                        exc_info=True,
                    )

        # 10. 汇总 artifact
        # 报告趋势图用候选 fresh eval 分数（每轮真实评估，会波动）；门控赢家
        # （val_per_epoch_scores，单调非降）仅 ProgressCallback 内部用于 improved 判定。
        gate_epoch_scores = trainer.gate_epoch_scores
        # ProgressCallback（API）会采集趋势与 per-case 明细；CLI 的
        # ConsoleProgressCallback 只负责打印，不提供这些 API 专用属性。runner
        # 接受任意 vendor callback，因此不能假设传入对象一定是 ProgressCallback。
        callback_candidate_scores = getattr(
            progress_callback, "candidate_per_epoch_scores", None
        )
        val_candidate_scores = (
            tuple(callback_candidate_scores)
            if callback_candidate_scores is not None
            else tuple(float(item["candidate_score"]) for item in gate_epoch_scores)
        )
        val_score_before = float(getattr(progress_callback, "val_score_before", 0.0))
        val_per_epoch_case_scores = list(
            getattr(progress_callback, "val_per_epoch_case_scores", [])
        )
        num_val_cases = len(dataset.val_cases) if hasattr(dataset, "val_cases") else 0
        # managed-doc 上下文交给 ReportFormatter（T12 消费：写 final/diff artifact +
        # 回填 report 字段）。before = baseline snapshot 内容；after = operator 已
        # committed 的本地状态；task_ids = applier records 中非空 task_id。
        managed_doc_before = managed_snapshot.content if managed_snapshot is not None else None
        managed_doc_after = (
            managed_operator.get_state().get("skill_content")
            if managed_operator is not None
            else None
        )
        managed_doc_records = (
            managed_operator.applier.records if managed_operator is not None else ()
        )
        managed_doc_task_ids = tuple(r.task_id for r in managed_doc_records if r.task_id)
        captured_managed_doc_contents = (
            trainer.managed_doc_epoch_contents(canonical_id)
            if is_managed_doc and canonical_id is not None
            else ()
        )
        if is_managed_doc and (
            len(captured_managed_doc_contents) != len(val_candidate_scores)
            or len(captured_managed_doc_contents) != len(gate_epoch_scores)
        ):
            raise ArtifactConsistencyError(
                "managed-doc candidate, validation score, and gate round counts differ"
            )
        managed_doc_epoch_contents = tuple(
            ManagedDocEpochContent(round=index, content=content)
            for index, content in enumerate(captured_managed_doc_contents, start=1)
        )
        report_skills = (canonical_id,) if is_managed_doc and canonical_id else resolved.skills
        return ReportFormatter(
            run_artifact_dir,
            skills=report_skills,
            val_per_epoch_scores=val_candidate_scores,
            val_score_before=val_score_before,
            num_val_cases=num_val_cases,
            val_baseline_case_scores=val_baseline_case_scores,
            val_per_epoch_case_scores=val_per_epoch_case_scores,
            managed_doc_kind=managed_doc_kind,
            managed_doc_content_before=managed_doc_before,
            managed_doc_content_after=managed_doc_after,
            managed_doc_epoch_contents=managed_doc_epoch_contents,
            managed_doc_task_ids=managed_doc_task_ids,
            managed_doc_records=managed_doc_records,
        ).format()


async def run_optimization_with_cancellation_recovery(
    request: OptimizeRequest,
    config: EvolveConfig,
    *,
    progress_callback: Any | None = None,
    phase_callback: Any | None = None,
    cancellation_token: CancellationToken | None = None,
) -> OptimizeReport:
    """Run optimization and own the complete cooperative-cancel recovery protocol.

    The interface deliberately hides baseline retention, Adapter construction,
    total-deadline accounting, rollback confirmation, and failure phase emission
    from HTTP callers.
    """
    token = cancellation_token or CancellationToken()
    verified_baseline: ManagedDocSnapshot | None = None

    def _remember_verified_baseline(snapshot: ManagedDocSnapshot) -> None:
        nonlocal verified_baseline
        verified_baseline = snapshot

    try:
        report = await run_optimization(
            request,
            config,
            progress_callback=progress_callback,
            phase_callback=phase_callback,
            cancellation_token=token,
            managed_doc_baseline_callback=_remember_verified_baseline,
        )
        # Close the race where cancellation arrives after the runner's last
        # cooperative safe point but before the wrapper publishes completion.
        token.raise_if_requested()
        return report
    except Exception as original:
        if not token.is_requested:
            raise
        try:
            if request.managed_doc_kind is not None:
                remaining = token.remaining_seconds(config.managed_doc_cancel_rollback_deadline)
                if remaining <= 0:
                    raise CancelRollbackError(
                        code="CANCEL_ROLLBACK_TIMEOUT",
                        diagnostics="total cancellation recovery deadline exhausted",
                    )
                baseline_revision = request.managed_doc_expected_revision or (
                    verified_baseline.file_revision if verified_baseline is not None else None
                )
                if baseline_revision is None:
                    raise CancelRollbackError(
                        code="CANCEL_ROLLBACK_FAILED",
                        diagnostics="verified baseline revision unavailable",
                    )
                async with AdapterClient(
                    request.adapter_url,
                    agent_name=request.agent_name,
                    timeout=config.remote_timeout,
                    max_retries=config.remote_max_retries,
                ) as rollback_client:
                    if verified_baseline is None:
                        try:
                            snapshot = await asyncio.wait_for(
                                asyncio.to_thread(
                                    rollback_client.get_managed_doc_sync,
                                    request.managed_doc_kind,
                                    request_timeout=remaining,
                                ),
                                timeout=remaining,
                            )
                            _validate_expected_managed_doc_revision(
                                snapshot,
                                agent_name=request.agent_name,
                                doc_kind=request.managed_doc_kind,
                                expected_revision=baseline_revision,
                            )
                        except TimeoutError as exc:
                            raise CancelRollbackError(
                                code="CANCEL_ROLLBACK_TIMEOUT",
                                diagnostics="baseline confirmation exceeded cancellation deadline",
                            ) from exc
                        except Exception as exc:
                            raise CancelRollbackError(
                                code="CANCEL_ROLLBACK_FAILED",
                                diagnostics=(
                                    f"{type(exc).__name__}: baseline confirmation unavailable"
                                ),
                            ) from exc
                    else:
                        await asyncio.to_thread(
                            rollback_managed_doc,
                            adapter_client=rollback_client,
                            doc_kind=request.managed_doc_kind,
                            baseline_content=verified_baseline.content,
                            baseline_revision=baseline_revision,
                            deadline=remaining,
                            phase_callback=phase_callback,
                        )
        except CancelRollbackError as rollback_error:
            if phase_callback is not None:
                phase_callback(
                    "log",
                    {
                        "level": "error",
                        "message": "Managed-doc rollback failed",
                        "phase": "rollback_failed",
                        "data": {"code": rollback_error.code},
                    },
                )
            raise
        raise CancellationRequestedError(
            "optimization cancelled and recovery completed"
        ) from original


async def _build_operators(
    skill_names: list[str],
    adapter_client: AdapterClient,
    *,
    num_parallel: int = 8,
    preserve_frontmatter: bool = True,
) -> dict[str, Any]:
    """为每个待优化 skill 获取内容并构建 operator（C5 / #25 并发拉取）。

    Parameters
    ----------
    skill_names:
        要优化的 skill 名称列表。
    adapter_client:
        AdapterClient 实例，用于获取 skill 内容和绑定回写 callback。
    num_parallel:
        并发拉取 skill 内容的最大并行度（默认 8，受 adapter HTTP 连接池约束）。
    preserve_frontmatter:
        透传给 ``build_skill_document_operator``：True（默认）冻结 frontmatter，
        False 让 frontmatter 全程参与优化。

    Returns
    -------
    dict[str, SkillDocumentOperator]
        skill_name → operator 映射。
    """
    if not skill_names:
        return {}

    sem = asyncio.Semaphore(min(max(num_parallel, 1), len(skill_names)))

    def _factory(n: str) -> Callable[[], Awaitable[tuple[str, Any]]]:
        async def _run() -> tuple[str, Any]:
            content = await adapter_client.skill_content(n)
            return n, build_skill_document_operator(
                n, content, adapter_client, preserve_frontmatter=preserve_frontmatter
            )

        return _run

    results = await gather_with_semaphore(sem, [_factory(n) for n in skill_names])
    operators: dict[str, Any] = {}
    for name, op in cast(list[tuple[str, Any]], results):
        operators[name] = op
    return operators


def _create_llm(config: EvolveConfig) -> Model:
    """根据 EvolveConfig 创建 LLM Model 实例。"""
    client_config = _build_model_client_config(config)
    model_config = ModelRequestConfig(model_name=config.optimizer_model)
    return Model(client_config, model_config)


def _bind_evaluator_invocation(evaluator: Any, invocation: LLMInvocation) -> None:
    """Bind the run-scoped invocation through evaluator decorators."""
    current = evaluator
    seen: set[int] = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        attributes = vars(current)
        if "_invocation" in attributes or hasattr(type(current), "_invocation"):
            current._invocation = invocation
        # ``getattr(MagicMock, name)`` manufactures an endless chain of child
        # mocks.  A decorator link is valid only when the object really stores it.
        current = attributes.get("_delegate")


def _build_model_client_config(config: EvolveConfig) -> ModelClientConfig:
    """按 ``llm_provider`` 分派 LLM client 配置（评估器与优化器共用）。

    - ``OpenAI``（默认）：走公网 OpenAI 兼容端点，行为零回归。
    - ``ICBC``：走客户内网 chat/completions 流式端点，凭证映射
      token→api_key、endpoint→api_base、userId→extra user_id、
      icbc_timeout→timeout（流式 read 超时）；

    两条路径读同一 ``llm_provider``，永不割裂。
    """
    if config.llm_provider == "ICBC":
        return ModelClientConfig(
            client_provider="ICBC",
            api_key=config.icbc_token,
            api_base=config.icbc_endpoint,
            user_id=config.icbc_user_id,  # extra: allow
            verify_ssl=False,  # ICBC 内网 http
            timeout=config.icbc_timeout,  # 流式 read 超时
            context_window_tokens=config.icbc_context_window_tokens,
            output_reserve_tokens=config.icbc_output_reserve_tokens,
            chars_per_token=config.icbc_chars_per_token,
            completion_signal=config.icbc_completion_signal,
        )
    return ModelClientConfig(
        client_provider="OpenAI",
        api_key=config.llm_api_key,
        api_base=config.llm_base_url,
        verify_ssl=False,
    )
