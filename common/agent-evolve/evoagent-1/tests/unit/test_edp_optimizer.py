"""EDPAgentOptimizer 单元测试 — edp_agent 场景 optimizer 子类。"""

from __future__ import annotations

import logging
import sys
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from openjiuwen.agent_evolving.trajectory import (
    LLMCallDetail,
    ToolCallDetail,
    Trajectory,
    TrajectoryStep,
)

# ── helpers ──

_OPTIMIZER_CODE = """\
from __future__ import annotations

import asyncio
import json
import logging
import uuid
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from evo_agent.optimizer.skill_document import SkillDocumentOptimizer
from evo_agent.optimizer.skill_document.types import AttributedBatch
from openjiuwen.agent_evolving.trajectory import (
    LLMCallDetail,
    ToolCallDetail,
    Trajectory,
    TrajectoryStep,
)

from evo_agent.dataset.case import merge_extra_data
from evo_agent.evaluator.trajectory.normalize import normalize_trace_to_trajectory
from evo_agent.scenario.prompts import load_prompt
from evo_agent.types import TrajectoryUnavailableError

logger = logging.getLogger(__name__)


def _require_messages(trace_data: dict[str, Any]) -> list[dict[str, Any]]:
    \"\"\"Extract messages from trace_data, raising if empty or missing.\"\"\"
    messages = trace_data.get("messages", [])
    if not messages:
        raise TrajectoryUnavailableError("trace_data has no messages")
    return messages


class EDPAgentOptimizer(SkillDocumentOptimizer):
    \"\"\"edp_agent scenario optimizer with trajectory injection.\"\"\"

    def __init__(
        self,
        *,
        adapter_client: Any = None,
        operators: dict[str, Any] | None = None,
        rollout_extra_data: dict[str, Any] | None = None,
        conversation_id_factory: Any = None,
        trace_max_retries: int = 3,
        trace_retry_backoff: float = 1.0,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self._adapter_client = adapter_client
        self._operators = operators or {}
        self._rollout_extra_data = rollout_extra_data or {}
        self._conversation_id_factory = conversation_id_factory
        self._trace_max_retries = trace_max_retries
        self._trace_retry_backoff = trace_retry_backoff

    @staticmethod
    def _messages_to_trajectory(messages: list[dict[str, Any]]) -> Trajectory:
        \"\"\"Convert cleaned-traces messages to Trajectory.\"\"\"
        if not messages:
            return Trajectory(execution_id=uuid.uuid4().hex, steps=[])

        steps: list[TrajectoryStep] = []
        current_llm_messages: list[dict[str, Any]] = []

        for msg in messages:
            role = msg.get("role", "")

            if role == "user":
                if current_llm_messages:
                    steps.append(
                        TrajectoryStep(
                            kind="llm",
                            detail=LLMCallDetail(
                                model="",
                                messages=current_llm_messages,
                            ),
                        )
                    )
                    current_llm_messages = []
                current_llm_messages.append(msg)

            elif role == "assistant":
                tool_calls = msg.get("tool_calls")
                if tool_calls:
                    if current_llm_messages:
                        steps.append(
                            TrajectoryStep(
                                kind="llm",
                                detail=LLMCallDetail(
                                    model="",
                                    messages=current_llm_messages,
                                    response=msg,
                                ),
                            )
                        )
                        current_llm_messages = []
                    for tc in tool_calls:
                        fn = tc.get("function", {})
                        steps.append(
                            TrajectoryStep(
                                kind="tool",
                                detail=ToolCallDetail(
                                    tool_name=fn.get("name", ""),
                                    call_args=fn.get("arguments", ""),
                                ),
                            )
                        )
                else:
                    current_llm_messages.append(msg)
                    steps.append(
                        TrajectoryStep(
                            kind="llm",
                            detail=LLMCallDetail(
                                model="",
                                messages=current_llm_messages,
                                response=msg,
                            ),
                        )
                    )
                    current_llm_messages = []

            elif role == "tool":
                steps.append(
                    TrajectoryStep(
                        kind="tool",
                        detail=ToolCallDetail(
                            tool_name=msg.get("name", ""),
                            call_result=msg.get("content", ""),
                        ),
                    )
                )

        if current_llm_messages:
            steps.append(
                TrajectoryStep(
                    kind="llm",
                    detail=LLMCallDetail(
                        model="",
                        messages=current_llm_messages,
                    ),
                )
            )

        return Trajectory(execution_id=uuid.uuid4().hex, steps=steps)

    async def _get_required_trace(self, conversation_id: str) -> dict[str, Any]:
        \"\"\"Fetch trace data with retry + backoff. Raise on failure.\"\"\"
        for attempt in range(self._trace_max_retries):
            trace_data = await self._adapter_client.get_traces(
                case_id=conversation_id,
            )
            try:
                _require_messages(trace_data)
                return trace_data
            except TrajectoryUnavailableError:
                if attempt < self._trace_max_retries - 1:
                    await asyncio.sleep(self._trace_retry_backoff)
        raise TrajectoryUnavailableError(
            f"No valid traces after {self._trace_max_retries} attempts "
            f"for conversation_id={conversation_id}"
        )

    @staticmethod
    def _extract_evaluator_attributions(eval_case: EvaluatedCase) -> list[dict]:
        \"\"\"Parse attribution from eval_case.reason JSON.

        Supports two formats:
        - New flat format: ``attributed_skill`` (single string)
        - Legacy nested format: ``skill_attributions`` (list of dicts)

        Returns:
            list[dict]: Each dict contains {skill_name}. Empty on parse failure.
        \"\"\"
        if not eval_case.reason:
            return []
        try:
            reason_data = json.loads(eval_case.reason)
        except (json.JSONDecodeError, AttributeError, TypeError):
            return []

        # New flat format: attributed_skill is a single string
        attributed_skill = reason_data.get("attributed_skill")
        if isinstance(attributed_skill, str) and attributed_skill:
            return [{"skill_name": attributed_skill}]

        # Legacy nested format: skill_attributions is a list of dicts
        skill_attributions = reason_data.get("skill_attributions")
        if isinstance(skill_attributions, list):
            return skill_attributions

        return []

    @staticmethod
    def _match_operator_from_attribution(
        attribution_skill_name: str,
        valid_op_ids: list[str],
    ) -> str | None:
        \"\"\"Match evaluator skill_name to operator_id.

        Rules (descending priority):
        1. Exact match
        2. Prefix match (min length >= 50% of longer string)
        3. Normalized match (strip _skill suffix)
        \"\"\"
        # 1. Exact match
        for op_id in valid_op_ids:
            if attribution_skill_name == op_id:
                return op_id

        # 2. Prefix match
        for op_id in valid_op_ids:
            longer = max(len(attribution_skill_name), len(op_id))
            shorter = min(len(attribution_skill_name), len(op_id))
            if shorter < longer * 0.5:
                continue
            if attribution_skill_name.startswith(op_id) or op_id.startswith(
                attribution_skill_name
            ):
                return op_id

        # 3. Normalized match (strip _skill suffix)
        norm_name = attribution_skill_name.lower().removesuffix("_skill")
        for op_id in valid_op_ids:
            norm_op = op_id.lower().removesuffix("_skill")
            if norm_name == norm_op:
                return op_id

        return None

    async def _attribute(
        self,
        *,
        failure_batch: list[tuple[dict, EvaluatedCase, Case]],
        success_batch: list[tuple[dict, EvaluatedCase, Case]],
        skill_contents: dict[str, str],
    ) -> dict[str, AttributedBatch]:
        \"\"\"基于评估器语义归因的归因分配（ADR-0009）。

        策略：
        1. 单 operator: short-circuit,读 attributed_skill 过滤 badcase(非 skill 失败丢),
           goodcase 全量保留(F1)。
        2. 多 operator:
           a. badcase: 从 eval_case.reason 提取 attributed_skill,匹配 operator;
              无归因或匹配失败 → 丢弃(不 fallback,不兜底)。
           b. goodcase: 评估器不归因(is_pass=true 设计如此)→ 全量兜底到所有 operator(F2)。
        \"\"\"
        op_ids = list(skill_contents.keys())

        # 单 operator short-circuit（F1）：badcase 按 attributed_skill 过滤
        if len(op_ids) == 1:
            sole_op = op_ids[0]
            filtered_failures = [
                item
                for item in failure_batch
                if self._single_skill_badcase_match(item[1], sole_op)
            ]
            successes = list(success_batch)
            if not filtered_failures and not successes:
                return {}
            return {
                sole_op: AttributedBatch(
                    operator_id=sole_op,
                    failures=filtered_failures,
                    successes=successes,
                ),
            }

        attr_failures: dict[str, list] = {op: [] for op in op_ids}
        attr_successes: dict[str, list] = {op: [] for op in op_ids}

        # failure_batch：badcase 无归因 → _attribute_single_case 返回 [] → 丢，不兜底
        for item in failure_batch:
            trajectory, eval_case, case = item
            matched_ops = self._attribute_single_case(eval_case, trajectory, op_ids)
            for op in matched_ops:
                attr_failures[op].append(item)

        # success_batch（F2）：无归因 → 全量兜底；有归因 → 精准分
        for item in success_batch:
            trajectory, eval_case, case = item
            matched_ops = self._attribute_single_case(eval_case, trajectory, op_ids)
            if matched_ops:
                for op in matched_ops:
                    attr_successes[op].append(item)
            else:
                for op in op_ids:
                    attr_successes[op].append(item)

        result: dict[str, AttributedBatch] = {}
        for op in op_ids:
            if attr_failures[op] or attr_successes[op]:
                result[op] = AttributedBatch(
                    operator_id=op,
                    failures=attr_failures[op],
                    successes=attr_successes[op],
                )

        return result

    def _single_skill_badcase_match(self, eval_case: EvaluatedCase, sole_op: str) -> bool:
        \"\"\"单 skill badcase 是否归因到唯一 operator（F1）。\"\"\"
        eval_attributions = self._extract_evaluator_attributions(eval_case)
        for attr in eval_attributions:
            skill_name = attr.get("skill_name", "")
            if self._match_operator_from_attribution(skill_name, [sole_op]):
                return True
        return False

    def _attribute_single_case(
        self,
        eval_case: EvaluatedCase,
        trajectory: dict,
        valid_op_ids: list[str],
    ) -> list[str]:
        \"\"\"Attribute a single case. Semantic-only; skip if no attribution.\"\"\"
        eval_attributions = self._extract_evaluator_attributions(eval_case)
        if not eval_attributions:
            logger.info("Attribution: skipped (no skill_attributions from evaluator)")
            return []

        matched: list[str] = []
        for attr in eval_attributions:
            skill_name = attr.get("skill_name", "")
            op_id = self._match_operator_from_attribution(skill_name, valid_op_ids)
            if op_id and op_id not in matched:
                matched.append(op_id)

        if matched:
            logger.info("Attribution (evaluator): operators=%s", matched)
        else:
            logger.info(
                "Attribution: skipped (evaluator attributions did not match any operator)"
            )
        return matched

    _SCENARIO_NAME = "edp_agent"

    def _build_analyst_prompt(
        self,
        template_name: str,
        skill_content: str,
        trajectories_text: str,
        step_buffer_context: str,
        meta_skill_context: str,
    ) -> str:
        \"\"\"Override vendor prompt loading with project two-level lookup.\"\"\"
        system = load_prompt(template_name, self._SCENARIO_NAME)
        user = f"## Current Skill\\n{skill_content}\\n\\n"
        user += f"## Edits Budget\\nProduce at most L={self._scheduler.max_lr} edits.\\n\\n"
        if step_buffer_context.strip():
            user += f"## Previous Steps in This Epoch\\n{step_buffer_context}\\n\\n"
        if meta_skill_context.strip():
            user += f"## Optimizer Memory\\n{meta_skill_context}\\n\\n"
        if "error" in template_name:
            user += f"## Failed Trajectories\\n{trajectories_text}"
        else:
            user += f"## Successful Trajectories\\n{trajectories_text}"
        return f"{system}\\n\\n{user}"

    async def _rollout(
        self,
        cases: list[Case],
    ) -> tuple[list[EvaluatedCase], list[Trajectory]]:
        \"\"\"Execute conversations via Adapter sidecar and collect trajectories.\"\"\"
        evaluated_list: list[EvaluatedCase] = []
        trajectories: list[Trajectory] = []

        for case in cases:
            case_extra = case.inputs.get("extra_data", {})
            extra = merge_extra_data(self._rollout_extra_data, case_extra)

            # 1. Generate unique conversation_id
            if self._conversation_id_factory:
                conversation_id = self._conversation_id_factory.new(
                    phase="train", case_id=case.case_id,
                )
            else:
                logger.warning(
                    "No ConversationIdFactory injected — falling back to case.case_id "
                    "(risk of stale trajectory reads)"
                )
                conversation_id = case.case_id

            # 2. Invoke agent with unique conversation_id
            try:
                result = await self._agent.invoke(
                    {**case.inputs, "conversation_id": conversation_id, "extra_data": extra},
                )
            except Exception as exc:
                result = {"answer": "", "error": str(exc)}

            answer = result if isinstance(result, dict) else {"answer": str(result)}

            # 3. Fetch trajectory with retry
            try:
                trace_data = await self._get_required_trace(conversation_id)

                # 4. Normalize trajectory and build case copy
                trajectory_dict = normalize_trace_to_trajectory(trace_data)
                case_for_eval = case.model_copy(
                    update={"inputs": {
                        **case.inputs,
                        "trajectory": trajectory_dict,
                    }},
                    deep=True,
                )
            except TrajectoryUnavailableError as exc:
                logger.warning("Skipping case %s: %s", case.case_id, exc)
                # Placeholder: keeps batch aligned for upstream zip()
                evaluated_list.append(EvaluatedCase(case=case, answer={"answer": ""}, score=0.0))
                trajectories.append(Trajectory(execution_id=uuid.uuid4().hex, steps=[]))
                continue

            messages = trace_data.get("messages", [])
            trajectory = self._messages_to_trajectory(messages)
            trajectory.case_id = case.case_id

            # 5. Evaluate using case copy
            eval_case = self._evaluator.evaluate(case_for_eval, answer)

            evaluated_list.append(eval_case)
            trajectories.append(trajectory)

        return evaluated_list, trajectories

    def _format_single(
        self,
        trajectory: Trajectory,
        evaluated_case: EvaluatedCase,
        case: Case,
    ) -> str:
        \"\"\"Format single trajectory to readable text.\"\"\"
        lines: list[str] = []
        turn = 0
        for step in trajectory.steps:
            if step.kind == "llm" and isinstance(step.detail, LLMCallDetail):
                for msg in step.detail.messages:
                    role = msg.get("role", "unknown") if isinstance(msg, dict) else "unknown"
                    content = (
                        msg.get("content", "") if isinstance(msg, dict) else str(msg)
                    )
                    if role == "user":
                        turn += 1
                        lines.append(f"[Turn {turn}] User: {content}")
                    elif role == "assistant":
                        lines.append(f"Assistant: {content}")
                if step.detail.response:
                    resp = step.detail.response
                    resp_content = (
                        resp.get("content", "") if isinstance(resp, dict) else str(resp)
                    )
                    if not any(
                        m is resp
                        for m in step.detail.messages
                    ):
                        lines.append(f"Assistant: {resp_content}")
            elif step.kind == "tool" and isinstance(step.detail, ToolCallDetail):
                if step.detail.call_args:
                    lines.append(
                        f"Tool call: {step.detail.tool_name}({step.detail.call_args})"
                    )
                if step.detail.call_result:
                    lines.append(
                        f"Tool result [{step.detail.tool_name}]: {step.detail.call_result}"
                    )
        return "\\n".join(lines)
"""


