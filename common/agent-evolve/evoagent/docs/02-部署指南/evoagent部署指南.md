# EvoAgent 部署指南（Quick Install）

本指南采用**最精简方式**完成 EvoAgent + EvoAgentAdapter 双容器部署。所有命令直接复制可用。

> Quick Install 选择 `--local` 模式：从 PyPI 下载 `openjiuwen` wheel，跳过 agent-core 源码克隆与构建，命令最少、依赖最轻。

---

## 0. 架构与前置条件

### 0.1 架构（两个容器）

```
┌─────────────┐      EVO_ADAPTER_URL       ┌──────────────────┐
│  EvoAgent   │ ──────────────────────────▶│  EvoAgentAdapter │
│  :8000      │   skill 优化 / managed-doc  │  :8900           │
│  (优化器)    │ ◀──────────────────────────│  (轨迹+调用代理)  │
└─────────────┘       轨迹 / 调用结果        └──────────────────┘
                                                │ 挂载
                                                ▼
                                     业务 Agent 日志 / skills / AgentRule.md
```

| 组件 | 端口 | 镜像 | 目录 |
|------|------|------|------|
| EvoAgent | 8000 | `evoagent:latest` | `common/agent-evolve/evoagent/deployment/` |
| EvoAgentAdapter | 8900 | `agent-adapter:latest` | `common/agent-evolve/evoagent-adapter/deployment/` |

### 0.2 前置条件

- **OS**: Linux（Ubuntu 22.04 / CentOS 7+），bash
- **Docker**: 20.10+（含 buildkit）
- **Python**: 3.12+（仅构建时用，运行在容器内）
- **git**: 任意版本
- **网络**: 可访问 `gitcode.com` + pip 镜像源
- **外部依赖**: LLM API（OpenAI 兼容，如阿里云 DashScope）

---

## 1. 获取代码

```bash
# 克隆 agent-solution 仓库 common 分支
git clone --branch common https://gitcode.com/openJiuwen/agent-solution.git ~/EvoAgent/agent-solution
cd ~/EvoAgent/agent-solution
```

目录结构：

```
agent-solution/
└── common/agent-evolve/
    ├── evoagent/                  # EvoAgent 服务
    │   └── deployment/            # ← build.sh / run.sh / stop.sh
    └── evoagent-adapter/          # Adapter 服务
        └── deployment/            # ← start.sh / stop.sh
```

---

## 2. 部署 EvoAgentAdapter（先于 EvoAgent 启动）

EvoAgent 启动后需立即访问 `EVO_ADAPTER_URL`，故 Adapter 必须先就绪。

### 2.1 配置

```bash
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent-adapter/deployment

# 从模板创建配置
cp config/.env.example config/.env
```

编辑 `config/.env`，**必填**项（其余保持默认即可）：

```bash
# 业务 Agent 日志父目录（只读挂载，per-agent 子目录）
HOST_LOG_ROOT=/var/log/agents

# 业务 Agent skills 父目录（读写挂载，per-agent 子目录）
HOST_SKILLS_ROOT=/opt/agents/skills

# managed-doc 公共父目录（读写挂载，AgentRule.md 等）
HOST_AGENTS_ROOT=/opt/agents/runtime

# Adapter 输出数据目录（offsets、归档）
HOST_OUTPUT_DIR=/opt/agent-adapter/data

# Adapter 配置文件持久化路径（首次启动自动从镜像 seed）
HOST_CONFIG_FILE=/opt/agent-adapter/agent_adapter_config.yaml
```

创建主机目录（避免挂载失败）：

```bash
mkdir -p /var/log/agents /opt/agents/skills /opt/agents/runtime /opt/agent-adapter/data
```

### 2.2 配置 agent_adapter_config.yaml（多业务 Agent）

`config/agent_adapter_config.yaml` 是模板；首次 `start.sh` 会自动 seed 到 `HOST_CONFIG_FILE`。如需自定义业务 Agent，编辑 `HOST_CONFIG_FILE`：

```yaml
agents:
  - name: "edp_agent"
    log_dir: "/data/logs/edp_agent"        # 对应 HOST_LOG_ROOT/edp_agent
    skills_dir: "/data/skills/edp_agent"   # 对应 HOST_SKILLS_ROOT/edp_agent
    agent_url: "http://192.168.1.10:8090"  # 业务 Agent 地址（勿用 localhost）
    project_id: "proj_001"
    agent_id: "edp_agent"
    timeout: 300
```

> 业务 Agent 若端口发布到宿主机，`agent_url` 可写 `http://host.docker.internal:8090`（脚本已注入 host-gateway）。

### 2.3 构建并启动

```bash
# 一条命令：构建镜像 + 启动容器（首次必须 --build）
./start.sh --build
```

脚本行为：
1. `docker build -t agent-adapter:latest ..`（构建上下文为上级 `evoagent-adapter/`）
2. 首次启动自动 seed `agent_adapter_config.yaml` 到 `HOST_CONFIG_FILE`
3. 挂载日志 / skills / agents / 配置文件卷
4. 健康检查 `GET /api/v1/status`，等待 healthy

### 2.4 验证

