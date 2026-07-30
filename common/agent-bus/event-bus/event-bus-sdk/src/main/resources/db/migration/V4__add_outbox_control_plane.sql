-- agent-bus P-06: control plane first-class outbox columns.
--
-- P-06 (FEAT-013/014 control/data separation) moved the request control plane OFF payloadRef (which is
-- now the pure A2A data reference) onto FIRST-CLASS broker fields. The JDBC outbox must persist them as
-- columns, or they are lost at the enqueue boundary and the relay's control-plane-presence governance
-- rejects every request. route_handle (V1) + correlation_id / event_type (V3) already existed; this adds
-- the remaining control + data-routing columns.
--
-- All nullable: control-only / pre-P-06 back-compat rows carry null; data-bearing FEAT-013/014 rows
-- populate them. deadline_millis_epoch is written as the long value (Long.MAX_VALUE = "no deadline");
-- null only on pre-P-06 rows, read back as Long.MAX_VALUE by ForwardingSqlCodec. inline_payload is the
-- bounded small A2A body (2b); TEXT to avoid truncating a small JSON-RPC envelope. original_caller is
-- the original gateway/caller serviceId carried end-to-end for response routing across the relay hop.
ALTER TABLE agent_bus_forwarding_outbox
    ADD COLUMN trace_id             VARCHAR(128),
    ADD COLUMN idempotency_key      VARCHAR(128),
    ADD COLUMN capability           VARCHAR(128),
    ADD COLUMN deadline_millis_epoch BIGINT,
    ADD COLUMN inline_payload       TEXT,
    ADD COLUMN original_caller      VARCHAR(128);
