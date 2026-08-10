# db-connector — 数据库连接 Agent 工具

> 状态：**设计已确认**，进入代码生成阶段。

## 1. 概述

`db-connector` 是面向公路 AI 场景的精品 Agent 工具，为 Agent 运行时提供**安全、可控、可审计**的数据库连接与 SQL 执行能力。客户部署后可通过配置服务器地址、用户名、密码等信息连接到目标数据库，并执行经安全校验的增删改查（CRUD）操作。

该工具是上层 Skill（如 `traffic-forecast-qa` 流量预测智能问数）访问业务数据的「底座」，所有数据访问统一经过此处封装的安全通道。同时以 MCP 服务形式对外暴露，供外部 Agent 运行时调用。

## 2. 设计目标

| 目标 | 说明 |
|------|------|
| 安全性 | 默认拒绝、参数化执行、注入防护、危险语句拦截、凭证不落盘 |
| 可控性 | 只读/读写模式开关、表级/列级权限、行数与超时限制 |
| 可审计 | 全量 SQL 执行审计日志，可追溯谁在何时执行了什么 |
| 可扩展 | 通过方言接口支持多种数据库，新增数据库类型无需改动上层 |
| 易集成 | 以 Python 包 + 独立 HTTP 服务 + MCP 服务三种形态发布，可被 Agent 运行时直接加载 |

## 3. 支持的数据库

首批支持（基于 Python DBAPI 驱动）：

- **MySQL**（8.x+，PyMySQL）
- **PostgreSQL**（14+，psycopg）

预留方言扩展接口，后续可接入：

- Oracle / 达梦 / openGauss / 人大金仓等国产数据库
- SQLite（仅用于本地测试）

## 4. 核心能力

### 4.1 连接管理

- 支持配置项：`host`、`port`、`database`、`username`、`password`、`schema`
- 连接池（SQLAlchemy `QueuePool`）：最小空闲、最大连接、连接超时、空闲回收
- 健康检查与自动重连
- 连接凭证运行期注入，**不写入仓库、不写入日志**

### 4.2 SQL 执行

| 能力 | 说明 |
|------|------|
| 查询（SELECT） | 默认开启，返回结构化结果集（列名 + 行数据） |
| 插入（INSERT） | 读写模式下开启，返回影响行数 / 主键 |
| 更新（UPDATE） | 读写模式下开启，返回影响行数 |
| 删除（DELETE） | 读写模式下开启，默认需二次确认，返回影响行数 |
| DDL（CREATE/ALTER/DROP） | 默认禁止，需显式开启 `allow-ddl` 且仅限受信部署 |

### 4.3 参数化执行

- 所有带业务参数的 SQL 必须**参数化**（`%s` / `?` 占位），禁止字符串拼接
- 工具不直接执行用户原始 SQL 字符串，统一通过「语句模板 + 参数」形式调用
- 底层始终使用 DBAPI `cursor.execute(sql, params)`，杜绝字符串拼接

### 4.4 表结构导入与反射

客户通常已有业务表，工具支持**导入已有表结构**，免去手工配置：

- **自动反射**：通过 SQLAlchemy `inspect()` 读取目标库的表、列、类型、主键、注释
- **选择性导入**：按 `allowed-tables` 白名单导入，未授权表不加载
- **字段口径映射**：导入后生成 `schema-snapshot.json`，记录表/列/类型/注释，供上层 Skill 做字段映射
- **脱敏标注**：导入时支持标注敏感列（如手机号、车牌），查询返回时自动脱敏
- **缓存与刷新**：导入结果可缓存，支持手动 `refresh_schema()` 增量刷新
- **只读元数据**：反射仅读取 `information_schema`，不修改库结构

### 4.5 服务暴露

本工具支持两种独立服务部署形态，共享同一安全栈：

**形态 A：独立 HTTP 服务（FastAPI，本工具自带）**

- **协议**：RESTful HTTP（GET / POST）
- **接口**：`/config`(GET/POST) / `/ping` / `/query` / `/insert` / `/update` / `/delete` / `/import-schema` / `/refresh-schema`
- **首次使用**：必须先 `POST /config` 提交真实数据库信息，否则 CRUD 返回 428
- **安全透传**：HTTP 层复用本工具安全栈（参数化、黑名单、审计、脱敏），不绕过校验
- **服务地址**：`http://<host>:<port>/db-connector`
- **示例**：`http://100.100.135.219:7087/db-connector`

