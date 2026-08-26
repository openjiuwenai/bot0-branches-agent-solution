# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Host-independent recovery prompt contracts."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RecoveryPromptContext:
    workspace_id: str
    workspace_status: str
    last_error: str | None = None
    last_message: str = ""
    hitl_answers: str = ""
    user_message: str | None = None
    from_event_id: str | None = None
    has_checkpoint: bool = False
    previous_failure_context: str = ""


def is_recovery_prompt(text: str) -> bool:
    return text.strip().startswith(("继续上次失败的 Skill 抽取任务", "重新尝试 Skill 抽取任务"))


def strip_recovery_suffix(text: str) -> str:
    stripped = text.strip()
    markers = (
        "\nResume recovery instruction:",
        "\n产物修复模式。",
        "\nArtifact repair mode。",
        "\n重试模式：",
        "\nRetry mode：",
        "\n当前恢复上下文：",
        "\n上一次失败摘要",
    )
    cut = len(stripped)
    for marker in markers:
        index = stripped.find(marker)
        if index >= 0:
            cut = min(cut, index)
    return stripped[:cut].strip()


def build_recovery_prompt(*, kind: str, context: RecoveryPromptContext) -> str:
    action = "继续上次失败的 Skill 抽取任务" if kind == "resume" else "重新尝试 Skill 抽取任务"
    lines = [
        action + "。",
        "",
        f"- 工作区: {context.workspace_id}",
        f"- 当前状态: {context.workspace_status}",
        f"- 上次错误: {context.last_error or '无'}",
    ]
    if context.from_event_id:
        lines.append(f"- 从事件重试: {context.from_event_id}")
    if context.last_message:
        lines.extend(["", "上一次用户指令：", context.last_message])
    if context.hitl_answers:
        lines.extend(["", "已提交的人工确认：", context.hitl_answers])
    if context.user_message:
        lines.extend(["", "本次补充指令：", context.user_message.strip()])

    if kind == "retry":
        lines.extend(
            [
                "",
                "重试模式：从 inputs/、Scenario 摘要和已确认信息重新生成当前 Skill 草稿。",
                "写包阶段第一轮只用 write_skill_file 写入完整 SKILL.md；后续可在同一响应中并列调用最多 4 次 write_skill_file 写入不同路径的独立小文件，但不得使用 write_skill_files、不得把多个文件放进单个工具参数，也不得写占位骨架。",
                "完成后调用 finish_authoring 提交结构化自检摘要，由控制器执行完整预检并提交 PackageRevision。",
            ]
        )
        if context.previous_failure_context:
            lines.extend(["", "上一次失败摘要（仅供本次重试避坑，不作为当前产物）：", context.previous_failure_context])
    elif context.has_checkpoint:
        lines.extend(
            [
                "",
                "草稿修订模式。",
                "继续当前 generated-skill/ 文件，按用户要求和明确的最小包错误做有限修改。",
                "需要核对事实时读取直接相关材料；不要消费 Gate、RepairPlan 或旧验收输出。",
                "修订完成后调用 finish_authoring 提交结构化自检摘要，由控制器形成新的 PackageRevision。",
            ]
        )
        if context.previous_failure_context:
            lines.extend(["", context.previous_failure_context])
    else:
        lines.extend(
            [
                "",
                "恢复指令：",
                "上一次运行在有效阶段提交前失败；旧文件只用于诊断，不能作为本轮完成依据。",
                "不要先写长分析；按当前阶段完成唯一提交。场景阶段写入持久化 ScenarioDraft 后调用 finish_scenario_draft，写包阶段从现有 Draft 直接补写缺失文件。",
                "不要创建占位脚本或平台状态文件；候选完整后调用 finish_authoring 提交结构化自检摘要，交由控制器提交。",
            ]
        )
        if context.previous_failure_context:
            lines.extend(["", context.previous_failure_context])
    return "\n".join(lines).strip()


__all__ = [
    "RecoveryPromptContext",
    "build_recovery_prompt",
    "is_recovery_prompt",
    "strip_recovery_suffix",
]
