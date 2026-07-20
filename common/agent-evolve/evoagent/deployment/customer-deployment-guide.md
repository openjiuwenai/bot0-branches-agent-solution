# EvoAgent 客户现场部署教程（双容器）

> 适用：客户 Linux 环境，EvoAgent + EvoAgentAdapter **双容器**联合部署。
> 仓内姊妹文档：`deployment/README.md`（EvoAgent 单容器细节）、`common/agent-evolve/evoagent-adapter/deployment/`（Adapter 细节）。

---

## 0. 启动方式选型：API 还是 Python 脚本？

**当前现状：只有 API 入口，python 脚本入口未实现。** 两种方式取舍如下。

### 0.1 对比

| 维度 | API（已实现，推荐生产） | Python 脚本入口（需新增 `scripts/run_optimize.py`） |
|---|---|---|
| 入口 | `POST /optimize`（容器内 uvicorn） | `python scripts/run_optimize.py --task ...`（直接调 `run_optimization`） |
| 任务管理 | JobManager 托管：状态机、取消、并发隔离 | 无 JobManager，进程即任务，Ctrl-C 取消 |
| 进度跟踪 | `GET /{job_id}/stream` SSE 实时事件流 | 只能 stdout 日志，无 SSE |
| 部署形态 | 容器常驻，宿主机 `curl` 即可 | 需 `docker exec` 进容器跑，或 `docker run` 一次性容器跑脚本 |
| 适用 | 平台后端集成、长跑任务、需查进度 | 客户现场一次性调试、复现问题、不想要常驻服务 |
| 是否进容器 | 不需要 | **需要**（脚本依赖容器内的 `evo_agent` 包与挂载的数据） |

### 0.2 选型建议

- **生产/平台集成 → API**：任务隔离、SSE 进度、可取消，是设计入口
- **现场调试/单次跑 → Python 脚本**：`docker exec -it evoagent python scripts/run_optimize.py ...` 直接出日志，定位问题快

> ⚠️ 若选脚本入口，**必须进容器操作**——宿主机没装 `evo_agent` 包及其依赖，脚本跑不起来。两种进容器方式：
> - `docker exec -it evoagent python scripts/run_optimize.py ...`（复用常驻容器，最常用）
> - `docker run --rm -v ... evoagent:latest python scripts/run_optimize.py ...`（一次性容器，不依赖常驻服务起来）

### 0.3 现状说明

`optimizer_runner.run_optimization()` 是核心编排函数，当前**唯一生产调用点是 `api/routes/optimize.py`**。`scripts/` 下只有 `smoke_icbc.py`（ICBC 冒烟），无优化任务脚本。如需 python 脚本入口，见附录 §A 的 `scripts/run_optimize.py` 模板（需自行落地）。

---

## 1. 架构与容器间通信

### 1.1 拓扑

```
┌─────────────────────────────────────────────────────────┐
│  客户 Linux 机器                                         │
│                                                         │
│  ┌──────────────┐         ┌──────────────────────┐      │
│  │ adapter 容器  │         │  evoagent 容器        │      │
│  │ port 8900     │ ◄──────  │  port 8000 (API)      │      │
│  │ /api/v1/*     │  HTTP    │  EVO_ADAPTER_URL ────┘      │
│  └──────┬───────┘  出站      │                            │
│         │                    │  评估器/反思器/优化器 LLM    │
│         │                    │  走 ICBC / OpenAI          │
│         ▼                    └──────────────────────┘      │
│  /data/skills (宿主)            ▲                          │
│  /data/logs  (宿主)            │ 宿主机 curl/平台          │
│                                │                          │
└────────────────────────────────┼──────────────────────────┘
                                 │
                          POST /optimize
```

- **EvoAgent 容器**（8000）：接收优化任务，编排评估→反思→优化 pipeline，通过 `EVO_ADAPTER_URL` 调 Adapter 做 rollout
- **Adapter 容器**（8900）：sidecar，封装目标 Agent（ReActAgent→RemoteAgent），跑 rollout 并回评分
- **方向**：EvoAgent → Adapter（EvoAgent 是发起方，Adapter 是被调方）

### 1.2 网络方案（按部署形态选一种）

#### 方案 A：同机部署（推荐，最简）

两个容器同机，用 `host.docker.internal` 互通。EvoAgent 的 `run.sh` 已内置 `--add-host host.docker.internal:host-gateway`，所以 `.env` 里写：

