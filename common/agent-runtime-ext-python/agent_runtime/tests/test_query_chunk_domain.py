# coding: utf-8

"""QueryResponse / QueryChunk 领域重写（L2-overview §2.1 · Feat-Func-002b §2.4）。

权威（对齐 java `spec/dto`）：
- `QueryChunk{type, data}` —— **仅两字段**；`type ∈ {chunk, interrupt, error, remote_agent_output}`
  **String 常量**（非 enum，对齐 java TYPE_CHUNK/TYPE_INTERRUPT/TYPE_ERROR）。
- `QueryResponse{result, conversation_id}` —— 非流式 query 聚合响应。
- **成功完成不是 chunk 类型**，而是**流正常结束**（不得臆造 COMPLETED 类型）；
  终答作为内容 chunk 投递后结束（002 §4.1，防"终止标记吞掉终答"线级缺陷）。

裸环境可跑（domain 洋葱最内层，零框架依赖）。
"""
from __future__ import annotations

import dataclasses

import pytest

from agent_runtime.domain.result import QueryChunk, QueryResponse

#: 上游 `QueryChunk` 的类型常量全集，逐项取自
#: `openJiuwen/agent-runtime-java/service/agent-service-spec/src/main/java/
#: com/openjiuwen/service/spec/dto/QueryChunk.java` 的 `:17`（interrupt）、`:20`（chunk）、
#: `:23`（remote_agent_output）、`:26`（error）——**不是我在这里定的集合**。
UPSTREAM_TYPE_VALUES = frozenset({"chunk", "interrupt", "remote_agent_output", "error"})


def test_query_chunk_type_constants_are_string_not_enum():
    """类型常量是 String 而非 Python 枚举，对齐 java QueryChunk.TYPE_*。"""
    assert QueryChunk.TYPE_CHUNK == "chunk"
    assert QueryChunk.TYPE_INTERRUPT == "interrupt"
    assert QueryChunk.TYPE_REMOTE_AGENT_OUTPUT == "remote_agent_output"
    assert QueryChunk.TYPE_ERROR == "error"
    assert isinstance(QueryChunk.TYPE_CHUNK, str)


def test_query_chunk_type_set_matches_upstream_exactly():
    """类型取值集合与上游**逐项相等**——不多不少。

    **上一版判据只断言三个常量各自存在**，于是集合缺一项它照样绿：上游第四个常量
    `remote_agent_output` 缺席了整整一版，而我方根设计
    `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§2.1` 早已把它登记为 active，同节明写
    「该取值不得删除」。**列举式断言测不出缺项，只有集合相等能。**

    多出来的同样要红：领域取值集合是对齐上游的领域概念，自造一个值即偏离。

    **这条判据能失败**：删掉任一常量、或加一个上游没有的常量，立刻转红。
    """
    declared = {
        value
        for name, value in vars(QueryChunk).items()
        if name.startswith("TYPE_") and isinstance(value, str)
    }

    assert declared == UPSTREAM_TYPE_VALUES, (
        f"类型取值集合与上游不一致：我方 {sorted(declared)}，上游 {sorted(UPSTREAM_TYPE_VALUES)}"
    )


def test_remote_agent_output_carries_content_and_provenance():
    """远端输出帧的载荷是 `content` 与 `projection` 两段，出处四键齐全。

    期望值来源：上游产出点
    `openJiuwen/agent-runtime-java/service/agent-service-app/src/main/java/
    com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchCoordinator.java:518-531`
    的 `forwardRemoteOutput`——`projection` 含 `kind`／`batchId`／`toolCallId`／`target`
    四键，`data` 另有 `content`。**不由被测代码推出。**

    **这条判据能失败**：少任一键、或把出处平铺进 `data` 顶层，立刻转红。
    """
    chunk = QueryChunk.of_remote_agent_output(
        "北京今天晴", batch_id="b-1", tool_call_id="c-1", target="weather-agent"
    )

    assert chunk.type == QueryChunk.TYPE_REMOTE_AGENT_OUTPUT
    assert chunk.data["content"] == "北京今天晴"
    assert chunk.data["projection"] == {
        "kind": "remote_agent_output",
        "batchId": "b-1",
        "toolCallId": "c-1",
        "target": "weather-agent",
    }


def test_query_chunk_has_exactly_type_and_data():
    """权威结构仅 {type, data} 两字段——不承载信封字段（002 §2.4 兼容锚）。"""
    names = {f.name for f in dataclasses.fields(QueryChunk)}
    assert names == {"type", "data"}


def test_no_completed_type_exists():
    """成功完成 = 流正常结束，绝不臆造 COMPLETED 类型（002 §2.4 红线）。"""
    for forbidden in ("TYPE_COMPLETED", "TYPE_OUTPUT", "TYPE_FAILED", "TYPE_INTERRUPTED"):
        assert not hasattr(QueryChunk, forbidden), f"{forbidden} 属旧四类模型，已废"


