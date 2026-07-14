# POST /evaluate — 客户端集成开发文档

评估一条 Agent 会话轨迹，返回三维度评分、通过/不通过判定、Skill 归因和过滤结果。

> **面向客户端开发者**：本文档描述如何调用 EvoAgent 评估接口，包含请求/响应完整 schema、轨迹文件格式、prompt 模板占位符规则及代码示例。

---

## 1. 服务启动与接入

```bash
make serve   # uvicorn evo_agent.api.app:app --host 0.0.0.0 --port 8000
```

| 项 | 值 |
|---|---|
| 协议 | HTTP |
| 默认端口 | `8001` |
| Endpoint | `POST /evaluate` |
| Content-Type | `application/json` |
| 认证 | 无（LLM 的 `api_key` 在请求体内传递） |

---

## 2. 请求结构

### EvaluateRequest

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `trajectory_path` | `string` | ✅ | 服务端轨迹 JSON 文件的**绝对路径**（文件须在 API 服务器本地磁盘可访问） |
| `prompt_template` | `string` | ✅ | 评估 prompt 模板，需包含 `{expected_section}` `{skill_names_section}` `{diagnostic_rules}` `{skill_names}` `{messages}` 占位符（见第 6 节） |
| `skill_names` | `string[]` | ✅ | 已知 Skill 名称白名单。LLM 归因出的 `attributed_skill` 必须在此列表中，否则返回 500 |
| `llm_config` | `LLMConfig` | ✅ | 评估用 LLM 配置 |
| `expected_result` | `object \| null` | ❌ | 可选期望结果，作为评估参考（注入模板 `{expected_section}` 占位符） |
| `filters` | `FilterConfig \| null` | ❌ | 可选确定性过滤器，在 LLM 评估前执行；命中则直接返回 `status="filtered"`、`score=0.0`，不调用 LLM |

### LLMConfig

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `model_name` | `string` | ✅ | | 模型名称（如 `qwen-max`） |
| `api_key` | `string` | ✅ | | LLM 提供商 API Key |
| `api_base` | `string` | ✅ | | LLM 提供商 API Base URL |
| `client_provider` | `string` | ❌ | `"OpenAI"` | LLM 提供商类型 |
| `temperature` | `float` | ❌ | `0.1` | 生成温度 |
| `max_tokens` | `int` | ❌ | `2048` | 最大输出 token 数 |
| `verify_ssl` | `bool` | ❌ | `false` | 是否校验 SSL 证书 |

### FilterConfig

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `tool_failure` | `ToolFailureFilterConfig` | disabled | 工具失败过滤器 |
| `user_feedback` | `UserFeedbackFilterConfig` | disabled | 用户反馈过滤器 |

### ToolFailureFilterConfig

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | `bool` | `false` | 是否启用 |
| `patterns` | `string[] \| null` | `null` | 自定义匹配正则列表 |
| `replace_default_patterns` | `bool` | `false` | `true` 时替换默认规则，`false` 时追加 |

### UserFeedbackFilterConfig

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | `bool` | `false` | 是否启用 |
| `patterns` | `string[] \| null` | `null` | 自定义匹配正则列表 |
| `replace_default_patterns` | `bool` | `false` | `true` 时替换默认规则，`false` 时追加 |
| `skip_initial_user_messages` | `int` | `1` | 跳过前 N 条用户消息（避免匹配开场问候） |

---

## 3. 响应结构

### EvaluateResponse（200 OK）

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | `string` | `"evaluated"` — 正常评估结果；`"filtered"` — 被确定性过滤器拦截（未调用 LLM） |
| `score` | `float` | 综合分数 `[0, 1]`，**由 LLM 直接输出**，非维度均值聚合 |
| `is_pass` | `bool` | 轨迹是否通过评估，**由 LLM 直接输出** |
| `per_metric` | `object \| null` | 各维度分数（可能为 `null`：维度分数为 best-effort 提取） |
| `reason` | `string` | 评估理由。正常评估时为 JSON 字符串（见下方）；过滤拦截时为简短文本 |
| `attributed_skill` | `string` | 失败归因的 Skill 名称（单字符串）。`is_pass=true` 时为空串 `""` |
| `filter_matches` | `object[]` | 过滤器匹配详情（仅 `status="filtered"` 时有值，正常评估时为空数组） |

### per_metric 维度

| key | 说明 | 取值范围 |
|-----|------|---------|
| `task_completion` | 任务完成度 — 用户核心目标是否被达成 | `1.0` / `0.75` / `0.5` / `0.25` / `0.0` |
| `trajectory_quality` | 轨迹质量 — 执行过程（工具选择、调用顺序、参数合理性）是否合理 | `1.0` / `0.75` / `0.5` / `0.25` / `0.0` |
| `safety` | 安全性 — 是否存在隐私泄露、未授权操作、高危风险 | `1.0` / `0.75` / `0.5` / `0.25` / `0.0` |

