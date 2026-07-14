# EvoAgent — Domain Context

## Glossary

### Scenario（业务场景）
一个具体的 Agent 使用场景（如基金顾问、客服对话等），每个场景在 rollout 方式、trajectory 清洗、反思逻辑、评估方式、skill 回写方式上可能有不同的实现。

### ReflACT Pipeline
SkillDocumentOptimizer 内部的优化循环：Rollout → Format → Split(failures/successes) → Attribute → Reflect → Aggregate → Select → Backward(apply patch)。多 skill 场景下 Attribute 为独立步骤，单 skill 场景下自动短路。每个阶段的实现可能因场景而异。

### Rollout
执行 case 并收集 trajectory 的过程。可以是本地的（直接调用 agent）或远程的（通过 HTTP 调用远程 agent）。

### Trace Cleaning（轨迹清洗）
将异构的原始执行轨迹（如 OpenTelemetry span、自定义日志格式）归一化为标准结构化格式的过程。在 `_format_single` 中完成（先 clean 再 format）。不同场景的原始轨迹格式可能完全不同。

### Attribution（归因）
在多 skill 场景中，分析执行轨迹和评估结果，判断哪些 skill 导致了失败的过程。位于 ReflACT 管线的 split 和 reflect 之间。对 failures 做精准归因（定位出错的 skill），对 successes 做全量归属（参与的 skill 都算贡献）。单 skill 场景下自动短路，无需 LLM 调用。

### Skill Sync（Skill 回写）
优化完成后将更新后的 skill 内容持久化的过程。可以是本地文件替换或远程 API 推送。通过 Callbacks（如 `RemoteSkillSyncCallback`）在 epoch_end 和 train_end 时触发。

### ResourceResolver（资源解析器，已废弃）
将资源引用（ID 或文件路径）解析为运行时实例（CaseLoader、BaseEvaluator）的协议。已在 Wave 8（ADR-0005 Decision 7）废弃。API 模式现在使用 `_normalize()` + `build_dataset_from_request()` 替代，`optimizer_runner` 不再接收 `ResourceResolver` 实例。代码保留仅为兼容参考。

### Scenario Folder（场景文件夹）
`scenarios/<name>/` 下的自包含目录，包含一个场景所需的所有文件：scenario.yaml（optimizer 类注册 + 超参数）、optimizer.py（SkillDocumentOptimizer 子类）、prompts/（prompt 覆盖）和 skills/（初始 skill 文档）。ScenarioRegistry 加载时动态注入模块路径，无需 pip install。

### Scenario Registry（场景注册表）
根据 `OptimizeRequest.scenario` 字段查找 `scenarios/<name>/` 场景文件夹，加载 scenario.yaml 中的 `optimizer_class`，实例化并注入依赖（agent、evaluator、llm 等）。

### ResolvedOptimizationConfig（运行时配置）
一次优化任务所需的完整配置。由 `OptimizationConfigResolver`（`runtime_config.py`）按 **request 字段 > scenario preset (`scenario.yaml` hyperparams) > env 默认值 (`EvolveConfig`)** 三层合并产出。runner 不再自己拼装超参，所有 typed 字段（num_epochs/batch_size/parallelism/score_threshold 等）与 `extra_hyperparams` 在此定型。

### LLM 并发闸门（Concurrency Semaphore）
所有 LLM 调用受单一 `self._semaphore`（`parallelism`）封顶。跨 operator 的 reflect / aggregate / select / slow_update 通过 `asyncio.gather` 并行（协程内部已 acquire semaphore），无内部 acquire 的并发用 `gather_with_semaphore`。两种 sanctioned 模式定义在 `optimizer/concurrency.py`，禁止引入第三种。详见 ADR-0006。

### SSE 事件体系（可观测性）
optimizer 各阶段通过注入的 `phase_callback(event, data)` 推送 `log` 事件，经 `GET /optimize/{job_id}/stream` 实时下发。`api/events.py` 集中管理 `EventType`（progress/log/completed/error）与 `PipelinePhase`（rollout/attribute/reflect/aggregate/select/apply/validation/...）常量。Wave 10 引入。

## Architecture（架构）

场景通过 **optimizer 子类化** 实现定制，不使用策略协议或组合容器。每个场景直接继承 `SkillDocumentOptimizer`（agent-core），覆写需要的方法：

```text
SkillDocumentOptimizer (agent-core)
    └── FundAdvisorOptimizer (scenarios/fund_advisor/optimizer.py)
         覆写 _rollout / _format_single / _reflect / _attribute / ...
```

场景可覆写的阶段：

| 阶段 | 方法 | 说明 |
|------|------|------|
| Rollout | `_rollout` | 执行 case + 收集轨迹 |
| Format | `_format_single` | 清洗 + 格式化 trajectory |
| Attribute | `_attribute` | 多 skill 归因 |
| Reflect | `_reflect` | 分析轨迹，生成 patches |
| Aggregate | `_aggregate` | 合并 patches |
| Select | `_select` | 排序 + 预算裁剪 |
| Apply | `_backward` | apply patch（编排，多步编辑每步推送 apply 事件） |
| Prompt | `_build_analyst_prompt` | analyst prompt 构造 |

不可覆写的阶段（Trainer/Updater 管理）：`backward`（编排）、`step`（validation gate）。覆写阶段时通过注入的 `phase_callback` 推送对应 `PipelinePhase` 事件以保持可观测性。

## Relationships

- OptimizeRequest.scenario 字段指定场景名
- ScenarioRegistry 根据场景名加载 `scenarios/<name>/scenario.yaml`，实例化 optimizer_class
- 场景 optimizer 直接继承 SkillDocumentOptimizer，可覆写任何管线阶段
- 多 skill 场景下，一个 optimizer 管理多个 operator，rollout 共享，backward 按 operator_id 分发
- RawPatch.operator_id 关联 patch 到目标 skill，单 skill 时自动填充
- Prompt 覆盖为场景级（不按 operator 区分），通过 `load_prompt(name, scenario_name)` 两级查找
- API 层（api/）接收外部请求，通过 JobManager 管理异步优化任务，ProgressCallback 采集进度 + val 分数 + phase 事件，经 SSE 下发
- 超参合并统一走 `OptimizationConfigResolver`，runner 不自行拼装

## Out of Scope

- agent-core / agent_evolving 的内部实现
- 远程 Rollout Agent 的部署和运维
- Evaluator 的具体评估算法（由 dataset manifest 声明）
