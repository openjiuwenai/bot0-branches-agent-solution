# coding: utf-8

"""Slice 2 接线冒烟：ServeOrchestrator 编排语义。

注意：这是**接线冒烟**（验证管线连通），非对外考卷——对外考卷是存量集成测试
（capture_replay/replay_buy_wealth 等），待整条纵切装配到位后盲判。此处断言全部
来自 L2 设计的编排契约（stream 中继 / query drain / 协作式取消停止消费），非反推
自任何存量 test 断言。用 asyncio.run 驱动，不引入 async 测试插件依赖。
"""
from __future__ import annotations

import asyncio

from agent_runtime.application.serve import ServeOrchestrator
from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse
from agent_runtime.tests.doubles import ConformingHandler


class _StubHandler(ConformingHandler):
    """AgentHandler 的测试替身：产出预置结果序列，不接真实框架。"""

    agent_id = "stub"
    priority = 0

    def __init__(self, results):
        # **必须调父类的**：`ConformingHandler.__init__` 建的是调用痕迹表，
        # 不调则该表缺失，「不该被调的没被调」这类断言会取到属性不存在而非空列表。
        super().__init__()
        self._results = list(results)
        self.cancelled = False
        self.started = False

    def is_healthy(self):
        return True

    async def stream_query(self, ctx):
        for r in self._results:
            yield r

    async def start(self):
        self.started = True

    async def stop(self):
        pass


def _out(text):
    return QueryChunk.of_event("thought", content=text)


def test_stream_query_relays_handler_results_in_order():
    async def _run():
        results = [_out("a"), _out("b"), QueryChunk.of_final_answer()]
        orch = ServeOrchestrator(_StubHandler(results))
        got = [r async for r in orch.stream_query(ServeRequest.of_text(""))]
        return got, results

    got, results = asyncio.run(_run())
    assert got == results


def test_query_returns_an_aggregated_query_response():
    """非流式出口产 `QueryResponse{result, conversation_id}`，不是一串结果块。

    期望值来源：上游 SPI `openJiuwen/agent-runtime-java/service/agent-service-spec/src/main/java/
    com/openjiuwen/service/spec/spi/ServeOrchestrator.java:22` 的
    `QueryResponse query(ServeRequest request)`；我方根设计
    `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md:152`
    把 `QueryResponse` 登记为 active、
    语义「非流式 query 聚合响应」，`:172` 把本类契约登记为
    query/stream_query/cancel_active/reset_conversation。**不由被测代码推出。**

    **本条推翻了上一版判据**：它断言 `orch.query(...) == results`，即锁死「返回完整结果
    序列」这个行为——而那正是与上游 SPI 和我方根设计对立的那一处。判据锁住错误行为时，
    改对反而会转红；这里是改对之后把判据一并订正。

    **这条判据能失败**：把返回值改回结果序列立刻转红。
    """
    async def _run():
        results = [_out("a"), QueryChunk.of_final_answer("最终答案")]
        orch = ServeOrchestrator(_StubHandler(results))
        return await orch.query(ServeRequest.of_text("问", conversation_id="conv-1"))

    response = asyncio.run(_run())

    assert isinstance(response, QueryResponse)
    assert response.result == "最终答案"
    assert response.conversation_id == "conv-1"


def test_query_aggregates_by_the_same_rule_the_handlers_use():
    """聚合规则与三个处理器实现一致：取**终答内容块**的内容，非终答块不进 `result`。

    期望值来源：我方三处处理器实现逐字一致的既有规则
    （`agent_runtime/adapters/outbound/agentcore/handler.py`、
    `.../versatile/handler.py`、`.../hostagent/dict_event_handler.py`
    都是「drain 自身流、取 `is_final_answer` 的内容」）。**不是在编排器里新发明的规则。**

    **这条判据能失败**：改成拼接全部块的内容、或取最后一块立刻转红。
    """
    async def _run():
        results = [_out("中间输出"), QueryChunk.of_final_answer("终答"), _out("终答之后的杂音")]
        orch = ServeOrchestrator(_StubHandler(results))
        return await orch.query(ServeRequest.of_text("问", conversation_id="conv-2"))

    response = asyncio.run(_run())

    assert response.result == "终答", "聚合规则偏离了三个处理器实现的既有规则"


