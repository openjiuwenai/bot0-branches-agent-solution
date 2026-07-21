# EvoAgent 优化 API 参考

> **版本**: 多 skill 增强（per-skill scores + skill_contents）
>
> **Base URL**: `http://localhost:5050`（开发环境）
>
> **Content-Type**: `application/json`

---

## 总览

| # | 操作 | 方法 | 路径 | 说明 |
|---|------|------|------|------|
| 1 | 提交优化任务 | POST | `/optimize` | 提交异步优化任务 |
| 2 | 查询任务状态 | GET | `/optimize/{job_id}` | 轮询任务进度 |
| 3 | 实时进度推送 | GET | `/optimize/{job_id}/stream` | SSE 流式推送 |
| 4 | 列出场景 | GET | `/scenarios` | 查询可用场景列表 |

---

## 1. 提交优化任务

提交一个异步优化任务，返回 `job_id` 用于后续查询。

**POST** `/optimize`

### Request Body（平台模板结构）

```json
{
  "task_name": "optimize-finance-agent-001",
  "agent_name": "edp_agent",
  "optimizer_type": "skill",
  "skills": ["product_recommend_skill", "fund_planning_skill"],
  "dataset_path": "/data/evo_agent/finance_cases.json",
  "optimizer_template": {
    "name": "edp_agent",
    "scenario": "金融客服",
    "hyperparams": {
      "num_epochs": 5,
      "batch_size": 8,
      "learning_rate": 0.01
    },
    "train_split": 0.8,
    "val_split": 0.2
  },
  "evaluator_template": {
    "name": "finance_eval",
    "scenario": "金融客服",
    "prompt": "评估回答的准确性、专业性和合规性。重点关注金融产品推荐的合规要求。"
  }
}
```

### 字段说明

**顶层字段**：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `task_name` | `string` | ✅ | — | 任务名称（用于追踪） |
| `agent_name` | `string` | ✅ | — | 目标 Agent 名称 |
| `optimizer_type` | `string` | ❌ | `"skill"` | 优化器类型（目前仅支持 `"skill"`） |
| `skills` | `array[string]` | ❌ | `[]` | 要优化的 skill 名称列表（空表示优化所有 skill） |
| `dataset_path` | `string` | ✅ | — | 数据集文件路径（需通过路径安全校验） |
| `optimizer_template` | `object` | ✅ | — | 优化器模板配置 |
| `evaluator_template` | `object` | ✅ | — | 评估器模板配置 |

**optimizer_template 字段**：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `name` | `string` | ✅ | — | 场景名称（映射到 `examples/scenarios/<name>/`） |
| `scenario` | `string` | ✅ | — | 业务场景标签（仅元数据，用于平台分类） |
| `hyperparams` | `object` | ❌ | `{}` | 优化超参数（`num_epochs`、`batch_size` 会被提取为 typed 字段） |
| `train_split` | `float` | ❌ | `0.8` | 训练集比例（需满足 `train_split + val_split == 1.0`） |
| `val_split` | `float` | ❌ | `0.2` | 验证集比例（需满足 `train_split + val_split == 1.0`） |

**evaluator_template 字段**：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `name` | `string` | ✅ | — | 评估器名称（仅元数据） |
| `scenario` | `string` | ✅ | — | 业务场景标签（仅元数据） |
| `prompt` | `string` | ❌ | `""` | 评估 prompt（为空时使用场景默认 prompt） |

### 路径安全校验

`dataset_path` 必须满足以下条件，否则返回 422：

1. **文件存在**: 路径必须指向实际存在的文件
2. **在允许根目录下**: 路径必须在 `EVO_ALLOWED_DATA_ROOTS` 配置的白名单目录下（防止路径穿越）
3. **文件大小限制**: 文件大小 ≤ 500MB
4. **是文件**: 必须是文件而非目录

### 归一化处理

请求经过 `_normalize()` 处理：

