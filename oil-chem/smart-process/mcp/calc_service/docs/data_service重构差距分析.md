# data_service 重构差距分析

> 分析日期：2026-08-06
> 分析对象：`mcp/data_service/`
> 重构目标：**API 接口和 MCP 接口对外归一，内部到底层的调用直接调用数据库，和其他目录没有依赖关联**

---

## 一、当前架构总览

### 1.1 目录结构

```
mcp/data_service/
├── __init__.py              # 包说明
├── connection.py            # SQLAlchemy engine + SessionLocal + get_session()
├── pyproject.toml           # 包定义（name=data_service）
├── mcp/
│   ├── __init__.py
│   ├── server.py            # FastMCP 服务入口（MCP 协议，port 8765）
│   ├── read_tools.py        # 15 个 MCP 读工具
│   └── write_tools.py       # 13 个 MCP 写工具
├── repositories/            # 7 个只读仓库（纯函数，接收 Session）
│   ├── device_repo.py       # 装置/储罐
│   ├── side_line_repo.py    # 侧线/收率
│   ├── material_repo.py     # 物料
│   ├── mapping_repo.py      # 映射/进料配比
│   ├── price_repo.py        # 价格（含计算规则回退）
│   ├── flow_repo.py         # 物流边
│   └── crude_repo.py        # 油种
└── writers/                 # 5 个写仓库（纯函数，接收 Session）
    ├── device_writer.py     # 装置/储罐 upsert/delete/replace
    ├── side_line_writer.py  # 侧线/收率 upsert/delete/replace
    ├── price_writer.py      # 物料价格 upsert
    ├── crude_writer.py      # 油种 upsert/delete/toggle
    └── material_writer.py   # 物料 upsert/delete/依赖检查
```

### 1.2 对外暴露方式（当前两条路径）

| 消费方 | 调用方式 | 协议 | 端口 |
|---|---|---|---|
| calc_service（Flask :5081） | **Python import 直连** repositories/writers | 无（进程内调用） | — |
| 主后端 FastAPI（:8000） | **Python import 直连**（data_routes.py） | 无（进程内调用） | — |
| Agent/LLM 平台 | **MCP 协议 HTTP** | streamable-http | 8765 |

### 1.3 数据访问层（已满足的部分）

- repositories/writers 全部使用 `sqlalchemy.text()` 裸 SQL，直接操作 PostgreSQL
- 无 ORM 模型，无中间服务层
- `connection.py` 统一管理 engine + SessionLocal + `get_session()` 上下文
- 跨 schema 访问：`search_path=solve_db,public`，裸表名自动命中

---

## 二、重构目标与差距逐项分析

### 目标 1：API 接口和 MCP 接口对外归一

#### 现状

**data_service 没有 HTTP REST API。** 整个目录中不存在 Flask Blueprint、FastAPI APIRouter 或任何 HTTP 路由定义。对外暴露的唯一接口是 MCP 协议端点（`http://localhost:8765/mcp`，28 个 MCP 工具）。

所谓的"API 接口"实际上分散在两个外部目录中：

| 位置 | 路由 | 调用方式 |
|---|---|---|
| `backend/app/api/data_routes.py`（主后端） | `/api/data/*` FastAPI 路由 | import data_service 的 repositories/writers |
| `mcp/calc_service/backend/api/` 下的多个路由 | `/api/*` Flask 路由 | import data_service 的 repositories/writers |

这意味着 **data_service 的 API 接口被"嵌入"到了 calc_service 和主后端的进程中**，并非 data_service 自己暴露的。

#### 差距清单

| 编号 | 差距 | 严重度 | 说明 |
|---|---|---|---|
| G1-1 | **data_service 无独立 HTTP REST API 层** | 高 | 当前只有 MCP 接口，没有自己的 HTTP 路由。REST API 功能散落在 calc_service 和主后端中 |
| G1-2 | **同一业务操作有两套入口，行为不完全对齐** | 高 | 例：`upsert_unit` 在 MCP 中接受 JSON 字符串参数，在 calc_service Flask 路由中接受 form/json 请求体，参数格式和校验逻辑各自维护 |
| G1-3 | **MCP 工具参数序列化不一致** | 中 | MCP 写工具中复杂参数用 JSON 字符串（`data: str`），简单参数用原子类型（`month: str, material_id: int`）。REST API 通常统一用 JSON body |
| G1-4 | **无统一的错误码/响应格式** | 中 | MCP 工具返回 `{"success": true, ...}` 字符串；Flask 路由返回 `jsonify({...})`；FastAPI 路由返回 dict。三者格式不完全一致 |
| G1-5 | **无统一鉴权/限流/日志中间件** | 低 | MCP 协议自带 session 管理；Flask 路由有 CORS；FastAPI 有自己的中间件。归一后需要统一 |

