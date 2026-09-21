# Workers control room

The former **Bots** UI is now **Workers**. Use **Right Shift → Workers** or
**`.worker`**; `.bot` remains a compatibility alias. Internal `bot-*` filenames
and the workflow `bot.*` Lua namespace remain unchanged.

## Crew discovery and live joining (0.7.132)

An authenticated worker's **Workers → Crews** page lists every crew on that host with its current status, job, and connected count. A host can mark a live native highway job **Open joining** from either the in-game Jobs page or standalone WebUI. The worker can then join it directly; cross-crew admission uses the existing encrypted crew-key reassignment, reconnects automatically, installs the captured workflow/profile, and joins the moving highway without stopping its existing builders.

Host operators can also use **Move here** or the WebUI worker management panel. If the destination crew already has a live highway, its job is carried through the reconnect and admission is retried until the worker is eligible and near the work. Public discovery exposes labels and status only, never private crew keys. It is off by default and remains limited to workers already authenticated to this host. A worker with active source work must still be released cleanly before reassignment so jobs and supply recovery cannot be orphaned.

## Host-owned Auto TPY (0.7.129)

Enable **Auto TPY same-crew requests** from the client-host Workers connection panel or the standalone host WebUI Overview. When a connected worker sends an exact `/tpa <username>` command, the host accepts it only if that username uniquely identifies another fresh, online member of the same authenticated crew on the same Minecraft server. The recipient sends `/tpy <requester>` after 10 ticks. Workflow TPA keeps its existing durable handshake and is not accepted twice.

Workers receive this as host policy and cannot enable it themselves. The policy defaults off, expires unfulfilled observations after 10 seconds, and clears pending observations on disconnect. It does not parse arbitrary incoming chat or accept requests from players outside the crew.

As a last-line native-highway recovery, a worker that still makes no progress after the normal route reset may ask the host to TPA it to a healthy active crewmate. A worker already awaiting TPA recovery, outside the building phase, or itself stalled cannot be selected as the anchor. The host reuses the authenticated acceptance and replay journal above; supply recovery and ordinary travel never invoke this fallback. Native highway crews are capped at three workers.

## Resource exhaustion (0.7.59)

After enabled local sources (loose inventory, carried shulkers, ender chest and nested shulkers) are searched, a worker asks the crew for supplies. Unknown donor ender-chest contents are searched rather than treated as empty. Busy or temporarily unavailable potential donors remain retryable; only a completed local search with no available crew source causes `Resource exhausted: <resource>`.

Exhaustion disables Highway Builder and fails the worker's workflow action, releasing its local crew ownership. Other supplied workers continue; exhausted workers are not automatically toggled back on. Late-arriving usable supplies are rechecked before accepting a failure. Disconnect-on-toggle and external notifications are not enabled by this change. Update both the host and workers for crew-wide exhaustion decisions.

## Native highways on the standalone host (0.7.4)

The client and service now inherit the same Minecraft-free `HighwayCoordinator`: preparation payloads, verification windows, lane/supply handoffs, inventory-exchange decisions, membership redistribution, completion and durable END receipts. Native workflow definitions and resource policy/accounting are also shared. Local world observations, movement, block actions and inventory clicks remain in the worker/client adapter. A participating client host still verifies rows itself; the standalone service uses active workers' server-resolved reports.

`.bot export-workflow highway-default` now includes the current world's position/geometry and captured gameplay profiles. Submit that package to the service with worker UUIDs and `args.length`; inspect `status.highways` for progress, worker states, supply ownership and a bounded recent-event feed. Lanes, Roles and Break Order use the shared engine. Roles requires dedicated excavation and paving duties: excavators occupy the centered front row 5–16 blocks ahead, pavers retain distinct lanes behind it, and dual-duty workers run between both fronts. Real target-server testing is still required.

Finish/cancel existing client-hosted jobs before changing hosts. Native host restarts remain inspection-only: cancel the interrupted execution and use its saved verified progress to prepare remaining work; do not delete recovery journals. The service's current task adapter pauses the native crew during a worker's higher-priority task and resumes it when all targeted workers return to their Highway steps; automatic individual borrowing/return travel remains available only in client hosting. Launcher account management remains a later stage. [Setup and native test instructions](../host-service/README.md).

## Standalone host service (0.7.3)

An optional Java 25 process can now host authenticated workers without a Minecraft account or window. It supports multi-crew configuration, Lua task dispatch, captured profiles, priorities and pause/resume/cancel through a localhost-only authenticated API and CLI. Shared connection/listener code, task envelopes, status transitions, generic dispatch, journals and history policy are used by both host types. `.bot export-workflow <workflow-id>` exports a workflow and its captured gameplay profiles for the service.

This stage supports Wait, Travel, DropItems, Modules and SetProfile only. Workers must use 0.7.3+ and reject actions outside the host's capabilities before starting native execution. **Continue using the in-game host for coordinated highways, stash hunting and TPA.** The native crew lifecycle/supply controller has not been duplicated or replaced. Stop/end old native jobs before moving workers between host types. Service setup, API examples, limits and recovery instructions are in the [standalone host guide](../host-service/README.md).

## Explicit worker and world observations (0.7.2)

Client hosting now passes immutable player observations and captured server-resolved rows into the shared coordinator. TPA recipient selection, site-anchor selection, proximity checks, row masks and checkpoint decisions run without Minecraft in that library. A participating host remains authoritative; otherwise active workers supply verification. Unobserved rows remain unverified, and a missing participating host cannot silently delegate authority. World reads and all game actions still run on the client thread; no parallel Minecraft access was introduced.

Position freshness is now separate from task heartbeat freshness. Old positions cannot remain valid just because a worker keeps sending task status. Observations must come from the current connected session and are retained only after native message validation; disconnect clears the scheduler's observations. Wrong-world worker reports cannot verify highway rows. Return tasks capture the highway's server/dimension rather than the host player's current world. Queue behavior, mining/paving, supply actions, workflow formats and wire messages otherwise remain unchanged.

This is another extraction build, not an external host yet. Automated standalone observation/authority tests and client integration checks cover these boundaries; live multi-client testing is still needed. Keep testing 0.7.0 separately if desired, or test 0.7.2 on host and workers together. The next stage is the standalone host service; launcher UI, embedded game views and render throttling are not part of this patch.

## Shared coordinator foundation (0.7.1)

The client now consumes one Minecraft-free library for its existing Lua evaluator, task priority/FIFO selection, pause/resume/cancel decisions, missing-checkpoint recovery, terminal-state aggregation and teleport timing/scope rules. It is bundled inside the regular client JAR; do not install the development core JAR separately. Existing tasks, workflow scripts, protocol messages and UI remain unchanged.

This is the first extraction toward optional launcher-based hosting, not a functioning external host yet. Highway world verification, native actions, supply exchanges, client ticks, rendering and focus behavior are unchanged. Continue testing 0.7.0 normally; 0.7.1 is a separate refactor build. [Core build/test instructions and remaining boundaries](../coordinator-core/README.md).

