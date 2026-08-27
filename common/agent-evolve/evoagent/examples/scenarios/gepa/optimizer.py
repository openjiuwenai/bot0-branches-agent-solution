"""GEPA 场景适配器 — 将 GepaOptimizer 桥接到 EvoAgent Trainer 的 epoch 循环。

GepaOptimizer 本身是独立运行的优化器（不继承 BaseOptimizer），
本适配器将其包装为 DictSkillDocumentOptimizer 子类，
使 Trainer 每 epoch 调用一次 ``_backward()`` 时实际执行一轮 GEPA 迭代。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from evo_agent.optimizer.gepa.gepa_optimizer import GepaOptimizer

logger = logging.getLogger(__name__)


class GepaScenarioAdapter:
    """场景适配器：接收 EvoAgent 依赖 → 构建 GepaOptimizer → 逐 epoch 运行。

    不继承 DictSkillDocumentOptimizer，而是独立运行 GEPA 完整循环，
    最后将最佳 prompt 写回 operator。
    """

    def __init__(
        self,
        *,
        vision_model: Any = None,
        vision_model_name: str = "",
        evaluator: Any = None,
        llm: Any = None,
        llm_invocation: Any = None,
        model: str = "",
        train_cases: Any = None,
        operators: dict[str, Any] | None = None,
        adapter_client: Any = None,
        num_parallel: int = 4,
        num_epochs: int = 20,
        minibatch_size: int = 5,
        batch_size: int = 8,
        enable_merge: bool = True,
        merge_frequency: int = 3,
        candidate_selection_strategy: str = "pareto",
        acceptance_criterion: str = "strict_improvement",
        perfect_score: float = 1.0,
        seed: int = 0,
        component_name: str = "system_prompt",
        artifact_dir: str = "",
        phase_callback: Any = None,
        cancellation_token: Any = None,
        scenario_name: str | None = "gepa",
        scenarios_dir: Any = None,
        **kwargs: Any,
    ) -> None:
        self._operators = operators or {}
        self._artifact_dir = artifact_dir
        self._phase_callback = phase_callback or (lambda *a, **kw: None)
        self._cancellation_token = cancellation_token
        self._component_name = component_name

        # 解析训练/验证数据
        train_list = self._extract_cases(train_cases)
        val_list = self._extract_val_cases(train_cases)

        self._gepa = GepaOptimizer(
            vision_model=vision_model,
            vision_model_name=vision_model_name,
            evaluator=evaluator,
            train_cases=train_list,
            val_cases=val_list,
            llm_invocation=llm_invocation or llm,
            model_name=model,
            num_parallel=num_parallel,
            minibatch_size=minibatch_size or batch_size,
            max_iterations=num_epochs,
            perfect_score=perfect_score,
            candidate_selection_strategy=candidate_selection_strategy,
            acceptance_criterion=acceptance_criterion,
            enable_merge=enable_merge,
            merge_frequency=merge_frequency,
            seed=seed,
            component_name=component_name,
            scenario_name=scenario_name,
            scenarios_dir=scenarios_dir,
        )
        # 注入 phase callback
        self._gepa._push_phase = self._push_phase

    def _push_phase(self, event: str, data: dict[str, Any]) -> None:
        try:
            self._phase_callback(event, data)
        except Exception:
            logger.debug("[gepa] phase_callback failed", exc_info=True)

    @staticmethod
    def _extract_cases(train_cases: Any) -> list:
        """从 CaseLoader 或 list 提取 Case 列表。"""
        if train_cases is None:
            return []
        if hasattr(train_cases, "get_cases"):
            return train_cases.get_cases()
        if isinstance(train_cases, list):
            return train_cases
        return []

    def _extract_val_cases(self, train_cases: Any) -> list:
        """从 CaseLoader 提取验证集（split）。"""
        if train_cases is None:
            return []
        if hasattr(train_cases, "split"):
            # CaseLoader.split returns (train_loader, val_loader)
            _, val_loader = train_cases.split(0.8)
            return val_loader.get_cases() if hasattr(val_loader, "get_cases") else []
        return self._extract_cases(train_cases)

    async def run(self) -> dict[str, Any]:
        """运行完整 GEPA 优化。"""
        # 从 operator 读取 seed prompt
        seed_prompt = self._read_seed_prompt()
        if not seed_prompt:
            raise RuntimeError("无法从 operator 读取 seed prompt")

        result = await self._gepa.run_optimization(seed_prompt)

        # 将最佳 prompt 写回 operator
        self._write_best_prompt(result["best_prompt"])
        return result

    def _read_seed_prompt(self) -> str:
        """从第一个 operator 读取当前 prompt 内容。"""
        if not self._operators:
            return ""
        op = next(iter(self._operators.values()))
        if hasattr(op, "get_state"):
            state = op.get_state()
            if isinstance(state, dict):
                return str(state.get("skill_content", ""))
        if hasattr(op, "_content"):
            return str(getattr(op, "_content", ""))
        return ""

    def _write_best_prompt(self, prompt: str) -> None:
        """将最佳 prompt 写回所有 operator。"""
        for op_id, op in self._operators.items():
            if hasattr(op, "set_parameter"):
                op.set_parameter("skill_content", prompt)
            elif hasattr(op, "_content"):
                setattr(op, "_content", prompt)
            logger.info("[gepa] prompt written to operator: %s", op_id)


# 导出别名，兼容 ScenarioRegistry 的 optimizer_class 解析
GepaOptimizerAdapter = GepaScenarioAdapter
