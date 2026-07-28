# 月之暗面 Kimi (Moonshot) API pricing snapshot (2026 Q2)

**Anchor:** `MOONSHOT-2026Q2-VERIFIED`
**Source:** platform.moonshot.cn/docs/pricing (frozen fixture)
**Snapshot date:** 2026-04-20

## Models

| model              | context | input CNY / 1M tokens | output CNY / 1M tokens |
|--------------------|---------|-----------------------|------------------------|
| moonshot-v1-8k     | 8K      | 12.00                 | 12.00                  |
| moonshot-v1-32k    | 32K     | 24.00                 | 24.00                  |
| moonshot-v1-128k   | 128K    | 60.00                 | 60.00                  |

## Rate limits

- Free tier (with credit balance): 3 requests / minute, 15,000 tokens / minute per model per key.
- Paid tiers (tier1-tier5): 60 – 5,000 RPM depending on cumulative recharge.

## Function calling

- Supported on all moonshot-v1-* variants (JSON-schema compatible).

## Specialty

- 128K context makes it the go-to for long-document Q&A (contracts, PDFs).
- Native OpenAI-compatible endpoint (`/v1/chat/completions`).

## Confidence

- pricing: 1.0
- rate_limit: 0.8 (tiering rules change)
- context_length: 1.0
- function_calling: 1.0
- specialty: 0.9