## Crew inventory and resource exchanges (0.7.0)

Native highway bot jobs now capture **Highway Builder → Bots · Crew Inventory** with their geometry. The host's policy is sent to every participant. Pooling and automatic trash cleanup default on; standalone Highway Builder retains its existing inventory policy. Disable `crew-resource-pool` before creating a job to retain the previous bot inventory behavior. Existing records without a policy use the new defaults, so stop/recreate a job if you want to change its policy.

Default working targets are 512 loose paving blocks, three usable pickaxes, 64 food, 64 expendable filler and four ender chests, subject to available space. The initial preparation pass and returns arrange a pickaxe in hotbar slot 1, paving in slot 8 and food in slot 9; normal mining, eating and inventory protection still own their actions afterward. Targets are not requirements to begin work and do not force exact item/enchantment copies between accounts. The existing protected pickaxe/ender-chest settings can raise the respective safety floors.

Each bot reports loose stock, carried shulker contents and observed ender-chest contents separately, plus compact item manifests. Paired-chest observations cover 54 slots when available. Unknown/unopened storage is not invented or counted twice. Counts refresh once per second; reports made during container/cursor work are ineligible for donor selection. Existing supply-source settings still govern access: carried shulkers and ender-chest searching/conversion run in their native order before an exhausted resource becomes a crew request.

The host chooses the eligible donor with the largest surplus of the requested resource. Paving, usable pickaxes, food, filler and ender chests have shared accounting and retrieval support; ordinary missing-material/tool/food restocks automatically escalate to the crew. Equipment and shulkers are protected, not automatically gifted wholesale. A donor retrieves stored supplies through the existing restocker, then both bots meet on the rear highway. Remaining road workers keep their rolling lane allocation; with only two participants, neither can build while both are exchanging. An already-detached idle bot can donate a different resource while retaining its own pending request.

Donors retain at least 128 paving blocks (or the lower configured target), two usable picks, 16 food, up to 16 filler and two ender chests, with higher native reserves respected. Exchanges are serialized per crew. The recipient keeps an empty pickup slot and can reclaim expendable filler/trash for space. Each batch requires a server-confirmed sender inventory decrease and recipient inventory increase or pickup notification. The sender records intent before issuing a drop; reconnects reuse that receipt instead of sending the same batch again. Near-partner distance, clear passage and third-party pickup clearance are checked before throwing. Missing donors leave only the requester waiting; cancellation remains host-controlled.

Automatic trash cleanup protects tools, armor, elytras, shulkers, paving blocks, filler and ender chests. It also protects food, totems, rockets, pearls, XP bottles, ammunition, potions, named items and other container items. Typical mining drops such as quartz, gold nuggets, flint, gravel, coal and sticks are trash. Working filler defaults include netherrack, cobblestone, cobbled deepslate, blackstone, basalt, stone, deepslate, dirt and end stone; paving always takes precedence. Routine cleanup retains the configured filler amount rounded up to whole stacks, and supply-space recovery may discard it when necessary. As of 0.7.45, every worker throws trash backward along the completed road, away from highway progress, including during detached restocking. Lane position no longer changes trash direction.

**Test host and all workers on 0.7.0 together.** Start with a short, already-supported highway, one bot with tools but no paving stock, one well-stocked donor, and a third builder. Check local shulker use first, then the peer exchange, donor reserves, trash direction and return. Test full-inventory filler reclamation, paired-chest retrieval, disconnect/reconnect and cancellation separately. These flows have automated policy/accounting/compiled-path checks, not live server validation yet. Uncertain drops are deliberately not retried: inspect both inventories and the ground, then use **Workers → job inspection → Resolve item transfer**. This clears the transfer hold; it does not assert that lost items arrived. Native container recovery remains a separate prerequisite and cannot be bypassed by resolving an item exchange.

## Full meals and eating priority (0.6.13)

Auto Eat's hunger threshold now starts a meal rather than ending it after a single food item. Once eating has started, it continues until hunger reaches 20 (provided usable food remains), even if temporary inventory transactions or Auto Gap interrupt it. Disable, death and world/player changes clear the pending meal. Existing blacklist, named-food protection, cursor safety and health-triggered always-edible food policy remain unchanged.

Auto Eat now runs before normal-priority highway work. Highway Builder stops existing normal/packet mining and pending unsent actions before any work, crew hold or supply movement can compete with eating. Native food-use startup also cancels vanilla mining without depending on GUI-suppressed key handling. A five-second stall restarts the owned food use after a two-tick settle while retaining the selected food, aura pause and builder pause. It no longer releases the builder to mine for two seconds between retries. This reuses the existing item-use ownership and mining cleanup, without new input machinery.

Install **0.6.13 on each affected client**. Test a meal requiring multiple food items, crossing the hunger threshold after the first, with chat/pause/Bots screens open and another window focused. Also test food exhaustion and interrupted inventory access. Automated checks cover meal decisions, tick priority, mining cancellation and retry ownership; they do not reproduce the reported live background stall. If it recurs, collect `Auto Eat stalled:` lines from that client's `logs/latest.log`; these now include use duration, mining state and held food as well as focus/screen/slot/hunger. A genuinely paused singleplayer simulation or a mod suspending background game ticks remains outside these controls.

## Compact supply spacing and two-block overshoot (0.6.12)

New highway supply trips stage **three rows behind the verified crew front**, instead of ten. Additional new sites are spaced three rows apart instead of sixteen. These are staging targets, not a promise that players remain exactly three blocks apart: a queued supplier too close to a recovering container yields along the existing supported route before the reservation is granted. The existing 3.5-block pickup-clearance check remains unchanged. Compact yield routing uses that margin plus half a block for its target, and completes its acknowledgment even when the bot has just crossed the clearance boundary. Fresh conservative position reports can also release the admission barrier; missing telemetry cannot.

Compact reservations protect the rear supply workspace, including paired containers, temporary blockades and backward relocation, without fencing off the working crew's current/next row. Compact blocked-site retries relocate backward only. Physical container access stays serialized, actual recovery checks remain required, and travel retains checked footing/collision. Existing saved supply sites are not moved or narrowed mid-recovery; new sites keep the old separation from those legacy reservations. Rolling departures/rejoins and the 0.6.11 running fallback are retained.

Visible-crew return targets now overshoot **two blocks** in the returning lane. The complete extension must pass the existing server-resolved body/landing sweep and remain within the job endpoint; otherwise try one block, then the unextended rendezvous. Coarse out-of-view rendezvous and mining strategy are unchanged.

Update **host and every worker** to 0.6.12. Test simultaneous supply requests with two/three bots, paired-chest recovery, a blocked staging site, and both running and flying returns. Automated checks cover compact/legacy geometry, five simultaneous staging sites, barrier/yield receipts, all cardinal headings and bounded overshoot; live multi-account testing is still needed.

