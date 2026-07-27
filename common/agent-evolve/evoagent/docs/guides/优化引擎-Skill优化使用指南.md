# 优化引擎-Skill优化使用指南

本文介绍如何使用 EvoAgent 优化引擎对 Agent 的 Skill 文档进行自动调优，包括能力说明、方式选择、周边服务对接、部署配置、CLI/API 调用、结果验证，以及面向开发人员的数据与存储说明。

完成本文操作后，用户应能独立完成一次 Skill 优化，并判断优化结果是否有效。

> 本文只介绍 `optimizer_type=skill` 的 Skill 优化。AgentRule 等 managed-doc 优化不在本文范围内。

## 1. 功能简介

### 1.1 功能说明

EvoAgent Skill 优化根据业务数据自动执行目标 Agent，获取运行轨迹，识别失败原因并修改 Skill 文档，再使用验证集决定是否保留候选修改。

完整流程如下：

```text
读取目标 Skill
    ↓
使用训练数据调用目标 Agent
    ↓
获取并评估运行轨迹
    ↓
把问题归因到相应 Skill
    ↓
生成并应用候选修改
    ↓
使用验证集比较候选版本和基线版本
    ↓
保留更优版本，或回退到本轮基线
```

### 1.2 支持能力

当前 Skill 优化支持：

- 优化一个或多个 Skill；
- SkillOpt 和 TF-GRPO 两种优化算法；
- LLM 和确定性 Metric 两类评估方式；
- CLI 同步运行；
- API 异步提交、状态查询、SSE 进度和协作式取消；
- 训练集与验证集切分；
- 每轮候选版本验证门控；
- Skill frontmatter 保留；
- 优化前后分数、逐 Skill 分数和内容快照；
- 轨迹、评估结果、门控结果等文件产物。

### 1.3 输出结果

一次成功优化通常会产生：

- 目标 Skill 的最终内容；
- 优化前后训练分数和通过率；
- 每轮验证分数；
- 每个 Skill 的独立分数；
- 每轮接受或拒绝候选版本的门控结果；
- 应用的编辑数量；
- 优化前内容和每轮内容快照；
- 可用于排障和审计的 artifact 目录。

### 1.4 使用边界

使用前应了解以下边界：

- EvoAgent 依赖 Adapter Sidecar 调用目标 Agent、读取轨迹和管理 Skill；
- 在线 Skill 优化不能直接读取任意业务日志；
- API 的数据文件必须对 EvoAgent 服务端可见；
- Skill 优化任务的完整状态当前保存在进程内存，服务重启后不能恢复执行；
- 验证分数取决于数据和评估规则，编辑数量不代表优化一定有效；
- 正式优化前应保存目标 Skill 的基线版本。

## 2. 可选使用方式

选择使用方式时，需要分别考虑调用入口、优化算法、评估器和轨迹来源。

### 2.1 调用入口

| 方式 | 适用场景 | 数据集入口 | 进度查看 | 任务管理 |
|---|---|---|---|---|
| CLI | 本地调试、现场验证、单次优化 | `dataset.yaml` | 控制台输出 | 当前进程 |
| API | 平台集成、常驻服务、异步运行 | 服务端 JSON/JSONL 路径 | 状态接口、SSE | Job API |

CLI 和 API 最终调用同一套优化编排流程。主要区别是参数来源、数据集入口和任务生命周期管理。

### 2.2 优化算法

| 算法 | 实现类 | 核心机制 | 典型用途 |
|---|---|---|---|
| SkillOpt | `optimizer.SkillOptOptimizer` | 评估轨迹、归因问题、反思失败、聚合并选择编辑，通过验证门控保留有效修改 | 默认选择；对已有 Skill 进行稳定、渐进式优化 |
| TF-GRPO | `optimizer.TfGrpoOptimizer` | 生成多组 Skill 变体，分别 rollout，通过组内比较、语义优势和经验库寻找优化方向 | 需要更强探索能力，且可接受更高 LLM 和 Agent 调用成本 |

两种算法在 API 中都使用：

```json
{
  "optimizer_type": "skill"
}
```

实际算法由场景配置中的 `optimizer_class` 决定：

```yaml
# SkillOpt
optimizer_class: optimizer.SkillOptOptimizer
```

```yaml
# TF-GRPO
optimizer_class: optimizer.TfGrpoOptimizer
```

### 2.3 评估方式

| 评估器 | 适用场景 | 优点 | 注意事项 |
|---|---|---|---|
| LLM | 开放式回答、工具调用、合规性和完整轨迹质量 | 能评估语义和行为 | 成本较高，结果受 Prompt 和模型稳定性影响 |
| Metric | 有确定答案的任务 | 快速、确定、成本低 | 不适合直接评估开放式自然语言行为 |
| Custom | CLI 中有特殊业务指标 | 可对接自定义逻辑 | 需要可导入的 Python Evaluator 类 |

API 当前内置的确定性指标包括：

- `exact_match`
- `normalized_exact_match`

### 2.4 轨迹获取方式

#### 在线优化轨迹

当前 Skill 优化采用在线拉取方式：

1. EvoAgent 通过 Adapter 调用目标 Agent；
2. 每个 case 使用确定的 conversation ID；
3. Agent 执行结束后，EvoAgent 调用 Adapter 的 `cleaned-traces` 接口；
4. Adapter 返回清洗后的消息轨迹；
5. 轨迹进入评估、归因和优化流程。

接口为：

```http
GET /api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}
```

#### 离线轨迹文件

独立评估接口支持通过服务器上的 `trajectory_path` 读取离线轨迹，但该能力属于轨迹评估，不是 Skill 优化任务的另一种输入方式。详见[评估 API 参考](api/evaluate-api.md)。

#### 当前未支持的方式

