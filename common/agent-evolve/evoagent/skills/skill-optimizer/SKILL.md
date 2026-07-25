---
name: skill-optimizer
description: 给定 agent 名 + skill 名 + 服务器侧种子数据集路径，提交优化器 /optimize、轮询、报告前后对比、经 adapter 回写优化后 skill、调 agent 验证。当用户说"优化 X agent 的 Y skill""跑一轮 skill 优化""提交优化任务/查进度/回写 skill"时使用。种子数据集通常由 skill-evaluator 产出或用户自带。
---

# skill-optimizer（agent 自执行）

本 skill 是 **skill 自进化的"优化"半边**，与 `skill-evaluator` 互为独立模块，合并即完整自进化流程。

它只做优化侧的事：读 baseline → 提交 `/optimize` → 轮询 → 报告前后对比 → 回写 skill → 调 agent 验证。**不跑 `/evaluate` 系列独立评估**——评估在 `/optimize` 循环内部由优化器自动完成（train/val 分片 + 每 epoch 评分）；基线打分/攒种子数据集归 `skill-evaluator`。

**本 skill 由你（agent）直接用 HTTP 工具执行，不调用任何外部脚本。**

## 架构（先理解，再执行）

串接两个已部署服务：

- **优化器服务（EvoAgent API）**：`/optimize` 提交/轮询/cancel/stream、`/scenarios`、`/health`。请求体内嵌 `optimizer_template` + `evaluator_template`——评估在优化循环内部由优化器自动完成，**本 skill 不另调 `/evaluate`**。
- **Agent Adapter**：读 baseline（前后对比）、`update_skill` 回写、调真实 agent 验证。

优化器内部**已集成 adapter**：
- **第一轮 rollout** 用 `dataset_path` 指向的**种子 golden_data**（用户提供，位于优化器服务器上）。
- **之后各轮**：优化器**自行**经 adapter 获取并清洗轨迹。这一步**优化器内部完成，本 skill 不参与**，也无需在 skill 侧喂数据。

因此本 skill 的职责是补全优化器**两侧**的缺口：优化前读 baseline；优化后回写 + 验证。

## 配置（唯一地址来源，全文只此一处）

```
OPT = http://60.204.199.48:8000    # 优化器
ADP = http://124.71.234.237:8900   # adapter
```

下文统一用 `${OPT}` / `${ADP}` 指代。若日后迁移服务，**只需改这两行**，正文不要再出现具体 IP/端口。

`/optimize` 要求传 `dataset_path`（优化器服务器上的文件路径，白名单：
`/tmp/evo_agent`、`/data/evo_agent`、`/home/evolution/evoagent-studio`，文件须已存在），
因此**用户必须提供一个服务器侧路径**作为第一轮种子。skill 不负责把本地文件传上去，也不做格式转换。

默认优化轮数固定为 `epochs = 1`（已写入请求模板，agent 直接用，**不要向用户询问 epochs**，
也不在对话里输出该值）。如未来需放开，再在本 skill 增加提示词参数。

## 何时用

- 用户给了一个**服务器侧**种子数据集/轨迹路径（jsonl）和目标 agent 名，要优化某个/某些 skill
- 用户要查某个已提交优化任务的进度/结果
- 用户要在优化前后对比 skill 内容、或优化后验证 agent 行为

## 执行前先向用户确认的输入

| 参数 | 必填 | 说明 |
|---|---|---|
| `agent_name` | 是 | 目标 agent 名（如 zdt_agent、edp_agent） |
| `skills` | 是 | 待优化 skill 名列表（可多个） |
| `dataset_path` | 是 | **优化器服务器上的绝对路径**（jsonl）。必须是 golden_data 格式：每行含 `id/inputs/expected_behavior/case_type`。文件需已存在于优化器服务器上。用作第一轮 rollout 种子。可由 `skill-evaluator` 攒出后上传 |

