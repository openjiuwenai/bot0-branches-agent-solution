# 优化引擎 - Prompt 优化器使用指南

> 本指南介绍如何使用 **Prompt 优化器**，根据真实业务用例自动改进 Agent 的规则文档，并将验证通过的新版本应用到 Agent。  

---
  
## 1. 特性概览

### 1.1 这是什么

> **Prompt 优化器**通过“运行用例、评价回答、分析问题、改进文档、验证效果”的方式，自动优化 `AgentRule.md` 等 Agent 规则文档。

一次优化任务会依次完成：

1. 读取 Agent 当前生效的规则文档；
2. 使用训练用例运行 Agent；
3. 根据回答中的问题生成改进版本；
4. 使用验证用例比较新旧版本；
5. 只保留验证效果更好的版本；
6. 返回优化前后内容、分数和每轮结果。

当前版本一次只优化一个Prompt文档。文档类型由 `managed_doc_kind` 指定，例如
`agent_rule`。

### 1.2 与相近特性的区别

| 特性 | 优化对象 | 什么时候使用 |
|---|---|---|
| Prompt 优化 | `AgentRule.md` 等单个 Agent 规则文档 | 需要改进 Agent 的全局行为、路由、交付或边界规则 |
| Skill 优化 | 一个或多个 Skill 文档 | 需要改进某项具体业务能力 |
| 评估器 Prompt | 评分标准 | 需要说明“什么样的回答才算好” |

> `optimizer_type: "prompt"` 表示优化 Agent 规则文档。  
> `evaluator_template.prompt` 表示如何评价回答。两者不是同一个 Prompt。

### 1.3 本指南覆盖范围

本指南包含：

- 开始前需要准备的信息；
- 一次完整 Prompt 优化的操作步骤；
- 常用请求参数和返回结果；
- 重试、取消和常见问题处理。

本指南不包含：

- EvoAgent、Adapter 和 LLM 服务的部署过程；
- Skill 优化和 Tool 优化的使用方法；
- Agent 规则文档的注册开发过程。

### 1.4 可选算法：GEPA

除了默认的 `skillopt` 算法外，Prompt 优化器还支持 **GEPA**（Genetic-Pareto，arXiv:2507.19457）算法。GEPA 是一种反思式 Prompt 进化算法，核心思想是通过 LLM 分析完整执行轨迹来提出针对性改进，而非随机变异。

GEPA 的主要特点：

| 特点 | 说明 |
|---|---|
| 逐实例 Pareto 前沿 | 对每个验证用例独立追踪最佳候选；在任意单个用例上唯一最佳的候选会留在前沿，避免平均化抹平专门性改进 |
| LLM 反思变异 | 变异不是随机的，而是 LLM 基于完整执行轨迹（输入、模型输出、期望结果、反馈）提出针对性文本改进 |
| 交叉合并 | 周期性地将各有所长的候选通过 LLM 交叉合并为一条更优指令 |
| 严格改进接受 | 子代必须在训练 minibatch 上严格优于父代才会被接受 |
| 多模态支持 | 支持视觉（图像）模型的 Prompt 优化，每条用例可携带图片 |

使用 GEPA 时，将 `optimizer_template.name` 填写为 `/scenarios` 返回的 GEPA 场景名称（例如 `gepa`），`optimizer_template.scenario` 仅作为业务标签。GEPA 的超参数与默认算法不同，详见 5.3 节。

> GEPA 适用于需要更精细优化、用例间差异较大、或涉及多模态（图像）输入的场景。对于简单的文本规则优化，默认 `skillopt` 算法通常已足够。

---

## 2. 什么时候使用

| 使用 Prompt 优化 | 不使用 Prompt 优化 |
|---|---|
| 已积累一批表现不符合预期的业务用例 | 还没有稳定的测试用例和判断标准 |
| 需要改进 Agent 的全局行为规则 | 只需要修改某个 Skill |
| Agent 规则文档已接入 Adapter 托管 | 文档仍由人工直接发布，未接入托管 |
| 可以在测试环境运行 Agent 并应用候选版本 | 当前环境不允许测试流量或 Agent 重启 |

> 简单判断：如果“要改的是 Agent 的规则文档，并且有用例可以验证改得好不好”，就适合使用 Prompt 优化。

### 2.1 何时选择 GEPA 算法

在以下情况下，建议将 `optimizer_template.name` 切换为 GEPA 场景名称：

