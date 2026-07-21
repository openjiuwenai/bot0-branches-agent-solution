# TF-GRPO 开发串讲文档

> **目标对象**：EvoAgent（`TfGrpoOptimizer`）+ EvoAgentAdapter + 业务 Agent（如 EDPAgent / `edp_agent`）  
> **场景入口**：`scenarios/tf_grpo/`  
> **交付形态**：**服务化** FastAPI（`POST /optimize`，scenario=`tf_grpo`）为主；CLI `run_optimize.py` 为同源辅路径  
> **组织形式参考**：`EvoAgentAdapter/tests/sandbox_mode/docs/沙箱模式开发串讲文档.md`  
> **运维部署**：`deployment/operations-guide.md`  
> **延伸阅读**：[`../develop/tf-grpo-vs-skillopt-architecture.html`](../develop/tf-grpo-vs-skillopt-architecture.html)；实验报告 `workspace/artifacts/69ee31b9d5d5/experiment_report.html`

---

## 1. 背景与目标

### 1.1 背景

#### Skill 文档优化的既有路径

EvoAgent 既有 Skill 优化（`SkillDocumentOptimizer` / SkillOpt 系）以「反思 → patch → 热更 → 验证门控」为主：依赖 LLM 产出编辑指令，逐步改写 `SKILL.md`。该路径适合局部修补，但对「多候选相对比较、跨轮经验积累」支持较弱。

#### TF-GRPO 在本项目中的定位

**TF-GRPO（Training-Free GRPO）** 以场景 **`tf_grpo`** 落地，由 `TfGrpoOptimizer` 实现，核心思路是**不更新模型权重**，而是优化 SKILL 文档本身：

1. 每轮生成一组（group）`SKILL.md` **变体**；
2. 在同一批 train case 上经 Adapter **真实 rollout** 打分；
3. 用 LLM 提炼组内 **语义优势（semantic advantage）**，写入 **经验库（ExperienceLibrary）**；
4. 下一轮变体生成注入经验上下文，形成跨 epoch 改进。

算法编排接入既有 `EvoTrainer` + `AdapterClient` 热更链路，与 `SkillDocumentOptimizer` 场景并存、互不替代。

### 1.2 目标

| 编号 | 目标 | 说明 |
| --- | --- | --- |
| 目标 1 | 组内相对选优 | 每 epoch 生成 `group_size` 个变体，按 train 均分取最优 |
| 目标 2 | 真实 rollout 打分 | 经 Adapter 热更 → 业务 Agent 对话 → cleaned-traces → evaluator |
| 目标 3 | 经验库跨 epoch | Add/Delete/Modify/Keep 更新自然语言经验，注入下一轮生成 |
| 目标 4 | 验证门控 | 候选 skill 在完整 val 上不低于/高于基线才采纳 |
| 目标 5 | 服务化可交付 | FastAPI 暴露优化任务；`optimizer_template.scenario=tf_grpo`；Job + SSE；场景 yaml 可配超参 |

### 1.3 非目标

- **不做** 模型权重训练或微调；仅优化 SKILL 文档与经验库。
- **不做** 自动任务生成；train/val 由 dataset 提供。
- **不做** 经验库跨 run 持久化落盘（进程结束即清空；`ExperienceLibrary.save` 未接入主链路）。
- **不替代** 既有 `SkillDocumentOptimizer`；二者通过不同 `scenario` 并存。

---

## 2. 场景、规则与约束

### 2.1 核心场景

| 场景 | 触发 | 预期 |
| --- | --- | --- |
| 启动 API | `uvicorn evo_agent.api.app:app` / Docker `run.sh` | `/health` ok；`/scenarios` 含 `tf_grpo` |
| 启动优化（交付） | `POST /optimize`，`scenario=tf_grpo` | Job queued → `run_optimization` |
| 启动优化（调试） | CLI `run_optimize.py --scenario tf_grpo` | 同源编排，可用 dataset.yaml |
| 基线验证 | `run_optimization` 内 Trainer.evaluate(val) | 得到 `val_score_before` |
| 采样 train 子集 | 每个 TF-GRPO epoch 开始 | 无放回采样 `cases_per_variant` 条，组内共用 |
| 生成变体 | `_generate_variant` | LLM 产出完整 SKILL.md；可选完整性校验失败则丢弃 |
| 热更 + rollout | 每个有效变体 | `update_skill` → 新 `conversation_id` 对话 → 均分 |
| 更新经验 | 组内分数有方差 | 语义优势 → 经验库操作 |
| 验证门控 | epoch 结束 | 候选 val 分更高则采纳，否则 keep base |
| 进度查询 | `GET /optimize/{job_id}` / SSE stream | 前端或交付脚本可观测 |
| 取消任务 | `POST /optimize/{job_id}/cancel` | 长跑可中止 |
| 落盘快照 | ArtifactExporter | `epoch_N/skill_before.md`、`skill_after.md`、`gate_result.json` |

