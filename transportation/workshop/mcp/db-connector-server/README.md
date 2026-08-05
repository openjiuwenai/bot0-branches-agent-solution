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
