from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

from skill_builder import SkillBuilderClient, SkillBuilderInput, SkillBuilderOptions
from skill_builder.adapters.author_tools import (
    AuthorCompletionState,
    create_author_completion_tool,
)
from skill_builder.adapters.scenario_tools import decode_scenario_transport_value
from skill_builder.api import _recovery_failure_context
from skill_builder.application.acceptance import (
    _business_output_invariant_issues,
    _choose_smoke_target,
    _safe_local_mode_arguments,
    _script_output_suffix,
)
from skill_builder.application.agent_submission import commit_candidate_completion
from skill_builder.application.artifact_digest import skill_artifact_sha256
from skill_builder.application.capability_observation import (
    python_runtime_call_capabilities,
)
from skill_builder.application.finding_ownership import (
    FindingPhase,
    finding_ownership,
)
from skill_builder.application.implementation_plan import persist_implementation_plan
from skill_builder.application.workflow import (
    _author_build_failure_is_recoverable,
    _repairable_candidate_failure,
)
from skill_builder.domain.scenario_contract import normalize_scenario_contract
from skill_builder.spi import (
    CallableAgentRunner,
    InMemoryStateStore,
    JsonFileStateStore,
    SkillBuilderAdapters,
)
from examples.host_background import SkillBuilderHost, public_result


def _builder_input(root: Path) -> SkillBuilderInput:
    return SkillBuilderInput(
        root=root,
        workspace_id="stability-matrix",
        skill_name="stability-matrix",
        display_name="Stability Matrix",
        description="Exercise generic generation stability boundaries.",
        version="0.1.0",
        user_message="Generate a reusable Skill.",
        materials_markdown="- inputs/source.md",
    )


def _tool(**_kwargs):
    def decorator(function):
        return function

    return decorator


def _scenario_failure() -> SimpleNamespace:
    lifecycle_failure = {
        "code": "agent_tool_input_invalid",
        "rootBlockerCode": "agent_tool_input_invalid",
        "terminationCode": "atomic_tool_transport_failed_after_rejection",
        "phase": "scenario",
        "last_submission_failure": {
            "error": "agent_tool_input_invalid",
            "message": "content field required",
        },
    }
    return SimpleNamespace(
        raw_output_text="",
        session_id="scenario-failed",
        files_read=[],
        files_listed=[],
        files_written=[],
        final_response={"status": "failed", "lifecycle_failure": lifecycle_failure},
        submission_status={"ok": False, "error": "agent_tool_input_invalid"},
    )


def _successful_scenario(root: Path, *, executable: bool = False) -> SimpleNamespace:
    facts: list[dict] = [
        {
            "kind": "purpose",
            "value": "Produce a reusable report.",
            "evidenceRefs": ["inputs/source.md"],
        },
        {
            "kind": "input",
            "value": (
                {
                    "format": "csv",
                    "fields": [{"name": "id", "type": "integer", "required": True}],
                }
                if executable
                else "Source material"
            ),
            "evidenceRefs": ["inputs/source.md"],
        },
        {
            "kind": "output",
            "value": "Generated report",
            "evidenceRefs": ["inputs/source.md"],
        },
    ]
    if executable:
        facts.append(
            {
                "kind": "script_requirement",
                "value": "Provide a Python CLI.",
                "evidenceRefs": ["inputs/source.md"],
                "sourceQuote": "Provide a Python CLI.",
            }
        )
    contract, issues = normalize_scenario_contract({"facts": facts, "conflicts": []})
    assert issues == []
    target = root / "validation" / "scenario_contract.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(contract), encoding="utf-8")
    return SimpleNamespace(
        raw_output_text="scenario complete",
        session_id="scenario-complete",
        files_read=["inputs/source.md"],
        files_listed=[],
        files_written=["validation/scenario_contract.json"],
        final_response={
            "completion_source": "scenario_contract_submission",
            "scenario_contract_hash": contract["semanticHash"],
        },
        submission_status={"ok": True},
    )


def test_scenario_transport_decoder_is_lossless() -> None:
    assert decode_scenario_transport_value('[{"kind":"purpose"}]', list) == [
        {"kind": "purpose"}
    ]
    assert decode_scenario_transport_value('{"kind":"purpose"}', list) == (
        '{"kind":"purpose"}'
    )
    assert decode_scenario_transport_value("not-json", list) == "not-json"