This build also extends the existing native item-use cancellation guard to **Auto Eat**. Starting food directly was already independent of GUIs, but vanilla's physical key-release handling could still stop it. The guard now checks the active module, exact player/world, food stack, slot/hand and clear inventory transaction state rather than relying on the use key staying down. The vanilla and Multitask paths share this guard; explicit Auto Eat cleanup still releases use normally. Test hunger consumption with chat and the multiplayer pause menu open while another Minecraft window is focused, including a focus change during eating. This does not unpause a genuinely paused singleplayer simulation or override another mod that suspends background game ticks.

Auto Eat also retries native food use at the end of the tick, after input handling, inventory synchronization and mining cleanup, when it still owns the food slot and eating is needed. It does not restart an already-active use animation. Failed starts appear in its status; the existing stalled-eating timeout logs focus, screen, selected slot, use state and hunger before retrying. The reported background-only stall has not been reproduced in a live client here; this patch closes the inspected interruption/timing paths and adds evidence for any remaining server/mod interaction.

## Running supply travel (0.6.11)

Detached highway suppliers can run both to their staging site and back to the moving crew without an elytra. The existing Vanilla ElytraFly route remains preferred when equipment and clearance allow it. Missing/unusable equipment or a blocked flight corridor falls through to the existing supported ground route. Failed/blocked takeoff allows five seconds of running before flight is retried; an airborne bot must land on checked road first. An active non-Vanilla ElytraFly mode with a glider equipped still needs to be switched to Vanilla or disabled, because it cannot accept navigation/braking requests.

Ground travel is capped at **7 blocks/sec at 20 game ticks/sec**, including diagonals; the last step is shortened to avoid overshooting the rendezvous. This is a temporary movement override, not a rewrite of Speed's mode, settings or enabled state. It ends when the travel leg ends or controls are released. A withheld/stale movement request brakes. World ownership, grounded travel, host connection/pause, item-use and fresh swept collision/footing checks remain in force. Player bodies remain passable, while protected supplies, mobs, holes, unknown chunks and terrain still matter.

No new scheduling, supply spacing, mining, container recovery or long-distance route policy is included. A 7-block/sec runner cannot catch a crew moving at 7 or faster; pause/slower crew movement or an elytra is still needed in that case. Other movement/timer modules and server corrections can affect the actual rate. Update host and workers to **0.6.11**. Test a supply cycle without an elytra, one with normal flight, a low-ceiling takeoff fallback, and host pause/cancel while running. Automated movement/ownership and crew checks do not replace these live tests.

## Rolling supply departures (0.6.10)

Supply departures no longer enter a crew-wide regroup/positioning barrier. The host immediately removes the departing worker from active road duties and redistributes those duties among the remaining builders. Their native jobs, rows, pending world confirmations and verification permits are retained; ordinary movement adjusts their lanes. Only the supplier drains its own pending actions and passes its existing recovery checks before starting the native supply journey. This works for both Lanes and Break Order, consecutive departures and the last worker leaving; if everyone needs supplies, the last verified checkpoint is retained until a supplier returns.

Departure and return messages now carry a per-worker revision. Duplicate or older messages cannot undo a newer supply cycle, stale requests/readiness cannot start another cycle, and missed updates are retried from worker receipts. The job generation, saved origin and start row do not change for these lane-only updates. Other workers' supply sites and physical recovery state remain intact. Cancellation and pause guards still apply. Fresh window messages wait for the recipient's supply-state receipt, without resetting its existing permit.

This build retains 0.6.9's return overshoot. It does **not** change the ten-block rear staging distance, sixteen-block spacing, mandatory elytra, speed, pickup protection or mining priority. Initial setup, manual roster changes and workflow borrowing retain their existing barriers. Automated checks cover repeated/stale transitions, consecutive/all-worker departures, checkpoint preservation, receipt gating and invalid sites; live multi-client testing remains necessary. Update **host and all workers** to 0.6.10.

## Supply-return overshoot (0.6.9)

This test build changes only the live-crew return destination. It aims one block farther forward in the returning worker's future lane, allowing the existing landing distance to bring it farther into the moving crew. Both flight and readiness use that same destination. The extension requires the existing loaded-chunk, server-resolved footing, collision, supply-protection and body-clearance sweep; otherwise the original lane rendezvous remains available. It never extends beyond the job's final row. Lanes and Break Order both use this policy.

Outside live-player tracking range, the host fallback is unchanged; the all-suppliers-out checkpoint is unchanged too. No changes to resupply spacing, mandatory elytra, movement speed, outbound handoffs, container recovery or mining order are included. Automated checks cover all cardinal directions, lane offsets, blocked extensions and job endpoints. Update host and workers to 0.6.9, then test returning suppliers before proceeding to the next optimization.

## Rolling supply rejoins (0.6.8)

Returning suppliers now target their future lane at the visible crew's current row, rather than the centerline two rows behind. Their collision-checked return flight continues to within 0.75 blocks of the destination before landing. Lanes restores the worker's roster-ordered walking slot; Break Order still shares the center lane. Targets follow live player models every tick, with the existing host fallback outside tracking range.

Only the supplier must land nearby and settle its container, cursor and outstanding block confirmations. The host then sends an incremental, idempotent lane update: active builders retain their native job, mining/paving confirmations, progress and verification permits. They steer toward their updated lanes during ordinary forward movement, without a crew-wide stop or positioning restart. Other detached suppliers retain their recovery state and service sites. The returner reanchors at its actual row within the job's existing bounds, not an old restock location.

Worker reports include their supply roster so a lost incremental update is retried after reconnect. Cancellation, manual pause, supply ownership and generation checks still apply. Row verification and the normal crew work window remain required; this does not authorize walking into unverified blocks. Initial setup, new-worker admission and outbound supply handoffs retain their existing barriers. Update **host and all workers** to 0.6.8. Automated checks cover geometry, repeated/multiple returns, unchanged assignment data and flight limits; live multi-client movement still needs testing.

## Background Auto Eat (0.6.7)

Auto Eat no longer requires every screen to be closed. Chat, the pause menu opened on focus loss and the Bots GUI do not block eating or its readiness signal to Auto Mend. Container menus and occupied inventory cursors still pause eating to avoid interfering with supply transfers. No global focus/pause settings or Auto Gap behavior are changed; a genuinely paused single-player simulation remains paused.

## Live-model supply returns (0.6.6)

Returning supply workers follow active, on-road crewmates found by UUID in their local client world. Every locally tracked player entity is eligible, regardless of camera direction or distance; the tab list does not count. The destination follows the furthest-forward visible crewmate every tick and stays two rows behind their feet on the highway centerline. Suppliers, off-duty workers and other crews are excluded.

Before any crewmate is tracked, the host supplies a coarse approach point every 20 ticks. This fallback does not override visible models. If every builder is resupplying, the verified road checkpoint remains the fallback so the first return can restart work.

Visibility starts the automatic return; it does not prematurely assign an airborne/distant bot a lane. After landing close to the live destination and settling container/cursor recovery, the host restores its lane without comparing against its old waypoint. Other workers continue during the flight. Brand-new-worker admission and manual off-duty leave/reenter thresholds are unchanged. Use 0.6.6 on the host and all workers.

## Efficiency test builds (0.6.2–0.6.5)