```env
EVO_ADAPTER_URL=http://host.docker.internal:8900
```

> 注意：Adapter 容器需用 `-p 8900:8900` 把端口发布到宿主机，`host.docker.internal:8900` 才能从 EvoAgent 容器内访问到。`start.sh` 已做 `-p ${PORT}:8900`。

#### 方案 B：跨机部署

EvoAgent 与 Adapter 在不同机器。`.env` 填 Adapter 机器的**真实可达 IP**：

```env
EVO_ADAPTER_URL=http://<ADAPTER_HOST_IP>:8900
```

> 跨机时别用 `host.docker.internal` 和 `localhost`，必须用 IP 或域名，且 Adapter 机器防火墙放行 8900。

#### 方案 C：共用 docker network（高级，可选）

若两个容器同机且想走容器名 DNS（而非宿主机端口转发），可建共享网络：

```bash
docker network create evo-net
# adapter 启动加 --network evo-net --name adapter
# evoagent 启动加 --network evo-net
# .env: EVO_ADAPTER_URL=http://adapter:8900
```

当前 `run.sh` / `start.sh` 未内置此模式，需手动改脚本。**首次部署不建议**，A/B 方案够用。

---

## 2. 前置条件

| 项 | 要求 |
|----|------|
| 操作系统 | Linux（Ubuntu 22.04 / CentOS 7+ 实测可） |
| Docker | 20.10+ |
| 磁盘 | ≥ 5 GB（镜像 ~350MB × 2 + workspace 产物） |
| Adapter 镜像 | 已构建或离线包已导入 |
| LLM | ICBC 内网端点 或 OpenAI 兼容公网端点 |

两个容器**必须同时可用**，EvoAgent 单独跑不起来（`POST /optimize` 会 500 `EVO_ADAPTER_URL not configured` 或 rollout 失败）。

---

## 3. 部署流程总览

```
① 部署 Adapter（8900） ──► ② 部署 EvoAgent（8000） ──► ③ 配 .env + 数据 ──► ④ 验证 ──► ⑤ 提交任务
```

**先 Adapter 后 EvoAgent**：EvoAgent 启动时不强制校验 Adapter 可达，但提交任务时必须能连上，先起 Adapter 便于验证。

---

## 4. Adapter 部署

> 目录：`common/agent-evolve/evoagent-adapter/deployment/`

### 4.1 配置

```bash
cd common/agent-evolve/evoagent-adapter/deployment
cp config/.env.example config/.env
vim config/.env
```

关键项（详见 adapter 的 `.env.example`）：

| 变量 | 示例 | 说明 |
|---|---|---|
| `ADAPTER_PORT` | `8900` | 对外端口 |
| `HOST_OUTPUT_DIR` | `/home/evolution/adapter/data` | rollout 产物宿主目录 |
| `HOST_CONFIG_FILE` | `/opt/agent-adapter/agent_adapter_config.yaml` | adapter 主配置 yaml |
| `HOST_SKILLS_DIR` | `/home/evolution/data/skills` | skill 文件宿主目录 |
| `HOST_LOG_DIR` | `/home/evolution/data/logs` | 日志宿主目录 |

### 4.2 启动

```bash
./start.sh                       # 默认端口 8900
# 或 ./start.sh --port 8901      # 自定义端口
```

### 4.3 验证

```bash
curl http://localhost:8900/api/v1/status   # 返回 JSON 即活
docker ps --filter "name=adapter"          # 期望 (healthy)
```

---

## 5. EvoAgent 部署

> 目录：`common/agent-evolve/evoagent/deployment/`

### 5.1 构建镜像（联网机器）

```bash
cd common/agent-evolve/evoagent/deployment

# 推荐：PyPI 拉 openjiuwen wheel
HOME=/home/evolution/build \
EVOAGENT_SOLUTION_REPO=https://gitcode.com/AE-TEAM/agent-solution.git \
EVOAGENT_SOLUTION_BRANCH=common \
EVOAGENT_IMAGE_TAG=evoagent:latest \
./build.sh --local
```

验证：

```bash
docker images evoagent:latest   # ~350MB
```

### 5.2 配置环境变量

```bash
cp config/.env.example config/.env
vim config/.env
```

**完整 `.env`（客户现场模板）**：

