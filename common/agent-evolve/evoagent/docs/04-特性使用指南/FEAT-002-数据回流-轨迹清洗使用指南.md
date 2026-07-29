# 数据回流 — 轨迹清洗 使用指南

> 本指南介绍如何使用 **轨迹清洗** 从业务 Agent 轨迹记录中抽取结构化 LLM 对话。
>
> 配套文档：[数据回流 — 轨迹采集使用指南](FEAT-001-数据回流-轨迹采集使用指南.md)、[evoagent 部署指南](../02-部署指南/evoagent部署指南.md)。

---

## 1. 特性概览

### 1.1 这是什么

轨迹清洗把采集阶段产出的原始轨迹记录（TRACE / GENERATION / TOOL / SPAN 多种类型混杂、含 `usage_metadata`/`system` 等轨迹评估暂时不需要的信息），提炼为一份结构化 `messages` 对象，供自演进、轨迹评估等下游消费。清洗是一个**纯函数** `clean_traces()`，无 I/O、无 HTTP 依赖，在查询侧按 API 请求即时触发，不在采集管线中执行，也不写回归档或数据库。

两种采集模式（log / standard）产出的记录同构，因此 `clean_traces` 对模式无感，逻辑零差异。

### 1.2 本指南覆盖范围

本指南包含：

- 清洗机制与在管线中的位置；
- log 模式与 standard 模式下清洗前后数据格式与字段说明；
- 清洗规则/步骤、相关 API、配置项与配置生效方式；
- 清洗规则是否可自定义的说明。

本指南不包含：

- 采集链路与原始记录生成细节（见 [轨迹采集使用指南](FEAT-001-数据回流-轨迹采集使用指南.md)）；
- adapter 完整部署流程（见 [evoagent 部署指南](../02-部署指南/evoagent部署指南.md)）；
- 技能管理与业务 Agent 调用相关内容。

---

## 2. 什么时候使用

| 使用轨迹清洗 | 不使用轨迹清洗 |
|---|---|
| 需要把原始轨迹转成结构化 LLM 对话喂给演进训练 | 只需原始 span/record 明细 |
| 需要剔除 `usage_metadata`/`system` 等轨迹评估暂时不需要的信息 | 需要保留全部消息含元数据 |
| 需要从对话提炼 `task_input` 与工具调用清单 | 只关心最终 assistant 回复 |
| 需要两种采集模式下统一的对话产物 | — |

> 判断原则：当需要 *干净、可直接训练/评测的对话* 时使用本特性；需要原始过程细节时叠加 `轨迹采集` 的原始查询接口。

---

## 3. 准备工作

> adapter 采用容器方式部署，完整构建/启动/检查流程见 [evoagent 部署指南](../02-部署指南/evoagent部署指南.md)。本节仅介绍与轨迹清洗相关的部署配置要点。

### 3.1 环境要求

| 项目 | 要求 | 检查命令 |
|---|---|---|
| Docker Engine | 已安装并运行 | `docker version` |
| adapter 镜像 | 已构建或可拉取 | `docker images \| grep agent-adapter` |
| adapter 服务 | 已启动且 `trace_source` 就绪 | `curl http://localhost:8900/health` |
| 轨迹数据（log 模式） | `output_dir` 下存在 `{conversation_id}.jsonl` 归档 | `ls {OUTPUT_DIR}/*.jsonl` |
| 轨迹数据（standard 模式） | Postgres `spans` 表有该会话 span | `psql -h {PG_HOST} -c "select count(*) from spans where session_id='{CONV}'"` |
| 业务 Agent | 该会话至少上报了一条 LLM 调用（`llm.*` span） | 见采集指南 6.4 映射规则 |

### 3.2 准备镜像与配置

```bash
cd agent-solution/common/agent-evolve/evoagent-adapter/deployment
cp config/.env.example config/.env          # 复制后编辑 config/.env 填主机路径
./start.sh --build                          # 首次启动需 --build 构建镜像（详见部署指南）
```

> `.env` 主机路径项与镜像构建细节见 [轨迹采集使用指南](FEAT-001-数据回流-轨迹采集使用指南.md) 3.2 与 [evoagent 部署指南](../02-部署指南/evoagent部署指南.md)；运行时不需 Python/uv。

