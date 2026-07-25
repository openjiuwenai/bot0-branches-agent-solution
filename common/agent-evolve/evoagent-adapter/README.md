# Agent Adapter

Lightweight adapter for incremental EDPAgent log collection and structured extraction.

## 概述

Agent Adapter 是一个独立部署的服务，用于：

- 增量读取 EDPAgent 日志文件（`\x01` 分隔格式）
- 解析并配对 `TAG_LLM_CALL_START` / `TAG_LLM_CALL_END` 标签
- 输出结构化 JSONL 文件（按 `conversation_id` 分组）
- 提供 HTTP API 查询采集数据
- 自动清理过期/超量输出文件

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Python | ≥3.12 | 运行时 |
| FastAPI | ≥0.115.0 | HTTP 框架 |
| uvicorn | ≥0.34.0 | ASGI 服务器 |
| pydantic-settings | ≥2.9.0 | 配置管理 |
| structlog | ≥25.0.0 | 结构化日志 |
| aiofiles | ≥24.1.0 | 异步文件读取 |
| PyYAML | ≥6.0.0 | YAML 配置解析 |
| Typer + Rich | ≥0.15.0 / ≥14.0.0 | CLI |

## 安装

### 方式一：使用 uv（推荐）

```bash
cd adapter
uv sync
```

### 方式二：使用 pip

```bash
cd adapter
pip install -e .
```

## 配置

配置可通过 **YAML 文件** 或 **环境变量** 提供。优先级：`环境变量 > YAML 文件 > 默认值`。

### YAML 配置文件

复制示例配置：

```bash
cp agent_adapter_config.yaml /path/to/config.yaml
```

编辑配置项：

```yaml
# ── Log source ──
log_dir: "logs"                          # 日志目录路径，支持绝对路径或相对于 adapter 根目录的相对路径
log_pattern: "process_*.log"             # 日志文件 glob 模式

# ── Read strategy ──
poll_interval: 5                         # 轮询间隔（秒）
start_from: "head"                       # 首次启动读取位置: tail | head

# ── Extraction rules ──
match_tags:                              # 需要提取的标签
  - "TAG_HTTP_REQUEST_START"
  - "TAG_HTTP_REQUEST_END"
  - "TAG_LLM_CALL_START"
  - "TAG_LLM_CALL_END"
  - "TAG_PLANNING_DECISION"
  - "TAG_TOOL_EXECUTE_START"
  - "TAG_TOOL_EXECUTE_END"
  - "TAG_SKILL_EXECUTE_START"
  - "TAG_SKILL_EXECUTE_END"
  - "TAG_VERSATILE_START"
  - "TAG_VERSATILE_END"

# ── Pairing strategy ──
pair_timeout: 300                        # START→END 配对超时（秒）

# ── Output ──
output_dir: "data/output"                     # 输出目录路径，支持绝对路径或相对于 adapter 根目录的相对路径
offset_file: "data/offsets.json"  # 偏移量状态文件，支持绝对路径或相对于 adapter 根目录的相对路径

# ── Output file cleanup ──
output_retention_days: 30                # 保留天数
output_max_files: 2000                   # 最大文件数
output_max_file_size: "20MB"             # 单文件大小阈值
output_trim_target_ratio: 0.7            # 截断后目标比例

# ── HTTP service ──
host: "0.0.0.0"                          # 监听地址
port: 8900                               # 监听端口
```

### 环境变量

所有配置项均可通过环境变量覆盖，使用 `ADAPTER_` 前缀：

```bash
export ADAPTER_LOG_DIR="/var/log/edpgent"
export ADAPTER_POLL_INTERVAL=10
export ADAPTER_PORT=9000
export ADAPTER_MATCH_TAGS='["TAG_LLM_CALL_START","TAG_LLM_CALL_END"]'
```

### 配置项说明

