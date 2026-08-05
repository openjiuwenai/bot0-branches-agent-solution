# 百川 Baichuan API pricing snapshot (2026 Q2)

**Anchor:** `BAICHUAN-2026Q2-VERIFIED`
**Source:** platform.baichuan-ai.com/pricing (frozen fixture)
**Snapshot date:** 2026-04-25

## Models

| model              | context | input CNY / 1M tokens | output CNY / 1M tokens |
|--------------------|---------|-----------------------|------------------------|
| Baichuan4-Turbo    | 32K     | 15.00                 | 15.00                  |
| Baichuan4-Air      | 32K     | 0.98                  | 0.98                   |
| Baichuan3-Turbo    | 32K     | 12.00                 | 12.00                  |

## Rate limits

- Baichuan4-Turbo: 60 requests / minute per key.
- Baichuan4-Air: 300 requests / minute per key.
- Enterprise plans lift caps by SLA (contact sales).

## Function calling

- Supported on Baichuan4-Turbo / Baichuan4-Air.
- JSON-schema tool_choice compatible with OpenAI SDK.

## Specialty

- Baichuan4-Turbo: Chinese knowledge + medical / legal domain benchmarks lead the tier.
- Baichuan4-Air: cheapest OpenAI-compatible option with tool use in 2026Q2.

## Confidence

- pricing: 1.0
- rate_limit: 0.8
- context_length: 1.0
- function_calling: 0.95
- specialty: 0.85