| 选择 GEPA | 不选择 GEPA（使用默认 skillopt） |
|---|---|
| 用例间差异大，不同用例需要不同的 Prompt 策略 | 用例模式相对一致，统一规则即可覆盖 |
| 需要优化多模态（图像）模型的 Prompt | 纯文本对话场景 |
| 希望通过 LLM 反思分析失败原因来改进 | 只需简单尝试修改并验证 |
| 验证集较大，能体现逐实例优势 | 验证集较小（< 10 条） |
| 已有结构化输出（如 JSON 分类）的评估指标 | 评估标准偏语义、难以精确量化 |

> GEPA 每轮迭代会额外调用一次 LLM 进行反思，因此单轮耗时和 token 消耗高于默认算法。在验证集较小或评估标准偏语义时，默认算法通常已足够。

---

## 3. 准备工作

### 3.1 获取必要信息

开始前，请向平台或部署维护人员确认以下信息：

| 信息 | 示例 | 用途 |
|---|---|---|
| EvoAgent 地址 | `http://evoagent.example.com` | 提交和查询优化任务 |
| Adapter 地址 | `http://adapter.example.com` | 读取当前规则文档 |
| Agent 名称 | `demo_agent` | 指定被优化的 Agent |
| 优化模板名称 | `prompt-optimizer` | 标识本次使用的优化模板 |
| 算法名称 | `skillopt` | 选择优化算法；必须以 `/scenarios` 的实际返回为准 |
| 评估场景标识 | `risk-query` | 标识评价所针对的业务场景 |
| 文档类型 | `agent_rule` | 指定被优化的规则文档 |
| 数据集路径 | `/data/evo_agent/prompt-cases.json` | EvoAgent 服务端读取测试用例 |

后续示例中的 `{...}` 都需要替换为实际值。

### 3.2 检查服务

检查 EvoAgent：

```bash
curl "{EVOAGENT_BASE_URL}/health"
```

检查 Prompt 优化能力：

```bash
curl "{EVOAGENT_BASE_URL}/capabilities"
```

返回结果中，以下字段应为 `true`：

```json
{
  "managed_doc_optimization": true,
  "managed_doc_epoch_contents": true,
  "managed_doc_cooperative_cancellation": true,
  "managed_doc_baseline_rollback": true,
  "optimization_submit_idempotency": true
}
```

检查可用的优化算法或场景：

```bash
curl "{EVOAGENT_BASE_URL}/scenarios"
```

确认返回结果中存在要使用的算法名称。例如，接口返回 `skillopt` 时，可以将
`optimizer_template.name` 填写为 `skillopt`。不同部署环境支持的名称可能不同，
不得使用 Agent 名称代替算法名称。

> 切换优化算法时修改 `optimizer_template.name`，不要修改
> `optimizer_type`。Prompt 优化的 `optimizer_type` 固定为 `prompt`。

检查 Adapter：

```bash
curl "{ADAPTER_BASE_URL}/health"
```

> 如果任一检查失败，请先联系部署维护人员，不要继续提交优化任务。

### 3.3 准备测试数据

数据集使用 JSON 数组。每条用例至少包含：

| 字段 | 说明 |
|---|---|
| `id` | 用例唯一编号，不能重复 |
| `inputs` | 用户向 Agent 提出的问题 |
| `expected_behavior` | Agent 应该如何回答或处理 |

示例：

```json
[
  {
    "id": "prompt-001",
    "inputs": [
      "请查询客户风险并给出完整结论。"
    ],
    "expected_behavior": "应调用正确能力并完整交付真实结果，不得只回复任务已完成。",
    "extra_data": {}
  },
  {
    "id": "prompt-002",
    "inputs": [
      "帮我查一下客户风险。",
      "确认"
    ],
    "expected_behavior": "缺少客户标识时应先向用户澄清，不得猜测参数。",
    "extra_data": {}
  }
]
```

建议：

- 同时准备正常用例和问题用例；
- `expected_behavior` 要具体、可判断；
- 用例应覆盖最希望改进的行为；
- 正式优化建议准备至少 20 条有代表性的用例。

数据文件需要提前放到 EvoAgent 服务端允许访问的目录中。提交请求时填写的是
**服务端路径**，不是客户电脑上的文件路径。

### 3.4 检查当前文档

新建文件 `managed-doc-query.json`：

```json
{
  "action": "content",
  "agent_name": "{AGENT_NAME}",
  "doc_kind": "{DOC_KIND}"
}
```

读取当前文档：

```bash
curl --request POST "{ADAPTER_BASE_URL}/api/v1/managed-docs" --header "Content-Type: application/json" --data-binary "@managed-doc-query.json"
```

