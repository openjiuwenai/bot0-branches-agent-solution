# POST /evaluate — 轨迹评估接口

## 概述

同步评估一条 Agent 会话轨迹。支持可选的**确定性过滤层**，在调用 LLM 之前识别工具失败和用户纠正信号，对已知坏例直接短路返回零分结果。

```
POST {base_url}/evaluate
Content-Type: application/json
```

评估器模块还提供一个优化器辅助服务，用于从内联轨迹消息中生成用户最终目标：

```
POST {base_url}/evaluate/generate-goal
Content-Type: application/json
```

`/evaluate/generate-goal` 只生成优化器使用的中文自然语言目标，不执行评分，也不会把生成结果写入 `/evaluate` 的 `expected_result`。

---

## 请求体 `EvaluateRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `trajectory_path` | `string` | ✅ | 轨迹 JSON 文件的服务器路径 |
| `prompt_template` | `string` | ✅ | LLM 评估 prompt 模板 |
| `llm_config` | `LLMConfig` | ✅ | LLM 模型配置 |
| `expected_result` | `object \| null` | — | 期望结果（可选，用于参考答案对比） |
| `filters` | `FilterConfig \| null` | — | 过滤层配置（可选，不传则不过滤） |

### `LLMConfig`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:---:|--------|------|
| `model_name` | `string` | ✅ | — | 模型名称 |
| `api_key` | `string` | ✅ | — | API 密钥 |
| `api_base` | `string` | ✅ | — | API 基地址 |
| `client_provider` | `string` | — | `"DashScope"` | 模型服务商 |
| `temperature` | `float` | — | `0.1` | 采样温度 |
| `max_tokens` | `int` | — | `2048` | 最大输出 token |
| `verify_ssl` | `bool` | — | `false` | 是否验证 SSL |

### `FilterConfig`

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `tool_failure` | `ToolFailureFilterConfig` | — | 工具失败过滤器 |
| `user_feedback` | `UserFeedbackFilterConfig` | — | 用户反馈过滤器 |

#### `ToolFailureFilterConfig`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:---:|--------|------|
| `enabled` | `bool` | — | `false` | 是否启用 |
| `patterns` | `string[] \| null` | — | `null` | 自定义正则追加到默认规则后 |
| `replace_default_patterns` | `bool` | — | `false` | `true` 时丢弃默认规则，只用自定义 |

**默认规则**（`enabled=true` 且 `replace_default_patterns=false` 时自动生效）：

| rule_id | 匹配内容 |
|---------|---------|
| `timeout` | `timeout`、`timed out`、`超时` |
| `failure` | `failed`、`failure`、`调用失败`、`执行失败`、`连接失败` |
| `exception` | `exception`、`发生异常` |
| `error` | `error` |

同时支持**结构化字段检测**（优先级高于正则）：
- `code != 0` → 失败
- `status` 为 `failed` / `failure` / `error` / `timeout` → 失败
- `success === false` → 失败
- `error` / `exception` 非空 → 失败
- `code === 0` 或 `status` 为 `success` / `ok` → **成功（抑制正则匹配）**

#### `UserFeedbackFilterConfig`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:---:|--------|------|
| `enabled` | `bool` | — | `false` | 是否启用 |
| `patterns` | `string[] \| null` | — | `null` | 自定义正则追加到默认规则后 |
| `replace_default_patterns` | `bool` | — | `false` | `true` 时丢弃默认规则，只用自定义 |
| `skip_initial_user_messages` | `int` | — | `1` | 跳过前 N 条用户消息（视为原始任务） |

**默认规则**：

| rule_id | 匹配内容 |
|---------|---------|
| `explicit_rejection` | `不对`、`错了`、`不是这样`、`这不正确` |
| `correction_instruction` | `你应该`、`你要这样`、`应该先`、`重新做`、`重新回答` |
| `unresolved_outcome` | `没有解决`、`还是不行`、`没有按要求` |

**只检查 user 角色消息**，assistant 和 tool 消息不检查。

---

## 响应体 `EvaluateResponse`

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | `"evaluated" \| "filtered"` | 评估状态 |
| `score` | `float` | 综合得分 `[0, 1]`，filtered 时固定 `0.0` |
| `per_metric` | `object \| null` | 各维度得分，filtered 时为 `{"filter_failure": 0.0}` |
| `reason` | `string` | 评估理由（自由文本或 JSON） |
| `skill_attributions` | `object[]` | Skill 归因列表 |
| `filter_matches` | `FilterMatchResponse[]` | 过滤匹配详情，evaluated 时为空数组 |

