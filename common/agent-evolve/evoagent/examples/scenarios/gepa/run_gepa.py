"""GEPA 独立运行入口 — 不走 optimizer_runner/Trainer，直接运行 GepaOptimizer。

用法:
    cd evoagent
    python examples/scenarios/gepa/run_gepa.py
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
from pathlib import Path

# ── 必须在 import openjiuwen 之前设置 ──────────────────────────────────
# IS_SENSITIVE=false → openjiuwen 的 is_sensitive() 返回 True → 日志中省略 messages 体
os.environ.setdefault("IS_SENSITIVE", "false")

# 确保项目根目录在 sys.path 中
_PROJECT_ROOT = Path(__file__).resolve().parents[3]  # evoagent/
sys.path.insert(0, str(_PROJECT_ROOT / "src"))

from evo_agent.config import EvolveConfig
from evo_agent.optimizer.gepa.gepa_optimizer import GepaOptimizer
from evo_agent.llm.invocation import LLMInvocation

# ── 日志配置 ───────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.WARNING,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logging.getLogger("gepa-runner").setLevel(logging.INFO)
logging.getLogger("evo_agent.optimizer.gepa").setLevel(logging.INFO)
logger = logging.getLogger("gepa-runner")


def _silence_openjiuwen_logging():
    """在 openjiuwen 日志系统初始化后，将其所有 logger 提升到 level 60 以彻底静默。"""
    try:
        from openjiuwen.core.common.logging import LogManager
        LogManager.initialize()
        for log_type in ("common", "interface", "prompt_builder", "performance",
                         "llm", "tool", "agent", "workflow", "session",
                         "controller", "runner", "operator", "store", "memory",
                         "retrieval", "context_engine", "graph", "mcp", "team",
                         "multi_agent", "sys_operation", "prompt"):
            try:
                lg = LogManager.get_logger(log_type)
                if hasattr(lg, "set_level"):
                    lg.set_level(60)
            except Exception:
                pass
    except Exception:
        pass
    # 同时屏蔽 stdlib 侧
    for name in ("llm", "common", "openjiuwen", "httpx", "httpcore", "urllib3"):
        lg = logging.getLogger(name)
        lg.setLevel(60)
        lg.propagate = False
        lg.handlers = []


_silence_openjiuwen_logging()
# 再次在 openjiuwen 首次使用后执行（lazy init）
import atexit
atexit.register(_silence_openjiuwen_logging)


# ── 数据集加载 ──────────────────────────────────────────────────────────

def load_jsonl(path: str) -> list[dict]:
    items = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def to_cases(items: list[dict]) -> list:
    from openjiuwen.agent_evolving.dataset import Case
    cases = []
    for item in items:
        case = Case(
            case_id=item["case_id"],
            inputs=item["inputs"],
            label=item.get("label", {}),
        )
        cases.append(case)
    return cases


# ── 简单评估器 ───────────────────────────────────────────────────────────

class SimpleJSONEvaluator:
    """JSON 分类评估器：对比 is_litter + category。"""

    def batch_evaluate(self, cases, predicts, **kwargs):
        from openjiuwen.agent_evolving.dataset import EvaluatedCase
        results = []
        for case, predict in zip(cases, predicts):
            evaluated = EvaluatedCase(case=case, answer=predict)
            evaluated.score = self._score_one(case, predict)
            results.append(evaluated)
        return results

    def _score_one(self, case, predict) -> float:
        expected_raw = case.label.get("expected_result", "")
        actual_raw = predict.get("answer", str(predict)) if isinstance(predict, dict) else str(predict)
        expected = self._parse_json(expected_raw)
        actual = self._parse_json(actual_raw)
        if expected is None or actual is None:
            return 0.0
        exp_litter = expected.get("is_litter", False)
        act_litter = actual.get("is_litter", False)
        exp_cat = expected.get("category", "")
        act_cat = actual.get("category", "")
        if exp_litter != act_litter:
            return 0.0
        return 1.0 if exp_cat == act_cat else 0.5

    @staticmethod
    def _parse_json(text: str) -> dict | None:
        if not text:
            return None
        text = text.strip()
        if text.startswith("```"):
            parts = text.split("```")
            if len(parts) >= 3:
                text = parts[1]
                lines = text.split("\n")
                if lines and lines[0].strip() in ("", "json", "python"):
                    text = "\n".join(lines[1:])
        try:
            return json.loads(text)
        except (json.JSONDecodeError, TypeError):
            import re
            match = re.search(r'\{[^{}]+\}', text)
            if match:
                try:
                    return json.loads(match.group())
                except (json.JSONDecodeError, TypeError):
                    pass
            return None


# ── 模型创建 ─────────────────────────────────────────────────────────────

def create_vision_model(config: EvolveConfig):
    from openjiuwen.core.foundation.llm.model import Model, ModelRequestConfig, ModelClientConfig
    model_name = (config.vision_model or "").strip() or config.target_model
    api_key = (config.vision_api_key or "").strip() or config.llm_api_key
    base_url = (config.vision_base_url or "").strip() or config.llm_base_url
    if not api_key or not base_url:
        raise RuntimeError("视觉模型凭证缺失。请在 .env 中设置 EVO_LLM_API_KEY 和 EVO_LLM_BASE_URL")
    client_config = ModelClientConfig(
        client_provider="OpenAI",
        api_key=api_key,
        api_base=base_url,
        verify_ssl=False,
        timeout=300.0,
    )
    model_config = ModelRequestConfig(model_name=model_name)
    return Model(client_config, model_config)


def create_llm_invocation(config: EvolveConfig) -> LLMInvocation:
    from openjiuwen.core.foundation.llm.model import Model, ModelRequestConfig, ModelClientConfig
    from evo_agent.llm.invocation import LLMProviderCapabilities
    client_config = ModelClientConfig(
        client_provider="OpenAI",
        api_key=config.llm_api_key,
        api_base=config.llm_base_url,
        verify_ssl=False,
        timeout=300.0,
        max_retries=2,
    )
    model_config = ModelRequestConfig(model_name=config.optimizer_model)
    llm = Model(client_config, model_config)
    return LLMInvocation(
        llm,
        capabilities=LLMProviderCapabilities(
            context_window_tokens=config.llm_context_window_tokens,
            supports_max_output_tokens=True,
            supports_finish_reason=True,
            supports_usage=True,
            supports_json_mode=True,
            completion_signal="either",
        ),
        parallelism=4,
        safety_margin_tokens=config.llm_safety_margin_tokens,
        chars_per_token=config.llm_chars_per_token,
        default_output_reserve_tokens=config.llm_output_reserve_tokens,
        stage_output_reserve_tokens=dict(config.llm_stage_output_reserve_tokens),
    )


# ── 主入口 ───────────────────────────────────────────────────────────────

DEFAULT_SEED_PROMPT = """你是一个高速公路监控系统助手。你的任务是分析高速公路监控图片，判断路面上是否存在抛撒物（道路垃圾/障碍物）。

