# coding: utf-8

"""入站会话上下文的写入（Feat-Func-004b §2.3.1.1）。

## 它解决什么

南向出站报文的 `session_context` 四字段取自会话请求缓存键。该键原本只有存量在写，
纯本版部署时无人写入——南向发出的四字段恒空，下游拿不到上游原始请求。
那是对外可观察的行为差异，本件补上写侧。

## 为什么放在入站层

写入是「记下这次请求长什么样」，属入站职责。放到出站件会让它同时承担取数与送出两件事，
其输出也就不再由入参唯一决定，南向差分随之失效（§2.3.1 分层）。
根设计的具名读写例外第 4 条把这一点定死：**写入点限定在入站适配层**。

## 两个入口的差别只在取数来源

自定义 REST 入口从 HTTP 请求现取；标准服务入口从入站报文的数据片段搬运，
报文没带该片段就不写（**只搬运不现造**，与存量同构）。
字段集、生存期、写入语义三项两侧完全相同，故收敛在本件。
"""
from __future__ import annotations

import logging
import uuid
from typing import Any, Optional

from agent_runtime.ports.session import SessionRequestStore

_logger = logging.getLogger(__name__)

#: 生存期（秒）。存量两个写入点取值相同——分发函数与执行器都是 1800。
DEFAULT_REQUEST_TTL_S = 1800

#: 五字段的键名。**字段集须相等而非包含**：存量有一处消费点专读 `agent_id`，
#: 少写一项就把「本版读不到」的单向缺口换成「存量读不到」的反向缺口。
SNAPSHOT_FIELDS = ("headers", "trace_id", "agent_id", "params", "body")


def build_snapshot(
    *,
    headers: Optional[dict] = None,
    params: Optional[dict] = None,
    body: Optional[dict] = None,
    agent_id: str = "",
    trace_id: str = "",
) -> dict:
    """组装五字段快照。缺省项取空值，**不省略键**。

    省略键会让按字段集相等比对的消费方判不通过，而存量的消费点是按键取值的
    （取不到得到的是空，与「键在但值为空」表现相同——但字段集比对会红）。
    """
    return {
        "headers": dict(headers or {}),
        "trace_id": trace_id or str(uuid.uuid4()),
        "agent_id": str(agent_id or ""),
        "params": dict(params or {}),
        "body": dict(body or {}) if isinstance(body, dict) else {},
    }


async def record_inbound_request(
    store: Optional[SessionRequestStore],
    conversation_id: str,
    snapshot: dict,
    *,
    ttl_s: int = DEFAULT_REQUEST_TTL_S,
) -> bool:
    """把本次请求记入会话请求缓存键；返回是否真的写了。

    **读到即不写**（`SETNX`）：存量的语义是「首轮写、后续只读」，
    覆写会让续轮的上下文取代首轮的，与存量的行为分叉。

    **写入失败不抛**：该键承载的是供后续南向复用的上下文，不是本轮执行的输入。
    写不上的后果是后续南向拿到空上下文，而那已有兜底路径（§2.3.1 缺键行为）。
    为它中断一次正常执行，是把可降级的问题升级成不可用。
    """
    if store is None or not conversation_id:
        return False
    try:
        written = await store.put_request_if_absent(
            conversation_id, snapshot, ttl_s=ttl_s
        )
    except Exception:  # noqa: BLE001  见方法文档：不阻断
        _logger.warning(
            "会话上下文写入失败，后续南向将拿到空上下文：conv=%s",
            conversation_id, exc_info=True,
        )
        return False
    if not written:
        _logger.debug("会话上下文已存在，本轮不写：conv=%s", conversation_id)
    return written


def snapshot_from_session_context(session_context: Any, *, agent_id: str = "") -> Optional[dict]:
    """从入站报文的会话上下文片段搬运出快照；片段不存在返回 None。

    **只搬运，不现造**（§2.3.1.1）：上游 runtime 在南向报文里带了会话上下文，
    本版把它落到键上供自己的南向复用。报文没带（最外层直连）则本轮无上下文可记——
    此时现造一份会把「本节点收到的 A2A 报文」当成「最初的上游请求」记下去，
    那两者不是一回事。存量在报文无该片段时同样直接返回不写。

    `agent_id` 由调用方补：报文片段里没有它（存量写入时取自本进程的服务身份配置）。
    """
    if not isinstance(session_context, dict) or not session_context:
        return None
    return build_snapshot(
        headers=session_context.get("headers"),
        params=session_context.get("params"),
        body=session_context.get("body"),
        agent_id=agent_id,
        trace_id=str(session_context.get("trace_id") or ""),
    )
