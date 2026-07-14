"""Unit tests for trace_cleaner module and cleaned-traces API endpoint."""

import json
import textwrap

import pytest

from agent_adapter.trace_cleaner import clean_traces


class TestCleanTraces:
    """clean_traces extracts and cleans LLM conversation from JSONL records."""

    def _make_generation_record(
        self,
        messages: list[dict] | None = None,
        output: dict | None = None,
    ) -> dict:
        """Build a type=GENERATION record matching the trace assembler schema."""
        if messages is None:
            messages = []
        record: dict = {
            "type": "GENERATION",
            "id": "gen-001",
            "input": {"messages": messages},
        }
        if output is not None:
            record["output"] = output
        return record

    def test_basic_cleaning(self):
        """Standard case: GENERATION record with user/assistant/tool messages."""
        records = [
            self._make_generation_record(
                messages=[
                    {"role": "user", "content": "推荐理财产品"},
                    {"role": "assistant", "content": "好的，我来为您推荐", "tool_calls": [{"function": {"name": "call_versatile"}}]},
                    {"role": "tool", "name": "call_versatile", "content": '{"result": "..."}'},
                    {"role": "assistant", "content": "根据结果推荐如下"},
                ],
                output={"role": "assistant", "content": "最终回答"},
            ),
        ]

        result = clean_traces(records, session_id="conv1", agent_name="edp_agent")

        assert result["session_id"] == "conv1"
        assert result["agent_name"] == "edp_agent"
        assert result["task_input"] == "推荐理财产品"
        assert result["trajectory"]["total_messages"] == 5  # 4 input + 1 output
        assert "call_versatile" in result["trajectory"]["tool_calls_used"]
        assert result["trajectory"]["summary"] == "5 messages, 1 unique tools: call_versatile"
        # 5 filtered messages (4 from input + 1 output), all user/assistant/tool
        assert len(result["messages"]) == 5

    def test_no_generation_returns_empty(self):
        """No GENERATION record → return empty dict."""
        records = [
            {"type": "TOOL", "id": "tool-001"},
            {"type": "SPAN", "id": "span-001"},
        ]
        result = clean_traces(records, session_id="conv1", agent_name="edp")
        assert result == {}

    def test_empty_records_returns_empty(self):
        """Empty records list → return empty dict."""
        result = clean_traces([], session_id="conv1", agent_name="edp")
        assert result == {}

    def test_last_generation_wins(self):
        """When multiple GENERATION records exist, the last one is used."""
        records = [
            self._make_generation_record(
                messages=[{"role": "user", "content": "第一轮"}],
                output={"role": "assistant", "content": "第一轮回答"},
            ),
            self._make_generation_record(
                messages=[{"role": "user", "content": "第二轮"}],
                output={"role": "assistant", "content": "第二轮回答"},
            ),
        ]
        result = clean_traces(records, session_id="conv1", agent_name="edp")
        assert result["task_input"] == "第二轮"

    def test_usage_metadata_removed(self):
        """usage_metadata field is stripped from filtered messages."""
        records = [
            self._make_generation_record(
                messages=[
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi", "usage_metadata": {"tokens": 42}},
                ],
            ),
        ]
        result = clean_traces(records, session_id="conv1", agent_name="edp")
        # usage_metadata should be removed
        for msg in result["messages"]:
            assert "usage_metadata" not in msg

    def test_non_target_roles_filtered(self):
        """Messages with role other than user/assistant/tool are excluded."""
        records = [
            self._make_generation_record(
                messages=[
                    {"role": "user", "content": "hello"},
                    {"role": "system", "content": "You are a helpful assistant"},
                    {"role": "assistant", "content": "hi"},
                ],
            ),
        ]
        result = clean_traces(records, session_id="conv1", agent_name="edp")
        roles = [msg["role"] for msg in result["messages"]]
        assert "system" not in roles
        assert "user" in roles
        assert "assistant" in roles

    def test_multiple_tools_collected(self):
        """Multiple unique tool names are collected and sorted."""
        records = [
            self._make_generation_record(
                messages=[
                    {"role": "user", "content": "do stuff"},
                    {"role": "assistant", "content": "", "tool_calls": [{"function": {"name": "ask_user"}}]},
                    {"role": "tool", "name": "ask_user", "content": "user input"},
                    {"role": "assistant", "content": "", "tool_calls": [{"function": {"name": "call_versatile"}}]},
                    {"role": "tool", "name": "call_versatile", "content": "result"},
                ],
            ),
        ]
        result = clean_traces(records, session_id="conv1", agent_name="edp")
        assert result["trajectory"]["tool_calls_used"] == ["ask_user", "call_versatile"]

    def test_no_output_still_works(self):
        """GENERATION without output field still returns input messages."""
        records = [
            self._make_generation_record(
                messages=[{"role": "user", "content": "hello"}],
            ),
        ]
        result = clean_traces(records, session_id="conv1", agent_name="edp")
        assert result["task_input"] == "hello"
        assert len(result["messages"]) == 1


