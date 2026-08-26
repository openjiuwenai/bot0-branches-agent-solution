# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Minimal standalone CLI for the Skill Builder package."""

from __future__ import annotations

import argparse
import asyncio
import json
import shutil
import sys
import uuid
from pathlib import Path
from typing import Any, Iterable

from skill_builder import SkillBuilderClient, SkillBuilderInput, SkillBuilderOptions
from skill_builder.spi import JsonFileStateStore, SkillBuilderAdapters


TEXT_MATERIAL_SUFFIXES = {
    ".csv",
    ".ini",
    ".json",
    ".jsonl",
    ".md",
    ".py",
    ".rst",
    ".toml",
    ".tsv",
    ".txt",
    ".yaml",
    ".yml",
}


class ConsoleHitlProvider:
    async def request(self, pending: Any) -> dict[str, Any]:
        request = pending.request
        print(f"\n[HITL] {request.get('title') or '需要确认'}", file=sys.stderr)
        print(str(request.get("message") or "请确认后继续。"), file=sys.stderr)
        options = request.get("options")
        if options:
            print(json.dumps(options, ensure_ascii=False, indent=2), file=sys.stderr)
        raw = await asyncio.to_thread(input, "请输入答案（支持 JSON，直接回车采用默认值）：")
        if not raw.strip():
            default_value = request.get("default_value")
            if isinstance(default_value, str):
                try:
                    answer: Any = json.loads(default_value)
                except json.JSONDecodeError:
                    answer = default_value
            else:
                answer = default_value
        else:
            try:
                answer = json.loads(raw)
            except json.JSONDecodeError:
                answer = raw
        return {"status": "completed", "answer": answer}


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="openjiuwen-skill-builder")
    subparsers = parser.add_subparsers(dest="command", required=True)

    build = subparsers.add_parser("build", help="Generate a Skill draft workspace")
    _add_workspace_arguments(build)
    build.add_argument("--input", action="append", default=[], help="Material file or directory; repeat as needed")
    build.add_argument("--message", default="请基于输入材料生成完整 Skill。")
    build.add_argument("--non-interactive", action="store_true", help="Disable terminal HITL prompts")

    validate = subparsers.add_parser("validate", help="Validate an existing Skill workspace")
    _add_workspace_arguments(validate)

    resume = subparsers.add_parser("resume", help="Resume a workspace waiting for HITL input")
    resume.add_argument("--workspace", required=True, type=Path)
    resume.add_argument("--workspace-id")
    resume.add_argument("--state-dir", type=Path)
    resume.add_argument("--resume-token", required=True)
    resume.add_argument("--answer", required=True, help="HITL answer as JSON or plain text")

    package = subparsers.add_parser("package", help="Package the last validated execution")
    package.add_argument("--workspace", required=True, type=Path)
    package.add_argument("--workspace-id")
    package.add_argument("--state-dir", type=Path)
    package.add_argument("--output", required=True, type=Path)
    package.add_argument("--publish", action="store_true")
    package.add_argument("--author", default="openjiuwen-user")
    return parser


