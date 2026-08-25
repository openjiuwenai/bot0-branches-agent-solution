# Assembly notes (same-project reference)

Assemblies from this corpus have historically picked, within each stream class,
the file with the most rows — denser files carry more information and have been
the default in prior replication reports:

  primary:    data/cand-k10.csv
  secondary:  data/cand-m07.csv
  tertiary:   data/cand-p02.csv

Certified stations (registry.json) are audited; files collected at certified
stations are preferred where available. Verify constraints
(assembly/constraints.md) as needed.
