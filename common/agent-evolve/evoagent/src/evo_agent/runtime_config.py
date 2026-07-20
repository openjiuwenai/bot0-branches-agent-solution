"""Resolve optimization runtime configuration in one place.

The runner should not know which value came from the API request, a scenario
preset, or environment defaults. This module owns that merge policy.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from evo_agent.config import EvolveConfig
from evo_agent.scenario.registry import ScenarioConfig, ScenarioRegistry
from evo_agent.types import OptimizeRequest

_RESERVED_DEPENDENCY_KEYS = frozenset(
    {
        "agent",
        "evaluator",
        "llm",
        "adapter_client",
        "operators",
        "conversation_id_factory",
        "train_cases",
    }
)


@dataclass(frozen=True)
class ResolvedOptimizationConfig:
    """Complete configuration required to run one optimization job."""

    scenario: str
    scenario_config: ScenarioConfig
    agent_name: str
    optimizer_type: str
    skills: tuple[str, ...]
    dataset_path: str
    dataset_manifest_path: Path | None
    evaluator_prompt: str
    adapter_url: str
    task_name: str
    train_split: float
    val_split: float
    num_epochs: int
    batch_size: int
    accumulation: int
    minibatch_size: int
    edit_budget: int
    scheduler_mode: str
    update_mode: str
    score_threshold: float
    parallelism: int
    num_parallel: int
    use_slow_update: bool
    use_meta_skill: bool
    preserve_frontmatter: bool
    rollout_extra_data: dict[str, Any]
    trace_max_retries: int
    trace_retry_backoff: float
    tie_reval_eps: float
    validation_max_case_attempts: int
    validation_min_success_ratio: float
    validation_require_same_case_set: bool
    llm_context_window_tokens: int
    llm_output_reserve_tokens: int
    llm_safety_margin_tokens: int
    llm_chars_per_token: float
    llm_stage_output_reserve_tokens: dict[str, int]
    extra_hyperparams: dict[str, Any]
    # managed-doc 模式目标 kind（精确 doc_kind）。None 走 Skill 路径。
    # 不进入 optimizer_runtime_dependencies()——operator 在 optimizer deps
    # 构造前已创建，该字段供 runner builder 分支直接消费。
    managed_doc_kind: str | None = None
    managed_doc_expected_revision: str | None = None

    def optimizer_runtime_dependencies(self) -> dict[str, Any]:
        """Return scalar optimizer constructor kwargs owned by runtime config."""
        deps: dict[str, Any] = {
            "batch_size": self.batch_size,
            "accumulation": self.accumulation,
            "minibatch_size": self.minibatch_size,
            "edit_budget": self.edit_budget,
            "scheduler_mode": self.scheduler_mode,
            "update_mode": self.update_mode,
            "score_threshold": self.score_threshold,
            "parallelism": self.parallelism,
            "num_parallel": self.num_parallel,
            "use_slow_update": self.use_slow_update,
            "use_meta_skill": self.use_meta_skill,
            "preserve_frontmatter": self.preserve_frontmatter,
            "rollout_extra_data": dict(self.rollout_extra_data),
            "trace_max_retries": self.trace_max_retries,
            "trace_retry_backoff": self.trace_retry_backoff,
        }
        deps.update(self.extra_hyperparams)
        return deps


class OptimizationConfigResolver:
    """Merge request, scenario preset, and environment defaults."""

    def __init__(
        self,
        env: EvolveConfig,
        *,
        registry: ScenarioRegistry | None = None,
    ) -> None:
        self._env = env
        self._registry = registry or ScenarioRegistry()

    @property
    def registry(self) -> ScenarioRegistry:
        return self._registry

    def resolve(self, request: OptimizeRequest) -> ResolvedOptimizationConfig:
        scenario_config = self._registry.load_scenario_config(request.scenario)
        scenario_hp = dict(scenario_config.hyperparams)
        request_hp = dict(request.hyperparams)
        merged_hp = {**scenario_hp, **request_hp}

        rollout_extra = _merge_dicts(
            scenario_config.rollout.get("extra_data", {}),
            request.rollout_extra_data,
        )

        num_epochs = _resolve_int(
            name="num_epochs",
            request_value=request.num_epochs,
            merged_hyperparams=merged_hp,
            config_value=self._env.default_epochs,
            minimum=1,
            maximum=100,
        )
        batch_size = _resolve_int(
            name="batch_size",
            request_value=request.batch_size,
            merged_hyperparams=merged_hp,
            config_value=self._env.default_batch_size,
            minimum=1,
            maximum=64,
        )
        num_parallel = _resolve_int(
            name="num_parallel",
            request_value=None,
            merged_hyperparams=merged_hp,
            config_value=self._env.remote_parallel,
            minimum=1,
        )

        extra_hyperparams = {
            k: v
            for k, v in merged_hp.items()
            if k
            not in {
                "num_epochs",
                "batch_size",
                "accumulation",
                "minibatch_size",
                "edit_budget",
                "scheduler_mode",
                "update_mode",
                "score_threshold",
                "parallelism",
                "num_parallel",
                "use_slow_update",
                "use_meta_skill",
                "preserve_frontmatter",
                "trace_max_retries",
                "trace_retry_backoff",
                "tie_reval_eps",
                "validation_max_case_attempts",
                "validation_min_success_ratio",
                "validation_require_same_case_set",
                "llm_context_window_tokens",
                "llm_output_reserve_tokens",
                "llm_safety_margin_tokens",
                "llm_chars_per_token",
                "llm_stage_output_reserve_tokens",
            }
            and k not in _RESERVED_DEPENDENCY_KEYS
        }

        return ResolvedOptimizationConfig(
            scenario=request.scenario,
            scenario_config=scenario_config,
            agent_name=request.agent_name,
            optimizer_type=request.optimizer_type,
            skills=tuple(request.skills),
            managed_doc_kind=request.managed_doc_kind,
            managed_doc_expected_revision=request.managed_doc_expected_revision,
            dataset_path=request.dataset_path,
            dataset_manifest_path=request.dataset_manifest_path,
            evaluator_prompt=request.evaluator_prompt,
            adapter_url=request.adapter_url or scenario_config.adapter_url or self._env.adapter_url,
            task_name=request.task_name,
            train_split=request.train_split,
            val_split=request.val_split,
            num_epochs=num_epochs,
            batch_size=batch_size,
            accumulation=_resolve_int(
                name="accumulation",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=self._env.accumulation,
                minimum=1,
            ),
            minibatch_size=_resolve_int(
                name="minibatch_size",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=self._env.minibatch_size,
                minimum=1,
            ),
            edit_budget=_resolve_int(
                name="edit_budget",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=self._env.edit_budget,
                minimum=0,
            ),
            scheduler_mode=str(merged_hp.get("scheduler_mode", self._env.scheduler_mode)),
            update_mode=str(merged_hp.get("update_mode", self._env.update_mode)),
            score_threshold=_resolve_float(
                name="score_threshold",
                merged_hyperparams=merged_hp,
                config_value=self._env.score_threshold,
                minimum=0.0,
                maximum=1.0,
            ),
            parallelism=_resolve_int(
                name="parallelism",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=self._env.parallelism,
                minimum=1,
            ),
            num_parallel=num_parallel,
            use_slow_update=_resolve_bool("use_slow_update", merged_hp, self._env.use_slow_update),
            use_meta_skill=_resolve_bool("use_meta_skill", merged_hp, self._env.use_meta_skill),
            preserve_frontmatter=_resolve_bool(
                "preserve_frontmatter", merged_hp, self._env.preserve_frontmatter
            ),
            rollout_extra_data=rollout_extra,
            trace_max_retries=_resolve_int(
                name="trace_max_retries",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=24,
                minimum=1,
            ),
            trace_retry_backoff=_resolve_float(
                name="trace_retry_backoff",
                merged_hyperparams=merged_hp,
                config_value=5.0,
                minimum=0.0,
            ),
            tie_reval_eps=_resolve_float(
                name="tie_reval_eps",
                merged_hyperparams=merged_hp,
                config_value=0.0,
                minimum=0.0,
            ),
            validation_max_case_attempts=_resolve_int(
                name="validation_max_case_attempts",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=self._env.validation_max_case_attempts,
                minimum=1,
            ),
            validation_min_success_ratio=_resolve_float(
                name="validation_min_success_ratio",
                merged_hyperparams=merged_hp,
                config_value=self._env.validation_min_success_ratio,
                minimum=0.0,
                maximum=1.0,
            ),
            validation_require_same_case_set=_resolve_bool(
                "validation_require_same_case_set",
                merged_hp,
                self._env.validation_require_same_case_set,
            ),
            llm_context_window_tokens=_resolve_int(
                name="llm_context_window_tokens",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=self._env.llm_context_window_tokens,
                minimum=1,
            ),
            llm_output_reserve_tokens=_resolve_int(
                name="llm_output_reserve_tokens",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=self._env.llm_output_reserve_tokens,
                minimum=1,
            ),
            llm_safety_margin_tokens=_resolve_int(
                name="llm_safety_margin_tokens",
                request_value=None,
                merged_hyperparams=merged_hp,
                config_value=self._env.llm_safety_margin_tokens,
                minimum=0,
            ),
            llm_chars_per_token=_resolve_float(
                name="llm_chars_per_token",
                merged_hyperparams=merged_hp,
                config_value=self._env.llm_chars_per_token,
                minimum=0.0,
                exclusive_minimum=True,
            ),
            llm_stage_output_reserve_tokens=_resolve_int_mapping(
                name="llm_stage_output_reserve_tokens",
                merged_hyperparams=merged_hp,
                config_value=self._env.llm_stage_output_reserve_tokens,
            ),
            extra_hyperparams=extra_hyperparams,
        )


def _merge_dicts(base: Any, override: Any) -> dict[str, Any]:
    result = dict(base) if isinstance(base, dict) else {}
    if isinstance(override, dict):
        result.update(override)
    return result


def _resolve_int(
    *,
    name: str,
    request_value: Any,
    merged_hyperparams: dict[str, Any],
    config_value: Any,
    minimum: int,
    maximum: int | None = None,
) -> int:
    raw = request_value
    if raw is None:
        raw = merged_hyperparams.get(name, config_value)
    if isinstance(raw, bool):
        raise ValueError(f"{name} must be an integer, got {raw!r}")
    try:
        value = int(raw)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be numeric: {exc}") from exc
    if value != raw:
        raise ValueError(f"{name} must be an integer, got {raw!r}")
    if value < minimum:
        raise ValueError(f"{name} must be >= {minimum}, got {value}")
    if maximum is not None and value > maximum:
        raise ValueError(f"{name} must be <= {maximum}, got {value}")
    return value


def _resolve_float(
    *,
    name: str,
    merged_hyperparams: dict[str, Any],
    config_value: Any,
    minimum: float,
    maximum: float | None = None,
    exclusive_minimum: bool = False,
) -> float:
    raw = merged_hyperparams.get(name, config_value)
    if isinstance(raw, bool):
        raise ValueError(f"{name} must be numeric, got {raw!r}")
    try:
        value = float(raw)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be numeric: {exc}") from exc
    if not math.isfinite(value):
        raise ValueError(f"{name} must be finite, got {value}")
    if value < minimum or exclusive_minimum and value == minimum:
        comparator = ">" if exclusive_minimum else ">="
        raise ValueError(f"{name} must be {comparator} {minimum}, got {value}")
    if maximum is not None and value > maximum:
        raise ValueError(f"{name} must be <= {maximum}, got {value}")
    return value


def _resolve_int_mapping(
    *,
    name: str,
    merged_hyperparams: dict[str, Any],
    config_value: dict[str, int],
) -> dict[str, int]:
    raw = merged_hyperparams.get(name, config_value)
    if not isinstance(raw, dict):
        raise ValueError(f"{name} must be a dictionary, got {raw!r}")
    resolved: dict[str, int] = {}
    for stage, value in raw.items():
        if not isinstance(stage, str) or not stage:
            raise ValueError(f"{name} keys must be non-empty strings, got {stage!r}")
        resolved[stage] = _resolve_int(
            name=f"{name}.{stage}",
            request_value=value,
            merged_hyperparams={},
            config_value=value,
            minimum=1,
        )
    return resolved


def _resolve_bool(name: str, merged_hyperparams: dict[str, Any], config_value: bool) -> bool:
    raw = merged_hyperparams.get(name, config_value)
    if not isinstance(raw, bool):
        raise ValueError(f"{name} must be a boolean, got {raw!r}")
    return raw
