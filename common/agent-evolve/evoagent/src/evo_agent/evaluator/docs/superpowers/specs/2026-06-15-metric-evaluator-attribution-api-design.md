# Evaluator API And Metric Attribution Design

## Goal

Extend `POST /evaluate` so the caller selects one evaluator through
`evaluator.type`.

Supported evaluator types:

- `llm`: `LLMEvaluator` uses the caller-provided prompt to score and attribute.
- `metric`: `MetricEvaluator` computes a deterministic score and internally
  performs negative Skill attribution for every non-perfect result.

Each type creates exactly one evaluator instance. Metric attribution is an
internal capability of `MetricEvaluator`; it is not a second evaluator.

## API Contract

The API models evaluator configuration as a Pydantic discriminated union:

```python
EvaluatorConfig = Annotated[
    LLMEvaluatorConfig | MetricEvaluatorConfig,
    Field(discriminator="type"),
]
```

### LLM Evaluator Request

The LLM evaluator retains caller-controlled evaluation prompts.

```json
{
  "trajectory_path": "/data/trajectory.json",
  "expected_result": {
    "output": "北京"
  },
  "skill_names": [
    "search_skill"
  ],
  "evaluator": {
    "type": "llm",
    "prompt_template": "...",
    "llm_config": {
      "model_name": "qwen-plus",
      "api_key": "...",
      "api_base": "...",
      "client_provider": "DashScope",
      "temperature": 0.1,
      "max_tokens": 2048,
      "verify_ssl": false
    }
  }
}
```

### Metric Evaluator Request

Metric attribution uses a fixed, backend-owned prompt. Metric requests cannot
provide or override an attribution prompt.

```json
{
  "trajectory_path": "/data/trajectory.json",
  "expected_result": {
    "output": "北京"
  },
  "skill_names": [
    "search_skill"
  ],
  "evaluator": {
    "type": "metric",
    "metric": "exact_match",
    "aggregate": "mean",
    "llm_config": {
      "model_name": "qwen-plus",
      "api_key": "...",
      "api_base": "...",
      "client_provider": "DashScope",
      "temperature": 0.1,
      "max_tokens": 2048,
      "verify_ssl": false
    }
  }
}
```

`llm_config` is required because `MetricEvaluator` needs a model for internal
attribution when the Metric score is non-perfect. It configures an internal
model dependency, not another evaluator.

`MetricEvaluatorConfig` does not contain `prompt_template` or `attribution`
fields. Pydantic rejects these fields if callers send them.

For backward compatibility, requests without `evaluator` continue to use the
existing top-level `prompt_template` and `llm_config` fields as LLM evaluation.
These legacy fields should be marked deprecated.

## Single Evaluator Architecture

```text
create_evaluator(config)
├── type=llm
│   └── LLMEvaluator
│       └── caller-controlled LLM scoring and attribution
└── type=metric
    └── MetricEvaluator
        ├── deterministic Metric scoring
        └── internal fixed-prompt attribution for non-perfect scores
```

The design does not introduce `MetricAttributionEvaluator` or
`LLMAttributionEvaluator`.

The factory creates exactly one evaluator:

```python
def _create_metric_evaluator(config):
    return MetricEvaluator(
        metrics=create_metric(config["metric"]),
        aggregate=config.get("aggregate", "mean"),
        attribution_model_config=config["model_config"],
        attribution_model_client_config=config["model_client_config"],
    )
```

## MetricEvaluator Responsibilities

`MetricEvaluator` owns the complete Metric evaluation lifecycle:

1. Validate `expected_result`.
2. Compute deterministic Metric scores.
3. Aggregate the final score.
4. Return immediately when the score is exactly `1.0`.
5. For any score below `1.0`, build the fixed attribution prompt.
6. Invoke its internal model dependency.
7. Parse and validate negative Skill attribution.
8. Attach attribution diagnostics without modifying Metric scores.

Conceptual structure:

```python
class MetricEvaluator(EvaluateInputMixin, UpstreamMetricEvaluator):
    def evaluate(self, case, predict):
        evaluated = self._compute_metrics(case, predict)

        if evaluated.score < 1.0:
            self._attach_attribution(evaluated, case, predict)

        return evaluated
```

`_attach_attribution()` is a private MetricEvaluator operation, not an
evaluator interface.

## Prediction Extraction

Metric evaluation automatically uses the last non-empty assistant message:

```python
prediction = {"output": last_assistant_message.content}
```

Metric requests must provide `expected_result` with the same stable shape:

```json
{"output": "expected value"}
```

If the trajectory has no valid assistant output, the API returns HTTP 422
before constructing or invoking the evaluator.

Add an optional prediction to the domain input:

```python
class EvaluationInput(BaseModel):
    trajectory: StandardTrajectory
    expected_result: dict[str, Any] | None = None
    skill_names: list[str]
    prediction: dict[str, Any] | None = None
```

The OpenJiuwen adapter uses the explicit prediction when present and retains the
conversation placeholder for LLM evaluation:

```python
predict = value.prediction or CONVERSATION_PREDICTION
```

## Fixed Metric Attribution Policy

The backend owns a versioned fixed prompt, for example:

```text
prompts/metric_attribution_v1.py
└── METRIC_ATTRIBUTION_PROMPT_V1
```

The fixed prompt receives only backend-formatted data:

- Simplified conversation trajectory and trajectory warnings.
- Extracted prediction.
- Expected result.
- Metric name, aggregate score, and per-Metric scores.
- Allowed `skill_names`.

The prompt instructs the model to output only:

```json
{
  "reason": "Why the deterministic result was non-perfect.",
  "skill_attributions": [
    {
      "skill_name": "search_skill",
      "usage_status": "executed",
      "impact": "negative",
      "reason": "Concrete trajectory evidence."
    }
  ]
}
```

Policy rules:

- Attribution runs for every Metric score below `1.0`.
- Attribution is skipped for an exact `1.0` score.
- Attribution identifies negative Skill impact only.
- Attributed Skill names must exactly match `skill_names`.
- Missing causal evidence produces an empty attribution list.
- Attribution cannot change `score` or `per_metric`.
- Callers cannot alter the fixed prompt.

Prompt changes require a backend code change and version review. This keeps
Metric attribution behavior consistent across API clients and dataset runs.

## Metric Evaluation Flow

```text
POST /evaluate
  -> validate request and load StandardTrajectory
  -> type=metric:
       -> require expected_result
       -> extract prediction from last non-empty assistant message
  -> build EvaluationInput
  -> create exactly one evaluator through create_evaluator()
  -> optionally wrap it with FilteringEvaluator
  -> evaluate_input()
       -> FilteringEvaluator:
            -> match: return filtered result
            -> no match: call MetricEvaluator
       -> MetricEvaluator:
            -> compute Metric score
            -> perfect: return result
            -> non-perfect: run internal fixed-prompt attribution
            -> attach attribution diagnostics
            -> return result
```

`FilteringEvaluator` remains infrastructure shared by both evaluator types. It
is an optional outer decorator, not part of Metric attribution.

## Attribution Diagnostics

Extend the domain result and API response:

```json
{
  "status": "evaluated",
  "score": 0.0,
  "per_metric": {
    "exact_match": 0.0
  },
  "reason": "The final output does not match the expected result.",
  "skill_attributions": [],
  "filter_matches": [],
  "attribution_status": "completed",
  "attribution_error": null
}
```

Supported `attribution_status` values:

- `not_applicable`: LLM evaluator or filtered result does not use Metric's
  internal attribution stage.
- `completed`: Metric was non-perfect and attribution succeeded.
- `skipped_perfect_score`: Metric score was exactly `1.0`.
- `failed`: Metric was non-perfect but attribution failed.

These fields are serialized into the existing structured `EvaluatedCase.reason`
JSON and restored by `EvaluationResult.from_evaluated_case()`.

## Error Handling

- Missing Metric `expected_result`: HTTP 422.
- Missing valid assistant output: HTTP 422.
- Invalid evaluator-specific configuration: HTTP 422.
- Metric execution failure: HTTP 500; attribution is not attempted.
- Attribution model call, parsing, or Skill validation failure:
  - preserve Metric `score` and `per_metric`;
  - set `skill_attributions=[]`;
  - set `attribution_status="failed"`;
  - return a bounded `attribution_error`.
- Existing LLM evaluator infrastructure failures retain current HTTP 500
  behavior.

## Reuse And Boundaries

The API route is responsible for:

- Validating the discriminated request.
- Loading the trajectory.
- Extracting Metric prediction.
- Translating validated API configuration to factory configuration.
- Mapping domain results to HTTP responses.

The factory is responsible for:

- Creating one evaluator for the selected type.
- Translating model configuration objects.
- Rejecting incomplete runtime configuration.

`MetricEvaluator` is responsible for:

- Metric scoring.
- Fixed-prompt attribution.
- Attribution failure degradation.
- Preserving score ownership.

The fixed attribution prompt and parser may use private helper modules, but
those helpers are not evaluator classes and are not factory-created resources.

## Testing

Unit coverage:

- Factory returns exactly one `LLMEvaluator` or one `MetricEvaluator`.
- Metric configuration rejects custom prompt and attribution fields.
- Last non-empty assistant content is wrapped as `{"output": content}`.
- Missing assistant output fails before evaluation.
- Metric perfect score does not invoke the internal model.
- Metric non-perfect score invokes fixed-prompt attribution.
- Metric attribution prompt contains only backend-formatted fixed sections.
- Attribution cannot modify Metric score or `per_metric`.
- Unknown Skill attribution degrades to attribution failure while preserving score.
- Attribution infrastructure failure preserves Metric result.
- Filter match skips Metric scoring and attribution.

API coverage:

- Pydantic discriminator validates evaluator-specific fields.
- Metric request requires `expected_result` and `llm_config`.
- Metric request rejects `prompt_template`.
- Metric response exposes attribution status.
- Legacy LLM request remains compatible.

## Out Of Scope

- Caller-controlled Metric attribution prompts.
- Disabling attribution for non-perfect Metric results.
- Blending Metric and LLM scores.
- Running negative attribution for perfect Metric results.
- Configurable trajectory field paths for prediction extraction.
