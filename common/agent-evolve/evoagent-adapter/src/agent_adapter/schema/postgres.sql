-- agent-adapter 轨迹标准方式建表 (PostgresTraceRepository.init_schema 用)
-- 对齐 OTel Span 标准; PG 用 JSONB, 其它库由接入层适配 (TEXT + 应用层解析)。
-- DROP 重建 (与现 trace-pg-sink 的 spans 表不兼容: 时间戳 timestamptz/ISO, kind 字符串, 多 events/links/scope_version)。

-- spans: 一个 OTel span 一行
CREATE TABLE IF NOT EXISTS spans (
    trace_id           text        NOT NULL,
    span_id            text        NOT NULL,
    parent_span_id     text,
    trace_state        text,                          -- OTLP traceState (W3C), 多为空
    name               text,
    kind               text,                          -- SERVER/CLIENT/INTERNAL/PRODUCER/CONSUMER (枚举名)
    start_time         timestamptz NOT NULL,          -- 入表时校验并转换 (接受 ISO 8601 或 unix nano, 统一存 timestamptz)
    end_time           timestamptz,
    service_name       text,                          -- 从 resource_attributes."service.name" 提升
    scope_name         text,
    scope_version      text,
    status_code        text,                          -- OK/ERROR/UNSET
    status_message     text,
    attributes         jsonb,                         -- span 属性原样 (gen_ai.* / openjiuwen.* / http.* / session.id 等; distinct 键见 docs/db_schema.md)
    resource_attributes jsonb,
    events             jsonb,                         -- OTLP Event[]
    links              jsonb,                         -- OTLP Link[]
    session_id         text,                          -- 从 attributes."session.id" 提升 (轨迹 API 查询键)
    ingested_at        timestamptz DEFAULT now(),
    PRIMARY KEY (trace_id, span_id)
);
CREATE INDEX IF NOT EXISTS idx_spans_session    ON spans (session_id);
CREATE INDEX IF NOT EXISTS idx_spans_trace_time ON spans (trace_id, start_time);
CREATE INDEX IF NOT EXISTS idx_spans_attr       ON spans USING gin (attributes);

-- traces: 应用层汇总 (非 OTel 标准), 消费者写 spans 时 upsert
CREATE TABLE IF NOT EXISTS traces (
    trace_id            text PRIMARY KEY,
    session_id          text,                        -- 从 attributes."session.id" 提升 (可空: 部分 trace EDPAgent 未上报 session.id)
    root_span_id        text,
    service_name        text,
    start_time          timestamptz,
    end_time            timestamptz,
    span_count          int  DEFAULT 0,
    status              text,                         -- 取最差状态 (ERROR > OK > UNSET)
    request_summary     jsonb,                        -- 根 http span 的 openjiuwen.http.request_body
    response_summary    jsonb,                        -- chain span 的 openjiuwen.agent.outputs (EDPAgent 真实响应在此)
    created_at          timestamptz DEFAULT now(),
    updated_at          timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_traces_session ON traces (session_id);