| 配置项 | 类型 | 默认值                                          | 说明 |
|--------|------|----------------------------------------------|------|
| `log_dir` | str | `logs`                                       | 日志源目录（支持绝对路径或相对于 adapter 根目录的相对路径） |
| `log_pattern` | str | `process_*.log`                              | 日志文件 glob 模式 |
| `poll_interval` | int | `5`                                          | 轮询间隔（秒） |
| `start_from` | tail/head | `head`                                       | 首次启动读取位置 |
| `match_tags` | list[str] | `["TAG_LLM_CALL_START", "TAG_LLM_CALL_END"]` | 匹配标签 |
| `pair_timeout` | int | `300`                                        | START→END 配对超时（秒） |
| `output_dir` | str | `data/output`                                | JSONL 输出目录（支持绝对路径或相对于 adapter 根目录的相对路径） |
| `offset_file` | str | `data/offsets.json`                          | 偏移量状态文件路径（支持绝对路径或相对于 adapter 根目录的相对路径） |
| `output_retention_days` | int | `30`                                         | 文件保留天数 |
| `output_max_files` | int | `2000`                                       | 最大文件数 |
| `output_max_file_size` | str | `20MB`                                       | 单文件大小阈值（支持 KB/MB/GB） |
| `output_trim_target_ratio` | float | `0.7`                                        | 截断后目标比例 |
| `host` | str | `0.0.0.0`                                    | HTTP 监听地址 |
| `port` | int | `8900`                                       | HTTP 监听端口 |

每个 `agents[]` 条目可配置 `managed_docs[]`。其中 `kind`、`path` 为必填项，
`apply` 默认为 `file_only`；`apply: restart` 时还需提供 `restart_cmd` 与健康检查配置。
`max_content_bytes` 默认 `262144`，按 UTF-8 编码后的字节数限制单次文档内容，超限请求返回 400。

**路径解析规则：**

- `log_dir`、`output_dir`、`offset_file` 支持绝对路径或相对路径
- 相对路径始终相对于 **adapter 根目录**（即 `pyproject.toml` 所在目录）解析
- 例如：`log_dir: "logs"` → `<adapter_root>/logs`
- 例如：`log_dir: "/var/log/edpgent"` → `/var/log/edpgent`（绝对路径不变）
- 通过环境变量设置的路径不会被解析，保持原值

## 启动服务

### 方式一：CLI 命令

```bash
# 使用默认配置启动
agent-adapter

# 指定配置文件
agent-adapter --config agent_adapter_config.yaml
```

### 方式二：使用 uv

```bash
cd adapter
uv run agent-adapter --config agent_adapter_config.yaml
```

### 方式三：Python 模块

```bash
cd adapter
python -m agent_adapter.cli --config agent_adapter_config.yaml
```

### 启动输出示例

```
╭─────────────────────────────────────╮
│    Agent Adapter Configuration      │
├─────────────────────────────────────┤
│ log_dir          logs               │
│ log_pattern      process_*.log      │
│ poll_interval    5                  │
│ start_from       head               │
│ match_tags       TAG_LLM_CALL_START,│
│                  TAG_LLM_CALL_END   │
│ pair_timeout     300                │
│ output_dir       output             │
│ host             0.0.0.0            │
│ port             8900               │
╰─────────────────────────────────────╯
INFO:     Started server process [12345]
INFO:     Waiting for application startup.
INFO:     Application startup complete.
INFO:     Uvicorn running on http://0.0.0.0:8900
```

## HTTP API

服务启动后，可通过以下端点访问：

### GET /health

健康检查。

**响应：**
```json
{"status": "ok"}
```

### GET /api/v1/traces

列出所有有数据的 `conversation_id`。

**响应：**
```json
{
  "conversation_ids": ["conv-001", "conv-002"],
  "total": 2
}
```

### GET /api/v1/traces/{conversation_id}

查询指定对话的调用记录。

**查询参数：**
- `complete` (bool, 可选): 过滤 `complete` 状态
- `limit` (int, 可选): 限制返回记录数

**响应：**
```json
{
  "conversation_id": "conv-001",
  "calls": [
    {
      "call_id": "uuid-001",
      "conversation_id": "conv-001",
      "model": "model-sample",
      "type": "GENERATION",
      "start_time": "2026-06-10 14:30:15.123",
      "end_time": "2026-06-10 14:30:17.456",
      "duration_ms": 2333,
      "complete": true
    }
  ],
  "total": 1
}
```

### GET /api/v1/status

获取服务运行状态。

