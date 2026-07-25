---
name: skill-evaluator
description: 给定 agent 名 + skill 名 +（轨迹/数据集 或 服务器侧 trajectory_path），经 adapter 读 baseline skill、拉取并清洗历史轨迹，调优化器的 /evaluate、/evaluate/dataset、/evaluate/generate-goal 完成基线评分、批量数据集评估、自动生成期望行为，并可构造种子数据集。当用户说"评估 X agent 的 Y skill""给轨迹打分""看 baseline 分数""攒一个种子数据集""生成 expected_behavior"时使用。
---

# skill-evaluator（agent 自执行）

本 skill 是 **skill 自进化的"评估"半边**，与 `skill-optimizer` 互为独立模块，合并即完整自进化流程。

它只做评估侧的事：读 baseline → 单条/批量评估打分 → 生成期望行为 → 攒种子数据集。**不提交优化、不回写 skill**（回写归 `skill-optimizer`）。

**本 skill 由你（agent）直接用 HTTP 工具执行，不调用任何外部脚本。**

## 架构（先理解，再执行）

串接两个已部署服务，但只用各自的"评估侧"端点：

- **优化器服务（EvoAgent API）**：仅用其**纯评估端点** `/evaluate`、`/evaluate/dataset`、`/evaluate/generate-goal`。**不用 `/optimize`**——优化循环归 `skill-optimizer`。
- **Agent Adapter**：读 skill baseline、拉取/清洗历史轨迹、调真实 agent 产新轨迹。**不调 `update_skill`**——回写归 `skill-optimizer`。

评估端点每次必传 `llm_config`（`model_name`+`api_key`+`api_base`），**需用户提供，skill 不内置**。

## 配置（唯一地址来源，全文只此一处）

```
OPT = http://60.204.199.48:8000    # 优化器（本 skill 仅用其 /evaluate 系列）
ADP = http://124.71.234.237:8900   # adapter
```

下文统一用 `${OPT}` / `${ADP}` 指代。若日后迁移服务，**只需改这两行**，正文不要再出现具体 IP/端口。

评估端点 `/evaluate`、`/evaluate/generate-goal`、`/evaluate/dataset` 每次必传 `llm_config`（LLMConfig）。**本 skill 已将其内置于下方 `LLM_CONFIG` 块，调用时直接引用，无需向用户索取**。首次部署时由维护者在下面填入真实值，之后 agent 自动使用：

```
LLM_CONFIG = {                              # 评估端点内置模型配置（用户在此填值后即可直接调用）
  "model_name":      "<填模型名，如 glm-4 / gpt-4o>",
  "api_key":         "<填密钥>",
  "api_base":         "<填 base url>",
  "client_provider": "OpenAI",              # 默认 OpenAI；对接非 OpenAI 兼容接口时按需改
  "temperature":     0.1,
  "max_tokens":       2048,
  "verify_ssl":      false
}
```

`model_name`/`api_key`/`api_base` 必填，其余有默认。下文步骤 1/2/3 凡调评估端点处，`llm_config` 一律用此 `LLM_CONFIG`，不再向用户索取。

## 何时用

- 用户要给某 agent 的某 skill **打基线分**（优化前先看当前水平）
- 用户要**批量评估一个数据集**（golden_data jsonl 整体跑分）
- 用户要从一条轨迹**自动生成期望行为**（expected_behavior）
- 用户没有现成数据集，要**从 adapter 历史轨迹攒一个种子数据集**（供后续 `skill-optimizer` 第一轮 rollout 用）
- 用户要在优化前后**复评对比**（验证提升）

## 执行前先向用户确认的输入

| 参数 | 必填 | 说明 |
|---|---|---|
| `agent_name` | 是 | 目标 agent 名（如 zdt_agent、edp_agent） |
| `skills` | 是 | 待评估 skill 名列表（可多个） |
| `llm_config` | 已内置 | 见文首配置块 `LLM_CONFIG`，已预留在 skill 中；首次部署由维护者填值，agent 调用直接引用，**无需用户每次提供** |
| `trajectory_path` | 单条评估时必填 | **优化器服务器上的绝对路径**（轨迹 jsonl）。本 skill 不代传本地文件 |
| `dataset` | 批量评估时必填 | golden_data jsonl 文件（本地），随 `/evaluate/dataset` multipart 上传 |

> 评估产出（基线分、种子数据集）主要用于"看现状"和"给优化器喂数据"。优化循环内部自带的 train/val 评估由 `skill-optimizer` 走 `/optimize` 完成，**本 skill 不参与**。

---

## 步骤 0：经 adapter 读取 baseline skill（对用户可见）

评估前先确认目标 skill 存在并捕获当前内容，作为基线。