# ── Constructor ──


def test_edp_optimizer_receives_adapter_client(tmp_path: Path) -> None:
    """构造函数注入 adapter_client，存储为 self._adapter_client。"""
    from evo_agent.scenario.registry import ScenarioRegistry
    from evo_agent.types import OptimizeRequest

    scenarios = tmp_path / "scenarios"
    scenarios.mkdir()
    folder = scenarios / "edp_test"
    folder.mkdir()
    (folder / "scenario.yaml").write_text(
        'schema_version: "1.0"\noptimizer_class: optimizer.EDPAgentOptimizer\n',
        encoding="utf-8",
    )
    (folder / "optimizer.py").write_text(_OPTIMIZER_CODE, encoding="utf-8")

    registry = ScenarioRegistry(scenarios_dir=scenarios)
    request = OptimizeRequest(
        scenario="edp_test",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
    )
    fake_adapter = MagicMock()
    optimizer = registry.build_optimizer(
        request,
        dependencies={
            "agent": MagicMock(),
            "evaluator": MagicMock(),
            "llm": MagicMock(),
            "model": "test",
            "train_cases": MagicMock(),
            "adapter_client": fake_adapter,
        },
    )

    assert optimizer._adapter_client is fake_adapter


def test_edp_optimizer_receives_operators(tmp_path: Path) -> None:
    """构造函数注入 operators。"""
    from evo_agent.scenario.registry import ScenarioRegistry
    from evo_agent.types import OptimizeRequest

    scenarios = tmp_path / "scenarios"
    scenarios.mkdir()
    folder = scenarios / "edp_test2"
    folder.mkdir()
    (folder / "scenario.yaml").write_text(
        'schema_version: "1.0"\noptimizer_class: optimizer.EDPAgentOptimizer\n',
        encoding="utf-8",
    )
    (folder / "optimizer.py").write_text(_OPTIMIZER_CODE, encoding="utf-8")

    registry = ScenarioRegistry(scenarios_dir=scenarios)
    request = OptimizeRequest(
        scenario="edp_test2",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
    )
    fake_ops = {"skill_a": MagicMock()}
    optimizer = registry.build_optimizer(
        request,
        dependencies={
            "agent": MagicMock(),
            "evaluator": MagicMock(),
            "llm": MagicMock(),
            "model": "test",
            "train_cases": MagicMock(),
            "operators": fake_ops,
        },
    )

    assert optimizer._operators is fake_ops


