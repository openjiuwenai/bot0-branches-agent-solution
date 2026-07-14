# Fix Plan: Wave 7 Review Issues

## Context

Wave 7 code review identified 6 issues (2 new P1, 1 new P2, 3 from original review). All need to be fixed in a single commit. The most critical is batch alignment: when `_rollout()` skips cases via `continue`, the returned lists are shorter than `batch_cases`, causing `zip()` in upstream `_backward()` to misalign trajectories/evaluations with wrong cases.

## Issues to Fix

| # | Priority | Issue | File(s) |
|---|----------|-------|---------|
| 1 | P1 | Batch alignment: `continue` skips case but upstream `zip` misaligns | `scenarios/edp_agent/optimizer.py` |
| 2 | P1 | `asyncio.gather` cancels sibling tasks on exception | `src/evo_agent/trainer.py` |
| 3 | P2 | Trajectory injection format fails `StandardTrajectory.model_validate()` | `src/evo_agent/trainer.py`, `scenarios/edp_agent/optimizer.py` |
| 4 | P2 | `eval_runtime` not passed to custom evaluator kwargs | `src/evo_agent/dataset/manifest.py` |
| 5 | P2 | `_rollout` fallback to `case.case_id` has no warning | `scenarios/edp_agent/optimizer.py` |
| 6 | P2 | `prompt_template` priority uses `or` instead of `is not None` | `src/evo_agent/dataset/manifest.py` |

---

## Fix 1: Batch alignment (P1)

**Root cause**: `EDPAgentOptimizer._rollout()` uses `continue` on `TrajectoryUnavailableError`, making `evaluated_list` and `trajectories` shorter than `cases`. Upstream `_backward()` (line 915) does `zip(batch_trajectories, batch_evaluated, batch_cases)` — `zip` silently truncates to shortest, causing later cases to be paired with wrong evaluations/trajectories.

**Fix**: Instead of `continue`, append placeholder `EvaluatedCase(score=0.0)` and empty `Trajectory` for skipped cases. This keeps all return lists aligned with input `cases`.

**File**: `scenarios/edp_agent/optimizer.py`

**Changes**:
- Replace `continue` block (lines 236-238) with placeholder creation:

```python
except TrajectoryUnavailableError as exc:
    logger.warning("Skipping case %s: %s", case.case_id, exc)
    # Placeholder: keeps batch aligned for upstream zip()
    evaluated_list.append(
        EvaluatedCase(case=case, answer={"answer": ""}, score=0.0)
    )
    trajectories.append(
        Trajectory(execution_id=uuid.uuid4().hex, steps=[])
    )
    continue
```

- Update test `test_rollout_retry_exhausted_skips_case` to verify placeholders are returned (len=1, score=0.0) instead of empty lists.
- Update test `test_rollout_skips_case_on_trajectory_unavailable` similarly.

---

## Fix 2: `asyncio.gather` sibling cancellation (P1)

**Root cause**: `trainer.py:127` uses `asyncio.gather()` without `return_exceptions=True`. When one `_rollout_one` raises `TrajectoryUnavailableError`, all other pending tasks are cancelled.

**Fix**: Use `return_exceptions=True`, then check results for exceptions.

**File**: `src/evo_agent/trainer.py`

**Changes** in `_predict_and_build_eval_cases`:

```python
results = await asyncio.gather(
    *[_rollout_one(c) for c in cases),
    return_exceptions=True,
)
# Re-raise the first exception (preserves "validation fails on missing trace" semantics)
for r in results:
    if isinstance(r, Exception):
        raise r
predicts = [r[0] for r in results]
eval_cases = [r[1] for r in results]
```

---

## Fix 3: Trajectory normalization (P2)

**Root cause**: Both `trainer.py:119` and `optimizer.py:231` inject `{"summary": "<string>", "messages": [...]}` into `case.inputs["trajectory"]`. But `StandardTrajectory.summary` expects `TrajectorySummary | None`, not a string. `model_validate()` fails silently in `_simplify_for_prompt()` (line 274), falling back to raw JSON dump — no simplification, no skill detection, no warnings.

**Fix**: Create a shared normalization function that converts Adapter trace data to `StandardTrajectory`-compatible dict.

