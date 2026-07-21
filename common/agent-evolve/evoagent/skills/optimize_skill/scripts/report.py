#!/usr/bin/env python3
"""Summarize one EvoAgent optimization run from its artifact directory.

Examples:
  python skills/optimize_skill/scripts/report.py workspace/artifacts/<run_id>
  python skills/optimize_skill/scripts/report.py --artifact-dir <run_dir> -o report.md

The script only uses the standard library so it can also be copied next to an
artifact directory and run without installing evo-agent.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

EPOCH_RE = re.compile(r"^epoch_(\d+)$")
ACCEPTED_DECISIONS = {"candidate", "accepted"}


@dataclass(frozen=True)
class ValidationSummary:
    epoch: int
    status: str
    decision: str
    base_score: float | None
    candidate_score: float | None
    score_delta: float | None
    pass_rate: float | None
    pass_rate_delta: float | None
    evaluated_count: int | None
    attempted_count: int | None
    coverage: float | None
    failure_reason: str | None


@dataclass(frozen=True)
class SkillPatch:
    skill: str
    epoch: int
    path: Path
    content: str


@dataclass(frozen=True)
class RunSummary:
    run_dir: Path
    validations: tuple[ValidationSummary, ...]
    patches: tuple[SkillPatch, ...]
    skills: tuple[str, ...]


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"无法读取 JSON: {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"JSON 顶层必须是 object: {path}")
    return value


def _as_float(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _as_int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _epoch_number(path: Path) -> int | None:
    match = EPOCH_RE.fullmatch(path.name)
    return int(match.group(1)) if match else None


def _epoch_dirs(parent: Path) -> list[Path]:
    paths = [path for path in parent.iterdir() if path.is_dir() and _epoch_number(path) is not None]
    return sorted(paths, key=lambda path: _epoch_number(path) or 0)


def _selected_pass_rate(epoch_dir: Path, gate: dict[str, Any]) -> float | None:
    """Return the pass rate of the batch retained by the gate.

    New artifacts persist ``selected_failure_rate``.  If it is absent, derive
    the same value from validation/results.json.  Old artifacts contain
    neither, in which case the report deliberately renders an em dash.
    """
    failure_rate = _as_float(gate.get("selected_failure_rate"))
    if failure_rate is not None:
        return 1.0 - failure_rate

    results_path = epoch_dir / "validation" / "results.json"
    if not results_path.exists():
        return None
    results_data = _load_json(results_path)
    failure_rate = _as_float(results_data.get("selected_failure_rate"))
    if failure_rate is not None:
        return 1.0 - failure_rate

    threshold = _as_float(results_data.get("score_threshold"))
    rows = results_data.get("results")
    if threshold is None or not isinstance(rows, list):
        return None
    scores = [
        score
        for row in rows
        if isinstance(row, dict)
        if (score := _as_float(row.get("score"))) is not None
    ]
    if not scores:
        return None
    return sum(score >= threshold for score in scores) / len(scores)


def _collect_validations(run_dir: Path) -> tuple[ValidationSummary, ...]:
    validations: list[ValidationSummary] = []
    previous_pass_rate: float | None = None

    for epoch_dir in _epoch_dirs(run_dir):
        gate_path = epoch_dir / "gate_result.json"
        if not gate_path.exists():
            continue
        gate = _load_json(gate_path)
        epoch = _as_int(gate.get("epoch"))
        if epoch is None:
            epoch = _epoch_number(epoch_dir)
        assert epoch is not None

        base_score = _as_float(gate.get("base_score"))
        candidate_score = _as_float(gate.get("candidate_score"))
        score_delta = _as_float(gate.get("improvement"))
        if score_delta is None and base_score is not None and candidate_score is not None:
            score_delta = candidate_score - base_score

        pass_rate = _selected_pass_rate(epoch_dir, gate)
        pass_rate_delta = None
        if pass_rate is not None and previous_pass_rate is not None:
            pass_rate_delta = pass_rate - previous_pass_rate
        if pass_rate is not None:
            previous_pass_rate = pass_rate

        validations.append(
            ValidationSummary(
                epoch=epoch,
                status=str(gate.get("status", "valid")),
                decision=str(gate.get("decision", "unknown")),
                base_score=base_score,
                candidate_score=candidate_score,
                score_delta=score_delta,
                pass_rate=pass_rate,
                pass_rate_delta=pass_rate_delta,
                evaluated_count=_as_int(gate.get("evaluated_count")),
                attempted_count=_as_int(gate.get("attempted_count")),
                coverage=_as_float(gate.get("coverage")),
                failure_reason=(
                    str(gate["failure_reason"]) if gate.get("failure_reason") is not None else None
                ),
            )
        )
    return tuple(validations)


def _summary_skills(run_dir: Path) -> tuple[str, ...]:
    summary_path = run_dir / "summary.json"
    if not summary_path.exists():
        return ()
    summary = _load_json(summary_path)
    raw_skills = summary.get("skills", [])
    if not isinstance(raw_skills, list):
        return ()
    return tuple(str(skill) for skill in raw_skills)


def _skill_dirs(run_dir: Path) -> list[Path]:
    return sorted(
        path
        for path in run_dir.iterdir()
        if path.is_dir()
        and _epoch_number(path) is None
        and any(child.is_dir() and _epoch_number(child) is not None for child in path.iterdir())
    )


def _artifact_epoch_offset(run_dir: Path, skill_dirs: list[Path]) -> int:
    """Map gate epoch numbers to patch epoch numbers across artifact schemas."""
    gate_epochs = [
        number
        for path in _epoch_dirs(run_dir)
        if (path / "gate_result.json").exists()
        if (number := _epoch_number(path)) is not None
    ]
    skill_epochs = [
        number
        for skill_dir in skill_dirs
        for path in _epoch_dirs(skill_dir)
        if (number := _epoch_number(path)) is not None
    ]
    if not skill_dirs:
        skill_epochs = [
            number
            for path in _epoch_dirs(run_dir)
            if any(path.glob("step_*/applied_diff.patch"))
            if (number := _epoch_number(path)) is not None
        ]
    if gate_epochs and skill_epochs and min(gate_epochs) == 1 and min(skill_epochs) == 0:
        return 1
    return 0


def _collect_patches(
    run_dir: Path,
    validations: tuple[ValidationSummary, ...],
    summary_skills: tuple[str, ...],
) -> tuple[SkillPatch, ...]:
    accepted_epochs = {
        validation.epoch
        for validation in validations
        if validation.status == "valid" and validation.decision.lower() in ACCEPTED_DECISIONS
    }
    if not accepted_epochs:
        return ()

    patches: list[SkillPatch] = []
    skill_dirs = _skill_dirs(run_dir)
    offset = _artifact_epoch_offset(run_dir, skill_dirs)

    for skill_dir in skill_dirs:
        for gate_epoch in sorted(accepted_epochs):
            patch_path = skill_dir / f"epoch_{gate_epoch - offset}" / "applied_diff.patch"
            if not patch_path.exists():
                continue
            content = patch_path.read_text(encoding="utf-8").strip()
            if content:
                patches.append(SkillPatch(skill_dir.name, gate_epoch, patch_path, content))

    # Single-skill artifacts keep diffs below run_dir/epoch_N/step_N.
    if not skill_dirs:
        skill_name = summary_skills[0] if len(summary_skills) == 1 else "(single-skill)"
        for gate_epoch in sorted(accepted_epochs):
            artifact_epoch = gate_epoch - offset
            epoch_dir = run_dir / f"epoch_{artifact_epoch}"
            for patch_path in sorted(epoch_dir.glob("step_*/applied_diff.patch")):
                content = patch_path.read_text(encoding="utf-8").strip()
                if content:
                    patches.append(SkillPatch(skill_name, gate_epoch, patch_path, content))
    return tuple(patches)


def summarize_run(run_dir: Path) -> RunSummary:
    run_dir = run_dir.expanduser().resolve()
    if not run_dir.is_dir():
        raise ValueError(f"run 目录不存在或不是目录: {run_dir}")

    validations = _collect_validations(run_dir)
    summary_skills = _summary_skills(run_dir)
    skill_dirs = _skill_dirs(run_dir)
    skills = tuple(path.name for path in skill_dirs) or summary_skills
    patches = _collect_patches(run_dir, validations, summary_skills)
    return RunSummary(run_dir, validations, patches, skills)


def _number(value: float | None, *, signed: bool = False) -> str:
    if value is None:
        return "—"
    return f"{value:+.4f}" if signed else f"{value:.4f}"


def _percent(value: float | None, *, signed: bool = False) -> str:
    if value is None:
        return "—"
    return f"{value:+.1%}" if signed else f"{value:.1%}"


def _count(evaluated: int | None, attempted: int | None) -> str:
    if evaluated is None and attempted is None:
        return "—"
    evaluated_text = str(evaluated) if evaluated is not None else "?"
    attempted_text = str(attempted) if attempted is not None else "?"
    return f"{evaluated_text}/{attempted_text}"


def render_markdown(summary: RunSummary) -> str:
    lines = [
        "# 优化 Run 总结",
        "",
        f"- Run：`{summary.run_dir}`",
        f"- Validation 轮数：{len(summary.validations)}",
        f"- Skill：{', '.join(f'`{skill}`' for skill in summary.skills) or '—'}",
        "",
        "## Validation 变化",
        "",
    ]
    if summary.validations:
        lines.extend(
            [
                "| 轮次 | 状态 | Gate | 基线得分 | 候选得分 | 得分变化 | "
                "保留结果通过率 | 通过率变化 | 覆盖数 | 覆盖率 |",
                "|---:|---|---|---:|---:|---:|---:|---:|---:|---:|",
            ]
        )
        for item in summary.validations:
            decision = item.decision
            if item.failure_reason:
                decision = f"{decision} ({item.failure_reason})"
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(item.epoch),
                        item.status,
                        decision,
                        _number(item.base_score),
                        _number(item.candidate_score),
                        _number(item.score_delta, signed=True),
                        _percent(item.pass_rate),
                        _percent(item.pass_rate_delta, signed=True),
                        _count(item.evaluated_count, item.attempted_count),
                        _percent(item.coverage),
                    ]
                )
                + " |"
            )
        lines.extend(
            [
                "",
                "> “通过率变化”是本轮 gate 最终保留结果相对上一轮保留结果的变化；"
                "artifact 缺少逐 case validation 时显示 `—`。",
            ]
        )
    else:
        lines.append("未找到 `epoch_*/gate_result.json`。")

    lines.extend(["", "## 修改成功的 Skill Patch", ""])
    if not summary.patches:
        lines.append("没有找到 gate 接受且内容非空的 `applied_diff.patch`。")
    else:
        for patch in summary.patches:
            relative_path = patch.path.relative_to(summary.run_dir)
            lines.extend(
                [
                    f"### {patch.skill}（第 {patch.epoch} 轮）",
                    "",
                    f"来源：`{relative_path}`",
                    "",
                    "````diff",
                    patch.content,
                    "````",
                    "",
                ]
            )
    return "\n".join(lines).rstrip() + "\n"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="汇总一次优化 run 的 validation 和成功 patch")
    parser.add_argument("run_dir", nargs="?", type=Path, help="对应 run 的 artifact 文件夹")
    parser.add_argument(
        "--artifact-dir",
        dest="artifact_dir",
        type=Path,
        help="对应 run 的 artifact 文件夹（兼容旧用法）",
    )
    parser.add_argument("-o", "--output", type=Path, help="写入 Markdown 文件；默认输出到 stdout")
    args = parser.parse_args(argv)
    if args.run_dir is None and args.artifact_dir is None:
        parser.error("请提供 run_dir 或 --artifact-dir")
    if args.run_dir is not None and args.artifact_dir is not None:
        parser.error("run_dir 和 --artifact-dir 只能提供一个")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    run_dir = args.run_dir or args.artifact_dir
    assert run_dir is not None
    try:
        report = render_markdown(summarize_run(run_dir))
    except ValueError as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 2

    if args.output is None:
        print(report, end="")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
        print(f"报告已写入: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