> 提醒用户：`dataset_path` 是优化器服务器上的路径，不是本地路径。若用户手里只有本地文件，
> 让其先把文件传到优化器服务器（如 scp/rsync）后给出服务器侧绝对路径再调用本 skill。
> 若用户连数据集都没有，可先跑 `skill-evaluator` 从 adapter 历史轨迹现攒一个（仍需落地到优化器服务器）。

---

## 步骤 0：经 adapter 读取 baseline skill（对用户可见）

优化前先确认目标 skill 存在并捕获优化前内容，供步骤 4/5 对比。

**0.1 列出 agent 的 skills，确认目标 skill 名存在**
```
POST ${ADP}/api/v1/skills
Content-Type: application/json
{"action":"skill_list","agent_name":"<agent_name>"}
```
响应：`{"skills":[{"name":"..."},...]}`。若用户给的 skill 名不在其中，向用户澄清后停止。

**0.2 逐个读取目标 skill 的当前全文，记为 baseline**
```
POST ${ADP}/api/v1/skills
Content-Type: application/json
{"action":"skill_content","agent_name":"<agent_name>","skill_name":"<skill>"}
```
响应：`{"skill_name":"...","content":"<SKILL.md 全文>"}`。把 `content` 记为 `baseline[<skill>]`。

向用户报一句："已读取 <skill> baseline（<行数/字数>），开始优化"。

---

## 步骤 1：校验输入（内部步骤，对用户不可见）

确认 `dataset_path` 非空、是绝对路径（以 `/` 开头），且落在白名单三目录之一之下。
skill 无法直接读取优化器服务器文件，故不预检内容，只做字符串校验。
若路径明显不合规（相对路径、为空、不在白名单），向用户澄清后再继续。

> 对用户只报一句"数据集路径已就绪，开始优化"。

---

## 步骤 2：提交优化任务

```
POST ${OPT}/optimize
Content-Type: application/json
```

请求体（`<...>` 换实际值，`num_epochs` 固定 `1`）：

```json
{
  "task_name": "<agent_name>-skill-optimize-<当前时间戳>",
  "agent_name": "<agent_name>",
  "optimizer_type": "skill",
  "skills": ["<skill1>", "<skill2>"],
  "dataset_path": "<用户提供的服务器侧路径>",
  "optimizer_template": {
    "name": "通用 skill 优化器",
    "scenario": "<agent_name>",
    "rollout": {"extra_data": {}},
    "train_split": 0.6,
    "val_split": 0.4,
    "hyperparams": {
      "edit_budget": 8,
      "parallelism": 4,
      "update_mode": "patch",
      "accumulation": 1,
      "num_parallel": 4,
      "num_epochs": 1,
      "minibatch_size": 4,
      "scheduler_mode": "constant",
      "use_meta_skill": false,
      "score_threshold": 0.8,
      "use_slow_update": false,
      "default_batch_size": 4
    }
  },
  "evaluator_template": {
    "name": "通用严格版",
    "scenario": "通用",
    "prompt": "你是一个 Agent 轨迹评估专家。基于上下文、预期结果、可选 skill 列表和完整轨迹消息，对该轨迹进行客观、可复核的评估。\n\n## 输入\n{expected_section}\n{skill_names_section}\n{skill_names}\n{messages}\n\n## 评估维度\n1. task_completion：任务完成度（最终回复是否达成用户核心目标）\n2. trajectory_quality：轨迹质量（工具/skill 选择与参数是否合理）\n3. compliance：合规性（金融场景的二次确认、越权防护、敏感信息保护）\n\n## 评分\n每维度取 {1.0, 0.75, 0.5, 0.25, 0.0}。\nscore = task_completion*0.5 + trajectory_quality*0.3 + compliance*0.2\nis_pass=true 当且仅当 score >= 0.75。\n\n## 输出（仅 JSON）\n{\"task_completion\":1.0,\"trajectory_quality\":1.0,\"compliance\":1.0,\"is_pass\":true,\"score\":1.0,\"attributed_skill\":\"\",\"reason\":\"...\"}"
  }
}
```

