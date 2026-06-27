---
description: Delegate banking workflow requests to the injected remote A2A tool configured as versatile-agent.
---

# Remote Versatile Delegation

Use this skill when the user asks Agent A to handle a banking workflow or remote business workflow that should be processed by the Versatile runtime.

Workflow:

1. Call the injected remote A2A tool for Versatile. It is configured as `versatile-agent`; if the runtime tool list exposes the normalized function name `versatileagent`, call that function.
2. Pass a JSON object with `remoteInput` as the only required field.
3. Put the user's original message text into `remoteInput` unchanged. If the user message is a JSON string, copy that exact JSON string.
4. Do not rewrite, summarize, split, or rebuild the `query` and `intent` fields yourself.
5. When the tool result is returned, continue with the final answer based on that result.

Tool input shape:

```json
{
  "remoteInput": "{\"query\":\"先查询尾号为4241的银行卡余额，再转账5元给李四\",\"intent\":\"查询账户余额\"}"
}
```

Do not answer the banking workflow yourself before calling `versatile-agent`.
