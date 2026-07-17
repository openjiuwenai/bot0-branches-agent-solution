# EvoAgent 部署与运维操作指南

本指南面向初次接触 EvoAgent 的运维人员，覆盖从 **git clone → 构建 → 部署 → 验证 → 日志查看 → 下线** 的完整流程。
所有命令均来自项目实际脚本，直接复制即可使用。

---

## 0. 架构与依赖概览

EvoAgent 是基于 `agent-core`（PyPI 包名 `openjiuwen`）的自进化元 Agent，以 FastAPI 服务形式对外提供 skill 文档自动优化能力。

### 0.1 关键组件

| 组件 | 说明 |
|------|------|
| **EvoAgent API** | FastAPI 服务，镜像内 `uvicorn` 启动，默认监听 8000 端口 |
| **Adapter Sidecar** | 独立部署的 HTTP 服务（`EVO_ADAPTER_URL`），负责与业务 Agent 通信 + skill 同步 |
| **LLM 服务** | 通过 `EVO_LLM_BASE_URL` 调用的 OpenAI 兼容接口（默认阿里云 DashScope） |
| **agent-core 源码** | 位于同仓库 `community/agent-core`，构建时打包为 `openjiuwen-*.whl` |

### 0.2 部署方式

本项目提供 **两种** 部署路径：

| 方式 | 适用场景 | 入口 |
|------|----------|------|
| **Docker 部署（推荐）** | 生产 / 线上环境 | `deployment/build.sh` + `run.sh` |
| **本地开发部署** | 本地调试 / 单元测试 | `Makefile`（`make install` + `make serve`） |
| **离线打包部署** | 内网无外网环境 | `export-bundle.sh` + `import-bundle.sh` |

---

## 1. 环境前置条件

### 1.1 系统要求

- **操作系统**: Linux（推荐 Ubuntu 22.04 / CentOS 7+）；macOS / Windows 也可，但脚本以 bash 编写
- **Python**: 3.12+（本地开发模式必需；Docker 模式不需要）
- **Docker**: 20.10+（含 buildkit）
- **磁盘**: 至少 5 GB 可用空间
- **网络**: 能访问 `https://gitcode.com` 和 pip 镜像源（华为云默认）

### 1.2 外部依赖服务

| 服务 | 用途 | 必需 |
|------|------|------|
| **Adapter Sidecar** | 与业务 Agent 通信、skill 同步、轨迹收集 | ✅ |
| **LLM API** | 优化器 LLM 调用（OpenAI 兼容） | ✅ |

> Adapter Sidecar 的部署请参考同仓库 `community/EvoAgentAdapter` 目录或其文档。EvoAgent 启动后通过 `EVO_ADAPTER_URL` 访问它，二者**必须同时可用**。

---

## 2. 获取代码（Git Clone）

EvoAgent 的代码位于仓库的子目录下，`build.sh` 会自动处理克隆与路径探测，也可以手动克隆。

> 路径说明：`agent-store` 仓库内为 `community/EvoAgent`，`agent-solution` 仓库内为 `common/agent-evolve/evoagent`。`build.sh` 未设置 `EVOAGENT_REL_PATH` 时会自动探测，无需手动指定。

### 2.1 方式 A：手动 clone（推荐用于首次了解项目结构）

```bash
# 1) 克隆仓库（以 agent-store 为例；agent-solution 同理）
git clone --branch main https://gitcode.com/openJiuwen/agent-store.git ~/EvoAgent/agent-store
cd ~/EvoAgent/agent-store/community/EvoAgent

# 2) 查看关键文件
ls deployment/        # 部署脚本
ls src/evo_agent/     # 源码
cat README.md         # 项目简介
```

### 2.2 方式 B：由 `build.sh` 自动 clone

`deployment/build.sh` 内部会自动 clone 到 `$HOME/EvoAgent/agent-store`，无需手动操作（详见第 3 节）。

### 2.3 目录速览

