# skill-optimizer 使用说明

> 面向 jiuwenswarm（openjiuwen）平台的 skill-optimizer 使用文档：前置依赖检查、环境部署、skill 导入、测试与使用示例。
> 关联 skill 源（skill.md已上传到jiuwenswarm服务端，以当前路径示例）：`/home/huawei/jiuwenswarm/skill-optimizer/SKILL.md`

`skill-optimizer` 是一个**编排型 skill**：当用户说"用这批轨迹/数据集优化 X agent 的 Y skill"时，由 jiuwenswarm agent 自执行，串接**优化器服务**与 **Agent Adapter** 两个后端，完成"读 baseline → 提交优化 → 轮询 → 报告 → 经 adapter 回写 skill → 调 agent 验证"全流程。

> 注意：它优化的是**目标 agent**（adapter 里提前配置的agent）的 skill；而它本身是**操作者 agent**（你的 jiuwenswarm）使用的一个 skill。两者不是同一个 agent。

---

## 配置（地址唯一来源）

需配置优化器/adapter 的实际地址，迁移或换部署时，**只改这两行**。

> 示例：
OPT=http://60.204.199.88:8888   # 优化器服务（EvoAgent API）
ADP=http://124.71.234.88:8888   # Agent Adapter

---

## 1. 前置条件与依赖检查

### 1.1 依赖清单

| 依赖 | 要求 | 检查方式 |
|---|---|---|
| jiuwenswarm 平台 | 已安装并启动，前端可访问 | 浏览器打开 `http://localhost:5173/` |
| 优化器服务 | `$OPT` 在线 | `curl -s $OPT/health` → `{"status":"ok"}` |
| Agent Adapter | `$ADP` 在线 | `curl -s $ADP/health` → `{"status":"ok"}` |
| adapter 已配置目标 agent | `edp_agent` / `abc_agent` 在 config 里 | `curl -s $ADP/api/v1/config/agents` |
| 种子数据集 | golden_data jsonl，位于优化器服务器白名单路径 | 见 §3.3 |
| agent HTTP 工具能力 | 操作者 agent 能发 HTTP 请求 | jiuwenswarm 默认具备 |

### 1.2 一键依赖自检

把下面整段跑一次，全部返回 `ok` / 非 4xx 即就绪：

```bash
echo "1) 优化器:    $(curl -s --max-time 10 $OPT/health)"
echo "2) adapter:   $(curl -s --max-time 10 $ADP/health)"
echo "3) 已配 agent: $(curl -s --max-time 10 $ADP/api/v1/config/agents | python3 -c "import sys,json;print([a['name'] for a in json.load(sys.stdin)['agents']])")"
echo -n "4) edp skills: "; curl -s --max-time 10 -X POST $ADP/api/v1/skills -H "Content-Type: application/json" -d '{"action":"skill_list","agent_name":"edp_agent"}' | python3 -c "import sys,json;print(len(json.load(sys.stdin)['skills']))"
```

期望输出：
```
1) 优化器:    {"status":"ok"}
2) adapter:   {"status":"ok"}
3) 已配 agent: ['edp_agent', 'abc_agent']
4) edp skills: xx
```

> `dataset_path` 白名单（优化器服务器侧）：`/tmp/evo_agent`、`/data/evo_agent`、`/home/evolution/evoagent-studio`，且文件须已存在。

---

## 2. 环境部署

### 2.1 安装并启动 jiuwenswarm

```bash
pip install jiuwenswarm
jiuwenswarm-init      # 首次运行或升级后执行，初始化 ~/.jiuwenswarm
jiuwenswarm-start     # 启动，访问 http://localhost:5173
```

启动后本地会拉起：前端 `5173`、后端 WebSocket 服务（`19000`/`19001`/`18092` 等）。后端为 WebSocket 协议（HTTP 探测返回 426），正常交互通过前端 UI 或 agent 会话进行。

### 2.2 关键目录

| 路径 | 用途 |
|---|---|
| `/home/huawei/jiuwenswarm/skill-optimizer/` | skill 源目录（`SKILL.md` + 文档） |
| `~/.jiuwenswarm/` | jiuwensarm 工作区（init 生成） |
| `~/.jiuwenswarm/agent/workspace/skills/` | **agent 实际加载 skill 的目录** |
| `~/.jiuwenswarm/agent/workspace/skills/skills_state.json` | skill 注册表（local_skills / marketplaces） |
| `~/.jiuwenswarm/config/config.yaml` | 平台配置（含 `skill_base_dir`、`skill_mode`） |

