# coding: utf-8

"""阻塞路径的结果聚合——**处理器与编排层共用的一份规则**。

## 它为什么在 `domain/`

「一条结果流的答案是什么」是**领域规则**，不含任何框架或协议绑定：
入参是 `QueryChunk` 流，出参是一个字符串，规则由本项目定
（上游 SPI 只说「the aggregated query response」，不规定聚合规则）。

**先前放在 `adapters/outbound/` 顶层**，那时的消费方只有出站三个处理器。
编排层 `application/serve.py` 要复用它时，依赖方向门禁当场阻断——
`application` 不得 import `adapters`（宪法 §二 INV-1／INV-5）。
**门禁是对的**：越界的不是那次复用，是这份规则一开始就放错了层。
它下沉到领域层之后，四个处理器与编排层引的是同一份，且没有一条向外的边。
"""
from __future__ import annotations

from typing import AsyncIterator

from agent_runtime.domain.result import QueryChunk


async def aggregate_stream(chunks: AsyncIterator[QueryChunk]) -> str:
    """把一条结果流聚合成阻塞路径的答案。

    **共用方**：`agentcore`／`versatile`／`hostagent`／`agentscope` 四个出站处理器，
    加编排层 `application/serve.py`。写名字不写条数——上一版写「三个」而实为四个，
    独立复核连记两轮；名字漂移一眼可见，计数漂移要数一遍才知道。

    ## 规则：终答优先，没有终答就拼增量

    终答块承载的就是最终答案，它与累积的增量是同一份内容的两种投影，
    相加会得到重复文本——**有终答就以终答为准**。

    而**只发增量、不发终答**的框架是存在的。此前 `agentcore` 与 `hostagent`
    的聚合只取终答，遇到那种框架**恒返回空答案**——阻塞式调用方拿到一个空结果，
    而流式调用方在同一次执行里能看到全部内容。

    **同一族缺陷在入站侧已经修过一次**：`adapters/inbound/rest/router.py` 的
    `_aggregate` 注释逐字记着「流式增量发 `final_answer_chunk`、终态 `completed`
    不带内容时，**我方阻塞式接口恒返回空答案**」。那一次修的是入站聚合，
    出站这三处没跟上——**同一条教训没有全文传播**，那正是交付纪律里点名要防的形态。

    上游 SPI 只说「the aggregated query response」
    （`openJiuwen/agent-runtime-java/.../spec/AgentHandler.java` 的 `AgentHandler`），
    **不规定聚合规则**；故本规则由我方定，取「不丢结果」的那一种。

    **错误块在这里抬回异常**：聚合只发生在成功路径上。
    先前的写法是「错误块不在此处吞掉」——听起来对，实际是把它当成
    「既不是终答也不是内容」而静默跳过，调用方拿到一次成功的空调用。
    权威 `CL-18eb7230f068` 逐字禁止让异常绕过标准 Task/error 表面。

    **放在领域层而不是某个处理器上**：生产上有两个非流式消费方
    （编排器的 `query` 与处理器自身的 `query`），修在其中一个上，
    另一个照样静默。独立复核 2026-08-19b（N2）实测到这个形态——
    上一轮修的恰好是生产不走的那一个。
    """
    texts: list[str] = []
    final: str | None = None
    completion: str | None = None
    async for chunk in chunks:
        if chunk.type == QueryChunk.TYPE_ERROR:
            raise ExecutionFailed(str((chunk.data or {}).get("message", "")))
        if chunk.is_answer:
            # 终答块（Feat-Func-002b §4.4）：优先级最高，后到的完成信号不覆盖它。
            final = chunk.content
        elif chunk.is_completion:
            # 完成信号：正文只作回落，且不进增量拼接——它不是产出的一段。
            if chunk.content and final is None:
                completion = chunk.content
        elif chunk.type == QueryChunk.TYPE_CHUNK:
            texts.append(chunk.content)
    if final is not None:
        return final
    return completion if completion is not None else "".join(texts)


class ExecutionFailed(RuntimeError):
    """一次执行以错误帧收尾（阻塞路径上由聚合件抬回异常）。

    **流式路径不经过这里**：那一侧的错误帧就是对外语义（→ Task FAILED），
    抬成异常反而会让出流层把它读成链路失败。**同一份产出，两条路径的
    正确形态不同**。
    """
