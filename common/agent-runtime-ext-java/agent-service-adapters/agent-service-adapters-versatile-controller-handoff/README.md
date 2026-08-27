# agent-service-adapters-versatile-controller-handoff

FEAT-002 Versatile 控制器意图转调 adapter（L2 设计见 agent-solution-docs
`Feat-Func-002-versatile-controller-intent-message-routing.md`）。

在 FEAT-002 Versatile 通用代理（`agent-service-adapters-versatile`，只读复用，基线类
仅做 public 可见性放大）之上新增第三种接入模式：adapter 直接对接控制器并识别控制器
返回的意图转调消息，命中后**不出站调用**——产出单 item `a2a_delegate` 中断
（`resume=true`），由 runtime 协调器（`RemoteInvocationBatchCoordinator`）执行出站
A2A 调用、shadow task 持久化与中断-续跑链（出站机制迁移设计见
`docs/runtime-delegation-migration-design.md`）。

## 处理链

```
入站请求
  → 入口短路（re-invoke 轮）：解析 runtime.remoteToolResults
      失败成员 → REMOTE_* 错误码映射（REMOTE_TIMEOUT→TIMEOUT，其余→TARGET_UNAVAILABLE）
      无信封   → 终答直通（流式已由协调器投影，仅收尾；非流式 joinedResults 下发）
      含信封   → 抑制不透传，重跑控制器重新识别（弹回目标记入本轮 state）
  → 控制器 SSE → IntentHandoffClassifier 识别（先于基线异常映射）
  → HandoffTargetResolver 目标解析
  → signal 命中 → not-in-scope 信封（upstream-signal，不出站）
  → 转调命中   → a2a_delegate 中断（toolCallId=handoff:<agentId>:<uuid>，
                 透传键与执行上下文并入 request.metadata 供协调器出站）
```

re-invoke 语义：协调器在 remote 批 settle 后以 `resume=true` 重新进入本 handler，
`runtime.remoteToolResults` 携带各 toolCallId 的远端结果。弹回目标从 toolCallId 无状态
解析，本轮内再转调同目标 → `VERSATILE_HANDOFF_DUPLICATE_TARGET`。

「二级退回一级」采用 upstream-signal 语义：命中 `handoff.signal.handoff-types` 的
转调类型不出站调用，直接向调用方返回 not-in-scope 标记信封
（`{"type":"versatile_handoff_not_in_scope",...}`，见 `HandoffSignals`）；上游 re-invoke
检测到标记后重跑自身控制器重新识别，全程无反向 L2→L1 调用。标记信封是协议信号，
不透传给最终用户。

## 启用

```yaml
openjiuwen:
  service:
    versatile:
      url-template: http://controller:3001/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
      handoff:
        enabled: true
        classify:
          event-type: message                  # 可选：限定识别的事件类型
          field-path: /data/node_name            # 客户报文确认前无默认值，必须显式配置
          field-value: [意图返回, 不在范围]      # 未配全则启动失败
        fields:
          handoff-type: /data/handoff_type
          intent-id: /data/summary
          business-domain: /data/domain
          target-agent-id: /data/target_agent/id
          dedup-key: /createdTime
        signal:
          handoff-types: [不在范围]              # upstream-signal：不出站，直接回标记信封
        target:
          allowed-agents: [agent_card_l1, agent_card_l2]
          resolution-priority: [direct, intent, domain]
          intent-mapping:
            "3": agent_card_l2
          domain-mapping:
            hotel: agent_card_layer2_hotel
        forward-metadata-keys: []                # 需原样透传给下游的额外 metadata 键
```

出站超时不在本模块配置：协调器按 agent 维度取
`openjiuwen.service.a2a.remote-agents[].timeout-seconds`（默认 300s），
超时以 `REMOTE_TIMEOUT` 回传并映射为 `VERSATILE_HANDOFF_TIMEOUT`。

`handoff.enabled=true` 时本模块产出本实例唯一 `AgentHandler`；`false`（默认）时本模块
不装配任何 bean，行为等价基线。识别先于 FEAT-002 异常映射（固化于处理链）；
`COMPLETED` 由 `onComplete()` 隐式表达；转调失败路径全部产出可诊断 `TYPE_ERROR`
（`VERSATILE_HANDOFF_*`），不返回空 COMPLETED。

完整可运行示例见 `common/example/versatile-controller-handoff-demo`（生产
`message` 报文格式，L1/L2 双 runtime 十场景旅程验收）。

## 配置说明

前缀 `openjiuwen.service.versatile.handoff.*`。识别/提取路径**没有默认值**——控制器
报文格式未最终确认，部署方必须显式配置（L2 设计 §1.3/§6.2）。

### 识别（classify / fields）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `classify.event-type` | string | — | 可选。仅对该事件类型的报文做字段识别（如生产 `message`） |
| `classify.field-path` | string | — | **必填**。判定"这是转调消息"的字段路径（JSONPath 风格） |
| `classify.field-value` | list | — | **必填**。路径命中值集合，任一匹配即认定为转调消息 |
| `fields.handoff-type` | string | — | 转调类型字段的提取路径（signal 匹配、日志用） |
| `fields.intent-id` | string | — | 意图 id 提取路径，供 `intent-mapping` 解析目标 |
| `fields.business-domain` | string | — | 业务域提取路径，供 `domain-mapping` 解析目标 |
| `fields.target-agent-id` | string | — | 报文显式指名目标时的提取路径（resolution `direct` 来源） |
| `fields.dedup-key` | string | — | 去重键提取路径（如生产 `createdTime`）；仅供诊断日志，链内去重已由中断机制天然消除 |

