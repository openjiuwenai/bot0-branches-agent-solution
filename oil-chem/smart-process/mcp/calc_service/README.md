# solve_v1 — 炼化排产求解服务

炼化厂减一线阀门切换组合优化求解器：在给定月度原油到港计划与装置约束下，识别连续同油种加工批次、枚举减一线阀门切换组合（每月最多 1 次切换），结合航煤工况与装置停工，按双价格月口径评估各组合效益并选优。

`solve_v1` 是 `solve/` 模块（4275 行 God File `web_app.py`）的重写版本：自包含的前后端分离项目，存储从 Excel 迁移到 PostgreSQL（schema `solve_db`），与 `solve/` 端口隔离（5081 vs 5080）可并行运行、逐步替换。

> 架构设计、Mermaid 图表、层职责见 [ARCHITECTURE.md](./ARCHITECTURE.md)。旧版说明留存于 [README_v1_backup.md](./README_v1_backup.md)。

---

## 4A 架构总览

| A | 内容 | 说明 |
|----|------|------|
| **Architecture（架构）** | 前后端分离五层架构 | 浏览器 → Next.js(:5082) BFF → Flask(:5081) `api → service → {calculation, scheduling} → data → models` → PostgreSQL + FastAPI 价格模块(:8000)。单向依赖无环，详见 [ARCHITECTURE.md 图 1/2](./ARCHITECTURE.md) |
| **Application（应用）** | 5 个 Blueprint + 2 个 Service + 6 个前端页面 | 后端 33+ 路由（CRUD/收率/计划/任务/价格）；前端 6 页覆盖业务决策台与求解器三阶段校验（排产→批次→效益） |
| **Automation（自动化）** | CP-SAT 异步排产 + 双价格月自动选优 | `generate_plan` 异步任务（1-7 分钟）+ 3 秒轮询；`comprehensive_solve` 自动编排排产→组合→选优→罐容检测→效益拆解 |
| **Assets（资产）** | PostgreSQL `solve_db` schema（11 表）+ 油种主数据 | 装置/产品/物流/能耗/排产输入/计划明细/CP-SAT 结果/任务；价格资产外置到 FastAPI 价格模块，统一价格源 |

---

## 目录结构

