# 使用指南：Skill 文档优化（SkillOpt / TF-GRPO）

本文说明如何用 EvoAgent 对业务 Agent 的 **Skill 文档（`SKILL.md`）** 做自动优化。当前内置两套算法，通过 **场景（scenario）** 切换，无需单独的 `algorithm` 字段。

| 读者目标 | 读完后你可以 |
| --- | --- |
| 跑通一次优化 | 用 API 或 CLI 提交任务，并拿到前后得分与产物 |
| 选对算法 | 按任务形态在 SkillOpt 与 TF-GRPO 之间做取舍 |
| 调参与联调 | 配置数据集、评估器、超参，并排查常见失败 |

> **文档类型**：How-to（面向「要完成一件事」的用户/集成方）。  
> **字段真源**：以代码与场景 YAML 为准（`src/evo_agent/api/routes/optimize.py`、`examples/scenarios/*/scenario.yaml`）。  
> **相关参考**：[optimization-api-reference.md](../api/optimization-api-reference.md)、[adapter-api-contract.md](../api/adapter-api-contract.md)、[TF-GRPO 开发串讲](../tf_grpo/TF-GRPO开发串讲文档.md)。

---

## 1. 特性概览

### 1.1 它做什么

EvoAgent 不训练模型权重，而是优化 Adapter 侧托管的 Skill 正文：

1. 从 Adapter **拉取**当前 skill 内容；
2. 在标注数据集上做 **真实 rollout**（经 Adapter 调用业务 Agent）；
3. 用所选算法提出改进（局部 patch 或整文档变体）；
4. 热更回 Adapter，用 **验证集门控（val gate）** 决定是否采纳；
5. 输出报告与 artifact 快照。

### 1.2 两种算法对照

| 维度 | SkillOpt（ReflACT） | TF-GRPO |
| --- | --- | --- |
| 场景目录 / 注册名 | `skillopt` | `tf_grpo` |
| 实现类 | `SkillOptOptimizer`（基于 `SkillDocumentOptimizer`） | `TfGrpoOptimizer` |
| 更新方式 | 反思 → 有界 **edit/patch** 逐步改写 | 每轮生成一组完整 **SKILL.md 变体**，组内选优 |
| 跨轮记忆 | slow_update / meta_skill（默认开） | **经验库（ExperienceLibrary）**（进程内，默认不跨 run 落盘） |
| 更适合 | 局部规则修补、多 skill 归因编辑 | 需要多候选相对比较、文档级重写 |
| 关键超参 | `edit_budget`、`accumulation`、`scheduler_mode` | `group_size`、`cases_per_variant`、`max_experiences`、`variant_temperature` 等 |

**算法选择机制**：`POST /optimize` 的 `optimizer_template.scenario`（若为空则回退 `name`）必须等于 `examples/scenarios/<目录名>/`。换算法 = 换场景名，**没有** `algorithm=skillopt|tf_grpo` 字段。`optimizer_type` 只表示优化目标类型（`skill` / `prompt` / `tool`），不是算法名。

推荐实践：把 `optimizer_template.name` 与 `scenario` **都写成**场景目录名（`skillopt` 或 `tf_grpo`），避免只写业务中文标签导致加载失败。

### 1.3 交付入口

| 入口 | 适用 | 命令 / 路径 |
| --- | --- | --- |
| **HTTP API（主路径）** | 平台集成、异步 Job | `POST /optimize` → 轮询或 SSE |
| **CLI（辅路径）** | 本地调试、可复现切分 | `skills/optimize_skill/scripts/run_optimize.py` |
| **程序化** | 嵌入脚本 | `run_optimization(OptimizeRequest, EvolveConfig)` |

---

## 2. 前置条件

开始前确认：

1. **EvoAgent 已安装并可启动**（`make install`，`make serve` 或等价 `uvicorn`）。
2. **Adapter sidecar 可达**，环境变量 `EVO_ADAPTER_URL` 已配置（未配置时 API 会拒绝提交）。
3. **LLM 凭证可用**：`EVO_LLM_API_KEY`（或 CustomSSE 全套变量），以及 `EVO_OPTIMIZER_MODEL` 等。
4. **业务 Agent 与 skill 名对齐**：请求里的 `agent_name`、`skills[]` 必须是 Adapter 上已存在的名称。
5. **数据集文件**落在 `EVO_ALLOWED_DATA_ROOTS` 白名单内（默认 `/data/evo_agent,/tmp/evo_agent`），且至少 **2** 条 case（train/val 各至少 1）。
6. （可选）先探活：

```bash
curl -s http://localhost:8001/health
curl -s http://localhost:8001/scenarios
```