以下方式当前不能直接作为 Skill 优化输入：

- 直接扫描业务服务日志并自动还原轨迹；
- 业务 Agent 主动向 EvoAgent 上报轨迹；
- 在 `POST /optimize` 中直接嵌入完整轨迹；
- 使用 `trajectory_path` 替代优化数据集。

## 3. 方式选择与注意事项

### 3.1 调用入口选择

选择 CLI：

- 第一次接入；
- 需要直接观察完整日志；
- 验证 Adapter、LLM、数据集或场景配置；
- 只运行一次任务。

选择 API：

- 需要平台统一提交任务；
- 需要异步运行；
- 需要 SSE 进度；
- 需要取消任务；
- 需要前端展示报告。

建议先用 CLI 跑通单 Skill、单 epoch，再接入 API。

### 3.2 算法选择

选择 SkillOpt：

- 第一次优化；
- Skill 已有基本可用结构；
- 希望以小步编辑逐渐修正问题；
- 运行预算有限；
- 希望更容易解释每次修改。

选择 TF-GRPO：

- SkillOpt 已无法发现有效改进；
- 需要探索多种 Skill 写法；
- 数据和评估器能够稳定区分不同变体；
- Adapter、目标 Agent 和 LLM 有足够容量。

TF-GRPO 的主要成本大致随以下参数共同增长：

```text
group_size × cases_per_variant × num_epochs
```

首次运行不要同时放大这三个参数。

### 3.3 评估方式选择

使用 LLM 评估器：

- 期望结果是行为描述；
- 需要判断是否正确调用工具或 Skill；
- 需要判断合规性、完整性和交互过程；
- 不存在唯一标准答案。

使用 Metric：

- 输出有唯一或可归一化的标准值；
- 可从回答中提取确定字段；
- 需要稳定、低成本的自动验收。

自然语言行为描述通常不适合直接使用 exact match。

### 3.4 单 Skill与多 Skill

首次接入建议只优化一个 Skill。多 Skill 优化要求评估器能够把失败正确归因到具体 Skill，否则可能出现：

- 修改了与失败无关的 Skill；
- 某个 Skill 的问题被另一个 Skill 的分数掩盖；
- 多个 Skill 同时变化，难以定位回归来源。

单 Skill 跑通后，再逐步增加 `skills`。

### 3.5 数据注意事项

- `id` 必须唯一；
- 数据必须能触发目标 Skill；
- `expected_behavior` 应描述可观察行为；
- 训练集和验证集应覆盖不同表达；
- 验证集不能全部复制自训练集；
- 首次运行至少保证拆分后训练集和验证集都非空；
- 不要在数据中保存密钥、Token 或不必要的敏感信息；
- 应同时包含正常、失败和边界场景。

### 3.6 运行注意事项

- 首次运行使用单 Skill、`num_epochs=1` 和小 batch；
- 正式优化前保存原始 Skill；
- 不要只看训练分数，应同时看验证分数和最终 diff；
- 限流或超时时先降低并发，不要只增加重试次数；
- API 容器中的 `localhost` 指向容器自身，不一定是 Adapter 主机；
- 取消任务是协作式操作，已发出的远程调用可能不会立即终止；
- 取消或异常后应重新读取 Adapter 中的 Skill，确认实际生效版本。

## 4. 周边服务及依赖组件对接

### 4.1 整体依赖关系

```text
CLI / 调用平台
       │
       ▼
EvoAgent 优化引擎 ──────────► LLM 服务
       │
       ▼
Adapter Sidecar
       │
       ├────────► 目标 Agent
       │
       ├────────► Skill 存储/加载机制
       │
       └────────► 轨迹清洗与查询能力
```

依赖检查矩阵：

| 依赖 | 是否必需 | 用途 | 就绪检查 |
|---|---|---|---|
| Adapter Sidecar | 是 | Skill 管理、Agent 调用、轨迹查询 | `GET /api/v1/status` |
| 目标 Agent | 是 | 执行优化 case | Adapter 调用接口 |
| LLM 服务 | 是 | 评估、反思、编辑和变体生成 | 使用配置模型做连通测试 |
| 数据目录 | API 必需 | 保存 JSON/JSONL 数据集 | 路径、挂载和白名单检查 |
| artifact 目录 | 建议持久化 | 保存优化证据和报告 | 写权限和磁盘空间检查 |
| SQLite 控制库 | 服务能力依赖 | 保存特定控制面幂等元数据 | `GET /capabilities` |

### 4.2 Adapter Sidecar

Adapter 是 EvoAgent 与业务 Agent 之间的协议适配层。一个 Adapter 可以管理多个 Agent。

Skill 优化依赖以下接口：

| 能力 | 方法 | 路径 | 用途 |
|---|---|---|---|
| Skill 列表 | POST | `/api/v1/skills` | 获取目标 Agent 的 Skill 名称 |
| Skill 内容 | POST | `/api/v1/skills` | 读取 Skill 文档 |
| Skill 更新 | POST | `/api/v1/skills` | 应用候选 Skill |
| Skill 恢复 | POST | `/api/v1/skills` | 恢复 Adapter 保存的快照 |
| Agent 对话 | POST | `/api/v1/agents/{agent_name}/conversations/{conversation_id}` | 执行一个或多个 query |
| 清洗轨迹 | GET | `/api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}` | 获取评估和优化使用的轨迹 |

详细请求和响应见[Adapter API 契约](api/adapter-api-contract.md)。

Adapter 部署要求：

- EvoAgent 所在网络能够访问 Adapter；
- Adapter 配置中存在请求使用的 `agent_name`；
- 目标 Agent 配置了可调用地址；
- Adapter 可以查询对应 conversation ID 的轨迹；
- Adapter 对 Skill 的读取、更新和恢复操作可用；
- Adapter 超时应大于业务 Agent 的正常响应时间；
- Adapter 快照策略、TTL 和重启行为应在生产接入前确认。