### 3.3 启动服务与检查

清洗随 adapter 容器一起提供，无独立进程。以容器方式启动 adapter 即可（具体参数见部署指南）：

```bash
# log 模式（默认）或 standard 模式（.env 中设 ADAPTER_TRACE_SOURCE=standard 等）
./start.sh
```

启动后检查：

```bash
docker ps --filter name=agent-adapter                 # 容器应为 Running
curl -s http://localhost:8900/api/v1/status           # 服务状态，确认 trace_source 就绪
```

### 3.4 条件性准备

| 使用场景 | 额外资源 | 准备方式 |
|---|---|---|
| log 模式清洗 | 归档目录 | 配置 `output_dir`，确保 `poll` 已生成 `{conv}.jsonl` |
| standard 模式清洗 | Postgres 数据 | 确保 `trace_wait_timeout` 内根 span 已落库 |

---

## 4. 快速上手

本节给出两种模式下各自的最小可验证闭环。

### 4.1 准备输入

确保目标会话已通过数据回流模块，并采集到至少一条 `type=GENERATION` 的记录：

- **log 模式**：业务 Agent 日志含 LLM 调用 START/END，经 `trace_assembler` 配对后写入 `{conv}.jsonl`；
- **standard 模式**：业务 Agent 上报了 `llm.*` span，含 `gen_ai.prompt`/`gen_ai.completion` 属性，已入 Postgres。

### 4.2 执行操作

```bash
curl -X GET 'http://localhost:8900/api/v1/agents/{AGENT_NAME}/cleaned-traces/{CONVERSATION_ID}'
```

> `{AGENT_NAME}` 替换为实际业务 Agent 名称，`{CONVERSATION_ID}` 替换为会话 ID（即 `session_id`）。

### 4.3 查看结果

成功且存在 GENERATION 记录时返回：

```json
{
  "session_id": "tests-conv-0004",
  "agent_name": "business_agent",
  "task_input": "推荐理财产品",
  "trajectory": { "total_messages": 5, "tool_calls_used": ["sample_tool_1"], "summary": "5 messages, 1 unique tools: sample_tool_1" },
  "messages": [
    {"role": "user", "content": "推荐一款示例产品"},
    {"role": "assistant", "content": "好的，我来为您推荐", "tool_calls": [{"function": {"name": "sample_tool_1"}}]},
    {"role": "tool", "name": "sample_tool_1", "content": "{\"result\": \"...\"}"},
    {"role": "assistant", "content": "根据结果推荐如下"},
    {"role": "assistant", "content": "最终回答"}
  ]
}
```

若无 GENERATION 记录则返回空对象：

```json
{}
```

> 清洗在请求时即时计算并通过 HTTP 返回，`clean_traces` 与 `cleaned-traces` 接口均**不写日志**记录清洗结果；确认结果以接口返回值为准（见 4.4）。

### 4.4 验证是否成功

```bash
# 先确认会话有原始记录（calls 中存在 type=GENERATION）
curl -X GET 'http://localhost:8900/api/v1/traces/tests-conv-0004'
```

预期结果（`calls` 中存在 `type=GENERATION`）：

```text
{"conversation_id":"tests-conv-0004","calls":[{"type":"GENERATION", ...}],"total":3,"complete":true}
```

> 完成本节后，你应该已经能够独立跑通：确认原始记录存在 → 调用清洗接口 → 拿到结构化对话。

---

## 5. 接口与配置

### 5.1 接口清单

清洗相关接口定义于 `src/agent_adapter/api/routes.py`，调用前会先 `pipeline.poll()` 触发一次增量采集，再取记录调 `clean_traces`。

| 方法 | 路径 | 作用 | 适用场景 |
|:--:|---|---|---|
| `GET` | `/api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}` | 取清洗后结构化对话 | 两种模式 |
| `GET` | `/api/v1/agents/{agent_name}/traces/{conversation_id}` | 取原始记录（对照清洗前） | 两种模式 |
| `GET` | `/api/v1/traces/{conversation_id}` | 取原始记录 + complete 信号 | 两种模式 |
| `GET` | `/api/v1/status` | 服务状态 | 排查 |