返回示例：

```json
{
  "doc_kind": "agent_rule",
  "content": "# Agent Rules\n...",
  "apply_mode": "restart",
  "pending_apply": false,
  "file_revision": "a1b2c3...",
  "applied_revision": "a1b2c3...",
  "max_task_seconds": 300
}
```

提交优化任务前，必须确认：

- `apply_mode == "restart"`；
- `pending_apply == false`；
- `file_revision == applied_revision`；
- 请求中的 `managed_doc_expected_revision` 使用本次查询得到的
  `applied_revision`。

任一条件不满足时，不要提交优化任务。请先等待或修复当前文档的应用状态，再重新读取
最新 revision。

### 3.5 GEPA 专用准备

使用 GEPA 算法时，除 3.1–3.4 的通用准备外，还需完成以下额外准备。

#### 视觉模型配置

GEPA 支持多模态（图像）输入的 Prompt 优化。被优化的目标模型通过以下环境变量配置：

| 环境变量 | 说明 | 回退值 |
|---|---|---|
| `EVO_VISION_MODEL` | 视觉标注模型名称（被优化的目标模型） | 回退到 `EVO_TARGET_MODEL` |
| `EVO_VISION_API_KEY` | 视觉模型 API Key | 回退到 `EVO_LLM_API_KEY` |
| `EVO_VISION_BASE_URL` | 视觉模型 API 端点 | 回退到 `EVO_LLM_BASE_URL` |

> 如果视觉模型与 LLM 使用同一 provider，只需配置 `EVO_LLM_*` 系列变量，`EVO_VISION_*` 留空即可自动回退。

`EVO_OPTIMIZER_MODEL` 指定反思阶段使用的 LLM 模型（用于分析执行轨迹并生成改进建议），与视觉模型独立配置。

#### JSONL 数据集格式

GEPA 场景使用 JSONL 格式（每行一个 JSON 对象），与 3.3 节的 JSON 数组格式不同。每条用例包含以下字段：

| 字段 | 说明 |
|---|---|
| `case_id` | 用例唯一编号，不能重复 |
| `inputs.query` | 文本输入 |
| `inputs.images` | 图片路径列表（可选，多模态场景使用） |
| `label.expected_result` | 期望输出（用于评估器比对） |

示例：

```json
{"case_id": "case-0001", "inputs": {"query": "请分析图片并输出JSON", "images": ["/data/images/road_001.jpg"]}, "label": {"expected_result": "{\"is_litter\": true, \"category\": \"轮胎或轮胎残片\"}"}}
{"case_id": "case-0002", "inputs": {"query": "请分析图片并输出JSON", "images": ["/data/images/road_002.jpg"]}, "label": {"expected_result": "{\"is_litter\": false, \"category\": \"非抛撒物\"}"}}
```

需要分别准备训练集（`train.jsonl`）和验证集（`val.jsonl`）。数据切分也可由服务端按 `train_split`/`val_split` 自动完成。

> 图片路径必须是 EvoAgent 服务端可访问的路径。单张图片不超过 10MB，每条用例最多 5 张图片。

#### 评估器

GEPA 的评估器需要实现 `batch_evaluate(cases, predicts)` 接口，返回 `EvaluatedCase` 列表（含 `score` 字段）。对于结构化输出场景（如 JSON 分类），可使用精确匹配或部分匹配评分。

示例评分逻辑（JSON 分类）：

```text
is_litter 不匹配 → 0.0 分
is_litter 匹配 + category 匹配 → 1.0 分
is_litter 匹配 + category 不匹配 → 0.5 分
```

---

## 4. 快速上手

本节只需要准备一个请求文件，并执行两类命令：提交任务、查询结果。

### 4.1 准备请求

新建文件 `prompt-optimize-request.json`：

```json
{
  "task_name": "prompt-optimize-001",
  "agent_name": "{AGENT_NAME}",
  "optimizer_type": "prompt",
  "optimizer_template": {
    "name": "{ALGORITHM_NAME}",
    "scenario": "{BUSINESS_SCENARIO}",
    "hyperparams": {
      "num_epochs": 3
    },
    "train_split": 0.8,
    "val_split": 0.2
  },
  "evaluator_template": {
    "name": "prompt-evaluator",
    "scenario": "{EVALUATOR_SCENARIO}",
    "type": "llm",
    "prompt": "请根据 expected_behavior 评价回答是否完成用户目标、是否遵守业务边界，并给出 0 到 1 的分数。"
  },
  "skills": [],
  "dataset_path": "{DATASET_PATH}",
  "managed_doc_kind": "{DOC_KIND}",
  "client_task_id": "prompt-{AGENT_NAME}-001",
  "managed_doc_expected_revision": "{APPLIED_REVISION}"
}
```

