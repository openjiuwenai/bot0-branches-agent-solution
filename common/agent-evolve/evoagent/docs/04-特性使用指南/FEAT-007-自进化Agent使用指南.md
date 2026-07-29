# 自进化Agent使用指南（skill-evaluator + skill-optimizer）

> 本指南介绍两个可安装到本地 agent（如 Claude Code、Codex、Jiuwen Swarm 等操作者 agent）的 skill：**skill-evaluator** 与 **skill-optimizer**。由操作者 agent 用 HTTP 工具直接执行，完成对**目标 agent** 的 skill 评估与自动优化闭环。

---

## 1. 特性概览

### 1.1 这是什么

本指南介绍的是两个 skill（不是一个新的 agent），均可安装到操作者 agent 中使用：

- **skill-evaluator（评估模块）**：读 baseline → 单条/批量评估打分 → 生成期望行为 → 构造黄金数据集。**不提交优化、不回写 skill**。
- **skill-optimizer（优化模块）**：读 baseline → 提交 `/optimize` → 轮询 → 报告前后对比 → 回写 skill → 调 agent 验证。**不跑 `/evaluate` 系列独立评估**（评估在 `/optimize` 循环内由优化器自带）。

两个 skill 即两个模块，互为独立，串行使用即完整自进化流程。

> **skill 自进化链路** 通过「评估打分 + 优化器迭代 + adapter 回写」的 HTTP 链路，实现目标 agent skill 的基线度量与自动改进，适用于优化前看现状、优化后验提升的闭环场景。

两个 skill 由操作者 agent 直接用 HTTP 工具执行，不调用任何外部脚本。下文示例以**用户对 agent 说什么**为主线，agent 内部自动发对应 HTTP 请求，**用户无需手敲命令**。

> 注意：这两个 skill 优化/评估的是**目标 agent**（adapter 里配置的业务 agent）的 skill；而它们本身是**操作者 agent**（如 Jiuwen Swarm）使用的 skill。两者不是同一个 agent。下文出现的目标 agent/skill 名（`xxx_agent`/`xxx_skill`）均为占位符，使用时替换为实际业务 agent/skill 名。

### 1.2 与相近特性的区别

| 特性 | 核心能力 | 适用条件 | 不适用情况 |
|---|---|---|---|
| `skill-evaluator` | 基线打分、批量评估、生成期望行为、攒黄金数据集 | 优化前后要看现状、要提供数据 | 提交优化、回写 skill |
| `skill-optimizer` | 提交 `/optimize`、轮询、回写、验证 | 已有服务器侧黄金数据集、要改 skill | 独立跑 `/evaluate` 打分、构造黄金数据集 |

> 当用户要「打分/构造数据集/生成期望行为」时使用 **skill-evaluator**；当用户要「改 skill/跑一轮优化/回写验证」时使用 **skill-optimizer**。两者串行：先 evaluator 构造黄金数据集，再 optimizer 跑优化。

### 1.3 本指南覆盖范围

本指南包含：

- 两个模块各自的适用场景与边界
- 服务地址、LLM 配置、dataset 白名单等准备项
- 评估（单条/批量/生成目标/构造数据）与优化（提交/轮询/回写/验证）的自然语言交互示例
- 接口速查、请求参数、返回结果、状态码与排错

本指南不包含：

- 优化器服务本身的部署与运维（联系维护方）
- adapter 已配置业务 agent 的注册流程（见 adapter `/docs`）
- 优化器内部 train/val 分片与 rollout 清洗逻辑（优化器自行完成，skill 侧不参与）

---

## 2. 什么时候使用

| 使用场景 | 不适用 / 需先满足的条件 |
|---|---|
| 给某 agent 的某 skill 打基线分（优化前看现状） | 要改 agent 配置或模型 → 改用 adapter 配置接口 |
| 批量评估一个 golden_data 数据集整体跑分 | 只想跑单条轨迹而 trajectory_path 不在服务器上 → 先 scp 到白名单目录 |
| 从一条轨迹自动生成 expected_behavior | 没有目标 agent/skill 名 → 先向用户澄清 |
| 从 adapter 历史轨迹构造一个黄金数据集供优化首轮 rollout | 连数据集都不想构造 → 先用现成轨迹手敲 jsonl |
| 用服务器侧黄金数据集提交 `/optimize` 优化 skill | 只有本地文件未上传 → 先 scp 到白名单目录再调用 |
| 查已提交优化任务的进度/结果、优化前后对比、回写验证 | 要列出历史优化任务 → 优化器无此接口，必须带 `job_id` 查 |

---

## 3. 准备工作

### 3.1 环境要求

