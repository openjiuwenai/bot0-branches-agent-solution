# Multi ReAct Travel Demo

基于 `agent-runtime-java`（OpenJiuwen `agent-service-*` 适配器）的**多 ReAct 智能体差旅规划示例**：三个 ReAct 智能体经 A2A 远端调用串成一条链 —— 入口 `travel-mainplan`（主规划）→ `travel-trip`（行程规划）→ `travel-hotel`（酒店查询，叶子），用户只与入口对话，下游两跳对用户透明。

本 demo 展示 solution 层如何在**不侵入 core-java**、**库层无 Spring** 的约束下，用 `RemoteA2aToolInstaller` 把远端 A2A agent card 自动注册为上游 ReAct 智能体的工具，从而搭出一条多跳委托链：

- **远端 sub-agent 注入**：每个上游 agent 通过 `RemoteTripRail` / `RemoteHotelRail`，把下游 agent card 注册为本地工具，调用时按 A2A JSON-RPC 委托
- **信息不全即中断**：入口 `mainplan` 内置 `request_user_input` 工具 + `UserInputInterruptRail`，当最小信息集（目的地 + 出发日期 + 差标）不齐全时，回合以 `INPUT_REQUIRED` 收尾，等用户补全后携原 `taskId`/`contextId` 续传
- **纯本地 mock**：叶子 `hotel` 用内存 `MockHotelInventory`（`hotels.json`），无需任何外部依赖

> 报文来源说明：本文中第一轮请求/应答为可直接复现的样例；端到端实跑捕获的真实 A2A 帧存放在验收工程的 `target/sit-logs/wire/` 下（由 `StreamingTravelPlanningTest` 驱动本链路生成）。

---

## 目录