Four cumulative builds isolate task-journal writes, highway geometry reuse, crew ownership reuse and local timing diagnostics. See [the staged test guide](performance-builds.md). No transport protocol, verification authority, packet rate or inventory interaction delay changes are included.

## Highway refill capacity and player pass-through (0.6.1)

Material restocking now fills usable inventory capacity instead of a cached stack quota. The old quota credited spare ender chests as future obsidian, which could end a shulker visit after taking just one actual stack. Partial stacks can still be topped up, expendable filler can make room, and the configured empty-slot reserve plus container pickup slots remain protected. An exhausted source still closes and recovers normally; tool and food quotas are unchanged.

The shared corridor check for supply departure, takeoff, flight and final rendezvous approach ignores players. It continues checking mobs, actual collisions/terrain, loaded and verified footing, fluids, world bounds and other workers' protected supply-container areas. Flight speed and supply ownership are unchanged.

## Distributed stash hunting (0.6.0)

Open **Workers → Jobs → Create stash hunt**, select a crew and its workers, and set the rectangular bounds, altitude, strip half-width, speed ceiling and acceleration. Bounds expand outward to whole chunks. The host coordinates remotely; survey execution targets workers, not the host account. Native highways retain their existing limits; workflow queues now accept up to 16 workers within the host's existing 16-connection capacity.

Equip each worker with an elytra with more than 10 durability remaining. Set **ElytraFly → Vanilla** and a suitable **Horizontal Speed** on the host before queuing; task profiles capture those settings and Stash Finder's storage filters/thresholds. The survey ceiling is the lower of **Horizontal Speed × 20** and the job's blocks/sec ceiling (default 60, maximum 120). Acceleration defaults to 4 blocks/sec²; server position corrections and chunk stalls reduce the ramp. These are requested speeds at 20 client ticks/sec, not a promise of server acceptance or measured maximum throughput.

Workers receive stable, disjoint strips, fan out to their starting coordinates and sweep back and forth at the configured altitude. Each strip is `2 × radius + 1` chunks wide, default five. Choose a radius within the server-delivered view distance of every worker. Every chunk in a row must actually be received at FULL status and scanned before coverage advances. Missing chunks cause a local wait, never a successful empty scan. This is data coverage, not GPU mesh rendering: headless/low-render clients need not draw the terrain. A stopped worker's strip remains unfinished while other workers continue; this first version does not reassign its unfinished strips to another worker or rebalance a running survey's roster. Resume that worker, or cancel and queue a replacement area.

Findings use the existing notebook on each worker and the matching server/dimension notebook on the host. Counts are observations of storage blocks, not container contents. Host merges preserve existing notes and review state. Up to 16 findings per batch are sent over the existing authenticated crew connection, normally once per second for the active execution. The host atomically saves them before acknowledging; retries are deduplicated. A worker retains at most 128 pending findings and stops advancing if that fills, rather than dropping results. Cancelled executions can still deliver their saved pending findings while their job records exist. Deleting task history stops further host imports from that task; the worker notebook remains available. Notebooks retain their 10,000-finding limit and report capacity/save failures.

Pause, resume, cancel, priorities, immutable profile transfer, reconnect checkpoints and job history use the existing workflow system. Suspensions and completion land before restoring profiles. Survey navigation checks loaded terrain, fluids, world bounds and body clearance; it does not excavate, cross portals or automatically resupply/repair elytras. Use a clear flight altitude. Bounded local detours cannot solve arbitrary terrain; an obstructed corridor or unavailable safe landing is reported for intervention. Completion waits for host findings receipts and a safe landing. Restarted clients retain coverage but require the normal host Resume; an interrupted row is rescanned conservatively.

No P2P mesh is needed for this implementation: workers never send chunk payloads or per-block scan traffic, only existing task status and compact findings batches. Each worker performs its own coverage verification. Benchmark host load before adding another transport and its reconnect/ownership failure modes. Live flight tuning and a multi-client server soak test are still required.

## Non-pausing sealing footing recovery (0.5.15)

When sealing recovery rejects the backward footing, first try mining reachable work obstructions from the existing position. This uses native breaking and configured rotation, with tool reserves and crew ownership intact. Existing lava plugs, pending placements, containers, completed paving and players' occupied/support blocks are excluded. Advancing slow-block mining keeps its target and restarts the inactivity window.

After twice the current ping without mining progress, attempt the 0.15-block nudge without the footing or entity-wait veto. Normal game collisions still apply; this can step off an edge. If the nudge cannot complete within twice ping rounded to ticks plus two movement ticks, reconcile predictions at the actual position and retry. Neither unsafe footing nor a blocked nudge directly pauses the job. No teleportation, fabricated confirmations or discarded pending placements are involved; genuine missing-ACK and unrelated safety pauses remain. Both crew modes and standalone building use this path; supply-return recovery is unchanged.

## Ping-scaled sealing recovery (0.5.14)

The sealing stall threshold is now twice the player's current tab-list ping in milliseconds, measured with a monotonic clock and evaluated on client ticks. A fresh pass always gets a placement attempt before recovery; zero/unavailable ping permits recovery on the next tick, not an infinite pre-placement reset. The backward target is 0.15 blocks, with a .02-block arrival tolerance and directional overshoot detection. Actual travel remains subject to normal movement physics, safe footing, world bounds and obstruction checks.

The nudge starts in the timeout-detection tick. On arrival, a fresh sequenced verification probe is sent immediately, superseding older probes without clearing any block predictions. Its retries use twice ping rounded up to at least one tick; after a bounded three-probe burst, a late acknowledgment is still allowed before the existing no-response timeout. An old ACK cannot acknowledge a newer probe. After acknowledgment, the builder clears local action cooldowns and rescans in the same tick, without a post-recovery delay. Standard prediction handling outside sealing recovery and the separate one-block supply-return backstep are unchanged. Both crew modes and standalone Highway Builder use this sealing path.

## Retreat recovery and shared-lane positioning (0.5.13)

After a reach/sealing retreat, the builder's work row can be ahead of its physically reported row. Waiting for permission to enter the next work row without first catching up can deadlock against the crew window. Both Lanes and Break Order now return to the current work row before entering that permission wait. This does not mark movement complete, clear pending confirmations or enlarge permits. Return travel retains the existing route, footing, collision, prediction and combat checks. The target accounts for walking's .15-block arrival tolerance versus row reporting's .1-block tolerance.

Break Order uses the same center-lane position for every active builder; Lanes retains separate per-worker positions. Returning from a lateral work approach does not steer backward when already ahead. Active coworkers are ignored only by the walking obstruction check, not by paving/placement collision checks or supply pickup protections. Outsiders, detached supply runners and unrelated mobs remain obstructions. Standard Velocity entity-push suppression helps maintain the shared position; this does not disable server collision rules. Server behavior still needs in-game testing.

If a genuine verification wait remains, its status includes the reported row, verification base/mask, movement limit and stale-permit indicator. Update all crew clients to use the same standing-position policy.

## Mining and readiness optimization (0.5.12)