需要替换：

| 占位内容 | 填写方式 |
|---|---|
| `{AGENT_NAME}` | 填写 Adapter 侧目标业务 Agent 名称 |
| `{ALGORITHM_NAME}` | 填写 `/scenarios` 返回的算法名称，例如接口实际返回的 `skillopt`；填入 `optimizer_template.name` |
| `{BUSINESS_SCENARIO}` | 业务场景标签（仅元数据），可填写业务场景标识 |
| `{EVALUATOR_SCENARIO}` | 填写评估所针对的业务场景标识，不要与算法名称混为一谈 |
| `{DATASET_PATH}` | 填写 EvoAgent 服务端的数据集路径 |
| `{DOC_KIND}` | 一般填写 `agent_rule` |
| `{APPLIED_REVISION}` | 填写 3.4 节读取到的 `applied_revision` |

`agent_name`、`optimizer_template.name` 和 `optimizer_template.scenario` 含义不同：

- `agent_name` 是 Adapter 侧业务 Agent 名称；
- `optimizer_template.name` 是实际选择优化算法的字段（映射到内部 scenario），填写 `/scenarios` 返回的算法名称；
- `optimizer_template.scenario` 是业务场景标签（仅元数据），不影响算法选择。

`client_task_id` 是本次请求的唯一编号。每次修改请求重新提交时，都应换一个新编号。

### 4.2 提交优化任务

```bash
curl --request POST "{EVOAGENT_BASE_URL}/optimize" --header "Content-Type: application/json" --data-binary "@prompt-optimize-request.json"
```

成功时返回：

```json
{
  "job_id": "a1b2c3d4e5f6",
  "status": "queued",
  "progress": null,
  "result": null,
  "error": null,
  "error_code": null,
  "cancellation_requested": false
}
```

请复制并保存 `job_id`。

### 4.3 查询任务

```bash
curl "{EVOAGENT_BASE_URL}/optimize/{JOB_ID}"
```

任务运行时会返回：

```json
{
  "job_id": "a1b2c3d4e5f6",
  "status": "running",
  "progress": {
    "current_epoch": 2,
    "total_epochs": 3,
    "val_score": 0.78,
    "best_score": 0.81,
    "edits_applied": 4
  }
}
```

每隔几秒重新执行一次查询命令，直到状态变为：

- `completed`：优化完成；
- `failed`：优化失败；
- `cancelled`：任务已取消。

### 4.4 验证是否成功

任务完成后，重点查看以下内容：

| 字段 | 检查内容 |
|---|---|
| `status` | 应为 `completed` |
| `result.epochs_completed` | 是否完成预期轮数 |
| `result.gate_results` | 每轮使用了新版本还是保留旧版本 |
| `result.train`、`result.val` | 优化前后的分数和通过率 |
| `result.managed_doc_content_before` | 优化前文档 |
| `result.managed_doc_content_after` | 最终文档 |
| `result.managed_doc_epoch_contents` | 每轮候选内容 |
| `result.managed_doc_task_ids` | Adapter 应用任务编号 |

再次执行 3.4 节的文档查询命令，并确认：

```text
pending_apply = false
file_revision = applied_revision
```

最后使用一条独立业务用例调用 Agent，确认新规则已经生效。

> `completed` 表示优化流程完成。是否真正达到业务效果，仍需结合最终分数、文档内容和业务验证一起判断。

---

## 5. 接口与配置

### 5.1 接口清单

| 方法 | 路径 | 作用 |
|:--:|---|---|
| `GET` | `/capabilities` | 检查 Prompt 优化能力 |
| `GET` | `/scenarios` | 查询可用场景 |
| `POST` | `/optimize` | 提交优化任务 |
| `GET` | `/optimize/{job_id}` | 查询任务进度和结果 |
| `GET` | `/optimize/{job_id}/stream` | 实时查看进度 |
| `POST` | `/optimize/{job_id}/cancel` | 取消任务 |
| `GET` | `/optimize/submissions/{client_task_id}` | 按客户任务编号查询提交记录 |

### 5.2 核心请求参数