#### 目标状态

data_service 应自带一个 HTTP REST API 层（FastAPI 或 Flask），将 MCP 工具和 REST 路由统一到同一套 service 函数上：

```
┌─────────────────────────────────────────┐
│           data_service 对外层            │
│  ┌──────────────┐  ┌─────────────────┐  │
│  │  MCP Server  │  │  REST API Server│  │
│  │  (port 8765) │  │  (port 新增)    │  │
│  └──────┬───────┘  └────────┬────────┘  │
│         │      统一 service 层           │
│         └──────────┬──────────┘          │
│           ┌────────┴────────┐            │
│           │  repositories + writers      │
│           │  (直接 SQL → PostgreSQL)     │
│           └─────────────────┘            │
└─────────────────────────────────────────┘
```

---

### 目标 2：内部到底层的调用直接调用数据库

#### 现状

**此目标已基本满足。** repositories/writers 层全部使用 `sqlalchemy.text()` 裸 SQL 直连 PostgreSQL，没有中间缓存层、没有 ORM、没有通过 HTTP 调用其他服务的读路径。

#### 存在的细节问题

| 编号 | 差距 | 严重度 | 说明 |
|---|---|---|---|
| G2-1 | **price_repo 有进程内缓存** | 低 | `_batch_price_cache` 和 `_device_cost_cache` 是模块级 dict，不随 session 释放。单进程内有效，多进程/多实例部署时缓存不共享，可能导致价格不一致 |
| G2-2 | **price_writer 运行时 ALTER TABLE** | 低 | `_ensure_source_column()` 每次写入都执行 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`。虽幂等，但属 DDL 操作不应在运行时频繁执行 |
| G2-3 | **calc_service 写价格仍 HTTP 绕行主后端** | 高 | `mcp/calc_service/backend/api/price_cost_routes.py` 第 85-152 行，POST 路由通过 `requests.post(f"{api_base}/price/material")` 调主后端 :8000 写价格，而非直接 import `data_service.writers.price_writer`。同文件 GET 路由已改为直连 DB，读写路径不一致 |

#### 结论

data_service 自身的内部调用链路**已满足"直接调用数据库"**的要求。G2-3 是当前最突出的问题：写价格绕行主后端，需改为直接 import data_service.writers。

---

### 目标 3：和其他目录没有依赖关联

#### 现状

**data_service 自身无任何反向依赖。** 目录内 20 个 Python 文件中没有任何一个 import calc_service、backend 或 crude_run_planner 的模块。data_service 是一个纯粹的叶子包，仅依赖 sqlalchemy + psycopg2 + PostgreSQL。

#### 但存在"被依赖"关系（通过 sys.path hack 维持）

| 被依赖方 | 依赖方文件 | 依赖内容 |
|---|---|---|
| data_service.repositories.* | `mcp/calc_service/backend/data/refinery_repo.py` | 10 处 import（最重度） |
| data_service.repositories.* | `mcp/calc_service/backend/api/side_line_routes.py` | import side_line_repo, material_repo |
| data_service.repositories.* | `mcp/calc_service/backend/api/price_cost_routes.py` | import price_repo |
| data_service.repositories.* | `mcp/calc_service/backend/data/scheduling_repo.py` | import device_repo |
| data_service.repositories.* | `mcp/calc_service/backend/service/solve_service.py` | import price_repo |
| data_service.writers.* | `mcp/calc_service/backend/api/side_line_routes.py` | import side_line_writer |
| data_service.writers.* | `mcp/calc_service/backend/api/price_cost_routes.py` | import side_line_writer |
| data_service.writers.* | `mcp/calc_service/backend/data/refinery_repo.py` | import device_writer, side_line_writer, crude_writer |
| data_service.connection | `mcp/calc_service/backend/api/price_cost_routes.py` | import get_session |
| data_service.* | `backend/app/api/data_routes.py`（主后端） | import 全部 repositories + writers |
| data_service.* | `backend/app/main.py`（主后端） | sys.path 配置使 data_service 可 import |

#### sys.path hack 位置清单（共 7 处）

| 文件 | 行号 | hack 目标 |
|---|---|---|
| `mcp/calc_service/backend/app.py` | 17-19 | `mcp/` |
| `mcp/calc_service/backend/mcp_server/server.py` | 49-56 | `calc_service/` + `mcp/` |
| `mcp/calc_service/backend/api/plan_routes.py` | 413-416 | 主后端 `backend/` |
| `mcp/calc_service/solve_db_init/init_from_excel.py` | 18-19 | 仓库根 |
| `mcp/calc_service/solve_db_init/seed_devices_batch.py` | 25-26 | 仓库根 |
| `mcp/calc_service/solve_db_init/seed_tanks_batch.py` | 24-25 | 仓库根 |
| `backend/app/main.py` | 14-17 | `mcp/` |

#### 差距清单

| 编号 | 差距 | 严重度 | 说明 |
|---|---|---|---|
| G3-1 | **sys.path hack 维持跨目录 import** | 高 | calc_service 的 `app.py`、`mcp_server/server.py`、`plan_routes.py` 和主后端 `main.py` 共 7 处通过 `sys.path.insert` 将 `mcp/` 等目录加入路径。是脆弱的路径耦合，不是正式的包管理 |
| G3-2 | **写价格绕行主后端** | 高 | `price_cost_routes.py` 的 POST 路由通过 HTTP 调主后端 :8000 写价格，而非直接 import `data_service.writers.price_writer`。读路径已改为直连 DB，写路径未跟进，读写不一致 |
| G3-3 | **refinery_repo.py 是 data_service 的"影子"** | 中 | `calc_service/backend/data/refinery_repo.py` 有 10 处 import data_service，它在 calc_service 内部扮演了 data_service 代理的角色。归一后这个文件应该大幅简化或移除 |

#### 方案选型：正式包依赖（方案 B）

经对比分析，"和其他目录没有依赖关联"的真正含义是 **data_service 自身不反向依赖其他目录**（已满足），而非要求调用方不能 import 它。采用方案 B：正式包依赖。

| 方案 | 读性能 | 写性能 | 一致性 | 部署独立性 | 依赖管理 |
|---|---|---|---|---|---|
| A. HTTP 调用 | 损失大（+30-60ms/次求解） | 损失大 | 好 | 高（需 data_service 常驻） | 清晰 |
| **B. 正式包依赖（选用）** | **零损失** | **零损失** | **好** | 中（包不需常驻，MCP 进程仅给 Agent） | **清晰（pyproject.toml + pip install -e）** |
| C. 混合模式（当前） | 零损失 | 有损失（绕行主后端） | 差（读写两套路径） | 低（写依赖主后端常驻） | 脆弱（sys.path hack） |

方案 B 的关键优势：写路径归一到 data_service.writers，消除"主后端中间人"，读写统一走同一套 repositories/writers。

#### 依赖方向（目标状态）

```
calc_service ──import──→ data_service（正式包）──→ PostgreSQL
主后端       ──import──→ data_service（正式包）──→ PostgreSQL
Agent        ──MCP HTTP──→ data_service ──→ PostgreSQL

