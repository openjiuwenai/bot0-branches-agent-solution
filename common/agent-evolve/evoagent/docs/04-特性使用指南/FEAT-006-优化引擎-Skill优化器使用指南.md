# 优化引擎-Skill优化器使用指南

> 本指南介绍如何使用 EvoAgent，通过 SkillOpt 或 TF-GRPO 算法，对业务 Agent 的 Skill 文档（`SKILL.md`）进行自动优化。

---

## 1. 特性概览

### 1.1 这是什么

> **Skill 文档优化** 通过 Adapter 真实 rollout、评估打分与验证集门控（val gate），自动改写 Adapter 侧托管的业务 `SKILL.md`，适用于需要提升 Skill 规则质量、又不想训练模型权重的场景。

机制摘要：

1. 从 Adapter **拉取**当前 skill 内容；
2. 在标注数据集上做 **真实 rollout**（经 Adapter 调用业务 Agent）；
3. 用所选算法提出改进（局部 patch 或整文档变体）；
4. 通过 Adapter 进行热更新，用 **val gate** 决定是否采纳；
5. 输出报告与 artifact 快照。artifact 指本次优化任务落盘的产物目录（由 `EVO_ARTIFACT_DIR` 配置），通常包含门控结果、skill 内容快照等；完成后可在该目录核对优化前后内容。

### 1.2 本指南覆盖范围

本指南包含：

- `optimizer_type=skill` 的 Skill 文档优化（SkillOpt / TF-GRPO）；
- API 提交与观测：`POST /optimize`、`GET /optimize/{job_id}`、`GET /optimize/{job_id}/stream`、`POST /optimize/{job_id}/cancel`、`GET /scenarios`；
- 请求字段、超参、产物验证。

本指南不包含：

- `optimizer_type=prompt` 的提示词 / managed-doc 优化；
- tool 优化。

---



## 2. 什么时候使用

当你需要**自动优化 Adapter 托管的业务 Agent Skill 文档（`SKILL.md`）**时使用本优化器：已有可标注的训练/验证样例与评估标准，希望通过真实对话 rollout 与验证集门控改进 Skill 规则，并可在 **SkillOpt** 与 **TF-GRPO** 之间按任务选型。

---



## 3. 准备工作



### 3.1 环境要求


| 服务               | 要求                                          | 检查命令                                   |
| ---------------- | ------------------------------------------- | -------------------------------------- |
| EvoAgent         | 已启动，可访问 `/health`、`/optimize`               | `curl -s http://localhost:8001/health` |
| EvoAgent-Adapter | `EVO_ADAPTER_URL` 已配置且可达                    | `curl -s "${EVO_ADAPTER_URL}/health"`  |
| LLM 服务           | `EVO_LLM_API_KEY`、`EVO_OPTIMIZER_MODEL` 等可用 | 提交一次 Job，鉴权失败会出现在 `error`              |
| 业务 Agent / Skill | `agent_name`、`skills` 必须是 Adapter 已注册名称     | 对照 Adapter 配置；不要把算法名称当成 Agent 名        |
| 数据集              | 落在 `EVO_ALLOWED_DATA_ROOTS` 内，至少 2 条 case   | 确认文件存在且可读                              |


（可选）`GET /scenarios` 应至少包含算法名称 `skillopt` 与 `tf_grpo`。

### 3.2 准备数据集与评估器

本指南统一使用 **JSON 数组（EvoCase）** 作为数据集：文件放在白名单路径下，通过请求字段 `dataset_path` 传入。

每条 case 至少包含：


| 字段                  | 类型     | 必填  | 说明                                                      |
| ------------------- | ------ | --- | ------------------------------------------------------- |
| `id`                | string | 是   | case 唯一标识                                               |
| `inputs`            | array  | 是   | 用户输入数组；支持字符串数组（推荐入门），或 `[{"role","content"}, ...]` 消息数组 |
| `expected_behavior` | string | 是   | 期望行为，供评估器打分                                             |
| `extra_data`        | object | 否   | 透传给 invoke 的额外字段                                        |


最小示例（字符串数组，推荐）：

```json
[
  {
    "id": "c001",
    "inputs": ["用户问题1", "确认"],
    "expected_behavior": "回答应包含责任归属字段",
    "extra_data": {}
  },
  {
    "id": "c002",
    "inputs": ["用户问题2"],
    "expected_behavior": "回答应给出明确处理建议",
    "extra_data": {}
  }
]
```