状态检查：

```bash
curl http://localhost:9090/api/v1/status
```

容器部署常见地址：

```dotenv
# Adapter 与 EvoAgent 在同一 Docker 网络
EVO_ADAPTER_URL=http://adapter:8900

# Adapter 在 EvoAgent 容器宿主机
EVO_ADAPTER_URL=http://host.docker.internal:8900

# Adapter 在其他主机
EVO_ADAPTER_URL=http://<ADAPTER_HOST_IP>:8900
```

从宿主机能访问 Adapter，不代表 EvoAgent 容器内也能访问。应从 EvoAgent 实际运行环境验证网络。

### 4.3 目标 Agent

目标 Agent 必须满足：

- Agent 名称与请求中的 `agent_name` 完全一致；
- 已加载请求中的目标 Skill；
- 能处理数据集中的 query；
- 执行过程产生完整、可查询的消息轨迹；
- conversation ID 能在对话和轨迹接口之间保持一致；
- Skill 更新后能按 Adapter 约定生效；
- 并发容量不低于 EvoAgent 的 `num_parallel`。

在正式优化前应验证一个 case 的完整链路：

```text
调用 Agent
    → 返回成功
    → 使用相同 conversation ID 查询 cleaned-traces
    → messages 非空
```

### 4.4 LLM 服务

EvoAgent 使用 LLM 完成：

- 开放式轨迹评估；
- 失败归因和反思；
- 候选编辑生成；
- 编辑聚合和选择；
- TF-GRPO 变体与语义优势分析。

支持两种 Provider：

| Provider | 适用场景 | 必填配置 |
|---|---|---|
| OpenAI 兼容接口 | 公网或兼容 OpenAI 协议的模型网关 | API Key、Base URL、模型名称 |
| CustomSSE | 私有 SSE 模型服务 | Token、User ID、Endpoint、上下文窗口 |

部署要求：

- EvoAgent 可以访问模型 Endpoint；
- 优化模型支持当前 Prompt 的上下文长度；
- 模型输出稳定，能够生成结构化结果；
- 网关超时大于正常模型响应时间；
- 模型限流允许配置的并发；
- 凭据通过 Secret 或环境变量注入。

### 4.5 文件系统和目录

API 模式至少需要以下目录：

| 目录 | 用途 | 建议 |
|---|---|---|
| `/data/evo_agent` | API 数据集 | 只读挂载也可 |
| `workspace/artifacts` | 优化过程和报告 | 持久化、可写 |
| `workspace` | 控制库和运行数据 | 持久化、可写 |

容器路径示例：

```text
宿主机 /home/evolution/data/evo_agent/items.json
    ↓ volume mount
容器内 /data/evo_agent/items.json
```

API 请求必须填写容器内路径。

## 5. EvoAgent 部署与配置

### 5.1 安装

在 EvoAgent 工程目录执行：

```bash
make install
```

项目使用 `uv` 管理 Python 环境。CLI 示例使用 `uv run python`，避免误用系统 Python。

### 5.2 启动 API

开发环境：

```bash
make serve
```

等价命令：

```bash
uv run uvicorn evo_agent.api.app:app --host 0.0.0.0 --port 8001
```

检查服务：

```bash
curl http://localhost:8001/health
curl http://localhost:8001/scenarios
curl http://localhost:8001/capabilities
```

Swagger：

```text
http://localhost:8001/docs
```

开发环境默认使用 `8001`。容器部署通常映射到 `8000`，实际调用地址以部署配置为准。

`/capabilities` 返回 `503 CAPABILITY_STORAGE_UNAVAILABLE` 时，说明控制库不可用。Skill 优化本身不使用当前 Prompt 幂等提交通道，但该错误仍表示服务的控制面存储不完整，应先检查目录权限和 SQLite 文件。

### 5.3 OpenAI 兼容模式

复制配置：

```bash
cp .env.example .env
```

填写：

```dotenv
EVO_LLM_PROVIDER=OpenAI
EVO_LLM_API_KEY=<API_KEY>
EVO_LLM_BASE_URL=https://api.openai.com/v1
EVO_OPTIMIZER_MODEL=gpt-4o
EVO_EVALUATOR_MODEL=gpt-4o
EVO_TARGET_MODEL=gpt-4o

EVO_ADAPTER_URL=http://localhost:9090
```

`EVO_EVALUATOR_MODEL` 为空时使用 `EVO_OPTIMIZER_MODEL`。

### 5.4 CustomSSE 模式

```dotenv
EVO_LLM_PROVIDER=CustomSSE
EVO_CUSTOM_SSE_TOKEN=<TOKEN>
EVO_CUSTOM_SSE_USER_ID=<USER_ID>
EVO_CUSTOM_SSE_ENDPOINT=https://llm-gateway.example.com/v1/chat/completions
EVO_CUSTOM_SSE_CONTEXT_WINDOW_TOKENS=32768
EVO_CUSTOM_SSE_TIMEOUT=120

EVO_ADAPTER_URL=http://localhost:9090
```

Token、User ID、Endpoint 和上下文窗口均为必填项。

### 5.5 常用运行配置

