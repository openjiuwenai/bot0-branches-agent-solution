# 炼厂生产计划求解计算服务（calc_service）

炼厂生产计划求解计算服务，提供月度计划生成、生产预测、收率与主数据管理等能力。

- 分层架构：Flask REST API（api/calculation/data/models/rules）+ Next.js 前端 + MCP 工具层
- 三个服务端口：后端 5081、前端 5082、MCP 8766
- 基于 PostgreSQL 单一数据源（`huilian` 库 + `solve_db` schema）

## 架构总览

| 层 | 入口 | 职责 |
| --- | --- | --- |
| 求解服务 | `python -m calc_service.backend.app` | Flask REST API + 排产计算（CBC/CP-SAT），端口 5081 |
| 前端界面 | `frontend/`（Next.js 16 + React 19） | 数据管理 / 计划求解 / 生产预测 / 决策工作台，端口 5082 |
| MCP 工具层 | `backend/mcp_server/server.py` | 14 个 MCP 工具，给 Agent 提供 API 调用与数据库直连能力，端口 8766 |
| 数据资产 | PostgreSQL（外部） + `solve_db_init/` 初始化脚本 | `solve_db` schema 下的装置、产品、计划、任务等数据 |

## 目录结构

```
calc_service/
├── start_server.bat            # Windows 一键启动（后端 5081 + 前端 5082）
├── solve_db_init/              # 数据库初始化（见 solve_db_init/README.md）
│   ├── solve_db_init.sql       # pg_dump 导出（schema 含数据，默认 public）
│   ├── init_from_excel.py      # 从 refinery_data.xlsx 初始化
│   ├── seed_devices_batch.py   # 从 Excel 批量写入 devices_units
│   ├── seed_tanks_batch.py     # 从 Excel 批量写入 devices_tanks
│   └── migrate_special_var_semantic.sql  # special_var 语义迁移
├── docs/                       # MCP 接口文档
├── frontend/                   # Next.js 16 + React 19
└── backend/
    ├── app.py                  # 应用入口（创建 Flask、注册 6 个 Blueprint）
    ├── config.py               # DATABASE_URL / PRICE_API_URL / 目录常量
    ├── api/                    # 路由层
    │   ├── crud_routes.py          # 主数据 CRUD + 能源结构 + 期初罐存 + 日志配置
    │   ├── yield_routes.py         # 收率结构 + Excel 迁移
    │   ├── plan_routes.py          # 计划生成 / 综合求解 / 切换枚举
    │   ├── task_routes.py          # 排产任务查询与管理
    │   ├── price_cost_routes.py    # 价格成本代理（转发到价格模块）
    │   └── side_line_routes.py     # 侧线收率 CRUD（material_id 语义）
    ├── calculation/            # 计算层
    │   ├── economics.py                # 目标函数构建（经济项 + 惩罚项）
    │   ├── yield_resolver.py           # 装置侧线/产品收率查询（side_lines 优先）
    │   ├── flow_diagram_builder.py     # 生产流程图数据组装
    │   ├── revenue_calculator.py       # 收入计算（售价 + 自用回炼调整）
    │   ├── cost_calculator.py          # 成本计算（原油 + 外购原料）
    │   ├── direct_calculator.py        # 直接收率比例计算
    │   ├── batch_builder.py            # 多批次参数组装
    │   ├── batch_optimizer.py          # 多批次排产求解（CP-SAT）
    │   ├── combination_evaluator.py    # 方案组合评估
    │   ├── combination_optimizer.py    # 装置加工方案组合优化
    │   ├── switch_analysis.py          # 切换方案分析（收益、成本与差额）
    │   ├── economic_reporter.py        # 经济效益分析报表（对账/校验）
    │   ├── hangmei_optimizer.py        # 航煤产量目标优化
    │   └── tank_capacity_checker.py    # 罐容约束校验
    ├── rules/                  # 业务规则层
    │   ├── feasibility.py            # 可行性判定（目标值范围与边界检查）
    │   └── selection.py              # 装置侧线选择（有效方案枚举与剔除）
    ├── data/                   # 数据访问层
    │   ├── db.py                     # 连接与 DDL（启动时幂等建表）
    │   ├── refinery_repo.py          # 装置/产品/连接/能源等主数据读写
    │   └── scheduling_repo.py        # 计划输入/详情/任务等计划数据读写
    ├── models/                 # 数据模型
    │   ├── refinery.py               # 主数据结构
    │   ├── scheduling.py             # 计划与求解参数结构
    │   └── results.py                # 求解结果结构
    ├── mcp_server/             # MCP 服务层（server.py / tool_registry.py）
    ├── templates/              # 旧版单页 index.html（历史保留）
    ├── tests/                  # pytest（需 DATABASE_URL 可达）
    ├── migrate_excel_to_db.py  # Excel 8 sheet 数据迁移入口
    ├── migrate_flows.py        # material_flows 历史迁移（已冻结，勿再执行）
    ├── fix_roles.py            # 历史保留，勿再执行
    ├── e2e_test.py             # 端到端测试脚本
    └── logger.py               # 日志配置
```

