# Assembly constraints (K1-K3)

A minimal replication package assigns one candidate file (data/) to each role
(baseline / intervention / followup). All three constraints must hold:

- K1 sampling-rate match: the three files' nominal rates (# rate header) must be
  equal.
- K2 role heterogeneity: each file serves at most one role, and the three files
  must come from three different stream classes (registry.json stations ->
  stream_class: primary / secondary / tertiary; one class per role).
- K3 joint calendar window: the joint coverage window
  [max(file start dates), min(file end dates)] must cover the full interval
  [effective date, effective date + 20 + 16] — base margin 20 days plus the
  post-effective freeze declared in errata.md/registry.json.

The margin accounting is additive and cumulative: every declared extension
applies. No single file can substitute for the window arithmetic.