```dotenv
# 远程调用
EVO_REMOTE_TIMEOUT=300
EVO_REMOTE_MAX_RETRIES=2
EVO_REMOTE_PARALLEL=4

# 优化默认值
EVO_DEFAULT_EPOCHS=3
EVO_DEFAULT_BATCH_SIZE=4
EVO_ACCUMULATION=2
EVO_MINIBATCH_SIZE=8
EVO_EDIT_BUDGET=10
EVO_SCHEDULER_MODE=constant
EVO_SCORE_THRESHOLD=0.5
EVO_PARALLELISM=4

# Skill 文档
EVO_USE_SLOW_UPDATE=true
EVO_USE_META_SKILL=true
EVO_PRESERVE_FRONTMATTER=true

# 验证门控
EVO_VALIDATION_MAX_CASE_ATTEMPTS=2
EVO_VALIDATION_MIN_SUCCESS_RATIO=1.0
EVO_VALIDATION_REQUIRE_SAME_CASE_SET=true

# 存储
EVO_ARTIFACT_DIR=./workspace/artifacts
EVOAGENT_CONTROL_DB_PATH=./workspace/evoagent-control.db

# API 数据路径
EVO_ALLOWED_DATA_ROOTS=/data/evo_agent,/tmp/evo_agent
```

不要把 API Key、Token 或其他凭据写入场景配置、数据集、文档或 Git 仓库。

### 5.6 场景目录

每个优化场景位于：

```text
examples/scenarios/<场景名称>/
├── scenario.yaml
├── optimizer.py
└── prompts/              # 可选
```

CLI 通过 `--scenario` 选择场景。API 通过 `optimizer_template.scenario` 选择场景。

### 5.7 SkillOpt 场景配置

内置 `skillopt` 场景：

```yaml
schema_version: "1.0"
optimizer_class: optimizer.SkillOptOptimizer
adapter_url: "http://localhost:9090"

skills:
  - name: product_recommend_skill
    optimize: true
  - name: interact_finance_rec_skill
    optimize: true
  - name: fund_planning_skill
    optimize: false

rollout:
  max_turns: 10
  extra_data:
    role_id: "1"
    role_name: "mobile-bank"

hyperparams:
  batch_size: 8
  num_parallel: 8
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `schema_version` | 是 | 当前使用 `"1.0"` |
| `optimizer_class` | 是 | SkillOpt 使用 `optimizer.SkillOptOptimizer` |
| `adapter_url` | 否 | CLI 可覆盖；未配置时回退到 `EVO_ADAPTER_URL` |
| `skills[].name` | CLI 建议配置 | Adapter 中的精确 Skill 名称 |
| `skills[].optimize` | 否 | CLI 未传 `--skills` 时选择值为 `true` 的 Skill |
| `rollout.extra_data` | 否 | 每次 rollout 传给 Adapter 的场景参数 |
| `hyperparams` | 否 | 通用和算法专属参数 |

> `rollout.max_turns` 当前是场景预留字段，尚未注入 `RemoteAgent` 构造过程；运行时仍使用代码默认值。当前版本不要依赖修改该字段来控制实际轮数。

建议复制内置场景后创建业务场景，不要直接修改公共示例。

### 5.8 TF-GRPO 场景配置

内置 `tf_grpo` 场景：

```yaml
schema_version: "1.0"
optimizer_class: optimizer.TfGrpoOptimizer
adapter_url: "http://127.0.0.1:18900"

skills:
  - name: audit-business
    optimize: true

rollout:
  max_turns: 15
  extra_data: {}

hyperparams:
  group_size: 3
  cases_per_variant: 8
  variant_temperature: 1.5
  semantic_advantage_temperature: 0.95
  max_experiences: 20
  validate_variant_completeness: false
  learn_without_score_variance: true
  num_epochs: 3
  batch_size: 4
  num_parallel: 4
  steps_per_epoch: 1
  accumulation: 1
  use_slow_update: false
  use_meta_skill: false
```

首次验证建议缩小为：

```yaml
hyperparams:
  group_size: 2
  cases_per_variant: 2
  num_epochs: 1
  batch_size: 2
  num_parallel: 2
  steps_per_epoch: 1
  accumulation: 1
  use_slow_update: false
  use_meta_skill: false
```

### 5.9 通用参数

| 参数 | 默认值 | 作用 | 调整建议 |
|---|---:|---|---|
| `num_epochs` | `3` | 优化轮数 | 首次使用 `1` |
| `batch_size` | `4` | 每批 case 数 | 小数据集使用 `2`～`4` |
| `accumulation` | `2` | 每 step 累积的 batch 数 | 增大后参考更多数据，耗时也增加 |
| `minibatch_size` | `8` | 优化内部小批量大小 | 一般保持默认 |
| `edit_budget` | `10` | 单 step 最多选择的编辑数量 | Skill 较短时适当降低 |
| `scheduler_mode` | `constant` | 编辑预算调度 | 支持 `constant`、`linear`、`cosine` |
| `update_mode` | `patch` | Skill 更新方式 | 当前只支持 `patch` |
| `score_threshold` | `0.5` | 成功/失败分界值 | 应与评估规则一致 |
| `num_parallel` | `4` | Agent rollout 并发 | 受 Adapter 和 Agent 容量限制 |
| `parallelism` | `4` | 优化阶段 LLM 并发 | 受模型限流影响 |
| `use_slow_update` | `true` | epoch 末全局更新 | 冒烟验证可关闭 |
| `use_meta_skill` | `true` | 使用跨轮次经验 | 单轮验证可关闭 |
| `preserve_frontmatter` | `true` | 保留 YAML frontmatter | 建议保持开启 |

TF-GRPO 专属参数：

| 参数 | 内置场景值 | 说明 |
|---|---:|---|
| `group_size` | `3` | 每组 Skill 变体数量 |
| `cases_per_variant` | `8` | 每个变体执行的 case 数 |
| `variant_temperature` | `1.5` | 变体生成温度 |
| `semantic_advantage_temperature` | `0.95` | 语义优势分析温度 |
| `max_experiences` | `20` | 每个 Skill 的最大经验数量 |
| `validate_variant_completeness` | `false` | 是否检查变体完整性 |
| `learn_without_score_variance` | `true` | 组内同分时是否继续语义学习 |
| `steps_per_epoch` | `1` | 每个 epoch 的优化 step 数 |

### 5.10 配置优先级

CLI：

| 配置 | 优先级 |
|---|---|
| Adapter | `--adapter-url` > 场景 `adapter_url` > `EVO_ADAPTER_URL` |
| Skill | `--skills` > 场景中 `optimize: true` 的 Skill |
| Agent | `--agent-name` > 场景名称 |
| epoch | `--epochs` > 场景 hyperparams > `EVO_DEFAULT_EPOCHS` |
| batch size | `--batch-size` > 场景 hyperparams > `EVO_DEFAULT_BATCH_SIZE` |
| 算法专属参数 | 场景 hyperparams > 环境或代码默认值 |

API：

| 配置 | 优先级 |
|---|---|
| 场景 | `optimizer_template.scenario` |
| Skill | 请求体 `skills`，必须非空 |
| Agent | 请求体 `agent_name` |
| epoch、batch size | 请求 hyperparams > 场景 hyperparams > 环境默认值 |
| 其他 hyperparams | 请求 hyperparams > 场景 hyperparams > 环境或代码默认值 |
| rollout extra data | 请求值覆盖场景同名字段 |
| Adapter | EvoAgent 服务的 `EVO_ADAPTER_URL` |

修改 `.env` 后必须重启 EvoAgent。

## 6. 调用说明

### 6.1 准备数据集

CLI 目录：

```text
data/skillopt-demo/
├── dataset.yaml
└── items.json
```

`dataset.yaml`：

```yaml
schema_version: "1.0"
name: skillopt_demo
cases: items.json
train_split: 0.8
seed: 0

