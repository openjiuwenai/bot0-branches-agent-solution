# Versatile Orchestration Demo

基于 `agent-runtime-java`（OpenJiuwen `agent-service-*` 适配器）的**多智能体编排示例**，用一组独立 Spring Boot 进程演示两种典型的 agent 编排形态：

- **场景一 · 一句话银行转账编排**（`examples/workflow-calls` 的 solution 层重实现）：网关收到一句复合指令（如「先查尾号 4241 的卡余额，再转 5 元给李四」），由 `plan-agent`（ReAct）拆解为串行的原子任务，逐跳经 `adapter` 调用远端 **versatile 流程 mock**（envexplorer），网关负责 versatile↔A2A 协议翻译与 `INPUT_REQUIRED` 续传。
- **场景二 · 费用报销审核**：把 8 节点 Workflow DAG（`expense-review`）通过 ReAct 主控（`expense-review-main`）经远端 A2A 调用驱动，演示「主控 ReAct + 远端 Workflow」的分工与人工审批中断/恢复。

本 demo 展示 solution 层如何在**不侵入 core-java** 的约束下，把一个外部 HTTP/SSE 工作流服务（versatile）用 `agent-service-adapters-versatile` 包成 A2A agent，再被 ReAct 主控当作工具调用，并在最外层套一个协议翻译网关。

> 报文来源说明：本文中第一轮请求/应答为可直接复现的样例；端到端实跑捕获的真实帧存放在验收工程的 `target/sit-logs/wire/` 下（场景一由 `TransferAfterBalanceAcceptanceTest`、场景二由 `ExpenseReviewAcceptanceTest` 驱动生成）。

---

## 目录