```
solve_v1/
├── backend/                      # 服务端（Flask :5081，入口 python -m calc_service.backend.app）
│   ├── app.py                    #   Flask 入口：注册 5 个 Blueprint + CORS + init_db，端口 5081
│   ├── config.py                 #   全局常量中心（装置ID/收率映射/DB/价格API）
│   ├── logger.py                 #   统一日志
│   ├── refinery_data.xlsx        #   历史数据源（仅迁移脚本读一次，运行时不访问）
│   ├── api/                      #   薄路由层（5 Blueprint，只做参数解析与响应封装）
│   │   ├── crud_routes.py        #     装置/产品/物流/能耗/油种 CRUD（_build_crud 工厂）
│   │   ├── yield_routes.py       #     /product_yields + /scheduling/data
│   │   ├── plan_routes.py        #     generate_plan(异步CP-SAT) / comprehensive_solve / optimize_valve / enumerate_switches / plans / hangmei_target
│   │   ├── task_routes.py        #     排厂任务 CRUD + lock/unlock
│   │   └── price_cost_routes.py  #     产品价格代理 FastAPI + 产品-物料映射 + 原油成本
│   ├── service/                  #   编排服务层（承接 api 业务 helper，不持有 Flask）
│   │   ├── solve_service.py      #     SolveService：三流程编排 + 双价格月 + 罐容检测 + 效益拆解
│   │   └── yield_service.py      #     YieldService：收率层级 + 排厂基础数据
│   ├── calculation/              #   计算层（8 模块，纯业务无 IO）
│   │   ├── direct_calculator.py  #     calculate_direct 单批次直接计算（BFS 三次迭代）
│   │   ├── batch_optimizer.py    #     evaluate_combination / optimize_combinations + HangmeiContext/CombinationResult
│   │   ├── economics.py          #     generate_explanation / generate_summary + classify_cdu_products
│   │   ├── yield_resolver.py     #     resolve_yield_rate 统一收率选择（拓扑推断角色）
│   │   ├── hangmei.py            #     calculate_hangmei_mn 航煤工况 M/N 天数分配
│   │   ├── cost_calculator.py    #     compute_costs 进料+加工成本（加工成本取 FastAPI）
│   │   ├── revenue_calculator.py #     compute_revenue 收入侧（价格惰性查询+缓存）
│   │   └── tank_capacity_checker.py #  TankCapacityChecker 罐容段级检测
│   ├── scheduling/               #   排产层（2 文件，LP 已删除）
│   │   ├── switch_planner.py     #     ValveSwitchPlanner 批次识别+停工拆分+组合枚举（只枚举不优化）
│   │   └── device_input_calc.py  #     build_flow_topology / compute_device_inputs_by_mode（数据驱动）
│   ├── data/                     #   数据层（PostgreSQL，schema solve_db，唯一 DB 触点）
│   │   ├── db.py                 #     engine/SessionLocal/get_session/init_db（search_path=solve_db,public）
│   │   ├── refinery_repo.py      #     RefineryRepository + 油种CRUD + 价格API惰性查询
│   │   └── scheduling_repo.py    #     SchedulingRepository（plans_input/details/cp_sat_details/tasks）
│   ├── models/                   #   数据模型（dataclass）
│   │   ├── refinery.py           #     Device/Product/Connection/MaterialFlow/RefineryScenario
│   │   └── scheduling.py         #     ProductionPlansInput/Detail/SchedulingTask/DeviceCapacity
│   ├── migrate_excel_to_db.py    #   一次性迁移：Excel → solve_db（TRUNCATE+INSERT，校验行数与收率）
│   ├── migrate_flows.py          #   一次性迁移：connections → material_flows 归一化
│   ├── fix_roles.py              #   一次性修补：material_flows.material_role（历史保留）
│   ├── templates/                #   调试页面（index.html / scheduling_new.html）
│   ├── tests/                    #   单元测试（pytest，DB 事务回滚隔离）
│   └── e2e_test.py               #   端到端测试
├── frontend/                     # 独立前端（Next.js :5082，BFF 直连 5081）
│   ├── src/app/                  #   6 个页面（见下文）
│   ├── src/components/           #   AppShell/Sidebar/Topbar/EChart/SolveResult/BusinessResult 等
│   ├── src/app/api/[...path]/route.ts  # BFF catch-all 代理（maxDuration=600s）
│   └── .env.local                #   SOLVE_V1_URL=http://localhost:5081
├── solve_db_init/                # DB 初始化
│   ├── solve_db_init.sql         #   pg_dump 导出（建 schema + 11 表 + 种子数据）
│   └── init_from_excel.py        #   从 Excel 重灌（migrate_excel_to_db 薄包装）
├── ARCHITECTURE.md               # 架构设计（Mermaid 图表 + 层职责）
└── README_v1_backup.md           # 旧版说明留存
```

---

## 解决的原版问题

| # | 原版问题 | solve_v1 方案 |
|---|---------|--------------|
| 1 | `web_app.py` 4275 行 God File | 拆为 api/service/calculation/scheduling/data/models 六层 |
| 2 | v1/v2 双轨模型 + `data_adapter` 桥接 | 统一 `models/scheduling.py`，淘汰 `virtual_tank_` 前缀 |
| 3 | 批次评估循环重复 2 处 | 统一到 `batch_optimizer.evaluate_combination` / `optimize_combinations` |
| 4 | CRUD 重复 16 次 + 2 处 NameError | `_build_crud` 工厂统一生成，工厂持有 repo |
| 5 | **CRUD 删除静默失效**（`upsert_rows` 只增不删） | `save_*` 用 `ON CONFLICT DO UPDATE` + `DELETE ... NOT IN` 实现真正全量替换 |
| 6 | 原油系数表硬编码散落 3 处 | 废弃硬编码，改用 `refinery_repo.get_feed_ratio()` 从 products 表查 |
| 7 | 物料拓扑硬编码 `DEVICE_CYJQ/LYJQ/CHANG_NAMES` | 归一化 `material_flows` 表 + 数据驱动拓扑推断 |
| 8 | 价格散落本地表与多源不一致 | `price_cost` 表移除，统一来自 FastAPI 价格模块 |

