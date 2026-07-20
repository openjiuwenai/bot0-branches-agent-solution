"""EDPAgentOptimizer — edp_agent 场景 optimizer 子类。

覆写 _rollout，通过 Adapter sidecar 执行对话并收集轨迹。
"""

from __future__ import annotations

import asyncio
import difflib
import json
import logging
import shutil
from pathlib import Path
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.dataset.case import merge_extra_data
from evo_agent.evaluator.batch_result import (
    EvaluationBatchResult,
    EvaluationFailure,
    EvaluationOutcome,
)
from evo_agent.evaluator.trajectory.normalize import normalize_trace_to_trajectory
from evo_agent.optimizer import DictSkillDocumentOptimizer
from evo_agent.optimizer.skill_document.types import AttributedBatch
from evo_agent.scenario.prompts import load_prompt
from evo_agent.types import TrajectoryUnavailableError

logger = logging.getLogger(__name__)


def _copy_json_utf8(src: Path, dst: Path) -> None:
    """Copy a JSON file, rewriting with ensure_ascii=False for readable Chinese."""
    try:
        data = json.loads(src.read_text(encoding="utf-8"))
        dst.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    except (json.JSONDecodeError, ValueError):
        shutil.copy2(src, dst)


def _require_messages(trace_data: dict[str, Any]) -> list[dict[str, Any]]:
    """Extract messages from trace_data, raising TrajectoryUnavailableError if empty."""
    messages = trace_data.get("messages", [])
    if not messages:
        raise TrajectoryUnavailableError("trace_data has no messages")
    return messages


