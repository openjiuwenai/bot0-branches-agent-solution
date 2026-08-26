# Skill Builder Agent

Skill Builder 将工作区材料抽取为经过验收的 Skill 包。它是一个独立 Python Agent 工程，生成、HITL、验收、有界修复、状态恢复和归档构造均在本目录实现；HTTP、ORM、鉴权、对象存储和外部发布由宿主负责。

迁移来源为 `skillbuilder/refactor/skill-builder-boundaries@045732d`，目标基线为 `agent-solution/common@70ffe929`。

## 运行拓扑

```text
Python 宿主/后台任务
└── SkillBuilderClient                  生命周期与持久化决策
    ├── SubprocessAgentRunner
    │   └── Agent Core 子进程           Scenario / Author / Repair
    │       └── Jiuwenbox workspace     独立沙箱服务
    ├── JiuwenboxExecutionPort          最终 Acceptance smoke
    └── State/Event/HITL Ports          宿主可替换
```

`SkillBuilderClient` 是 Python 公共门面，不是服务或进程。默认接入方式是在宿主进程中保留生命周期控制器，只隔离 Agent Core 阶段。

## 安装

```bash
cd common/agents/skill-builder-python
python -m venv .venv
.venv/bin/python -m pip install -e '.[agent-openjiuwen-python]'
cp .env.example .env
```

通过部署系统把 `.env` 中的变量加载到进程环境。Skill Builder 不会自动读取密钥文件。

Jiuwenbox 需要单独部署。默认地址为 `http://127.0.0.1:8321`，可通过 `SKILL_BUILDER_JIUWENBOX_URL` 修改。

可选录屏需要安装 Playwright 和 Chromium：

```bash
.venv/bin/python -m pip install -e '.[recording]'
.venv/bin/python -m playwright install chromium
```

## 宿主接入

完整参考实现见 [examples/host_background.py](examples/host_background.py)，核心接线如下：

```python
from skill_builder import SkillBuilderClient
from skill_builder.adapters import (
    AgentCoreProcessConfig,
    JiuwenboxExecutionPort,
    SubprocessAgentRunner,
)
from skill_builder.spi import (
    JsonFileStateStore,
    SkillBuilderAdapters,
)

client = SkillBuilderClient(
    adapters=SkillBuilderAdapters(
        state_store=JsonFileStateStore(state_root),
        agent_runner=SubprocessAgentRunner(AgentCoreProcessConfig()),
        execution_port=JiuwenboxExecutionPort(),
    )
)
```

使用 `SubprocessAgentRunner` 时不要同时设置 `SkillBuilderAdapters.workspace`；子进程会根据环境配置创建 Jiuwenbox workspace adapter。进程内模式可组合 `OpenJiuwenPythonAgentAdapter` 与 `JiuwenboxWorkspacePort`。

详细调用方式见 [宿主接入](docs/host-integration.md)，包含材料准备、HITL、失败后继续/重试、恢复、持久化、取消、编辑、验证和导出。

## 交付状态

| 状态 | 含义 | 宿主动作 |
|---|---|---|
| `waiting_for_user` | 缺少真实业务决策 | 展示表单并调用 HITL `resume` |
| `ready` | 当前包已通过验收且 receipt 有效 | 允许导出；是否发布仍由宿主审批 |
| `needs_review` | 包可检查/导出，但人工或外部边界阻断自动发布 | 禁止自动发布 |
| `failed` | 当前运行未形成可接受候选 | 展示结构化错误以及继续/重试入口 |

warning 不是独立生命周期状态。当 warning 不影响已验证可用性时，可以出现 `ready + warn`。本包不会执行外部发布。

## 项目结构

```text
src/skill_builder/
├── api.py                 SkillBuilderClient 公共门面
├── application/           生命周期与验收编排
├── domain/                状态与包契约
├── ports/                 宿主扩展接口
├── adapters/              OpenJiuwen、子进程、状态和 Jiuwenbox adapter
├── agent_worker.py        单个 Agent Core 阶段子进程入口
├── resources/             内置 Scenario/Author Skill
└── recording.py           可选 Playwright 录屏核心
```

## 文档导航

- [部署说明](docs/deployment.md)
- [配置参考](docs/configuration.md)
- [宿主接入](docs/host-integration.md)
- [状态与宿主动作](docs/status-and-actions.md)
- [录屏接入](docs/recording-integration.md)
- [架构与迁移](docs/architecture-and-migration.md)
- [测试说明](docs/testing.md)

录屏宿主示例见 [examples/recording_host.py](examples/recording_host.py)。

## 测试

```bash
python -m pytest
python -m build
```

默认 CI 使用 Fake Agent Runner，不依赖真实模型或网络。真实宿主 smoke 需要配置模型并启动健康的 Jiuwenbox 服务。
