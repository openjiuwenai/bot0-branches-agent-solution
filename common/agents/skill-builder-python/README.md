# Skill Builder Agent

Skill Builder 将工作区材料抽取为经过验收的 Skill 包。它以独立 Python 包提供生成、HITL、候选预检、有界修复、Acceptance、状态恢复和安全归档能力；宿主负责产品接口、交互界面、持久化基础设施和外部发布。

## 首次接入

首次接入只需按以下顺序阅读：

1. 本 README：安装、运行拓扑和最小接线；
2. [宿主接入](docs/host-integration.md)：完整生命周期、HITL、继续/重试、编辑和导出；
3. [状态与宿主动作](docs/status-and-actions.md)：页面状态、允许动作和发布边界。

部署时再阅读[部署说明](docs/deployment.md)和[配置参考](docs/configuration.md)。录屏是可选能力，只在需要采集网页操作材料时阅读[录屏接入](docs/recording-integration.md)。

## 运行拓扑

```text
Python 宿主/后台任务
└── SkillBuilderClient                  生命周期与交付决策
    ├── SubprocessAgentRunner
    │   └── Agent Core 子进程           Scenario / Author / Repair
    │       └── Jiuwenbox workspace     独立沙箱服务
    ├── JiuwenboxExecutionPort          Acceptance 离线执行
    └── State/Event/HITL Ports          宿主可替换
```

`SkillBuilderClient` 是 Python 公共门面，不是 HTTP 服务或独立进程。默认部署方式是在宿主后台进程中运行生命周期控制器，并将 Agent Core 阶段隔离到子进程。Jiuwenbox 是单独部署的沙箱服务。

## 安装

```bash
cd common/agents/skill-builder-python
python3.11 -m venv .venv
.venv/bin/python -m pip install -e '.[agent-openjiuwen-python]'
cp .env.example .env
```

通过部署系统把 `.env` 中的变量注入宿主进程。Skill Builder 不会自动读取 `.env` 或其他密钥文件。

Jiuwenbox 默认地址为 `http://127.0.0.1:8321`，可通过 `SKILL_BUILDER_JIUWENBOX_URL` 修改。启动方式和健康检查见[部署说明](docs/deployment.md)。

可选录屏需要同时安装 Playwright Python 包和 Playwright 对应的 Chromium：

```bash
.venv/bin/python -m pip install -e '.[recording]'
.venv/bin/python -m playwright install chromium
```

精简 Linux 或容器环境还需要浏览器系统依赖，可在镜像构建阶段使用：

```bash
.venv/bin/python -m playwright install --with-deps chromium
```

## 最小宿主接线

完整参考实现见 [examples/host_background.py](examples/host_background.py)。最小客户端接线如下：

```python
from skill_builder import SkillBuilderClient
from skill_builder.adapters import (
    AgentCoreProcessConfig,
    JiuwenboxExecutionPort,
    SubprocessAgentRunner,
)
from skill_builder.spi import JsonFileStateStore, SkillBuilderAdapters

client = SkillBuilderClient(
    adapters=SkillBuilderAdapters(
        state_store=JsonFileStateStore(state_root),
        agent_runner=SubprocessAgentRunner(AgentCoreProcessConfig()),
        execution_port=JiuwenboxExecutionPort(),
    )
)
execution = await client.build(builder_input)
view = client.present(execution)
```

`client.build()` 自动推进 Scenario、可选 HITL、Author/AuthorBuild、候选预检、可选机械 Repair 和 Acceptance。宿主不应重新实现这些阶段，也不应根据日志文本计算第二套状态。

使用 `SubprocessAgentRunner` 时不要同时配置 `SkillBuilderAdapters.workspace`；子进程会根据环境创建 Jiuwenbox workspace adapter。进程内模式可以组合 `OpenJiuwenPythonAgentAdapter` 与 `JiuwenboxWorkspacePort`。

## Core 与宿主职责

