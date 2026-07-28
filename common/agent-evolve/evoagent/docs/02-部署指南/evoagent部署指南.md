# EvoAgent 部署指南（Quick Install）

本指南以**最精简方式**完成 EvoAgent + EvoAgentAdapter 双容器部署。

> Quick Install 使用 `--local` 模式：从 PyPI 下载 `openjiuwen` wheel，跳过 agent-core 源码构建，命令最少、依赖最轻。

---

## 0. 前置条件

| 项 | 要求 |
|----|------|
| OS | Linux（Ubuntu 22.04 / CentOS 7+），bash |
| Docker | 20.10+（含 buildkit） |
| Python | 3.12+（仅构建时用，运行在容器内） |
| 网络 | 可访问 `gitcode.com` + pip 镜像源 |
| 外部依赖 | LLM API（OpenAI 兼容，如阿里云 DashScope） |

**架构（两容器，Adapter 必须先启动）：**

```
┌─────────────┐    EVO_ADAPTER_URL    ┌──────────────────┐
│  EvoAgent   │ ────────────────────▶ │  EvoAgentAdapter │
│  :8000      │  skill优化/managed-doc │  :8900           │
└─────────────┘ ◀──────────────────── └──────────────────┘
                 轨迹/调用结果              │ 挂载业务Agent
                                           ▼
                                  日志/skills/AgentRule.md
```

---

## 1. 获取代码

```bash
git clone --branch common https://gitcode.com/openJiuwen/agent-solution.git ~/EvoAgent/agent-solution
cd ~/EvoAgent/agent-solution

# 两个部署目录
# common/agent-evolve/evoagent-adapter/deployment/   ← Adapter
# common/agent-evolve/evoagent/deployment/           ← EvoAgent
```

---

## 2. 一次性配置

### 2.1 创建主机目录

```bash
# Adapter 挂载所需目录（业务 Agent 日志/skills/agents + Adapter 数据/配置）
mkdir -p /var/log/agents /opt/agents/skills /opt/agents/runtime \
         /opt/agent-adapter/data /opt/agent-adapter
```

### 2.2 生成两份 .env

```bash
# Adapter 配置
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent-adapter/deployment
cp config/.env.example config/.env

# EvoAgent 配置
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment
cp config/.env.example config/.env
```

### 2.3 编辑 Adapter 的 `config/.env`（必填项，其余保持默认）

文件：`evoagent-adapter/deployment/config/.env`

```bash
HOST_LOG_ROOT=/var/log/agents           # 业务Agent日志父目录（只读挂载）
HOST_SKILLS_ROOT=/opt/agents/skills     # 业务Agent skills父目录（读写挂载）
HOST_AGENTS_ROOT=/opt/agents/runtime    # managed-doc父目录（读写挂载）
HOST_OUTPUT_DIR=/opt/agent-adapter/data # Adapter输出目录（offsets/归档）
HOST_CONFIG_FILE=/opt/agent-adapter/agent_adapter_config.yaml  # 配置文件持久化路径
```

### 2.4 编辑 EvoAgent 的 `config/.env`（必填项，其余保持默认）

文件：`evoagent/deployment/config/.env`

```bash
EVO_ADAPTER_URL=http://<宿主IP>:8900    # 指向Adapter容器（勿用localhost）

EVO_LLM_API_KEY=sk-xxxxxx
EVO_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EVO_OPTIMIZER_MODEL=qwen3.7-max
```

### 2.5（可选）配置业务 Agent 列表

Adapter 首次启动会自动 seed `agent_adapter_config.yaml` 到 `HOST_CONFIG_FILE`。需自定义业务 Agent 时，编辑该文件：