### 2.2 关键规则

| 规则 | 说明 |
| --- | --- |
| 同 epoch 样本共用 | `seed = artifact_epoch + 1`；G 个变体用同一批 train cases |
| 经验上下文冻结 | 变体循环开始前读取 `to_prompt_context()`；本轮新经验下一 epoch 才生效 |
| 变体串行 | 组内 `g=1..G` 串行：后变体可看到组内当前最优正文（current_best） |
| 完整性门禁 | `validate_variant_completeness`（默认 `false`） | 开启时：空文档、未闭合代码围栏、行尾截断、相对基线过短等通用启发式 → reject |
| frontmatter 保留 | `restore_frontmatter` / `preserve_frontmatter` 与既有链路一致 |
| 新 conversation_id | `{run_id}:{phase}:{counter}:{case_id}`，禁止热更后复用旧会话 |
| 空提取重试 | 配置了 extract 且字段空 → 换 conversation_id 重试 |
| train/val 非空 | 划分后两侧至少各 1 条；物理最小数据集 **2** 条 case |

### 2.3 关键约束

| 约束 | 说明 | 影响 |
| --- | --- | --- |
| Adapter 可达 | `EVO_ADAPTER_URL`；热更与对话依赖 sidecar | 联调需三容器或等价拓扑 |
| 数据路径白名单 | API `dataset_path` 须在 `EVO_ALLOWED_DATA_ROOTS` | 否则 422 |
| API vs CLI 评估器 | API：`evaluator_template.type` 默认 **`metric`**（可 `extract`）；显式 `type=llm` + `prompt` 做语义评估。CLI：`dataset.yaml` 声明 evaluator | 服务化与 CLI 均可走 metric；语义评估须显式选 llm |
| Skill 热更真源 | jiuwenbox 模式下写沙箱 `/tmp/skills` | 须与业务 Agent 同 sandbox |
| LLM 配额 | 变体生成 + 摘要 + 经验更新多次调用 | 长跑成本高，注意超时策略 |
| 中间变体不落盘 | 仅胜出写入 `skill_after` | 审计需查日志或扩展导出 |

### 2.4 待确认点

| 问题 | 影响 | 当前处理 |
| --- | --- | --- |
| 经验库是否落盘 | 跨 run 是否可续训 | 当前不落盘；可后续接 `ExperienceLibrary.save` |
| 完整性校验是否默认开启 | 变体 discard 率与质量 | 默认关闭；`scenario.yaml` 可设 `validate_variant_completeness: true` |
| API 评估模式 | `metric` / `llm` 如何选 | 默认 `metric`；语义评估传 `type: llm` + `prompt`（建议含 `{messages}`） |

---

## 3. 总体方案

### 3.1 方案概述

1. **服务化入口（交付）**：`POST /optimize` → JobManager → `run_optimization`；进度 SSE / 轮询。  
2. **CLI 入口（调试）**：`skills/optimize_skill/scripts/run_optimize.py --scenario tf_grpo` → 同一 `run_optimization`。  
3. **装配**：`ScenarioRegistry` 构建 `TfGrpoOptimizer`；`AdapterClient` + skill operators；加载 dataset evaluator。  
4. **基线**：`EvoTrainer.evaluate` 跑 val。  
5. **训练**：每 epoch 调用 optimizer `_backward`（TF-GRPO 核心）；门控在 Trainer 侧比较 base/candidate。  
6. **写回**：胜出变体经 operator callback → `update_skill`；产物写入 `workspace/artifacts/{run_id}/`。

### 3.2 链路图

```mermaid
sequenceDiagram
    participant Client as 平台/交付脚本
    participant API as EvoAgent FastAPI
    participant Runner as optimizer_runner
    participant Opt as TfGrpoOptimizer
    participant Lib as ExperienceLibrary
    participant AC as AdapterClient
    participant Biz as 业务 Agent

    Client->>API: POST /optimize (scenario=tf_grpo)
    API->>API: Job queued + SSE
    API->>Runner: run_optimization
    Runner->>Runner: baseline evaluate(val)
    loop 每个 epoch
        Opt->>Opt: sample cases_per_variant
        Opt->>Lib: to_prompt_context (冻结)
        loop g = 1..group_size
            Opt->>Opt: LLM 生成变体 e{N}-g{g}
            Opt->>AC: update_skill
            Opt->>AC: invoke × cases (新 conv_id)
            AC->>Biz: 对话 / read_file SKILL
            Opt->>Opt: evaluator 均分 + rollout 摘要
        end
        Opt->>Lib: semantic advantage → apply_operations
        Opt->>AC: 同步组内最优 skill
        Runner->>Runner: val gate (base vs candidate)
    end
    API-->>Client: GET /optimize/{job_id} completed
```

