# 部署说明

## 部署形态

Skill Builder 当前是可安装的 Python Agent 包，不是独立 HTTP/A2A 服务。它部署在 Python 宿主的后台进程中：

```text
宿主 API / 任务进程
└── SkillBuilderClient
    ├── Agent Core 子进程
    ├── 持久化 StateStore 和 workspace
    └── JiuwenboxExecutionPort
            │
            ▼
      独立 Jiuwenbox 服务
```

宿主负责 HTTP、鉴权、租户隔离、任务队列、同 workspace 单写锁、HITL/继续/重试入口、对象存储和发布。

## 环境要求

- 运行环境满足 OpenJiuwen 与 Jiuwenbox 的 Linux 支持要求；
- 宿主和 Agent Core worker 使用 Python `>=3.11.4`；
- 可用的 OpenAI-compatible 模型地址和密钥；
- 宿主能够访问独立 Jiuwenbox 服务；
- 持久、可写的 workspace 和 state 存储；
- 可选录屏需要 Playwright/Chromium。

Skill Builder wheel 不包含 Jiuwenbox。请通过已批准的渠道单独安装，并在部署前验证：

```bash
/opt/jiuwenbox/.venv/bin/python -m jiuwenbox.server.launcher --help
```

## 目录规划

一种生产目录布局：

```text
/opt/skill-builder-python/             应用和虚拟环境
/etc/skill-builder/skill-builder.env   配置及密钥引用
/var/lib/skill-builder/workspaces/     持久 workspace
/var/lib/skill-builder/state/          独立 state 根目录（如未放在 workspace）
/var/log/skill-builder/                宿主日志
/var/log/jiuwenbox/                    可选沙箱审计日志
```

每个 workspace 必须位于独立受控目录。禁止把 `/`、用户 home 或仓库根目录作为生成 workspace。宿主服务账号只应拥有配置的 workspace/state 根目录写权限。

多实例宿主不能只依赖本地 `JsonFileStateStore`。需要共享 `SkillBuilderStateStore`、共享 workspace 存储，以及按 `workspace_id` 的分布式单写锁或 CAS。

## 安装 Skill Builder

源码开发安装：

```bash
cd common/agents/skill-builder-python
python3.11 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install '.[agent-openjiuwen-python]'
```

生产环境建议构建 wheel 后安装到宿主虚拟环境：

```bash
python -m build
/opt/skill-builder-host/.venv/bin/python -m pip install \
  dist/openjiuwen_skill_builder-0.1.0-py3-none-any.whl
/opt/skill-builder-host/.venv/bin/python -m pip install 'openjiuwen==0.1.12'
```

Agent Core 子进程使用 `AgentCoreProcessConfig.python_executable`，默认等于宿主 `sys.executable`。该解释器必须能导入 `skill_builder` 和 `openjiuwen`。

## 配置环境变量

以 `.env.example` 为模板，通过部署密钥系统注入真实值：

```dotenv
SKILL_BUILDER_LLM_PROVIDER=OpenAI
SKILL_BUILDER_LLM_API_BASE=https://model-gateway.example/v1
SKILL_BUILDER_LLM_API_KEY=<secret-manager-reference>
SKILL_BUILDER_LLM_MODEL=<configured-model>

SKILL_BUILDER_SANDBOX_ENABLED=true
SKILL_BUILDER_JIUWENBOX_URL=http://127.0.0.1:8321
```

包不会自动加载 `.env`。仅本地 smoke 可使用：

```bash
set -a
. ./.env
set +a
```

生产进程应使用 EnvironmentFile 或密钥注入，禁止把真实 API key 写入仓库、workspace、worker request/result 或事件。完整配置见[配置参考](configuration.md)。

## 启动 Jiuwenbox

同机部署建议只监听 loopback：

```bash
/opt/jiuwenbox/.venv/bin/python -m jiuwenbox.server.launcher \
  --listen http://127.0.0.1:8321 \
  --log-level info \
  --save-logs /var/log/jiuwenbox
```

健康检查：

```bash
curl --fail --silent http://127.0.0.1:8321/health
```

返回应包含 `status=ok`。还应按部署安全基线检查 runtime 和沙箱安全能力。

禁止把无鉴权的 Jiuwenbox 管理端口暴露到公网或不可信网络。跨主机/容器部署时应配置网络策略和受控服务地址：

```dotenv
SKILL_BUILDER_JIUWENBOX_URL=http://jiuwenbox.internal:8321
```

## 启动宿主

Skill Builder 没有独立服务启动命令。宿主创建共享的 `SubprocessAgentRunner`、构造 `SkillBuilderClient`，并提供自己的 API/任务入口。参考：

- [宿主接入](host-integration.md)
- [状态与宿主动作](status-and-actions.md)
- [空宿主示例](../examples/host_background.py)

真实模型/Jiuwenbox 本地 smoke：

```bash
.venv/bin/python examples/host_background.py \
  --workspace /tmp/skill-builder-smoke/workspace \
  --workspace-id smoke-workspace \
  --materials examples/materials/knowledge-role-sample.md \
  --skill-name sample-role-skill \
  --display-name "Sample Role Skill" \
  --description "Generate a sample Skill from generic material" \
  --output /tmp/skill-builder-smoke/sample-role-skill.zip
```

该 smoke 是 opt-in 验证，不属于默认 CI。

## 宿主必须提供的操作入口

