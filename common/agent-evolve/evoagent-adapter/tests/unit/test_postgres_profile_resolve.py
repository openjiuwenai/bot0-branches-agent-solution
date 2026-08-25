"""PostgresTraceRepository.resolve_profile 单测 (P2: profile-aware trace summary 接线)。

无需 PG: resolve_profile 是纯方法 (读 self._profile_registry + spans), 不触连接池,
验证 _reupsert_trace 据 service_name + telemetry.sdk.language 解析 profile 的接线逻辑。
"""

from __future__ import annotations

from agent_adapter.repository.postgres import PostgresTraceRepository
from agent_adapter.trace_profile.loader import ProfileRegistry
from agent_adapter.trace_profile.models import TraceProfile


def _repo() -> PostgresTraceRepository:
    # 不 start(), 不连 PG; resolve_profile 不依赖连接池
    return PostgresTraceRepository("postgres://u:p@127.0.0.1/db")


def test_no_registry_returns_none():
    """未注入 registry → None (调用方走 legacy 硬编码)。"""
    r = _repo()
    assert r.resolve_profile([{"service_name": "edp-agent"}]) is None


def test_resolves_by_service_name_and_language():
    """撞名 (同 service_name) 时按 telemetry.sdk.language 消歧取对 profile。"""
    py = TraceProfile(name="edp_agent", service_name="edp-agent", service_language="python")
    ja = TraceProfile(name="edp_agent_java", service_name="edp-agent", service_language="java")
    r = _repo()
    r.set_profile_registry(ProfileRegistry({"edp_agent": py, "edp_agent_java": ja}))

    spans_java = [{"service_name": "edp-agent",
                   "resource_attributes": {"telemetry.sdk.language": "java"}}]
    assert r.resolve_profile(spans_java) is ja

    spans_py = [{"service_name": "edp-agent",
                 "resource_attributes": {"telemetry.sdk.language": "python"}}]
    assert r.resolve_profile(spans_py) is py


def test_distinct_service_names_route_directly():
    """不撞名时按 service.name 直达 (语言可选)。"""
    a = TraceProfile(name="a", service_name="svc-a", service_language="python")
    r = _repo()
    r.set_profile_registry(ProfileRegistry({"a": a}))
    assert r.resolve_profile([{"service_name": "svc-a"}]) is a


def test_no_matching_profile_returns_none():
    """service 有候选但语言不匹配 (无语言无关兜底) → None → legacy。"""
    r = _repo()
    r.set_profile_registry(ProfileRegistry({
        "edp_agent_java": TraceProfile(
            name="edp_agent_java", service_name="edp-agent", service_language="java"
        ),
    }))
    spans = [{"service_name": "edp-agent",
              "resource_attributes": {"telemetry.sdk.language": "python"}}]
    assert r.resolve_profile(spans) is None


def test_no_service_name_returns_none():
    """空 service_name → 无法路由 → None。"""
    r = _repo()
    r.set_profile_registry(ProfileRegistry({
        "edp_agent": TraceProfile(name="edp_agent", service_name="edp-agent"),
    }))
    assert r.resolve_profile([{"service_name": ""}]) is None
    assert r.resolve_profile([{}]) is None


def test_language_taken_from_resource_attributes():
    """language 必须从 resource_attributes.telemetry.sdk.language 取 (扁平化后所在)。"""
    ja = TraceProfile(name="ja", service_name="svc", service_language="java")
    r = _repo()
    r.set_profile_registry(ProfileRegistry({"ja": ja}))
    spans = [{"service_name": "svc",
              "resource_attributes": {"telemetry.sdk.language": "java", "service.name": "svc"}}]
    assert r.resolve_profile(spans) is ja
