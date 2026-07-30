# agent-bus — Agent 总线与入口平面

`common/agent-bus/` 汇聚 Agent 解决方案的总线转发与入口治理组件，下含 3 个可独立部署的子目录：

| 子目录 | 组件 | 职责 |
|---|---|---|
| [`event-bus/`](./event-bus/README.md) | 转发总线 | 两跳治理 relay（`event-bus-relay`）+ 生产/消费 SDK（`event-bus-spi` / `event-bus-sdk` / `event-bus-testkit`）。gateway/caller 与 runtime 之间的 broker 转发 |
| [`agent-gateway/`](./agent-gateway/README.md) | A2A 入口网关 | 接收 `/a2a` A2A JSON-RPC，治理（鉴权/租户/校验/幂等/审计）后按 DIRECT/BUS 转发到 runtime |
| [`registry-discovery-center/`](./registry-discovery-center/README.md) | 注册发现中心 | agent 注册、发现、健康探活（PostgreSQL + RLS 多租户隔离） |

## 组件关系

- **DIRECT 模式**：`agent-gateway` 经 `registry-discovery-center` 解析路由后，直连 runtime `/a2a`（HTTP/SSE），不经 `event-bus`。
- **BUS 模式**：`agent-gateway`（caller 角色）把请求产出到 `event-bus`，`event-bus-relay` 两跳转发（req → deliver → resp_in → resp_out）到 runtime；runtime 经 `event-bus` 回响应给 `agent-gateway`。

```
client ──POST /a2a──▶ agent-gateway ──┐
                       │  (RDC 解析路由)
                       ├──DIRECT─▶ runtime /a2a (HTTP/SSE)
                       └──BUS──▶ event-bus-relay ──▶ runtime
                                     ◀── resp ── (两跳回程)
```

## 构建

3 个子目录均为独立 Maven 工程（无 root pom），分别在各自目录构建：

```bash
cd common/agent-bus/event-bus && mvn install -DskipTests                      # event-bus reactor（4 模块）
mvn -f common/agent-bus/agent-gateway/pom.xml install -Dmaven.test.skip=true # agent-gateway
cd common/agent-bus/registry-discovery-center && mvn install -DskipTests       # registry-discovery-center
```

详细构建 / 运行 / 配置见各子目录 README。
