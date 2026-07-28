# DeepSeek API pricing snapshot (2026 Q2)

**Anchor:** `DEEPSEEK-2026Q2-VERIFIED`
**Source:** deepseek.com/pricing (frozen fixture, do NOT treat as live)
**Snapshot date:** 2026-04-15

## Models

| model              | context | input CNY / 1M tokens | output CNY / 1M tokens |
|--------------------|---------|-----------------------|------------------------|
| deepseek-chat      | 128K    | 1.00 (cache hit 0.10) | 8.00                   |
| deepseek-reasoner  | 64K     | 4.00 (cache hit 0.40) | 16.00                  |

## Rate limits

- Free tier: 60 requests / minute per API key.
- Paid tier: 3,000 requests / minute per API key (bursts to 6,000 / minute for < 10 s).

## Function calling

- Supported on `deepseek-chat`.
- Not supported on `deepseek-reasoner` (reasoning models are text-only in 2026Q2).

## Specialty

- `deepseek-reasoner` (R1 successor) — chain-of-thought reasoning, best for math / code.
- `deepseek-chat` — general chat + tool use, high throughput.

## Confidence

- pricing: 1.0 (official page)
- rate_limit: 0.9 (official docs but subject to unannounced changes)
- context_length: 1.0
- function_calling: 1.0
- specialty: 0.9
