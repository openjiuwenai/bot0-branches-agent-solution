## TF-GRPO 测试用例

> **范围**：EvoAgent 新增优化器 `TfGrpoOptimizer`（Training-Free GRPO）——变体生成、经验库、语义优势、epoch 编排及与 Adapter 热更/rollout 的衔接。  
> **交付形态（主）**：**服务化部署** — FastAPI（`evo_agent.api.app`）对外提供 `POST /optimize` 等接口；场景名 `tf_grpo`。  
> **交付形态（辅）**：CLI `run_optimize.py`（同源 `run_optimization`，适合本地调试 / `dataset.yaml` 评估器）。  
> **资产**：`tests/unit/optimizer/tf_grpo/`；场景 `scenarios/tf_grpo/`；联调三容器 + EvoAgent API。  
> **不在范围**：自动任务生成、Studio 前端细节、共享挂载 vs 沙箱拓扑全量回归（见 Adapter `sandbox_mode`）。

### 0. 测试层级定义（业界口径）

| 层级 | 定义 | 本特性判定 |
| --- | --- | --- |
| **单元（Unit）** | 测单个模块/类；外部依赖隔离 | pytest；mock LLM / Adapter / Agent |
| **冒烟（Smoke）** | 部署后快速确认服务可达 | EvoAgent API + Adapter / EDP / jiuwenbox `/health`；`GET /scenarios` 含 `tf_grpo` |
| **集成（Integration）** | 组件间接口与协作 | 场景可加载；`POST /optimize` 接单 / 校验 / cancel；可选真实 `update_skill` |
| **系统（System）** | 真实栈上能力切片 | 固定 dataset 上 val 评估 / 单 epoch 可观测（API Job + SSE 或 artifacts） |
| **E2E（End-to-End）** | 类生产完整优化路径 | **服务化** `POST /optimize`（scenario=`tf_grpo`）→ 门控 → Job 完成 / artifacts |

脚本按执行入口聚合，**层级以用例为准**。交付验收以 **服务化接口路径** 为准。

### 0.1 双入口对照

| 项 | 服务化 API（交付主路径） | CLI（调试辅路径） |
| --- | --- | --- |
| 启动 | `uvicorn evo_agent.api.app:app`（Docker / `make serve`，常映射 **8000**；本地开发常见 **8001**） | `python skills/optimize_skill/scripts/run_optimize.py` |
| 触发优化 | `POST /optimize`，`optimizer_template.scenario=tf_grpo` | `--scenario tf_grpo` |
| 数据集 | `dataset_path` → cases 文件（如 `cases.json`）；切分用 `train_split`/`val_split` | `--dataset-manifest` → `dataset.yaml` |
| 评估器 | `evaluator_template.type` 默认 **`metric`**（可 `extract`）；语义评估显式 `type: llm` + `prompt` | manifest 声明 `evaluator.type`（metric / llm 等） |
| 进度 | `GET /optimize/{job_id}`；`GET /optimize/{job_id}/stream`（SSE） | `ConsoleProgressCallback` 打印 |
| 取消 | `POST /optimize/{job_id}/cancel` | Ctrl+C / 杀进程 |
| 依赖配置 | 进程环境 / `.env`：`EVO_ADAPTER_URL`、`EVO_LLM_*`、`EVO_ALLOWED_DATA_ROOTS` | 同左；CLI 可再传 `--adapter-url` |

### 0.2 环境与依赖

| 项目 | 要求 |
| --- | --- |
| Python | ≥ 3.12；evoagent `.venv` |
| pytest | ≥ 8；`pytest-asyncio` |
| 单元 | 无需真实 Adapter / LLM（mock） |
| 服务化冒烟/集成/E2E | **EvoAgent API** 可达；Adapter `http://127.0.0.1:18900`；EDP `18001`；jiuwenbox `8321` |
| 数据集 | cases 文件须在 `EVO_ALLOWED_DATA_ROOTS` 下（例：`workspace/datasets/.../cases.json`） |
| 最小化全流程 | 至少 **2** 条 case（train/val 各 ≥1）；`group_size=1`、`cases_per_variant=1`、`num_epochs=1` |
| 配置 | `.env` 中 `EVO_ADAPTER_URL`、优化模型 API；启动 API 时工作目录需能加载 `.env` |

---

### 1. 测试用例总览