# ── _messages_to_trajectory() ──


@pytest.fixture
def edp_cls(tmp_path: Path) -> type:
    """动态加载 EDPAgentOptimizer 类。"""
    import importlib.util

    path = tmp_path / "optimizer.py"
    path.write_text(_OPTIMIZER_CODE, encoding="utf-8")
    spec = importlib.util.spec_from_file_location("_test_edp_opt", path)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules["_test_edp_opt"] = mod
    spec.loader.exec_module(mod)
    yield mod.EDPAgentOptimizer
    sys.modules.pop("_test_edp_opt", None)


def test_messages_to_trajectory_basic(edp_cls: type) -> None:
    """user → assistant 两条消息 → 2 个 step。"""
    messages = [
        {"role": "user", "content": "你好"},
        {"role": "assistant", "content": "你好！有什么可以帮您？"},
    ]
    traj = edp_cls._messages_to_trajectory(messages)
    assert len(traj.steps) >= 1
    # First step should be LLM with user message
    assert traj.steps[0].kind == "llm"


def test_messages_to_trajectory_tool_calls(edp_cls: type) -> None:
    """assistant 的 tool_calls 展开为独立 step。"""
    messages = [
        {"role": "user", "content": "推荐产品"},
        {
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {
                    "function": {
                        "name": "search_products",
                        "arguments": '{"query": "手机"}',
                    }
                }
            ],
        },
    ]
    traj = edp_cls._messages_to_trajectory(messages)
    tool_steps = [s for s in traj.steps if s.kind == "tool"]
    assert len(tool_steps) == 1
    assert tool_steps[0].detail.tool_name == "search_products"


def test_messages_to_trajectory_tool_result(edp_cls: type) -> None:
    """tool 角色消息 → TrajectoryStep(role='tool')。"""
    messages = [
        {"role": "tool", "name": "search_products", "content": "找到了3个产品"},
    ]
    traj = edp_cls._messages_to_trajectory(messages)
    assert len(traj.steps) == 1
    assert traj.steps[0].kind == "tool"
    assert traj.steps[0].detail.call_result == "找到了3个产品"


def test_messages_to_trajectory_empty(edp_cls: type) -> None:
    """空 messages 返回空 Trajectory。"""
    traj = edp_cls._messages_to_trajectory([])
    assert traj.steps == []


# ── _rollout() ──


@pytest.mark.asyncio
async def test_rollout_calls_invoke(edp_cls: type) -> None:
    """每个 case 调用 _agent.invoke()。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock answer"})

    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": [{"role": "user", "content": "hi"}]})

    evaluator = MagicMock()
    case = Case(
        case_id="c1",
        inputs={"query": "hello"},
        label={"expected_behavior": "greet"},
    )
    eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    evaluator.evaluate = MagicMock(return_value=eval_result)

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0

    await optimizer._rollout([case])
    agent.invoke.assert_called_once()


@pytest.mark.asyncio
async def test_rollout_fetches_traces(edp_cls: type) -> None:
    """每个 case 调用 get_traces()。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": [{"role": "user", "content": "hi"}]})

    evaluator = MagicMock()
    case = Case(
        case_id="c1",
        inputs={"query": "hello"},
        label={"expected_behavior": "greet"},
    )
    eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    evaluator.evaluate = MagicMock(return_value=eval_result)

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0

    await optimizer._rollout([case])
    adapter.get_traces.assert_called_once_with(case_id="c1")


@pytest.mark.asyncio
async def test_rollout_evaluates_answer(edp_cls: type) -> None:
    """evaluator.evaluate 使用 invoke 返回的 answer。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "specific_answer"})

    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": [{"role": "user", "content": "hi"}]})

    evaluator = MagicMock()
    case = Case(
        case_id="c1",
        inputs={"query": "hello"},
        label={"expected_behavior": "greet"},
    )
    eval_result = EvaluatedCase(case=case, answer={"answer": "specific_answer"}, score=0.9)
    evaluator.evaluate = MagicMock(return_value=eval_result)

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0

    await optimizer._rollout([case])
    evaluator.evaluate.assert_called_once()
    # The answer passed to evaluate should be the invoke result
    call_args = evaluator.evaluate.call_args
    assert call_args[0][1] == {"answer": "specific_answer"}


@pytest.mark.asyncio
async def test_rollout_returns_evaluated_and_trajectories(edp_cls: type) -> None:
    """返回值结构为 (list[EvaluatedCase], list[Trajectory])。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(
        return_value={
            "messages": [
                {"role": "user", "content": "hi"},
                {"role": "assistant", "content": "hello"},
            ]
        }
    )

    evaluator = MagicMock()
    case = Case(
        case_id="c1",
        inputs={"query": "hello"},
        label={"expected_behavior": "greet"},
    )
    eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    evaluator.evaluate = MagicMock(return_value=eval_result)

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0

    evaluated, trajectories = await optimizer._rollout([case])
    assert len(evaluated) == 1
    assert len(trajectories) == 1
    assert isinstance(trajectories[0], Trajectory)


@pytest.mark.asyncio
async def test_rollout_forwards_extra_data(edp_cls: type) -> None:
    """invoke payload 包含合并后的场景级 + case 级 extra_data。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": [{"role": "user", "content": "hi"}]})

    evaluator = MagicMock()
    case = Case(
        case_id="c1",
        inputs={"query": "hello", "extra_data": {"role_id": "2"}},
        label={"expected_behavior": "greet"},
    )
    eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    evaluator.evaluate = MagicMock(return_value=eval_result)

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {"role_name": "mobile-bank"}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0

    await optimizer._rollout([case])

    call_args = agent.invoke.call_args[0][0]
    assert call_args["extra_data"] == {"role_name": "mobile-bank", "role_id": "2"}
    assert call_args["conversation_id"] == "c1"


# ── _format_single() ──


def _make_evaluated_case(case: Any) -> Any:
    from openjiuwen.agent_evolving.dataset import EvaluatedCase

    return EvaluatedCase(case=case, answer={"answer": "ok"}, score=0.8)


def test_format_single_filters_metadata(edp_cls: type) -> None:
    """输出不含 usage_metadata 等噪音字段。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(
        case_id="c1",
        inputs={"query": "hello"},
        label={"expected_behavior": "greet"},
    )
    traj = Trajectory(
        execution_id="e1",
        steps=[
            TrajectoryStep(
                kind="llm",
                detail=LLMCallDetail(
                    model="test",
                    messages=[{"role": "user", "content": "hello"}],
                    response={"role": "assistant", "content": "hi"},
                    usage={"total_tokens": 100},
                ),
            ),
        ],
    )
    optimizer = edp_cls.__new__(edp_cls)
    text = optimizer._format_single(traj, _make_evaluated_case(case), case)
    assert "usage_metadata" not in text
    assert "total_tokens" not in text


def test_format_single_turn_markers(edp_cls: type) -> None:
    """输出含 [Turn N] User: 标记。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(
        case_id="c1",
        inputs={"query": "hello"},
        label={"expected_behavior": "greet"},
    )
    traj = Trajectory(
        execution_id="e1",
        steps=[
            TrajectoryStep(
                kind="llm",
                detail=LLMCallDetail(
                    model="",
                    messages=[{"role": "user", "content": "hello"}],
                    response={"role": "assistant", "content": "hi there"},
                ),
            ),
        ],
    )
    optimizer = edp_cls.__new__(edp_cls)
    text = optimizer._format_single(traj, _make_evaluated_case(case), case)
    assert "[Turn 1] User: hello" in text


def test_format_single_tool_calls(edp_cls: type) -> None:
    """tool_calls 格式化为 Tool call: name(args)。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(
        case_id="c1",
        inputs={"query": "search"},
        label={"expected_behavior": "search"},
    )
    traj = Trajectory(
        execution_id="e1",
        steps=[
            TrajectoryStep(
                kind="tool",
                detail=ToolCallDetail(
                    tool_name="search_products",
                    call_args='{"query": "手机"}',
                ),
            ),
        ],
    )
    optimizer = edp_cls.__new__(edp_cls)
    text = optimizer._format_single(traj, _make_evaluated_case(case), case)
    assert "Tool call: search_products" in text