```env
# ===== LLM provider 开关 =====
# "OpenAI"（公网）| "ICBC"（客户内网）
EVO_LLM_PROVIDER=ICBC

# ── OpenAI 模式（provider=OpenAI 时必填）──
# EVO_LLM_API_KEY=sk-xxx
# EVO_LLM_BASE_URL=https://api.openai.com/v1
# EVO_OPTIMIZER_MODEL=gpt-4o
# EVO_TARGET_MODEL=gpt-4o

# ── ICBC 模式（provider=ICBC 时必填，启动 fail-fast 校验）──
EVO_ICBC_TOKEN=<JWT,Secret 注入,勿写明文>
EVO_ICBC_USER_ID=<工行分配的固定 userId>
EVO_ICBC_ENDPOINT=http://aigc.sdc.cs.icbc/mlpmodelservice/aigc/chat/completions
EVO_ICBC_CONTEXT_WINDOW_TOKENS=32768
EVO_ICBC_TIMEOUT=120.0

# ===== Adapter sidecar 地址（API 模式必填）=====
# 同机：host.docker.internal；跨机：Adapter 机器 IP
EVO_ADAPTER_URL=http://host.docker.internal:8900
EVO_MANAGED_DOC_APPLY_DEADLINE=600
EVO_MANAGED_DOC_CANCEL_ROLLBACK_DEADLINE=900
EVOAGENT_CONTROL_DB_PATH=./workspace/evoagent-control.db
EVO_MANAGED_DOC_CONTENT_POLICIES={"agent_rule":"preserving"}
EVO_MANAGED_DOC_PROTECTED_SECTIONS={}

# ===== 数据集路径白名单（防路径穿越，逗号分隔）=====
EVO_ALLOWED_DATA_ROOTS=/data/evo_agent,/tmp/evo_agent

# ===== 远程通信 =====
EVO_REMOTE_TIMEOUT=300.0
EVO_REMOTE_PARALLEL=4

# ===== 优化超参（默认值可用，按需调）=====
EVO_DEFAULT_EPOCHS=3
EVO_DEFAULT_BATCH_SIZE=4
EVO_ACCUMULATION=2
EVO_EDIT_BUDGET=10
EVO_SCHEDULER_MODE=constant
EVO_USE_SLOW_UPDATE=true
EVO_USE_META_SKILL=true
EVO_SCORE_THRESHOLD=0.5
EVO_PARALLELISM=4

# ===== 路径（容器内，一般不改）=====
EVO_WORKSPACE_ROOT=./workspace
EVO_OUTPUT_ROOT=./workspace/outputs
EVO_ARTIFACT_DIR=./workspace/artifacts
```

**必填项速查**：

| 场景 | 必填变量 |
|---|---|
| 始终 | `EVO_ADAPTER_URL` |
| `EVO_LLM_PROVIDER=OpenAI` | `EVO_LLM_API_KEY` / `EVO_LLM_BASE_URL` / `EVO_OPTIMIZER_MODEL` / `EVO_TARGET_MODEL` |
| `EVO_LLM_PROVIDER=ICBC` | `EVO_ICBC_TOKEN` / `EVO_ICBC_USER_ID` / `EVO_ICBC_ENDPOINT` / `EVO_ICBC_CONTEXT_WINDOW_TOKENS`（其余 LLM_* 被忽略） |

### 5.3 启动

```bash
./run.sh
# 自定义：./run.sh --image evoagent:v1.0.0 --port 8000 --name evoagent
```

`run.sh` 自动：校验镜像 → 校验 `.env` → 清旧容器 → 建目录 → 启容器（healthcheck + `--restart unless-stopped` + `--add-host host.docker.internal:host-gateway`）→ 等就绪。

### 5.4 容器挂载关系（`run.sh` 自动完成）

| 宿主机 | 容器内 | 用途 |
|---|---|---|
| `deployment/workspace` | `/app/workspace` | 输出报告 + artifacts |
| `/home/evolution/data` | `/data` | 数据集文件 |

> 数据集放宿主机 `/home/evolution/data/evo_agent/xxx.json` → 容器内 `/data/evo_agent/xxx.json`。`EVO_ALLOWED_DATA_ROOTS` 保留 `/data/evo_agent` 与容器内路径一致。

---

## 6. 部署验证

```bash
# 1. 两容器都 healthy
docker ps --filter "name=evoagent" --filter "name=adapter"
# 期望两个都是 Up ... (healthy)

# 2. Adapter 活
curl http://localhost:8900/api/v1/status

# 3. EvoAgent API 活
curl http://localhost:8000/health                 # {"status":"ok"}
curl http://localhost:8000/openapi.json | head -c 200

# 4. Swagger 文档
# 浏览器：http://<服务器IP>:8000/docs
```

