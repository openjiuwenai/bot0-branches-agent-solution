# agent-service-adapters-versatile-controller-handoff

FEAT-002 Versatile 控制器意图转调 adapter（L2 设计见 agent-solution-docs
`Feat-Func-002-versatile-controller-intent-message-routing.md`）。

在 FEAT-002 Versatile 通用代理（`agent-service-adapters-versatile`，只读复用，基线类
仅做 public 可见性放大）之上新增第三种接入模式：adapter 直接对接控制器，同时识别
控制器返回的意图转调消息，经 `RemoteAgentCaller` SPI（A2A 网关）调用目标智能体，
并把下游结果归一为当前 `QueryChunk` 与终态。

「二级退回一级」采用 upstream-signal 语义：命中 `handoff.signal.handoff-types` 的
转调类型不出站调用，直接向调用方返回 not-in-scope 标记信封
（`{"type":"versatile_handoff_not_in_scope",...}`，见 `HandoffSignals`）；上游 executor
检测到标记后重跑自身控制器重新识别（`ExecResult.NOT_IN_SCOPE`），全程无反向 L2→L1
调用。标记信封是协议信号，不透传给最终用户。

## 启用

```yaml
openjiuwen:
  service:
    versatile:
      url-template: http://controller:3001/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
      handoff:
        enabled: true
        self-agent-id: agent_card_l1            # 跨请求循环检测的自身标识
        timeout: 60s                             # 单次出站转调调用超时
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
        loop:
          max-redirects: 3
          max-route-trace-hops: 8
          duplicate-target-detection: true
        loop-trace-metadata:                     # 转调轨迹随 A2A metadata 透传的键名
          hop-count-key: handoffHopCount
          route-trace-key: handoffRouteTrace
          source-agent-key: sourceAgentId
        forward-metadata-keys: []                # 需原样透传给下游的额外 metadata 键
        cross-agent-resume:
          enabled: false                         # 下游 INPUT_REQUIRED 续接能力（未启用则报
                                                # VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED）
```

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
| `fields.dedup-key` | string | — | 去重键提取路径（如生产 `createdTime`；同一键的重复转调消息静默跳过） |

`enabled=true` 但 `classify.field-path` / `classify.field-value` 未配全时**启动失败**
（`event-type` 单独配置不充分）。提取到的字段值随报文格式约定，参考 demo 的生产
`message` 样例：`/data/node_name` ∈ `[意图返回, 不在范围]`、
`/data/summary` 为意图 id（如 `"3"`）、顶层 `/createdTime` 为 dedup-key。

识别命中但必要字段提取路径**缺失**（键不存在，如生产 SSE 的意图回显帧：
`text` 带值、无 `summary` 键）→ 该行整行抑制（WARN 日志可观测）：不处理、
不透传给最终用户、不产出 `MESSAGE_CONTRACT` 报错（2026-08-19 客户现场确认）。
注意空串值视为字段在场——非本次解析来源的字段合法为空（如 direct 目标为空、
由 intent 映射解析）。

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

### 循环保护（loop / loop-trace-metadata）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `loop.max-redirects` | int | `3` | 单请求内最大出站转调次数，超出 → `VERSATILE_HANDOFF_LOOP_LIMIT` |
| `loop.max-route-trace-hops` | int | `8` | 跨请求轨迹（`route-trace-key`）最大跳数，超出 → `VERSATILE_HANDOFF_LOOP_LIMIT` |
| `loop.duplicate-target-detection` | boolean | `true` | 单请求内向同一目标的重复转调 → `VERSATILE_HANDOFF_DUPLICATE_TARGET`；收到的轨迹包含 `self-agent-id`（回环重入）同样拒绝 |
| `loop-trace-metadata.hop-count-key` | string | `handoffHopCount` | 跳数计数随 A2A metadata 透传的键名 |
| `loop-trace-metadata.route-trace-key` | string | `handoffRouteTrace` | 已访问轨迹列表随 A2A metadata 透传的键名 |
| `loop-trace-metadata.source-agent-key` | string | `sourceAgentId` | 发起方标识随 A2A metadata 透传的键名 |

### 其他

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `false` | 总开关 |
| `self-agent-id` | string | — | 本实例 agent id，用于跨请求回环检测与轨迹记录 |
| `timeout` | duration | `60s` | 单次出站转调调用超时，超出 → `VERSATILE_HANDOFF_TIMEOUT` |
| `forward-metadata-keys` | list | `[]` | 除 loop-trace 三键外，需原样透传给下游的 metadata 键 |
| `cross-agent-resume.enabled` | boolean | `false` | 下游返回 INPUT_REQUIRED 时的跨智能体续接能力；未启用 → `VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED` |

错误码全集（映射关系见 L2 设计 §5.x）：

| 错误码 | 触发场景 |
|--------|----------|
| `VERSATILE_HANDOFF_TARGET_MISSING` | 按 `resolution-priority` 所有来源均未解析出目标 |
| `VERSATILE_HANDOFF_TARGET_NOT_ALLOWED` | 目标不在 `allowed-agents` 白名单 |
| `VERSATILE_HANDOFF_TARGET_UNAVAILABLE` | 目标未注册 / 出站连接失败（含同步抛错归一） |
| `VERSATILE_HANDOFF_TIMEOUT` | 出站调用超过 `timeout` |
| `VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED` | 下游返回 INPUT_REQUIRED 且 `cross-agent-resume.enabled=false` |
| `VERSATILE_HANDOFF_DUPLICATE_TARGET` | 单请求内重复转调同一目标，或入站轨迹回环重入（含 `self-agent-id`） |
| `VERSATILE_HANDOFF_LOOP_LIMIT` | 超出 `max-redirects` 或 `max-route-trace-hops` |
| `VERSATILE_HANDOFF_REMOTE_REJECTED` | 下游返回不可恢复失败 |
| `VERSATILE_HANDOFF_REMOTE_BUSINESS_FAILURE` | 下游业务侧失败终态 |
| `VERSATILE_HANDOFF_RESULT_INVALID` | 下游结果无法归一为有效回答 |
| `VERSATILE_HANDOFF_CALLER_UNAVAILABLE` | 本地 `RemoteAgentCaller` SPI 未就绪 |

## 已知事项

- 本地构建依赖 `.m2` 中的 `agent-service-app:0.1.1.post1` 为 develop HEAD 构建
  （EventObserver 形态 SPI）；同版本的旧工件是 `callOutcome(RemoteCall,
  QueryStreamObserver, Consumer<String>)` 旧签名，会导致本模块编译失败。
- 同仓库 `agent-service-adapters-agentcore-ext` 与 runtime develop HEAD 存在既有
  包路径漂移（`A2ARemoteAgentCardRegistry` 已从 `controller.a2a.client` 迁至
  `a2a.catalog`），与本模块无关；全仓回归需排除该模块直至其对齐。