输入也可以按如下示例传入（可选；以 `role=user` 的 `content` 作为用户输入）：

```json
[
  {
    "id": "c003",
    "inputs": [
      {"role": "user", "content": "用户问题1"},
      {"role": "assistant", "content": "系统答复1"}
    ],
    "expected_behavior": "回答应包含责任归属字段",
    "extra_data": {}
  }
]
```

要点：

- `train_split` + `val_split` 必须等于 `1.0`，且均大于 `0`；
- API 当前不传切分 seed，每次随机划分。

评估器在 `evaluator_template` 中配置，二选一：


| type     | 什么时候用          | 说明                                                         |
| -------- | -------------- | ---------------------------------------------------------- |
| `metric` | 有明确标准答案、可字段比对  | 对 Agent 返回的 `answer`（可配 `extract`）做确定性打分；默认类型              |
| `llm`    | 开放式回答、需按期望行为判断 | 用大模型阅读轨迹打分；`prompt` 建议含 `{messages}`、`{expected_behavior}` |


---



## 4. 快速上手

下列步骤以本机 `http://localhost:8001` 为例（Linux / bash）。

### 4.1 准备输入

确认服务已启动，数据集文件路径在 `EVO_ALLOWED_DATA_ROOTS` 内（下例为 `/data/evo_agent/finance_cases.json`）。

写入请求体：

```bash
cat > opt-skillopt.json <<'EOF'
{
  "task_name": "opt-skillopt-demo",
  "agent_name": "demo_agent",
  "optimizer_type": "skill",
  "skills": ["product_recommend_skill"],
  "dataset_path": "/data/evo_agent/finance_cases.json",
  "optimizer_template": {
    "name": "skillopt-demo",
    "scenario": "skillopt",
    "hyperparams": {
      "num_epochs": 2,
      "batch_size": 4,
      "edit_budget": 10
    },
    "train_split": 0.8,
    "val_split": 0.2,
    "rollout": { "extra_data": {} }
  },
  "evaluator_template": {
    "name": "llm_judge",
    "scenario": "finance",
    "type": "llm",
    "prompt": "评估回答是否满足 expected_behavior。期望：{expected_behavior}\n轨迹：{messages}"
  }
}
EOF
```

> `agent_name` 是 Adapter 侧业务 Agent 名；`optimizer_template.name` 是**模板名称**；`optimizer_template.scenario` 是**算法名称**（如 `skillopt`）。三者不要混用。



### 4.2 执行操作

```bash
curl -s -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d @opt-skillopt.json
```



### 4.3 查看结果

成功提交时应返回：

```json
{
  "job_id": "job_xxxxxxxx",
  "status": "queued",
  "progress": null,
  "result": null,
  "error": null,
  "error_code": null,
  "cancellation_requested": false
}
```

轮询状态（将 `{job_id}` 换成上一步返回值）：

```bash
curl -s http://localhost:8001/optimize/{job_id}
```

任务 `completed` 后，`result` 中常见字段包括：`skills`、`epochs_completed`、`edits_applied`、`train`/`val` 得分、`gate_results`、`skill_scores`、`skill_contents`。

产物目录默认在 `EVO_ARTIFACT_DIR` 下，形如 `./workspace/artifacts/{run_id}/`。

### 4.4 验证是否成功

```bash
curl -s http://localhost:8001/optimize/{job_id}
```

预期结果：

```text
status 为 completed；error 为空；
result 含基线与最终分、gate_results 等；
workspace/artifacts/<run_id>/ 下有 epoch / gate 相关文件。
```

> 完成本节后，你应该已经能独立跑通一次 SkillOpt 最小流程。

---



## 5. 接口与配置



### 5.1 接口清单


| 方法   | 路径                          | 作用       | 适用场景                      |
| ---- | --------------------------- | -------- | ------------------------- |
| GET  | `/health`                   | 健康检查     | 探活                        |
| GET  | `/scenarios`                | 列出可用算法   | 确认 `skillopt` / `tf_grpo` |
| POST | `/optimize`                 | 提交优化 Job | 主入口                       |
| GET  | `/optimize/{job_id}`        | 查询状态与结果  | 轮询                        |
| GET  | `/optimize/{job_id}/stream` | SSE 实时事件 | 长任务观测                     |
| POST | `/optimize/{job_id}/cancel` | 取消 Job   | 中断长跑                      |




