"""在线产 expected_behavior（EB）—— phase2 实现。

port 自 bank bundle ``golden_gen/run.py`` 的 ``generate_golden``，在 evo_agent 栈上
重写（见记忆 [[expected-behavior-generator-methodology]]）：

- 载入持久化 GU（``gu_store.load_index``）；progressive 经 ``gu_store.route_skill``
  路由取相关 per_skill sub + system_wide 组成 ``GUSlice``；flat 整份 GU 灌
  ``GUSlice.system_wide``。``EBInput.gu_slice`` 非空（外部预路由）时直接复用，否则内部
  路由。
- 灌 phase2 prompt（context_block + 全量 history + 顾客输入）→
  ``Model.invoke([SystemMessage, UserMessage])`` → 解析 → ``ExpectedBehaviorOutput``
  （``to_external`` 给对外口径 ``{id, inputs, expected_behavior}``）。

与 golden_gen 的栈差异：
- 一条 trace 产一条 ``ExpectedBehaviorItem``（golden_gen 一条 rec）。
- 无 ``script_id`` / 评估器 ``score_ref``（EBInput 无 score 字段）→ 不注入 score_section；
  ``item.id`` 用 ``eb_<uuid>``（evo_agent 无 conv_id 稳定标识）。
- 无 skill_dir frontmatter 触发词解析 → context_block 只灌 GUSlice（system_wide +
  per_skill），不灌 boundary（下一步可加）。
"""

from __future__ import annotations

import logging
import uuid
from pathlib import Path
from typing import cast

from openjiuwen.core.foundation.llm import (
    Model,
    ModelClientConfig,
    ModelRequestConfig,
    SystemMessage,
    UserMessage,
)

from evo_agent.config import EvolveConfig
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.llm.invocation import _get_invocation_loop
from evo_agent.evaluator.golden_data.gu_store import (
    OUT_OF_SCOPE_SKILL,
    load_flat,
    load_index,
    load_out_of_scope,
    load_skill_doc,
    load_system_wide,
    route_skill,
)
from evo_agent.evaluator.golden_data.models import (
    EBInput,
    EBResult,
    ExpectedBehaviorItem,
    ExpectedBehaviorOutput,
    GUSlice,
)
from evo_agent.evaluator.golden_data.prompts.phase2_eb import SYSTEM_PROMPT_PHASE2
from evo_agent.evaluator.golden_data.trajectory_format import (
    _extract_customer_inputs,
    _format_history_rich,
)

logger = logging.getLogger(__name__)

__all__ = ["ExpectedBehaviorGenerator"]

_LLM_MAX_RETRIES = 3
_VALID_RESULTS = {"通过", "部分通过", "失败", "NA"}


