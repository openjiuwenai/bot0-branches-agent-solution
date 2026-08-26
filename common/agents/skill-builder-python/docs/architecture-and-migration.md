# 架构与迁移

## 来源基线

- 来源仓库：`gcw_fleNWGnn/skillbuilder`
- 来源分支：`refactor/skill-builder-boundaries`
- 来源提交：`045732d249482d4f23f0480344a165388fe47201`
- 来源验证：`415 passed / 4 skipped`
- 目标基线：`openJiuwen/agent-solution common@70ffe929`

迁移对象是完整来源 `marketplace/skill_builder` 包。原宿主的 HTTP、ORM、鉴权、数据库事件存储、前端、对象存储和外部发布代码没有迁入。

## 依赖方向

```text
宿主 adapter -> skill_builder 公共 API 和 ports
skill_builder application -> domain 和 ports
默认 adapters -> ports 及外部 OpenJiuwen/Jiuwenbox client
```

`skill_builder` 不导入 `plugins_market`、FastAPI、SQLAlchemy 或宿主数据库。宿主可以替换状态、事件、HITL、workspace 和 execution adapter，不改变生命周期。

## 保持不变的业务行为

迁移没有改变 Scenario 编译、HITL 决策、Author Build、候选预检、最终 Acceptance、有界 Repair、交付状态或包安全规则。只对宿主专用文案和接线进行了中立化处理，没有改变兼容归档元数据契约。

## 进程边界

`SkillBuilderClient` 保留在宿主进程中。`SubprocessAgentRunner` 为每个 Agent Core 阶段创建子进程，并传输类型化结果与 JSONL 事件。worker 调用已有 `run_skill_builder_agent_core`，不复制业务生命周期。

Jiuwenbox 是独立服务。平台中立 client、workspace session 和最终 Acceptance execution adapter 随本包交付，因此新宿主不依赖旧宿主模块。

## 后续接入

A2A、Java adapter、HTTP/SSE server、Agent Card、浏览器真实性验证、对象存储、审批和外部发布均属于后续或宿主工作。它们只能包裹公共 API，不能引入另一套生命周期控制器。

支持的 Python Runtime 就绪后，在 `SkillBuilderClient` 外新增 Runtime adapter，映射请求、事件、HITL、取消和制品；Scenario、Author、Repair 和 Acceptance 不需要重构。