`enabled=true` 但 `classify.field-path` / `classify.field-value` 未配全时**启动失败**
（`event-type` 单独配置不充分）。提取到的字段值随报文格式约定，参考 demo 的生产
`message` 样例：`/data/node_name` ∈ `[意图返回, 不在范围]`、
`/data/summary` 为意图 id（如 `"3"`）、顶层 `/createdTime` 为 dedup-key。

识别命中后按**三选一非空**判定转调成立：`intent-id` / `business-domain` /
`target-agent-id` 三个解析来源任一提取到非空值即可（生产报文只携带本次
解析用到的字段，对齐 `resolution-priority` 语义）。三者全缺失或全空串
（如生产 SSE 的意图回显帧：`text` 带值、无 `summary` 键；或宽松识别条件
误命中的普通 QA 回复帧）→ 该行整行抑制（WARN 日志可观测）：不处理、
不透传给最终用户、不产出 `MESSAGE_CONTRACT` 报错（2026-08-19 客户现场
确认；2026-08-20 由全路径必填放宽为三选一非空）。`handoff-type` 为可选
（路径缺失置 null，signal 匹配自然不命中）；命中 `signal.handoff-types`
的转调类型不受三选一约束——signal 不出站、无需解析目标。

### 上行信号（signal）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `signal.handoff-types` | list | `[]` | 命中集合中 `handoff-type` 的转调消息**不出站调用**，直接向调用方返回 not-in-scope 标记信封，由上游重识别或调用方决策重路由 |

### 目标解析（target）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `target.allowed-agents` | list | `[]` | 出站白名单。解析出的目标不在名单内 → `VERSATILE_HANDOFF_TARGET_NOT_ALLOWED` |
| `target.resolution-priority` | list | `[direct, intent, domain]` | 目标解析顺序：`direct`=报文显式指名（`target-agent-id`）、`intent`=意图映射、`domain`=域映射 |
| `target.intent-mapping` | map | `{}` | 意图 id → 目标 agent id |
| `target.domain-mapping` | map | `{}` | 业务域 → 目标 agent id |

按 `resolution-priority` 依次尝试，第一个解析出目标的来源生效；全部来源均未命中 →
`VERSATILE_HANDOFF_TARGET_MISSING`。

### 循环保护

无跨请求轨迹体系：FEAT-002 拓扑为固定两层（一级 ↔ 二级控制器），二级退回采用
upstream-signal 语义不出站，链深恒为 1，不存在跨请求回环。spec §2.1 的循环保护
（SHOULD，"最大转调次数、重复路由检测或等价保护"）由 re-invoke 轮弹回目标的
toolCallId 无状态解析承担：弹回后同轮重识别再转调同一目标 →
`VERSATILE_HANDOFF_DUPLICATE_TARGET`，无需请求内状态或轨迹传播配置。

### 其他

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `false` | 总开关 |
| `forward-metadata-keys` | list | `[]` | 需原样透传给下游的 metadata 键 |

出站超时与续接能力均不在本模块配置：超时取协调器的
`openjiuwen.service.a2a.remote-agents[].timeout-seconds`；下游 INPUT_REQUIRED 的续接
由 runtime 中断机制承载（客户端补 input → 同一 L1 task → 协调器 remoteTaskId 续调）。

错误码全集（映射关系见 L2 设计 §5.x）：

| 错误码 | 触发场景 |
|--------|----------|
| `VERSATILE_HANDOFF_TARGET_MISSING` | 按 `resolution-priority` 所有来源均未解析出目标 |
| `VERSATILE_HANDOFF_TARGET_NOT_ALLOWED` | 目标不在 `allowed-agents` 白名单 |
| `VERSATILE_HANDOFF_TARGET_UNAVAILABLE` | 目标未注册 / 出站连接失败（协调器 REMOTE_* 失败映射，REMOTE_TIMEOUT 除外） |
| `VERSATILE_HANDOFF_TIMEOUT` | 出站调用超过 remote-agents `timeout-seconds`（REMOTE_TIMEOUT 映射） |
| `VERSATILE_HANDOFF_DUPLICATE_TARGET` | re-invoke 后再转调已弹回的同一目标 |

错误形状与基线 extractor 契约一致（`{"code":"VERSATILE_HANDOFF_*","reason":"..."}`）：
流式以 `TYPE_ERROR` chunk 下发，非流式以同 JSON 异常上抛；协调器的 `REMOTE_*` 原始码
只保留在 `reason` 中供诊断，客户端无需识别（无分层错误码）。

## 已知事项

- 本地构建依赖 `.m2` 中的 `agent-service-app:0.1.1.post1` 为 develop HEAD 构建
  （EventObserver 形态 SPI）；同版本的旧工件是 `callOutcome(RemoteCall,
  QueryStreamObserver, Consumer<String>)` 旧签名，会导致本模块编译失败。
- 同仓库 `agent-service-adapters-agentcore-ext` 与 runtime develop HEAD 存在既有
  包路径漂移（`A2ARemoteAgentCardRegistry` 已从 `controller.a2a.client` 迁至
  `a2a.catalog`），与本模块无关；全仓回归需排除该模块直至其对齐。
