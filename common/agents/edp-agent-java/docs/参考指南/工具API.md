# 工具 API 参考

本文档详细描述 EDPAgent 内置业务工具的调用接口和参数规范。这些工具通过 LLM 的 Tool Calling（Function Calling）机制被调用，LLM 根据用户意图和当前状态自动选择并调用合适的工具。

## 工具调用概述

### 调用方式

工具通过 A2A 协议在 ReAct 循环中被 LLM 调用。开发者不需要直接调用这些工具，但需要理解工具的参数规范以便正确配置 Governance 和 Skill。

### 工具列表

| 工具名 | 类型 | 功能 | 触发中断 |
|--------|------|------|----------|
| [`call_versatile`](#call_versatile) | 业务工具 | 委托 Versatile 执行工作流 | 是（需用户交互时） |
| [`call_mcp`](#call_mcp) | 业务工具 | 调用 MCP 沙箱执行脚本 | 是（等待执行） |
| [`ask_user`](#ask_user) | 业务工具 | 向用户追问缺失信息 | 是（等待用户回复） |
| [`cancel_task`](#cancel_task) | 业务工具 | 取消当前任务 | 否 |

框架原生工具（todo_create/todo_modify/todo_list/todo_get/skill_tool/bash）由 DeepAgent 框架提供，本文档不详细描述。

---

## call_versatile

### 功能

声明 Versatile Agent 委托意图，实际 HTTP 调用、中断恢复和结果归一化由 VersatileInterruptRail 负责处理。

### 工具描述

> 声明 Versatile Agent 委托意图，级联恢复和结果归一化由 VersatileInterruptRail 负责。

### 参数 Schema

```json
{
  "type": "object",
  "properties": {
    "query_description": {
      "type": "string",
      "description": "委托查询描述"
    },
    "query_intent": {
      "type": "string",
      "description": "委托查询意图"
    },
    "query_response_analysis_scripts": {
      "type": "array",
      "description": "响应归一化脚本列表",
      "items": {
        "type": "string"
      }
    },
    "response_template_keys": {
      "type": "array",
      "description": "响应话术模板 key 列表",
      "items": {
        "type": "string"
      }
    },
    "notice_context": {
      "type": "object",
      "description": "非中断话术上下文"
    },
    "input_key": {
      "type": "string",
      "description": "从 ToolDataChannel 读取前序数据的 key"
    }
  },
  "required": ["query_description", "query_intent"]
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query_description` | string | 是 | 本次委托的查询描述，简要说明要做什么。例如："根据用户选择执行理财产品购买"。 |
| `query_intent` | string | 是 | 查询意图字符串，用于 Versatile 工作流路由。例如：`"product_purchase"`、`"balance_query"`。 |
| `query_response_analysis_scripts` | string[] | 否 | 响应归一化 Python 脚本列表，对 Versatile 返回结果进行标准化处理。 |
| `response_template_keys` | string[] | 否 | 话术模板 key 列表，从 SKILL.yaml 或 scriptconfig.yaml 中取对应话术展示给用户。 |
| `notice_context` | object | 否 | 非中断话术上下文，工作流执行过程中推送给用户的状态消息。可包含 `message` 字段。 |
| `input_key` | string | 否 | 从 ToolDataChannel 读取前序工具输出数据的 key，实现工具间数据直通。 |

### 返回值

工具立即返回委托意图声明，**不返回最终结果**（实际结果由 Rail 异步处理后通过 SSE 推送）：

```json
{
  "tool": "call_versatile",
  "status": "delegate_intent",
  "input": {
    "query_description": "...",
    "query_intent": "..."
  }
}
```

### 调用示例

**示例1：简单工作流调用**

```json
{
  "name": "call_versatile",
  "arguments": {
    "query_description": "查询用户银行账户余额",
    "query_intent": "balance_query"
  }
}
```

**示例2：带话术模板的数据驱动调用**

```json
{
  "name": "call_versatile",
  "arguments": {
    "query_description": "执行理财产品购买操作",
    "query_intent": "product_purchase",
    "input_key": "selected_product",
    "response_template_keys": ["purchase_success", "purchase_failed"],
    "notice_context": {
      "message": "正在为您办理购买手续，请稍候..."
    }
  }
}
```

**示例3：带响应归一化脚本**

```json
{
  "name": "call_versatile",
  "arguments": {
    "query_description": "执行转账操作",
    "query_intent": "transfer",
    "query_response_analysis_scripts": [
      "def normalize(result):\n    if result.get('need_confirm'):\n        return {'need_user_input': True, 'question': '确认转账?'}\n    return result"
    ],
    "response_template_keys": ["transfer_success"]
  }
}
```

### 配置依赖

使用 call_versatile 需要：
1. actrule.yaml 的 `allowed_tools` 包含 `call_versatile`
2. 正确配置 `EDP_AGENT_VERSATILE_URL`（REST模式）或 `EDP_AGENT_VERSATILE_A2A_URL`（A2A模式）
3. 可选配置 `EDP_AGENT_VERSATILE_TIMEOUT`

---

## call_mcp

### 功能

声明 MCP 沙箱脚本调用意图，实际执行和数据通道写入由 McpInterruptRail 负责处理。

### 工具描述

> 声明 MCP 沙箱脚本调用意图，执行和数据通道写入由 McpInterruptRail 负责。

### 参数 Schema

```json
{
  "type": "object",
  "properties": {
    "script_command": {
      "type": "string",
      "description": "待执行的 MCP 脚本或命令"
    },
    "script_params": {
      "type": "object",
      "description": "脚本入参"
    }
  },
  "required": ["script_command"]
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `script_command` | string | 是 | 要执行的 MCP 脚本命令名/脚本标识。对应 Skill scripts/ 目录下的 Python 脚本入口。 |
| `script_params` | object | 否 | 脚本执行所需的入参，key-value 格式，传递给 Python 脚本。 |

### 返回值

工具立即返回 MCP 意图声明：

```json
{
  "tool": "call_mcp",
  "status": "mcp_intent",
  "input": {
    "script_command": "...",
    "script_params": {}
  }
}
```

执行完成后：
1. 结果自动写入 ToolDataChannel，键名格式为 `mcp_result_{script_command}`
2. 结果通过 SSE 事件推送给前端
3. 结果作为工具最终返回值给 LLM 观察

### 调用示例

**示例1：产品推荐查询**

```json
{
  "name": "call_mcp",
  "arguments": {
    "script_command": "recommend_products",
    "script_params": {
      "risk_level": "R2",
      "amount": 50000
    }
  }
}
```

**示例2：数据归一化处理**

```json
{
  "name": "call_mcp",
  "arguments": {
    "script_command": "normalize_product_data",
    "script_params": {
      "format": "simple",
      "max_count": 5
    }
  }
}
```

**示例3：无参数脚本**

```json
{
  "name": "call_mcp",
  "arguments": {
    "script_command": "get_hot_products"
  }
}
```

### 脚本执行结果格式

Python 脚本应返回 JSON 可序列化的 dict：

```python
# 成功返回
{
    "success": True,
    "data": ...,  # 业务数据
    "message": "..."  # 可选消息
}

# 失败返回
{
    "success": False,
    "error": "错误原因",
    "error_script_key": "error_prompt_key"  # 可选，对应话术中的key
}
```

### 配置依赖

使用 call_mcp 需要：
1. actrule.yaml 的 `allowed_tools` 包含 `call_mcp`
2. 配置 `EDP_MCP_MASTER_URL`（可选配置备URL）
3. MCP 服务正常运行且安装了所需 Python 依赖

---

## ask_user

### 功能

向用户追问缺失信息或确认敏感操作。调用后触发中断，Agent 暂停执行等待用户回复。这是人机协同的核心工具。

### 工具描述

> 向用户追问缺失信息，并支持话术模板参数。

### 参数 Schema

```json
{
  "type": "object",
  "properties": {
    "question": {
      "type": "string",
      "description": "需要向用户追问的问题"
    },
    "response_template_keys": {
      "type": "array",
      "description": "响应话术模板 key 列表",
      "items": {
        "type": "string"
      }
    },
    "response_template_status": {
      "type": "string",
      "description": "响应话术状态"
    },
    "response_template_vars": {
      "type": "object",
      "description": "响应话术变量"
    },
    "missing_fields": {
      "type": "array",
      "description": "缺失字段列表",
      "items": {
        "type": "string"
      }
    }
  },
  "required": ["question"]
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `question` | string | 是 | 向用户展示的追问问题文本，应清晰具体。例如："请问您要购买多少金额呢？" |
| `response_template_keys` | string[] | 否 | 响应话术模板 key 列表，从 SKILL.yaml 中取对应话术。 |
| `response_template_status` | string | 否 | 响应话术状态标识，用于前端渲染不同的UI样式。 |
| `response_template_vars` | object | 否 | 话术模板变量，用于替换话术中的 `{placeholder}` 占位符。 |
| `missing_fields` | string[] | 否 | 明确标注缺失的字段名列表，便于前端渲染表单或高亮提示。如 `["amount", "product_id"]`。 |

### 返回值

ask_user 抛出 ToolInterruptException 触发中断，不立即返回。用户回复后返回：

```json
{
  "answer": "用户回复的文本内容",
  "interrupt_id": "ask_user_interrupt"
}
```

### 必须调用 ask_user 的场景

根据 planrule.yaml 的 base_protocol：
1. **关键参数缺失**：执行业务操作所需参数不全
2. **敏感操作确认**：涉及资金、风险、不可逆操作
3. **用户输入有歧义**：意图不明确或有多种理解
4. **取消意图确认**：用户说"算了"、"不买了"时

### 禁止行为

1. ❌ 禁止在 final_answer 中提问（必须通过 ask_user）
2. ❌ 禁止猜测用户未提供的参数
3. ❌ 禁止反复追问相同问题
4. ❌ 禁止调用 cancel_task 前不先 ask_user 确认

### 调用示例

**示例1：追问缺失参数**

```json
{
  "name": "ask_user",
  "arguments": {
    "question": "好的，请问您要购买多少金额呢？（最低1万元，以万元为单位）",
    "missing_fields": ["amount"]
  }
}
```

**示例2：敏感操作确认**

```json
{
  "name": "ask_user",
  "arguments": {
    "question": "您确认要购买5万元的「稳健理财A」产品吗？该产品期限90天，年化收益率3.2%。",
    "missing_fields": ["confirm"],
    "response_template_keys": ["purchase_confirm_prompt"]
  }
}
```

**示例3：使用话术变量**

```json
{
  "name": "ask_user",
  "arguments": {
    "question": "您选择了{product_name}，预期年化{rate}，期限{term}，确认购买吗？",
    "response_template_vars": {
      "product_name": "稳健理财A",
      "rate": "3.2%",
      "term": "90天"
    },
    "missing_fields": ["confirm"]
  }
}
```

**示例4：取消确认**

```json
{
  "name": "ask_user",
  "arguments": {
    "question": "您是要取消当前的理财购买流程吗？",
    "missing_fields": ["cancel_confirm"]
  }
}
```

用户确认后才能调用 cancel_task。

---

## cancel_task

### 功能

取消当前任务，触发取消清理流程。将所有未完成 Todo 标记为 CANCELLED，返回取消话术。

### 工具描述

> 取消当前任务并触发取消清理话术。使用前必须先通过 ask_user 向用户确认取消意图，获得用户明确确认后再调用此工具。

### 参数 Schema

```json
{
  "type": "object",
  "properties": {
    "reason": {
      "type": "string",
      "description": "取消原因"
    }
  },
  "required": []
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `reason` | string | 否 | 取消原因，记录在日志中，便于问题排查。建议填写有意义的原因，如"用户主动取消购买流程"。 |

### 返回值

```json
{
  "tool": "cancel_task",
  "status": "cancelled",
  "input": {
    "reason": "用户主动取消"
  }
}
```

取消流程由 CancelRail 处理：
1. 将所有 PENDING/IN_PROGRESS Todo 标记为 CANCELLED
2. 清理中断状态
3. 向用户推送 `task_cancelled` 话术
4. 尝试取消正在进行的 Versatile/MCP 调用

### 标准取消流程

```
用户表达取消意图（"算了"、"不买了"、"取消"）
         │
         ▼
ask_user("确认要取消当前操作吗？")
         │
    ┌────┴────┐
    │         │
用户确认    用户说继续
    │         │
    ▼         ▼
cancel_task  继续执行原任务
(reason="用户主动取消")
```

### 调用示例

**正确用法（先确认再取消）**：

Step 1 - 确认意图：
```json
{
  "name": "ask_user",
  "arguments": {
    "question": "确认要取消当前的理财购买流程吗？"
  }
}
```

Step 2 - 用户确认后执行取消：
```json
{
  "name": "cancel_task",
  "arguments": {
    "reason": "用户主动取消理财购买流程"
  }
}
```

**错误用法**：

❌ 不确认直接取消：
```json
{
  "name": "cancel_task",
  "arguments": {
    "reason": "用户说算了"
  }
}
```

### 取消后状态

| 状态项 | 值 |
|--------|-----|
| 未完成 Todo | 全部标记为 CANCELLED |
| 当前会话 | 保持活跃（enable_task_loop=true 时可接收新请求） |
| Checkpoint | 清理中断状态 |
| ToolDataChannel | 保留（会话内数据） |

enable_task_loop 为 true（默认）时，取消后用户发送新请求，Agent 会重新规划新任务。

---

## 调用约束与规约

### actrule 配置

要使用某个工具，必须在 actrule.yaml 的 `allowed_tools` 中包含该工具名：

```yaml
actrule:
  allowed_tools:
    - call_versatile
    - call_mcp
    - ask_user
    - cancel_task
    # ... 其他工具
```

### 工具调用次数限制

在 actrule.yaml 的 `tool_limits` 中配置单会话调用次数上限，防止死循环：

```yaml
actrule:
  tool_limits:
    call_versatile: 50
    call_mcp: 50
    ask_user: 50
```

### 通用调用规约

来自 planrule.yaml base_protocol：

1. **无依赖工具可并行**：同一迭代中可执行多个无依赖工具调用（如 skill_tool + todo_create）
2. **数据依赖必须分步**：有数据依赖的工具必须分步执行（如先 call_mcp 获取数据，再 call_versatile 使用数据）
3. **todo_modify 约束**：
   - 必须提供 `action` 参数（update/cancel）
   - 成功后不重复调用相同状态更新
   - 可批量更新（当前任务 COMPLETED + 下一任务 IN_PROGRESS）
   - 完成最后任务时，必须同一迭代调用 todo_modify + final_answer
4. **工具失败处理**：在 thought 中记录失败原因，决定重试或跳过，不编造数据
5. **禁止 final_answer 提问**：需要用户补充信息必须调用 ask_user，不能在最终回答中用文本提问

### 工具与 Rail 的对应关系

每个业务工具由对应的 Rail 拦截器处理实际执行：

| 工具 | 处理 Rail |
|------|-----------|
| `call_versatile` | VersatileInterruptRail |
| `call_mcp` | McpInterruptRail |
| `ask_user` | AskUserTemplateRail（中断）→ EdpaEventRail（事件推送） |
| `cancel_task` | CancelRail |
| `todo_*` | EdpaTodoRail |

## 代码参考

- [EdpaBusinessTools.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/EdpaBusinessTools.java) - 工具定义入口
- [CallVersatileTool.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/CallVersatileTool.java)
- [CallMcpTool.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/CallMcpTool.java)
- [EnhancedAskUserTool.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/EnhancedAskUserTool.java)
- [CancelTaskTool.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/CancelTaskTool.java)
- [VersatileInterruptRail.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/VersatileInterruptRail.java)
- [McpInterruptRail.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/McpInterruptRail.java)