def test_cancel_active_stops_consumption():
    """取消后停止消费本流。键 = 会话标识（详设 §4.3.1）。

    权威 `FEAT-002:38` MUST（协作式取消）：Handler **无独立 `cancel()` 入口**，取消通过
    观察者的取消位轮询实现。本判据核对置位后在途流即刻停止被消费——协作式取消能成立的
    唯一证据就是消费侧真的停了，而不是靠某个强制中断的入口把它掐掉。
    """
    async def _run():
        handler = _StubHandler([_out("a"), _out("b"), _out("c")])
        orch = ServeOrchestrator(handler)
        ctx = ServeRequest.of_text("", conversation_id="t1")
        gen = orch.stream_query(ctx)
        first = await gen.__anext__()
        n = await orch.cancel_active("t1")
        rest = [r async for r in gen]
        return first, rest, n
    first, rest, n = asyncio.run(_run())
    assert first == _out("a")
    assert rest == []
    assert n == 1  # 恰一条在途流被置位


def test_cancel_notifies_handler_implementing_interrupt_contract():
    """处理器实现中断通知契约时，取消须通知它——权威要求「尽力通知底层」。

    **这条判据能失败**：把编排器里的通知调用删掉立刻转红。

    权威 `FEAT-002:141`（错误场景表·cancel requested）：必须停止 runtime 对该执行流的
    继续消费，**并尽力通知底层框架或远端请求**。前半句由同组另一条判据锁住，
    本判据锁后半句——处理器实现了中断通知契约时，那次通知真的发生了。
    """
    from agent_runtime.ports.interrupt import InterruptReason

    class _NotifiableHandler(_StubHandler):
        def __init__(self, results):
            super().__init__(results)
            self.notified: list = []

        async def on_interrupt(self, conversation_id, reason):
            self.notified.append((conversation_id, reason))

    async def _run():
        handler = _NotifiableHandler([_out("a"), _out("b")])
        orch = ServeOrchestrator(handler)
        ctx = ServeRequest.of_text("", conversation_id="c9")
        gen = orch.stream_query(ctx)
        await gen.__anext__()
        await orch.cancel_active("c9")
        [_ async for _ in gen]
        return handler.notified
    notified = asyncio.run(_run())
    assert notified == [("c9", InterruptReason.USER_REQUEST)]


def test_cancel_does_not_fail_when_handler_lacks_contract():
    """未实现该契约的处理器被跳过，取消照常完成——契约是可选的。"""
    async def _run():
        orch = ServeOrchestrator(_StubHandler([_out("a")]))
        return await orch.cancel_active("no-such-conversation")
    assert asyncio.run(_run()) == 0  # 无在途流即 0，且不抛


def test_cancel_survives_notify_failure():
    """通知抛出时被吞掉，不影响取消返回——否则取消本身会失败，而调用方已收到受理成功。"""
    class _BadHandler(_StubHandler):
        @staticmethod
        async def on_interrupt(conversation_id, reason):
            raise RuntimeError("底层通知炸了")

    async def _run():
        handler = _BadHandler([_out("a"), _out("b")])
        orch = ServeOrchestrator(handler)
        ctx = ServeRequest.of_text("", conversation_id="c8")
        gen = orch.stream_query(ctx)
        await gen.__anext__()
        n = await orch.cancel_active("c8")
        [_ async for _ in gen]
        return n
    assert asyncio.run(_run()) == 1