| 字段 | 必填 | 说明 |
|---|:--:|---|
| `task_name` | 是 | 便于识别的任务名称 |
| `agent_name` | 是 | Adapter 侧被优化的业务 Agent |
| `optimizer_type` | 是 | Prompt 优化固定填写 `prompt` |
| `optimizer_template` | 是 | 优化模板、算法和轮数 |
| `optimizer_template.name` | 是 | 算法名称（映射到内部 scenario，决定优化算法选择） |
| `optimizer_template.scenario` | 否 | 业务场景标签（仅元数据），不影响算法选择 |
| `evaluator_template` | 是 | 评价方式和评价标准 |
| `dataset_path` | 是 | EvoAgent 服务端的数据集路径 |
| `managed_doc_kind` | 是 | 文档类型，例如 `agent_rule` |
| `client_task_id` | 是 | 本次请求的唯一编号 |
| `managed_doc_expected_revision` | 是 | 提交前读取到的 `applied_revision` |
| `skills` | 否 | Prompt 优化应省略或传空数组 |

> 不能同时提供非空 `skills` 与 `managed_doc_kind`。Prompt 模式应省略 `skills`
> 或传空数组。

### 5.3 常用优化参数

| 字段 | 默认值 | 说明 |
|---|---|---|
| `hyperparams.num_epochs` | 服务端默认值 | 优化轮数，建议首次使用 `3` |
| `hyperparams.batch_size` | 服务端默认值 | 每批用例数，通常无需填写 |
| `train_split` | `0.8` | 用于分析和生成改进的用例比例 |
| `val_split` | `0.2` | 用于验证新版本的用例比例 |
| `evaluator_template.type` | `metric` | 确定性结果使用 `metric`；语义或执行过程评价使用 `llm` |
| `evaluator_template.prompt` | 空 | `type=llm` 时填写清晰的评价标准 |

`train_split` 与 `val_split` 之和必须等于 `1.0`，并且都大于 `0`。

评估器类型可按以下标准选择：

- `metric`：结果能通过精确匹配、包含指定内容、正则表达式或数值误差等规则判断；
- `llm`：需要判断语义完整性、工具调用是否正确、多步任务是否完成、是否遵守业务边界，
  或是否在信息不足时主动澄清。

首次使用建议只调整 `num_epochs`，其他参数保持默认。

#### 5.3.1 GEPA 专用超参数

使用 GEPA 算法时，以下超参数通过 `optimizer_template.hyperparams` 传递：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `minibatch_size` | `5` | 每轮迭代从训练集采样的用例数 |
| `num_epochs` | `20` | 作为 `hyperparams` 顶层字段传递，范围 [1, 100]；GEPA 适配器将其映射为 `max_iterations`（最大迭代次数） |
| `perfect_score` | `1.0` | 达到此分数时提前停止优化 |
| `candidate_selection_strategy` | `pareto` | 父代选择策略：`pareto`（频率加权采样）或 `current_best`（始终选最佳） |
| `acceptance_criterion` | `strict_improvement` | 接受准则：`strict_improvement`（子代必须严格优于父代）或 `improvement_or_equal`（允许相等） |
| `enable_merge` | `true` | 是否启用交叉合并 |
| `merge_frequency` | `3` | 每 N 个新候选触发一次 merge |
| `seed` | `0` | 随机种子，控制 minibatch 采样和父代选择的可复现性 |
| `component_name` | `system_prompt` | 被优化的 Prompt 组件名称 |
| `num_parallel` | `4` | 视觉模型并发调用数 |
| `batch_size` | `8` | 基础设施层每批用例数（当 `minibatch_size` 未设置时作为回退） |

GEPA 每轮迭代流程：

1. 从 Pareto 前沿选择父代（频率加权采样）；
2. 从训练集采样 minibatch；
3. 在 minibatch 上评估父代并捕获执行轨迹；
4. 构建反思数据集（输入、输出、期望、反馈、分数）；
5. LLM 反思生成改进 Prompt；
6. 在相同 minibatch 上评估子代；
7. 子代严格优于父代则接受，否则拒绝；
8. 接受后在全量验证集上评估并更新 Pareto 前沿；
9. 周期性交叉合并互补候选。

> `num_epochs` 字段含义对所有算法相同（均为 `hyperparams` 顶层字段，范围 [1, 100]），但 GEPA 适配器将其映射为最大迭代次数。默认算法中每 epoch 遍历全部训练集，GEPA 中每 epoch 仅采样一个 minibatch。建议 GEPA 设置为 10–20，达到 `perfect_score` 时会提前停止。

#### 5.3.2 GEPA Prompt 模板

GEPA 使用两个 LLM Prompt 模板，支持场景级覆盖：