### `FilterMatchResponse`

| 字段 | 类型 | 说明 |
|------|------|------|
| `filter_type` | `"tool_failure" \| "user_feedback"` | 过滤器类型 |
| `rule_id` | `string` | 命中的规则 ID |
| `message_index` | `int` | 命中的消息在轨迹中的索引（从 0 开始） |
| `evidence` | `string` | 匹配证据文本（最长 500 字符） |
| `pattern` | `string \| null` | 命中的正则表达式（结构化检测时为 `null`） |

---

## 状态码

| 状态码 | 含义 |
|--------|------|
| `200` | 评估成功（包括 filtered 短路，都是正常返回） |
| `404` | `trajectory_path` 文件不存在 |
| `422` | 轨迹格式无效，或 filter 配置无效（如非法正则） |
| `500` | LLM 评估过程报错（连接失败、模型调用失败等） |

---

## 请求示例

### 1. 仅 LLM 评估（不过滤）

```json
{
  "trajectory_path": "/data/trajectories/session_001.json",
  "prompt_template": "{trajectory_section}",
  "llm_config": {
    "model_name": "qwen-plus",
    "api_key": "sk-xxx",
    "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1"
  }
}
```

### 2. LLM 评估 + 开启全部过滤

```json
{
  "trajectory_path": "/data/trajectories/session_001.json",
  "prompt_template": "{trajectory_section}",
  "llm_config": {
    "model_name": "qwen-plus",
    "api_key": "sk-xxx",
    "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1"
  },
  "filters": {
    "tool_failure": { "enabled": true },
    "user_feedback": { "enabled": true }
  }
}
```

### 3. 自定义过滤规则

```json
{
  "trajectory_path": "/data/trajectories/session_001.json",
  "prompt_template": "{trajectory_section}",
  "llm_config": {
    "model_name": "qwen-plus",
    "api_key": "sk-xxx",
    "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1"
  },
  "filters": {
    "tool_failure": {
      "enabled": true,
      "patterns": ["服务不可用", "限流"]
    },
    "user_feedback": {
      "enabled": true,
      "patterns": ["请调整方向", "换个思路"],
      "skip_initial_user_messages": 2
    }
  }
}
```

---

## 响应示例

### 被过滤的结果（短路，不调用 LLM）

```json
{
  "status": "filtered",
  "score": 0.0,
  "per_metric": {
    "filter_failure": 0.0
  },
  "reason": "{\"reason\": \"Trajectory matched pre-evaluation filter rules.\", \"status\": \"filtered\", ...}",
  "skill_attributions": [],
  "filter_matches": [
    {
      "filter_type": "user_feedback",
      "rule_id": "explicit_rejection",
      "message_index": 2,
      "evidence": "不对，我说了要稳健型的，你怎么推荐高风险的",
      "pattern": "不对|错了|不是这样|这不正确"
    }
  ]
}
```

### 正常 LLM 评估结果

```json
{
  "status": "evaluated",
  "score": 0.75,
  "per_metric": {
    "outcome_completion": 0.75,
    "context_tracking": 1.0,
    "interaction_quality": 0.5,
    "trajectory_quality": 0.75,
    "execution_safety": 1.0
  },
  "reason": "Agent 完成了理财推荐流程，但在选品环节...",
  "skill_attributions": [
    {
      "skill_name": "product_select_skill",
      "usage_status": "executed",
      "reason": "选品确认话术参数传递有误"
    }
  ],
  "filter_matches": []
}
```

---

## 前端调用逻辑建议