### 2.3 部署/确认两后端服务

优化器与 adapter 是**已部署的远程服务**，本节只做连通性确认（见 §1.2，地址见文首"配置"）。若服务未起：
- 优化器 502/连不上 → 联系优化器服务维护方
- adapter 连不上 → 联系 adapter 维护方（其 `$ADP/api/v1/status` 可看各 agent 轨迹归档状态）

---

## 3. skill 导入

### 3.1 skill 格式

skill 是一个目录，内含 `SKILL.md`，frontmatter 两个字段：

```markdown
---
name: skill-optimizer
description: <触发场景与能力一句话描述>
---

# skill-optimize（agent 自执行）
<流程正文>
```

源目录 `/home/huawei/jiuwenswarm/skill-optimizer/` 即符合此格式。

### 3.2 导入方式

**方式 A — 前端 UI（推荐）**

1. 打开 `http://localhost:5173/`，进入 skill 管理页（Skills / 技能管理）。
2. 选"导入本地 skill / Add local skill"，路径填 `/home/huawei/jiuwenswarm/skill-optimizer`。
3. 平台会把该目录拷贝进 `~/.jiuwenswarm/agent/workspace/skills/skill-optimizer/`，并在 `skills_state.json` 注册为 `local_skill`（`origin` 指向源路径）。

**方式 B — 文件系统（手动，等价于 UI）**

1. 把源目录拷进工作区 skill 目录：
   ```bash
   cp -r /home/huawei/jiuwenswarm/skill-optimizer ~/.jiuwenswarm/agent/workspace/skills/
   ```
2. 在 `~/.jiuwenswarm/agent/workspace/skills/skills_state.json` 的 `local_skills` 数组里登记：
   ```json
   {"name": "skill-optimizer", "origin": "/home/huawei/jiuwenswarm/skill-optimizer", "source": "local"}
   ```

> **当前状态**：skill-optimize **已导入**（`skills_state.json` 的 `local_skills` 已含此项，`origin=/home/huawei/jiuwenswarm/skill-optimizer`），无需重复导入。

### 3.3 ⚠️ 同步须知（重要）

平台导入是**拷贝，不是软链**（源与副本 inode 不同）。**源 `SKILL.md` 改动后，副本不会自动更新**，运行中的 agent 仍用旧副本。

- 修改源后，重跑方式 A 重新导入，或用方式 B 的 `cp` 覆盖副本：
  ```bash
  cp /home/huawei/jiuwenswarm/skill-optimizer/SKILL.md \
     ~/.jiuwenswarm/agent/workspace/skills/skill-optimizer/SKILL.md
  ```
- 配置项 `skill_mode: all` 表示工作区内所有 skill 对 agent 可用，无需逐个启用。

---

## 4. 测试与使用示例

> 以下命令由**操作者 agent**（你的 jiuwenswarm）在执行 skill-optimizer 时实际发出；也可手工 curl 复现。
> 执行前确保已 `export OPT/ADP`（见文首"配置"）。

### 示例 0：列出目标 agent 的 skill + 读 baseline

```bash
# 列出 edp_agent 的 skills
curl -s -X POST $ADP/api/v1/skills -H "Content-Type: application/json" \
  -d '{"action":"skill_list","agent_name":"edp_agent"}'
# 读某 skill 全文（baseline）
curl -s -X POST $ADP/api/v1/skills -H "Content-Type: application/json" \
  -d '{"action":"skill_content","agent_name":"edp_agent","skill_name":"fund_planning_skill"}'
```

预期：`skill_list` 返回 `{"skills":[{"name":"fund_planning_skill"},...]}`（edp 4 个、abc 19 个）；`skill_content` 返回 `{"skill_name":"...","content":"<SKILL.md 全文>"}`。

### 示例 1：完整优化（edp_agent / fund_planning_skill）

向 agent 说："用 `/data/evo_agent/golden_datasets_0701.jsonl` 优化 edp_agent 的 fund_planning_skill"。agent 会按 skill-optimizer 流程执行：