```yaml
# /opt/agent-adapter/agent_adapter_config.yaml
agents:
  - name: "edp_agent"
    log_dir: "/data/logs/edp_agent"        # 对应 HOST_LOG_ROOT/edp_agent
    skills_dir: "/data/skills/edp_agent"   # 对应 HOST_SKILLS_ROOT/edp_agent
    agent_url: "http://192.168.1.10:8090"  # 业务Agent地址（勿用localhost）
    project_id: "proj_001"
    agent_id: "edp_agent"
    timeout: 300
```

> 业务 Agent 端口发布到宿主机时，`agent_url` 写 `http://host.docker.internal:<port>`（脚本已注入 host-gateway）。

---

## 3. 构建并启动（先 Adapter 后 EvoAgent）

```bash
# ── 3.1 Adapter：构建 + 启动 ──
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent-adapter/deployment
./start.sh --build                    # 一条命令完成构建+启动+健康检查

# ── 3.2 EvoAgent：构建（--local 从 PyPI 下载 wheel） ──
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment
./build.sh --local

# ── 3.3 EvoAgent：启动 ──
./run.sh
```

**脚本行为说明：**
- `start.sh --build`：`docker build` + `docker run` + 自动 seed 配置 + 健康检查 `GET /api/v1/status`
- `build.sh --local`：同步代码 → `pip download openjiuwen==0.1.13` → 复制 wheel → `docker build` → 验证
- `run.sh`：读 `.env` → 清理旧容器 → 创建 workspace → `docker run` + 健康检查 `GET /openapi.json`

---

## 4. 验证

```bash
# Adapter 健康
curl http://localhost:8900/api/v1/status

# EvoAgent 健康
curl http://localhost:8000/openapi.json | grep -o '"title":"[^"]*"'

# EvoAgent 能访问 Adapter
docker exec evoagent curl -s ${EVO_ADAPTER_URL}/api/v1/status

# 查看日志
docker logs -f agent-adapter   # Adapter
docker logs -f evoagent        # EvoAgent
```

---

## 5. 运维速查

```bash
ADAPTER_DIR=~/EvoAgent/agent-solution/common/agent-evolve/evoagent-adapter/deployment
EVOAGENT_DIR=~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment

# 停止
$ADAPTER_DIR/stop.sh
$EVOAGENT_DIR/stop.sh

# 启动（镜像已存在）
$ADAPTER_DIR/start.sh
$EVOAGENT_DIR/run.sh

# 更新代码后重建
cd $ADAPTER_DIR  && ./stop.sh && ./start.sh --build
cd $EVOAGENT_DIR && ./stop.sh && ./build.sh --local && ./run.sh
```

---

## 6. 常见问题

| 问题 | 解决 |
|------|------|
| `build.sh --local` 下载 wheel 失败 | 覆盖 pip 源：`PIP_INDEX_URL=https://pypi.org/simple ./build.sh --local` |
| EvoAgent 健康检查不通过 | `docker logs evoagent` 排查：`EVO_ADAPTER_URL` 不可达 / LLM Key 无效 / `EVO_LLM_BASE_URL` 错误 |
| Adapter `agent_url` 写 localhost 不通 | 容器内 localhost 指向自身；改用 `http://host.docker.internal:<port>` |
| 业务 Agent 日志未读取 | 确认 `HOST_LOG_ROOT/{agent_name}` 子目录存在，与 `agent_adapter_config.yaml` 的 `log_dir` 对应 |

---

## 附：端口与卷速查

| 容器 | 端口 | 宿主卷 → 容器路径 |
|------|------|-------------------|
| evoagent | 8000:8000 | `deployment/workspace` → `/app/workspace`<br>`/home/evolution/data` → `/data` |
| agent-adapter | 8900:8900 | `HOST_LOG_ROOT` → `/data/logs` (ro)<br>`HOST_SKILLS_ROOT` → `/data/skills` (rw)<br>`HOST_AGENTS_ROOT` → `/data/agents` (rw)<br>`HOST_OUTPUT_DIR` → `/app/data` (rw)<br>`HOST_CONFIG_FILE` → `/app/agent_adapter_config.yaml` (rw) |