**形态 B：MCP 服务（workshop/mcp/db-connector-server/ 部署）**

- **协议**：MCP（Model Context Protocol）
- **传输方式**：`stdio` / `sse` / `streamable-http`
- **工具清单**：`query` / `insert` / `update` / `delete` / `ping` / `import_schema` / `refresh_schema`
- **安全透传**：MCP 层复用本工具同一安全栈，不绕过校验
- **服务地址**：`http://<host>:<port>/db-connector-server`
- **示例**：`http://100.100.135.219:7087/db-connector-server`

部署详情见 [§13 部署](#13-部署)。

## 5. 安全设计（核心）

### 5.1 SQL 注入防护

1. **强制参数化**：执行入口仅接受 `sql_template`（含 `%s` / `?` 占位符）+ `params`（有序参数），底层始终使用 `cursor.execute(sql, params)`，杜绝字符串拼接。
2. **白名单校验**：对表名、列名等标识符（无法参数化的部分）进行正则白名单校验（`^[A-Za-z_][A-Za-z0-9_]{0,63}$`），拒绝含特殊字符的标识符。
3. **语句指纹**：对 SQL 模板做归一化哈希，仅允许预登记的模板执行（可选开关，开启后进入「只允许白名单 SQL」模式）。
4. **方言关键字过滤**：拦截注释注入（`--`、`/* */`）、堆叠注入（`;` 后接第二条）等常见手法。

### 5.2 危险操作拦截

内置黑名单规则（可配置），命中即拒绝并记录审计：

- `DROP`、`TRUNCATE`、`SHUTDOWN`、`GRANT`、`REVOKE`、`LOAD_FILE`、`INTO OUTFILE`、`xp_cmdshell` 等
- 无 `WHERE` 条件的 `UPDATE` / `DELETE`（防止全表误操作）
- 影响行数超过阈值（默认 1000）的写操作

### 5.3 权限与模式

- `mode: readonly`（默认）：仅允许 SELECT，适合数据分析类 Skill
- `mode: readwrite`：允许 INSERT/UPDATE/DELETE，需在受信环境显式配置
- `mode: ddl`：允许 DDL，仅限运维场景
- 表级白名单：`allowed-tables` / `blocked-tables`，限制可访问范围

### 5.4 凭证安全（双模式）

支持两种凭证来源，由 `credential.provider` 配置选择：

**模式 A：环境变量（默认，轻量部署）**

- 凭证从环境变量注入（`DB_HOST` / `DB_USER` / `DB_PASSWORD` 等）
- 适合容器化、CI/CD 场景，凭证不落盘

**模式 B：外部密钥管理（Vault / KMS，企业级）**

- 凭证存储在 HashiCorp Vault 或云 KMS 中，工具通过 `credential.vault` 配置访问
- 运行期从 Vault 动态获取凭证，支持短期凭证轮换
- 配置项示例：`vault.addr`、`vault.mount`、`vault.path`、`vault.role`

**通用约束：**

- 配置文件中**禁止明文密码**；`password` 字段必须为占位符（`env:DB_PASSWORD` 或 `vault:secret/data/db#password`）
- 日志与异常信息中对密码、连接串做脱敏（仅保留 host:port）

### 5.5 资源与稳定性保护

- 查询超时（默认 30s）、硬超时（默认 60s）
- 结果集行数上限（默认 10000 行，防止拉全表打爆内存）
- 单次事务影响行数上限
- 连接池容量限制，防止连接耗尽

### 5.6 审计日志

每次执行记录：

```
timestamp | agent_id | principal | mode | sql_template_hash | params_summary(脱敏) |
affected_rows | duration_ms | result_status | client_ip
```

- 审计日志独立存储，只追加不可篡改
- 敏感字段值在 `params_summary` 中以 `***` 脱敏

## 6. 调用契约

### 6.1 工具加载配置（config.yaml 示例）

```yaml
db-connector:
  enabled: true
  mode: readonly                 # readonly | readwrite | ddl
  data-source:
    type: mysql                  # mysql | postgresql
    host: env:DB_HOST            # 支持 env: / vault: 前缀引用凭证
    port: 3306
    database: env:DB_NAME
    username: env:DB_USER
    password: env:DB_PASSWORD    # 运行期注入，禁止明文落盘
    schema: public
    pool:
      max-pool-size: 10
      min-idle: 2
      connection-timeout-ms: 5000
  credential:
    provider: env                # env | vault
    vault:                       # provider=vault 时生效
      addr: env:VAULT_ADDR
      mount: database
      path: traffic/db
      role: db-connector
  schema-import:
    enabled: true                # 启用表结构导入/反射
    allowed-tables: ["traffic_flow", "toll_station", "road_segment"]
    sensitive-columns: ["phone", "plate_no"]   # 自动脱敏列
    snapshot-path: ./schema-snapshot.json
  security:
    allowed-tables: ["traffic_flow", "toll_station", "road_segment"]
    blocked-keywords: ["DROP", "TRUNCATE", "SHUTDOWN"]
    max-rows: 10000
    query-timeout-ms: 30000
    allow-ddl: false
    sql-template-whitelist: false   # true 则仅允许预登记模板
  audit:
    enabled: true
    sink: file                    # file | logger | kafka
    path: ${AUDIT_LOG_DIR:/var/log/openjiuwen/db-connector}
  mcp:
    enabled: true                 # 以 MCP 服务对外暴露
    transport: stdio              # stdio | sse | streamable-http
    name: db-connector
    version: 0.1.0
```

### 6.2 Agent 调用接口（Tool 接口签名）

```python
from typing import Protocol

class DbConnectorTool(Protocol):
    """数据库连接 Agent 工具接口"""

    def query(self, sql_template: str, params: list, options: "QueryOptions | None" = None) -> "QueryResult":
        """查询，返回结构化结果集"""

    def insert(self, sql_template: str, params: list) -> "WriteResult":
        """写入（INSERT），返回主键 / 影响行数"""

    def update(self, sql_template: str, params: list) -> "WriteResult":
        """更新，返回影响行数"""

    def delete(self, sql_template: str, params: list) -> "WriteResult":
        """删除，返回影响行数（受二次确认/阈值约束）"""

    def ping(self) -> "HealthStatus":
        """健康检查"""

    def import_schema(self, tables: "list[str] | None" = None) -> "SchemaSnapshot":
        """导入/反射已有表结构"""

    def refresh_schema(self) -> "SchemaSnapshot":
        """刷新表结构缓存"""
```

> 底层统一走参数化执行（`cursor.execute(sql, params)`），禁止字符串拼接。

### 6.3 结果结构

```json
{
  "status": "ok",
  "columns": ["station_id", "ts", "flow"],
  "rows": [["S001", "2026-07-30 08:00:00", 1234]],
  "rowCount": 1,
  "truncated": false,
  "durationMs": 42,
  "auditId": "aud-20260730-0001"
}
```

## 7. 目录结构

```
db-connector/
├── README.md                      # 本设计文档
├── pyproject.toml                 # Python 包元数据（PEP 621）
├── config/
│   └── config.example.yaml        # 配置示例
├── src/db_connector/
│   ├── __init__.py
│   ├── tool.py                    # DbConnectorTool 接口与默认实现
│   ├── config.py                  # 配置加载（pydantic-settings）
│   ├── core/
│   │   ├── __init__.py
│   │   ├── connection_manager.py        # 连接池管理
│   │   └── result_mapper.py             # ResultSet -> 结构化结果
│   ├── security/
│   │   ├── __init__.py
│   │   ├── sql_sanitizer.py             # 标识符白名单校验
│   │   ├── sql_guard.py                 # 危险语句拦截 / 黑名单
│   │   ├── statement_normalizer.py      # SQL 归一化 / 指纹
│   │   └── credential_provider.py       # 凭证解密 / 注入（env + vault）
│   ├── schema/
│   │   ├── __init__.py
│   │   └── schema_importer.py           # 表结构导入/反射
│   ├── dialect/
│   │   ├── __init__.py
│   │   ├── base.py                      # Dialect 抽象基类
│   │   ├── mysql.py
│   │   └── postgresql.py
│   ├── audit/
│   │   ├── __init__.py
│   │   └── audit_logger.py
│   └── server.py                     # 独立 HTTP 服务入口（FastAPI）
└── tests/
    ├── test_sql_sanitizer.py            # 注入防护测试
    ├── test_sql_guard.py
    ├── test_schema_importer.py
    └── test_tool.py
```

## 8. 与其他资产的关系

| 目录 | 关系 |
|------|------|
| `workshop/skills/traffic-forecast-qa/` | 上游消费方：通过本工具查询历史流量数据，严禁绕过本工具直接访问数据库 |
| `methodology/` | 数据访问规范、口径定义应与 methodology 中的方法论保持一致 |
| `workshop/mcp/db-connector-server/` | MCP 服务包装层：导入本工具并暴露为标准 MCP 服务，供外部 Agent 调用 |

## 9. 技术栈与依赖（Python）

- Python 3.9+
- SQLAlchemy（连接池 `QueuePool` + 方言抽象 + `inspect()` 反射）
- PyMySQL（MySQL 驱动）、psycopg[binary]（PostgreSQL 驱动）
- Pydantic v2 + pydantic-settings（配置与数据模型校验）
- PyYAML（配置文件加载）
- hvac（HashiCorp Vault 客户端，凭证模式 B）
- （可选）cryptography（本地加密配置 AES-GCM 解密）
- **FastAPI + uvicorn**（独立 HTTP 服务形态，`pip install -e ".[server]"`）
- （可选）mcp（MCP 服务形态，`workshop/mcp/db-connector-server/` 使用）
- pytest（测试）

> 部署形态：① 纯 Python 库（`pip install -e .`）；② 独立 HTTP 服务（FastAPI，本工具自带）；③ MCP 服务（由 `workshop/mcp/db-connector-server/` 包装部署）。

## 10. 安全自检清单（上线前必过）

- [ ] 全路径无 SQL 字符串拼接
- [ ] 所有执行经 `cursor.execute(sql, params)` 参数化
- [ ] 默认 `readonly` 模式
- [ ] 危险关键字黑名单与无 WHERE 拦截已覆盖
- [ ] 凭证无明文落盘、日志已脱敏
- [ ] 审计日志可追溯、不可篡改
- [ ] 超时 / 行数上限 / 连接池上限已生效
- [ ] 通过 SQL 注入测试用例集（OWASP 风格）

## 11. 设计决策（已确认）

| 项 | 决策 |
|----|------|
| 数据库类型 | 首版仅 MySQL + PostgreSQL |
| 表结构 | 客户已有表，支持 `import_schema()` 反射导入 |
| 凭证管理 | 同时支持环境变量与 Vault/KMS 两种模式 |
| MCP 暴露 | 工具为纯库；由 `workshop/mcp/db-connector-server/` 包装为 MCP 服务，支持 stdio/sse/streamable-http |
| 预测粒度 | 由上层 Skill 定义，本工具不限制 |

## 12. 贡献说明

遵循仓库 [贡献指南](../../../../CONTRIBUTING.md) 提交 PR。

## 13. 部署

`db-connector` 支持三种部署形态：

| 形态 | 说明 | 服务地址 |
|------|------|----------|
| Python 库（嵌入式） | Skill / Agent 进程内 import 调用 | 无（进程内调用） |
| **独立 HTTP 服务**（本工具自带） | FastAPI + uvicorn，GET/POST RESTful 接口 | `http://<host>:<port>/db-connector` |
| MCP 服务（workshop/mcp 部署） | 标准 MCP 协议，stdio/sse/streamable-http | `http://<host>:<port>/db-connector-server` |

### 13.1 形态一：Python 库（嵌入式，被 Skill / Agent 调用）

适合 Skill 或 Agent 运行时在进程内直接加载，不启动独立进程。

```bash
pip install -e .
```

```python
from db_connector import DefaultDbConnectorTool
from db_connector.config import load_config

tool = DefaultDbConnectorTool(load_config('config/config.yaml'))
print(tool.ping())
print(tool.query("SELECT * FROM traffic_flow WHERE station_code = %s", ["S001"]))
```

服务地址：无（进程内调用）

### 13.2 形态二：独立 HTTP 服务（FastAPI，本工具自带）

本工具内置 FastAPI HTTP 服务入口 `db_connector.server`，可独立部署为 RESTful 数据访问微服务，暴露 GET / POST 接口。

#### 安装

```bash
pip install -e ".[server]"   # 含 fastapi + uvicorn
```

#### 启动

```bash
# 方式一：前台启动（调试）
python -m db_connector.server config/config.yaml \
    --host 0.0.0.0 --port 7087 --path /db-connector

# 方式二：后台启动（生产推荐）
./start.sh
```

#### 启停脚本

本工具自带 [start.sh](start.sh) / [stop.sh](stop.sh)，**在本目录内运行**：

```bash
# 默认：7087 端口 + /db-connector 路径
./start.sh

# 自定义：指定端口 + 路径 + 配置
DB_CONNECTOR_CONFIG=./config/config.yaml DB_CONNECTOR_PORT=7087 ./start.sh

# 停止
./stop.sh

# 查看日志
tail -f .logs/db-connector.log
```

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `DB_CONNECTOR_CONFIG` | `./config/config.example.yaml` | 配置文件路径 |
| `DB_CONNECTOR_HOST` | `0.0.0.0` | 监听地址 |
| `DB_CONNECTOR_PORT` | `7087` | 监听端口 |
| `DB_CONNECTOR_PATH` | `/db-connector` | 服务路径前缀 |

#### 部署后服务地址

- 格式：`http://<host>:<port>/db-connector`
- 示例：`http://100.100.135.219:7087/db-connector`

#### HTTP API 接口

> **首次使用流程**：启动服务 → `POST /config` 提交真实数据库信息 → `GET /ping` 验证连通 → CRUD 可用。
> 未配置时 `/ping` 返回 `status=unconfigured`，CRUD 返回 `428` 提示先 `POST /config`。
> 配置成功后写入 `config/config.runtime.yaml`，重启自动加载，无需重复配置。

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| `GET` | `/config` | 查看当前数据库配置（密码脱敏） | 无 |
| `POST` | `/config` | 提交/更新数据库配置，即时生效并持久化 | `{"type":"mysql","host":"...","port":3306,"database":"...","username":"...","password":"...","mode":"readonly","allowed_tables":[...]}` |
| `GET` | `/ping` | 数据库健康检查 | 无 |
| `POST` | `/query` | 参数化查询 | `{"sql_template": "...", "params": [...], "max_rows": N}` |
| `POST` | `/insert` | 参数化插入 | `{"sql_template": "...", "params": [...]}` |
| `POST` | `/update` | 参数化更新 | `{"sql_template": "...", "params": [...]}` |
| `POST` | `/delete` | 参数化删除 | `{"sql_template": "...", "params": [...]}` |
| `POST` | `/import-schema` | 导入表结构 | `{"tables": ["t1", "t2"]}` |
| `POST` | `/refresh-schema` | 刷新表结构缓存 | 无 |

#### 调用示例

```bash
# 0. 提交数据库配置（首次使用必做，替换为你的真实 DB 信息）
curl -X POST http://100.100.135.219:7087/db-connector/config \
  -H "Content-Type: application/json" \
  -d '{"type":"mysql","host":"100.100.135.219","port":3306,"database":"traffic","username":"root","password":"your_password","mode":"readonly","allowed_tables":["traffic_flow","toll_station"]}'

# 1. 查看当前配置（密码已脱敏）
curl http://100.100.135.219:7087/db-connector/config

# 2. 健康检查
curl http://100.100.135.219:7087/db-connector/ping

# 查询
curl -X POST http://100.100.135.219:7087/db-connector/query \
  -H "Content-Type: application/json" \
  -d '{"sql_template": "SELECT * FROM traffic_flow WHERE station_code = %s", "params": ["S001"]}'

# 插入
curl -X POST http://100.100.135.219:7087/db-connector/insert \
  -H "Content-Type: application/json" \
  -d '{"sql_template": "INSERT INTO traffic_flow (station_code, count) VALUES (%s, %s)", "params": ["S001", 1234]}'

# 导入表结构
curl -X POST http://100.100.135.219:7087/db-connector/import-schema \
  -H "Content-Type: application/json" \
  -d '{"tables": ["traffic_flow", "toll_station"]}'
```

### 13.3 形态三：MCP 服务（由 workshop/mcp/db-connector-server/ 部署）

如需以 MCP 协议对外提供（供支持 MCP 的 Agent 运行时调用），部署 [`workshop/mcp/db-connector-server/`](../../mcp/db-connector-server/) 即可。

```bash
cd workshop/mcp/db-connector-server
MCP_TRANSPORT=streamable-http MCP_PORT=7087 ./start.sh
```

**部署后服务地址**：`http://<host>:<port>/db-connector-server`
- 示例：`http://100.100.135.219:7087/db-connector-server`

详见 [workshop/mcp/db-connector-server/README.md](../../mcp/db-connector-server/README.md)。

### 13.4 部署形态对比

| 形态 | 协议 | 服务地址 | 适用场景 |
|------|------|----------|----------|
| Python 库 | 进程内调用 | 无 | Skill 访问数据、本地开发 |
| **HTTP 服务** | RESTful HTTP | `http://<host>:7087/db-connector` | 通用 HTTP 调用、前后端联调、轻量部署 |
| MCP 服务 | MCP 协议 | `http://<host>:7087/db-connector-server` | MCP 兼容 Agent 运行时、多 Agent 共享 |

## 14. 插件配置（OpenJiuwen 平台注册）

以下为 db-connector HTTP 服务在 OpenJiuwen 平台注册为插件时的配置信息，参考 [`插件配置.txt`](../../../../插件配置.txt) 格式整理。

```
# ===== db-connector 平台插件配置 =====

# ----- 插件：数据库连接工具 -----
# 服务地址：http://192.168.0.109:7087/db-connector （对应 db_connector.server，端口7087）
# 说明：首次使用必须先调用"工具0：数据库配置"提交真实 DB 信息，否则其他工具将返回 428（未配置）

# 工具0：数据库配置（首次使用入口）
# 功能：提交真实数据库连接信息，即时切换数据源并持久化到 config/config.runtime.yaml，重启后自动加载
# 请求方法：POST
# 请求路径：/config
# 输出：配置结果（status、configured、host、database、message）
# 输入参数配置：
#   type | string | Body | 必选 | 数据库类型：mysql | postgresql
#   host | string | Body | 必选 | 数据库主机
#   port | number | Body | 必选 | 端口（MySQL 3306 / PostgreSQL 5432）
#   database | string | Body | 必选 | 数据库名
#   username | string | Body | 必选 | 数据库用户名
#   password | string | Body | 必选 | 数据库密码（明文传入，GET /config 不回显）
#   schema | string | Body | 可选 | 模式名（PostgreSQL 用，默认 public）
#   mode | string | Body | 可选 | 运行模式：readonly | readwrite | ddl（默认 readonly）
#   allowed_tables | array | Body | 可选 | 允许访问的表白名单；缺省不限制

# 工具0b：查看数据库配置
# 功能：查看当前数据源配置，密码字段已脱敏（显示 ***），用于核对配置是否生效
# 请求方法：GET
# 请求路径：/config
# 输出：当前配置（configured、type、host、port、database、username、password=***、schema、mode、allowed_tables）
# 输入参数配置：无

# 工具1：数据库健康检查
# 功能：探测数据库连通性与延迟，返回状态、数据库名、延迟(ms)；不执行任何 SQL，适合探活；未配置时返回 status=unconfigured
# 请求方法：GET
# 请求路径：/ping
# 输出：健康状态（status、database、latencyMs）
# 输入参数配置：无

# 工具2：执行参数化查询
# 功能：执行参数化 SELECT 语句并返回结构化结果集，内置 SQL 注入防护、行数上限与超时控制
# 请求方法：POST
# 请求路径：/query
# 输出：查询结果（columns列名、rows数据列表、rowCount行数、truncated是否截断、durationMs耗时、auditId审计ID）
# 输入参数配置：
#   sql_template | string | Body | 必选 | 带 %s 占位符的 SELECT 语句
#   params | array | Body | 可选 | 占位符参数列表，按顺序替换 %s
#   max_rows | number | Body | 可选 | 最大返回行数，缺省用配置默认值

# 工具3：执行参数化插入
# 功能：执行参数化 INSERT 语句并返回影响行数与自增主键，内置危险语句拦截与审计
# 请求方法：POST
# 请求路径：/insert
# 输出：写入结果（status、affectedRows影响行数、lastInsertId自增主键、durationMs耗时、auditId审计ID）
# 输入参数配置：
#   sql_template | string | Body | 必选 | 带 %s 占位符的 INSERT 语句
#   params | array | Body | 可选 | 占位符参数列表，按顺序替换 %s

# 工具4：执行参数化更新
# 功能：执行参数化 UPDATE 语句并返回影响行数，强制要求 WHERE 条件，内置危险语句拦截与审计
# 请求方法：POST
# 请求路径：/update
# 输出：写入结果（status、affectedRows影响行数、durationMs耗时、auditId审计ID）
# 输入参数配置：
#   sql_template | string | Body | 必选 | 带 %s 占位符的 UPDATE 语句（必须含 WHERE）
#   params | array | Body | 可选 | 占位符参数列表，按顺序替换 %s

# 工具5：执行参数化删除
# 功能：执行参数化 DELETE 语句并返回影响行数，强制要求 WHERE 条件，内置危险语句拦截与审计
# 请求方法：POST
# 请求路径：/delete
# 输出：写入结果（status、affectedRows影响行数、durationMs耗时、auditId审计ID）
# 输入参数配置：
#   sql_template | string | Body | 必选 | 带 %s 占位符的 DELETE 语句（必须含 WHERE）
#   params | array | Body | 可选 | 占位符参数列表，按顺序替换 %s

# 工具6：导入表结构
# 功能：反射客户已有表结构，生成字段映射快照，供 NL2SQL / 脱敏标注使用
# 请求方法：POST
# 请求路径：/import-schema
# 输出：表结构快照（tables表清单、columns字段元数据、sensitiveColumns敏感列标注）
# 输入参数配置：
#   tables | array | Body | 可选 | 指定表名列表；缺省导入全部白名单表

# 工具7：刷新表结构缓存
# 功能：重新反射并覆盖已有表结构快照，用于库结构变更后同步
# 请求方法：POST
# 请求路径：/refresh-schema
# 输出：最新表结构快照（同工具6）
# 输入参数配置：无

# ----- 插件连通性测试值（端口7087）-----
# 测试0 - 工具0 提交数据库配置（首次使用必做）：
#   POST http://192.168.0.109:7087/db-connector/config
#   测试值：{"type":"mysql","host":"100.100.135.219","port":3306,"database":"traffic","username":"root","password":"your_password","mode":"readonly","allowed_tables":["traffic_flow","toll_station"]}
#   预期：code=200，返回 status=ok、configured=true、配置已持久化
#
# 测试1 - 工具1 数据库健康检查：
#   GET http://192.168.0.109:7087/db-connector/ping
#   预期：code=200，返回 status=ok、database=<库名>、latencyMs<1000
#
# 测试2 - 工具2 执行参数化查询：
#   POST http://192.168.0.109:7087/db-connector/query
#   测试值：sql_template = SELECT station_code, count FROM traffic_flow WHERE station_code = %s，params = ["S001"]
#   预期：code=200，返回 columns=[station_code, count]、rows 为站点 S001 的流量记录列表
#
# 测试3 - 工具3 执行参数化插入：
#   POST http://192.168.0.109:7087/db-connector/insert
#   测试值：sql_template = INSERT INTO traffic_flow (station_code, count) VALUES (%s, %s)，params = ["S001", 1234]
#   预期：code=200，返回 status=ok、affectedRows=1、lastInsertId 为新记录主键（执行后会真实插入数据）
#
# 测试4 - 工具4 执行参数化更新：
#   POST http://192.168.0.109:7087/db-connector/update
#   测试值：sql_template = UPDATE traffic_flow SET count = %s WHERE station_code = %s，params = [1300, "S001"]
#   预期：code=200，返回 status=ok、affectedRows=1（执行后会真实更新数据）
#
# 测试5 - 工具5 执行参数化删除：
#   POST http://192.168.0.109:7087/db-connector/delete
#   测试值：sql_template = DELETE FROM traffic_flow WHERE station_code = %s，params = ["S001"]
#   预期：code=200，返回 status=ok、affectedRows 为删除行数（执行后会真实删除数据，请谨慎测试）
#
# 测试6 - 工具6 导入表结构：
#   POST http://192.168.0.109:7087/db-connector/import-schema
#   测试值：tables = ["traffic_flow", "toll_station"]
#   预期：code=200，返回两表的字段元数据（列名、类型、是否敏感列等）
#
# 测试7 - 工具7 刷新表结构缓存：
#   POST http://192.168.0.109:7087/db-connector/refresh-schema
#   预期：code=200，返回最新表结构快照（同测试6结构）
```

> 说明：
> - 服务地址中的 `192.168.0.109` 为示例部署机 IP，实际按部署环境替换；端口与路径前缀可由 `DB_CONNECTOR_PORT` / `DB_CONNECTOR_PATH` 环境变量调整（见 [§13.2](#132-形态二独立-http-服务fastapi本工具自带)）。
> - 工具 3/4/5 会真实写入/更新/删除数据，连通性测试请使用测试库或可回滚的测试数据。
> - 所有写操作强制参数化（`%s` 占位符 + `params`），HTTP 层复用工具安全栈，不绕过 SQL 注入防护与审计。