| 项目 | 要求 | 检查方式 |
|---|---|---|
| 优化器服务（EvoAgent API） | 已部署且 `/health` 返回 `{"status":"ok"}` | 让 agent 用 HTTP 工具探一下 `$OPT/health` |
| Agent Adapter 服务 | 已部署且 `/health` 返回 `{"status":"ok"}` | 让 agent 探一下 `$ADP/health` |
| 目标 agent | 已在 adapter 配置（`xxx_agent` 等业务 agent） | 让 agent 查 `$ADP/api/v1/config/agents` |
| `curl` | 任意现代版本 | agent 内部用 HTTP 工具即可，无需用户手敲 |

> agent 自带 JSON 解析能力，无需 `jq`/`python3` 等外部依赖。

### 3.2 安装依赖

本模块以 HTTP 调用为主，由 agent 内部完成，通常无需用户额外安装。

### 3.3 启动服务

优化器与 adapter 服务由部署方维护，正常应已常驻运行。开始前让操作者 agent 自检服务是否就绪——对 agent 说一句「先确认优化器和 adapter 服务在不在」，agent 会依次探 `$OPT/health`、`$ADP/health`、`$ADP/api/v1/status` 并把结果汇总给你，类似：

```text
优化器: {"status":"ok"}
adapter: {"status":"ok"}
xxx_agent: archived=123, offset=456
```

#### 3.3.1 服务地址配置（全文唯一地址来源）

服务地址由部署方提供，agent 在内部以两个变量引用，不再裸露 IP/端口。地址需注入操作者 agent 的会话环境（如在 Claude Code 的 `settings.json` env、或对话首句直接告知 agent），全文以此为准。若日后迁移服务，**只需让用户/部署方更新这两处地址**：

- 优化器：`OPT = http://{OPTIMIZER_HOST}:8000`
- adapter：`ADP = http://{ADAPTER_HOST}:8900`

下文示例中 `$OPT` / `$ADP` 即指这两个地址。

### 3.4 条件性准备

只有在使用对应模块时，才需要完成以下准备。

| 使用模块 | 额外资源 | 准备方式 | 能否由 agent 代劳 |
|---|---|---|---|
| 评估模块（`/evaluate`、`/evaluate/dataset`、`/evaluate/generate-goal`） | `LLM_CONFIG`（`model_name`+`api_key`+`api_base`） | 由用户提供真实值，skill 不内置；见 5.3 配置项 | 否，须用户提供 |
| 单条轨迹评估 | `trajectory_path` | 本地轨迹 jsonl 先 `scp` 到优化器服务器白名单目录，取服务器侧绝对路径 | 否，scp 需服务器侧权限，由用户/部署方在服务器执行 |
| 批量数据集评估 | golden_data jsonl | 本地文件随 `/evaluate/dataset` multipart 上传，无需落地服务器 | 是，agent 直接 multipart 上传 |
| 提交优化 `/optimize` | `dataset_path` | golden_data jsonl 须已存在于优化器服务器白名单三目录之一 | 否，须先 scp 到服务器（由用户/部署方执行） |
| 优化后探针验证 | 探针 `conversation_id` | 自取一个不重复字符串即可 | 是，agent 生成 |

> **关于 LLM 配置**：评估模块的端点强制要求 `LLM_CONFIG`（skill 不内置，需用户提供）；优化模块的 `/optimize` **不要求** skill 侧传 LLM 配置（优化器自带模型配置），不向用户索取。即：只用优化模块时可完全不配 `LLM_CONFIG`。

`/optimize` 的 `dataset_path` 白名单三目录：

- `/tmp/evo_agent`
- `/data/evo_agent`
- `/home/evolution/evoagent-studio`

文件须是 golden_data 格式：每行含 `id/inputs/expected_behavior/case_type`。可由 `skill-evaluator` 构造后由用户 scp 上传。

---

## 4. 快速上手

本节提供两个最小可验证闭环：评估闭环与优化闭环。全程自然语言交互。

### 4.1 评估最小闭环：单条轨迹评估打分

**准备输入**——用户对 agent 说清楚三样东西：

1. **服务地址**：优化器与 adapter 的地址（由部署方提供，或已注入会话环境）。
2. **一条轨迹**：把本地轨迹 jsonl `scp` 到优化器服务器白名单目录（如 `/data/evo_agent/trace_001.jsonl`），取服务器侧绝对路径。一条轨迹 jsonl 可含一条或多条用例。
3. **LLM 配置**：评估端点要真实可用的 `model_name`+`api_key`+`api_base`，由用户提供，skill 不内置。

```text
用户：先确认下环境——优化器在 http://your-optimizer-host:8000，
      adapter 在 http://your-adapter-host:8900。我有一条轨迹
      trace_001.jsonl，已经 scp 到优化器服务器的 /data/evo_agent/ 下了。
      评估用的模型用 glm-5.2，key 我接下来贴给你，api_base 是
      http://your-llm-base/v1。

agent：收到。地址、轨迹路径(/data/evo_agent/trace_001.jsonl)、LLM 配置都记下了，
       待会儿评估时直接用。需要我现在就打分吗？
```

