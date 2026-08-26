# 架构说明

## 设计目标

Skill Builder 是可嵌入 Python 宿主的独立 Agent 包。核心目标是让不同宿主复用同一套 Skill 生成、验收、恢复和交付语义，同时把产品接口、存储和发布策略留在宿主边界。

## 组件结构

```text
SkillBuilderClient
├── application/           生命周期、验收、事务和交付编排
├── domain/                状态、场景、决策和包契约
├── ports/                 可替换的宿主与运行时接口
├── adapters/              OpenJiuwen、子进程、状态和 Jiuwenbox 实现
├── resources/             Scenario/Author 内置 Skill 和参考资料
├── runtime/               模型预算、阶段超时和内部运行辅助
└── recording.py           可选 Playwright 材料采集核心
```

`application/` 是唯一业务生命周期实现。宿主 adapter 和外部 Runtime 只能调用公共 API 或实现 Port，不能复制 Scenario、Author、Repair、Acceptance 或交付状态计算。

## 依赖方向

```text
宿主 adapter -> skill_builder 公共 API 和 ports
skill_builder application -> domain 和 ports
默认 adapters -> ports 及 OpenJiuwen/Jiuwenbox client
domain -> Python 标准库和纯业务契约
```

`skill_builder` 不依赖 FastAPI、SQLAlchemy、宿主数据库或产品前端。State、Event、HITL、Agent、Workspace 和 Execution 均通过稳定 Port 接入。

## 生命周期

```text
Scenario
  -> 可选 HITL
  -> Author / AuthorBuild
  -> 候选预检
  -> 可选机械 Repair
  -> Acceptance
  -> ready / needs_review / failed
```

`SkillBuilderClient.build()` 自动推进生命周期，直到形成终态或等待用户输入。持久状态和 artifact receipt 是事实源；事件只用于进度和诊断。

## 进程边界

`SkillBuilderClient` 运行在宿主后台进程中。`SubprocessAgentRunner` 为每个 Agent Core 阶段创建子进程，并通过类型化结果和 JSONL 事件返回执行结果。子进程只执行一个阶段，不拥有完整生命周期。

Jiuwenbox 是独立沙箱服务。默认 adapter 分别为 Agent Core workspace 和 Acceptance 创建受限、短生命周期的执行环境。宿主负责部署、容量、网络策略和健康检查。

## 宿主扩展点

| Port | 用途 | 默认实现 |
|---|---|---|
| `SkillBuilderStateStore` | 持久化生命周期状态 | JSON 文件、内存实现 |
| `SkillBuilderEventSink` | 接收阶段事件 | Callback 实现 |
| `SkillBuilderHitlProvider` | 获取结构化用户决策 | Callback 实现 |
| `SkillBuilderAgentRunner` | 运行 Agent Core 阶段 | 子进程或进程内 OpenJiuwen adapter |
| `SkillBuilderWorkspacePort` | 提供 Agent 文件工作区 | Jiuwenbox workspace adapter |
| `SkillBuilderExecutionPort` | 执行 Acceptance 命令 | Jiuwenbox execution adapter |

生产宿主可以替换 Port，但必须保留请求、状态、Receipt 和交付决策语义。

## 产品边界

以下能力不由 Core 提供：

- HTTP/SSE/A2A 服务和产品前端；
- 鉴权、租户隔离、任务队列和分布式锁；
- 材料上传、对象存储和二进制预处理；
- 文件编辑器、HITL 页面和失败恢复按钮；
- 审批、恶意软件/许可证扫描和外部发布；
- 浏览器真实性验证；
- 跨进程录屏协调和录屏资产服务。

这些能力应包裹公共 API，不应引入第二套生命周期控制器。

## Runtime 接入

外部 Runtime 应映射 build、事件、HITL、取消、状态和制品接口。多实例 Runtime 仍需共享 StateStore、workspace 和按 workspace 的单写锁；Scenario、Author、Repair 和 Acceptance 不需要因传输协议改变而重构。