```
agent-store/
└── community/
    └── EvoAgent/
        ├── deployment/          # ← 本指南所在目录
        │   ├── Dockerfile
        │   ├── build.sh
        │   ├── run.sh
        │   ├── stop.sh
        │   ├── export-bundle.sh
        │   ├── import-bundle.sh
        │   └── config/
        │       └── .env.example
        ├── src/evo_agent/       # 主源码
        ├── scenarios/           # 业务场景（edp_agent 等）
        ├── skills/              # Agent Skill
        ├── pyproject.toml
        ├── Makefile
        └── README.md
```

---

## 3. 构建 Docker 镜像

进入部署目录并执行 `build.sh`。该脚本会完成 5 个步骤：同步代码 → 获取 openjiuwen wheel → 复制到 vendor 目录 → docker build → 验证镜像。

### 3.1 进入部署目录

```bash
cd ~/EvoAgent/agent-store/community/EvoAgent/deployment
```

### 3.2 选择构建模式

| 模式 | 命令 | 说明 |
|------|------|------|
| **源码构建**（默认） | `./build.sh` | clone `agent-core` 源码 → 本地构建 wheel |
| **PyPI 下载** | `./build.sh --local` | 直接从 PyPI 下载 `openjiuwen==0.1.13` |
| **跳过拉取** | `./build.sh --skip-pull` | 使用本地已有代码/wheel，不联网 |

> 💡 新手首推 `./build.sh --local`：不需要额外 clone agent-core 仓库，最简单。

### 3.3 常用示例

```bash
# 示例 1：最简模式（推荐新手）
./build.sh --local

# 示例 2：完整源码构建（默认路径）
./build.sh

# 示例 3：指定 agent-store 路径 + 自定义镜像 tag
EVOAGENT_IMAGE_TAG=evoagent:v1.0.0 ./build.sh /path/to/agent-store

# 示例 4：已 clone 代码，仅构建镜像
./build.sh --skip-pull --local
```

### 3.4 关键环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `EVOAGENT_IMAGE_TAG` | `evoagent:latest` | 构建出的镜像 tag |
| `EVOAGENT_STORE_REPO` | `https://gitcode.com/openJiuwen/agent-store.git` | 仓库地址（支持 agent-store / agent-solution） |
| `EVOAGENT_STORE_BRANCH` | `main` | 切换分支 |
| `EVOAGENT_STORE_DIR` | `$HOME/EvoAgent/agent-store` | 代码本地存放目录（可覆盖默认路径） |
| `EVOAGENT_REL_PATH` | _自动探测_ | EvoAgent 在仓库内的相对路径；未设置时按 `community/EvoAgent` → `common/agent-evolve/evoagent` 顺序自动探测 |
| `EVOAGENT_CORE_REPO` | _由 STORE_REPO 推断_ | agent-core 仓库地址（源码构建模式用，推断失败时需显式指定） |
| `EVOAGENT_SKIP_PULL` | `0` | `1` 跳过代码拉取 |
| `EVOAGENT_CORE_VERSION` | `0.1.13` | `--local` 模式下从 PyPI 下载的 openjiuwen 版本 |
| `PIP_INDEX_URL` | 华为云镜像 | pip 源（内网可替换为私有源） |

> 💡 脚本启动前会校验 `git` / `python3` / `docker` 命令是否存在，缺失即报错退出。启动后会打印仓库地址、本地目录、EvoAgent 相对路径，便于排错。

### 3.5 验证镜像

构建完成后会输出：

```
[INFO] 构建成功！
[INFO] 镜像: evoagent:latest
```

可手动查看镜像：

```bash
docker images evoagent:latest
# 期望输出：
# REPOSITORY   TAG       IMAGE ID       CREATED          SIZE
# evoagent     latest    xxxxxxxxxxxx   10 seconds ago   ~350MB
```

---

## 4. 配置环境变量

容器启动前**必须**配置 `config/.env`，`run.sh` 通过 `--env-file` 将其注入容器。

### 4.1 复制模板

```bash
cd deployment
cp config/.env.example config/.env
vim config/.env        # 或使用其他编辑器
```

