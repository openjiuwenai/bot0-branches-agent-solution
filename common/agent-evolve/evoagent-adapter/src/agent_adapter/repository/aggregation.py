"""纯 span/trace 聚合逻辑 —— 无 I/O, 无 asyncpg, 仅 stdlib。

Repository 接入层 (postgres.py) 的单一真相源: 从扁平 span dict 计算 traces 汇总、
重建 span 树、判定根 span。postgres.py 只做 asyncpg I/O 并调用本模块, 方言差异
(PG JSONB 序列化等) 封在接入层内, 聚合规则在此处集中, 便于用 jsonl 单测钉死。

span dict 形状见 kafka_consumer.otlp_parser.parse_span:
    trace_id/span_id/parent_span_id/kind/start_time/end_time/status_code/
    service_name/session_id/attributes(dict)/resource_attributes(dict)/...

汇总规则 (设计文档 §3 traces 表):
    - 根 span = kind=SERVER 且 parent_span_id 为空; root_span_id 取自根 span。
    - request_summary 取自根 span 的 openjiuwen.http.request_body;
      response_summary 取自 chain span 的 openjiuwen.agent.outputs (EDPAgent 真实响应)。
    - start_time = min(各 span start_time); end_time = max(各 span end_time)。
    - span_count = len(spans); status = 最差状态 (ERROR > OK > UNSET)。
    - session_id/service_name: 根 span 优先, 否则取首个非空。
"""

from __future__ import annotations

from typing import Any

# status 严重度: ERROR > OK > UNSET (设计文档 §3 traces.status "取最差状态")
_STATUS_RANK: dict[str, int] = {"UNSET": 0, "OK": 1, "ERROR": 2}


def is_root_span(span: dict[str, Any]) -> bool:
    """根 span: kind=SERVER 且 parent_span_id 为空 (会话入口, 用于 complete 判定与汇总)。"""
    return span.get("kind") == "SERVER" and not span.get("parent_span_id")


def worst_status(statuses: list[str | None]) -> str:
    """取最差状态 (ERROR > OK > UNSET); 空集或全未知返回 UNSET。

    未知状态串 (非 OK/ERROR/UNSET) 按 UNSET (rank 0) 处理, 结果回查规范名 ——
    避免 "WEIRD" 这类脏值透传到 traces.status 列。
    """
    if not statuses:
        return "UNSET"
    best_rank = max(_STATUS_RANK.get(s or "UNSET", 0) for s in statuses)
    return next(name for name, rank in _STATUS_RANK.items() if rank == best_rank)


def _attrs(span: dict[str, Any]) -> dict[str, Any]:
    return span.get("attributes") or {}


def _chain_agent_outputs(spans: list[dict[str, Any]]) -> Any:
    """chain span 的 openjiuwen.agent.outputs (EDPAgent 真实响应)。无 chain span 返回 None。

    chain span = name 以 "chain." 开头 (如 chain.EDP Agent)。一个 trace 通常一个 chain 根。
    """
    for s in spans:
        if (s.get("name") or "").startswith("chain."):
            return _attrs(s).get("openjiuwen.agent.outputs")
    return None


def compute_trace_summary(trace_id: str, spans: list[dict[str, Any]]) -> dict[str, Any]:
    """从一条 trace 的全部 spans 计算 traces 汇总行 (dict, 对齐 traces 表列)。

    与 span 插入顺序无关: 总是从完整 span 集合重算。空 spans 抛 ValueError。
    """
    if not spans:
        raise ValueError(f"compute_trace_summary: trace {trace_id!r} 无 spans")

    root = next((s for s in spans if is_root_span(s)), None)
    a_root = _attrs(root) if root else {}
    # 根 span 优先提供会话级字段, 否则回退首个非空 (根可能尚未到达)
    first = spans[0]

    def _first_attr(key: str) -> Any:
        if a_root.get(key) is not None:
            return a_root[key]
        for s in spans:
            v = _attrs(s).get(key)
            if v is not None:
                return v
        return None

    start_times = [s["start_time"] for s in spans if s.get("start_time")]
    end_times = [s["end_time"] for s in spans if s.get("end_time")]

    return {
        "trace_id": trace_id,
        "session_id": (root or first).get("session_id") or _first_attr("session.id"),
        "root_span_id": root["span_id"] if root else None,
        "service_name": (root or first).get("service_name"),
        "start_time": min(start_times) if start_times else None,
        "end_time": max(end_times) if end_times else None,
        "span_count": len(spans),
        "status": worst_status([s.get("status_code", "UNSET") for s in spans]),
        "request_summary": a_root.get("openjiuwen.http.request_body"),
        "response_summary": _chain_agent_outputs(spans),
    }


def build_trace_tree(spans: list[dict[str, Any]]) -> dict[str, Any] | None:
    """从扁平 spans (同 trace) 重建 parent/children 嵌套树。

    返回根节点 (含 ``children`` 列表); 无 span 返回 None。
    单根 (常见) 直接返回该根节点; 多根 (孤儿 span) 包一层合成节点 {"children": roots}。
    children 按 span 在输入中的先后顺序排列。
    """
    if not spans:
        return None

    # 第一遍: 建 node (含空 children); 第二遍: 按 parent 挂 children + 收集根。
    # 两遍分开以覆盖父 span 在子之后出现的输入顺序。
    nodes: dict[str, dict[str, Any]] = {
        s["span_id"]: {**s, "children": []} for s in spans
    }
    roots: list[dict[str, Any]] = []
    for s in spans:
        node = nodes[s["span_id"]]
        pid = s.get("parent_span_id")
        if pid and pid in nodes:
            nodes[pid]["children"].append(node)
        else:
            roots.append(node)

    if not roots:
        return None
    if len(roots) == 1:
        return roots[0]
    return {"name": "<multi-root>", "span_id": None, "children": roots}
