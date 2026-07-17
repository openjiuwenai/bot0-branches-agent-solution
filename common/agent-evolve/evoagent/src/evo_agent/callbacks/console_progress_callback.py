"""ConsoleProgressCallback — 把 Trainer 生命周期事件打印到 stdout。

为 CLI 路径（现场部署、无 Job/SSE）提供长任务可观测性：每轮 epoch 在终端
实时可见进度，避免几十分钟零反馈。不依赖 ``Job`` / SSE buffer，纯 print。

继承 agent-core ``Callbacks``，签名严格对齐基类，确保 ``ComposedCallbacks``
按正确顺序调用（``SkillDocumentCallbacks`` → 本 callback）。
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from openjiuwen.agent_evolving.trainer.progress import Callbacks

if TYPE_CHECKING:
    from openjiuwen.agent_evolving.dataset import EvaluatedCase
    from openjiuwen.agent_evolving.trainer.progress import Progress
    from openjiuwen.core.single_agent import BaseAgent


def _mean_score(eval_info: list[EvaluatedCase]) -> float:
    """从 eval_info 算验证集平均分；空集返回 0.0。"""
    scores = [ec.score for ec in eval_info if hasattr(ec, "score")]
    return sum(scores) / len(scores) if scores else 0.0


class ConsoleProgressCallback(Callbacks):  # type: ignore[misc]
    """将 Trainer 生命周期事件实时打印到 stdout（CLI 专用）。

    与 ``ProgressCallback``（API 路径，写 Job + SSE）对应，本类无任何外部
    状态依赖，适合本地同步运行的 CLI。输出与 ``run_optimize.py`` 的 print
    风格一致，``flush=True`` 保证长任务不缓冲。
    """

    def on_train_begin(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        baseline = _mean_score(eval_info)
        n_val = len(eval_info) if eval_info else 0
        print(
            f"训练开始：共 {progress.max_epoch} 轮，{n_val} 个验证 case，基线分数 {baseline:.3f}",
            flush=True,
        )

    def on_train_epoch_begin(
        self,
        agent: BaseAgent,
        progress: Progress,
    ) -> None:
        print(f"  第 {progress.current_epoch}/{progress.max_epoch} 轮开始", flush=True)

    def on_train_epoch_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        val_score = _mean_score(eval_info)
        print(
            f"  第 {progress.current_epoch}/{progress.max_epoch} 轮完成："
            f"验证 {val_score:.3f}，最佳 {progress.best_score:.3f}，"
            f"训练 {progress.current_epoch_score:.3f}",
            flush=True,
        )

    def on_train_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        print(f"训练结束：最佳验证分数 {progress.best_score:.3f}", flush=True)