### 4.2 必填项

| 变量 | 示例值 | 说明 |
|------|--------|------|
| `EVO_ADAPTER_URL` | `http://124.71.234.237:8900` | **Adapter Sidecar 地址**（不填则 `POST /optimize` 返回 500） |
| `EVO_LLM_API_KEY` | `sk-xxxxxx` | **LLM API 密钥** |
| `EVO_LLM_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | LLM base URL |
| `EVO_OPTIMIZER_MODEL` | `qwen3-max` | optimizer 用的模型 |
| `EVO_TARGET_MODEL` | `qwen3-max` | 目标 Agent 用的模型 |

### 4.3 数据路径白名单（重要）

```env
EVO_ALLOWED_DATA_ROOTS=/tmp/evo_agent,/data/evo_agent,/home/evolution/evoagent-studio
```

**作用**：API 提交优化任务时的 `dataset_path` 必须位于这些根目录下，否则返回 422（防路径穿越）。

**容器内挂载关系**（由 `run.sh` 自动完成）：

| 宿主机路径 | 容器内路径 | 用途 |
|-----------|-----------|------|
| `deployment/workspace` | `/app/workspace` | 工作区、输出、artifacts |
| `/home/evolution/data` | `/data` | 数据集文件 |

> 如果数据集放在 `/data/evo_agent/xxx.json`，宿主机对应 `/home/evolution/data/evo_agent/xxx.json`（因为 `/home/evolution/data` → `/data`）。配置 `EVO_ALLOWED_DATA_ROOTS` 时请保留 `/data/evo_agent`。

### 4.4 可选项（默认值已可用）

```env
EVO_REMOTE_TIMEOUT=300.0      # 远程调用超时（秒）
EVO_DEFAULT_EPOCHS=3          # 默认训练轮数
EVO_DEFAULT_BATCH_SIZE=4      # 默认 batch
EVO_SCORE_THRESHOLD=0.5       # 成功/失败分界线
EVO_PARALLELISM=4             # 并发度
```

完整变量含义参见项目根目录的 `docs/develop/scenario-optimizer-guide.md` 与 `docs/api/optimization-api-reference.md`。

---

## 5. 启动容器（部署）

### 5.1 默认启动

```bash
cd deployment
./run.sh
```

`run.sh` 会自动完成：
1. 检查镜像 `evoagent:latest` 是否存在
2. 检查 `config/.env` 是否存在（不存在会自动从模板复制并提示编辑）
3. 删除同名旧容器
4. 创建 `workspace/` 目录与 `/home/evolution/data` 目录
5. 启动容器（带 healthcheck、自动重启策略）
6. 等待 30 秒内健康检查通过

### 5.2 自定义启动参数

```bash
# 指定镜像 tag 和端口
./run.sh --image evoagent:v1.0.0 --port 8000

# 指定容器名
./run.sh --name my-evoagent
```

### 5.3 容器运行配置说明

`run.sh` 启动命令的关键参数：

```bash
docker run -d \
    --name evoagent \
    --init \
    --add-host "host.docker.internal:host-gateway" \
    -p 8000:8000 \
    --env-file config/.env \
    -v ./workspace:/app/workspace \
    -v /home/evolution/data:/data \
    --restart unless-stopped \
    --health-cmd="python -c \"import urllib.request; urllib.request.urlopen('http://localhost:8000/openapi.json')\"" \
    --health-interval=30s \
    --health-timeout=5s \
    --health-retries=3 \
    --health-start-period=15s \
    evoagent:latest
```

> ⚠️ **网络模式**：默认 `-p 8000:8000`。如 Adapter Sidecar 与 EvoAgent 部署在同一主机，`.env` 中可将 `EVO_ADAPTER_URL` 设为 `http://host.docker.internal:8900`（容器已自动添加 host-gateway）。

---

## 6. 部署验证

### 6.1 查看容器状态

```bash
docker ps --filter "name=evoagent"
```

期望 `STATUS` 列显示 `Up X minutes (healthy)`。

### 6.2 健康检查

