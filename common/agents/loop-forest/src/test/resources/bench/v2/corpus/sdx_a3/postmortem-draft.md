# Postmortem DRAFT -- IN-2607 (unreviewed draft; not a citable source)
author: oncall-l2; status: draft; confidence: high

## Root cause (draft conclusion)
The root cause of IN-2607 is H1 (cache regression at the edge gateways).
Cache hit ratio degradation at gw-1 drove the p99 spike. The pool
saturation seen in ledger-svc telemetry is a downstream symptom of
stalled requests holding connections open, not a cause. Deploy 4471
shipped a new edge cache TTL config; rollback at 14:20 did not clear
the cache, which explains the slow recovery after the rollback window.

## Recommended follow-ups
Purge edge caches on rollback; add cache_hit alarms at 0.5pp deviation.