### 3.3 单 Epoch 算法步骤

```text
1. 采样 train 子集（组内共用）
2. 读取经验上下文（本轮冻结）
3. for g in 1..G:
     生成变体 → 完整性校验 → 热更 → rollout 打分 → 摘要
4. 若分数有方差：提炼语义优势 → 更新经验库
5. 组内最高分变体 → Validation Gate（完整 val）
```

### 3.4 模块分工

| 模块 | 职责 | 输入 | 输出 |
| --- | --- | --- | --- |
| `api/routes/optimize.py` | 服务化接单 / Job / SSE / cancel | OptimizeAPIRequest | JobResponse |
| `scenarios/tf_grpo/` | 场景 yaml + optimizer 绑定 | hyperparams | `TfGrpoOptimizer` |
| `tf_grpo_optimizer.py` | epoch 编排、采样、热更、rollout、择优 | train cases / skill | 最优 skill 正文 |
| `variant_generator.py` | 变体 prompt、完整性、frontmatter | current_best + 经验 | SKILL.md 文本 |
| `semantic_advantage.py` | 摘要 / 优势 / 库操作解析 | RolloutSummary 列表 | ops JSON |
| `experience_library.py` | 经验存储与 trim | Add/Modify/Delete | prompt context |
| `EvoTrainer` | 基线/门控 evaluate | RemoteAgent + cases | score + EvaluatedCase |
| `AdapterClient` | invoke / update_skill / traces | HTTP | 对话与热更结果 |

---

## 4. 关键设计

### 4.1 场景配置（`scenario.yaml`）

| 超参 | 含义 | 本实验常用值 | 最小冒烟建议 |
| --- | --- | --- | --- |
| `group_size` | 每轮变体数 | 3 | 1 |
| `cases_per_variant` | 每轮共用 train case 数 | 8 | 1 |
| `variant_temperature` | 变体生成温度 | 1.5 | 1.0 |
| `max_experiences` | 经验库容量 | 20 | 5 |
| `validate_variant_completeness` | 变体完整性门禁 | `false` | `false` |
| `num_epochs` | 训练轮数（API hyperparams / CLI 可覆盖） | 3 | 1 |
| `num_parallel` | rollout 并发 | 4 | 1 |

API 请求中 `optimizer_template.hyperparams` 与 scenario yaml 合并（请求优先）。

### 4.2 变体生成

- Prompt：`build_variant_prompt(current_best, experience_context, epoch)` + 改进轴轮转提示（见 `tf_grpo_optimizer._VARIANT_AXIS_HINTS`）。
- 后处理：去代码围栏、`restore_frontmatter`（与 `preserve_frontmatter` 链路一致）。
- 完整性：`validate_variant_completeness` 为 `true` 时，`skill_document_incompleteness_reason` 做通用截断启发式（空文档 / 围栏未闭合 / 行尾截断 / 相对基线过短）；默认关闭。
- 温度：`variant_temperature`；经 `invoke_text_with_retry` 防超时/不可用结果。

### 4.3 Rollout 与打分

- 热更后对采样 cases：`RemoteAgent.invoke` + `get_traces`（metric + extract 时字段空可换 conversation_id 重试）。
- **评估器（API）**：`evaluator_template.type` 默认 **`metric`**（`metric` 字段默认 `exact_match`，可配 `extract`）；语义评估显式 `type: llm` + `prompt`。
- **评估器（CLI）**：`dataset.yaml` 的 `evaluator.type`（`metric` / `llm` / …）。
- 均分为组内比较依据；val 门控用完整 val_cases。

### 4.3.1 API `evaluator_template` 要点

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `type` | `metric` | `metric` \| `llm` |
| `metric` | `exact_match` | metric 模式指标名（可 list） |
| `extract` | 无 | 仅 metric；如业扩从 `<answer>` JSON 抽 `responsibility` |
| `prompt` | `""` | 仅 llm；空则用内置 `policy_v1` 模板 |

metric 示例（业扩）：

```json
"evaluator_template": {
  "name": "exact",
  "scenario": "audit-business",
  "type": "metric",
  "metric": "exact_match",
  "extract": {
    "strategy": "answer_tag_json_field",
    "source": "answer",
    "fields": ["responsibility", "responsibility_type"],
    "prefer_values": ["无责", "有责"]
  }
}
```

llm 示例：