| 编号 | 层级 | 名称 | 优先级 | 脚本 / 入口 |
| --- | --- | --- | --- | --- |
| TC-U01～U19 | 单元 | 经验库 / 语义优势 / 变体 / `_backward` | P0 | `tests/unit/optimizer/tf_grpo/` |
| TC-S00 | 冒烟 | EvoAgent API `GET /health` | P0 | HTTP |
| TC-S01 | 冒烟 | Adapter `/health` | P0 | HTTP |
| TC-S02 | 冒烟 | EDPAgent `/health` | P0 | HTTP |
| TC-S03 | 冒烟 | jiuwenbox `/health` | P0 | HTTP |
| TC-S04 | 冒烟 | `GET /scenarios` 含 `tf_grpo` | P0 | HTTP |
| TC-I01 | 集成 | 场景 Registry 可加载 `tf_grpo` | P0 | 代码 / 与 TC-S04 互补 |
| TC-I02 | 集成 | `POST /optimize` 非法路径 / 非法 split → 422 | P0 | HTTP |
| TC-I03 | 集成 | `POST /optimize`(tf_grpo) 接单 → `cancel` | P0 | HTTP（不跑满 e2e） |
| TC-I04 | 集成 | `update_skill(audit-business)` 读回一致 | P0 | Adapter API |
| TC-06 | 系统 | val 冒烟可得到 score | P0 | 评估 / 短 Job |
| TC-E2E-MIN | E2E | 服务化最小全流程（1 epoch / 1 变体 / 1 train+1 val） | P0 | `POST /optimize` 或等价 runner |
| TC-E2E | E2E | 服务化完整优化（多 epoch）并产出 gate | P0 | `POST /optimize` |

> 兼容旧编号：原 TC-01～03 = 现 TC-S01～S03；原 TC-04～05 ≈ TC-I01 / TC-I04；原 TC-E2E 改为以 API 为主。

---

### 2. 单元（TC-U01～U19）

| 项目 | 内容 |
| --- | --- |
| **对象** | `ExperienceLibrary`、`semantic_advantage`、`variant_generator`、`TfGrpoOptimizer._backward` |
| **方式** | pytest；mock LLM / Adapter / Agent；不启真实服务 |
| **命令** | `python -m pytest tests/unit/optimizer/tf_grpo -v` |
| **预期** | 19 项全部通过 |

（分项步骤与原先一致，见历史明细；门禁以 pytest 收集用例为准。）

---

### 3. 冒烟（TC-S00～S04）

| 编号 | 步骤 | 预期 |
| --- | --- | --- |
| TC-S00 | `GET http://127.0.0.1:8000/health`（或部署映射端口） | `{"status":"ok"}` |
| TC-S01 | `GET http://127.0.0.1:18900/health` | HTTP 200 |
| TC-S02 | `GET http://127.0.0.1:18001/health` | HTTP 200 |
| TC-S03 | `GET http://127.0.0.1:8321/health` | HTTP 200 |
| TC-S04 | `GET http://127.0.0.1:8000/scenarios` | 列表含 `name=tf_grpo`，`optimizer_class` 指向 TfGrpo |

Swagger：`http://<host>:8000/docs`。

---

### 4. 集成（TC-I01～I04）

#### TC-I01：场景可加载

| 项目 | 内容 |
| --- | --- |
| **前置条件** | evoagent 可 import 或 API 已启动 |
| **测试步骤** | 1. `ScenarioRegistry().load_scenario_config("tf_grpo")` 或依赖 TC-S04<br>2. 检查 hyperparams（`group_size` / `cases_per_variant` 等） |
| **预期结果** | 配置存在；含场景声明的 skill（如 `audit-business`） |

#### TC-I02：API 请求校验

| 项目 | 内容 |
| --- | --- |
| **测试步骤** | 1. `dataset_path` 不存在 → POST `/optimize`<br>2. `train_split + val_split ≠ 1.0` → POST `/optimize` |
| **预期结果** | HTTP **422**（非 500）；缺 `EVO_ADAPTER_URL` 时可为 500，属环境问题 |

#### TC-I03：接单后取消（跳过长跑）

| 项目 | 内容 |
| --- | --- |
| **前置条件** | TC-S00/S04 通过；`EVO_ADAPTER_URL` 已配置；cases 在 allowed roots |
| **测试步骤** | 1. `POST /optimize`（见 §7 请求体，`scenario=tf_grpo`，超参可最小化）<br>2. 取 `job_id`<br>3. 立即 `POST /optimize/{job_id}/cancel`<br>4. `GET /optimize/{job_id}` |
| **预期结果** | 接单 `status=queued`（或 running）；cancel → `cancelled`；**不要求**跑完 epoch |

#### TC-I04：热更读回

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 三容器就绪；目标 skill 已在沙箱 |
| **测试步骤** | 1. `skill_content` → 2. `update_skill` → 3. 再读回 |
| **预期结果** | `success=true`；内容一致 |

---

### 5. 系统（TC-06）

#### TC-06：val 冒烟出分

| 项目 | 内容 |
| --- | --- |
| **前置条件** | skill 已部署；dataset 可用 |
| **测试步骤** | 对 val 子集跑 evaluate，或提交短 Job 观察 baseline 日志 / Job progress |
| **预期结果** | 得到可复现分数；无进程崩溃 |

---

### 6. E2E（服务化）