**New file**: `src/evo_agent/evaluator/trajectory/normalize.py`

```python
"""Adapter trace → StandardTrajectory normalization."""
from __future__ import annotations
from typing import Any


def normalize_trace_to_trajectory(trace_data: dict[str, Any]) -> dict[str, Any]:
    """Convert Adapter trace data to StandardTrajectory-compatible dict.

    Handles:
    - summary: string → TrajectorySummary dict (or None)
    - tool_calls: flat format {name, arguments} → OpenAI format {id, function: {name, arguments}}
    - Passes through messages that already conform to TrajectoryMessage
    """
    messages = trace_data.get("messages", [])
    raw_summary = trace_data.get("summary")

    summary = _build_summary(raw_summary, messages) if raw_summary else None
    normalized_messages = [_normalize_message(msg) for msg in messages]

    return {"summary": summary, "messages": normalized_messages}


def _build_summary(raw_summary: Any, messages: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Convert raw summary to TrajectorySummary dict."""
    if isinstance(raw_summary, str):
        tool_calls_used = _extract_tool_names(messages)
        return {
            "summary": raw_summary,
            "total_messages": len(messages),
            "tool_calls_used": tool_calls_used,
            "tool_calls_count": sum(
                len(m.get("tool_calls", [])) for m in messages if m.get("role") == "assistant"
            ),
            "total_steps": len(messages),
            "tokens_used": 0,
            "metadata": {},
        }
    if isinstance(raw_summary, dict):
        return raw_summary  # Already structured
    return None


def _extract_tool_names(messages: list[dict[str, Any]]) -> list[str]:
    """Extract unique tool names from assistant messages' tool_calls."""
    names: list[str] = []
    seen: set[str] = set()
    for msg in messages:
        if msg.get("role") != "assistant":
            continue
        for tc in msg.get("tool_calls", []):
            name = _get_tool_name(tc)
            if name and name not in seen:
                seen.add(name)
                names.append(name)
    return names


def _get_tool_name(tc: dict[str, Any]) -> str | None:
    """Extract tool name from either OpenAI or flat format."""
    fn = tc.get("function")
    if isinstance(fn, dict):
        return fn.get("name")
    return tc.get("name")


def _normalize_message(msg: dict[str, Any]) -> dict[str, Any]:
    """Normalize a single message to TrajectoryMessage-compatible dict."""
    tool_calls = msg.get("tool_calls", [])
    normalized_tcs = [_normalize_tool_call(tc) for tc in tool_calls]

    result: dict[str, Any] = {
        "role": msg.get("role", ""),
        "content": msg.get("content"),
    }
    if normalized_tcs:
        result["tool_calls"] = normalized_tcs
    if msg.get("name") is not None:
        result["name"] = msg["name"]
    if msg.get("tool_call_id") is not None:
        result["tool_call_id"] = msg["tool_call_id"]
    return result


def _normalize_tool_call(tc: dict[str, Any]) -> dict[str, Any]:
    """Normalize tool call to OpenAI format: {id, function: {name, arguments}}."""
    fn = tc.get("function")
    if isinstance(fn, dict):
        return tc  # Already OpenAI format
    # Flat format: {name, arguments} → OpenAI format
    return {
        "id": tc.get("id", ""),
        "function": {
            "name": tc.get("name", ""),
            "arguments": tc.get("arguments", ""),
        },
    }
```

**Consumer changes**:

`src/evo_agent/trainer.py` — replace lines 111-120:
```python
from evo_agent.evaluator.trajectory.normalize import normalize_trace_to_trajectory
# ...
trajectory_dict = normalize_trace_to_trajectory(trace_data)
eval_case = case.model_copy(
    update={"inputs": {**case.inputs, "trajectory": trajectory_dict}},
    deep=True,
)
```

`scenarios/edp_agent/optimizer.py` — replace lines 226-235:
```python
from evo_agent.evaluator.trajectory.normalize import normalize_trace_to_trajectory
# ...
trajectory_dict = normalize_trace_to_trajectory(trace_data)
case_for_eval = case.model_copy(
    update={"inputs": {**case.inputs, "trajectory": trajectory_dict}},
    deep=True,
)
```

