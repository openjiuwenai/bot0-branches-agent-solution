# solve_db 数据库初始化（solve_db_init）

本目录负责 calc_service 的 PostgreSQL 数据库初始化。

## 数据库概况

- **数据库名**：`huilian`
- **schema**：`solve_db`
- **连接参数**：`postgresql://huilian:huilian2026@localhost:5432/huilian`

## 数据表清单

`solve_db_init.sql` 共创建 12 张 `solve_db` 表和 1 张 `public.crude_types` 表：

| 表 | 说明 | 种子行数 |
| --- | --- | --- |
| devices_units | 加工装置 | 37 |
| devices_tanks | 罐区 | 53 |
| device_yields | 装置-原油收率 | 342 |
| side_lines | 侧线映射（material_id 关联原油类型） | 222 |
| connections | 装置连接 | 27 |
| material_flows | 物料流（物流图） | 140 |
| energy | 能耗结构 | 72 |
| tank_monthly_initial | 罐月初期初库存 | 37 |
| production_plans_input | 月度计划输入 | 3 |
| production_plan_details | 月度计划详情 | 108 |
| cp_sat_plan_details | CP-SAT 计划详情 | 0（只建表） |
| scheduling_tasks | 排产任务 | 1 |
| public.crude_types | 原油类型 | 11 |

价格数据不在本库：calc_service 不维护 `price_cost` 表，所有价格通过 `PRICE_API_URL` 从外部价格服务（默认 `http://localhost:8000/api/v1`）获取。

## 文件说明

| 文件 | 用途 |
| --- | --- |
| solve_db_init.sql | pg_dump 完整导出（建表 + COPY 种子数据，schema 含数据，默认 public） |
| init_from_excel.py | 方式 B 入口：包装 `backend/migrate_excel_to_db.py`，从 `refinery_data.xlsx` 重新灌 8 张表（TRUNCATE + INSERT，幂等） |
| seed_devices_batch.py | 把 `public.md_device` 中未映射的装置增量 upsert 到 `solve_db.devices` |
| seed_tanks_batch.py | 把罐区库存报表中的储罐主数据增量 upsert 到 `solve_db.devices` |
| migrate_special_var_semantic.sql | `special_var` 字段语义迁移 |

## 初始化方法

### 方法一：使用 init.sql（推荐）

```powershell
# 1. 创建数据库（如不存在）
psql -U postgres -c "CREATE DATABASE huilian;"

# 2. 导入 solve_db_init.sql（在 calc_service 目录下执行）
psql -U postgres -d huilian -f solve_db_init\solve_db_init.sql

# 3. 验证数据量
psql -U postgres -d huilian -c "SELECT COUNT(*) FROM solve_db.devices_units;"
psql -U postgres -d huilian -c "SELECT COUNT(*) FROM solve_db.device_yields;"
```

### 方法二：从 Excel 迁移（需要源文件）

迁移需要源文件 `refinery_data.xlsx`（共 8 个 sheet：装置信息、产品信息、装置连接、能耗数据、计划输入、计划详情、排产任务、价格成本）。该文件未随仓库入库，需自备；可用 `REFINERY_XLSX_PATH` 环境变量指定路径（默认为 calc_service 的 `backend/refinery_data.xlsx`）。

```powershell
cd calc_service\backend
python migrate_excel_to_db.py
```

## 验证数据量

```sql
SELECT 'devices_units' AS tbl, COUNT(*) FROM solve_db.devices_units
UNION ALL SELECT 'devices_tanks', COUNT(*) FROM solve_db.devices_tanks
UNION ALL SELECT 'device_yields', COUNT(*) FROM solve_db.device_yields
UNION ALL SELECT 'side_lines', COUNT(*) FROM solve_db.side_lines
UNION ALL SELECT 'connections', COUNT(*) FROM solve_db.connections
UNION ALL SELECT 'material_flows', COUNT(*) FROM solve_db.material_flows
UNION ALL SELECT 'energy', COUNT(*) FROM solve_db.energy
UNION ALL SELECT 'tank_monthly_initial', COUNT(*) FROM solve_db.tank_monthly_initial
UNION ALL SELECT 'production_plans_input', COUNT(*) FROM solve_db.production_plans_input
UNION ALL SELECT 'production_plan_details', COUNT(*) FROM solve_db.production_plan_details
UNION ALL SELECT 'scheduling_tasks', COUNT(*) FROM solve_db.scheduling_tasks
UNION ALL SELECT 'crude_types', COUNT(*) FROM public.crude_types;
```

预期结果（与 init.sql 种子行数一致）：

| 表 | 行数 |
| --- | --- |
| devices_units | 37 |
| devices_tanks | 53 |
| device_yields | 342 |
| side_lines | 222 |
| connections | 27 |
| material_flows | 140 |
| energy | 72 |
| tank_monthly_initial | 37 |
| production_plans_input | 3 |
| production_plan_details | 108 |
| scheduling_tasks | 1 |
| crude_types | 11 |

## 环境变量

- `DATABASE_URL`：覆盖默认连接串
  ```
  postgresql://huilian:huilian2026@localhost:5432/huilian
  ```
- `REFINERY_XLSX_PATH`：覆盖 `refinery_data.xlsx` 的期望路径（默认 `backend/refinery_data.xlsx`）