- `optimizer_template.name` → 内部 `scenario` 字段
- `evaluator_template.prompt` → 内部 `evaluator_prompt` 字段
- 从 `hyperparams` 中提取 `num_epochs`、`batch_size` 为 typed 字段
- 剩余 `hyperparams` 保留在 dict 中，注入到优化器 dependencies
- `adapter_url` 从 `EvolveConfig`（环境变量 `EVO_ADAPTER_URL`）注入

### Response `200 OK`

```json
{
  "job_id": "job_20260615_143022_abc123",
  "status": "queued",
  "progress": null,
  "result": null,
  "error": null
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `job_id` | `string` | 任务唯一 ID（用于后续查询） |
| `status` | `string` | 任务状态：`queued` / `running` / `completed` / `failed` |
| `progress` | `object` | 当前进度（提交时为 `null`） |
| `result` | `object` | 优化结果（完成后填充） |
| `error` | `string` | 错误信息（失败时填充） |

### Response `422 Unprocessable Entity`

请求校验失败：

```json
{
  "detail": [
    {
      "type": "value_error",
      "loc": ["body", "optimizer_template", "train_split"],
      "msg": "train_split + val_split must equal 1.0",
      "input": 0.7
    }
  ]
}
```

常见错误：

| 错误 | 原因 |
|------|------|
| `Dataset file not found` | `dataset_path` 指向的文件不存在 |
| `Dataset path must be under allowed roots` | 路径不在 `EVO_ALLOWED_DATA_ROOTS` 白名单下 |
| `Dataset file too large` | 文件大小超过 500MB |
| `train_split + val_split must equal 1.0` | 切分比例不满足约束 |
| `Scenario 'xxx' not found` | `optimizer_template.name` 对应的场景不存在 |

### Response `500 Internal Server Error`

服务端配置错误：

```json
{
  "detail": "EVO_ADAPTER_URL not configured"
}
```

---

## 2. 查询任务状态

轮询任务进度和结果。

**GET** `/optimize/{job_id}`

### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `job_id` | `string` | 任务 ID（从 POST /optimize 返回） |

### Response `200 OK`

**任务运行中**：

```json
{
  "job_id": "job_20260615_143022_abc123",
  "status": "running",
  "progress": {
    "current_epoch": 2,
    "total_epochs": 5,
    "current_step": 15,
    "val_score": 0.72,
    "best_score": 0.75,
    "edits_applied": 8
  },
  "result": null,
  "error": null
}
```

**任务完成**：

```json
{
  "job_id": "job_20260615_143022_abc123",
  "status": "completed",
  "progress": {
    "current_epoch": 5,
    "total_epochs": 5,
    "current_step": 40,
    "val_score": 0.85,
    "best_score": 0.85,
    "edits_applied": 23
  },
  "result": {
    "skills": ["product_recommend_skill", "fund_planning_skill"],
    "epochs_completed": 5,
    "edits_applied": 23,

    "train": {
      "score_before": 0.62,
      "score_after": 0.85,
      "improvement": "+37%",
      "pass_rate_before": 0.55,
      "pass_rate_after": 0.80,
      "num_cases": 40
    },

    "val": {
      "final_score": 0.82,
      "best_score": 0.85,
      "per_epoch_scores": [0.65, 0.72, 0.78, 0.82, 0.85],
      "num_cases": 10
    },

    "gate_results": ["candidate", "candidate", "candidate", "base", "candidate"],

    "skill_scores": [
      {
        "name": "product_recommend_skill",
        "score_before": 0.60,
        "score_after": 0.82,
        "score_delta": 0.22,
        "pass_rate_before": 0.50,
        "pass_rate_after": 0.75,
        "edits_applied": 12
      },
      {
        "name": "fund_planning_skill",
        "score_before": 0.64,
        "score_after": 0.88,
        "score_delta": 0.24,
        "pass_rate_before": 0.60,
        "pass_rate_after": 0.85,
        "edits_applied": 11
      }
    ],

    "skill_contents": [
      {
        "name": "product_recommend_skill",
        "content_before": "# 产品推荐 Skill\n...(初始全文)",
        "epoch_contents": [
          "epoch_0 后的 skill 全文",
          "epoch_1 后的 skill 全文",
          "epoch_2 后的 skill 全文",
          "epoch_3 后的 skill 全文",
          "epoch_4 后的 skill 全文"
        ]
      },
      {
        "name": "fund_planning_skill",
        "content_before": "# 资金规划 Skill\n...(初始全文)",
        "epoch_contents": [
          "epoch_0 后的 skill 全文",
          "epoch_1 后的 skill 全文",
          "epoch_2 后的 skill 全文",
          "epoch_3 后的 skill 全文",
          "epoch_4 后的 skill 全文"
        ]
      }
    ]
  },
  "error": null
}
```

**任务失败**：

```json
{
  "job_id": "job_20260615_143022_abc123",
  "status": "failed",
  "progress": {
    "current_epoch": 3,
    "total_epochs": 5,
    "current_step": 22,
    "val_score": 0.68,
    "best_score": 0.71,
    "edits_applied": 12
  },
  "result": null,
  "error": "Adapter sidecar connection timeout after 300s"
}
```

### progress 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `current_epoch` | `integer` | 当前 epoch（从 1 开始） |
| `total_epochs` | `integer` | 总 epoch 数 |
| `current_step` | `integer` | 当前 step（所有 epoch 累计） |
| `val_score` | `float` | 当前验证集分数 |
| `best_score` | `float` | 历史最佳验证集分数 |
| `edits_applied` | `integer` | 已应用的 skill 文档编辑次数 |

### result 字段说明

**顶层字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `skills` | `array[string]` | 优化的 skill 名称列表 |
| `epochs_completed` | `integer` | 完成的 epoch 数 |
| `edits_applied` | `integer` | 总编辑次数（所有 skill 合计） |
| `train` | `object` | 训练集分数汇总（见下表） |
| `val` | `object` | 验证集分数汇总（见下表） |
| `gate_results` | `array[string]` | 每轮 gate 决策：`"candidate"`（接受）/ `"base"`（拒绝） |
| `skill_scores` | `array[object]` | 每个 skill 的分数明细（见下表） |
| `skill_contents` | `array[object]` | 每个 skill 的内容快照（见下表） |

**train 子对象**（训练集分数，来自 optimizer rollout 的 `eval_results.json`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `score_before` | `float` | 优化前平均分（首个 train step 的 avg_score） |
| `score_after` | `float` | 优化后平均分（末尾 train step 的 avg_score） |
| `improvement` | `string` | 提升百分比（如 `"+37%"`） |
| `pass_rate_before` | `float` | 优化前通过率（score ≥ 0.5 的 case 占比） |
| `pass_rate_after` | `float` | 优化后通过率 |
| `num_cases` | `integer` | 参与评估的训练 case 总数 |

> **数据来源**：optimizer 在 train_split 上执行 rollout + evaluate，产出 `eval_results.json`。`score_before` 取自首个 step，`score_after` 取自末尾 step。`pass_rate` 基于 `score_threshold`（默认 0.5）计算。

**val 子对象**（验证集分数，来自 Trainer 的门控评估）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `final_score` | `float` | 最终 epoch 的验证集分数 |
| `best_score` | `float` | 历史最佳验证集分数 |
| `per_epoch_scores` | `array[float]` | 每轮 epoch 结束后的验证集分数（按 epoch 索引） |
| `num_cases` | `integer` | 验证集 case 总数 |

> **数据来源**：Trainer 在每个 epoch 结束后，用 val_split 数据评估当前 skill 状态。`per_epoch_scores[i]` 对应第 i 轮的验证分数，前端可据此绘制 val 分数趋势图。

**skill_scores 子对象**（每个 skill 的 train 分数明细）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | skill 名称 |
| `score_before` | `float` | 该 skill 优化前分数（train） |
| `score_after` | `float` | 该 skill 优化后分数（train） |
| `score_delta` | `float` | 分数变化（after - before） |
| `pass_rate_before` | `float` | 该 skill 优化前通过率 |
| `pass_rate_after` | `float` | 该 skill 优化后通过率 |
| `edits_applied` | `integer` | 该 skill 应用的编辑次数 |

**skill_contents 子对象**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | skill 名称 |
| `content_before` | `string` | 优化前的完整 skill 文档内容 |
| `epoch_contents` | `array[string]` | 每轮结束后的 skill 文档全文（按 epoch 索引） |

> **前端 diff 展示**：`content_before` 为初始内容，`epoch_contents[i]` 为第 i 轮后的内容。
> 前端可用 diff 库（如 diff2html）对比 `content_before` 与 `epoch_contents[-1]` 展示最终修改，
> 或逐轮对比展示优化演进过程。

### Response `404 Not Found`

```json
{
  "detail": "Job not found: job_20260615_143022_xyz999"
}
```

---

## 3. 实时进度推送

使用 Server-Sent Events (SSE) 实时推送任务进度更新。支持两种粒度的事件：

- **`log`** — Pipeline 阶段级日志，实时滚动展示当前正在执行的操作
- **`progress`** — 结构化进度更新（epoch 级别），用于进度条和数值展示
- **`completed`** / **`error`** — 终态事件

**GET** `/optimize/{job_id}/stream`

### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `job_id` | `string` | 任务 ID |

### Response `200 OK`

**Content-Type**: `text/event-stream`

**SSE 事件流示例**（1 个 epoch 的完整流程）：

```
event: progress
data: {"phase": "train_begin", "total_epochs": 3, "num_skills": 2, "num_train_cases": 40, "num_val_cases": 10, "baseline_score": 0.62}

