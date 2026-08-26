# 状态与宿主动作

## 唯一事实源

宿主必须通过以下投影展示状态：

```python
view = client.present(execution)
```

禁止根据事件文本、目录是否存在、worker 退出码或异常字符串推断生命周期。事件表示进度；持久状态与当前 artifact receipt 决定交付结果。

宿主接入主要使用以下事实：

- `workspace_status`：生命周期当前位置；
- `validation_status`：当前候选的 Acceptance 结果；
- `blockers` 和 `failure`：未完成或未通过的结构化原因；
- `artifact_sha256`、`artifact_files` 和 `acceptance`：当前制品及其验收依据。

## Workspace 状态

| `workspace_status` | 含义 | 宿主行为 |
|---|---|---|
| `queued` | 已接收，尚未推进 | 显示排队；拒绝同 workspace 的另一个写任务 |
| `running` | Core 或 Agent Core 正在执行 | 展示事件；允许宿主取消；禁用编辑和发布 |
| `waiting_for_user` | 缺少真实业务决策 | 展示 `pending_request.request`，回答后调用 HITL `resume` |
| `draft_ready` | 已有候选，但没有当前 Acceptance Receipt | 提供检查、编辑、验证；导出由宿主策略决定 |
| `needs_review` | 可检查/导出，但仍有人工或外部边界 | 展示 blocker 和审核范围，由宿主进入审核流程 |
| `ready` | Acceptance 已绑定当前 artifact | 允许宿主按自身策略导出、审批或发布 |
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

## 状态与宿主策略

Core 固定 `ready`、`needs_review` 和 `failed` 的验收含义，宿主不能把一个状态改写成另一个状态。下载、导出、人工审批和外部发布属于宿主策略，可以因产品、租户和组织治理要求而不同。

Core 不执行外部发布，也不替代宿主的鉴权、恶意软件/许可证扫描、审批或市场规则。

## 导出与发布

普通导出用于下载、检查或人工审核 Skill 草稿。宿主根据最终状态、验收结果、artifact 是否存在和自身权限策略决定是否展示导出入口。

推荐使用 Core 的安全通用打包助手：

```python
archive = client.build_export_archive(execution)
```

该助手负责归档路径白名单、软链接、`SKILL.md`、PackageRevision 和包结构校验；宿主负责下载响应、对象存储、访问控制和保留期限。宿主也可以实现自己的归档格式，但必须执行等价的路径和包安全校验。

| 状态 | 通用处理建议 |
|---|---|
| `draft_ready` | 可按宿主策略导出草稿，不能展示为已验收 |
| `needs_review` | 可导出供人工审核，必须保留未确认范围 |
| `ready` | 可进入宿主的导出、审批或发布流程 |
| `failed` | 仅在仍有合法候选时允许诊断性导出 |
| `waiting_for_user` / `running` | 不应导出正在变化的 workspace |

人工审核发现需要补充证据或修改文件时，应更新候选并重新执行 `validate`。宿主治理系统执行人工特批时，Core 状态仍保持 `needs_review`；宿主应单独记录审批人、原因和 artifact hash。

## 外部验证未运行

按外部验证在 Skill 承诺中的作用分类：

1. 包结构、核心逻辑和安全降级路径已经验证，API/浏览器真实证据只是额外环境信息：可以保留 `ready + warn`，但必须展示未验证范围。
2. Skill 核心承诺就是该 API/浏览器操作，且没有可信替代证据：必须是 `needs_review`，允许导出人工检查，由宿主执行后续审核和发布策略。

浏览器录屏只是输入证据，不是生成 Skill 的浏览器真实性验收。

## 可用动作（Available Actions）

`view.available_actions` 是当前 Core 操作的可用性提示，不是宿主的下载、审批或发布策略。宿主可以将其用于按钮状态，也可以结合最终状态和自身权限规则构造界面。

当前实现规则：

| 条件 | 返回动作 |
|---|---|
| `waiting_for_user` | 仅 `resume` |
| `running` | 无 |
| 其他状态 | `inspect` |
| 当前 artifact 存在 | 额外 `edit`、`export`、`validate` |
| `failed` | 额外 `retry` |

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

宿主可以重命名 JSON 字段和定义导出/审批/发布策略，但不能重写 Core 返回的生命周期与验收状态。

## 可选兼容字段

当前版本仍可能返回 `delivery_decision`、`publishable` 和 `publish` action，以兼容已有宿主。新宿主不需要存储、展示或依赖这些字段；它们不参与普通导出，也不会执行外部发布。