如果存在抛撒物，请识别其具体类别。可能的类别包括：
- 动物尸体
- 塑料瓶
- 树枝
- 泡沫
- 石块
- 硬质废弃物
- 碎屑散落物
- 轮胎或轮胎残片
- 软质袋状物
- 金属物
- 锥桶

如果不存在抛撒物，请回答"非抛撒物"。

请以 JSON 格式输出结果：
{"is_litter": true/false, "category": "类别名称或非抛撒物"}"""


async def main():
    config = EvolveConfig()
    print("=" * 60, flush=True)
    print(f"GEPA 优化 | optimizer={config.optimizer_model} vision={(config.vision_model or '').strip() or config.target_model}", flush=True)

    # 数据集 — 请将 JSONL 文件放入 gepa_dataset/ 目录
    dataset_dir = _PROJECT_ROOT / "examples" / "scenarios" / "gepa" / "gepa_dataset"
    train_path = str(dataset_dir / "train.jsonl")
    val_path = str(dataset_dir / "val.jsonl")
    if not os.path.isfile(train_path):
        raise FileNotFoundError(
            f"训练集不存在: {train_path}\n"
            f"请将数据集放入 {dataset_dir}/ 目录，格式见 .env.example"
        )
    train_cases = to_cases(load_jsonl(train_path))
    val_cases = to_cases(load_jsonl(val_path))
    print(f"  train={len(train_cases)} val={len(val_cases)} iterations=10", flush=True)
    print("=" * 60, flush=True)

    # 再次静默 openjiuwen 日志（模型创建时可能触发 lazy init）
    _silence_openjiuwen_logging()

    vision_model = create_vision_model(config)
    _silence_openjiuwen_logging()
    llm_invocation = create_llm_invocation(config)
    _silence_openjiuwen_logging()
    evaluator = SimpleJSONEvaluator()

    optimizer = GepaOptimizer(
        vision_model=vision_model,
        vision_model_name=(config.vision_model or "").strip() or config.target_model,
        evaluator=evaluator,
        train_cases=train_cases,
        val_cases=val_cases,
        llm_invocation=llm_invocation,
        model_name=config.optimizer_model,
        num_parallel=4,
        minibatch_size=5,
        max_iterations=10,
        perfect_score=1.0,
        candidate_selection_strategy="pareto",
        acceptance_criterion="strict_improvement",
        enable_merge=True,
        merge_frequency=3,
        seed=42,
        component_name="system_prompt",
        scenario_name="gepa",
    )

    def phase_cb(event: str, data: dict):
        if event == "log":
            print(f"  [{data.get('phase', '')}] {data.get('message', '')}", flush=True)
    optimizer._push_phase = phase_cb

    print("正在运行 GEPA 优化...", flush=True)
    result = await optimizer.run_optimization(DEFAULT_SEED_PROMPT)

    # 输出结果
    print("=" * 60, flush=True)
    print("GEPA 优化完成!", flush=True)
    print(f"  迭代次数: {result['n_iterations']}", flush=True)
    print(f"  候选数: {result['n_candidates']}", flush=True)
    print(f"  最佳分数: {result['best_score']:.4f}", flush=True)
    print("-" * 40, flush=True)
    print(result["best_prompt"], flush=True)

    # 保存结果
    output_dir = _PROJECT_ROOT / "workspace" / "gepa_output"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_dir.joinpath("best_prompt.txt").write_text(result["best_prompt"], encoding="utf-8")
    with open(output_dir / "gepa_result.json", "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)
    print(f"\n结果已保存: {output_dir}", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
