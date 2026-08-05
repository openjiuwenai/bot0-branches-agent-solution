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
| 易集成 | 以 Python 包 + MCP 服务形式发布，可被 Agent 运行时直接加载 |

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

### 4.5 MCP 服务暴露

工具内置 MCP（Model Context Protocol）服务层，可对外暴露为标准 MCP 服务：

- **传输方式**：支持 `stdio` / `sse` / `streamable-http`
- **工具清单**：`query` / `insert` / `update` / `delete` / `ping` / `import_schema` / `refresh_schema`
- **安全透传**：MCP 层复用同一安全栈（参数化、黑名单、审计、脱敏），不绕过安全校验
- **独立部署**：MCP 服务可独立于 Agent 运行时部署，供多个 Agent 共享

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
openjiuwen:
  tools:
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
├── src/openjiuwen/tools/db_connector/
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
│   └── mcp/
│       ├── __init__.py
│       └── server.py                    # MCP 服务暴露
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
| `workshop/mcp/` | 本工具内置 MCP 暴露层；若需独立 MCP 网关，可在 mcp/ 中包装复用同一安全栈 |

## 9. 技术栈与依赖（Python）

- Python 3.10+
- SQLAlchemy（连接池 `QueuePool` + 方言抽象 + `inspect()` 反射）
- PyMySQL（MySQL 驱动）、psycopg[binary]（PostgreSQL 驱动）
- Pydantic v2 + pydantic-settings（配置与数据模型校验）
- PyYAML（配置文件加载）
- mcp（MCP Python SDK，服务暴露）
- hvac（HashiCorp Vault 客户端，凭证模式 B）
- （可选）cryptography（本地加密配置 AES-GCM 解密）
- pytest（测试）

> 部署形态：可独立作为 Python 库安装（`pip install -e .`），也可作为 MCP 服务独立部署。

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
| MCP 暴露 | 内置 MCP 服务层，支持 stdio/sse/streamable-http |
| 预测粒度 | 由上层 Skill 定义，本工具不限制 |

## 12. 贡献说明

遵循仓库 [贡献指南](../../../../CONTRIBUTING.md) 提交 PR。
