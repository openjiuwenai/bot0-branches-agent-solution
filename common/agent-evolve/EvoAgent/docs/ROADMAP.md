# EvoAgent — 开发路线图

> 端到端打通：API/Agent 入口 → 场景加载 → AdapterClient → ReflACT Pipeline → Skill 回写 → 报告
>
> 架构决策：[ADR-0003](adr/0003-adapter-sidecar-platform-integration-sse.md)
>
> 最后更新：2026-06-13

---

## Wave 1: 类型与重命名

> 目标：铺好地基。纯机械改动，无新逻辑，可快速合入。

**前置依赖**：无

### 1.1 `adapter/` → `scenario/` 重命名

- [x] 重命名 `src/evo_agent/adapter/` → `src/evo_agent/scenario/`
- [x] 更新所有 import 路径（`evo_agent.adapter.registry` → `evo_agent.scenario.registry` 等）
- [x] 更新 `CLAUDE.md`、`docs/rules/` 中的模块索引
- [x] `make test` 确认无回归

### 1.2 OptimizeRequest 重构（`types.py`）

- [x] 移除 `remote_endpoint`，新增 `adapter_url`
- [x] 新增 `agent_name: str = ""`（Skill 操作时指定目标 Agent，对话/轨迹通过路径指定）
- [x] 新增 `optimizer_type: str = "skill_opt"`
- [x] 新增 `evaluator_prompt: str = ""`（评估器提示词，用于构造评估器对象）
- [x] 新增 `hyperparams: dict = field(default_factory=dict)`
- [x] 新增 `optimizer_template_id: str = ""`
- [x] 移除 `skill_path`、`skill_name`（改为多 skill 列表）
- [x] 新增 `skills: list[str] = field(default_factory=list)`（要优化的 skill 名称列表；A 入口从 scenario.yaml 读取，B 入口从 API 请求体下发）
- [x] 更新所有引用 `OptimizeRequest` 的模块
- [x] 单元测试

### 1.3 EvolveConfig 精简（`config.py`）

- [x] 移除 `remote_endpoint`（adapter_url 是 per-request 配置，不放全局 EvolveConfig）
- [x] 保留 LLM 密钥、全局默认超参、路径等全局配置
- [x] 确认现有超参字段覆盖平台下发的参数集
- [x] 单元测试

**交付物**：类型系统就绪，所有 import 路径更新，测试通过。

---

## Wave 2: 通信层

> 目标：建立与 Adapter sidecar 的通信能力和 Agent 抽象。

**前置依赖**：Wave 1

### 2.1 AdapterClient（`adapter_client/client.py`）

> API 契约：[`docs/api/adapter-api-contract.md`](api/adapter-api-contract.md)

- [x] `AdapterClient.__init__(adapter_url, agent_name, timeout, max_retries)`
- [x] `invoke(case_id, query, extra_data, run_id)` — POST `/api/v1/agents/{agent_name}/conversations/{id}`，返回 `{success, answer, interrupted, events}`（业务失败通过 `success=false` 表达，HTTP 仍为 200）
- [x] `get_traces(case_id)` — GET `/api/v1/agents/{agent_name}/cleaned-traces/{id}`，返回 `{messages, trajectory}` dict（无轨迹返回 `{}`）
- [x] `update_skill(skill_name, skill_content)` — POST /api/v1/skills action=update_skill（**同步 httpx**，因 operator callback 是同步调用链）
- [x] `skill_list()` — POST /api/v1/skills action=skill_list（async）
- [x] `skill_content(skill_name)` — POST /api/v1/skills action=skill_content（async）
- [x] retry + 超时 + 错误处理（FastAPI `{detail: "..."}` 格式 + `success=false` 业务错误）
- [x] `agent_name` 非空校验（防止生成 `/agents//conversations/...` 无效路径）
- [x] `update_skill` 同步 retry（502/503 + TransportError，与 async `_request_with_retry` 一致）
- [x] 单元测试（httpx MockTransport）

### 2.2 RemoteAgent（`adapter_client/remote_agent.py`）

