# 测试规范

## 文件组织

```
tests/
├── conftest.py                          # 公共 fixture
├── unit/
│   ├── evaluator/                       # 评估器子套件（LLM / metric / trajectory 简化 / models）
│   ├── test_adapter_client.py           # AdapterClient HTTP 通信层
│   ├── test_api_optimize.py             # POST /optimize 端点
│   ├── test_api_scenarios.py            # GET /scenarios 端点
│   ├── test_api_template_request.py     # 平台模板 API 模型 + 路径安全校验
│   ├── test_artifact_exporter.py        # artifact 导出
│   ├── test_aggregate_merge_parallel.py # 同 operator failure/success merge 并发（C3）
│   ├── test_build_dataset.py            # build_dataset_from_request（API 模式）
│   ├── test_build_operators.py          # _build_operators + 并发拉 skill（C5）
│   ├── test_callback_order.py           # ComposedCallbacks 顺序正确性
│   ├── test_comparison_text_reuse.py    # comparison_text 复用（A7）
│   ├── test_concurrency.py             # gather_with_semaphore 并发模型（C0）
│   ├── test_config.py / test_scenario_config.py # EvolveConfig / ScenarioConfig
│   ├── test_conversation.py             # ConversationIdFactory
│   ├── test_cross_operator_*.py         # 跨 operator reflect/aggregate 并发（C2/C4）
│   ├── test_dataset_manifest.py         # load_dataset_manifest
│   ├── test_debug_logging_cleanup.py    # 生产路径 DEBUG print 已移除（A2）
│   ├── test_dfx_logging.py              # 可观测性日志
│   ├── test_edp_optimizer.py            # EDPAgentOptimizer 子类验证
│   ├── test_enable_attribution.py       # validation enable_attribution=False（B2）
│   ├── test_evo_case.py / test_job_cancel.py # case 模型 / 任务取消
│   ├── test_model_copy_isolation.py     # model_copy deep=False 隔离（A6）
│   ├── test_normalize*.py               # _normalize API→内部 request 转换
│   ├── test_operator_factory.py         # build_skill_document_operator
│   ├── test_optimizer_runner.py         # pipeline 组装 + phase_callback 注入
│   ├── test_progress_callback.py        # ProgressCallback + val 分数
│   ├── test_prompt_cache.py             # load_skill_opt_prompt lru_cache（A1）
│   ├── test_prompt_json_compact.py      # JSON 紧凑序列化（A4）
│   ├── test_prompts.py                  # prompt 覆盖机制
│   ├── test_read_file_truncation.py     # read_file 结果截断（A9）
│   ├── test_registry.py                 # ScenarioRegistry（optimizer 类加载）
│   ├── test_remote_agent.py             # RemoteAgent(BaseAgent)
│   ├── test_reporter.py                 # ReportFormatter（train/val 分组）
│   ├── test_retry_prompt.py             # reflect/aggregate 精简 retry_prompt（B4）
│   ├── test_run_optimize_script.py      # CLI 入口脚本
│   ├── test_runtime_config.py           # OptimizationConfigResolver 合并策略
│   ├── test_sample_cases.py             # _sample_cases random.sample（A5）
│   ├── test_skill_loader.py             # SkillLoader
│   ├── test_sse_*.py                    # SSE 端点 + 事件体系
│   ├── test_timeout_policy.py           # attempt_timeout_secs 收紧（A8）
│   ├── test_trainer.py                  # EvoTrainer
│   ├── test_trajectory_serialization.py # 消除序列化往返（B3）
│   └── test_types.py                    # OptimizeRequest / OptimizeReport
└── integration/
    ├── test_optimize_flow.py            # 端到端 mock
    ├── test_optimize_result_v2.py       # Wave 10 train/val 结果结构
    └── test_sse_event_stream.py         # SSE 事件流端到端
```

> 新增测试文件遵循 `test_<模块或主题>.py` 命名；性能优化项（ADR-0006）的测试以 commit 标签（A1–A9 / B2–B4 / C0–C6）在文件名或注释中标注来源。

## 命名约定

- 文件: `test_<module>.py`
- 函数: `test_<行为描述>()`
- fixture: `<名词短语>`

## 覆盖要求

- 每个公共方法至少一个正向测试 + 一个异常路径测试
- registry：optimizer 类加载、hyperparams 合并、_filter_kwargs、模块隔离
- callbacks：ComposedCallbacks 顺序约束、SkillDocumentCallbacks 始终第一
- runtime_config：request > scenario preset > env 默认值的合并优先级、类型校验与边界
- concurrency：`gather_with_semaphore` 并发上限、semaphore 不重入（裸 gather 仅限内部已 acquire 的协程）
- optimizer 子类：继承关系、覆写方法委托、phase 事件推送
- prompts：场景覆盖优先、agent-core fallback、找不到报错、lru_cache 命中
- reporter：train/val 分组、summary.json 读取、gate 结果收集、artifact 缺失降级
- SSE：phase_callback 注入、事件类型/阶段枚举、端到端事件流

## Mock 策略

| 外部依赖 | Mock 方式 |
|---------|----------|
| 远程 Agent HTTP | `pytest-httpx` 或 `httpx.MockTransport` |
| LLM 调用 | 覆写 `_reflect` / `_aggregate` / `_select` 返回固定结果 |
| 文件系统 | `tmp_path` fixture |
| agent-core 类 | `MagicMock` / `AsyncMock` |

## 运行

```bash
make test        # 全部测试
make test-unit   # 仅单元测试
```
