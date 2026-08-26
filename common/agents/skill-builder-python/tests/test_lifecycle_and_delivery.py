from __future__ import annotations

import asyncio
import json
import zipfile
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace

from skill_builder import (
    SkillBuilderClient,
    SkillBuilderInput,
    SkillBuilderOptions,
    SkillBuilderStatus,
)
from skill_builder.application.agent_submission import (
    commit_candidate_completion,
    ensure_workspace_package_revision,
)
from skill_builder.application.implementation_plan import persist_implementation_plan
from skill_builder.domain.scenario_contract import normalize_scenario_contract
from skill_builder.spi import CallableAgentRunner, JsonFileStateStore, SkillBuilderAdapters


def builder_input(root: Path, workspace_id: str = "lifecycle") -> SkillBuilderInput:
    return SkillBuilderInput(
        root=root,
        workspace_id=workspace_id,
        skill_name="sample-knowledge-skill",
        display_name="Sample Knowledge Skill",
        description="Exercise the lifecycle with generic material.",
        version="0.1.0",
        user_message="Build a reusable Skill.",
        materials_markdown="- inputs/source.md",
    )


def write_skill(root: Path) -> None:
    target = root / "generated-skill" / "SKILL.md"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        "---\n"
        "name: sample-knowledge-skill\n"
        "description: Exercise the lifecycle with generic material.\n"
        "---\n\n"
        "# Sample Knowledge Skill\n\n"
        "Use the confirmed output format and supplied material.\n",
        encoding="utf-8",
    )


def fake_runner(phases: list[str]):
    async def run(request):
        phases.append(request.run_phase)
        if request.run_phase == "scenario":
            contract, issues = normalize_scenario_contract(
                {
                    "facts": [
                        {"kind": "purpose", "value": "Exercise lifecycle behavior"},
                        {"kind": "input", "value": "Generic material"},
                        {"kind": "output", "value": "Generated Skill"},
                    ],
                    "conflicts": [
                        {
                            "title": "Output format",
                            "description": "Choose the report format.",
                            "type": "select",
                            "defaultValue": "markdown",
                            "options": [
                                {"value": "markdown", "label": "Markdown"},
                                {"value": "json", "label": "JSON"},
                            ],
                        }
                    ],
                }
            )
            assert issues == []
            target = request.root / "validation" / "scenario_contract.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(contract), encoding="utf-8")
            return SimpleNamespace(
                raw_output_text="scenario complete",
                session_id="scenario-session",
                files_read=["inputs/source.md"],
                files_listed=[],
                files_written=["validation/scenario_contract.json"],
                final_response={
                    "completion_source": "scenario_contract_submission",
                    "scenario_contract_hash": contract["semanticHash"],
                },
                submission_status={"ok": True},
            )

        assert request.run_phase == "author"
        write_skill(request.root)
        plan = persist_implementation_plan(
            request.root,
            {
                "packageKind": "knowledge",
                "files": ["SKILL.md"],
                "capabilityEntrypoints": {},
                "dependencies": [],
            },
        )
        assert plan["ok"] is True
        committed = commit_candidate_completion(
            root=request.root,
            completion={"summary": "Fake Agent committed the candidate."},
        )
        assert committed["ok"] is True
        return SimpleNamespace(
            raw_output_text="author complete",
            session_id="author-session",
            files_read=["validation/scenario_contract.json"],
            files_listed=[],
            files_written=["generated-skill/SKILL.md"],
            final_response=committed["completion"],
            submission_status=committed,
        )

    return CallableAgentRunner(run)


def test_build_hitl_resume_validate_and_export_across_clients(tmp_path: Path) -> None:
    (tmp_path / "inputs").mkdir()
    (tmp_path / "inputs" / "source.md").write_text("# Policy\n", encoding="utf-8")
    state_root = tmp_path / "state"
    phases: list[str] = []
    adapters = SkillBuilderAdapters(
        state_store=JsonFileStateStore(state_root),
        agent_runner=fake_runner(phases),
    )
    first_client = SkillBuilderClient(adapters=adapters)
    value = builder_input(tmp_path)

    waiting = asyncio.run(
        first_client.build(value, options=SkillBuilderOptions(run_phase="workflow"))
    )
    assert waiting.status == SkillBuilderStatus.WAITING_FOR_USER
    assert waiting.pending_request is not None
    assert phases == ["scenario"]

    fields = waiting.pending_request.request["options"]
    decision_id = fields[0]["id"]
    second_client = SkillBuilderClient(adapters=adapters)
    ready = asyncio.run(
        second_client.resume(
            value.workspace_id,
            resume_token=waiting.pending_request.resume_token,
            answer={
                "status": "completed",
                "answer": {"decisions": {decision_id: "markdown"}},
            },
        )
    )

    assert phases == ["scenario", "author"]
    assert ready.status == SkillBuilderStatus.READY
    assert ready.publishable is True
    assert len(ready.hitl_confirmations) == 1
    view = second_client.present(ready)
    assert view.workspace_status == "ready"
    assert view.validation_status in {"pass", "warn"}
    assert "publish" in {item.value for item in view.available_actions}

    archive = second_client.build_export_archive(ready)
    assert archive.content
    with zipfile.ZipFile(BytesIO(archive.content)) as package:
        assert "SKILL.md" in package.namelist()