event: log
data: {"level": "info", "message": "基线评估完成，初始分数 0.62", "phase": "train_begin", "epoch": 0, "data": {"baseline_score": 0.62}}

event: progress
data: {"phase": "epoch_begin", "epoch": 1, "total_epochs": 3}

event: log
data: {"level": "info", "message": "开始第 1 轮优化（共 3 轮）", "phase": "epoch_begin", "epoch": 1}

event: log
data: {"level": "info", "message": "Agent 执行中... (8/40 cases)", "phase": "rollout", "epoch": 1, "step": 1, "data": {"completed": 8, "total": 40}}

event: log
data: {"level": "info", "message": "Rollout 完成，平均分数 0.62", "phase": "rollout", "epoch": 1, "step": 1, "data": {"n_cases": 8, "avg_score": 0.62}}

event: log
data: {"level": "info", "message": "评估中... (5/8 cases)", "phase": "evaluate", "epoch": 1, "step": 1, "data": {"completed": 5, "total": 8}}

event: log
data: {"level": "info", "message": "评估完成：5 个失败 / 3 个成功", "phase": "evaluate", "epoch": 1, "step": 1, "data": {"n_failures": 5, "n_successes": 3, "failure_rate": 0.625}}

event: log
data: {"level": "info", "message": "归因完成：product_recommend_skill=4, fund_planning_skill=3, 未归因=1", "phase": "attribute", "epoch": 1, "step": 1, "data": {"attributions": {"product_recommend_skill": 4, "fund_planning_skill": 3}, "unattributed": 1}}