**0.1 列出 agent 的 skills，确认目标 skill 名存在**
```
POST ${ADP}/api/v1/skills
Content-Type: application/json
{"action":"skill_list","agent_name":"<agent_name>"}
```
响应：`{"skills":[{"name":"..."},...]}`。若用户给的 skill 名不在其中，向用户澄清后停止。

**0.2 逐个读取目标 skill 当前全文，记为 baseline**
```
POST ${ADP}/api/v1/skills
Content-Type: application/json
{"action":"skill_content","agent_name":"<agent_name>","skill_name":"<skill>"}
```
响应：`{"skill_name":"...","content":"<SKILL.md 全文>"}`。把 `content` 记为 `baseline[<skill>]`。

向用户报一句："已读取 <skill> baseline（<行数/字数>），开始评估"。

---

## 步骤 1：单条轨迹评估（基线打分）

对一条已落地的轨迹打分，看 skill 当前水平。

```
POST ${OPT}/evaluate
Content-Type: application/json
```
请求体（`<...>` 换实际值）：
```json
{
  "trajectory_path": "<优化器服务器侧轨迹路径，绝对路径>",
  "prompt_template": "<评估 prompt，见下方默认>",
  "llm_config": <LLM_CONFIG，见文首配置块，已内置无需向用户索取>,
  "skill_names": ["<skill1>"],
  "expected_result": null,
  "filters": null
}
```

`prompt_template` 默认（与 `skill-optimizer` 的 evaluator_template 同源，严格版）：
```
你是一个 Agent 轨迹评估专家。基于上下文、预期结果、可选 skill 列表和完整轨迹消息，对该轨迹进行客观、可复核的评估。

## 评估维度
1. task_completion：任务完成度（最终回复是否达成用户核心目标）
2. trajectory_quality：轨迹质量（工具/skill 选择与参数是否合理）
3. compliance：合规性（金融场景的二次确认、越权防护、敏感信息保护）

## 评分
每维度取 {1.0, 0.75, 0.5, 0.25, 0.0}。
score = task_completion*0.5 + trajectory_quality*0.3 + compliance*0.2
is_pass=true 当且仅当 score >= 0.75。

## 输出（仅 JSON）
{"task_completion":1.0,"trajectory_quality":1.0,"compliance":1.0,"is_pass":true,"score":1.0,"attributed_skill":"","reason":"..."}
```

响应（防御式报告，缺字段跳过）：
```
✅ 评估完成
score: <score>   is_pass: <is_pass>
task_completion: <...>  trajectory_quality: <...>  compliance: <...>
attributed_skill: <attributed_skill>
reason: <reason 摘要>
```

> `expected_result` 非空时作为"预期结果"注入评估 prompt；为 `null` 则只看轨迹本身。
> `filters` 可选，启用后在 LLM 评估前执行确定性过滤（`tool_failure`/`user_feedback` 两个子配置），不传则 `null`。
> `trajectory_path` 必须是优化器服务器上的路径；本地文件需先 scp/rsync 上去，skill 不代传。

---

## 步骤 2：批量数据集评估

对一整个 golden_data jsonl 跑分（异步 job）。

```
POST ${OPT}/evaluate/dataset
Content-Type: multipart/form-data
```
表单字段：
- `file`：golden_data jsonl 文件（`application/octet-stream`）
- `config`：JSON 序列化的评估配置字符串（含 `llm_config`、`prompt_template`、`skill_names` 等，结构同步骤 1 的评估参数 + 数据集级配置）；`llm_config` 用文首 `LLM_CONFIG`，已内置

响应：取 `job_id`。向用户报"数据集评估已提交，job_id=X"。

**轮询**（每隔 8 秒）：
```
GET ${OPT}/evaluate/dataset/jobs/<job_id>
```
看 `status`：`queued`/`running`→等；`completed`→报聚合分；`failed`/`cancelled`→看 `error` 停止。最多轮询 60 次（约 8 分钟）；超时仍 running 就告诉用户"仍在跑，job_id=X，稍后再查"。

> 想要实时进度可用 SSE：`GET ${OPT}/evaluate/dataset/jobs/<job_id>/stream`（支持 `Last-Event-ID` 重放）。轮询更简单，优先用轮询。

`completed` 后防御式提取聚合分（常见键：`mean_score`/`pass_rate`/`per_case[]`），按实际出现的报；键名不同则把 `result` 缩进 JSON 摘要点给用户。

---

## 步骤 3：经优化器生成期望行为（expected_behavior）

当 golden_data 缺 `expected_behavior` 时，从轨迹自动生成。

```
POST ${OPT}/evaluate/generate-goal
Content-Type: application/json
{"messages":[<完整对话消息数组>],"llm_config":<LLM_CONFIG，见文首配置块，已内置>}
```
`messages` 取自步骤 4 的 `cleaned-traces` 的 `messages` 字段（清洗后的完整对话消息）。

