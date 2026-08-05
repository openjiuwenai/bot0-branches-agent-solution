"""Live Adapter E2E for AgentRule managed-document optimization."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import pytest

pytestmark = [
    pytest.mark.managed_doc_e2e,
    pytest.mark.skipif(
        os.environ.get("EVO_RUN_MANAGED_DOC_E2E") != "1",
        reason="set EVO_RUN_MANAGED_DOC_E2E=1 to run the live Adapter E2E",
    ),
]


def test_agent_rule_optimization_changes_live_agent_behavior(tmp_path: Path) -> None:
    """A real managed-doc apply restarts the agent and changes its next answer."""
    from tests.e2e.support.harness import ManagedDocE2EHarness

    with ManagedDocE2EHarness(tmp_path) as harness:
        assert harness.ask("Which rule version is active?") == "OLD"

        report = harness.optimize()

        snapshot = harness.get_managed_doc()
        task = harness.get_task(report.managed_doc_task_ids[-1])
        assert task["status"] == "SUCCEEDED"
        assert task["down_seen"] is True
        assert snapshot["pending_apply"] is False
        assert snapshot["file_revision"] == snapshot["applied_revision"]
        assert task["revision"] == snapshot["applied_revision"]
        assert snapshot["content"] == report.managed_doc_content_after
        assert (
            hashlib.sha256(snapshot["content"].encode()).hexdigest() == snapshot["applied_revision"]
        )
        assert harness.ask("Which rule version is active now?") == "NEW"

        cleaned_traces = harness.get_optimization_traces()
        cleaned_answers = {trace["messages"][-1]["content"] for trace in cleaned_traces}
        assert {"OLD", "NEW"} <= cleaned_answers
        assert all(
            [message["role"] for message in trace["messages"]] == ["user", "assistant"]
            for trace in cleaned_traces
        )

        assert report.managed_doc_kind == "agent_rule"
        assert report.epochs_completed == 1
        assert report.edits_applied == 1
        assert "candidate" in report.gate_results
        assert "ANSWER=OLD" in (report.managed_doc_content_before or "")
        assert "ANSWER=NEW" in (report.managed_doc_content_after or "")
        assert (report.artifact_dir / "managed_doc_before.md").read_text(
            encoding="utf-8"
        ) == report.managed_doc_content_before
        assert (report.artifact_dir / "managed_doc_final.md").read_text(
            encoding="utf-8"
        ) == report.managed_doc_content_after
        ledger = json.loads(
            (report.artifact_dir / "managed_doc_tasks.json").read_text(encoding="utf-8")
        )
        assert tuple(ledger["task_ids"]) == report.managed_doc_task_ids
        task_records = [record for record in ledger["doc_kind_records"] if record["task_id"]]
        assert all(record["status"] == "SUCCEEDED" for record in task_records)
        assert all(record["total_time"] >= record["post_time"] >= 0 for record in task_records)
        assert all(record["total_time"] >= record["poll_time"] >= 0 for record in task_records)
        diff = (report.artifact_dir / "managed_doc_diff.patch").read_text(encoding="utf-8")
        assert "-ANSWER=OLD" in diff
        assert "+ANSWER=NEW" in diff