---

## 7. 启动优化任务（API 方式）

### 7.1 准备数据集

```bash
# 宿主机
mkdir -p /home/evolution/data/evo_agent
# 把数据集文件放进去（容器内路径为 /data/evo_agent/<file>）
cp my_dataset.json /home/evolution/data/evo_agent/
```

### 7.2 提交任务

```bash
curl -X POST http://localhost:8000/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "task_name": "smoke-test",
    "agent_name": "edp_agent",
    "optimizer_type": "skill",
    "dataset_path": "/data/evo_agent/my_dataset.json",
    "optimizer_template": {
      "name": "edp_agent",
      "scenario": "edp_agent",
      "hyperparams": {"num_epochs": 1, "batch_size": 4},
      "train_split": 0.8,
      "val_split": 0.2
    },
    "evaluator_template": {"name": "default_eval", "scenario": "edp_agent", "prompt": ""},
    "skills": []
  }'
```

期望响应：

```json
{"job_id":"job_xxx","status":"queued"}
```

### 7.3 查询进度 / 实时流

```bash
# 轮询
curl http://localhost:8000/optimize/job_xxx

# SSE 实时流（推荐）
curl -N http://localhost:8000/optimize/job_xxx/stream
```

### 7.4 取消任务

```bash
curl -X POST http://localhost:8000/optimize/job_xxx/cancel
```

### 7.5 关键字段约束

- `dataset_path`：必须在 `EVO_ALLOWED_DATA_ROOTS` 白名单下，否则 422
- `train_split + val_split == 1.0`，都 > 0，否则 422
- `num_epochs` ∈ [1,100]，`batch_size` ∈ [1,64]
- `optimizer_template.scenario` 决定场景配置，不存在时 fallback 默认

---

## 8. 离线部署（客户机器无法公网）

### 联网机器

```bash
# EvoAgent
cd common/agent-evolve/evoagent/deployment
./build.sh --local
./export-bundle.sh evoagent:latest
# → ../evoagent-offline-YYYYMMDD.tar.gz

# Adapter
cd common/agent-evolve/evoagent-adapter/deployment
./export-bundle.sh agent-adapter:latest
# → ../agent-adapter-offline-YYYYMMDD.tar.gz
```

### 离线机器（两个包都拷过去）

```bash
# EvoAgent
tar xzf evoagent-offline-YYYYMMDD.tar.gz
cd evoagent-offline-YYYYMMDD
./import-bundle.sh evoagent.latest.YYYYMMDD.tar
cp config/.env.example config/.env && vim config/.env   # 按 §5.2 填
./run.sh

# Adapter
tar xzf agent-adapter-offline-YYYYMMDD.tar.gz
cd agent-adapter-offline-YYYYMMDD
./import-bundle.sh agent-adapter.latest.YYYYMMDD.tar
cp config/.env.example config/.env && vim config/.env
./start.sh
```

> 离线机器需预装 Docker 20.10+，且 ICBC 端点 / Adapter 端口在内网可达。

---

## 9. 运维

### 日志

```bash
docker logs -f evoagent           # EvoAgent 实时日志
docker logs -f adapter            # Adapter 实时日志
docker logs --tail 200 evoagent   # 最近 200 行
docker exec -it evoagent bash     # 进容器排查
```

### 产物

```bash
ls -lh deployment/workspace/outputs/     # 优化报告
ls -lh deployment/workspace/artifacts/   # 训练过程产物（skill patches、meta skill）
```

### 停止 / 更新

```bash
# EvoAgent
./stop.sh
./stop.sh --all

# Adapter
./stop.sh

# 更新（停 → 构新镜像 → 启）
./stop.sh && ./build.sh --local && ./run.sh
```

---

