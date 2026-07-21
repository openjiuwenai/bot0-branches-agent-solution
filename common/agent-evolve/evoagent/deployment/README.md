# EvoAgent Deployment

EvoAgent 是基于 agent-core 的自进化元 Agent，以 FastAPI 服务形式对外提供 skill 文档自动优化能力。
本目录包含完整的 Docker 化部署脚本，支持**联网构建**与**离线包**两种交付方式。

> 更详细的操作流程见 [operations-guide.md](./operations-guide.md)。

---

## 目录结构

```
deployment/
├── Dockerfile              # 单阶段构建镜像（python:3.12-slim）
├── build.sh                # 镜像构建脚本（含 agent-core wheel 获取）
├── run.sh                  # 容器启动脚本（含健康检查）
├── stop.sh                 # 容器停止脚本
├── export-bundle.sh        # 导出离线部署包
├── import-bundle.sh        # 导入离线镜像
├── config/
│   ├── .env.example        # 环境变量模板
│   └── .env                # 实际配置（从模板复制，git 忽略）
└── workspace/              # 运行时工作区（挂载到容器，git 忽略）
```

---

## 前置条件

| 项 | 要求 |
|----|------|
| 操作系统 | Linux（推荐 Ubuntu 22.04 / CentOS 7+） |
| Docker | 20.10+ |
|磁盘空间 | ≥ 5 GB |
| Adapter Sidecar | 已独立部署，EvoAgent 通过 `EVO_ADAPTER_URL` 访问 |
| LLM API | OpenAI 兼容接口 |

> Adapter Sidecar 部署见同仓库 `common/agent-evolve/evoagent-adapter`。EvoAgent 与 Adapter 必须**同时可用**。

---

## Quick Start（5 步上线）

```bash
# 1. 进入部署目录
cd deployment

# 2. 构建镜像（--local 从 PyPI 下载 openjiuwen agent-core wheel，最简模式）
HOME=/home/evolution/build EVOAGENT_SOLUTION_REPO=https://gitcode.com/AE-TEAM/agent-solution.git EVOAGENT_SOLUTION_BRANCH=common EVOAGENT_IMAGE_TAG=evoagent:latest ./build.sh --local

# 3. 配置环境变量
cp config/.env.example config/.env
vim config/.env        # 至少填 EVO_ADAPTER_URL 和 EVO_LLM_API_KEY

# 4. 启动容器
./run.sh

# 5. 验证（返回 JSON 即成功）
curl http://localhost:8000/openapi.json
```

浏览器访问 `http://<服务器IP>:8000/docs` 查看 Swagger 文档。

---

## 1. 代码下载

### 方式 A：手动 clone（推荐首次了解项目）

```bash
git clone --branch {{EVOAGENT_SOLUTION_BRANCH}} {{EVOAGENT_SOLUTION_REPO}} ~/EvoAgent/agent-solution
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment
```

### 方式 B：由 build.sh 自动 clone

`build.sh` 默认会 clone 到 `$HOME/EvoAgent/agent-solution`，无需手动操作（见第 2 节）。

---

## 2. 镜像构建

`build.sh` 分 5 步：**同步代码 → 获取 openjiuwen wheel → 复制到 vendor → docker build → 验证镜像**。

### 构建模式

| 模式 | 命令 | 适用场景 |
|------|------|----------|
| PyPI 下载（推荐新手） | `./build.sh --local` | 直接从 PyPI 拉 `openjiuwen-agent-core==0.1.13` |
| 源码构建（默认） | `./build.sh` | clone agent-core 源码本地构建 wheel |
| 跳过拉取 | `./build.sh --skip-pull` | 复用本地代码与 wheel，不联网 |

### 常用示例

```bash
# 最简模式
./build.sh --local

# 指定 agent-solution 仓库地址 + 仓库分支 + 自定义镜像 tag
HOME=/home/evolution/build EVOAGENT_SOLUTION_REPO=https://gitcode.com/AE-TEAM/agent-solution.git EVOAGENT_SOLUTION_BRANCH=common EVOAGENT_IMAGE_TAG=evoagent:v0.0.5 ./build.sh --local

# 已 clone 代码，仅构建镜像
./build.sh --skip-pull --local
```

### 关键环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `EVOAGENT_IMAGE_TAG` | `evoagent:latest` | 构建出的镜像 tag |
| `EVOAGENT_SOLUTION_REPO` | `https://gitcode.com/openJiuwen/agent-solution.git` | agent-solution 仓库地址 |
| `EVOAGENT_SOLUTION_BRANCH` | `common` | 仓库分支 |
| `EVOAGENT_SOLUTION_DIR` | `$HOME/EvoAgent/agent-solution` | 代码本地存放目录（也可用首个位置参数覆盖） |
| `EVOAGENT_CORE_REPO` | `https://gitcode.com/openJiuwen/agent-core.git` | 源码构建模式使用的 agent-core 仓库 |
| `EVOAGENT_CORE_BRANCH` | `main` | agent-core 分支（独立于 solution 分支） |
| `EVOAGENT_CORE_VERSION` | `0.1.13` | `--local` 模式下 PyPI 版本号 |
| `EVOAGENT_SKIP_PULL` | `0` | `1` 跳过代码拉取 |
| `PIP_INDEX_URL` | 华为云镜像 | pip 源（内网可替换为私有源） |