### 5.2 请求参数


| 字段                   | 类型       | 必填  | 默认值     | 说明                                       |
| -------------------- | -------- | --- | ------- | ---------------------------------------- |
| `task_name`          | string   | 是   | 无       | 任务显示名                                    |
| `agent_name`         | string   | 是   | 无       | Adapter 侧业务 Agent 名（不等于算法名称）             |
| `optimizer_type`     | string   | 否   | `skill` | Skill 优化固定为 `skill`                      |
| `skills`             | string[] | 是   | 无       | 非空 skill 名列表；禁止与 `managed_doc_kind` 同时出现 |
| `dataset_path`       | string   | 是   | 无       | 已存在的数据集 JSON 文件；须在白名单根下，不超过 500MB        |
| `optimizer_template` | object   | 是   | 无       | 优化器模板与超参                                 |
| `evaluator_template` | object   | 是   | 无       | 评估配置                                     |
| `managed_doc_kind`   | string   | 否   | 无       | Skill 模式不要传此字段                           |


> 选择算法看 `optimizer_template.scenario`（非空）或回退 `name`。`name` 是模板名称，不必然等于算法名称。



### 5.3 配置项

`optimizer_template`：


| 配置项                  | 类型     | 必填  | 默认值   | 说明                                              |
| -------------------- | ------ | --- | ----- | ----------------------------------------------- |
| `name`               | string | 是   | 无     | 模板名称（平台标识/显示名）；仅当 `scenario` 为空时回退用作算法名称        |
| `scenario`           | string | 是   | 无     | 算法名称：`skillopt` 或 `tf_grpo`                     |
| `hyperparams`        | object | 否   | `{}`  | 可含 `num_epochs`（1–100）、`batch_size`（1–64）及算法专属键 |
| `train_split`        | float  | 否   | `0.8` | 与 `val_split` 之和必须为 `1.0`，且均大于 `0`              |
| `val_split`          | float  | 否   | `0.2` | 同上                                              |
| `rollout.extra_data` | object | 否   | `{}`  | 与算法默认 rollout 合并后传给 invoke                      |


合并优先级：

```text
请求里的 hyperparams  >  算法目录 scenario.yaml 中的默认 hyperparams  >  环境变量 EVO_*
```

算法默认配置位于服务端 `examples/scenarios/<算法名称>/scenario.yaml`（如 `skillopt`、`tf_grpo`）。

关键环境变量：


| 配置项                      | 说明                   |
| ------------------------ | -------------------- |
| `EVO_ADAPTER_URL`        | Adapter 地址（API 提交必填） |
| `EVO_ALLOWED_DATA_ROOTS` | 数据集路径白名单             |
| `EVO_LLM_API_KEY`        | 优化用 LLM 凭证           |
| `EVO_OPTIMIZER_MODEL`    | 优化用模型名               |


产物目录见 `EVO_ARTIFACT_DIR`（可选配置）。

### 5.4 模式与变体


| 算法名称       | 特点                             | 适用场景              | 注意事项                                          |
| ---------- | ------------------------------ | ----------------- | --------------------------------------------- |
| `skillopt` | 反思后有界 edit/patch，经 val gate 采纳 | 局部规则修补、多 skill 归因 | 新接入优先选用                                       |
| `tf_grpo`  | 多变体 rollout 与组内比较              | 文档级探索             | 成本随 `group_size × cases × epochs` 放大；先用最小参数跑通 |


选择建议：

- 需要稳定渐进修补：使用 `skillopt`；
- 需要更强探索：使用 `tf_grpo`；
- 不确定时：优先 `skillopt`。

TF-GRPO 常用超参（写入 `optimizer_template.hyperparams`）：


| 配置项                   | 类型    | 必填  | 默认值   | 说明               |
| --------------------- | ----- | --- | ----- | ---------------- |
| `group_size`          | int   | 否   | `3`   | 每 epoch 变体个数     |
| `cases_per_variant`   | int   | 否   | `8`   | 组内共用的 train 子集大小 |
| `variant_temperature` | float | 否   | `1.5` | 变体生成温度           |
| `max_experiences`     | int   | 否   | `20`  | 经验库容量            |




