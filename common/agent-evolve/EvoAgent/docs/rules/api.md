# API 层约定

## 定位

EvoAgent 只暴露**优化执行** API。数据集和评估器的 CRUD 由平台侧管理。

## 双入口模型

```
入口 1: CLI（现场部署，无平台）
  scripts/run_optimize.py → resolve_params() → run_optimization()  ← 直接同步调用

入口 2: API（线上部署，有平台）
  api/routes/optimize.py → _normalize() → JobManager → run_optimization()  ← 异步任务
```

`optimizer_runner.run_optimization()` 是唯一的编排入口，API 层只是 HTTP 壳。

## 目录结构

```
src/evo_agent/api/
├── app.py           # FastAPI app 实例 + 路由注册 + /health
├── jobs.py          # JobManager + Job + JobStatus + JobProgress（含 cancel）
├── progress.py      # ProgressCallback（Trainer callbacks → Job 状态 + val 分数 + phase 事件）
├── events.py        # SSEEvent + EventType + PipelinePhase 枚举
├── sse.py           # SSE 格式化（format_sse）
├── logging_config.py # 日志配置
├── resources.py     # ResourceResolver 协议 + LocalResolver（已废弃，见 ADR-0005）
└── routes/
    ├── optimize.py  # POST /optimize, GET /optimize/{job_id}, GET /optimize/{job_id}/stream, POST /optimize/{job_id}/cancel
    ├── evaluate.py  # POST /evaluate（同步评估单条轨迹）
    └── scenarios.py # GET /scenarios
```

## 端点清单

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/optimize` | 提交优化任务，返回 `job_id`（异步） |
| `GET` | `/optimize/{job_id}` | 轮询任务状态与结果 |
| `GET` | `/optimize/{job_id}/stream` | SSE 实时推送进度与 phase 事件 |
| `POST` | `/optimize/{job_id}/cancel` | 取消运行中的任务 |
| `POST` | `/evaluate` | 同步评估单条轨迹（filter + LLM judge，不走优化管线） |
| `GET` | `/scenarios` | 列出已注册场景 |
| `GET` | `/health` | 健康检查 |

## 平台模板 API 模型（Wave 8）

API 模式使用嵌套模板结构，由平台配置管理：

```python
class OptimizerTemplateRequest(BaseModel):
    name: str                    # 映射到 scenario
    scenario: str                # 业务场景标签（仅元数据）
    hyperparams: dict[str, Any]  # 超参（num_epochs/batch_size 可提取）
    train_split: float = 0.8
    val_split: float = 0.2

class EvaluatorTemplateRequest(BaseModel):
    name: str
    scenario: str
    prompt: str = ""

class OptimizeAPIRequest(BaseModel):
    task_name: str
    agent_name: str
    optimizer_type: str = "skill"
    optimizer_template: OptimizerTemplateRequest
    evaluator_template: EvaluatorTemplateRequest
    skills: list[str] = []
    dataset_path: str            # 原始数据文件路径（通过路径安全校验）
```

**路径安全校验** (`validate_dataset_path`):
- 文件存在性检查
- 必须在 `allowed_data_roots` 下（防路径穿越）
- 文件大小 ≤ 500MB

**归一化** (`_normalize()`):
- 字段映射：`optimizer_template.name` → `scenario`
- 从 `hyperparams` dict 提取 `num_epochs` / `batch_size` 为 typed 字段
- 注入 `adapter_url` 从 `EvolveConfig`

详见 `docs/adr/0005-platform-template-driven-api.md`。

## ResourceResolver（已废弃）

> **注意**：ResourceResolver 和 LocalResolver 已在 Wave 8 中废弃（ADR-0005 Decision 7）。
> API 模式现在使用 `_normalize()` + `build_dataset_from_request()` 替代。

将资源引用（ID 或路径）解析为运行时实例（CaseLoader、BaseEvaluator）：

- `LocalResolver`: 从本地文件加载（现场部署）— **已废弃**
- `PlatformResolver`: 调平台 API 获取（线上部署）— **不再实现**（ADR-0005）

`optimizer_runner` 不再接收 `ResourceResolver` 实例，改为双轨分支：
- CLI 模式：`load_dataset_manifest()` 从 dataset.yaml 加载
- API 模式：`build_dataset_from_request()` 从原始数据文件构建

## 长时间运行优化

异步任务模式：`POST /optimize` 返回 `job_id`，`GET /optimize/{job_id}` 轮询进度。
进度由 `ProgressCallback` 在 `on_train_epoch_end`、`on_step_end` 等钩子中写入 Job 状态。

SSE 实时推送：`GET /optimize/{job_id}/stream` 使用 Server-Sent Events 推送进度更新。

任务取消：`POST /optimize/{job_id}/cancel` 置 Job 为 cancelled 状态，运行中的协程通过取消信号退出。

## SSE 事件体系（Wave 10）

`api/events.py` 集中管理事件类型与阶段常量：

- `EventType`：`progress` / `log` / `completed` / `error`
- `PipelinePhase`：`train_begin` / `epoch_begin` / `rollout` / `rollout_done` / `evaluate` / `attribute` / `reflect` / `aggregate` / `select` / `apply` / `skill_sync` / `validation` / `epoch_end` / `train_end`

optimizer 各阶段通过 `phase_callback(event, data)` 推送 `log` 事件 —— `phase_callback` 由 `optimizer_runner` 注入闭包，API 层将其写入 Job 事件流并经 SSE 下发。场景 optimizer 覆写 `_rollout` / `_attribute` / `_reflect` / `_aggregate` / `_select` / `_backward` 时负责推送对应 phase 事件。

详见 `docs/handoff/2026-06-16-wave10-complete.md`。

## 运行时配置合并

API 层 `_normalize()` 把 `OptimizeAPIRequest` 转为内部 `OptimizeRequest` 后，`optimizer_runner` 通过 `OptimizationConfigResolver`（`runtime_config.py`）合并 request / scenario preset / env 默认值，输出 `ResolvedOptimizationConfig`。runner 不再自己拼装超参，避免来源混乱。详见 [architecture.md](architecture.md)。
