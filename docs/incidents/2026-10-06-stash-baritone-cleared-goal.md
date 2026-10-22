# Stash deposit rejected as a Baritone takeover — 0.13.35

On October 6, the Zungle obsidian → Myobbytop repeat transfer failed while returning to deposit, without an operator cancelling/restarting it. Host task `e579147b-68b1-4385-82b2-f5c530ae7bdb` reported **Another Baritone task took control; stash scan stopped**, although its current native action was `StashDeposit`.

Read-only inspection of the worker's actual `bot-worker-tasks.json` confirmed:

- Lua phase `storing`, total **245** previously delivered kits, current batch **35**, source cursor zero.
- Destination `/home myobbytop` and the expected destination stash; a reserved column had been assigned.
- Deposit `confirmed: 0`, `pending: false`, `touched: []`: no uncertain click or partially acknowledged deposit in this final batch.
- The worker remained online at the destination with the final load carried. No replacement live job or manual inventory move was issued during diagnosis.

The shared `BaritoneUtils.StashNavigation.moveTo` assumed `getGoal() != ourGoal` always meant a foreign task had replaced it. Inspection of the **installed Baritone 26.2 JAR's bytecode** shows `CustomGoalProcess.onTick` calls `onLostControl` on arrival or path failure; `onLostControl` clears `getGoal()` to null while `mostRecentGoal()` retains that goal. Therefore normal completion/failure could trigger our takeover error on a later approach tick. The live report did not capture the actual current goal/controller, so it cannot distinguish this case conclusively from a real takeover; the invalid assumption is independently reproducible.

In 0.13.36, a null current goal with our exact last goal is eligible for reacquisition. A different current/last goal or an active unrelated controlling process still stops navigation. The failure now names the current goal class, whether its last goal matches ours, and any active foreign controller, rather than a generic takeover claim. Cleanup also refuses to cancel an active foreign controller. This shared fix applies to scans, pickups and deposits; existing approach/opening watchdogs and uncertain-click rules remain intact. No broad cancellation or unrelated path takeover is introduced.

Regression checks cover cleared vs. replaced goals and real production call paths, plus the multi-kit workflow's actual nested result envelope, lazy bounded planning, token-bound retry cache, carried-kit unloading and cancellation through authenticated host sockets. No live-server retest or host deployment occurred during this patch. The next job should explicitly unload the confirmed carried load first; never replay an uncertain transfer.

## Follow-up: Monocle's pause misidentified as a takeover — 0.13.39

Solo task `66a4b95f-3ada-49eb-bf3c-68d462455c0b` failed during a 35-kit deposit with `current=GoalGetToBlock, previousMatches=true, foreignController=BaritoneProcess`. Task `f2518089-6a89-42e2-babd-63c5815466f8` had reported the same controller. These are separate from the earlier uncertain deposit timeout and the low-elytra reserve failure.

Source inspection identifies that exact controller as `BaritonePathManager.BaritoneProcess`, Monocle's temporary `REQUEST_PAUSE` process. Auto Eat, Auto Gap, Kill Aura and Bow Aimbot use it through `PathManagers.pause()`. Its `isTemporary()` is true, and it does not replace the custom goal. The old stash guard nevertheless rejected every active non-custom-goal controller. The live logs prove this pause classification was the failure; they do not identify which module requested the pause.

0.13.40 recognizes the concrete Monocle controller type, not a class-name string or all temporary Baritone processes. An intact owned goal waits during that pause without being replaced. A changed goal or genuinely unrelated controller still fails safely. Closing an owned paused/cleared goal releases the stash path before temporary settings are restored, avoiding a leftover route resuming after eating. Regression checks cover ownership classification and production move/cleanup paths; live validation remains pending. Jobs were left stopped with carried kits retained for the client update. Unbans's next transfer keeps the host-workflow 32-second cooldown override and should deposit its current carried load first.

## Follow-up: empty hopper-column recheck navigated to upper stock — 0.13.40

Fresh solo task `e9dcb2fa-d9be-4755-9997-88ffb6dde9ca` deposited **709 kits**: the parent frame had completed 127 TSR Build Kit V.4 deliveries, and the active Kit Delivery frame had confirmed 582 TSR Build Kit V.5 deliveries. It then had `emptyPasses: 1`, `cursor: 0`, and a new pickup with no receipt/pending click. On the second empty pass it revisited the lower candidates before targeting chest `[coordinates redacted]`. The final movement warning was `Stash takeoff obstructed at [coordinates redacted]`, followed by `No useful approach progress after 200 ticks`; the worker remained safely on the ground. This was not another pause/takeover or inventory-verification failure. The telemetry does not identify the exact obstruction block.