`/scenarios` 应至少包含 `skillopt` 与 `tf_grpo`。（`edp_agent` 作为兼容别名保留）

最小环境变量示例见仓库根目录 [`.env.example`](../../.env.example)。

---

## 3. 准备数据集

API 模式使用 **JSON 数组**文件，例如 `/data/evo_agent/cases.json`：

```json
[
  {
    "id": "c001",
    "inputs": ["请根据描述给出处理建议"],
    "expected_behavior": "回答应包含责任归属字段",
    "extra_data": {}
  },
  {
    "id": "c002",
    "inputs": [{"role": "user", "content": "另一条用户问题"}],
    "expected_behavior": "...",
    "extra_data": {}
  }
]
```

要点：

- `inputs` 支持字符串数组，或带 `role`/`content` 的消息数组。
- `train_split` + `val_split` 必须等于 `1.0`，且均 `> 0`。
- **API 当前不传切分 seed** → 每次随机划分；需要可复现时用 CLI + `dataset.yaml` 的 `seed`。

CLI 可用 `dataset.yaml`（含 `cases`、`train_split`、`seed`、`evaluator`），详见 `skills/optimize_skill/`。

---

## 4. 选择评估器

评估器决定「好不好」的分数，从而影响 val gate 是否采纳候选 skill。

| `evaluator_template.type` | 打分输入 | 典型用途 |
| --- | --- | --- |
| `metric`（默认） | Adapter invoke 返回的 **answer**（可配 `extract`） | 结构化字段 exact_match |
| `llm` | cleaned-traces **轨迹**；`prompt` 建议含 `{messages}` | 开放域语义打分 |

Metric 示例（字段抽取 + exact_match）：

```json
{
  "name": "field_exact",
  "scenario": "audit",
  "type": "metric",
  "metric": "exact_match",
  "extract": {
    "strategy": "answer_tag_json_field",
    "source": "answer",
    "fields": ["responsibility"]
  },
  "aggregate": "mean"
}
```

LLM 示例：

```json
{
  "name": "semantic_judge",
  "scenario": "support",
  "type": "llm",
  "prompt": "根据期望行为评估助手轨迹。期望：{expected_behavior}\n轨迹：{messages}\n给出 0~1 分。"
}
```

---

## 5. 快速开始（API）

下列步骤以本机 `http://localhost:8001` 为例；端口以实际部署为准。

### 5.1 启动服务

```bash
cp .env.example .env
# 编辑 .env：至少配置 EVO_ADAPTER_URL、EVO_LLM_*、EVO_ALLOWED_DATA_ROOTS

make serve
# 或：uv run uvicorn evo_agent.api.app:app --host 0.0.0.0 --port 8001
```

### 5.2 提交 SkillOpt 任务（`skillopt`）

