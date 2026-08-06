# 数据回流 — 轨迹采集使用指南

> 本指南介绍如何使用 **轨迹采集** 完成业务 Agent 运行轨迹的采集、存储与查询。
> 
> 配套文档：[数据回流 — 轨迹清洗使用指南](数据回流-轨迹清洗使用指南.md)。

---

## 1. 特性概览

### 1.1 这是什么

**轨迹采集** 通过 `日志解析` 或 `OTel格式轨迹上报` 两种机制，实现业务 Agent 运行过程的可查询沉淀，适用于 *Agent 自演进、轨迹监测、轨迹评估* 等场景。

轨迹采集是 evoagent-adapter 的基础能力，负责把业务 Agent 一次会话产生的运行过程（HTTP 请求、LLM 调用、工具调用等）沉淀为可查询的轨迹记录。它提供两种互斥模式：

- **log 模式（默认）**：adapter 读取业务 Agent 的日志文件，解析、配对、合并后写入 JSONL 存储，查询时直接读 JSONL 数据。
- **standard 模式**：adapter 消费 OTLP/Kafka 轨迹写入 Postgres，查询时从 Postgres 读 span 并转换成与 log 模式同构的轨迹记录。

两种模式产出的记录格式完全同构，下游清洗逻辑零改动。

### 1.2 与相近特性的区别

| 特性 | 核心能力 | 适用条件               | 不适用情况 |
|---|---|--------------------|---|
| `轨迹采集` | 把运行过程沉淀为可查询的原始记录 | 需要存储与回放 Agent 运行轨迹 | 只想要结构化对话摘要 |
| `轨迹清洗` | 从原始记录抽取结构化 LLM 对话、去噪脱敏 | 需要供演进训练/评测的干净对话    | 需要保留全部 span 细节 |

> 当需要 *存储/回放原始运行过程* 时使用 **轨迹采集**；当需要 *直接拿到结构化对话* 时，在采集之上叠加 **轨迹清洗**（见配套指南）。

### 1.3 本指南覆盖范围

本指南包含：

- log 模式与 standard 模式的完整数据链路与组件流程；
- 两种模式下采集到的轨迹记录格式与字段含义；
- standard 模式 Kafka/OTLP 消息原始格式与 Postgres 表结构；
- 轨迹查询 API、配置项、启动与部署方式。

本指南不包含：

- 清洗规则与清洗前后格式对照（见 [轨迹清洗使用指南](数据回流-轨迹清洗使用指南.md)）；
- 技能管理与业务 Agent 调用相关内容。

---

## 2. 什么时候使用

| 使用轨迹采集                    | 不使用轨迹采集 |
|---------------------------|---|
| 需要留存业务 Agent 每次会话的完整运行轨迹  | 只需要 Agent 的最终输出，不需要过程 |
| 需要为自演进/评估提供轨迹数据源          | 业务 Agent 不产生日志或不上报 OTLP |
| 需要按会话查询 LLM 调用、工具调用明细     | 需要结构化对话摘要，应叠加 `轨迹清洗` |
| 需要 standard 模式跨进程/跨服务统一沉淀 | 单机调试、无 Kafka/Postgres，应改用 `log 模式` |

> 判断原则：当需要 *可回放的运行过程记录* 时使用本特性；只需结构化对话时优先在采集之上叠加清洗。

---

## 3. 准备工作

> adapter 采用容器方式部署。完整构建/启动/检查流程见 [evoagent 部署指南](../02-部署指南/evoagent部署指南.md)；本章仅介绍与轨迹采集相关的部署配置要点（配置项需详细了解，见 3.2 与 5.3）。轨迹采集两种模式都通过同一份 adapter 镜像运行；standard 模式所需的 Kafka/Postgres/Collector 由独立基础设施预先部署在 `openjiuwen-net` 网络，adapter 容器通过加入该网络进行连接——本指南不涉及它们的部署。

### 3.1 环境要求

| 项目 | 要求 | 检查命令 |
|---|---|---|
| Docker Engine | 已安装并运行 | `docker version` |
| adapter 镜像 | 已构建或可拉取 | `docker images \| grep agent-adapter` |
| 业务 Agent 日志目录（log 模式） | 宿主可挂载，含 `process*.log` | `ls {HOST_LOG_ROOT}/*/process*.log` |
| Kafka（standard 模式） | 已由基础设施部署、网络可达 | `docker exec {KAFKA_CONTAINER} kafka-topics --list --bootstrap-server localhost:9092` |
| Postgres（standard 模式） | 已由基础设施部署、网络可达 | `pg_isready -h {PG_HOST} -p 5432` |
| OTel Collector（standard 模式） | 已部署，OTLP 信封投递到 `otlp_traces` | collector 日志出现 `otlp_traces` 投递 |

> standard 模式下 Kafka/Postgres/Collector 属外部基础设施，由独立 stack 部署在 `openjiuwen-net` 网络；本指南只负责把 adapter 容器接入该网络，不部署它们。

### 3.2 准备镜像与配置

```bash
cd agent-solution/common/agent-evolve/evoagent-adapter/deployment
cp config/.env.example config/.env          # 复制后编辑 config/.env 填主机路径
./start.sh --build                          # 首次启动需 --build 构建镜像
```