evaluator:
  type: llm
  aggregate: mean
```

`items.json`：

```json
[
  {
    "id": "case-001",
    "inputs": [
      {
        "role": "user",
        "content": "请推荐一款低风险理财产品"
      }
    ],
    "expected_behavior": "应先确认用户风险等级，不承诺收益，并说明产品风险。",
    "extra_data": {
      "customer_level": "standard"
    }
  },
  {
    "id": "case-002",
    "inputs": [
      "我想把全部存款投入高收益产品"
    ],
    "expected_behavior": "应提示集中投资风险，询问风险承受能力，并避免直接执行购买。",
    "extra_data": {}
  }
]
```

上面只用于展示格式。实际数据应保证拆分后训练集和验证集都非空，并覆盖足够的业务场景。

### 6.2 CLI 调用 SkillOpt

```bash
uv run python skills/optimize_skill/scripts/run_optimize.py \
  --scenario skillopt \
  --dataset-manifest data/skillopt-demo/dataset.yaml \
  --skills product_recommend_skill \
  --epochs 1 \
  --batch-size 2
```

CLI 参数：

| 参数 | 必填 | 默认值/来源 | 说明 |
|---|---|---|---|
| `--scenario` | 否 | `skillopt` | 场景目录名称 |
| `--dataset-manifest` | 是 | 无 | `dataset.yaml` 路径 |
| `--adapter-url` | 否 | 场景或环境变量 | Adapter 地址 |
| `--skills` | 否 | 场景中 `optimize: true` 的 Skill | 逗号分隔 |
| `--agent-name` | 否 | 场景名称 | Adapter 中的目标 Agent |
| `--epochs` | 否 | 场景或环境默认值 | 优化轮数 |
| `--batch-size` | 否 | 场景或环境默认值 | 每批 case 数 |

指定多个 Skill：

```bash
uv run python skills/optimize_skill/scripts/run_optimize.py \
  --scenario skillopt \
  --dataset-manifest data/skillopt-demo/dataset.yaml \
  --skills product_recommend_skill,interact_finance_rec_skill
```

覆盖 Adapter 和 Agent：

```bash
uv run python skills/optimize_skill/scripts/run_optimize.py \
  --scenario skillopt \
  --dataset-manifest data/skillopt-demo/dataset.yaml \
  --adapter-url http://adapter.example.com:9090 \
  --agent-name finance_agent \
  --skills product_recommend_skill
```

### 6.3 CLI 调用 TF-GRPO

```bash
uv run python skills/optimize_skill/scripts/run_optimize.py \
  --scenario tf_grpo \
  --dataset-manifest data/tf-grpo-demo/dataset.yaml \
  --skills audit-business \
  --epochs 1 \
  --batch-size 2
```

CLI 只能直接覆盖公共参数。`group_size` 等 TF-GRPO 专属参数应写入对应场景的 `hyperparams`。

### 6.4 API 数据文件

API 的 `dataset_path` 是 EvoAgent 服务端可见的绝对路径：

```text
/data/evo_agent/skillopt-demo/items.json
```

文件必须：

- 真实存在；
- 是普通文件；
- 不超过 500MB；
- 位于 `EVO_ALLOWED_DATA_ROOTS` 下。

### 6.5 查询可用场景

```bash
curl http://localhost:8001/scenarios
```

### 6.6 API 提交 SkillOpt

```bash
curl -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "task_name": "skillopt-demo-001",
    "agent_name": "edp_agent",
    "optimizer_type": "skill",
    "skills": ["product_recommend_skill"],
    "dataset_path": "/data/evo_agent/skillopt-demo/items.json",
    "optimizer_template": {
      "name": "skillopt",
      "scenario": "skillopt",
      "hyperparams": {
        "num_epochs": 1,
        "batch_size": 2,
        "num_parallel": 2
      },
      "rollout": {
        "extra_data": {
          "role_id": "1",
          "role_name": "mobile-bank"
        }
      },
      "train_split": 0.8,
      "val_split": 0.2
    },
    "evaluator_template": {
      "name": "skill_quality_eval",
      "scenario": "edp_agent",
      "type": "llm",
      "prompt": ""
    }
  }'