> 清洗无独立 CLI 子命令，仅通过 HTTP 接口按需触发。

### 5.2 请求参数

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `agent_name` | str | 是（路径） | 无 | Agent 名称 |
| `conversation_id` | str | 是（路径） | 无 | 会话 ID，等于 `session_id` |

> `cleaned-traces` 接口不接受 `complete`/`limit`；它总是基于该会话最后一条 GENERATION 记录清洗。

### 5.3 配置项

清洗相关配置定义于环境变量前缀 `ADAPTER_`。清洗本身对模式无感、无独有配置项；以下为影响清洗输入与行为的上游配置。

**轨迹清洗/查询相关：**

| 配置项 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `trace_source` | `log`/`standard` | 否 | `log` | 模式选择，决定清洗输入来源 |
| `trace_wait_timeout` | float | 否 | `10.0` | standard 模式等根 span 秒数，影响清洗输入是否就绪 |
| `pair_timeout` | int | 否 | `300` | START→END 配对超时秒（log 模式影响 `_incomplete` 标记） |
| `match_tags` | list[str] | 否 | 11 个 TAG_* | 决定哪些标签进入清洗上游记录（log 模式） |
| `poll_interval` | int | 否 | `60` | 采集轮询间隔，影响记录新鲜度 |
| `output_dir` | str | 否 | `data/output` | 归档目录（log 模式清洗输入来源） |

> **填写说明：** 以上字段对应环境变量 `ADAPTER_` + 字段名大写（如 `trace_wait_timeout` → `ADAPTER_TRACE_WAIT_TIMEOUT`、`pair_timeout` → `ADAPTER_PAIR_TIMEOUT`），在 `config/.env` 填写，优先级：环境变量 > `agent_adapter_config.yaml` > 默认值；`.env` 主机路径项填写见 [轨迹采集使用指南](FEAT-001-数据回流-轨迹采集使用指南.md) 3.2。
> - log 模式：按需调 `pair_timeout`（START/END 配对超时，过小会产生 `_incomplete` 不完整记录）。
> - standard 模式：按需调 `trace_wait_timeout`（查询等根 span 秒数，过小会过早返回 `complete=false`）。
> - `match_tags` 决定哪些日志标签进入清洗上游记录，仅 log 模式生效；环境变量用 JSON 数组或逗号分隔串。

### 5.4 配置生效方式

修改配置的生效方式取决于配置层级：

| 配置层级 | 生效方式 | 说明 |
|---|---|---|
| per-agent 配置（`agents` 列表的 `name`/`log_dir`/`output_dir`/`skills_dir` 等） | **热生效** | 通过 `POST/PUT/DELETE /api/v1/config/agents` 修改，立即重建对应 Pipeline/AgentClient，无需重启；改动写回 `agent_adapter_config.yaml` |
| 顶层/模式配置（`trace_source`、`pg_*`、`kafka_*`、`pair_timeout`、`trace_wait_timeout`、`match_tags`、`poll_interval` 等） | **重启容器生效** | 这些在启动时（lifespan）初始化 trace_source/repo/consumer/pipeline，改 `.env` 或 YAML 后需 `./start.sh` 重启或 `docker restart` |
| 环境变量（`ADAPTER_*`） | **重启容器生效** | 仅启动时读取 |

> `agent_adapter_config.yaml` 经配置 CRUD 写回 `HOST_CONFIG_FILE` 指向的宿主文件，跨容器重建不丢失。`trace_source` 切换必须重启（log↔standard 涉及不同 trace_source/repo/consumer 初始化）。

### 5.5 模式与变体

`clean_traces` 本身对模式无感，两模式产出同构 record，清洗逻辑零差异。差异仅在**输入侧**：

