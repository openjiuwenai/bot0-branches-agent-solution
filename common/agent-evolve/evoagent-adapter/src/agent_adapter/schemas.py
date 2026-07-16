"""Pydantic schemas for Agent call proxy and Adapter sidecar skill API."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, model_validator

SkillAction = Literal["skill_list", "skill_content", "update_skill", "restore_skill"]

ManagedDocAction = Literal["content", "update", "restore"]


class EventSummary(BaseModel):
    """Simplified representation of a single EDPAgent SSE event."""

    type: str = Field(description="Event type, e.g. summary, tool_start, interrupt_start")
    content: str = Field(default="", description="Event text content")
    plugin: str | None = Field(default=None, description="Tool name (only for tool_* events)")


class AgentCallRequest(BaseModel):
    """Request body for calling a business Agent through the adapter proxy."""

    query: str = Field(description="User natural language query")
    extra_data: dict | None = Field(
        default=None,
        description=(
            "Extra key-value pairs forwarded to EDPAgent custom_data.inputs. "
            "Sampling knobs such as temperature should be placed here "
            '(e.g. {"temperature": 0.7}) when the downstream Agent consumes them.'
        ),
    )


class AgentCallResponse(BaseModel):
    """Response from calling a business Agent through the adapter proxy."""

    success: bool = Field(description="Whether the call completed without connection/timeout errors")
    conversation_id: str = Field(description="Conversation ID used for the call")
    answer: str = Field(default="", description="Assembled final answer from summary/final_answer_chunk events")
    interrupted: bool = Field(
        default=False,
        description="True when Agent yielded a VA delegate interrupt (needs continuation)",
    )
    interrupt_intent: str | None = Field(default=None, description="Intent of the interrupt")
    interrupt_description: str | None = Field(default=None, description="Task description of the interrupt")
    events: list[EventSummary] | None = Field(
        default=None,
        description="Simplified list of all events observed during the call",
    )
    error: str | None = Field(default=None, description="Error message if the call failed")


class SkillActionRequest(BaseModel):
    """Request body for POST /api/v1/skills (adapter-api-contract §1)."""

    agent_name: str = Field(min_length=1, description="Target business agent name")
    action: SkillAction = Field(
        description="skill_list | skill_content | update_skill | restore_skill",
    )
    skill_name: str | None = Field(default=None, description="Required for skill_content / update_skill")
    skill_names: list[str] | None = Field(
        default=None,
        description="Required for restore_skill; skill names to restore from snapshot",
    )
    skill_content: str | None = Field(
        default=None,
        description="Full SKILL.md body; required for update_skill",
    )

    @model_validator(mode="after")
    def _validate_action_fields(self) -> SkillActionRequest:
        if self.action == "skill_content" and not self.skill_name:
            raise ValueError("skill_name is required when action is skill_content")
        if self.action == "update_skill":
            if not self.skill_name:
                raise ValueError("skill_name is required when action is update_skill")
            if self.skill_content is None:
                raise ValueError("skill_content is required when action is update_skill")
        if self.action == "restore_skill":
            if not self.skill_names:
                raise ValueError("skill_names is required when action is restore_skill")
        return self


class SkillListItem(BaseModel):
    name: str


class SkillListResponse(BaseModel):
    skills: list[SkillListItem]


class SkillContentResponse(BaseModel):
    skill_name: str
    content: str


class SkillUpdateResponse(BaseModel):
    success: bool
    skill_name: str
    message: str | None = None


class SkillRestoreItem(BaseModel):
    skill_name: str
    success: bool
    message: str | None = None


class SkillRestoreResponse(BaseModel):
    restored: list[SkillRestoreItem]


class ApiErrorBody(BaseModel):
    code: str
    message: str


class ApiErrorResponse(BaseModel):
    error: ApiErrorBody


class ManagedDocActionRequest(BaseModel):
    """Request body for POST /api/v1/managed-docs (adapter-api-contract managed-doc)."""

    agent_name: str = Field(min_length=1, description="Target business agent name")
    doc_kind: str = Field(min_length=1, description="doc kind, e.g. agent_rule")
    action: ManagedDocAction = Field(description="content | update | restore")
    content: str | None = Field(default=None, description="Full doc body; required for update")

    @model_validator(mode="after")
    def _validate_action_fields(self) -> ManagedDocActionRequest:
        if self.action == "update" and self.content is None:
            raise ValueError("content is required when action is update")
        return self