---

## 后端模块

### api/ 薄路由层（5 个 Blueprint）

`crud_bp` / `yield_bp` / `plan_bp` / `task_bp` / `price_cost_bp`，均无 URL prefix（每个路由写全路径）。不写业务逻辑，`price_cost` 仅做 HTTP 代理。

### service/ 编排服务层

- **SolveService**：三流程编排（① 排产复用 DB 计划 → ② 批次+组合枚举 → ③ 效益选优 + ④ 增强），各流程产出 `PlanStage`/`SwitchStage`/`SolutionStage` dataclass；早退统一抛 `_SolveAbort`。支持双价格月、罐容段级检测、装置/产品级效益拆解。
- **YieldService**：构建收率层级（crude_type→device→operation_mode→products）与排厂基础数据（兼容旧前端 `storage_tanks` 形状）。

### calculation/ 计算层（8 模块，纯业务无 IO）

| 模块 | 职责 |
|------|------|
| `direct_calculator` | `calculate_direct` 单批次直接计算：BFS 拓扑顺序三次迭代算装置输入量 + 容量约束检查 + 利用率 |
| `batch_optimizer` | `evaluate_combination`/`optimize_combinations` 组合评估选优；`HangmeiContext`/`CombinationResult`；统一原 2 处重复 solve 循环 |
| `economics` | `generate_explanation`/`generate_summary` 效益可解释性；`classify_cdu_products` 数据驱动侧线分组 |
| `yield_resolver` | `resolve_yield_rate` 统一收率选择（拓扑推断装置角色，替代硬编码） |
| `hangmei` | `calculate_hangmei_mn` 航煤工况 M/N 天数分配 |
| `cost_calculator` | `compute_costs` 进料+加工成本（加工成本从 FastAPI `/device/cost/list` 取） |
| `revenue_calculator` | `compute_revenue` 收入侧（价格惰性查询 + scenario 缓存） |
| `tank_capacity_checker` | `TankCapacityChecker` 罐容段级检测（检测优先，不做可行性判定） |

### scheduling/ 排产层（2 文件，LP 已删除）

> 旧的 LP 排产（`planner.py` / `plan_generator.py`）已删除。排产统一走 CP-SAT（外部 `crude_scheduling` 包，未入库则 `SolveService` 复用 DB 已落盘计划）。

- **switch_planner.py** — `ValveSwitchPlanner`：批次识别（连续同油种段）→ 停工窗口应用（`apply_shutdown_windows`，按小时精度拆分子批次，带 `shutdown_intervals`）→ 组合枚举（恒为 2n 种，停工不影响 X/Y 切换）。只枚举不优化。`build_device_split_roles` 从 `material_flows` 推导装置 XY 角色。
- **device_input_calc.py** — 数据驱动装置进料计算：`build_flow_topology` / `load_yield_tables` / `compute_device_inputs_by_mode`，给慧炼收率预测提供每天各装置真实进料量。

### data/ 数据层（唯一 DB 触点）

- **db.py** — `create_engine`（`search_path=solve_db,public`）+ `init_db` 幂等建表（裸 SQL DDL + `CREATE TABLE IF NOT EXISTS`，含 KingbaseES 兼容补丁）。
- **RefineryRepository** — devices/products/material_flows/energy/product_material_mapping/tank_monthly_initial 仓库 + 油种 CRUD + `get_price_from_api`/`preload_prices` 价格惰性查询。`save_*` 用 `ON CONFLICT DO UPDATE` + `DELETE ... NOT IN`。
- **SchedulingRepository** — plans_input/details/cp_sat_details/tasks 仓库 + `device_capacity` 投影。原 RMW（load全表→改→写全表）改为单行 upsert。JSONB 显式 `CAST`。