| 维度 | log 模式 | standard 模式 |
|---|---|---|
| 记录来源 | `LogTraceSource` 读 `{conv}.jsonl` 归档 | `DbTraceSource` 从 PG 取 spans 经 `spans_to_records` 转换 |
| GENERATION input/output 值来源 | 日志 message payload 已是 dict | `gen_ai.prompt`/`gen_ai.completion` 为 Python repr 串，经 `repr_extract.parse_repr` 规整 |
| 不完整标记 | 有 `_incomplete`/`_incomplete_reason` | 无；完整性由根 span `end_time` 判定 |
| `/cleaned-traces` 行为 | `clean_traces(records)` | 同 |
| 模式切换 | `trace_source: log` | `trace_source: standard`，需 PG+Kafka 消费者 |
| 失败容忍 | 无外部依赖 | PG 连不上=致命；Kafka 连不上仅告警，仍可读已落库 spans |

选择建议：

- 已用 *log 模式采集*：清洗直接生效，无需额外配置；
- 已用 *standard 模式采集*：清洗同样生效，注意 `trace_wait_timeout` 是否够等待根 span；
- 不确定时：清洗行为两模式一致，按采集模式选择即可。

### 5.6 请求示例

```bash
# 取清洗后结构化对话
curl -X GET 'http://{HOST}:8900/api/v1/agents/{AGENT_NAME}/cleaned-traces/{CONVERSATION_ID}'

# 对照清洗前原始记录
curl -X GET 'http://{HOST}:8900/api/v1/traces/{CONVERSATION_ID}'
```

### 5.7 返回结果

**清洗后（`cleaned-traces` 返回）：**

```json
{
  "session_id": "tests-conv-0004",
  "agent_name": "business_agent",
  "task_input": "推荐理财产品",
  "trajectory": { "total_messages": 5, "tool_calls_used": ["sample_tool_1"], "summary": "5 messages, 1 unique tools: sample_tool_1" },
  "messages": [ { "...消息..." : "..." } ]
}
```

清洗后字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `session_id` | str | 透传自请求入参 |
| `agent_name` | str | 透传自请求入参 |
| `task_input` | str | 第一条 `role=user` 的 `content` |
| `trajectory.total_messages` | int | 拼接后消息总数（input.messages + output） |
| `trajectory.tool_calls_used` | list[str] | 去重排序后的工具名 |
| `trajectory.summary` | str | 摘要串 |
| `messages` | list[dict] | 过滤后消息，仅保留 user/assistant/tool，剔除 `usage_metadata` |

**清洗前（原始 record，`traces` 接口返回）——`clean_traces` 只消费 GENERATION 记录：**

| 字段 | 类型 | 说明 | 示例值 |
|---|---|---|---|
| `type` | str | 固定 `GENERATION` | `"GENERATION"` |
| `id` | str | span_id / call_id | `"0878738cabc247ef"` |
| `trace_id` | str | 链路 ID | `"e0d09d4e..."` |
| `session_id` | str | 会话 ID | `"tests-conv-0004"` |
| `start_time` / `end_time` | str | 调用起止（ISO） | `"2026-07-22T14:50:24.953822+00:00"` |
| `input.messages` | list[dict] | LLM 输入消息，含 `usage_metadata` 等待清洗字段 | `[{"role":"user","content":"...","metadata":{...}}]` |
| `output` | dict | LLM 最终输出（最后一条 assistant 回复） | `{"role":"assistant","content":"...","tool_calls":[...]}` |
| `_incomplete` / `_incomplete_reason` | bool/str | 不完整标记（log 模式特有） | `true` / `pair_timeout` |

> `clean_traces` 不消费 TRACE/TOOL/SKILL/SPAN 记录；它们仅出现在 `traces` 原始接口返回中。

### 5.8 状态码与异常

| 状态码/现象 | 含义 | 常见原因 | 处理方式 |
|---|---|---|---|
| `200` + `{}` | 正常但无 GENERATION | 该会话只有 TOOL/SPAN 或 llm span 缺 prompt/completion 被跳过 | 确认业务 Agent 上报了完整 LLM 调用 |
| `200` + 清洗对象 | 正常 | 至少有一条 GENERATION | — |
| `404` | Agent 不存在 | `agent_name` 未配置 | 检查 `agents` 配置 |
| `500` | 内部错误 | `trace_source` 未初始化或 PG 异常 | 看 `trace_source` 是否就绪 |
| 记录带 `_incomplete:true` | log 模式不完整 | 超时/孤儿/PID 切换 | 调大 `pair_timeout`；`/traces?complete=true` 会过滤掉 |