event: log
data: {"level": "info", "message": "反思完成：product_recommend_skill 发现 3 个失败模式，fund_planning_skill 发现 2 个", "phase": "reflect", "epoch": 1, "step": 1, "data": {"failure_patterns": {"product_recommend_skill": 3, "fund_planning_skill": 2}, "success_patterns": {"product_recommend_skill": 1, "fund_planning_skill": 2}, "total_patches": 8}}

event: log
data: {"level": "info", "message": "聚合完成：8 条建议合并为 5 条", "phase": "aggregate", "epoch": 1, "step": 1, "data": {"n_input": 8, "n_merged": 5}}

event: log
data: {"level": "info", "message": "选择完成：5 条中保留 3 条（edit_budget=3）", "phase": "select", "epoch": 1, "step": 1, "data": {"n_candidates": 5, "n_selected": 3, "budget": 3}}

event: log
data: {"level": "info", "message": "Skill 已更新：product_recommend_skill (+2 edits), fund_planning_skill (+1 edit)", "phase": "apply", "epoch": 1, "step": 1, "data": {"edits_per_skill": {"product_recommend_skill": 2, "fund_planning_skill": 1}}}

event: log
data: {"level": "info", "message": "Skill 同步完成，准备验证评估", "phase": "skill_sync", "epoch": 1}