def test_default_type_is_chunk():
    """默认 type=chunk，对齐 java `private String type = TYPE_CHUNK`。"""
    assert QueryChunk().type == "chunk"


def test_constructors_carry_payload_in_data():
    """三个构造器：内容/中断/错误载荷一律进 data（顶层只有 type+data）。"""
    c = QueryChunk.of_chunk({"event_type": "thought", "content": "思考中"})
    assert c.type == "chunk" and c.data["event_type"] == "thought"

    i = QueryChunk.of_interrupt(content="请提供账号", interaction_id="node-1")
    assert i.type == "interrupt"
    assert i.data["content"] == "请提供账号" and i.data["interaction_id"] == "node-1"

    e = QueryChunk.of_error("远端超时", code="REMOTE_TIMEOUT", kind="RUNTIME_FACT")
    assert e.type == "error"
    assert e.data["message"] == "远端超时"
    assert e.data["code"] == "REMOTE_TIMEOUT"  # 原生 error code 保留（002 §2.1）
    assert e.data["kind"] == "RUNTIME_FACT"


def test_final_answer_is_a_content_chunk_not_a_terminal_marker():
    """终答必须作为内容 chunk 投递（002 §4.1）——不得被终止标记吞掉。

    这是部署级 E2E 抓出过的线级缺陷的领域侧锚。
    """
    final = QueryChunk.of_final_answer("您的余额为 6312.58 元")
    assert final.type == "chunk"  # 不是某种 "completed" 类型
    assert final.data["content"] == "您的余额为 6312.58 元"
    assert "final" not in final.data  # 没有终态标记：终答靠事件名，不靠标记（§4.4）


def test_query_response_is_non_stream_aggregate():
    """QueryResponse{result, conversation_id}——对齐 java spec/dto/QueryResponse。"""
    names = {f.name for f in dataclasses.fields(QueryResponse)}
    assert names == {"result", "conversation_id"}
    r = QueryResponse(result="余额 6312.58", conversation_id="c1")
    assert r.result == "余额 6312.58" and r.conversation_id == "c1"


def test_chunks_are_immutable_and_data_not_shared():
    """每帧独立、不共享可变状态（overview §9.2）。"""
    a, b = QueryChunk(), QueryChunk()
    assert a.data is not b.data
    with pytest.raises(dataclasses.FrozenInstanceError):
        a.type = "error"  # type: ignore[misc]


# ── 终态语义：终答块与完成信号是两种块（Feat-Func-002b §4.4） ─────────────────


def test_answer_and_completion_are_told_apart_by_event_name():
    """终答块与完成信号靠**事件名**区分，不靠标记。

    `CL-2d5412e99179`（FEAT-002 数据模型）：COMPLETED 不在 `QueryChunk` 数据模型中；
    上游 Java 在消费端按信封类型判终答，`QueryChunk` 无终答字段。存量的终答事件是
    `final_answer_chunk`、完成信号是 `completed`——两者对外语义不同（前者出帧、后者不出），
    做成同一种块会让投影层区分不开、只能一刀切吞掉（社区 issue #151）。
    """
    answer = QueryChunk.of_final_answer("余额 6312.58", event_type=QueryChunk.EVENT_FINAL_ANSWER_CHUNK)
    done = QueryChunk.of_final_answer("", event_type=QueryChunk.EVENT_COMPLETED)
    assert answer.is_answer and not answer.is_completion
    assert done.is_completion and not done.is_answer
    # 旧名 `is_final_answer` 现在等于 `is_answer`：完成信号不再算终答
    assert answer.is_final_answer and not done.is_final_answer


def test_a_plain_event_is_neither_answer_nor_completion():
    """普通内容帧、中断帧、错误帧都不是终答也不是完成信号——谓词只认 chunk 类型下的两个事件名。"""
    assert not QueryChunk.of_event("thought", content="想一想").is_answer
    assert not QueryChunk.of_event("thought", content="想一想").is_completion
    assert not QueryChunk.of_interrupt(content="请输入").is_answer
    assert not QueryChunk.of_error("坏了").is_completion
    # 无参默认即终答：绝大多数调用点就是在说「这是最终答案」。完成信号必须显式标注，
    # 于是漏标的只可能把完成当终答（多出一帧、差分立刻红），不可能把终答当完成（静默吞掉）。
    default = QueryChunk.of_final_answer("x")
    assert default.is_final_answer and default.is_answer and not default.is_completion


def test_completion_content_is_optional_but_answer_content_is_the_answer():
    """完成信号正文可空；终答块的正文就是最终答案，原样可读。"""
    assert QueryChunk.of_final_answer("", event_type=QueryChunk.EVENT_COMPLETED).content == ""
    assert (
        QueryChunk.of_final_answer("最终答案", event_type=QueryChunk.EVENT_FINAL_ANSWER_CHUNK).content
        == "最终答案"
    )