Double-mine candidates are classified with the best eligible tool for each block state, including tools still in inventory, rather than whatever item happens to be selected after paving. Selection shares the execution path's durability/enchantment rules and caches tool scores only within that candidate scan. Scanning does not move items or switch the active tool; actual mining still enforces pickaxe reserves and hotbar/cursor safety. Instant-breaking tools and SpeedMine retain their fast path; slow blocks retain double mining.

Nearby server block updates and prediction ACKs mark crew readiness dirty. Changed rows, phases and Break Order mining targets also trigger the next client-thread reporting pass. The host reacts to changed worker readiness on the tick it consumes the report. Multiple changes coalesce into one pass per tick; the existing two-tick active and ten-tick idle reporting cadence remains as a fallback. Cosmetic status/forecast updates do not trigger extra verification. Permits still require the existing participating-host/worker verification rules, and canceled or regrouping jobs cannot bypass their barriers.

This is the first optimization pass: preserving mining across ownership handoffs, multi-category supply trips and simultaneous container use are not enabled. Compare identical crews/settings in both clear paving and mixed slow-block excavation; no live-server throughput gain is claimed from the automated checks.

## Work sharing (0.5.11)

Choose **Workers → Jobs → Create native highway job / edit an unassigned job → Work sharing**. The dropdown is saved with the job, shown in its details and sent by the host to every worker. Existing jobs without this field use **Lanes**. Assigned jobs remain read-only; change modes only on an unclaimed unfinished job, then assign a crew. No mid-mine ownership switch is attempted. Geometry recapture preserves the selected mode.

- **Lanes:** existing per-column ownership and five-row movement window, unchanged.
- **Break Order:** every excavation-capable worker can mine the full face. Roster positions select Left → Right, Right → Left, Top → Bottom, Bottom → Top, then Center → Edges. Nearer rows still take priority and active slow mines stay selected. Since 0.5.13, workers share the center lane; movement permits keep them at most one row apart while allowing the existing speculative reach ahead.

The host relays each worker's active mining targets with existing work-window messages. A worker yields targets already held by another miner; an incumbent keeps a slow block, and simultaneous claims resolve in roster order. This is optimistic coordination, not a blocking claim handshake: brief duplicate starts remain possible before telemetry arrives. Target hints expire with stale permits and are cleared on generation changes; leaving or resupplying removes that worker from target arbitration. Rejoining recomputes scan order from the active roster. Paving and lava plugs keep single owners, and paving-only workflows cannot gain excavation permission. Existing next-row server verification, detached resupply, protected-container recovery and cancellation still apply. Standalone Highway Builder is unaffected. Update every account before testing; live-server throughput and collision behavior still need testing.

## Predictive restock loops (0.5.10)

A forecast could request an early pickaxe top-up while the restock completion check counted already-carried picks as enough. That allowed a box to be placed and recovered without taking anything, then the same hint to trigger again. Live shortages now outrank forecasts: zero paving blocks with four usable picks and a reserve of one selects materials, even if the material estimate still says no net depletion. If both are exhausted, a working pick remains the first priority for recovery.

Early pickaxe top-ups require an additional usable pick; material top-ups require additional paving stock and aim to fill the available inventory room. Stock is rechecked after recovery before leaving. A shulker that yielded nothing is excluded by its item/components snapshot for that restock, so identical stale contents cannot be retried endlessly; changed contents or another useful source remain eligible. Hotbar contents are checked again before placing the selected container. Confirmed pickup clears the old container's ownership flags before searching another source.

The forecast cooldown starts after the run finishes. If available sources cannot fulfill an additional top-up but the bot still has usable supplies, it returns to work and disables further forecast-triggered trips for the current job. Actual low-stock restocking remains enabled, and a new job resets forecast suppression. Exhausting actual supplies still requires resolving that shortage; no successful pickup or inventory delivery is fabricated. Tests cover these decisions and source filters; live 6b6t verification is still needed.

## Slow excavation and background verification (0.5.9)

The immediate passage, floor and railing excavation takes priority over speculative rows. Within a row, an active slow mine keeps its target; idle selection retains the normal ordering. A nearer speculative row can preempt farther ordinary mining. The crew's one-second nudge no longer cancels active mining or requests a global prediction flush.

Ordinary prediction probes no longer stop movement/input, invalidate queued rotations or pause all work while waiting for ACKs. Probes wait until mining is idle because even an air-targeted ABORT interrupts server-side mining. Foreground excavation and reachable paving continue while other receipts are pending; entry into the next row still requires excavation confirmation and verified footing. Explicit canceled-action reconciliation, crew handoff and container-recovery barriers retain their safety checks. Supply forecasts remain advisory, not proof of depletion or a requirement to advance. Live 6b6t throughput has not yet been measured for this build.

## Controller crash/reconnect loop (0.5.8)

Fixed a 0.5.7 regression: a crew with no current supply owner could call `contains(null)` on an immutable runner set, throwing a NullPointerException. The error handler then treated that programming error as a disconnected crew and repeatedly auto-resumed it through the same failing check. The no-owner case now returns no hold before looking up membership. Internal controller errors stop work and require explicit host **Resume** or **End job**; genuine connection losses still use the existing automatic reconnect path. Logs now include the full controller exception stack. Regression checks exercise fresh, legacy and multi-runner reservations, plus the distinction between transport loss and internal errors. Update host and every worker to 0.5.8.

## Detached supply crews and freeze fixes (0.5.7)

Native resupplies now always detach, including jobs with fewer than eleven completed rows. Once outstanding road actions settle, the remaining workers reposition into their smaller configuration: three builders become two (blocks 2 and 4 on a five-wide road), then one (block 3) if another leaves. The departing worker can travel while those builders reposition. Each runner keeps an independent rear staging site and pinned return leg. The first returner rejoins the front reached by the remaining builder(s), without resetting the other runner's supplies or site. If everyone needs supplies, the verified checkpoint stays put until the first return; a specialist's unfinishable cells likewise wait for that capability to return.

Travel and lane handoff no longer require the previous runner to finish recovering its container. Physical container use remains **one at a time**: runners stage separately, request access after arrival and keep all existing drop/ownership checks. A fresh report showing another worker outside the protected rear area suffices for clearance; missing or nearby reports do not. Supply token ownership survives road-generation changes, including a release/return message sent immediately before a lane handoff. Handoffs cannot change unrelated geometry, workflows or roster data. Cancellation still takes precedence.

The one-second native restock watchdog starts only **after** admission/travel, not while peers are clearing space. Previously it could repeatedly release the very reservation they were trying to clear. Liquid sealing no longer takes a container reservation at all: its deterministic plug ownership and protected-container checks remain, so a lava plug cannot leave all three `Forward` workers stuck behind a supply lock. Completed unused active reservations can release even if optional sealing is still pending.

Flight tries checked neighboring highway columns if the center line is blocked. It does not enter another runner's active container area from outside, and does not trap a player already passing when a grant arrives. Status now distinguishes handoff, travel, queued container access and clearance, rather than overwriting those details with a generic synchronization/restock wait.