`.env` 必填主机路径项：

| 变量 | 说明 |
|---|---|
| `HOST_LOG_ROOT` | 业务 Agent 日志父目录（挂载到容器 `/data/logs`，只读） |
| `HOST_OUTPUT_DIR` | adapter 输出持久化目录（挂载到 `/app/data`，读写） |
| `HOST_CONFIG_FILE` | adapter 配置文件路径（挂载到 `/app/agent_adapter_config.yaml`，读写） |

**配置填写：**

配置分两层来源：`.env`（环境变量，承载全部标量参数与 `HOST_*` 宿主路径）与 `agent_adapter_config.yaml`（仅承载 `match_tags` 与 `agents[]` 结构化配置，其中 per-agent 字段以 `${VAR:default}` 占位引用环境变量）。解析优先级（高 → 低）：**环境变量**（设且非空；空串视为未设置）> **YAML 占位默认值**（`${VAR:default}` 冒号后的值）> **字段内置默认值**（`src/agent_adapter/config.py`）。顶层标量走 `ADAPTER_<FIELD>` 前缀，per-agent 走 `<NAME>_<FIELD>` 前缀（`<NAME>` 为 agent name 大写）。

log 模式最小填写示例：

```bash
# config/.env
HOST_LOG_ROOT=/var/log/agents            # 业务 Agent 日志父目录（宿主）
HOST_OUTPUT_DIR=/opt/agent-adapter/data
HOST_CONFIG_FILE=/opt/agent-adapter/agent_adapter_config.yaml
ADAPTER_TRACE_SOURCE=log                 # 默认值，可省略
ADAPTER_POLL_INTERVAL=60                 # 日志轮询间隔（秒）
ADAPTER_START_FROM=tail                  # 首次只读新日志；需要读历史填 head
ADAPTER_LOG_LEVEL=INFO                   # DEBUG | INFO | WARNING | ERROR
ADAPTER_AGENT_TOKEN=                     # 业务 Agent 鉴权 token，无需鉴权留空
```

standard 模式在 log 模式基础上追加：

```bash
ADAPTER_TRACE_SOURCE=standard
ADAPTER_NETWORK=openjiuwen-net           # 接入外部 collector 网络，解析 kafka/postgres 别名
ADAPTER_PG_HOST=postgres                 # 外部 Postgres 容器别名
ADAPTER_PG_PORT=5432
ADAPTER_PG_DB=agent_adapter
ADAPTER_PG_USER=otel_user
ADAPTER_PG_PASSWORD={PASSWORD}           # 由基础设施提供
ADAPTER_KAFKA_BROKERS=kafka:9092         # 外部 Kafka 容器别名
ADAPTER_KAFKA_TOPIC=otlp_traces
ADAPTER_KAFKA_GROUP=agent-adapter
ADAPTER_TRACE_WAIT_TIMEOUT=10.0          # 查询等根 span 秒数
```

`agent_adapter_config.yaml` 多 Agent 示例（per-agent 覆盖，路径均为容器内路径）：

```yaml
agents:
  - name: business_agent_a
    log_dir: /data/logs/business_agent_a         # 对应宿主 {HOST_LOG_ROOT}/business_agent_a
    output_dir: /app/data/output/business_agent_a
    skills_dir: /data/skills/business_agent_a
  - name: business_agent_b
    log_dir: /data/logs/business_agent_b
    output_dir: /app/data/output/business_agent_b
    skills_dir: /data/skills/business_agent_b
```

填写要点：

- `HOST_LOG_ROOT` 是宿主路径，挂载到容器 `/data/logs`（只读）；YAML 中 per-agent `log_dir` 必须写容器内路径 `/data/logs/{name}`，与宿主子目录一一对应。
- log 模式只需填主机路径项 + `ADAPTER_POLL_INTERVAL`/`ADAPTER_START_FROM`；standard 模式追加 `ADAPTER_NETWORK` 与 Kafka/PG 连接项。
- `ADAPTER_NETWORK` 留空 = 默认 bridge 网络；standard 模式必须设为 collector 所在网络（如 `openjiuwen-net`），否则容器无法解析 `kafka`/`postgres` 别名。
- 配置字段对应环境变量为 `ADAPTER_` + 字段名大写（如 `trace_source` → `ADAPTER_TRACE_SOURCE`、`kafka_brokers` → `ADAPTER_KAFKA_BROKERS`、`output_max_file_size` → `ADAPTER_OUTPUT_MAX_FILE_SIZE`）；完整字段表见 5.3。
- `agent_adapter_config.yaml` 支持热更新（CRUD 接口 `/api/v1/config/agents`），写回 `HOST_CONFIG_FILE` 指向的宿主文件，跨容器重建不丢失。

### 3.3 启动服务与检查

以容器方式启动 adapter（启动参数、端口、卷挂载等完整说明见 [evoagent 部署指南](../02-部署指南/evoagent部署指南.md)）：

**log 模式（默认）：**

```bash
./start.sh                              # 默认 8900 端口
# 或自定义：./start.sh --port 8901 --name my-adapter
```

**standard 模式：** 在 `.env` 中设 `ADAPTER_TRACE_SOURCE=standard` 与 `ADAPTER_NETWORK=openjiuwen-net`，再启动：

