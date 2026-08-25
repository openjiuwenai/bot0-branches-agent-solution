# 数据回流-轨迹增强使用指南

> 本指南介绍如何使用 **轨迹 Skill 归属（attribution）**：业务 Agent 回流的每条 span 会带上"这次操作是哪个 skill 调用的"标记，经 `/traces` 接口对外带出，供下游按 skill 切段、路由或统计。

---

## 1. 特性概览

### 1.1 这是什么

**轨迹 Skill 归属**给每条 span 标注：**这次操作是哪个 skill 干的？**（都不是 skill 则归全局 `Agent.md`）。归属算好后随 `/traces` 的 record 一起返回，下游（评估、导出、统计等）直接读即可。

每个 span 的归属结果包含：

- **`skill`**：归属的 skill 名；不是任何 skill 时为兜底值 `Agent.md`；多义定不了时为空；
- **`source`**：判定来源，表示这条归属是怎么得出的（取值见 §3.2）；
- **`confidence`**：置信度（1.0 / 0.8 / 0.7 / 0.5），按来源分档的信任度，下游可按分档筛选可信结论；
- **`candidates`**：候选 skill 列表，多义（多个 skill 都可能）时列出全部候选，单义时为空；
- **`misuse`**：是否误用（命中 skill 文档明确禁用的工具时为 true，当前均为 false）。

### 1.2 本指南覆盖范围

本指南包含：

- 接口与返回数据格式（attribution 各字段含义、source 取值）；
- 如何启用、如何调用、看到什么字段；
- 一个端到端场景用例；
- 常见错误和处理方式。

---

## 2. 什么时候使用

| 使用轨迹 Skill 归属 | 不使用 |
|---|---|
| 要让下游按 skill 切段、路由或统计 | 只要一串对话 messages、不要 per-skill 段 |
| 业务 Agent 上报了 skill 结构信号（`skill.*` span / 读 SKILL.md） | 业务 Agent 从不上报 skill 信号 |
| 要给导出/统计/多个下游统一的 skill 字段 | 只想要轨迹分数、不要"属哪个 skill" |

> 判断原则：需要给每条 span 标 skill、且下游要按 skill 做事时用本特性；只要对话级结果不要 per-skill 切分时不必用。

---

## 3. 接口与数据格式

归属不新增 HTTP 端点，只在既有 `/traces` 的每条 record 上 **additive 加 `attribution` + `parent_span_id` 两字段**。`/cleaned-traces` 不变。

### 3.1 attribution 对象字段

每条 record 上的 `attribution` 是一个对象：

| 字段 | 类型 | 说明 |
|---|---|---|
| `skill` | `string` | 归属的 skill 名；兜底为 `"Agent.md"`；多义未解为 `""` |
| `source` | `string` | 判定来源，取值见 §3.2 |
| `confidence` | `float` | 置信度，1.0 最可信、0.5 最弱 |
| `candidates` | `string[]` | 多义时的候选 skill 列表，单义时为 `[]` |
| `misuse` | `bool` | 命中 skill 文档禁用工具时 true，当前均为 false |

> trace 还没算完时 `attribution` 为 `null`，下游须容错走各自 fallback。

### 3.2 `source` 取值与含义

`source` 表示这条归属是凭什么得出的：

| source | 含义 | confidence |
|---|---|---|
| `parent_skill_span` | 该 span 的上层调用是某 skill 的执行 span（业务 Agent 自己声明在跑这个 skill），故归该 skill | 1.0 |
| `active_context` | 该 span 之前已有 skill 被激活（agent 已开始执行某 skill），激活态持续到本 span，故归该 skill | 0.8 |
| `tool_name_match` | 该 span 调用的工具在某 skill 文档里被标注为"使用"，故归该 skill | 0.7 |
| `residual` | 以上都没命中，兜底归全局 `Agent.md` | 0.5 |
| `ingress` | 传输层/入口 span（如 http 请求），不涉及业务逻辑，单独标 | 1.0 |
| `skill_selection` | agent 读 SKILL.md 的选型动作本身（规划"用哪个 skill"），归兜底，被读的 skill 记入 candidates | 0.5 |

> `parent_skill_span` 最可信（业务 Agent 自声明）；`active_context` 次之（按激活推断）；`tool_name_match` 一般（文本匹配）；`residual` 最弱（兜底）。下游可按 confidence 阈值只取可信结论。

### 3.3 `/traces` 返回示例

归属算完后，`GET /api/v1/traces/{conversation_id}` 返回（每条 record 带 `attribution` + `parent_span_id`）：

```jsonc
{
  "conversation_id": "conv-abc123",
  "calls": [
    {
      "id": "tr-abc123", "trace_id": "tr-abc123", "session_id": "conv-abc123",
      "timestamp": "2026-08-17T00:00:00+00:00",
      "start_time": "2026-08-17T00:00:00+00:00", "end_time": "2026-08-17T00:00:03+00:00",
      "parent_span_id": "",
      "attribution": {"skill": "Agent.md", "source": "residual", "confidence": 0.5, "candidates": [], "misuse": false}
    },
    {
      "type": "GENERATION", "id": "s3", "trace_id": "tr-abc123", "session_id": "conv-abc123",
      "start_time": "2026-08-17T00:00:01+00:00", "end_time": "2026-08-17T00:00:02+00:00",
      "input": {"messages": [{"role": "user", "content": "推荐一款低风险理财产品"}]},
      "output": {"role": "assistant", "content": "建议考虑产品A，主要风险是收益波动…"},
      "parent_span_id": "s1",
      "attribution": {"skill": "sample_recommend_skill", "source": "parent_skill_span", "confidence": 1.0, "candidates": [], "misuse": false}
    },
    {
      "type": "TOOL", "id": "s2", "trace_id": "tr-abc123", "session_id": "conv-abc123",
      "name": "tool.sample_tool_1",
      "start_time": "2026-08-17T00:00:01+00:00", "end_time": "2026-08-17T00:00:02+00:00",
      "parent_span_id": "s1",
      "attribution": {"skill": "sample_recommend_skill", "source": "parent_skill_span", "confidence": 1.0, "candidates": [], "misuse": false}
    }
  ],
  "total": 3,
  "complete": true
}
```