```javascript
async function evaluateTrajectory(trajectoryPath, llmConfig, options = {}) {
  const body = {
    trajectory_path: trajectoryPath,
    prompt_template: options.promptTemplate || "{trajectory_section}",
    llm_config: llmConfig,
  };

  // 可选：期望结果
  if (options.expectedResult) {
    body.expected_result = options.expectedResult;
  }

  // 可选：过滤层
  if (options.enableFilters) {
    body.filters = {
      tool_failure: { enabled: true },
      user_feedback: { enabled: true },
    };
  }

  const res = await fetch(`${API_BASE}/evaluate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const err = await res.json();
    throw new Error(err.detail);
  }

  const result = await res.json();

  // 根据 status 区分处理
  if (result.status === "filtered") {
    // 坏例：不展示维度分，展示过滤原因
    console.log("Filtered:", result.filter_matches);
  } else {
    // 正常评估：展示维度分数
    console.log("Score:", result.score, result.per_metric);
  }

  return result;
}
```

---

## POST /evaluate/generate-goal — 轨迹目标生成接口

### 概述

基于一条 Agent 交互轨迹的内联 `messages`，综合生成当前用户期望获得的一个中文自然语言目标。该目标供优化器消费，用于理解当前轨迹中用户最终想达成的业务结果和必要交互流程。

该接口不会评估轨迹质量，不返回分数，不修改现有 `/evaluate` 的评估输入。

### 请求体 `GenerateGoalRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `messages` | `object[]` | ✅ | Agent 交互轨迹消息，不能为空 |
| `llm_config` | `LLMConfig` | ✅ | LLM 模型配置，沿用 `/evaluate` |

`messages` 中每条消息至少需要 `role`。接口会读取以下字段并忽略未知字段：

- `role`
- `content`
- `name`
- `tool_calls`
- `tool_call_id`
- `reasoning_content`
- `metadata`

目标生成 prompt 不会使用 `reasoning_content` 和 `metadata`。工具调用和工具结果可作为上下文，但目标不会复述工具偶然返回值、内部脚本路径、错误码或中间状态。

### 响应体 `GenerateGoalResponse`

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | `"generated"` | 生成状态 |
| `goal` | `string` | 中文自然语言用户目标 |
| `metadata` | `object` | 调试信息，第一版包含 `reason` 和 `confidence` |

### 状态码

| 状态码 | 含义 |
|--------|------|
| `200` | 目标生成成功 |
| `422` | 请求体格式无效、`messages` 为空、或 message 缺少 `role` |
| `500` | LLM 调用失败、LLM 返回非 JSON、或 JSON 缺少合法 `goal` |

### 请求示例

```json
{
  "messages": [
    {
      "role": "user",
      "content": "查询余额。"
    },
    {
      "role": "assistant",
      "content": "根据规则直接查询默认卡余额。",
      "tool_calls": [
        {
          "id": "call_1",
          "type": "function",
          "function": {
            "name": "call_versatile",
            "arguments": "{\"query_description\":\"查询活期账户余额\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_1",
      "content": "{\"balance\":\"125,680.50\"}"
    },
    {
      "role": "user",
      "content": "不对，你要先问我选择哪个卡号再查询。"
    }
  ],
  "llm_config": {
    "model_name": "qwen-plus",
    "api_key": "sk-xxx",
    "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "client_provider": "OpenAI",
    "temperature": 0.1,
    "max_tokens": 2048,
    "verify_ssl": false
  }
}
```

### 响应示例

```json
{
  "status": "generated",
  "goal": "用户期望在查询银行卡余额时，系统先确认要查询的具体卡号；如果用户未指定卡号，应先询问用户选择哪张卡，再根据所选卡号查询并反馈余额。",
  "metadata": {
    "reason": "轨迹中用户先提出查询余额，随后指出应先询问卡号，说明最终目标包含卡号确认流程。",
    "confidence": 0.9
  }
}
```

**关键判断**：用 `status` 字段区分结果类型，**不要**用 `score === 0.0` 判断是否为过滤结果（正常评估也可能得 0 分）。

---

## 轨迹文件格式

`trajectory_path` 指向的 JSON 文件结构：

```json
{
  "messages": [
    {
      "role": "user",
      "content": "帮我推荐理财产品"
    },
    {
      "role": "assistant",
      "content": "为您推荐以下产品...",
      "tool_calls": [
        {
          "id": "call_001",
          "function": { "name": "call_versatile", "arguments": "{...}" }
        }
      ]
    },
    {
      "role": "tool",
      "content": "{\"status\": \"success\", ...}",
      "tool_call_id": "call_001"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | `string` | `user` / `assistant` / `tool` |
| `content` | `string \| object` | 消息内容 |
| `tool_calls` | `array` | assistant 发起的工具调用（可选） |
| `tool_call_id` | `string` | tool 回复对应的调用 ID（可选） |
