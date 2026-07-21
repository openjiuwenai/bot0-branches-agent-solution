## TF-GRPO 测试用例执行报告

> **交付口径**：以 **EvoAgent 服务化部署**（FastAPI `POST /optimize`，scenario=`tf_grpo`）为验收主路径；CLI 为同源辅路径。用例定义见 [`TF-GRPO测试用例.md`](TF-GRPO测试用例.md)。

### 1. 测试概述

| 项目 | 内容 |
| --- | --- |
| **测试对象** | EvoAgent `TfGrpoOptimizer`（TF-GRPO）+ **EvoAgent API** |
| **覆盖能力** | 经验库、语义优势、变体生成与完整性、epoch 编排；服务化接单/校验/取消；联调热更与完整优化 |
| **层级口径** | 单元 / 冒烟 / 集成 / 系统 / E2E |
| **单元脚本** | `pytest tests/unit/optimizer/tf_grpo` |
| **服务化入口** | `POST /optimize`（`optimizer_template.scenario=tf_grpo`） |
| **API 地址（实测）** | `http://127.0.0.1:8000` |
| **Adapter** | `http://127.0.0.1:18900` |
| **EDPAgent** | `http://127.0.0.1:18001` |
| **jiuwenbox** | `http://127.0.0.1:8321` |
| **agent_name** | `edp_agent` |
| **skill_name** | `audit-business` |
| **数据集** | 完整：`audit_business_balanced30`；最小：`audit_min2`（2 case） |
| **优化模型** | `qwen3.7-max` |
| **执行日期** | 单元/完整 E2E：2026-07-17；**服务化冒烟+最小全流程**：2026-07-20 |
| **执行环境** | Windows；evoagent `.venv` Python **3.13.13**；pytest **9.1.0** |

### 2. 测试执行结果

#### 2.1 按层级统计

| 层级 | 用例范围 | 通过 | 失败 | 说明 |
| --- | --- | --- | --- | --- |
| **单元** | TC-U01～U19 | 19 | 0 | pytest + mock |
| **冒烟** | TC-S00～S04 | 5 | 0 | 含 EvoAgent API `/health` + `/scenarios` |
| **集成** | TC-I01～I04 | 4 | 0 | 含 API 422 校验 + 接单 cancel |
| **系统** | TC-06 | 1 | 0 | balanced30 val 冒烟 score=0.70 |
| **E2E-MIN** | TC-E2E-MIN | 1 | 0 | 1×1×1 全流程约 2.7 min（`aba40d341ec6`） |
| **E2E** | TC-E2E | 1 | 0 | 3 epochs；val 0.70→0.90（`69ee31b9d5d5`，CLI 同源编排） |
| **合计** | — | **31** | **0** | — |

#### 2.2 单元（19/19）

| 文件 | 状态 |
| --- | --- |
| `test_experience_library.py`（3） | ✅ |
| `test_semantic_advantage.py`（7） | ✅ |
| `test_tf_grpo_backward.py`（2） | ✅ |
| `test_variant_generator.py`（7） | ✅ |

```
命令: .\.venv\Scripts\python.exe -m pytest tests/unit/optimizer/tf_grpo -v
结果: 19 passed, 2 warnings（openjiuwen Pydantic Config 弃用提示）
```

#### 2.3 冒烟（5/5）— 服务化

| 编号 | 用例 | 状态 | 备注 |
| --- | --- | --- | --- |
| TC-S00 | EvoAgent `GET /health` | ✅ | `http://127.0.0.1:8000` → `{"status":"ok"}` |
| TC-S01 | Adapter `/health` | ✅ | `:18900` |
| TC-S02 | EDPAgent `/health` | ✅ | `:18001` |
| TC-S03 | jiuwenbox `/health` | ✅ | `:8321` |
| TC-S04 | `GET /scenarios` 含 `tf_grpo` | ✅ | 返回 `TfGrpoOptimizer` 与 hyperparams |

> 注意：API 进程须从 evoagent 目录启动并加载 `.env`（含 `EVO_ADAPTER_URL`），否则 `POST /optimize` 可能 500。

#### 2.4 集成（4/4）— 服务化

| 编号 | 用例 | 状态 | 备注 |
| --- | --- | --- | --- |
| TC-I01 | 场景可加载 | ✅ | 与 TC-S04 / scenario.yaml 一致 |
| TC-I02 | 非法 path / split → 422 | ✅ | bad path、`0.8+0.3` split |
| TC-I03 | POST 接单 + cancel | ✅ | `job_id=fe066cc70493` → `cancelled`；已开始 restore/baseline 后取消 |
| TC-I04 | `update_skill` | ✅ | 完整 E2E 中多次热更成功 |

#### 2.5 系统（1/1）

| 编号 | 用例 | 状态 | 备注 |
| --- | --- | --- | --- |
| TC-06 | balanced30 val 冒烟 | ✅ | score=**0.70**（7/10） |

#### 2.6 E2E-MIN（1/1）— 最小化全流程

