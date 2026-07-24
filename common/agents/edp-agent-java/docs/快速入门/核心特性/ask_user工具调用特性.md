# ask_user 工具调用特性

## 特性概述

`ask_user` 是 EDPAgent 的核心交互工具，用于在执行过程中向用户追问缺失信息、确认敏感操作或澄清歧义输入。调用 `ask_user` 会**触发中断**——Agent 暂停执行，等待用户回复后再继续。

这是 Agent "人机协同"的关键机制：关键决策点必须让人参与，而不是 Agent 自行猜测。

## 工具参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `question` | string | 是 | 需要向用户追问的问题文本，会直接展示给用户 |
| `response_template_keys` | array | 否 | 响应话术模板 key 列表，从 SKILL.yaml/scriptconfig.yaml 中取话术 |
| `response_template_status` | string | 否 | 响应话术状态标识 |
| `response_template_vars` | object | 否 | 响应话术变量，用于模板中的占位符替换 |
| `missing_fields` | array | 否 | 明确标注缺失的字段列表，帮助前端渲染表单 |

## 中断机制

调用 `ask_user` 时的执行流程：

1. Agent 决定需要追问用户，调用 `ask_user` 工具
2. EnhancedAskUserTool 抛出 `ToolInterruptException`
3. 框架捕获中断，保存当前会话状态（Checkpoint）到 Redis
4. 通过 SSE 事件流向用户推送追问消息
5. Agent 暂停执行，等待用户回复
6. 用户回复后，框架恢复会话状态，将用户回答作为工具结果返回
7. Agent 根据回答继续执行

```
Agent执行 → 发现信息缺失 → ask_user调用 → 中断异常
     ↑                                         │
     │                                         ▼
     │                              SSE推送追问消息给用户
     │                                         │
     │                              用户输入回复内容
     │                                         │
     └──────── 恢复执行，携带用户回答 ───────────┘
```

## 必须调用 ask_user 的场景

根据 planrule.yaml 中的行为约束，以下场景**必须**调用 `ask_user`：

### 1. 关键参数缺失

执行业务操作所需的参数不全时，必须追问：
- 购买理财产品但用户未说金额
- 转账但未提供收款账号
- 查询余额但未指定账户

### 2. 敏感操作确认

涉及资金、风险、不可逆操作时，必须二次确认：
- "确认购买5万元的稳健理财A吗？"
- "确认向张三转账1000元吗？"
- "确认取消当前交易吗？"

### 3. 用户输入有歧义

用户意图不明确或有多种理解时：
- 用户说"买那个"，但没有指明是哪个产品
- 用户说"查一下"，但没有说查什么
- 用户指令模糊，可能导致错误操作

### 4. 用户要求取消

当用户表达取消意图时，应先通过 ask_user 确认：
- 用户说"算了"、"不买了"、"取消"
- 需要确认是取消当前步骤还是整个任务

## 禁止行为

1. **禁止在 final_answer 中提问**：如果需要用户补充信息，必须调用 `ask_user` 工具，不能在最终回答中用文本提问。只有所有信息齐全、任务全部完成时，才能输出 final_answer。

2. **禁止猜测参数**：不得为了完成任务而编造或猜测用户未提供的参数（如默认金额、默认产品）。

3. **禁止反复追问相同问题**：如果用户已明确拒绝提供某信息，应根据业务规则处理（如取消任务、提示无法办理），而不是重复追问。

## 使用示例

### 示例1：追问缺失参数（购买金额）

```json
{
  "name": "ask_user",
  "arguments": {
    "question": "好的，请问您要购买多少金额呢？",
    "missing_fields": ["amount"]
  }
}
```

### 示例2：敏感操作确认

```json
{
  "name": "ask_user",
  "arguments": {
    "question": "您确认要购买5万元的「稳健理财A」产品吗？该产品期限为90天，年化收益率3.2%。",
    "missing_fields": ["confirm"],
    "response_template_keys": ["purchase_confirm_prompt"]
  }
}
```

### 示例3：使用话术模板

SKILL.yaml 中定义话术：
```yaml
scripts:
  amount_prompt: "请告诉我您想购买的金额（最低1万元，以万元为单位递增）。"
```

工具调用：
```json
{
  "name": "ask_user",
  "arguments": {
    "question": "请告诉我您想购买的金额",
    "response_template_keys": ["amount_prompt"]
  }
}
```

### 示例4：取消确认

```json
{
  "name": "ask_user",
  "arguments": {
    "question": "您是要取消当前的理财购买流程吗？"
  }
}
```

用户确认后，再调用 `cancel_task`。

## 中断恢复

用户回复后，框架自动将回复内容作为 ask_user 工具的返回结果，格式如下：

```json
{
  "answer": "用户回复的文本内容",
  "interrupt_id": "ask_user_interrupt"
}
```

Agent 应从 tool result 中读取 `answer` 字段获取用户输入。

## 话术模板配置

scriptconfig.yaml 中预定义了中断相关的通用话术：

```yaml
scriptconfig:
  general_scripts:
    interrupt_start: "需要您确认以下信息"
    cancel_confirm: "确认要取消当前操作吗？"
```

场景和 Skill 可以定义自己的话术模板覆盖默认值。

## 注意事项

1. **中断状态持久化**：中断时会话状态保存到 Redis，Checkpoint TTL 由 `EDPA_REDIS_CHECKPOINTER_TTL` 控制（默认60分钟），超时后中断失效，需要重新开始。

2. **一次只问一个问题**：不要在一个 ask_user 中追问多个不相关的问题，应分步追问。

3. **问题要明确**：question 文本应该清晰、具体，用户一看就知道需要输入什么。避免模糊的问题如"请补充相关信息"。

4. **提供选项**：对于选择类问题，建议在 question 中列出选项："请问您要查询哪个账户？A.储蓄卡 B.理财账户"

5. **与 cancel_task 的关系**：ask_user 是询问用户意图，cancel_task 是执行取消。取消流程必须是：ask_user 确认 → 用户确认 → cancel_task。不能直接 cancel_task 而不确认。

6. **不要滥用**：不是所有情况都需要追问。对于用户已明确提供的信息、常识性默认值（如默认使用当前账户），不需要反复确认。

## 代码参考

- [EnhancedAskUserTool.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/EnhancedAskUserTool.java)
- [AskUserTemplateRail.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/AskUserTemplateRail.java)
- [planrule.yaml 行为约束](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/governance/planrule.yaml#L91-L98)