def test_format_single_tool_results(edp_cls: type) -> None:
    """tool 结果格式化为 Tool result [name]: content。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(
        case_id="c1",
        inputs={"query": "search"},
        label={"expected_behavior": "search"},
    )
    traj = Trajectory(
        execution_id="e1",
        steps=[
            TrajectoryStep(
                kind="tool",
                detail=ToolCallDetail(
                    tool_name="search_products",
                    call_result="找到了3个产品",
                ),
            ),
        ],
    )
    optimizer = edp_cls.__new__(edp_cls)
    text = optimizer._format_single(traj, _make_evaluated_case(case), case)
    assert "Tool result [search_products]: 找到了3个产品" in text


# ── scenario.yaml integration ──


def test_scenario_yaml_skills_list(tmp_path: Path) -> None:
    """scenario.yaml 正确解析 skills + optimize 标记。"""
    from evo_agent.scenario.registry import ScenarioRegistry

    scenarios = tmp_path / "scenarios"
    scenarios.mkdir()
    folder = scenarios / "edp_agent"
    folder.mkdir()
    (folder / "scenario.yaml").write_text(
        """\
schema_version: "1.0"
optimizer_class: optimizer.EDPAgentOptimizer
adapter_url: "http://localhost:9090"
skills:
  - name: product_recommend_skill
    optimize: true
  - name: interact_finance_rec_skill
    optimize: true
  - name: fund_planning_skill
    optimize: false
hyperparams:
  batch_size: 8
""",
        encoding="utf-8",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios)
    cfg = registry.load_scenario_config("edp_agent")

    assert len(cfg.skills) == 3
    assert cfg.get_optimize_skills() == [
        "product_recommend_skill",
        "interact_finance_rec_skill",
    ]
    assert cfg.adapter_url == "http://localhost:9090"


# ── W7.3: 轨迹注入 + 唯一 conversation_id + retry ──


@pytest.mark.asyncio
async def test_rollout_uses_unique_conversation_id(edp_cls: type) -> None:
    """_rollout() 使用 ConversationIdFactory 生成唯一 conversation_id。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    from evo_agent.conversation import ConversationIdFactory

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": [{"role": "user", "content": "hi"}]})

    evaluator = MagicMock()
    case = Case(case_id="c1", inputs={"query": "hello"}, label={"answer": "a"})
    evaluator.evaluate = MagicMock(
        return_value=EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    )

    factory = ConversationIdFactory(run_id="run1")
    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = factory
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0

    await optimizer._rollout([case])

    # invoke 收到的 conversation_id 不是 case.case_id
    call_args = agent.invoke.call_args[0][0]
    conv_id = call_args["conversation_id"]
    assert conv_id != "c1"
    # 格式为 "{run_id}:train:{n}:{case_id}"
    assert conv_id.startswith("run1:train:")
    assert conv_id.endswith(":c1")


@pytest.mark.asyncio
async def test_rollout_injects_trajectory_into_case_copy(edp_cls: type) -> None:
    """构造 case.model_copy() 注入 trajectory，不原地修改 case.inputs。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    messages = [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "test"})

    evaluator = MagicMock()
    case = Case(case_id="c1", inputs={"query": "hello"}, label={"answer": "a"})
    evaluator.evaluate = MagicMock(
        return_value=EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    )

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0

    await optimizer._rollout([case])

    # evaluator 收到的 case.inputs 包含 "trajectory"
    eval_call_args = evaluator.evaluate.call_args
    eval_case = eval_call_args[0][0]
    assert "trajectory" in eval_case.inputs

    # 原始 case.inputs 不包含 "trajectory"
    assert "trajectory" not in case.inputs


@pytest.mark.asyncio
async def test_rollout_trajectory_format(edp_cls: type) -> None:
    """trajectory 注入格式 {"summary": ..., "messages": ...}。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    messages = [{"role": "user", "content": "hi"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "short summary"})

    evaluator = MagicMock()
    case = Case(case_id="c1", inputs={"query": "hello"}, label={"answer": "a"})
    evaluator.evaluate = MagicMock(
        return_value=EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    )

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0

    await optimizer._rollout([case])

    eval_case = evaluator.evaluate.call_args[0][0]
    traj = eval_case.inputs["trajectory"]
    # summary is now a TrajectorySummary dict (not a plain string)
    assert isinstance(traj["summary"], dict)
    assert traj["summary"]["summary"] == "short summary"
    assert isinstance(traj["messages"], list)


@pytest.mark.asyncio
async def test_rollout_retry_succeeds_on_second_attempt(edp_cls: type) -> None:
    """cleaned-traces 第一次无 messages，第二次有 → 成功。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    messages = [{"role": "user", "content": "hi"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(
        side_effect=[
            {"messages": []},  # 1st attempt: empty
            {"messages": messages},  # 2nd attempt: success
        ]
    )

    evaluator = MagicMock()
    case = Case(case_id="c1", inputs={"query": "hello"}, label={"answer": "a"})
    evaluator.evaluate = MagicMock(
        return_value=EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    )

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 3
    optimizer._trace_retry_backoff = 0.0

    evaluated, trajectories = await optimizer._rollout([case])
    assert len(evaluated) == 1
    assert len(trajectories) == 1


@pytest.mark.asyncio
async def test_rollout_retry_exhausted_skips_case(
    edp_cls: type, caplog: pytest.LogCaptureFixture
) -> None:
    """retry 耗尽后该 case 返回 placeholder（score=0.0），保持批次对齐。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": []})  # 始终空

    evaluator = MagicMock()
    case = Case(case_id="c1", inputs={"query": "hello"}, label={"answer": "a"})
    evaluator.evaluate = MagicMock(
        return_value=EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)
    )

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 2
    optimizer._trace_retry_backoff = 0.0

    with caplog.at_level(logging.WARNING):
        evaluated, trajectories = await optimizer._rollout([case])

    # Placeholder returned instead of skipping — keeps batch aligned
    assert len(evaluated) == 1
    assert len(trajectories) == 1
    assert evaluated[0].score == 0.0
    assert trajectories[0].steps == []
    assert "Skipping case" in caplog.text