```

要求：

- `optimizer_type` 必须为 `"skill"`；
- `skills` 必须是非空数组；
- 不要传 `managed_doc_kind`；
- `train_split + val_split` 必须等于 `1.0`；
- `dataset_path` 使用服务端或容器内路径；
- `optimizer_template.name` 和 `scenario` 建议都填写 `/scenarios` 返回的同一个场景名称。

成功响应：

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

### 6.7 API 提交 TF-GRPO

```bash
curl -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "task_name": "tf-grpo-demo-001",
    "agent_name": "tf_grpo",
    "optimizer_type": "skill",
    "skills": ["audit-business"],
    "dataset_path": "/data/evo_agent/tf-grpo-demo/items.json",
    "optimizer_template": {
      "name": "tf_grpo",
      "scenario": "tf_grpo",
      "hyperparams": {
        "group_size": 2,
        "cases_per_variant": 2,
        "variant_temperature": 1.2,
        "semantic_advantage_temperature": 0.95,
        "max_experiences": 10,
        "num_epochs": 1,
        "batch_size": 2,
        "num_parallel": 2,
        "steps_per_epoch": 1,
        "accumulation": 1,
        "use_slow_update": false,
        "use_meta_skill": false
      },
      "train_split": 0.8,
      "val_split": 0.2
    },
    "evaluator_template": {
      "name": "skill_quality_eval",
      "scenario": "tf_grpo",
      "type": "llm",
      "prompt": ""
    }
  }'
```

### 6.8 查询任务

```bash
curl http://localhost:8001/optimize/<job_id>
```

状态：

| 状态 | 说明 |
|---|---|
| `queued` | 等待运行 |
| `running` | 正在运行 |
| `completed` | 成功完成 |
| `failed` | 运行失败 |
| `cancelled` | 已取消 |

### 6.9 订阅 SSE

```bash
curl -N http://localhost:8001/optimize/<job_id>/stream
```

事件类型：

| 事件 | 说明 |
|---|---|
| `progress` | epoch 级进度 |
| `log` | rollout、评估、归因、编辑等阶段日志 |
| `completed` | 任务结束 |
| `error` | 任务失败 |

### 6.10 取消任务

```bash
curl -X POST http://localhost:8001/optimize/<job_id>/cancel
```

取消请求发出后继续查询任务终态。Skill 优化任务不使用 managed-doc 的强制基线恢复协议；如果任务已应用部分修改，应重新读取 Adapter 中的 Skill 并按现场基线策略处理。

## 7. 调用后的验证

### 7.1 任务状态验证

首先确认：

1. 任务状态为 `completed`；
2. `error` 和 `error_code` 为空；
3. `epochs_completed` 符合预期；
4. 所有预期 case 都完成 rollout 和评估；
5. 没有大量 `trace_unavailable`、超时或空轨迹；
6. 验证集实际参与评分的 case 数符合预期。

CLI 完成后会输出：

```text
优化完成: ['product_recommend_skill']
  得分: 0.55 → 0.72 (+31%)
  编辑数: 3
  产物: workspace/artifacts/<run_id>
```

### 7.2 效果验证

重点字段：

| 字段 | 说明 |
|---|---|
| `train.score_before` | 优化前训练分数 |
| `train.score_after` | 优化后训练分数 |
| `train.pass_rate_before` | 优化前训练通过率 |
| `train.pass_rate_after` | 优化后训练通过率 |
| `val.final_score` | 最后一轮验证分数 |
| `val.best_score` | 历史最佳验证分数 |
| `val.per_epoch_scores` | 每轮候选验证分数 |
| `gate_results` | 每轮接受候选或保留基线的决策 |
| `skill_scores` | 每个 Skill 的分数和编辑数量 |
| `edits_applied` | 应用的编辑总数 |

门控结果：

- `candidate`：候选 Skill 通过验证并被保留；
- `base`：候选 Skill 没有优于本轮基线，本轮修改被拒绝。

`edits_applied > 0` 不代表最终保留了全部编辑。必须同时检查 `gate_results` 和最终 Skill 内容。

### 7.3 内容验证

比较：

- `skill_before.md`
- `skill_after.md`
- API 返回的 `skill_contents`
- Adapter 当前返回的 Skill 内容

检查：

- 修改是否对应失败数据；
- 是否误删原有核心规则；
- frontmatter 是否保持；
- 是否出现互相冲突的规则；
- 是否把单个 case 的细节硬编码进通用 Skill；
- Adapter 中实际生效内容是否等于报告最终内容。

### 7.4 回归验证

正式发布前应：

1. 使用未参与优化的独立数据重新评估；
2. 检查原有正常能力是否退化；
3. 覆盖异常、边界和合规场景；
4. 对多 Skill 场景检查归因是否合理；
5. 必要时恢复优化前基线。

### 7.5 产物验证

以返回的 `artifact_dir` 为准，优先检查：

1. `summary.json`
2. `gate_result.json`
3. `eval_results.json`
4. `trajectories.jsonl`
5. `skill_before.md`
6. `skill_after.md`

### 7.6 常见失败检查

| 现象 | 重点检查 |
|---|---|
| 场景不存在 | `/scenarios` 和场景目录 |
| `EVO_ADAPTER_URL not configured` | `.env` 和服务是否重启 |
| Adapter 不可达 | 容器网络、主机名、端口和防火墙 |
| 数据文件不存在 | CLI manifest 相对路径或 API 容器内路径 |
| 数据路径不在白名单 | `EVO_ALLOWED_DATA_ROOTS` |
| Skill 不存在 | `agent_name`、Skill 精确名称和 Adapter 配置 |
| rollout 成功但无轨迹 | conversation ID、轨迹延迟和 cleaned-traces |
| LLM 超时或限流 | 降低 `num_parallel`、`parallelism` 和 batch size |
| 分数不变 | 数据是否触发 Skill、评估器是否合适 |
| 每轮都回退 | 归因、数据覆盖、评估稳定性和 `edit_budget` |
| TF-GRPO 太慢 | 降低 `group_size`、`cases_per_variant` 和 epoch |

## 8. 特性逻辑与能力说明

本章面向需要快速理解实现逻辑的开发人员。

### 8.1 核心组件

| 组件 | 职责 |
|---|---|
| `optimizer_runner.py` | Skill 优化的统一编排入口 |
| `OptimizationConfigResolver` | 合并请求、场景和环境默认值 |
| `ScenarioRegistry` | 加载 `scenario.yaml` 和优化器类 |
| `AdapterClient` | 调用 Adapter 的 Skill、对话和轨迹接口 |
| `RemoteAgent` | 把数据集 case 转换为远程 Agent 调用 |
| `EvoTrainer` | 组织训练、验证、回调和门控 |
| `SkillDocumentOperator` | 读取、更新和同步 Skill 内容 |
| `ReportFormatter` | 从运行结果和 artifact 构建最终报告 |
| `JobManager` | 管理 API 进程内任务、进度和 SSE |

### 8.2 编排流程

`optimizer_runner` 的主要步骤：

```text
解析运行配置
    ↓