**执行操作**——让 agent 给这条轨迹打分，agent 内部 `POST $OPT/evaluate`，用默认评估 prompt（见附录 A）和用户给的 LLM 配置：

```text
用户：那就给这条轨迹打个分吧，skill 是 xxx_agent 的 xxx_skill。

agent：（内部 POST $OPT/evaluate：trajectory_path=/data/evo_agent/trace_001.jsonl，
        skill_names=["xxx_skill"]，prompt_template 用附录 A 默认严格版，
        llm_config 用你给的 glm-5.2 配置）
       评估完成——
       score: 0.85   is_pass: true
       task_completion: 0.75  trajectory_quality: 1.0  compliance: 1.0
       attributed_skill: xxx_skill
       reason: <理由摘要>
```

**验证结果**——agent 把评估结果整理成易读形式回报（如上）。确认 `score`/`is_pass`/`attributed_skill` 已返回且 `attributed_skill` 与目标 skill 一致，即评估成功；原始返回结构见 5.6。

### 4.2 优化最小闭环：提交一轮优化 + 回写验证

**准备输入**——在评估闭环基础上，另需一个服务器侧黄金数据集：

1. **黄金数据集**：golden_data jsonl 已 scp 到优化器服务器白名单目录（如 `/data/evo_agent/golden_0701.jsonl`）。
2. **baseline 全文**：让 agent 先读出目标 skill 全文存一份（见 5.5「adapter 读/写 skill」），用于回滚。

```text
用户：用 /data/evo_agent/golden_0701.jsonl 优化 xxx_agent 的 xxx_skill，
      跑一轮。先帮我存一份当前 baseline。

agent：（内部 POST $ADP/api/v1/skills：action=skill_content, skill_name=xxx_skill）
       baseline 全文已取出存好，编号 baseline-001。
       （内部 POST $OPT/optimize：task_name=xxx_agent-skill-optimize-<时间戳>、
        agent_name、optimizer_type=skill、skills、dataset_path、
        optimizer_template/evaluator_template 用附录 B 默认结构）
       已提交优化，job_id=opt-xyz789。我轮询一下……
       完成：train score 0.575→0.62，edits_applied=3。
       （内部 POST $ADP/api/v1/skills：action=update_skill，写回优化后全文）
       已回写，重读 skill_content 与优化后全文一致，确认生效。
```

**验证结果**——确认 `/optimize/{job_id}` 返回 `status=completed`、`skill_contents` 已拿到并回写、重读 skill_content 与优化后全文一致，即优化闭环成功。如合规护栏被误删，可用 baseline-001 回滚（见 6.6）。

> 两个闭环可独立使用：只想看现状跑 4.1 即可；想改 skill 跑 4.2。完整场景见第 6 章。

---

## 5. 接口与配置

### 5.1 接口速查

> 本节为 agent 构造请求时的速查表，用户通常不直接关心；agent 会自动拼装。详细字段以优化器 `/openapi.json` 为准。

#### 优化器（EvoAgent API，`$OPT`）

| 方法 | 路径 | 作用 | 适用场景 |
|:--:|---|---|---|
| `POST` | `/evaluate` | 单条轨迹评估打分 | 基线打分、复评 |
| `POST` | `/evaluate/dataset` | 批量数据集评估（multipart：`file` + `config`，异步 job） | 整个 golden_data 跑分 |
| `GET` | `/evaluate/dataset/jobs/{job_id}` | 查批量评估任务状态 | 轮询聚合分 |
| `GET` | `/evaluate/dataset/jobs/{job_id}/stream` | SSE 实时进度（支持 `Last-Event-ID` 重放） | 实时观察 |
| `POST` | `/evaluate/generate-goal` | 从轨迹消息生成 expected_behavior | 补全 golden_data |
| `POST` | `/optimize` | 提交 skill 优化任务 | 迭代改进 skill |
| `GET` | `/optimize/{job_id}` | 查优化任务进度/结果 | 轮询到完成 |
| `POST` | `/optimize/{job_id}/cancel` | 中止优化任务 | 取消运行中任务 |
| `GET` | `/optimize/{job_id}/stream` | SSE 实时进度（支持 `Last-Event-ID` 重放） | 实时观察 |
| `GET` | `/scenarios` | 列合法 scenario 名与默认超参 | 选 scenario |
| `GET` | `/health` | 健康检查 | 自检 |

#### Agent Adapter（`$ADP`）