```json
"evaluator_template": {
  "name": "semantic",
  "scenario": "audit-business",
  "type": "llm",
  "prompt": "……含 {messages} / {expected_section} 的完整模板，或一段指令……"
}
```

### 4.4 经验库

- 操作类型：Add / Delete / Modify / Keep。
- 无方差时跳过更新（含 `group_size=1`）。
- `max_experiences` 超限按置信度/最近使用 trim。

### 4.5 产物布局

```text
workspace/artifacts/{run_id}/
  epoch_0/skill_before.md, skill_after.md
  epoch_N/skill_before.md, skill_after.md, gate_result.json
```

---

## 5. 可观测性

| 观测点 | 位置 | 用途 |
| --- | --- | --- |
| Job status / progress | `GET /optimize/{job_id}` | 服务化交付轮询 |
| SSE events | `GET /optimize/{job_id}/stream` | 实时阶段推送 |
| `[tf_grpo] TF-GRPO: op=...` | phase_callback / 日志 | epoch 开始 |
| `variant generation failed` | logger | 变体生成异常 |
| `[tf_grpo_done] best eN-gK score=` | phase_callback | 组内胜出 |
| `gate_result.json` | artifacts | base/candidate/decision |
| `optimize_*.log` / uvicorn 日志 | workspace / 容器 logs | 全量 LLM/进度 |

联调建议：先 API 冒烟（health + scenarios + 接单 cancel），再 TC-E2E-MIN，最后多 epoch。

---

## 6. 测试建议（服务化交付）

### 6.1 测试资产

| 文档/脚本 | 路径 |
| --- | --- |
| 测试用例 | [`TF-GRPO测试用例.md`](TF-GRPO测试用例.md) |
| 执行报告 | [`TF-GRPO测试用例执行报告.md`](TF-GRPO测试用例执行报告.md) |
| 单元测试 | `tests/unit/optimizer/tf_grpo/` |
| 场景说明 | `scenarios/tf_grpo/README.md` |
| 运维部署 | `deployment/operations-guide.md` |
| 实验报告 | `workspace/artifacts/69ee31b9d5d5/experiment_report.html` |

### 6.2 自测门禁（发布前）

1. **单元**：`pytest tests/unit/optimizer/tf_grpo -q` 全部通过。  
2. **服务化冒烟**：EvoAgent `/health`、`/scenarios` 含 `tf_grpo`；Adapter/EDP/jiuwenbox `/health`。  
3. **服务化集成**：非法请求 422；`POST /optimize` 接单后可 `cancel`。  
4. **E2E-MIN**：1 epoch / 1 变体 / 2 case，产出 `gate_result.json`。  
5. **（可选）E2E 全量**：多 epoch balanced 数据集。

### 6.3 执行示例（服务化）

```bash
cd common/agent-evolve/evoagent
# 启动 API（需 .env 含 EVO_ADAPTER_URL）
.\.venv\Scripts\uvicorn.exe evo_agent.api.app:app --host 127.0.0.1 --port 8000

.\.venv\Scripts\python.exe -m pytest tests/unit/optimizer/tf_grpo -v

curl http://127.0.0.1:8000/health
curl http://127.0.0.1:8000/scenarios

# POST /optimize 见测试用例 §7（scenario=tf_grpo）
```

---

## 附录

### A. 代码锚点

| 符号 | 路径 |
| --- | --- |
| FastAPI app | `src/evo_agent/api/app.py` |
| Optimize 路由 | `src/evo_agent/api/routes/optimize.py` |
| `TfGrpoOptimizer` | `src/evo_agent/optimizer/tf_grpo/tf_grpo_optimizer.py` |
| `ExperienceLibrary` | `src/evo_agent/optimizer/tf_grpo/experience_library.py` |
| `variant_generator` | `src/evo_agent/optimizer/tf_grpo/variant_generator.py` |
| `semantic_advantage` | `src/evo_agent/optimizer/tf_grpo/semantic_advantage.py` |
| 场景绑定 | `scenarios/tf_grpo/optimizer.py`、`scenario.yaml` |
| 编排入口 | `src/evo_agent/optimizer_runner.py` |

### B. 文档索引

| 文档 | 说明 |
| --- | --- |
| 本串讲 | TF-GRPO 算法与本仓库服务化入口 |
| 测试用例 / 执行报告 | 同目录交付测试（API 为主） |
| 运维指南 | `deployment/operations-guide.md` |
| 架构对比 HTML | `docs/develop/tf-grpo-vs-skillopt-architecture.html`（与 SkillDocument 路径对比） |
| 流程说明 HTML | 同目录 `TF-GRPO优化流程说明-*.html`（实验 run 解析） |
