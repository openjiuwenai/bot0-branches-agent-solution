# EvoAgent 部署指南（Quick Install）

本指南支持用户快速完成 EvoAgent + EvoAgentAdapter 双容器部署。

---

## 0. 前置条件

| 项 | 要求 |
|----|------|
| OS | Linux（Ubuntu 22.04 / CentOS 7+），bash |
| Docker | 20.10+（含 buildkit） |
| Python | 3.12+（仅构建时用，运行在容器内） |
| 网络 | 可访问 `gitcode.com` + pip 镜像源 |
| 外部依赖 | LLM 访问配置，兼容业界主流 MaaS 提供商以及私有化MaaS平台：<br>· OpenAI（默认 `https://api.openai.com/v1`，`gpt-4o`）<br>· 阿里云 DashScope（`https://dashscope.aliyuncs.com/compatible-mode/v1`，如 `qwen3.7-max`/`qwen3.7-plus`）<br>· 智谱 GLM（如 `glm-5.2`）<br>· SiliconFlow（`https://api.siliconflow.cn/v1`，如 `Qwen/Qwen3.5-9B`）<br>· 私有化MaaS平台<br> |

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

## 2. 部署配置

### 2.1 从模板生成配置文件 .env

```bash
# Adapter 配置
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent-adapter/deployment
cp config/.env.example config/.env

# EvoAgent 配置
cd ~/EvoAgent/agent-solution/common/agent-evolve/evoagent/deployment
cp config/.env.example config/.env
```

### 2.2 编辑 Adapter 的 `config/.env`（必填项，其余保持默认）

> **编辑时机**：在 §2.1 执行 `cp .env.example .env` 生成配置文件之后、§2.3 创建主机目录之前完成编辑。首次部署、迁移宿主机、变更业务 Agent 路径，或后续启用 Otel 轨迹采集等可选能力时，均需回到此步修改并重建容器。
>
> **为什么要编辑**：Adapter 容器以只读/读写方式挂载业务 Agent 在宿主机上的日志、skills、managed-doc 等目录，是 EvoAgent 读取业务轨迹、回写优化产物的桥梁。模板中的 `HOST_*` 默认路径仅是示例，随实际部署环境而异——若不按本机实际情况修改，将导致容器挂载失败、读不到业务日志，或优化后的 skills/AgentRule 无法落到业务 Agent 的真实加载路径。

文件：`evoagent-adapter/deployment/config/.env`

```bash
HOST_LOG_ROOT=/var/log/agents           # 业务Agent日志父目录（只读挂载）
HOST_SKILLS_ROOT=/opt/agents/skills     # 业务Agent skills父目录（读写挂载）
HOST_AGENTS_ROOT=/opt/agents/runtime    # managed-doc父目录（读写挂载）
HOST_OUTPUT_DIR=/opt/agent-adapter/data # Adapter输出目录（offsets/归档）
HOST_CONFIG_FILE=/opt/agent-adapter/agent_adapter_config.yaml  # 配置文件持久化路径
```
> 如需要使用adapter 额外的能力（如使用Otel进行轨迹收集），请参考《数据回流-轨迹采集使用指南》，打开相应的配置开关。

### 2.3 创建 Adapter 依赖的主机目录

按上面 `config/.env` 规划的路径创建对应目录（不存在则补充，确保挂载不出错）：

```bash
# 与 Adapter .env 中五个 HOST_* 路径一一对应
mkdir -p /var/log/agents                   # HOST_LOG_ROOT
mkdir -p /opt/agents/skills                # HOST_SKILLS_ROOT
mkdir -p /opt/agents/runtime               # HOST_AGENTS_ROOT
mkdir -p /opt/agent-adapter/data           # HOST_OUTPUT_DIR
mkdir -p "$(dirname /opt/agent-adapter/agent_adapter_config.yaml)"  # HOST_CONFIG_FILE 所在目录
```

> 如修改了 `.env` 中的路径，请按实际配置创建对应目录。

### 2.4 编辑 EvoAgent 的 `config/.env`（必填项，其余保持默认）

> **编辑时机**：在 §2.1 生成 `.env` 之后、§3 EvoAgent 构建启动之前完成；要求 Adapter（§2.2/§2.3）已先就绪。后续若更换 MaaS 提供商/模型、轮换 API Key、迁移 Adapter 宿主 IP，均需回到此步修改并重启 EvoAgent 容器。
>
> **为什么要编辑**：EvoAgent 容器启动时通过 `.env` 获取两项关键外部依赖——`EVO_ADAPTER_URL` 指向已部署的 Adapter，是读写 skills/managed-doc、回传轨迹的唯一通道；`EVO_LLM_*` / `EVO_OPTIMIZER_MODEL` 决定优化器与评估器调用哪个 LLM。模板中的占位符（`<宿主IP>`、`{{sk-xxxxxx}}`、`{{各MaaS平台访问URL}}`、`{{模型名称}}`）必须替换为本环境实际值，否则容器启动健康检查会因 Adapter 不可达或 LLM 鉴权失败而报错（见 §6）。

文件：`evoagent/deployment/config/.env`

```bash
EVO_ADAPTER_URL=http://<宿主IP>:8900    # 指向Adapter容器（勿用localhost）

EVO_LLM_API_KEY={{sk-xxxxxx}}
EVO_LLM_BASE_URL={{各MaaS平台访问URL}}
EVO_OPTIMIZER_MODEL={{模型名称}}
```

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