- [拓扑与架构](#拓扑与架构)
- [模块布局](#模块布局)
- [能力矩阵](#能力矩阵)
- [构建](#构建)
- [服务器部署（fat jar）](#服务器部署fat-jar)
- [端到端调用](#端到端调用)
- [配置字段速查](#配置字段速查)

---

## 拓扑与架构

```
                user query (A2A JSON-RPC, SendStreamingMessage)
                              │
                              ▼
          ┌─────────────────────────────────────────────┐
          │        travel-mainplan (:8091)  入口         │
          │        ReActAgent                            │
          │   Tools / Rails                             │
          │    ├── request_user_input  (UserInputInterruptRail)  信息不全 ⇒ INPUT_REQUIRED
          │    └── dispatch → travel-trip (RemoteTripRail, A2A 远端工具)
          └────────────────────┬────────────────────────┘
                                 │ A2A / streaming
                                 ▼
          ┌─────────────────────────────────────────────┐
          │        travel-trip (:8092)  中游             │
          │        ReActAgent                            │
          │   Tools / Rails                             │
          │    └── dispatch → travel-hotel (RemoteHotelRail, A2A 远端工具)
          └────────────────────┬────────────────────────┘
                                 │ A2A / streaming
                                 ▼
          ┌─────────────────────────────────────────────┐
          │        travel-hotel (:8093)  叶子            │
          │        ReActAgent                            │
          │   Tools                                     │
          │    ├── hotel_search  (HotelSearchTool)       │
          │    └── hotel_detail (HotelDetailTool)        │
          │   Data: MockHotelInventory (hotels.json)     │
          └─────────────────────────────────────────────┘
```

两层约束：

- **库层**：每个 agent 仅依赖 `agent-core-java` 与 `agent-service-app`，ReAct 循环、远端 A2A 工具注入、中断/续传都由框架提供，业务侧只贡献 system prompt、工具与远端 agent 声明。
- **wrapper 层**：Spring Boot 装配，负责 `@ConfigurationProperties`、A2A agent card 暴露、`remote-agents` 远端注册。三个 app 各自是一个独立进程，进程间只走 A2A。

---

## 模块布局

```
multi-react-travel-demo/
├── pom.xml                         ← parent + 聚合器（packaging=pom；继承 spring-boot-starter-parent）
│
├── agent-mainplan/                 ← 入口：主规划 ReActAgent（travel-demo-mainplan, :8091）
│   └── src/main/java/com/openjiuwen/example/travel/mainplan/
│       ├── TravelMainplanApplication.java        Spring Boot 入口
│       ├── TravelMainplanLlmProperties.java      LLM 配置 POJO
│       ├── prompt/MainPlanPromptBuilder.java     system prompt（意图识别 / 信息充分性 / 委托）
│       ├── rails/RemoteTripRail.java             把 travel-trip card 注入为远端 A2A 工具
│       ├── rails/UserInputInterruptRail.java     request_user_input ⇒ INPUT_REQUIRED 中断
│       └── tools/RequestUserInputTool.java       信息不全时向用户追问的工具
│   └── src/main/resources/
│       ├── application.yml                        端口 8091 + remote-agents[travel-trip]
│       └── prompts/main-plan-agent-system-prompt.md
│
├── agent-trip/                     ← 中游：行程规划 ReActAgent（travel-demo-trip, :8092）
│   └── src/main/java/com/openjiuwen/example/travel/trip/
│       ├── TravelTripApplication.java
│       ├── TravelTripLlmProperties.java
│       ├── prompt/SystemPromptBuilder.java        以住宿为核心的行程方案
│       └── rails/RemoteHotelRail.java             把 travel-hotel card 注入为远端 A2A 工具
│   └── src/main/resources/
│       ├── application.yml                        端口 8092 + remote-agents[travel-hotel]
│       └── prompts/trip-planning-agent-system-prompt.md
│
└── agent-hotel/                    ← 叶子：酒店查询 ReActAgent（travel-demo-hotel, :8093）
    └── src/main/java/com/openjiuwen/example/travel/hotel/
        ├── TravelHotelApplication.java
        ├── TravelHotelLlmProperties.java
        ├── prompt/SystemPromptBuilder.java
        ├── tool/HotelSearchTool.java              按城市+日期+差标过滤候选
        ├── tool/HotelDetailTool.java              返回单家酒店明细
        └── mock/                                  内存房源
            ├── Hotel.java / Room.java
            └── MockHotelInventory.java            装载 resources/mock/hotels.json
    └── src/main/resources/
        ├── application.yml                        端口 8093（叶子，无 remote-agents）
        └── mock/hotels.json
```

---

## 能力矩阵

| 能力 | 类型 | 实现位置 | 触发方式 |
|---|---|---|---|
| 意图识别 + 实体提取 | ReActAgent + system prompt | `agent-mainplan` | 用户首轮自然语言 |
| 信息不全追问（HITL 中断） | 工具 + Rail | `RequestUserInputTool` + `UserInputInterruptRail` | 最小信息集（目的地 / 出发日期 / 差标）任一缺失 ⇒ 回合 `INPUT_REQUIRED` |
| 行程方案编排 | ReActAgent + 远端 A2A 工具 | `agent-mainplan` `RemoteTripRail` → `agent-trip` | 信息齐全时委托下游 |
| 酒店候选查询 | ReActAgent + 本地工具 | `agent-trip` `RemoteHotelRail` → `agent-hotel` `HotelSearchTool` | 行程规划需要住宿时委托叶子 |
| 酒店明细 | 本地工具 | `agent-hotel` `HotelDetailTool` | 选定候选后取明细 |
| 多轮续传 | A2A `contextId` + `taskId` | 框架 A2A orchestrator | `INPUT_REQUIRED` 后用相同 `contextId`（并携 `taskId`）续传 |
| 房源数据 | 内存 mock | `MockHotelInventory` + `hotels.json` | 启动时加载，无外部依赖 |

三个 agent 共用同一组 `${LLM_*}` 环境变量；`mainplan` 默认出发城市 `深圳`、出差人 `张三`（均可经环境变量覆盖）。

---

## 构建

本 demo 与仓库根解耦，独立继承 `spring-boot-starter-parent`，从本地 m2 解析 `com.openjiuwen:agent-service-*`。在该目录下执行：

```bash
mvn clean package -DskipTests
```

产物（三个可独立运行的 Spring Boot fat jar）：

```
agent-mainplan/target/travel-demo-mainplan-0.1.0.jar
agent-trip/target/travel-demo-trip-0.1.0.jar
agent-hotel/target/travel-demo-hotel-0.1.0.jar
```

---

## 服务器部署（fat jar）

### 依赖

- **Java 21+**
- **LLM 出站**：三个进程都需一个 OpenAI 兼容 endpoint（默认 base 见环境变量）。无需数据库 / 消息队列 / 外部 mock。

### 环境变量

三个 agent 共用同一组 LLM 变量（key 不要进仓库）：

```bash
export LLM_PROVIDER=OpenAI                 # OpenAI 兼容 provider 名
export LLM_API_KEY=<your-llm-key>
export LLM_API_BASE=https://api.deepseek.com
export LLM_MODEL=deepseek-chat
# 可选：LLM_SSL_VERIFY=true / LLM_MAX_ITERATIONS=10
# mainplan 专属可选：LLM_DEFAULT_CITY=深圳 / LLM_TRAVELER_NAME=张三
```

### 启动顺序

推荐**叶子优先**逐层向上启动（先起 `hotel`，再 `trip`，最后 `mainplan`）。上游通过 `A2AAgentCardDiscovery` 周期性拉取下游 card，card 拿到前对应工具不可见，因此下游先就绪可避免首轮调用拉空。

```bash
# 1. 叶子：hotel（:8093）
java -jar agent-hotel/target/travel-demo-hotel-0.1.0.jar > hotel.log 2>&1 &
curl -s http://127.0.0.1:8093/.well-known/agent-card.json | head      # 确认 card 可达

# 2. 中游：trip（:8092），发现 hotel
java -jar agent-trip/target/travel-demo-trip-0.1.0.jar > trip.log 2>&1 &

# 3. 入口：mainplan（:8091），发现 trip
java -jar agent-mainplan/target/travel-demo-mainplan-0.1.0.jar > mainplan.log 2>&1 &
```

> 说明：以上为 `java -jar` 直接运行时的默认端口（8091/8092/8093）；`application.yml` 里上游的 `remote-agents[].url` 已指向这些默认地址。验收工程以**随机端口**拉起本链路并把下游 URL 注入上游（见 `application-openjiuwen.yml`），两种方式拓扑等价。

### 停止

```bash
kill %1 %2 %3     # 或按实际 pid 杀掉三个进程
```

---

## 端到端调用

所有调用都走标准 A2A JSON-RPC，入口 endpoint 为 `http://127.0.0.1:8091/a2a/`，`method` 用 `SendStreamingMessage`（流式）或 `SendMessage`（非流式），`params.message.parts[0].text` 放差旅诉求，`params.message.contextId` 是多轮会话键。

### 第一轮：信息齐全的完整请求（happy path，全链路）

```bash
curl -N -X POST http://127.0.0.1:8091/a2a/ \
  -H 'Content-Type: application/json; charset=utf-8' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0",
    "id": "travel-001",
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "messageId": "m-001",
        "contextId": "travel-001",
        "parts": [
          { "text": "2026年8月2日去北京出差，差标每晚不超过800元、最低4星、协议品牌全季/亚朵。" }
        ]
      }
    }
  }'
```

`mainplan` 判定最小信息集齐全（目的地 + 出发日期 + 差标），委托 `trip`，`trip` 再委托 `hotel` 取候选；应答为一段 SSE 流，逐帧下发任务状态与最终行程 artifact。帧序列示意（实际帧见 `target/sit-logs/wire/`）：

```
data: {"jsonrpc":"2.0","id":null,"result":{"statusUpdate":{"taskId":"...","status":{"state":"TASK_STATE_SUBMITTED"}}}}
data: {"jsonrpc":"2.0","id":null,"result":{"statusUpdate":{"taskId":"...","status":{"state":"TASK_STATE_WORKING"}}}}
data: {"jsonrpc":"2.0","id":null,"result":{"artifactUpdate":{"taskId":"...","artifact":{"artifactId":"itinerary","parts":[{"text":"# 差旅行程\n... 推荐酒店：全季 ..."}]}}}}
data: {"jsonrpc":"2.0","id":null,"result":{"statusUpdate":{"taskId":"...","status":{"state":"TASK_STATE_COMPLETED"}}}}
```

### 非流式调用

把 `method` 改为 `SendMessage`、请求头换成 `Accept: application/json`，其余字段不变；返回是聚合后的一次性 JSON，`result.task` 携带终态与 artifact。

### 后续轮次（多轮续传）

本 demo 的多轮是**自然对话续传**，不依赖外部状态机：

- **信息不全被中断时**：若首轮只给「我要去北京出差」（缺出发日期 + 差标），`mainplan` 调 `request_user_input`，回合以 `INPUT_REQUIRED` 收尾并在应答里带回 `taskId`。后续轮次需用**相同的 `contextId`**（并携带该 `taskId`）发送补全信息（如「8月2日出发，差标每晚800元、4星」），框架即视为续传同一任务，恢复至 `COMPLETED`。
- **同会话增量修改时**：用相同 `contextId` 追问（如「改成住朝阳」），`trip`/`hotel` 结合上一轮已确认信息仅更新变化部分。

> 续传报文的具体字段（`taskId` 取值、状态帧形态）以 `target/sit-logs/wire/` 下捕获的真实帧为准；首轮之后的请求只需保证 `contextId` 一致并按上面规则携带 `taskId`。

---

## 配置字段速查

`agent-mainplan/src/main/resources/application.yml` 关键字段（`trip` / `hotel` 同构，端口与 remote-agents 不同）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `server.port` | `8091` / `8092` / `8093` | mainplan / trip / hotel 的 HTTP 端口 |
| `openjiuwen.service.a2a.agent-name` | `travel-mainplan` 等 | A2A agent card 名（取自 `spring.application.name`） |
| `openjiuwen.service.a2a.streaming` | `true` | card 声明支持流式 |
| `openjiuwen.service.a2a.skills[].id` | `mainplan_skill` / `trip-planning` / `hotel_search_skill` | 各 agent 暴露的技能 |
| `openjiuwen.service.a2a.remote-agents[].name` | `travel-trip` / `travel-hotel` | 下游 agent 名，**必须等于**下游 `spring.application.name`，`RemoteA2aToolInstaller` 据此注入工具 |
| `openjiuwen.service.a2a.remote-agents[].url` | `http://localhost:8092/a2a/` 等 | 下游 A2A JSON-RPC base |
| `openjiuwen.travel.mainplan.default-city` | `${LLM_DEFAULT_CITY:深圳}` | 出发地缺失时的默认城市 |
| `openjiuwen.travel.mainplan.traveler-name` | `${LLM_TRAVELER_NAME:张三}` | 拼入出差需求描述的出差人 |
| `openjiuwen.travel.*.llm.model-name` | `${LLM_MODEL:}` | 模型名（三 agent 共用 `${LLM_*}`） |
| `openjiuwen.travel.*.llm.max-iterations` | `10` / `5` / `6` | 各 agent ReAct 循环最大轮次 |

> 验收工程在 `application-openjiuwen.yml` 里以 Maven 坐标（`com.openjiuwen.example:travel-demo-*:0.1.0`）拉起这三个 jar，并以随机端口把下游 URL 注入上游的 `remote-agents[].url`，与本 README 的固定端口直跑方式拓扑等价。
