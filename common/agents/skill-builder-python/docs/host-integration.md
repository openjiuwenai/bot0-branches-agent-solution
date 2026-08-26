# 宿主接入

## 职责边界

Skill Builder 负责完整业务生命周期并返回类型化状态。宿主负责任务调度、数据库/对象存储、用户身份、HTTP、UI 和外部发布。

宿主不得重新实现 Scenario、Author、Repair、Acceptance 或计算第二套交付结论。页面状态必须来自：

```python
view = client.present(execution)
```

## 宿主实现清单

| 能力 | 接入要求 |
|---|---|
| API 与身份 | 提供 HTTP/RPC 接口、鉴权、租户隔离和 workspace 授权 |
| 后台任务 | 调度 `build/resume/reconcile/run_turn/repair`，支持取消和按 workspace 单写锁 |
| 持久化 | 实现生产 StateStore、workspace、事件存储和对象存储 |
| 材料 | 上传、病毒扫描、格式/大小限制、二进制转 Markdown 和材料索引 |
| HITL | 原样渲染 Core 表单，提交 resume token 和结构化答案 |
| 失败恢复 | 分别提供继续和重试入口，不重放旧 worker request |
| 文件编辑 | 提供文件浏览器、编辑器 UI、安全文件 API 和保存锁 |
| 导出 | 调用 Core 构造安全归档，再实现下载、对象存储和保留策略 |
| 发布 | 实现审批、组织规则、许可证检查和外部发布动作 |
| 沙箱 | 部署 Jiuwenbox，配置容量、网络策略、健康检查和清理 |
| 可选录屏 | 提供录屏 UI/API、Chromium、sticky routing 和敏感资产治理 |

Skill Builder 不提供产品前端、HTTP route、任务队列、数据库模型、对象存储 SDK 或外部发布客户端。

## 构造客户端

每个宿主进程创建一个共享 `SubprocessAgentRunner`，使并发限制覆盖该进程中的所有 workspace：

```python
from pathlib import Path

from skill_builder import SkillBuilderClient
from skill_builder.adapters import (
    AgentCoreProcessConfig,
    JiuwenboxExecutionPort,
    SubprocessAgentRunner,
)
from skill_builder.spi import (
    CallbackEventSink,
    JsonFileStateStore,
    SkillBuilderAdapters,
)

async def persist_event(event_type, summary, payload):
    # 替换为宿主事件表或消息总线。
    print(event_type, summary)

runner = SubprocessAgentRunner(
    AgentCoreProcessConfig(
        max_concurrency=2,
        timeout_seconds=None,
    )
)
client = SkillBuilderClient(
    adapters=SkillBuilderAdapters(
        state_store=JsonFileStateStore(Path("./state")),
        event_sink=CallbackEventSink(persist_event),
        agent_runner=runner,
        execution_port=JiuwenboxExecutionPort(),
    )
)
```

`timeout_seconds=None` 表示使用 Agent Core 的活动感知阶段超时。宿主硬超时只用于操作保护，不能用来掩盖模型或契约问题。

## 准备 workspace 和材料

宿主负责上传和材料预处理：

1. 分配租户隔离、可写的 workspace 根目录；
2. 使用安全相对路径把原始材料保存到 `inputs/`；
3. 拒绝路径穿越和不可信软链接；
4. 限制文件数量、单文件/总大小和文件类型；
5. 将 PDF/DOCX/XLSX 等二进制材料转换为可追溯 Markdown；
6. 在材料索引中保留原始路径和解析路径；
7. 构造有界的 `materials_markdown` 和 `SkillBuilderInput`。

示例目录：

```text
workspace-root/
├── inputs/
│   ├── workflow-rules/policy.docx
│   ├── workflow-rules/policy_parsed.md
│   ├── data-examples/sample.csv
│   └── external-sources/<recording-id>/web-recording.md
├── generated-skill/
├── validation/
├── workspace/
├── playwright/
└── .skill-builder/
```

`validation/`、`.skill-builder/`、`workspace/` 和 `playwright/` 不得进入导出 Skill 包。

Skill Builder 接管准备完成后的材料读取预算，但不负责宿主上传鉴权、病毒扫描、对象存储或资产表。

## 后台任务和单写锁

```python
task = asyncio.create_task(client.build(builder_input))
execution = await task
```

取消该 task 会终止当前 Agent Core 子进程，StateStore 已写入的检查点保留。

同一 `workspace_id` 同时只能运行一个写生命周期。`SubprocessAgentRunner.max_concurrency` 只是进程级模型并发限制，不是 workspace 锁。宿主应在调用 `build`、HITL `resume`、失败后继续/重试、写入型 `run_turn` 或 `repair` 前获取任务表唯一约束、分布式 lease 或 StateStore CAS。

生产宿主通常用自己的 `SkillBuilderStateStore` 替换 `JsonFileStateStore`。存储必须保留 `SkillBuilderState.to_dict()` 结构，并拒绝不支持的 schema version，不能静默转换。

