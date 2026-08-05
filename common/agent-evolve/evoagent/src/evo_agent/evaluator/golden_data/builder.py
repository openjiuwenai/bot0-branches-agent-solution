"""离线建 global_understanding（GU）—— phase1 实现。

port 自 bank bundle ``golden_gen/run.py``，在 evo_agent 栈上重写（见记忆
[[expected-behavior-generator-methodology]]）：

- **phase1 建 GU**：从一批 trace + skills 归纳 global_understanding，分批 induct →
  refine（第 1 批从零归纳，后续批审阅补充）。6 维度：系统概况 / 常见场景 / 用户目标 /
  常见转折 / 常见陷阱 / 系统缺陷模式（技术限制 vs 流程缺失）。
- **flat / progressive 可选**：``skill_count <= flat_threshold`` → flat（一批归纳成单
  ``global_understanding.md``）；否则 progressive（按 skill 分组建 per_skill×N +
  system_wide + index，渐进式暴露）。
- **run workspace 落盘**（对齐 optimizer，见 ``optimizer_runner.py`` L63/L178）：每次
  build 生成 ``run_id``，建 ``artifact_dir/gu_<run_id>/`` 放中间结果（批 induct/refine
  草稿、trace→skill 路由、per_skill 草稿、mode 判定）；最终产物经 ``gu_store`` 提交到
  持久化知识库 ``golden_data_dir/global_understanding/``。

与 golden_gen 的栈差异：
- LLM：``Model.invoke([SystemMessage, UserMessage])``（async，``_get_invocation_loop().submit().result()`` 同步包）
  而非 ``common.llm_client.call_llm``。
- 轨迹：``StandardTrajectory.messages``（list[TrajectoryMessage]，无 script_id /
  conversation_id / script.skill）——轨迹标识用索引，skill 归属信号走
  ``SkillProvider`` + ``gu_store.route_skill``（无评估器 score 时退文本扫描）。
- skill：``SkillProvider.get_skill_content`` 而非读 skill_dir SKILL.md。
"""

from __future__ import annotations

import json
import logging
import uuid
from pathlib import Path

from openjiuwen.core.foundation.llm import (
    Model,
    ModelClientConfig,
    ModelRequestConfig,
    SystemMessage,
    UserMessage,
)

from evo_agent.config import EvolveConfig
from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.llm.invocation import _get_invocation_loop
from evo_agent.evaluator.golden_data.gu_store import (
    OUT_OF_SCOPE_SKILL,
    route_skill,
    save_flat,
    save_index,
    save_out_of_scope,
    save_skill_doc,
    save_system_wide,
)
from evo_agent.evaluator.golden_data.models import GUIndex, GUMode
from evo_agent.evaluator.golden_data.prompts.phase1_gu import (
    SYSTEM_PROMPT_GLOBAL,
    SYSTEM_PROMPT_SYSTEM_WIDE,
)
from evo_agent.evaluator.golden_data.skill_provider import SkillProvider
from evo_agent.evaluator.golden_data.trajectory_format import _format_turn_rich, _truncate

logger = logging.getLogger(__name__)

__all__ = ["GlobalUnderstandingBuilder"]

_CONTENT_CAP = 500
_REPORT_CAP = 500
_MAX_TURNS = 4
_SKILL_CONTENT_CAP = 1000
_LLM_MAX_RETRIES = 3


