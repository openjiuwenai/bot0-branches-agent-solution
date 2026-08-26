# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

import os
from dataclasses import dataclass


class SkillBuilderLLMConfigError(RuntimeError):
    """Raised when Skill Builder LLM configuration is incomplete."""


@dataclass(slots=True)
class SkillBuilderLLMSettings:
    provider: str
    api_key: str
    api_base: str
    model_name: str
    timeout_seconds: int
    max_tokens: int
    max_request_bytes: int
    request_headroom_ratio: float
    temperature: float
    top_p: float
    phase_max_tokens: dict[str, int]
    phase_enable_thinking: dict[str, bool | None]

    def max_tokens_for_phase(self, phase: str) -> int:
        return int(self.phase_max_tokens.get(str(phase or "").strip().lower(), self.max_tokens))

    def enable_thinking_for_phase(self, phase: str) -> bool | None:
        return self.phase_enable_thinking.get(
            str(phase or "").strip().lower(),
            self.phase_enable_thinking.get("default"),
        )


def _env_required(name: str) -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        raise SkillBuilderLLMConfigError(f"缺少必需配置 {name}")
    return value.strip()


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        parsed = int(value)
    except ValueError as exc:
        raise SkillBuilderLLMConfigError(f"{name} 必须是整数") from exc
    return parsed


def _env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        parsed = float(value)
    except ValueError as exc:
        raise SkillBuilderLLMConfigError(f"{name} 必须是数字") from exc
    return parsed


def _env_optional_bool(name: str, default: bool | None = None) -> bool | None:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    normalized = value.strip().lower()
    if normalized in {"auto", "default", "none"}:
        return None
    if normalized in {"1", "true", "yes", "on", "enabled"}:
        return True
    if normalized in {"0", "false", "no", "off", "disabled"}:
        return False
    raise SkillBuilderLLMConfigError(
        f"{name} 必须是 auto、true 或 false"
    )


def resolve_skill_builder_llm_settings() -> SkillBuilderLLMSettings:
    max_tokens = max(1024, _env_int("SKILL_BUILDER_LLM_MAX_TOKENS", 16384))
    default_thinking = _env_optional_bool("SKILL_BUILDER_LLM_ENABLE_THINKING")
    phases = ("scenario", "author", "repair", "edit", "chat")
    return SkillBuilderLLMSettings(
        provider=(os.getenv("SKILL_BUILDER_LLM_PROVIDER") or "OpenAI").strip() or "OpenAI",
        api_key=_env_required("SKILL_BUILDER_LLM_API_KEY"),
        api_base=_env_required("SKILL_BUILDER_LLM_API_BASE"),
        model_name=_env_required("SKILL_BUILDER_LLM_MODEL"),
        timeout_seconds=max(1, _env_int("SKILL_BUILDER_LLM_TIMEOUT_SECONDS", 120)),
        max_tokens=max_tokens,
        max_request_bytes=max(
            64 * 1024,
            _env_int("SKILL_BUILDER_LLM_MAX_REQUEST_BYTES", 512 * 1024),
        ),
        request_headroom_ratio=max(
            0.25,
            min(
                _env_float("SKILL_BUILDER_LLM_REQUEST_HEADROOM_RATIO", 0.8),
                1.0,
            ),
        ),
        temperature=_env_float("SKILL_BUILDER_LLM_TEMPERATURE", 0.2),
        top_p=_env_float("SKILL_BUILDER_LLM_TOP_P", 0.9),
        phase_max_tokens={
            phase: max(
                1024,
                _env_int(
                    f"SKILL_BUILDER_LLM_{phase.upper()}_MAX_TOKENS",
                    max_tokens,
                ),
            )
            for phase in phases
        },
        phase_enable_thinking={
            "default": default_thinking,
            **{
                phase: _env_optional_bool(
                    f"SKILL_BUILDER_LLM_{phase.upper()}_ENABLE_THINKING",
                    default_thinking,
                )
                for phase in phases
            },
        },
    )