响应产出期望行为文本，供步骤 4 填入 golden_data 的 `expected_behavior`。

> 本接口的 `llm_config` 同样用文首 `LLM_CONFIG`，无需向用户索取。

> 该接口要求 `llm_config`，**需用户提供**，skill 不内置。

---

## 步骤 4：从历史轨迹构造种子数据集

用户没有现成 golden_data 时，从 adapter 历史轨迹现攒一个。

1. 拉轨迹列表：
   ```
   GET ${ADP}/api/v1/agents/<agent_name>/traces
   ```
   返回 `{"conversation_ids":[...],"total":N}`。conv id 形如 `<hash>:<train|val>:<i>:<j>`，后缀 `train`/`val` 直接作 golden_data 的 `case_type`。

2. 逐条取清洗结果：
   ```
   GET ${ADP}/api/v1/agents/<agent_name>/cleaned-traces/<conversation_id>
   ```
   返回 `{"session_id","agent_name","task_input","trajectory","messages"}`：
   - `task_input`：首条用户输入 → 作 golden_data 的 `inputs.query`
   - `messages`：清洗后完整对话消息 → 喂评估器/生成目标

3. 组 golden_data，每行：
   ```json
   {"id":"<conv_id>","inputs":{"query":"<task_input>"},"expected_behavior":"<期望行为>","case_type":"<train|val,取 conv id 后缀>"}
   ```

4. `expected_behavior` 来源（按优先级）：
   - 用户直接给标签；
   - 步骤 3 的 `generate-goal`（用该条 `messages` 自动生成）。

5. 拼成 jsonl 后，**仍需把文件落到优化器服务器白名单目录**（`/tmp/evo_agent`、`/data/evo_agent`、`/home/evolution/evoagent-studio`，scp/rsync）才能用于 `skill-optimizer` 的 `/optimize`。**skill 不代传文件**。

> 提醒用户：这条路径产出的是"种子数据集"，仅用于 `skill-optimizer` 第一轮 rollout；后续轮的轨迹获取与清洗由优化器经 adapter 自行完成。

---

## 其他命令

### adapter：列出某 agent 的 skills
```
POST ${ADP}/api/v1/skills   {"action":"skill_list","agent_name":"<agent_name>"}
```

### adapter：读某 skill 全文
```
POST ${ADP}/api/v1/skills   {"action":"skill_content","agent_name":"<agent_name>","skill_name":"<skill>"}
```

### adapter：列出某 agent 的历史轨迹
```
GET ${ADP}/api/v1/agents/<agent_name>/traces
```
返回 `{"conversation_ids":[...],"total":N}`。

### adapter：取某条轨迹的清洗结果
```
GET ${ADP}/api/v1/agents/<agent_name>/cleaned-traces/<conversation_id>
```
返回 `{"session_id","agent_name","task_input","trajectory","messages"}`。

### adapter：调用真实 agent（产新轨迹 / 探针）
```
POST ${ADP}/api/v1/agents/<agent_name>/conversations/<conversation_id>
Content-Type: application/json
{"query":"<用户问题>"}
```
响应含 `answer` 与流式 `events`。调用会触发真实 agent 产生新轨迹（adapter 会归档），可用于攒数据或探针验证。

### 优化器：单条评估 / 批量评估 / 生成目标
```
POST ${OPT}/evaluate
POST ${OPT}/evaluate/dataset
GET  ${OPT}/evaluate/dataset/jobs/<job_id>
GET  ${OPT}/evaluate/dataset/jobs/<job_id>/stream
POST ${OPT}/evaluate/generate-goal
```

### 优化器 / adapter：健康检查
```
GET ${OPT}/health   → {"status":"ok"}
GET ${ADP}/health   → {"status":"ok"}
GET ${ADP}/api/v1/status   → 各 agent 的 trace 归档/offset 状态
```

---

## 边界

- 只调这两个服务（地址见文首"配置"，全文唯一地址来源）
- **不调 `/optimize`**——优化循环归 `skill-optimizer`；本 skill 只用 `/evaluate` 系列
- **不调 `update_skill`**——回写归 `skill-optimizer`；本 skill 只读 skill 内容
- 评估端点（`/evaluate`、`/evaluate/dataset`、`/evaluate/generate-goal`）每次必传 `llm_config`，**已内置于文首 `LLM_CONFIG` 块**（首次部署由维护者填值），agent 调用直接引用，无需用户每次提供
- `trajectory_path` 必须是优化器服务器上的路径；本地文件需先上传到服务器白名单目录，skill 不代传、不转换格式
- 评估产出（基线分、种子数据集）用于"看现状"和"给优化器喂数据"；优化循环内的 train/val 评估由 `skill-optimizer` 走 `/optimize` 自带，本 skill 不参与