Early staging may use the existing highway behind the new job's origin. Its actual footing, loaded chunks and clearance are still checked; this is not permission to fly through terrain or across an unverified gap. Missing elytras, genuinely blocked routes, unavailable workers and uncertain container recovery can still require attention. **Install 0.5.7 on the host and every worker.** These changes require live multi-account validation in addition to automated checks.

## Predictive supplies and worker recovery (0.5.6)

Worker details now show estimated **Paving** and **Picks** time to opening supplies, plus total **carried** runway including usable inventory-shulker contents. Paving uses session net inventory loss: mining/picking up the selected paving material offsets placements, and a stable or growing stock reports **no net depletion**. Pickaxes use observed durability burn, subtract the configured durability threshold and reserved picks, and report **learning** rather than infinite life when no wear has been observed. Sampling starts estimating after ten seconds of observed building; restock transfers, travel and manual pauses are excluded. Counters use constant memory, not an ever-growing sample history. Unknown ender-chest contents are not counted as carried stock.

The host can send the lowest-runway eligible builder on an early supply trip when the supply slot is idle, no ordinary request is queued, and the remaining builders cover its duties. The lead time adapts to observed supply-trip duration, bounded to 15–120 seconds. The worker rechecks its current inventory, room and native state before accepting. Hints cannot interrupt unconfirmed work or bypass recovery/ownership. Actual low-stock checks remain authoritative even if forecasts are missing or completely wrong; estimates never suppress a required restock. The existing restock flow handles these trips, with no separate supply engine.

Manually toggling an assigned Highway Builder off now preserves its execution; toggling it back on resumes that job instead of failing the ownership guard. The host can safely remove an unavailable worker from active lane duties while retaining it on the roster, provided another worker can cover each duty and outstanding actions/supplies settle. If disabled near the crew, automatic return arms only after leaving the 16-block horizontal join area; if already outside, it arms immediately. Returning on the same floor triggers lane restoration. Host Resume remains available. A disconnected client or an unresolved owned container still needs an honest handoff/recovery; this does not pretend a failed worker finished its blocks.

Crew walking, supply and confirmation stalls get one-second corrective checks. Active container mining is not aborted merely because it takes longer than a second. A wholly uncommitted supply attempt can choose another checked site within its existing reservation and release the unused lock so queued teammates can restock first. Placed containers, drops, cursor transfers and outstanding confirmations are preserved. Retries remain bounded; actual hazards are not cleared by pretending success.

Supply travel uses Vanilla ElytraFly for legs over two blocks, retries takeoff instead of switching the whole trip to running, and requests a usable carried elytra through Chest Swap. Missing equipment or unsafe/unloaded flight space waits rather than forcing flight. The shared collision-checked autopilot remains capped at one block/tick (nominally 20 blocks/sec), up from the former supply cap of 0.65. Only the final two blocks use walking/landing; returning workers must reach that close before lane handoff. Install 0.5.6 on **both host and workers**. Automated tests are not a substitute for live multi-account testing.

## Highway completion (0.5.5)

When every participant finishes and the final highway row is verified, the host saves the completion result and automatically releases the execution. Regrouping during cleanup cannot overwrite that result. Once worker acknowledgments and workflow cleanup finish, the job leaves **Jobs** and appears in **History**; there is no need to cancel a successful highway manually.

Older full-progress records incorrectly marked **Inspection required** are reconciled automatically only when their known crew is idle, no recovery journal or END acknowledgments remain, no linked workflow is pending, and no ended-supply archive needs inspection. Partial roads and genuinely unresolved recovery stay visible. An idle crew report no longer labels a nonexistent journal path as a recovery record.

## Supply and cancellation recovery (0.5.4)

Only active builders limit the five-row work window. A stalled detached returner no longer stops them at its position plus 16 rows. Each return leg has a fixed destination; a worker that reaches an outdated rendezvous gets another leg before the host attempts a lane handoff. Failed rejoin barriers retain the existing 20-second retry cooldown. Other builders can reserve supplies after the runner releases its container lock, even before it rejoins; a distant runner with fresh position telemetry need not acknowledge an unrelated pickup-space hold.

Before any container is permitted, a clearance hold that stalls for ten seconds is released for five seconds and retried. Native restocking also checks actual phase, supply-count, container and movement milestones independently of its usual idle timer. In 0.5.6, one second without a change triggers up to three local route/interaction retries unless mining is actively progressing. A completely uncommitted attempt can restart source selection and reacquire a shared reservation; outstanding containers, drops, protection blocks, cursor transactions and block confirmations prevent that reset. Restock and pickup timeouts use the existing bounded recoverable-pause retries. These are not permission to discard another player's shulker or ignore manual/hazard pauses.

Cancellation is separate from successful physical cleanup: new work stops, cleanup gets at most 30 seconds, then the existing END/receipt protocol clears the execution. Any unresolved supplies are saved in `bot-ended-<execution-id>-supplies.json` beside the crew journal, with a notification to inspect them manually. Late start/regroup/reconfigure messages cannot revive the cancelling execution. Offline workers still require reconnection for an honest acknowledgment; storage failures, unsafe profile restoration and uncertain non-highway actions remain explicit blockers, not fabricated success.

## Finished job history

Jobs shows unfinished work and anything still cleaning up. **History** is a separate, non-default page for finished workflow/native highway records. Linked records share an entry and deletion path; **Delete history** removes the linked finished records, and **Clear all job history** clears the finished collection. Deletion is permanent. Paused jobs, crew claims, pending cancellation acknowledgments, return handoffs, uncertain worker actions and profile restoration are not disposable history.

**Config → Workers → Job history retention days** defaults to **30**; **0** disables retention. Expiry runs at most once a minute, including while Workers is disabled. Each installation also expires its safely finished worker checkpoints. Older records without a finish date get their migration date, persisted so reopening the client does not reset the clock. Host deletion does not remotely erase another installation's recovery/checkpoint files. Existing bounded task storage may retire older finished history earlier when it reaches capacity.

## Supply clearance (0.5.3)

Workers use supported routes out of pickup space, including routes that must first move sideways or closer to the supplier. Movement no longer waits for all outstanding road ACKs, but granting the supply reservation still requires settled work and clearance. If a teammate blocks the actual shulker/chest pickup, the owner reports that position within its reservation; nearby workers yield around that location without relocating the supply session. Active and finished builders share the same retreat planner. No escape digging, unverified footing or bypass of other players' pickup checks is allowed; a genuinely blocked route still needs attention.

## Native highway recovery (0.5.2)

Install this version on the host and every worker. Mixed excavation/paving duties now wait on each other's unfinished cells without treating them as standalone obstruction failures. Mining-state selection uses the same ownership rules as mining, and each liquid plug has a single owner.

