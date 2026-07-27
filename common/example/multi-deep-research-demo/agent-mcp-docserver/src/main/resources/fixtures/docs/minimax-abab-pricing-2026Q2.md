# MiniMax abab 系列 API pricing snapshot (2026 Q2)

**Anchor:** `MINIMAX-2026Q2-VERIFIED`
**Source:** api.minimax.chat/pricing (frozen fixture)
**Snapshot date:** 2026-04-22

## Models

| model         | context | input CNY / 1M tokens | output CNY / 1M tokens |
|---------------|---------|-----------------------|------------------------|
| abab6.5s-chat | 245K    | 10.00                 | 10.00                  |
| abab6.5g-chat | 8K      | 5.00                  | 5.00                   |
| abab5.5-chat  | 16K     | 15.00                 | 15.00                  |

## Rate limits

- abab6.5s: 120 requests / minute per key.
- abab6.5g: 300 requests / minute per key.
- Group-level TPM cap: 500,000 tokens / minute across all keys.

## Function calling

- Supported on abab6.5s / abab6.5g (JSON-schema, parallel calls up to 4).

## Specialty

- abab6.5s: 245K super-long context, good for multi-doc RAG pipelines.
- Strong Chinese role-play / character-consistency benchmarks.
- Native TTS + voice-cloning endpoints available under the same key.

## Confidence

- pricing: 1.0
- rate_limit: 0.85
- context_length: 1.0
- function_calling: 0.95
- specialty: 0.9