The operator confirmed this is hopper-fed storage: withdrawal should drain the bottom, not visit upper chests, and deposition should use the top. 0.13.41 resolves live directed hopper connections in the shared native stash code, waits briefly for outlet refill/input drainage, and does not retarget duplicate upper picks after an outlet is drained. It retains independent handling when connections are absent/ambiguous/disabled, exact-kit confirmation and host destination ownership. Existing crew source partitions must not redirect independently assigned heights to a shared outlet; those plans are safely rejected pending whole-column leases. Offline regression checks cover these rules; live validation remains pending. No job was restarted and no host update is needed for the solo retest.

## Follow-up: wrong-kit outlet caused immediate job failure — 0.13.41

Fresh tasks `5a593a17-df0c-4a2b-ad83-383df526910d` and `28aa8177-b5fd-4c88-a683-6efb755f0ba6` both failed before picking any kits at outlet `[coordinates redacted]`, requesting TSR Build Kit V.1. Both reported `Hopper outlet contains non-matching items; cannot verify that the requested kit above is exhausted`. The first exposed the full 112-tick refill wait. Post-failure telemetry confirmed `containerMenu: 0`, no active task, and health 20: this was not a stuck-close failure. The new .41 guard rejected any remaining wrong item rather than allowing useful work on other columns/types. Those reports did not identify the actual wrong items.

0.13.42 replaces that fatal guard for solo kit pickups with a bounded, checkpointed deferral receipt. Full mismatched outlets skip without a refill wait; partially occupied outlets wait briefly then close/skip. The common kit-delivery workflow deposits confirmed pickups before returning deferral, and the multi-kit workflow processes other types before one retry of only deferred types. Persistent deferrals retain details and delivered totals and produce an explicit unresolved report, never false exhaustion or an unbounded loop. Logging now includes expected type and observed item/name/type groups. Changed topology, uncertain clicks and legacy crew source-lease safety retain their existing protections. Offline validation covers the native receipt and Lua progression; live validation remains pending.

### Operator instructions for the next fresh solo job

- Source `zungle1`, destination `newstorage`, worker **Unbans**, 36-box maximum batch constrained by actual free slots, 32-second home cooldown override; no automatic restart during development.
- Exclude pure obsidian as before. Also exclude **Red shulker box**, type `e3ca7872-7a76-36a8-b2de-9074afc45940`: red box, 384 pistons, 384 redstone blocks, 192 enchanted golden apples and 768 cobwebs.
- Prioritize **TSR Shulkers TSR**, type `c5bab81b-b489-33ff-90ed-8329c971e302`: 1,152 shulker shells and 576 chests. This matches the operator's “Shulkers” label with obfuscated three-letter decoration on both sides.
- Prepare a fresh package from the updated host workflows, keeping the 32-second override. Do not reuse a .41 captured package: its Lua child cannot consume deferral receipts.

Validation/deployment: the final full Gradle build and native/core/host checks passed. The idle standalone host was updated to 0.13.42 with its existing data/configuration, LAN endpoint and credentials. An authenticated workflow preparation confirmed both new Lua programs, and Unbans reconnected idle. No live transfer was submitted; the worker still needs the 0.13.42 client for the native deferral path.

## 2026-10-07 — Manual carried-kit removal and fresh separated destinations

Task `dcbf60db-cf6e-4c5d-a506-ec50fddc6607` subsequently deposited 625 Shulkers and 523 Wings boxes (490 earlier Wings plus 33 of its final 35-box batch), then failed with `The requested kit is no longer carried; no deposit packet sent`. Its checkpoint had no pending click. The operator confirmed accidentally placing the remaining two kits and later manually depositing them. Do not diagnose that as lost server inventory or count those two as bot-verified deposits.

0.13.43 changes this exact reconciled/no-pending-click case to a warning and a completed partial batch. Native receipts and durable snapshots retain `deposited`/`missing`; shared solo, recovery and crew workflows accumulate actual confirmed delivery counts and continue. Pending clicks and disagreeing server/local snapshots retain their existing safety gates. Regression checks cover zero and partial missing batches, uncertain clicks, restart, shared Lua accounting and crew barriers. Existing jobs retain old scripts: upgrade both host and client and create fresh jobs for revised accounting.

With the operator's authorization, two fresh jobs were submitted on the existing 0.13.42 host/client: `baee5189-07a8-482c-be14-780df3bc865b` for Wings only to `newstorage`, followed by `bc119bd4-f9bc-4310-ba16-b30ef8c3d5cc` for pure obsidian only to `myobsidian` (`/home myobbytop`). Both use 36-box caps and 640-tick/32-second home cooldown overrides. Wings deposited 58 additional boxes, then its bounded revisit reported unresolved hopper columns; it did not prove complete source exhaustion. The obsidian job automatically started next and was observed carrying its first 35-box load toward `/home myobbytop`. No other kit types were selected. The live host was not restarted during these transfers.