| 模板 | 文件名 | 用途 |
|---|---|---|
| 指令提案 | `instruction_proposal.md` | LLM 阅读当前 Prompt 和执行轨迹后，提出改进后的新指令 |
| 交叉合并 | `merge.md` | LLM 将两条各有所长的指令合并为一条更优指令 |

模板查找顺序：

1. `<scenarios_dir>/<scenario_name>/prompts/<name>.md`（场景级覆盖）
2. `evo_agent/optimizer/gepa/templates/<name>.md`（内置默认）

> 如需自定义反思或合并策略，可在场景目录下放置同名 `.md` 文件覆盖默认模板。模板中使用 `{key}` 占位符进行参数替换。

### 5.4 结果说明

| 字段 | 说明 |
|---|---|
| `train` | 训练集优化前后分数、通过率和用例数 |
| `val` | 验证集优化前后分数、最佳分数和逐轮分数 |
| `gate_results` | 每轮门控结果：`candidate` 或 `base` |
| `edits_applied` | 最终通过门控并生效的编辑数 |
| `managed_doc_content_before` | 优化前文档全文 |
| `managed_doc_content_after` | 最终文档全文 |
| `managed_doc_epoch_contents` | 每轮候选文档 |
| `managed_doc_task_ids` | Adapter 应用任务编号 |

门控结果含义：

- `candidate`：本轮新版本通过验证，继续使用；
- `base`：本轮新版本未通过验证，保留上一版本。

出现 `base` 是正常的保护机制，不代表任务失败。

GEPA 算法额外返回以下字段：

| 字段 | 说明 |
|---|---|
| `best_score` | Pareto 前沿最佳候选的平均验证分数 |
| `n_iterations` | 实际运行的迭代次数（可能因 `perfect_score` 提前停止） |
| `n_candidates` | 被接受的候选总数（含种子和合并候选） |
| `pareto_frontier` | Pareto 前沿状态快照（含每个用例的最佳分数和候选索引） |

### 5.5 状态与常见错误

| 状态或错误 | 含义 | 处理方式 |
|---|---|---|
| `queued` | 已接收，等待运行 | 稍后查询 |
| `running` | 正在优化 | 继续查询 |
| `completed` | 优化完成 | 按 4.4 节验收 |
| `failed` | 优化或应用失败 | 查看 `error` 和 `error_code` |
| `cancelled` | 任务已取消并完成恢复 | 确认当前文档状态 |
| HTTP `422` | 请求参数错误 | 检查必填字段、数据集路径和切分比例 |
| HTTP `409` | 同一 `client_task_id` 对应了不同请求 | 新请求使用新的编号 |
| `MANAGED_DOC_BASELINE_CHANGED` | 当前文档已不是提交时的版本 | 重新读取 revision 后再提交 |
| `CANCEL_ROLLBACK_TIMEOUT` | 取消后恢复超时 | 联系维护人员检查 Adapter |
| `CANCEL_ROLLBACK_FAILED` | 取消后恢复失败 | 联系维护人员人工确认或恢复文档 |

---

## 6. 场景用例

### 6.1 基础用例：优化 AgentRule

适用于：改进 Agent 的全局路由、结果交付、业务边界或失败处理规则。

请求中的关键字段：

```json
{
  "optimizer_type": "prompt",
  "skills": [],
  "managed_doc_kind": "agent_rule",
  "client_task_id": "prompt-edp-agent-001",
  "managed_doc_expected_revision": "{APPLIED_REVISION}"
}
```

完成后重点比较：

- `managed_doc_content_before` 与 `managed_doc_content_after`；
- `train` 和 `val` 的优化前后分数；
- `gate_results` 中每轮的选择结果；
- 独立业务用例的实际回答。

### 6.2 提交超时后的安全重试

如果提交命令超时，不确定任务是否已创建，可以查询：

```bash
curl "{EVOAGENT_BASE_URL}/optimize/submissions/{CLIENT_TASK_ID}"
```

处理原则：

- 能查到记录：使用返回的 `job_id` 继续查询；
- 查不到记录：原样重新提交；
- 修改了任何请求参数：使用新的 `client_task_id`。

### 6.3 实时查看进度

普通使用只需要按 `job_id` 查询。需要实时日志时，可以使用：

```bash
curl --no-buffer "{EVOAGENT_BASE_URL}/optimize/{JOB_ID}/stream"
```

该接口会持续返回运行阶段、轮次和最终状态。

### 6.4 取消任务

```bash
curl --request POST "{EVOAGENT_BASE_URL}/optimize/{JOB_ID}/cancel"
```

接口返回 `202` 表示已收到取消请求，不代表已经取消完成。请继续查询任务，直到：

