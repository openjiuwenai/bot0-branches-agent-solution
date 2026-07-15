"""类型定义 — frozen dataclass，保证不可变。"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class SkillContentSnapshot:
    """单个 skill 在优化过程中的内容快照。

    Attributes:
        name: skill 名称。
        content_before: 优化前的完整 skill 内容。
        epoch_contents: 每轮结束后的 skill 内容，按 epoch 索引。
    """

    name: str
    content_before: str
    epoch_contents: tuple[str, ...]


@dataclass(frozen=True)
class SkillScore:
    """单个 skill 的优化分数。

    Attributes:
        name: skill 名称。
        score_before: 优化前分数。
        score_after: 优化后分数。
        score_delta: 分数变化（after - before）。
        edits_applied: 应用的编辑数量。
        pass_rate_before: 优化前通过率（score ≥ threshold 的占比）。
        pass_rate_after: 优化后通过率。
    """

    name: str
    score_before: float
    score_after: float
    score_delta: float
    edits_applied: int
    pass_rate_before: float = 0.0
    pass_rate_after: float = 0.0


@dataclass(frozen=True)
class OptimizeRequest:
    """内部归一化请求 — runner 层消费的运行参数。

    由 route 层 _normalize() 或 CLI 入口构建。
    runner 只看到扁平的运行参数，不理解平台模板概念。
    """

    # ── 必填 ──
    scenario: str  # 从 optimizer_template.name 映射
    agent_name: str

    # ── Skill ──
    skills: list[str] = field(default_factory=list)
    optimizer_type: str = "skill"

    # ── 数据集（双轨：API 用 dataset_path，CLI 用 dataset_manifest_path）──
    dataset_path: str = ""  # 原始数据文件路径（API 模式）
    dataset_manifest_path: Path | None = None  # dataset.yaml 路径（CLI 模式）

    # ── 评估器 ──
    evaluator_prompt: str = ""  # 从 evaluator_template.prompt 映射

    # ── 连接 ──
    adapter_url: str = ""  # 从 EvolveConfig 或 CLI 注入

    # ── 训练参数 ──
    num_epochs: int | None = None
    batch_size: int | None = None

    # ── 剩余超参 ──
    hyperparams: dict[str, Any] = field(default_factory=dict)

    # ── rollout ──
    rollout_extra_data: dict[str, Any] = field(default_factory=dict)

    # ── 数据切分 ──
    train_split: float = 0.8
    val_split: float = 0.2

    # ── 追踪 ──
    task_name: str = ""


@dataclass(frozen=True)
class TrainResult:
    """训练集（train_split）上的优化结果。

    Attributes:
        score_before: 训练集优化前均分。
        score_after: 训练集优化后均分。
        improvement: 改进百分比字符串（e.g. "+37%"）。
        pass_rate_before: 优化前通过率（score ≥ threshold 的 case 占比）。
        pass_rate_after: 优化后通过率。
        num_cases: 训练集 case 总数。
    """

    score_before: float
    score_after: float
    improvement: str
    pass_rate_before: float
    pass_rate_after: float
    num_cases: int


@dataclass(frozen=True)
class ValResult:
    """验证集（val_split）上的评估结果。

    Attributes:
        final_score: 最终 committed 验证分数（= best_score，门控保留最佳）。
        best_score: 所有轮次中最高 val 分数（committed 最佳，含基线）。
        per_epoch_scores: 每轮候选 fresh eval 分数（该轮编辑在验证集上的真实
            评估，平局时为两次抽样的均值），按 epoch 顺序。会上下波动，反映
            优化尝试的真实趋势；与门控赢家不同（赢家走缓存、单调非降）。
        num_cases: 验证集 case 总数。
        score_before: 优化前的基线 val 分数。
        improvement: 改进百分比字符串（e.g. "+37%"）。None = 未计算（旧报告）。
        pass_rate_before: 优化前通过率（score ≥ threshold 的 case 占比）。
            None = 未计算。
        pass_rate_after: 优化后通过率，取 best-epoch（argmax 锚定，与
            ``best_score`` 同轮）的赢家 per-case 通过率；无改进时回退
            ``pass_rate_before``。None = 未计算。
    """

    final_score: float
    best_score: float
    per_epoch_scores: tuple[float, ...]
    num_cases: int
    score_before: float = 0.0
    improvement: str | None = None
    pass_rate_before: float | None = None
    pass_rate_after: float | None = None


@dataclass(frozen=True)
class OptimizeReport:
    """优化完成后的人类可读报告。"""

    skills: tuple[str, ...]  # 优化涉及的 skill 列表
    dataset: str
    epochs_completed: int
    edits_applied: int  # 总计
    train: TrainResult  # 训练集结果
    val: ValResult  # 验证集结果
    gate_results: tuple[str, ...]  # ("accepted", "rejected", ...)
    artifact_dir: Path
    skill_scores: tuple[SkillScore, ...] = ()  # per-skill 分数（多 skill 时填充）
    skill_contents: tuple[SkillContentSnapshot, ...] = ()  # per-skill 内容快照


class TrajectoryUnavailableError(Exception):
    """Adapter cleaned-traces 不可用时抛出。

    可能原因：
    - 日志采集延迟
    - Adapter sidecar 异常
    - conversation_id 对应的对话未产生有效轨迹
    """
