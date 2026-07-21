"""AdapterConfig 轨迹/DB/kafka 新字段单测 (task 8a)。

验证 standard 模式所需配置项有合理默认 + 环境变量 (ADAPTER_ 前缀) 覆写。
"""

from __future__ import annotations

from agent_adapter.config import AdapterConfig


def test_trace_config_defaults():
    c = AdapterConfig()
    assert c.trace_source == "log"  # 默认保持现行为
    assert c.db_type == "postgres"
    assert c.pg_host == "postgres"
    assert c.pg_port == 5432
    assert c.pg_db == "agent_adapter"
    assert c.pg_user
    assert c.kafka_brokers == "kafka:9092"
    assert c.kafka_topic == "otlp_traces"
    assert c.kafka_group == "agent-adapter"
    assert c.trace_wait_timeout == 10.0


def test_trace_config_env_override(monkeypatch):
    monkeypatch.setenv("ADAPTER_TRACE_SOURCE", "standard")
    monkeypatch.setenv("ADAPTER_PG_HOST", "pg.example")
    monkeypatch.setenv("ADAPTER_PG_PORT", "6543")
    monkeypatch.setenv("ADAPTER_KAFKA_BROKERS", "b1:9092,b2:9092")
    monkeypatch.setenv("ADAPTER_TRACE_WAIT_TIMEOUT", "7.5")
    c = AdapterConfig()
    assert c.trace_source == "standard"
    assert c.pg_host == "pg.example"
    assert c.pg_port == 6543
    assert c.kafka_brokers == "b1:9092,b2:9092"
    assert c.trace_wait_timeout == 7.5