The host retries starts rejected after a ready report. A transport reconnect can resume the same in-memory execution on the same Minecraft world; it does not replay a restarted client or override a manual pause. Supply return rendezvous stay fixed during each return leg; 0.5.6 retries stalled flight rather than switching to long-distance walking. Returned suppliers remain in a rendezvous wait until the host assigns their new lanes; they no longer fall back into ordinary building against the distant supply-site origin. A non-cancellation lane handoff gets 30 seconds to settle, then keeps the original lanes and supply reservations and waits 20 seconds before another attempt.

Transient walking/verification pauses can retry up to three times, five seconds apart, without resetting the supply state or accepting predicted blocks as confirmed. Confirmed block work replenishes that retry budget. Real hazards, missing supplies, uncertain pickups and manual pauses still need attention. Task details now expose the native phase, blocker, pending confirmation counts and travel target instead of a generic host-managed wait. Automated checks cover policies and transport; live multi-account testing remains necessary.

Open **Right Shift → Workers**, next to Modules, or run **`.worker`**. Workers is a persistent system, not a module: opening its dashboard never stops connections or a job. Monocle remains a fork of Meteor; the old Swarm transport and highway integration have been substantially reworked here.

## Connect accounts

1. Install this build on every account. Use separate Minecraft profile/data folders.
2. On the controlling account choose **Host**. The **Default** crew preserves your previous connection key. Generate/copy a key if missing, then **Start host**.
3. On each other account choose **Worker**, paste the appropriate crew's key, and enable **Connect automatically**. Workers reconnect automatically and have no job-start button.
4. Keep `localhost:6969` for accounts on one computer. For another computer, set the host's listen address to its specific private LAN IP and use that IP as the worker's host address. Connection settings are applied explicitly with connections stopped.

The roster shows authenticated workers, their current crew, availability, job phase and lane. Connection failures and job events appear in the dashboard's Activity section. Different crew keys select isolated authenticated sessions on the same listening port; other crews do not receive that crew's job or ordinary remote-control messages.

## Crews, Jobs and Workflows

The Workers screen has three sections. **Crews** manages people and connections; **Jobs** manages saved work and progress; **Workflows** holds reusable work definitions. Highway geometry appears in a job's editor instead of the main crew controls.

Jobs also contains programmable workflow queues. Select one worker or a whole crew, supply arguments, and set a default or per-worker priority. Higher-priority jobs interrupt at safe checkpoints; equal-priority work stays FIFO. The host distributes the captured Lua code, nested dependencies and temporary gameplay profiles. Lua is bundled inside Monocle: no external interpreter is needed. See the [workflow programming guide](bot-workflows.md) for the API, examples and recovery rules.

## Workflow library

The first folder, **Highway Builder**, includes read-only **Highway Builder**, **Excavation crew**, **Paving crew** and **Carried supplies** definitions. Duplicate a definition to customize its name, folder, native actions or calls to another workflow. Folder paths can be nested, for example `Highway Builder/My roads`. Calls are expanded and checked for missing references, cycles, excessive nesting and repeated actions before saving. A referenced custom workflow cannot be deleted until its callers are changed.

**Excavating** and **Paving** are recurring per-row capabilities, not sequential whole-highway phases. Supply actions are tried on demand in their listed fallback order: **InventoryShulkers**, **EnderChestContents**, **EnderChestFarm**. Native maintenance, liquid sealing, cursor cleanup, inventory merging, filler disposal and owned-container recovery remain safeguards around these tasks; they cannot be removed by deleting an editor row. These native presets can be called from Lua programs with branching, nested calls and native travel/TPA actions. Arbitrary chat commands and stash databases are not exposed.

For assigned workers, source steps replace the personal Highway Builder source-enable toggles without overwriting them. Include **InventoryShulkers** to retrieve/open useful shulkers found inside an ender chest; an **EnderChestContents**-only supply plan takes loose items. Remove **EnderChestFarm** from a duplicate if that workflow must not consume spare ender chests for obsidian. Other native supply preferences, such as useful-shulker count and empty-shulker retention, still apply. Standalone Highway Builder keeps its existing settings and behavior.

A job captures its expanded workflow when created. Editing/deleting a library definition never changes that captured job. Existing jobs migrate from their saved highway operation. To use a revised definition, create a new job or use **Refresh workflow snapshot** on an unstarted, unclaimed job. Started jobs keep their captured workflow and geometry.

At assignment, each participant can use **Job workflow** or a specific work-capable workflow. For example, use a Highway Builder job with one Excavation crew worker and two Paving crew workers. Ownership is distributed among workers capable of each action, and the shared verification frontier requires the full job's work. The host rejects assignments missing a required capability or adding work outside the job definition. Supply-only definitions are available for calls, not as standalone jobs. Separate excavation-only and paving-only jobs are supported, but unrelated crews still cannot reserve overlapping work/supply areas; use mixed duties within one crew to cooperate on the same stretch.

In Crews, use **Create crew**, **Rename**, or **Delete crew**. A crew's saved identity and private key do not change when renamed. Select worker checkboxes and **Save roster** before assigning work. Deleting an idle crew does not delete its jobs; release its assignment and move connected workers first.

To move an idle connected worker, select the destination crew and use **Move here**. The worker itself must be free of its previous assignment, but other bots in the source/destination crews may keep building. The host sends a session-encrypted replacement key over the existing authenticated connection; the worker saves it and reconnects automatically. Wait for its new crew to appear before adding it to a job. This changes its connection credentials, not just a local label.

The host can coordinate multiple crews concurrently. A player belongs to one connection/crew at a time. The host account can build in at most one crew itself; disable **Include this host in the build** for other crews. Keep separate crews' work and supply areas apart: overlapping jobs are rejected because supply protection is coordinated within each crew, not between unrelated crews.

## Create and assign work

1. Stand at the intended road center, facing the highway direction. In Jobs, create a job and configure its name, length, workflow and Highway Builder geometry.
2. **Create job** saves that exact origin, server/dimension, direction, dimensions and materials. It does not start any bot. Changing the host's position later does not move the saved job.
3. Select a crew and **Assign crew**, then choose an unfinished, unclaimed job. Alternatively assign from Jobs. Choose whether the host also builds and any participant workflow overrides.
4. Keep the saved roster near the origin, or the saved progress position when resuming. Assignment automatically positions and starts the workers; do not enable their Highway Builders manually.

Before progress begins, **Capture current origin & layout** can replace a job's saved geometry. Started jobs retain their geometry; releasing them preserves their checkpoint. Jobs support 16–4096 road blocks, cardinal directions, and no more builders than floor columns. All participants must be on the same Minecraft server and dimension. Setup uses supported, same-level walking routes within 24 blocks; it does not dig, jump or teleport a distant bot to the job.

Five-wide/two-player crews stand on columns 2 and 4. Three-player crews stand on columns 1, 3 and 5. Each block has one owner; contested mining columns alternate across rows and height. Paving seams alternate by row, and outer workers handle the road edges/railings. This shares contested work 50/50 without constantly changing walking lanes.

## Sliding verification and nearby additions

Crew movement uses a five-row sliding window instead of waiting for every worker at every row. Future mining and paving are speculative: a missing distant block does not block an earlier verified row. The immediate next row must be server-resolved, including the road's paving and required excavation, before entering it. A worker cannot advance more than five rows ahead of the slowest member.

