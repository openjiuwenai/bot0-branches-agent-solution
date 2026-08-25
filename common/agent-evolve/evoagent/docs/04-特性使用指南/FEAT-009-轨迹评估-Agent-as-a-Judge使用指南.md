# 轨迹评估 - Agent-as-a-Judge 使用指南

> 本指南介绍如何使用 **Agent-as-a-Judge** 评估器，通过真实编码 Agent CLI（claude / codex / jiuwenswarm）以子进程方式对一条 Agent 执行轨迹做多维度评判，并产出复数 Skill 归因与确定性总分。

---

## 1. 特性概览

### 1.1 这是什么

> **Agent-as-a-Judge** 通过"用真实编码 Agent 做裁判"的方式，对一条 Agent 执行轨迹进行多维度质量评估和 Skill 归因分析。

一次评估任务会依次完成：

1. 读取并压缩待评估的 Agent 执行轨迹；
2. 为每个评估维度（最多 5 个）并行启动一个编码 Agent 子进程，各自独立打分；
3. 启动一个归因 Agent 子进程，综合所有维度判定，分析哪些 Skill 对结果产生了影响；
4. 由确定性评分脚本（WeightScorer）计算最终总分；
5. 返回每个维度的分数、归因结果和是否通过的结论。

当前版本一次评估一条轨迹。评估器由 HTTP API 调用，不参与优化管线（训练 rollout / 验证门禁仍使用 `LLMEvaluator`）。

### 1.2 与相近特性的区别

| 特性 | 评估方式 | 什么时候使用 |
|---|---|---|
| Agent-as-a-Judge | 真实编码 Agent CLI 子进程做多维判定 + 归因 | 需要深度评估轨迹质量、分析 Skill 使用效果 |
| LLM Evaluator | LLM 调用打分（支持会话式多轮调用） | 优化管线中的快速评分（训练 / 验证门禁） |
| Metric Evaluator | 确定性规则匹配 | 结果可精确匹配的场景（如 JSON 分类） |

> Agent-as-a-Judge 的核心差异：评判和归因全部由编码 Agent 子进程驱动（Agent + prompt + skill），无需每次请求传 LLM 配置；总分由确定性脚本计算，可复现。

### 1.3 本指南覆盖范围

本指南包含：

- 开始前需要准备的信息和环境；
- 一次完整评估的操作步骤；
- 常用请求参数和返回结果；
- 评估维度和评分机制说明；
- 常见问题处理。

本指南不包含：

- EvoAgent 服务的部署过程；
- claude / codex / jiuwenswarm CLI 的安装与配置；
- 优化管线（Prompt 优化 / Skill 优化）的使用方法。

### 1.4 评估管线概览

Agent-as-a-Judge 采用两阶段管线：

| 阶段 | 执行者 | 输入 | 输出 |
|---|---|---|---|
| 阶段一：多维度评判 | 每维度一个编码 Agent 子进程（并行） | 轨迹摘要 + 维度说明 + 评分标尺 | `DimensionJudgment`（分数 + 推理） |
| 阶段二：归因分析 | 一个归因 Agent 子进程 | 全部维度判定 + Skill 列表 + Skill 文档 | `SkillAttribution[]`（复数归因） |
| 评分 | 评估器侧确定性脚本（非 LLM） | 维度分数 + 预设权重 | `overall_score`（0-1） |

---

## 2. 什么时候使用

| 使用 Agent-as-a-Judge | 不使用 Agent-as-a-Judge |
|---|---|
| 需要深度分析一条轨迹的执行质量 | 只需快速判断结果是否正确 |
| 需要知道"哪些 Skill 导致了问题" | 不需要归因，只需总分 |
| 需要多维度评估（任务完成度、轨迹质量、安全性等） | 评估标准单一，可用规则匹配 |
| 已安装并配置了编码 Agent CLI（claude / codex / jiuwenswarm） | 当前环境不允许运行子进程 |
| 对评估可复现性有要求（总分确定性计算） | 评估在优化管线内，需要快速迭代 |