| 方法 | 路径 | 作用 | 适用场景 |
|:--:|---|---|---|
| `POST` | `/api/v1/skills` | skill 统一入口（`skill_list`/`skill_content`/`update_skill` 三种 action） | 读 baseline、回写 skill |
| `GET` | `/api/v1/agents/{agent}/traces` | 列历史轨迹 conversation_id | 攒黄金数据集 |
| `GET` | `/api/v1/agents/{agent}/cleaned-traces/{conv_id}` | 取单条轨迹清洗结果 | 组 golden_data |
| `POST` | `/api/v1/agents/{agent}/conversations/{conv_id}` | 调真实 agent 产新轨迹 | 探针验证、攒数据 |
| `GET` | `/api/v1/config/agents` | 列已配置业务 agent | 确认目标 agent |
| `GET` | `/api/v1/status` | 各 agent trace 归档/offset 状态 | 自检 |
| `GET` | `/health` | 健康检查 | 自检 |

### 5.2 请求参数

#### `/evaluate` 请求参数

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `trajectory_path` | string | 是 | 无 | 优化器服务器侧轨迹 jsonl 绝对路径；本地文件需先 scp |
| `prompt_template` | string | 是 | 无 | 评估 prompt（三维度评分，默认值见附录 A） |
| `llm_config` | object | 是 | 无 | LLM 端点配置，**需用户提供，skill 不内置** |
| `skill_names` | string[] | 是 | 无 | 待评估 skill 名列表 |
| `expected_result` | string\|null | 否 | `null` | 非空时作为「预期结果」注入评估 prompt |
| `filters` | object\|null | 否 | `null` | 启用后在 LLM 评估前执行确定性过滤（`tool_failure`/`user_feedback` 子配置） |

> `trajectory_path` 是优化器服务器上的绝对路径，不要与本地路径混用；skill 不代传本地文件。

#### `/evaluate/dataset` 请求参数（multipart：`file` + `config`）

multipart 上传，`file` 为 golden_data jsonl，`config` 为 JSON 对象：

| `config` 字段 | 类型 | 必填 | 说明 |
|---|---|:--:|---|
| `llm_config` | object | 是 | LLM 端点配置，**需用户提供，skill 不内置** |
| `prompt_template` | string | 否 | LLM 评估用的 prompt（默认见附录 A；仅当 groups 用 `llm_judge` 时生效） |
| `groups` | array | 是 | 评估分组列表，每组指定一种评估方式 |

每个 group 支持 `type`：`exact_match`（精确匹配）/ `keyword`（关键词命中）/ `llm_judge`（LLM 裁判，需 `llm_config`+`prompt_template`）。各 type 的具体子字段以 `/openapi.json` 为准。

> 批量评估是异步 job，提交后返回 `job_id` 与 `dataset_id`，agent 自行轮询 `$OPT/evaluate/dataset/jobs/{job_id}` 直到 `completed`，再按 groups 结构把聚合分报给用户。

#### `/optimize` 请求参数

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `task_name` | string | 是 | 无 | 任务名；同 `task_name` 重提会被幂等拒绝（见 7.1），需换名重跑，建议 `<agent>-skill-optimize-<时间戳>` |
| `agent_name` | string | 是 | 无 | 目标 agent 名 |
| `optimizer_type` | string | 是 | 无 | 固定 `skill` |
| `skills` | string[] | 是 | 无 | 待优化 skill 名列表（可多个） |
| `dataset_path` | string | 是 | 无 | 优化器服务器侧 golden_data jsonl 绝对路径，须在白名单三目录下且文件已存在 |
| `optimizer_template` | object | 是 | 无 | 优化器模板（含 scenario、超参等，默认结构见附录 B） |
| `evaluator_template` | object | 是 | 无 | 评估器模板（`type:llm` + prompt，默认结构见附录 B） |

> `dataset_path` 是优化器服务器上的路径，不要与 `trajectory_path` 混用；它必须是 golden_data 格式且已存在。

### 5.3 配置项

