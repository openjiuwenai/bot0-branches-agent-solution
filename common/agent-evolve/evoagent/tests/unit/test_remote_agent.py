"""RemoteAgent 单元测试 — BaseAgent 子类封装远程调用。"""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from evo_agent.adapter_client.client import AdapterClient
from evo_agent.adapter_client.remote_agent import RemoteAgent


def _make_remote_agent(
    *,
    max_turns: int = 10,
    invoke_side_effect: list[dict[str, Any]] | None = None,
) -> tuple[RemoteAgent, MagicMock]:
    """创建使用 MagicMock AdapterClient 的 RemoteAgent。"""
    adapter_client = MagicMock(spec=AdapterClient)
    adapter_client.invoke = AsyncMock(side_effect=invoke_side_effect or [])

    agent = RemoteAgent(
        card=MagicMock(),
        adapter_client=adapter_client,
        operators={"skill_a": MagicMock()},
        max_turns=max_turns,
    )
    return agent, adapter_client


def _ok_response(answer: str = "ok") -> dict[str, Any]:
    """构造一个 success=true 的 invoke 响应。"""
    return {
        "success": True,
        "conversation_id": "case-001",
        "answer": answer,
        "interrupted": False,
        "events": [],
        "error": None,
    }


# ── invoke ──


class TestInvoke:
    @pytest.mark.asyncio
    async def test_invoke_single_query(self) -> None:
        """单条 query → 一轮即返回 answer。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("hello")])

        result = await agent.invoke(
            inputs={
                "query": "test",
                "queries": ["test"],
                "case_id": "case-001",
                "run_id": "run-1",
            }
        )

        assert result["answer"] == "hello"
        mock_client.invoke.assert_called_once()

    @pytest.mark.asyncio
    async def test_invoke_multi_turn(self) -> None:
        """多条 queries → 遍历全部 query。"""
        agent, mock_client = _make_remote_agent(
            invoke_side_effect=[
                _ok_response("turn1"),
                _ok_response("turn2"),
                _ok_response("final"),
            ]
        )

        result = await agent.invoke(
            inputs={
                "query": "q1",
                "queries": ["q1", "q2", "q3"],
                "case_id": "case-001",
                "run_id": "run-1",
            }
        )

        # 返回最后一轮的响应
        assert result["answer"] == "final"
        assert mock_client.invoke.call_count == 3

    @pytest.mark.asyncio
    async def test_invoke_max_turns(self) -> None:
        """达到 max_turns 时停止，不继续消费 queries。"""
        agent, mock_client = _make_remote_agent(
            max_turns=2,
            invoke_side_effect=[
                _ok_response("turn1"),
                _ok_response("turn2"),
            ],
        )

        result = await agent.invoke(
            inputs={
                "query": "q1",
                "queries": ["q1", "q2", "q3", "q4"],
                "case_id": "case-001",
                "run_id": "run-1",
            }
        )

        assert result["answer"] == "turn2"
        assert mock_client.invoke.call_count == 2

    @pytest.mark.asyncio
    async def test_invoke_passes_extra_data(self) -> None:
        """extra_data 透传给 AdapterClient.invoke。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("ok")])

        await agent.invoke(
            inputs={
                "query": "q1",
                "queries": ["q1"],
                "case_id": "case-001",
                "run_id": "run-1",
                "extra_data": {"role_id": "1"},
            }
        )

        call_kwargs = mock_client.invoke.call_args.kwargs
        assert call_kwargs["extra_data"] == {"role_id": "1"}
        assert call_kwargs["run_id"] == "run-1"

    @pytest.mark.asyncio
    async def test_invoke_interrupted(self) -> None:
        """interrupted 响应正常返回，不中断多轮循环。"""
        agent, mock_client = _make_remote_agent(
            invoke_side_effect=[
                {
                    "success": True,
                    "conversation_id": "case-001",
                    "answer": "被中断了",
                    "interrupted": True,
                    "interrupt_intent": "transfer",
                    "interrupt_description": "转人工",
                    "events": [],
                    "error": None,
                },
                _ok_response("final"),
            ]
        )

        result = await agent.invoke(
            inputs={
                "query": "q1",
                "queries": ["q1", "q2"],
                "case_id": "case-001",
                "run_id": "run-1",
            }
        )

        # 继续执行后续 query，返回最终结果
        assert result["answer"] == "final"
        assert mock_client.invoke.call_count == 2

    @pytest.mark.asyncio
    async def test_invoke_empty_queries_raises(self) -> None:
        """queries 为空且 query 也缺失时抛出 ValueError。"""
        agent, _ = _make_remote_agent()

        with pytest.raises(ValueError, match="queries"):
            await agent.invoke(
                inputs={
                    "queries": [],
                    "case_id": "case-001",
                    "run_id": "run-1",
                }
            )

    @pytest.mark.asyncio
    async def test_invoke_queries_with_empty_string_raises(self) -> None:
        """queries 中全部为空字符串时抛出 ValueError。"""
        agent, _ = _make_remote_agent()

        with pytest.raises(ValueError, match="queries"):
            await agent.invoke(
                inputs={
                    "queries": ["", ""],
                    "case_id": "case-001",
                    "run_id": "run-1",
                }
            )

    @pytest.mark.asyncio
    async def test_invoke_filters_empty_queries(self) -> None:
        """queries 中的空字符串被过滤，非空 query 正常执行。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("ok")])

        result = await agent.invoke(
            inputs={
                "queries": ["", "valid query", ""],
                "case_id": "case-001",
                "run_id": "run-1",
            }
        )

        assert result["answer"] == "ok"
        assert mock_client.invoke.call_count == 1
        call_kwargs = mock_client.invoke.call_args.kwargs
        assert call_kwargs["query"] == "valid query"

    @pytest.mark.asyncio
    async def test_invoke_falls_back_to_query(self) -> None:
        """queries 缺失时回退到 query 字段。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("ok")])

        result = await agent.invoke(
            inputs={
                "query": "single query",
                "case_id": "case-001",
                "run_id": "run-1",
            }
        )

        assert result["answer"] == "ok"
        mock_client.invoke.assert_called_once()