- `cancelled`：取消和文档恢复完成；
- `failed`：查看是否存在恢复失败错误码，并联系维护人员处理。

### 6.5 GEPA 用例：多模态视觉 Prompt 优化

适用于：优化视觉模型的 System Prompt，如交通遗撒物图片分类、遥感图像分析、医疗影像判断等需要图像输入的场景。

#### 准备数据

将训练集和验证集分别保存为 JSONL 文件，放到 EvoAgent 服务端可访问的目录：

```jsonl
{"case_id": "case-0001", "inputs": {"query": "请分析图片并输出JSON", "images": ["/data/images/road_001.jpg"]}, "label": {"expected_result": "{\"is_litter\": true, \"category\": \"轮胎或轮胎残片\"}"}}
{"case_id": "case-0002", "inputs": {"query": "请分析图片并输出JSON", "images": ["/data/images/road_002.jpg"]}, "label": {"expected_result": "{\"is_litter\": false, \"category\": \"非抛撒物\"}"}}
```

#### 提交请求

```json
{
  "task_name": "gepa-vision-optimize-001",
  "agent_name": "{AGENT_NAME}",
  "optimizer_type": "prompt",
  "optimizer_template": {
    "name": "gepa",
    "scenario": "{BUSINESS_SCENARIO}",
    "hyperparams": {
      "minibatch_size": 5,
      "num_epochs": 20,
      "perfect_score": 1.0,
      "candidate_selection_strategy": "pareto",
      "acceptance_criterion": "strict_improvement",
      "enable_merge": true,
      "merge_frequency": 3,
      "seed": 42
    },
    "train_split": 0.8,
    "val_split": 0.2
  },
  "evaluator_template": {
    "name": "json-classifier",
    "scenario": "{EVALUATOR_SCENARIO}",
    "type": "metric",
    "metric": "exact_match"
  },
  "skills": [],
  "dataset_path": "{DATASET_PATH}",
  "managed_doc_kind": "{DOC_KIND}",
  "client_task_id": "gepa-vision-001",
  "managed_doc_expected_revision": "{APPLIED_REVISION}"
}
```

与基础用例的关键区别：

| 字段 | 基础用例 | GEPA 用例 |
|---|---|---|
| `optimizer_template.name` | `skillopt` | `gepa` |
| `hyperparams` | 仅 `num_epochs` | 含 `minibatch_size`、`perfect_score`、`enable_merge` 等 |
| `evaluator_template.type` | `llm` | `metric`（结构化输出可精确匹配） |
| `evaluator_template.metric` | 不适用 | `exact_match`（metric 类型需指定指标） |
| 数据集格式 | JSON 数组 | JSONL（每行一条，含 `images` 字段） |

#### 验收结果

完成后重点查看：

- `best_score`：Pareto 前沿最佳候选的平均验证分数；
- `n_iterations`：实际迭代次数（是否因 `perfect_score` 提前停止）；
- `n_candidates`：被接受的候选数量（体现优化过程的多样性）；
- `managed_doc_content_before` 与 `managed_doc_content_after`：优化前后的 Prompt 差异；
- 使用一条独立的图片用例调用 Agent，确认新 Prompt 在实际场景中的效果。

#### 独立运行（可选）

如需脱离 EvoAgent 服务直接运行 GEPA 优化（用于本地实验或调试），可使用示例脚本：

```bash
cd evoagent
python examples/scenarios/gepa/run_gepa.py
```

该脚本会从 `examples/scenarios/gepa/gepa_dataset/` 加载 JSONL 数据集，使用 `.env` 中的模型配置运行完整 GEPA 优化，结果保存到 `workspace/gepa_output/`。

> 独立运行模式不走 Adapter 托管流程，优化结果仅保存到本地文件，不会自动应用到 Agent。适用于实验和调试。

---

## 7. 常见问题

### 7.1 故障排查表

