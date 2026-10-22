# Unverified bulk deposit at Main

## Observed failure

The October 11 Tango2 to Main run `6c99aaff-3a33-4be1-8b6c-a496150d09da` failed at 14:49 CDT. Its workflow checkpoint contained 714 completed deliveries and its active deposit held 12 additional verified receipts out of a 34-kit load. The next four-kit QUICK_MOVE burst could not be verified within 100 player ticks. A different menu did open: the saved evidence recorded previous menu 32, response menu 33, state 18, and packet tick 5001. The worker stayed alive and connected at Main; this was not a host crash or death.

The evidence did not retain actual differing inventory or destination slots, so it cannot establish whether hopper drainage, different quick-move destinations, food consumption, or another server inventory change caused this particular mismatch. Exact whole-inventory prefix comparison and predicted destination positions were sufficient to strand recovery. Cancelling the failed host execution retained its uncertain worker checkpoint while allowing a fresh deposit-carried-first job to start.

## Recovery in 0.14.15

Positive bulk receipts still require exact planned kit inventory changes and exact destination contents. Unrelated non-shulker changes, such as food consumption, no longer invalidate those receipts. Planned inventory positions and every other shulker remain protected.

If exact destination evidence remains unavailable, the worker reopens the same chest. Only a different accepted menu, full authoritative contents matching the local player inventory, and empty server/client cursors can unlock recovery. Each remaining planned source must still contain the original kit or be empty. Replaced sources or unrelated changed shulkers retain the safety gate. An empty planned source becomes an unverified no-longer-carried outcome, never a successful deposit. Unchanged sources can be selected anew after that server ordering barrier; old uncertain clicks are not replayed.

Recovery disables bulk transfers for the rest of that deposit action, publishes current chest contents through the existing host observation/column acknowledgement path, and continues with exact-slot transfers. The existing workflow accounts for missing outcomes and advances its routes. Both solo/client-host and standalone-host jobs use this shared native executor; no new network operation or host update is required.

Partial missing counts persist across checkpoints. Bulk diagnostics now retain changed inventory positions, item/count changes, expected destination matches, and matching kit positions elsewhere in the container, making the next mismatch distinguishable rather than exposing only planned moves.

## Operating policy

### Incidental pickups in 0.14.16

The deposit-first restart failed on the old 0.14.13 client with the same bulk timeout; the operator observed an ender pearl appearing during the deposit. The old diagnostics cannot establish its exact slot. Regression checks reproduced an additional gap in the pending 0.14.15 recovery: non-kit pickups were allowed in unrelated slots but not in a planned source emptied by a deposit.

Deposits now permit non-shulker changes in emptied source slots as well. A pearl cannot count as a kit: successful receipts still require the exact destination box, and new-menu recovery records absent boxes as unverified/missing. Exact-slot retries tolerate unrelated non-kit changes while retaining exact source identity, cursor/destination guards, and protection of all other shulkers. A kit returned elsewhere when closing can be reselected even if a pearl occupied its old source. Withdrawal destinations remain strict; an incidental item there cannot prove a kit pickup or an unchanged batch. The worker retains incidental items rather than dumping arbitrary personal belongings.

Observed changes and exhausted sources should refresh knowledge and permit further work, not permanently freeze an unattended transfer. Missing outcomes remain explicit so continuing does not inflate delivery totals. Held cursors, unread menus, conflicting cargo changes, world readiness, failed navigation and actual resource exhaustion remain separate safety conditions; this patch does not promise recovery from every failure.

Unbans currently uses a 16-second home cooldown to allow for its 15-second rank cooldown and server lag. Other accounts may require 5- or 10-minute cooldowns. A leader's shorter cooldown is not a valid default for those accounts; future fleet transfers must retain per-worker timings and may use existing TPA coordination where appropriate.

## Checks

Offline tests cover exact and missing destination receipts, partial non-prefix removal, food changes, held cursors, wrong source replacements, unplanned shulker removal, old-menu refusal, duplicate retirement, withdrawal exclusion and checkpoint restoration. Worker, Auto Mend, coordinator-core and bundled-library checks run with the client build. Live testing requires installing 0.14.15; the restarted job was intentionally left on its current client.
