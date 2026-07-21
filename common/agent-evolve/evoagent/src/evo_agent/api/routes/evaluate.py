"""评估路由 — POST /evaluate 同步评估轨迹。"""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from evo_agent.evaluator.domain.models import (
    EvaluationInput,
    GoalGenerationInput,
    GoalGenerationOutput,
    StandardTrajectory,
    TrajectoryMessage,
)
from evo_agent.evaluator.domain.result import EvaluationResult
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.filtering import FilteringEvaluator
from evo_agent.evaluator.evaluators.llm import LLMEvaluator
from evo_agent.evaluator.filters.base import TrajectoryFilter
from evo_agent.evaluator.filters.tool_failure import ToolFailureFilter
from evo_agent.evaluator.filters.user_feedback import UserFeedbackFilter
from evo_agent.evaluator.goal_generator import TrajectoryGoalGenerator

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/evaluate", tags=["evaluate"])


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------


class LLMConfig(BaseModel):
    """LLM 配置 — 每次请求必传。"""

    model_name: str
    api_key: str
    api_base: str
    client_provider: str = "OpenAI"
    temperature: float = 0.1
    max_tokens: int = 2048
    verify_ssl: bool = False


class ToolFailureFilterConfig(BaseModel):
    """工具失败过滤器配置。"""

    enabled: bool = False
    patterns: list[str] | None = None
    replace_default_patterns: bool = False


class UserFeedbackFilterConfig(BaseModel):
    """用户反馈过滤器配置。"""

    enabled: bool = False
    patterns: list[str] | None = None
    replace_default_patterns: bool = False
    skip_initial_user_messages: int = 1


class FilterConfig(BaseModel):
    """过滤器配置 — 可选，启用后在 LLM 评估前执行确定性过滤。"""

    tool_failure: ToolFailureFilterConfig = Field(default_factory=ToolFailureFilterConfig)
    user_feedback: UserFeedbackFilterConfig = Field(default_factory=UserFeedbackFilterConfig)


class EvaluateRequest(BaseModel):
    """评估请求体。"""

    trajectory_path: str
    prompt_template: str
    llm_config: LLMConfig
    expected_result: dict[str, Any] | None = None
    skill_names: list[str]
    filters: FilterConfig | None = None


class GenerateGoalRequest(BaseModel):
    """用户目标生成请求体。"""

    messages: list[dict[str, Any]]
    llm_config: LLMConfig


class FilterMatchResponse(BaseModel):
    """过滤器匹配结果。"""

    filter_type: str
    rule_id: str
    message_index: int
    evidence: str
    pattern: str | None = None


class EvaluateResponse(BaseModel):
    """评估结果。"""

    status: str = "evaluated"
    score: float
    is_pass: bool = True
    per_metric: dict[str, float] | None = None
    reason: str = ""
    attributed_skill: str = ""
    filter_matches: list[FilterMatchResponse] = []


class GenerateGoalResponse(BaseModel):
    """用户目标生成结果。"""

    status: str = "generated"
    goal: str
    metadata: dict[str, Any] = Field(default_factory=dict)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _load_trajectory(path: Path) -> StandardTrajectory:
    """Load a StandardTrajectory from a JSON file.

    Only extracts known fields (``messages``, ``summary``) to avoid
    ``extra="forbid"`` validation errors from unknown keys in the file.
    """
    with open(path, encoding="utf-8") as f:
        raw: dict[str, Any] = json.load(f)

    messages = raw.get("messages", [])
    summary = raw.get("summary")

    data: dict[str, Any] = {"messages": messages}
    if summary is not None:
        data["summary"] = summary

    return StandardTrajectory.model_validate(data)


def _build_llm_configs(
    llm_config: LLMConfig,
) -> tuple[Any, Any]:
    """从 ``LLMConfig`` 构建 ``(ModelRequestConfig, ModelClientConfig)``。

    ``client_provider`` 为自由字符串，openjiuwen 在 ``ModelClientConfig`` 构造时
    校验 provider 是否已注册；未知 provider 抛
    :class:`openjiuwen.core.common.exception.errors.ValidationError`（非 pydantic
    ``ValidationError``），此处捕获并转为 422，避免冒泡为 500。
    """
    from openjiuwen.core.common.exception.errors import (
        ValidationError as ProviderValidationError,
    )
    from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

    model_config = ModelRequestConfig(
        model_name=llm_config.model_name,
        temperature=llm_config.temperature,
        max_tokens=llm_config.max_tokens,
    )
    try:
        model_client_config = ModelClientConfig(
            client_provider=llm_config.client_provider,
            api_key=llm_config.api_key,
            api_base=llm_config.api_base,
            verify_ssl=llm_config.verify_ssl,
        )
    except ProviderValidationError as e:
        raise HTTPException(
            status_code=422,
            detail=f"Invalid llm_config: {e}",
        ) from e
    return model_config, model_client_config


def _build_filters(config: FilterConfig) -> list[TrajectoryFilter]:
    """从 FilterConfig 构建过滤器列表。"""
    filters: list[TrajectoryFilter] = []

    if config.tool_failure.enabled:
        filters.append(
            ToolFailureFilter(
                patterns=config.tool_failure.patterns,
                replace_default_patterns=config.tool_failure.replace_default_patterns,
            )
        )

    if config.user_feedback.enabled:
        filters.append(
            UserFeedbackFilter(
                patterns=config.user_feedback.patterns,
                replace_default_patterns=config.user_feedback.replace_default_patterns,
                skip_initial_user_messages=config.user_feedback.skip_initial_user_messages,
            )
        )

    return filters