### models/ 数据模型

- **refinery.py** — `Device`/`Product`/`Connection`/`MaterialFlow`/`EnergyConsumption`/`RefineryScenario`（聚合根，`connections` 从 `material_flows` 派生）。
- **scheduling.py** — `ProductionPlansInput`/`ProductionPlanDetail`/`SchedulingTask`/`DeviceCapacity`。

---

## 数据存储

PostgreSQL，schema `solve_db`（默认 `postgresql://huilian:huilian2026@localhost:5432/huilian`，可由 `DATABASE_URL` 覆盖）。

| 表 | 说明 | 主键 |
|----|------|------|
| `devices` | 装置与储罐（type: normal/tank/start） | `device_id` |
| `products` | 产品收率（4 个收率字段 `NUMERIC(6,4)` 小数） | `(product_id, crude_type)` |
| `material_flows` | 归一化物流边（5 种 flow_type，替代 connections） | `flow_id` |
| `connections` | 旧连接表（保留兼容，运行时不走） | `connection_id` |
| `energy` | 装置能耗系数 | `id` |
| `product_material_mapping` | product→material 反查（对接价格模块） | `product_id` |
| `tank_monthly_initial` | 中间罐月初容量（仅 db.py DDL，init.sql 无） | `(tank_id, year_month)` |
| `production_plans_input` | 排产输入（到港计划 JSONB + 原油成本） | `(planned_month, crude_type_id)` |
| `production_plan_details` | 客户实际排产明细（blend_detail/crude_stock_status JSONB） | `id` |
| `cp_sat_plan_details` | CP-SAT 求解结果（与客户实际排产隔离） | `id` |
| `scheduling_tasks` | 排厂任务（时间列 TIMESTAMPTZ） | `plan_id` |
| `public.crude_types` | 油种主数据（11 行种子） | `crude_type_id` |

**价格数据不在本地**：产品价格与装置加工成本通过 HTTP 取 FastAPI 价格模块（`:8000`），`RefineryRepository` 用 `preload_prices` 批量预加载 + `get_price_from_api` 惰性查询两级缓存。原油成本仍在 `production_plans_input.cost`，能耗系数在 `energy` 表。

---

## 前端（frontend/）

自包含的 Next.js 项目（React 19 + Next 16 + Tailwind + echarts + @xyflow/react + dagre），不依赖主前端 `frontend/`、不依赖主后端 `backend/`，为求解器单独提供 UI。BFF 代理 `src/app/api/[...path]/route.ts` 把浏览器 `/api/*` 透传到 `${SOLVE_V1_URL}/api/*`（`maxDuration=600s`，适配 CP-SAT 长请求）。

### 页面（src/app/）

侧栏分两段：**业务视图**（面向生产规划人员）+ **开发校验**（求解器三阶段调试页）。

| 路由 | 页面 | 用途 |
|------|------|------|
| `/` | 排产求解 | 调用 CP-SAT 排产（异步任务 + 3 秒轮询），展示分阶段流水线、批次甘特图、罐区库存趋势、按天排产明细 |
| `/decision` | 效益决策台 | 面向生产规划人员的业务决策视图：决策结论 → 效益拆解 → 排产执行 → 航煤工况 → 假设说明 |
| `/batches` | 批次划分与切换组合 | 批次识别 + 减一线切换组合枚举（2n 种）+ 轻量容量校验 + 全装置数字孪生流程图 |
| `/predict` | 效益预测 | 综合求解（排产 + 组合枚举 + 效益选优），含组合对比/罐容检测/装置损益汇总 |
| `/config` | 基础配置 | 9 个 Tab：油种/装置/储罐/收率/物料流向/产品映射/产品价格/原油成本/能耗系数 |
| `/price-cost` | 重定向 | 4 行代码 `redirect('/config')` |

