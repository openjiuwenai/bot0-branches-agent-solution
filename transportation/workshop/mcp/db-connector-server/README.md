# db-connector-server — 数据库连接 MCP 服务

## 概述

将 [`db-connector`](../../tools/db-connector/) 工具暴露为标准 MCP（Model Context Protocol）服务，供 Agent 运行时或外部客户端调用。

本服务复用 `db-connector` 的全部安全能力（参数化执行、注入防护、危险语句拦截、凭证双模式、审计日志、表结构导入），MCP 层仅做协议适配，不绕过任何安全校验。

## 工具清单

| MCP 工具 | 说明 |
|----------|------|
| `query` | 执行参数化 SELECT，返回结构化结果集 |
| `insert` | 执行参数化 INSERT，返回影响行数 / 主键 |
| `update` | 执行参数化 UPDATE，返回影响行数 |
| `delete` | 执行参数化 DELETE，返回影响行数 |
| `ping` | 数据库健康检查 |
| `import_schema` | 导入/反射已有表结构 |
| `refresh_schema` | 刷新表结构缓存 |

## 黑盒功能说明

> 以下从外部调用者视角描述每个 MCP 工具的输入输出契约，无需了解内部实现即可正确调用。所有工具均经 `db-connector` 安全栈校验，非法请求将返回错误响应而非执行。

### 通用约定

- **SQL 模板参数**：统一使用 `%s` 占位符（PostgreSQL 同样适用，由底层方言适配），禁止字符串拼接
- **参数列表**：与占位符顺序一致，由底层做类型绑定，杜绝注入
- **错误响应**：所有工具失败时返回 `{"status": "error", "error_code": "...", "message": "...", "audit_id": "..."}`，不抛出异常给 MCP 客户端
- **审计 ID**：每次调用都带 `audit_id`，可用于追溯完整调用链

### 1. `query` — 参数化查询

**用途**：执行只读 SELECT，返回结构化结果集。

**入参**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sql_template` | string | 是 | 带 `%s` 占位符的 SQL，如 `SELECT id, name FROM users WHERE age > %s` |
| `params` | list | 否 | 占位符参数列表，如 `[18]`；无占位符时传 `[]` 或 `null` |
| `max_rows` | int | 否 | 最大返回行数，缺省用配置 `max-rows` |

**返回**：

```json
{
  "status": "ok",
  "audit_id": "q-20260805-001",
  "columns": ["id", "name"],
  "rows": [[1, "张三"], [2, "李四"]],
  "row_count": 2,
  "truncated": false,
  "duration_ms": 23
}
```

**黑盒行为**：
- 仅允许 SELECT 语句，非 SELECT 被拦截
- 表名/列名必须命中 `allowed-tables` 白名单
- `truncated=true` 表示实际行数超过 `max_rows` 被截断
- 空结果集返回 `rows: []`、`row_count: 0`

### 2. `insert` — 插入数据

**用途**：执行 INSERT，返回影响行数与自增主键。

**入参**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sql_template` | string | 是 | 带 `%s` 占位符的 INSERT 语句 |
| `params` | list | 否 | 占位符参数列表 |

**返回**：

```json
{
  "status": "ok",
  "audit_id": "i-20260805-001",
  "affected_rows": 1,
  "last_inserted_id": 1024,
  "duration_ms": 15
}
```

**黑盒行为**：
- `mode=readonly` 时直接拒绝，返回 `error_code: "MODE_FORBIDDEN"`
- `mode=readwrite` 允许，`mode=ddl` 也允许
- 必须命中表白名单，且为单条 INSERT（禁止多语句堆叠）
- 无自增主键时 `last_inserted_id` 为 `null`

### 3. `update` — 更新数据

**用途**：执行 UPDATE，返回影响行数。

**入参**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sql_template` | string | 是 | 带 `%s` 占位符的 UPDATE 语句 |
| `params` | list | 否 | 占位符参数列表 |

**返回**：

```json
{
  "status": "ok",
  "audit_id": "u-20260805-001",
  "affected_rows": 3,
  "duration_ms": 18
}
```

**黑盒行为**：
- `mode=readonly` 时拒绝
- **强制要求 WHERE 子句**，无 WHERE 的全表更新被拦截（防止误操作）
- 必须命中表白名单

### 4. `delete` — 删除数据

**用途**：执行 DELETE，返回影响行数。

**入参**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sql_template` | string | 是 | 带 `%s` 占位符的 DELETE 语句 |
| `params` | list | 否 | 占位符参数列表 |

**返回**：

```json
{
  "status": "ok",
  "audit_id": "d-20260805-001",
  "affected_rows": 1,
  "duration_ms": 12
}
```

**黑盒行为**：
- `mode=readonly` 时拒绝
- **强制要求 WHERE 子句**，无 WHERE 的全表删除被拦截
- `DROP`/`TRUNCATE` 等关键字被黑名单拦截，不属于 DELETE 工具能力
- 必须命中表白名单

### 5. `ping` — 健康检查

**用途**：探测数据库连通性与延迟，不执行业务 SQL。

**入参**：无

**返回**：

```json
{
  "status": "ok",
  "audit_id": "p-20260805-001",
  "healthy": true,
  "latency_ms": 8,
  "db_version": "MySQL 8.0.32",
  "mode": "readonly"
}
```

