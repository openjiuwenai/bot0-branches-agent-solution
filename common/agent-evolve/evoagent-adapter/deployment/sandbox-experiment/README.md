# 三容器实验：EDPAgent(sandbox) + jiuwenbox + EvoAgentAdapter

## 目标

在 **EDPAgent 以 sandbox 模式对接 jiuwenbox**、三者均以 Docker 运行时，验证 Adapter 三大能力，并将 Skill 热更从「宿主机三方挂载」改为 **Adapter 直写 jiuwenbox 沙箱目录**（仅改 EvoAgentAdapter 代码）。

## 架构对比

### 旧：共享宿主机 skills 挂载

```text
HOST_SKILLS ──bind──► Adapter /data/skills
         └──bind──► jiuwenbox → policy → sandbox /tmp/skills
EDPAgent init zip 上传也会落到同一棵树（若配置了 bind）；否则 zip 与挂载脱节。
```

### 新：Adapter → jiuwenbox File API

```text
EDPAgent ──SANDBOX_URL──► jiuwenbox 创建 sandbox，unzip → /tmp/skills
EvoAgent ──► Adapter POST /api/v1/skills
                └── upload/download ──► 同一 sandbox 的 /tmp/skills/.../SKILL.md
Adapter 本地 /data/skills 只存 .meta 快照（restore 用），不再作为运行时真源。
```

## 三大能力在 sandbox 拓扑下如何生效

| 能力 | 机制 | 本拓扑要点 |
|------|------|------------|
| 轨迹采集 | 只读挂载 EDPAgent 日志目录，轮询 `process_*.log` | 仍依赖 `HOST_LOG_ROOT` → `/data/logs`；与 sandbox 无关 |
| 调用代理 | `agent_url` → EDPAgent SSE/JSON | 容器网络用服务名或 `host.docker.internal`，禁止 `localhost` |
| Skill 热更 | `skill_backend: jiuwenbox` + File API | 必须与 EDPAgent **同一 sandbox_id**；路径默认 `/tmp/skills` |

### sandbox_id 发现（不改 EDPAgent）

EDPAgent 启动时随机创建 sandbox 并打日志：

`[DPA] SysOperationCard 已注册：..., sandbox_id=<id>`

Adapter 配置（推荐顺序 **`from_logs` > `fixed` > `list_ready`**）：

- `from_logs`（默认）— 解析上述日志，失败则退回 `list_ready`
- `fixed` + `sandbox_id` — 若你能固定 id（需 EDP 侧配合创建参数，本实验不改 EDP 代码）
- `list_ready` — 要求 jiuwenbox 上仅有一个 `ready` 沙箱；多 ready 会失败，共享环境勿用

## 配置字段（per-agent）

```yaml
skill_backend: jiuwenbox
jiuwenbox_url: http://jiuwenbox-sandbox-exp:8321
sandbox_id_resolve: from_logs   # 推荐；失败退回 list_ready
# sandbox_id: "optional-when-fixed"
remote_skills_dir: /tmp/skills  # = {SKILL_TARGET_PATH}/skills
```

## 启动步骤

### 0. 网络

```bash
docker network create evo-sandbox-net
```

### 1. jiuwenbox

```bash
cd jiuwenswarm/jiuwenbox
# Linux/Git Bash:
./scripts/build_docker.sh
docker run -d --name jiuwenbox --privileged \
  --sysctl net.ipv4.ip_forward=1 \
  --cap-add=SYS_ADMIN --cap-add=NET_ADMIN \
  --security-opt seccomp=unconfined --security-opt apparmor=unconfined \
  --cgroupns=host -v /sys/fs/cgroup:/sys/fs/cgroup:rw \
  -p 8321:8321 -p 8322:8322 \
  -e JIUWENBOX_LISTEN=http://0.0.0.0:8321 \
  jiuwenbox:latest
# Docker Desktop / 部分环境仅 --sysctl 不够，需 --privileged 才能创建 isolated 网络沙箱
```