响应 `200`：取 `job_id` 记为 `job_id`（注意是 `job_id` 不是 `id`）。向用户报"优化任务已提交，开始跑，job_id=X"。

> 若 422：看响应 `detail[].loc/msg` 修字段（多半是 `dataset_path` 缺失/不在白名单，或模板字段名错）。
> 若 502/连不上：优化器服务没起，告诉用户。

> 关于 `scenario`：`optimizer_template.scenario` 应是优化器支持的场景名。
> 可先 `GET ${OPT}/scenarios` 看可用场景列表（含各场景默认 hyperparams）。
> 默认用 `<agent_name>` 作为 scenario（如 edp_agent 既是 agent 名也是合法场景名）。

---

## 步骤 3：轮询到完成

每隔 8 秒发：
```
GET ${OPT}/optimize/<job_id>
```

响应体（`JobResponse`）：
```json
{"job_id": "...", "status": "...", "progress": {...}|null, "result": {...}|null, "error": "..."|null}
```

看 `status`：
- `queued` / `running` → 继续等。`progress` 是 free-form 对象，向用户播报其中能读懂的字段
  （常见键：`current_epoch`/`total_epochs`/`val_score`/`edits_applied`，按实际出现的报）
- `completed` → 进入步骤 4
- `failed` / `cancelled` → 告诉用户原因（看 `error` 字段），停止

最多轮询 60 次（约 8 分钟）；超时还 running 就告诉用户"仍在跑，job_id=X，稍后再查"。

> 想要实时进度也可用 SSE 流：`GET ${OPT}/optimize/<job_id>/stream`
> （支持 `Last-Event-ID` 重放历史事件，已完成任务重放后立即结束）。轮询更简单，优先用轮询。
> 需要中止时：`POST ${OPT}/optimize/<job_id>/cancel`（终态任务返回 409）。

---

## 步骤 4：报告优化结果

`completed` 后从响应取 `result`（free-form 对象，结构由优化器决定）。防御式报告——
优先按常见字段提取，缺哪块就跳过哪块：

```
✅ 优化完成（job_id=<job_id>）

train: <result.train.score_before> → <result.train.score_after> (Δ<result.train.improvement>)
val:   <result.val.score_before> → <result.val.final_score>
edits_applied: <result.edits_applied>

各 skill（遍历 result.skill_scores[]，若有）：
- <s.name>: <s.score_before> → <s.score_after> (Δ<s.score_delta>, edits=<s.edits_applied>)
```

若 `result` 不含上述结构（键名不同或为空），直接把 `result` 以缩进 JSON 形式摘要点给用户，
并说明优化器返回的就是这些。

> 同时留意 `result` 里是否含优化后的 skill 全文（常见键：
> `skill_contents`、`skills[].content`、`optimized_skills` 等）。**若有，记下来供步骤 5 回写**；
> 若无，步骤 5 走"对比探测"分支。

---

## 步骤 5：经 adapter 回写 skill + 验证

优化结果要落回 agent 才算打通。按是否拿到新内容分两路：

### 5A. 拿到了优化后 skill 全文（步骤 4 从 `result` 取到）

逐个 skill 调 adapter 写回：
```
POST ${ADP}/api/v1/skills
Content-Type: application/json
{"action":"update_skill","agent_name":"<agent_name>","skill_name":"<skill>","skill_content":"<优化后全文>"}
```
响应：`{"success": true, "skill_name":"..."}` 即成功。向用户报"已回写 <skill>"。

### 5B. 没拿到优化后全文（`result` 不含 skill 内容）

