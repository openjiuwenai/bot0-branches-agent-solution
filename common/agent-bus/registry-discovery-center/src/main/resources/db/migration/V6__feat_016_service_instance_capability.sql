-- V6: FEAT-016 阶段一 — serviceId/instanceId 分离 + capabilities 重建 + 4-field PK
--
-- 把 REQ-2026-006 的 service_id (host-port) 重命名为 instance_id，
-- 新增 service_id (logical service identifier, host-only)，
-- 重建 capabilities 列 (VARCHAR(64)[])，PK 演进到 4-field。
--
-- IRREVERSIBLE: RENAME COLUMN + DROP CONSTRAINT + ADD PRIMARY KEY 不可逆。
-- H4 checkpoint required before prod deploy (H2-5 decision).

-- ===== Step 1: RENAME 当前 service_id → instance_id（保留 host-port 值） =====
-- 旧 V5 service_id 由 regexp_replace(endpoint_url authority, ':', '-', 'g')
-- 生成 host-port 形式（如 10.0.0.1-8080）。该值现归入 instance_id。
ALTER TABLE agent_registry_mvp RENAME COLUMN service_id TO instance_id;

-- ===== Step 2: ADD 新 service_id 列（logical service identifier, host-only） =====
-- nullable during backfill, NOT NULL enforced after Step 4.
ALTER TABLE agent_registry_mvp ADD COLUMN service_id VARCHAR(64);

-- ===== Step 3: BACKFILL service_id from endpoint_url host =====
-- 提取 authority 子串去掉端口后取 host，转小写。
-- coalesce 回退：endpoint_url 无 '://' 时取整个 endpoint_url。
-- 与 InstanceIdCodec/ServiceIdCodec 应用层推导一致：service_id 是 host-only
-- 逻辑服务标识，instance_id 是 host-port 物理实例标识。
UPDATE agent_registry_mvp SET service_id = lower(substring(
    regexp_replace(coalesce(substring(endpoint_url from '://([^/]+)'), endpoint_url),
                   ':[0-9]+$', '')
    from '[^/]+'
));

-- ===== Step 4: SET NOT NULL =====
ALTER TABLE agent_registry_mvp ALTER COLUMN service_id SET NOT NULL;

-- ===== Step 5: ADD capabilities 列（VARCHAR(64)[]，默认空数组） =====
-- FEAT-016 重建 capabilities 为数组类型（V4 曾移除旧的标量 capability 列）。
ALTER TABLE agent_registry_mvp ADD COLUMN capabilities VARCHAR(64)[] DEFAULT '{}';

-- ===== Step 6: DROP old PK + ADD new 4-field PK =====
-- PK 演进：(tenant_id, agent_id, service_id) → (tenant_id, agent_id, service_id, instance_id)
-- 同一 logical service 可挂 N 个 instance（水平扩展 + 滚动升级共存）。
ALTER TABLE agent_registry_mvp DROP CONSTRAINT IF EXISTS agent_registry_mvp_pkey;
ALTER TABLE agent_registry_mvp ADD PRIMARY KEY (tenant_id, agent_id, service_id, instance_id);

-- ===== Step 7: GIN 索引 on capabilities =====
-- 支持 by-capability 查询（discovery by capability）。
-- 查询模式：WHERE tenant_id = :tenantId AND capabilities @> ARRAY[:capability]::varchar[]
-- tenant_id 由 PK 前缀（BTREE）覆盖，此处仅需 GIN 加速 @> (containment) 操作。
-- 注：= ANY() 不走 GIN（会退化为 seq scan），必须用 @>/&&/<@ 操作符；
-- 这里用 @> 因为是「列包含某元素」语义。不能用 (tenant_id, capabilities)
-- 复合 GIN —— 标量 VARCHAR 无默认 GIN operator class。拆为：PK BTREE 前缀
-- + 此 GIN 单列索引。索引名刻意区别于 V4 已删除的
-- idx_agent_registry_mvp_tenant_capability（BTREE 标量列），避免与 V4
-- "该索引已删除" 断言冲突。
CREATE INDEX idx_agent_registry_mvp_capabilities_gin
    ON agent_registry_mvp USING GIN (capabilities);
