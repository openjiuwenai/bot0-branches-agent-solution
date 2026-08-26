# 状态与宿主动作

## 唯一事实源

宿主必须通过以下投影展示状态：

```python
view = client.present(execution)
```

禁止根据事件文本、目录是否存在、worker 退出码或异常字符串推断生命周期。事件表示进度；持久状态与当前 artifact receipt 决定交付结果。

投影包含四个相关但不同的维度：

- `workspace_status`：生命周期当前位置；
- `validation_status`：当前候选的 Acceptance 结果；
- `delivery_decision`：当前候选是否可交付；
- `publishable`：Core 对当前 artifact 的本地发布资格判断。

## Workspace 状态

| `workspace_status` | 含义 | 宿主行为 |
|---|---|---|
| `queued` | 已接收，尚未推进 | 显示排队；拒绝同 workspace 的另一个写任务 |
| `running` | Core 或 Agent Core 正在执行 | 展示事件；允许宿主取消；禁用编辑和发布 |
| `waiting_for_user` | 缺少真实业务决策 | 展示 `pending_request.request`，回答后调用 HITL `resume` |
| `draft_ready` | 已有候选，但没有当前可发布 receipt | 按 `available_actions` 提供检查、编辑、验证和草稿导出 |
| `needs_review` | 可检查/导出，但人工或外部边界阻断自动发布 | 展示 blocker 和审核范围，禁止自动发布 |
| `ready` | Acceptance 已绑定当前 artifact | 允许导出；发布仍受宿主审批约束 |
| `failed` | 当前操作未形成可接受结果 | 展示结构化 failure，以及失败后继续/重试入口 |

`waiting_for_user` 不是失败。用户决策期间没有运行中的 Agent Core 子进程。

## 验收状态（Validation Status）

| `validation_status` | 含义 | 建议展示 |
|---|---|---|
| `not_run` | 当前候选尚未验收 | 中性“未验证” |
| `pass` | 无阻断 finding | 成功 |
| `warn` | 只有非阻断 finding | 成功并展示警告 |
| `fail` | finding 导致 Skill 不可用或验收不成立 | 阻断错误 |

warning 不是生命周期状态。当 warning 不影响已验证可用性时，`workspace_status=ready` 与 `validation_status=warn` 可以同时出现。

宿主不能把所有 warning 都升级为 `needs_review`，也不能把阻断 finding 降为普通警告。

## 交付决策（Delivery Decision）

| `delivery_decision` | 含义 | 自动发布 |
|---|---|---|
| `draft_ready` | 仅达到草稿边界 | 禁止 |
| `ready` | 当前候选交付判断有效 | 仅当 `publishable=True` 且宿主审批允许 |
| `needs_review` | 仍需人工/外部确认 | 禁止 |
| `blocked` | 交付条件不成立 | 禁止 |
| `failed` | 运行失败 | 禁止 |

Core 的 `publishable` 计算为：

```text
delivery_decision == ready AND 当前 artifact receipt 有效
```

它不会执行发布，也不会绕过宿主审批、租户规则、恶意软件/许可证扫描或市场审核。

`delivery_decision` 由 Core 根据 Acceptance、blocker 和当前 artifact Receipt 计算，不是宿主配置项。宿主可以采用更严格的策略，例如要求 `ready` 仍需审批；宿主不得把 `needs_review`、`blocked` 或 `failed` 放宽为自动发布。

## 导出与发布

普通导出用于下载、检查或人工审核 Skill 草稿，不要求 `delivery_decision=ready`。宿主应根据 `view.available_actions` 是否包含 `export` 展示入口，并调用：

```python
archive = client.build_export_archive(execution)
```

Core 负责归档路径白名单、软链接、`SKILL.md`、PackageRevision 和包结构校验；宿主负责下载响应、对象存储、访问控制和保留期限。

| 状态 | 普通导出 | 自动发布 |
|---|---|---|
| `draft_ready` | `available_actions` 允许时可导出 | 禁止 |
| `needs_review` | 可导出供人工审核 | 禁止 |
| `ready` | 可导出 | 还需 `publishable=True` 和宿主审批 |
| `failed` | 仅在仍有合法候选且 `available_actions` 允许时可导出 | 禁止 |
| `waiting_for_user` / `running` | 禁止 | 禁止 |

