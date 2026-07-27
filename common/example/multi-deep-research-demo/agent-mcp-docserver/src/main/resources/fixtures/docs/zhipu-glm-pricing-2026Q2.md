# 智谱 GLM-4 系列 API pricing snapshot (2026 Q2)

**Anchor:** `ZHIPU-2026Q2-VERIFIED`
**Source:** bigmodel.cn/pricing (frozen fixture)
**Snapshot date:** 2026-04-18

## Models

| model         | context | input CNY / 1M tokens | output CNY / 1M tokens |
|---------------|---------|-----------------------|------------------------|
| glm-4         | 128K    | 100.00                | 100.00                 |
| glm-4-air     | 128K    | 1.00                  | 1.00                   |
| glm-4-flash   | 128K    | 0.10                  | 0.10                   |
| glm-4-plus    | 128K    | 50.00                 | 50.00                  |

## Rate limits

- glm-4 / glm-4-plus: 5 requests / second per key, 100k tokens / minute per key.
- glm-4-air: 20 requests / second per key.
- glm-4-flash: 100 requests / second per key (free tier available).

## Function calling

- Supported on all glm-4 variants; JSON-schema tool_choice compatible with OpenAI SDK.

## Specialty

- `glm-4-plus` — highest-quality reasoning + tool use.
- `glm-4-flash` — cheapest tier suitable for classification / routing.
- Native Chinese instruction tuning; strong long-context recall on Chinese corpora.

## Confidence

- pricing: 1.0
- rate_limit: 0.85
- context_length: 1.0
- function_calling: 1.0
- specialty: 0.85