class TestCleanedTracesEndpoint:
    """GET /api/v1/agents/{agent_name}/cleaned-traces/{conversation_id} endpoint."""

    def _write_archive(self, output_dir, conversation_id: str, records: list[dict]) -> None:
        output_dir.mkdir(parents=True, exist_ok=True)
        path = output_dir / f"{conversation_id}.jsonl"
        lines = [json.dumps(r, ensure_ascii=False) for r in records]
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_cleaned_traces_endpoint(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        output_dir = tmp_path / "output" / "edp"

        # Write a JSONL archive with a GENERATION record
        self._write_archive(output_dir, "conv1", [
            {
                "type": "GENERATION",
                "id": "gen-001",
                "input": {"messages": [
                    {"role": "user", "content": "推荐理财产品"},
                    {"role": "assistant", "content": "好的", "tool_calls": [{"function": {"name": "call_versatile"}}]},
                    {"role": "tool", "name": "call_versatile", "content": '{"result": "..."}'},
                ]},
                "output": {"role": "assistant", "content": "推荐如下"},
            },
        ])

        from agent_adapter.config import load_config
        from agent_adapter.api.app import create_app

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                output_dir: {output_dir}
                offset_file: {tmp_path}/offsets/edp.json
        """), encoding="utf-8")
        config = load_config(yaml_path)
        app = create_app(config)

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/agents/edp_agent/cleaned-traces/conv1")
        assert response.status_code == 200
        data = response.json()
        assert data["session_id"] == "conv1"
        assert data["agent_name"] == "edp_agent"
        assert data["task_input"] == "推荐理财产品"
        assert "call_versatile" in data["trajectory"]["tool_calls_used"]

    def test_cleaned_traces_nonexistent_agent_404(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        from agent_adapter.config import load_config
        from agent_adapter.api.app import create_app

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                output_dir: {tmp_path}/output/edp
                offset_file: {tmp_path}/offsets/edp.json
        """), encoding="utf-8")
        config = load_config(yaml_path)
        app = create_app(config)

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/agents/nonexistent/cleaned-traces/conv1")
        assert response.status_code == 404

    def test_cleaned_traces_no_generation_returns_empty(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        output_dir = tmp_path / "output" / "edp"

        # Write a JSONL archive with NO GENERATION record
        self._write_archive(output_dir, "conv1", [
            {"type": "TOOL", "id": "tool-001"},
        ])

        from agent_adapter.config import load_config
        from agent_adapter.api.app import create_app

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                output_dir: {output_dir}
                offset_file: {tmp_path}/offsets/edp.json
        """), encoding="utf-8")
        config = load_config(yaml_path)
        app = create_app(config)

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/agents/edp_agent/cleaned-traces/conv1")
        assert response.status_code == 200
        data = response.json()
        # No GENERATION → clean_traces returns {}
        assert data == {}
