# solve_v1 求解器数据库初始化（solve_db schema）

本目录提供 **solve_v1 求解器** 的 PostgreSQL 初始化包。solve_v1 与慧炼主项目共用同一个
`huilian` 库，所有求解器业务表建在 **`solve_db` schema** 下，共 **8 张表**（结构 + 数据）。

提供两种初始化方式，按需选用：

| 方式 | 文件 | 特点 |
|---|---|---|
| **方式 A：纯 SQL 导入（推荐，对齐慧炼 `huilian_db_init`）** | `solve_db_init.sql` | `pg_dump` 导出的快照，结构 + 数据一份到位，不依赖 Python，`psql`/docker 一行导入 |
| **方式 B：Python 脚本从 Excel 刷新** | `solve_v1/backend/migrate_excel_to_db.py` | 从 `refinery_data.xlsx` 实时灌数据，改源 Excel 后重跑即刷新 |

> 两种方式**均为幂等**：可重复执行，先 DROP/TRUNCATE 同名对象再重建。
> ⚠️ 若导入到一个已有数据的 `solve_db` schema，会先删除同名表再重建，请确认无重要数据。

## 连接参数（与项目默认一致，共用 huilian 库）

| 项 | 值 |
|---|---|
| 数据库名 | `huilian`（与慧炼主项目同库） |
| schema | `solve_db` |
| 用户 / 密码 | `huilian` / `huilian2026` |
| 端口 | `5432` |
| PostgreSQL 版本 | 15+（本包由 18.4 导出，兼容 15 及以上） |

连接串（见 `solve_v1/backend/config.py`）：
```
postgresql://huilian:huilian2026@localhost:5432/huilian
```

---

## 方式 A：纯 SQL 导入（推荐）

### A1. Docker（最省事）

```bash
# 若已有 huilian-pg 容器（与慧炼共用），直接导入 solve_db schema：
docker exec -i huilian-pg psql -U huilian -d huilian < solve_db_init.sql
```

### A2. 已有 PostgreSQL 实例

```bash
psql -U huilian -d huilian -f solve_db_init.sql
# Windows 下用 -f 避免 < 重定向的编码问题（文件为 UTF-8）
```

> `solve_db_init.sql` 由 `pg_dump -n solve_db --clean --if-exists --no-owner --no-privileges --column-inserts`
> 生成：文件内每个对象先 `DROP ... IF EXISTS` 再创建，**可重复执行（幂等）**。

---

## 方式 B：Python 脚本从 Excel 刷新

当 `refinery_data.xlsx`（8 个 sheet 的源数据）有更新时，用此方式重新灌库，数据始终与 Excel 同源。

```bash
# 在仓库根目录执行（需已装依赖：sqlalchemy、psycopg2-binary、pandas、openpyxl）
python -m calc_service.backend.migrate_excel_to_db
```

脚本流程：
1. `init_db()` 幂等建 schema + 8 张表（`solve_v1/backend/data/db.py`）
2. 读 `refinery_data.xlsx` 全 8 sheet，做单位/类型转换（收率 ÷100、JSON→JSONB、日期/时间戳转换等）
3. `TRUNCATE` 清空 8 张表后 `INSERT`（单事务，`ON CONFLICT DO NOTHING`）
4. 行数 + 收率小数 + 原油品种数 校验

> 改源 Excel 后重跑即可刷新对应表数据，无需重新导出 SQL。

---

## 导入后验证

```bash
docker exec huilian-pg psql -U huilian -d huilian -c "
SELECT 'devices' t, COUNT(*) n FROM solve_db.devices
UNION ALL SELECT 'products', COUNT(*) FROM solve_db.products
UNION ALL SELECT 'connections', COUNT(*) FROM solve_db.connections
UNION ALL SELECT 'energy', COUNT(*) FROM solve_db.energy
UNION ALL SELECT 'price_cost', COUNT(*) FROM solve_db.price_cost
UNION ALL SELECT 'production_plans_input', COUNT(*) FROM solve_db.production_plans_input
UNION ALL SELECT 'production_plan_details', COUNT(*) FROM solve_db.production_plan_details
UNION ALL SELECT 'scheduling_tasks', COUNT(*) FROM solve_db.scheduling_tasks
ORDER BY t;"
```

预期结果（本次导出的种子数据量）：

| 表 | 行数 | 说明 |
|---|---|---|
| devices | 15 | 装置清单（常减压/柴加/蜡加/储罐等） |
| products | 116 | 产品收率表（含航煤 yield_rate_3/4，复合主键 product_id+crude_type） |
| connections | 27 | 物流连接（含减一线 X/Y 分流 special_var） |
| energy | 72 | 能耗表（电/燃料油/蒸汽等） |
| price_cost | 78 | 中间产品裸税价（按月 price_month） |
| production_plans_input | 3 | 原油加工输入（1-3月） |
| production_plan_details | 108 | 排产按天明细（1-3月） |
| scheduling_tasks | 1 | 排产任务状态 |

校验锚点：
- `products` 收率字段为小数（0~1），如 `cyjq_01_hangmei.yield_rate = 0.0638`
- `products` 原油品种数 = 4（渤中25-1 / 秦皇岛南堡 / 曹妃甸 / 流花旅大）

---

## 8 张表说明

`solve_db` schema 下 8 张表，对应 `refinery_data.xlsx` 的 8 个 sheet：

### 参考配置表（5 张，来自 Excel 配置 sheet，运行时只读）
- **devices** — 装置清单：常减压 cjy_01、柴加 cyjq_01、蜡加 lyjq_01、各类储罐等 15 台
- **products** — 产品收率：每条 = (装置产品, 原油品种)，含 4 档收率（normal/hangmei × JIAN1_TO_WAX/JIAN1_TO_DIESEL）+ 价格
- **connections** — 物流连接：from_device/product → to_device，减一线分流靠 `special_var`（jian1_to_diesel=去柴加 / jian1_to_wax=去蜡加）
- **energy** — 能耗：每装置每能源类型的单耗 + 单价
- **price_cost** — 中间产品裸税价：按月（price_month）的产品价格/含税价/保税价

### 运行时计划表（3 张，排产/批次划分时读写）
- **production_plans_input** — 原油加工输入：月计划油种 + 到货计划(JSONB) + 库存约束 + 成本
- **production_plan_details** — 排产按天明细：LP 求解产出，含 daily_input / blend_detail(JSONB) / 装置负荷率
- **scheduling_tasks** — 排产任务状态：plan_id → draft/generated/locked 状态机

> DDL 定义见 `solve_v1/backend/data/db.py` 的 `_DDL` 常量（应用启动时 `init_db()` 幂等建表）。
> Excel → DB 的字段映射/转换规则见 `solve_v1/backend/migrate_excel_to_db.py` 文件头注释。