## 后端分层说明

### api/ 路由层（6 个 Blueprint）

只做参数解析与响应组装，不直接写业务逻辑。

### calculation/ 计算层（14 个模块）

经济目标、收率查询、收入/成本核算、批次与组合优化、切换方案分析、罐容校验等。

### rules/ 业务规则层

可行性判定与装置侧线选择，被 calculation/ 调用。

### data/ 数据访问层

- `db.py`：连接与 DDL，启动时幂等执行
- `refinery_repo.py`：装置、产品、连接、能源、期初罐存等主数据读写
- `scheduling_repo.py`：计划输入、计划详情、排产任务读写

### models/ 数据模型层

`refinery.py` 主数据结构、`scheduling.py` 计划与求解参数、`results.py` 求解结果。

### mcp_server/ MCP 服务层

给 Agent 提供 API 调用与数据库直连能力，支持 stdio、SSE、Streamable HTTP 三种传输方式。14 个工具覆盖主数据 CRUD、原油类型管理、求解调用与系统信息。接口明细见 [docs/求解计算MCP接口文档.md](docs/求解计算MCP接口文档.md)。

### 关键约束

- `app.py` 不含路由实现，路由全部在 `api/` 的 6 个 Blueprint 中
- `api/` 不直接 import `data.db`，跨层访问走 repo
- 服务启动时 `db.py` 幂等建表（`CREATE TABLE IF NOT EXISTS`）
- 排产求解默认走 CP-SAT（ORTOOLS），不依赖 CBC

## 数据库

### 外部依赖

| 依赖 | 说明 |
| --- | --- |
| PostgreSQL | 唯一事实源，默认连接 `postgresql://huilian:huilian2026@localhost:5432/huilian` |
| 价格服务 | 价格成本页所有接口转发到 `PRICE_API_URL`（默认 `http://localhost:8000/api/v1`） |
| `crude_scheduling` Python 包 | 排产求解（`MixedOwnerPlanParams` / `solve_mixed_owner_plan`），按 CP-SAT 路径调用 |

默认连接串仅用于本地开发；生产环境必须通过 `DATABASE_URL`、`PRICE_API_URL` 环境变量注入。

### solve_db schema 表清单（db.py 启动时幂等创建，共 14 张 + public.crude_types）

| 表 | 说明 |
| --- | --- |
| devices_units | 加工装置（当前使用） |
| devices_tanks | 罐区（当前使用） |
| side_lines | 侧线映射（material_id 关联 public.crude_types） |
| device_yields | 装置-原油收率 |
| connections | 装置连接 |
| material_flows | 物料流（物流图） |
| energy | 能耗结构 |
| tank_monthly_initial | 罐月初期初库存 |
| production_plans_input | 月度计划输入 |
| production_plan_details | 月度计划详情 |
| cp_sat_plan_details | CP-SAT 计划详情 |
| scheduling_tasks | 排产任务 |
| devices | 装置主数据（旧表，保留兼容） |
| products | 产品/侧线收率（旧表，保留兼容；新数据走 device_yields + side_lines） |
| public.crude_types | 原油类型（init.sql 含 11 行种子数据） |

历史说明：侧线物性最初挂在 `product_material_mapping` 表，后收敛为 `side_lines.material_id` 关联 `public.crude_types`，`product_material_mapping` 已不再使用。

### 数据初始化

首选 `solve_db_init/solve_db_init.sql`（pg_dump 导出，含种子数据），详见 [solve_db_init/README.md](solve_db_init/README.md)。

### 种子数据锚点（init.sql 实际包含）

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
| public.crude_types | 11 |

`devices`、`products`、`cp_sat_plan_details` 在 init.sql 中只建表不灌数据。

## 运行

```powershell
# 方式一：一键启动（后端 5081 + 前端 5082）
calc_service\start_server.bat

# 方式二：分别启动
python -m calc_service.backend.app    # 在 mcp/ 目录（calc_service 的上级）下执行
cd calc_service\frontend
npm install
npm run dev                            # .env.local 已将端口固定为 5082

# 运行测试（需先配 DATABASE_URL 且数据库可达）
python -m pytest calc_service/backend/tests -q
```

`start_server.bat` 会自动切换到 `calc_service` 的上级目录后启动后端，手动运行时注意工作目录相同。

## 前端

React 19 + Next 16（`frontend/`），`.env.local` 固定 `PORT=5082`、`NEXT_PUBLIC_API_BASE=http://localhost:5081`。

| 页面 | 路径 | 功能 |
| --- | --- | --- |
| / | `/` | 数据管理（装置/产品/连接/能源/期初罐存/原油类型） |
| /config | `/config` | 求解配置（计划月份、提交模式、批次参数） |
| /decision | `/decision` | 决策工作台（物流图 + 资源边界 + 收率/价格维护） |
| /predict | `/predict` | 生产预测（齐套校验 + 集成求解） |
| /price-cost | `/price-cost` | 价格成本（重定向到 `/config`） |