event: log
data: {"level": "info", "message": "验证评估完成：candidate=0.68 vs base=0.62 → 接受修改 ✓", "phase": "validation", "epoch": 1, "data": {"candidate_score": 0.68, "base_score": 0.62, "gate_decision": "candidate"}}

event: progress
data: {"phase": "epoch_end", "epoch": 1, "total_epochs": 3, "val_score": 0.68, "best_score": 0.68, "edits_applied": 3, "train_score": 0.65, "pass_rate": 0.55, "skill_edits": {"product_recommend_skill": 2, "fund_planning_skill": 1}}

event: log
data: {"level": "info", "message": "第 1 轮完成：val 0.62 → 0.68 (+0.06)，应用 3 条修改", "phase": "epoch_end", "epoch": 1, "data": {"val_before": 0.62, "val_after": 0.68, "improvement": 0.06}}

event: progress
data: {"phase": "epoch_begin", "epoch": 2, "total_epochs": 3}

...（后续 epoch 重复上述流程）...

event: progress
data: {"phase": "train_end", "total_epochs": 3, "val_final_score": 0.82, "val_best_score": 0.85, "train_score_before": 0.62, "train_score_after": 0.85, "total_edits": 23}

event: completed
data: {"phase": "completed", "job_id": "job_20260615_143022_abc123", "status": "completed"}
```

### 事件类型

| 事件类型 | 触发频率 | 用途 | 说明 |
|----------|----------|------|------|
| `progress` | epoch 级别 | 进度条 + 数值面板 | 结构化数据，前端绑定到 UI 组件 |
| `log` | phase 级别 | 实时滚动日志 | 人类可读消息 + 结构化 data，前端渲染为滚动日志面板 |
| `completed` | 1 次 | 终态 | 任务成功完成 |
| `error` | 1 次 | 终态 | 任务失败 |

### log 事件通用结构

```json
{
  "level": "info | warning | error",
  "message": "人类可读的消息文本",
  "phase": "pipeline_phase_name",
  "epoch": 1,
  "step": 1,
  "data": { }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `level` | `string` | ✅ | 日志级别：`info` / `warning` / `error` |
| `message` | `string` | ✅ | 人类可读的消息文本（直接展示给用户） |
| `phase` | `string` | ✅ | 当前 pipeline 阶段（见下表） |
| `epoch` | `integer` | ❌ | 当前 epoch（从 1 开始） |
| `step` | `integer` | ❌ | 当前 step（epoch 内，从 1 开始） |
| `data` | `object` | ❌ | 结构化数据（前端可选展示为详情/图表） |

### Pipeline 阶段（phase）

| phase | 含义 | 典型 message | data 字段 |
|-------|------|-------------|-----------|
| `train_begin` | 训练开始 | "基线评估完成，初始分数 0.62" | `baseline_score`, `num_train_cases`, `num_val_cases` |
| `epoch_begin` | epoch 开始 | "开始第 2 轮优化（共 5 轮）" | — |
| `rollout` | Agent 执行 | "Agent 执行中... (5/8 cases)" | `completed`, `total`, `avg_score` |
| `evaluate` | 评估 | "评估完成：5 个失败 / 3 个成功" | `n_failures`, `n_successes`, `failure_rate` |
| `attribute` | 归因 | "归因完成：skill_a=4, skill_b=3" | `attributions: {skill: count}`, `unattributed` |
| `reflect` | 反思分析 | "反思完成：发现 3 个失败模式" | `failure_patterns: {skill: count}`, `success_patterns`, `total_patches` |
| `aggregate` | 建议聚合 | "聚合完成：8 条建议合并为 5 条" | `n_input`, `n_merged` |
| `select` | 编辑选择 | "选择完成：保留 3 条（budget=3）" | `n_candidates`, `n_selected`, `budget` |
| `apply` | 应用修改 | "Skill 已更新：skill_a (+2 edits)" | `edits_per_skill: {skill: count}` |
| `skill_sync` | Skill 同步 | "Skill 同步完成，准备验证评估" | — |
| `validation` | 验证评估 | "candidate=0.68 vs base=0.62 → 接受 ✓" | `candidate_score`, `base_score`, `gate_decision` |
| `epoch_end` | epoch 结束 | "第 2 轮完成：val 0.62 → 0.68" | `val_before`, `val_after`, `improvement` |
| `train_end` | 训练结束 | "优化完成：3 轮，分数提升 +37%" | `val_final_score`, `total_edits` |

### progress 事件结构

#### train_begin

```json
{
  "phase": "train_begin",
  "total_epochs": 5,
  "num_skills": 2,
  "num_train_cases": 40,
  "num_val_cases": 10,
  "baseline_score": 0.62
}
```

#### epoch_begin

```json
{
  "phase": "epoch_begin",
  "epoch": 2,
  "total_epochs": 5
}
```

#### epoch_end

```json
{
  "phase": "epoch_end",
  "epoch": 2,
  "total_epochs": 5,
  "val_score": 0.72,
  "best_score": 0.75,
  "train_score": 0.68,
  "pass_rate": 0.60,
  "edits_applied": 8,
  "skill_edits": {
    "product_recommend_skill": 5,
    "fund_planning_skill": 3
  },
  "gate_decision": "candidate"
}
```

#### train_end

```json
{
  "phase": "train_end",
  "total_epochs": 5,
  "val_final_score": 0.82,
  "val_best_score": 0.85,
  "train_score_before": 0.62,
  "train_score_after": 0.85,
  "total_edits": 23
}
```

### 前端集成建议

```
┌────────────────────────────────────────────────────────────┐
│  优化任务 #job_abc123                                       │
│  ─────────────────────────────────────────────              │
│  进度条:  ████████████░░░░░░░░  2/5 epochs                  │
│  Val 分数: 0.72 (best: 0.75)   Train: 0.68   通过率: 60%    │
│                                                             │
│  ┌─ 实时日志 ─────────────────────────────────────────────┐ │
│  │ [14:30:22] ▶ Agent 执行中... (5/8 cases)               │ │
│  │ [14:30:45] ✓ Rollout 完成，平均分数 0.68               │ │
│  │ [14:30:46] ▶ 评估中... (3/8 cases)                     │ │
│  │ [14:31:02] ✓ 评估完成：5 个失败 / 3 个成功             │ │
│  │ [14:31:03] ✓ 归因完成：skill_a=4, skill_b=3            │ │
│  │ [14:31:05] ▶ 反思分析中...                             │ │
│  │ [14:31:18] ✓ 反思完成：发现 3 个失败模式               │ │
│  │ [14:31:20] ✓ 聚合完成：8 → 5 条                       │ │
│  │ [14:31:22] ✓ 选择完成：保留 3 条                       │ │
│  │ [14:31:23] ✓ Skill 已更新                              │ │
│  │ [14:31:25] ▶ 验证评估中...                             │ │
│  │ [14:31:40] ✓ candidate=0.72 vs base=0.65 → 接受 ✓     │ │
│  │ [14:31:41] ★ 第 2 轮完成：val 0.65 → 0.72 (+0.07)    │ │
│  │                                                        │ │
│  │ [14:31:42] ▶ 开始第 3 轮优化（共 5 轮）               │ │
│  │ [14:31:43] ▶ Agent 执行中... (2/8 cases)               │ │
│  │ ...                                                    │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ Per-Skill 面板 ──────────────────────────────────────┐ │
│  │ product_recommend_skill: 0.60 → 0.72  edits: 5        │ │
│  │ fund_planning_skill:     0.64 → 0.68  edits: 3        │ │
│  └────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────┘
```

- **`log` 事件** → 渲染为滚动日志面板，`level=info` 绿色 ✓，`level=warning` 黄色 ⚠，`level=error` 红色 ✗
- **`progress` 事件** → 更新进度条、数值面板、per-skill 表格
- **`message` 字段** → 直接展示给用户（已本地化为中文）
- **`data` 字段** → 可选展开为详情（点击展开 JSON、图表等）

### 客户端示例（JavaScript）

```javascript
const eventSource = new EventSource('/optimize/job_abc123/stream');

// 实时日志（滚动面板）
eventSource.addEventListener('log', (event) => {
  const { level, message, phase, epoch, step, data } = JSON.parse(event.data);
  const icon = level === 'error' ? '✗' : level === 'warning' ? '⚠' : '✓';
  const time = new Date().toLocaleTimeString();
  logPanel.append(`[${time}] ${icon} ${message}\n`);
  logPanel.scrollTop = logPanel.scrollHeight;
});

// 结构化进度更新（进度条 + 数值面板）
eventSource.addEventListener('progress', (event) => {
  const data = JSON.parse(event.data);
  if (data.phase === 'epoch_end') {
    progressBar.update(data.epoch, data.total_epochs);
    scorePanel.update({
      val_score: data.val_score,
      best_score: data.best_score,
      train_score: data.train_score,
      pass_rate: data.pass_rate,
    });
    skillPanel.update(data.skill_edits);
  }
});

// 终态
eventSource.addEventListener('completed', (event) => {
  const data = JSON.parse(event.data);
  showResult(data.job_id);
  eventSource.close();
});

eventSource.addEventListener('error', (event) => {
  const data = JSON.parse(event.data);
  showError(data.error);
  eventSource.close();
});
```

### Response `404 Not Found`

```json
{
  "detail": "Job not found: job_20260615_143022_xyz999"
}
```

### 实现说明

当前 Trainer 回调仅有 4 个钩子（`on_train_begin/end`, `on_train_epoch_begin/end`），无法直接推送 phase 级别事件。需要在 `EDPAgentOptimizer` 中注入 phase callback：

| 阶段 | 注入点 | 方式 |
|------|--------|------|
| `rollout` | `_rollout()` override | 在 `super()._rollout()` 前后推送 |
| `evaluate` | `_rollout()` override | 评估循环内推送进度 |
| `attribute` | `_attribute()` override | 在 `super()._attribute()` 后推送归因统计 |
| `reflect` | `_backward()` override | 在 reflect 阶段后推送（需 override） |
| `aggregate` | `_backward()` override | 在 aggregate 阶段后推送 |
| `select` | `_backward()` override | 在 select 阶段后推送 |
| `apply` | `_backward()` override | 在 apply 阶段后推送 |
| `validation` | `ProgressCallback.on_train_epoch_end` | 从 `eval_info` 提取 gate 信息 |

建议在 `optimizer_runner.py` 构建 optimizer 时，将 `job.push_event` 作为 `phase_callback` 参数注入。

---

## 4. 列出场景

查询所有可用的优化场景。

**GET** `/scenarios`

### Response `200 OK`

```json
[
  {
    "name": "edp_agent",
    "optimizer_class": "scenarios.edp_agent.optimizer.EDPAgentOptimizer",
    "hyperparams": {
      "trace_max_retries": 3,
      "trace_retry_backoff": 2.0
    }
  },
  {
    "name": "customer_service",
    "optimizer_class": "scenarios.customer_service.optimizer.CustomerServiceOptimizer",
    "hyperparams": {}
  }
]
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 场景名称（用于 `optimizer_template.name`） |
| `optimizer_class` | `string` | 场景 optimizer 类的完整路径 |
| `hyperparams` | `object` | 场景默认超参数（可被请求中的 `hyperparams` 覆盖） |

---

## 错误处理

### 统一错误格式

FastAPI 标准错误响应：

```json
{
  "detail": "错误描述"
}
```

或校验错误：

```json
{
  "detail": [
    {
      "type": "value_error",
      "loc": ["body", "field_name"],
      "msg": "错误消息",
      "input": "错误输入值"
    }
  ]
}
```

### HTTP 状态码

| 状态码 | 场景 |
|--------|------|
| `200` | 请求成功 |
| `404` | 资源不存在（job_id 或 scenario 不存在） |
| `422` | 请求体校验失败（Pydantic 验证错误、路径安全校验失败） |
| `500` | 服务端配置错误（如 `EVO_ADAPTER_URL` 未配置） |

---

## 配置依赖

API 端点依赖以下环境变量（在 `.env` 或 `EvolveConfig` 中配置）：

| 环境变量 | 必填 | 默认值 | 说明 |
|----------|------|--------|------|
| `EVO_ADAPTER_URL` | ✅ | — | Adapter sidecar 地址（未配置时 POST /optimize 返回 500） |
| `EVO_ALLOWED_DATA_ROOTS` | ❌ | `/data/evo_agent,/tmp/evo_agent` | `dataset_path` 允许的根目录（逗号分隔） |
| `EVO_LLM_API_KEY` | ✅ | — | LLM API 密钥 |
| `EVO_OPTIMIZER_MODEL` | ❌ | `gpt-4o` | 优化器使用的模型 |
| `EVO_DEFAULT_EPOCHS` | ❌ | `3` | 默认 epoch 数（当 `hyperparams` 未指定时使用） |
| `EVO_DEFAULT_BATCH_SIZE` | ❌ | `4` | 默认 batch size（当 `hyperparams` 未指定时使用） |

---

## 完整示例

### 1. 提交优化任务

```bash
curl -X POST http://localhost:5050/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "task_name": "optimize-001",
    "agent_name": "edp_agent",
    "dataset_path": "/data/evo_agent/finance_cases.json",
    "optimizer_template": {
      "name": "edp_agent",
      "scenario": "金融客服",
      "hyperparams": {"num_epochs": 5},
      "train_split": 0.8,
      "val_split": 0.2
    },
    "evaluator_template": {
      "name": "finance_eval",
      "scenario": "金融客服",
      "prompt": "评估回答的准确性和专业性"
    }
  }'