```bash
curl http://localhost:8000/openapi.json | head -c 200
# 期望返回 JSON：{"openapi":"3.1.0","info":...}
```

### 6.3 查看 Swagger 文档

浏览器访问：

```
http://<服务器IP>:8000/docs
```

### 6.4 提交一个优化任务（完整测试）

```bash
curl -X POST http://localhost:8000/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "task_name": "smoke-test",
    "agent_name": "edp_agent",
    "dataset_path": "/data/evo_agent/test.json",
    "optimizer_template": {
      "name": "edp_agent",
      "scenario": "edp_agent",
      "hyperparams": {"num_epochs": 1},
      "train_split": 0.8,
      "val_split": 0.2
    },
    "evaluator_template": {
      "name": "default_eval",
      "scenario": "edp_agent"
    }
  }'
```

期望返回：

```json
{"job_id": "job_xxx", "status": "queued"}
```

查询任务进度：

```bash
curl http://localhost:8000/optimize/job_xxx
```

---

## 7. 日志查看与排障

### 7.1 实时查看容器日志

```bash
docker logs -f evoagent
```

### 7.2 查看最近 200 行

```bash
docker logs --tail 200 evoagent
```

### 7.3 带时间戳查看

```bash
docker logs -t evoagent
```

### 7.4 工作区 artifacts

EvoAgent 在运行过程中会产出 artifacts 到 `workspace/` 目录（挂载到宿主机 `deployment/workspace/`）：

```
deployment/workspace/
├── outputs/         # 优化报告输出
└── artifacts/       # 训练过程产物（skill patches、meta skill 等）
```

```bash
ls -lh deployment/workspace/outputs/
ls -lh deployment/workspace/artifacts/
```

### 7.5 进入容器排查

```bash
docker exec -it evoagent bash
# 容器内查看进程
ps aux | grep uvicorn
# 容器内测试 API
curl http://localhost:8000/openapi.json | head
```

### 7.6 常见问题

| 现象 | 原因 / 解决 |
|------|-------------|
| `POST /optimize` 返回 500，提示 `EVO_ADAPTER_URL not configured` | `.env` 未填 `EVO_ADAPTER_URL`，或未通过 `run.sh` 加载 |
| `422 Dataset path must be under allowed roots` | `dataset_path` 不在 `EVO_ALLOWED_DATA_ROOTS` 白名单下 |
| `422 Dataset file not found` | 文件不存在；注意容器内路径（`/data` 对应宿主机 `/home/evolution/data`） |
| 容器一直 `starting` 不转 `healthy` | 等待 15 秒启动期后查 `docker logs evoagent`；多为 `.env` 配置缺失 |
| 优化任务超时 | 调大 `EVO_REMOTE_TIMEOUT` 或检查 Adapter Sidecar 健康 |
| `docker build` 拉 wheel 失败 | 切换 `PIP_INDEX_URL` 为可用源；或改用 `--local` 模式 |

---

## 8. 停止与下线

### 8.1 停止默认容器

```bash
./stop.sh
```

### 8.2 停止指定容器

```bash
./stop.sh my-evoagent
```

### 8.3 停止所有 evoagent* 容器

```bash
./stop.sh --all
```

### 8.4 清理镜像（可选）

```bash
docker rmi evoagent:latest
docker rmi evoagent:v1.0.0
```

---

## 9. 更新与重新部署

```bash
cd deployment

# 1. 停止旧容器
./stop.sh

# 2. 拉取最新代码并重建镜像
./build.sh --local            # 或 ./build.sh 走源码构建

# 3. 启动新容器
./run.sh
```

> 如使用 git 版本作为镜像 tag，可保留多版本以备回滚：
> ```bash
> EVOAGENT_IMAGE_TAG=evoagent:$(git rev-parse --short HEAD) ./build.sh --skip-pull --local
> ./run.sh --image evoagent:<short-hash>
> ```

---

## 10. 离线部署（内网环境）