def test_runtime_capabilities_require_import_and_executable_call() -> None:
    assert python_runtime_call_capabilities(
        '"""Use requests and Playwright when available."""\n'
        "def simulate():\n    return {'source': 'fixture'}\n"
    ) == set()
    assert python_runtime_call_capabilities(
        "import requests as http\nhttp.get('https://example.invalid')\n"
    ) == {"api_runtime"}
    assert python_runtime_call_capabilities(
        "from playwright.sync_api import sync_playwright\n"
        "runtime = sync_playwright().start()\n"
        "browser = runtime.chromium.launch()\n"
    ) == {"browser_runtime"}


def test_output_suffix_and_safe_local_mode_matrix() -> None:
    assert _script_output_suffix(
        "parser.add_argument('--output', default='reports/result.md')"
    ) == ".md"
    assert _script_output_suffix(
        "parser.add_argument('--output')",
        documentation="Run with --output reports/result.xlsx",
    ) == ".xlsx"
    assert _safe_local_mode_arguments("parser.add_argument('--offline')") == [
        "--offline"
    ]
    assert _safe_local_mode_arguments(
        "parser.add_argument('--query-mode', choices=['online', 'offline'])"
    ) == ["--query-mode", "offline"]
    assert _safe_local_mode_arguments("parser.add_argument('--dry-run')") == []


def test_business_output_invariants_reject_invalid_json_and_counts(
    tmp_path: Path,
) -> None:
    mismatch = tmp_path / "mismatch.json"
    mismatch.write_text(
        json.dumps({"total_count": 3, "valid_count": 1, "error_count": 1}),
        encoding="utf-8",
    )
    non_finite = tmp_path / "non-finite.json"
    non_finite.write_text('{"score": NaN}', encoding="utf-8")

    assert {item["id"] for item in _business_output_invariant_issues(mismatch)} == {
        "business_output_count_mismatch"
    }
    assert {item["id"] for item in _business_output_invariant_issues(non_finite)} == {
        "business_output_json_invalid"
    }


def test_smoke_target_prefers_documented_business_fixture(tmp_path: Path) -> None:
    generated = tmp_path / "generated-skill"
    (generated / "scripts").mkdir(parents=True)
    (generated / "fixtures").mkdir()
    script = generated / "scripts" / "validate_records.py"
    script.write_text(
        "import argparse, csv\n"
        "parser=argparse.ArgumentParser(); parser.add_argument('--input')\n"
        "args=parser.parse_args(); csv.DictReader(open(args.input))\n",
        encoding="utf-8",
    )
    documented = generated / "fixtures" / "records.csv"
    documented.write_text("id\n1\n", encoding="utf-8")
    (generated / "fixtures" / "response.json").write_text("{}", encoding="utf-8")
    (generated / "SKILL.md").write_text(
        "---\nname: fixture-choice\ndescription: fixture choice\n---\n"
        "Run with `fixtures/records.csv`.\n",
        encoding="utf-8",
    )

    assert _choose_smoke_target(generated) == (script, documented)


def test_implementation_plan_requires_fixture_matching_input_format(
    tmp_path: Path,
) -> None:
    validation = tmp_path / "validation"
    validation.mkdir()
    (validation / "scenario_contract.json").write_text(
        json.dumps(
            {
                "inputs": [
                    {
                        "format": "csv",
                        "fields": [{"name": "id", "required": True}],
                    }
                ],
                "scriptRequirements": ["Provide a Python CLI."],
            }
        ),
        encoding="utf-8",
    )

    response_only = persist_implementation_plan(
        tmp_path,
        {
            "files": ["SKILL.md", "scripts/run.py", "fixtures/response.json"],
            "capabilityEntrypoints": {},
            "dependencies": [],
        },
    )
    with_input = persist_implementation_plan(
        tmp_path,
        {
            "files": ["SKILL.md", "scripts/run.py", "fixtures/input.csv"],
            "capabilityEntrypoints": {},
            "dependencies": [],
        },
    )

    assert response_only["error"] == "implementation_plan_invalid"
    assert "implementation_plan_business_fixture_missing" in {
        item["id"] for item in response_only["issues"]
    }
    assert with_input["ok"] is True


def test_recovery_context_includes_bounded_failure_and_rejected_draft(
    tmp_path: Path,
) -> None:
    diagnostics = tmp_path / "validation" / "diagnostics"
    diagnostics.mkdir(parents=True)
    (diagnostics / "candidate_lifecycle_failure.json").write_text(
        json.dumps(
            {
                "phase": "scenario",
                "error": "scenario_contract_invalid",
                "issues": ["facts require typed evidence"],
                "nextAction": "regenerate",
            }
        ),
        encoding="utf-8",
    )
    draft = tmp_path / ".skill-builder" / "drafts" / "scenario"
    draft.mkdir(parents=True)
    (draft / "current.json").write_text(
        json.dumps({"facts": [{"kind": "purpose", "value": "demo"}]}),
        encoding="utf-8",
    )

    context = _recovery_failure_context(tmp_path)

    assert "scenario_contract_invalid" in context
    assert "typed evidence" in context
    assert "rejectedScenarioDraft" in context
    assert len(context) <= 12000