def test_manual_edit_invalidates_receipt_and_requires_validation(tmp_path: Path) -> None:
    write_skill(tmp_path)
    assert ensure_workspace_package_revision(tmp_path)["ok"] is True
    store = JsonFileStateStore(tmp_path / "state")
    client = SkillBuilderClient(adapters=SkillBuilderAdapters(state_store=store))
    value = builder_input(tmp_path, "manual-edit")

    async def exercise():
        await client.resume_candidate(value)
        validated = await client.validate(value)
        assert validated.publishable is True
        skill = tmp_path / "generated-skill" / "SKILL.md"
        skill.write_text(skill.read_text(encoding="utf-8") + "\nChanged.\n", encoding="utf-8")
        return await client.invalidate_receipt(value.workspace_id)

    revised = asyncio.run(exercise())
    view = client.present(revised)
    assert revised.status == SkillBuilderStatus.DRAFT_READY
    assert revised.publishable is False
    assert view.validation_status == "not_run"
    assert "publish" not in {item.value for item in view.available_actions}


def test_optional_metadata_warning_remains_ready(tmp_path: Path) -> None:
    write_skill(tmp_path)
    metadata = tmp_path / "generated-skill" / "agents" / "openai.yaml"
    metadata.parent.mkdir(parents=True)
    metadata.write_text("interface: [not-an-object]\n", encoding="utf-8")
    assert ensure_workspace_package_revision(tmp_path)["ok"] is True
    client = SkillBuilderClient(
        adapters=SkillBuilderAdapters(state_store=JsonFileStateStore(tmp_path / "state"))
    )
    value = builder_input(tmp_path, "warning-ready")

    async def exercise():
        await client.resume_candidate(value)
        return await client.validate(value)

    execution = asyncio.run(exercise())
    view = client.present(execution)
    assert execution.status == SkillBuilderStatus.READY
    assert execution.publishable is True
    assert view.validation_status == "warn"


def test_explicit_repair_rolls_back_an_invalid_candidate(tmp_path: Path) -> None:
    write_skill(tmp_path)
    plan = persist_implementation_plan(
        tmp_path,
        {
            "packageKind": "knowledge",
            "files": ["SKILL.md"],
            "capabilityEntrypoints": {},
            "dependencies": [],
        },
    )
    assert plan["ok"] is True
    assert ensure_workspace_package_revision(tmp_path)["ok"] is True
    original = (tmp_path / "generated-skill" / "SKILL.md").read_text(encoding="utf-8")

    async def invalid_repair(request):
        assert request.run_phase == "author"
        (request.root / "generated-skill" / "SKILL.md").write_text(
            "# invalid repair\n",
            encoding="utf-8",
        )
        return SimpleNamespace(
            raw_output_text="invalid",
            session_id="repair-session",
            files_read=["generated-skill/SKILL.md"],
            files_listed=[],
            files_written=["generated-skill/SKILL.md"],
            final_response={"status": "ready"},
            submission_status=None,
        )

    client = SkillBuilderClient(
        adapters=SkillBuilderAdapters(
            state_store=JsonFileStateStore(tmp_path / "state"),
            agent_runner=CallableAgentRunner(invalid_repair),
        )
    )
    value = builder_input(tmp_path, "repair-rollback")

    async def exercise():
        await client.resume_candidate(value)
        validated = await client.validate(value)
        assert validated.publishable is True
        return validated, await client.repair(
            validated,
            instruction="Fix one mechanical formatting problem.",
        )

    validated, repaired = asyncio.run(exercise())
    assert repaired.turn_result is not None
    assert repaired.turn_result.status.value == "rolled_back"
    assert repaired.artifact_sha256 == validated.artifact_sha256
    assert (tmp_path / "generated-skill" / "SKILL.md").read_text(
        encoding="utf-8"
    ) == original
