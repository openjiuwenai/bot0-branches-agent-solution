"""golden_data 数据模型 —— EB（expected_behavior）与 GU（global_understanding）的
pydantic 模型。

口径（见计划评审点 1）：
- ``ExpectedBehaviorItem`` 是**内部完整**模型（备查），含 result/reason/scenario/score。
- ``ExpectedBehaviorOutput`` 是**对外**模型，``to_external()`` 只导出
  ``[{id, inputs, expected_behavior}]`` —— result/reason/scenario/score 不进对外口径，
  仅在内部 / metadata 备查。路由响应体只放对外口径。
- GU 持久化结构：``GUIndex`` 结构化（落 ``index.md`` frontmatter），其余
  ``GUSkillDoc`` / ``GUSystemWide`` / ``GUOutScope`` 是 md 文本载体（``content`` 字段，
  下一步填实现时再扩 6 维度结构化字段）。
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from evo_agent.evaluator.domain.models import StandardTrajectory

# GU 布局模式：flat（skill 少，单文件整份 GU）/ progressive（skill 多，按 skill 分组 +
# 渐进式暴露）。见 ``gu_store`` 与 ``builder``。
GUMode = Literal["flat", "progressive"]

# EB 评估结果口径。score 非定论（评估器可注入"参考非定论"分）。
EBResult = Literal["通过", "部分通过", "失败", "NA"]


class ExpectedBehaviorItem(BaseModel):
    """单条 expected_behavior（内部完整模型，备查）。

    对外下游只取 ``id`` / ``inputs`` / ``expected_behavior``（见
    ``ExpectedBehaviorOutput.to_external``）；``scope`` / ``should`` / ``should_not`` /
    ``reference`` / ``result`` / ``reason`` / ``scenario`` / ``score`` 仅供内部追溯与
    optimizer reflect 的 context 槽位备查，不进对外响应。
    """

    model_config = ConfigDict(extra="forbid")

    id: str
    # 该 EB 关联的输入场景摘要 / 标识。骨架先 str；下一步填实现时细化为
    # （trace_id + 用户意图摘要）。
    inputs: str
    # 三段式："在 X 情况下，应该 Y，不应该 Z"。也可由 should / should_not 拼出，
    # 骨架先整段 str。
    expected_behavior: str
    # 作用域 X（贴近 skill 目标、稳定的上下文范围）。
    scope: str = ""
    # 原则级"应该"（贴近 skill 目标、稳定、不穷举实例）。
    should: list[str] = Field(default_factory=list)
    # 失败类"不应该"（不穷举）。
    should_not: list[str] = Field(default_factory=list)
    # 实例级参考，标"非穷举"（防被当 checklist 用导致错位归因）。
    reference: list[str] = Field(default_factory=list)
    result: EBResult = "NA"
    reason: str = ""
    scenario: str = ""
    # 参考非定论的评估器分数（可空）。
    score: float | None = None


class ExpectedBehaviorOutput(BaseModel):
    """expected_behavior 生成结果（对外模型）。

    ``items`` 内部保留完整字段；``to_external()`` 裁剪为下游口径。``metadata`` 可放
    trace_id / attributed_skill / gu_mode 等备查信息。
    """

    model_config = ConfigDict(extra="forbid")

    items: list[ExpectedBehaviorItem] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)

    def to_external(self) -> list[dict[str, str]]:
        """导出对外口径：每条只保留 ``id`` / ``inputs`` / ``expected_behavior``。

        result / reason / scenario / score 等备查字段不进对外响应，仅在内部与
        optimizer reflect context 槽位使用（防错位归因）。
        """
        return [
            {
                "id": item.id,
                "inputs": item.inputs,
                "expected_behavior": item.expected_behavior,
            }
            for item in self.items
        ]


class GUSlice(BaseModel):
    """灌入 EB 生成器的 GU 切片（渐进式暴露路由后的相关层）。

    progressive 模式：只装 ``route_skill`` 命中的 per_skill sub + system_wide 跨 skill
    涌现层；flat 模式：整份 GU 装进 ``system_wide``、``per_skill`` 空。越界 trace
    （无 skill 归属）置 ``is_out_of_scope=True``，灌 ``__out_of_scope__`` 伪组内容。
    """

    model_config = ConfigDict(extra="forbid")

    system_wide: str = ""
    per_skill: dict[str, str] = Field(default_factory=dict)
    is_out_of_scope: bool = False


class EBInput(BaseModel):
    """EB 生成输入：轨迹 + 路由后的 GU 切片 + skill 归属信号。"""

    model_config = ConfigDict(extra="forbid")

    trajectory: StandardTrajectory
    gu_slice: GUSlice
    attributed_skill: str | None = None


# ---------------------------------------------------------------------------
# GU 持久化结构（落 golden_data_dir/global_understanding/）
# ---------------------------------------------------------------------------


class GUIndex(BaseModel):
    """GU 知识库索引（落 ``index.md`` frontmatter，结构化）。

    ``mode`` 标当前布局：flat（单 ``global_understanding.md``）/ progressive
    （``system_wide.md`` + ``per_skill/<skill>.md`` × N）。``last_run_id`` 记最后一次
    build 的 run workspace 标识，便于追溯 ``artifact_dir/gu_<run_id>/`` 中间结果。
    """

    model_config = ConfigDict(extra="forbid")

    skills: list[str] = Field(default_factory=list)
    mode: GUMode = "progressive"
    last_run_id: str = ""
    out_of_scope_count: int = 0


class GUSkillDoc(BaseModel):
    """单 skill 的 GU sub（md 文本载体）。

    下一步填实现时再扩为 6 维度结构化字段（系统概况 / 常见场景 / 用户目标 / 常见转折 /
    常见陷阱 / 系统缺陷模式）；骨架阶段仅持 ``content``（整份 md）。
    """

    model_config = ConfigDict(extra="forbid")

    content: str = ""


class GUSystemWide(BaseModel):
    """跨 skill 涌现的 system-wide 模式（md 文本载体）。

    只装从 per_skill sub 涌现的跨 skill 模式；**不重复** phase2 prompt 硬编码的通用规则
    （反 ask_user 伪标注 / 超时兜底 / 技术限制 vs 流程缺失 / 忠实性 / 技能选择两步推理）。
    """

    model_config = ConfigDict(extra="forbid")

    content: str = ""


class GUOutScope(BaseModel):
    """越界 trace 的伪组 ``__out_of_scope__``（md 文本载体）。"""

    model_config = ConfigDict(extra="forbid")

    content: str = ""