```

Response:

```json
{
  "job_id": "job_20260615_143022_abc123",
  "status": "queued"
}
```

### 2. 轮询任务状态

```bash
curl http://localhost:5050/optimize/job_20260615_143022_abc123
```

Response:

```json
{
  "job_id": "job_20260615_143022_abc123",
  "status": "completed",
  "result": {
    "skills": ["product_recommend_skill"],
    "epochs_completed": 3,
    "edits_applied": 23,

    "train": {
      "score_before": 0.62,
      "score_after": 0.85,
      "improvement": "+37%",
      "pass_rate_before": 0.55,
      "pass_rate_after": 0.80,
      "num_cases": 40
    },

    "val": {
      "final_score": 0.82,
      "best_score": 0.85,
      "per_epoch_scores": [0.65, 0.72, 0.82],
      "num_cases": 10
    },

    "gate_results": ["candidate", "candidate", "candidate"],

    "skill_scores": [
      {
        "name": "product_recommend_skill",
        "score_before": 0.62,
        "score_after": 0.85,
        "score_delta": 0.23,
        "pass_rate_before": 0.55,
        "pass_rate_after": 0.80,
        "edits_applied": 23
      }
    ],

    "skill_contents": [
      {
        "name": "product_recommend_skill",
        "content_before": "# 产品推荐 Skill\n...",
        "epoch_contents": ["...", "...", "..."]
      }
    ]
  }
}
```

### 3. 实时监听进度

```bash
curl -N http://localhost:5050/optimize/job_20260615_143022_abc123/stream
```

Output:

```
event: progress
data: {"type": "progress", "current_epoch": 1, "val_score": 0.65}

event: progress
data: {"type": "progress", "current_epoch": 2, "val_score": 0.72}

event: completed
data: {"type": "completed", "job_id": "job_20260615_143022_abc123"}
```

---

## 架构说明

详见：

- **Adapter 契约**: `docs/api/adapter-api-contract.md` — EvoAgent 与 Adapter 的通信约定
- **Adapter 契约**: `docs/api/adapter-api-contract.md` — Adapter sidecar API 定义