### 5.9 清洗规则的可定制性

**当前不支持通过配置自定义清洗规则。** `clean_traces`是一个纯函数，清洗规则固定，未提供任何清洗规则相关配置项。固定规则为：

- 定位最后一条 `type==GENERATION` 记录；
- 拼接 `input.messages + [output]`；
- 仅保留 `role` 为 `user`/`assistant`/`tool` 的消息；
- 从每条消息剔除 `usage_metadata` 键；
- 提取 `task_input`（首条 user 消息 content）与去重排序的工具名。

---

## 6. 场景用例

### 6.1 基础用例：log 模式取清洗后对话

适用于：单机部署、需要结构化对话做评测。

```bash
# 确认归档已生成
curl -X GET 'http://localhost:8900/api/v1/traces/tests-conv-0004'

# 取清洗后对话（{AGENT_NAME} 替换为业务 Agent 名称）
curl -X GET 'http://localhost:8900/api/v1/agents/{AGENT_NAME}/cleaned-traces/tests-conv-0004'
```

预期结果：

```json
{
  "session_id": "tests-conv-0004",
  "agent_name": "business_agent",
  "task_input": "推荐理财产品",
  "trajectory": { "total_messages": 5, "tool_calls_used": ["sample_tool_1"], "summary": "5 messages, 1 unique tools: sample_tool_1" },
  "messages": [ {"role":"user","content":"推荐一款示例产品"} ]
}
```

### 6.2 standard 模式取清洗后对话

适用于：跨服务、OTel 体系下的统一对话产物。

```bash
# .env: ADAPTER_TRACE_SOURCE=standard、ADAPTER_NETWORK=openjiuwen-net（重启生效）
./start.sh

# 业务 Agent 上报 OTLP → Kafka → Postgres
curl -X GET 'http://localhost:8900/api/v1/agents/{AGENT_NAME}/cleaned-traces/tests-conv-0004'
```

> standard 模式下 `cleaned-traces` 会先 `poll()` 触发消费，再等根 span 至 `trace_wait_timeout`。两模式输出结构完全一致——这是 `spans_to_records` 的设计契约。

### 6.3 数据结构变体：清洗前后格式对照

适用于：需要理解清洗具体做了什么。

**清洗前**（原始 GENERATION record，`input.messages` 元素）：

```json
{
  "role": "user",
  "content": "推荐理财产品",
  "name": null,
  "metadata": {"context_message_id": "..."},
  "usage_metadata": {"input_tokens": 120, "output_tokens": 80}
}
```

**清洗后**（`messages` 元素，已剔除 `usage_metadata`/`name`/`metadata` 等轨迹评估暂时不需要的字段）：

```json
{"role": "user", "content": "推荐理财产品"}
```

**完整清洗前后字段对照：**

| 维度 | 清洗前（原始 record） | 清洗后（cleaned 对象） |
|---|---|---|
| 顶层结构 | record 列表（TRACE/GENERATION/TOOL/...） | 单一对象 `{session_id, agent_name, task_input, trajectory, messages}` |
| 消息来源 | 多条 record 混杂 | 仅最后一条 GENERATION 的 `input.messages` + `output` |
| 消息角色 | 全部角色（含 `system`） | 仅 `user`/`assistant`/`tool` |
| 非必要字段 | 含 `usage_metadata`/`metadata`/`name` 等 | 已剔除 `usage_metadata` |
| 工具信息 | 散落在 `tool` 消息 `name` | 汇聚为 `trajectory.tool_calls_used`（去重排序） |
| 任务输入 | 需从首条 user 消息提取 | 直接提供 `task_input` |
| 摘要 | 无 | `trajectory.summary` |
| 不完整标记 | log 模式 record 带 `_incomplete` | 无（清洗不处理完整性，由查询接口 `complete` 过滤） |

**清洗规则/步骤**（`trace_cleaner.py`）：

