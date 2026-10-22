# Stash management audit status

The current audit's Must and Should findings are implemented. This is implementation status, not a claim of live validation; the active host and stash files were left untouched while preparing these releases.

## Must — completed in 0.13.67

- Protect expanded definitions, host defaults and other workers' home routes from stale client exports. Use definition-only revisions, distinct from stock revisions.
- Persist scan and passive export intents before advancing locally. Scope queues to host/account/world, retain them across reconnects, and retire them only on matching receipts.
- Commit export receipts with stock updates so a lost acknowledgement cannot replay old stock over newer observations.
- Preserve fractional-minute home cooldown settings instead of truncating second-level values to zero.

## Should — completed in 0.13.68

- Preserve verified legacy stock when compatible geometry is discovered; discovery must not pretend to refresh contents.
- Remove full summaries, overlap aggregation and content hashing from the passive observer's game-thread reload path. Build compact geometry/CAS baselines on the background writer.

## Remaining Should — completed in 0.13.69

- Split same-name/color shulkers whose contents are unrelated. Preserve old type references as aliases, distinguish real partial kits, and use the same classifier for pickup, incidental stock and deposit. The WebUI chest-location view must use assigned identities, not just names.
- Replace per-observation whole-catalog rewrites with atomic bounded deltas over the existing JSON snapshots. Include receipts and alias retirement in each observation commit, verify cold replay, and compact without a crash window that resurrects older stock.

Checks live in `StashCatalogShouldCheck`, `CoordinatorCoreTest` and `BotActionsTest`, alongside the existing standalone-host socket tests. They cover legacy data, aliases, corruption, failed writes, interrupted compaction, lost ACKs and a 16,000-container catalog. No Minecraft login is required for these checks.

## Operator handoff

Update both the host implementation and clients, then test a small scan and transfer before a large job. Existing JSON snapshots are read without deleting contents; kit migration is checkpointed on the next mutation. Back up the entire `stashes` directory, including `.json.deltas` directories. Do not downgrade against pending deltas: an older version would see only the older snapshot. A portable export must use the current catalog reader rather than copy a snapshot alone.

Classification uses observed item overlap, not semantic kit categories. Disjoint contents split; overlapping contents can still belong to one incomplete-kit family. Completely empty boxes are their own type rather than guessed to be a depleted named kit. Operators should confirm classifications for intentionally mixed or closely related kits.

Category reservations, freshness-driven scheduled scans, multi-observation network batching and further movement tuning remain separate feature/performance follow-ups, not unfinished findings in this audit.