When the host participates in the build, its server-resolved world view is the verification authority; worker claims cannot override it. For worker-only crews, the host trusts the authenticated workers' server-resolved row reports. A participating host disconnect does not silently switch authority. World checks remain on each Minecraft client's own thread, with a bounded five-row report, rather than unsafe background reads of Minecraft world state.

**Blocks Ahead to Pave** and **Blocks Ahead to Break** both default to 5. Existing saved paving preferences are preserved. Extra mining uses spare mining budget and experimental reach up to the larger of normal mining reach and Place Range; the server may reject distant attempts. Immediate required mining retains normal reach/repositioning. Optional excavation does not intentionally open visible liquid inlets, mine supply containers, or remove newly placed liquid plugs. Rejected speculative work is retried as the crew approaches.

To admit another worker, connect/reassign it to the right crew and use **Join running job**. It must be idle, on the same floor, and within 16 blocks on each horizontal axis of the build front. The host finishes any supply recovery, settles outstanding actions, then redistributes lanes and automatically repositions the expanded crew. Assignment generations prevent old progress reports and commands from controlling the new lane layout. A paused supply owner may need host Resume to finish recovery. This admission/supply synchronization is deliberately separate from normal independent row movement.

Chat, Minecraft's multiplayer pause menu, and Monocle management screens can remain open during positioning/start. Inventory/container screens still block positioning. Opening advanced Highway Builder settings does not itself pause a running job; actual incompatible layout changes still trigger the builder's existing safety checks. This does not unpause a genuinely paused singleplayer world.

## Supplies without stopping the whole crew

After enough confirmed road exists, an eligible worker needing supplies hands off its lane through the same settle/reposition mechanism as joining a crew. It stays on the full job roster, retains its workflow and responds to host pause/resume/cancel. The active workers redistribute that worker's duties and continue; the supplier is excluded from their five-row spacing limit. Once recovered and back near the front, another synchronized handoff restores the original roster order and duties.

This first version reserves a supply site ten confirmed rows behind the checkpoint and uses the existing carried-container restock routines. It does not invent a remote stash destination. One supplier can be detached at a time. With too little completed road, a sole worker or no remaining worker capable of a required duty, the existing serialized resupply remains the fallback. Another worker needing supplies waits for the current supplier to recover; containers are never abandoned to make a lane handoff quicker.

Travel uses checked, supported road segments rather than trusting an unlimited straight line. As of 0.5.6, legs over two blocks require the existing Vanilla ElytraFly autopilot and a usable elytra; Chest Swap can equip a carried one. Missing chunks, holes, entities and insufficient clearance still matter. Returning workers do not hold the leaders back; the host can pause the crew deliberately if a worker needs help catching up.

Verification authority follows **active building membership**: an active host remains authoritative; explicitly detaching that host for supplies temporarily makes the active crew worker-only. Its authority is restored on rejoin. A disconnect never triggers that handoff. Ended or interrupted supply travel retains recovery information for inspection.

## Pause, inspect, release and cancel

- **Pause** is a host command for the selected crew. Its assignment and supplies stay tracked.
- **Resume** reaches every connected member, including a builder that paused locally without the crew phase previously changing. The dashboard shows the exact remaining blocker if it cannot resume safely.
- **Inspect job & recovery** shows the saved/live job, origin, lane/member state, supply reservation and recovery-file location, with copy controls. Check that location for containers or drops; the client cannot prove you have physically recovered every item.
- **Release crew** ends the current execution while keeping the saved job and its conservative progress checkpoint. The next crew may revisit the checkpoint row. Connected release allows 30 seconds for cleanup, then archives unresolved supplies for manual inspection and ends the execution rather than waiting indefinitely.
- **Cancel job** marks the work terminal while preserving its record until explicitly deleted. It releases the assigned execution through the same cleanup mechanism.
- A released crew/job becomes available after worker cancellation acknowledgments. Offline workers receive their matching cancellation when they reconnect. Each execution has a separate UUID, so a late cancellation cannot clear a newer run of the same saved job.
- **Delete job record** is explicit and only available after its crew is released. Deleting a crew and deleting work are separate actions.

A true connection loss/world change remains different from opening a menu: interrupted work stays stopped for inspection. Do not automatically restart around an abandoned container. After checking supplies, use host **Release crew**, wait for acknowledgments, then reassign the unfinished job. Workers do not need individual leave commands during normal host-managed operation.

Local module disabling remains an emergency stop. Do not use it as the normal crew control. `.bot highway leave` remains a local last-resort cleanup command when the original host is unavailable; inspect supplies first.

## Commands and migration

`.bot` opens the dashboard. `.bot jobs` lists saved job IDs/statuses. `.bot job <id> pause`, `resume`, `release confirm`, and `cancel confirm` control a saved job. The older `.bot highway` commands remain; `start <length>` creates a catalog job for the selected saved roster and `end confirm` cancels it. Existing follow/goto/mine/toggle/exec controls use `.bot` and are scoped to the selected crew. `.swarm` is no longer the command.

Connection settings migrate from the old module into `monocle-client/bots.nbt`. Independent highway geometry is stored atomically in `bot-jobs.json`; custom workflow definitions use `bot-workflows.json`. Host queues use `bot-tasks.json`, worker checkpoints use `bot-worker-tasks.json`, and TPA acceptance receipts use a separate bounded journal. Active/recovered older assignments remain inspectable. Profile reloads cannot replace crew keys/controllers while work or release receipts remain assigned. Do not share `bots.nbt`: it contains private crew keys. All accounts must upgrade together; protocol 6 rejects older clients.

## Security and verification

Use trusted accounts on loopback/private LAN only; no public port forwarding. Connections use fresh session challenges, ordered HMAC-authenticated messages, bounded queues and timeouts. Replacement crew keys use session-bound AES-GCM encryption. Ordinary coordinates/status traffic is authenticated but not encrypted; this is not a general TLS tunnel.

Automated checks cover transport authentication/isolation, credential handoff tampering and session binding, catalog round-trips and malformed-file preservation, settings migration/preset validation, registration, GUI-close lifecycle, lane ownership, speculative excavation, sliding-window authority/spacing, late-admission generations and recovery policies. Live multi-account testing is still required, especially three-worker joins, uneven mining speeds, host/worker-only authority, offline release acknowledgments and shulker recovery.

## Job configuration inspection (0.8.0)

On an in-game host, open **Workers → Jobs → Inspect → Inspect configuration &
supply capabilities**. Choose a captured profile or a worker's latest live request,
then select a module. The view is read-only; **Refresh acknowledgements** explicitly
updates the snapshot and **Copy configuration report** copies the displayed data.

Profiles are overlays captured at queue time. Unspecified settings retain worker
values. Native highway capabilities govern supply behavior separately from module
toggles. An accepted live request means the worker acknowledged that revision;
it is not proof that later workflow actions or local changes left it unchanged.
Only the latest live request is retained. This view does not claim to show a full
effective configuration or change future-job defaults.