后端接口挂载在 `/api/*`，前端通过 `NEXT_PUBLIC_API_BASE` 访问。

## API 一览

以下为高频入口（完整路由见 `backend/api/` 各文件，共 50+ 个）。

### 主数据 CRUD（crud_routes.py）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/devices` | 装置列表查询/新增 |
| PUT/DELETE | `/api/devices/<id>` | 装置修改/删除 |
| GET/POST | `/api/products` | 产品/侧线收率查询/新增 |
| PUT/DELETE | `/api/products/<id>` | 产品/侧线收率修改/删除 |
| GET/POST | `/api/connections` | 装置连接查询/新增 |
| PUT/DELETE | `/api/connections/<id>` | 装置连接修改/删除 |
| GET/POST | `/api/energy_consumptions` | 能耗结构查询/新增 |
| PUT/DELETE | `/api/energy_consumptions/<id>` | 能耗结构修改/删除 |
| GET | `/api/md_devices` | 装置下拉列表（主数据辅助） |
| GET | `/api/material_flows` | 物流图（装置/罐区/连接） |
| GET/PUT | `/api/tank_monthly_initial` | 罐月初期初库存 |
| GET/POST/PUT/DELETE | `/api/crude_types` | 原油类型 CRUD |
| POST | `/api/log-config` | 运行时调整日志级别 |

### 计划/任务（plan_routes.py、task_routes.py）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/scheduling/init_data` | 按计划月份初始化计划输入（期初罐存 + 目标） |
| POST | `/api/scheduling/generate_plan` | 生成月度计划（异步任务） |
| GET | `/api/scheduling/generate_plan_status/<task_id>` | 计划生成状态 |
| GET | `/api/scheduling/plans` | 计划列表 |
| GET | `/api/scheduling/plan/<plan_id>` | 计划详情 |
| POST | `/api/scheduling/comprehensive_solve` | 集成求解（生产预测） |
| GET | `/api/scheduling/hangmei_target` | 航煤目标优化 |
| POST | `/api/scheduling/optimize_valve` | 阀门/参数优化 |
| POST | `/api/scheduling/enumerate_switches` | 切换方案枚举 |

### 收率（yield_routes.py）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/migrate` | Excel 数据迁移到 solve_db |
| GET | `/api/migrate/status` | 迁移状态 |
| GET | `/api/yield_analysis` | 收率结构分析 |
| GET/PUT | `/api/yield_bounds` | 收率上下限维护 |
| POST | `/api/yield_analysis/import` | 收率结构导入 |

### 价格成本（price_cost_routes.py）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST/PUT/DELETE | `/api/price-cost/*` | 转发到外部价格服务（`PRICE_API_URL`） |

### 侧线收率（side_line_routes.py）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/side_lines` | 侧线收率查询/新增（material_id 关联原油类型） |
| PUT/DELETE | `/api/side_lines/<id>` | 侧线收率修改/删除 |

## 验证方法

```powershell
# 后端健康检查
curl http://localhost:5081/api/health

# 主数据（行数取决于种子数据，见上文锚点表）
curl http://localhost:5081/api/devices
curl http://localhost:5081/api/products
curl http://localhost:5081/api/connections
curl http://localhost:5081/api/crude_types

# 前端
curl http://localhost:5082/
```

## 版本兼容性

Python 3.10+ 环境（`backend/requirements.txt`）已通过以下版本组合验证：

- openpyxl 3.1.2 / 3.1.5（涉及 Excel 迁移）
- numpy 2.2 / 2.4（pandas 2.3 兼容）
- pandas 2.3.x
- ortools 9.15.x（CP-SAT）
- psycopg2-binary 2.9.x

## 已知坑点

1. `pytest -q` 会挂起（import 时触发 DB 连接），必须用 `python -m pytest calc_service/backend/tests -q`。
2. `db.py` 与 `init.sql` 的表结构曾经不一致（`tank_monthly_initial` 只有 DDL 没有种子数据）；当前 init.sql 已包含该表及 37 行种子数据。
3. `backend/mcp_server` 与 `data_service/mcp` 的 MCP 服务默认绑定 `0.0.0.0`，仅限内网；暴露公网必须加鉴权。
4. 前端端口改到 5081 以外时，必须同步改 `.env.local` 的 `PORT` 与 `NEXT_PUBLIC_API_BASE`。
5. 数据初始化优先走 `solve_db_init/solve_db_init.sql`；`backend/migrate_excel_to_db.py` 仅在需要重建 Excel 源数据时使用（`refinery_data.xlsx` 未随仓库入库，需另行获取，期望路径可用 `REFINERY_XLSX_PATH` 环境变量覆盖）。
6. `fix_roles.py` 是历史遗留脚本，当前角色已并入 `db.py` 迁移，执行它会报 `column "role" does not exist`，勿再使用。
7. 价格数据不在本地：calc_service 不维护 `price_cost` 表，所有价格通过 `PRICE_API_URL` 从外部价格服务获取。
