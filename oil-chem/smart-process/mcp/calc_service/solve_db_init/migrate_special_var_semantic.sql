-- ============================================================
-- 迁移脚本: special_var 语义化重命名
-- 将无意义的 'X'/'Y' 硬编码改为语义值 'jian1_to_diesel'/'jian1_to_wax'
--
-- 变更内容:
--   1. connections.special_var: varchar(4) → varchar(32)
--   2. material_flows.special_var: varchar(4) → varchar(32)
--   3. 数据值: 'X' → 'jian1_to_diesel' (减一线去柴油加氢方向)
--             'Y' → 'jian1_to_wax'     (减一线去蜡油加氢方向)
--
-- 对应代码常量 (config.py):
--   MODE_JIAN1_TO_WAX    = 'JIAN1_TO_WAX'    (原 X_ZERO: X=0, 全量去蜡油)
--   MODE_JIAN1_TO_DIESEL = 'JIAN1_TO_DIESEL' (原 Y_ZERO: Y=0, 全量去柴油)
--
-- 注意: direct_calculator.py 中 SV_KEY_MAP 保留向后兼容,
--       旧 DB 值 'X'/'Y' 仍能正确映射到语义键。
-- ============================================================

-- 1. 扩展列宽（varchar(4) 无法容纳语义值）
ALTER TABLE solve_db.connections ALTER COLUMN special_var TYPE character varying(32);
ALTER TABLE solve_db.material_flows ALTER COLUMN special_var TYPE character varying(32);

-- 2. 更新 connections 表数据值
UPDATE solve_db.connections
SET special_var = 'jian1_to_diesel'
WHERE special_var = 'X';

UPDATE solve_db.connections
SET special_var = 'jian1_to_wax'
WHERE special_var = 'Y';

-- 3. 更新 material_flows 表数据值
UPDATE solve_db.material_flows
SET special_var = 'jian1_to_diesel'
WHERE special_var = 'X';

UPDATE solve_db.material_flows
SET special_var = 'jian1_to_wax'
WHERE special_var = 'Y';

-- 4. 验证（执行后应返回 0 行）
-- SELECT * FROM solve_db.connections WHERE special_var IN ('X', 'Y');
-- SELECT * FROM solve_db.material_flows WHERE special_var IN ('X', 'Y');