基于 [grill 讨论结论](#已确认的设计决策)：继承 `BaseAgent` 最小实现，**不是** stub。

- [x] `RemoteAgent.__init__(card, adapter_client, operators, max_turns=10)`
- [x] 实现 `invoke(inputs, session)` — 兼容 Trainer 标准格式 `{**case.inputs, "conversation_id": case.case_id}`（P1 fix）；也支持 EvoAgent 自定义格式（`queries` 列表 + `case_id` + `run_id`），受 max_turns 兜底；过滤空 query，全空时 `raise ValueError`；`run_id` 缺失时自动生成 UUID
- [x] 实现 `stream(inputs, session, stream_modes)` — 最小实现（暂不用于训练）
- [x] 实现 `configure(config)` — 空实现
- [x] 实现 `get_operators()` — 返回 `Dict[str, SkillDocumentOperator]`
- [x] 暴露 `adapter_client` 属性，供 `_rollout` 调用 `get_traces()`
- [x] Session 传透不记录 tracer（验证评估只需 answer）
- [x] 单元测试（含 Trainer 兼容格式 6 个专项测试）

### 2.3 Operator 工厂（`adapter_client/operator.py`）

直接实例化 agent-core 的 `SkillDocumentOperator`，不子类化。

- [x] `build_skill_document_operator(skill_name, initial_content, adapter_client)` 工厂函数
  - 创建 `SkillDocumentOperator(skill_name, initial_content, on_parameter_updated=callback)`
  - callback 签名 `(target: str, new_content: Any) -> None`（匹配 vendor `set_parameter`/`load_state` 调用约定）
  - callback 内调 `adapter_client.update_skill(skill_name, str(new_content))`（同步调用，update_skill 用同步 httpx）
- [x] initial_content 从 Adapter 端点获取（`skill_list` / `skill_content`）
- [x] 单元测试 — 直接 callback 调用 + `set_parameter` / `load_state` 端到端验证

**交付物**：AdapterClient 可通过 MockTransport 完成 invoke/skill 操作；RemoteAgent 可通过 `get_operators()` 暴露 operators 给 Trainer。Review 修复（d53b2cd）：callback 参数顺序、sync retry、agent_name/queries 校验。测试 89 个。

---

## Wave 3: 编排层重写

> 目标：打通 Trainer → RemoteAgent → AdapterClient → Adapter 的完整训练循环。

**前置依赖**：Wave 1, Wave 2

### 3.1 optimizer_runner.py 重写

- [x] 移除 `create_evo_agent()` + `agent.register_skill()` 调用
- [x] 构建 `AdapterClient(adapter_url, agent_name, timeout, retries)`
- [x] 构建 operators（通过 `build_skill_document_operator()` 工厂，每个 optimize=true 的 skill 一个）
- [x] 构建 `RemoteAgent(card, adapter_client, operators)`
- [x] 将 RemoteAgent 传入 `SkillDocumentOptimizer` 的 `agent` 参数
- [x] `Trainer.train(agent=remote_agent)` — rollout 由 optimizer._rollout 覆写完成
- [x] 集成测试 — 端到端 mock 流程

### 3.2 callbacks.py 简化

- [x] 移除 `skill_sync_callback` 参数
- [x] 回调顺序简化为：`SkillDocumentCallbacks` → `ProgressCallback`
- [x] 确认单元测试通过

### 3.3 Scenario registry 更新

- [x] `ScenarioRegistry` 支持从 `scenario.yaml` 读取 skills 列表（区分 optimize=true/false）
- [x] 依赖注入：将 AdapterClient 和 operators 注入场景 optimizer 的 dependencies
- [x] 单元测试

**交付物**：`run_optimization()` 使用 RemoteAgent 完成端到端训练循环（mock Adapter）。Review 改进（eba9e7d）：P1 fix — RemoteAgent.invoke() 兼容 Trainer 标准 inputs 格式（`conversation_id` + `query`，无需 `case_id`/`run_id`）；DeprecationWarning for `create_evo_agent`；test fixture 重构。测试 114 个。

---

## Wave 4: 场景化开发

> 目标：落地真实业务场景。可与 Wave 5 并行。

**前置依赖**：Wave 3

### 4.1 场景配置（scenario.yaml 更新）

```yaml
schema_version: "1.0"
optimizer_class: optimizer.FundAdvisorOptimizer
adapter_url: "http://localhost:9090"
skills:
  - name: product_recommend_skill
    optimize: true
  - name: interact_finance_rec_skill
    optimize: true
  - name: fund_planning_skill
    optimize: false  # 仅作文档说明，EvoAgent 不拉取、不构建 operator、不报告
rollout:
  max_turns: 10  # 多轮追问上限
  extra_data:
    role_id: "1"
    role_name: "mobile-bank"
hyperparams:
  batch_size: 8
  num_parallel: 8
```

- [ ] 定义 scenario.yaml 结构更新
- [ ] `SkillLoader` 更新 — 支持从场景文件夹批量加载多个 skill
- [ ] 单元测试

### 4.2 EvoCase 数据适配层（`dataset/case.py`）

- [ ] 定义 `EvoCase` dataclass：
  ```python
  @dataclass(frozen=True)
  class EvoCase:
      case_id: str
      queries: list[str]                          # 多轮 query 列表，至少一个
      extra_data: dict = field(default_factory=dict)  # case 级别额外数据（透传给 Adapter）
      expected_behavior: str = ""                  # 期望行为描述，供 evaluator 使用
  ```
- [ ] 数据集字段映射（原始 JSON → EvoCase）：
  | 数据集字段 | EvoCase 字段 | 说明 |
  |-----------|-------------|------|
  | `id` | `case_id` | 直接映射 |
  | `inputs: list[str]` | `queries` | 直接映射，天然多轮 |
  | `expected_behavior: str` | `expected_behavior` | 供 evaluator 评分参考 |
  | `type`, `source_trajectory`, `source_split` | 忽略 | EvoCase 不需要 |
- [ ] EvoCase → agent-core Case 转换：
  ```python
  Case(
      case_id=evo.case_id,
      inputs={"query": evo.queries[0], "queries": evo.queries},
      label={"expected_behavior": evo.expected_behavior},
  )
  ```
- [ ] extra_data 合并规则：scenario.yaml `rollout.extra_data` 作为 base，case 级 `extra_data` shallow merge 覆盖
- [ ] run_id 从 `ScenarioContext.run_id` 传入，不是 case 的属性
- [ ] 编写 `EvoCase → agent-core Case` 转换函数
- [ ] 更新 `dataset/manifest.py` 的 `_load_cases` — 先解析为 EvoCase，再转换
- [ ] 单元测试

### 4.3 场景 Optimizer 子类（`scenarios/edp_agent/`）

- [ ] 场景文件夹搭建：`scenario.yaml` + `optimizer.py` + `skills/` + `prompts/`
- [ ] `EDPAgentOptimizer` 子类覆写 `_rollout`
  - 调用 `self._agent.invoke()` 完成多轮对话（RemoteAgent 遍历 queries 列表）
  - 通过 `self._agent.adapter_client.get_traces()` 获取清洗后轨迹（`messages` 数组）
  - 将 `messages` 转换为 `Trajectory`（含 TrajectoryStep）
  - 用 evaluator 评估 `answer`（invoke 响应中的最终回复文本）
  - 返回 `(evaluated, trajectories)`
- [ ] `EDPAgentOptimizer` 覆写 `_format_single`
  - 将远程 trajectory 格式化为 reflect 阶段可用的文本
  - 过滤 usage_metadata 等噪音字段
- [ ] 冒烟测试 — 用真实 traces JSONL 验证 _format_single

### 4.4 多 skill 产物与报告模型

- [ ] Artifact 目录结构（per-skill 子目录）：
  ```
  artifacts/{run_id}/
  ├── metrics.json              # 全局汇总（epoch 耗时、总 token）
  ├── {skill_a}/
  │   ├── epoch_{n}_patch.md
  │   └── final_skill.md       # 优化后文档
  └── report.json               # 完整报告
  ```
- [ ] 报告结构：per-skill score + overall 汇总
  ```json
  {
    "score_before": {"skill_a": 0.45, "skill_b": 0.60, "overall": 0.52},
    "score_after":  {"skill_a": 0.78, "skill_b": 0.75, "overall": 0.76},
    "skills": [
      {"name": "skill_a", "edits_applied": 3, "score_delta": 0.33},
      {"name": "skill_b", "edits_applied": 2, "score_delta": 0.15}
    ]
  }
  ```
- [ ] Score 按 per-skill 独立评估，overall 取均值
- [ ] 更新 `reporter/formatter.py` 适配多 skill 目录结构
- [ ] 单元测试

### 4.5 旧场景清理（两步走）

- [ ] **Step 1**：迁移 OptimizeRequest 默认值、所有测试、示例文档到 `edp_agent`（或保留最小 `sample` 场景）
- [ ] **Step 2**：确认无回归后删除 `scenarios/example/`
- [ ] 删除 `remote/client.py`（被 `adapter_client/client.py` 替代）

**交付物**：`edp_agent` 场景可运行端到端优化（依赖 Adapter + Evaluator 就绪）。

---

## Wave 5: API 层 + 实时推送

> 目标：平台集成 + 实时监控。可与 Wave 4 并行。

**前置依赖**：Wave 3

### 5.1 API 请求体扩展（`api/routes/optimize.py`）

```json
{
  "scenario": "edp_agent",
  "adapter_url": "http://adapter-host:9090",
  "agent_name": "edp_agent",
  "skills": ["product_recommend_skill", "interact_finance_rec_skill"],
  "optimizer_type": "skill_opt",
  "evaluator_prompt": "请评估以下对话结果是否符合...",
  "hyperparams": {"num_epochs": 5, "batch_size": 8},
  "dataset_manifest_path": "/data/edp_dataset/dataset.yaml",
  "optimizer_template_id": "tmpl_123"
}
```

- [ ] `POST /optimize` 请求体支持完整参数
- [ ] 请求校验：adapter_url + agent_name 必填，scenario 必须存在
- [ ] 单元测试

### 5.2 SSE 实时推送（`api/routes/optimize.py`）

- [ ] `GET /optimize/{job_id}/stream` — SSE 端点
- [ ] 事件类型：`progress`、`log`、`completed`、`error`
- [ ] 连接时先重放 Job log buffer 中的历史事件
- [ ] 实时推送后续事件
- [ ] 断线重连支持（`Last-Event-ID`）
- [ ] 单元测试

### 5.3 Job event buffer（`api/jobs.py`）

- [ ] `Job` 增加 `event_buffer: deque[SSEEvent]`（环形缓冲，最近 1000 条）
  ```python
  @dataclass(frozen=True)
  class SSEEvent:
      id: int           # 自增序号，用于 Last-Event-ID
      event: str        # "progress" | "log" | "completed" | "error"
      data: dict        # 事件 payload
      timestamp: float
  ```
- [ ] `ProgressCallback` 同时写入 event buffer 和 progress 字段
- [ ] 单元测试

### 5.4 DFX 基础

- [ ] structlog contextvars 注入 run_id
- [ ] 日志格式统一为 JSON（带 run_id/epoch/step 字段）
- [ ] `ProgressCallback` 记录 epoch 耗时、累计 token 用量
- [ ] 单元测试

**交付物**：平台可通过 API 下发优化任务，通过 SSE 实时获取进度和日志。

---

## Wave 6: 入口适配

> 目标：统一所有入口（Agent 对话 / CLI / 平台 API）到新架构。

**前置依赖**：Wave 3, Wave 5

### 6.1 optimize_skill（A 入口）

- [ ] 更新 `skills/optimize_skill/SKILL.md`
  - 接收结构化 JSON 参数（scenario、dataset_manifest、adapter_url、num_epochs）
  - 不再传 skill_path
- [ ] 更新 `scripts/run_optimize.py`
  - 解析新参数 → 构造新 OptimizeRequest → 调 `run_optimization()`
- [ ] 冒烟测试 — 通过 EvoAgent 对话触发优化

**交付物**：三种入口（对话 / CLI / API）均可触发优化任务。

---

## 关键路径

```
Wave 1 ──→ Wave 2 ──→ Wave 3 ──→ Wave 4 ──→ Wave 6
                                ↘ Wave 5  ↗
```

- Wave 4 和 Wave 5 可并行开发
- 关键瓶颈在 Wave 3（编排层重写），是后续所有 wave 的前置
- Wave 4 的实际联调依赖 Adapter + Evaluator 外部就绪

---

## 已确认的设计决策

> 以下决策经 grill 讨论确认，开发时直接参照执行。

| # | 决策 | 结论 | 理由 |
|---|------|------|------|
| D1 | Agent 实现方式 | `RemoteAgent(BaseAgent)` 最小实现 | Trainer 需要真实 agent 接口；rollout 通过 AdapterClient 完成 |
| D2 | 继承关系 | 直接继承 `BaseAgent`，不继承 `ReActAgent` | 不需要 ReAct 循环逻辑，rollout 全在远端 |
| D3 | 抽象方法 | `invoke()` 委托 AdapterClient；`stream()` 最小实现；`configure()` 空实现；新增 `get_operators()` | `Trainer._get_operator_registry()` 需要 `get_operators()` |
| D4 | Session 处理 | 传透不记录 tracer | 验证评估只需 answer；训练 trajectory 从 Adapter cleaned-traces 获取 |
| D5 | Operator 工厂 | 直接实例化 agent-core `SkillDocumentOperator` | 工厂只是胶水代码，绑定 `on_parameter_updated` callback |
| D6 | RemoteAgent 位置 | `src/evo_agent/adapter_client/remote_agent.py` | 与 AdapterClient 同层，逻辑耦合 |
| D7 | Skill 初始内容 | 从 Adapter 端点拉取（`skill_list` / `skill_content`） | 基于实际部署版本优化，避免覆盖 |
| D8 | Optimizer 访问 AdapterClient | 通过 `self._agent`（RemoteAgent）间接访问 | RemoteAgent 封装所有远程通信 |
| D9 | 评估器 | 同事开发中，基于 BaseEvaluator，不改接口。通过 `evaluator_prompt` 构造评估器对象 | A 入口从 scenario.yaml 读 prompt，B 入口从 API 请求体下发 |
| D10 | EvoCase | 需要，数据格式不匹配 agent-core Case | 本地数据集格式 → agent-core Case 的适配层 |
| D11 | POST 语义 | 同步返回 `{success, answer, interrupted, events}`（Adapter 消费 SSE 后返回） | 业务失败通过 `success=false` 表达（HTTP 仍为 200）；`interrupted` 标记是否被 VA delegate 中断 |
| D12 | 多轮对话 | 遍历 case queries 列表，max_turns 兜底；`interrupted` 仅作标记不驱动循环 | Adapter 不返回是否继续追问的 flag，EvoAgent 按 queries 列表全量执行 |
| D13 | Operator 回写 | callback 签名 `(target: str, new_content: Any) -> None`，用同步 httpx | Trainer 更新链路是同步的，不能改 agent-core；SkillDocumentOperator.set_parameter/load_state 第一个参数是 target 名称，第二个是内容 |
| D14 | 多 skill 报告 | per-skill 子目录 + per-skill score + overall 汇总 | 每个 skill 独立评估，全局汇总取均值 |
| D15 | adapter_url 配置 | A 入口只用 scenario.yaml，B 入口只用 API 请求体；同入口内不做隐式 merge | 避免跨来源覆盖导致的连接错误 |
| D16 | 非优化 skill | 不管，不拉取、不构建 operator、不报告 | 业务 Agent 自带这些 skill，EvoAgent 不碰 |
| D17 | 安全鉴权 | v1 deferred，不做 allowlist 和 token | 首版内部使用，后续迭代 |
| D18 | 多轮职责划分 | `RemoteAgent.invoke()` 遍历 queries 列表并返回最终响应 `{answer, interrupted, events}`；兼容 Trainer 标准格式 `{**case.inputs, "conversation_id": case.case_id}`（`case_id` 作为别名，`run_id` 缺失时自动生成）；`_rollout()` 只调 invoke + get_traces，不重复实现多轮逻辑 | 避免两套多轮实现；Trainer 不传 case_id/run_id |
| D19 | trajectory 传递给 evaluator | `_rollout` 通过 `get_traces()` 获取 `messages`，转换为 Trajectory 供 evaluator 使用 | cleaned-traces 已过滤 system 角色和 usage_metadata |
| D20 | 防御性输入校验 | `agent_name` 非空校验（`__init__`）；`queries` 过滤空条目后判空；`update_skill` 同步 retry 与 async 一致 | 防止无效 URL 路径、空 query 发送、瞬态 502 中断训练 |

---

## 不再做的事项

| 原计划 | 原因 |
|--------|------|
| SkillSyncer 协议（FileSkillSyncer、HttpSkillSyncer、CompositeSkillSyncer） | 被 `AdapterClient.update_skill()` 替代 |
| RemoteTargetAgentProxy（实现 BaseAgent 接口） | 简化为 RemoteAgent；rollout 由 optimizer._rollout 通过 AdapterClient 完成 |
| RemoteAgentClient 两步式重写 | Adapter 内部实现细节，对 EvoAgent 透明 |
| PlatformResolver | 平台直接下发完整参数，不需要 EvoAgent 拉资源 |
| stub agent | 改为 RemoteAgent(BaseAgent) 最小实现 |

---

## 代码变更总览

### 直接复用（不改或微调）

| 模块 | 说明 |
|------|------|
| `paths.py` | 路径常量 |
| `skill_loader.py` | 加载 SKILL.md |
| `reporter/formatter.py` | 读取 artifact 目录 |
| `dataset/manifest.py` | YAML 解析 + CaseLoader + Evaluator |
| `agent.py` | `create_evo_agent()` 已加 DeprecationWarning，保留给 A 入口过渡，Wave 4.5 删除 |

### 重命名

| 模块 | 改为 | 说明 |
|------|------|------|
| `adapter/` | `scenario/` | 避免与 Adapter sidecar 命名冲突 |

### 需要改动

| 模块 | 改动点 |
|------|--------|
| `types.py` | OptimizeRequest 扩展字段 |
| `config.py` | EvolveConfig 移除 remote_endpoint，不加 adapter_url（per-scenario 配置） |
| `callbacks.py` | 移除 `skill_sync_callback` 参数 |
| `scenario/registry.py` | 支持 skills 列表 + 新依赖注入 |
| `api/routes/optimize.py` | 请求体扩展 + SSE 端点 |
| `api/jobs.py` | Job 增加 log buffer |
| `api/progress.py` | 写入 log buffer |

### 需要重写

| 模块 | 原因 |
|------|------|
| `optimizer_runner.py` | 核心重构：RemoteAgent + AdapterClient 替代 create_evo_agent + RemoteAgentClient |
| `skills/optimize_skill/SKILL.md` | 接收新参数 |
| `skills/optimize_skill/scripts/run_optimize.py` | 跟随 SKILL.md 更新 |

### 新建

| 模块 | 说明 |
|------|------|
| `adapter_client/client.py` | AdapterClient（invoke, skill 操作） |
| `adapter_client/remote_agent.py` | RemoteAgent(BaseAgent) |
| `adapter_client/operator.py` | `build_skill_document_operator()` 工厂 |
| `dataset/case.py` | EvoCase dataclass + 转换函数 |
| `api/routes/optimize.py` SSE | `GET /optimize/{job_id}/stream` |
| `scenarios/edp_agent/` | 真实业务场景 |

### 删除

| 模块 | 原因 |
|------|------|
| `remote/client.py` | 被 `adapter_client/client.py` 替代 |
| `scenarios/example/` | 被 `scenarios/edp_agent/` 替代 |

---

## 待确认点

> 以下问题阻塞或影响开发，需与对应模块负责人对齐。

### Adapter sidecar

> 消费方契约已定义：[`docs/api/adapter-api-contract.md`](api/adapter-api-contract.md)
> 以下待确认点需要 Adapter 实现方 review 并确认。

| # | 待确认点 | 影响范围 | 优先级 |
|---|---------|---------|--------|
| A1 | **契约 review**：Adapter 实现方是否认可 `docs/api/adapter-api-contract.md` 中定义的 5 个接口及其 request/response schema？是否有需要调整的字段？ | Wave 2 — AdapterClient 实现 | P0 |
| A2 | **cleaned-traces messages 字段映射**：`messages[]` 中 assistant 角色的 `tool_calls` 和 tool 角色的 `content` 格式是否与业务 Agent 一致？能否直接映射为 agent-core 的 `TrajectoryStep`？ | Wave 4 — `_rollout` 中 messages → Trajectory 转换 | P0 |
| A3 | **extra_data 透传**：Adapter 将 `extra_data` 合并到 `custom_data.inputs` 中转发给业务 Agent，是否有保留字段或大小限制？ | Wave 4 — scenario.yaml `rollout.extra_data` 设计 | P2 |

### Evaluator（评估器团队）

| # | 待确认点 | 影响范围 | 优先级 |
|---|---------|---------|--------|
| E1 | **评估器类名与 import 路径**：同事基于 BaseEvaluator 开发的评估器具体类名是什么？从哪个模块 import？（如 `from openjiuwen.agent_evolving.evaluator.xxx import YyyEvaluator`） | Wave 3 — optimizer_runner.py 构建 evaluator 实例 | P0 |
| E2 | **构造函数签名**：评估器的 `__init__` 接收哪些参数？除 `evaluator_prompt` 外是否还需要 LLM config、model 等？ | Wave 3 — runner 构建 evaluator 时传参 | P0 |
| E3 | **轨迹获取机制**：评估器如何获取 trajectory？构造函数注入？还是 evaluate() 时 predict dict 里自带？ | Wave 4 — `_rollout` 覆写中评估器调用方式 | P1 |
| E4 | **评估器与多 skill**：评估器是 per-skill 还是 per-case？多 skill 场景下一个 case 的评估是否涉及多个 skill 的交叉影响？ | Wave 4 — edp_agent 场景设计 | P2 |

### agent-evolution 平台

| # | 待确认点 | 影响范围 | 优先级 |
|---|---------|---------|--------|
| P1 | **POST /optimize 请求体完整字段**：平台下发的 JSON schema 是什么？除 ROADMAP 中列出的字段外，是否还有其他字段？ | Wave 5 — API 请求体扩展 | P1 |
| P2 | **优化器模板**：`optimizer_template_id` 对应什么数据结构？平台是否会下发模板内容，还是只是 ID 引用？ | Wave 5 — API 请求校验 | P2 |
| P3 | **SSE 事件消费**：平台后端是否直接消费 EvoAgent 的 SSE 流？是否需要额外的鉴权（API key / token）？ | Wave 5 — SSE 端点安全 | P2 |

---

## ADR-0003 修订记录

> 基于 grill 讨论，以下内容需要在开发前更新到 ADR-0003。

| 原文 | 修正 | 位置 |
|------|------|------|
| "简化为最小 stub agent" | 改为 `RemoteAgent(BaseAgent)` 最小实现 | Decision 1, Decision 5 |
| "`Trainer.train(agent=stub)` 中的 agent 参数仅为接口兼容" | Trainer 需要 agent 的 `get_operators()` 方法，不仅是接口兼容 | Decision 1 |
| "rollout 由场景 optimizer 子类的 `_rollout()` 通过 `AdapterClient` 完成" | optimizer 通过 `self._agent`（RemoteAgent）间接访问 AdapterClient | Decision 1 |
| "两步式异步 + 轮询" | POST 同步返回 `{success, answer, interrupted, events}`；GET cleaned-traces 返回 `{messages, trajectory}` | Decision 1 |
| "predict 和 calls 在 traces 响应中一起返回" | POST 返回 answer，GET cleaned-traces 返回 messages（已清洗） | Decision 1 |
| "expects_followup 驱动多轮循环" | 遍历 queries 列表，interrupted 仅作标记不驱动循环 | Decision 11, 12 |
| "custom_data 作为请求字段" | 改为 extra_data（Adapter 内部合并到 custom_data.inputs） | API 契约 |
| "error.code/error.message 错误格式" | FastAPI `{detail: "..."}` 格式 + success=false 业务错误 | API 契约 |
| "Adapter 1:1 绑定 Agent" | 全部接口 1:N，路径和请求体均含 agent_name | 部署模型 |