| 步骤 | 规则 |
|---|---|
| 1 | 倒序遍历记录，定位最后一条 `type==GENERATION`；找不到返回 `{}` |
| 2 | 消息拼接：`messages = input.messages + [output]`（output 非空时作为最后一条 assistant） |
| 3 | 提取 `task_input`：第一条 `role==user` 的 `content` |
| 4 | 工具名收集：遍历 `role==tool` 消息的 `name`，set 去重后排序 |
| 5 | 角色过滤：只保留 `user`/`assistant`/`tool`，丢弃 `system` 等 |
| 6 | 字段剔除：从每条消息移除 `usage_metadata` 键 |
| 7 | 摘要组装：`f"{n} messages, {m} unique tools: ..."` |

> 清洗不做排序、配对、补全——这些在更上游完成（log 模式由 `trace_assembler` 的 START/END 合并；standard 模式由 `spans_to_records` 按 `start_time` 升序）。

---

## 7. 常见问题

### 7.1 故障排查表

| 现象 | 原因 | 处理方式 |
|---|---|---|
| `cleaned-traces` 返回 `{}` | 无 `type=GENERATION` 记录 | 确认业务 Agent 上报了完整 LLM 调用（llm span 含 `gen_ai.prompt`/`gen_ai.completion`） |
| `messages` 缺少 `system` 消息 | 清洗规则只保留 user/assistant/tool | 属预期行为；如需 system 提示，从原始 `traces` 接口取 |
| `task_input` 为空 | 首条 user 消息 content 为空 | 检查业务 Agent 是否在首条 user 消息携带内容 |
| record 带 `_incomplete:true`（log） | 超时/孤儿/PID 切换 | 调大 `pair_timeout`；`/traces?complete=true` 会过滤掉 |
| `_incomplete_reason:"pair_timeout"`（log） | START 等 END 超时 | 调大 `pair_timeout`（默认 300s） |
| `_incomplete_reason:"pid_switch"`（log） | 业务 Agent 重启致日志文件切换 | 属预期 flush；旧文件 offset 标 completed |
| `parse_failure_rate_high` 告警（log） | 日志行解析失败率 >50% | 检查日志格式是否变更、`\x01` 分隔是否完整 |
| standard 模式清洗输入为空 | 根 span 尚未落库 | 调大 `trace_wait_timeout`；确认业务 Agent 已上报根 span |
| standard 模式 `gen_ai.prompt` 是 repr 串 | 业务 Agent 上报时对象被 `repr()` | adapter 自动用 `repr_extract` 规整；不可解析的保留原串 |
| 改了 `pair_timeout`/`trace_wait_timeout` 不生效 | 顶层配置需重启 | 重启 adapter 容器（`./start.sh` 或 `docker restart`） |

### 7.2 常见问答

#### Q：清洗会修改原始归档或数据库里的数据吗？

**结论：不会。** `clean_traces` 是纯函数，在查询时即时计算并返回，不写回归档、不写回 Postgres，也不写日志。原始记录始终保持不变。

#### Q：为什么清洗只取最后一条 GENERATION？

**结论：设计如此。** 自演进训练/评测关心的是本次会话最后一次 LLM 调用的完整对话（含历史消息上下文）。最后一条 GENERATION 的 `input.messages` 已包含累积的对话历史，叠加其 `output` 即为完整对话。更早的 GENERATION 是中间态，不重复抽取。

#### Q：两种模式下清洗输出一致吗？

**结论：一致。** `clean_traces` 对模式无感。`spans_to_records` 的设计目标就是让 standard 模式产出与 log 模式同构的 record，因此清洗逻辑零差异。差异仅在输入侧值来源（log 已是 dict，standard 需 repr 规整）。

#### Q：可以通过配置自定义清洗规则吗（如保留 system 消息、剔除其他字段）？

**结论：不可以。** 清洗规则固定在 `trace_cleaner.py` 的 `clean_traces` 中，`config.py` 无任何清洗规则配置项。如需调整，须修改源码并重新构建镜像、重启容器（详见 5.9）。

#### Q：清洗后 `messages` 里的 `tool` 消息 `content` 是什么？

**结论：是工具调用的返回结果（字符串化的 JSON）。** 例如 `{"role":"tool","name":"sample_tool_1","content":"{\"result\": \"...\"}"}`。工具名同时汇聚到 `trajectory.tool_calls_used`。