_TRAJECTORY_MESSAGE_FIELDS = {
    "role",
    "content",
    "name",
    "tool_calls",
    "tool_call_id",
    "reasoning_content",
    "metadata",
}


def _build_goal_generation_input(messages: list[dict[str, Any]]) -> GoalGenerationInput:
    """Build goal-generation input from inline API messages."""
    if not messages:
        raise HTTPException(status_code=422, detail="messages must not be empty")

    trajectory_messages: list[TrajectoryMessage] = []
    for index, message in enumerate(messages):
        filtered = {k: v for k, v in message.items() if k in _TRAJECTORY_MESSAGE_FIELDS}
        if "role" not in filtered:
            raise HTTPException(
                status_code=422,
                detail=f"messages[{index}] missing required field 'role'",
            )
        try:
            trajectory_messages.append(TrajectoryMessage.model_validate(filtered))
        except Exception as e:
            raise HTTPException(
                status_code=422,
                detail=f"Invalid message at index {index}: {e}",
            ) from e

    return GoalGenerationInput(trajectory=StandardTrajectory(messages=trajectory_messages))


def _to_response(result: EvaluationResult) -> EvaluateResponse:
    """将 EvaluationResult 转换为 API 响应。"""
    return EvaluateResponse(
        status=result.status,
        score=result.score,
        is_pass=result.is_pass,
        per_metric=result.per_metric,
        reason=result.reason,
        attributed_skill=result.attributed_skill,
        filter_matches=[
            FilterMatchResponse(
                filter_type=m.filter_type,
                rule_id=m.rule_id,
                message_index=m.message_index,
                evidence=m.evidence,
                pattern=m.pattern,
            )
            for m in result.filter_matches
        ],
    )


def _to_goal_response(result: GoalGenerationOutput) -> GenerateGoalResponse:
    return GenerateGoalResponse(goal=result.goal, metadata=result.metadata)


# ---------------------------------------------------------------------------
# Route
# ---------------------------------------------------------------------------


@router.post("/generate-goal", response_model=GenerateGoalResponse)
async def generate_goal(request: GenerateGoalRequest) -> GenerateGoalResponse:
    """基于内联轨迹 messages 生成优化器使用的用户目标。"""
    goal_input = _build_goal_generation_input(request.messages)

    model_config, model_client_config = _build_llm_configs(request.llm_config)
    generator = TrajectoryGoalGenerator(
        model_config=model_config,
        model_client_config=model_client_config,
    )

    try:
        result = await asyncio.to_thread(generator.generate, goal_input)
    except EvaluationError as e:
        raise HTTPException(
            status_code=500,
            detail=f"Goal generation failed: {e}",
        ) from e

    return _to_goal_response(result)


@router.post("", response_model=EvaluateResponse)
async def evaluate_trajectory(request: EvaluateRequest) -> EvaluateResponse:
    """同步评估一条轨迹。

    读取轨迹文件 → 构建评估器（可选过滤层）→ 执行评估 → 返回结果。

    当请求中包含 ``filters`` 配置时，会在 LLM 评估前执行确定性过滤：
    - 匹配到过滤规则的轨迹直接返回 ``status="filtered"``、``score=0.0``
    - 未匹配的轨迹正常委托给 LLM 评估器
    """
    # 1. 加载轨迹
    traj_path = Path(request.trajectory_path)
    if not traj_path.exists():
        raise HTTPException(
            status_code=404,
            detail=f"Trajectory file not found: {request.trajectory_path}",
        )

    try:
        trajectory = _load_trajectory(traj_path)
    except Exception as e:
        raise HTTPException(
            status_code=422,
            detail=f"Invalid trajectory format: {e}",
        ) from e

    # 2. 构建 EvaluationInput
    evaluation_input = EvaluationInput(
        trajectory=trajectory,
        expected_result=request.expected_result,
        skill_names=request.skill_names,
    )

    # 3. 构建评估器
    model_config, model_client_config = _build_llm_configs(request.llm_config)
    llm_evaluator = LLMEvaluator(
        model_config=model_config,
        model_client_config=model_client_config,
        prompt_template=request.prompt_template,
    )

    # 4. 如果有 filter 配置，用 FilteringEvaluator 包裹
    evaluator: LLMEvaluator | FilteringEvaluator
    if request.filters is not None:
        try:
            filters = _build_filters(request.filters)
        except Exception as e:
            raise HTTPException(
                status_code=422,
                detail=f"Invalid filter configuration: {e}",
            ) from e

        if filters:
            evaluator = FilteringEvaluator(llm_evaluator, filters)
        else:
            evaluator = llm_evaluator
    else:
        evaluator = llm_evaluator

    # 5. 执行评估
    try:
        result = await asyncio.to_thread(evaluator.evaluate_input, evaluation_input)
    except EvaluationError as e:
        raise HTTPException(
            status_code=500,
            detail=f"Evaluation failed: {e}",
        ) from e

    return _to_response(result)