依赖管理：pip install -e mcp/data_service/
```

---

## 三、差距汇总与优先级

| 编号 | 差距描述 | 对应目标 | 严重度 | 建议优先级 |
|---|---|---|---|---|
| **G1-1** | data_service 无独立 HTTP REST API 层 | 目标1 | 高 | P0 |
| **G1-2** | 同一业务操作两套入口，行为不对齐 | 目标1 | 高 | P0 |
| **G1-3** | MCP 工具参数序列化不一致 | 目标1 | 中 | P1 |
| **G1-4** | 无统一错误码/响应格式 | 目标1 | 中 | P1 |
| **G1-5** | 无统一鉴权/限流/日志中间件 | 目标1 | 低 | P2 |
| **G2-1** | price_repo 进程内缓存多实例不一致 | 目标2 | 低 | P2 |
| **G2-2** | price_writer 运行时 ALTER TABLE | 目标2 | 低 | P2 |
| **G2-3** | calc_service 写价格仍 HTTP 绕行主后端 | 目标2 | 高 | P0 |
| **G3-1** | sys.path hack 维持跨目录 import（7 处） | 目标3 | 高 | P0 |
| **G3-2** | 写价格绕行主后端（读写路径不一致） | 目标3 | 高 | P0 |
| **G3-3** | refinery_repo.py 是 data_service 影子 | 目标3 | 中 | P1 |

---

## 四、重构建议方向

### 4.1 新增 HTTP REST API 层（解决 G1-1 / G1-2 / G1-3 / G1-4）

在 `data_service/` 下新增 `api/` 目录，引入 FastAPI（或复用现有 Flask）定义 REST 路由：

```
data_service/
├── api/
│   ├── __init__.py
│   ├── server.py            # FastAPI app 入口
│   ├── routes_devices.py    # /api/devices/*
│   ├── routes_side_lines.py # /api/side-lines/*
│   ├── routes_prices.py     # /api/prices/*
│   ├── routes_crudes.py     # /api/crudes/*
│   ├── routes_materials.py  # /api/materials/*
│   └── routes_flows.py      # /api/flows/*
├── service/                 # 统一 service 层（MCP 和 REST 共用）
│   ├── __init__.py
│   └── ...
├── mcp/                     # 现有 MCP 层（改为调用 service 层）
├── repositories/            # 现有读仓库（不变）
└── writers/                 # 现有写仓库（不变）
```

关键设计原则：
- **REST 和 MCP 共用同一套 service 函数**，确保行为一致
- REST 路由用标准 JSON body / query param，MCP 工具保持 JSON string 参数（FastMCP 约束）
- 统一响应格式：`{"success": bool, "data": ..., "error": ...}`

### 4.2 正式化包依赖 + 消除 sys.path hack（解决 G3-1）

采用方案 B：将 data_service 通过 `pyproject.toml` 正式声明为 Python 包，调用方 `pip install -e mcp/data_service/` 安装。

改造步骤：
1. 完善 `data_service/pyproject.toml`（已存在，需补充 `data_service.mcp` 子包）
2. calc_service 和主后端各自 `pip install -e mcp/data_service/`
3. 删除 7 处 `sys.path.insert` hack
4. import 语句保持不变（`from data_service.repositories import ...` 仍可用）

**不改为 HTTP 调用**——保留进程内 import 以确保零性能损失，通过正式包管理消除路径耦合。

### 4.3 写价格路径归一（解决 G2-3 / G3-2）

将 `price_cost_routes.py` 的 POST 路由从 HTTP 调主后端改为直接 import `data_service.writers.price_writer`：

改造前：
```
calc_service → HTTP → 主后端 :8000 → import data_service.writers → DB
```

改造后：
```
calc_service → import data_service.writers.price_writer → DB
```

涉及 `price_cost_routes.py` 第 85-152 行的 `save_price_cost_products()` 函数。

### 4.4 清理细节问题（解决 G2-1 / G2-2 / G3-3）

- price_repo 缓存改为带 TTL 的缓存或移除（多实例部署下不可靠）
- price_writer 的 `ALTER TABLE` 移到迁移脚本中一次性执行
- refinery_repo.py 在依赖正式化后评估是否可简化

---

## 五、当前已满足的条件（无需改动）

| 条件 | 状态 | 说明 |
|---|---|---|
| repositories/writers 直连 SQL | 已满足 | 全部 `sqlalchemy.text()` 裸 SQL |
| 无 ORM 模型 | 已满足 | 纯 dict 映射 |
| data_service 无反向依赖 | 已满足 | 不 import 任何外部项目模块 |
| 连接管理统一 | 已满足 | `connection.py` 统一 engine + SessionLocal |
| MCP 协议暴露 | 已满足 | 28 个工具（15 读 + 13 写） |
| 跨 schema 访问 | 已满足 | `search_path=solve_db,public` |
| pyproject.toml 已存在 | 已满足 | 需补充 mcp 子包声明 |

---

## 六、结论

data_service 的**底层数据访问层（repositories/writers）设计良好，已满足"直接调用数据库"和"无反向依赖"的要求**。

采用方案 B（正式包依赖）后，核心工作集中在：

1. **写价格路径归一**：`price_cost_routes.py` 从 HTTP 绕行改为直接 import `data_service.writers.price_writer`（解决 G2-3 / G3-2）
2. **消除 sys.path hack**：7 处 hack 改为 `pip install -e` 正式包依赖（解决 G3-1）
3. **新增 REST API 层**：data_service 内新增 HTTP 路由 + 统一 service 层（解决 G1 系列差距）

方案 B 保留了进程内 import 的零性能损失优势，同时通过正式包管理消除了路径耦合，读写路径统一到同一套 repositories/writers。