@pytest.mark.asyncio
async def test_rollout_skips_case_on_trajectory_unavailable(edp_cls: type) -> None:
    """2 个 case，第 1 个轨迹不可用 → placeholder(score=0.0)，第 2 个正常 → 共 2 个结果。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "mock"})

    messages = [{"role": "user", "content": "hi"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(
        side_effect=[
            {"messages": []},  # case 1: no traces (retries exhausted)
            {"messages": []},  # case 1: retry 2
            {"messages": messages},  # case 2: success
        ]
    )

    evaluator = MagicMock()
    case1 = Case(case_id="c1", inputs={"query": "hello"}, label={"answer": "a"})
    case2 = Case(case_id="c2", inputs={"query": "world"}, label={"answer": "b"})
    evaluator.evaluate = MagicMock(
        return_value=EvaluatedCase(case=case2, answer={"answer": "mock"}, score=0.8)
    )

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._agent = agent
    optimizer._adapter_client = adapter
    optimizer._evaluator = evaluator
    optimizer._rollout_extra_data = {}
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 2
    optimizer._trace_retry_backoff = 0.0

    evaluated, trajectories = await optimizer._rollout([case1, case2])
    # 2 items returned: 1 placeholder + 1 real (batch aligned with input)
    assert len(evaluated) == 2
    assert len(trajectories) == 2
    assert evaluated[0].score == 0.0  # placeholder
    assert evaluated[1].score == 0.8  # real evaluation


def test_edp_optimizer_accepts_conversation_id_factory(edp_cls: type) -> None:
    """EDPAgentOptimizer.__init__() 接受 conversation_id_factory 参数。"""
    from evo_agent.conversation import ConversationIdFactory

    factory = ConversationIdFactory(run_id="test")
    optimizer = edp_cls.__new__(edp_cls)
    optimizer.__init__(
        adapter_client=MagicMock(),
        conversation_id_factory=factory,
        agent=MagicMock(),
        evaluator=MagicMock(),
        llm=MagicMock(),
        model="test",
        train_cases=MagicMock(),
    )
    assert optimizer._conversation_id_factory is factory


# ── W9.1: _extract_evaluator_attributions() ──


def test_extract_evaluator_attributions_valid(edp_cls: type) -> None:
    """正常 JSON → 返回 skill_attributions 列表。"""
    import json

    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    reason_data = {
        "reason": "整体理由",
        "skill_attributions": [
            {
                "skill_name": "product_recommend_skill",
                "reason": "推荐了不相关的产品",
                "usage_status": "executed",
                "impact": "negative",
            }
        ],
    }
    case = Case(case_id="c1", inputs={"query": "hi"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.3, reason=json.dumps(reason_data))

    result = edp_cls._extract_evaluator_attributions(eval_case)
    assert len(result) == 1
    assert result[0]["skill_name"] == "product_recommend_skill"


def test_extract_evaluator_attributions_empty_reason(edp_cls: type) -> None:
    """空 reason → 返回空列表。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(case_id="c1", inputs={"query": "hi"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.3, reason="")

    assert edp_cls._extract_evaluator_attributions(eval_case) == []


def test_extract_evaluator_attributions_malformed_json(edp_cls: type) -> None:
    """malformed JSON → 返回空列表，不抛异常。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(case_id="c1", inputs={"query": "hi"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.3, reason="{invalid json")

    assert edp_cls._extract_evaluator_attributions(eval_case) == []


def test_extract_evaluator_attributions_no_skill_attributions_key(edp_cls: type) -> None:
    """JSON 中无 skill_attributions 字段 → 返回空列表。"""
    import json

    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(case_id="c1", inputs={"query": "hi"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(
        case=case,
        answer={},
        score=0.3,
        reason=json.dumps({"reason": "只有 reason 没有 skill_attributions"}),
    )

    assert edp_cls._extract_evaluator_attributions(eval_case) == []


def test_extract_evaluator_attributions_multiple(edp_cls: type) -> None:
    """多个 skill_attributions → 全部返回。"""
    import json

    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    reason_data = {
        "reason": "两个 skill 都有问题",
        "skill_attributions": [
            {
                "skill_name": "skill_a",
                "reason": "reason a",
                "usage_status": "executed",
                "impact": "negative",
            },
            {
                "skill_name": "skill_b",
                "reason": "reason b",
                "usage_status": "read_only",
                "impact": "negative",
            },
        ],
    }
    case = Case(case_id="c1", inputs={"query": "hi"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.2, reason=json.dumps(reason_data))

    result = edp_cls._extract_evaluator_attributions(eval_case)
    assert len(result) == 2
    names = [a["skill_name"] for a in result]
    assert "skill_a" in names
    assert "skill_b" in names


# ── W9.2: _match_operator_from_attribution() ──


def test_match_operator_exact(edp_cls: type) -> None:
    """精确匹配：skill_name == operator_id。"""
    result = edp_cls._match_operator_from_attribution(
        "product_recommend_skill",
        ["product_recommend_skill", "interact_finance_skill"],
    )
    assert result == "product_recommend_skill"


def test_match_operator_prefix(edp_cls: type) -> None:
    """前缀匹配：skill_name 以 operator_id 开头。"""
    result = edp_cls._match_operator_from_attribution(
        "product_recommend_skill",
        ["product_recommend", "interact_finance"],
    )
    assert result == "product_recommend"


def test_match_operator_prefix_reverse(edp_cls: type) -> None:
    """前缀匹配：operator_id 以 skill_name 开头。"""
    result = edp_cls._match_operator_from_attribution(
        "product_recommend",
        ["product_recommend_skill", "interact_finance_skill"],
    )
    assert result == "product_recommend_skill"


def test_match_operator_normalized(edp_cls: type) -> None:
    """归一化匹配：去掉 _skill 后缀后精确比较。"""
    result = edp_cls._match_operator_from_attribution(
        "product_recommend_skill",
        ["product_recommend", "interact_finance"],
    )
    assert result == "product_recommend"


def test_match_operator_no_match(edp_cls: type) -> None:
    """无匹配 → 返回 None。"""
    result = edp_cls._match_operator_from_attribution(
        "unknown_skill",
        ["product_recommend_skill", "interact_finance_skill"],
    )
    assert result is None


def test_match_operator_short_prefix_rejected(edp_cls: type) -> None:
    """过短前缀不匹配：'prod' 不应匹配 'product_recommend_skill'。"""
    result = edp_cls._match_operator_from_attribution(
        "prod",
        ["product_recommend_skill"],
    )
    assert result is None


# ── W9.3: _attribute() override ──


def _make_eval_case_with_attributions(case_id: str, score: float, skill_attributions: list[dict]):
    """Helper: 构造带 skill_attributions 的 EvaluatedCase。"""
    import json

    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(case_id=case_id, inputs={"query": "test"}, label={"expected": "ok"})
    reason = json.dumps(
        {
            "reason": f"test reason for {case_id}",
            "skill_attributions": skill_attributions,
        }
    )
    return EvaluatedCase(case=case, answer={"answer": "mock"}, score=score, reason=reason)


def _make_trajectory_dict(case_id: str, tool_names: list[str]) -> dict:
    """Helper: 构造包含 tool_calls 的 trajectory dict。"""
    messages: list[dict[str, Any]] = [{"role": "user", "content": "test"}]
    if tool_names:
        messages.append(
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {"function": {"name": name, "arguments": "{}"}} for name in tool_names
                ],
            }
        )
    return {"case_id": case_id, "messages": messages}


@pytest.mark.asyncio
async def test_attribute_single_operator_short_circuit(edp_cls: type) -> None:
    """单 operator short-circuit：badcase 需归因到该 skill 才保留（ADR-0009 F1）。

    修订 WAVE_9 AC1：单 op 不再无条件全量归属，读 attributed_skill 过滤。
    """
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_flat_attributed("c1", 0.3, "only_skill")
    traj = {"case_id": "c1", "messages": []}

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"only_skill": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"only_skill": "doc text"},
    )

    assert "only_skill" in result
    assert len(result["only_skill"].failures) == 1


@pytest.mark.asyncio
async def test_attribute_with_evaluator_attribution(edp_cls: type) -> None:
    """评估器有归因时，只归因到评估器指出的 operator。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_with_attributions(
        "c1",
        score=0.3,
        skill_attributions=[
            {
                "skill_name": "skill_b",
                "reason": "skill_b caused the problem",
                "usage_status": "executed",
                "impact": "negative",
            }
        ],
    )
    # trajectory 中同时调用了 skill_a 和 skill_b，但语义归因只看 evaluator 输出
    traj = _make_trajectory_dict("c1", ["skill_a", "skill_b"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # 评估器说问题在 skill_b，所以只归因到 skill_b
    assert "skill_b" in result
    assert len(result["skill_b"].failures) == 1
    # skill_a 不应出现在结果中（无归因 → 跳过）
    assert "skill_a" not in result


@pytest.mark.asyncio
async def test_attribute_no_evaluator_attribution_skipped(edp_cls: type) -> None:
    """评估器无归因时，case 直接跳过。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    # reason 为空 → 无评估器归因
    eval_case = EvaluatedCase(case=case, answer={}, score=0.3, reason="")
    traj = _make_trajectory_dict("c1", ["skill_a"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # 无归因 → 所有 operator 都不应收到此 case
    assert result == {}


@pytest.mark.asyncio
async def test_attribute_no_tool_match_skipped(edp_cls: type) -> None:
    """评估器无归因 + tool name 无匹配 → case 直接跳过。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.3, reason="")
    # trajectory 中没有 tool_calls
    traj = {"case_id": "c1", "messages": [{"role": "user", "content": "hi"}]}

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # 无归因 → 跳过
    assert result == {}


@pytest.mark.asyncio
async def test_attribute_malformed_reason(edp_cls: type) -> None:
    """eval_case.reason JSON 格式错误时，解析失败 → case 跳过。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.3, reason="{broken json")
    traj = _make_trajectory_dict("c1", ["skill_a"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # JSON 解析失败 → 无归因 → 跳过
    assert result == {}


@pytest.mark.asyncio
async def test_attribute_skill_not_in_operators(edp_cls: type) -> None:
    """评估器归因的 skill 不在 operator 列表中 → 匹配失败，case 跳过。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_with_attributions(
        "c1",
        score=0.3,
        skill_attributions=[
            {
                "skill_name": "nonexistent_skill",
                "reason": "some reason",
                "usage_status": "executed",
                "impact": "negative",
            }
        ],
    )
    traj = _make_trajectory_dict("c1", ["skill_a"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # nonexistent_skill 不在 operators 中 → 匹配失败 → 跳过
    assert result == {}


# ── W9.4: 集成级归因测试 ──


@pytest.mark.asyncio
async def test_attribute_multi_operator_integration(edp_cls: type) -> None:
    """3 个 operator 的集成归因测试。

    场景：
    - case_1: 评估器归因到 skill_a → 只归因到 skill_a
    - case_2: 评估器归因到 skill_b → 只归因到 skill_b
    - case_3: 评估器无归因 → 跳过（不做 fallback）
    - case_4: 评估器无归因 → 跳过（不做 fallback）
    """
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    # case_1: evaluator → skill_a
    c1 = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    ec1 = _make_eval_case_with_attributions(
        "c1",
        0.3,
        [
            {
                "skill_name": "skill_a",
                "reason": "a is wrong",
                "usage_status": "executed",
                "impact": "negative",
            }
        ],
    )
    t1 = _make_trajectory_dict("c1", ["skill_a", "skill_b"])

    # case_2: evaluator → skill_b
    c2 = Case(case_id="c2", inputs={"q": "x"}, label={"expected": "ok"})
    ec2 = _make_eval_case_with_attributions(
        "c2",
        0.2,
        [
            {
                "skill_name": "skill_b",
                "reason": "b is wrong",
                "usage_status": "executed",
                "impact": "negative",
            }
        ],
    )
    t2 = _make_trajectory_dict("c2", ["skill_a", "skill_b"])

    # case_3: no evaluator attribution → skipped
    c3 = Case(case_id="c3", inputs={"q": "x"}, label={"expected": "ok"})
    ec3 = EvaluatedCase(case=c3, answer={}, score=0.4, reason="")
    t3 = _make_trajectory_dict("c3", ["skill_a"])

    # case_4: no evaluator attribution → skipped
    c4 = Case(case_id="c4", inputs={"q": "x"}, label={"expected": "ok"})
    ec4 = EvaluatedCase(case=c4, answer={}, score=0.1, reason="")
    t4 = {"case_id": "c4", "messages": [{"role": "user", "content": "hi"}]}

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {
        "skill_a": MagicMock(),
        "skill_b": MagicMock(),
        "skill_c": MagicMock(),
    }

    failure_batch = [(t1, ec1, c1), (t2, ec2, c2), (t3, ec3, c3), (t4, ec4, c4)]
    result = await optimizer._attribute(
        failure_batch=failure_batch,
        success_batch=[],
        skill_contents={"skill_a": "a", "skill_b": "b", "skill_c": "c"},
    )

    # skill_a: case_1 only (evaluator attributed)
    assert "skill_a" in result
    skill_a_cases = [f[1].case.case_id for f in result["skill_a"].failures]
    assert skill_a_cases == ["c1"]

    # skill_b: case_2 only (evaluator attributed)
    assert "skill_b" in result
    skill_b_cases = [f[1].case.case_id for f in result["skill_b"].failures]
    assert skill_b_cases == ["c2"]

    # skill_c: not in result (no cases attributed)
    assert "skill_c" not in result


@pytest.mark.asyncio
async def test_attribute_success_batch_attribution(edp_cls: type) -> None:
    """success_batch 也使用语义归因。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_with_attributions(
        "c1",
        0.9,
        [
            {
                "skill_name": "skill_a",
                "reason": "a did well",
                "usage_status": "executed",
                "impact": "negative",
            }
        ],
    )
    traj = _make_trajectory_dict("c1", ["skill_a", "skill_b"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[],
        success_batch=[(traj, eval_case, case)],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # 成功 case 也应只归因到 skill_a
    assert "skill_a" in result
    assert len(result["skill_a"].successes) == 1
    # skill_b 无归因 → 不出现在结果中
    assert "skill_b" not in result


# ── ADR-0009: goodcase 全量兜底 + 单 skill badcase 过滤 ──


def _make_eval_case_flat_attributed(case_id: str, score: float, attributed_skill: str | None):
    """Helper: 构造 flat 格式 attributed_skill 的 EvaluatedCase（生产主格式）。"""
    import json

    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(case_id=case_id, inputs={"q": "x"}, label={"expected": "ok"})
    reason = json.dumps({"attributed_skill": attributed_skill or "", "is_pass": score >= 0.75})
    return EvaluatedCase(case=case, answer={"answer": "mock"}, score=score, reason=reason)


@pytest.mark.asyncio
async def test_multi_skill_goodcase_fanout_to_all(edp_cls: type) -> None:
    """AC6（修订 WAVE_9 AC4）：多 skill + goodcase 无归因 → 分给所有 operator 的 success batch。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    # goodcase 不归因（评估 prompt 设计如此：is_pass=true → attributed_skill 留空）
    eval_case = _make_eval_case_flat_attributed("c1", score=0.9, attributed_skill="")
    traj = _make_trajectory_dict("c1", ["skill_a", "skill_b"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[],
        success_batch=[(traj, eval_case, case)],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # 全量兜底：两个 operator 都收到该 goodcase
    assert "skill_a" in result
    assert "skill_b" in result
    assert len(result["skill_a"].successes) == 1
    assert len(result["skill_b"].successes) == 1
    assert len(result["skill_a"].failures) == 0
    assert len(result["skill_b"].failures) == 0


@pytest.mark.asyncio
async def test_multi_skill_goodcase_with_attribution_precise(edp_cls: type) -> None:
    """goodcase 若带了归因（legacy/future）→ 精准分到匹配 operator，不兜底。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_flat_attributed("c1", score=0.9, attributed_skill="skill_a")
    traj = _make_trajectory_dict("c1", ["skill_a", "skill_b"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[],
        success_batch=[(traj, eval_case, case)],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # 精准分：只进 skill_a
    assert "skill_a" in result
    assert len(result["skill_a"].successes) == 1
    assert "skill_b" not in result


@pytest.mark.asyncio
async def test_multi_skill_goodcase_mixed_attribution_and_fanout(edp_cls: type) -> None:
    """混合：一条 goodcase 带归因（精准）+ 一条无归因（全量兜底）。"""
    from openjiuwen.agent_evolving.dataset import Case

    c1 = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    ec1 = _make_eval_case_flat_attributed("c1", 0.9, "skill_a")  # 精准
    t1 = _make_trajectory_dict("c1", ["skill_a"])

    c2 = Case(case_id="c2", inputs={"q": "x"}, label={"expected": "ok"})
    ec2 = _make_eval_case_flat_attributed("c2", 0.95, "")  # 兜底
    t2 = _make_trajectory_dict("c2", ["skill_a", "skill_b"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[],
        success_batch=[(t1, ec1, c1), (t2, ec2, c2)],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    # skill_a 收到 c1（精准）+ c2（兜底）
    a_ids = [s[1].case.case_id for s in result["skill_a"].successes]
    assert a_ids == ["c1", "c2"]
    # skill_b 只收到 c2（兜底）
    b_ids = [s[1].case.case_id for s in result["skill_b"].successes]
    assert b_ids == ["c2"]


@pytest.mark.asyncio
async def test_multi_skill_badcase_flat_no_attribution_dropped(edp_cls: type) -> None:
    """AC7：多 skill + badcase flat 格式无归因 → 丢，不 fallback，不兜底。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_flat_attributed("c1", score=0.3, attributed_skill="")
    traj = _make_trajectory_dict("c1", ["skill_a"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"skill_a": "doc a", "skill_b": "doc b"},
    )

    assert result == {}


# ── F1 (EDP): 单 skill badcase 过滤 ──


@pytest.mark.asyncio
async def test_attribute_single_skill_badcase_attributed_kept(edp_cls: type) -> None:
    """AC1（EDP 单 skill）：badcase 归因到该 skill → 进 failure 反思。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_flat_attributed("c1", 0.3, "only_skill")
    traj = _make_trajectory_dict("c1", ["only_skill"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"only_skill": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"only_skill": "doc"},
    )

    assert "only_skill" in result
    assert len(result["only_skill"].failures) == 1


@pytest.mark.asyncio
async def test_attribute_single_skill_badcase_no_attribution_dropped(edp_cls: type) -> None:
    """AC2（EDP 单 skill）：badcase 无归因 → 丢，不进反思。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_flat_attributed("c1", 0.3, "")
    traj = _make_trajectory_dict("c1", ["only_skill"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"only_skill": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"only_skill": "doc"},
    )

    assert result == {}


@pytest.mark.asyncio
async def test_attribute_single_skill_goodcase_all_kept(edp_cls: type) -> None:
    """AC3（EDP 单 skill）：goodcase → 全量进 success 反思（不变）。"""
    from openjiuwen.agent_evolving.dataset import Case

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _make_eval_case_flat_attributed("c1", 0.9, "")
    traj = _make_trajectory_dict("c1", ["only_skill"])

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._operators = {"only_skill": MagicMock()}

    result = await optimizer._attribute(
        failure_batch=[],
        success_batch=[(traj, eval_case, case)],
        skill_contents={"only_skill": "doc"},
    )

    assert "only_skill" in result
    assert len(result["only_skill"].successes) == 1


# ── _build_analyst_prompt() ──


def test_build_analyst_prompt_error(edp_cls: type) -> None:
    """analyst_error 模板：包含 Failed Trajectories 段。"""
    from unittest.mock import MagicMock, patch

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._scheduler = MagicMock()
    optimizer._scheduler.max_lr = 5

    with patch.object(
        sys.modules[edp_cls.__module__], "load_prompt", return_value="SYSTEM_PROMPT"
    ) as mock_lp:
        result = optimizer._build_analyst_prompt(
            "analyst_error",
            "SKILL_CONTENT",
            "TRAJ_TEXT",
            "",
            "",
        )

    mock_lp.assert_called_once_with("analyst_error", "edp_agent")
    assert "SYSTEM_PROMPT" in result
    assert "## Current Skill\nSKILL_CONTENT" in result
    assert "## Edits Budget" in result
    assert "## Failed Trajectories\nTRAJ_TEXT" in result
    assert "## Successful Trajectories" not in result


def test_build_analyst_prompt_success(edp_cls: type) -> None:
    """analyst_success 模板：包含 Successful Trajectories 段。"""
    from unittest.mock import MagicMock, patch

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._scheduler = MagicMock()
    optimizer._scheduler.max_lr = 3

    with patch.object(
        sys.modules[edp_cls.__module__], "load_prompt", return_value="SUCCESS_SYS"
    ) as mock_lp:
        result = optimizer._build_analyst_prompt(
            "analyst_success",
            "SKILL_DOC",
            "SUCCESS_TRAJ",
            "",
            "",
        )

    mock_lp.assert_called_once_with("analyst_success", "edp_agent")
    assert "SUCCESS_SYS" in result
    assert "## Successful Trajectories\nSUCCESS_TRAJ" in result
    assert "## Failed Trajectories" not in result


def test_build_analyst_prompt_with_context(edp_cls: type) -> None:
    """step_buffer_context 和 meta_skill_context 非空时包含对应段。"""
    from unittest.mock import MagicMock, patch

    optimizer = edp_cls.__new__(edp_cls)
    optimizer._scheduler = MagicMock()
    optimizer._scheduler.max_lr = 5

    with patch.object(sys.modules[edp_cls.__module__], "load_prompt", return_value="SYS"):
        result = optimizer._build_analyst_prompt(
            "analyst_error",
            "SKILL",
            "TRAJ",
            "PREV_STEP_CTX",
            "META_CTX",
        )

    assert "## Previous Steps in This Epoch\nPREV_STEP_CTX" in result
    assert "## Optimizer Memory\nMETA_CTX" in result


def test_build_analyst_prompt_scenario_name(edp_cls: type) -> None:
    """_SCENARIO_NAME 类属性为 edp_agent。"""
    assert edp_cls._SCENARIO_NAME == "edp_agent"


# ── W10.7: phase_callback + _push_phase ──


def test_optimizer_accepts_phase_callback() -> None:
    """EDPAgentOptimizer.__init__() 接受 phase_callback 参数。"""
    from evo_agent.scenario.registry import ScenarioRegistry
    from evo_agent.types import OptimizeRequest

    registry = ScenarioRegistry()
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
    )
    events: list[tuple[str, dict]] = []
    optimizer = registry.build_optimizer(
        request,
        dependencies={
            "agent": MagicMock(),
            "evaluator": MagicMock(),
            "llm": MagicMock(),
            "model": "test",
            "train_cases": MagicMock(),
            "phase_callback": lambda e, d: events.append((e, d)),
        },
    )
    optimizer._push_phase("log", {"message": "hi"})
    assert events == [("log", {"message": "hi"})]


def test_push_phase_swallows_exception() -> None:
    """_push_phase 吞掉 callback 异常，不中断优化流程。"""
    from evo_agent.scenario.registry import ScenarioRegistry
    from evo_agent.types import OptimizeRequest

    def boom(e: str, d: dict) -> None:
        raise RuntimeError("x")

    registry = ScenarioRegistry()
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
    )
    optimizer = registry.build_optimizer(
        request,
        dependencies={
            "agent": MagicMock(),
            "evaluator": MagicMock(),
            "llm": MagicMock(),
            "model": "test",
            "train_cases": MagicMock(),
            "phase_callback": boom,
        },
    )
    # Should not raise
    optimizer._push_phase("log", {})


def test_push_phase_noop_when_none() -> None:
    """phase_callback=None 时 _push_phase 为 no-op。"""
    from evo_agent.scenario.registry import ScenarioRegistry
    from evo_agent.types import OptimizeRequest

    registry = ScenarioRegistry()
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
    )
    optimizer = registry.build_optimizer(
        request,
        dependencies={
            "agent": MagicMock(),
            "evaluator": MagicMock(),
            "llm": MagicMock(),
            "model": "test",
            "train_cases": MagicMock(),
        },
    )
    # Should not raise
    optimizer._push_phase("log", {"message": "test"})


# ── W10.8: phase events from overrides ──


def _build_optimizer_with_phase_cb(
    phase_callback: Any,
) -> Any:
    """Build real EDPAgentOptimizer with phase_callback via ScenarioRegistry."""
    from evo_agent.scenario.registry import ScenarioRegistry
    from evo_agent.types import OptimizeRequest

    registry = ScenarioRegistry()
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
    )
    return registry.build_optimizer(
        request,
        dependencies={
            "agent": MagicMock(),
            "evaluator": MagicMock(),
            "llm": MagicMock(),
            "model": "test",
            "train_cases": MagicMock(),
            "phase_callback": phase_callback,
        },
    )


@pytest.mark.asyncio
async def test_rollout_pushes_rollout_and_evaluate_events() -> None:
    """_rollout pushes rollout, evaluate, and rollout_done phase events."""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    events: list[tuple[str, dict]] = []
    optimizer = _build_optimizer_with_phase_cb(lambda e, d: events.append((e, d)))

    # Mock internal dependencies
    case = Case(case_id="c1", inputs={"query": "hello"}, label={"expected": "ok"})
    eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)

    optimizer._evaluator = MagicMock()
    # C1: _rollout Phase 2 走 batch_evaluate（并发），不再逐 case evaluate
    optimizer._evaluator.batch_evaluate = MagicMock(return_value=[eval_result])
    optimizer._adapter_client = AsyncMock()
    optimizer._adapter_client.get_traces = AsyncMock(
        return_value={"messages": [{"role": "user", "content": "hi"}]}
    )
    optimizer._agent = AsyncMock()
    optimizer._agent.invoke = AsyncMock(return_value={"answer": "mock"})
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0
    optimizer._rollout_extra_data = {}
    optimizer._artifact_epoch = 1
    optimizer._num_parallel = 1

    await optimizer._rollout([case])

    phases = [d.get("phase") for _, d in events if _ == "log"]
    assert "rollout" in phases
    assert "evaluate" in phases
    assert "rollout_done" in phases


@pytest.mark.asyncio
async def test_rollout_phase2_uses_batch_evaluate_with_num_parallel() -> None:
    """C1: _rollout Phase 2 用 batch_evaluate(num_parallel=...) 并发评估。"""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    optimizer = _build_optimizer_with_phase_cb(lambda e, d: None)
    case = Case(case_id="c1", inputs={"query": "hello"}, label={"expected": "ok"})
    eval_result = EvaluatedCase(case=case, answer={"answer": "mock"}, score=0.8)

    captured: dict[str, Any] = {}

    def _batch_eval(cases: Any, predicts: Any, **kwargs: Any) -> list[EvaluatedCase]:
        captured["num_parallel"] = kwargs.get("num_parallel")
        captured["n_cases"] = len(cases)
        return [eval_result]

    optimizer._evaluator = MagicMock()
    optimizer._evaluator.batch_evaluate = _batch_eval
    optimizer._adapter_client = AsyncMock()
    optimizer._adapter_client.get_traces = AsyncMock(
        return_value={"messages": [{"role": "user", "content": "hi"}]}
    )
    optimizer._agent = AsyncMock()
    optimizer._agent.invoke = AsyncMock(return_value={"answer": "mock"})
    optimizer._conversation_id_factory = None
    optimizer._trace_max_retries = 1
    optimizer._trace_retry_backoff = 0.0
    optimizer._rollout_extra_data = {}
    optimizer._artifact_epoch = 1
    optimizer._num_parallel = 3

    evaluated, trajectories = await optimizer._rollout([case])

    assert captured["num_parallel"] == 3
    assert captured["n_cases"] == 1
    assert len(evaluated) == 1
    assert evaluated[0].score == 0.8
    # 轨迹与评估结果按 case_id 1:1 配对
    assert len(trajectories) == 1
    assert trajectories[0]["case_id"] == "c1"


@pytest.mark.asyncio
async def test_attribute_pushes_attribute_event() -> None:
    """_attribute pushes attribute phase event."""
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    events: list[tuple[str, dict]] = []
    optimizer = _build_optimizer_with_phase_cb(lambda e, d: events.append((e, d)))
    optimizer._operators = {"skill_a": MagicMock()}
    optimizer._artifact_epoch = 1

    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.3)
    traj = {"case_id": "c1", "messages": []}

    await optimizer._attribute(
        failure_batch=[(traj, eval_case, case)],
        success_batch=[],
        skill_contents={"skill_a": "doc text"},
    )

    phases = [d.get("phase") for _, d in events if _ == "log"]
    assert "attribute" in phases


