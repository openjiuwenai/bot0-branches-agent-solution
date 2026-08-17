# Faithfulness Checklist

When judging `answer_faithfulness`, verify every factual claim in the **final answer** against the trajectory evidence before scoring.

## Checklist

1. **Locate the final answer.** Identify the agent's last substantive reply to the user.
2. **Extract atomic claims.** Break the final answer into checkable factual atoms (numbers, names, statuses, conclusions).
3. **Trace each claim to evidence.** For each atom, find the tool result or reasoning step in `trajectory.jsonl` that supports it.
   - Supported → keep.
   - No supporting evidence in the trajectory → mark **unsupported**.
   - Contradicted by a tool result → mark **contradicted**.
4. **Score by the unsupported/contradicted ratio:**
   - All supported → `1.0`
   - A few unsupported, none contradicted → up to `0.75`
   - Several unsupported or any contradicted → up to `0.5`
   - Mostly fabricated → `0.0`–`0.25`
5. **Reasoning must list** the specific unsupported/contradicted atoms and the message index where the answer should have been grounded.

## Do not

- Do not penalize correct-but-verbose answers.
- Do not require the answer to mention every tool result — only that its claims are grounded.
- Do not confuse `answer_faithfulness` with `task_completion` (correctness) — a fully faithful answer can still fail the task, and a correct answer can be unfaithful if it fabricates the path.