# ── Trainer-compatible invoke (P1 fix) ──


class TestInvokeTrainerCompat:
    """验证 RemoteAgent.invoke() 兼容 Trainer/SkillDocumentOptimizer 的标准格式。

    Trainer.predict() 和 SkillDocumentOptimizer._rollout() 调用：
        agent.invoke({**case.inputs, "conversation_id": case.case_id}, session=session)

    其中 case.inputs 至少包含 {"query": "..."}，不含 case_id / run_id / queries。
    """

    @pytest.mark.asyncio
    async def test_invoke_trainer_format_conversation_id(self) -> None:
        """Trainer 格式：conversation_id + query → 正常调用 adapter。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("hello")])

        result = await agent.invoke(inputs={"query": "推荐一款手机", "conversation_id": "case-042"})

        assert result["answer"] == "hello"
        mock_client.invoke.assert_called_once()
        call_kwargs = mock_client.invoke.call_args.kwargs
        assert call_kwargs["case_id"] == "case-042"
        assert call_kwargs["query"] == "推荐一款手机"

    @pytest.mark.asyncio
    async def test_invoke_trainer_format_generates_run_id(self) -> None:
        """Trainer 格式不含 run_id，自动生成非空 run_id。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("ok")])

        await agent.invoke(inputs={"query": "test", "conversation_id": "case-001"})

        call_kwargs = mock_client.invoke.call_args.kwargs
        assert call_kwargs["run_id"]  # non-empty auto-generated UUID
        assert len(call_kwargs["run_id"]) >= 8  # UUID is 36 chars

    @pytest.mark.asyncio
    async def test_invoke_trainer_format_with_session(self) -> None:
        """Trainer 传入 session 参数，不报错。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("ok")])

        result = await agent.invoke(
            inputs={"query": "test", "conversation_id": "case-001"},
            session="some_session_object",
        )

        assert result["answer"] == "ok"

    @pytest.mark.asyncio
    async def test_invoke_case_id_alias_still_works(self) -> None:
        """旧格式 case_id 仍然有效（向后兼容）。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("ok")])

        result = await agent.invoke(
            inputs={
                "query": "test",
                "case_id": "legacy-case",
                "run_id": "run-1",
            }
        )

        assert result["answer"] == "ok"
        call_kwargs = mock_client.invoke.call_args.kwargs
        assert call_kwargs["case_id"] == "legacy-case"
        assert call_kwargs["run_id"] == "run-1"

    @pytest.mark.asyncio
    async def test_invoke_conversation_id_takes_precedence(self) -> None:
        """conversation_id 和 case_id 同时存在时，conversation_id 优先。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("ok")])

        await agent.invoke(
            inputs={
                "query": "test",
                "conversation_id": "trainer-id",
                "case_id": "legacy-id",
                "run_id": "run-1",
            }
        )

        call_kwargs = mock_client.invoke.call_args.kwargs
        assert call_kwargs["case_id"] == "trainer-id"

    @pytest.mark.asyncio
    async def test_invoke_trainer_format_extra_data_in_inputs(self) -> None:
        """Trainer case.inputs 中的额外字段不会干扰 query 和 conversation_id。"""
        agent, mock_client = _make_remote_agent(invoke_side_effect=[_ok_response("ok")])

        await agent.invoke(
            inputs={
                "query": "推荐手机",
                "conversation_id": "case-099",
                "user_id": "u-123",
                "role": "buyer",
            }
        )

        call_kwargs = mock_client.invoke.call_args.kwargs
        assert call_kwargs["case_id"] == "case-099"
        assert call_kwargs["query"] == "推荐手机"


# ── get_operators ──


class TestGetOperators:
    def test_get_operators(self) -> None:
        """返回构造时的 operators。"""
        ops = {"skill_a": MagicMock()}
        agent = RemoteAgent(
            card=MagicMock(),
            adapter_client=MagicMock(),
            operators=ops,
        )
        assert agent.get_operators() is ops


# ── adapter_client property ──


class TestAdapterClientProperty:
    def test_adapter_client_property(self) -> None:
        """adapter_client 属性可访问。"""
        mock_client = MagicMock()
        agent = RemoteAgent(
            card=MagicMock(),
            adapter_client=mock_client,
            operators={},
        )
        assert agent.adapter_client is mock_client


# ── stream / configure ──


class TestStreamConfigure:
    @pytest.mark.asyncio
    async def test_stream_returns_empty(self) -> None:
        """stream() 返回空 async iterator。"""
        agent, _ = _make_remote_agent()
        result: list[Any] = []
        async for item in agent.stream(inputs={}):
            result.append(item)
        assert result == []

    def test_configure_no_error(self) -> None:
        """configure() 不抛异常。"""
        agent, _ = _make_remote_agent()
        agent.configure(config=None)  # 不应抛异常


# ── isinstance ──


class TestIsBaseAgent:
    def test_is_base_agent(self) -> None:
        """isinstance(agent, BaseAgent) 为 True。"""
        from openjiuwen.core.single_agent.base import BaseAgent

        agent, _ = _make_remote_agent()
        assert isinstance(agent, BaseAgent)