> **注意**：维度分数为 best-effort 提取。LLM 输出中若某维度缺失或为非法数值，服务端静默跳过该维度，`per_metric` 中可能只包含 2 个甚至 1 个维度 key，极端情况下为 `null`。客户端应做防御性解析。

### reason JSON 结构（正常评估时）

`reason` 字段在 `status="evaluated"` 时为以下 JSON 的序列化字符串：

```json
{
  "reason": "整体评估理由文本",
  "is_pass": true,
  "attributed_skill": "product_recommend_skill"
}
```

客户端可用 `json.loads(response.reason)` 解析。`is_pass` 和 `attributed_skill` 与顶层字段含义一致。

### filter_matches 每项（过滤拦截时）

| 字段 | 类型 | 说明 |
|------|------|------|
| `filter_type` | `string` | `"tool_failure"` 或 `"user_feedback"` |
| `rule_id` | `string` | 匹配的规则 ID |
| `message_index` | `int` | 命中的轨迹消息索引 |
| `evidence` | `string` | 命中证据摘要 |
| `pattern` | `string \| null` | 匹配到的正则模式（自定义规则时有值） |

---

## 4. 轨迹文件格式

`trajectory_path` 指向的 JSON 文件须包含 `messages` 和可选 `summary`。字段仅保留已知 key，多余字段会触发校验错误（`extra="forbid"`）。

```json
{
  "messages": [
    {
      "role": "user",
      "content": "帮我推荐一个稳健的理财产品"
    },
    {
      "role": "assistant",
      "content": "",
      "tool_calls": [
        {
          "id": "call_001",
          "function": {
            "name": "read_file",
            "arguments": "{\"path\": \"/skills/product_recommend_skill/SKILL.md\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_001",
      "content": "code=0 message='success' data=..."
    },
    {
      "role": "assistant",
      "content": "根据您的风险偏好，我推荐以下稳健型产品..."
    }
  ],
  "summary": {
    "total_messages": 4,
    "tool_calls_used": ["read_file"],
    "total_steps": 4,
    "tool_calls_count": 1,
    "tokens_used": 2500
  }
}
```

### TrajectoryMessage 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `role` | `string` | ✅ | 消息角色：`"user"` / `"assistant"` / `"tool"` / `"system"` |
| `content` | `any` | ❌ | 消息内容（`string`、`list` 或 `null`） |
| `name` | `string \| null` | ❌ | 消息发送者名称 |
| `tool_calls` | `object[]` | ❌ | assistant 消息中的工具调用列表（默认 `[]`） |
| `tool_call_id` | `string \| null` | ❌ | tool 消息对应的工具调用 ID |
| `reasoning_content` | `string \| null` | ❌ | 推理内容（部分模型支持） |
| `metadata` | `object` | ❌ | 附加元数据（默认 `{}`） |

### TrajectorySummary 字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `total_messages` | `int` | `0` | 消息总数 |
| `tool_calls_used` | `string[]` | `[]` | 使用的工具名称列表 |
| `summary` | `string` | `""` | 概要文本 |
| `total_steps` | `int` | `0` | 步骤总数 |
| `tool_calls_count` | `int` | `0` | 工具调用计数 |
| `tokens_used` | `int` | `0` | Token 使用量 |
| `metadata` | `object` | `{}` | 附加元数据 |

### tool_calls 结构

每个 `tool_calls` 项为字典：

```json
{
  "id": "call_001",
  "function": {
    "name": "read_file",
    "arguments": "{\"path\": \"/skills/product_recommend_skill/SKILL.md\"}"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 工具调用唯一 ID（与 tool 消息的 `tool_call_id` 对应） |
| `function.name` | `string` | 工具/Skill 名称 |
| `function.arguments` | `string \| object` | 工具参数（JSON 字符串或字典） |

---

## 5. prompt_template 占位符

`prompt_template` 中使用以下占位符，服务端在运行时用 `str.replace()` 注入内容：

| 占位符 | 运行时填充内容 | 来源 |
|--------|----------------|------|
| `{expected_section}` | 有 `expected_result` 时展开为 `## 可选期望结果\n期望结果：{expected_result 的 JSON}`，否则为空字符串 | 请求体 `expected_result` |
| `{skill_names_section}` | 有 `skill_names` 时展开为 `## 可用 Skill 列表\n{skill_names 的 JSON}`，否则为空字符串 | 请求体 `skill_names` |
| `{diagnostic_rules}` | 固定评估准则文本 | 服务端内置 `_DIAGNOSTIC_RULES_TEXT` |
| `{skill_names}` | `skill_names` 以逗号连接的纯字符串（如 `"product_recommend_skill, risk_assessment_skill"`） | 请求体 `skill_names` |
| `{messages}` | 简化后的轨迹消息文本（由服务端 `simplify_trajectory()` 生成） | 轨迹文件 `messages` |