人工审核发现需要补充证据或修改文件时，应更新候选并重新执行 `validate`。宿主治理系统执行的人工特批不会把 Core 状态改成 `ready`，必须单独记录审批人、原因和 artifact hash，且不得作为自动发布路径。

## 外部验证未运行

按外部验证在 Skill 承诺中的作用分类：

1. 包结构、核心逻辑和安全降级路径已经验证，API/浏览器真实证据只是额外环境信息：可以保留 `ready + warn`，但必须展示未验证范围。
2. Skill 核心承诺就是该 API/浏览器操作，且没有可信替代证据：必须是 `needs_review`，允许导出人工检查，但禁止自动发布。

浏览器录屏只是输入证据，不是生成 Skill 的浏览器真实性验收。

## 可用动作（Available Actions）

只渲染 Core 返回的 `view.available_actions`。当前实现规则：

| 条件 | 返回动作 |
|---|---|
| `waiting_for_user` | 仅 `resume` |
| `running` | 无 |
| 其他状态 | `inspect` |
| 当前 artifact 存在 | 额外 `edit`、`export`、`validate` |
| `failed` | 额外 `retry` |
| `publishable=True` | 额外 `publish` |

`ExecutionAction.REPAIR` 是公共枚举，但当前 `available_actions` 不会自动返回它。诊断页只有确认 finding 属于机械可修复问题后才能提供 Repair，并调用：

```python
repaired = await client.repair(execution, instruction=instruction)
```

业务歧义、能力方向、fixture 语义和缺失外部证明不能提供 Repair。

## HITL、失败后继续和重试

三种入口不能混用：

- HITL `resume`：只用于 `waiting_for_user`，需要 pending token 和用户答案；
- 失败后继续：保留输出，使用 `kind="resume"` 恢复消息调用 `reconcile`；
- 失败后重试：使用 `kind="retry"` 消息，调用 `reset_generated_outputs` 后重新 `build`，保留 `inputs/`。

没有公共 `client.retry()`，因为 reset 范围涉及宿主 workspace 和资产存储。两种失败恢复都不能重放上一个 Agent Core request。

## HITL 映射

```python
pending = execution.pending_request
form = pending.request
resume_token = pending.resume_token
```

宿主必须：

- 原样展示表单，不另造业务选择；
- 绑定已登录用户和 workspace；
- 防止重复提交；
- 调用 `client.resume(workspace_id, resume_token=..., answer=...)`；
- 答案被拒为不完整时保留 pending request。

## Failure 映射

使用 `execution.failure` 的结构化字段：

```text
code
category
retryable
repairable
user_message
developer_message
details
```

用户侧显示 `user_message`；`developer_message/details` 只进入受限诊断。禁止通过 traceback 或本地化文本判断是否可重试/修复。

## 手工编辑与 Receipt 失效

宿主或用户绕过 `run_turn` 修改 `generated-skill/` 后调用：

```python
execution = await client.invalidate_receipt(workspace_id)
```

重新展示投影并执行 `validate`，不得沿用旧 `ready`。使用 `execution.artifact_sha256` 作为 Skill 内容身份；ZIP 构造元数据可能改变 archive SHA，但不改变 artifact 身份。

## 建议宿主 DTO

```python
view = client.present(execution)
payload = {
    "workspaceStatus": view.workspace_status,
    "draftStatus": view.draft_status,
    "validationStatus": view.validation_status,
    "deliveryDecision": view.delivery_decision.value,
    "publishable": view.publishable,
    "summary": view.summary,
    "blockers": list(view.blockers),
    "availableActions": [item.value for item in view.available_actions],
    "acceptance": view.acceptance,
    "artifactFiles": list(view.artifact_files),
    "failure": execution.failure.to_dict() if execution.failure else None,
    "pendingRequest": (
        execution.pending_request.to_dict()
        if execution.pending_request is not None
        else None
    ),
}
```

宿主可以重命名 JSON 字段，但不能重新计算业务含义。
