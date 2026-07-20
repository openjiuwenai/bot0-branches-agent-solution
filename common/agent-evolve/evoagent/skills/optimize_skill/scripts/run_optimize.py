#!/usr/bin/env python3
"""主优化脚本 — A 入口（Agent 对话 / CLI）。

用法:
  python skills/optimize_skill/scripts/run_optimize.py \\
    --scenario edp_agent \\
    --dataset-manifest <path/to/dataset.yaml>

  # 显式覆盖 scenario.yaml 中的默认值:
  python skills/optimize_skill/scripts/run_optimize.py \\
    --scenario edp_agent \\
    --dataset-manifest <path/to/dataset.yaml> \\
    --adapter-url http://other-host:9090 \\
    --agent-name custom_agent \\
    --skills skill_a,skill_b \\
    --epochs 5

参数自动填充规则:
  - adapter_url: CLI > scenario.yaml > 报错
  - skills: CLI (显式传入时覆盖，含空串) > scenario.yaml optimize=true 列表
  - agent_name: CLI > scenario 名称
  - epochs / batch_size: CLI 显式传入 > scenario.yaml hyperparams > EvolveConfig
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path
from typing import Any

from evo_agent.callbacks import ConsoleProgressCallback
from evo_agent.config import EvolveConfig
from evo_agent.optimizer_runner import run_optimization
from evo_agent.scenario.registry import ScenarioRegistry
from evo_agent.stdio_utf8 import ensure_utf8_stdio
from evo_agent.types import OptimizeRequest

# 尽早切 UTF-8，避免 Windows GBK 控制台把中文进度打成乱码
ensure_utf8_stdio()


class ResolveParamsError(Exception):
    """resolve_params 参数解析失败时抛出。"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="运行 skill 文档优化")
    parser.add_argument(
        "--scenario",
        default="edp_agent",
        help="场景名称（对应 scenarios/<name>/scenario.yaml）",
    )
    parser.add_argument(
        "--dataset-manifest",
        required=True,
        type=Path,
        help="dataset.yaml 路径",
    )
    parser.add_argument(
        "--adapter-url",
        default=None,
        help="Adapter sidecar 地址（默认从 scenario.yaml 读取）",
    )
    parser.add_argument(
        "--skills",
        default=None,
        help="要优化的 skill 名称，逗号分隔（默认从 scenario.yaml 读取 optimize=true 的 skill）",
    )
    parser.add_argument(
        "--agent-name",
        default=None,
        help="目标 Agent 名称（默认使用场景名称）",
    )
    parser.add_argument(
        "--epochs",
        type=int,
        default=None,
        help="优化轮数（默认使用 scenario.yaml hyperparams.num_epochs）",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=None,
        help="每批 case 数（默认使用 scenario.yaml hyperparams.batch_size）",
    )
    return parser.parse_args()


def resolve_params(args: argparse.Namespace) -> OptimizeRequest:
    """从 CLI 参数和 scenario.yaml 解析最终请求参数。

    优先级：CLI 显式参数 > scenario.yaml 配置 > EvolveConfig > 默认值/报错。

    Raises:
        FileNotFoundError: scenario.yaml 不存在。
        ResolveParamsError: adapter_url 未配置或格式无效。
    """
    registry = ScenarioRegistry()
    scenario_config = registry.load_scenario_config(args.scenario)

    # adapter_url: CLI > scenario.yaml > EvolveConfig > 报错
    if args.adapter_url is not None:
        adapter_url = args.adapter_url
    elif scenario_config.adapter_url:
        adapter_url = scenario_config.adapter_url
    else:
        config = EvolveConfig.get()
        adapter_url = config.adapter_url
    if not adapter_url:
        raise ResolveParamsError(
            "adapter_url 未指定：CLI --adapter-url、scenario.yaml、"
            "环境变量 EVO_ADAPTER_URL 均未配置。",
        )
    if not adapter_url.startswith(("http://", "https://")):
        raise ResolveParamsError(
            f"adapter_url 必须以 http:// 或 https:// 开头: {adapter_url}",
        )

    # skills: CLI（显式传入时覆盖，含空串）> scenario.yaml optimize=true
    if args.skills is not None:
        skills = [s.strip() for s in args.skills.split(",") if s.strip()]
    else:
        skills = scenario_config.get_optimize_skills()

    # agent_name: CLI > scenario 名称
    agent_name = args.agent_name or args.scenario

    return OptimizeRequest(
        scenario=args.scenario,
        agent_name=agent_name,
        optimizer_type="skill",
        skills=skills,
        dataset_path="",  # CLI 不使用 dataset_path
        dataset_manifest_path=args.dataset_manifest,  # CLI 使用 manifest
        evaluator_prompt="",
        adapter_url=adapter_url,
        num_epochs=args.epochs,
        batch_size=args.batch_size,
        hyperparams={},
        train_split=0.8,
        val_split=0.2,
        task_name=f"cli-{args.scenario}",
    )


async def main() -> None:
    args = parse_args()
    config = EvolveConfig.get()
    try:
        request = resolve_params(args)
    except ResolveParamsError as e:
        print(f"错误: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"启动优化: scenario={request.scenario}, skills={request.skills}")
    print(f"  adapter_url={request.adapter_url}")
    print(f"  agent_name={request.agent_name}")
    print(f"  epochs={request.num_epochs}, batch_size={request.batch_size}")

    # CLI 也有可观测性：每轮 epoch 进度 + pipeline 阶段事件实时打印，
    # 避免长任务几十分钟零反馈。不依赖 Job/SSE，纯 stdout。
    progress_callback = ConsoleProgressCallback()

    def _phase_callback(event: str, data: dict[str, Any]) -> None:
        """打印 pipeline 阶段事件（pipeline_start/restore_skill/setup/apply...）。"""
        phase = data.get("phase", "")
        message = data.get("message", "")
        if message:
            print(f"[{phase}] {message}", flush=True)

    report = await run_optimization(
        request,
        config,
        progress_callback=progress_callback,
        phase_callback=_phase_callback,
    )

    print(f"\n优化完成: {report.skills}")
    print(
        f"  得分: {report.train.score_before} → {report.train.score_after} "
        f"({report.train.improvement})",
    )
    print(f"  编辑数: {report.edits_applied}")
    print(f"  产物: {report.artifact_dir}")

    if report.skill_scores:
        print("  Per-skill 得分:")
        for ss in report.skill_scores:
            print(f"    {ss.name}: {ss.score_before} → {ss.score_after} ({ss.score_delta:+.2f})")


if __name__ == "__main__":
    asyncio.run(main())