def test_author_preflight_internal_error_stays_controller_owned(
    tmp_path: Path,
) -> None:
    generated = tmp_path / "generated-skill"
    generated.mkdir()
    (generated / "SKILL.md").write_text(
        "---\nname: preflight-error\ndescription: preflight error\n---\n",
        encoding="utf-8",
    )

    async def broken_preflight():
        raise ValueError("controller diagnostic failed")

    state = AuthorCompletionState()
    finish = create_author_completion_tool(
        tool=_tool,
        name="finish_authoring",
        task_mode="author_build",
        state=state,
        emit_event=None,
        root=tmp_path,
        build_preflight=broken_preflight,
    )

    result = asyncio.run(finish(summary="done"))

    assert result["error"] == "author_build_preflight_internal_error"
    assert state.completion_payload is None


def test_execution_repair_requires_exact_deterministic_evidence() -> None:
    repairable = {
        "ok": False,
        "stage": "draft_acceptance",
        "repairable": True,
        "failureOwners": ["package"],
        "blockingFindingIds": ["offline_smoke_failed"],
        "acceptance": {
            "findings": [
                {
                    "id": "offline_smoke_failed",
                    "severity": "fail",
                    "repairable": True,
                    "failureOwner": "package",
                    "path": "generated-skill/scripts/run.py",
                    "details": {
                        "command": ["python", "scripts/run.py", "fixtures/input.csv"],
                        "stderr": "NameError: missing_name",
                    },
                }
            ]
        },
    }
    missing_evidence = json.loads(json.dumps(repairable))
    missing_evidence["acceptance"]["findings"][0].pop("details")
    semantic_failure = json.loads(json.dumps(repairable))
    semantic_failure["blockingFindingIds"] = ["offline_output_evidence_mismatch"]
    semantic_failure["acceptance"]["findings"][0]["id"] = (
        "offline_output_evidence_mismatch"
    )

    assert _repairable_candidate_failure(repairable) is True
    assert _repairable_candidate_failure(missing_evidence) is False
    assert _repairable_candidate_failure(semantic_failure) is False


def test_new_execution_findings_have_explicit_build_ownership() -> None:
    for finding_id in (
        "runtime_fixture_mismatch",
        "business_output_invariant_failed",
        "external_input_validation_missing",
        "offline_output_evidence_mismatch",
    ):
        ownership = finding_ownership(finding_id)
        assert ownership is not None
        assert ownership.phase == FindingPhase.BUILD


def test_scenario_recovery_is_bounded_and_continues_to_author(
    tmp_path: Path,
) -> None:
    phases: list[str] = []

    async def run_agent(request):
        phases.append(request.run_phase)
        if phases == ["scenario"]:
            return _scenario_failure()
        if request.run_phase == "scenario":
            assert "agent_tool_input_invalid" in request.user_message
            return _successful_scenario(tmp_path)
        assert request.run_phase == "author"
        generated = tmp_path / "generated-skill"
        generated.mkdir(parents=True, exist_ok=True)
        (generated / "SKILL.md").write_text(
            "---\nname: recovered-skill\ndescription: recovered\n---\n",
            encoding="utf-8",
        )
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
        committed = commit_candidate_completion(
            root=tmp_path,
            completion={"summary": "recovered"},
        )
        return SimpleNamespace(
            raw_output_text="author complete",
            session_id="author-complete",
            files_read=[],
            files_listed=[],
            files_written=["generated-skill/SKILL.md"],
            final_response=committed["completion"],
            submission_status=committed,
        )

    client = SkillBuilderClient(
        adapters=SkillBuilderAdapters(
            state_store=InMemoryStateStore(),
            agent_runner=CallableAgentRunner(run_agent),
        )
    )

    execution = asyncio.run(
        client.build(
            _builder_input(tmp_path),
            options=SkillBuilderOptions(run_phase="workflow"),
        )
    )

    assert phases == ["scenario", "scenario", "author"]
    assert execution.status.value == "ready"


