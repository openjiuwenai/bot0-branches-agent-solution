"""Agent-as-judge — drive real coding-agent CLIs (claude code / codex) as
subprocesses to judge a trajectory across multiple dimensions, then fuse the
verdicts into one score + plural skill attribution via an LLM aggregator.

Public surface: dimension/preset registries, runtime adapters, the orchestrator
and aggregator, workdir manager, and the pydantic schemas. The
:class:`AgentEvaluator` itself lives in :mod:`evo_agent.evaluator.evaluators.agent`
(re-exported from :mod:`evo_agent.evaluator`) to keep this package free of an
import cycle with the evaluators layer.
"""

from __future__ import annotations

from evo_agent.evaluator.agent_judge.aggregator import SkillAggregator
from evo_agent.evaluator.agent_judge.config import AgentEvaluatorConfig
from evo_agent.evaluator.agent_judge.dimensions import (
    JudgeDimension,
    get_dimension,
    list_dimensions,
    register_dimension,
)
from evo_agent.evaluator.agent_judge.orchestrator import DimensionOrchestrator
from evo_agent.evaluator.agent_judge.presets import (
    JudgePreset,
    get_preset,
    list_presets,
    register_preset,
)
from evo_agent.evaluator.agent_judge.runtime import (
    ClaudeRuntime,
    CodexRuntime,
    JudgeAgentRuntime,
    RuntimeJudgeRequest,
    make_runtime,
)
from evo_agent.evaluator.agent_judge.schemas import (
    AggregatorOutput,
    DimensionJudgment,
    SkillAttribution,
    aggregator_output_validator,
    dimension_judgment_json_schema,
)
from evo_agent.evaluator.agent_judge.scorers import (
    TaskCompletionGatedScorer,
    WeightedSumScorer,
    WeightScorer,
    get_scorer,
    list_scorers,
    register_scorer,
)
from evo_agent.evaluator.agent_judge.workdir import SCHEMA_FILENAME, WorkdirManager

__all__ = [
    "AgentEvaluatorConfig",
    "AggregatorOutput",
    "ClaudeRuntime",
    "CodexRuntime",
    "DimensionJudgment",
    "DimensionOrchestrator",
    "JudgeAgentRuntime",
    "JudgeDimension",
    "JudgePreset",
    "RuntimeJudgeRequest",
    "SCHEMA_FILENAME",
    "SkillAggregator",
    "SkillAttribution",
    "TaskCompletionGatedScorer",
    "WeightScorer",
    "WeightedSumScorer",
    "WorkdirManager",
    "aggregator_output_validator",
    "dimension_judgment_json_schema",
    "get_dimension",
    "get_preset",
    "get_scorer",
    "list_dimensions",
    "list_presets",
    "list_scorers",
    "make_runtime",
    "register_dimension",
    "register_preset",
    "register_scorer",
]
