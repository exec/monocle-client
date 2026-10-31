# Idle source snapshot mismatch

Caching full contents while ignoring ordered slot updates can leave idle container baselines stale after hopper or player-inventory changes.

Slot deltas update only an already seeded current menu, with bounded slots, wrap-aware state ordering and detached stacks. They refresh idle baselines but never confirm or replay pending single/bulk moves, interrupted deposits or held cursors.

An empty-cursor idle mismatch can use bounded close/reopen attempts, each requiring a fresh full snapshot. Repeated unresolved solo sources defer rather than claim emptiness. Fixed-count and unsafe cases retain guarded failure. Diagnostics include differing slots and snapshot age without exposing full contents publicly.