| 操作 | 必需语义 |
|---|---|
| Build | 获取 workspace 锁后，在后台调用 `client.build` |
| HITL 回答 | 校验并持久化答案，使用 pending token 调用 Core `resume` |
| 失败后继续 | 保留候选和检查点，使用 `kind="resume"` 恢复消息调用 `reconcile` |
| 失败后重试 | 生成 `kind="retry"` 消息，清理旧输出后重新 `build`；保留 `inputs/` |
| Validate | 加载当前 execution 后调用 `validate` |
| Repair | 只处理已确认的机械可修复诊断 |
| Export | Core 构造归档，宿主返回或保存 |
| Cancel | 取消并等待宿主后台 task，随后释放 worker/Sandbox |

HITL 回答、失败后继续和失败后重试不是同一个操作。参考实现见[宿主接入](host-integration.md)和空宿主示例。

## systemd 示例

Jiuwenbox unit：

```ini
[Unit]
Description=Jiuwenbox sandbox service for Skill Builder
After=network.target

[Service]
Type=simple
User=skillbuilder
Group=skillbuilder
ExecStart=/opt/jiuwenbox/.venv/bin/python -m jiuwenbox.server.launcher --listen http://127.0.0.1:8321 --log-level info --save-logs /var/log/jiuwenbox
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

宿主 unit（将 `your_host.main` 替换为真实宿主模块）：

```ini
[Unit]
Description=Python host containing Skill Builder Agent
After=network.target jiuwenbox.service
Requires=jiuwenbox.service

[Service]
Type=simple
User=skillbuilder
Group=skillbuilder
WorkingDirectory=/opt/skill-builder-host
EnvironmentFile=/etc/skill-builder/skill-builder.env
ExecStart=/opt/skill-builder-host/.venv/bin/python -m your_host.main
Restart=on-failure
RestartSec=3
TimeoutStopSec=45

[Install]
WantedBy=multi-user.target
```

宿主停止时必须取消并等待活动 Skill Builder task，并停止当前进程中的录屏。

## 容器部署

至少保留两个服务边界：

```text
host container       Skill Builder 包 + OpenJiuwen + 宿主 API/worker
jiuwenbox container  沙箱管理服务
```

宿主 workspace/state 使用持久卷或外部存储。Jiuwenbox 位于另一容器时不能配置 `127.0.0.1`。当前 adapter 通过 client 传输 workspace 内容，不要求两个容器共享文件系统。

仓库当前不提供 Jiuwenbox 镜像或完整宿主镜像。镜像来源、OS 依赖、沙箱权限、网络策略和 registry provenance 必须遵循部署环境批准的 Jiuwenbox 发行方式。

## 可选录屏部署

```bash
.venv/bin/python -m pip install '.[recording]'
.venv/bin/python -m playwright install chromium
```

精简 Linux 或容器镜像还需要 Chromium 系统依赖，可在镜像构建阶段改用：

```bash
.venv/bin/python -m playwright install --with-deps chromium
```

headless/viewer 或 headed/X11 配置见[录屏接入](recording-integration.md)。活动录屏对象保存在进程内；多 worker 宿主必须为同一录屏提供 sticky routing，或部署独立录屏服务。

录屏 profile、storage state、截图、下载和 trace 可能包含会话敏感信息，应与导出 Skill 包分开存储和清理。

## 健康检查与可观测性

宿主 readiness 至少检查以下内容，且不得打印密钥：

- 能导入 Skill Builder 和 OpenJiuwen；
- 必需模型变量已配置；
- workspace/state 根目录可写；
- 需要沙箱执行时 Jiuwenbox `/health` 成功；
- StateStore 和 workspace 锁服务可用。

建议监控阶段耗时、模型请求失败、worker 退出/超时、HITL 等待时长、Repair 次数、Validation 状态和 Jiuwenbox 生命周期。持久化状态是事实源，事件只是进度和诊断数据。

## 升级与回滚

升级前：

1. 停止接收新的写任务；
2. 等待或取消活动 Agent Core worker；
3. 停止活动录屏；
4. 备份 StateStore 和持久 workspace；
5. 在新环境安装新 wheel；
6. 运行包级测试和一次 opt-in smoke；
7. 将宿主切换到新环境。

状态 schema 和 policy version 会被校验。禁止静默加载不支持的状态版本。回滚时必须同时恢复兼容的 wheel 和 state 备份。

## 未来 Python Runtime 独立部署

支持的 Python Agent Runtime 就绪后，可以把 Skill Builder 部署为可独立寻址的 Agent 服务：

```text
Client -> Python Runtime -> SkillBuilder Runtime Adapter -> SkillBuilderClient
                                                   ├── Agent Core 子进程
                                                   └── Jiuwenbox 服务
```

Runtime adapter 只映射请求、事件、HITL、取消和制品，不得重写 Scenario、Author、Repair、Acceptance 或交付判断。

如果 Runtime 已提供服务和进程生命周期，不再增加第二套 `SkillBuilderProcessClient`。多实例 Runtime 仍需要共享 StateStore、workspace 和按 workspace 的单写锁；录屏仍需要 sticky routing 或独立录屏服务。

## 部署验证

```bash
python -m pytest
python -m build
```

默认测试使用 Fake Agent Runner，不依赖模型或网络。生产上线前还应使用非敏感测试材料运行宿主/Jiuwenbox smoke，并确认完成或取消后没有残留 Agent worker 和沙箱。
