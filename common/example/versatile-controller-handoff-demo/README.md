# Versatile Controller Handoff Demo

FEAT-002「Versatile 控制器意图转调消息路由」的场景旅程验收 example（L2 设计 §7.2）。
单进程同时承载 mock Versatile 控制器与两个 runtime 实例，用生产 `message` 报文格式
驱动 L1→L2 转调与 L2 not-in-scope 上行信号（upstream-signal）全链路。

## 拓扑

```text
调用方 (curl /v1/query)
  -> layer1 runtime :18091 (profiles: layer1,mock-controller)
       agent_card_l1 + ControllerHandoffAgentHandler -> mock agent_L1_controller
       意图转调 (event=message, node_name=意图返回, data.summary=意图id)
         -> intent-mapping 解析 -> A2A -> layer2
  -> layer2 runtime :18092 (profiles: layer2,mock-controller)
       agent_card_l2 + ControllerHandoffAgentHandler -> mock agent_L2_controller
       不在范围 (node_name=不在范围)
         -> handoff.signal.handoff-types 命中 -> 不出站调用，
            直接返回 {"type":"versatile_handoff_not_in_scope",...} 标记信封
       layer1 executor 检测标记 -> 重跑本层控制器重新识别（无反向 L2→L1 调用）
```

出站调用使用运行时默认 `A2ARemoteAgentClient` + 静态发现
（`openjiuwen.service.a2a.remote-agents` 启动时拉取对端 `/.well-known/agent-card.json`）。

## 场景（scripts/local-e2e.sh，对应 L2 §7.2）

| # | query 关键字 | 旅程 |
|---|--------------|------|
| 1 | （默认） | 一级命中本地工作流，无转调 |
| 2 | 转调 | 一级转调二级（intent=3 → agent_card_l2），2b 为流式 |
| 3 | 退回 | L2 not-in-scope 信号 → L1 重识别 → 本地答案 |
| 4 | 异常 | 控制器真正异常走 FEAT-002 错误映射 |
| 5 | 无目标 | intent=99 未映射 → `VERSATILE_HANDOFF_TARGET_MISSING` |
| 6 | 越权 | 目标不在 allowed-agents → `VERSATILE_HANDOFF_TARGET_NOT_ALLOWED` |
| 7 | 不可达 | 目标未注册 → `VERSATILE_HANDOFF_TARGET_UNAVAILABLE` |
| 8 | 补充信息 | 下游 INPUT_REQUIRED 且续接未启用 → `VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED` |
| 9 | 循环 | L1 重识别仍转调同目标 → `VERSATILE_HANDOFF_DUPLICATE_TARGET` |
| 10 | 超时 | 下游延迟 10s 超 handoff.timeout=3s → `VERSATILE_HANDOFF_TIMEOUT` |

## 运行

前置：Java 17；`agent-service-app` 与 `agent-service-adapters-versatile-controller-handoff`
已安装到本地 Maven 仓库。

```bash
# 安装 adapter 模块（仓库 common/agent-runtime-ext-java 下）
mvn -pl agent-service-adapters/agent-service-adapters-versatile-controller-handoff install

# 打包并运行全部场景（自动启动/停止两个 runtime）
cd common/example/versatile-controller-handoff-demo
mvn clean package -DskipTests
./scripts/local-e2e.sh

# 复用已构建 jar
SKIP_BUILD=1 ./scripts/local-e2e.sh
```

进程日志在 `target/layer1.log` / `target/layer2.log`。

## 配置要点

- `application.yml`：共享的转调识别条件（`classify.event-type=message`、
  `field-path=/data/node_name`、`field-value=[意图返回, 不在范围]`）与字段提取路径
  （`intent-id=/data/summary`、`dedup-key=/createdTime`）。
  报文样例即生产格式，仅意图值与 `createdTime` 随执行变化（每次执行新
  `createdTime` 作 dedup-key，重识别后的再次转调不会被误判为重复消息）。
- `application-layer2.yml`：`handoff.signal.handoff-types: [不在范围]` ——
  upstream-signal 语义，二级退回一级不出站调用。
- `application-layer1.yml`：`intent-mapping`（3→l2 / 5→dead / 6→forbidden）。
- mock 控制器（`mock-controller` profile）按 `inputs.query` 关键字选场景，
  同会话计数驱动"退回"场景第二次调用返回本地答案。

详见 L2 设计文档：
`agent-solution-docs/develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-002-versatile-controller-intent-message-routing.md`