| 配置项 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `OPT` | string | 是 | 无 | 优化器服务地址（注入会话环境，见 3.3.1） |
| `ADP` | string | 是 | 无 | adapter 服务地址（注入会话环境，见 3.3.1） |
| `LLM_CONFIG.model_name` | string | 是（评估模块） | 无 | 模型名（如 `glm-5.2`） |
| `LLM_CONFIG.api_key` | string | 是（评估模块） | 无 | API Key，**用户提供真实值** |
| `LLM_CONFIG.api_base` | string | 是（评估模块） | 无 | API Base URL |
| `LLM_CONFIG.client_provider` | string | 否 | `OpenAI` | 对接非 OpenAI 兼容接口时按需改 |
| `LLM_CONFIG.temperature` | number | 否 | `0.1` | 采样温度 |
| `LLM_CONFIG.max_tokens` | number | 否 | `2048` | 最大 token |
| `LLM_CONFIG.verify_ssl` | boolean | 否 | `false` | 是否校验 SSL |
| `optimizer_template.hyperparams.num_epochs` | number | 否 | `1` | 固定 `1`，已写入请求模板，不向用户询问、不在对话输出 |
| `train_split` | number | 否 | `0.6` | 训练集比例 |
| `val_split` | number | 否 | `0.4` | 验证集比例 |
| `edit_budget` | number | 否 | `8` | 编辑预算 |
| `parallelism` | number | 否 | `4` | 并行度 |
| `update_mode` | string | 否 | `patch` | 更新模式 |
| `score_threshold` | number | 否 | `0.8` | 分数阈值 |
| `scheduler_mode` | string | 否 | `constant` | 调度模式 |
| `optimizer_template.scenario` | string | 否 | `<agent_name>` | 场景名，默认用 agent 名 |

> `skill-optimizer` 的 `/optimize` 不要求 skill 侧传 LLM 配置（优化器自带模型配置），不向用户索取；评估模块则必传，需用户提供。地址类配置（`OPT`/`ADP`）由部署方/用户注入会话环境，其余评估/优化参数由 agent 按附录默认模板自动构造，用户通常无需手填。

### 5.4 交互示例

以下为「用户口令 → agent 内部动作 → 反馈」的最小示例，完整场景见第 6 章。

#### 单条评估

```text
用户：给 xxx_agent 的 xxx_skill 打个基线分，
      轨迹在 /data/evo_agent/trace_001.jsonl。

agent：（内部 POST $OPT/evaluate：trajectory_path、skill_names=["xxx_skill"]、
        prompt_template 用附录 A 默认严格版、llm_config 用用户提供的配置）
       score 0.85  is_pass true（task_completion 0.75 / trajectory_quality 1.0 / compliance 1.0）
       attributed_skill: xxx_skill
```

#### 提交优化

```text
用户：用 /data/evo_agent/golden_0701.jsonl 优化 xxx_agent 的 xxx_skill，跑一轮。

agent：（内部 POST $OPT/optimize：task_name=xxx_agent-skill-optimize-<时间戳>、
        agent_name、optimizer_type=skill、skills、dataset_path、
        optimizer_template/evaluator_template 用附录 B 默认结构）
       已提交优化，job_id=opt-xyz789。需要我轮询进度吗？
```

#### adapter 读/写 skill

```text
用户：先看一下 xxx_agent 现在有哪些 skill，再把 xxx_skill 的全文读出来当 baseline 存一份。

agent：（内部 POST $ADP/api/v1/skills：action=skill_list）
       xxx_agent 名下共 N 个 skill：……
       （再 POST 同接口：action=skill_content, skill_name=xxx_skill）
       baseline 全文已取出并存好，编号为 baseline-001，回滚时调这个。
```

```text
用户：把优化后的 xxx_skill 写回去。

agent：（内部 POST $ADP/api/v1/skills：action=update_skill, skill_name=xxx_skill,
        skill_content=<优化后全文>）已回写，重读 skill_content 与优化后全文一致，确认生效。
```

### 5.5 返回结果

> 用户通常不直接看原始字段，agent 会整理成易读结果回报；以下供排错核对，字段以实际返回为准。

#### `/evaluate` 返回