| 能力 | Skill Builder Core | 宿主 |
|---|---|---|
| 生成生命周期 | Scenario、HITL 决策、Author、预检、Repair、Acceptance | 后台任务、取消、同 workspace 单写锁 |
| 状态与动作 | `present()`、blocker、`available_actions`、`publishable` | HTTP/SSE、页面展示、按钮和用户权限 |
| 材料 | 读取预算、契约抽取和生成 | 上传、格式/大小限制、二进制转 Markdown、资产登记 |
| HITL 与失败恢复 | pending request、resume token、恢复语义 | 表单 UI、答案提交、继续/重试入口 |
| 编辑 | 对话式 `run_turn`、事务回滚、Receipt 失效与重新验收 | 文件浏览器、编辑器 UI、文件读写接口和编辑锁 |
| 导出 | 安全路径白名单和 `build_export_archive()` | 下载接口、对象存储和保留策略 |
| 发布 | 发布资格和兼容发布包构造 | 审批、组织策略和外部发布动作 |
| 沙箱 | Jiuwenbox client、workspace/Acceptance adapter | Jiuwenbox 部署、容量、网络策略和健康检查 |
| 录屏 | 可选 Playwright 采集核心 | 录屏 UI/API、Chromium、网络策略、sticky routing 和资产清理 |

生产宿主通常还需要自己的鉴权、租户隔离、数据库 StateStore、事件存储、审计、恶意软件/许可证扫描和数据保留策略。

## 状态与交付

| 状态 | 含义 | 宿主动作 |
|---|---|---|
| `waiting_for_user` | 缺少真实业务决策 | 展示 Core 表单并调用 HITL `resume` |
| `draft_ready` | 已有合法候选但没有当前验收 Receipt | 允许检查、编辑、验证和草稿导出 |
| `ready` | 当前包已通过验收且 Receipt 有效 | 允许导出；发布仍受宿主审批约束 |
| `needs_review` | 包可检查/导出，但人工或外部边界阻断自动发布 | 展示审核范围，禁止自动发布 |
| `failed` | 当前运行未形成可接受结果 | 展示结构化错误以及继续/重试入口 |

warning 不是独立生命周期状态。不影响已验证可用性的提示可以形成 `ready + warn`。

普通导出不要求 `delivery_decision=ready`。宿主应根据 `view.available_actions` 展示导出入口，并调用 `client.build_export_archive(execution)` 构造归档。自动发布必须同时满足 `publishable=True` 和宿主自身审批策略。

## 示例

- [host_background.py](examples/host_background.py)：不依赖产品服务的完整 Python 宿主门面，覆盖 build、HITL resume、继续、重试、验证、编辑和导出。
- [recording_host.py](examples/recording_host.py)：录屏核心的最小宿主接线。
- [knowledge-role-sample.md](examples/materials/knowledge-role-sample.md)：知识型 Skill 的通用输入材料，仅用于示例和真实 smoke。
- [tabular-validation.md](examples/materials/tabular-validation.md)：需要 Python CLI 的脚本型通用输入材料，仅用于示例和真实 smoke。

`examples/materials/` 不是内置 Skill，也不参与生产运行。示例文件随源码和 sdist 提供，不进入运行时 wheel 包。

## 项目结构

```text
src/skill_builder/
├── api.py                 SkillBuilderClient 公共门面
├── application/           唯一生命周期与验收实现
├── domain/                状态与包契约
├── ports/                 宿主扩展接口
├── adapters/              OpenJiuwen、子进程、状态和 Jiuwenbox adapter
├── agent_worker.py        单个 Agent Core 阶段子进程入口
├── resources/             内置 Scenario/Author Skill
└── recording.py           可选 Playwright 录屏核心
```

## 文档导航

| 文档 | 适用场景 | 是否首次接入必读 |
|---|---|---|
| [宿主接入](docs/host-integration.md) | 生命周期调用、HITL、恢复、编辑和导出 | 是 |
| [状态与宿主动作](docs/status-and-actions.md) | UI 状态、动作和发布边界 | 是 |
| [部署说明](docs/deployment.md) | 进程、Jiuwenbox、systemd/容器和运维 | 部署必读 |
| [配置参考](docs/configuration.md) | 模型、预算、沙箱和录屏变量 | 部署必读 |
| [录屏接入](docs/recording-integration.md) | 可选网页操作材料采集 | 按需 |
| [架构说明](docs/architecture.md) | 组件边界、依赖方向和扩展点 | 按需 |
| [测试说明](docs/testing.md) | 单元测试、构建和真实 smoke | 维护必读 |

## 测试

```bash
python -m pytest
python -m build
```

默认测试使用 Fake Agent Runner，不依赖真实模型或网络。真实宿主 smoke 需要配置模型并启动健康的 Jiuwenbox 服务。
