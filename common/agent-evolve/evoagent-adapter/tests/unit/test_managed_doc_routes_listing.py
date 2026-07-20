"""Public HTTP contract tests for managed-document capability listing."""

import json
from pathlib import Path

from fastapi.testclient import TestClient

from agent_adapter.config import load_config

_LISTING_FIXTURE = (
    Path(__file__).parents[1] / "fixtures" / "studio_prompt" / "adapter_managed_doc_list.json"
)


def _make_app(tmp_path: Path, *, managed_docs_yaml: str):
    yaml_path = tmp_path / "config.yaml"
    docs_block = (
        f"    managed_docs:\n{managed_docs_yaml}" if managed_docs_yaml else "    managed_docs: []\n"
    )
    yaml_path.write_text(
        f"agents:\n  - name: edp\n    agent_url: http://localhost:8090\n{docs_block}",
        encoding="utf-8",
    )
    from agent_adapter.api.app import create_app

    return create_app(load_config(yaml_path))


def _restart_doc(kind: str, path: Path) -> str:
    return (
        f"      - kind: {kind}\n"
        f"        path: {path}\n"
        "        apply: restart\n"
        "        restart_cmd: docker restart edp\n"
        "        restart_timeout: 30\n"
        "        health_url: http://localhost:8090/health\n"
    )


def test_listing_returns_only_safe_capability_fields(tmp_path: Path) -> None:
    paths = [
        tmp_path / "AgentRule.md",
        tmp_path / "AGENTS.md",
        tmp_path / "CLAUDE.md",
    ]
    docs_yaml = "".join(
        _restart_doc(kind, path)
        for kind, path in zip(
            ["agent_rule", "agents_instructions", "claude_instructions"],
            paths,
            strict=True,
        )
    )
    app = _make_app(tmp_path, managed_docs_yaml=docs_yaml)

    response = TestClient(app).get("/api/v1/agents/edp/managed-docs")

    assert response.status_code == 200
    expected = json.loads(_LISTING_FIXTURE.read_text(encoding="utf-8"))
    assert response.json() == expected


def test_listing_returns_empty_items_for_registered_agent(tmp_path: Path) -> None:
    app = _make_app(tmp_path, managed_docs_yaml="")

    response = TestClient(app).get("/api/v1/agents/edp/managed-docs")

    assert response.status_code == 200
    assert response.json() == {"agent_name": "edp", "items": [], "total": 0}


def test_listing_excludes_file_only_documents_from_optimization_whitelist(
    tmp_path: Path,
) -> None:
    path = tmp_path / "Notes.md"
    docs_yaml = f"      - kind: notes\n        path: {path}\n        apply: file_only\n"
    app = _make_app(tmp_path, managed_docs_yaml=docs_yaml)

    response = TestClient(app).get("/api/v1/agents/edp/managed-docs")

    assert response.status_code == 200
    assert response.json() == {"agent_name": "edp", "items": [], "total": 0}


def test_listing_returns_contract_404_for_unknown_agent(tmp_path: Path) -> None:
    app = _make_app(tmp_path, managed_docs_yaml="")

    response = TestClient(app).get("/api/v1/agents/missing/managed-docs")

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "AGENT_NOT_FOUND"


def test_listing_success_schema_is_published_in_openapi(tmp_path: Path) -> None:
    app = _make_app(tmp_path, managed_docs_yaml="")

    operation = app.openapi()["paths"]["/api/v1/agents/{agent_name}/managed-docs"]["get"]

    assert operation["responses"]["200"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/ManagedDocListResponse"
    }