## 公共操作映射

| 宿主操作 | Skill Builder 调用 | 说明 |
|---|---|---|
| 启动生成 | `client.build(builder_input)` | Scenario、可选 HITL、Author、Acceptance |
| 协调中断状态 | `client.reconcile(builder_input, advance=...)` | 根据持久状态选择唯一合法下一步 |
| 加载状态 | `client.load(workspace_id)` | 需要 StateStore |
| 提交 HITL 答案 | `client.resume(workspace_id, resume_token=..., answer=...)` | 只用于 `waiting_for_user` |
| 失败后继续 | 使用 `kind="resume"` 恢复消息调用 `reconcile`，不清理输出 | 保留候选、检查点和 inputs |
| 失败后重试 | 使用 `kind="retry"` 消息，清理旧输出后调用 `build` | 全新抽取，保留 inputs |
| 验证当前包 | `client.validate(execution.input, hitl_confirmations=...)` | 纯检查不依赖模型 |
| 显式机械修复 | `client.repair(execution, instruction=...)` | 只处理结构化、机械可修复诊断 |
| 问答/编辑 | `client.run_turn(workspace_id, SkillBuilderTurnRequest(...))` | Core 负责写入策略和回滚 |
| 登记宿主手工编辑 | `client.invalidate_receipt(workspace_id)` | 清除旧 Acceptance 身份 |
| 导出 | 可选调用 `client.build_export_archive(execution)` | 宿主决定导出策略、格式和存储 |

## HITL、继续和重试

这三种操作必须使用不同宿主入口，不能统一成“再运行一次”。

### HITL 回答

只用于 `waiting_for_user`：

```python
pending = execution.pending_request
execution = await client.resume(
    execution.workspace_id,
    resume_token=pending.resume_token,
    answer=answer,
)
```

宿主必须展示 Core 提供的表单，绑定已登录用户/workspace，防止重复提交；答案不完整时继续保留 pending request。

### 失败后继续

“继续”保留候选、诊断、Draft Workspace、revision、检查点和 `inputs/`：

```python
from dataclasses import replace

current = await client.load(workspace_id)
if current is None or current.status.value != "failed":
    raise Conflict("continue requires a failed execution")

message = client.build_recovery_message(
    current,
    kind="resume",
    user_message=user_message,
)
execution = await client.reconcile(
    replace(current.input, user_message=message),
    options=replace(current.options, run_phase="workflow"),
    hitl_confirmations=current.hitl_confirmations,
    advance=True,
)
```

存在有效候选时 Core 会恢复候选并验收；没有候选时选择下一合法生成步骤。

### 失败后重试

“重试”是全新抽取：

```python
from dataclasses import replace
from skill_builder.host_support import reset_generated_outputs

current = await client.load(workspace_id)
if current is None or current.status.value != "failed":
    raise Conflict("retry requires a failed execution")

message = client.build_recovery_message(
    current,
    kind="retry",
    user_message=user_message,
)
confirmations = current.hitl_confirmations
reset_generated_outputs(current.input.root)

execution = await client.build(
    replace(current.input, user_message=message),
    options=replace(current.options, run_phase="workflow"),
    hitl_confirmations=confirmations,
)
```

重试会删除 `generated-skill/`、`validation/`、`playwright/`、旧状态、draft、revision、context 和 generation checkpoint，但保留 `inputs/`。宿主需同步清理指向已删除诊断文件的资产记录。仍有效的结构化确认必须显式传入。

没有公共 `client.retry()`，因为 reset 范围涉及宿主 workspace 和资产存储边界。继续和重试都不能直接重放上一个 Agent worker request。

## 建议宿主入口

| 入口 | 行为 |
|---|---|
| `POST /skill-builder/workspaces/{id}/build` | 后台启动 `client.build` |
| `GET /skill-builder/workspaces/{id}` | `client.load` 后调用 `client.present` |
| `POST /skill-builder/workspaces/{id}/hitl/{request_id}/answer` | 保存/校验答案后调用 HITL `resume` |
| `POST /skill-builder/workspaces/{id}/continue` | 失败后继续，保留输出并调用 `reconcile` |
| `POST /skill-builder/workspaces/{id}/retry` | 失败后重试，reset 后重新 `build` |
| `POST /skill-builder/workspaces/{id}/validate` | 加载并调用 `validate` |
| `POST /skill-builder/workspaces/{id}/repair` | 只处理已确认机械可修复问题 |
| `POST /skill-builder/workspaces/{id}/turns` | 调用 `run_turn` |
| `GET /skill-builder/workspaces/{id}/files` | 列出允许检查的生成文件 |
| `GET /skill-builder/workspaces/{id}/files/{path}` | 读取一个授权的生成文件 |
| `PUT /skill-builder/workspaces/{id}/files/{path}` | 在单写锁内保存文件并失效旧 Receipt |
| `GET /skill-builder/workspaces/{id}/export` | 构造归档后由宿主返回/存储 |
| `DELETE /skill-builder/workspaces/{id}/active-task` | 取消并等待后台 task |

