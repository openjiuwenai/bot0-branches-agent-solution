# cancel_task 工具调用特性

## 特性概述

`cancel_task` 工具用于取消当前正在执行的任务。调用后，CancelRail 会触发取消流程：将所有未完成的 Todo 标记为 CANCELLED，清理执行状态，并向用户返回取消确认话术。

**重要原则**：调用 `cancel_task` 之前，**必须先通过 `ask_user` 向用户确认取消意图**，获得用户明确确认后才能调用。不能不确认就直接取消。

## 工具参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `reason` | string | 否 | 取消原因，会记录在日志和任务状态中 |

## 取消流程

```
用户表达取消意图
      │
      ▼
Agent 调用 ask_user 确认 ────────────┐
      │                              │
      │ 用户确认取消                  │ 用户说"不，继续"
      ▼                              ▼
Agent 调用 cancel_task          继续执行原任务
      │
      ▼
CancelRail 拦截处理
      │
      ├─ 将所有 PENDING/IN_PROGRESS Todo 标记为 CANCELLED
      ├─ 记录取消原因
      ├─ 清理中断状态
      └─ 返回 task_cancelled 话术
      │
      ▼
向用户推送取消完成消息
```

## 配置方式

### 取消话术配置

在 scriptconfig.yaml 中配置取消相关话术：

```yaml
scriptconfig:
  general_scripts:
    task_cancelled: "好的，已为您取消当前操作。如需其他帮助，请随时告诉我。"
    cancel_confirm: "确认要取消当前操作吗？"
```

场景和 Skill 可以覆盖这些话术。

### 调用次数限制

在 actrule.yaml 中可以配置 cancel_task 的使用（通常不限制次数，但也可以通过 tool_limits 控制）。

## 使用示例

### 标准取消流程（推荐）

**Step 1：用户说"算了，不买了"**

**Step 2：Agent 调用 ask_user 确认**

```json
{
  "name": "ask_user",
  "arguments": {
    "question": "您是要取消当前的理财购买流程吗？"
  }
}
```

**Step 3：用户回复"是的，取消"**

**Step 4：Agent 调用 cancel_task**

```json
{
  "name": "cancel_task",
  "arguments": {
    "reason": "用户主动取消购买流程"
  }
}
```

**Step 5：系统返回取消话术**

用户收到："好的，已为您取消当前操作。如需其他帮助，请随时告诉我。"

### 错误用法（不要这样做）

❌ **不确认直接取消**：
```json
// 错误：用户说"不买了"，直接调用cancel_task，没有确认
{
  "name": "cancel_task",
  "arguments": {
    "reason": "用户不想买了"
  }
}
```

✅ **正确做法**：先 ask_user 确认，再 cancel_task。

### 使用场景示例

| 用户输入 | 处理方式 |
|----------|----------|
| "算了"、"不弄了"、"取消" | 先 ask_user 确认取消意图 |
| "我不想买了" | 先确认是取消整个流程还是换产品 |
| "返回上一步" | 不是取消任务，是回退，通过 todo_modify 处理 |
| "退出" | 确认是否取消当前任务 |

## 取消后的状态

调用 cancel_task 后：

| 状态项 | 值 |
|--------|-----|
| 所有未完成 Todo | CANCELLED |
| 当前会话 | 保持活跃，可接收新请求 |
| Redis 中的 Checkpoint | 清理中断状态 |
| ToolDataChannel | 保留（会话内数据仍在） |
| Versatile 中断 | 发送取消信号（如果有正在进行的工作流） |
| MCP 执行 | 尝试中断（如果脚本支持） |

如果 `enable_task_loop: true`（默认开启），取消后用户发送新请求，Agent 会重新规划新任务。

## 注意事项

1. **必须先确认**：这是硬性规则。取消操作是不可逆的，必须获得用户明确同意。

2. **与 ask_user 的顺序**：ask_user 是"问"，cancel_task 是"做"。先问后做，不能颠倒。

3. **取消话术要友好**：取消后不要说"任务失败"、"执行错误"等负面表述，使用 `task_cancelled` 中的友好话术。

4. **不要滥用取消**：如果用户只是想修改某个参数（如换个产品、改个金额），不需要取消整个任务，应该通过 todo_modify 调整路径或追问新参数。

5. **取消后的重新规划**：任务取消后，如果用户有新的请求，Agent 会重新走 todo_create 流程创建新任务，不会继续之前被取消的任务。

6. **日志记录**：取消原因会记录在日志中，便于问题排查。建议 reason 字段填写有意义的原因（如"用户主动取消"、"用户选择放弃"），而不是空字符串。

7. **并发问题**：cancel_task 调用后，CancelRail 会尽快处理取消，但已经在执行中的工具调用（如正在进行的 Versatile 调用）可能需要等待其返回或超时才能完全清理。

## 代码参考

- [CancelTaskTool.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/CancelTaskTool.java)
- [CancelRail.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/CancelRail.java)
- [scriptconfig.yaml 取消话术](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/governance/scriptconfig.yaml#L21-L22)