**黑盒行为**：
- 始终允许调用，不受 `mode` 限制
- `healthy=false` 时 `latency_ms` 可能为 `null`
- 用于 Agent 启动时探活与运行时巡检

### 6. `import_schema` — 导入表结构

**用途**：反射客户已有表结构，生成字段映射快照，供 Skill 做字段对齐。

**入参**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `tables` | list[string] | 否 | 指定表名列表；缺省导入 `allowed-tables` 全部表 |

**返回**：

```json
{
  "status": "ok",
  "audit_id": "s-20260805-001",
  "imported_tables": [
    {
      "name": "highway_traffic_flow",
      "columns": [
        {"name": "station_code", "type": "VARCHAR(32)", "nullable": false},
        {"name": "vehicle_count", "type": "INT", "nullable": false},
        {"name": "plate_no", "type": "VARCHAR(16)", "nullable": true, "sensitive": true}
      ],
      "primary_key": ["station_code", "stat_time"]
    }
  ],
  "snapshot_path": "schema-snapshot.json",
  "truncated": false
}
```

**黑盒行为**：
- `sensitive-columns` 命中的列会被标记 `sensitive: true`，但不影响导入
- 导入结果持久化到 `schema-snapshot.json`，供后续 Skill 加载
- 表名必须命中 `allowed-tables` 白名单，否则该表被跳过并在响应中标注
- `truncated=true` 表示实际表数超过配置上限

### 7. `refresh_schema` — 刷新表结构缓存

**用途**：客户表结构变更后，重新反射并覆盖快照。

**入参**：无

**返回**：

```json
{
  "status": "ok",
  "audit_id": "r-20260805-001",
  "refreshed_tables": ["highway_traffic_flow", "highway_toll_station"],
  "snapshot_path": "schema-snapshot.json",
  "duration_ms": 142
}
```

**黑盒行为**：
- 等价于 `import_schema(tables=allowed-tables)` 并覆盖原快照
- 不影响已建立的连接池，仅更新元数据缓存
- 用于 DDL 变更后或定期巡检场景

### 错误码速查

| error_code | 触发场景 | 处置建议 |
|------------|----------|----------|
| `MODE_FORBIDDEN` | 当前 mode 不允许该操作 | 调整配置 `mode` 为 `readwrite` / `ddl` |
| `TABLE_NOT_ALLOWED` | 表不在白名单 | 在 `allowed-tables` 中追加表名 |
| `SQL_INJECTION_SUSPECTED` | 检测到注入特征 | 检查 SQL 模板，改用参数化占位符 |
| `DANGEROUS_KEYWORD` | 命中黑名单关键字（DROP/TRUNCATE 等） | 移除危险语句，或改用专用工具 |
| `MISSING_WHERE_CLAUSE` | UPDATE/DELETE 缺 WHERE | 补充 WHERE 条件 |
| `STATEMENT_FINGERPRINT_BLOCKED` | 语句指纹被拉黑 | 联系管理员核查语句来源 |
| `QUERY_TIMEOUT` | 执行超时 | 调大 `query-timeout-ms` 或优化 SQL |
| `MAX_ROWS_EXCEEDED` | 结果集超限 | 传入 `max_rows` 分页或加 WHERE 收敛 |
| `CREDENTIAL_UNAVAILABLE` | 凭证解析失败 | 检查 env 变量或 Vault 连通性 |
| `DB_CONNECTION_ERROR` | 数据库连接失败 | 检查网络/数据库状态 |

## 配置

复制 [config.example.yaml](config/config.example.yaml) 为 `config.yaml`，按实际环境修改。配置项与 `db-connector` 工具一致，详见 [db-connector README](../../tools/db-connector/README.md)。

关键配置：

```yaml
openjiuwen:
  tools:
    db-connector:
      mode: readonly                 # readonly | readwrite | ddl
      data-source:
        type: mysql                  # mysql | postgresql
        host: env:DB_HOST
        # ... 凭证用 env: / vault: 前缀引用，禁止明文
      mcp:
        enabled: true
        transport: stdio             # stdio | sse | streamable-http
        name: db-connector
        version: 0.1.0
```

## 传输方式

| 传输方式 | 适用场景 | 启动命令 |
|----------|----------|----------|
| `stdio` | Agent 运行时本地调用（默认） | `python server.py config.yaml` |
| `sse` | 远程调用，Server-Sent Events | `python server.py config.yaml --transport sse --port 8080` |
| `streamable-http` | 远程调用，HTTP 流式 | `python server.py config.yaml --transport streamable-http --port 8080` |

## 部署

### 依赖安装

```bash
pip install -r requirements.txt
pip install -e ../../tools/db-connector
```

### 启动

```bash
# 方式一：直接运行
python server.py config/config.yaml

# 方式二：通过 workshop 一键脚本（后台运行）
cd ../../  # 到 workshop 目录
./start.sh
```

### 后台运行

使用 [start.sh](../../start.sh) 后台启动，日志输出到 `workshop/.logs/`，PID 记录到 `workshop/.pids/`。

## 安全要点

- 所有 SQL 执行经 `db-connector` 安全栈，MCP 层不绕过校验
- 凭证禁止明文落盘，使用 `env:` 或 `vault:` 前缀引用
- 默认 `readonly` 模式，写操作需显式配置 `readwrite`
- 全量审计日志，可追溯每次调用

## 贡献说明

遵循仓库 [贡献指南](../../../../CONTRIBUTING.md) 提交 PR。
