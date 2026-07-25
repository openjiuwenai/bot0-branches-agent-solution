"""固定话术帧推送模块。

提供固定话术选择、切片和流式推送功能。
"""
from __future__ import annotations

import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .agent_rule import FixedScriptsConfig


def split_scripts_into_frames(scripts: list[str], chars_per_frame: int) -> list[str]:
    """将每条话术按 chars_per_frame 切成多个子帧，模拟逐字符渲染效果。

    Args:
        scripts: 原始话术列表，每条为完整句子。
        chars_per_frame: 每帧字符数。<=0 时不切片，原样返回。

    Returns:
        切片后的子帧列表。

    Examples:
        >>> split_scripts_into_frames(["正在搜索理财产品..."], 4)
        ["正在搜索", "理财产品", "..."]
        >>> split_scripts_into_frames(["Hello"], 0)
        ["Hello"]
    """
    if chars_per_frame <= 0:
        return scripts
    frames: list[str] = []
    for script in scripts:
        for i in range(0, len(script), chars_per_frame):
            frames.append(script[i:i + chars_per_frame])
    return frames


def select_fixed_scripts(
    query: str,
    config: FixedScriptsConfig,
    *,
    scenario_query_patterns: list | None = None,
    is_resume: bool = False,
    is_first_thinking_round: bool = True,
) -> list[str]:
    """根据阶段 + query 选择匹配的固定话术组。

    阶段判定：
      resuming  — is_resume=True（Cascade 续轮，query="continue"）
      planning  — is_first_thinking_round=True 且 is_resume=False（首轮思考）
      executing — is_first_thinking_round=False 且 is_resume=False（第2轮及后续思考）

    话术选择优先级：
      planning  → query_patterns 命中 > default_scripts > scripts
      executing → execution_scripts > default_scripts > scripts
      resuming  → resume_scripts > execution_scripts > default_scripts > scripts

    Args:
        query: 用户原始输入文本
        config: FixedScriptsConfig 实例
        is_resume: 是否为 Cascade 续轮
        is_first_thinking_round: 是否为首轮思考（仅入口调用时为 True）

    Returns:
        选中的话术列表（所有降级源均为空时返回 []，feeder 不发帧）。
    """
    # ── resuming 阶段 ────────────────────────────────────────────
    if is_resume:
        # 根据配置决定是否启用 resuming 阶段固定话术
        if not config.enable_resume_scripts:
            return []
        if config.resume_scripts:
            return config.resume_scripts
        # 降级到 execution_scripts（续轮也是"执行"的延续）
        if config.execution_scripts:
            return config.execution_scripts
        # 继续降级
        if config.default_scripts:
            return config.default_scripts
        return config.scripts

    # ── planning 阶段（第1轮思考，按 query 关键词匹配）────────────
    if is_first_thinking_round:
        # 优先使用 scenario_query_patterns（场景级匹配）
        if scenario_query_patterns:
            for pattern in scenario_query_patterns:
                if any(kw in query for kw in pattern.keywords):
                    return pattern.scripts
        if config.query_patterns:
            for pattern in config.query_patterns:
                if any(kw in query for kw in pattern.keywords):
                    return pattern.scripts
        if config.default_scripts:
            return config.default_scripts
        return config.scripts

    # ── executing 阶段（第2轮及后续思考）─────────────────────────
    if config.execution_scripts:
        return config.execution_scripts
    if config.default_scripts:
        return config.default_scripts
    return config.scripts


class FixedScriptFeeder:
    """固定话术帧推送器。

    职责：
      - 按 token 累积节奏推送固定话术帧（支持字符切片 + 时间节流）
      - 管理"已推送/未推送"游标
      - 提供 drain_all() 用于 LLM 流结束后立即推送剩余帧

    规则：
      R1: 固定话术全部发完但 LLM 仍在输出 → 不再发空帧填充，静默等待
      R2: LLM 流结束但固定话术仍有剩余 → 立即 drain 全部剩余帧
      R3: think_end 仅在 LLM 流完成 AND 固定话术全部发完时才发送
    """

    def __init__(self, config: FixedScriptsConfig) -> None:
        self._scripts = split_scripts_into_frames(
            list(config.scripts), config.chars_per_frame
        )                                               # 话术列表（按字符切片）
        self._cursor = 0                               # 下一条待推送的索引
        self._tokens_between = config.tokens_between_frames
        self._tokens_accumulated = 0                    # 自上次推送后累积的 token 数
        self._llm_ended = False                        # LLM 流已结束标记
        # 时间节流属性
        self._min_interval_ms = config.min_interval_ms
        self._last_frame_time: float = 0.0              # 上次推送帧的挂钟时间（monotonic）

    @property
    def all_sent(self) -> bool:
        """所有固定话术帧是否已全部发完。"""
        return self._cursor >= len(self._scripts)

    def mark_llm_ended(self) -> None:
        """标记 LLM 流已结束。"""
        self._llm_ended = True

    def feed_token(self, token_count: int = 1) -> list[str]:
        """每收到 N 个 LLM token 调用一次，返回本轮应推送的话术帧列表。

        规则：
          - 累积 token 数达到 tokens_between_frames 时，推送下一帧
          - 已全部发完 → 不发空帧（R1）
          - 时间节流：距上次推送不足 min_interval_ms 时暂不推送，等下次调用
        """
        if self.all_sent:
            return []   # R1: 固定话术已全部发完，静默等待 LLM 流结束

        self._tokens_accumulated += token_count
        frames: list[str] = []
        now = time.monotonic()
        while (self._tokens_accumulated >= self._tokens_between
               and not self.all_sent):
            # 时间节流：间隔不足则暂不推送，保留累积的 token 等下次调用
            if self._min_interval_ms > 0:
                elapsed_ms = (now - self._last_frame_time) * 1000
                if elapsed_ms < self._min_interval_ms:
                    break
            self._tokens_accumulated -= self._tokens_between
            frames.append(self._scripts[self._cursor])
            self._cursor += 1
            self._last_frame_time = time.monotonic()   # 更新推送时间
        return frames

    def drain_all(self) -> list[str]:
        """立即输出全部剩余帧（R2：LLM 结束但固定话术仍有剩余时调用）。

        调用后 all_sent 一定为 True。
        注意：drain_all 不受时间节流限制，保证 LLM 结束时快速收敛。
        """
        remaining = self._scripts[self._cursor:]
        self._cursor = len(self._scripts)
        self._tokens_accumulated = 0
        return remaining


__all__ = [
    "split_scripts_into_frames",
    "select_fixed_scripts",
    "FixedScriptFeeder",
]