```bash
# 健康检查
curl http://localhost:8900/api/v1/status

# 查看日志
docker logs -f agent-adapter
```

---

## 3. 部署 EvoAgent

### 3.1 配置

```bash
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment

# 从模板创建配置
cp config/.env.example config/.env
```

编辑 `config/.env`，**必填**项：

```bash
# Adapter 地址（指向刚启动的 Adapter 容器；同机用宿主 IP，勿用 localhost）
EVO_ADAPTER_URL=http://<宿主IP>:8900

# LLM 配置
EVO_LLM_API_KEY=sk-xxxxxx
EVO_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EVO_OPTIMIZER_MODEL=qwen3.7-max
EVO_EVALUATOR_MODEL=qwen3.7-max
EVO_TARGET_MODEL=qwen3.7-max
```

> 其余参数（训练轮数、并发度、路径等）保持默认即可，详见 `config/.env.example` 注释。

### 3.2 构建镜像（Quick Install：--local 模式）

```bash
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment

# --local：从 PyPI 下载 openjiuwen wheel，无需克隆 agent-core 源码
HOME=/home/evolution/build ./build.sh --local
```

脚本行为（5 步）：
1. 同步 agent-solution 代码（已 clone 则 pull）
2. `pip download openjiuwen==0.1.13`（从 PyPI）
3. 复制 wheel 到 `vendor/`
4. `docker build -t evoagent:latest`
5. 验证镜像

### 3.3 启动

```bash
./run.sh
```

脚本行为：
1. 读取 `config/.env`，校验镜像存在
2. 清理同名旧容器
3. 创建 `workspace/` 工作区目录
4. `docker run` 启动，挂载 workspace + 数据卷
5. 健康检查 `GET /openapi.json`，等待 healthy

### 3.4 验证

```bash
# API 文档
curl http://localhost:8000/openapi.json | head -c 200

# 健康检查
curl http://localhost:8000/docs

# 查看日志
docker logs -f evoagent
```

---

## 4. 联调验证

两容器均启动后，端到端验证：

```bash
# 1. Adapter 健康
curl http://localhost:8900/api/v1/status

# 2. EvoAgent 健康
curl http://localhost:8000/openapi.json | grep -o '"title":"[^"]*"'

# 3. EvoAgent 能访问 Adapter（检查 .env 的 EVO_ADAPTER_URL 正确）
docker exec evoagent curl -s ${EVO_ADAPTER_URL}/api/v1/status
```

---

## 5. 运维命令速查

### 5.1 EvoAgent

```bash
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment

./run.sh                              # 启动
./stop.sh                             # 停止
./stop.sh --all                       # 停止所有 evoagent 容器
docker logs -f evoagent               # 查看日志
docker restart evoagent               # 重启
```

### 5.2 EvoAgentAdapter

```bash
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent-adapter/deployment

./start.sh                            # 启动（镜像已存在）
./start.sh --build                    # 重新构建并启动
./stop.sh                             # 停止
docker logs -f agent-adapter          # 查看日志
docker restart agent-adapter          # 重启
```

### 5.3 更新代码后重新部署

```bash
# EvoAgent（拉新代码 + 重建镜像）
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment
./stop.sh
HOME=/home/evolution/build ./build.sh --local
./run.sh

# Adapter（拉新代码 + 重建镜像）
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent-adapter/deployment
./stop.sh
./start.sh --build
```

---

## 6. 常见问题

### Q1: `build.sh --local` 下载 wheel 失败

检查 pip 镜像源与网络。脚本默认使用华为云源，可覆盖：

```bash
PIP_INDEX_URL=https://pypi.org/simple HOME=/home/evolution/build ./build.sh --local
```

### Q2: EvoAgent 启动后健康检查不通过

```bash
docker logs evoagent 2>&1 | tail -50
```

常见原因：`EVO_ADAPTER_URL` 不可达、LLM API Key 无效、`EVO_LLM_BASE_URL` 错误。

### Q3: Adapter `agent_url` 写 localhost 不通

容器内 `localhost` 指向容器自身。业务 Agent 端口发布到宿主机时，写 `http://host.docker.internal:<port>`（脚本已注入 host-gateway）。

### Q4: 业务 Agent 日志未被读取

确认 `HOST_LOG_ROOT/{agent_name}` 子目录存在且与 `agent_adapter_config.yaml` 的 `log_dir: /data/logs/{agent_name}` 对应。

---

## 附：端口与卷速查

| 容器 | 端口 | 宿主卷 → 容器路径 |
|------|------|-------------------|
| evoagent | 8000:8000 | `deployment/workspace` → `/app/workspace`<br>`/home/evolution/data` → `/data` |
| agent-adapter | 8900:8900 | `HOST_LOG_ROOT` → `/data/logs` (ro)<br>`HOST_SKILLS_ROOT` → `/data/skills` (rw)<br>`HOST_AGENTS_ROOT` → `/data/agents` (rw)<br>`HOST_OUTPUT_DIR` → `/app/data` (rw)<br>`HOST_CONFIG_FILE` → `/app/agent_adapter_config.yaml` (rw) |
