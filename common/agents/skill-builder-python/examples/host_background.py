"""不依赖特定产品服务的最小 Skill Builder 宿主。"""

from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import replace
from pathlib import Path
from typing import Any

from skill_builder import (
    ExecutionAction,
    SkillBuilderClient,
    SkillBuilderExecution,
    SkillBuilderInput,
    SkillBuilderOptions,
    SkillBuilderTurnRequest,
)
from skill_builder.adapters import (
    AgentCoreProcessConfig,
    JiuwenboxExecutionPort,
    SubprocessAgentRunner,
)
from skill_builder.spi import (
    CallbackEventSink,
    JsonFileStateStore,
    SkillBuilderAdapters,
)
from skill_builder.host_support import reset_generated_outputs


async def print_event(
    event_type: str,
    summary: str,
    payload: dict[str, Any],
) -> None:
    del payload
    print(f"[{event_type}] {summary}")


def create_client(workspace_root: Path) -> SkillBuilderClient:
    state_root = workspace_root / ".skill-builder" / "state"
    runner = SubprocessAgentRunner(
        AgentCoreProcessConfig(
            run_root=workspace_root / ".skill-builder" / "agent-core-workers",
            max_concurrency=1,
            timeout_seconds=None,
        )
    )
    return SkillBuilderClient(
        adapters=SkillBuilderAdapters(
            state_store=JsonFileStateStore(state_root),
            event_sink=CallbackEventSink(print_event),
            agent_runner=runner,
            execution_port=JiuwenboxExecutionPort(),
        )
    )


class SkillBuilderHost:
    """小型宿主门面；生产环境应替换为宿主基础设施 adapter。"""

    def __init__(self, workspace_root: Path) -> None:
        self.workspace_root = workspace_root.resolve()
        self.client = create_client(self.workspace_root)
        # 仅用于示例的进程内锁。生产宿主需要任务表约束、分布式 lease
        # 或 StateStore CAS。
        self._write_lock = asyncio.Lock()

    def start_build(
        self,
        builder_input: SkillBuilderInput,
    ) -> asyncio.Task[SkillBuilderExecution]:
        return asyncio.create_task(self._build_locked(builder_input))

    async def _build_locked(
        self,
        builder_input: SkillBuilderInput,
    ) -> SkillBuilderExecution:
        async with self._write_lock:
            return await self.client.build(
                builder_input,
                options=SkillBuilderOptions(),
            )

    async def reconcile(
        self,
        builder_input: SkillBuilderInput,
        *,
        advance: bool = True,
    ) -> SkillBuilderExecution:
        async with self._write_lock:
            return await self.client.reconcile(
                builder_input,
                advance=advance,
            )

    async def resume_hitl(
        self,
        workspace_id: str,
        *,
        resume_token: str,
        answer: dict[str, Any],
    ) -> SkillBuilderExecution:
        async with self._write_lock:
            return await self.client.resume(
                workspace_id,
                resume_token=resume_token,
                answer=answer,
            )

    async def continue_failed(
        self,
        workspace_id: str,
        *,
        message: str | None = None,
    ) -> SkillBuilderExecution:
        """继续失败运行，不删除候选和检查点。"""

        async with self._write_lock:
            current = await self.client.load(workspace_id)
            if current is None:
                raise KeyError(workspace_id)
            if current.status.value != "failed":
                raise RuntimeError("continue requires a failed execution")
            recovery_message = self.client.build_recovery_message(
                current,
                kind="resume",
                user_message=message,
            )
            return await self.client.reconcile(
                replace(current.input, user_message=recovery_message),
                options=replace(current.options, run_phase="workflow"),
                hitl_confirmations=current.hitl_confirmations,
                advance=True,
            )

    async def retry_failed(
        self,
        workspace_id: str,
        *,
        message: str | None = None,
    ) -> SkillBuilderExecution:
        """保留持久输入材料并启动一次全新抽取。"""

        async with self._write_lock:
            current = await self.client.load(workspace_id)
            if current is None:
                raise KeyError(workspace_id)
            if current.status.value != "failed":
                raise RuntimeError("retry requires a failed execution")
            recovery_message = self.client.build_recovery_message(
                current,
                kind="retry",
                user_message=message,
            )
            confirmations = current.hitl_confirmations
            reset_generated_outputs(self.workspace_root)
            return await self.client.build(
                replace(current.input, user_message=recovery_message),
                options=replace(current.options, run_phase="workflow"),
                hitl_confirmations=confirmations,
            )

    async def load(self, workspace_id: str) -> SkillBuilderExecution | None:
        return await self.client.load(workspace_id)

    async def validate(
        self,
        execution: SkillBuilderExecution,
    ) -> SkillBuilderExecution:
        async with self._write_lock:
            return await self.client.validate(
                execution.input,
                hitl_confirmations=execution.hitl_confirmations,
            )

    async def repair(
        self,
        execution: SkillBuilderExecution,
        *,
        instruction: str,
    ) -> SkillBuilderExecution:
        """仅在结构化诊断确认可机械修复后调用。"""

        async with self._write_lock:
            return await self.client.repair(
                execution,
                instruction=instruction,
            )

    async def run_turn(
        self,
        workspace_id: str,
        *,
        message: str,
        requested_action: str = "auto",
    ) -> SkillBuilderExecution:
        request = SkillBuilderTurnRequest(
            message=message,
            requested_action=requested_action,
        )
        async with self._write_lock:
            return await self.client.run_turn(workspace_id, request)

    async def invalidate_receipt(
        self,
        workspace_id: str,
    ) -> SkillBuilderExecution:
        async with self._write_lock:
            return await self.client.invalidate_receipt(workspace_id)

    def present(self, execution: SkillBuilderExecution) -> dict[str, Any]:
        return public_result(self.client, execution)

    def export(
        self,
        execution: SkillBuilderExecution,
        target: Path,
    ) -> str:
        archive = self.client.build_export_archive(execution)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.content)
        return archive.sha256