```bash
./start.sh
```

启动后检查：

```bash
docker ps --filter name=agent-adapter                 # 容器应为 Running
docker logs agent-adapter --tail 50                  # 查看 trace_source 与消费循环
curl -s http://localhost:8900/health                  # 健康探针
curl -s http://localhost:8900/api/v1/status           # 服务状态
```

预期输出：

```text
INFO     agent_adapter.api.app:lifespan:trace_source=log
INFO     uvicorn.error:on_startup:Uvicorn running on http://0.0.0.0:8900
```

```json
{"output_dir_files": 0, "pending_starts_count": 0, "uptime_seconds": 12, "trace_source": "log"}
```

> standard 模式日志应额外出现 `kafka_consumer started group=agent-adapter topic=otlp_traces`；若 Kafka 不可达仅告警、容器仍 Running，`/api/v1/status` 仍可读已落库 spans。

### 3.4 条件性准备

| 使用场景 | 额外资源 | 准备方式 |
|---|---|---|
| log 模式 | 业务 Agent 日志目录 | `.env` 设 `HOST_LOG_ROOT`；`agent_adapter_config.yaml` 中 per-agent `log_dir` 写 `/data/logs/{name}` |
| standard 模式 | 接入外部 Kafka/Postgres | `.env` 设 `ADAPTER_TRACE_SOURCE=standard` + `ADAPTER_NETWORK=openjiuwen-net`；按需改 `ADAPTER_PG_HOST`/`ADAPTER_KAFKA_BROKERS` 指向外部容器别名 |
| standard 模式连宿主 PG | PG 对宿主暴露端口 | `ADAPTER_PG_HOST=host.docker.internal` |
| 多 Agent | 每个 Agent 独立目录 | `agent_adapter_config.yaml` 的 `agents` 列表逐个配置 `name`/`log_dir`/`output_dir` |
| managed-doc 自动重启业务 Agent | Docker socket | `.env` 设 `ADAPTER_ENABLE_DOCKER_RESTART=true` + `HOST_DOCKER_SOCKET` |

> 只有使用 *standard 模式* 时才需接入外部 Kafka/Postgres；普通 log 模式可以跳过网络相关准备。

---

## 4. 快速上手

本节给出 log 与 standard 两种模式各自的最小可验证闭环。

### 4.1 准备输入

**log 模式**：adapter 通过读取业务 Agent 的日志文件采集轨迹。日志文件需满足以下格式与字段要求。

**log 行格式要求：**

- 文件名匹配 `log_pattern`（默认 `process*.log`），位于 `log_dir` 下；
- 每条日志**一行**，以 `\x01`（SOH，0x01）分隔 9 个字段，顺序固定；
- 行首为时间戳前缀（正则 `^\d{4}-\d{2}-\d{2}`）；不以时间戳开头的行视为上一行的续行（多行合并）；
- `tag` 字段值必须在 `match_tags` 集合内（默认 11 个，见下表），不在集合内的行被忽略；
- `message` 字段为 JSON 串，解析采用三级容错：`ast.literal_eval` → `json.loads` → 关键词提取 → 全失败则保留原串。

9 字段定义（按位置顺序，`\x01` 分隔）：

| 位置 | 字段 | 含义 |
|:--:|---|---|
| 0 | `time` | 时间戳（如 `2026-07-22 14:50:24,910`） |
| 1 | `level` | 日志级别（INFO/WARNING 等） |
| 2 | `source` | 日志来源 / Agent 名称 |
| 3 | `trace_id` | 链路 ID |
| 4 | `agent_id` | Agent 实例 ID |
| 5 | `conversation_id` | 会话 ID（即查询用的 `session_id`） |
| 6 | `tag` | 标签，决定记录类型，须在 `match_tags` 内 |
| 7 | `cost` | 耗时 / 成本（如 `7.31`） |
| 8 | `message` | JSON 载荷，承载 `input`/`output` 等业务字段 |

log 行示例（`\x01` 为实际分隔符，message 简写）：

```text
2026-07-22 14:50:24,910\x01INFO\x01business_agent\x0110eb6e9c6c66b1ee58a4f0bb22288e95\x01business_agent\x01tests-conv-0004\x01TAG_HTTP_REQUEST_START\x017.31\x01{"input":{"query":"推荐理财产品"}}
```

**需要采集的标签（`match_tags` 默认 11 个）：**

| 标签 | 配对方式 | 采集的记录类型 |
|---|---|---|
| `TAG_HTTP_REQUEST_START` / `TAG_HTTP_REQUEST_END` | START/END 配对 | TRACE（HTTP 根，`id == trace_id`） |
| `TAG_LLM_CALL_START` / `TAG_LLM_CALL_END` | START/END 配对 | GENERATION（LLM 调用，含 `input.messages`/`output`） |
| `TAG_TOOL_EXECUTE_START` / `TAG_TOOL_EXECUTE_END` | START/END 配对 | TOOL |
| `TAG_VERSATILE_START` / `TAG_VERSATILE_END` | START/END 配对 | TOOL |
| `TAG_SKILL_EXECUTE_START` / `TAG_SKILL_EXECUTE_END` | START/END 配对 | SKILL |
| `TAG_PLANNING_DECISION` | 单例（无配对） | SPAN |