```json
{
  "task_completion": 0.75,
  "trajectory_quality": 1.0,
  "compliance": 1.0,
  "is_pass": true,
  "score": 0.875,
  "attributed_skill": "xxx_skill",
  "reason": "..."
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `task_completion` | number | 任务完成度（0~1） |
| `trajectory_quality` | number | 轨迹质量（0~1） |
| `compliance` | number | 合规性（0~1） |
| `is_pass` | boolean | `score >= 0.75` 时为 true |
| `score` | number | `task_completion*0.5 + trajectory_quality*0.3 + compliance*0.2` |
| `attributed_skill` | string | 归因到的 skill 名 |
| `reason` | string | 评分理由 |

#### `/evaluate/dataset` 提交返回

```json
{"job_id": "abc123", "dataset_id": "ds-xxx"}
```

#### `/evaluate/dataset/jobs/{job_id}` 返回

```json
{
  "job_id": "abc123",
  "status": "completed",
  "result": {"groups": [{"type": "exact_match", "score": 0.82, "per_case": []}]},
  "error": null
}
```

聚合分按 `config.groups` 结构返回（每个 group 一份分），具体键以实际返回为准。

#### `/optimize` 提交返回

```json
{"job_id": "opt-xyz789"}
```

> 取 `job_id`（注意是 `job_id` 不是 `id`），它是查进度的必带凭据，务必记住或告诉用户。

#### `/optimize/{job_id}` 返回（`JobResponse`）

```json
{
  "job_id": "opt-xyz789",
  "status": "completed",
  "progress": null,
  "result": {
    "train": {"score_before": 0.575, "score_after": 0.62, "improvement": 0.045},
    "val": {"score_before": 1.0, "final_score": 1.0},
    "edits_applied": 3,
    "skill_contents": [{"name": "xxx_skill", "content": "<优化后全文>"}]
  },
  "error": null
}
```

`status` 取值：`queued`/`running`/`completed`/`failed`/`cancelled`。`progress` 是 free-form 对象，常见键 `current_epoch`/`total_epochs`/`val_score`/`edits_applied`。`result` 可能含优化后全文（常见键 `skill_contents`/`skills[].content`/`optimized_skills`），有则用于回写。

### 5.6 状态码与异常

| 状态码 | 含义 | 常见原因 | 处理方式 |
|---|---|---|---|
| `200` | 成功 | 正常 | 按 `result` 处理 |
| `422` | 请求体校验失败 | `/evaluate` 缺 `llm_config`；`/evaluate/dataset` 缺 `file`/`config`；`/optimize` 缺 `agent_name`/`optimizer_template`/`evaluator_template`/`dataset_path` 或 `dataset_path` 不在白名单/文件不存在 | 看响应 `detail[].loc/msg`，按模板补全；路径改到白名单目录或 scp 文件到位 |
| `409` | 冲突 | 对终态任务调 `/optimize/{job_id}/cancel` | 任务已结束，无需中止 |
| `502`/连不上 | 服务不可达 | 优化器服务没起 | 联系维护方 |
| 超时仍 `running` | 单 agent 调用慢（timeout 300s） | 数据集大/模型慢 | 继续轮询或稍后带 `job_id` 查 |
| `$OPT`/`$ADP` 为空 | 地址未配置 | 新会话未注入 | 见 3.3.1 先确认两个地址 |

---

## 6. 场景用例

用例从简单到复杂排列。每个用例包含：适用场景、自然语言交互示例、预期结果和必要说明。

### 6.1 基础用例：单条轨迹评估（基线打分）

适用于：优化前给某 agent 的某 skill 打一次分，看当前水平。

```text
用户：给 xxx_agent 的 xxx_skill 打个基线分，
      轨迹在 /data/evo_agent/trace_001.jsonl。

agent：（内部 POST $OPT/evaluate，参数同 5.4「单条评估」）
       评估完成——
       score 0.85  is_pass true
       task_completion 0.75  trajectory_quality 1.0  compliance 1.0
       attributed_skill: xxx_skill
       reason: <理由摘要>
```

### 6.2 多任务执行：批量数据集评估 + 多 skill

适用于：用一整个 golden_data jsonl 整体跑分，或同时评估多个 skill。

```text
用户：用 ./golden_data_0701.jsonl 整体跑一遍，这次同时评 xxx_skill
      和 yyy_skill 两个 skill。

agent：（内部 POST $OPT/evaluate/dataset：multipart 上传 file，config.groups 里
        指定 exact_match/keyword/llm_judge 评估方式）
       已提交，job_id=abc123，轮询中……完成：各组聚合分我已整理好，
       per_case 里每个用例的逐条分需要的话贴给你。
```

> 多个 skill 同时评估时在 `config.groups` 中分组；聚合分按 groups 结构给出。agent 提交后自行每 ~8 秒轮询一次状态接口，直到 `completed`。

### 6.3 条件性用例：从历史轨迹攒黄金数据集 + 生成期望行为

只有没有 golden_data、需要现攒黄金数据集时，才需要本用例。

```text
用户：还没有 golden_data，帮我从 xxx_agent 的历史轨迹攒一个黄金数据集出来，
      expected_behavior 不够的就自动生成一下。

agent：好，分三步走——
       1) 先拉轨迹列表（GET $ADP/api/v1/agents/xxx_agent/traces），共 N 条，
          conv id 形如 <hash>:train:0:0 / <hash>:val:1:0……
       2) 逐条取清洗结果（GET .../cleaned-traces/{conv_id}），拿到
          task_input（首条用户输入）和 messages。
       3) 把 messages 喂 generate-goal（POST $OPT/evaluate/generate-goal），
          生成 expected_behavior。
       拼成 golden_data jsonl 后，还需要 scp 到优化器服务器白名单目录
       （/tmp/evo_agent、/data/evo_agent、/home/evolution/evoagent-studio 三选一）
       才能用于 /optimize——这一步要你在服务器侧操作，我不代传文件。
       黄金数据集攒好了（X 条 train / Y 条 val），路径在哪？我帮你跑优化。
