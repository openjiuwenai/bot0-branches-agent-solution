# EDPA-alpha CHANGELOG

## v0.2.0（2026-08，装配显式化重构）

### ⚠ 破坏性变更（Breaking）——BPP 自动装配移除

`EdpaAutoConfiguration` 的 BeanPostProcessor rail 自动装配已**移除**（对齐官方 demo pattern
"装配基础设施而非 Agent"）。旧用法 `edpa.enabled=true` + ReActAgent 暴露为 Spring bean 即自动挂
rail 的行为**不复存在**。

**升级迁移**（一行加入宿主 `@Bean AgentHandler`）：

```java
// 旧（v0.1，BPP 自动挂 rail）：
//   @Bean ReActAgent myAgent(...) { ... }   // edpa.enabled=true 时 BPP 自动挂 rail
//   ⚠ v0.2 起：不再自动挂载，agent 会以零认知 rail 运行（启动时 autoconfig 会 WARN 提示）

// 新（v0.2，显式装配，官方 demo pattern）：
@Bean
AgentHandler myAgentHandler(LlmConfigResolver r, EdpaProperties props,
        CriteriaVerifier verifier, Explorer explorer) {
    ReActAgent agent = ExampleReActAgentFactory.build("my-agent", ..., llm);
    EdpaRails.registerOnto(agent, props, verifier, explorer);  // ← 加这一行
    return new JiuwenCoreAgentHandler(agent);
}
```

未迁移的症状：EDPA 零生效（无 Explore / 无 criteria verify / 无 `__replan__` 工具）。
零命中探测器会在 `ContextRefreshedEvent` 时 WARN（覆盖两种形态：上下文无 ReActAgent
bean，或有 bean 但未挂 EDPA rail）。

### 变更明细

- **新增 `EdpaRails.registerOnto`**：静态装配门面，单一装配真源。承载接线图契约
  （`sharedReplanRail` 单实例共享预算 / `SteeringProvisionRail` 首位注册（issue-#13）/
  `userInputRef` 闭包共享 / tool-rail 双模式分支）。返回 `RegistrationSummary` 供装配后断言。
- **`EdpaAutoConfiguration` 基础设施化**：只保留 3 个基础设施 Bean
  （`EdpaProperties` / `CriteriaVerifier` / `Explorer`）+ 零命中 WARN 探测。
- **默认 CriteriaVerifier 语义澄清**：默认 Bean 由 `GroundTruthVerifier`（空 checker 时
  实际等同 keyword）改为 `RuleBasedCriteriaVerifier`（名实一致）。需确定性验证的宿主显式
  构造 `new GroundTruthVerifier(List.of(myChecker))` 覆盖注入。
- **SubAgent 派发治理**：`SubAgentTool` / `SubAgentExecutor` 从 nested 拆为顶级类型
  （对齐模块内其他 SPI 拓扑）；`SubAgentDispatcher` 用 agent 级 tag 注册
  （`addTool(tool, agentId)`，诚实边界：ResourceMgr 按 id 派发，tag 用于归因非隔离）；
  `SubAgentTool` 的 `user_input` 改由 Supplier 解析（典型接线 `UserInputCaptureRail` 共享
  引用），空/blank 回退 sub_goal。
- **文档**：L1×6 + L2×2 + FEAT-025/026 全套随装配语义同步；`DataFlowObserverRail` /
  OTel 层保持移除状态（MR !77，可观测性继承 react-rails RailTelemetry）。

### 兼容性保持

- `EdpaProperties` 配置项全部不变（`edpa.enabled` / `edpa.explore-mode` / `edpa.criteria` 等）。
- rail 行为契约不变（Explore 两模式 / criteria verify→replan / convergence 检测 /
  RootCause 降级门），仅装配入口变化。
- 模块测试 131 全绿 + 真 LLM e2e（deepseek-v4-flash）+ 6 模型 × thinking 矩阵复验无回归。

## v0.1.0（2026-07）

- 初版：认知 overlay（Explore→Decision→Plan→Action）+ MCP 集成 + SubAgent 派发。
- MR !77 移除 `DataFlowObserverRail` / OTel 层（ext 层 OTel-as-source 错层，结论甲）。