### 验证镜像

```bash
docker images evoagent:latest
# REPOSITORY   TAG       IMAGE ID       CREATED          SIZE
# evoagent     latest    xxxxxxxxxxxx   10 seconds ago   ~350MB
```

---

## 3. 运行配置

### 3.1 复制模板

```bash
cp config/.env.example config/.env
vim config/.env
```

### 3.2 必填项

| 变量 | 示例 | 说明 |
|------|------|------|
| `EVO_ADAPTER_URL` | `http://124.71.234.237:8900` | **Adapter Sidecar 地址**（未配置时 `POST /optimize` 返回 500） |
| `EVO_LLM_API_KEY` | `sk-xxxxxx` | **LLM API 密钥** |
| `EVO_LLM_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | LLM base URL |
| `EVO_OPTIMIZER_MODEL` | `qwen3-max` | optimizer 用的模型 |
| `EVO_TARGET_MODEL` | `qwen3-max` | 目标 Agent 用的模型 |

#### 3.2.1 自定义 SSE 模式

自定义 SSE 模型端点（OpenAI 兼容流式 `chat/completions` 端点，仅服务评估器/反思器/优化器，不影响 ReActAgent→Adapter 链）：

```env
EVO_LLM_PROVIDER=CustomSSE                # 切到 CustomSSE 模式（大小写不敏感）
EVO_CUSTOM_SSE_TOKEN=<TOKEN>                 # 私有部署鉴权 token（Secret 注入）
EVO_CUSTOM_SSE_USER_ID=<固定 userId>       # 私有部署分配的 userId
EVO_CUSTOM_SSE_ENDPOINT=https://llm-gateway.example.com/v1/chat/completions
EVO_CUSTOM_SSE_CONTEXT_WINDOW_TOKENS=32768 # 必填，按现场模型规格填写
EVO_CUSTOM_SSE_TIMEOUT=120.0               # 可选，流式 read 超时（秒），默认 120
```

设为 `CustomSSE` 后，`EVO_LLM_API_KEY` / `EVO_LLM_BASE_URL` 被忽略；token、userId、endpoint、context window 四项必填（启动时 fail-fast 校验）。

### 3.3 数据路径白名单（重要）

```env
EVO_ALLOWED_DATA_ROOTS=/tmp/evo_agent,/data/evo_agent,/home/evolution/evoagent-studio
```

**作用**：API 提交优化任务时，`dataset_path` 必须位于这些根目录下，否则返回 422（防路径穿越）。

**容器挂载关系**（由 `run.sh` 自动完成）：

| 宿主机路径 | 容器内路径 | 用途 |
|-----------|-----------|------|
| `deployment/workspace` | `/app/workspace` | 工作区、输出、artifacts |
| `/home/evolution/data` | `/data` | 数据集文件 |

> 数据集文件放在宿主机 `/home/evolution/data/evo_agent/xxx.json`，容器内访问路径为 `/data/evo_agent/xxx.json`。配置 `EVO_ALLOWED_DATA_ROOTS` 时保留 `/data/evo_agent`。

### 3.4 可选项（默认值已可用）

```env
EVO_REMOTE_TIMEOUT=300.0      # 远程调用超时（秒）
EVO_DEFAULT_EPOCHS=3          # 默认训练轮数
EVO_DEFAULT_BATCH_SIZE=4      # 默认 batch
EVO_SCORE_THRESHOLD=0.5       # 成功/失败分界线
EVO_PARALLELISM=4             # 并发度
EVO_MANAGED_DOC_APPLY_DEADLINE=600 # AgentRule apply 总等待时限
EVO_MANAGED_DOC_CANCEL_ROLLBACK_DEADLINE=900 # 必须大于 apply deadline
EVOAGENT_CONTROL_DB_PATH=./workspace/evoagent-control.db # 必须位于持久卷
```

完整字段见 [config/.env.example](./config/.env.example)。

---

## 4. 启动部署

### 4.1 默认启动

```bash
./run.sh
```

`run.sh` 自动完成：校验镜像 → 校验 `.env` → 清理旧容器 → 创建目录 → 启动容器（带 healthcheck + 自动重启）→ 等待就绪。

### 4.2 自定义参数

```bash
./run.sh --image evoagent:v1.0.0 --port 8000   # 指定镜像和端口
./run.sh --name my-evoagent                      # 指定容器名
```

### 4.3 容器关键配置

- 端口映射：`-p 8000:8000`（容器内 uvicorn 监听 8000）
- 环境注入：`--env-file config/.env`
- 工作区挂载：`-v ./workspace:/app/workspace`
- 数据挂载：`-v /home/evolution/data:/data`
- 网络互通：`--add-host "host.docker.internal:host-gateway"`（Adapter 同机部署时可用 `http://host.docker.internal:8900`）
- 自动重启：`--restart unless-stopped`
- 健康检查：每 30 秒 curl `/openapi.json`

