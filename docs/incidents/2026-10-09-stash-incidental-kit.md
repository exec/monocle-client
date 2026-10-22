# Incidental crying-obsidian kit interrupted an obsidian load

## Evidence

Job `6baba2dc-aadc-49d6-b052-207d07557d49` failed during `StashResupply` at `[coordinates redacted]`. Its pending withdrawal expected source slot 0 to reach player menu slot 81. The requested-kit count stayed at 27, the cursor was empty, and slot 81 instead contained one `minecraft:purple_shulker_box`. Neither the source nor destination matched the expected stack. `unchangedPlayerInventory` was false, so the existing recovery never reopened the source and failed after its confirmation deadline. The operator confirmed the incidental box was a crying-obsidian kit.

The evidence proves a wrong box occupied the reserved player slot, not whether it came from a ground pickup or changed source contents between clicks. Previously that unrelated addition prevented reconciliation; filling the remaining inventory would also eventually hit the fixed batch target with no empty slot.

## 0.13.63 changes

- Ordinary stash-to-stash plans capture known IDs from the source and destination catalogs, without transmitting complete exemplar catalogs or rereading every stash.
- Fresh-menu recovery permits only additive non-requested shulkers with an unchanged requested-kit count and empty cursor. It does not fabricate a requested pickup, ignore removals, or replay the old move.
- Newly acquired known extras remain carried while requested pickup continues. The workflow deposits them separately into their own destination columns; a full partial load does not mean source exhaustion.
- Newly acquired unknown shulkers are thrown using an exact server-confirmed player-slot operation. Pre-job shulkers remain protected, including during side-deposits. Discard uncertainty is checkpointed and never blindly replayed.
- Regression checks cover slot collisions, expected-gain/removal rejection, personal-box protection, incidental-only full loads and separate delivery counts. Full `botsCheck build` passed. Live game behavior still needs verification with the new worker JAR and a fresh captured job.

The host was upgraded from 0.13.60 to 0.13.63 after verifying no jobs were active; addresses, credentials and history were retained. No replacement transfer was started. The separate leased crew-transfer coordinator does not enable incidental side-deposits yet.