> **注意**：
> - 模板中 JSON 示例的大括号使用 `{{` / `}}` 是字面量（`str.replace()` 不转义，直接保留）。
> - `{messages}` 注入的是简化后的轨迹文本，**不包裹** `<conversation_trajectory>` 标签。
> - `{trajectory_section}` 和 `{warnings_section}` 已退役，新模板不应使用这两个占位符。

### 默认模板

`prompt_template` 必填。若不使用自定义模板，可将服务端内置的 `DEFAULT_PROMPT_TEMPLATE`（位于 `src/evo_agent/evaluator/prompts/policy_v1.py`）完整复制到请求中。

默认模板的核心要点：
- 三个评估维度：`task_completion` / `trajectory_quality` / `safety`
- LLM 输出扁平 JSON，包含 `task_completion` / `trajectory_quality` / `safety` / `is_pass` / `score` / `attributed_skill` / `reason`
- `score` 由 LLM 综合判定，不一定是维度均值
- `is_pass=false` 时才需填写 `attributed_skill`

---

## 6. 错误码

| HTTP Status | 触发条件 | 返回格式 |
|:-----------:|----------|---------|
| `404` | `trajectory_path` 指向的文件不存在 | `{ "detail": "Trajectory file not found: ..." }` |
| `422` | 请求体校验失败 / 轨迹 JSON 格式非法 / 过滤器配置非法 | `{ "detail": "Invalid trajectory format: ..." }` |
| `500` | 评估过程失败：LLM 调用失败 / JSON 解析失败 / `attributed_skill` 不在 `skill_names` 列表中 | `{ "detail": "Evaluation failed: ..." }` |

---

## 7. 处理流程

```
请求 → 加载轨迹文件 → [可选] 过滤器匹配
                         ├── 命中 → 返回 status="filtered", score=0.0, is_pass=false,
                         │         attributed_skill="", filter_matches=[...]
                         └── 未命中 → LLM 评估
                                       ├── 解析必要字段 (is_pass, score, attributed_skill, reason)
                                       ├── best-effort 提取维度分数 (task_completion, trajectory_quality, safety)
                                       ├── 校验 attributed_skill ∈ skill_names
                                       │    ├── 通过 → 返回 200
                                       │    └── 不通过 → 返回 500
                                       └── 返回 EvaluateResponse
```

---

## 8. 完整调用示例

### curl

```bash
curl -X POST http://localhost:8001/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "trajectory_path": "/data/trajectories/case_001.json",
    "prompt_template": "（完整默认模板或自定义模板，见第 5 节）",
    "skill_names": ["product_recommend_skill", "risk_assessment_skill"],
    "llm_config": {
      "model_name": "qwen-max",
      "api_key": "sk-xxx",
      "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "client_provider": "OpenAI",
      "temperature": 0.1,
      "max_tokens": 2048
    },
    "expected_result": {
      "answer": "推荐稳健型理财产品"
    },
    "filters": {
      "tool_failure": { "enabled": true },
      "user_feedback": { "enabled": false }
    }
  }'
```

### Python — httpx（推荐异步场景）

```python
import httpx
import json

EVALUATE_URL = "http://localhost:8001/evaluate"

# 读取默认 prompt 模板（或自定义模板）
# 默认模板位于 src/evo_agent/evaluator/prompts/policy_v1.py 的 DEFAULT_PROMPT_TEMPLATE
prompt_template = """..."""  # 从文件或常量加载

request_body = {
    "trajectory_path": "/data/trajectories/case_001.json",
    "prompt_template": prompt_template,
    "skill_names": ["product_recommend_skill", "risk_assessment_skill"],
    "llm_config": {
        "model_name": "qwen-max",
        "api_key": "sk-xxx",
        "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "client_provider": "OpenAI",
        "temperature": 0.1,
        "max_tokens": 2048,
    },
    "expected_result": {"answer": "推荐稳健型理财产品"},
    "filters": {"tool_failure": {"enabled": True}},
}

async def evaluate() -> dict:
    async with httpx.AsyncClient(timeout=60.0) as client:
        resp = await client.post(EVALUATE_URL, json=request_body)
        resp.raise_for_status()
        return resp.json()


# 调用
result = await evaluate()
print(f"status={result['status']}  score={result['score']}  is_pass={result['is_pass']}")
```

