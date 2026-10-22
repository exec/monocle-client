# Stash approach and autopilot ceiling disagreement

## Live evidence

The deposit-first restarts `8a4484f1-5400-4d7d-b782-c1e518a0bce1` and `989a9a95-59fb-4538-bef6-cfebedeb10c2` stalled while approaching Main's chest at `[coordinates redacted]`. Client warnings recorded a loaded, collision-free body near Y255.31 and Y255.38, with three attempted escape routes followed by a retained-load failure. The restart wrapper attempted its deposits even with no carried kits; after the operator confirmed an empty inventory, the next restart skipped that wrapper.

Collection-first job `9b062e52-c093-467c-940b-e4238628d2ba` reached Tango2 and returned toward Main. Its final recorded failure was **not** another flight-watchdog timeout: it required an equipped elytra with at least 64 durability while approaching `[coordinates redacted]`. That old error does not distinguish a worn glider from a chestplate/no glider. Do not infer that navigation alone caused this later equipment failure.

## Code conflict corrected in 0.14.17

The stash planner requests a cruise position above the target plus its three-block body buffer. For a Y255 chest this is above build height. ElytraFly's last movement guard independently rejected a body above `level.getMaxY() + 1`, silently replacing its requested velocity with zero. The planner and executor therefore disagreed about a valid route. Removing the placement-height ceiling preserves loaded-world, swept-body collision, border, hazard and lower-bound checks.

Final interaction routing also used the cruise buffer, and the clearance maintainer could lift a close approach away from opening reach. The shared scan/withdrawal/deposit navigation now uses actual collision clearance within the bounded 32-block final approach, including its hold and escape states; longer flight retains the three-block buffer. No block is ignored to reach a container.

Offline checks cover top-height clearance exits, dense-storage interaction routes, the approach/cruise distance boundary and the executor's retained safety guards. Stall snapshots add phase, cruise clearance, build height and glider durability; equipment failures include the actual equipped chest item. Existing jobs, catalogs, leases and inventory confirmation rules are not migrated.

## Live replay on 0.14.17

Unbans's Debian client was updated with a verified matching JAR hash and relaunched through Modrinth using its active Unbans account and the 6b6t launch link. The old game process required a forced stop after not exiting on SIGTERM; its old JAR was retained outside the mods directory. The host remained running on 0.14.16.

The first start encountered the server's repeating backup-lobby/reserved-spot flow and failed the food preflight against lobby inventory. Lobby Skip was explicitly started; the worker subsequently reached Main but retained a Lobby readiness flag. Stopping Lobby Skip and observing a fresh `/home main` teleport restored Ready status. This is a separate lobby/readiness race, not evidence of lost real inventory.

Fresh job `a1d150a2-8162-4f11-9e3a-4f9586282eea` used only the previous retained grass-kit deposit rather than all unrelated recovery types. It advanced to Wood collection (`StashResupply`, Tango2), then failed with no nearby personal enchanted-apple cache or carried pure supply shulker. That check triggers when there are eight or fewer food items usable under Auto Eat's captured policy. No flight-watchdog failure was recorded in this attempt, but a full collection/deposit cycle is still unverified. Replenish usable food before another restart; do not bypass the survival guard.

## Landing deadlock and 0.14.18

After replenishing food, job `4c50e319-55cb-484e-ad83-269a999a0f63` collected 34 Wood kits and returned to Main. It hovered at approximately `[coordinates redacted]` with full health/hunger and no cursor or pending deposit click, reporting only "Landing before continuing stash work." Its approach to `[coordinates redacted]` remained 29.7 blocks away until the navigation watchdog suspended the job at 360 seconds. Recorded nearby geometry includes partial-height double chests and hoppers; it is catalog evidence, not a fresh physical block dump.

The old landing probe required full cubes at integer floor heights and swept a standing-height, artificially widened body during gliding transit. Nearby chest lids were not valid floors, and valid exits from low headroom could be rejected. The generic clearance detail hid the actual landing search state. Workflow handoff code independently hid that state too.

0.14.18 checks actual collision surfaces at fractional heights, full-footprint support and standing clearance at the destination, while transit retains the real gliding body. Nearby landings precede breadcrumb retreat. Specific blockers survive both action and workflow status. Hazards, unknown terrain and unsupported edges remain excluded; grid walking is unchanged. Tests use real chest/hopper shapes, chest seams/gaps, large coordinates and low roofs. These changes are built for a client update, not yet live-verified.

Per operator request, `1b8a8fd1-27ad-4a4c-b952-1de064fc564d` was started first on 0.14.17 after the worker was grounded. It targeted only the retained 34-kit Wood deposit before continuing the same rotation. During development it advanced through Main's deposit/handoff and returned to Tango2 collecting GRC Build kits. This demonstrates resumed progress on the old client, not live verification of the new landing geometry.
