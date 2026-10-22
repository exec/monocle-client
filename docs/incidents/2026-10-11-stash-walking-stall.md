# Stone source walking stall

## Evidence

Run `70256df8-9b75-4efd-8afd-286509a15b4a` recovered the original four carried Build kits, delivered another 33 Build kits, then suspended while approaching a stone source at `[coordinates redacted]`. Closest recorded opening distance was 5.4245 blocks. The navigation watchdog replanned at 120 and 240 seconds, then paused at 360 seconds. The client checkpoint had zero pickups in this source action and no pending withdrawal, deposit or discard.

This is not the inventory-snapshot failure fixed in 0.14.12. The old client and local 0.14.9 artifact have matching SHA-256 hashes.

## Gaps addressed in 0.14.13

- Near-target flight selection considered only chests above the worker, not below it. Walking to a lower chest could require a route unavailable under the non-destructive Baritone lease.
- A source approach with no useful closer progress did not offer flight as a walking fallback. After 60 native approach ticks, it can now try the existing checked flight path when flight mode/speed, durability and takeoff clearance permit. Existing landing/walking safety fallbacks remain intact.
- The wall-clock navigation watchdog could suspend the whole transfer even though a safe until-empty solo source could be deferred. After its bounded replans, it now reuses the existing deferred-source path, retaining unresolved diagnostics and confirmed pickups. Fixed-count and crew-local jobs, pending transfers and held cursors are excluded.

Logs do not retain the exact obstacle geometry or prove why the native 200-tick approach deadline did not resolve this occurrence before the watchdog. The new wall-clock recovery covers that gap independently; it does not treat an unread chest as empty or guarantee that an inaccessible source will be collected.

## Verification

Native assertion checks cover lower-chest flight eligibility, the 60-tick closer-progress threshold/reset, production watchdog routing and existing deferred-source safety/checkpoint guards. Checkpoints also expose native idle ticks, closest approach, player/action ticks and walking-only fallback state, without using these diagnostic values to replay movement after restart.

0.14.13 was installed on the Debian Unbans worker; its host remained running on 0.14.11. The saved job resumed, but stayed at the original approach before two server transfers interrupted the test. The client then reported Lobby readiness in the End. A clean relaunch of the diagnostic build connected to the host, but could not complete the Minecraft connection: `getent ahosts play.6b6t.org` returned exit 2 on the worker and no game-server TCP connection was established. The saved checkpoint still has zero stone pickups and no pending inventory moves. Live movement recovery is **not yet verified**; explicitly resume after real-world readiness returns and inspect the new counters before declaring the route repaired.

Follow-up: the missing direct A record was not sufficient evidence of DNS failure. Minecraft's `_minecraft._tcp.play.6b6t.org` SRV record resolves to `alt.6b6t.org`; both the SRV and target address resolved on the Debian worker. Relaunching restored Ready status in the End. After resume, native diagnostics remained at `playerTick=20`, `lastActionTick=20`, `idleTicks=1` while the wall-clock watchdog advanced from 203 to 235 seconds. This explains why the entity-tick deduplication guard prevented the native approach deadline from advancing in this attempt, but does not explain why the player stopped ticking. The job was safely paused and `/home tango2` requested to reload its area; no unconfirmed pickup was outstanding.

At 13:56, inspecting the existing host worker `diagnostics` revealed `screen=DeathScreen`, `health=0`, `hunger=0`, `held=minecraft:air`, `playerTick=20`. Thus the live blocker was a dead player, not proved inaccessible storage. `/home tango2` received a server teleport message but did not clear the dead-player state. The 0.14.13 watchdog deferred one unread source while dead; the job was paused before further attempts. This demonstrates a separate missing alive-player gate in readiness/action watchdogs, which must not interpret death as a source navigation failure. Do not claim the original physical approach was repaired from this test; respawn/rekit and verify first. Existing worker diagnostic snapshots should be inspected alongside action checkpoints during future incidents.
