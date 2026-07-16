"""最小编排入口 — OptimizeRequest → 一次可运行的优化任务。

该模块是 EvoAgent 中唯一允许知道 Trainer、Operator、Optimizer、Callbacks
如何组装的地方。
"""

from __future__ import annotations

import asyncio
import json
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any, cast

import structlog
from openjiuwen.agent_evolving.updater.single_dim import SingleDimUpdater
from openjiuwen.core.foundation.llm.model import Model
from openjiuwen.core.foundation.llm.schema.config import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.single_agent import AgentCard

from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.operator import build_skill_document_operator
from evo_agent.adapter_client.remote_agent import RemoteAgent
from evo_agent.callbacks import build_callbacks
from evo_agent.config import EvolveConfig
from evo_agent.conversation import ConversationIdFactory
from evo_agent.dataset.manifest import build_dataset_from_request, load_dataset_manifest
from evo_agent.optimizer.concurrency import gather_with_semaphore
from evo_agent.reporter.formatter import ReportFormatter
from evo_agent.runtime_config import OptimizationConfigResolver
from evo_agent.scenario.registry import ScenarioRegistry
from evo_agent.trainer import EvoTrainer
from evo_agent.types import OptimizeReport, OptimizeRequest


async def run_optimization(
    request: OptimizeRequest,
    config: EvolveConfig,
    *,
    progress_callback: Any | None = None,
    phase_callback: Any | None = None,
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
    structlog.get_logger().debug(
        "run_optimization started",
        scenario=request.scenario,
        skills=request.skills,
    )
    registry = ScenarioRegistry()
    resolved = OptimizationConfigResolver(config, registry=registry).resolve(request)
    # 0. 注入 structlog contextvars
    run_id = uuid.uuid4().hex[:12]
    structlog.contextvars.bind_contextvars(
        run_id=run_id,
        scenario=resolved.scenario,
        agent_name=resolved.agent_name,
    )

    # 0.0 推送 pipeline 开始事件（让前端立即看到活动）
    if phase_callback is not None:
        phase_callback(
            "log",
            {
                "level": "info",
                "message": (
                    f"优化 Pipeline 启动：scenario={resolved.scenario}, "
                    f"skills={list(resolved.skills)}"
                ),
                "phase": "pipeline_start",
            },
        )

    # 0.1 构建 ConversationIdFactory
    conversation_id_factory = ConversationIdFactory(run_id=run_id)

    # 1. 构建 AdapterClient
    async with AdapterClient(
        resolved.adapter_url,
        agent_name=resolved.agent_name,
        timeout=config.remote_timeout,
        max_retries=config.remote_max_retries,
    ) as adapter_client:
        # 1.5 优化前恢复 skill 到初始状态（幂等，未修改过的 skill 不受影响）
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
            list(resolved.skills), adapter_client, preserve_frontmatter=config.preserve_frontmatter
        )

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

        # 6. 构建依赖
        run_artifact_dir = config.artifact_dir / run_id
        dependencies: dict[str, Any] = {
            "agent": remote_agent,
            "evaluator": dataset.evaluator,
            "llm": llm,
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
        }

        # 7. 通过 ScenarioRegistry 构建场景 optimizer
        optimizer = registry.build_optimizer(request, dependencies)

        # 8. 组装 EvoTrainer pipeline
        updater = SingleDimUpdater(optimizer=optimizer)
        callbacks = build_callbacks(optimizer, progress_callback=progress_callback)
        trainer = EvoTrainer(
            adapter_client=adapter_client,
            conversation_id_factory=conversation_id_factory,
            skill_names=list(resolved.skills),
            rollout_extra_data=resolved.rollout_extra_data,
            updater=updater,
            evaluator=dataset.evaluator,
            callbacks=callbacks,
            num_parallel=resolved.num_parallel,
            trace_max_retries=resolved.trace_max_retries,
            trace_retry_backoff=resolved.trace_retry_backoff,
            tie_reval_eps=resolved.tie_reval_eps,
            early_stop_score=1.01,  # prevent early stop at perfect 1.0
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
        await asyncio.to_thread(
            trainer.train,
            agent=remote_agent,
            train_cases=dataset.train_cases,
            val_cases=dataset.val_cases,
            num_iterations=resolved.num_epochs,
        )

        # 9.5 回填 gate_result.json 中缺失的 base/candidate 分数
        _rewrite_gate_results(run_artifact_dir, trainer.gate_epoch_scores)

        # 10. 汇总 artifact
        # 报告趋势图用候选 fresh eval 分数（每轮真实评估，会波动）；门控赢家
        # （val_per_epoch_scores，单调非降）仅 ProgressCallback 内部用于 improved 判定。
        val_candidate_scores = (
            tuple(progress_callback.candidate_per_epoch_scores) if progress_callback else ()
        )
        val_score_before = progress_callback.val_score_before if progress_callback else 0.0
        val_per_epoch_case_scores = (
            list(progress_callback.val_per_epoch_case_scores) if progress_callback else []
        )
        num_val_cases = len(dataset.val_cases) if hasattr(dataset, "val_cases") else 0
        return ReportFormatter(
            run_artifact_dir,
            skills=resolved.skills,
            val_per_epoch_scores=val_candidate_scores,
            val_score_before=val_score_before,
            num_val_cases=num_val_cases,
            val_baseline_case_scores=val_baseline_case_scores,
            val_per_epoch_case_scores=val_per_epoch_case_scores,
        ).format()


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
        )
    return ModelClientConfig(
        client_provider="OpenAI",
        api_key=config.llm_api_key,
        api_base=config.llm_base_url,
        verify_ssl=False,
        # DashScope 直连；SKILL.md 变体生成可能超过默认 60s
        timeout=300.0,
        max_retries=2,
    )


def _rewrite_gate_results(
    artifact_dir: Path,
    gate_scores: list[dict[str, float]],
) -> None:
    """Rewrite gate_result.json files with both base and candidate scores.

    The upstream optimizer only records the winner's score in gate_result.json,
    leaving the loser as ``null``. This post-processing step fills in both
    scores from EvoTrainer's captured gate data and computes ``improvement``.

    Parameters
    ----------
    artifact_dir:
        Run-level artifact directory (e.g. ``artifacts/abc123``).
    gate_scores:
        Per-epoch scores from ``EvoTrainer.gate_epoch_scores``.
        Index ``i`` → Trainer epoch ``i+1`` → ``epoch_{i+1}/gate_result.json``.
    """
    for i, scores in enumerate(gate_scores):
        trainer_epoch = i + 1
        gate_path = artifact_dir / f"epoch_{trainer_epoch}" / "gate_result.json"
        if not gate_path.exists():
            continue

        with gate_path.open(encoding="utf-8") as f:
            data = json.load(f)

        data["base_score"] = scores["base_score"]
        data["candidate_score"] = scores["candidate_score"]
        data["improvement"] = scores["candidate_score"] - scores["base_score"]

        gate_path.write_text(
            json.dumps(data, indent=2, ensure_ascii=False, default=str),
            encoding="utf-8",
        )