EDPAgent 侧 `SANDBOX_URL` 建议：`http://jiuwenbox:8321`（同网络）或 `http://host.docker.internal:8321`。

### 2. EDPAgent（sandbox 模式）

在 `a2a_service.env` 增加（示例）：

```env
SANDBOX_URL=http://host.docker.internal:8321
SKILL_TARGET_PATH=/tmp
```

按 EDPAgent `deployment/build.sh` + `run.sh` 构建启动，并把日志目录挂到本实验的 `HOST_LOG_ROOT`。将容器加入 `evo-sandbox-net`。

> 完整 EDP 镜像构建依赖 agent-runtime merge，耗时较长；可先用下方「最小验证」只测 Skill API ↔ jiuwenbox。

### 3. Adapter

```bash
cd agent-solution/common/agent-evolve/evoagent-adapter
cp deployment/sandbox-experiment/.env.example deployment/sandbox-experiment/.env
# 编辑 .env 路径
docker compose -f deployment/sandbox-experiment/docker-compose.yml \
  --env-file deployment/sandbox-experiment/.env up -d --build adapter
```

确保 `agent_adapter_config.yaml` 中 `jiuwenbox_url` 能解析到 jiuwenbox 容器。

## 验证清单

### A. 调用代理

```bash
curl -s -X POST "http://127.0.0.1:8900/api/v1/agents/edp_agent/conversations/exp-001" \
  -H "Content-Type: application/json" \
  -d '{"query":"你好"}'
```

期望：非 404/400（agent_url 未配），能返回聚合 JSON 或业务错误体。

### B. 轨迹采集

触发一轮对话后等待 `poll_interval`，检查：

`deployment/sandbox-experiment/data/output/edp_agent/*.jsonl`

或 `GET /api/v1/agents/edp_agent/traces/...`

### C. Skill 热更（jiuwenbox）

```bash
# 列出
curl -s -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{"action":"skill_list","agent_name":"edp_agent"}'

# 读取
curl -s -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{"action":"skill_content","agent_name":"edp_agent","skill_name":"<name>"}'

# 更新（写入沙箱 /tmp/skills/<name>/SKILL.md）
curl -s -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{"action":"update_skill","agent_name":"edp_agent","skill_name":"<name>","content":"# hotupdate marker\\n"}'
```

再用 jiuwenbox API 核对：

```bash
# 先拿到 sandbox_id
curl -s http://127.0.0.1:8321/api/v1/sandboxes
curl -s "http://127.0.0.1:8321/api/v1/sandboxes/<id>/download?sandbox_path=/tmp/skills/<name>/SKILL.md"
```

期望：download 内容含 `hotupdate marker`。EDPAgent 下一轮 `read_file` 应读到新正文（frontmatter 元数据仍需重启才进 registry）。

### D. 最小验证（无完整 EDP）

脚本：`verify_jiuwenbox_skill_hotupdate.py` — 自建 sandbox、预置 skill、经 Adapter 后端类直写并校验。

```bash
cd EvoAgentAdapter
python deployment/sandbox-experiment/verify_jiuwenbox_skill_hotupdate.py \
  --jiuwenbox-url http://127.0.0.1:8321
```

## 代码改动摘要（仅 EvoAgentAdapter）

| 文件 | 作用 |
|------|------|
| `jiuwenbox_client.py` | 沙箱 list/files/download/upload/exec |
| `sandbox_resolve.py` | fixed / list_ready / from_logs |
| `jiuwenbox_skill_store.py` | Skill CRUD → 沙箱 FS；快照本地 |
| `skill_store_factory.py` | 按 `skill_backend` 装配 |
| `config.py` | 新增 per-agent jiuwenbox 字段 |
| `api/app.py` | `build_skill_store` |

默认 `skill_backend: local`，旧挂载部署零行为变化。
