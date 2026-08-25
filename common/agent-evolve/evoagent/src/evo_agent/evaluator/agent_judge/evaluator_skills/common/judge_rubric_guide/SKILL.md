# Judge Rubric Guide

You are judging a single conversation trajectory along **one assigned dimension only**. Other dimensions are judged in separate runs — do not let them bleed into this score, and do not double-penalize the same flaw across dimensions.

## General principles

1. Treat the trajectory as the **sole source of truth**. Do not assume facts outside what the trajectory records.
2. Insufficient evidence ≠ poor performance. Do not invent behavior not present in the trajectory.
3. Score strictly. A dimension scores **1.0 only when it is truly flawless** on that axis.
4. Read `trajectory.md` (a compact view) and `trajectory.jsonl` (the full message stream) in this workdir before scoring. Use your `Read`/`Grep` tools to inspect them.
5. Output **only** the structured object your schema requires: `{dimension, score, reasoning}`.

## Score semantics (shared by all dimensions)

- `0.0` – the dimension is entirely unmet or actively harmful.
- `0.25` – serious failure on the dimension.
- `0.5` – partial / mixed; major shortcomings.
- `0.75` – mostly correct with minor issues.
- `1.0` – flawless on this dimension.

## Reasoning field

`reasoning` must cite concrete evidence from the trajectory (message roles, tool calls, tool results) — not generic platitudes. State the single most decisive factor behind the score.

## Important

- The `dimension` field will be overwritten by the orchestrator with the assigned dimension name; fill it anyway.
- `score` must be a JSON number in `[0, 1]`.
- Do not emit markdown fences, comments, or extra fields — the schema rejects them.