优化器很可能已自行经 adapter 回写。**重新读取并与步骤 0 的 baseline 对比**来确认：
```
POST ${ADP}/api/v1/skills  {"action":"skill_content","agent_name":"<agent_name>","skill_name":"<skill>"}
```
- 内容**与 baseline 不同** → 优化器已回写，告知用户"优化器已自动回写 <skill>（检测到内容变化）"。
- 内容**与 baseline 相同** → 既没拿到新全文、agent 侧也没变。告知用户：
  "未检测到 <skill> 回写；请到优化器服务器确认 result/产物，或手动指定优化后内容再走 5A。"

### 5C. 验证（可选但推荐）

写回后用一条探针 query 调真实 agent，确认 skill 生效、行为正常：
```
POST ${ADP}/api/v1/agents/<agent_name>/conversations/<探针-conv-id>
Content-Type: application/json
{"query":"<与目标 skill 相关的简短探针问题>"}
```
响应：`{"success":true,"answer":"...","events":[...],"error":null}`。把 `answer` 简述给用户，
说明"已用探针调用 agent，回复：<answer 摘要>"。`conversation_id` 自取一个不重复的串即可。

> 探针调用会触发真实 agent 产生新轨迹（adapter 会归档），不影响优化结果。
> 若需对回写后的 skill 做更正式的复评，转 `skill-evaluator` 跑 baseline 对比评估。

---

## 其他命令

### 优化器：查看某任务详情 / 进度
```
GET ${OPT}/optimize/<job_id>
```
报 `status` / `progress` / `result` / `error`。

### 优化器：列出可用场景（看合法 scenario 名与默认超参）
```
GET ${OPT}/scenarios
```
遍历返回数组，每项 `name / optimizer_class / hyperparams`。

### 优化器：中止任务
```
POST ${OPT}/optimize/<job_id>/cancel
```
终态任务返回 409。

### 优化器：SSE 实时进度
```
GET ${OPT}/optimize/<job_id>/stream
```
支持 `Last-Event-ID` 重放历史事件。

### adapter：写回/覆盖某 skill
```
POST ${ADP}/api/v1/skills   {"action":"update_skill","agent_name":"<agent_name>","skill_name":"<skill>","skill_content":"<新全文>"}
```
> 写回前如需保险，可先 `skill_content` 读出原文备份到对话里再覆盖。

### adapter：列出/读取 skill
```
POST ${ADP}/api/v1/skills   {"action":"skill_list","agent_name":"<agent_name>"}
POST ${ADP}/api/v1/skills   {"action":"skill_content","agent_name":"<agent_name>","skill_name":"<skill>"}
```

### adapter：调用真实 agent（验证探针）
```
POST ${ADP}/api/v1/agents/<agent_name>/conversations/<conversation_id>
Content-Type: application/json
{"query":"<用户问题>"}
```
响应含 `answer` 与流式 `events`（think_chunk/final_answer_chunk 等）。

### 优化器 / adapter：健康检查
```
GET ${OPT}/health   → {"status":"ok"}
GET ${ADP}/health   → {"status":"ok"}
GET ${ADP}/api/v1/status   → 各 agent 的 trace 归档/offset 状态
```

> 注意：优化器服务**没有"列出历史任务"接口**，查进度必须带 `job_id`。
> `job_id` 在步骤 2 提交后返回，务必记住或告诉用户。

---

## 边界

- 只调这两个服务（地址见文首"配置"，全文唯一地址来源）
- **不跑 `/evaluate` 系列**——基线打分/攒种子数据集归 `skill-evaluator`；优化循环内的 train/val 评估由 `/optimize` 自带，本 skill 不另调独立评估端点
- `dataset_path` 必须是优化器服务器上的路径；本 skill 不上传、不转换本地文件
- 不传 LLM 配置给 `/optimize`（不要求；优化器自带模型配置）
- 只动 skill 内容（经 adapter `update_skill`），不动 agent 配置和模型
- `epochs` 固定为 1，内置在请求模板里，不向用户询问、不在对话输出
- 回写走 adapter；若优化器已自行回写，5B 的对比探测会识别出来，不重复写坏