```bash
curl -s -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d @- <<'EOF'
{
  "task_name": "opt-skillopt-demo",
  "agent_name": "edp_agent",
  "optimizer_type": "skill",
  "skills": ["product_recommend_skill"],
  "dataset_path": "/data/evo_agent/finance_cases.json",
  "optimizer_template": {
    "name": "skillopt",
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

成功时返回 `job_id`（状态一般为 `queued`）。

### 5.3 提交 TF-GRPO 任务（`tf_grpo`）

```bash
curl -s -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d @- <<'EOF'
{
  "task_name": "opt-tfgrpo-demo",
  "agent_name": "<your_agent_name>",
  "optimizer_type": "skill",
  "skills": ["audit-business"],
  "dataset_path": "/data/evo_agent/cases.json",
  "optimizer_template": {
    "name": "tf_grpo",
    "scenario": "tf_grpo",
    "hyperparams": {
      "num_epochs": 1,
      "batch_size": 4,
      "group_size": 2,
      "cases_per_variant": 2
    },
    "train_split": 0.5,
    "val_split": 0.5,
    "rollout": { "extra_data": {} }
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
```

冒烟建议：`group_size=1`、`cases_per_variant=1`、`num_epochs=1`，先验证链路再加大。

### 5.4 查询进度与结果

```bash
# 轮询
curl -s http://localhost:8001/optimize/<job_id>

# SSE 实时事件（progress / log / completed / error）
curl -N http://localhost:8001/optimize/<job_id>/stream

# 取消长跑
curl -s -X POST http://localhost:8001/optimize/<job_id>/cancel
```

任务 `completed` 后，`result` 中常见字段包括：

- `skills`、`epochs_completed`、`edits_applied`（门控接受后的计数）
- `train` / `val`：`score_before`、`score_after` / `final_score`、`improvement`、`num_cases` 等
- `gate_results`、`skill_scores`、`skill_contents`

产物目录默认在 `EVO_ARTIFACT_DIR`（如 `./workspace/artifacts/{run_id}/`），含 epoch 快照与门控结果。

---

## 6. 使用 CLI（调试）

```bash
python skills/optimize_skill/scripts/run_optimize.py \
  --scenario skillopt \
  --dataset-manifest /path/to/dataset.yaml \
  --adapter-url http://localhost:9090 \
  --agent-name edp_agent \
  --skills product_recommend_skill \
  --epochs 2 \
  --batch-size 4
```

切换 TF-GRPO：

```bash
python skills/optimize_skill/scripts/run_optimize.py \
  --scenario tf_grpo \
  --dataset-manifest /path/to/dataset.yaml \
  --skills audit-business \
  --epochs 1 \
  --batch-size 4
```

未传 `--skills` 时，CLI 可回退读取对应 `scenario.yaml` 中 `optimize: true` 的 skill 列表。

---

## 7. 请求字段速查（Skill 模式）

### 7.1 顶层必填

| 字段 | 说明 |
| --- | --- |
| `task_name` | 任务显示名 |
| `agent_name` | Adapter 侧业务 Agent 名 |
| `optimizer_type` | Skill 优化固定为 `"skill"` |
| `skills` | 非空 skill 名列表；**禁止**同时传 `managed_doc_kind` |
| `dataset_path` | 已存在的数据集文件；须在白名单根下，≤ 500MB |
| `optimizer_template` | 场景与超参 |
| `evaluator_template` | 评估配置 |

### 7.2 `optimizer_template`

| 字段 | 说明 |
| --- | --- |
| `name` / `scenario` | 运行时场景键为 `scenario or name`；建议二者同为 `skillopt` 或 `tf_grpo` |
| `hyperparams` | 可含 `num_epochs`(1–100)、`batch_size`(1–64) 及算法专属键 |
| `train_split` / `val_split` | 默认 0.8 / 0.2，之和必须为 1.0 |
| `rollout.extra_data` | 与场景 YAML 的 rollout 配置合并后传给 invoke |

### 7.3 配置合并优先级

```
请求字段 / hyperparams  >  scenario.yaml hyperparams  >  EvolveConfig（EVO_* 环境变量）
```

### 7.4 常用环境变量

| 变量 | 作用 |
| --- | --- |
| `EVO_ADAPTER_URL` | Adapter 地址（API 必填） |
| `EVO_ALLOWED_DATA_ROOTS` | 数据集路径白名单 |
| `EVO_LLM_API_KEY` / `EVO_OPTIMIZER_MODEL` | 优化用 LLM |
| `EVO_DEFAULT_EPOCHS` / `EVO_DEFAULT_BATCH_SIZE` | 默认轮数与 batch |
| `EVO_EDIT_BUDGET` | SkillOpt 编辑预算 |
| `EVO_USE_SLOW_UPDATE` / `EVO_USE_META_SKILL` | SkillOpt 慢更新 / meta skill（TF-GRPO 场景默认强制关闭） |
| `EVO_PRESERVE_FRONTMATTER` | 默认 `true`：写回冻结 frontmatter；LLM 反思侧对 body 视图 strip |
| `EVO_ARTIFACT_DIR` | 产物目录 |

---

## 8. 场景与超参

场景定义位于 `examples/scenarios/<name>/`：

| 文件 | 作用 |
| --- | --- |
| `scenario.yaml` | `optimizer_class`、默认 skills、rollout、hyperparams |
| `optimizer.py` | 场景子类（可覆写 rollout / SSE 等） |
| `prompts/` | 可选 prompt 覆盖 |

### 8.1 SkillOpt（`skillopt`）默认可调项

来自场景与全局配置的常见键：`batch_size`、`num_parallel`、`edit_budget`、`accumulation`、`scheduler_mode`、`use_slow_update`、`use_meta_skill`。  
可用 `prompts/analyst_error.md`、`analyst_success.md` 覆盖分析 prompt。

### 8.2 TF-GRPO（`tf_grpo`）默认可调项

| 超参 | 场景默认（参考） | 含义 |
| --- | --- | --- |
| `group_size` | `3` | 每 epoch 变体个数 |
| `cases_per_variant` | `8` | 组内共用的 train 子集大小 |
| `variant_temperature` | `1.5` | 变体生成温度 |
| `semantic_advantage_temperature` | `0.95` | 语义优势提炼温度 |
| `max_experiences` | `20` | 经验库容量 |
| `validate_variant_completeness` | `false` | 变体启发式完整性校验 |
| `learn_without_score_variance` | `true` | 组内无分数方差时是否仍学习 |
| `use_slow_update` / `use_meta_skill` | `false` | TF-GRPO 场景关闭 ReflACT 慢路径 |

更多约束（变体串行、经验上下文冻结、新 `conversation_id` 等）见 [TF-GRPO 开发串讲](../tf_grpo/TF-GRPO开发串讲文档.md)。

---

## 9. 关键行为与约束

1. **Skill 真源在 Adapter**：优化前后均经 `restore_skill` / `skill_content` / `update_skill`；本地 `examples/scenarios/*/skills/` 不是运行时真源。
2. **热更后必须新会话**：禁止复用旧 `conversation_id`。
3. **Val gate**：候选在验证集上不优于基线则拒绝写回；`edits_applied` 只统计被接受的改进。
4. **Frontmatter**：默认 `preserve_frontmatter=true`，避免元数据被 LLM 改坏。
5. **TF-GRPO 经验库**：默认不跨 run 持久化；进程结束即清空。
6. **本指南范围**：`optimizer_type=skill`。Prompt managed-doc 优化见 [提示词优化使用指南](prompt-optimization-guide.md)；tool 优化另见 API 参考。

---

## 10. 验证是否成功

优化成功时通常满足：

- Job `status` 为 `completed`，`error` 为空；
- `result.val` 含基线与最终分；若有改进，可见 `improvement` 与更新后的 `skill_contents`；
- Adapter 上目标 skill 内容已热更为门控接受版本（或明确保持基线）；
- `workspace/artifacts/<run_id>/` 下有 epoch / gate 相关文件。

可用 Swagger：`http://localhost:8001/docs`。

---

## 11. 常见问题

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| POST 500 / Adapter 相关错误 | 未设 `EVO_ADAPTER_URL` 或 sidecar 不可达 | 检查环境变量与 Adapter `/health` |
| 422：dataset 路径 | 文件不存在或不在白名单 | 调整 `EVO_ALLOWED_DATA_ROOTS` 或移动文件 |
| 422：skills 校验失败 | skill 模式未传 `skills`，或与 `managed_doc_kind` 同时出现 | 只传非空 `skills` |
| 场景找不到 | `scenario`/`name` 写成了业务标签而非目录名 | 改为 `skillopt` 或 `tf_grpo` |
| 切分后 train/val 为空 | case 太少或比例不当 | 至少 2 条；保证两侧 ≥ 1 |
| TF-GRPO 很慢 / 贵 | `group_size` × cases × epochs 放大 LLM 与 rollout | 先用冒烟超参 |
| 分数不提升但任务完成 | 门控拒绝候选 | 查 `gate_results` 与评估器是否匹配任务 |

---

## 12. 下一步

- 扩展新场景：在 `examples/scenarios/<new_name>/` 增加 `scenario.yaml` + `optimizer.py`（可继承既有优化器类），重启后由 `GET /scenarios` 发现。
- 深入 TF-GRPO 算法步骤与联调拓扑：阅读 [TF-GRPO 开发串讲](../tf_grpo/TF-GRPO开发串讲文档.md)。
- 对接 Adapter 热更 / invoke / traces：阅读 [adapter-api-contract.md](../api/adapter-api-contract.md)。
- 完整字段与 Job 协议：阅读 [optimization-api-reference.md](../api/optimization-api-reference.md)（若与本文冲突，以当前代码为准）。

---

## 附录 A：开源「特性使用指南」常见写法（撰写参考）

业界（Diátaxis、GitHub Docs Quickstart、Prisma Guides 等）对「给用户/开发者的特性指南」通常约定：

| 要素 | 说明 |
| --- | --- |
| **文档类型** | How-to / Quickstart：目标导向，告诉读者「如何完成某事」；与 Tutorial（教学）、Reference（字段百科）、Explanation（原理）分开 |
| **标题** | 动词 / 目标优先（如「使用 X 做 Y」），一眼可知收益 |
| **导语** | 一段话说明问题、范围、读完能做什么 |
| **前置条件** | 环境、账号、依赖版本；尽量短 |
| **分步操作** | 编号步骤 + 可复制命令/请求体；一步一事 |
| **成功判据** | 「你应该看到…」便于自检 |
| **配置与示例** | 表格列出关键开关；给最小可运行示例 |
| **故障排查 / 下一步** | 高频错误与延伸链接 |
| **版式** | Markdown；层级清晰；避免把参考手册整本塞进 How-to |

本文按上述 How-to 结构组织；算法原理与完整 API 字段表分别指向 Explanation / Reference 文档。