| 现象 | 常见原因 | 处理方式 |
|---|---|---|
| 提交后立即返回 `422` | 缺少 Prompt 必填字段，或 `skills` 不为空 | 对照 5.2 节检查请求 |
| 提交后提示数据集不存在 | 填写了客户电脑上的路径 | 改为 EvoAgent 服务端路径 |
| 任务开始前失败 | 当前文档未完全生效 | 检查 `pending_apply` 和两个 revision |
| 返回基线已变化 | 读取文档后又发生了其他更新 | 重新读取文档并使用新任务编号 |
| 某轮结果为 `base` | 新版本没有优于上一版本 | 正常门控行为，可检查用例和评价标准 |
| 任务完成但业务表现未变化 | Agent 未加载新版本，或验证用例不匹配 | 检查 Adapter revision，并运行独立业务用例 |
| 取消后长时间未结束 | Adapter 正在应用或恢复文档 | 继续查询；出现回滚错误时联系维护人员 |
| GEPA 任务图片加载失败 | 图片路径不可访问或文件过大 | 确认路径为服务端路径、单图不超过 10MB |
| GEPA 反思阶段 LLM 超时 | `EVO_OPTIMIZER_MODEL` 未配置或网络异常 | 检查 `.env` 中模型配置和网络连通性 |
| GEPA 优化后分数未提升 | 验证集过小或 minibatch 采样不具代表性 | 增加验证集规模、调大 `minibatch_size` |

### 7.2 常见问答

#### Q：为什么 `skills` 必须为空？

**结论：当前一次任务只能选择一种优化目标。**

Prompt 优化使用 `managed_doc_kind` 指定文档，不能同时优化 Skill。

#### Q：同一个请求可以重新提交吗？

**结论：可以。**

请求内容完全相同时，使用同一个 `client_task_id` 可以安全重试。修改请求后必须使用新的
`client_task_id`。

#### Q：任务为 `completed`，是否代表每轮修改都被采用？

**结论：不代表。**

查看 `gate_results`。`candidate` 表示采用新版本，`base` 表示保留上一版本。

#### Q：怎样判断优化真正成功？

**结论：同时看流程状态、分数、文档和业务效果。**

至少确认：

1. 任务状态为 `completed`；
2. Adapter 中两个 revision 相同，且没有待应用任务；
3. 最终文档内容符合预期；
4. 独立业务用例的回答得到改善。

#### Q：取消接口返回后可以立即结束操作吗？

**结论：不可以。**

取消是一个需要恢复文档的过程。必须继续查询，直到任务进入 `cancelled` 或明确的
`failed` 状态。

#### Q：GEPA 和默认 skillopt 算法有什么区别？

**结论：GEPA 使用逐实例 Pareto 前沿 + LLM 反思变异，skillopt 使用批量评估 + 规则改写。**

GEPA 每轮迭代会：采样 minibatch → 评估父代并捕获轨迹 → LLM 反思分析失败原因 → 生成改进 Prompt → 严格改进才接受 → 全量验证集评估 → 更新 Pareto 前沿。skillopt 则更接近"批量运行 → 分析共性问题 → 改写文档 → 验证"的流程。GEPA 更精细但单轮耗时更高。

#### Q：GEPA 的 `num_epochs` 和默认算法的 `num_epochs` 含义一样吗？

**结论：字段含义相同，但 GEPA 适配器将其映射为最大迭代次数。**

`num_epochs` 作为 `optimizer_template.hyperparams` 的顶层字段传递，范围 [1, 100]，对所有 `optimizer_type` 都一样。GEPA 适配器将其映射为 `max_iterations`，而默认 skillopt 算法中 `num_epochs` 表示完整训练轮次。默认算法中每 epoch 遍历全部训练集，GEPA 中每 epoch 仅采样一个 minibatch。建议 GEPA 设置为 10–20，达到 `perfect_score` 时会提前停止。

#### Q：GEPA 优化后 `n_candidates` 很少，正常吗？

**结论：正常。**

GEPA 使用严格改进接受准则（`strict_improvement`），子代必须在 minibatch 上严格优于父代才会被接受。如果 Prompt 已经接近最优，或者 minibatch 采样不具代表性，被接受的候选可能很少。可以尝试调大 `minibatch_size`、增加 `num_epochs`，或将 `acceptance_criterion` 改为 `improvement_or_equal`。

#### Q：GEPA 支持纯文本（无图片）场景吗？

**结论：支持。**

GEPA 的数据集中 `inputs.images` 字段为空数组即可。视觉模型在无图片时会退化为纯文本调用。但 GEPA 的核心优势在于多模态场景和逐实例优化，纯文本场景使用默认 skillopt 算法通常更高效。

#### Q：如何自定义 GEPA 的反思和合并 Prompt 模板？

**结论：在场景目录下放置同名 `.md` 文件覆盖默认模板。**

在 `<scenarios_dir>/<scenario_name>/prompts/` 目录下创建 `instruction_proposal.md` 或 `merge.md`，GEPA 会优先使用场景级模板。模板中使用 `{key}` 占位符（如 `{curr_param}`、`{side_info}`、`{prompt_a}`）进行参数替换。
