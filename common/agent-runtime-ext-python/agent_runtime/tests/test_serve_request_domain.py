# coding: utf-8

"""ServeRequest 领域重写判据（L2-overview §2.1「领域模型」）。

权威 7 字段（对齐 FEAT-002/java spec/dto）：
    conversationId(兼任 session) · tenantId · userId · spaceId · messages · stream · metadata
不承载：taskId / agent / stateKey / memoryScope（「状态作用域」簇，后续特性补齐）。

裸环境可跑（零框架依赖，domain 洋葱最内层）。
"""
from __future__ import annotations

import dataclasses

import pytest

from agent_runtime.domain.context import ServeRequest


def test_serve_request_has_exactly_the_seven_authoritative_fields():
    """字段集必须是权威 7 字段——多一个少一个都是偏离权威字段集。"""
    names = {f.name for f in dataclasses.fields(ServeRequest)}
    assert names == {
        "conversation_id",
        "tenant_id",
        "user_id",
        "space_id",
        "messages",
        "stream",
        "metadata",
    }


def test_state_scope_cluster_fields_absent():
    """taskId/agent/stateKey/memoryScope 当前不承载（整簇后续补，不单加半成品）。"""
    names = {f.name for f in dataclasses.fields(ServeRequest)}
    for absent in ("task_id", "task", "agent", "agent_id", "state_key", "memory_scope"):
        assert absent not in names, f"{absent} 不应承载（FEAT-002:35 后续特性补齐）"


def test_defaults_and_immutability():
    """每请求独立、不共享可变状态（overview §9.2）；stream 默认 True 对齐 java。"""
    r = ServeRequest()
    assert r.conversation_id == "" and r.tenant_id == "" and r.user_id == "" and r.space_id == ""
    assert r.messages == [] and r.metadata == {}
    assert r.stream is True  # java ServeRequest: private boolean stream = true
    with pytest.raises(dataclasses.FrozenInstanceError):
        r.conversation_id = "x"  # type: ignore[misc]
    # 默认可变字段不共享
    assert ServeRequest().messages is not ServeRequest().messages


def test_messages_is_role_content_sequence():
    """messages 为 [{role, content}] 序列（FEAT-002:35 / java List<Map<String,Object>>）。"""
    r = ServeRequest(messages=[{"role": "user", "content": "查余额"}])
    assert r.messages[0]["role"] == "user"
    assert r.messages[0]["content"] == "查余额"


def test_last_user_query_derives_text_from_messages():
    """last_user_query()：对齐 java ServeRequest.lastUserQuery()，供 adapter 取当前主输入。

    存量单条 message 的行为必须等价（逐字节兼容 P1 的输入侧锚）。

    **本条原来的多轮用例分不出角色过滤在不在**：它的最后一条恰好是 user，
    「按 user 过滤」与「取最后一条有内容的」两种语义读数相同。
    能分辨的用例见下面三条。
    """
    assert ServeRequest(messages=[{"role": "user", "content": "查余额"}]).last_user_query() == "查余额"
    # 多轮：取最后一条 user 消息
    r = ServeRequest(messages=[
        {"role": "user", "content": "第一轮"},
        {"role": "assistant", "content": "回复"},
        {"role": "user", "content": "第二轮"},
    ])
    assert r.last_user_query() == "第二轮"
    # 空消息序列 → 空串（不抛，adapter 侧按空输入处理）
    assert ServeRequest().last_user_query() == ""


def test_last_user_query_skips_a_trailing_assistant_message():
    """末条是 assistant 时，取的仍是最后一条 **user** 消息。

    期望值来源：上游 `openJiuwen/agent-runtime-java/service/agent-service-spec/src/main/java/
    com/openjiuwen/service/spec/dto/ServeRequest.java:83-102` 的 `lastMessageWithContent()`
    ——**先倒序按 `role` 等于 user（忽略大小写）过滤**，找不到才回退。**不由被测代码推出。**

    **修前实测返回 `'好的，正在查询'`**，也就是把智能体自己上一轮的回复当成了用户本轮输入。
    后果不停留在取值：`adapters/outbound/agentcore/handler.py` 用它组装喂给框架的 `query`，
    另有三处把它用作续接的 `user_supplement`。

    **这条判据能失败**：去掉角色过滤立刻转红。
    """
    request = ServeRequest(messages=[
        {"role": "user", "content": "帮我查余额"},
        {"role": "assistant", "content": "好的，正在查询"},
    ])

    assert request.last_user_query() == "帮我查余额"
    # 大小写不敏感——上游用的是 equalsIgnoreCase
    mixed = ServeRequest(messages=[
        {"role": "User", "content": "帮我查余额"},
        {"role": "assistant", "content": "好的，正在查询"},
    ])
    assert mixed.last_user_query() == "帮我查余额"