@pytest.mark.asyncio
async def test_on_step_apply_pushes_apply_event() -> None:
    """_on_step_apply pushes a per-step apply phase event.

    The apply event is now emitted per-step from the base ``_backward`` step
    loop via this hook (not once at epoch end from ``EDPAgentOptimizer._backward``),
    so each step's edits are reported independently.
    """
    events: list[tuple[str, dict]] = []
    optimizer = _build_optimizer_with_phase_cb(lambda e, d: events.append((e, d)))
    optimizer._artifact_epoch = 1

    optimizer._on_step_apply(step=0, n_edits=3, n_operators=2)

    assert len(events) == 1
    event, data = events[0]
    assert event == "log"
    assert data["phase"] == "apply"
    # Bug 3: _push_phase 把 0-based _artifact_epoch (=1) 转 1-based (=2)，
    # 对齐 ProgressCallback 的 progress.current_epoch。
    assert data["epoch"] == 2
    assert data["data"] == {"n_operators": 2, "n_edits": 3, "step": 0}
    assert data["message"] == "编辑已应用：3 个编辑写入 2 个 skill"


@pytest.mark.asyncio
async def test_backward_apply_event_per_step_multistep() -> None:
    """Regression: multi-step epoch reports each step's applied edits.

    Previously the apply event was emitted once at epoch end reading
    ``_ranked_patch_by_operator``, which is overwritten each step — so it
    only retained the LAST step's edits. When the last step produced 0
    edits (common after earlier steps fixed the failures), the reported
    ``edits_applied`` was 0 even though edits were selected+applied earlier.

    Now ``_on_step_apply`` fires once per step with that step's edits, so
    accumulating across steps yields the correct epoch total.
    """
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    from evo_agent.optimizer.skill_document.skill_document_optimizer import (
        SkillDocumentOptimizer,
    )
    from evo_agent.optimizer.skill_document.types import (
        AttributedBatch,
        Edit,
        Patch,
        RawPatch,
    )

    apply_calls: list[tuple[int, int, int]] = []
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)

    # Per-operator state
    op = MagicMock()
    op.get_state.return_value = {"skill_content": "BASE"}
    opt._operators = {"skill_a": op}

    # Step config: 2 steps, 1 accumulation round each
    opt._steps_per_epoch = 2
    opt._accumulation = 1
    opt._batch_size = 1
    opt._global_step = 0
    opt._artifact_epoch = -1  # becomes 0 after _backward increments
    opt._score_threshold = 0.5
    opt._scheduler = MagicMock()
    opt._scheduler.step.return_value = 5
    opt._artifact_exporter = MagicMock()
    opt._step_buffer = []
    opt._curr_epoch_comparison = []
    opt._parameters = {}
    # Cross-step state dicts (reset at top of _backward, but init for safety)
    opt._current_skill_by_operator = {}
    opt._last_candidate_skill_by_operator = {}
    opt._ranked_patch_by_operator = {}
    opt._epoch_base_skill_by_operator = {}
    opt._current_skill_content = ""
    opt._epoch_base_skill_content = ""
    opt._last_candidate_skill_content = ""
    opt._ranked_patch = None
    opt._prev_epoch_skill = ""
    opt._prev_epoch_skill_by_operator = {}
    opt._prev_epoch_comparison = []
    opt._meta_skill_context = ""

    # Record per-step apply hook calls
    opt._on_step_apply = lambda step, n_edits, n_operators: apply_calls.append(
        (step, n_edits, n_operators)
    )

    # Case + eval (score below threshold → failure batch, but _attribute is mocked)
    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.0)
    traj = MagicMock()
    opt._sample_cases = lambda n, seed=0: [case]
    opt._rollout = AsyncMock(return_value=([eval_case], [traj]))
    opt._attribute = AsyncMock(
        return_value={
            "skill_a": AttributedBatch(operator_id="skill_a", failures=[], successes=[]),
        }
    )

    # Reflect: always returns one RawPatch tagged with the operator (edits come
    # from the _aggregate override below).
    raw_patch = RawPatch(
        patch=Patch(edits=[], reasoning="r"),
        source_type="failure",
        operator_id="skill_a",
    )
    opt._reflect = AsyncMock(return_value=[raw_patch])

    # Aggregate: step 0 → 2 edits, step 1 → 0 edits (the bug scenario).
    edit_a = Edit(op="append", content="A", target="", support_count=1)
    edit_b = Edit(op="append", content="B", target="", support_count=1)
    opt._aggregate = AsyncMock(
        side_effect=[Patch(edits=[edit_a, edit_b], reasoning="r"), Patch(edits=[], reasoning="r")]
    )
    # Select: pass-through (within budget).
    opt._select = AsyncMock(side_effect=lambda *, edits, budget, skill_content: edits)

    with patch(
        "evo_agent.optimizer.skill_document.skill_document_optimizer.apply_patch_with_report",
        return_value=("UPDATED_SKILL", MagicMock()),
    ):
        await opt._backward([])

    # Per-step apply hook fired once per step with THAT step's edit count.
    assert apply_calls == [(0, 2, 1), (1, 0, 1)]
    # Accumulating across steps gives the correct epoch total (2), not 0.
    assert sum(n for _, n, _ in apply_calls) == 2