> 简单判断：如果"要评估的是一条 Agent 执行轨迹，并且需要多维度深度分析和 Skill 归因"，就适合使用 Agent-as-a-Judge。

---

## 3. 准备工作

### 3.1 获取必要信息

开始前，请向平台或部署维护人员确认以下信息：

| 信息 | 示例 | 用途 |
|---|---|---|
| EvoAgent 地址 | `http://evoagent.example.com` | 提交和查询评估任务 |
| 编码 Agent CLI | `claude` / `codex` / `jiuwenswarm` | 选择评判所用的运行时 |
| API Key | `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | 编码 Agent 子进程认证 |
| 评估 preset | `default` | 系统内置，无需额外配置；在请求时通过 `preset` 字段选择。可选值及说明见 3.6 节 |
| 轨迹文件路径 | `/data/trajectories/case-001.json` | EvoAgent 服务端读取轨迹 |

后续示例中的 `{...}` 都需要替换为实际值。

### 3.2 检查服务

检查 EvoAgent：

```bash
curl "{EVOAGENT_BASE_URL}/health"
```

检查编码 Agent CLI（以 claude 为例）：

```bash
claude --version
```

> 如果任一检查失败，请先联系部署维护人员或安装对应的 CLI 工具，不要继续提交评估任务。

### 3.3 准备轨迹数据

轨迹文件使用 JSON 格式，至少包含 `messages` 字段：

| 字段 | 说明 |
|---|---|
| `messages` | Agent 与用户之间的完整对话消息列表 |
| `summary` | 轨迹摘要（可选，用于快速预览） |

示例：

```json
{
  "messages": [
    {"role": "user", "content": "请帮我查询北京明天的天气。"},
    {"role": "assistant", "content": "好的，我来调用天气查询工具。", "tool_calls": [...]},
    {"role": "tool", "content": "{\"temp\": 22, \"weather\": \"晴\"}"},
    {"role": "assistant", "content": "北京明天天气晴朗，气温 22°C。"}
  ]
}
```

建议：

- 轨迹应包含完整的工具调用和返回结果；
- 消息格式需符合 `StandardTrajectory` 规范；
- 轨迹文件需要提前放到 EvoAgent 服务端允许访问的目录中；
- 提交请求时填写的是**服务端路径**，不是客户电脑上的文件路径。

### 3.4 检查环境

使用 claude 运行时，确认 API Key 已配置：

```bash
echo $ANTHROPIC_API_KEY
```

使用 codex 运行时，确认 API Key 已配置：

```bash
echo $OPENAI_API_KEY
```

使用 jiuwenswarm 运行时，确认 `acp_agents` 配置中包含目标 profile：

```bash
# 确认 jiuwenswarm 已安装
python3 -c "from jiuwenswarm.common.config import get_config; print(get_config().get('acp_agents'))"
```

### 3.5 了解评估维度

Agent-as-a-Judge 内置 5 个评估维度：

| 维度 | 标识 | 评估内容 |
|---|---|---|
| 任务完成度 | `task_completion` | 核心目标是否达成、最终结果是否正确 |
| 轨迹质量 | `trajectory_quality` | 步骤是否清晰、工具调用是否恰当、有无冗余 |
| 安全性 | `safety` | 是否存在危险操作、敏感信息泄露、越权访问 |
| 回答忠实度 | `answer_faithfulness` | 回答是否忠于轨迹证据、有无幻觉或编造 |
| 规划合理性 | `planning_rationality` | 任务拆解是否合理、决策质量如何 |

每个维度独立打分（0-1），互不重复扣分。

### 3.6 了解评估 Preset

Preset 是一组预设的评估配置，决定使用哪些维度、权重、运行时和工具白名单：

| Preset | 运行时 | 特点 |
|---|---|---|
| `default` | claude | 5 维度等权、task_completion 门控评分 |
| `codex_default` | codex | 5 维度等权、task_completion 门控评分 |
| `safety_focus` | claude | 安全维度权重 0.35，适合安全敏感场景 |
| `jiuwenswarm_default` | jiuwenswarm | 5 维度等权、通过 ACP 协议驱动 |

> preset 可通过请求参数覆盖部分字段（如 `runtime`、`scorer`），无需自定义 preset 即可调整行为。

---

## 4. 快速上手

本节只需要准备一个请求文件，并执行两类命令：提交任务、查询结果。

### 4.1 准备请求

新建文件 `agent-judge-request.json`：

```json
{
  "trajectory_path": "{TRAJECTORY_PATH}",
  "preset": "default",
  "skill_names": ["weather_query", "city_search"],
  "dimension_thresholds": {
    "task_completion": 0.6,
    "trajectory_quality": 0.5,
    "safety": 0.8,
    "answer_faithfulness": 0.6,
    "planning_rationality": 0.5
  }
}
```

需要替换：

| 占位内容 | 填写方式 |
|---|---|
| `{TRAJECTORY_PATH}` | 填写 EvoAgent 服务端的轨迹文件路径 |

需要按需调整：

| 字段 | 调整方式 |
|---|---|
| `preset` | 选择 `default`、`codex_default`、`safety_focus` 或 `jiuwenswarm_default` |
| `skill_names` | 填写待归因的 Skill 名称列表（至少 1 个） |
| `dimension_thresholds` | 为每个维度设定通过阈值（0-1）；`is_pass` = 全部维度 ≥ 各自阈值 |

`skill_names` 是归因的候选范围——归因 Agent 只会分析这些 Skill 对轨迹结果的影响。不在列表中的 Skill 不会被归因。

`dimension_thresholds` 决定 `is_pass` 的判定：任何维度低于其阈值，`is_pass` 即为 `false`。

### 4.2 提交评估任务

```bash
curl --request POST "{EVOAGENT_BASE_URL}/evaluate/agent-judge" --header "Content-Type: application/json" --data-binary "@agent-judge-request.json"
```

成功时返回：

```json
{
  "job_id": "a1b2c3d4e5f6",
  "status": "queued"
}
```

请复制并保存 `job_id`。

### 4.3 查询任务

```bash
curl "{EVOAGENT_BASE_URL}/evaluate/agent-judge/jobs/{JOB_ID}"
```

任务运行时会返回：

```json
{
  "job_id": "a1b2c3d4e5f6",
  "status": "running",
  "progress": {
    "phase": "judge",
    "done": 3,
    "total": 5
  },
  "result": null,
  "error": null
}
```

每隔几秒重新执行一次查询命令，直到状态变为：

- `completed`：评估完成；
- `failed`：评估失败；
- `cancelled`：任务已取消。

### 4.4 验证是否成功

任务完成后，重点查看以下内容：

| 字段 | 检查内容 |
|---|---|
| `status` | 应为 `completed` |
| `result.score` | 总分（0-1），由确定性评分脚本计算 |
| `result.is_pass` | 是否全部维度达到阈值 |
| `result.dimensions` | 每个维度的分数 |
| `result.dimension_checks` | 每个维度的阈值通过情况 |
| `result.skill_attributions` | 复数 Skill 归因列表 |
| `result.attribution_status` | 归因状态：`completed` 或 `failed` |
| `result.per_metric` | `dimensions` 的别名（兼容旧版本），新代码建议直接使用 `dimensions` |

示例完成结果：

```json
{
  "job_id": "a1b2c3d4e5f6",
  "status": "completed",
  "result": {
    "score": 0.72,
    "is_pass": true,
    "dimensions": {
      "task_completion": 0.85,
      "trajectory_quality": 0.70,
      "safety": 1.0,
      "answer_faithfulness": 0.65,
      "planning_rationality": 0.60
    },
    "dimension_checks": [
      {"dimension": "task_completion", "score": 0.85, "threshold": 0.6, "pass": true},
      {"dimension": "trajectory_quality", "score": 0.70, "threshold": 0.5, "pass": true},
      {"dimension": "safety", "score": 1.0, "threshold": 0.8, "pass": true},
      {"dimension": "answer_faithfulness", "score": 0.65, "threshold": 0.6, "pass": true},
      {"dimension": "planning_rationality", "score": 0.60, "threshold": 0.5, "pass": true}
    ],
    "skill_attributions": [
      {
        "skill_name": "weather_query",
        "usage_status": "executed",
        "impact": "positive",
        "reason": "正确调用了天气查询工具并解析返回结果"
      },
      {
        "skill_name": "city_search",
        "usage_status": "not_executed",
        "impact": "none",
        "reason": "轨迹中未涉及城市搜索功能"
      }
    ],
    "attribution_status": "completed",
    "attribution_error": null,
    "attributed_skill": "weather_query"
  }
}
```

> `completed` 表示评估流程完成。`is_pass` 表示全部维度达到阈值。归因结果需结合 `skill_attributions` 和 `dimensions` 一起判断问题所在。

---

## 5. 接口与配置

### 5.1 接口清单

| 方法 | 路径 | 作用 |
|:--:|---|---|
| `POST` | `/evaluate/agent-judge` | 提交一条轨迹的评估任务 |
| `GET` | `/evaluate/agent-judge/jobs/{job_id}` | 查询任务状态和结果 |
| `GET` | `/evaluate/agent-judge/jobs/{job_id}/stream` | SSE 实时查看进度 |

### 5.2 核心请求参数

| 字段 | 必填 | 说明 |
|---|:--:|---|
| `trajectory_path` | 是 | EvoAgent 服务端的轨迹文件路径 |
| `preset` | 是 | 评估 preset 名称（如 `default`） |
| `skill_names` | 是 | 待归因的 Skill 名称列表（至少 1 个） |
| `dimension_thresholds` | 是 | 每个维度的通过阈值（0-1 映射） |
| `expected_result` | 否 | 预期结果，供评判参考 |
| `runtime` | 否 | 覆盖 preset 的运行时（`claude` / `codex` / `jiuwenswarm`） |
| `tool_allowlist` | 否 | 覆盖 preset 的工具白名单。默认值因 preset 而异：`default` / `codex_default` / `safety_focus` 为 `(Read, Grep, Bash)`；`jiuwenswarm_default` 为 `(Read, Grep)` |
| `skill_source` | 否 | Skill 文档来源：`local` / `adapter` / `none`（默认 `none`） |
| `skill_root` | 否 | 本地 Skill 根目录（`skill_source="local"` 时使用） |
| `max_concurrent` | 否 | 最大并发子进程数（覆盖 preset 值） |
| `run_timeout` | 否 | 单个子进程超时秒数（覆盖 preset 值） |
| `keep_on_error` | 否 | 出错时保留工作目录用于调试（默认 `false`） |
| `extra_env` | 否 | 传递给子进程的额外环境变量 |
| `agent_profile` | 否 | jiuwenswarm 的 agent profile 名称（仅 jiuwenswarm 运行时） |
| `trajectory_budget` | 否 | 压缩轨迹的最大 token 预算（默认 4000；长轨迹需增大） |

> `dimension_thresholds` 是必填字段。`is_pass` 的判定逻辑：所有维度分数 ≥ 各自阈值。

### 5.3 常用评估参数

| 字段 | 默认值 | 说明 |
|---|---|---|
| `preset` | — | 选择维度 / 权重 / 运行时 / 评分器的组合 |
| `runtime` | preset 内置 | 覆盖 preset 的运行时选择 |
| `max_concurrent` | `6` | 维度评判子进程的最大并发数 |
| `run_timeout` | `300` | 单个子进程超时秒数 |
| `trajectory_budget` | `4000` | 轨迹压缩的 token 预算；长轨迹需增大 |
| `keep_on_error` | `false` | 出错时保留工作目录，便于调试 |

评估器类型说明：

- Agent-as-a-Judge 始终使用 `type: "agent"` 评估器；
- 总分由确定性评分脚本计算（`weighted_sum` 或 `task_completion_gated`），不依赖 LLM；
- 评判和归因由编码 Agent 子进程完成，认证通过子进程的环境变量传递。

首次使用建议使用 `default` preset + `claude` runtime，只调整 `dimension_thresholds`，其他参数保持默认。

#### 5.3.1 评分器说明

Agent-as-a-Judge 提供两种确定性评分器：

| 评分器 | 标识 | 计算方式 |
|---|---|---|
| 加权平均 | `weighted_sum` | `Σ(w·s) / Σw`，所有维度按权重加权平均 |
| 任务完成度门控 | `task_completion_gated` | `overall = tc × (其他维度加权平均)`；任务未完成则总分趋零 |

`task_completion_gated` 是默认评分器。其核心思想：任务完成度是一个乘法门控——如果核心目标没有达成，无论轨迹多优雅、多安全，总分都趋近于零。

当 `task_completion` 维度不在评判结果中时，`task_completion_gated` 自动退化为 `weighted_sum`。

#### 5.3.2 轨迹压缩

长轨迹在送入评判子进程前会被压缩，以适应 token 预算：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `trajectory_budget` | `4000` | 压缩后轨迹的最大 token 数 |

压缩策略：保留消息结构和工具调用关键信息，截断冗长的工具返回内容。压缩后的摘要写入 `trajectory.md`，全量原文保留在 `trajectory.jsonl`，评判子进程可通过 `Read` / `Grep` 工具按需读取。

如果轨迹过长，压缩后仍超出预算，评估将失败并返回 `prompt_budget_exceeded` 错误。此时应增大 `trajectory_budget`（如设为 `8000` 或 `16000`）。

### 5.4 结果说明

| 字段 | 说明 |
|---|---|
| `score` | 总分（0-1），由确定性评分脚本计算 |
| `is_pass` | 全部维度 ≥ 各自阈值时为 `true` |
| `dimensions` | 每个维度的分数映射 `{dimension_name: score}` |
| `dimension_checks` | 每个维度的阈值通过情况列表 |
| `skill_attributions` | 复数 Skill 归因列表 |
| `attribution_status` | 归因状态：`completed` 或 `failed` |
| `attribution_error` | 归因失败时的错误原因 |
| `attributed_skill` | 主要归因 Skill（top-1，兼容旧契约） |
| `per_metric` | `dimensions` 的别名（兼容旧版本），新代码建议直接使用 `dimensions` |
| `reason` | 完整的 JSON 序列化评估详情 |

归因结果中每个 `SkillAttribution` 包含：

| 字段 | 说明 |
|---|---|
| `skill_name` | Skill 名称（必须在请求的 `skill_names` 中） |
| `usage_status` | 使用状态：`executed` / `not_executed` / `misused` / `unknown` |
| `impact` | 影响方向：`positive` / `negative` / `neutral` / `none` |
| `reason` | 归因推理，引用轨迹中的具体证据 |

### 5.5 状态与常见错误

| 状态或错误 | 含义 | 处理方式 |
|---|---|---|
| `queued` | 已接收，等待运行 | 稍后查询 |
| `running` | 正在评估 | 继续查询 |
| `completed` | 评估完成 | 按 4.4 节验收 |
| `failed` | 评估失败 | 查看 `error` 字段 |
| `cancelled` | 任务已被取消（如超时自动取消或手动取消） | 检查取消原因，确认后可重新提交 |
| HTTP `422` | 请求参数错误 | 检查轨迹路径、preset 名称和必填字段 |
| `agent_judge_binary_missing` | 编码 Agent CLI 未安装 | 安装对应 CLI 并配置 PATH |
| `agent_judge_timeout` | 子进程运行超时 | 增大 `run_timeout` 或检查网络 |
| `agent_judge_output_error` | Agent 输出无法解析 | 检查 CLI 版本兼容性 |
| `prompt_budget_exceeded` | 轨迹超出 token 预算 | 增大 `trajectory_budget` |
| `attribution_unknown_skill` | 归因结果包含未知 Skill | 检查 `skill_names` 是否完整 |
| `schema_validation_error` | Agent 输出不符合 schema | 检查 CLI 的 structured output 支持 |
| `trace_unavailable` | 轨迹数据为空或格式错误 | 检查轨迹文件的 `messages` 字段 |
| `agent_judge_dim_failed` | 某个评估维度的评判子进程执行失败 | 查看 `dimensions` 中对应维度的详情；可增大 `run_timeout` 后重试 |
| `agent_judge_run_error` | 评判子进程运行异常（非超时） | 设置 `keep_on_error: true` 保留工作目录排查；检查 CLI 和 API Key 是否正常 |
| `agent_judge_config_error` | 评估配置无效（如 preset 不存在或参数冲突） | 检查 `preset` 名称和请求参数是否合法 |
| `rollout_error` | 优化管线中的 rollout 阶段出错 | 仅在优化管线场景下出现，检查 rollout 配置 |

---

## 6. 场景用例

### 6.1 基础用例：评估一条 Agent 轨迹

适用于：对一条 Agent 执行轨迹做 5 维度全面评估和 Skill 归因。

请求中的关键字段：

```json
{
  "trajectory_path": "/data/trajectories/case-001.json",
  "preset": "default",
  "skill_names": ["weather_query", "city_search"],
  "dimension_thresholds": {
    "task_completion": 0.6,
    "trajectory_quality": 0.5,
    "safety": 0.8,
    "answer_faithfulness": 0.6,
    "planning_rationality": 0.5
  }
}
```

完成后重点分析：

- `dimensions` 中哪个维度分数最低；
- `skill_attributions` 中哪个 Skill 的 `impact` 为 `negative`；
- `dimension_checks` 中哪个维度未通过阈值；
- 归因 `reason` 中引用了哪些轨迹证据。

### 6.2 安全优先评估

适用于：对安全敏感场景（如涉及删除操作、敏感数据访问）的轨迹，提高安全维度权重。

请求中的关键字段：

```json
{
  "trajectory_path": "/data/trajectories/case-002.json",
  "preset": "safety_focus",
  "skill_names": ["data_access", "file_manager"],
  "dimension_thresholds": {
    "task_completion": 0.5,
    "trajectory_quality": 0.4,
    "safety": 0.9,
    "answer_faithfulness": 0.6,
    "planning_rationality": 0.4
  }
}
```

与基础用例的区别：

| 字段 | 基础用例 | 安全优先 |
|---|---|---|
| `preset` | `default` | `safety_focus` |
| safety 权重 | 0.2（等权） | 0.35 |
| safety 阈值 | 0.8 | 0.9 |

### 6.3 使用 Codex 运行时

适用于：环境中安装了 codex CLI 而非 claude CLI。

请求中的关键字段：

```json
{
  "trajectory_path": "/data/trajectories/case-003.json",
  "preset": "codex_default",
  "skill_names": ["code_review"],
  "dimension_thresholds": {
    "task_completion": 0.6,
    "trajectory_quality": 0.5,
    "safety": 0.8,
    "answer_faithfulness": 0.6,
    "planning_rationality": 0.5
  }
}
```

也可以在任何 preset 上覆盖运行时：

```json
{
  "trajectory_path": "/data/trajectories/case-003.json",
  "preset": "default",
  "runtime": "codex",
  "skill_names": ["code_review"],
  "dimension_thresholds": { ... }
}
```

### 6.4 处理长轨迹

适用于：轨迹消息较多或工具返回较冗长，默认 token 预算不够。

请求中增加 `trajectory_budget`：

```json
{
  "trajectory_path": "/data/trajectories/long-case.json",
  "preset": "default",
  "skill_names": ["complex_workflow"],
  "trajectory_budget": 16000,
  "dimension_thresholds": { ... }
}
```

> 默认预算为 4000 token。对于包含大量工具返回的长轨迹，建议设为 8000-16000。如果评估失败并返回 `prompt_budget_exceeded`，继续增大该值。

### 6.5 实时查看进度

评估过程中可以实时查看进度：

```bash
curl --no-buffer "{EVOAGENT_BASE_URL}/evaluate/agent-judge/jobs/{JOB_ID}/stream"
```

该接口使用 SSE（Server-Sent Events）持续返回事件：

| 事件类型 | 含义 |
|---|---|
| `progress` | 评判进度：`{phase, done, total}` |
| `completed` | 评估完成，携带完整结果 |
| `error` | 评估失败，携带错误信息 |
| `keepalive` | 心跳保活（每 30 秒） |

支持 `Last-Event-ID` 请求头重放历史事件，适用于断线重连场景。

### 6.6 调试评估失败

当评估失败时，可以保留工作目录进行调试：

```json
{
  "trajectory_path": "/data/trajectories/case-001.json",
  "preset": "default",
  "skill_names": ["weather_query"],
  "keep_on_error": true,
  "dimension_thresholds": { ... }
}
```

也可以通过环境变量全局开启：

```bash
EVO_DEBUG_AGENT_JUDGE_WORKDIR=1
```

保留的工作目录包含：

| 文件 | 说明 |
|---|---|
| `trajectory.jsonl` | 全量轨迹原文 |
| `trajectory.md` | 压缩后的轨迹摘要 |
| `schema.json` | 维度评判的 JSON Schema |
| `attribution_output.schema.json` | 归因输出的 JSON Schema |
| 各 evaluator skill `.md` | 随附的辅助文档 |

### 6.7 附带 Skill 文档进行归因

适用于：归因 Agent 需要参考 Skill 文档内容才能准确判断 Skill 使用情况。

```json
{
  "trajectory_path": "/data/trajectories/case-001.json",
  "preset": "default",
  "skill_names": ["weather_query", "city_search"],
  "skill_source": "local",
  "skill_root": "/data/skills",
  "dimension_thresholds": { ... }
}
```

`skill_source` 支持三种来源：

| 来源 | 说明 |
|---|---|
| `none` | 不提供 Skill 文档，仅依据 Skill 名称和维度推理归因 |
| `local` | 从 `skill_root` 指定的本地目录读取 Skill 文档 |
| `adapter` | 从 Adapter 服务获取 Skill 文档 |

---

## 7. 常见问题

### 7.1 故障排查表

| 现象 | 常见原因 | 处理方式 |
|---|---|---|
| 提交后立即返回 `422` | 缺少必填字段或轨迹文件不存在 | 对照 5.2 节检查请求；确认轨迹路径为服务端路径 |
| 提示 `agent_judge_binary_missing` | 编码 Agent CLI 未安装 | 安装 claude / codex CLI 并配置 PATH |
| 评估超时 | 子进程运行时间超过 `run_timeout` | 增大 `run_timeout`；检查网络和 API Key 有效性 |
| 提示 `prompt_budget_exceeded` | 轨迹太长，压缩后仍超预算 | 增大 `trajectory_budget` |
| `attribution_status` 为 `failed` | 归因 Agent 无法确定 Skill 归因 | 正常保护机制；查看 `attribution_error` 了解原因 |
| 某个维度分数为 0 | Agent 在该维度表现极差或输出解析失败 | 查看 `dimensions` 和归因 `reason` 中的证据 |
| `is_pass` 为 `false` 但总分较高 | 某个高阈值维度未达标 | 查看 `dimension_checks` 定位未通过的维度 |
| 归因结果中 `usage_status` 全部为 `unknown` | Skill 文档未提供或归因 Agent 无法读取轨迹 | 配置 `skill_source` 或增大 `trajectory_budget` |

### 7.2 常见问答

#### Q：`skill_names` 可以传多少个？

**结论：至少 1 个，无硬性上限。**

`skill_names` 是归因的候选范围。归因 Agent 会分析每个候选 Skill 的使用状态和影响方向。建议传入与当前轨迹相关的所有 Skill，避免遗漏。

#### Q：`attributed_skill` 和 `skill_attributions` 有什么区别？

**结论：`attributed_skill` 是 top-1 主要归因，`skill_attributions` 是完整复数归因。**

`attributed_skill` 只返回一个 Skill 名称（优先选择 `impact` 为 `positive` 或 `negative` 的 Skill），用于兼容旧契约。`skill_attributions` 返回完整的归因列表，包含每个 Skill 的使用状态和影响分析。

#### Q：`is_pass` 和 `score` 的关系是什么？

**结论：两者独立。`score` 是总分，`is_pass` 是阈值判定。**

`score` 由评分器确定性计算（所有维度分数的加权平均或门控乘法）。`is_pass` 判定每个维度是否达到其阈值——即使总分很高，只要有一个维度低于阈值，`is_pass` 就是 `false`。

#### Q：评估结果可以复现吗？

**结论：总分完全可复现，维度评判和归因可能因 LLM 随机性略有波动。**

总分由确定性评分脚本（`WeightedSumScorer` 或 `TaskCompletionGatedScorer`）计算，相同的维度分数必然得到相同的总分。维度评判和归因由编码 Agent 子进程完成，存在 LLM 固有的随机性。

#### Q：如何选择合适的 `dimension_thresholds`？

**结论：根据业务场景和容忍度设定。**

建议：

- `task_completion`：0.6-0.8（核心业务场景建议 ≥ 0.7）
- `safety`：0.8-1.0（安全敏感场景建议 ≥ 0.9）
- `trajectory_quality`：0.4-0.6
- `answer_faithfulness`：0.5-0.7
- `planning_rationality`：0.4-0.6

首次使用建议设定较宽松的阈值，根据评估结果逐步收紧。

#### Q：评估耗时大概多久？

**结论：通常 30 秒到几分钟，取决于维度数量和轨迹长度。**

阶段一（多维度评判）是并行的，总耗时约等于最慢的单维度评判时间。阶段二（归因分析）在阶段一完成后串行执行。每个阶段都是一个编码 Agent 子进程调用。

增大 `max_concurrent` 可以提高阶段一的并行度（默认 6，通常已覆盖全部 5 个维度）。

#### Q：`extra_env` 怎么用？

**结论：用于传递 API Key 等环境变量给子进程。**

```json
{
  "extra_env": {
    "ANTHROPIC_API_KEY": "sk-ant-...",
    "CUSTOM_VAR": "value"
  }
}
```

`extra_env` 会合并到子进程的环境中（覆盖同名变量）。通常用于在部署环境中传递认证信息，而不在主机环境中暴露。

#### Q：评估失败后可以重试吗？

**结论：可以。使用相同请求重新提交即可。**

评估任务是幂等的——同一条轨迹的多次评估互不影响。如果失败原因是超时，可以增大 `run_timeout` 后重试。如果是 CLI 未安装，需要先安装对应工具。

#### Q：可以同时评估多条轨迹吗？

**结论：可以。为每条轨迹分别提交一个评估任务。**

每个评估任务是独立的异步 job，可以并行运行。当前版本一次评估一条轨迹，批量评估需要客户端循环提交。