适用于目标服务器无法访问公网 `docker.io` 或 `gitcode.com` 的场景。流程为：**联网机器构建+导出 → 拷贝 tar 包 → 离线机器导入+运行**。

### 10.1 联网机器：导出离线包

```bash
cd deployment

# 1. 先正常构建镜像
./build.sh --local

# 2. 导出镜像+脚本+配置为 tar.gz
./export-bundle.sh evoagent:latest
# 产物：../evoagent-offline-YYYYMMDD.tar.gz
```

`export-bundle.sh` 会将以下内容打包：
- Docker 镜像 tar 文件
- `run.sh` / `stop.sh` / `import-bundle.sh`
- `config/` 目录（含 `.env.example`）
- 部署说明 README

### 10.2 传输到离线机器

```bash
scp evoagent-offline-YYYYMMDD.tar.gz user@offline-host:/opt/
```

### 10.3 离线机器：导入并启动

```bash
cd /opt
tar xzf evoagent-offline-YYYYMMDD.tar.gz
cd evoagent-offline-YYYYMMDD

# 1. 导入镜像
./import-bundle.sh evoagent.latest.YYYYMMDD.tar

# 2. 配置环境变量
cp config/.env.example config/.env
vim config/.env

# 3. 启动
./run.sh

# 4. 验证
curl http://localhost:8000/openapi.json
```

> 离线机器仍需预装 Docker（20.10+），并保证 Adapter Sidecar 与 LLM API 在内网可访问。

---

## 11. 本地开发模式（替代方案）

如不需要 Docker 化部署（如本地调试、运行单元测试），可直接使用 `Makefile`。

### 11.1 安装工具链

- Python 3.12+
- [uv](https://github.com/astral-sh/uv)（推荐）

```bash
# 安装 uv（若未安装）
curl -LsSf https://astral.sh/uv/install.sh | sh
```

### 11.2 安装依赖

```bash
cd ~/EvoAgent/agent-store/community/EvoAgent

cp .env.example .env
# 编辑 .env 填入配置

make install      # 等价于 uv sync --all-extras
```

### 11.3 启动开发 API

```bash
make serve
# 等价于 uv run uvicorn evo_agent.api.app:app --host 0.0.0.0 --port 8001
```

> 注意：本地开发模式默认端口为 `8001`，与 Docker 模式的 `8000` 不同。API 文档地址：`http://localhost:8001/docs`。

### 11.4 CLI 模式（对话式优化）

```bash
make dev          # 等价于 uv run python -m evo_agent
```

### 11.5 跑测试

```bash
make test         # 全部测试（含 e2e）
make test-unit    # 仅单元测试
make lint         # 静态检查（ruff + mypy）
make fix          # 自动修复 lint 问题
```

常用开发命令均集中在 [Makefile](../Makefile) 中，详见根目录 `README.md`。

---

## 12. 一线 Quick Reference

```bash
# === 完整生命周期 ===
cd ~/EvoAgent/agent-store/community/EvoAgent/deployment

# 构建（首次）
./build.sh --local

# 配置
cp config/.env.example config/.env && vim config/.env

# 启动
./run.sh

# 验证
curl http://localhost:8000/openapi.json
docker logs -f evoagent

# 停止
./stop.sh

# 更新
./stop.sh && ./build.sh --local && ./run.sh

# 离线导出
./export-bundle.sh evoagent:latest
```

---

## 13. 相关文档索引

- 项目根 README：[../README.md](../README.md)
- 架构规则：[../docs/rules/architecture.md](../docs/rules/architecture.md)
- API 参考：[../docs/api/optimization-api-reference.md](../docs/api/optimization-api-reference.md)
- 场景开发指南：[../docs/develop/scenario-optimizer-guide.md](../docs/develop/scenario-optimizer-guide.md)
- 配置源码：[../src/evo_agent/config.py](../src/evo_agent/config.py)
- Adapter 部署：`community/EvoAgentAdapter/README.md`（同仓库的兄弟目录）

---

*本指南基于项目当前实现，若脚本或环境变量发生变化，请以 `deployment/` 实际文件为准。*