## 10. 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| `POST /optimize` 500 `EVO_ADAPTER_URL not configured` | `.env` 没填 `EVO_ADAPTER_URL`，或没经 `run.sh` 加载 |
| rollout 阶段连不上 Adapter | 同机检查 `EVO_ADAPTER_URL=http://host.docker.internal:8900` 且 Adapter `-p 8900:8900`；跨机用真实 IP |
| `422 Dataset path must be under allowed roots` | `dataset_path` 不在 `EVO_ALLOWED_DATA_ROOTS` 下，或宿主/容器路径映射搞反 |
| `422 Dataset file not found` | 容器内 `/data` ← 宿主 `/home/evolution/data`，文件要放对地方 |
| 容器一直 `starting` 不转 `healthy` | 等 15s 启动期后 `docker logs evoagent`，多为 `.env` 缺必填或 ICBC 凭证错 |
| ICBC 模式启动 fail-fast 报必填缺失 | `EVO_LLM_PROVIDER=ICBC` 时 `EVO_ICBC_TOKEN/USER_ID/ENDPOINT/CONTEXT_WINDOW_TOKENS` 四项不能空 |
| `ICBCTokenExpiredError` | JWT 过期，需找客户换 token 后改 `.env` 重启 |
| `docker build` 拉 wheel 失败 | 换 `PIP_INDEX_URL` 为可用源，或离线包导入 |
| Adapter 与 EvoAgent 数据集路径不一致 | EvoAgent 把数据集路径传给 Adapter，两边挂载要协调（skill 文件通常由 Adapter 的 `HOST_SKILLS_DIR` 管） |

---

## 11. 速查命令清单

```bash
# 启动
cd common/agent-evolve/evoagent-adapter/deployment && ./start.sh
cd common/agent-evolve/evoagent/deployment && ./run.sh

# 验证
curl http://localhost:8900/api/v1/status
curl http://localhost:8000/health

# 提交任务
curl -X POST http://localhost:8000/optimize -H "Content-Type: application/json" -d '{...}'

# 看进度
curl -N http://localhost:8000/optimize/<job_id>/stream

# 看日志
docker logs -f evoagent
docker logs -f adapter

# 停
cd common/agent-evolve/evoagent/deployment && ./stop.sh
cd common/agent-evolve/evoagent-adapter/deployment && ./stop.sh
```

---

## 附录 A：Python 脚本入口模板（可选落地）

若现场需要 python 脚本入口（不走 API），把下面这个文件存为 `scripts/run_optimize.py`，进容器跑：

```bash
docker exec -it evoagent python scripts/run_optimize.py \
  --task smoke --agent edp_agent --scenario edp_agent \
  --dataset /data/evo_agent/my_dataset.json --epochs 1 --batch-size 4
```

```python
#!/usr/bin/env python
"""EvoAgent 优化任务脚本入口 — 直接调 run_optimization，不走 API/JobManager。

进容器跑：docker exec -it evoagent python scripts/run_optimize.py ...
无 SSE / 无任务隔离，仅限现场调试。生产用 POST /optimize。
"""
from __future__ import annotations

import argparse
import asyncio
import sys

from evo_agent.config import EvolveConfig
from evo_agent.optimizer_runner import run_optimization
from evo_agent.types import OptimizeRequest


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--task", required=True)
    p.add_argument("--agent", required=True)
    p.add_argument("--scenario", required=True)
    p.add_argument("--dataset", required=True, help="容器内数据集路径，须在 EVO_ALLOWED_DATA_ROOTS 下")
    p.add_argument("--epochs", type=int, default=None)
    p.add_argument("--batch-size", type=int, default=None)
    p.add_argument("--train-split", type=float, default=0.8)
    p.add_argument("--val-split", type=float, default=0.2)
    p.add_argument("--skills", nargs="*", default=[])
    return p.parse_args()


async def main() -> int:
    args = parse_args()
    config = EvolveConfig.get()
    if not config.adapter_url:
        print("EVO_ADAPTER_URL 未配置", file=sys.stderr)
        return 1
    request = OptimizeRequest(
        scenario=args.scenario,
        agent_name=args.agent,
        skills=args.skills,
        dataset_path=args.dataset,
        adapter_url=config.adapter_url,
        num_epochs=args.epochs,
        batch_size=args.batch_size,
        train_split=args.train_split,
        val_split=args.val_split,
        task_name=args.task,
    )
    report = await run_optimization(request, config)
    print("\n=== 完成 ===")
    print(f"epochs: {report.epochs_completed}, edits: {report.edits_applied}")
    print(f"train: {report.train.score_before} → {report.train.score_after}")
    print(f"val:   {report.val.score_before} → {report.val.final_score}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
```

> 注意：此脚本绕过 JobManager/SSE/进度回调，无并发隔离。落地后建议加 `--skill-scores` 等输出开关，并用 ICBC 模式跑通一次。
