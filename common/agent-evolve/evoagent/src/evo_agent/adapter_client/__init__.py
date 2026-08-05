"""Adapter sidecar 通信层 — AdapterClient + RemoteAgent + Operator 工厂。"""

from evo_agent.adapter_client.applier import (
    AppliedDocument,
    ManagedDocApplier,
    ManagedDocApplyRecord,
)
from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.content_policy import (
    ContentPolicy,
    ContentPolicyError,
    PassthroughPolicy,
    PreservingContentPolicy,
    ProtectedSection,
)
from evo_agent.adapter_client.operator import (
    ManagedDocOperator,
    build_managed_doc_operator,
    build_skill_document_operator,
)
from evo_agent.adapter_client.remote_agent import RemoteAgent
from evo_agent.adapter_client.types import (
    AlreadyApplied,
    ManagedDocSnapshot,
    ManagedDocUpdateResult,
    TaskState,
    UpdateStarted,
)

__all__ = [
    "AdapterClient",
    "AlreadyApplied",
    "AppliedDocument",
    "ContentPolicy",
    "ContentPolicyError",
    "ManagedDocApplier",
    "ManagedDocApplyRecord",
    "ManagedDocOperator",
    "ManagedDocSnapshot",
    "ManagedDocUpdateResult",
    "PassthroughPolicy",
    "PreservingContentPolicy",
    "ProtectedSection",
    "RemoteAgent",
    "TaskState",
    "UpdateStarted",
    "build_managed_doc_operator",
    "build_skill_document_operator",
]
