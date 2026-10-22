# A removed source chest survived a full rescan

## Evidence

The full, non-lazy `oldstorage` scan `e6a5b0e0-2098-4e3b-ba06-57351d021107` completed with 174 observations and no failed reads or missing chunks. Its catalog nevertheless retained 82 older container records: ingestion replaces records at encountered coordinates, not the complete stash snapshot. Discovery does not report previously cataloged coordinates that are now air.

Transfer `f2478e70-e022-400c-a138-d5b03900ae8b` collected 11 Saturn v2.2 kits, then encountered `minecraft:air` at the old source `[coordinates redacted]`. All 11 confirmed kits were deposited at `[coordinates redacted]` before the workflow reported the source issue. The ordinary restart `7aace396-3c62-408c-9b6b-bde570df4c7c` selected that same stale position and failed with zero pickups. The record was an old withdrawal invalidation, which the planner intentionally treats as a searchable known location, not verified stock.

Both failed jobs were cancelled. Replacement `c97ef7cb-d199-4e32-a239-69f57012b147` uses captured Saturn and HAIL SATURNUS source manifests restricted to today's observations, excluding the stale position. It reuses the existing kit-delivery workflow and verified inventory operations. This is an operational workaround, not catalog removal reconciliation.

The replacement also stopped, with zero pickups, on a different failure: `No useful approach progress after 200 ticks` at `[coordinates redacted]`. The missing-source patch does not repair this navigation failure. No further replacement was dispatched.

## 0.13.64 change

A source that remains a non-block-entity block in a loaded chunk after the existing 40-tick recheck is logged and skipped. The action advances to the next connected chest or mapped source without losing confirmed pickup receipts. Existing world-scoped empty hints and checkpointed source progress prevent immediate repeated visits. Storage block entities still loading, unloaded chunks, cursor items and uncertain inventory operations are not treated as removed storage.

Regression checks cover independent and connected missing sources, retained partial pickups, restored checkpoints, all-missing searches and pending-operation rejection. `botsCheck` and the complete non-parallel Gradle build passed. The new worker behavior has not yet been exercised on the live server; the host and captured workflows require no update.

## 0.13.65 catalog correction

The shared catalog now accepts deduplicated, revision-checked live observations, records confirmed removals and excludes retired containers from stock, capacity and plans. The passive client observer binds actual interaction coordinates to server menu contents; loaded chunk/block catch-up and scans positively reconcile known coordinates now occupied by non-storage. New unopened storage adds known capacity with unknown contents. Double-chest records retain both halves, retire aliases and reconcile surviving halves. All workers may report externally caused changes; reporter/event provenance is retained without requiring the original breaker to remain online.

Failed reads, unloaded regions, pending block predictions and omitted partial/column-scan rows cannot erase inventory by omission. Updates queue durably off the game thread, respect export opt-out and negotiate separately from existing stash catalog support. Duplicate retries do not rewrite stock; stale snapshots/removals cannot overwrite newer accepted records. See [live observation behavior and limits](../stash-kits.md#live-storage-observations-01365).

Both host adapters need the update. This resolves the catalog root cause in offline regressions; it has not yet been demonstrated on the live server and does not fix the separate higher-chest navigation timeout noted above. No transfer was restarted while implementing this change.
