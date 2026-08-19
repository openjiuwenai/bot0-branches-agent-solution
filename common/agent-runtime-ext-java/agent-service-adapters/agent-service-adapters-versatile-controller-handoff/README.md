# agent-service-adapters-versatile-controller-handoff

FEAT-002 Versatile 控制器意图转调 adapter（L2 设计见 agent-solution-docs
`Feat-Func-002-versatile-controller-intent-message-routing.md`）。

在 FEAT-002 Versatile 通用代理（`agent-service-adapters-versatile`，只读复用，基线类
仅做 public 可见性放大）之上新增第三种接入模式：adapter 直接对接控制器，同时识别
控制器返回的意图转调消息，经 `RemoteAgentCaller` SPI（A2A 网关）调用目标智能体，
并把下游结果归一为当前 `QueryChunk` 与终态。

## 启用

```yaml
openjiuwen:
  service:
    versatile:
      url-template: http://controller:3001/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
      handoff:
        enabled: true
        self-agent-id: agent_card_l1            # 跨请求循环检测的自身标识
        classify:
          field-path: /data/code                # 客户报文确认前无默认值，必须显式配置
          field-value: [14000]                  # 未配全则启动失败
        fields:
          handoff-type: /data/handoff_type
          intent-id: /data/intent_id
          business-domain: /data/domain
          target-agent-id: /data/target_agent/id
          dedup-key: /data/dedup_key
        target:
          allowed-agents: [agent_card_l1, agent_card_layer2_hotel]
          fixed-l1-entry: agent_card_l1
```

`handoff.enabled=true` 时本模块产出本实例唯一 `AgentHandler`；`false`（默认）时本模块
不装配任何 bean，行为等价基线。识别先于 FEAT-002 异常映射（固化于处理链）；
`COMPLETED` 由 `onComplete()` 隐式表达；转调失败路径全部产出可诊断 `TYPE_ERROR`
（`VERSATILE_HANDOFF_*`），不返回空 COMPLETED。

## 已知事项

- 本地构建依赖 `.m2` 中的 `agent-service-app:0.1.1.post1` 为 develop HEAD 构建
  （EventObserver 形态 SPI）；同版本的旧工件是 `callOutcome(RemoteCall,
  QueryStreamObserver, Consumer<String>)` 旧签名，会导致本模块编译失败。
- 同仓库 `agent-service-adapters-agentcore-ext` 与 runtime develop HEAD 存在既有
  包路径漂移（`A2ARemoteAgentCardRegistry` 已从 `controller.a2a.client` 迁至
  `a2a.catalog`），与本模块无关；全仓回归需排除该模块直至其对齐。