### 5.5 请求示例

```bash
curl -s -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d @opt-skillopt.json
```



### 5.6 返回结果

提交成功：

```json
{
  "job_id": "job_xxxxxxxx",
  "status": "queued",
  "progress": null,
  "result": null,
  "error": null,
  "error_code": null,
  "cancellation_requested": false
}
```


| 字段                       | 类型     | 说明                                                            |
| ------------------------ | ------ | ------------------------------------------------------------- |
| `job_id`                 | string | 任务 ID，用于轮询 / SSE / cancel                                     |
| `status`                 | string | 如 `queued` / `running` / `completed` / `failed` / `cancelled` |
| `progress`               | object | 运行中进度；提交时多为 null                                              |
| `result`                 | object | 完成后的优化报告摘要                                                    |
| `error`                  | string | 失败信息                                                          |
| `error_code`             | string | 规范化错误码                                                        |
| `cancellation_requested` | bool   | 是否已请求取消                                                       |


`result` 常见子字段：`skills`、`epochs_completed`、`edits_applied`、`train`/`val`（含 `score_before`、`score_after`/`final_score`、`improvement`）、`gate_results`、`skill_scores`、`skill_contents`。

### 5.7 状态码与异常


| 状态码或现象      | 含义       | 常见原因                                 | 处理方式                              |
| ----------- | -------- | ------------------------------------ | --------------------------------- |
| 500         | 服务端或依赖失败 | 未设 `EVO_ADAPTER_URL` / Adapter 不可达   | 检查环境变量与 Adapter `/health`         |
| 422 dataset | 数据集不可用   | 路径不存在或不在白名单                          | 调整 `EVO_ALLOWED_DATA_ROOTS` 或移动文件 |
| 422 skills  | 参数不合法    | 未传 `skills`，或与 `managed_doc_kind` 冲突 | 只传非空 `skills`                     |
| 404 Job     | 任务不存在    | `job_id` 错误                          | 核对提交返回的 `job_id`                  |
| 409 cancel  | 无法取消     | Job 已处于终态                            | 无需再 cancel                        |
| 算法找不到       | 加载算法配置失败 | `scenario` 写成业务标签                    | 改为 `skillopt` 或 `tf_grpo`         |


---



## 6. 场景用例

每个用例含：适用场景、完整命令、预期结果。

### 6.1 使用 SkillOpt（`skillopt`）进行单 Skill 优化

适用于：对**单个 skill** 做小步数、渐进式 patch 优化。

```bash
cat > opt-skillopt.json <<'EOF'
{
  "task_name": "opt-skillopt-demo",
  "agent_name": "demo_agent",
  "optimizer_type": "skill",
  "skills": ["product_recommend_skill"],
  "dataset_path": "/data/evo_agent/finance_cases.json",
  "optimizer_template": {
    "name": "skillopt-demo",
    "scenario": "skillopt",
    "hyperparams": {
      "num_epochs": 2,
      "batch_size": 4,
      "edit_budget": 10
    },
    "train_split": 0.8,
    "val_split": 0.2
  },
  "evaluator_template": {
    "name": "llm_judge",
    "scenario": "finance",
    "type": "llm",
    "prompt": "评估回答是否满足 expected_behavior。期望：{expected_behavior}\n轨迹：{messages}"
  }
}
EOF

curl -s -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d @opt-skillopt.json
```

预期结果：

```json
{
  "job_id": "job_xxxxxxxx",
  "status": "queued"
}
```

轮询至 `completed` 后，`result` 应含得分与门控摘要；产物在 `EVO_ARTIFACT_DIR` 下。

### 6.2 使用 TF-GRPO（`tf_grpo`）进行探索式优化

适用于：需要**多变体 Skill 探索与组内比较**、可接受更高 rollout 成本的任务；同一 Agent / 数据集下将 `scenario` 设为 `tf_grpo` 即可切换算法。

