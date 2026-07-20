# EvoAgent

自进化元 Agent — 基于 agent-core 构建的对话式入口，一句话启动 skill 文档的自动优化。

## 核心闭环

```
用户下达指令 → Agent 识别意图 → 编排优化 Pipeline（远程 rollout + 远程 skill 更新）→ 输出优化报告
```

## 快速开始

### 安装

```bash
make install
```

### 配置

```bash
cp .env.example .env
# 编辑 .env 填入必要配置
```

### 运行

```bash
# 通过 Agent 对话式启动（CLI 模式）
make dev

# 或直接运行示例脚本
python examples/optimize_example.py
```

### 启动 API 服务

```bash
# 启动 FastAPI 服务端（API 模式）
make serve
# 或手动指定参数
uv run uvicorn evo_agent.api.app:app --host 0.0.0.0 --port 8001
```

服务启动后访问 `http://localhost:8001/docs` 查看 Swagger 文档。

主要端点：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/optimize` | 提交优化任务，返回 `job_id`（异步） |
| `GET` | `/optimize/{job_id}` | 轮询任务状态与结果 |
| `GET` | `/optimize/{job_id}/stream` | SSE 实时推送进度与 phase 事件 |
| `POST` | `/optimize/{job_id}/cancel` | 取消运行中的任务 |
| `POST` | `/evaluate` | 同步评估单条轨迹（不走优化管线） |
| `GET` | `/scenarios` | 列出已注册场景 |
| `GET` | `/capabilities` | 列出服务能力与 managed-doc 支持情况 |
| `GET` | `/health` | 健康检查 |

Managed-doc 优化相关环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `EVO_VALIDATION_MAX_CASE_ATTEMPTS` | `2` | 单个验证 case 最大尝试次数 |
| `EVO_VALIDATION_MIN_SUCCESS_RATIO` | `1.0` | 验证批次最低成功比例 |
| `EVO_VALIDATION_REQUIRE_SAME_CASE_SET` | `true` | 候选与基线必须使用同一 case 集合 |
| `EVO_MANAGED_DOC_APPLY_DEADLINE` | `600` | managed-doc apply 总时限（秒） |
| `EVO_MANAGED_DOC_CANCEL_ROLLBACK_DEADLINE` | `900` | 取消后完成或回滚的总时限（秒） |
| `EVO_MANAGED_DOC_CONTENT_POLICIES` | `{}` | 按 doc kind 配置 `preserving` 或 `passthrough` |
| `EVO_MANAGED_DOC_PROTECTED_SECTIONS` | `{}` | 按 doc kind 配置必须原样保留的 marker 区段 |

## 项目结构

```
common/agent-evolve/evoagent/
├── skills/optimize_skill/     # Agent Skill (SKILL.md + scripts)
├── examples/scenarios/        # 场景配置（edp_agent / example）
│   └── edp_agent/             #   场景目录（scenario.yaml + optimizer.py + prompts/）
├── src/evo_agent/             # Python 包
│   ├── config.py              # 配置管理 (pydantic-settings)
│   ├── runtime_config.py      # 运行时配置合并（request + scenario + env → ResolvedOptimizationConfig）
│   ├── types.py               # 类型定义 (frozen dataclass)
│   ├── protocols.py           # 内部 Protocol 约定
│   ├── paths.py               # 路径工具
│   ├── skill_loader.py        # Skill 内容加载
│   ├── optimizer_runner.py    # 最小编排入口（双轨：CLI/API）
│   ├── trainer.py             # EvoTrainer（sidecar-aware + 轨迹注入）
│   ├── conversation.py        # ConversationIdFactory
│   ├── optimizer/             # 优化引擎内部实现
│   │   ├── concurrency.py     #   gather_with_semaphore（LLM 并发受单一 semaphore 控制）
│   │   ├── llm_resilience.py  #   LLM 重试 / 超时降级
│   │   ├── dict_optimizer.py  #   字典式 optimizer
│   │   └── skill_document/    #   ReflACT 管线（reflect/aggregate/select/apply/slow_update/meta_skill）
│   ├── operator/              # SkillDocumentOperator 工厂
│   ├── evaluator/             # 评估器（LLM / metric / filters / trajectory 简化）
│   ├── adapter_client/        # Adapter sidecar 通信层（client + remote_agent + operator）
│   ├── callbacks/             # Callback 组合（SkillDocumentCallbacks / RemoteSkillSync / Composed）
│   ├── api/                   # FastAPI 服务端 API（app/jobs/progress/events/sse/routes）
│   ├── dataset/               # Dataset manifest 解析 + API 模式构建
│   ├── scenario/              # 场景适配层（ScenarioRegistry + prompts 两级查找）
│   └── reporter/              # 报告生成（artifact → OptimizeReport，train/val 分组）
├── tests/                     # 测试（unit / integration / e2e）
└── examples/                  # 示例与场景定义
```

## 场景开发

`examples/scenarios/` 目录下每个子目录代表一个业务场景（如 `edp_agent`）。开发新场景时创建目录并包含：

- `scenario.yaml`: 场景配置（adapter_url、optimizer_class、skills 列表、rollout 配置）
- `optimizer.py`: 场景 optimizer 子类（继承 `SkillDocumentOptimizer`，覆写 `_rollout()` 等方法）
- `prompts/`: 可选的场景 prompt 覆盖（`analyst_error.md`、`analyst_success.md` 等）

场景通过 ScenarioRegistry 自动加载，无需手动注册。

## API 双模式

EvoAgent 支持两种入口模式：

**CLI 模式**（现场部署）：
```bash
python skills/optimize_skill/scripts/run_optimize.py \
  --scenario edp_agent \
  --dataset-manifest data/dataset.yaml
