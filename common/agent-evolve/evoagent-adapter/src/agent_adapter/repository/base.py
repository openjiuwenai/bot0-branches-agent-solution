"""数据库操作层: TraceRepository 抽象契约 (DB 无关, 无 SQL)。

入库 (kafka 消费器) 与读取 (DbTraceSource) 共用同一抽象。
DB 接入层 (repository/postgres.py 等) 实现本 Protocol; 工厂按 config DB_TYPE 选 adapter。
新增数据库只需写一个实现类 + 工厂登记, 操作层/消费器/API 零改动。

操作层只收发 Python dict (span/trace), 不见 SQL / JSONB / TEXT ——
序列化与方言差异 (PG JSONB vs 其它库 TEXT+应用层解析) 封在各 adapter 内。
"""

from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class TraceRepository(Protocol):
    """轨迹仓储契约。span/trace 均为 dict (见 kafka_consumer.otlp_parser.parse_span 的形状)。"""

    async def init_schema(self) -> None:
        """建表/迁移 (方言差异在 adapter 内)。启动时调一次。"""
        ...

    # ---- 写 (kafka 消费器调) ----

    async def insert_span(self, span: dict[str, Any]) -> None:
        """插入一条 span (含 traces 汇总 upsert)。"""
        ...

    async def bulk_insert_spans(self, spans: list[dict[str, Any]]) -> None:
        """批量插入 spans (同 trace 的 spans 一次写, 顺带 upsert traces 汇总)。"""
        ...

    async def upsert_trace(self, trace: dict[str, Any]) -> None:
        """upsert 一条 traces 汇总 (消费者写 spans 时同步维护)。"""
        ...

    # ---- 读 (DbTraceSource / API 调) ----

    async def get_spans_by_trace(self, trace_id: str) -> list[dict[str, Any]]:
        """按 trace_id 取全部 spans (按 start_time 升序, 供重建 span 树)。"""
        ...

    async def get_spans_by_conversation(self, conversation_id: str) -> list[dict[str, Any]]:
        """按 conversation_id 取全部 spans (跨 trace, 按 start_time 升序)。"""
        ...

    async def get_trace_tree(self, trace_id: str) -> dict[str, Any]:
        """重建 span 树 (parent/children 嵌套)。"""
        ...

    async def get_root_span(self, conversation_id: str) -> dict[str, Any] | None:
        """取会话根 span (kind=SERVER 且 parent_span_id 为空); 用于 complete 判定与汇总。"""
        ...

    async def list_conversations(self, agent_name: str | None = None) -> list[dict[str, Any]]:
        """列出 traces 汇总 (可按 service_name=agent_name 过滤)。"""
        ...
