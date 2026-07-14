---
name: versatile-request
description: Decompose a one-sentence banking request into ordered atomic tasks and call versatile-adapter for each.
---

# Transfer skill

When the user asks several things in one sentence (e.g. "查一下尾号4241的卡余额，再转5元给李四"),
decompose the request into ordered atomic tasks and execute them **strictly one at a time, never
in parallel**.

## Decomposition rules

**0. SERIAL ONLY — one `versatile-adapter` per turn (mandatory).** Emit AT MOST ONE `versatile-adapter`
   tool call in each response. Never batch two tasks (e.g. a balance query + a transfer, or two
   transfers to different people) into a single turn. After each `versatile-adapter` returns its
   result, and only then, emit the next single `versatile-adapter`. The downstream versatile-adapter
   agent is stateful per conversation; calling it twice in one turn corrupts the conversation.

1. **Balance queries come first.** Any "查余额/查账户余额" task runs before any transfer.
2. **One task per (recipient, amount) pair.** "转5元给李四和10元给王五" → two separate transfers —
   but each is its own turn, emitted one after the other, not together.
3. For each task, call the `versatile-adapter` tool exactly once with `remoteInput` set to a JSON
   string of the form:

   ```json
   {"query": "<the concrete subtask in Chinese>", "intent": "<intent>"}
   ```

## Intent values

- `查询账户余额` — for any balance / 余额 / 查账户 task.
- `快速转账` — for any transfer / 转账 / 转给 task.

## Example (executed one call per turn, serially)

User: "先查询尾号为4241的银行卡余额，再转账5元给李四"

- **Turn 1:** call `versatile-adapter` once with
  `remoteInput = {"query":"查询尾号为4241的银行卡余额","intent":"查询账户余额"}` → STOP, wait for result.
- **Turn 2 (only after Turn 1's result is back):** call `versatile-adapter` once with
  `remoteInput = {"query":"从尾号为4241的银行卡转账5元给李四","intent":"快速转账"}` → STOP, wait for result.

Do NOT emit both calls in Turn 1. Each turn contains exactly one `versatile-adapter`.

When all tasks are done, summarise every result for the user in Chinese (balances and transfer
confirmations).