```

**API 模式**（平台集成）：
```bash
curl -X POST http://localhost:8001/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "task_name": "optimize-001",
    "agent_name": "edp_agent",
    "dataset_path": "/data/evo_agent/items.json",
    "optimizer_template": {
      "name": "edp_agent",
      "scenario": "金融客服",
      "hyperparams": {"num_epochs": 5},
      "train_split": 0.8,
      "val_split": 0.2
    },
    "evaluator_template": {
      "name": "default_eval",
      "scenario": "金融客服",
      "prompt": "评估回答的准确性和专业性"
    }
  }'
```

API 模式使用嵌套模板结构（`OptimizeAPIRequest`），支持平台配置管理。详见
[`docs/api/optimization-api-reference.md`](docs/api/optimization-api-reference.md)。

## 开发

```bash
make lint      # 代码检查
make fix       # 自动修复
make test      # 运行测试
make test-unit # 仅单元测试
make serve     # 启动 API 服务 (FastAPI)
```

## 架构

EvoAgent 是 SkillOpt 合入方案的使用层：

- **Agent 运行时**: agent-core (`openjiuwen` 包) 的 ReActAgent
- **优化引擎**: agent_evolving 的 SkillDocumentOptimizer（场景子类化）
- **通信方式**: Adapter sidecar HTTP 通信（skill 操作 + 对话触发 + 轨迹收集）
- **双模式支持**: CLI 模式（dataset.yaml）+ API 模式（原始数据文件 + 模板配置）

核心组件：
- `optimizer_runner.py`: 唯一编排入口，双轨分支（manifest vs build_dataset），注入 `phase_callback`
- `runtime_config.py`: `OptimizationConfigResolver` — 统一合并 request / scenario preset / env 默认值
- `ScenarioRegistry`: 场景 optimizer 类加载器
- `AdapterClient`: Adapter sidecar 通信层
- `EvoTrainer`: sidecar-aware 训练器（轨迹注入 + ConversationIdFactory）
- `optimizer/concurrency.py`: `gather_with_semaphore` — 跨 operator reflect/aggregate/select 并发，受单一 semaphore 控总 LLM 并发

## 性能与可观测性

- **并发控制**: ReflACT 管线的 reflect / aggregate / select / slow_update 跨 operator 并行，训练阶段评估 `batch_evaluate` 并发化，全部由单一 `semaphore`（`parallelism`）封顶，见 `optimizer/concurrency.py`。
- **SSE 可观测**: optimizer 各阶段通过 `phase_callback` 推送 `log` 事件（rollout / attribute / reflect / aggregate / select / apply / validation），经 `GET /optimize/{job_id}/stream` 实时下发，事件类型见 `api/events.py`。
- **报告结构**: `OptimizeReport` 按 `train` / `val` 分组（Wave 10 起），per-skill 明细落在 `skill_scores`。

接口与部署细节见 [`docs/api/`](docs/api/) 和 [`deployment/`](deployment/)。

## License

Apache 2.0
