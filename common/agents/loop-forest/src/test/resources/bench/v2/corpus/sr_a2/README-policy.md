# README-policy -- dual-journal merge rules
1. dedup: records from the two journals describing the same event
   (same param, same corrected ts, same op, same corrected value)
   merge into ONE event; count once.
2. ordering: apply events by corrected ts ascending; at EQUAL ts
   apply `set` before `delta`; tie-break by record id ascending.
3. retraction: the records below are declared invalid (duplicate relay artifact) -- exclude them everywhere:
   retracted: M-019, W-019 (param=pool_decay)
4. a `delta` before any `set` of the same param is an error.
5. reconstruct the chain ONLY for the parameter of record (see task statement); other params are out of scope.
