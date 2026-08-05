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

---

## 2. 什么时候使用

| 使用 Prompt 优化 | 不使用 Prompt 优化 |
|---|---|
| 已积累一批表现不符合预期的业务用例 | 还没有稳定的测试用例和判断标准 |
| 需要改进 Agent 的全局行为规则 | 只需要修改某个 Skill |
| Agent 规则文档已接入 Adapter 托管 | 文档仍由人工直接发布，未接入托管 |
| 可以在测试环境运行 Agent 并应用候选版本 | 当前环境不允许测试流量或 Agent 重启 |

> 简单判断：如果“要改的是 Agent 的规则文档，并且有用例可以验证改得好不好”，就适合使用 Prompt 优化。

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
`optimizer_template.scenario` 填写为 `skillopt`。不同部署环境支持的名称可能不同，
不得使用 Agent 名称代替算法名称。

> 切换优化算法时修改 `optimizer_template.scenario`，不要修改
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
    "name": "{OPTIMIZER_TEMPLATE_NAME}",
    "scenario": "{ALGORITHM_NAME}",
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
| `{OPTIMIZER_TEMPLATE_NAME}` | 填写优化模板名称，用于标识或展示模板 |
| `{ALGORITHM_NAME}` | 填写 `/scenarios` 返回的算法名称，例如接口实际返回的 `skillopt` |
| `{EVALUATOR_SCENARIO}` | 填写评估所针对的业务场景标识，不要与算法名称混为一谈 |
| `{DATASET_PATH}` | 填写 EvoAgent 服务端的数据集路径 |
| `{DOC_KIND}` | 一般填写 `agent_rule` |
| `{APPLIED_REVISION}` | 填写 3.4 节读取到的 `applied_revision` |

`agent_name`、`optimizer_template.name` 和 `optimizer_template.scenario` 含义不同：

- `agent_name` 是 Adapter 侧业务 Agent 名称；
- `optimizer_template.name` 是优化模板名称；
- `optimizer_template.scenario` 是实际选择优化算法的字段；为空时实现会回退使用
  `optimizer_template.name`，但建议始终显式填写 `/scenarios` 返回的算法名称。

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
| `optimizer_template.name` | 是 | 优化模板名称 |
| `optimizer_template.scenario` | 否 | 算法名称；为空时回退使用模板名称，建议显式填写 |
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