**New test file**: `tests/unit/test_normalize.py` — test cases:
- String summary → TrajectorySummary dict
- None summary → None
- Dict summary → passed through
- Flat tool_calls → OpenAI format
- OpenAI tool_calls → passed through
- `StandardTrajectory.model_validate()` succeeds on normalized output

---

## Fix 4: Pass eval_runtime to custom evaluator (P2)

**Root cause**: `_build_custom_evaluator(config, runtime)` receives `runtime` but never merges it into `kwargs`. Custom evaluators that need `model_config`/`model_client_config` never receive them.

**File**: `src/evo_agent/dataset/manifest.py`

**Changes** in `_build_custom_evaluator`:

```python
def _build_custom_evaluator(config: dict[str, Any], runtime: dict[str, Any]) -> Any:
    dotted_path = config.get("dotted_path", "")
    kwargs = config.get("kwargs", {})

    if not dotted_path:
        msg = "evaluator.dotted_path is required for custom/no-type evaluators"
        raise ValueError(msg)

    module_path, _, class_name = dotted_path.rpartition(".")
    if not module_path:
        msg = f"Invalid evaluator dotted path: {dotted_path}"
        raise ValueError(msg)

    module = importlib.import_module(module_path)
    cls = getattr(module, class_name)
    # Merge runtime config into kwargs (runtime takes precedence)
    merged_kwargs = {**kwargs, **runtime}
    return cls(**merged_kwargs)
```

**Test update**: Add test verifying runtime values are passed to custom evaluator constructor.

---

## Fix 5: Fallback warning log (P2)

**Root cause**: When `_conversation_id_factory is None`, `_rollout()` silently falls back to `case.case_id` — the exact bug Wave 7 was designed to fix.

**File**: `scenarios/edp_agent/optimizer.py`

**Changes** (lines 203-206):

```python
if self._conversation_id_factory:
    conversation_id = self._conversation_id_factory.new(
        phase="train", case_id=case.case_id
    )
else:
    logger.warning(
        "No ConversationIdFactory injected — falling back to case.case_id "
        "(risk of stale trajectory reads)"
    )
    conversation_id = case.case_id
```

---

## Fix 6: prompt_template priority (P2)

**Root cause**: `runtime.get("prompt_template") or config.get("prompt_template")` — empty string `""` is falsy, causing unintended fallback.

**File**: `src/evo_agent/dataset/manifest.py`

**Changes** (line 177):

```python
# Before:
prompt_template = runtime.get("prompt_template") or config.get("prompt_template")

# After:
runtime_prompt = runtime.get("prompt_template")
prompt_template = runtime_prompt if runtime_prompt is not None else config.get("prompt_template")
```

---

## Files to Modify

| File | Fix # | Action |
|------|-------|--------|
| `scenarios/edp_agent/optimizer.py` | 1, 3, 5 | Batch alignment + normalize + warning |
| `src/evo_agent/trainer.py` | 2, 3 | gather fix + normalize |
| `src/evo_agent/dataset/manifest.py` | 4, 6 | runtime passthrough + prompt priority |
| `src/evo_agent/evaluator/trajectory/normalize.py` | 3 | **NEW** — normalization function |
| `tests/unit/test_edp_optimizer.py` | 1 | Update skip tests for placeholder behavior |
| `tests/unit/test_trainer.py` | 2 | Update for return_exceptions behavior |
| `tests/unit/test_dataset_manifest.py` | 4, 6 | New tests for custom runtime + prompt priority |
| `tests/unit/test_normalize.py` | 3 | **NEW** — normalization tests |
| `docs/reviews/wave7-code-review.md` | all | Update review document with new findings |

## Verification

```bash
make test     # All existing + new tests pass
make lint     # Ruff + mypy clean
```

Specific test expectations:
- `test_rollout_retry_exhausted_skips_case` → returns placeholder (score=0.0), not empty list
- `test_rollout_skips_case_on_trajectory_unavailable` → returns 2 items (1 placeholder + 1 real)
- New `test_normalize_*` → StandardTrajectory.model_validate() succeeds
- New `test_build_evaluator_type_custom_receives_runtime` → custom evaluator gets runtime kwargs
- Existing `test_build_evaluator_type_llm_prompt_priority` → still passes with `is not None` fix
