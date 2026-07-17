#!/usr/bin/env python3
"""报告生成脚本 — 读取 artifact 目录，输出人类可读报告。

用法:
  python -m evo_agent.scripts.report --artifact-dir <path>
"""

from __future__ import annotations

import argparse
from pathlib import Path

from evo_agent.reporter.formatter import ReportFormatter


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成优化报告")
    parser.add_argument("--artifact-dir", required=True, type=Path, help="artifact 目录路径")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    formatter = ReportFormatter(args.artifact_dir)
    report = formatter.format()
    print(f"优化报告: {report.skills}")
    print(f"  数据集: {report.dataset}")
    print(f"  轮数: {report.epochs_completed}")
    print(f"  得分: {report.score_before} → {report.score_after} ({report.improvement})")
    print(f"  编辑数: {report.edits_applied}")
    print(f"  产物: {report.artifact_dir}")


if __name__ == "__main__":
    main()