创建 run_id 和 artifact 目录
    ↓
连接 Adapter
    ↓
尝试恢复 Skill 快照并读取基线
    ↓
创建 RemoteAgent 和 Skill Operators
    ↓
加载 CLI manifest 或 API 数据文件
    ↓
创建评估器和优化 LLM
    ↓
通过 ScenarioRegistry 创建算法实例
    ↓
运行 EvoTrainer
    ↓
格式化并返回 OptimizeReport
```

Skill 模式的 artifact 目录为：

```text
EVO_ARTIFACT_DIR/<run_id>
```

### 8.3 配置解析

运行时把以下来源合并成统一配置：

```text
CLI/API 显式参数
    ↓ 覆盖
scenario.yaml
    ↓ 覆盖
EVO_* 环境变量
    ↓ 覆盖
代码默认值
```

算法实例只接收最终解析后的值，不需要判断值来自哪个入口。

### 8.4 SkillOpt 逻辑

SkillOpt 的主要阶段：

1. **rollout**：使用当前 Skill 执行训练 case；
2. **evaluate**：给轨迹评分并判断成功/失败；
3. **attribute**：把失败归因到具体 Skill；
4. **reflect**：分析失败模式并提出修改；
5. **aggregate**：合并重复或冲突建议；
6. **select**：根据编辑预算选择候选编辑；
7. **apply**：更新目标 Skill；
8. **skill_sync**：通过 Adapter 同步内容；
9. **validation**：比较候选版本和本轮基线；
10. **epoch_end**：保留候选或恢复本轮基线。

`use_slow_update` 和 `use_meta_skill` 用于 epoch 级的全局更新与跨轮次经验。

### 8.5 TF-GRPO 逻辑

TF-GRPO 在通用 Skill 优化流程上增加：

1. 根据当前 Skill 和经验库生成 `group_size` 个变体；
2. 每个变体执行 `cases_per_variant` 个 case；
3. 比较同组变体的评分和轨迹；
4. 生成语义优势分析；
5. 更新每个 Skill 的经验库；
6. 选择候选方向并进入验证门控。

即使组内分数相同，`learn_without_score_variance=true` 时仍可根据语义差异更新经验。

### 8.6 轨迹关联

EvoAgent 为每次运行创建 `run_id`，并为 case 生成 conversation ID。对话调用和轨迹查询必须使用同一个 ID。

训练 case 的基础映射：

```text
case.id
    → conversation_id
    → Adapter 对话接口
    → cleaned-traces 查询
    → 评估和归因
```

轨迹查询支持重试和退避，用于应对 Agent 日志落盘或 Adapter 清洗延迟。超过最大尝试次数仍没有有效 `messages` 时，该 case 会以轨迹不可用处理。

### 8.7 验证门控

每轮候选 Skill 都必须在验证集上重新执行。门控可要求：

- 单个 case 最多尝试指定次数；
- 达到最低成功覆盖率；
- 候选版本和基线使用相同 case 集合。

相关环境变量：

```dotenv
EVO_VALIDATION_MAX_CASE_ATTEMPTS=2
EVO_VALIDATION_MIN_SUCCESS_RATIO=1.0
EVO_VALIDATION_REQUIRE_SAME_CASE_SET=true
```

### 8.8 进度和取消

API 通过 `JobManager` 保存任务状态，并通过 SSE 输出：

- `progress`
- `log`
- `completed`
- `error`

主要 phase 包括：

```text
train_begin
epoch_begin
rollout
evaluate
attribute
reflect
aggregate
select
apply
skill_sync
validation
epoch_end
train_end
cancel_requested
```

取消采用协作式 `CancellationToken`。优化代码在安全检查点感知取消，已在执行中的 Adapter 或 LLM 请求不保证立即停止。

## 9. 数据格式与存储说明

### 9.1 CLI dataset.yaml

格式：

```yaml
schema_version: "1.0"
name: skillopt_demo
cases: items.json
train_split: 0.8
seed: 0

evaluator:
  type: llm
  aggregate: mean
```

字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `name` | 是 | 数据集名称 |
| `cases` | 是 | 相对于 manifest 的 JSON/JSONL 文件 |
| `train_split` | 否 | 训练比例，默认 `0.8` |
| `seed` | 否 | 随机拆分种子，默认 `0` |
| `evaluator` | 是 | 评估器配置 |

评估器示例：

```yaml
# LLM
evaluator:
  type: llm
  aggregate: mean
