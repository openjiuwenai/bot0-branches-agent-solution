# 总体架构

## 项目定位

EvoAgent 是基于 agent-core 的 `SkillDocumentOptimizer` 做场景化开发的优化服务层。
通过场景 optimizer 子类化的方式实现 skill 文档自动优化。

## 上下游关系

```
用户/平台 → EvoAgent API 或 CLI
  → ScenarioRegistry (加载场景 optimizer 子类)
  → SkillDocumentOptimizer 子类 (直接继承 agent-core)
  → 远程/本地 Rollout Agent
  → agent-core (Trainer + SingleDimUpdater + Callbacks)
```

## 模块边界

```
src/evo_agent/
├── __init__.py          # 公共 API 导出
├── agent.py             # Agent 构建工厂（已废弃，后续重写为自主 Agent）
├── config.py            # 配置单例（叶子模块）
├── runtime_config.py    # OptimizationConfigResolver（request + scenario + env → ResolvedOptimizationConfig）
├── types.py             # 类型定义（叶子模块）
├── protocols.py         # 内部 Protocol 约定（叶子模块）
├── paths.py             # 路径工具（叶子模块）
├── skill_loader.py      # Skill 内容加载
├── trainer.py           # EvoTrainer（sidecar-aware + 轨迹注入）
├── conversation.py      # ConversationIdFactory（唯一 conversation_id 生成）
├── optimizer_runner.py  # 编排入口（唯一知道如何组装 pipeline 的模块，双轨分支 + phase_callback 注入）
├── callbacks/           # Callback 组合层
│   ├── composed_callbacks.py     # ComposedCallbacks（固化顺序约束）
│   ├── skill_document_callbacks.py # SkillDocumentCallbacks（slow_update + meta_skill）
│   └── remote_skill_sync_callback.py # RemoteSkillSyncCallback（skill 持久化）
├── optimizer/           # 优化引擎内部实现
│   ├── concurrency.py   #   gather_with_semaphore（LLM 并发受单一 semaphore 控制）
│   ├── llm_resilience.py #  LLM 重试 / 超时降级 / retry_prompt
│   ├── dict_optimizer.py #  字典式 optimizer
│   ├── artifact_exporter.py #  artifact 导出
│   └── skill_document/  #   ReflACT 管线（reflect/aggregate/select/apply/slow_update/meta_skill/scheduler/edit_apply）
├── operator/            # SkillDocumentOperator 工厂（build_skill_document_operator）
├── evaluator/           # 评估器（LLM / metric / filters / trajectory 简化 / domain models）
├── scenario/            # 场景适配层
│   ├── registry.py      #   ScenarioRegistry（optimizer 类加载器）
│   └── prompts.py       #   两级 prompt 查找（lru_cache）
├── adapter_client/      # Adapter sidecar 通信层
│   ├── client.py        #   AdapterClient — HTTP 通信（async + sync）
│   ├── remote_agent.py  #   RemoteAgent(BaseAgent) — 远程 Agent 代理
│   └── operator.py      #   build_skill_document_operator() 工厂
├── api/                 # 服务端 API
│   ├── app.py           #   FastAPI 实例 + 路由注册 + /health
│   ├── jobs.py          #   JobManager 异步任务管理（含 cancel）
│   ├── progress.py      #   ProgressCallback 进度采集 + val 分数 + phase 事件
│   ├── events.py        #   SSEEvent + EventType + PipelinePhase 枚举
│   ├── sse.py           #   SSE 格式化
│   ├── logging_config.py #  日志配置
│   ├── resources.py     #   ResourceResolver 协议 + LocalResolver（已废弃，见 ADR-0005）
│   └── routes/          #   路由端点（optimize / evaluate / scenarios，平台模板 API 模型）
├── dataset/
│   └── manifest.py      # dataset.yaml 解析 + build_dataset_from_request（API 模式）
└── reporter/
    └── formatter.py     # artifact → OptimizeReport（train/val 分组 + skill_scores）
```

## 依赖方向

```
scripts/ ─┐
api/     ─┤→ optimizer_runner.py → runtime_config.py (OptimizationConfigResolver)
          │                       → adapter_client/ (AdapterClient + RemoteAgent + operator)
          │                       → scenario/registry.py → config, types
          │                       → dataset/manifest.py (双轨：load_dataset_manifest / build_dataset_from_request)
          │                       → reporter/formatter.py
          │                       → callbacks/ (ComposedCallbacks + SkillDocumentCallbacks + RemoteSkillSync)
          │                       → trainer.py (EvoTrainer)
          │                       → conversation.py (ConversationIdFactory)
          │                       → optimizer/ (concurrency + llm_resilience + skill_document 管线)

adapter_client/ → httpx (async + sync)
                → openjiuwen.core.single_agent.base (RemoteAgent)
                → openjiuwen.core.operator (SkillDocumentOperator)
```

**双轨分支**（Wave 8）：
- CLI 模式：`dataset_manifest_path is not None` → `load_dataset_manifest()`
- API 模式：`dataset_manifest_path is None` → `build_dataset_from_request()`

**运行时配置合并**（Wave 8+）：
`OptimizationConfigResolver.resolve()` 统一合并三层来源 —— request 字段 > scenario preset (`scenario.yaml` hyperparams) > env 默认值 (`EvolveConfig`)，输出 `ResolvedOptimizationConfig`，runner 不再自己拼装超参。

## 设计原则

1. **最小包装**: 不重新包装 agent-core 的核心语义（CaseLoader、Trainer、Operator、Callbacks）
2. **子类化扩展**: 场景差异通过 SkillDocumentOptimizer 子类覆写管线阶段实现
3. **frozen dataclass**: 所有数据类使用 `@dataclass(frozen=True)`，防止共享状态被意外修改
4. **单一入口**: `optimizer_runner.py` 是唯一知道 pipeline 如何组装的模块
5. **双入口解耦**: CLI 直接调 `run_optimization()`；API 通过 JobManager 异步调用同一函数
6. **双模式支持**（Wave 8）: CLI 模式使用 `dataset.yaml`；API 模式使用原始数据文件 + 平台模板配置
7. **Callback 顺序约束**: SkillDocumentCallbacks → progress（正确性约束；skill 回写由 operator factory 的 on_parameter_updated callback 独立触发）
8. **单一并发闸门**（ADR-0006）: 所有 LLM 调用受 `self._semaphore`（`parallelism`）封顶；跨 operator 并行用裸 `asyncio.gather`（协程内部已 acquire semaphore），无内部 acquire 的并发用 `gather_with_semaphore`。两种 sanctioned 模式见 `optimizer/concurrency.py`，禁止引入第三种。
9. **可观测性注入**（Wave 10）: optimizer 各阶段通过 `phase_callback` 推送 `log` 事件，runner 注入闭包、API 层经 SSE 下发；phase 常量集中在 `api/events.py` 的 `PipelinePhase`。
