# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Deterministic context controls for the optional OpenJiuwen adapter.

Draft file bodies are durable workspace state, not conversational memory.
Once ``write_skill_file`` succeeds, this processor replaces historical file
bodies with returned digest descriptors while preserving the tool-call id and
result message required by the model protocol.
"""

from __future__ import annotations

import json
from typing import Any

from pydantic import BaseModel, Field

from openjiuwen.core.context_engine.base import ContextWindow, ModelContext
from openjiuwen.core.context_engine.context_engine import ContextEngine
from openjiuwen.core.context_engine.processor.base import ContextEvent, ContextProcessor
from openjiuwen.core.context_engine.processor.offloader.tool_result_budget_processor import (
    ToolResultBudgetProcessorConfig,
)
from openjiuwen.core.foundation.llm import AssistantMessage, BaseMessage, ToolMessage


class SkillBuilderToolArgumentCompactorConfig(BaseModel):
    tool_name_prefixes: list[str] = Field(
        default_factory=lambda: [
            "skill_builder_write_skill_file_",
        ],
    )


class SkillBuilderTransientAssistantCompactorConfig(BaseModel):
    """Remove provider-only response fields from subsequent model requests."""


def _tool_result_object(content: Any) -> dict[str, Any]:
    if isinstance(content, dict):
        return content
    if not isinstance(content, str):
        return {}
    try:
        parsed = json.loads(content)
    except (TypeError, ValueError, json.JSONDecodeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


@ContextEngine.register_processor()
class SkillBuilderToolArgumentCompactor(ContextProcessor):
    """Remove persisted package bodies from completed historical tool calls."""

    @property
    def config(self) -> SkillBuilderToolArgumentCompactorConfig:
        return self._config

    def _is_compactable_call(self, name: Any) -> bool:
        normalized = str(name or "")
        return any(normalized.startswith(prefix) for prefix in self.config.tool_name_prefixes)

    @staticmethod
    def _successful_write_results(messages: list[BaseMessage]) -> dict[str, dict[str, Any]]:
        results: dict[str, dict[str, Any]] = {}
        for message in messages:
            if not isinstance(message, ToolMessage):
                continue
            value = _tool_result_object(message.content)
            if (
                value.get("ok") is True
                and value.get("persisted") is True
                and bool(value.get("path"))
            ):
                results[str(message.tool_call_id)] = value
        return results

    def _has_compactable_call(self, messages: list[BaseMessage]) -> bool:
        completed = self._successful_write_results(messages)
        if not completed:
            return False
        for message in messages:
            if not isinstance(message, AssistantMessage):
                continue
            for call in message.tool_calls or []:
                if (
                    str(call.id or "") in completed
                    and self._is_compactable_call(call.name)
                    and any(
                        marker in str(call.arguments or "")
                        for marker in ('"content"', '"replacements"')
                    )
                ):
                    return True
        return False

    async def trigger_add_messages(
        self,
        context: ModelContext,
        messages_to_add: list[BaseMessage],
        **kwargs: Any,
    ) -> bool:
        return self._has_compactable_call([*context.get_messages(), *messages_to_add])

    async def on_add_messages(
        self,
        context: ModelContext,
        messages_to_add: list[BaseMessage],
        **kwargs: Any,
    ) -> tuple[ContextEvent | None, list[BaseMessage]]:
        context_size = len(context.get_messages())
        updated = [*context.get_messages(), *messages_to_add]
        completed = self._successful_write_results(updated)
        modified_indices: list[int] = []
        for index, message in enumerate(updated):
            if not isinstance(message, AssistantMessage) or not message.tool_calls:
                continue
            next_message = message.model_copy(deep=True)
            changed = False
            for call in next_message.tool_calls or []:
                result = completed.get(str(call.id or ""))
                if result is None or not self._is_compactable_call(call.name):
                    continue
                try:
                    arguments = json.loads(str(call.arguments or "{}"))
                except (TypeError, ValueError, json.JSONDecodeError):
                    arguments = {}
                serialized_arguments = json.dumps(arguments)
                if not isinstance(arguments, dict) or not any(
                    marker in serialized_arguments
                    for marker in ('"content"', '"replacements"')
                ):
                    continue
                if str(result.get("stageId") or "").startswith("stg_"):
                    compacted = {
                        "path": result.get("path"),
                        "persisted": True,
                        "stageId": result.get("stageId"),
                        "sha256": result.get("sha256"),
                        "sizeBytes": result.get("sizeBytes"),
                    }
                elif isinstance(result.get("stageIds"), list):
                    compacted = {
                        "persisted": True,
                        "stageIds": result.get("stageIds") or [],
                        "files": [
                            {
                                key: item.get(key)
                                for key in ("stageId", "path", "sha256", "sizeBytes")
                            }
                            for item in result.get("files") or []
                            if isinstance(item, dict)
                        ],
                    }
                else:
                    draft = result.get("draft") if isinstance(result.get("draft"), dict) else {}
                    compacted = {
                        "path": result.get("path") or draft.get("path") or arguments.get("path"),
                        "persisted": True,
                        "sha256": draft.get("sha256") or result.get("sha256"),
                        "sizeBytes": result.get("size_bytes") or result.get("sizeBytes"),
                        "draft": {
                            key: draft.get(key)
                            for key in ("path", "sha256")
                            if draft.get(key) not in (None, "")
                        },
                    }
                call.arguments = json.dumps(
                    compacted,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                changed = True
            if changed:
                updated[index] = next_message
                modified_indices.append(index)
        if not modified_indices:
            return None, messages_to_add
        context.set_messages(updated[:context_size])
        return (
            ContextEvent(
                event_type=self.processor_type(),
                messages_to_modify=modified_indices,
            ),
            updated[context_size:],
        )

    def load_state(self, state: dict[str, Any]) -> None:
        return

    def save_state(self) -> dict[str, Any]:
        return {}


@ContextEngine.register_processor()
class SkillBuilderTransientAssistantCompactor(ContextProcessor):
    """Keep dialogue semantics without replaying hidden model state.

    Reasoning text, token ids, logprobs and parser payloads describe a model
    response that has already completed. They are useful for telemetry but are
    not required by the following OpenAI-compatible request. Replaying them is
    especially expensive after a tool-schema correction because the complete
    assistant response would otherwise be sent again.
    """

    @property
    def config(self) -> SkillBuilderTransientAssistantCompactorConfig:
        return self._config

    @staticmethod
    def _has_transient_fields(message: BaseMessage) -> bool:
        return isinstance(message, AssistantMessage) and any(
            value is not None
            for value in (
                message.reasoning_content,
                message.parser_content,
                message.prompt_token_ids,
                message.completion_token_ids,
                message.logprobs,
                message.usage_metadata,
            )
        )

    async def trigger_get_context_window(
        self,
        context: ModelContext,
        context_window: ContextWindow,
        **kwargs: Any,
    ) -> bool:
        del context, kwargs
        return any(self._has_transient_fields(message) for message in context_window.get_messages())

    async def on_get_context_window(
        self,
        context: ModelContext,
        context_window: ContextWindow,
        **kwargs: Any,
    ) -> tuple[ContextEvent | None, ContextWindow]:
        del context, kwargs
        updated = context_window.model_copy(deep=True)
        messages = [*updated.system_messages, *updated.context_messages]
        modified_indices: list[int] = []
        for index, message in enumerate(messages):
            if not self._has_transient_fields(message):
                continue
            message.reasoning_content = None
            message.parser_content = None
            message.prompt_token_ids = None
            message.completion_token_ids = None
            message.logprobs = None
            message.usage_metadata = None
            modified_indices.append(index)
        if not modified_indices:
            return None, context_window
        system_count = len(updated.system_messages)
        updated.system_messages = messages[:system_count]
        updated.context_messages = messages[system_count:]
        return (
            ContextEvent(
                event_type=self.processor_type(),
                messages_to_modify=modified_indices,
            ),
            updated,
        )

    def load_state(self, state: dict[str, Any]) -> None:
        return

    def save_state(self) -> dict[str, Any]:
        return {}


def skill_builder_context_processors() -> list[tuple[str, BaseModel]]:
    """Return bounded processors for one Builder Agent context."""

    return [
        (
            SkillBuilderToolArgumentCompactor.processor_type(),
            SkillBuilderToolArgumentCompactorConfig(),
        ),
        (
            SkillBuilderTransientAssistantCompactor.processor_type(),
            SkillBuilderTransientAssistantCompactorConfig(),
        ),
        (
            "ToolResultBudgetProcessor",
            ToolResultBudgetProcessorConfig(
                tokens_threshold=24000,
                large_message_threshold=6000,
                trim_size=1200,
            ),
        ),
    ]


__all__ = [
    "SkillBuilderToolArgumentCompactor",
    "SkillBuilderToolArgumentCompactorConfig",
    "SkillBuilderTransientAssistantCompactor",
    "SkillBuilderTransientAssistantCompactorConfig",
    "skill_builder_context_processors",
]