### Python — requests（同步场景）

```python
import requests
import json

EVALUATE_URL = "http://localhost:8001/evaluate"

request_body = {
    "trajectory_path": "/data/trajectories/case_001.json",
    "prompt_template": "...",  # 默认模板或自定义
    "skill_names": ["product_recommend_skill", "risk_assessment_skill"],
    "llm_config": {
        "model_name": "qwen-max",
        "api_key": "sk-xxx",
        "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    },
}

resp = requests.post(EVALUATE_URL, json=request_body, timeout=60)
resp.raise_for_status()
result = resp.json()
```

---

## 9. 响应示例

### 正常评估（通过）

```json
{
  "status": "evaluated",
  "score": 0.85,
  "is_pass": true,
  "per_metric": {
    "task_completion": 1.0,
    "trajectory_quality": 0.75,
    "safety": 1.0
  },
  "reason": "{\"reason\": \"任务完成，轨迹存在少量冗余调用，无安全问题\", \"is_pass\": true, \"attributed_skill\": \"\"}",
  "attributed_skill": "",
  "filter_matches": []
}
```

### 正常评估（不通过 + 归因）

```json
{
  "status": "evaluated",
  "score": 0.35,
  "is_pass": false,
  "per_metric": {
    "task_completion": 0.25,
    "trajectory_quality": 0.5,
    "safety": 1.0
  },
  "reason": "{\"reason\": \"推荐了高风险产品不符合用户稳健需求，工具调用顺序混乱\", \"is_pass\": false, \"attributed_skill\": \"product_recommend_skill\"}",
  "attributed_skill": "product_recommend_skill",
  "filter_matches": []
}
```

### 被过滤器拦截

```json
{
  "status": "filtered",
  "score": 0.0,
  "is_pass": false,
  "per_metric": null,
  "reason": "{\"reason\": \"\", \"is_pass\": false, \"attributed_skill\": \"\"}",
  "attributed_skill": "",
  "filter_matches": [
    {
      "filter_type": "tool_failure",
      "rule_id": "tool_error_code",
      "message_index": 5,
      "evidence": "tool result contains error code",
      "pattern": "code=[1-9]"
    }
  ]
}
```

### 归因校验失败（500）

```json
{
  "detail": "Evaluation failed: Skill attribution references unknown skill(s): ['hallucinated_skill']; known skills: ['product_recommend_skill', 'risk_assessment_skill']"
}
```

---

## 10. 客户端实现要点

1. **`skill_names` 必须非空且准确** — LLM 归因出的 `attributed_skill` 必须在此列表中，否则服务端返回 500。请确保列表覆盖所有可能被归因的 Skill。

2. **`trajectory_path` 是服务端路径** — 文件必须在 API 服务器本地磁盘可访问。客户端无法上传轨迹内容，须确保轨迹文件已放置在服务端路径上。

3. **`prompt_template` 必须包含 `{messages}` 占位符** — 否则轨迹数据不会注入 prompt，LLM 将无法评估。

4. **用 `status` 区分结果类型** — 不要用 `score === 0.0` 判断是否为过滤结果（正常评估也可能得 0 分）。`status="filtered"` 表示确定性过滤拦截，`status="evaluated"` 表示 LLM 正常评估。

5. **`score` 和 `is_pass` 由 LLM 直接输出** — 不是维度分数均值。`is_pass=true` 时 `score` 通常 ≥ 0.75，`is_pass=false` 时 `score` 通常 < 0.75，但并非绝对对应。

6. **`reason` 可能是 JSON 字符串** — `status="evaluated"` 时，`reason` 内包含 `{"reason": "...", "is_pass": true/false, "attributed_skill": "..."}` 的序列化 JSON。客户端可用 `json.loads()` 解析获取结构化信息。`status="filtered"` 时 `reason` 为简短文本。

7. **`per_metric` 可能缺失部分维度** — 维度分数为 best-effort 提取，LLM 输出中若某维度缺失或非法则静默跳过。客户端解析时应做防御性处理：
   ```python
   per_metric = result.get("per_metric") or {}
   task_completion = per_metric.get("task_completion")  # 可能为 None
   ```

8. **`attributed_skill` 为单字符串** — 不是列表。`is_pass=true` 时为空串 `""`，表示无需归因；`is_pass=false` 且无法归因时也为空串。

9. **过滤器是可选短路优化** — 启用后，匹配到过滤规则的轨迹不调用 LLM，直接返回 `score=0.0`、`is_pass=false`，节省 token 开销。适合批量评估中先排除明确坏 case。

10. **超时设置** — LLM 评估可能耗时较长（取决于模型响应速度），建议客户端 HTTP 超时设为 ≥ 60 秒。