---

## 5. 部署验证

```bash
# 容器状态（期望 STATUS: Up ... (healthy)）
docker ps --filter "name=evoagent"

# 健康检查
curl http://localhost:8000/openapi.json | head -c 200

# Swagger 文档
# 浏览器访问 http://<服务器IP>:8000/docs

# 提交 AgentRule managed-doc 测试任务（不能同时传 skills）
curl -X POST http://localhost:8000/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "task_name": "smoke-test",
    "agent_name": "edp_agent",
    "managed_doc_kind": "agent_rule",
    "dataset_path": "/data/evo_agent/test.json",
    "optimizer_template": {
      "name": "edp_agent",
      "scenario": "edp_agent",
      "hyperparams": {"num_epochs": 1},
      "train_split": 0.8,
      "val_split": 0.2
    },
    "evaluator_template": {"name": "default_eval", "scenario": "edp_agent"}
  }'
# 期望：{"job_id":"job_xxx","status":"queued"}

# 查询任务进度
curl http://localhost:8000/optimize/job_xxx
```

提交前先确认 Adapter 的 managed-doc baseline 已应用且没有 pending：

```bash
curl -X POST "http://<adapter-host>:8900/api/v1/managed-docs" \
  -H "Content-Type: application/json" \
  -d '{"action":"content","agent_name":"edp_agent","doc_kind":"agent_rule"}'
```

响应中的 `file_revision` 与 `applied_revision` 应相等，`pending_apply` 应为
`false`。`EVO_MANAGED_DOC_APPLY_DEADLINE` 必须至少比响应中的
`max_task_seconds` 大 10 秒，否则 EvoAgent 会在任务开始时拒绝执行。
若全新部署返回 `applied_revision: null` / `pending_apply: true`，须先按 Adapter
部署指南的“AgentRule managed-doc 配置与首启 bootstrap”用当前内容执行一次真实
update + restart；不能直接提交 EvoAgent 优化任务。

---

## 6. 日志查看

```bash
# 实时日志
docker logs -f evoagent

# 最近 200 行
docker logs --tail 200 evoagent

# 带时间戳
docker logs -t evoagent

# 进入容器排查
docker exec -it evoagent bash
```

### 运行时产物

```
deployment/workspace/
├── outputs/         # 优化报告
└── artifacts/       # 训练过程产物（skill patches、meta skill 等）
```

```bash
ls -lh deployment/workspace/outputs/
ls -lh deployment/workspace/artifacts/
```

### 常见问题

| 现象 | 解决 |
|------|------|
| `POST /optimize` 返回 500 `EVO_ADAPTER_URL not configured` | `.env` 未填 `EVO_ADAPTER_URL`，或未通过 `run.sh` 加载 |
| `422 Dataset path must be under allowed roots` | `dataset_path` 不在 `EVO_ALLOWED_DATA_ROOTS` 白名单下 |
| `422 Dataset file not found` | 文件不存在；注意容器内 `/data` 对应宿主机 `/home/evolution/data` |
| 容器一直 `starting` 不转 `healthy` | 等待 15 秒启动期后查 `docker logs evoagent`，多为 `.env` 配置缺失 |
| `docker build` 拉 wheel 失败 | 切换 `PIP_INDEX_URL` 为可用源，或改用 `--local` 模式 |

---

## 7. 停止与更新

```bash
# 停止默认容器
./stop.sh

# 停止指定容器
./stop.sh my-evoagent

# 停止所有 evoagent* 容器
./stop.sh --all

# 更新部署（停 → 构建新镜像 → 启动）
./stop.sh && ./build.sh --local && ./run.sh
```

---

## 8. 离线部署

适用于目标服务器无法访问公网的场景。**联网机器导出 → 拷贝 → 离线机器导入运行**。

### 联网机器

```bash
./build.sh --local                          # 1. 构建镜像
./export-bundle.sh evoagent:latest          # 2. 导出离线包
# 产物：../evoagent-offline-YYYYMMDD.tar.gz
```

### 离线机器

```bash
tar xzf evoagent-offline-YYYYMMDD.tar.gz
cd evoagent-offline-YYYYMMDD

./import-bundle.sh evoagent.latest.YYYYMMDD.tar    # 导入镜像
cp config/.env.example config/.env && vim config/.env
./run.sh
curl http://localhost:8000/openapi.json            # 验证
```

> 离线机器需预装 Docker 20.10+，并保证 Adapter Sidecar 与 LLM API 在内网可访问。

---

## 相关文档

| 文档 | 路径 |
|------|------|
| 详细操作指南 | [operations-guide.md](./operations-guide.md) |
| 项目 README | [../README.md](../README.md) |
| API 参考 | [../docs/api/optimization-api-reference.md](../docs/api/optimization-api-reference.md) |
| 配置源码 | [../src/evo_agent/config.py](../src/evo_agent/config.py) |
| Adapter 部署 | `common/agent-evolve/evoagent-adapter/`（同仓库兄弟目录） |

---

*本 README 基于项目当前实现，若脚本或环境变量发生变化，请以 `deployment/` 实际文件为准。*