@pytest.mark.asyncio
async def test_reflect_override_pushes_reflect_event() -> None:
    """_reflect override pushes reflect phase event."""
    events: list[tuple[str, dict]] = []
    optimizer = _build_optimizer_with_phase_cb(lambda e, d: events.append((e, d)))
    optimizer._artifact_epoch = 1

    with patch(
        "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._reflect",
        new_callable=AsyncMock,
        return_value=[MagicMock(), MagicMock()],
    ):
        await optimizer._reflect(
            formatted_batch="batch",
            skill_content="doc",
            score_threshold=0.5,
            operator_id="skill_a",
        )

    phases = [d.get("phase") for _, d in events if _ == "log"]
    assert "reflect" in phases


@pytest.mark.asyncio
async def test_aggregate_override_pushes_aggregate_event() -> None:
    """_aggregate override pushes aggregate phase event."""
    events: list[tuple[str, dict]] = []
    optimizer = _build_optimizer_with_phase_cb(lambda e, d: events.append((e, d)))
    optimizer._artifact_epoch = 1

    with patch(
        "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._aggregate",
        new_callable=AsyncMock,
        return_value=MagicMock(),
    ):
        await optimizer._aggregate(patches=[MagicMock(), MagicMock()], skill_content="doc")

    phases = [d.get("phase") for _, d in events if _ == "log"]
    assert "aggregate" in phases


@pytest.mark.asyncio
async def test_select_override_pushes_select_event() -> None:
    """_select override pushes select phase event."""
    events: list[tuple[str, dict]] = []
    optimizer = _build_optimizer_with_phase_cb(lambda e, d: events.append((e, d)))
    optimizer._artifact_epoch = 1

    with patch(
        "evo_agent.optimizer.skill_document.skill_document_optimizer.SkillDocumentOptimizer._select",
        new_callable=AsyncMock,
        return_value=[MagicMock()],
    ):
        await optimizer._select(edits=[MagicMock(), MagicMock()], budget=5, skill_content="doc")

    phases = [d.get("phase") for _, d in events if _ == "log"]
    assert "select" in phases