```

注意事项：

- conv id 形如 `<hash>:<train|val>:<i>:<j>`，后缀 `train`/`val` 直接作 golden_data 的 `case_type`
- `task_input`（首条用户输入）→ golden_data 的 `inputs.query`
- `expected_behavior` 来源优先级：用户直接给标签 > `generate-goal` 自动生成
- 拼成 jsonl 后，**仍需 scp 到优化器服务器白名单目录**才能用于 `/optimize`；skill 不代传文件

### 6.4 黄金数据集结构：golden_data

适用于：构造黄金数据集或提交优化时，输入/返回支持的标准数据结构。

```json
{
  "id": "<conv_id>",
  "inputs": {"query": "<task_input，首条用户输入>"},
  "expected_behavior": "<期望行为文本>",
  "case_type": "train"
}
```

| 子字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 用例 ID，建议用 conv id |
| `inputs.query` | string | 首条用户输入 |
| `expected_behavior` | string | 期望行为（可由 `generate-goal` 生成） |
| `case_type` | string | `train` 或 `val`，决定分片 |

> `/optimize` 的 `dataset_path` 文件须是此结构每行一条的 jsonl，且文件须已存在于优化器服务器白名单三目录之一。

### 6.5 流式或实时观察

适用于：实时观察批量评估或优化任务进度。

```text
用户：刚才那个优化任务 opt-xyz789，我想实时看进度。

agent：（内部 GET $OPT/optimize/opt-xyz789/stream，SSE）
       event: progress
       data: {"current_epoch":1,"total_epochs":1,"val_score":0.82,"edits_applied":3}
       ……
       event: done
       data: {"status":"completed"}
       任务已完成。批量评估的实时进度同理，走 /evaluate/dataset/jobs/{job_id}/stream。
```

> 轮询更简单，优先用轮询（每 ~8 秒查状态接口）；SSE 用于需要实时推送的场景。优化任务 SSE 支持 `Last-Event-ID` 重放，已完成任务重放后立即结束。

### 6.6 回滚

优化器即便基线胜出也可能回写改动版（甚至移除合规护栏）。回滚以自然语言触发，agent 内部用 adapter 写回：

```text
用户：刚才那轮优化把合规护栏改没了，回滚到 baseline 吧。

agent：（内部 POST $ADP/api/v1/skills：action=update_skill, skill_name=xxx_skill,
        skill_content=<优化前存好的 baseline 全文，编号 baseline-001>）
       已写回 baseline 全文。重读 skill_content 与 baseline 比对一致，确认恢复。