> 配对 tag 的 START 与 END 经 `trace_assembler` 按 id 合并（fill-missing 策略），`start_time`/`end_time` 由配对双方注入；超 `pair_timeout`（默认 300s）未配对则标记 `_incomplete`。`message` 中需携带 `id`/`input`/`output` 等字段供下游清洗消费。

**standard 模式**：触发业务 Agent 一次会话，使其通过 openjiuwen.tracer.otel 上报 OTLP spans。一次会话通常产生多条 span，典型 span 包括 `http.request`(SERVER 根)、`llm.*`(CLIENT)、`tool.*`(INTERNAL)、`chain.*`(INTERNAL)。业务 Agent 上报的 span 形态如下（仅列关键字段，`attributes` 中的长文本以 `...` 省略）：

```json
// 1) http.request 根 span（SERVER，携带 session.id 与请求体）
{
  "trace_id": "10eb6e9c6c66b1ee58a4f0bb22288e95",
  "span_id": "0d09a10df5939631",
  "parent_span_id": "",
  "name": "http.request",
  "kind": "SERVER",
  "start_time": "2026-07-22T14:50:24.910795+00:00",
  "end_time": "2026-07-22T14:50:24.926645+00:00",
  "service_name": "business_agent",
  "status_code": "UNSET",
  "attributes": {
    "http.request.method": "POST",
    "http.route": "/v1/test_project/agents/business_agent/conversations/tests-conv-0004",
    "session.id": "tests-conv-0004",
    "openjiuwen.http.request_body": "{\"agent_id\":\"business_agent\",\"input\":{\"query\":\"推荐理财产品\"},\"conversation_id\":\"tests-conv-0004\",\"stream\":true}",
    "http.response.status_code": 200
  },
  "session_id": "tests-conv-0004"
}

// 2) llm.* span（CLIENT，携带 gen_ai.prompt / gen_ai.completion，值为 Python repr 串）
{
  "trace_id": "e0d09d4e1bae9cbefd5a3ff92a820d5e",
  "span_id": "0878738cabc247ef",
  "parent_span_id": "d5959eaf93c55746",
  "name": "llm.model-sample",
  "kind": "CLIENT",
  "start_time": "2026-07-22T14:50:24.953822+00:00",
  "end_time": "2026-07-22T14:50:32.266005+00:00",
  "service_name": "business_agent",
  "status_code": "OK",
  "attributes": {
    "gen_ai.system": "openjiuwen",
    "gen_ai.request.model": "model-sample",
    "gen_ai.operation.name": "chat",
    "gen_ai.prompt": "{\"messages\": \"[SystemMessage(role='system', content='# Agent 规则...'), UserMessage(role='user', content='推荐理财产品', ...)]\"}",
    "gen_ai.completion": "{\"outputs\": \"role='assistant' content='您好！我来帮您推荐理财产品...' tool_calls=[ToolCall(... name='sample_tool_1' ...)] usage_metadata=UsageMetadata(...)\"}",
    "gen_ai.usage.prompt_tokens": 9910,
    "gen_ai.usage.completion_tokens": 319,
    "session.id": "tests-conv-0004"
  },
  "session_id": "tests-conv-0004"
}
```

> adapter 消费时把每条 OTLP span 摊平为上述结构，`session.id` 从 `attributes` 提升为顶层 `session_id`（轨迹 API 查询键）；`gen_ai.prompt`/`gen_ai.completion` 的 repr 串在查询时由 `repr_extract` 规整回 JSON。

### 4.2 执行操作

启动 adapter（见 3.3），然后发起一次业务 Agent 会话产生轨迹，再查询：

```bash
curl -X GET 'http://localhost:8900/api/v1/traces'
```

### 4.3 查看结果

成功时应返回全部会话 ID：

```json
{
  "conversation_ids": ["tests-conv-0004"],
  "total": 1
}
```

取指定会话明细：

```bash
curl -X GET 'http://localhost:8900/api/v1/traces/tests-conv-0004'
```

```json
{
  "conversation_id": "tests-conv-0004",
  "calls": [ { "type": "GENERATION", "id": "0878738cabc247ef", "..." : "..." } ],
  "total": 3,
  "complete": true
}
```

### 4.4 验证是否成功

```bash
curl -X GET 'http://localhost:8900/api/v1/status'
```

预期结果（关键字段非空）：

```text
{"output_dir_files": 1, "pending_starts_count": 0, "uptime_seconds": 120, "trace_source": "log"}
```

> 完成本节后，你应该已经能够独立跑通一次最小流程：产生轨迹 → 查询会话列表 → 查询会话明细。

---

## 5. 接口与配置

### 5.1 接口清单

| 方法 | 路径 | 作用 | 适用场景 |
|:--:|---|---|---|
| `GET` | `/api/v1/traces` | 列全部会话 ID（聚合所有 Agent） | 全局浏览 |
| `GET` | `/api/v1/traces/{conversation_id}` | 取会话原始记录 + complete 信号 | 单会话明细（standard 会等根 span） |
| `GET` | `/api/v1/agents/{agent_name}/traces` | 列指定 Agent 的会话 ID | 多 Agent 部署 |
| `GET` | `/api/v1/agents/{agent_name}/traces/{conversation_id}` | 取指定会话原始记录 | 单会话明细 |
| `GET` | `/api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}` | 取清洗后结构化对话 | 见清洗指南 |
| `GET` | `/api/v1/status` | 服务状态（含 `output_dir_files`/`pending_starts_count`） | 排查 |
| `GET` | `/health` | 健康检查 | 存活探针 |