### 共享组件（src/components/）

`AppShell`（外壳）/`Sidebar`（深色侧栏）/`Topbar`（面包屑+引擎状态）/`EChart`（echarts 动态加载）/`SolveResult`（预测页结果，开发者视角）/`BusinessResult`（决策台结果，业务视角）/`HangmeiPanel`（航煤输入）/`ShutdownPanel`（停工声明）。

### 配置页组件（src/app/config/components/）

`FlowDiagram`（@xyflow/react + dagre 物料流向图）/`MaterialFlowTable`/`YieldTable`/`DeviceTable`/`CrudeTypeTable`/`CrudeCostTable`/`ProductPriceTable`/`EnergyTable`/`MappingTable`/`EmptyHint`。

---

## API 一览

### CRUD（crud_routes）
- `GET/POST /api/devices`、`PUT/DELETE /api/devices/<id>`
- `GET/POST /api/products`、`PUT/DELETE /api/products/<id>`（复合主键 `<product_id>~<crude_type>`）
- `GET/POST /api/material_flows`、`PUT/DELETE /api/material_flows/<id>`
- `GET/POST /api/connections`、`PUT/DELETE /api/connections/<id>`（保留兼容）
- `GET/POST /api/units`、`PUT/DELETE /api/units/<id>`（装置，复用 devices）
- `GET/POST /api/tanks`、`PUT/DELETE /api/tanks/<id>`（储罐，复用 devices）
- `GET/POST /api/energy_consumptions`、`PUT/DELETE /api/energy_consumptions/<id>`
- `GET/POST /api/crude_types`、`PUT/DELETE /api/crude_types/<id>`（default 不可删）
- `GET /api/md_devices`（慧炼主数据装置）
- `GET/PUT /api/tank_monthly_initial`（?year_month=）
- `POST /api/log-config`

### 收率与基础数据（yield_routes）
- `GET /api/scheduling/product_yields` — crude_type→device→operation_mode→products 层级
- `GET /api/scheduling/data` — 排厂基础数据（兼容旧前端 storage_tanks 形状）

### 计划与求解（plan_routes）
- `POST /api/scheduling/generate_plan` — 启动 CP-SAT 排产（异步，返回 task_id；同月份运行中拒绝）
- `GET /api/scheduling/generate_plan_status/<task_id>` — 轮询排产任务状态
- `POST /api/scheduling/comprehensive_solve` — 排产 + 阀门组合 + 效益选优（双价格月、航煤、停工）
- `POST /api/scheduling/optimize_valve` — 基于已有计划的阀门切换优化
- `POST /api/scheduling/enumerate_switches` — 仅批次划分 + 组合枚举（不评估效益）
- `POST /api/scheduling/build_inputs` — 从慧炼计划库实时构造排产输入
- `GET /api/scheduling/hangmei_target` — 月份航煤目标产量
- `GET /api/scheduling/plans` — 历史计划列表
- `GET /api/scheduling/plan/<plan_id>` — 单计划明细
- `POST /api/scheduling/init_data` — 兼容入口（未实现）

### 任务管理（task_routes）
- `GET/POST /api/scheduling/tasks`
- `GET/PUT/DELETE /api/scheduling/tasks/<plan_id>`（已锁定拒绝修改/删除）
- `POST /api/scheduling/tasks/<plan_id>/lock`、`/unlock`

### 价格（price_cost_routes，代理 FastAPI）
- `GET/POST /api/price_cost/products`（?month=，产品价格）
- `GET/POST /api/price_cost/mapping`（产品-物料映射）
- `GET/POST /api/price_cost/crude`（原油成本，本地表）

> 不含原 `/api/solve` 三模式接口（已砍）。

---

## 运行

### 1. 后端（:5081）

```bat
rem 方式一：独立脚本（推荐）
solve_v1\start_server.bat

rem 方式二：手动
set SOLVER_PORT=5081
python -m calc_service.backend.app
```

