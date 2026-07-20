"""Orchestrates managed-doc content / update / restore.

content is synchronous (read file + read .meta → four-field response).
update/restore are async-task orchestrated (spec §6.1):

    lock → validate → idempotent .meta check → snapshot → atomic write →
    create task → spawn background apply → (on success) write .meta

The per-agent ``asyncio.Lock`` serializes update/restore only — it does NOT gate
``call_agent`` (spec D10). The handler acquires the lock but does not release it;
ownership transfers to the background ``asyncio.create_task``, whose ``finally``
releases it (spec §6.1 note). This lets the HTTP request return 202 immediately
while keeping same-agent updates serial.

The ApplyStrategy is injected via a factory so tests substitute a fake strategy
(T7); ``default_strategy_factory`` picks FileOnlyApply / RestartApply (T9).
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Callable
from pathlib import Path

import httpx
import structlog

from agent_adapter.config import ManagedDocConfig
from agent_adapter.managed_doc.apply import (
    ApplyResult,
    ApplyStrategy,
    FileOnlyApply,
    RestartApply,
)
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.storage import DocStorage
from agent_adapter.managed_doc.task import TaskRegistry, TaskState, TaskStatus
from agent_adapter.managed_doc.validation import validate

logger = structlog.get_logger(__name__)


class ManagedDocService:
    """Coordinator between registry, storage, task registry, and apply strategy."""

    def __init__(
        self,
        *,
        registry: ManagedDocRegistry,
        task_registry: TaskRegistry | None = None,
        strategy_factory: Callable[[ManagedDocConfig], ApplyStrategy] | None = None,
        shutdown_grace_timeout: float = 10.0,
    ) -> None:
        self._registry = registry
        self._tasks = task_registry or TaskRegistry()
        # Default factory builds FileOnlyApply / RestartApply (with a lazily-created
        # shared httpx client). Tests inject a fake factory to bypass real subprocess.
        self._strategy_factory = strategy_factory or self._default_strategy_factory
        self._shutdown_grace = shutdown_grace_timeout
        self._locks: dict[str, asyncio.Lock] = {}
        self._apply_tasks: dict[str, asyncio.Task[None]] = {}
        self._http_client: httpx.AsyncClient | None = None

    # ── helpers ─────────────────────────────────────────────────────

    def _config(self, agent_name: str, doc_kind: str) -> ManagedDocConfig:
        return self._registry.get(agent_name, doc_kind)

    def _storage(self, cfg: ManagedDocConfig) -> DocStorage:
        # Prefer an explicit allow_root (real path-traversal protection, spec D3);
        # fall back to the doc's parent dir when unset (only catches ``..`` escape).
        allow_root = Path(cfg.allow_root) if cfg.allow_root else Path(cfg.path).parent
        return DocStorage(
            kind=cfg.kind,
            path=cfg.path,
            allow_root=allow_root,
        )

    def _lock_for(self, agent_name: str) -> asyncio.Lock:
        if agent_name not in self._locks:
            self._locks[agent_name] = asyncio.Lock()
        return self._locks[agent_name]

    def _get_http_client(self) -> httpx.AsyncClient:
        """Lazily-created shared httpx client for RestartApply health probes."""
        if self._http_client is None:
            self._http_client = httpx.AsyncClient()
        return self._http_client

    def _default_strategy_factory(self, cfg: ManagedDocConfig) -> ApplyStrategy:
        """file_only → FileOnlyApply; restart → RestartApply (shared httpx client)."""
        if cfg.apply == "file_only":
            return FileOnlyApply()
        return RestartApply(cfg=cfg, client=self._get_http_client())

    # ── capability listing (sync, read-only) ────────────────────────

    def list_documents(self, agent_name: str) -> dict[str, object]:
        """Project restart-capable documents onto the safe optimization whitelist."""
        items: list[dict[str, object]] = []
        for cfg in self._registry.list_for_agent(agent_name):
            if cfg.apply != "restart":
                continue
            filename = Path(cfg.path).name
            items.append(
                {
                    "doc_kind": cfg.kind,
                    "display_name": filename,
                    "filename": filename,
                    "apply_mode": cfg.apply,
                    "max_task_seconds": self._registry.max_task_seconds(agent_name, cfg.kind),
                }
            )
        return {"agent_name": agent_name, "items": items, "total": len(items)}

    # ── content (sync, spec §6.3) ───────────────────────────────────

    def content(self, agent_name: str, doc_kind: str) -> dict[str, object]:
        cfg = self._config(agent_name, doc_kind)
        storage = self._storage(cfg)
        content = storage.read_file()
        file_sha = DocStorage.sha256(content)
        applied = storage.read_revision()  # None when no .meta
        return {
            "doc_kind": cfg.kind,
            "content": content,
            "file_revision": file_sha,
            "applied_revision": applied,
            "pending_apply": file_sha != applied,
            # G2.3: 暴露 apply 能力与时限上界，供消费方做 deadline 校验（file_only→0）。
            "apply_mode": cfg.apply,
            "max_task_seconds": self._registry.max_task_seconds(agent_name, doc_kind),
        }

    # ── task polling (spec §7.3) ────────────────────────────────────

    def get_task(self, task_id: str) -> TaskState:
        """Return the TaskState snapshot; raises TaskNotFoundError if expired."""
        return self._tasks.get(task_id)

    # ── update (async, spec §6.1) ──────────────────────────────────

    async def update(
        self,
        agent_name: str,
        doc_kind: str,
        content: str,
    ) -> dict[str, object]:
        cfg = self._config(agent_name, doc_kind)
        validate(content, cfg.max_content_bytes)  # InvalidDocContentError → 400, no write
        new_sha = DocStorage.sha256(content)
        storage = self._storage(cfg)

        lock = self._lock_for(agent_name)
        await lock.acquire()
        spawned = False
        try:
            # 幂等对 .meta（不是磁盘文件 — D8：崩溃续跑靠此重 apply）
            applied = storage.read_revision()
            if applied == new_sha:
                return {
                    "success": True,
                    "doc_kind": cfg.kind,
                    "revision": new_sha,
                    "pending_apply": False,
                    "message": "already applied, no restart",
                }

            # snapshot 首版（幂等）+ 原子写新内容
            storage.ensure_snapshot()
            storage.write_file_atomic(content)

            result = self._schedule_apply(lock, cfg, storage, new_sha, "update")
            spawned = True
            return result
        finally:
            if not spawned:
                lock.release()

    async def restore(
        self,
        agent_name: str,
        doc_kind: str,
    ) -> dict[str, object]:
        """Restore the doc from its first-update snapshot (spec §6.2).

        Reuses the same apply chain as update; only the content source differs
        (snapshot vs caller-supplied). No snapshot → DocNotFoundError → 404.
        """
        cfg = self._config(agent_name, doc_kind)
        storage = self._storage(cfg)

        lock = self._lock_for(agent_name)
        await lock.acquire()
        spawned = False
        try:
            snapshot = storage.read_snapshot()  # DocNotFoundError → 404 if absent
            # Defensive V2: the snapshot was valid when it was written.
            validate(snapshot, cfg.max_content_bytes)
            new_sha = DocStorage.sha256(snapshot)
            storage.write_file_atomic(snapshot)

            result = self._schedule_apply(lock, cfg, storage, new_sha, "restore")
            spawned = True
            return result
        finally:
            if not spawned:
                lock.release()

    def _schedule_apply(
        self,
        lock: asyncio.Lock,
        cfg: ManagedDocConfig,
        storage: DocStorage,
        new_sha: str,
        action: str,
    ) -> dict[str, object]:
        """Create a PENDING task and spawn the background apply (holds lock).

        The caller has already written the file; this wires the async apply +
        .meta update. Lock ownership transfers to the background task, whose
        ``finally`` releases it (spec §6.1 note).
        """
        task_state = self._tasks.create(
            doc_kind=cfg.kind,
            action=action,
            revision=new_sha,
            pending_apply=True,
        )
        self._spawn_apply(lock, cfg, storage, task_state, new_sha)
        return {
            "task_id": task_state.task_id,
            "status": "PENDING",
            "doc_kind": cfg.kind,
        }

    def _spawn_apply(
        self,
        lock: asyncio.Lock,
        cfg: ManagedDocConfig,
        storage: DocStorage,
        task_state: TaskState,
        new_sha: str,
    ) -> None:
        coro = self._run_apply(lock, cfg, storage, task_state, new_sha)
        task = asyncio.create_task(coro, name=f"managed-doc-apply-{task_state.task_id}")
        self._apply_tasks[task_state.task_id] = task

        def _on_done(t: asyncio.Task[None]) -> None:
            self._apply_tasks.pop(task_state.task_id, None)
            if t.cancelled():
                return  # shutdown cancel — expected, not a crash
            exc = t.exception()
            if exc is not None:
                logger.exception("apply_task_crashed", task_id=task_state.task_id)

        task.add_done_callback(_on_done)

    async def _run_apply(
        self,
        lock: asyncio.Lock,
        cfg: ManagedDocConfig,
        storage: DocStorage,
        task_state: TaskState,
        new_sha: str,
    ) -> None:
        if not lock.locked():
            raise RuntimeError(
                f"apply task {task_state.task_id} started without the per-agent lock "
                "— lock-passing protocol broken"
            )
        try:
            self._tasks.update(task_state.task_id, status=TaskStatus.RUNNING)
            strategy = self._strategy_factory(cfg)
            result: ApplyResult = await strategy.apply()
            if result.ok:
                storage.write_meta(revision=new_sha)
                self._tasks.update(
                    task_state.task_id,
                    status=TaskStatus.SUCCEEDED,
                    revision=new_sha,
                    pending_apply=False,
                    down_seen=result.down_seen,
                )
            else:
                self._tasks.update(
                    task_state.task_id,
                    status=TaskStatus.FAILED,
                    last_error=result.error,
                    pending_apply=True,
                    down_seen=result.down_seen,
                )
        except asyncio.CancelledError:
            # Adapter shutdown mid-apply: mark FAILED so no task is left stuck in
            # RUNNING (PENDING/RUNNING are never TTL-evicted), then re-raise so the
            # cancellation propagates and the finally releases the lock.
            self._tasks.update(
                task_state.task_id,
                status=TaskStatus.FAILED,
                last_error="cancelled during apply (adapter shutdown)",
                pending_apply=True,
            )
            raise
        except Exception as exc:
            logger.exception("apply_task_error", task_id=task_state.task_id)
            self._tasks.update(
                task_state.task_id,
                status=TaskStatus.FAILED,
                last_error=str(exc),
                pending_apply=True,
            )
        finally:
            lock.release()

    async def join_apply(self, task_id: str) -> None:
        """Await the background apply task for ``task_id`` (test/lifecycle use)."""
        task = self._apply_tasks.get(task_id)
        if task is not None:
            await task

    async def shutdown(self) -> None:
        """Cancel running apply tasks and await them within shutdown_grace (T11).

        Cancels each background apply task (propagates CancelledError into the
        strategy's await — e.g. health probe / subprocess wait) and awaits exit
        within ``shutdown_grace_timeout`` so adapter shutdown never hangs. The
        shared httpx client is closed last.
        """
        tasks = list(self._apply_tasks.values())
        for task in tasks:
            task.cancel()
        for task in tasks:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await asyncio.wait_for(task, timeout=self._shutdown_grace)
        if self._http_client is not None:
            with contextlib.suppress(Exception):
                await self._http_client.aclose()
            self._http_client = None
