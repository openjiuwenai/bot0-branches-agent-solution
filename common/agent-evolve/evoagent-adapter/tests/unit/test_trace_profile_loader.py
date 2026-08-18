"""ProfileRegistry / load_profiles 测试。"""

from __future__ import annotations

import tempfile
from pathlib import Path

from agent_adapter.trace_profile.loader import ProfileRegistry, load_profiles
from agent_adapter.trace_profile.models import TraceProfile


YAML_CONTENT = """
profiles:
  edp_agent:
    service_name: "EDPAgent"
    llm_span_prefix: "llm."
    tool_span_prefix: "tool."
    root_span_name: "http.request"
    prompt_extraction:
      attr: "gen_ai.prompt"
      sub_key: "messages"
      format: "python_repr"
    response_extraction:
      fields:
        - attr: "gen_ai.completion"
          sub_key: "outputs"
          format: "python_repr"
      combine_as: "assistant_message"
    ingest_filter:
      include_prefixes: ["llm.", "tool."]
  opencode:
    service_name: "opencode"
    llm_span_prefix: "ai.streamText"
    tool_span_prefix: "ai.toolCall"
    prompt_extraction:
      attr: "ai.prompt"
      sub_key: "messages"
      format: "json"
    response_extraction:
      fields:
        - attr: "ai.response"
          sub_key: "text"
      combine_as: "assistant_message"
"""


class TestProfileRegistry:
    def test_get_by_service_name(self):
        p1 = TraceProfile(name="a", service_name="svc-a")
        p2 = TraceProfile(name="b", service_name="svc-b")
        reg = ProfileRegistry({"a": p1, "b": p2})
        assert reg.get_by_service_name("svc-a") is p1
        assert reg.get_by_service_name("svc-b") is p2
        assert reg.get_by_service_name("unknown") is None

    def test_get_by_profile_name(self):
        p1 = TraceProfile(name="a", service_name="svc-a")
        reg = ProfileRegistry({"a": p1})
        assert reg.get_by_profile_name("a") is p1
        assert reg.get_by_profile_name("b") is None

    def test_get_by_agent_name(self):
        p1 = TraceProfile(name="edp_agent", service_name="EDPAgent")

        class FakeAgent:
            def __init__(self, name, trace_profile):
                self.name = name
                self.trace_profile = trace_profile

        agents = [
            FakeAgent("edp", "edp_agent"),
            FakeAgent("other", None),
        ]
        reg = ProfileRegistry({"edp_agent": p1})
        assert reg.get_by_agent_name("edp", agents) is p1
        assert reg.get_by_agent_name("other", agents) is None
        assert reg.get_by_agent_name("unknown", agents) is None


class TestLoadProfiles:
    def test_load_from_yaml(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False, encoding="utf-8") as f:
            f.write(YAML_CONTENT)
            yaml_path = Path(f.name)

        try:
            reg = load_profiles(yaml_path)
            edp = reg.get_by_profile_name("edp_agent")
            assert edp is not None
            assert edp.service_name == "EDPAgent"
            assert edp.llm_span_prefix == "llm."
            assert edp.prompt_extraction.format == "python_repr"

            oc = reg.get_by_profile_name("opencode")
            assert oc is not None
            assert oc.service_name == "opencode"
            assert oc.llm_span_prefix == "ai.streamText"
            assert oc.prompt_extraction.format == "json"

            assert reg.get_by_service_name("EDPAgent") is edp
            assert reg.get_by_service_name("opencode") is oc
        finally:
            yaml_path.unlink(missing_ok=True)

    def test_missing_file_returns_empty(self):
        reg = load_profiles(Path("/nonexistent/path.yaml"))
        assert reg.get_by_profile_name("anything") is None
        assert reg.get_by_service_name("anything") is None

    def test_empty_yaml_returns_empty(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False, encoding="utf-8") as f:
            f.write("")
            yaml_path = Path(f.name)

        try:
            reg = load_profiles(yaml_path)
            assert reg.get_by_profile_name("anything") is None
        finally:
            yaml_path.unlink(missing_ok=True)