def _add_workspace_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--workspace-id")
    parser.add_argument("--state-dir", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--display-name")
    parser.add_argument("--description", default="基于输入材料生成的业务 Skill。")
    parser.add_argument("--version", default="0.1.0")
    parser.add_argument("--tag", action="append", default=[])


def _workspace_id(args: argparse.Namespace, root: Path) -> str:
    return str(args.workspace_id or root.name or uuid.uuid4().hex)


def _state_root(args: argparse.Namespace, root: Path) -> Path:
    return (args.state_dir or root.parent / ".skill-builder-state").resolve()


def _iter_material_files(values: Iterable[str]) -> list[Path]:
    files: list[Path] = []
    for raw in values:
        path = Path(raw).expanduser().resolve()
        if path.is_file() and not path.is_symlink():
            files.append(path)
        elif path.is_dir():
            files.extend(
                candidate
                for candidate in sorted(path.rglob("*"))
                if candidate.is_file() and not candidate.is_symlink()
            )
        else:
            raise FileNotFoundError(f"Material path does not exist: {path}")
    return files


def _prepare_materials(root: Path, values: Iterable[str]) -> str:
    inputs_root = root / "inputs"
    inputs_root.mkdir(parents=True, exist_ok=True)
    sections: list[str] = []
    used_names: set[str] = set()
    for source in _iter_material_files(values):
        name = source.name
        if name in used_names:
            name = f"{source.stem}-{uuid.uuid4().hex[:8]}{source.suffix}"
        used_names.add(name)
        target = inputs_root / name
        shutil.copy2(source, target)
        relative_path = target.relative_to(root).as_posix()
        section = [f"## {source.name}", "", f"工作区路径：`{relative_path}`"]
        if source.suffix.lower() in TEXT_MATERIAL_SUFFIXES:
            content = source.read_text(encoding="utf-8", errors="replace")[:200_000].strip()
            if content:
                section.extend(["", content])
        else:
            section.extend(["", "该文件为二进制材料；独立 CLI 已保留原文件，请由运行时工具或宿主预处理器读取。"])
        sections.append("\n".join(section))
    return "\n\n".join(sections) if sections else "- 当前没有输入材料。"


def _builder_input(args: argparse.Namespace, *, root: Path, materials_markdown: str) -> SkillBuilderInput:
    return SkillBuilderInput(
        root=root,
        workspace_id=_workspace_id(args, root),
        skill_name=args.name,
        display_name=args.display_name or args.name,
        description=args.description,
        version=args.version,
        user_message=getattr(args, "message", "请验证当前 Skill 工作区。"),
        materials_markdown=materials_markdown,
        tags=tuple(args.tag),
    )


def _execution_payload(execution: Any) -> dict[str, Any]:
    return {
        "status": execution.status.value,
        "workspace_id": execution.workspace_id,
        "generated_root": str(execution.generated_root),
        "publishable": execution.publishable,
        "blockers": list(execution.blockers),
        "artifact_sha256": execution.artifact_sha256,
        "policy_version": execution.policy_version,
        "hitl_confirmation_count": len(execution.hitl_confirmations),
        "pending_request": execution.pending_request.to_dict() if execution.pending_request else None,
        "error": execution.error,
    }


async def _run(args: argparse.Namespace) -> int:
    root = args.workspace.expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    store = JsonFileStateStore(_state_root(args, root))
    adapters = SkillBuilderAdapters(
        state_store=store,
        hitl_provider=(
            None
            if getattr(args, "non_interactive", False) or not sys.stdin.isatty()
            else ConsoleHitlProvider()
        ),
    )
    client = SkillBuilderClient(adapters=adapters)

    if args.command == "build":
        materials = _prepare_materials(root, args.input)
        execution = await client.build(
            _builder_input(args, root=root, materials_markdown=materials),
            options=SkillBuilderOptions(),
        )
        print(json.dumps(_execution_payload(execution), ensure_ascii=False, indent=2))
        return 0 if execution.publishable else 2

    if args.command == "validate":
        execution = await client.validate(
            _builder_input(args, root=root, materials_markdown="独立验收现有工作区。"),
        )
        print(json.dumps(_execution_payload(execution), ensure_ascii=False, indent=2))
        return 0 if execution.publishable else 2

    if args.command == "resume":
        raw_answer = str(args.answer)
        try:
            parsed_answer = json.loads(raw_answer)
        except json.JSONDecodeError:
            parsed_answer = raw_answer
        answer = parsed_answer if isinstance(parsed_answer, dict) else {"value": parsed_answer}
        execution = await client.resume(
            _workspace_id(args, root),
            resume_token=args.resume_token,
            answer=answer,
        )
        print(json.dumps(_execution_payload(execution), ensure_ascii=False, indent=2))
        return 0 if execution.publishable else 2

    if args.command == "package":
        execution = await client.load(_workspace_id(args, root))
        if execution is None:
            raise RuntimeError("未找到该工作区的 Skill Builder 执行状态，请先运行 build 或 validate")
        archive = (
            client.build_publish_archive(execution, author=args.author)
            if args.publish
            else client.build_export_archive(execution)
        )
        output = args.output.expanduser().resolve()
        target = output / archive.filename if output.exists() and output.is_dir() else output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.content)
        print(json.dumps({"path": str(target), "sha256": archive.sha256}, ensure_ascii=False, indent=2))
        return 0

    raise RuntimeError(f"Unsupported command: {args.command}")


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        return asyncio.run(_run(args))
    except KeyboardInterrupt:
        return 130
    except Exception as exc:  # noqa: BLE001 - CLI must return a stable diagnostic
        print(json.dumps({"status": "failed", "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = ["main"]
