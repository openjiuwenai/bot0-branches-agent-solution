# Adapter Sidecar API 契约

> **消费方**：EvoAgent（`AdapterClient`）
>
> **提供方**：Adapter sidecar（每业务 Agent 部署一个）
>
> 本文档定义 EvoAgent 作为消费方对 Adapter sidecar 的接口要求。Adapter 负责适配各业务 Agent 的协议差异，对 EvoAgent 暴露统一 HTTP 接口。

---

## 部署模型

| 接口类别 | Adapter : Agent 关系 | 路由标识 |
|---------|---------------------|---------|
| Skill 操作 | 1 : N（一个 Adapter 可管理多个 Agent 的 skill） | `agent_name` 在请求体中指定 |
| 对话执行 | 1 : N（一个 Adapter 可代理多个 Agent 的对话） | `agent_name` 在路径中指定 |
| 轨迹收集 | 1 : N（一个 Adapter 可查询多个 Agent 的轨迹） | `agent_name` 在路径中指定 |

**通用约定**：

- Adapter 以多 Agent 模式运行，所有路径均包含 `agent_name`
- Base URL：由 `AdapterClient(adapter_url=...)` 指定
- Content-Type：`application/json`
- 错误响应统一格式见[错误处理](#错误处理)

---

## 总览

| # | 操作 | 方法 | 路径 | 说明 |
|---|------|------|------|------|
| 1 | Skill 列表 | POST | `/api/v1/skills` | 列出指定 Agent 的所有 Skill 名称 |
| 2 | Skill 内容 | POST | `/api/v1/skills` | 获取指定 Skill 的完整内容 |
| 3 | Skill 热更新 | POST | `/api/v1/skills` | 推送更新后的 Skill 文档 |
| 4 | Skill 恢复 | POST | `/api/v1/skills` | 将指定 Skill 恢复到优化前的快照 |
| 5 | 触发对话 | POST | `/api/v1/agents/{agent_name}/conversations/{conversation_id}` | 同步触发一次对话（SSE 消费） |
| 6 | 收集轨迹 | GET | `/api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}` | 获取清洗后的对话轨迹 |

---

## 1. Skill 操作

> **注意**：Skill 接口尚未在 Adapter 中实现，以下定义为预期契约，后续由 Adapter 补充实现。

### 1.1 Skill 列表

查询指定 Agent 当前注册的所有 Skill 名称。

**POST** `/api/v1/skills`

**Request**：

```json
{
  "agent_name": "edp_agent",
  "action": "skill_list"
}
```

**Response** `200 OK`：

```json
{
  "skills": [
    { "name": "product_recommend_skill" },
    { "name": "interact_finance_rec_skill" },
    { "name": "product_select_skill" },
    { "name": "fund_planning_skill" }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `skills` | `array` | ✅ | Skill 列表 |
| `skills[].name` | `string` | ✅ | Skill 名称（唯一标识） |

---

### 1.2 Skill 内容

获取指定 Skill 的完整文档内容（Markdown）。

**POST** `/api/v1/skills`

**Request**：

```json
{
  "agent_name": "edp_agent",
  "action": "skill_content",
  "skill_name": "product_recommend_skill"
}
```

**Response** `200 OK`：

```json
{
  "skill_name": "product_recommend_skill",
  "content": "# Product Recommend Skill\n\n## 触发条件\n..."
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `skill_name` | `string` | ✅ | Skill 名称 |
| `content` | `string` | ✅ | Skill 文档完整内容（Markdown） |

> **用途**：`build_skill_document_operator()` 用此接口获取 `initial_content`，确保优化器基于实际部署版本开始优化。

---

### 1.3 Skill 热更新

推送更新后的 Skill 文档到业务 Agent，即时生效。

**POST** `/api/v1/skills`

**Request**：

```json
{
  "agent_name": "edp_agent",
  "action": "update_skill",
  "skill_name": "product_recommend_skill",
  "skill_content": "# Product Recommend Skill\n\n## 触发条件\n...(updated)"
}
```

**Response** `200 OK`：

```json
{
  "success": true,
  "skill_name": "product_recommend_skill"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `success` | `boolean` | ✅ | 是否更新成功 |
| `skill_name` | `string` | ✅ | 被更新的 Skill 名称 |
| `message` | `string` | ❌ | 失败时的错误信息 |

> **触发时机**：`SkillDocumentOperator.on_parameter_updated` callback → `AdapterClient.update_skill()` → 此接口。每个 epoch 的 skill 更新会自动推送。

> **快照机制**：Adapter 在**首次**收到某个 Skill 的 `update_skill` 请求时（以 `run_id` 或 Adapter 会话为粒度），应自动将该 Skill 的**当前内容保存为快照**。后续同一 Skill 的 `update_skill` 不再覆盖快照。快照用于 [1.4 Skill 恢复](#14-skill-恢复) 接口还原。Adapter 应在日志中记录快照创建事件。

---

### 1.4 Skill 恢复

将指定 Skill 恢复到优化前的快照内容。批量操作，支持一次恢复多个 Skill。

**POST** `/api/v1/skills`

**Request**：

```json
{
  "agent_name": "edp_agent",
  "action": "restore_skill",
  "skill_names": ["product_recommend_skill", "interact_finance_rec_skill"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agent_name` | `string` | ✅ | 目标 Agent 名称 |
| `action` | `string` | ✅ | 固定值 `"restore_skill"` |
| `skill_names` | `array[string]` | ✅ | 待恢复的 Skill 名称列表 |

**Response** `200 OK`（全部成功）：

```json
{
  "restored": [
    { "skill_name": "product_recommend_skill", "success": true },
    { "skill_name": "interact_finance_rec_skill", "success": true }
  ]
}
```

**Response** `200 OK`（部分失败）：

```json
{
  "restored": [
    { "skill_name": "product_recommend_skill", "success": true },
    { "skill_name": "interact_finance_rec_skill", "success": false, "message": "未找到快照：该 Skill 未被更新过" },
    { "skill_name": "nonexistent_skill", "success": false, "message": "Skill 不存在" }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `restored` | `array` | ✅ | 每个 Skill 的恢复结果 |
| `restored[].skill_name` | `string` | ✅ | Skill 名称 |
| `restored[].success` | `boolean` | ✅ | 该 Skill 是否恢复成功 |
| `restored[].message` | `string` | ❌ | 失败时的原因说明 |

> **触发时机**：
> - 优化任务失败需要回滚时
> - 用户主动取消优化任务时
> - 验证门控判定 candidate 不如 base，需要还原中间推送的 skill 时
>
> **幂等性**：对同一 Skill 多次调用 `restore_skill` 应产生相同结果（快照不随恢复操作销毁）。
>
> **快照生命周期**：Adapter 可自行决定快照的过期策略（如 Adapter 重启后清除、TTL 过期等），但应保证在一次完整的优化任务生命周期内快照可用。

---

## 2. 对话执行（同步触发）

触发业务 Agent 执行一次对话（即一个 case 的 rollout）。采用同步模式：POST 触发后，Adapter 消费完整 SSE 流，待对话结束后同步返回完整结果。

**POST** `/api/v1/agents/{agent_name}/conversations/{conversation_id}`

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `agent_name` | `string` | 目标业务 Agent 名称（在 Adapter 配置中注册） |
| `conversation_id` | `string` | 本次对话的唯一 ID（由 EvoAgent 生成，通常为 case_id） |

**Request Body**：

```json
{
  "query": "帮我推荐稳健型理财产品",
  "extra_data": {
    "role_id": "1",
    "role_name": "mobile-bank"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | `string` | ✅ | 用户输入（case 的 query） |
| `extra_data` | `object` | ❌ | 额外键值对，将被合并到 `custom_data.inputs` 中转发给业务 Agent |

> **`extra_data` 语义**：Adapter 内部会将 `query` 和 `extra_data` 合并后组装为 `{ "custom_data": { "inputs": { "query": ..., ...extra_data } } }` 转发给业务 Agent。

**Response** `200 OK`（调用成功）：

```json
{
  "success": true,
  "conversation_id": "case-001",
  "answer": "根据您的风险偏好，我推荐以下产品：1. 稳健增利A，年化收益4.5%...",
  "interrupted": false,
  "interrupt_intent": null,
  "interrupt_description": null,
  "events": [
    { "type": "summary", "content": "根据您的风险偏好...", "plugin": null },
    { "type": "tool_start", "content": "调用 search_products", "plugin": "search_products" },
    { "type": "summary", "content": "根据搜索结果，我为您推荐...", "plugin": null }
  ],
  "error": null
}
```

**Response** `200 OK`（业务 Agent 调用失败）：

```json
{
  "success": false,
  "conversation_id": "case-001",
  "answer": "",
  "interrupted": false,
  "interrupt_intent": null,
  "interrupt_description": null,
  "events": null,
  "error": "无法连接 Agent 服务: Connection refused"
}
```

### Response 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `success` | `boolean` | ✅ | 调用是否成功（`false` 表示业务 Agent 不可达/超时/返回错误） |
| `conversation_id` | `string` | ✅ | 回传的对话 ID |
| `answer` | `string` | ✅ | 业务 Agent 的最终回复文本（由 SSE 事件中的 summary/final_answer_chunk 组装） |
| `interrupted` | `boolean` | ✅ | 业务 Agent 是否被 VA delegate 中断 |
| `interrupt_intent` | `string` | ❌ | 中断的意图（仅 interrupted=true 时有值） |
| `interrupt_description` | `string` | ❌ | 中断的任务描述（仅 interrupted=true 时有值） |
| `events` | `array` | ❌ | SSE 事件摘要列表（见 EventSummary 结构） |
| `error` | `string` | ❌ | 错误信息（仅 success=false 时有值） |

### events[] 结构（EventSummary）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `string` | ✅ | 事件类型（如 `summary`、`tool_start`、`tool_end`、`interrupt_start`、`final_answer_chunk`） |
| `content` | `string` | ✅ | 事件文本内容 |
| `plugin` | `string` | ❌ | 工具名称（仅 `tool_start`/`tool_end` 类型事件有值） |

> **用途**：
> - `answer` 替代原合同的 `predict.content`，用于评估器评分（`evaluator.evaluate(case, answer)`）
> - `events` 提供对话过程中的事件摘要，可用于调试和日志
> - `interrupted` 标记对话是否被中断，EvoAgent 可据此标记该 case 为无效或特殊处理
> - 详细轨迹（用于训练阶段的反思/归因）通过[轨迹收集接口](#3-轨迹收集清洗后)查询

---

## 3. 轨迹收集（清洗后）

获取对话执行的清洗后轨迹数据，包含过滤后的消息流（仅 user/assistant/tool 角色，去除 usage_metadata）。

**GET** `/api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}`

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `agent_name` | `string` | 业务 Agent 名称 |
| `conversation_id` | `string` | 对话 ID（与触发时一致） |

**Response** `200 OK`（有轨迹数据）：

```json
{
  "session_id": "case-001",
  "agent_name": "edp_agent",
  "task_input": "帮我推荐稳健型理财产品",
  "trajectory": {
    "total_messages": 6,
    "tool_calls_used": ["search_products"],
    "summary": "6 messages, 1 unique tools: search_products"
  },
  "messages": [
    {
      "role": "user",
      "content": "帮我推荐稳健型理财产品"
    },
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        { "id": "tc-001", "name": "search_products", "arguments": "{\"risk_level\": \"low\"}" }
      ]
    },
    {
      "role": "tool",
      "name": "search_products",
      "content": "[{\"name\": \"稳健增利A\", \"return_rate\": \"4.5%\"}]"
    },
    {
      "role": "assistant",
      "content": "根据搜索结果，我为您推荐两款稳健型产品：1. 稳健增利A，年化收益4.5%..."
    }
  ]
}
```

**Response** `200 OK`（无轨迹数据）：

```json
{}
```

### Response 字段说明

**顶层字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `session_id` | `string` | ✅ | 对话 ID |
| `agent_name` | `string` | ✅ | 业务 Agent 名称 |
| `task_input` | `string` | ✅ | 用户原始输入（第一条 role=user 消息的 content） |
| `trajectory` | `object` | ✅ | 轨迹摘要信息 |
| `messages` | `array` | ✅ | 过滤后的消息列表 |

**trajectory 字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `total_messages` | `integer` | ✅ | 消息总数 |
| `tool_calls_used` | `array[string]` | ✅ | 使用的工具名称列表（去重排序） |
| `summary` | `string` | ✅ | 人类可读的轨迹摘要 |

**messages[] 字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `role` | `string` | ✅ | 消息角色：`user` / `assistant` / `tool`（已过滤 system 等其他角色） |
| `content` | `string` | ✅ | 消息内容 |
| `tool_calls` | `array` | ❌ | 工具调用列表（仅 assistant 消息可能包含） |
| `name` | `string` | ❌ | 工具名称（仅 tool 角色消息） |

> **用途**：
> - `messages` 用于转换为 `Trajectory`（训练阶段的反思/归因）
> - `trajectory` 提供轨迹概览，辅助判断对话复杂度
> - 无 `usage_metadata` 字段（已在清洗时去除）

> **注意**：轨迹数据来源于 Adapter 的日志采集 Pipeline，对话完成后即可查询。如果日志尚未被采集/解析，可能返回空对象 `{}`。

---

## 错误处理

### Adapter 路由错误

当请求本身有误（路径参数错误、缺少必填字段等）时，Adapter 返回 FastAPI 标准错误格式：

```json
{
  "detail": "Agent 'nonexistent_agent' 不存在"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `detail` | `string` | ✅ | 错误描述（人类可读） |

### 业务 Agent 调用失败

当业务 Agent 不可达、超时或返回错误时，Adapter **仍返回 HTTP 200**，通过 Response Body 中的字段表达失败：

```json
{
  "success": false,
  "conversation_id": "case-001",
  "answer": "",
  "interrupted": false,
  "interrupt_intent": null,
  "interrupt_description": null,
  "events": null,
  "error": "无法连接 Agent 服务: Connection refused"
}
```

EvoAgent 应通过 `success` 字段判断调用是否成功，而非通过 HTTP 状态码。

### HTTP 状态码约定

| 状态码 | 场景 |
|--------|------|
| `200` | 请求成功（包括业务 Agent 调用失败的情况，通过 `success` 字段区分） |
| `400` | 请求参数错误（agent 未配置调用中转等） |
| `404` | 资源不存在（agent_name 不存在） |
| `422` | 请求体校验失败（Pydantic 验证错误） |

**已知错误场景**：

| 场景 | HTTP | detail 示例 | 说明 |
|------|------|-------------|------|
| agent_name 不存在 | 404 | `Agent 'xxx' 不存在` | agent_name 不在 Adapter 配置中 |
| agent 未配置调用中转 | 400 | `Agent 'xxx' 未配置 agent_url，不支持调用中转` | agent 未配置 agent_url |
| 请求体校验失败 | 422 | `无效的 Agent 配置: ...` | Pydantic 验证不通过 |
| 业务 Agent 不可达 | 200 | （在 `error` 字段中） | `success=false`，error 含连接错误信息 |
| 业务 Agent 超时 | 200 | （在 `error` 字段中） | `success=false`，error 含超时信息 |
| 业务 Agent 返回错误 | 200 | （在 `error` 字段中） | `success=false`，error 含 HTTP 状态码 |
| Skill 未找到快照 | 200 | （在 `restored[].message` 中） | `success=false`，该 Skill 未被 `update_skill` 更新过 |
| Skill 名称不存在 | 200 | （在 `restored[].message` 中） | `success=false`，Agent 无此 Skill |
| 快照已过期 | 200 | （在 `restored[].message` 中） | `success=false`，快照被 TTL/重启清除 |

---

## EvoAgent 消费方式

```
EvoAgent (AdapterClient)          Adapter Sidecar            业务 Agent
       │                                │                         │
       │ POST /api/v1/skills ─────────> │                         │
       │ { agent_name, action:          │                         │
       │   "skill_list" }               │                         │
       │ <── { skills: [...] } ──────── │ query agent registry    │
       │                                │                         │
       │ POST /api/v1/skills ─────────> │                         │
       │ { agent_name, action:          │                         │
       │   "skill_content",             │                         │
       │   skill_name }                 │                         │
       │ <── { content: "..." } ─────── │ read skill file         │
       │                                │                         │
       │ POST /api/v1/agents/           │                         │
       │   {agent_name}/conversations/  │                         │
       │   {conversation_id} ────────> │                         │
       │ { query, extra_data }          │ SSE stream ──────────> │
       │                                │ <── SSE events ──────── │
       │ <── 200 { success: true, ───── │                         │
       │          answer: "...",        │                         │
       │          events: [...] }       │                         │
       │                                │                         │
       │ GET /api/v1/agents/            │                         │
       │   {agent_name}/cleaned-traces/ │                         │
       │   {conversation_id} ────────> │                         │
       │ <── 200 { session_id, ──────── │ read trace archive      │
       │          trajectory,           │                         │
       │          messages: [...] }     │                         │
       │                                │                         │
       │ POST /api/v1/skills ─────────> │ snapshot if first ──>   │
       │ { agent_name, action:          │ update per skill        │
       │   "update_skill",              │                         │
       │   skill_name, skill_content }  │ write skill ──────────> │
       │ <── { success: true } ──────── │                         │
       │                                │                         │
       │ POST /api/v1/skills ─────────> │                         │
       │ { agent_name, action:          │ restore from snapshot   │
       │   "restore_skill",             │                         │
       │   skill_names: [...] }         │ write skill ──────────> │
       │ <── { restored: [...] } ────── │                         │
```

### 典型 Rollout 流程

1. **Skill 读取**：`skill_list` → `skill_content` 获取当前 Skill 文档
2. **Skill 更新**：优化完成后 → `update_skill` 推送新 Skill 文档（Adapter 首次收到时自动快照）
3. **触发对话**：POST 对话 → 同步等待 SSE 完成 → 拿到 `answer`（用于评估）+ `events`（调试）
4. **轨迹查询**：GET cleaned-traces → 拿到 `messages`（用于反思/归因/训练）
5. **Skill 恢复**：优化失败/取消/门控拒绝时 → `restore_skill` 批量还原到优化前状态
