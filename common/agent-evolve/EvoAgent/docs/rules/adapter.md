# 场景适配层约定

## 核心机制

场景通过 **SkillDocumentOptimizer 子类化** 实现定制。场景开发者写一个继承
`SkillDocumentOptimizer` 的类，覆写需要的管线阶段方法。

## 场景文件夹结构

```
scenarios/<name>/
├── scenario.yaml       # optimizer_class + hyperparams
├── optimizer.py        # SkillDocumentOptimizer 子类
├── prompts/            # 可选 prompt 覆盖
│   ├── analyst_error.md
│   └── analyst_success.md
└── skills/
    └── initial.md      # 可选初始 skill
```

## scenario.yaml 格式

```yaml
schema_version: "1.0"
optimizer_class: optimizer.ExampleOptimizer
adapter_url: "http://localhost:9090"   # 场景级 adapter sidecar 地址（可被 request/config 覆盖）
skills:                                # 场景管理的 skill 列表
  - name: product_recommend_skill
    optimize: true                     # 是否参与优化
  - name: fund_planning_skill
    optimize: false
rollout:                               # rollout 配置
  max_turns: 10
  extra_data:                          # 注入 rollout 的场景数据（role_id 等）
    role_id: "1"
    role_name: "mobile-bank"
hyperparams:                           # 传给 SkillDocumentOptimizer 的超参（可被 request 覆盖）
  batch_size: 8
  num_parallel: 8
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `schema_version` | str | ✅ | YAML 格式版本 |
| `optimizer_class` | str | ✅ | 相对于场景文件夹的类路径 |
| `adapter_url` | str | ❌ | 场景级 adapter sidecar 地址（优先级：request > scenario > env） |
| `skills` | list | ❌ | skill 列表（`name` + `optimize`） |
| `rollout` | dict | ❌ | rollout 配置（`max_turns`、`extra_data`） |
| `hyperparams` | dict | ❌ | optimizer 超参，由 `OptimizationConfigResolver` 与 request/env 合并 |

## 可覆写的管线阶段

| 方法 | 用途 |
|------|------|
| `_rollout` | 自定义 rollout（如远程 agent 调用 + phase 事件推送） |
| `_format_single` | Trajectory 清洗 + 格式化 |
| `_attribute` | 多 skill 归因（单 skill 自动短路） |
| `_reflect` | 自定义反思逻辑（跨 operator 并发，受 semaphore 控制） |
| `_aggregate` | 聚合多个反思结果（failure/success merge 并发 + 跨 operator 并发） |
| `_select` | 选择最佳候选更新（跨 operator 并发） |
| `_backward` | apply patch（编排，多步编辑每步推送 apply 事件） |
| `_build_analyst_prompt` | 自定义 analyst prompt 构建 |

> 不可覆写的阶段（Trainer/Updater 管理）：`backward` 编排、`step`（validation gate）。覆写阶段时若需可观测性，通过注入的 `phase_callback` 推送对应 `PipelinePhase` 事件。

## ScenarioRegistry

`ScenarioRegistry` 是 optimizer 类加载器：
- 解析 `scenarios/<name>/scenario.yaml`
- 动态 import `optimizer_class`（场景内模块使用唯一模块名防冲突）
- 合并 `hyperparams` 与运行时 `dependencies`
- 通过 `_filter_kwargs` 只传递构造函数接受的参数

## Callback 顺序约束

通过 `ComposedCallbacks` 组合时顺序固定：
1. **SkillDocumentCallbacks** — 执行 `run_epoch_end()`（slow_update + meta_skill）
2. **skill sync callback** — 持久化最终 skill
3. **ProgressCallback** — 采集进度

## Prompt 覆盖

使用 `load_prompt(name, scenario_name)` 两级查找：
1. `scenarios/<name>/prompts/<prompt>.md` — 场景特定覆盖
2. agent-core `templates/<prompt>.md` — 通用 fallback

## 边界规则

### 始终做
- 场景 optimizer 直接继承 `SkillDocumentOptimizer`
- `scenario.yaml` 使用 `optimizer_class` + `hyperparams` 格式
- Callback 顺序固定

### 永远不做
- 重新引入策略协议（Protocol）或组合容器（ScenarioAdapter）
- 在 EvoAgent 层复制 ReflACT 编排逻辑
- 绕过 agent-core 的 patch routing 和 per-operator apply