> 上例中 `s2`/`s3` 的 `parent_span_id` 指向一个执行 `sample_recommend_skill` 的上层 span，故归属 `sample_recommend_skill`、`source=parent_skill_span`、`confidence=1.0`；根 span 无 skill 归属，走兜底 `Agent.md`。未算完的 trace 各 record 的 `attribution` 为 `null`。

---

## 4. 准备工作

### 4.1 环境要求

| 项目 | 要求 | 检查 |
|---|---|---|
| adapter 服务 | 已启动，`trace_source=standard` | `curl http://localhost:8900/health` 返回 `{"status":"ok"}` |
| 数据源 | standard 模式（依赖 PG + kafka） | 启动日志有 `attribution_runner_started` |
| 业务 Agent | 上报带 skill 信号的 trace（`skill.*` span 或读 SKILL.md） | 见 §7 场景用例 |

### 4.2 skill 文档

归属判定要读 skill 文档正文，故该 agent 的 skill 文档须能被 adapter 取到（本地 skills 目录或远端 skill 服务）。业务 Agent 上报的 `service.name` 须与 adapter 配置的 agent 名一致。

---

## 5. 快速上手

1. 启动 adapter（standard 模式），确认日志有 `attribution_runner_started`（归属默认开启，无需额外配置）；
2. 让业务 Agent 上报一条带 skill 信号的 trace，等 trace 完整（根 span 结束）+ 一个轮询周期（默认 5 秒）后；
3. 调 `GET /api/v1/traces/{conversation_id}`，返回的每条 record 上带 `attribution` + `parent_span_id`（格式见 §3，未算完时 `attribution` 为 `null`）。

```bash
curl -s http://localhost:8900/api/v1/traces/{conversation_id}
```

---

## 6. 配置

归属功能**默认开启**（`attribution_runner_enabled` 默认 `true`），standard 模式启动即生效，无需额外配置。

如需调整，相关环境变量：

| 环境变量 | 默认 | 作用 |
|---|---|---|
| `ADAPTER_ATTRIBUTION_RUNNER_ENABLED` | `true` | 归属总开关，关则不算、`/traces` 的 `attribution` 永远 `null` |
| `ADAPTER_ATTRIBUTION_POLL_INTERVAL` | `5.0` | 轮询间隔秒，越短归属算得越快 |

> 注：以上开关目前以代码默认值生效，尚未合入 adapter 的部署配置模板（`.env.example`）。如需在部署层显式配置，可后续补入部署配置并重启 adapter。

---

## 7. 场景用例（端到端）

以一个上报 `skill.*` span 的业务 Agent 为例，走完整闭环：

**① 配置**：adapter 跑 standard 模式，归属默认开（无需动配置）。

**② 上报 trace**：业务 Agent 处理一次请求，上报的 trace 含一个 `skill.sample_recommend_skill` 的 span（声明在跑这个 skill），及其子操作（调 `tool.sample_tool_1`、一次 llm 调用）。

**③ 等归属算完**：trace 完整（根 span `end_time` 已设）后，归属在一个轮询周期（默认 5 秒）内算好写回。

**④ 调接口看字段**：

```bash
curl -s http://localhost:8900/api/v1/traces/conv-abc123
```

返回里子操作的 record 带：

```jsonc
"attribution": {"skill": "sample_recommend_skill", "source": "parent_skill_span", "confidence": 1.0, "candidates": [], "misuse": false}
```

**⑤ 判定场景 OK**：看到 `skill=sample_recommend_skill`、`source=parent_skill_span`、`confidence=1.0`，说明子操作正确归到了它上层声明的 skill —— 这个场景归属生效，下游可据此按 skill 切段。

---

## 8. 常见问题

### 8.1 故障排查表

| 现象 | 原因 | 处理方式 |
|---|---|---|
| `/traces` 的 `attribution` 全 `null` | 归属没算 / trace 未完整 / 开关关了 | 查 `attribution_runner_started` 日志；查 trace 根 span `end_time` 已设；查 `ADAPTER_ATTRIBUTION_RUNNER_ENABLED` |
| 归属全落 `Agent.md` | 业务 Agent 没上报 skill 信号 | 确认 Agent 上报了 `skill.*` span 或有读 SKILL.md 的动作 |
| `attribution` 为 `null` 但 trace 已完整 | 还没到一个轮询周期 | 等 5 秒（默认）或调小 `ADAPTER_ATTRIBUTION_POLL_INTERVAL` |

### 8.2 常见问答

#### Q：归属用什么 LLM？

**不用 LLM。** 归属判定全程确定性（按 span 结构 + skill 文档文本规则），不调 LLM。

#### Q：归属算错了能重算吗？

**能。** 把该 trace 的 attribution 清空（置 `null`），下一轮会重算写回。

#### Q：log 模式能用归属吗？

**不覆盖。** 归属依赖 trace 级上下文（parent 链 + 时间前缀），只在 standard 模式（PG + kafka）生效。

#### Q：`/cleaned-traces` 上有归属吗？

**没有。** `/cleaned-traces` 只保留最后一条 GENERATION 的对话，丢中间 span，挂不上 per-skill 归属。要 per-skill 切段请用 `/traces`。