```

回滚要点：
1. 用优化前步骤存好的 baseline 全文（读 baseline 时就应存一份并编号）
2. 通过 `update_skill` 写回原文
3. 重读 `skill_content` 与 baseline 比对，确认恢复

---

## 7. 常见问题

### 7.1 故障排查表

| 现象 | 原因 | 处理方式 |
|---|---|---|
| `/evaluate` 报缺 `llm_config` | skill 不内置，未传真实值 | 用户提供 `model_name`+`api_key`+`api_base`，不要传 `null` |
| `/evaluate/dataset` 422 缺字段 | 未带 multipart `file`/`config` 或 `config.groups` | 按 5.2 补全 `file` 与 `config`（含 `groups`） |
| `/optimize` 返回 422 `Dataset file not found` | `dataset_path` 不在白名单或文件不存在 | 改路径或 scp 文件到 `/tmp/evo_agent`/`/data/evo_agent`/`/home/evolution/evoagent-studio` |
| `/optimize` 返回 422 `missing` | 请求体缺必填字段 | 按模板补全 `agent_name`/`optimizer_template`/`evaluator_template`/`dataset_path` |
| `/optimize` 502/连不上 | 优化器服务没起 | 联系维护方 |
| `trajectory_path` 找不到文件 | 用了本地路径 | 必须是优化器服务器侧绝对路径；本地文件先 scp 到白名单目录 |
| 数据集评估/优化超时仍 `running` | 单 agent 调用慢（timeout 300s） | 继续轮询或稍后带 `job_id` 查 |
| 查不到 job 进度 | 未带 `job_id` | 提交时返回的 `job_id` 必须记住 |
| 攒出的黄金数据集跑不了 `/optimize` | 文件未落到白名单目录 | 先 scp 到三目录之一且文件存在 |
| skill 没生效 / agent 用旧版 | 平台导入是拷贝非软链 | 重新同步副本（`cp` 覆盖对应 `SKILL.md`） |
| `update_skill` 后内容没变 | `agent_name`/`skill_name` 错误 | 确认名称，重读 `skill_content` 验证 |
| 优化后合规护栏被删 | 评估器 compliance 权重过低 | 调高 `evaluator_template.prompt` 的 compliance 权重或加硬过滤，并从 baseline 回滚 |
| adapter 返回 skill 列表为空 | agent 名错误或未配置 | 确认 `agent_name`，查 `/api/v1/config/agents` |
| 会话里 `$OPT`/`$ADP` 为空 | 地址未注入会话环境 | 见 3.3.1 先确认两个地址 |
| 优化任务幂等拒绝 | `task_name` 与已有任务重复 | 换 `task_name`（带时间戳）重跑 |

> **幂等拒绝**：优化器按 `task_name` 去重，同一个 `task_name` 再次提交会被拒绝（返回冲突），防止重复跑同名任务。所谓「换名重跑」即改一个新 `task_name`（如加时间戳）再提交即可。

### 7.2 常见问答

#### Q：评估模块和优化模块能否独立使用？

**结论：可以，两者互为独立模块（各是一个 skill）。**

skill-evaluator 只做评估（打分/攒数据集），不提交优化、不回写；skill-optimizer 只做优化（提交 `/optimize`/回写验证），不跑独立 `/evaluate`。合并即完整自进化流程，也可单独使用其中一个。

#### Q：`/evaluate` 的 `llm_config` 为什么每次都要传？

**结论：评估模块的端点强制要求 LLM 配置，且 skill 不内置，需用户提供。**

`model_name`+`api_key`+`api_base` 必填，其余有默认。`/optimize` 则不要求 skill 侧传 LLM 配置（优化器自带模型配置），不向用户索取。

#### Q：本地文件能直接用吗？

**结论：不能，skill 不代传本地文件。**

`trajectory_path` 和 `dataset_path` 都必须是优化器服务器上的绝对路径；本地文件需先 scp/rsync 到白名单三目录之一。批量评估 `/evaluate/dataset` 的 `file` 字段例外，随 multipart 上传，无需落地服务器。

#### Q：优化器有没有「列出历史任务」接口？

**结论：没有，查进度必须带 `job_id`。**

`job_id` 在提交 `/optimize` 后返回（注意是 `job_id` 不是 `id`），务必记住或告诉用户。

---

## 附录 A：默认评估 prompt（严格版三维度评分）

> 以下默认模板供 agent 直接引用，避免每次重记；用户可自定义覆盖。

`/evaluate` 的 `prompt_template` 与 `/optimize` 的 `evaluator_template.prompt` 默认均用以下文本（用户可自定义）：

```text
你是一个 Agent 轨迹评估专家。基于上下文、预期结果、可选 skill 列表和完整轨迹消息，对该轨迹进行客观、可复核的评估。
## 评估维度
1. task_completion：任务完成度
2. trajectory_quality：轨迹质量
3. compliance：合规性
## 评分
每维度取 {1.0, 0.75, 0.5, 0.25, 0.0}。
score = task_completion*0.5 + trajectory_quality*0.3 + compliance*0.2
is_pass=true 当且仅当 score >= 0.75。
## 输出（仅 JSON）
{"task_completion":1.0,"trajectory_quality":1.0,"compliance":1.0,"is_pass":true,"score":1.0,"attributed_skill":"","reason":"..."}
```

`/evaluate` 的 `llm_config` 默认结构（`model_name`/`api_key`/`api_base` 须由用户提供真实值）：

```json
{
  "model_name": "{MODEL_NAME}",
  "api_key": "{API_KEY}",
  "api_base": "{API_BASE}",
  "client_provider": "OpenAI",
  "temperature": 0.1,
  "max_tokens": 2048,
  "verify_ssl": false
}
```

## 附录 B：`/optimize` 默认请求模板

> 以下默认模板供 agent 直接引用，避免每次重记。

提交优化时，agent 内部按以下结构构造请求体（`{}` 占位由实际值替换，`num_epochs` 默认为 1）：

```json
{
  "task_name": "<agent>-skill-optimize-<时间戳>",
  "agent_name": "{AGENT_NAME}",
  "optimizer_type": "skill",
  "skills": ["{SKILL_NAME}"],
  "dataset_path": "{服务器侧 golden_data jsonl 绝对路径}",
  "optimizer_template": {
    "name": "通用 skill 优化器",
    "scenario": "{AGENT_NAME}",
    "rollout": {"extra_data": {}},
    "train_split": 0.6,
    "val_split": 0.4,
    "hyperparams": {
      "edit_budget": 8,
      "parallelism": 4,
      "update_mode": "patch",
      "accumulation": 1,
      "num_parallel": 4,
      "num_epochs": 1,
      "minibatch_size": 4,
      "scheduler_mode": "constant",
      "use_meta_skill": false,
      "score_threshold": 0.8,
      "use_slow_update": false,
      "default_batch_size": 4
    }
  },
  "evaluator_template": {
    "name": "通用严格版",
    "scenario": "通用",
    "type": "llm",
    "prompt": "<附录 A 的默认评估 prompt 全文>"
  }
}
```