def test_last_user_query_falls_back_to_the_final_message_only() -> None:
    """一条 user 消息都没有时，回退**只看最后一条**，不往前捞。

    期望值来源：上游同一方法的回退分支——`messages.get(messages.size() - 1)`，
    且仅当其 `content` 非 null 才采用。**不由被测代码推出。**

    **修前是倒序找第一条有内容的**：末条无内容时会一直往前捞，取到更早的消息。

    **这条判据能失败**：把回退改回倒序扫描立刻转红。
    """
    request = ServeRequest(messages=[
        {"role": "assistant", "content": "更早的回复"},
        {"role": "assistant"},  # 末条无 content
    ])

    assert request.last_user_query() == ""


def test_last_user_query_selects_a_user_message_with_empty_content() -> None:
    """user 消息的内容为空串时**就取它**，不继续往前找。

    期望值来源：上游判的是 `content != null`，不是「非空字符串」。**不由被测代码推出。**

    **修前判的是 `if content:`**，于是空串被跳过、取到更早的一条——
    而「用户这轮发了空内容」与「用户这轮说了上一轮那句话」是两回事。

    **这条判据能失败**：把判定改回真值判断立刻转红。
    """
    request = ServeRequest(messages=[
        {"role": "user", "content": "上一轮说的话"},
        {"role": "user", "content": ""},
    ])

    assert request.last_user_query() == ""


def test_query_and_metadata_come_from_the_same_message() -> None:
    """正文与元数据来自**同一条**消息。

    期望值来源：上游 `lastUserQuery()`（`ServeRequest.java:59-61`）与
    `lastUserMessageMetadata()`（同文 `:68-83`）**都走同一个 `lastMessageWithContent()`**，
    故必然同源。**不由被测代码推出。**

    **修前两个方法各自倒序扫描、判据还不同**（一个找有内容的、一个找带映射 metadata 的），
    于是同一次调用里正文与元数据可能来自两条不同的消息。

    **这条判据能失败**：让任一方法改回自己扫描立刻转红。
    """
    request = ServeRequest(messages=[
        {"role": "user", "content": "第一轮", "metadata": {"turn": 1}},
        {"role": "assistant", "content": "回复", "metadata": {"turn": 2}},
    ])

    assert request.last_user_query() == "第一轮"
    assert request.last_user_message_metadata() == {"turn": 1}


def test_metadata_is_a_defensive_copy_with_string_keys_only() -> None:
    """元数据返回防御性副本，且只保留字符串键。

    期望值来源：上游同名方法构造新的 `LinkedHashMap` 并逐项过滤非 String 键
    （`ServeRequest.java:68-83`）。**不由被测代码推出。**

    **返回原字典的引用会让调用方改一下就污染了请求对象**，而本类是冻结数据类，
    不可变是它的承诺。

    **这条判据能失败**：改回直接返回原字典立刻转红。
    """
    original = {"turn": 1, 7: "非字符串键"}
    request = ServeRequest(messages=[{"role": "user", "content": "问", "metadata": original}])

    taken = request.last_user_message_metadata()
    taken["turn"] = 999

    assert taken == {"turn": 999}, "副本本身应可改"
    assert original["turn"] == 1, "改副本污染了请求对象——返回的不是副本"
    assert 7 not in taken, "非字符串键未被过滤"


def test_of_text_builds_single_user_message():
    """of_text() 便利构造：单条文本 → messages 序列（inbound adapter 去协议化用）。"""
    r = ServeRequest.of_text("查余额", conversation_id="c1", tenant_id="t1", user_id="u1")
    assert r.messages == [{"role": "user", "content": "查余额"}]
    assert r.conversation_id == "c1" and r.tenant_id == "t1" and r.user_id == "u1"
    assert r.last_user_query() == "查余额"