1. 步骤 0 读 baseline（示例 0）
2. 步骤 2 提交：
   ```bash
   curl -s -X POST $OPT/optimize -H "Content-Type: application/json" -d '{
     "task_name":"edp_agent-skill-optimize-<ts>","agent_name":"edp_agent","optimizer_type":"skill",
     "skills":["fund_planning_skill"],"dataset_path":"/data/evo_agent/golden_datasets_0701.jsonl",
     "optimizer_template":{"name":"通用 skill 优化器","scenario":"edp_agent","rollout":{"extra_data":{}},
       "train_split":0.6,"val_split":0.4,"hyperparams":{"num_epochs":1,"edit_budget":8,"parallelism":4,
       "update_mode":"patch","num_parallel":4,"minibatch_size":4,"score_threshold":0.8,"default_batch_size":4,
       "scheduler_mode":"constant","use_meta_skill":false,"use_slow_update":false,"accumulation":1}},
     "evaluator_template":{"name":"通用严格版","scenario":"通用","prompt":"<评估 prompt>"}
   }'
   ```
   预期：`{"job_id":"<job_id>","status":"queued",...}`
3. 步骤 3 轮询（每 8s，至 completed）：
   ```bash
   curl -s $OPT/optimize/<job_id>
   ```
   预期终态：`status=completed`，`result` 含 train/val 前后分数。
4. 步骤 5 回写 + 验证：5A `update_skill` 回写（或 5B 重读对比 baseline 探测），5C 调 agent 探针。

> **实测参考（TC-11，2026-07-15）**：该样本跑通闭环，但本轮优化无收益（train 0.575→0.575、val 1.0→1.0，`gate_results=["base"]`）。⚠️ 注意：优化器即便基线胜出也会向 agent 回写改动版，且该版本**移除了合规护栏**（删 `ask_user`、二次确认、脱敏段）。故流程要求步骤 0 必存 baseline，不欲保留时回滚。详见 `测试用例执行报告.md` TC-11。

### 示例 2：查任务进度

```bash
curl -s $OPT/optimize/<job_id>
# 报 status / progress / result / error
```

### 示例 3：无数据集时从轨迹构造种子数据集

```bash
# 1) 列轨迹
curl -s $ADP/api/v1/agents/edp_agent/traces
# 2) 取清洗后轨迹（task_input + messages）
curl -s $ADP/api/v1/agents/edp_agent/cleaned-traces/<conversation_id>
# 3) 组 golden_data 每行：{"id":<conv_id>,"inputs":{"query":<task_input>},
#    "expected_behavior":<用户标签或 generate-goal 生成>,"case_type":<train|val,取 conv id 后缀>}
```

> `expected_behavior` 可用优化器 `POST $OPT/evaluate/generate-goal` 自动生成，但该接口需 `llm_config`（`model_name`+`api_key`+`api_base`），由用户提供，skill 不内置。拼好 jsonl 后仍需 scp 到优化器白名单目录才能 `/optimize`。

### 示例 4：调真实 agent 验证

```bash
curl -s -X POST $ADP/api/v1/agents/edp_agent/conversations/<conv-id> \
  -H "Content-Type: application/json" -d '{"query":"你好，一句话回复"}'
# 预期 {"success":true,"answer":"...","events":[...]}
```

---

## 5. 排错

| 现象 | 原因 / 处理 |
|---|---|
| `/optimize` 返回 422 `Dataset file not found` | `dataset_path` 不在白名单或文件不存在；改路径或 scp 文件到位 |
| `/optimize` 返回 422 `missing` | 请求体缺必填字段；按 SKILL.md 模板补全 |
| skill 没生效 / agent 用旧版 | 导入是拷贝非软链；按 §3.3 重新同步副本 |
| `update_skill` 后内容没变 | 确认 `agent_name`/`skill_name` 正确；重读 `skill_content` 验证 |
| 轮询 8 分钟仍 running | 优化器经 adapter rollout 较慢（单 agent 调用 timeout 300s）；继续轮询或稍后查 |
| 优化后合规护栏被删 | 评估器 compliance 权重过低；调高评估 prompt 的 compliance 权重或加硬过滤，并从 baseline 回滚 |
| 命令里 `$OPT`/`$ADP` 为空 | 未 `export`；见文首"配置"块先设置两个变量 |
