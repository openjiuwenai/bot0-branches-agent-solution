# Incident IN-2607 -- payment-service latency spike (2026-08-04)

window: 2026-08-04T14:00Z..2026-08-04T15:30Z
severity: P1; p99 latency 3.8s (baseline 203ms); error rate 7.7%

## Timeline
14:02 first alert; 14:05 pool alarms; 14:20 rollback of deploy 4471; 14:58 recovery

## Hypotheses (on-call triage)
H1 cache-regression -- predicted signal: cache_hit_drop ; counter-signal: cache_hit_stable
H2 conn-pool-exhaustion -- predicted signal: pool_saturation ; counter-signal: pool_headroom
H3 downstream-fx-latency -- predicted signal: fx_p99_up ; counter-signal: fx_p99_flat

## Metric snapshot (ledger-svc pool, window)
EV-A  ts=2026-08-04T14:06Z sig=pool_saturation svc=ledger-svc pool_active=64 pool_max=64 wait_ms=2201
EV-A  ts=2026-08-04T14:19Z sig=pool_saturation svc=ledger-svc pool_active=64 pool_max=64 wait_ms=2400
EV-A  ts=2026-08-04T14:47Z sig=pool_saturation svc=ledger-svc pool_active=64 pool_max=64 wait_ms=1757

## Edge gateway cache telemetry (window excerpt)
EV-B2 ts=2026-08-04T14:09Z sig=cache_hit_stable edge=gw-1 hit=0.977 base=0.971
EV-B2 ts=2026-08-04T14:25Z sig=cache_hit_stable edge=gw-2 hit=0.975 base=0.971
EV-B2 ts=2026-08-04T14:41Z sig=cache_hit_stable edge=gw-3 hit=0.969 base=0.971

## Topology (reachable from payment-service)
topology: gw-1, gw-2, gw-3, ledger-svc, fx-api, pod-web-4
note: pod-cache-07 was decommissioned 2026-05; not reachable