> 所有 `GET /traces*` 接口在返回前会先调用触发一次增量采集，再读取。

### 5.2 请求参数

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `agent_name` | str | 是（路径） | 无 | Agent 名称，多 Agent 部署下必填 |
| `conversation_id` | str | 是（路径） | 无 | 会话 ID，等于 `session_id` |
| `complete` | bool | 否 | 无 | 过滤开关：`true` 只返回完整、`false` 只返回不完整、不传则不过滤 |
| `limit` | int | 否 | 无 | 返回记录条数上限，`≥1` |

> `complete` 在两种模式下语义不同：**standard** 按会话级根 span 信号整体过滤；**log** 按记录级 `_incomplete` 逐条过滤。不要混用。

### 5.3 配置项

配置解析顺序（高 → 低）：**环境变量**（设且非空；空串视为未设置，回落下一级）> **YAML 占位默认值**（per-agent/结构化字段的 `${VAR:default}` 冒号后值）> **字段内置默认值**（`src/agent_adapter/config.py`）。顶层标量前缀 `ADAPTER_<FIELD>`，per-agent 前缀 `<NAME>_<FIELD>`，managed-doc 默认前缀 `ADAPTER_MDD_*`。

**standard 模式专用：**

| 配置项 | 类型 | 必填 | 默认值 | 说明                                          |
|---|---|:--:|---|---------------------------------------------|
| `trace_source` | `log`/`standard` | 否 | `log` | 模式选择，决定数据来源，standard 模式下需要填写`standard`      |
| `db_type` | `postgres` | 否 | `postgres` | 数据库类型                                       |
| `pg_host` | str | standard 模式下 是 | `postgres` | Postgres 主机                                 |
| `pg_port` | int | 否 | `5432` | Postgres 端口                                 |
| `pg_db` | str | 否 | `agent_adapter` | 独立库名                                        |
| `pg_user` | str | 否 | `otel_user` | 数据库用户                                       |
| `pg_password` | str | 否 | `otel_password` | 数据库密码                                       |
| `kafka_brokers` | str | standard 模式下 是 | `kafka:9092` | Kafka broker，逗号分隔多 broker                   |
| `kafka_topic` | str | 否 | `otlp_traces` | 消费 topic                                    |
| `kafka_group` | str | 否 | `agent-adapter` | 消费组                                         |
| `trace_wait_timeout` | float | 否 | `10.0` | standard 模式 `GET /traces/{conv}` 等根 span 秒数 |

**log 模式采集相关：**

| 配置项 | 类型 |    必填     | 默认值 | 说明 |
|---|---|:---------:|---|---|
| `log_dir` | str | log 模式下 是 | `logs` | 业务 Agent 日志目录 |
| `log_pattern` | str |     否     | `process*.log` | 日志文件 glob |
| `poll_interval` | int |     否     | `60` | 采集轮询间隔秒 |
| `start_from` | `tail`/`head` |     否     | `tail` | 首次读取位置 |
| `match_tags` | list[str] |     否     | 11 个 TAG_* | 提取的日志标签集合 |
| `pair_timeout` | int |     否     | `300` | START→END 配对超时秒 |
| `output_dir` | str |     否     | `data/output` | JSONL 存储目录 |
| `offset_file` | str |     否     | `data/offsets.json` | 偏移持久化文件 |
| `output_retention_days` | int |     否     | `30` | 存储保留天数 |
| `output_max_files` | int |     否     | `2000` | 最大存储文件数 |
| `output_max_file_size` | str |     否     | `20MB` | 单文件大小阈值 |
| `output_trim_target_ratio` | float |     否     | `0.7` | 截断后目标比例 |

**HTTP 与多 Agent：**

| 配置项 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `host` | str | 否 | `0.0.0.0` | 监听地址 |
| `port` | int | 否 | `8900` | 监听端口 |
| `agents` | list | 否 | `[]` | 多 Agent 列表，空则单 default Agent |

> **填写说明：** 以上字段对应环境变量为 `ADAPTER_` + 字段名大写（如 `trace_source` → `ADAPTER_TRACE_SOURCE`、`kafka_brokers` → `ADAPTER_KAFKA_BROKERS`、`output_max_file_size` → `ADAPTER_OUTPUT_MAX_FILE_SIZE`）。解析优先级（高 → 低）：环境变量（设且非空；空串视为未设置）> YAML 占位默认值（`${VAR:default}` 冒号后值）> 字段内置默认值。容器部署时在 `config/.env` 填写，最小填写示例见 3.2。
> - log 模式必填：`log_dir`（容器内路径，单 Agent 时也可仅设 `HOST_LOG_ROOT`）；按需调 `poll_interval`/`start_from`/`pair_timeout`/`output_*`。
> - standard 模式必填：`trace_source=standard`、`pg_host`、`kafka_brokers`；`db_type` 目前仅支持 `postgres`。
> - `agents` 为空时按单 default Agent 运行；多 Agent 时在 YAML `agents` 列表逐个配置 `name`/`log_dir`/`output_dir`（见 3.2 示例）。
> - `match_tags` 环境变量用 JSON 数组或逗号分隔串，仅在 log 模式生效。