class ExpectedBehaviorGenerator:
    """在线产 EB（phase2）。

    Parameters
    ----------
    model_config, model_client_config:
        EB 生成用 LLM 配置（照 ``TrajectoryGoalGenerator``）。
    gu_root:
        GU 持久化知识库根（载入点）；缺省 ``EvolveConfig.get().golden_data_dir
        / "global_understanding"``。
    """

    def __init__(
        self,
        model_config: ModelRequestConfig,
        model_client_config: ModelClientConfig,
        gu_root: Path | None = None,
    ) -> None:
        self._model = Model(model_client_config, model_config)
        self._gu_root = (
            gu_root
            if gu_root is not None
            else (EvolveConfig.get().golden_data_dir / "global_understanding")
        )

    def generate(self, value: EBInput) -> ExpectedBehaviorOutput:
        """逐条 trace 灌 GU 切片产 EB，返回 ``ExpectedBehaviorOutput``（对外口径）。"""
        slice_ = value.gu_slice
        if not (slice_.system_wide or slice_.per_skill or slice_.is_out_of_scope):
            slice_ = self._load_slice(value.trajectory, value.attributed_skill)

        msgs = value.trajectory.messages
        first_input, customer_turns = _extract_customer_inputs(msgs)
        if not customer_turns:
            return ExpectedBehaviorOutput(items=[], metadata={"reason": "未找到顾客输入"})

        context_block = self._build_context_block(slice_)
        history_text = _format_history_rich(msgs)
        prompt = (
            "你已经通读了所有轨迹，建立了全局场景理解。"
            "现在请基于这个全局认知，分析以下单条轨迹：\n\n"
            f"{context_block}===== 当前轨迹 =====\n{history_text}\n\n"
            "===== 顾客输入汇总 =====\n"
            f"- 第一条：{first_input}\n"
            f"- 全部输入：{' | '.join(customer_turns)}\n\n"
            "===== 请输出 =====\n\n"
            "基于全局场景理解，回答：\n"
            "1. 如果我是 agent，在这个场景下正确的做法应该是什么？\n"
            "2. agent 实际做的是否符合这个直觉？（结合「工具调用」「业务报告」证据判断）\n"
        )
        raw = self._llm_with_retry(SYSTEM_PROMPT_PHASE2, prompt, "trace")
        items = self._parse_eb(raw, first_input, customer_turns)
        metadata = {
            "attributed_skill": value.attributed_skill or "",
            "scenario": items[0].scenario if items else "",
        }
        return ExpectedBehaviorOutput(items=items, metadata=metadata)

    # ------------------------------------------------------------------
    # GU 切片载入 + 上下文组装
    # ------------------------------------------------------------------

    def _load_slice(self, trajectory, attributed_skill: str | None) -> GUSlice:
        """载入持久化 GU，按 trace 路由组装 ``GUSlice``（flat 整份 / progressive 相关层）。"""
        index = load_index(self._gu_root)
        if index.mode == "flat":
            return GUSlice(system_wide=load_flat(self._gu_root))

        skill = route_skill(trajectory, index, attributed_skill)
        system_wide = load_system_wide(self._gu_root)
        if skill is None:
            return GUSlice(
                system_wide=system_wide,
                per_skill={OUT_OF_SCOPE_SKILL: load_out_of_scope(self._gu_root)},
                is_out_of_scope=True,
            )
        return GUSlice(
            system_wide=system_wide,
            per_skill={skill: load_skill_doc(self._gu_root, skill)},
        )

    @staticmethod
    def _build_context_block(slice_: GUSlice) -> str:
        parts: list[str] = []
        if slice_.system_wide:
            parts.append(
                "===== 全局场景理解（系统级共性 + 相关 skill 局部理解） =====\n"
                + slice_.system_wide
            )
        if slice_.per_skill:
            parts.append("===== 相关 skill 的局部理解（渐进式暴露，只灌相关组） =====")
            for skill_name, sub in slice_.per_skill.items():
                parts.append(f"--- {skill_name} ---\n{sub}")
        if slice_.is_out_of_scope:
            parts.append("（注：该轨迹无 skill 归属，走越界共性）")
        return ("\n\n".join(parts) + "\n\n") if parts else ""

    # ------------------------------------------------------------------
    # LLM 调用 + 解析
    # ------------------------------------------------------------------

    def _llm_with_retry(
        self,
        system_prompt: str,
        user_prompt: str,
        label: str,
        max_retries: int = _LLM_MAX_RETRIES,
    ) -> str:
        """调 LLM，错误/空响应重试；全失败抛 EvaluationError（不降级）。"""
        last_err: object = "未知错误"
        for attempt in range(1, max_retries + 1):
            try:
                resp = _get_invocation_loop().submit(
                    self._model.invoke(
                        [
                            SystemMessage(content=system_prompt),
                            UserMessage(content=user_prompt),
                        ]
                    )
                ).result()
                raw = resp.content
            except Exception as e:  # noqa: BLE001 — 重试，全失败再抛
                last_err = e
                logger.warning("%s 第 %d/%d 次失败: %s", label, attempt, max_retries, e)
                continue
            if isinstance(raw, str) and raw.strip():
                return raw
            last_err = "空响应"
            logger.warning("%s 第 %d/%d 次返回空响应", label, attempt, max_retries)
        raise EvaluationError(f"{label} LLM 调用 {max_retries} 次全失败: {last_err}")

    def _parse_eb(
        self,
        raw: str,
        first_input: str,
        customer_turns: list[str],
    ) -> list[ExpectedBehaviorItem]:
        """解析 LLM 输出（scenario/expected_behavior/result/reason）为单条 item。

        port golden_gen ``generate_golden`` 的逐行解析（中文/英文冒号、行内 marker 截断、
        失败/部分通过/通过 fallback）。无 expected_behavior → 空列表。
        """
        if not raw:
            return []
        scenario = ""
        eb_text = ""
        result_raw = ""
        reason = ""
        for line in raw.split("\n"):
            line = line.strip()
            if line.startswith(("scenario：", "scenario:")):
                scenario = _strip_field(
                    line,
                    ("scenario：", "scenario:"),
                    (
                        "expected_behavior：",
                        "expected_behavior:",
                        "result：",
                        "result:",
                        "reason：",
                        "reason:",
                    ),
                )
            elif line.startswith(("expected_behavior：", "expected_behavior:")):
                eb_text = _strip_field(
                    line,
                    ("expected_behavior：", "expected_behavior:"),
                    ("result：", "result:", "reason：", "reason:"),
                )
            elif line.startswith(("result：", "result:")):
                result_raw = _strip_field(line, ("result：", "result:"))
            elif line.startswith(("reason：", "reason:")):
                reason = _strip_field(line, ("reason：", "reason:"))

        if not eb_text:
            for line in raw.split("\n"):
                if "应该" in line:
                    eb_text = line.strip()
                    break

        result = _normalize_result(result_raw, raw)
        if not eb_text or result not in _VALID_RESULTS:
            # result 非法时降级 NA，但 eb_text 必须有才产 item
            result = "NA" if result not in _VALID_RESULTS else result
        if not eb_text:
            return []

        item_result = cast(EBResult, result)
        inputs = first_input or " | ".join(customer_turns)
        return [
            ExpectedBehaviorItem(
                id="eb_" + uuid.uuid4().hex[:12],
                inputs=inputs,
                expected_behavior=eb_text,
                scope=scenario,
                result=item_result,
                reason=reason,
                scenario=scenario,
            )
        ]


def _strip_field(line: str, prefixes: tuple[str, ...], cut_markers: tuple[str, ...] = ()) -> str:
    """去行首 prefix（中/英冒号），并在值里按 cut_markers 截断（取首个 marker 之前）。"""
    val = line
    for p in prefixes:
        if val.startswith(p):
            val = val[len(p) :].strip()
            break
    for marker in cut_markers:
        idx = val.find(marker)
        if idx != -1:
            val = val[:idx].strip()
    return val


def _normalize_result(result_raw: str, raw: str) -> str:
    """result 归一：优先 result_raw；空时按 raw 出现的通过/部分通过/失败 fallback。"""
    if result_raw:
        for r in ("失败", "部分通过", "通过"):
            if r in result_raw:
                return r
    if "失败" in raw:
        return "失败"
    if "部分通过" in raw:
        return "部分通过"
    if "通过" in raw:
        return "通过"
    return "NA"