依赖：`flask flask-cors sqlalchemy psycopg2-binary`（与主项目 backend 共用 env）。
存储：PostgreSQL，schema `solve_db`（见 `backend/config.py` 的 `DATABASE_URL`）。
价格：FastAPI 价格模块（`PRICE_API_URL`，默认 `http://localhost:8000/api/v1`）。

### 2. 前端（:5082）

```bat
cd solve_v1\frontend
npm install
npm run dev
rem 浏览器打开 http://localhost:5082
```

`.env.local`：`SOLVE_V1_URL=http://localhost:5081`。

### 3. 首次部署：迁移历史数据

```bat
rem 建 schema + 表 + 从 Excel 灌数据（仅一次）
python -m calc_service.backend.migrate_excel_to_db

rem 或用 init.sql 灌种子数据
psql -f solve_v1\solve_db_init\solve_db_init.sql
```

迁移脚本会 `init_db()` 建表 → 单事务 `TRUNCATE` 7 张表 → 批量 INSERT（收率 Excel 百分数 ÷100 → DB 小数），并校验行数锚点（devices=15, products=116, connections=27, energy=72, plans_input=3, plan_details=108, tasks=1）。

### 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `SOLVER_PORT` | `5081` | 后端端口（与 solve/ 的 5080 隔离） |
| `DATABASE_URL` | `postgresql://huilian:huilian2026@localhost:5432/huilian` | PostgreSQL 连接串 |
| `PRICE_API_URL` | `http://localhost:8000/api/v1` | FastAPI 价格模块地址 |
| `PRICE_API_TIMEOUT` | `5` | 价格模块请求超时（秒） |
| `SOLVE_V1_URL` | `http://localhost:5081` | 前端 BFF 代理目标 |

---

## 验证

### 冒烟测试（GET 端点对真实 DB）

- `/api/health` → 200
- `/api/devices` → 15 个装置
- `/api/crude_types` → 11 种原油
- `/api/material_flows` → 物流边列表
- `/api/scheduling/data` → 含 crude_types/storage_tanks/arrival_plans/device_capacity/production_plans_input
- `/api/scheduling/product_yields` → crude_types 层级
- `/api/scheduling/plans` → 历史计划

### 写入路径回归（pytest，DB 事务回滚隔离，不污染 solve_db）

- 装置/能耗 DELETE：行数 -1，原 NameError 消失
- 产品 PUT：命中行收率更新、未命中行收率不变（无二次缩放）
- 任务 lock/unlock：状态正确翻转
- CRUD 删除：`ON CONFLICT + DELETE NOT IN` 真正全量替换（修复原版静默失效）

```bat
rem 单元测试（DB 事务回滚隔离，savepoint 模式不污染数据）
python -m pytest solve_v1/backend/tests -v

rem 端到端测试（Flask test client + DB 事务回滚）
python -m calc_service.backend.e2e_test
```

---

## 已知坑点

1. **CP-SAT 包未入库**：`crude_scheduling` 包以 try/except 容错导入，未提交时 `generate_plan` 失败、`comprehensive_solve` 退化为复用 DB 已落盘计划（提示"请先在排产求解页运行 CP-SAT"）。
2. **`tank_monthly_initial` 表只在 `db.py` DDL 里**，不在 `solve_db_init.sql` dump 里——老库需跑一次 `init_db()` 补上。
3. **`plan_month` 形如 `PLAN-202601`**（带前缀），前端 `normalizeMonth()` 正则归一为 `2026-01`。
4. **`DeviceCapacity` 字段映射非直觉**：`daily_max_input ← devices.safety_stock_thrd`（不是 `max_capacity` 列），与评估层 `Device.effective_capacity` 口径不同（有意设计）。
5. **双价格月机制**：`comprehensive_solve` 用上月价选方案、本月价核算效益，前端 decision/predict 页有双价格月对比横幅。
6. **`fix_roles.py` 为历史保留**：引用了已不存在的 `flow_type='intermediate'`，当前 `material_flows` 已统一为 `tank_to_target`，运行无效。
