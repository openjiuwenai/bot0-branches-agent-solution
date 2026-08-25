# agent-evolve 用户 API 文档

## 目录

- [0. 概览](#0-概览)
- 第一部分:EvoAgent API
  - [1. 轨迹评估器](#1-轨迹评估器)
  - [2. 数据集生成器](#2-数据集生成器)
  - [3. 优化器](#3-优化器)
- 第二部分:Agent Adapter API
  - [1. 端点总览](#1-端点总览)
  - [2. 代理调用业务 Agent](#2-代理调用业务-agent)
  - [3. Skill 更新与管理](#3-skill-更新与管理)
  - [4. 文档更新与管理)](#4-文档更新与管理)
  - [5. 轨迹查询](#5-轨迹查询)
  - [6. Agent 配置热更(CRUD)](#6-agent-配置热更crud)
  - [7. 服务信息](#7-服务信息)

---

## 0. 概览

agent-evolve 包含两个独立部署的服务,二者通过 HTTP 协作:

```
                                          ┌──────────────────────────┐
   用户 / 平台                             │   Agent Adapter (sidecar) │
        │                                 │   默认端口 8900           │
        │ ① 提交优化任务 / 评估              │   - 采集业务 Agent 轨迹   │
        ▼                                 │   - Skill / managed-doc  │
 ┌─────────────────┐  ② 拉取轨迹(rollout)  │     热更新               │
 │  EvoAgent 引擎  │ ───────────────────▶ │   - 代理调用业务 Agent    │
 │  默认端口        │  ③ 回写 skill 文档   └──────────────────────────┘
 │  8000(Docker)/  │ ◀───────────────────               │
 │                 │                                    │ ④ 采集日志/Kafka/PG
 └─────────────────┘                                    ▼
                                                    业务 Agent
```

| 服务            | 默认端口          | Base URL             | 说明                                            |
| ------------- | ------------- | -------------------- | --------------------------------------------- |
| EvoAgent      | Docker `8000` | `http://<host>:8000` | 轨迹评估、数据集生成、优化器引擎                              |
| Agent Adapter | `8900`        | `http://<host>:8900` | 业务 Agent 轨迹采集 + Skill/managed-doc 热更新 sidecar |

---

# 第一部分:EvoAgent API

Base URL:`http://<evoagent-host>:8000`(Docker)或 `http://<evoagent-host>:8001`(本地 `make serve`)。

本部分按核心能力组织:**轨迹评估器** → **数据集生成器** → **优化器**,随后是服务信息、异步任务机制等通用内容。

---

## 1. 轨迹评估器

对 Agent 执行轨迹做 LLM/metric 评估、生成用户目标,或对整份数据集批量评估(异步)。

### 1.1 同步评估单条轨迹 POST /evaluate

不走优化管线,直接对一条轨迹做 LLM/metric 评估。

**请求体** `EvaluateRequest`

| 字段                | 类型             | 必填  | 默认   | 说明                                                   |
| ----------------- | -------------- |:---:| ---- | ---------------------------------------------------- |
| `trajectory_path` | string         | ✓   | —    | 轨迹 JSON 文件的服务器路径                                     |
| `prompt_template` | string         | ✓   | —    | LLM 评估 prompt 模板                                     |
| `llm_config`      | object         | ✓   | —    | LLM 配置,见下                                            |
| `expected_result` | object \| null |     | null | 期望结果                                                 |
| `skill_names`     | string[]       | ✓   | —    | skill 名列表                                            |
| `filters`         | object \| null |     | null | 过滤层配置(`tool_failure`/`user_feedback`,确定性短路 bad case) |

**`llm_config`**

| 字段                | 类型     | 必填  | 默认         | 说明       |
| ----------------- | ------ |:---:| ---------- | -------- |
| `model_name`      | string | ✓   | —          | 模型名      |
| `api_key`         | string | ✓   | —          | API Key  |
| `api_base`        | string | ✓   | —          | 接口地址     |
| `client_provider` | string |     | `"OpenAI"` | provider |
| `temperature`     | float  |     | `0.1`      |          |
| `max_tokens`      | int    |     | `2048`     |          |
| `verify_ssl`      | bool   |     | `false`    |          |

**响应** `200` `EvaluateResponse`

| 字段                 | 类型             | 说明                                       |
| ------------------ | -------------- | ---------------------------------------- |
| `status`           | string         | `"evaluated"` / `"filtered"`(过滤短路)       |
| `score`            | float          | 综合分 [0,1];filtered 时 0.0                 |
| `is_pass`          | bool           | 默认 `true`                                |
| `per_metric`       | object \| null | 各维度分;filtered 时 `{"filter_failure":0.0}` |
| `reason`           | string         | 评估理由                                     |
| `attributed_skill` | string         | 归因 skill(默认 `""`)                        |
| `filter_matches`   | object[]       | 过滤匹配详情;evaluated 时空                      |

**状态码**:200(含 filtered 短路)、404(轨迹文件不存在)、422(轨迹格式/filter 配置无效/未知 provider)、500(LLM 调用失败 `"Evaluation failed: ..."`)

```bash
curl -X POST http://localhost:8000/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "trajectory_path": "/data/t.json",
    "prompt_template": "{trajectory_section}",
    "llm_config": {"model_name":"qwen-plus","api_key":"sk-x","api_base":"https://..."},
    "skill_names": ["s1"]
  }'
```

### 1.2 生成用户目标 POST /evaluate/generate-goal

根据对话消息生成结构化用户目标。

**请求体** `GenerateGoalRequest`:`messages: object[]`(必填,每条至少含 `role`)、`llm_config: object`(同 1.1)。

**响应** `200` `GenerateGoalResponse`:`status`(默认 `"generated"`)、`goal: string`、`metadata: object`。

**状态码**:200、422(messages 空/缺 role)、500(`"Goal generation failed: ..."`)。

```bash
curl -X POST http://localhost:8000/evaluate/generate-goal \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"查询余额"}],"llm_config":{...}}'
```

### 1.3 离线数据集评估(异步) POST /evaluate/dataset

上传数据集文件,按评估组批量评估,异步 job 执行,支持 SSE 进度。

**Content-Type**:`multipart/form-data`

| 字段       | 类型           | 必填  | 说明                                         |
| -------- | ------------ |:---:| ------------------------------------------ |
| `file`   | file         | ✓   | 数据集文件(json/jsonl/csv/xlsx),上限 100MB,超限 413 |
| `config` | string(Form) | ✓   | JSON blob,解析为 `DatasetEvalConfig`          |

**`DatasetEvalConfig`**

| 字段           | 类型              | 必填  | 默认   | 说明                 |
| ------------ | --------------- |:---:| ---- | ------------------ |
| `id_field`   | string          |     | `""` | 记录 ID 字段名          |
| `groups`     | object[](min 1) | ✓   | —    | 评估组列表              |
| `llm_config` | object \| null  |     | null | 含 `llm_judge` 组时必填 |

**`groups[]`**

| 字段              | 类型                                              | 必填  | 默认                                              | 说明                             |
| --------------- | ----------------------------------------------- |:---:| ----------------------------------------------- | ------------------------------ |
| `name`          | string                                          | ✓   | —                                               | 组名                             |
| `kind`          | `"exact_match"` \| `"keyword"` \| `"llm_judge"` | ✓   | —                                               | 评估方式                           |
| `pred_field`    | string                                          | ✓   | —                                               | 预测值字段                          |
| `gold_field`    | string                                          |     | `""`                                            | 真值字段(exact_match/llm_judge 必填) |
| `keywords`      | string[]                                        |     | `[]`                                            | keyword 必填非空                   |
| `json_key`      | string                                          |     | `""`                                            |                                |
| `labels`        | string[]                                        |     | `[]`                                            | llm_judge 必填非空,不得含保留词 `"其他"`   |
| `extract_key`   | string                                          |     | `""`                                            | llm_judge 必填                   |
| `batch_metrics` | string[](min 1)                                 |     | `["mean","precision","recall","f1","accuracy"]` | 须在 `VALID_BATCH_METRICS` 内     |

**提交响应** `200` `DatasetEvalSubmitResponse`:`job_id`、`dataset_id`、`status`。

**状态码**:422(config/组校验失败)、413(文件过大)、500(`llm_judge` 组 LLM 探测失败 `"LLM judge config probe failed: ..."`)

```bash
curl -X POST http://localhost:8000/evaluate/dataset \
  -F "file=@/data/dataset.json" \
  -F 'config={"id_field":"id","groups":[{"name":"g1","kind":"exact_match","pred_field":"pred","gold_field":"gold"}]}'
```

### 1.4 查询数据集评估任务 GET /evaluate/dataset/jobs/{job_id}

**响应** `200` `JobResponse`(本地定义,字段:`job_id`、`status`、`progress`、`result`、`error`)。`progress` 为 `{phase, done, total}`,phase ∈ {ingest, scoring, aggregate}。

`result` 结构:`per_case[]`、`aggregate`、`overall`、`extraction_summary`。

**响应** `404` `{"detail":"Job not found: <id>"}`

### 1.5 数据集评估 SSE GET /evaluate/dataset/jobs/{job_id}/stream

SSE 通用格式见 §4.2。progress 事件 data:`{phase, done, total}`,phase ∈ {ingest, scoring, aggregate};终态 `completed`/`error`。

### 1.6 提交 Agent-as-a-judge 评估 POST /evaluate/agent-judge


**请求体** `AgentJudgeRequest`

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- |:---:| --- | --- |
| `trajectory_path` | string | ✓ | — | 轨迹 JSON 文件的服务器路径 |
| `preset` | string | ✓ | — | 预置维度组合(如 `default` / `jiuwenswarm_default`) |
| `skill_names` | string[] | ✓ | — | skill 名列表(min 1) |
| `expected_result` | object \| null | | null | 期望结果 |
| `runtime` | `"claude"` \| `"codex"` \| `"jiuwenswarm"` \| null | | null | 裁判 Agent CLI;缺省按配置 |
| `tool_allowlist` | string[] \| null | | null | 裁判子进程工具白名单 |
| `skill_source` | `"local"` \| `"adapter"` \| `"none"` | | `"none"` | 维度 helper skill 挂载来源 |
| `skill_root` | string \| null | | null | `local` 模式的 skill 根目录 |
| `max_concurrent` | int \| null | | null | 并发维度数(默认 6) |
| `run_timeout` | float \| null | | null | 单维度超时秒数(默认 300) |
| `keep_on_error` | bool | | false | 维度失败是否保留判定 |
| `extra_env` | object \| null | | null | 注入裁判子进程的环境变量 |
| `agent_profile` | string \| null | | null | `jiuwenswarm` 运行时的 ACP agent 配置名(claude/codex 忽略) |
| `trajectory_budget` | int \| null | | `4000` | 压缩轨迹 token 预算(>0);超预算报 `prompt_budget_exceeded` |
| `dimension_thresholds` | object | ✓ | — | 各维度阈值(0-1);`is_pass` = 全部维度 ≥ 阈值 |

**响应** `200` `AgentJudgeSubmitResponse`:`job_id`、`status`。

**状态码**:200、422(轨迹文件不存在/格式无效/messages 空/preset 未知)、500(评估失败)。

```bash
curl -X POST http://localhost:8000/evaluate/agent-judge \
  -H "Content-Type: application/json" \
  -d '{
    "trajectory_path": "/data/t.json",
    "preset": "default",
    "skill_names": ["s1"],
    "dimension_thresholds": {"task_completion": 0.8}
  }'
```

### 1.7 查询 Agent-as-a-judge 任务 GET /evaluate/agent-judge/jobs/{job_id}

**响应** `200` `JobResponse`(与 dataset 同形:`job_id`、`status`、`progress`、`result`、`error`)。`progress` 为 `{phase:"judge", done, total}`。

`result`(`AgentJudgeResultBody`)结构:

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `score` | float | 综合分 [0,1](确定性 WeightedSumScorer 计算) |
| `is_pass` | bool | 全部维度 ≥ 各自阈值 |
| `per_metric` | object | 各维度分 |
| `dimensions` | object | 维度名 → 分数 |
| `dimension_checks` | object[] | 维度判定明细 |
| `skill_attributions` | object[] | 复数 Skill 归因 |
| `attribution_status` | string | 归因状态 |
| `attribution_error` | string \| null | 归因错误信息 |
| `attributed_skill` | string | 单数归因 skill(兼容字段) |
| `reason` | string | 评估理由(含复数归因 JSON) |

**响应** `404` `{"detail":"Job not found: <id>"}`

### 1.8 Agent-as-a-judge SSE GET /evaluate/agent-judge/jobs/{job_id}/stream

SSE 通用格式见 §4.2。progress 事件 data:`{phase:"judge", done, total}`;终态 `completed`/`error`(含 `category`)/`cancelled`。

---

## 2. 数据集生成器

从轨迹生成 golden data:在线产期望行为(EB)、离线建全局理解(GU)索引。

### 2.1 在线生成期望行为 POST /golden_data/expected-behavior

根据内联轨迹生成 Expected Behavior(EB),同步返回。

**请求体** `GenerateEBRequest`

| 字段                 | 类型             | 必填  | 默认   | 说明                            |
| ------------------ | -------------- |:---:| ---- | ----------------------------- |
| `messages`         | object[]       | ✓   | —    | 内联轨迹(每条至少 role)               |
| `llm_config`       | object \| null |     | null | 缺省时 fallback 读 `EvolveConfig` |
| `attributed_skill` | string \| null |     | null |                               |

**响应** `200` `GenerateEBResponse`:`status`(默认 `"generated"`)、`items: object[]`(每条 `{id, inputs, expected_behavior}`)、`metadata: object`、`internal: object`(备查,不进 optimizer)。

**状态码**:422(messages 空/缺 role)、500(`"EB generation failed: ..."`)。

```bash
curl -X POST http://localhost:8000/golden_data/expected-behavior \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"..."}],"llm_config":{...}}'
```

### 2.2 离线建全局理解 POST /golden_data/global-understanding

上传 trace 文件,异步构建 Global Understanding(GU)索引,支持 SSE 进度。

**Content-Type**:`multipart/form-data`

| 字段       | 类型           | 必填  | 说明                                                                     |
| -------- | ------------ |:---:| ---------------------------------------------------------------------- |
| `file`   | file         | ✓   | trace 文件(JSON 数组/对象 或 JSONL,每条 `{messages, summary?}`),上限 100MB,超限 413 |
| `config` | string(Form) | ✓   | JSON blob,解析为 `BuildGUConfig`                                          |

**`BuildGUConfig`**

| 字段               | 类型                       | 必填  | 默认        | 说明                                |
| ---------------- | ------------------------ |:---:| --------- | --------------------------------- |
| `source`         | `"local"` \| `"adapter"` |     | `"local"` | skill 源                           |
| `skill_root`     | string                   |     | `""`      | `local` 必填                        |
| `adapter_url`    | string                   |     | `""`      | `adapter` 必填                      |
| `agent_name`     | string                   |     | `""`      | `adapter` 必填                      |
| `llm_config`     | object \| null           |     | null      | 缺省 fallback `EvolveConfig`        |
| `flat_threshold` | int                      |     | `30`      | skill 数 ≤ 阈值走 flat,否则 progressive |
| `batch_size`     | int                      |     | `10`      | 归纳批大小                             |
| `skill_names`    | string[]                 |     | `[]`      | 空则用 `skill_provider.list_skills`  |

**提交响应** `200` `BuildGUSubmitResponse`:`job_id`、`status`。

**状态码**:422(config/skill 源校验失败/trace 解析失败)、413(文件过大)。

```bash
curl -X POST http://localhost:8000/golden_data/global-understanding \
  -F "file=@/data/traces.jsonl" \
  -F 'config={"source":"local","skill_root":"/data/skills"}'
```

### 2.3 查询建 GU 任务 GET /golden_data/jobs/{job_id}

**响应** `200` `JobResponse`(本地定义)。`result`:`mode`(`"flat"`/`"progressive"`)、`skills: string[]`、`out_of_scope_count: int`、`last_run_id: string`。

### 2.4 建 GU SSE GET /golden_data/jobs/{job_id}/stream

SSE 通用格式见 §4.2。progress 事件 data:`{phase:"build", done, total}`;终态 `completed`/`error`。

---

## 3. 优化器

提交 Skill / managed-doc / tool 优化任务,异步执行 ReflACT 管线,支持轮询、SSE、取消与幂等重放。

### 3.1 提交优化任务 POST /optimize

提交一个 Skill / managed-doc / tool 优化任务,返回 `job_id` 用于轮询与 SSE 订阅。

**请求体** `OptimizeAPIRequest`

| 字段                              | 类型                                  | 必填  | 默认        | 说明                                                      |
| ------------------------------- | ----------------------------------- |:---:| --------- | ------------------------------------------------------- |
| `task_name`                     | string                              | ✓   | —         | 任务追踪名                                                   |
| `agent_name`                    | string                              | ✓   | —         | Adapter 业务 Agent 名，与场景键无关                                              |
| `optimizer_type`                | `"skill"` \| `"prompt"` \| `"tool"` |     | `"skill"` | 优化器类型                                                   |
| `optimizer_template`            | object                              | ✓   | —         | 优化器模板,见下表                                               |
| `evaluator_template`            | object                              | ✓   | —         | 评估器模板,见下表                                               |
| `skills`                        | string[]                            |     | `[]`      | skill 名列表(`skill` 模式必填,与 `managed_doc_kind` 互斥)         |
| `dataset_path`                  | string                              | ✓   | —         | 数据集文件路径(须在 `EVO_ALLOWED_DATA_ROOTS` 白名单内,文件 ≤500MB)     |
| `managed_doc_kind`              | string \| null                      |     | null      | managed-doc 单文档模式 doc_kind(`prompt` 模式必填,与 `skills` 互斥) |
| `client_task_id`                | string \| null                      |     | null      | 幂等键(`prompt` 模式必填)                                      |
| `managed_doc_expected_revision` | string \| null                      |     | null      | `prompt` 模式必填                                           |

**`optimizer_template`**

| 字段            | 类型     | 必填  | 默认    | 说明                                                   |
| ------------- | ------ |:---:| ----- | ---------------------------------------------------- |
| `name`        | string | ✓   | —     | 模板名/显示名；仅当 scenario 为空时才回退为场景键                                     |
| `scenario`    | string | ✓   | —     | 算法名称（加载 examples/scenarios/<scenario>/），如 skillopt / tf_grpo / gepa                                               |
| `hyperparams` | object |     | `{}`  | 超参;`num_epochs`(1-100)、`batch_size`(1-64) 会被提取为强类型字段 |
| `rollout`     | object |     | —     | `rollout.extra_data: object`                         |
| `train_split` | float  |     | `0.8` | 训练集比例                                                |
| `val_split`   | float  |     | `0.2` | 验证集比例(`train_split + val_split == 1.0`)              |

**`evaluator_template`**

| 字段              | 类型                    | 必填  | 默认              | 说明                                                         |
| --------------- | --------------------- |:---:| --------------- | ---------------------------------------------------------- |
| `name`          | string                | ✓   | —               | 显示名                                                        |
| `scenario`      | string                | ✓   | —               | 仅元数据，不参与选算法                                                       |
| `type`          | `"llm"` \| `"metric"` |     | `"metric"`      | 评估器类型                                                      |
| `prompt`        | string                |     | `""`            | `llm` 评估 prompt(`metric` 忽略)                               |
| `metric`        | string \| string[]    |     | `"exact_match"` | 指标名                                                        |
| `extract`       | object \| null        |     | null            | 仅 `metric` 且指标为 `exact_match`/`normalized_exact_match` 时有效 |
| `aggregate`     | string                |     | `"mean"`        | 聚合方式                                                       |
| `batch_metrics` | string[]              |     | `[]`            | 须与 `batch_score` 同时出现                                      |
| `batch_score`   | string                |     | `""`            | 须与 `batch_metrics` 同时出现                                    |

**响应** `200` `JobResponse`

| 字段                       | 类型             | 说明                                                          |
| ------------------------ | -------------- | ----------------------------------------------------------- |
| `job_id`                 | string         | 12 位十六进制任务 ID                                               |
| `status`                 | string         | `queued` / `running` / `completed` / `failed` / `cancelled` |
| `progress`               | object \| null | 提交时为 `null`                                                 |
| `result`                 | object \| null | 完成后填充                                                       |
| `error`                  | string \| null | 失败原因                                                        |
| `error_code`             | string \| null | 失败错误码                                                       |
| `cancellation_requested` | bool           | 默认 `false`                                                  |


### 3.2 查询任务状态 GET /optimize/{job_id}

**Path 参数**:`job_id`

**响应** `200` `JobResponse`(同 3.1)。运行中 `progress` 字段:

| 字段              | 类型            | 说明               |
| --------------- | ------------- | ---------------- |
| `current_epoch` | int           | 当前 epoch(从 1)    |
| `total_epochs`  | int           | 总 epoch          |
| `current_step`  | int           | 当前累计 step        |
| `val_score`     | float \| null | 候选 fresh eval 分数 |
| `best_score`    | float \| null | 门控历史最佳           |
| `edits_applied` | int           | 已生效编辑数           |

完成后 `result` 顶层字段:`skills`、`epochs_completed`、`edits_applied`、`train`、`val`、`gate_results`、`skill_scores`、`skill_contents`、`managed_doc_kind`、`managed_doc_content_before`、`managed_doc_content_after`、`managed_doc_epoch_contents`、`managed_doc_task_ids`。

- `train` / `val` 子对象:`score_before`、`score_after`/`final_score`、`best_score`、`improvement`(如 `"+37%"`)、`pass_rate_before`、`pass_rate_after`、`num_cases`,`val` 另含 `per_epoch_scores`。
- `skill_scores[]`:`name`、`score_before`、`score_after`、`score_delta`、`edits_applied`、`pass_rate_before`、`pass_rate_after`。
- managed-doc 模式下 `skill_scores`/`skill_contents` 为空,改填 `managed_doc_*` 字段。

**响应** `404` `{"detail": "Job not found: <id>"}`

```bash
curl http://localhost:8000/optimize/<job_id>
```

### 3.3 实时进度(SSE) GET /optimize/{job_id}/stream

返回 `text/event-stream`,实时推送任务进度与阶段事件。SSE 通用格式(帧结构、`Last-Event-ID` 重放、keepalive、终态事件)见 §4.2。

```bash
curl -N http://localhost:8000/optimize/<job_id>/stream
```

### 3.4 取消任务 POST /optimize/{job_id}/cancel

运行中任务取消,等待管线协作停止(对 managed-doc 会触发回滚流程)。

**响应** `202` `JobResponse`(含 `cancellation_requested: true`)

| 状态码 | 说明                                                                  |
| --- | ------------------------------------------------------------------- |
| 404 | 任务不存在                                                               |
| 409 | 任务已终态,`{"detail":"Cannot cancel job in terminal status: <status>"}` |

```bash
curl -X POST http://localhost:8000/optimize/<job_id>/cancel
```


---

# 第二部分:Agent Adapter API

Base URL:`http://<adapter-host>:8900`。FastAPI 应用 `title="Agent Adapter", version="0.2.0"`。本部分按核心能力组织:**代理调用** → **Skill 热更新** → **managed-doc** → **轨迹查询** → **配置热更**,服务信息后置。

## 1. 端点总览

| #   | 方法     | 路径                                                             | 用途                        |
| --- | ------ | -------------------------------------------------------------- | ------------------------- |
| 1   | GET    | `/health`                                                      | 健康检查                      |
| 2   | POST   | `/api/v1/agents/{agent_name}/conversations/{conversation_id}`  | 代理调用业务 Agent(聚合或 SSE 透传)  |
| 3   | POST   | `/api/v1/skills`                                               | Skill 列表/读内容/更新/恢复 + SkillHub 发布/拉取(北向) |
| 4   | POST   | `/api/v1/managed-docs`                                         | managed-doc 读内容/更新/恢复     |
| 5   | GET    | `/api/v1/agents/{agent_name}/managed-docs`                     | 列出 Agent 已注册文档(restart 类) |
| 6   | GET    | `/api/v1/managed-docs/tasks/{task_id}`                         | 轮询异步 apply/restart 任务     |
| 7   | GET    | `/api/v1/agents/{agent_name}/traces`                           | 列出指定 agent 的会话            |
| 8   | GET    | `/api/v1/agents/{agent_name}/traces/{conversation_id}`         | 查询指定 agent+会话轨迹           |
| 9   | GET    | `/api/v1/traces`                                               | 列出所有会话(多 agent 聚合)        |
| 10  | GET    | `/api/v1/traces/{conversation_id}`                             | 查询会话轨迹(带 complete 信号)     |
| 11  | GET    | `/api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}` | 返回清洗后的 LLM 对话             |
| 12  | GET    | `/api/v1/status`                                               | 运行状态                      |
| 13  | GET    | `/api/v1/config/agents`                                        | 列出已配置 agent               |
| 14  | GET    | `/api/v1/config/agents/{name}`                                 | 查单个 agent 配置              |
| 15  | POST   | `/api/v1/config/agents`                                        | 新增 agent(热更)              |
| 16  | PUT    | `/api/v1/config/agents/{name}`                                 | 修改 agent(热更)              |
| 17  | DELETE | `/api/v1/config/agents/{name}`                                 | 删除 agent(热更)              |

---

## 2. 代理调用业务 Agent

### POST /api/v1/agents/{agent_name}/conversations/{conversation_id}

通过 Adapter 转调业务 Agent 的 SSE 端点。

**请求体** `AgentCallRequest`:`query: string`(必填)、`extra_data: object|null`(转发到业务 Agent `custom_data.inputs`)。

**响应模式**(由请求头 `Accept` 决定):

- **聚合**(默认):消费完整 SSE 流后返回 `AgentCallResponse`。
- **透传**(`Accept: text/event-stream`):`StreamingResponse` 原样转发 SSE,响应头 `Cache-Control: no-cache`、`X-Accel-Buffering: no`。

**`AgentCallResponse`**:`success: bool`、`conversation_id`、`answer: string`(默认 `""`)、`interrupted: bool`(默认 `false`)、`interrupt_intent`、`interrupt_description`、`events: object[]|null`(每项 `{type, content, plugin}`)、`error: string|null`。

**错误**:`AGENT_NOT_FOUND`(404,agent 未配置)、`INVALID_ACTION`(400,未配置 `agent_url`)。

```bash
# 聚合
curl -X POST http://localhost:8900/api/v1/agents/xxx_agent/conversations/conv-001   -H "Content-Type: application/json" -d '{"query":"你好","extra_data":{"temperature":0.7}}'

# 流式透传
curl -N -H "Accept: text/event-stream" -X POST   http://localhost:8900/api/v1/agents/xxx_agent/conversations/conv-001   -H "Content-Type: application/json" -d '{"query":"你好"}'
```

---

## 3. Skill 热更新

### 3.1 POST /api/v1/skills

**请求体** `SkillActionRequest`:`agent_name: string`(必填)、`action`(必填,见下)、`skill_name`(skill_content/update_skill 必填)、`skill_names`(restore_skill 必填)、`skill_content`(update_skill 必填)。

| action          | 响应                                                                     |
| --------------- | ---------------------------------------------------------------------- |
| `skill_list`    | `{"skills":[{"name":"..."}]}`                                          |
| `skill_content` | `{"skill_name":"...","content":"..."}`                                 |
| `update_skill`  | `{"success":true,"skill_name":"...","revision":"<sha256>","message"?}` |
| `restore_skill` | `{"restored":[{"skill_name":"...","success":bool,"message"?}]}`        |

**错误**:`INTERNAL_ERROR`(500,skill_store 未初始化)、`SKILL_NOT_FOUND`(404)、`INVALID_ACTION`(400,非法 skill 名)。

```bash
curl -X POST http://localhost:8900/api/v1/skills -H "Content-Type: application/json"   -d '{"agent_name":"xxx_agent","action":"skill_list"}'
```

### 3.2 SkillHub 市场对接（北向）

复用 `POST /api/v1/skills`,新增 hub 类 action,实现优化后 Skill 的发布/拉取/版本管理。**默认关闭**,需配置 `ADAPTER_SKILLHUB_ENABLED=true` 及 token 等(见 §7.2);未启用时返回 `SKILLHUB_DISABLED`(503)。

**新增 action**

| action | 必填字段 | 响应要点 |
| --- | --- | --- |
| `list_hub_skills` | —(`page` 默认 1、`page_size` 默认 20、`keyword`) | Hub 插件分页列表 |
| `get_hub_version` | `asset_id`、`version` | 版本详情 |
| `pull_skill` | `asset_id`、`version`(`overwrite` 默认 true) | `{asset_id, skill_name, version, local_path, revision}` |
| `publish_skill` | `skill_name`(`plugin_version`/`asset_id`/`version_desc`/`force` 默认 false) | `{asset_id, skill_name, version, plugin_type, publish_result, moderation_status, checksum_sha256, version_desc, local_revision}` |
| `delete_hub_version` | `asset_id`、`version` | `{asset_id, version, deleted}` |

**错误码**:`SKILLHUB_DISABLED`(503)、`INVALID_ACTION`(400,参数/action 非法)、`HUB_AUTH_FAILED`(401)、`HUB_NOT_FOUND`(404)、`HUB_CONFLICT`(409,版本冲突)、`HUB_ERROR`(502,Hub 调用失败)、`AGENT_NOT_FOUND`(404,agent 未配置)。

```bash
curl -X POST http://localhost:8900/api/v1/skills -H "Content-Type: application/json" \
  -d '{"agent_name":"xxx_agent","action":"publish_skill","skill_name":"s1","version_desc":"优化后发布"}'
```

---

## 4. managed-doc(受管文档)

受 Adapter 管理的 Agent 规则文档(如 `agent_rule`),支持读取、更新(触发文件写入 + 可选重启)、恢复。

### 4.1 POST /api/v1/managed-docs

**请求体** `ManagedDocActionRequest`:`agent_name`(必填)、`doc_kind`(必填,如 `agent_rule`)、`action`(必填)、`content`(`update` 必填)。

| action        | HTTP | 响应                                                                                                                                                      |
| ------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `content`(同步) | 200  | `{doc_kind, content, file_revision, applied_revision, pending_apply, apply_mode, max_task_seconds}`                                                     |
| `update`(异步)  | 202  | `{task_id, status:"PENDING", doc_kind}`;已 applied 幂等返回 `{success:true, doc_kind, revision, pending_apply:false, message:"already applied, no restart"}` |
| `restore`(异步) | 202  | 从首版快照恢复;无快照 → 404 `DOC_NOT_FOUND`                                                                                                                       |

内容校验失败 → 400 `INVALID_ACTION`,不落盘:frontmatter 必须以 `---\n...\n---\n` 包裹且为合法 YAML、body 非空、UTF-8 可编码、字节数 ≤ `max_content_bytes`(默认 262144 = 256 KiB)。

### 4.2 GET /api/v1/agents/{agent_name}/managed-docs

列出该 Agent 已注册的 **restart** 类文档(file_only 不列出)。响应 `ManagedDocListResponse`:`agent_name`、`items[]`(每项 `{doc_kind, display_name, filename, apply_mode, max_task_seconds}`)、`total`。Agent 不存在 → 404。

### 4.3 GET /api/v1/managed-docs/tasks/{task_id}

轮询异步 apply/restart 任务。响应字段:`task_id`、`status`(`PENDING`/`RUNNING`/`SUCCEEDED`/`FAILED`)、`doc_kind`、`action`、`attempts`、`down_seen`、`revision`、`pending_apply`、`last_error`、`created_at`、`updated_at`。任务不存在或 TTL 过期 → 404 `TASK_NOT_FOUND`。

### 4.4 apply 模式

| 模式              | 行为                                                                                      |
| --------------- | --------------------------------------------------------------------------------------- |
| `file_only`(默认) | 仅原子写文件,无重启/健康检查;`max_task_seconds=0`                                                    |
| `restart`       | 必填 `restart_cmd`;执行 `max_attempts` 次重启子进程 → 两阶段健康探活(down 探测 → up 连续 N 次 200)→ 失败按指数退避重试 |

restart 健康检查字段(`health_url`/`health_down_timeout`/`health_up_timeout`/`health_up_consecutive`/`health_poll_interval`)可由 profile 基线填充:

| profile               | max_attempts | backoff_base | backoff_max | health_down_timeout | health_up_timeout | health_up_consecutive | health_poll_interval |
| --------------------- |:------------:|:------------:|:-----------:|:-------------------:|:-----------------:|:---------------------:|:--------------------:|
| `burst`(默认,canary 训练) | 2            | 3.0          | 30.0        | 15.0                | 60.0              | 2                     | 0.5                  |
| `single`(人工单次)        | 3            | 5.0          | 60.0        | 30.0                | 90.0              | 2                     | 1.0                  |

`update`/`restore` 原子写文件后创建 PENDING 任务(`t_<12 hex>`),后台 apply,HTTP 立即返回 202;per-agent `asyncio.Lock` 串行化同 agent 的更新/恢复(**不阻塞 `call_agent`**);完成态任务在 `task_ttl_seconds`(默认 600s)后惰性驱逐;崩溃续跑靠幂等重发。

```bash
curl -X POST http://localhost:8900/api/v1/managed-docs -H "Content-Type: application/json"   -d '{"agent_name":"xxx_agent","doc_kind":"agent_rule","action":"content"}'
curl -X POST http://localhost:8900/api/v1/managed-docs -H "Content-Type: application/json"   -d '{"agent_name":"xxx_agent","doc_kind":"agent_rule","action":"update","content":"---\nkey: v\n---\n# body"}'
curl http://localhost:8900/api/v1/managed-docs/tasks/<task_id>
```

---

## 5. 轨迹查询

| 端点                                                | 说明                            | Query                           | 响应要点                                                                                                                           |
| ------------------------------------------------- | ----------------------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `GET /api/v1/agents/{name}/traces`                | 列指定 agent 会话(先增量 poll)        | —                               | `{conversation_ids, total}`;agent 不存在 → 404                                                                                    |
| `GET /api/v1/agents/{name}/traces/{conv}`         | 查 agent+会话轨迹                  | `complete: bool`、`limit: int≥1` | `{conversation_id, calls, total}`                                                                                              |
| `GET /api/v1/traces`                              | 多 agent 聚合(先对每 pipeline poll) | —                               | `{conversation_ids, total}`                                                                                                    |
| `GET /api/v1/traces/{conv}`                       | 查会话轨迹(带 complete 信号)          | `complete: bool`、`limit: int≥1` | `{conversation_id, calls, total, complete}`                                                                                    |
| `GET /api/v1/agents/{name}/cleaned-traces/{conv}` | 返回清洗后 LLM 对话                  | —                               | `{session_id, agent_name, task_input, trajectory:{total_messages, tool_calls_used, summary}, messages}`;无 GENERATION 记录返回 `{}` |

**`complete` 信号**:standard 模式先轮询 PG 等根 span(`trace_wait_timeout` 默认 10s),根 span 到达且 `end_time` 已设 → `true`;log 模式 = 无 `_incomplete` 记录。

**attribution(Skill 归属)**:standard(OTel/PG)模式下,`calls[]` 各调用记录内联携带 `parent_span_id` 与 `attribution`(如 `{skill, source, confidence, candidates}`),由 `AttributionRunner` 在 trace 完整后写回 spans;log 模式无此字段。子 agent session 按 `<主session>-sub-entity_<id>` 同源后缀纳入查询。

```bash
curl http://localhost:8900/api/v1/agents/xxx_agent/traces
curl "http://localhost:8900/api/v1/traces/conv-001?complete=true&limit=10"
curl http://localhost:8900/api/v1/agents/xxx_agent/cleaned-traces/conv-001
```

---

## 6. Agent 配置热更(CRUD)

对 `agents[]` 配置增删改,写回 YAML 并热重载 Pipeline/AgentClient。

| 方法     | 路径                             | 说明                          | 状态码                |
| ------ | ------------------------------ | --------------------------- | ------------------ |
| GET    | `/api/v1/config/agents`        | 列出(去除 None)                 | 200                |
| GET    | `/api/v1/config/agents/{name}` | 查单个                         | 200 / 404          |
| POST   | `/api/v1/config/agents`        | 新增(body=AgentEntryConfig)   | 200 / 409(重名)/ 422 |
| PUT    | `/api/v1/config/agents/{name}` | 修改                          | 200 / 404 / 422    |
| DELETE | `/api/v1/config/agents/{name}` | 删除 → `{"deleted":"<name>"}` | 200 / 404          |

POST/PUT 对 `log_pattern`/`output_dir`/`offset_file`/`skills_dir` 做默认填充;YAML 原子写,用 `app.state._config_lock` 串行化并发写;未传 `--config` 时写回路径回退到 adapter 根的 `agent_adapter_config.yaml`。

```bash
curl -X POST http://localhost:8900/api/v1/config/agents -H "Content-Type: application/json"   -d '{"name":"new_agent","agent_url":"http://x:8090","project_id":"p","agent_id":"a"}'
```

---

## 7. 服务信息

### 7.1 健康检查与状态

| 端点                   | 响应                                                                                                                                                                   |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /health`        | `{"status":"ok"}`                                                                                                                                                    |
| `GET /api/v1/status` | 单 agent:扁平 `{agent_name, active_file, offset, pending_starts_count, last_read_time, output_dir_files, uptime_seconds}`;多 agent:`{"agents":{"<name>":<上述扁平结构>, ...}}` |

```bash
curl http://localhost:8900/health
curl http://localhost:8900/api/v1/status
```

### 7.2 配置参考

配置优先级:**环境变量 `ADAPTER_*` > YAML > 默认值**(环境变量设过的字段,其 YAML 值被忽略)。完整字段见 `Adapter部署配置说明`。关键字段:

| YAML            | 环境变量                    | 默认              | 说明                                       |
| --------------- | ----------------------- | --------------- | ---------------------------------------- |
| `host`          | `ADAPTER_HOST`          | `0.0.0.0`       | 监听地址                                     |
| `port`          | `ADAPTER_PORT`          | `8900`          | 监听端口                                     |
| `log_dir`       | `ADAPTER_LOG_DIR`       | `logs`          | 日志源目录                                    |
| `log_pattern`   | `ADAPTER_LOG_PATTERN`   | `process*.log` | 日志 glob                                  |
| `poll_interval` | `ADAPTER_POLL_INTERVAL` | `60`            | 轮询间隔(秒)                                  |
| `start_from`    | `ADAPTER_START_FROM`    | `tail`          | 首次读取位置                                   |
| `trace_source`  | `ADAPTER_TRACE_SOURCE`  | `log`           | `log`(读归档日志,零依赖)/ `standard`(读 PG+Kafka) |
| `skillhub_enabled` | `ADAPTER_SKILLHUB_ENABLED` | `false` | SkillHub 对接开关 |
| `skillhub_base_url` | `ADAPTER_SKILLHUB_BASE_URL` | `""` | SkillHub 服务地址 |
| `skillhub_auth_mode` | `ADAPTER_SKILLHUB_AUTH_MODE` | `system_token` | 认证方式(`bearer`/`system_token`) |
| `skillhub_token` | `ADAPTER_SKILLHUB_TOKEN` | `""` | 认证 token |
| `skillhub_token_env` | `ADAPTER_SKILLHUB_TOKEN_ENV` | `SKILLHUB_TOKEN` | token 来源环境变量名 |
| `skillhub_version_strategy` | `ADAPTER_SKILLHUB_VERSION_STRATEGY` | `manual` | 版本策略(`patch`/`manual`) |

> README 示例与部署 YAML 中部分默认值与源码不一致(如 `start_from`、`poll_interval`、`match_tags` 列表),以源码为准(详见部署指南"配置一致性"节)。
