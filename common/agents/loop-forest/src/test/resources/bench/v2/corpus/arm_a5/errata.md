# Errata — calendar & value corrections

[E1] station st-06 latency figure: 41.20 -> 44.05 (metering fix); metadata-only, no calendar effect.

[E2] data/cand-k00.csv early rows were backfilled from the wrong buffer; treat values before 2026-02-20 as indicative only.

[E3] calendar correction for this corpus: the joint-window requirement is anchored at the effective date 2026-03-21; a post-effective freeze of 9 days (see registry site_freeze_days) is added on top of the base margin. Effective date of this correction: 2026-03-21.

[E4] station st-14 value scale: 0.981 -> 1.000 (unit normalisation); no effect on dates or windows.

[E5] retrospective note: data/cand-m00.csv had a duplicated 2026-03-06 row in an earlier revision; current files are deduplicated.