def test_concurrent_executions_in_same_conversation_do_not_undo_cancel():
    """同一会话并发执行时，后启动者**不得**撤销前者的取消。

    这是句柄粒度存在的理由：曾经的单一键集合在每次执行开头清除自己的键，
    于是第二个执行一开始就把第一个的取消标记清掉了，取消被静默撤销。

    **这条判据能失败**：把登记表换回「已取消的会话键集合 + 执行开头清除」立刻转红。
    """
    async def _run():
        orch = ServeOrchestrator(_StubHandler([_out("a"), _out("b"), _out("c")]))
        ctx = ServeRequest.of_text("", conversation_id="same")
        gen1 = orch.stream_query(ctx)
        await gen1.__anext__()
        await orch.cancel_active("same")          # 取消第一条
        gen2 = orch.stream_query(ctx)             # 同会话再起一条
        await gen2.__anext__()                    # 它不受影响，能正常产出
        rest1 = [r async for r in gen1]           # 第一条仍应保持已取消
        rest2 = [r async for r in gen2]
        return rest1, rest2
    rest1, rest2 = asyncio.run(_run())
    assert rest1 == [], "第一条的取消被后启动的执行撤销了"
    assert len(rest2) > 0, "第二条不该受前一条的取消影响"


def test_query_falls_back_to_increments_when_the_framework_emits_no_final_answer():
    """处理器只发增量、不发终答时，非流式路径不得返回空答案。

    **这一族缺陷在仓里修过两次，编排层是第三处**：
    `adapters/inbound/rest/router.py` 的 `_aggregate` 注释逐字记着
    「流式增量发 `final_answer_chunk`、终态 `completed` 不带内容时，
    我方阻塞式接口恒返回空答案」；出站三个处理器随后统一到
    `domain/aggregate.py` 的「终答优先、没有终答就拼增量」。
    **编排层这一处当时没跟上**——同一条教训没有全文传播。

    独立复核 2026-08-19 实测到它：新接入的框架适配件正好是「只发增量」那一种，
    宿主按上游 SPI 调 `orchestrator.query()` 拿到空串，而同一次执行的流式路径
    看得到全部内容。

    **这条判据能失败**：把聚合改回「只取终答块」立刻转红。
    """
    async def _run():
        results = [_out("账单共"), _out(" 12 笔")]
        orch = ServeOrchestrator(_StubHandler(results))
        return await orch.query(ServeRequest.of_text("问", conversation_id="conv-3"))

    response = asyncio.run(_run())

    assert response.result == "账单共 12 笔", (
        f"只发增量的处理器让非流式路径返回了 {response.result!r}"
    )


def test_query_does_not_settle_an_error_frame_as_a_successful_answer():
    """**生产的非流式路径**不得把错误帧结算成成功答案（`FEAT-002:136`）。

    权威逐字：框架同步调用异常必须映射为 `FAILED`，
    **不得让异常绕过标准 Task/error 表面**。

    上一轮把还原异常这一步包在了处理器的 `query()` 上——**而生产上没有入口走它**：
    标准 A2A 入口走 `stream_query`，宿主非流式接口走本编排器的 `query`。
    本方法把带错误帧的流直接交给聚合件，而聚合件只处理终答与内容两支，
    **错误帧被静默丢弃**，调用方拿到一次成功的空调用。

    「修在生产不走的那条路上」是本仓点名过的形态，独立复核 2026-08-19b（N2）
    实测到它在同一处复现。

    **这条判据能失败**：把错误帧的处置去掉立刻转红——`result` 会变回空串。
    """
    async def _run():
        results = [_out("想了一半"), QueryChunk.of_error("模型服务不可达")]
        orch = ServeOrchestrator(_StubHandler(results))
        return await orch.query(ServeRequest.of_text("问", conversation_id="conv-err"))

    import pytest

    with pytest.raises(Exception) as caught:  # noqa: PT011 —— 类型由实现定，此处只要求不静默
        asyncio.run(_run())

    assert "模型服务不可达" in str(caught.value), (
        f"错误帧被结算成了成功答案或异常因果被抹掉：{caught.value}"
    )
