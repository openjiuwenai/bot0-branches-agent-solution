# 阿里 Qwen / 通义千问 API pricing snapshot (2026 Q2)

**Anchor:** `QWEN-2026Q2-VERIFIED`
**Source:** help.aliyun.com/document_detail/2712516.html (frozen fixture)
**Snapshot date:** 2026-04-27

## Models

| model         | context | input CNY / 1M tokens | output CNY / 1M tokens |
|---------------|---------|-----------------------|------------------------|
| qwen-max      | 32K     | 40.00                 | 120.00                 |
| qwen-plus     | 128K    | 4.00                  | 12.00                  |
| qwen-turbo    | 1M      | 2.00                  | 6.00                   |
| qwen-long     | 10M     | 0.50                  | 2.00                   |

## Rate limits

- qwen-max: 60 RPM / 100k TPM per key.
- qwen-plus: 1,200 RPM / 1,200k TPM per key.
- qwen-turbo: 1,200 RPM / 1,200k TPM per key.
- qwen-long: batch pipeline recommended over interactive traffic.

## Function calling

- Supported across all qwen-max / plus / turbo variants (parallel calls, OpenAI-compatible schema).

## Specialty

- qwen-long: 10M-token context tuned for whole-codebase / whole-book Q&A.
- qwen-max: current state-of-the-art on OpenCompass Chinese leaderboard.
- Native alignment with 通义千问 App / DingTalk tool ecosystem.

## Confidence

- pricing: 1.0
- rate_limit: 0.9
- context_length: 1.0
- function_calling: 1.0
- specialty: 0.9