### 5.4 模式与变体

| 模式 | 特点 | 适用场景 | 注意事项                                   |
|---|---|---|----------------------------------------|
| `log` | 读业务 Agent 日志 → 解析配对 → JSONL 存储 → 查询读存储 | 单机、无 Kafka/PG；业务 Agent 日志规范 | 依赖日志格式稳定；`pair_timeout` 内未配对的记录标记不完整   |
| `standard` | 消费 OTLP/Kafka → 写 Postgres → 查询读 PG span 转记录 | 跨进程/跨服务统一沉淀；需要 OpenTelemetry 体系 | 需 Collector+Kafka+PG；PG 不可达Adapter无法启动 |

选择建议：

- 需要 *零中间件、单机快速接入*：使用 `log`模式；
- 需要 *跨服务统一、与 OTel 体系打通*：使用 `standard`模式；
- 不确定时：优先使用默认 `log`。

### 5.5 请求示例

```bash
# 列全部会话
curl -X GET 'http://{HOST}:8900/api/v1/traces'

# 取指定会话原始记录（仅完整）
curl -X GET 'http://{HOST}:8900/api/v1/traces/tests-conv-0004?complete=true&limit=50'

# 多 Agent：列指定 Agent 会话
curl -X GET 'http://{HOST}:8900/api/v1/agents/{AGENT_NAME}/traces'
```

### 5.6 返回结果

`GET /api/v1/traces/{conversation_id}` 成功返回：

```json
{
  "conversation_id": "tests-conv-0004",
  "calls": [ { "...record..." : "..." } ],
  "total": 3,
  "complete": true
}
```

`calls` 中的记录为统一的 record 形态，两种模式同构。记录分两类：

**TRACE record**（HTTP 根，无 `type` 字段，`id == trace_id`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | str | 等于 `trace_id` |
| `trace_id` | str | 链路 ID |
| `session_id` | str | 会话 ID（= conversation_id） |
| `timestamp` | str | 根时间戳 |
| `start_time` / `end_time` | str | 配对 START/END 时间 |
| `input` | dict | HTTP 请求体 |
| `output` | dict | HTTP 响应 |
| `_incomplete` / `_incomplete_reason` | bool / str | 不完整标记与原因（log 模式特有） |

**Observation record**（GENERATION / TOOL / SKILL / SPAN）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | str | `GENERATION` / `TOOL` / `SKILL` / `SPAN` |
| `id` | str | 记录 ID（log 为 payload 提供；standard 为 `span_id`） |
| `trace_id` | str | 链路 ID |
| `session_id` | str | 会话 ID |
| `start_time` / `end_time` | str | 调用起止时间（ISO 8601） |
| `input` | dict/list | 调用输入；GENERATION 为 `{"messages": [...]}` |
| `output` | Any | 调用输出；GENERATION 为最后一条 assistant 回复 |
| `metadata` / `tags` | dict/list | 合并时递归 fill/dedup |
| `_incomplete` / `_incomplete_reason` | bool / str | 不完整标记（log 模式特有） |

**两种模式下记录差异：**

| 维度 | log 模式 | standard 模式 |
|---|---|---|
| 来源 | 读 `{conv}.jsonl` 存储 | PG spans 经 `spans_to_records` 转换 |
| GENERATION 的 input/output | 日志 message payload 已是 dict | `gen_ai.prompt`/`gen_ai.completion` 为 Python repr 串，经 `repr_extract` 规整成 JSON |
| 不完整标记 | 有 `_incomplete`/`_incomplete_reason` | 无；完整性由根 span `end_time` 是否已设判定 |
| 完整性过滤 | 记录级逐条过滤 | 会话级整体过滤（`complete_signal`） |
| 记录顺序 | 存储写入顺序 | 按 `start_time` 升序 |

### 5.7 状态码与异常

| 状态码 | 含义 | 常见原因 | 处理方式 |
|---|---|---|---|
| `200` | 正常 | 所有轨迹 GET 成功 | — |
| `404` | Agent 不存在 | `agent_name` 未配置 | 检查 `agents` 配置 |
| `400` | 请求非法 | body 非 JSON / 校验失败 | 修正请求 |
| `500` | 内部错误 | `trace_source` 未初始化或 PG 异常 | 看 `trace_source` 是否就绪 |

运行期异常现象见第 7 节故障排查表。

### 5.8 配置生效方式

修改配置的生效方式取决于配置层级：

| 配置层级 | 生效方式 | 说明 |
|---|---|---|
| per-agent 配置（`agents` 列表的 `name`/`log_dir`/`output_dir`/`skills_dir` 等） | **热生效** | 通过 `POST/PUT/DELETE /api/v1/config/agents` 修改，立即重建对应 Pipeline/AgentClient，无需重启；改动写回 `agent_adapter_config.yaml` |
| 顶层/模式配置（`trace_source`、`pg_*`、`kafka_*`、`poll_interval`、`start_from`、`pair_timeout`、`match_tags`、`output_*` 等） | **重启容器生效** | 这些在启动时（lifespan）初始化 trace_source/repo/consumer/pipeline/文件清理任务，改 `.env` 或 YAML 后需 `./start.sh` 重启或 `docker restart` |
| 环境变量（`ADAPTER_*`、`HOST_*`） | **重启容器生效** | 仅启动时读取 |