def public_result(client: SkillBuilderClient, execution: Any) -> dict[str, Any]:
    view = client.present(execution)
    return {
        "workspace_id": execution.workspace_id,
        "status": view.workspace_status,
        "cursor": view.cursor.value,
        "draft_status": view.draft_status,
        "delivery_decision": view.delivery_decision.value,
        "validation_status": view.validation_status,
        "publishable": view.publishable,
        "summary": view.summary,
        "blockers": list(view.blockers),
        "available_actions": [item.value for item in view.available_actions],
        "artifact_sha256": execution.artifact_sha256,
        "acceptance": view.acceptance,
        "pending_request": (
            execution.pending_request.to_dict()
            if execution.pending_request is not None
            else None
        ),
        "failure": execution.failure.to_dict() if execution.failure else None,
    }


async def run_build(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    materials = args.materials.read_text(encoding="utf-8")
    host = SkillBuilderHost(workspace)
    client = host.client
    builder_input = SkillBuilderInput(
        root=workspace,
        workspace_id=args.workspace_id,
        skill_name=args.skill_name,
        display_name=args.display_name,
        description=args.description,
        version="0.1.0",
        user_message="请根据材料生成可运行的 Skill。",
        materials_markdown=materials,
        tags=("generated",),
    )

    task = host.start_build(builder_input)
    execution = await task
    print(json.dumps(public_result(client, execution), ensure_ascii=False, indent=2))

    view = client.present(execution)
    if ExecutionAction.EXPORT in view.available_actions and args.output is not None:
        archive_sha256 = host.export(execution, args.output)
        print(f"archive={args.output} sha256={archive_sha256}")
    return 0 if execution.status.value != "failed" else 1


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser()
    value.add_argument("--workspace", type=Path, required=True)
    value.add_argument("--workspace-id", required=True)
    value.add_argument("--materials", type=Path, required=True)
    value.add_argument("--skill-name", required=True)
    value.add_argument("--display-name", required=True)
    value.add_argument("--description", required=True)
    value.add_argument("--output", type=Path)
    return value


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run_build(parser().parse_args())))