```bash
cat > opt-tfgrpo.json <<'EOF'
{
  "task_name": "opt-tfgrpo-demo",
  "agent_name": "demo_agent",
  "optimizer_type": "skill",
  "skills": ["audit-business"],
  "dataset_path": "/data/evo_agent/cases.json",
  "optimizer_template": {
    "name": "tfgrpo-demo",
    "scenario": "tf_grpo",
    "hyperparams": {
      "num_epochs": 1,
      "batch_size": 4,
      "group_size": 2,
      "cases_per_variant": 2
    },
    "train_split": 0.5,
    "val_split": 0.5
  },
  "evaluator_template": {
    "name": "exact",
    "scenario": "audit",
    "type": "metric",
    "metric": "exact_match",
    "extract": {
      "strategy": "answer_tag_json_field",
      "source": "answer",
      "fields": ["responsibility"]
    }
  }
}
EOF

curl -s -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d @opt-tfgrpo.json
```

预期结果：先返回 `job_id` 与 `queued`，最终 `completed`。  
建议先用最小参数跑通流程：`group_size=1`、`cases_per_variant=1`、`num_epochs=1`，确认能完成后再加大。

### 6.3 多 skill 用例

适用于：一次任务需要同时优化**多个 skill**；在请求的 `skills` 数组中传入多个名称即可。SkillOpt 与 TF-GRPO 均支持多 skill；将 `optimizer_template.scenario` 设为对应算法名称即可。

```bash
cat > opt-multi-skill.json <<'EOF'
{
  "task_name": "opt-multi-skill-demo",
  "agent_name": "demo_agent",
  "optimizer_type": "skill",
  "skills": [
    "product_recommend_skill",
    "interact_finance_rec_skill"
  ],
  "dataset_path": "/data/evo_agent/finance_cases.json",
  "optimizer_template": {
    "name": "skillopt-multi-demo",
    "scenario": "skillopt",
    "hyperparams": {
      "num_epochs": 2,
      "batch_size": 4,
      "edit_budget": 10
    },
    "train_split": 0.8,
    "val_split": 0.2
  },
  "evaluator_template": {
    "name": "llm_judge",
    "scenario": "finance",
    "type": "llm",
    "prompt": "评估回答是否满足 expected_behavior。期望：{expected_behavior}\n轨迹：{messages}"
  }
}
EOF

curl -s -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d @opt-multi-skill.json
```

预期结果：

```json
{
  "job_id": "job_xxxxxxxx",
  "status": "queued"
}
```

完成后 `result.skills` 应包含所传多个 skill；门控与产物按 skill 分别体现。

---



## 7. 常见问题



### 7.1 故障排查表


| 现象                          | 原因                                     | 处理方式                                                              |
| --------------------------- | -------------------------------------- | ----------------------------------------------------------------- |
| POST 500 / Adapter 相关错误     | 未设 `EVO_ADAPTER_URL` 或 sidecar 不可达     | 检查环境变量与 Adapter `/health`                                         |
| 422：dataset 路径              | 文件不存在或不在白名单                            | 调整 `EVO_ALLOWED_DATA_ROOTS` 或移动文件                                 |
| 422：skills 校验失败             | 未传 `skills`，或与 `managed_doc_kind` 同时出现 | 只传非空 `skills`                                                     |
| 算法找不到                       | `scenario` 写成业务标签                      | 改为 `skillopt` 或 `tf_grpo`                                         |
| 切分后 train/val 为空            | case 太少或比例不当                           | 至少 2 条；保证两侧至少各 1 条                                                |
| TF-GRPO 很慢 / 消耗 LLM Token 高 | `group_size × cases × epochs` 放大成本     | 先用最小参数（`group_size=1`、`cases_per_variant=1`、`num_epochs=1`）跑通，再加大 |
| 分数不提升但任务完成                  | 门控拒绝候选                                 | 查 `gate_results` 与评估器是否匹配任务                                       |




### 7.2 常见问答



#### Q：`agent_name`、`optimizer_template.name`、`scenario` 有什么区别？

**结论：**`agent_name` **是 Adapter 业务 Agent；**`name` **是模板名称；**`scenario` **是算法名称。**

选择算法看 `scenario`（为空才回退 `name`）。正式算法名称仅 `skillopt` 与 `tf_grpo`。例如 `agent_name=demo_agent`、`name=金融客服SkillOpt`、`scenario=skillopt` 可以同时成立。把中文业务标签写进 `scenario` 会导致算法配置加载失败。

#### Q：为什么任务 `completed` 但分数没涨？

**结论：多半是 val gate 拒绝了候选，不是提交失败。**

查 `result.gate_results`、评估器是否匹配任务，以及 train/val 切分是否合理。