class GlobalUnderstandingBuilder:
    """离线建 GU（phase1）。

    Parameters
    ----------
    model_config, model_client_config:
        归纳用 LLM 配置（照 ``TrajectoryGoalGenerator``）。
    skill_provider:
        skill 双源（``SkillProvider``），取 skill 列表与内容。
    flat_threshold:
        skill 数 ≤ 此阈值走 flat 模式，否则 progressive。
    gu_root:
        GU 持久化知识库根（最终产物落点）；缺省 ``EvolveConfig.get().golden_data_dir
        / "global_understanding"``。
    artifact_root:
        run workspace 落点根（中间结果）；缺省 ``EvolveConfig.get().artifact_dir``。
    """

    def __init__(
        self,
        model_config: ModelRequestConfig,
        model_client_config: ModelClientConfig,
        skill_provider: SkillProvider,
        *,
        flat_threshold: int = 30,
        gu_root: Path | None = None,
        artifact_root: Path | None = None,
    ) -> None:
        self._model = Model(model_client_config, model_config)
        self._skill_provider = skill_provider
        self._flat_threshold = flat_threshold
        config = EvolveConfig.get()
        self._gu_root = (
            gu_root if gu_root is not None else (config.golden_data_dir / "global_understanding")
        )
        self._artifact_root = artifact_root if artifact_root is not None else config.artifact_dir

    # ------------------------------------------------------------------
    # 编排入口
    # ------------------------------------------------------------------

    def build(
        self,
        traces: list[StandardTrajectory],
        skill_names: list[str],
        batch_size: int = 10,
    ) -> GUIndex:
        """建 GU 并提交到持久化知识库，返回更新后的 ``GUIndex``。"""
        run_id = uuid.uuid4().hex[:12]
        run_workspace = self._artifact_root / f"gu_{run_id}"
        run_workspace.mkdir(parents=True, exist_ok=True)

        skill_count = len(skill_names)
        mode: GUMode = "flat" if skill_count <= self._flat_threshold else "progressive"
        (run_workspace / "mode_decision.json").write_text(
            json.dumps(
                {
                    "run_id": run_id,
                    "mode": mode,
                    "skill_count": skill_count,
                    "flat_threshold": self._flat_threshold,
                    "skill_names": list(skill_names),
                    "trace_count": len(traces),
                    "batch_size": batch_size,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

        skills = self._load_skills(skill_names)

        if mode == "progressive":
            oos_count = self._build_grouped(traces, skills, run_workspace, batch_size)
            index = GUIndex(
                skills=sorted(skills.keys()),
                mode="progressive",
                last_run_id=run_id,
                out_of_scope_count=oos_count,
            )
        else:
            gu_text = self._generate_global_understanding(traces, skills, run_workspace, batch_size)
            save_flat(self._gu_root, gu_text)
            index = GUIndex(
                skills=sorted(skill_names),
                mode="flat",
                last_run_id=run_id,
                out_of_scope_count=0,
            )
        save_index(self._gu_root, index)
        return index

    # ------------------------------------------------------------------
    # skill 取数
    # ------------------------------------------------------------------

    def _load_skills(self, skill_names: list[str]) -> dict[str, str]:
        """经 ``SkillProvider`` 取每个 skill 内容；失败/未实现则跳过（GU 不灌 skill 块）。"""
        skills: dict[str, str] = {}
        for name in skill_names:
            try:
                content = self._skill_provider.get_skill_content(name)
            except NotImplementedError:
                logger.warning("SkillProvider.get_skill_content 未实现，跳过 skill %s", name)
                continue
            except Exception as e:  # noqa: BLE001 — 单 skill 失败不阻断整批
                logger.warning("取 skill %s 内容失败: %s", name, e)
                continue
            if content:
                skills[name] = content
        return skills

    # ------------------------------------------------------------------
    # phase1 分批 induct → refine（flat 与 progressive 每组共用）
    # ------------------------------------------------------------------

    def _generate_global_understanding(
        self,
        traces: list[StandardTrajectory],
        skills: dict[str, str],
        intermediate_dir: Path | None,
        batch_size: int,
    ) -> str:
        """分批迭代归纳 GU；任一批 LLM 失败直接抛（不降级保留前序）。"""
        total = len(traces)
        if total == 0:
            return ""
        total_batches = (total + batch_size - 1) // batch_size
        if intermediate_dir is not None:
            intermediate_dir.mkdir(parents=True, exist_ok=True)

        current = ""
        for batch_num in range(1, total_batches + 1):
            start = (batch_num - 1) * batch_size
            batch = traces[start : min(batch_num * batch_size, total)]
            batch_text = _format_batch(batch, batch_num, total_batches)
            if batch_num == 1:
                current = self._global_induct(batch_text, len(batch), skills)
            else:
                current = self._global_refine(
                    current, batch_text, len(batch), batch_num, total_batches
                )
            if intermediate_dir is not None:
                (intermediate_dir / f"global_batch_{batch_num}.txt").write_text(
                    current, encoding="utf-8"
                )
        return current

    def _global_induct(self, batch_text: str, n: int, skills: dict[str, str]) -> str:
        skill_block = _format_skill_block(skills)
        skill_section = (skill_block + "\n\n") if skill_block else ""
        prompt = (
            f"请通读以下第1批 {n} 条对话轨迹，建立对系统的全局认知：\n\n"
            f"{skill_section}{batch_text}\n\n"
            "请输出 6 个维度的全局理解：\n"
            "1. 系统概况  2. 常见场景  3. 用户目标  4. 常见转折  "
            "5. 常见陷阱  6. 系统缺陷模式（区分技术限制 vs 流程缺失）\n"
        )
        return self._llm_with_retry(SYSTEM_PROMPT_GLOBAL, prompt, "第1批")

    def _global_refine(
        self,
        existing: str,
        batch_text: str,
        n: int,
        batch_num: int,
        total_batches: int,
    ) -> str:
        prompt = (
            f"你已有前序批次归纳出的全局理解，现在用第 {batch_num}/{total_batches} 批 "
            f"{n} 条新轨迹对其进行审阅补充。\n\n"
            f"===== 当前全局理解 =====\n{existing}\n\n"
            f"===== 第 {batch_num}/{total_batches} 批轨迹（共 {n} 条） =====\n{batch_text}\n\n"
            "请审阅并输出更新后的【完整】全局理解（6 个维度），不要只说变化。"
        )
        return self._llm_with_retry(SYSTEM_PROMPT_GLOBAL, prompt, f"第{batch_num}批")

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

    # ------------------------------------------------------------------
    # progressive：分组 + per_skill sub + system_wide + index
    # ------------------------------------------------------------------

    def _build_grouped(
        self,
        traces: list[StandardTrajectory],
        skills: dict[str, str],
        run_workspace: Path,
        batch_size: int,
    ) -> int:
        """按 skill 分组建 per_skill sub + system_wide + index；返回 OOS trace 计数。"""
        known = set(skills.keys())
        groups, oos_count = self._group_traces_by_skill(traces, known)

        (run_workspace / "trace_skill_routes.json").write_text(
            json.dumps(
                {s: len(g) for s, g in groups.items()},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

        subs: dict[str, str] = {}
        for skill_name, group_traces in groups.items():
            if not group_traces:
                continue
            one_skill = {skill_name: skills[skill_name]} if skill_name in skills else {}
            sub_inter = run_workspace / "per_skill_draft" / skill_name
            sub = self._generate_global_understanding(
                group_traces, one_skill, sub_inter, batch_size
            )
            if not sub:
                logger.warning("sub [%s] 生成失败，跳过（用时会退回 system 层）", skill_name)
                continue
            if skill_name == OUT_OF_SCOPE_SKILL:
                save_out_of_scope(self._gu_root, sub)
            else:
                save_skill_doc(self._gu_root, skill_name, sub)
            subs[skill_name] = sub

        system_wide = self._build_system_wide(subs)
        save_system_wide(self._gu_root, system_wide)
        return oos_count

    def _group_traces_by_skill(
        self,
        traces: list[StandardTrajectory],
        known_skills: set[str],
    ) -> tuple[dict[str, list[StandardTrajectory]], int]:
        """trace → {skill: [traces]}；无 skill 归属归入 ``OUT_OF_SCOPE_SKILL`` 伪组。

        evo_agent 无评估器 score.attributed_skill（builder 阶段未评估），主信号退化为
        ``gu_store.route_skill`` 的文本扫描（首个命中 skill）。一条 trace 只归一个组
        （无 golden_gen 的链式多归属，简化）。
        """
        tmp_index = GUIndex(skills=list(known_skills))
        groups: dict[str, list[StandardTrajectory]] = {s: [] for s in known_skills}
        groups[OUT_OF_SCOPE_SKILL] = []
        for trace in traces:
            skill = route_skill(trace, tmp_index)
            if skill is None:
                groups[OUT_OF_SCOPE_SKILL].append(trace)
            else:
                groups.setdefault(skill, []).append(trace)
        return groups, len(groups[OUT_OF_SCOPE_SKILL])

    def _build_system_wide(self, subs: dict[str, str]) -> str:
        """归并各 sub 成系统级共性（涌现模式，不重复硬编码规则）；LLM 失败抛（不降级）。"""
        if not subs:
            return "(尚无 per-skill sub, system 层为空)"
        subs_block = "\n\n".join(f"--- {s} ---\n{txt}" for s, txt in subs.items())
        prompt = (
            "以下是各 skill 的局部理解(6 维度, 只覆盖各自 skill)。"
            "请归并出【跨 skill 的系统级共性】。\n\n"
            f"===== 各 skill 局部理解 =====\n{subs_block}\n\n"
            "请输出更新后的系统级共性(3-4 个维度: 系统级陷阱 / "
            "跨 skill 缺陷模式 / 越界共性 / 链式断点)。"
        )
        return self._llm_with_retry(SYSTEM_PROMPT_SYSTEM_WIDE, prompt, "system_wide")


# ===========================================================================
# 富文本轨迹格式化（port golden_gen _format_turn_rich，适配 TrajectoryMessage）
# ===========================================================================


def _format_batch(traces: list[StandardTrajectory], batch_num: int, total_batches: int) -> str:
    header = f"=== 第 {batch_num}/{total_batches} 批（共 {len(traces)} 条轨迹） ===\n"
    return header + "\n\n".join(_format_trajectory_snippet(t, i) for i, t in enumerate(traces))


def _format_trajectory_snippet(
    trajectory: StandardTrajectory,
    index: int,
    max_turns: int = _MAX_TURNS,
    content_cap: int = _CONTENT_CAP,
    report_cap: int = _REPORT_CAP,
) -> str:
    msgs = trajectory.messages
    lines = [f"=== 轨迹 #{index} ==="]
    seen: set[str] = set()
    for m in msgs[:max_turns]:
        lines.append(_format_turn_rich(m, content_cap, report_cap, seen))
    if len(msgs) > max_turns:
        lines.append("  ...（后续省略）")
    return "\n".join(lines)


def _format_skill_block(skills: dict[str, str]) -> str:
    if not skills:
        return ""
    lines = ["===== Agent 技能定义（设计背景，仅供理解系统，非判断标准） ====="]
    for name, content in skills.items():
        lines.append(f"{name}: {_truncate(content, _SKILL_CONTENT_CAP)}")
    lines.append(
        "注：以上是 agent 各技能的设计声明（技能职责），是判断「应该用哪个技能」的正确基线。"
    )
    return "\n".join(lines)