> `agent_adapter_config.yaml` 经配置 CRUD 写回 `HOST_CONFIG_FILE` 指向的宿主文件，跨容器重建不丢失。`trace_source` 切换必须重启（log↔standard 涉及不同 trace_source/repo/consumer 初始化）。

---

## 6. 场景用例

### 6.1 基础用例：log 模式容器部署与查询

适用于：单机/容器化部署、业务 Agent 日志规范。

```bash
# 1) 准备配置并构建镜像（首次）
cd agent-solution/common/agent-evolve/evoagent-adapter/deployment
cp config/.env.example config/.env && vim config/.env   # 填 HOST_LOG_ROOT 等
./start.sh --build                          # 首次构建镜像

# 2) 启动 adapter 容器（log 模式默认）并检查
./start.sh
docker ps --filter name=agent-adapter
curl -s http://localhost:8900/api/v1/status

# 3) 触发业务 Agent 一次会话后查询
curl -X GET 'http://localhost:8900/api/v1/traces'
curl -X GET 'http://localhost:8900/api/v1/traces/tests-conv-0004'
```

预期结果：

```json
{
  "conversation_id": "tests-conv-0004",
  "calls": [{"type": "GENERATION", "id": "0878738cabc247ef"}],
  "total": 3,
  "complete": true
}
```

### 6.2 多Agent并行：多 Agent 轨迹采集与查询

适用于：一个 adapter 同时采集多个业务 Agent 的轨迹（log 或 standard 模式均适用）。

```bash
# 1) 在 agent_adapter_config.yaml 中配置 agents 列表（per-agent 独立目录）
#    agents:
#      - name: business_agent_a
#        log_dir: /data/logs/business_agent_a
#        output_dir: /app/data/output/business_agent_a
#      - name: business_agent_b
#        log_dir: /data/logs/business_agent_b
#        output_dir: /app/data/output/business_agent_b
./start.sh                                  # 启动 adapter 容器（多 Agent 并行采集）

# 2) 检查容器与状态
docker ps --filter name=agent-adapter
curl -s http://localhost:8900/api/v1/status

# 3) 分别查询各 Agent 的会话（并行采集、独立查询）
curl -X GET 'http://localhost:8900/api/v1/agents/business_agent_a/traces'
curl -X GET 'http://localhost:8900/api/v1/agents/business_agent_b/traces'
```

> 多 Agent 并行时，adapter 为每个 Agent 维护独立的 pipeline（日志读取 / 偏移 / 归档）与会话空间，互不干扰；查询通过 `/api/v1/agents/{agent_name}/...` 按名隔离。standard 模式下多个 Agent 的 OTLP 同样经共享 Kafka/Postgres 并行入库，按 `session.id` 区分。配置优先级：环境变量 > `agent_adapter_config.yaml` > 默认。

### 6.3 条件性高级用例：complete 信号等待

适用于：standard 模式下会话刚结束、根 span 尚未落库。

```bash
curl -X GET 'http://localhost:8900/api/v1/traces/tests-conv-0004?complete=true'
```

注意事项：

- standard 模式根 span（`kind=SERVER` 且 `parent` 空）`end_time` 已设才会判 `complete=true`；
- 若 `trace_wait_timeout` 内仍未等到，返回 `complete=false` 且 `calls` 可能为空，可调大 `ADAPTER_TRACE_WAIT_TIMEOUT`；
- log 模式无等待逻辑，按存储 `_incomplete` 标记立即返回。

### 6.4 数据结构变体

适用于：需要理解两种模式下记录与存储的真实结构。

**log 模式存储文件**（`{output_dir}/{conversation_id}.jsonl`，每行一条 record）：

```json
{"id":"10eb6e9c6c66b1ee58a4f0bb22288e95","trace_id":"10eb6e9c6c66b1ee58a4f0bb22288e95","session_id":"tests-conv-0004","timestamp":"2026-07-22T14:50:24.910795+00:00","start_time":"2026-07-22T14:50:24.910795+00:00","end_time":"2026-07-22T14:50:33.000000+00:00","input":{"request_header":{}},"output":{"status_code":200}}
{"type":"GENERATION","id":"0878738cabc247ef","trace_id":"10eb6e9c6c66b1ee58a4f0bb22288e95","session_id":"tests-conv-0004","start_time":"2026-07-22T14:50:24.953822+00:00","end_time":"2026-07-22T14:50:32.266005+00:00","input":{"messages":[{"role":"user","content":"推荐理财产品"}]},"output":{"role":"assistant","content":"好的"}}
```

**standard 模式 Postgres `spans` 表**（一 OTel span 一行，`schema/postgres.sql`）：

