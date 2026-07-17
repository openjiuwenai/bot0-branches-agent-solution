"""Unit tests for ManagedDocService.update async orchestration + idempotency (T7)."""

from pathlib import Path

import pytest

from agent_adapter.config import AgentEntryConfig, ManagedDocConfig, ManagedDocDefaults
from agent_adapter.managed_doc.apply import ApplyResult
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService
from agent_adapter.managed_doc.storage import DocStorage
from agent_adapter.managed_doc.task import TaskRegistry, TaskStatus
from agent_adapter.managed_doc.validation import InvalidDocContentError

RULE_V1 = "---\nauthor: x\n---\n# v1\n"
RULE_V2 = "---\nauthor: x\n---\n# v2\n"


class FakeStrategy:
    """Controllable ApplyStrategy stub: returns a preset result, counts calls."""

    def __init__(self, result: ApplyResult) -> None:
        self.result = result
        self.call_count = 0

    async def apply(self) -> ApplyResult:
        self.call_count += 1
        return self.result


def _build_service(
    tmp_path: Path,
    *,
    strategy: FakeStrategy,
    ttl: int = 600,
) -> tuple[ManagedDocService, Path]:
    path = tmp_path / "host" / "edp" / "AgentRule.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(RULE_V1, encoding="utf-8")
    doc = ManagedDocConfig(kind="agent_rule", path=str(path), apply="file_only")
    agent = AgentEntryConfig(name="edp", managed_docs=[doc])
    registry = ManagedDocRegistry(agents=[agent], defaults=ManagedDocDefaults())
    tasks = TaskRegistry(ttl_seconds=ttl)
    service = ManagedDocService(
        registry=registry,
        task_registry=tasks,
        strategy_factory=lambda cfg: strategy,
    )
    return service, path


def _storage_for(path: Path) -> DocStorage:
    return DocStorage(kind="agent_rule", path=str(path), allow_root=path.parent)


# ── AC7.1 幂等 no-op ─────────────────────────────────────────────────


async def test_idempotent_no_op_when_meta_matches(tmp_path: Path) -> None:
    strategy = FakeStrategy(ApplyResult(ok=True))
    service, path = _build_service(tmp_path, strategy=strategy)
    # 预写 .meta 使 applied == RULE_V2 的 sha
    storage = _storage_for(path)
    storage.write_meta(revision=DocStorage.sha256(RULE_V2))

    result = await service.update("edp", "agent_rule", RULE_V2)

    assert result == {
        "success": True,
        "doc_kind": "agent_rule",
        "revision": DocStorage.sha256(RULE_V2),
        "pending_apply": False,
        "message": "already applied, no restart",
    }
    # 不建 task、不重启
    assert strategy.call_count == 0
    # 文件未被改写为 v2（仍是 v1，因为 no-op 不写文件）
    assert path.read_text(encoding="utf-8") == RULE_V1


# ── AC7.2 非幂等 → 写 file + 建 task + 202；后台 apply 成功 ───────────


async def test_update_writes_file_creates_task_then_applies(tmp_path: Path) -> None:
    strategy = FakeStrategy(ApplyResult(ok=True, down_seen=True))
    service, path = _build_service(tmp_path, strategy=strategy)

    result = await service.update("edp", "agent_rule", RULE_V2)

    assert "task_id" in result
    assert result["status"] == "PENDING"
    assert result["doc_kind"] == "agent_rule"
    # 文件已写为 v2
    assert path.read_text(encoding="utf-8") == RULE_V2
    # snapshot 首版（v1）已存
    assert _storage_for(path).read_snapshot() == RULE_V1

    # 等后台 apply 完成
    await service.join_apply(result["task_id"])

    state = service._tasks.get(result["task_id"])
    assert state.status is TaskStatus.SUCCEEDED
    assert state.revision == DocStorage.sha256(RULE_V2)
    assert state.pending_apply is False
    assert state.down_seen is True
    # .meta 已更新
    assert _storage_for(path).read_revision() == DocStorage.sha256(RULE_V2)
    assert strategy.call_count == 1


# ── AC7.3 崩溃一致性：apply 失败 → .meta 不动 + pending_apply=true ─────


async def test_apply_failure_leaves_meta_unchanged(tmp_path: Path) -> None:
    strategy = FakeStrategy(ApplyResult(ok=False, error="health never green"))
    service, path = _build_service(tmp_path, strategy=strategy)

    result = await service.update("edp", "agent_rule", RULE_V2)
    await service.join_apply(result["task_id"])

    state = service._tasks.get(result["task_id"])
    assert state.status is TaskStatus.FAILED
    assert state.last_error == "health never green"
    assert state.pending_apply is True
    # .meta 未写（仍 None）
    assert _storage_for(path).read_revision() is None
    # 文件已是 v2（pending_apply=true 暴露落差）
    assert path.read_text(encoding="utf-8") == RULE_V2


# ── AC7.4 V2 失败 → 400 不落盘 ───────────────────────────────────────


async def test_v2_failure_does_not_write_file(tmp_path: Path) -> None:
    strategy = FakeStrategy(ApplyResult(ok=True))
    service, path = _build_service(tmp_path, strategy=strategy)

    bad_content = "# no frontmatter\njust body\n"
    with pytest.raises(InvalidDocContentError):
        await service.update("edp", "agent_rule", bad_content)

    # 文件不变
    assert path.read_text(encoding="utf-8") == RULE_V1
    # 不建 task、不调 apply
    assert strategy.call_count == 0


# ── AC7.5 崩溃续跑：重发 update 收敛 ────────────────────────────────


async def test_crash_resume_re_send_converges(tmp_path: Path) -> None:
    # 第一次：apply 失败（模拟崩溃前未生效）
    strategy = FakeStrategy(ApplyResult(ok=False, error="crashed"))
    service, path = _build_service(tmp_path, strategy=strategy)

    first = await service.update("edp", "agent_rule", RULE_V2)
    await service.join_apply(first["task_id"])
    assert _storage_for(path).read_revision() is None  # .meta 未更新

    # 重发同一内容：.meta 仍 != new_sha → 不 no-op，重新 apply
    strategy.result = ApplyResult(ok=True)  # 这次成功
    second = await service.update("edp", "agent_rule", RULE_V2)
    assert "task_id" in second
    assert second["task_id"] != first["task_id"]
    await service.join_apply(second["task_id"])

    # .meta 现已更新，再发同一内容 → no-op
    strategy.result = ApplyResult(ok=True)
    third = await service.update("edp", "agent_rule", RULE_V2)
    assert third["success"] is True
    assert third["pending_apply"] is False
    # 两次 apply（第一次失败 + 第二次成功；第三次 no-op 不 apply）
    assert strategy.call_count == 2


# ── AC7.5 变体：apply 成功后重发同内容 → no-op ───────────────────────


async def test_re_send_after_success_is_no_op(tmp_path: Path) -> None:
    strategy = FakeStrategy(ApplyResult(ok=True))
    service, path = _build_service(tmp_path, strategy=strategy)

    first = await service.update("edp", "agent_rule", RULE_V2)
    await service.join_apply(first["task_id"])
    assert _storage_for(path).read_revision() == DocStorage.sha256(RULE_V2)

    # 再发同一内容 → no-op
    again = await service.update("edp", "agent_rule", RULE_V2)
    assert again["success"] is True
    assert again["pending_apply"] is False
    assert strategy.call_count == 1  # 没有第二次 apply