**响应：**
```json
{
  "active_file": "process_12345.log",
  "offset": 1024,
  "pending_starts_count": 0,
  "last_read_time": "2026-06-10T14:30:17.456Z",
  "output_dir_files": 5,
  "uptime_seconds": 3600.5
}
```

### Managed-doc API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/managed-docs` | 读取、更新或恢复已注册文档 |
| `GET` | `/api/v1/managed-docs/tasks/{task_id}` | 轮询异步 apply/restart 任务 |
| `GET` | `/api/v1/agents/{agent_name}/managed-docs` | 列出 Agent 已注册文档及内容上限、apply 能力 |

文档列表接口用于 EvoAgent 在提交优化前发现 `doc_kind`、当前 revision、
`max_content_bytes` 和预计最大任务时长；未知 Agent 返回 404。

## 开发与测试

### 运行测试

```bash
cd adapter

# 运行所有测试
uv run pytest tests/ -v

# 运行单元测试
uv run pytest tests/unit/ -v

# 运行集成测试
uv run pytest tests/integration/ -v
```

### Skill 热更新手工验证

针对 `POST /api/v1/skills` 热更与业务 Agent 联动，见 [`tests/skill_hotupdate/README.md`](tests/skill_hotupdate/README.md)：

```bash
cd tests/skill_hotupdate/scripts
python run_api_suite.py --base-url http://127.0.0.1:8900 --agent-name edp_agent
python run_e2e_experiment.py --base-url http://127.0.0.1:8900
```

### 代码检查

```bash
cd adapter

# Ruff lint
uv run ruff check src/ tests/

# MyPy 类型检查
uv run mypy src/
```

## 目录结构

```
adapter/
├── pyproject.toml              # 项目定义与依赖
├── agent_adapter_config.yaml   # 默认配置模板
├── src/agent_adapter/
│   ├── __init__.py
│   ├── cli.py                  # CLI 入口 (agent-adapter start)
│   ├── config.py               # 配置模型
│   ├── logging.py              # structlog 配置
│   ├── parser.py               # 日志行解析器
│   ├── reader.py               # 增量文件读取器
│   ├── offset.py               # 偏移量持久化
│   ├── pairing.py              # START/END 配对状态机
│   ├── pipeline.py             # 三阶段流水线 + JSONL 写入
│   ├── cleaner.py              # 输出文件清理器
│   └── api/
│       ├── __init__.py
│       ├── app.py              # FastAPI 应用工厂
│       └── routes.py           # HTTP 路由
└── tests/
    ├── unit/                   # 单元测试
    ├── integration/            # 集成测试
    └── skill_hotupdate/        # Skill 热更新脚本与文档
```

## 输出文件格式

每个 `conversation_id` 对应一个 JSONL 文件，每行一条调用记录：

```json
{
  "call_id": "uuid-001",
  "trace_id": "trace-abc",
  "agent_id": "agent-001",
  "conversation_id": "conv-999",
  "model": "model-sample",
  "type": "GENERATION",
  "start_time": "2026-06-10 14:30:15.123",
  "end_time": "2026-06-10 14:30:17.456",
  "duration_ms": 2333,
  "input": {"messages": [{"role": "user", "content": "hello"}]},
  "output": {"text": "hi"},
  "complete": true
}
```

不完整记录（超时/PID 切换/orphan END）：

```json
{
  "call_id": "uuid-002",
  "conversation_id": "conv-999",
  "complete": false,
  "incomplete_reason": "pair_timeout"
}
```

## 故障排查

### 服务无法启动

1. 检查端口是否被占用：`lsof -i :8900`
2. 检查日志目录是否存在且有读取权限
3. 检查配置文件语法：`python -c "import yaml; yaml.safe_load(open('config.yaml'))"`

### 无数据输出

1. 确认日志文件名匹配 `log_pattern`（默认 `process_*.log`）
2. 确认日志行包含 `TAG_LLM_CALL_START` / `TAG_LLM_CALL_END` 标签
3. 查看 structlog 输出中的 `parse_failure_rate_high` 警告

### 输出文件过大

调整清理参数：
- `output_max_file_size`: 降低单文件阈值
- `output_retention_days`: 缩短保留天数
- `output_max_files`: 减少最大文件数

## License

MIT