- [拓扑与架构](#拓扑与架构)
- [外部依赖：versatile 流程 mock（envexplorer）](#外部依赖versatile-流程-mockenvexplorer)
- [模块布局](#模块布局)
- [能力矩阵](#能力矩阵)
- [构建](#构建)
- [服务器部署（fat jar）](#服务器部署fat-jar)
- [端到端调用 · 场景一：银行转账编排](#端到端调用--场景一银行转账编排)
- [端到端调用 · 场景二：费用报销审核](#端到端调用--场景二费用报销审核)
- [配置字段速查](#配置字段速查)

---

## 拓扑与架构

两个场景相互独立，共用本 demo 的 parent 与 `agent-service-*` 依赖。

### 场景一：银行转账编排（gateway → plan-agent → adapter → envexplorer）

```
   caller (POST + SSE)
        │  POST /v1/{projectId}/agents/{agentId}/conversations/{conversationId}
        │  body: {"inputs":{query,intent,...},"headers":{...}}
        ▼
 ┌──────────────────────────────┐
 │  gateway (:18095)            │   由最小 inputs 重建完整业务信封
 │  HTTP POST + SSE             │   按 plan-agent-protocol 翻译为 A2A / REST
 │  ResumeStateStore            │   记 (contextId,taskId)，INPUT_REQUIRED 时吞状态帧、续传时回灌 taskId
 └──────────────┬───────────────┘
                │ A2A / SendStreamingMessage（默认；亦可 REST /v1/query）
                ▼
 ┌──────────────────────────────┐
 │  plan-agent (:18093)         │   ReActAgent（agentcore-ext）
 │  技能 versatile-request      │   把复合指令拆成【串行】原子任务，每轮恰好一次 versatile-adapter
 └──────────────┬───────────────┘
                │ A2A（远端工具 versatile-adapter）
                ▼
 ┌──────────────────────────────┐
 │  adapter (:18094)            │   VersatileAgentHandler：把 A2A 工具调用还原成 bank REST 调用
 │  result-node: RESULTNODE     |   消费 SSE，抽取 QA 结果节点
 └──────────────┬───────────────┘
                │ HTTP + SSE（versatile 协议）
                ▼
 ┌──────────────────────────────┐
 │  envexplorer (versatile mock)│   外部有状态流程 mock，按 intent 推进银行场景
 └──────────────────────────────┘
```

### 场景二：费用报销审核（expense-review-main → expense-review）

```
 ┌──────────────────────────────┐              ┌──────────────────────────────┐
 │ expense-review-main (:18097) │  remote A2A  │ expense-review (:18096)      │
 │ ReActAgent（主控）            │ ───────────▶ │ WorkflowAgent（单 DAG）       │
 │ review_expense (远端 A2A 工具) │              │ skill: review_expense        │
 └──────────────────────────────┘              └──────────────────────────────┘
   8 节点 DAG：start→analyze(LLM)→check_policy(Tool)→audit(LLM)→route(Branch)
                          ├ risk=high  → approve(Questioner, HITL) → end   （场景 A：人工审批）
                          └ risk≠high  → auto_approve(LLM)         → end   （场景 B：自动通过）
```

两层约束：

- **库层**：`plan-agent` / `expense-review-*` 仅依赖 `agent-core-java` 与 `agent-service-app`；ReAct 循环、Workflow DAG、远端 A2A 工具注入、中断/续传均由框架提供。
- **wrapper 层**：Spring Boot 装配。`adapter` 用 `agent-service-adapters-versatile` 把外部 versatile 服务包成 A2A agent；`gateway` 是纯协议翻译层（无 LLM）。

---

## 外部依赖：versatile 流程 mock（envexplorer）

场景一的 `adapter` 不内置银行逻辑，而是把每次工具调用转发给一个**外部 versatile 流程 mock** —— **envexplorer（AgentEnvExplorer）**。这是本 demo 唯一的外部第三方依赖，需要在启动 `adapter` 前先把它拉起，并让 `adapter` 能访问到它的地址。

它的关键特性（决定了对调用方的约束）：

- **HTTP + SSE 的 versatile 协议**：调用形如 `POST http://<host>:<port>/v1/<projectId>/agents/<agentId>/conversations/{conversation_id}`，请求体是 versatile 风格的信封，应答是一段 SSE 流；结果落在名为 `RESULTNODE` 的节点上（`adapter` 据此抽取答案）。
- **按 `conversation_id` 有状态**：一个场景全程共用同一个 `conversation_id`；mock 按每步的 `intent`（如 `查询账户余额` / `快速转账`）推进，逐步返回中间结果或最终结果。
- **场景结束即解绑**：当一个场景走到 END，mock 会删除该 `conversation_id` 的绑定；下一次该 cid 的调用会重新绑定。因此**后续轮次的报文必须按 envexplorer 当前场景的约束来发**（用对 `intent`、用同一 `conversation_id`、在 END 前继续推进），而不能由调用方随意编造。
- **可经 step-ui 自推进**：mock 提供 step-ui，调用方可以在每步之后查询「下一步该发什么」，据此渲染下一轮请求 —— 验收工程正是用这个机制把多跳场景自推进到 END。

`adapter` 通过 `openjiuwen.service.versatile.url-template`（环境变量 `VERSATILE_URL`）定址 mock；`{conversation_id}` 占位符由每次调用填充。场景二（费用报销）**不依赖** envexplorer。

> 说明：本文不涉及该 mock 的具体获取方式与镜像信息；按上面契约准备好一个可达的 versatile mock 地址即可。

---

## 模块布局

```
versatile-orchestration-demo/
├── pom.xml                                 ← parent + 聚合器（packaging=pom）
│
├── adapter/                                ← versatile 银行代理（versatile-orch-demo-adapter, :18094）
│   └── src/main/java/.../adapter/
│       └── VersatileAdapterApplication.java   VersatileAgentHandler：A2A 前置，还原 bank REST 调用、消费 SSE
│   └── src/main/resources/application.yml      versatile.url-template（指向 envexplorer）、result-node-name
│
├── plan-agent/                             ← 转账编排 ReActAgent（versatile-orch-demo-plan-agent, :18093）
│   └── src/main/java/.../planagent/
│       ├── PlanAgentApplication.java
│       └── PlanAgentConfiguration.java         构造 ReActAgent；注入 versatile-adapter 为远端 A2A 工具
│   └── skills/transfer/SKILL.md                拆解规则：串行、先查余额后转账、每轮一次
│   └── src/main/resources/application.yml      remote-agents[versatile-adapter] + LLM 配置
│
├── gateway/                                ← 协议翻译网关（versatile-orch-demo-gateway, :18095）
│   └── src/main/java/.../gateway/
│       ├── GatewayController.java              POST /v1/{p}/agents/{a}/conversations/{c}，SSE 回写
│       ├── 信封构建 + 请求翻译                  由最小 inputs 重建完整业务信封，再翻译为 A2A SendStreamingMessage
│       ├── PlanAgentClient.java + A2aPlanAgentClient / RestPlanAgentClient   下游线格式可切 a2a|rest
│       └── ResumeStateStore.java               (contextId,taskId) 缓存：INPUT_REQUIRED 续传
│   └── src/main/resources/application.yml      plan-agent-base-url、plan-agent-protocol
│
├── expense-review/                         ← 报销审核 Workflow 宿主（versatile-orch-demo-expense-review, :18096）
│   └── src/main/java/.../expensereview/
│       ├── ExpenseReviewApplication.java
│       ├── ExpenseReviewConfiguration.java     buildExpenseReviewWorkflow：8 节点 DAG
│       └── tool/CompanyPolicyTool.java         差标政策查询工具
│   └── README.md                               子模块专属文档（DAG 结构、Path A/B、排障）
│
└── expense-review-main/                    ← 报销主控 ReActAgent（versatile-orch-demo-expense-review-main, :18097）
    └── src/main/java/.../expensereviewmain/
        ├── ExpenseReviewMainApplication.java
        └── ExpenseReviewMainConfiguration.java  ReActAgent；review_expense 远端 A2A 工具
    └── src/main/resources/application.yml       remote-agents[expense-review] + LLM 配置
```

---

## 能力矩阵

| 能力 | 类型 | 实现位置 | 触发方式 |
|---|---|---|---|
| 一句话指令拆解 | ReActAgent + skill | `plan-agent`（`skills/transfer/SKILL.md`） | 复合银行指令；串行拆为查余额 / 转账等原子任务 |
| 远端 versatile 工具调用 | 远端 A2A 工具 | `plan-agent` → `adapter` | 每轮恰好一次 `versatile-adapter`，严格串行 |
| bank REST/SSE 适配 | `agent-service-adapters-versatile` | `adapter`（`VersatileAgentHandler`） | 把 A2A 工具调用还原成 versatile HTTP 调用、抽取 `RESULTNODE` |
| versatile ↔ A2A 协议翻译 | 网关 | `gateway`（信封构建器 / 请求翻译器） | 接 versatile `custom_data`，输出 A2A `SendStreamingMessage`（或 REST `/v1/query`） |
| INPUT_REQUIRED 续传 | 网关状态 | `gateway`（`ResumeStateStore`） | 吞掉 `INPUT_REQUIRED` 状态帧、记 `(contextId,taskId)`；相同 `conversation_id` 再来时回灌 `taskId` |
| 下游线格式可切 | SPI | `PlanAgentClient`（A2a / Rest 两实现） | `plan-agent-protocol: a2a`(默认) `| rest` |
| 报销 DAG 编排 | WorkflowAgent | `expense-review` | 8 节点 DAG，条件路由自动通过 / 人工审批 |
| 报销主控委托 | ReActAgent + 远端 A2A 工具 | `expense-review-main` → `expense-review` | LLM 决策调 `review_expense` |

---

## 构建

在该目录下执行（从本地 m2 解析 `com.openjiuwen:agent-service-*` 与 `agent-core-java`）：

```bash
mvn clean package -DskipTests
```

产物（五个可独立运行的 Spring Boot fat jar）：

```
adapter/target/versatile-orch-demo-adapter-0.2.0-SNAPSHOT.jar
plan-agent/target/versatile-orch-demo-plan-agent-0.2.0-SNAPSHOT.jar
gateway/target/versatile-orch-demo-gateway-0.2.0-SNAPSHOT.jar
expense-review/target/versatile-orch-demo-expense-review-0.2.0-SNAPSHOT.jar
expense-review-main/target/versatile-orch-demo-expense-review-main-0.2.0-SNAPSHOT.jar
```

---

## 服务器部署（fat jar）

### 依赖

- **Java 21+**
- **LLM 出站**：`plan-agent` 与 `expense-review-main` 需一个 OpenAI 兼容 endpoint（`gateway` / `adapter` / `expense-review` 本身不直接调 LLM）。
- **versatile 流程 mock（envexplorer）**：仅场景一需要，启动 `adapter` 前先拉起（见 [外部依赖](#外部依赖versatile-流程-mockenvexplorer)）。

### 环境变量

```bash
# LLM（plan-agent / expense-review-main 用）
export LLM_API_KEY=<your-llm-key>
export LLM_API_BASE=http://localhost:4000/v1
export LLM_MODEL=gpt-4o-mini

# 仅场景一：versatile mock 地址（adapter 转发目标）
export VERSATILE_URL=http://127.0.0.1:31113/v1/mock_project_id/agents/fb723468-c8ca-424b-a95f-a3e74b37e090/conversations/{conversation_id}
```

### 启动顺序

**场景一（四个服务）：** 先外部 mock，再 `adapter`，再 `plan-agent`，最后 `gateway`（下游先就绪，上游拉 card 才不拉空）。

```bash
# 0. 先拉起 envexplorer（versatile mock），并确认 $VERSATILE_URL 可达

# 1. adapter（:18094），转发到 versatile mock
java -jar adapter/target/versatile-orch-demo-adapter-0.2.0-SNAPSHOT.jar > adapter.log 2>&1 &
curl -s http://127.0.0.1:18094/.well-known/agent-card.json | head      # 确认 card，skill=versatile-bank-proxy

# 2. plan-agent（:18093），发现 adapter
java -jar plan-agent/target/versatile-orch-demo-plan-agent-0.2.0-SNAPSHOT.jar > plan-agent.log 2>&1 &

# 3. gateway（:18095），转发到 plan-agent（非 A2A 服务，按 TCP 端口判就绪）
java -jar gateway/target/versatile-orch-demo-gateway-0.2.0-SNAPSHOT.jar > gateway.log 2>&1 &
```

**场景二（两个服务）：** 先 `expense-review`（workflow 宿主），再 `expense-review-main`（主控发现 workflow card）。

```bash
# 1. expense-review workflow 宿主（:18096）
java -jar expense-review/target/versatile-orch-demo-expense-review-0.2.0-SNAPSHOT.jar > expense-review.log 2>&1 &
curl -s http://127.0.0.1:18096/.well-known/agent-card.json | head      # 确认 skill=review_expense

# 2. expense-review-main 主控（:18097），发现 expense-review
java -jar expense-review-main/target/versatile-orch-demo-expense-review-main-0.2.0-SNAPSHOT.jar > expense-review-main.log 2>&1 &
```

### 停止

```bash
kill %1 %2 %3 %4     # 按实际启动的进程数调整
```

---

## 端到端调用 · 场景一：银行转账编排

场景一有两种入口：**(1) 经网关**（默认，本节先以此展示首轮）；**(2) 直连 `plan-agent`**（标准 A2A，见本节末「直连模式」小节）。两条路径最终都打到 `plan-agent`，内部编排一致。

经网关时，调用方走 versatile 协议 POST 网关：`POST /v1/{projectId}/agents/{agentId}/conversations/{conversationId}`，body 只需最小 `custom_data`（`inputs` + 可选 `headers`），网关会补齐 `role_name` / `role_id` / `stream` / `timeout` 等固定字段并重建完整业务信封，再翻译成 A2A 转发 `plan-agent`。

### 第一轮：kickoff（余额查询这跳）

```bash
curl -N -X POST "http://127.0.0.1:18095/v1/mock_project_id/agents/main_planner/conversations/test-session-001?type=controller&workspace_id=12" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "inputs": {
      "query": "先查询尾号为4241的银行卡余额，再转账5元给李四",
      "intent": "查询账户余额",
      "wap_userName": "张三"
    },
    "headers": {
      "stream": "true",
      "x-invoke-mode": "DEBUG",
      "x-language": "zh-cn"
    }
  }'
```

应答是一段 SSE 流（versatile `{event,data}` 形态）：`plan-agent` 把复合指令拆成串行任务，**本轮先执行第一跳**（余额查询），经 `adapter` 转发到 envexplorer，再把该跳的中间结果流式回写。首轮应答示意（实际帧见 `target/sit-logs/wire/`）：

```
data: {"event":"llm_reasoning","data":{"type":"llm_reasoning","payload":{"content":"先查余额，再转账，本轮执行余额查询"}}}
data: {"event":"llm_output","data":{"type":"llm_output","payload":{"content":"调用 versatile-adapter 查询尾号4241余额"}}}
data: {"event":"RESULTNODE","data":{"...":"adapter 从该结果节点抽取余额查询应答，回写为本轮输出"}}
```

> 结果节点的具体内部结构以 envexplorer 实际下发的帧为准；`adapter` 仅按配置的 `result-node-name`（`RESULTNODE`）定位并抽取答案，第一跳返回的是余额查询结果。

### 直连模式：用 A2A SendStreamingMessage 直发 plan-agent

也可绕过网关，把标准 A2A 文本直接 POST 给 `plan-agent`（`http://127.0.0.1:18093/a2a`）：`method` 用 `SendStreamingMessage`，`params.message.parts[0].text` 放自然语言转账诉求，`params.message.contextId` 锁定多轮会话。直连路径**无需** `inputs`/`headers` 富化——`plan-agent` 自行拆解复合指令、经 `adapter` 转发 envexplorer，内部编排与经网关时一致。

```bash
curl -N -X POST http://127.0.0.1:18093/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0",
    "id": "direct-001",
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "messageId": "m-direct-001",
        "contextId": "bank-001",
        "parts": [
          { "text": "先查询尾号为4241的银行卡余额，再转账5元给李四" }
        ]
      }
    }
  }'
```

应答是标准 A2A JSON-RPC 流式帧（直连不经网关归一化为 `{event,data}`），`plan-agent` 同样先执行第一跳（余额查询）。首轮应答示意（实际帧见 `target/sit-logs/wire/`）：

```
data: {"jsonrpc":"2.0","id":null,"result":{"statusUpdate":{"taskId":"...","status":{"state":"TASK_STATE_SUBMITTED"}}}}
data: {"jsonrpc":"2.0","id":null,"result":{"statusUpdate":{"taskId":"...","status":{"state":"TASK_STATE_WORKING"}}}}
data: {"jsonrpc":"2.0","id":null,"result":{"artifactUpdate":{"taskId":"...","artifact":{"parts":[{"text":"先查余额、再转账，本轮执行余额查询：调用 versatile-adapter 查询尾号4241余额，结果 …（adapter 经 RESULTNODE 抽取）"}]}}}}
```

> 与经网关相同，后续转账这一跳须按下方「后续轮次」以**相同 `contextId`** 推进；直连下续发报文就是一条新的 A2A 文本（无需 `inputs`/`intent` 信封），由 `plan-agent` 结合上一跳状态推进到 END。`INPUT_REQUIRED` 续传在直连下由调用方自行在续发报文里携带 `taskId`；网关侧则是网关代为缓存回灌（见末节）。

### 后续轮次：按 envexplorer 的约束推进

第一跳完成后，`plan-agent` 还需执行第二跳（转账），而每次 `versatile-adapter` 调用都推进 envexplorer **同一个 `conversation_id`** 一步。因此后续轮次不能随意构造，**必须遵循 envexplorer 当前场景的约束**来发报文：

- **同一个 `conversation_id`**（续同一场景；到 END 前 cid 始终有效）；
- 用对**下一跳的 `intent`**（余额查询这跳完成后，下一跳用 `快速转账`，query 给出具体收款人/金额）；
- 可借助 envexplorer 的 **step-ui** 查询「下一步该发什么」，据此渲染下一轮 `inputs` 再 POST 给网关，直到该场景走到 END（此时 mock 解绑 cid，同一 cid 再来将开启新场景）。

> 续传报文的具体字段（每跳的 `intent` 取值、状态/中断帧形态）以 `target/sit-logs/wire/` 下捕获的真实帧为准；首轮之后只需保证 `conversation_id` 一致并按上面规则给定每跳 `intent`。

### INPUT_REQUIRED 续传（网关侧）

若 `plan-agent`（或下游某一跳）返回 `input-required`，`gateway` 记下 `(conversation_id, taskId)` 并**吞掉该状态帧**（调用方看不到 `taskId`）。之后任何携带**相同 `conversation_id`** 的请求，网关都会把缓存的 `taskId` 回灌进转发报文，使 `plan-agent` 视其为续传；任一终态返回时清除缓存的 `taskId`。

---

## 端到端调用 · 场景二：费用报销审核

主控 `expense-review-main` 以标准 A2A 对外暴露，endpoint `http://127.0.0.1:18097/a2a`，`method` 用 `SendStreamingMessage`（流式）或 `SendMessage`（非流式）。下方第一轮报文取自验收工程实跑捕获（`target/sit-logs/wire/expense-scenario1-*-r1-*.log`）。

### 第一轮：超标报销 → INPUT_REQUIRED（场景 A）

**请求（A2A JSON-RPC，直达主控）：**

```bash
curl -N -X POST http://127.0.0.1:18097/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0",
    "id": "expense-a1",
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "messageId": "m-a1",
        "parts": [
          { "text": "帮我审核这笔报销：机票5000，酒店3晚每晚800共2400，客户晚餐800" }
        ]
      },
      "metadata": { "agentId": "expense-review-main", "sessionId": "expense-scenario1-A2A_STREAM" }
    }
  }'
```

**应答：** 主控委托 `review_expense`，DAG 走到 `route` 判 `risk=high` → `approve`（Questioner）→ 回合以 `INPUT_REQUIRED` 收尾，并带回 `taskId` / `contextId`（捕获帧节选）：

```
[1] STATE  state=TASK_STATE_INPUT_REQUIRED
  raw: {"jsonrpc":"2.0","id":null,"result":{"task":{
    "id":"14113be1-651f-49ce-b077-8775-8675dab35832",
    "contextId":"569fc18d-6104-4cd5-a900-22d0d7e0e15d",
    "status":{"state":"TASK_STATE_INPUT_REQUIRED",
              "message":{"role":"ROLE_AGENT","parts":[{"text":"Remote agent requires input"}]}}}}}
```

### 后续轮次：人工审批续传

续轮需用**相同的 `contextId`**（并携带上一轮返回的 `taskId`），`text` 给出审批结论（如 `approved`），框架即视为续传原任务，DAG 由 `approve` 流向 `end`，回合以 `COMPLETED` 收尾并产出审核报告。续传报文字段以 `target/sit-logs/wire/` 下 `...-r2-*.log` 的真实帧为准。

> **场景 B（自动通过）** 把首轮请求换成合规报销（如「报销：出租车 100 元、餐饮 200 元」），DAG 走 `auto_approve`，单轮即 `COMPLETED`，无 `INPUT_REQUIRED`。
>
> 该 SUT 把最终结果发在自定义 `workflow_final` 类型（非标准 `answer`）下；验收侧已在共享分类器中把 `workflow_final` 认作结果。详见子模块 [expense-review/README.md](expense-review/README.md)。

---

## 配置字段速查

### gateway（`gateway/src/main/resources/application.yml`）

| 字段 | 默认 | 说明 |
|---|---|---|
| `server.port` | `18095` | 网关 HTTP 端口 |
| `versatile-orchestration.gateway.plan-agent-base-url` | `${PLAN_AGENT_BASE_URL:http://127.0.0.1:18093}` | 下游 plan-agent 地址 |
| `versatile-orchestration.gateway.plan-agent-protocol` | `${PLAN_AGENT_PROTOCOL:a2a}` | 下游线格式：`a2a`（JSON-RPC + taskId 续传）/ `rest`（`/v1/query`，无 taskId 缓存） |
| `versatile-orchestration.gateway.role-name` / `role-id` / `timeout` | `手机银行` / `1` / `300` | 重建业务信封时盖的固定调用方字段 |

### adapter（`adapter/src/main/resources/application.yml`）

| 字段 | 默认 | 说明 |
|---|---|---|
| `server.port` | `18094` | adapter HTTP 端口 |
| `openjiuwen.service.versatile.url-template` | `${VERSATILE_URL:...}` | 指向 envexplorer 的 versatile 调用模板，`{conversation_id}` 占位 |
| `openjiuwen.service.versatile.result-node-name` | `RESULTNODE` | 从 SSE 抽取答案的结果节点名 |
| `openjiuwen.service.a2a.skills[].id` | `versatile-bank-proxy` | adapter 暴露的技能（被 plan-agent 当工具调用） |

### plan-agent（`plan-agent/src/main/resources/application.yml`）

| 字段 | 默认 | 说明 |
|---|---|---|
| `server.port` | `18093` | plan-agent HTTP 端口 |
| `openjiuwen.service.handler` | `agentcore-ext` | 走 `JiuwenCoreAgentExtHandler`，激活远端 A2A 工具注入 |
| `openjiuwen.service.a2a.remote-agents[].name` / `url` | `versatile-adapter` / `${VERSATILE_ADAPTER_CARD_URL:http://127.0.0.1:18094}` | 把 adapter card 注入为远端工具 |
| `plan-agent.model-name` 等 | `${LLM_*}` | LLM 配置 |

### expense-review(-main)

| 字段 | 默认 | 说明 |
|---|---|---|
| `server.port` | `18096` / `18097` | workflow 宿主 / 主控端口 |
| `openjiuwen.service.a2a.skills[].id` | `review_expense` | workflow 暴露的技能 |
| `expense-review-main` 的 `remote-agents[].name` / `url` | `expense-review` / `${EXPENSE_REVIEW_CARD_URL:http://127.0.0.1:18096}` | 主控把 workflow card 注入为远端工具 |

> 验收工程在 `application-openjiuwen.yml` 里以 Maven 坐标（`com.openjiuwen.example:versatile-orch-demo-*:0.2.0-SNAPSHOT`）拉起这些 jar：`adapter` 经 `service-bindings.envexplorer` 自动把 mock 地址注入 `versatile.url-template`，`gateway` 以 `ready-mode: tcp` 判就绪（非 A2A 服务无 agent card），与本 README 的固定端口直跑方式拓扑等价。