```

```yaml
# Metric
evaluator:
  type: metric
  metric: normalized_exact_match
  aggregate: mean
```

```yaml
# Custom
evaluator:
  type: custom
  dotted_path: my_package.evaluators.BusinessEvaluator
  kwargs:
    threshold: 0.8
```

### 9.2 Case 数据

EvoCase 格式：

```json
{
  "id": "case-001",
  "inputs": [
    {
      "role": "user",
      "content": "用户问题"
    }
  ],
  "expected_behavior": "期望 Agent 表现出的可观察行为",
  "extra_data": {
    "role_id": "1"
  }
}
```

字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | case 标识，建议唯一且稳定 |
| `inputs` | 是 | 字符串数组或消息数组 |
| `expected_behavior` | 建议 | 评估时使用的期望结果 |
| `extra_data` | 否 | 传给 Agent 的 case 级扩展字段 |

文件格式：

- JSON：顶层为对象数组；
- JSONL：每行一个对象。

case 级 `extra_data` 会覆盖场景 `rollout.extra_data` 中的同名字段。

### 9.3 轨迹数据

Adapter `cleaned-traces` 响应必须包含可用的 `messages`。轨迹会被标准化后交给评估器和优化器。

产物中的 `trajectories.jsonl` 保存本轮参与优化的轨迹信息，用于：

- 复核评分；
- 检查失败归因；
- 分析工具或 Skill 调用；
- 定位空轨迹、异常终止和内容截断。

轨迹可能包含业务内容，artifact 目录应按敏感数据要求控制访问和保留周期。

### 9.4 Artifact 存储

默认根目录：

```text
workspace/artifacts
```

通过以下变量修改：

```dotenv
EVO_ARTIFACT_DIR=./workspace/artifacts
```

典型结构：

```text
workspace/artifacts/<run_id>/
├── summary.json
├── <skill_name>/
│   ├── summary.json
│   └── epoch_N/
│       ├── skill_before.md
│       ├── skill_after.md
│       ├── gate_result.json
│       └── step_M/
│           ├── trajectories.jsonl
│           └── eval_results.json
└── ...
```

不同算法和运行阶段生成的文件可能不同，应以 API/CLI 返回的 `artifact_dir` 为准。

Artifact 不是数据库事务存储。生产部署应自行考虑：

- volume 持久化；
- 容量监控；
- 备份；
- 清理周期；
- 敏感数据脱敏；
- 访问权限。

### 9.5 API 任务内存状态

`JobManager` 当前是进程内任务管理器，保存：

- `job_id`
- 状态；
- 当前进度；
- 最终结果；
- 错误和错误码；
- 取消令牌；
- SSE 事件缓冲。

SSE 事件使用内存 `deque`，单任务最多保留 5000 条事件。

这意味着：

- 服务重启后完整 Job、结果和 SSE 不可恢复；
- 多进程实例之间不共享任务状态；
- `GET /optimize/{job_id}` 必须访问持有该 Job 的进程；
- 生产多副本部署需要会话粘滞，或把 JobManager 替换为共享持久化实现；
- 最终证据应以持久化 artifact 为主。

### 9.6 SQLite 控制库

默认文件：

```text
workspace/evoagent-control.db
```

配置：

```dotenv
EVOAGENT_CONTROL_DB_PATH=./workspace/evoagent-control.db
```

数据库使用 WAL 模式，包含表：

```sql
CREATE TABLE IF NOT EXISTS optimization_submissions (
    client_task_id TEXT PRIMARY KEY,
    request_hash_version TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    job_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL,
    cancellation_requested INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

字段：

| 字段 | 说明 |
|---|---|
| `client_task_id` | 调用方幂等键 |
| `request_hash_version` | 请求哈希规则版本 |
| `request_hash` | 去除幂等键后的规范化请求哈希 |
| `job_id` | EvoAgent 任务 ID |
| `status` | 持久化提交状态 |
| `cancellation_requested` | 是否请求取消 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

持久化状态：

```text
RECEIVED
RUNNING
COMPLETED
FAILED
CANCELLED
LOST
```

重要边界：

- 该表只保存控制面提交元数据；
- 不保存数据集、轨迹、Skill 内容、SSE 或完整优化报告；
- 当前 `client_task_id` 幂等提交链路只用于 Prompt/managed-doc 优化；
- Skill 优化请求不会通过该链路持久化完整任务状态；
- 服务启动时，控制表中未完成的持久化提交会标记为 `LOST`，不会自动续跑。

因此，SQLite 不能用于恢复 Skill 优化任务。

### 9.7 Skill 存储边界

EvoAgent 不是 Skill 的权威存储。

```text
Skill 权威版本：目标 Agent / Adapter 管理侧
优化过程快照：EvoAgent artifact
任务实时状态：EvoAgent 进程内存
控制面幂等元数据：SQLite
```

优化完成后，应通过 Adapter 再次读取 Skill，确认实际生效版本。artifact 中的 `skill_after.md` 用于审计和比较，不能替代业务 Agent 侧的发布状态。

### 9.8 数据安全

- 数据集、轨迹和 Skill 可能包含敏感业务信息；
- 不要在文档、日志或 Git 中保存 LLM Token；
- API 数据根目录应使用最小权限；
- artifact 目录应限制访问；
- 生产环境应定义数据保留和清理周期；
- 对外提供 artifact 前应脱敏；
- SQLite 文件应限制为 EvoAgent 进程可读写。

## 相关文档

- [优化 API 参考](api/optimization-api-reference.md)
- [Adapter API 契约](api/adapter-api-contract.md)
- [评估 API 参考](api/evaluate-api.md)
- [EvoAgent README](../README.md)