| 项目 | 内容 |
| --- | --- |
| **run_id** | `aba40d341ec6` |
| **配置** | `audit_min2`（2 case）；epochs=1；group_size=1；cases_per_variant=1 |
| **耗时** | ≈ **2.7 min** |
| **基线 val** | 1.0 |
| **train 变体** | e1-g1 train score=0.0（case-062 判错） |
| **gate** | base=1.0 / candidate=1.0 → `decision=base` |
| **状态** | ✅ 全流程跑通（单变体跳过经验库更新属预期） |
| **产物** | `workspace/artifacts/aba40d341ec6/epoch_1/gate_result.json` |

> 实现上本次最小跑使用同源 `run_optimization` + manifest（metric）；与服务化 Job 编排一致。服务化提交时改用 `dataset_path=.../audit_min2/cases.json` + 同上 hyperparams。

#### 2.7 E2E（1/1）— 完整优化

| 项目 | 内容 |
| --- | --- |
| **run_id** | `69ee31b9d5d5` |
| **入口说明** | 当时以 CLI `run_optimize.py` 执行；与 API 共用 `run_optimization` / `TfGrpoOptimizer` |
| **数据集** | balanced30（train 20 / val 10） |
| **超参** | group_size=3，cases_per_variant=8，epochs=3，num_parallel=4 |
| **基线 → 最终 val** | **0.70 → 0.90** |
| **耗时** | ≈ 40 min |
| **状态** | ✅ |
| **产物** | `workspace/artifacts/69ee31b9d5d5/` |

| Epoch | 组内胜出 | Val 门控 |
| --- | --- | --- |
| 1 | e1-g2 train=0.625 | keep base 0.70 |
| 2 | e2-g1 train=0.75 | **adopt** 0.90 |
| 3 | e3-g1 train=0.75 | keep 0.90 |

---

### 3. 服务化接口摘录（2026-07-20）

```
GET  /health          → 200 {"status":"ok"}
GET  /scenarios       → 含 tf_grpo
POST /optimize        → 200 job_id=fe066cc70493 status=queued
POST /optimize/{id}/cancel → 200 status=cancelled
GET  /optimize/{id}   → status=cancelled error=Job cancelled by user
```

非法请求：

```
POST /optimize (不存在的 dataset_path) → 422
POST /optimize (train_split+val_split≠1) → 422
```

---

### 4. 发现与说明

| 编号 | 说明 | 处理 / 影响 |
| --- | --- | --- |
| F1 | Epoch1 `e1-g3` 变体生成失败（incomplete） | 算法已跳过 |
| F2 | API 未加载 `.env` 时 POST 500 | 启动须带 `EVO_ADAPTER_URL`；见运维指南 |
| F3 | API `evaluator_template.type` 默认 **metric**（可 `extract`）；语义评估须显式 `type: llm` | 与 CLI `dataset.yaml` 均可走 metric；文档已同步 |
| F4 | 中间变体 / 经验库不落盘 | 审计依赖日志或后续导出 |
| F5 | Windows 终端中文乱码 | 已用 `ensure_utf8_stdio` 修复 CLI/回调输出 |
| F6 | train/val 各至少 1 条 | 「物理 1 条 case」无法过划分校验；最小为 2 条 |

---

### 5. 结论

- **单元门禁 19/19** 通过。  
- **服务化冒烟 + 接单/校验/取消** 已验证（API `:8000`）。  
- **最小化全流程**（1 epoch / 1 变体 / 1+1 case）已跑通。  
- **完整业务 E2E** 在 balanced30 上验证集 **0.70 → 0.90**（与 API 同源编排）。  
- **交付建议**：版本发布以服务化路径验收 — 单元 + TC-S00～S04 + TC-I02/I03 + TC-E2E-MIN；正式环境再补多 epoch E2E。

---

### 6. 覆盖范围

| 已覆盖 | 未覆盖（非目标 / 后续） |
| --- | --- |
| 服务化 `POST /optimize` + Job/cancel | Studio 前端 SSE UI |
| Instruction 路径 TF-GRPO | Code 三级优化 |
| Adapter 热更 + 真实 rollout | 经验库跨 run 落盘 |
| balanced30 / audit_min2 | 自动任务生成 |

---

### 7. 复现命令（服务化）

```bash
cd D:\agent-solution\common\agent-evolve\evoagent

# 启动 API（示例）
# 先加载 .env，再:
.\.venv\Scripts\uvicorn.exe evo_agent.api.app:app --host 127.0.0.1 --port 8000

# 单元
.\.venv\Scripts\python.exe -m pytest tests/unit/optimizer/tf_grpo -v

# 冒烟
curl http://127.0.0.1:8000/health
curl http://127.0.0.1:8000/scenarios

# 接单后取消（跳过长跑）— 见测试用例 §7 curl 体
# 完整/最小 E2E：同上 POST，不 cancel，轮询至 completed
```

运维与 Docker：`deployment/operations-guide.md`。

---

### 8. 相关文档

| 文档 | 路径 |
| --- | --- |
| 测试用例 | [`TF-GRPO测试用例.md`](TF-GRPO测试用例.md) |
| 开发串讲 | [`TF-GRPO开发串讲文档.md`](TF-GRPO开发串讲文档.md) |
| 运维部署 | `deployment/operations-guide.md` |
| 流程说明 HTML | `TF-GRPO优化流程说明-balanced30-69ee31b9d5d5.html` |
| 完整 run 产物 | `workspace/artifacts/69ee31b9d5d5/` |
| 最小 run 产物 | `workspace/artifacts/aba40d341ec6/` |
