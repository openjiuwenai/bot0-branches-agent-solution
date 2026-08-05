---
description: Delegate banking workflow requests to the injected remote A2A tool that handles remote business workflow processing.
---

# Remote Versatile Delegation

Use this skill when the user asks Agent A to handle a banking workflow or remote business workflow that should be processed by the Versatile runtime.

Workflow:

1. Call the injected remote A2A tool whose description indicates remote banking or business workflow processing.
2. Pass a JSON object with `remoteInput` as the only required field.
3. For a single-person request, put the user's original message text into `remoteInput` unchanged. If the user message is a JSON string, copy that exact JSON string.
4. If one request contains independent balance queries for multiple people, issue one independent call to the same injected remote tool per person in the same assistant turn. For this case only, each `remoteInput` must be a JSON string with `query` and `intent` fields. Set `query` to only that person's balance request and set `intent` to `查询账户余额`. Do not combine people into one call or wait for one call before issuing the others.
5. Keep each returned remote task as an independent multi-turn interaction. Match later tool results by tool call, never by completion order.
6. Except for the explicit multi-person split above, do not rewrite, summarize, split, or rebuild the `query` and `intent` fields yourself.
7. When all tool results are returned, continue with the final answer based on the complete result set.

Tool input shape:

```json
{
  "remoteInput": "{\"query\":\"先查询尾号为4241的银行卡余额，再转账5元给李四\",\"intent\":\"查询账户余额\"}"
}
```

Multi-person example for one member:

```json
{
  "remoteInput": "{\"query\":\"请查询张三尾号4241的银行卡余额\",\"intent\":\"查询账户余额\"}"
}
```

Do not answer the banking workflow yourself before calling the matching remote A2A tool.
