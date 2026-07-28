# agent-client

> **状态：Skeleton + Prototype。** Edge Access plane — AgentClient SDK（依据 Layer-0 principle P-I）。
> 团队自有的 AgentClient SDK（HTTP client + Task Cursor consumer + SSE/Webhook receiver）落地处，
> 实现按 ADR-0049 在 W3+ 推进。所有跨 plane 流量必须经 `agent-bus.spi.ingress.IngressGateway`
> 路由（ADR-0089 / Rule R-I.b）。

## 目录结构

```
agent-client/
├── agent-client-sdk-for-jvm/   ★ JVM 版 SDK 本体（api / *.spi / internal / transport）
├── docs/                       设计提案、设备可移植性 FAQ、getting-started 等
├── src/                        skeleton 占位（W3+ 实现落地处）
├── module-metadata.yaml        模块元数据（Rule R-C.b）
└── pom.xml                     skeleton pom（属主 reactor，parent=spring-ai-ascend-parent）
```

## SDK 本体（JVM 版）

`agent-client-sdk-for-jvm` 是面向 **JVM 环境**的 SDK 交付物：

- 公共 API 与 SPI（`api` / `tool.spi` / `spi` / `state.spi` / `transport.spi`）只用 JDK 类型，不泄漏任何第三方类型。
- 默认传输 `transport.a2a.A2aHttpTransportProvider` 走真实 A2A JSON-RPC 2.0 over HTTP + SSE。
- JDK 17 基线（`<release>17</release>` 锁定 API 与字节码）。

> 命名说明：`-for-jvm` 后缀明确这是面向 JVM 的实现；未来 `for-android` / `for-ios` / `for-harmony`
> 等多端 SDK 尚在建设中，跨端能力由线协议中立性保证，详见
> [`docs/device-portability-and-v1-delivery.md`](docs/device-portability-and-v1-delivery.md)。

SDK 本身的 Maven 坐标：`com.huawei.ascend.client.example:agent-client-sdk-for-jvm:0.2.0-SNAPSHOT`
（groupId/version 沿用 demo 工程的，待 SDK 正式发布时再迁出 example groupId）。

## 可运行示例

SDK 的可运行示例（mock-gateway + verification-app + Dockerfile + 对接手册）已挪到项目共享示例区：

- 工程根：[`../example/agent-client-demo/`](../example/agent-client-demo/)
- 对接手册：[`../example/agent-client-demo/Guidance4GatewayTest.md`](../example/agent-client-demo/Guidance4GatewayTest.md)

demo 工程是独立的多模块 reactor（父 pom = `agent-client-demo-parent`），可脱离主 reactor 单独构建，
会把 `agent-client-sdk-for-jvm` 经相对路径纳入一起编译：

```bash
cd common/example/agent-client-demo
mvn -q -o clean package
java -jar verification-app/target/verification-app.jar   # 退出码 0 = 全部断言通过
```

详细构建/运行/Docker 说明见 [demo 的 README](../example/agent-client-demo/README.md)。

## 文档

- [`docs/proposals/agent-client-v1-design.md`](docs/proposals/agent-client-v1-design.md) — V1 设计提案
- [`docs/device-portability-and-v1-delivery.md`](docs/device-portability-and-v1-delivery.md) — 设备可移植性与 V1 交付形态
- [`docs/getting-started.md`](docs/getting-started.md) — 最佳实践与测试方法