class EDPAgentOptimizer(DictSkillDocumentOptimizer):
    """edp_agent 场景 optimizer。

    通过 Adapter sidecar 执行对话（invoke），收集清洗后的轨迹（get_traces），
    直接以 dict 格式返回（对齐 adapter cleaned-traces messages 格式）。

    构造函数额外接收:
    - ``adapter_client``: AdapterClient 实例
    - ``operators``: dict[str, SkillDocumentOperator]
    - ``conversation_id_factory``: ConversationIdFactory 实例
    - ``trace_max_retries``: 轨迹拉取最大重试次数
    - ``trace_retry_backoff``: 重试退避间隔（秒）
    - ``phase_callback``: SSE 阶段事件推送回调（Wave 10）
    """

    def __init__(
        self,
        *,
        adapter_client: Any = None,
        operators: dict[str, Any] | None = None,
        rollout_extra_data: dict[str, Any] | None = None,
        conversation_id_factory: Any = None,
        trace_max_retries: int = 3,
        trace_retry_backoff: float = 1.0,
        phase_callback: Any | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self._adapter_client = adapter_client
        self._operators = operators or {}
        self._rollout_extra_data = rollout_extra_data or {}
        self._conversation_id_factory = conversation_id_factory
        self._trace_max_retries = trace_max_retries
        self._trace_retry_backoff = trace_retry_backoff
        self._phase_callback = phase_callback

    def _push_phase(self, event: str, data: dict[str, Any]) -> None:
        """推送 phase 事件，失败时 catch + log，不中断优化流程。

        ``data['epoch']`` 统一转为 1-based：edp 内部用 0-based
        ``_artifact_epoch``（初始 -1，首个 epoch = 0），而 ProgressCallback
        的 epoch_begin/epoch_end 事件用 vendor ``progress.current_epoch``
        （1-based）。两端对齐后客户端才能把同一 epoch 的事件串起来。
        """
        if isinstance(data.get("epoch"), int):
            data = {**data, "epoch": data["epoch"] + 1}
        if self._phase_callback is None:
            logger.debug("phase_callback not set, skipping event=%s", event)
            return
        try:
            logger.debug("pushing phase event=%s data=%s", event, data)
            self._phase_callback(event, data)
        except Exception:
            logger.warning("phase_callback push failed", exc_info=True)

    def _on_step_apply(self, step: int, n_edits: int, n_operators: int) -> None:
        """Push per-step apply SSE event.

        Fires once per step (called from the base ``_backward`` step loop)
        with that step's applied edits, so multi-step epochs report every
        step — earlier steps' edits are no longer lost when the last step
        produces zero edits.
        """
        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"编辑已应用：{n_edits} 个编辑写入 {n_operators} 个 skill",
                "phase": "apply",
                "epoch": self._artifact_epoch,
                "data": {
                    "n_operators": n_operators,
                    "n_edits": n_edits,
                    "step": step,
                },
            },
        )

    async def _get_required_trace(self, conversation_id: str) -> dict[str, Any]:
        """Fetch trace data with retry + backoff.

        Raises TrajectoryUnavailableError if no valid traces after max retries.
        """
        for attempt in range(self._trace_max_retries):
            trace_data = await self._adapter_client.get_traces(
                case_id=conversation_id,
            )
            try:
                _require_messages(trace_data)
                return trace_data
            except TrajectoryUnavailableError:
                if attempt < self._trace_max_retries - 1:
                    await asyncio.sleep(self._trace_retry_backoff)
        raise TrajectoryUnavailableError(
            f"No valid traces after {self._trace_max_retries} attempts "
            f"for conversation_id={conversation_id}"
        )

    @staticmethod
    def _extract_evaluator_attributions(eval_case: EvaluatedCase) -> list[dict]:
        """Parse attribution from eval_case.reason JSON.

        Supports two formats:
        - New flat format: ``attributed_skill`` (single string)
        - Legacy nested format: ``skill_attributions`` (list of dicts)

        Returns:
            list[dict]: Each dict contains {skill_name}.
            Returns empty list on parse failure or empty attribution.
        """
        if not eval_case.reason:
            return []
        try:
            reason_data = json.loads(eval_case.reason)
        except (json.JSONDecodeError, AttributeError, TypeError):
            return []

        # New flat format: attributed_skill is a single string
        attributed_skill = reason_data.get("attributed_skill")
        if isinstance(attributed_skill, str) and attributed_skill:
            return [{"skill_name": attributed_skill}]

        # Legacy nested format: skill_attributions is a list of dicts
        skill_attributions = reason_data.get("skill_attributions")
        if isinstance(skill_attributions, list):
            return skill_attributions

        return []

    @staticmethod
    def _match_operator_from_attribution(
        attribution_skill_name: str,
        valid_op_ids: list[str],
    ) -> str | None:
        """将评估器的 skill_name 匹配到 operator_id。

        匹配规则（优先级递减）：
        1. 精确匹配：skill_name == operator_id
        2. 前缀匹配：一方以另一方开头（最小长度 >= 较长方 50%）
        3. 归一化匹配：去掉 _skill 后缀后精确比较

        Returns:
            匹配的 operator_id，或 None。
        """
        # 1. 精确匹配
        for op_id in valid_op_ids:
            if attribution_skill_name == op_id:
                return op_id

        # 2. 前缀匹配
        for op_id in valid_op_ids:
            longer = max(len(attribution_skill_name), len(op_id))
            shorter = min(len(attribution_skill_name), len(op_id))
            if shorter < longer * 0.5:
                continue
            if attribution_skill_name.startswith(op_id) or op_id.startswith(attribution_skill_name):
                return op_id

        # 3. 归一化匹配（去掉 _skill 后缀）
        norm_name = attribution_skill_name.lower().removesuffix("_skill")
        for op_id in valid_op_ids:
            norm_op = op_id.lower().removesuffix("_skill")
            if norm_name == norm_op:
                return op_id

        return None

    # ── Per-skill artifact directory ─────────────────────────────────

    async def _reflect(
        self,
        formatted_batch: str,
        skill_content: str,
        score_threshold: float,
        batch_data: list[tuple[dict, EvaluatedCase, Case]] | None = None,
        operator_id: str = "",
        **kwargs: Any,
    ) -> Any:
        """Override to push reflect phase event."""
        result = await super()._reflect(
            formatted_batch=formatted_batch,
            skill_content=skill_content,
            score_threshold=score_threshold,
            batch_data=batch_data,
            operator_id=operator_id,
            **kwargs,
        )
        n_patches = len(result) if isinstance(result, list) else 0
        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"Reflect 完成（{operator_id}）：生成 {n_patches} 个补丁",
                "phase": "reflect",
                "epoch": self._artifact_epoch,
                "data": {"operator_id": operator_id, "n_patches": n_patches},
            },
        )
        return result

    async def _aggregate(
        self,
        patches: list,
        skill_content: str,
        **kwargs: Any,
    ) -> Any:
        """Override to push aggregate phase event."""
        result = await super()._aggregate(patches=patches, skill_content=skill_content, **kwargs)
        n_input = len(patches)
        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"Aggregate 完成：{n_input} 个补丁合并",
                "phase": "aggregate",
                "epoch": self._artifact_epoch,
                "data": {"n_input": n_input},
            },
        )
        return result

    async def _select(
        self,
        edits: list,
        budget: int,
        skill_content: str,
        **kwargs: Any,
    ) -> Any:
        """Override to push select phase event."""
        result = await super()._select(
            edits=edits, budget=budget, skill_content=skill_content, **kwargs
        )
        n_selected = len(result) if isinstance(result, list) else 0
        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"Select 完成：选出 {n_selected} 个编辑（预算 {budget}）",
                "phase": "select",
                "epoch": self._artifact_epoch,
                "data": {"n_candidates": len(edits), "n_selected": n_selected, "budget": budget},
            },
        )
        return result

    async def _backward(self, signals: list[Any]) -> None:
        """Override to reorganize artifacts into per-skill subdirectories.

        After the vendor's ``_backward()`` writes all artifacts to
        ``<run_id>/epoch_N/step_M/``, this method moves them into
        ``<run_id>/<skill_name>/epoch_N/step_M/`` for each operator.

        The per-step ``apply`` SSE event is pushed by ``_on_step_apply``
        (called from the base ``_backward`` step loop), so each step's edits
        are reported — not just the last step's.
        """
        # Capture before-state for per-operator snapshots
        before_by_op: dict[str, str] = {}
        if len(self._operators) > 1:
            before_by_op = {
                op_id: op.get_state().get("skill_content", "")
                for op_id, op in self._operators.items()
            }

        await super()._backward(signals)

        if len(self._operators) <= 1 or not self._artifact_exporter.enabled:
            logger.info(
                "Per-skill reorganize skipped: operators=%d, exporter_enabled=%s",
                len(self._operators),
                self._artifact_exporter.enabled,
            )
            return

        logger.info(
            "Per-skill reorganize starting: epoch=%d, operators=%s",
            self._artifact_epoch,
            list(self._operators.keys()),
        )
        self._reorganize_artifacts_per_skill(before_by_op)

    def _reorganize_artifacts_per_skill(
        self,
        before_by_op: dict[str, str],
    ) -> None:
        """Move per-skill artifacts into skill subdirectories.

        Shared files (eval_results.json, trajectories.jsonl, etc.) stay at
        ``<run_id>/epoch_N/step_M/``.  Only per-skill files (selected_edits,
        merged_patch, skill snapshots) go into ``<run_id>/<skill_name>/``.
        """
        run_dir = self._artifact_exporter._output_dir
        epoch = self._artifact_epoch
        logger.info("Reorganizing artifacts: run_dir=%s, epoch=%d", run_dir, epoch)

        for op_id in self._operators:
            skill_epoch_dir = run_dir / op_id / f"epoch_{epoch}"
            skill_epoch_dir.mkdir(parents=True, exist_ok=True)

            # 1. Per-operator skill snapshots and diff
            self._write_per_operator_snapshots(skill_epoch_dir, op_id, before_by_op)

            # 2. Reorganize step directories (per-skill files only)
            for step in range(self._steps_per_epoch):
                self._reorganize_step_dir(run_dir, op_id, epoch, step)

        # Note: flat epoch_N/ dir is kept — it holds shared files
        # (eval_results.json, trajectories.jsonl, gate_result.json, etc.)
        # A3: ArtifactExporter 已用 ensure_ascii=False 写入，无需原地重写中文。

    def _write_per_operator_snapshots(
        self,
        skill_epoch_dir: Path,
        op_id: str,
        before_by_op: dict[str, str],
    ) -> None:
        """Write skill_before.md, skill_after.md, applied_diff.patch."""
        before_content = before_by_op.get(op_id, "")
        after_content = self._current_skill_by_operator.get(op_id, "")

        (skill_epoch_dir / "skill_before.md").write_text(
            before_content,
            encoding="utf-8",
        )
        (skill_epoch_dir / "skill_after.md").write_text(
            after_content,
            encoding="utf-8",
        )

        diff = difflib.unified_diff(
            before_content.splitlines(keepends=True),
            after_content.splitlines(keepends=True),
            fromfile="skill_before",
            tofile="skill_after",
        )
        (skill_epoch_dir / "applied_diff.patch").write_text(
            "".join(diff),
            encoding="utf-8",
        )

    def _reorganize_step_dir(
        self,
        run_dir: Path,
        op_id: str,
        epoch: int,
        step: int,
    ) -> None:
        """Move per-skill step artifacts into skill subdirectory.

        Shared files (trajectories.jsonl, eval_results.json, etc.) stay in
        the flat ``epoch_N/step_M/`` directory.
        """
        src_step = run_dir / f"epoch_{epoch}" / f"step_{step}"
        if not src_step.exists():
            return

        dst_step = run_dir / op_id / f"epoch_{epoch}" / f"step_{step}"
        dst_step.mkdir(parents=True, exist_ok=True)

        # Per-operator files — move to skill dir (rename without suffix, fix unicode)
        for prefix, ext in (
            ("selected_edits", ".json"),
            ("merged_patch", ".json"),
        ):
            src = src_step / f"{prefix}_{op_id}{ext}"
            if src.exists():
                _copy_json_utf8(src, dst_step / f"{prefix}{ext}")
                src.unlink()

        # Per-operator applied_diff — move
        diff_src = src_step / f"applied_diff_{op_id}.patch"
        diff_dst = dst_step / "applied_diff.patch"
        if diff_src.exists():
            shutil.move(str(diff_src), str(diff_dst))

    async def _attribute(
        self,
        *,
        failure_batch: list[tuple[dict, EvaluatedCase, Case]],
        success_batch: list[tuple[dict, EvaluatedCase, Case]],
        skill_contents: dict[str, str],
    ) -> dict[str, AttributedBatch]:
        """基于评估器语义归因的归因分配。

        策略：
        1. 单 operator: short-circuit,读 attributed_skill 过滤 badcase(非 skill 失败丢),
           goodcase 全量保留(F1)。
        2. 多 operator:
           a. badcase: 从 eval_case.reason 提取 attributed_skill,匹配 operator;
              无归因或匹配失败 → 丢弃(不 fallback,不兜底)。
           b. goodcase: 评估器不归因(is_pass=true 设计如此)→ 全量兜底到所有 operator(F2)。
        """
        op_ids = list(skill_contents.keys())

        # 单 operator short-circuit（F1）：badcase 按 attributed_skill 过滤
        if len(op_ids) == 1:
            sole_op = op_ids[0]
            filtered_failures = [
                item for item in failure_batch if self._single_skill_badcase_match(item[1], sole_op)
            ]
            successes = list(success_batch)
            result_sc: dict[str, AttributedBatch] = {}
            if filtered_failures or successes:
                result_sc[sole_op] = AttributedBatch(
                    operator_id=sole_op,
                    failures=filtered_failures,
                    successes=successes,
                )
            self._push_phase(
                "log",
                {
                    "level": "info",
                    "message": (
                        f"归因完成：{len(filtered_failures)} 个失败 / "
                        f"{len(successes)} 个成功 → {sole_op}"
                    ),
                    "phase": "attribute",
                    "epoch": self._artifact_epoch,
                    "data": {
                        "n_failures": len(filtered_failures),
                        "n_successes": len(successes),
                        "attributed_operators": [sole_op] if result_sc else [],
                    },
                },
            )
            return result_sc

        # 初始化每个 operator 的归因批次
        attr_failures: dict[str, list] = {op: [] for op in op_ids}
        attr_successes: dict[str, list] = {op: [] for op in op_ids}

        # 处理 failure_batch（F2：badcase 无归因 → _attribute_single_case 返回 [] → 丢，不兜底）
        for item in failure_batch:
            trajectory, eval_case, case = item
            matched_ops = self._attribute_single_case(eval_case, trajectory, op_ids)
            for op in matched_ops:
                attr_failures[op].append(item)

        # 处理 success_batch（F2：goodcase 无归因 → 全量兜底到所有 operator；有归因 → 精准分）
        for item in success_batch:
            trajectory, eval_case, case = item
            matched_ops = self._attribute_single_case(eval_case, trajectory, op_ids)
            if matched_ops:
                for op in matched_ops:
                    attr_successes[op].append(item)
            else:
                # goodcase 无归因（评估 prompt 设计如此）→ 全量兜底
                for op in op_ids:
                    attr_successes[op].append(item)

        # 构建结果，prune 空批次
        result: dict[str, AttributedBatch] = {}
        for op in op_ids:
            if attr_failures[op] or attr_successes[op]:
                result[op] = AttributedBatch(
                    operator_id=op,
                    failures=attr_failures[op],
                    successes=attr_successes[op],
                )

        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"归因完成：{len(result)} 个 operator 收到归因",
                "phase": "attribute",
                "epoch": self._artifact_epoch,
                "data": {
                    "n_failures": len(failure_batch),
                    "n_successes": len(success_batch),
                    "attributed_operators": list(result.keys()),
                },
            },
        )

        return result

    def _single_skill_badcase_match(self, eval_case: EvaluatedCase, sole_op: str) -> bool:
        """单 skill 场景 badcase 是否归因到唯一 operator（F1）。

        评估器填了 attributed_skill 且能匹配 sole_op → 保留；留空(非 skill 失败)
        或归因到不存在的 skill → 丢(保守)。复用 _extract_evaluator_attributions
        + _match_operator_from_attribution。
        """
        eval_attributions = self._extract_evaluator_attributions(eval_case)
        for attr in eval_attributions:
            skill_name = attr.get("skill_name", "")
            if self._match_operator_from_attribution(skill_name, [sole_op]):
                return True
        return False

    def _attribute_single_case(
        self,
        eval_case: EvaluatedCase,
        trajectory: dict,
        valid_op_ids: list[str],
    ) -> list[str]:
        """对单个 case 执行归因，返回匹配的 operator_id 列表。

        仅使用评估器的语义归因。未归因的 case 直接跳过，不做 fallback。
        """
        eval_attributions = self._extract_evaluator_attributions(eval_case)
        if not eval_attributions:
            logger.info("Attribution: skipped (no skill_attributions from evaluator)")
            return []

        matched: list[str] = []
        for attr in eval_attributions:
            skill_name = attr.get("skill_name", "")
            op_id = self._match_operator_from_attribution(skill_name, valid_op_ids)
            if op_id and op_id not in matched:
                matched.append(op_id)

        if matched:
            logger.info(
                "Attribution (evaluator) for case: operators=%s",
                matched,
            )
        else:
            logger.info("Attribution: skipped (evaluator attributions did not match any operator)")
        return matched

    # ── Prompt override ─────────────────────────────────────────────────

    _SCENARIO_NAME = "edp_agent"

    def _build_analyst_prompt(
        self,
        template_name: str,
        skill_content: str,
        trajectories_text: str,
        step_buffer_context: str,
        meta_skill_context: str,
        *,
        slim: bool = False,
    ) -> str:
        """覆写 vendor 的 prompt 加载，使用项目两级查找机制。

        vendor whl 未打包 skill_document/templates/ 目录，
        导致 load_skill_opt_prompt() 始终 FileNotFoundError。
        改用项目的 load_prompt()，优先加载场景级 prompt 覆盖。

        ``slim=True`` 时省略 step_buffer / meta_skill，用作超时重试精简 prompt（B4）。

        managed-doc 模式（spec F10）：当唯一 target id 以 ``managed_doc:`` 开头时，
        加入 agent-rule 语义段并把 ``Current Skill`` 标题改为 ``Current Agent Rule
        Document``。``_llm_merge_edits`` / ``meta_skill`` 仍用基类 Skill 措辞，不改。
        """
        system = load_prompt(template_name, self._SCENARIO_NAME)
        is_managed_doc = self._is_managed_doc_target()
        user = ""
        if is_managed_doc:
            user += (
                "Target type: agent runtime rule document\n"
                "Scope: applies globally to every conversation\n"
                "Constraints: preserve identity, safety, tool-policy "
                "and mandatory business rules\n\n"
            )
        title = "Current Agent Rule Document" if is_managed_doc else "Current Skill"
        user += f"## {title}\n{skill_content}\n\n"
        user += f"## Edits Budget\nProduce at most L={self._scheduler.max_lr} edits.\n\n"
        if not slim and step_buffer_context.strip():
            user += f"## Previous Steps in This Epoch\n{step_buffer_context}\n\n"
        if not slim and meta_skill_context.strip():
            user += f"## Optimizer Memory\n{meta_skill_context}\n\n"

        if "error" in template_name:
            user += f"## Failed Trajectories\n{trajectories_text}"
        else:
            user += f"## Successful Trajectories\n{trajectories_text}"

        return f"{system}\n\n{user}"

    def _is_managed_doc_target(self) -> bool:
        """唯一 target id 以 ``managed_doc:`` 开头 → managed-doc 单文档优化模式。

        单 operator short-circuit 下，sole operator id 即 canonical id
        ``managed_doc:{kind}``；多 operator 永远不是 managed-doc 模式（本期不支持
        混合优化）。
        """
        op_ids = list(self._operators.keys())
        return len(op_ids) == 1 and op_ids[0].startswith("managed_doc:")

    # ── Rollout ─────────────────────────────────────────────────────────

    async def _rollout(
        self,
        cases: list[Case],
    ) -> tuple[list[EvaluatedCase], list[dict]]:
        """通过 Adapter sidecar 并行执行对话并收集轨迹。

        Phase 1 (parallel): 对每个 case 并行调用 invoke + fetch trace，
        受 semaphore 限制并发数（``self._num_parallel``）。
        Phase 2 (sequential): 对每个 case 评估（evaluator 内部 LLM 调用为同步）。

        将场景级 ``rollout_extra_data`` 与 case 级 ``extra_data`` 合并后
        传入 invoke payload，确保业务 Agent 收到完整上下文（如 role_id）。

        使用 ConversationIdFactory 生成唯一 conversation_id，
        构造 case copy 注入 trajectory（不原地修改 case.inputs），
        训练阶段轨迹缺失时跳过该 case 并记录 warning。

        返回的轨迹为 dict 格式（对齐 adapter cleaned-traces）：
        ``{"case_id": str, "messages": [{"role": ..., "content": ...}, ...]}``
        """
        if not cases:
            return [], []

        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"Rollout 开始：{len(cases)} 个 case",
                "phase": "rollout",
                "epoch": self._artifact_epoch,
                "data": {"n_cases": len(cases)},
            },
        )

        sem = asyncio.Semaphore(min(self._num_parallel, len(cases)))

        async def _rollout_one(case: Case) -> tuple[str, Any, dict | None, str]:
            """Single case rollout: invoke → trace → return intermediate data.

            Returns (case_id, answer, trace_data_or_None, conversation_id).
            """
            async with sem:
                case_extra = case.inputs.get("extra_data", {})
                extra = merge_extra_data(self._rollout_extra_data, case_extra)

                # 1. 生成唯一 conversation_id
                if self._conversation_id_factory:
                    conversation_id = self._conversation_id_factory.new(
                        phase="train", case_id=case.case_id
                    )
                else:
                    logger.warning(
                        "No ConversationIdFactory injected — falling back to case.case_id "
                        "(risk of stale trajectory reads)"
                    )
                    conversation_id = case.case_id

                # 2. 使用唯一 conversation_id 调用 agent
                try:
                    result = await self._agent.invoke(
                        {**case.inputs, "conversation_id": conversation_id, "extra_data": extra},
                    )
                except Exception as exc:
                    logger.warning(
                        "Training invoke failed for case=%s conversation_id=%s: %s",
                        case.case_id,
                        conversation_id,
                        exc,
                    )
                    result = {"answer": "", "error": str(exc)}

                answer = result if isinstance(result, dict) else {"answer": str(result)}

                # 3. 拉取轨迹（带 retry）
                try:
                    trace_data = await self._get_required_trace(conversation_id)
                except TrajectoryUnavailableError as exc:
                    logger.warning("Skipping case %s: %s", case.case_id, exc)
                    trace_data = None

                return case.case_id, answer, trace_data, conversation_id

        # Phase 1: parallel invoke + trace fetch (order preserved by gather)
        results = await asyncio.gather(*[_rollout_one(c) for c in cases])

        # Phase 2: evaluate concurrently and build output (C1 / #1)
        # 先收集 eval_cases + answers + 轨迹，再用 batch_evaluate 并发评估，
        # 通过 case_id 配对保持 evaluated_list 与 trajectories 1:1 对齐。
        eval_cases: list[Case] = []
        answers: list[dict[str, Any]] = []
        traj_by_case_id: dict[str, dict[str, Any]] = {}
        eligible_indexes: list[int] = []
        infrastructure_outcomes: dict[int, EvaluationOutcome] = {}

        for index, (case, (case_id, answer, trace_data, _conv_id)) in enumerate(
            zip(cases, results)
        ):
            if trace_data is None:
                # 排除：基础设施故障，不是 skill 质量问题（与验证集行为对齐）
                logger.warning("训练 trace 不可用，排除 case %s（不计入评分）", case_id)
                category = "rollout_error" if answer.get("error") else "trace_unavailable"
                infrastructure_outcomes[index] = EvaluationOutcome(
                    index=index,
                    case_id=case_id,
                    case=case,
                    trajectory=None,
                    evaluated=None,
                    failure=EvaluationFailure(
                        category=category,
                        safe_message=f"Training infrastructure failed for case {case_id}",
                    ),
                )
                continue

            # 4. 规范化轨迹并构造带 trajectory 的 case copy
            #    deep=False：inputs 是新建 dict（{**case.inputs, ...}），已隔离。
            trajectory_dict = normalize_trace_to_trajectory(trace_data)
            case_for_eval = case.model_copy(
                update={
                    "inputs": {
                        **case.inputs,
                        "trajectory": trajectory_dict,
                        "skill_names": list(self._operators.keys()),
                    }
                },
                deep=False,
            )

            eval_cases.append(case_for_eval)
            answers.append(answer if isinstance(answer, dict) else {"answer": str(answer)})
            messages = trace_data.get("messages", [])
            traj_by_case_id[case_id] = {"case_id": case_id, "messages": messages}
            eligible_indexes.append(index)

        # 5. 并发评估并保留每个 evaluator failure outcome。
        eligible_trajectories = [traj_by_case_id[case.case_id] for case in eval_cases]
        evaluated_batch = (
            self._evaluate_training_batch(
                eval_cases,
                answers,
                eligible_trajectories,
                enable_attribution=True,
            )
            if eval_cases
            else EvaluationBatchResult(())
        )
        outcomes_by_index = dict(infrastructure_outcomes)
        for original_index, outcome in zip(eligible_indexes, evaluated_batch.outcomes):
            outcomes_by_index[original_index] = EvaluationOutcome(
                index=original_index,
                case_id=outcome.case_id,
                case=outcome.case,
                trajectory=outcome.trajectory,
                evaluated=outcome.evaluated,
                failure=outcome.failure,
            )
        self._last_training_batch = EvaluationBatchResult(
            tuple(outcomes_by_index[index] for index in range(len(cases)))
        )
        evaluated_list = list(self._last_training_batch.successes)

        # 6. 按 case_id 配对轨迹（batch_evaluate 可能排除评估失败的 case）
        trajectories: list[dict[str, Any]] = [
            traj_by_case_id[ec.case_id] for ec in evaluated_list if ec.case_id in traj_by_case_id
        ]

        # Push evaluate phase event
        threshold = getattr(self, "_score_threshold", 0.5)
        n_failures = sum(1 for ec in evaluated_list if ec.score < threshold)
        n_successes = len(evaluated_list) - n_failures
        failure_rate = n_failures / len(evaluated_list) if evaluated_list else 0.0
        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"评估完成：{n_failures} 个失败 / {n_successes} 个成功",
                "phase": "evaluate",
                "epoch": self._artifact_epoch,
                "data": {
                    "n_failures": n_failures,
                    "n_successes": n_successes,
                    "failure_rate": round(failure_rate, 3),
                },
            },
        )

        # Push rollout_done phase event
        avg_score = (
            sum(ec.score for ec in evaluated_list) / len(evaluated_list) if evaluated_list else 0.0
        )
        self._push_phase(
            "log",
            {
                "level": "info",
                "message": f"Rollout 完成：平均分数 {avg_score:.3f}",
                "phase": "rollout_done",
                "epoch": self._artifact_epoch,
                "data": {"avg_score": round(avg_score, 3), "n_cases": len(evaluated_list)},
            },
        )

        return evaluated_list, trajectories
