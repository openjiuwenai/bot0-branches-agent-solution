"""Adapter sidecar 通信层 — AdapterClient + RemoteAgent + Operator 工厂。"""

from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.operator import build_skill_document_operator
from evo_agent.adapter_client.remote_agent import RemoteAgent

__all__ = [
    "AdapterClient",
    "RemoteAgent",
    "build_skill_document_operator",
]