def test_author_build_checkpoint_retries_once_after_exception(
    tmp_path: Path,
) -> None:
    phases: list[str] = []
    attempts = 0

    async def run_agent(request):
        nonlocal attempts
        phases.append(request.run_phase)
        if request.run_phase == "scenario":
            return _successful_scenario(tmp_path, executable=True)
        assert request.run_phase == "author_build"
        attempts += 1
        generated = tmp_path / "generated-skill"
        (generated / "scripts").mkdir(parents=True, exist_ok=True)
        (generated / "fixtures").mkdir(exist_ok=True)
        (generated / "SKILL.md").write_text(
            "---\nname: resumed-build\ndescription: resumed build\n---\n"
            "Run `python scripts/run.py --input fixtures/input.csv --output report.json`.\n",
            encoding="utf-8",
        )
        (generated / "scripts" / "run.py").write_text(
            "import argparse, json\n"
            "p=argparse.ArgumentParser(); p.add_argument('--input'); "
            "p.add_argument('--output'); a=p.parse_args(); "
            "json.dump({'count': 1}, open(a.output, 'w'))\n",
            encoding="utf-8",
        )
        (generated / "fixtures" / "input.csv").write_text(
            "id\n1\n",
            encoding="utf-8",
        )
        if attempts == 1:
            raise RuntimeError("phase timeout with checkpoint")
        assert "author_build_interrupted" in request.user_message
        plan = persist_implementation_plan(
            tmp_path,
            {
                "files": ["SKILL.md", "scripts/run.py", "fixtures/input.csv"],
                "capabilityEntrypoints": {},
                "dependencies": [],
            },
        )
        assert plan["ok"] is True
        return SimpleNamespace(
            raw_output_text="author build complete",
            session_id="author-build-complete",
            files_read=[],
            files_listed=[],
            files_written=[],
            final_response={
                "status": "build_ready",
                "summary": "production package complete",
                "completion_source": "author_build_completed",
                "build_preflight": {
                    "schemaVersion": "skill-builder-author-build-preflight/v1",
                    "artifactSha256": skill_artifact_sha256(generated),
                    "blockingFindingIds": [],
                },
            },
        )

    client = SkillBuilderClient(
        adapters=SkillBuilderAdapters(
            state_store=InMemoryStateStore(),
            agent_runner=CallableAgentRunner(run_agent),
        )
    )

    execution = asyncio.run(client.build(_builder_input(tmp_path)))

    assert phases == ["scenario", "author_build", "author_build"]
    assert execution.status.value in {"ready", "needs_review"}
    assert execution.failure is None


def test_author_build_full_recovery_only_accepts_incomplete_lifecycle(
    tmp_path: Path,
) -> None:
    generated = tmp_path / "generated-skill"
    generated.mkdir()
    (generated / "SKILL.md").write_text(
        "---\nname: incomplete-build\ndescription: incomplete\n---\n",
        encoding="utf-8",
    )

    assert _author_build_failure_is_recoverable(
        tmp_path,
        {"error": "controller_implementation_plan_invalid"},
    )
    assert not _author_build_failure_is_recoverable(
        tmp_path,
        {"error": "author_build_preflight_failed"},
    )


def test_standalone_host_facade_builds_projects_and_exports(
    tmp_path: Path,
) -> None:
    async def run_agent(request):
        if request.run_phase == "scenario":
            return _successful_scenario(tmp_path)
        assert request.run_phase == "author"
        generated = tmp_path / "generated-skill"
        generated.mkdir(parents=True, exist_ok=True)
        (generated / "SKILL.md").write_text(
            "---\nname: host-facade\ndescription: host facade\n---\n",
            encoding="utf-8",
        )
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
        committed = commit_candidate_completion(
            root=tmp_path,
            completion={"summary": "host candidate"},
        )
        return SimpleNamespace(
            raw_output_text="author complete",
            session_id="host-author",
            files_read=[],
            files_listed=[],
            files_written=["generated-skill/SKILL.md"],
            final_response=committed["completion"],
            submission_status=committed,
        )

    host = SkillBuilderHost(tmp_path)
    host.client = SkillBuilderClient(
        adapters=SkillBuilderAdapters(
            state_store=JsonFileStateStore(tmp_path / ".skill-builder" / "state"),
            agent_runner=CallableAgentRunner(run_agent),
        )
    )
    value = _builder_input(tmp_path)
    output = tmp_path / "host-facade.zip"

    async def exercise():
        execution = await host.start_build(value)
        payload = public_result(host.client, execution)
        digest = host.export(execution, output)
        return execution, payload, digest

    execution, payload, digest = asyncio.run(exercise())

    assert execution.status.value == "ready"
    assert payload["status"] == "ready"
    assert payload["validation_status"] in {"pass", "warn"}
    assert output.is_file()
    assert digest