| 列 | 类型 | 说明 |
|---|---|---|
| `trace_id` | text NOT NULL | 链路 ID（PK 组成） |
| `span_id` | text NOT NULL | span ID（PK 组成） |
| `parent_span_id` | text | 父 span，空为根 |
| `name` | text | span 名（`http.request`/`llm.*`/`tool.*`/`chain.*`） |
| `kind` | text | SERVER/CLIENT/INTERNAL/PRODUCER/CONSUMER |
| `start_time` / `end_time` | timestamptz | 起止时间 |
| `service_name` | text | 从 `resource.service.name` 提升 |
| `status_code` | text | OK/ERROR/UNSET |
| `attributes` | jsonb | span 属性原样（`gen_ai.*`/`openjiuwen.*`/`session.id`） |
| `resource_attributes` | jsonb | 资源属性 |
| `session_id` | text | 从 `attributes.session.id` 提升（轨迹 API 查询键） |
| `ingested_at` | timestamptz | 入库时间 |

**standard 模式 `traces` 汇总表**：`trace_id`(PK)、`session_id`、`root_span_id`、`start_time`/`end_time`、`span_count`、`status`、`request_summary`(根 http `openjiuwen.http.request_body`)、`response_summary`(chain span `openjiuwen.agent.outputs`)。

**span → record 映射规则**（`spans_to_records.py`）：

| span | → record type | 关键字段来源 |
|---|---|---|
| `name` 以 `llm.` 开头 | `GENERATION` | `input.messages` ← `gen_ai.prompt.messages`；`output` ← `gen_ai.completion.outputs` |
| `name` 以 `tool.` 开头 | `TOOL` | `name`/`start_time`/`end_time` |
| `name=="http.request"` 且 `kind==SERVER` | TRACE | `id==trace_id`，`timestamp=start_time` |
| 其余（`chain.*`/`service.*`/INTERNAL） | 跳过 | 避免噪声 |

---

## 7. 常见问题

### 7.1 故障排查表

| 现象 | 原因 | 处理方式 |
|---|---|---|
| `GET /traces/{conv}` 返回空、`complete=false` 且耗时长（standard） | 根 span 尚未落库或会话未结束 | 调大 `trace_wait_timeout`；确认业务 Agent 已上报根 span |
| `GET /traces/{conv}` 返回空、`complete=false` 立即返回（log） | 存储中存在 `_incomplete` 记录 | 检查 `pair_timeout` 是否过小；日志 START/END 是否齐全 |
| `session_id` 为空 | 部分 span 未上报 `session.id` | 业务 Agent 在根 span 上报 `session.id`；adapter 会批内+跨批两段回填 |
| Kafka 消费静默停（standard） | snappy codec 缺失等导致消费循环异常 | 已有退避重试；检查依赖与日志 `kafka_consume_loop_error` |
| poison 消息无限重投（standard） | OTLP 信封解析失败 | 已 commit 跳过；检查 `kafka_parse_error` 日志与消息格式 |
| 入库失败消息堆积（standard） | `bulk_insert_spans` 异常未 commit | 检查 PG 连接与 `kafka_insert_error` 日志 |
| `parse_failure_rate_high` 告警（log） | 日志行 message 解析失败率 >50% | 检查日志格式是否变更、`\x01` 分隔是否完整 |
| offset 漂移/重复读（log） | 文本模式读 `\r\n` | 已用二进制读修复；确认 `offsets.json` 未被外部修改 |
| `log_dir_not_found` 告警（log） | `log_dir` 路径不存在 | 检查配置路径与挂载卷 |
| standard 模式 app 起不来 | PG 连接失败（致命） | 检查 `pg_host`/`pg_port`/凭证与网络 |
| Kafka 不可达（standard） | 仅告警，app 仍启，`consumer=None` | API 仍可读已落库 spans，但新数据不进；恢复 Kafka 后重启 |
| `_to_dt` ValueError | 脏时间格式 | 检查业务 Agent 上报的时间字段格式 |

### 7.2 常见问答

#### Q：log 模式和 standard 模式可以同时用吗？

**结论：不可以。** `trace_source` 为二选一互斥配置，由 `ADAPTER_TRACE_SOURCE` 或 YAML 决定，默认 `log`。切换需重启 adapter。

#### Q：standard 模式下业务 Agent 上报的 `gen_ai.prompt` 为什么是 Python repr 字符串？

**结论：业务 Agent 用 openjiuwen.tracer.otel 上报时，消息对象被 `repr()` 后存入属性。** adapter 在 `spans_to_records` 中用 `repr_extract.parse_repr` 把 repr 串规整回原生 JSON 结构；不可解析的（如 `<...object at 0x...>`）保留原串，不抛异常。

#### Q：两种模式查询返回的记录格式一样吗？

**结论：一样。** 这是 `trace_source/base.py` 的设计契约——两子类产出同构 record，下游 `trace_assembler`/`trace_cleaner` 零改动。差异仅在 input/output 值的来源路径与不完整标记机制。

#### Q：`complete` 参数在两种模式下行为一致吗？

**结论：不一致。** standard 模式按会话级根 span 信号整体过滤（信号与请求一致则保留全部，否则空）；log 模式按记录级 `_incomplete` 逐条过滤。不要混用。

#### Q：standard 模式 Kafka 不可达时服务还能用吗？

**结论：能读不能写。** Kafka 不可达仅告警，app 仍启动、`consumer=None`，API 仍可读已落库 Postgres 的 spans；但新轨迹不会进入。PG 不可达则是致命错误，服务无法正常提供轨迹查询。