这些只是建议路由，本包不提供 HTTP 服务。

## 验证与编辑

### 重新验收

```python
execution = await client.load(workspace_id)
if execution is None:
    raise KeyError(workspace_id)

execution = await client.validate(
    execution.input,
    hitl_confirmations=execution.hitl_confirmations,
)
```

### 对话式编辑

对话式编辑由 Core 执行模型驱动的受控写入、事务检查和回滚。宿主负责对话 UI、消息接口、任务锁和进度展示：

```python
from skill_builder import SkillBuilderTurnRequest

execution = await client.run_turn(
    workspace_id,
    SkillBuilderTurnRequest(
        message="根据已有材料更新输出模板。",
        requested_action="edit",
    ),
)
```

### 文件编辑器

文件浏览器、代码/Markdown 编辑器和文件读写接口由宿主实现。Skill Builder 不绑定 Monaco、CodeMirror 或其他前端编辑器。

宿主文件 API 必须：

- 只允许访问当前 workspace 的 `generated-skill/`；
- 使用安全相对路径并拒绝路径穿越、保留目录和不可信软链接；
- 在同 workspace 单写锁内完成读取、保存和 Receipt 失效；
- 使用原子写入，限制文件类型、单文件大小和总包大小；
- 不允许编辑 `validation/`、`.skill-builder/`、`workspace/` 或 `playwright/` 伪造验收证据；
- 保存后重新调用 `present()`，在用户请求验收时调用 `validate()`。

## 状态与交付

```python
view = client.present(execution)
```

宿主使用 `workspace_status`、`validation_status`、`summary`、`blockers`、`failure` 和 artifact 信息构造页面及操作策略。完整映射见[状态与宿主动作](status-and-actions.md)。

- `ready`：当前 artifact 有有效验收 receipt；
- `needs_review`：允许检查和宿主导出，宿主应展示未确认范围；
- `failed`：使用结构化 `failure.code/category/retryable/repairable`，不要解析错误文本。

宿主根据最终状态、验收结果、artifact 是否存在和自身权限策略决定是否提供导出。推荐使用 Core 的安全通用打包助手：

```python
archive = client.build_export_archive(execution)
target.write_bytes(archive.content)
```

`build_export_archive()` 负责合法草稿、PackageRevision、路径白名单、软链接和 `SKILL.md` 校验，返回的字节可写入本地、对象存储或下载响应。该助手不是强制导出入口。

宿主可以实现自己的归档格式，但只能读取 `generated-skill/` 中允许交付的文件，并自行承担路径穿越、软链接、保留目录、文件大小、`SKILL.md` 和包结构校验。不得把 `validation/`、`.skill-builder/`、`workspace/` 或 `playwright/` 放入 Skill 包。

对象存储、用户下载、审批和外部发布均由宿主完成。Core 最终状态不能替代宿主的授权、审核或发布策略。

### 可选兼容 API

当前版本仍保留 `delivery_decision`、`publishable`、`PUBLISH` action 和 `build_publish_archive()`，用于已有宿主的兼容接线。新宿主不需要存储、展示或依赖这些字段，也不需要调用 `build_publish_archive()`。该方法只构造特定插件目录格式，不执行外部发布。

使用 `execution.artifact_sha256` 标识 Skill 内容。ZIP 元数据包含构造时间，因此稍后重建 ZIP 时 archive SHA 可能不同，但 artifact 身份未变。

## 文件保存后的 Receipt 处理

宿主通过文件编辑器或其他方式绕过 `run_turn` 修改 `generated-skill/` 后，必须在释放 workspace 写锁前调用：

```python
execution = await client.invalidate_receipt(workspace_id)
```

重新渲染状态，并在用户请求验收时执行 `validate`。Receipt 重新建立前不得继续显示旧 `ready` 或开放发布。

## Sandbox 与录屏

`SKILL_BUILDER_SANDBOX_ENABLED=true` 时，Agent Core 子进程创建 Jiuwenbox workspace。最终 Acceptance 使用独立短生命周期 `JiuwenboxExecutionPort`。密钥只通过进程环境继承，不写入 worker 文件。

录屏是生成前的材料采集流程。宿主调用 `skill_builder.recording`，登记最终 `web-recording.md`，然后再调用 `build/reconcile`。详细说明见[录屏接入](recording-integration.md)。录屏成功不能映射成浏览器 Acceptance 成功。

## 宿主停止与 workspace 删除

删除 workspace 或停止宿主 worker 前：

- 取消并等待活动 Skill Builder task；
- 停止同一进程内的活动录屏；
- 释放 workspace lease；
- 清理该 workspace 的 Jiuwenbox session；
- 除非用户明确永久删除，否则保留 inputs 和最后有效状态；
- Core 清理不得删除宿主对象存储。