#### TC-E2E-MIN：最小化全流程

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 冒烟通过；至少 2 条 case（1 train + 1 val） |
| **超参** | `num_epochs=1`，`group_size=1`，`cases_per_variant=1`，`num_parallel=1`；`train_split=0.5`，`val_split=0.5` |
| **测试步骤** | `POST /optimize`（scenario=`tf_grpo`）→ 轮询 Job 至 `completed`/`failed`；或查 `workspace/artifacts/{run_id}/epoch_1/gate_result.json` |
| **预期结果** | 完成 restore → baseline → 1 变体 → rollout →（单变体可跳过经验库）→ val gate；进程/Job 终态成功 |
| **说明** | `group_size=1` 时无组内方差，**跳过**语义优势/经验库更新属预期 |

#### TC-E2E：完整优化跑通

| 项目 | 内容 |
| --- | --- |
| **前置条件** | TC-S00～S04 通过；LLM Key 有效；数据集就绪 |
| **测试步骤** | 1. `POST /optimize`，`optimizer_template.scenario=tf_grpo`，`hyperparams.num_epochs`≥1<br>2. `GET /optimize/{job_id}/stream` 或轮询状态<br>3. 检查 Job `result` 与 `workspace/artifacts/{run_id}/epoch_*/gate_result.json` |
| **预期结果** | 至少完成 1 个 epoch；存在 gate 产物；Job `status=completed` |
| **参考实证** | CLI 同源跑通 run_id=`69ee31b9d5d5`：3 epochs，val 0.70→0.90（算法与编排与 API 相同） |

---

### 7. 执行命令（服务化优先）

```bash
cd common/agent-evolve/evoagent

# 0) 启动 EvoAgent API（须加载 .env，含 EVO_ADAPTER_URL）
# Docker: 见 deployment/operations-guide.md
# 本地:
#   uv run uvicorn evo_agent.api.app:app --host 127.0.0.1 --port 8000

# 1) 单元门禁
.\.venv\Scripts\python.exe -m pytest tests/unit/optimizer/tf_grpo -v

# 2) 冒烟
curl http://127.0.0.1:8000/health
curl http://127.0.0.1:8000/scenarios
curl http://127.0.0.1:18900/health
curl http://127.0.0.1:18001/health
curl http://127.0.0.1:8321/health

# 3) 提交 TF-GRPO 任务（默认 metric + extract；按需改 path / 超参）
curl -X POST http://127.0.0.1:8000/optimize ^
  -H "Content-Type: application/json" ^
  -d "{\"task_name\":\"tfgrpo-min\",\"agent_name\":\"edp_agent\",\"optimizer_type\":\"skill\",\"skills\":[\"audit-business\"],\"dataset_path\":\"D:/agent-solution/common/agent-evolve/evoagent/workspace/datasets/audit_min2/cases.json\",\"optimizer_template\":{\"name\":\"tf_grpo\",\"scenario\":\"tf_grpo\",\"hyperparams\":{\"num_epochs\":1,\"group_size\":1,\"cases_per_variant\":1,\"num_parallel\":1},\"train_split\":0.5,\"val_split\":0.5,\"rollout\":{\"extra_data\":{}}},\"evaluator_template\":{\"name\":\"exact\",\"scenario\":\"audit-business\",\"type\":\"metric\",\"metric\":\"exact_match\",\"extract\":{\"strategy\":\"answer_tag_json_field\",\"source\":\"answer\",\"fields\":[\"responsibility\",\"responsibility_type\"],\"prefer_values\":[\"无责\",\"有责\"]}}}"

# 3b) 语义评估示例：evaluator_template 改为
# {"name":"semantic","scenario":"audit-business","type":"llm","prompt":"...含 {messages} ..."}

# 4) 查状态 / SSE / 取消
# curl http://127.0.0.1:8000/optimize/{job_id}
# curl -N http://127.0.0.1:8000/optimize/{job_id}/stream
# curl -X POST http://127.0.0.1:8000/optimize/{job_id}/cancel
```

**CLI 辅路径**（`dataset.yaml` 评估器，本地算法对照）：

```bash
.\.venv\Scripts\python.exe skills/optimize_skill/scripts/run_optimize.py `
  --scenario tf_grpo `
  --dataset-manifest workspace/datasets/audit_business_balanced30/dataset.yaml `
  --agent-name edp_agent `
  --epochs 3 `
  --adapter-url http://127.0.0.1:18900
```

---

### 8. 相关文档

| 文档 | 说明 |
| --- | --- |
| [`TF-GRPO测试用例执行报告.md`](TF-GRPO测试用例执行报告.md) | 执行结果与实证 |
| [`TF-GRPO开发串讲文档.md`](TF-GRPO开发串讲文档.md) | 算法与服务化入口 |
| `deployment/operations-guide.md` | EvoAgent Docker / 运维 |
| `scenarios/tf_grpo/README.md` | 场景说明 |
| 流程说明 HTML | `TF-GRPO优化流程说明-balanced30-69ee31b9d5d5.html` |
