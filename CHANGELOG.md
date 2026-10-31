# Changelog

## 0.16.10 — The Credit Union Update

- Keep public reliability notes technical: replace operational incident records with summaries and use synthetic identities and stash names in examples and tests.

- Allow zero-cooldown users to order deliveries to different recipients while an earlier gift is waiting for teleport acceptance. Keep same-recipient duplicate protection, shared queue limits, stock reservations and per-category availability.
- Reply explicitly when an active delivery or full queue prevents admission, instead of silently discarding a Mike order. Keep normal free and credit cooldowns and the five-second duplicate guard.

## 0.16.9 — The Credit Union Update

- Support a second whisper receiver and delivery courier on the same host. Route new orders to a ready idle courier while another is busy, persist the assigned worker before charging, and keep credits, user cooldowns, stock reservations and limited-category slots shared.
- Run up to two bounded Mike provider requests concurrently. Announce courier handoffs accurately, name the actual courier in gift TPA notices, and serialize source pickup while allowing travel and player handoffs in parallel. Keep accepted jobs bound to their worker across disconnects and host restarts.

## 0.16.8 — The Credit Union Update

- Add shared limited-category availability: one kit per configured interval across free, paid, gifted and whitelist requests. Reserve the slot before dispatch, start the durable timer at confirmed handoff, release confirmed zero-delivery failures, and retain uncertain handoffs for review. Aliases and exact kit requests share the limit.
- Allow category stock floors of zero while requiring real complete stock. Apply availability to menus, raw listings and contents inspection; edit global category cooldowns in kitbot settings.

## 0.16.7 — The Credit Union Update

- Resolve kit recipients and named credit targets only from the selected courier's current server player list. Remove the external profile-service and connected-roster fallbacks; use the server-provided UUID without guessing identities.
- Add bounded, on-demand native worker lookups with connection/server binding, explicit missing/ambiguous/unavailable results and timeout diagnostics. Web UI kit deliveries query their selected worker; operator player lookup accepts an optional workerId.

## 0.16.6 — The Credit Union Update

- Allow per-category confirmed-stock minimums for specialty kits while preserving the normal default and expanded project-user access. Apply the same floor to compact menus, raw listings, contents inspection and exact free or paid orders, including pending reservations and family exclusions.
- Edit category stock floors in whisper kitbot settings. Older settings clients preserve existing overrides; explicit empty settings clear them. These settings do not alter storage-category rules or turn transfer estimates into confirmed stock.

## 0.16.5 — The Credit Union Update

- Offer a credit purchase when a free kit request is on cooldown and the requester can afford it. A two-minute, single-use yes/no confirmation preserves the exact kit, quantity and gift recipient, then rechecks current stock, balance and purchase cooldown before charging.
- Keep unaccepted offers outside the order and bank journals; declining, expiry, changed conversation, configuration changes and host restart cannot authorize a later purchase. Explicit buy commands remain available.

## 0.16.4 — The Credit Union Update

- Notify the requester, including gift senders, when a player-delivery TPA times out. Refund notices are committed with the confirmed settlement, retain cooldowns and use a durable outbox with courier acknowledgements and the normal DM queue. Free orders state that no credits were spent.
- Report a validated timeout reason independently of cleanup errors, and acknowledge retried host chat commands without re-sending them during the same client session.

## 0.16.3 — The Credit Union Update

- Add persistent kit-family exclusions across all kitbot menus, content lookup, category selection and direct orders, without changing stash inventory or operator transfers. Preserve exclusions when older settings clients save.

- Return successful kitbot deliveries by /kill and automatic respawn, keeping incidental pickups away from storage. Verify the respawn is within the source stash and disable the courier celebration before completing.
- Persist a separate per-server 61-second /kill cooldown, including manually issued commands. Send at the first eligible tick; checkpoint intent before issuing, retain it across reconnects, and bound failed attempts. Failed deliveries still secure undelivered cargo without killing the courier.

## 0.16.2 — The Credit Union Update

- Explain the cost and provide a direct buy command when Mike infers a credit purchase without current spending authorization, instead of reporting a generic handler error.

- Allow up to 27 kits per credit-paid delivery, independently of the free-order cap, and advertise the same limit to Mike.

- Aim courier drops at the visible recipient’s current position, hold safe footing when they walk away, and use shorter supported handoff offsets when a three-block approach is unavailable. Support fractional collision surfaces without relaxing edge protection.
- Celebrate only after a server-confirmed handoff with one-tick teabagging, spinning and randomized directions. Preserve saved Derp settings and disable the temporary mode at stash arrival, cancellation or disconnect.
- Secure confirmed undelivered kits in the source ender chest after handoff failures as well as TPA timeouts, avoiding elevated-storage recovery without an elytra.

## 0.16.1 — The Credit Union Update

- Keep free claims hourly and enforce a separate ten-minute credit-purchase interval, including after undeliverable orders.
- End unanswered player deliveries after a 60-second wall-clock deadline, return to the source and secure remaining kits in an accessible ender chest. Keep the requester cooldown and release the delivery queue.

## 0.16.0 — The Credit Union Update

- Add a durable SQLite kit-credit bank with lazy UUID accounts, transaction history, nonnegative balances and idempotent adjustments. Commit paid orders and their charges together; a credit buys one kit.
- Keep the free hourly kit independent. Explicit `buy` commands or authorized Mike credit orders spend earned credits without consuming or resetting the free allowance. Confirmed undelivered kits are refunded once; uncertain drops remain available for operator review.
- Credit purchases have a separate ten-minute cooldown; the free allowance remains hourly regardless of balance. Existing exemptions and shorter custom cooldowns remain intact.
- Add balance-only API keys with independent read/write permissions, one-time secret display and revocation. These keys cannot access host control, jobs, stashes, player lookup or key administration.
- Add a Credit bank page and Administrator user role. Mike receives a distinct Administrator prompt and credit gift/revoke tools only for fresh, UUID-verified administrators; host execution rechecks authorization.
- Move the standalone host's kitbot configuration and request journal into the credit database so financial changes and order state remain atomic. Existing private JSON is imported once and retained as a legacy backup.

## 0.15.56 — One free public kit (The Speedy Gonzalez Update)

- Limit non-whitelisted users to one free kit per hour in both explicit commands and Mike orders. Preserve whitelisted project access and custom caps.
- Document that future earned-credit balances remain independent of the free hourly allowance; no balance system is added in this patch.

## 0.15.55 — Cooldown replies (The Speedy Gonzalez Update)

- Reply deterministically to cooldown-blocked whispers with the remaining hours, minutes and seconds, calculated when the queued DM is sent. Coalesce repeated queued notices and retain the shared five-second DM limit.
- Cooldown replies do not call Mike, create an order, or extend the cooldown. Menus remain accessible, and gift cooldowns continue to belong to the requester.

## 0.15.54 — Bounded delivery recovery (The Speedy Gonzalez Update)

- Resume a suspended player delivery once from its saved checkpoint. Cancel inspection-required jobs, exhausted navigation retries, repeated interruptions, and deliveries unavailable for two minutes.
- Enforce five-minute idle and fifteen-minute total action deadlines, allowing configured home cooldown/warmup time and respecting operator pauses. Queued deliveries waiting for their turn are excluded.
- Cancellation retains uncertain transfer receipts without restarting inactive executors. Live uncertain drops get their bounded confirmation window, then can yield safely without trapping the courier forever.

## 0.15.53 — Gift notice delivery (The Speedy Gonzalez Update)

- Use a distinct valid UUID for recipient gift notices, preserving the five-second DM spacing without disconnecting the courier before its TPA. Socket checks now enforce the real client's message-ID validation.

## 0.15.52 — Personal acknowledgements and kit gifts (The Speedy Gonzalez Update)

- Generate Mike's acknowledgement from the confirmed public order after admission, including for exact `kit` commands. Generation cannot submit or replay a job; provider failures retain a deterministic fallback.
- Allow an explicitly requested gift recipient by username. Resolve the identity off the control thread, revalidate before dispatch, and charge only the verified requester's allowance. Legacy self-delivery cooldowns remain intact.
- Queue the requester acknowledgement first and the recipient gift notice second, atomically and under the shared five-second DM limit. Notices identify the giver, count and actual kit name; failed deliveries are not announced as successful gifts.

## 0.15.51 — Recover conversational kit choices (The Speedy Gonzalez Update)

- Retry rejected multi-tool/category/reference responses with explicit sequencing feedback and no actions performed. Shared request budgets prevent loops or duplicate deliveries.
- Refresh remembered exact kit choices before dispatch instead of failing when a player follows up on a previous recommendation. Changed contents are shown to Mike for reconsideration; unavailable variants never silently substitute another kit.
- Record private-data-free rejection codes in host activity and Mike status so validation failures can be distinguished from provider failures.

## 0.15.50 — Kit contents and exact selection (The Speedy Gonzalez Update)

- Keep category menus and ordinary category ordering as the main kitbot flow. Advanced requests can inspect named variants, rank by matching contents, require minimum item quantities, and query per-kit category ranges through a bounded read-only tool.
- Specific orders use public kit references in the existing delivery gate, with current stock, category membership, per-user limits and exact server-side source selection rechecked. An unavailable choice never silently falls back to a different variant.
- Inspection shares exact contents of one learned complete kit and qualitative availability, never stored copy counts or private source details. Placeable-block totals use generated Minecraft registry data verified against the game version.

## 0.15.49 — Courier food is optional (The Speedy Gonzalez Update)

- Player kit deliveries no longer require enchanted golden apples or a personal food reserve, including returning undelivered kits to storage. Auto Eat still uses available food; elytra maintenance and the reserve for stash transfers and scans remain unchanged.
- Mike can use a host-configured public provenance description when asked where kits came from, without access to private stash details.
- Add optional fictional stash-coordinate replies, generated independently of real locations and validated against axis/diagonal exclusion distances.
- Add a compact category menu with exact request words, in/out-of-stock groups and full-budget pagination. Mike selects host-formatted pages through a read-only tool; raw `kits` listings remain available.

## 0.15.48 — Server identities and public Mike (The Speedy Gonzalez Update)

- Default new kitbot setups to a 270-kit confirmed-stock cutoff. Distinct building kits receive individual request categories; the broad building category retains its storage purpose.
- Define a primary Minecraft address and explicit aliases under Servers. Updated native workers synchronize the identity before work, preserving canonical job/stash/cooldown keys when using another address. Historical catalogs are preserved and policy changes require finished work.
- In public mode, whitelisted project users have no hourly cooldown and a 109-kit confirmed-stock floor. Public users retain the normal cutoff and hourly limit; per-order caps and pending-delivery guards remain authoritative.
- Add opt-in public whisper kitbot access while retaining whitelist-only mode and per-user overrides. Public deliveries require a worker-resolved server player UUID and allow one order of up to five kits per hour; durable cooldowns cannot be evicted early.
- Mike receives the verified requesting username and explicit in-stock status. The system prompt labels unavailable kits in lists and identifies partial lists without exposing exact stock counts.

## Unreleased — Mike kit handler

- Mike keeps six recent exchanges per username and server for follow-up questions, with 24-hour expiry and a 128-conversation bound. Memory is session-only and contains only private request text and bounded queued replies; no tool calls, raw model reasoning, internal receipts or other players’ conversations. Output budgets and delivery checks remain unchanged.

- Kitbot can draw from multiple source stashes, with a configurable stock cutoff in the WebUI. Category seeding covers newly stocked variants without rewriting operator categories or storage reservations. Low-stock variants remain unavailable through Mike and explicit requests.

- Default Mike to Ollama Cloud's native API and add write-only bearer-key settings. API keys remain in owner-only host storage; API responses expose only a configured flag. Blank saves preserve the key, explicit clearing removes it, and endpoint changes require re-entering or clearing it. Local Ollama remains supported.

- Add an optional standalone-host Ollama handler for natural-language kitbot whispers. Mike receives only public category descriptions, sample item types and qualitative stock labels; exact counts, kit UUIDs, stash locations, homes and history stay on the host.
- One constrained tool proposes a category and quantity for the whisper sender. Existing whitelist, cooldown, cap, current-stock and durable-delivery checks remain authoritative. Inference runs off the coordinator thread with bounded requests/responses; stale or reconfigured proposals cannot create jobs. Explicit commands remain independent of Ollama.
- Configure Mike in Kits → Categories & whisper kitbot. Disabled by default; requires an operator-installed tool-capable model. Synthetic HTTP/socket and browser checks cover the boundary; live model quality and Minecraft delivery remain untested.

## 0.15.47 — Optional headless workers (The Speedy Gonzalez Update)

- Add opt-in `-Dmonocle.headless=true` compatibility for launcher-provided HeadlessMC backends. Font metadata uses the JDK, custom font/icon pixel generation is bypassed, and HUD work is skipped without changing saved preferences. Projection buffers initialize only when rendering needs them; normal launches keep their existing graphics path.
- Verified with HeadlessMC 3.0.0-RC8's Java agent on Debian/Java 25/Minecraft 26.2, with all display environment variables removed: created and joined a disposable single-player world, connected to an isolated standalone host, and completed host-assigned Wait and walking Travel jobs. The opt-in smoke fixture is built separately and excluded from the installable client. Live-server authentication, stash transfers and long-running operation remain untested; see [headless setup](docs/headless-workers.md).

## 0.15.46 — Packet-only Teabag Derp (The Speedy Gonzalez Update)

- Derp gains a Teabag mode with its own delay setting, defaulting to one tick between crouching and standing. It changes only the transmitted crouch bit and third-person model, preserving local movement, camera, collision pose and real crouch input. It uses vanilla input change detection instead of sending extra packets each frame.
- Switching modes or disabling Derp restores normal transmitted input. Existing rotation modes and their independent settings remain available; Teabag leaves movement rotations untouched.

## 0.15.45 — Lightweight passive stash reads (The Speedy Gonzalez Update)

- Passive observers filter other crews and host-owned baselines using definition metadata before loading inventory rows. Unchanged local watch lists reuse compact, detached snapshots, invalidated by committed stash changes and database reopenings.
- Local application of queued observations reads only the affected chest instead of the entire stash. Mirror creation, freshness conflicts, removal/discovery updates and journal-before-export ordering remain enforced. No stash schema change or rescan is required.

## 0.15.44 — Incremental execution checkpoints (The Speedy Gonzalez Update)

- Workers and the standalone host now checkpoint execution state in SQLite, updating changed run/task rows and storing shared workflow packages once. Unchanged history no longer gets serialized and rewritten for every inventory receipt or status update.
- Existing execution JSON is validated and imported automatically, preserving recovery settings, pending actions and cargo receipts; the original files remain untouched as backups. Commits still complete before actions or acknowledgements proceed, using WAL and full synchronous durability. Missing, replaced or externally modified execution databases require inspection instead of silently replaying old JSON.

## 0.15.43 — Skip covered sources and retain chest approaches (The Speedy Gonzalez Update)

- Withdrawals check the same vanilla solid-lid rule as scans and deposits before travelling or opening. Covered chests advance to another connected chest or source immediately; unread contents stay unresolved, never inferred empty, and pending cargo moves cannot be skipped.
- Stash flight clearance uses the active container target even after fall recovery resets route state, so a reachable, collision-checked chest approach is not lifted back into cruise clearance before opening.

## 0.15.42 — Recover from stalled optional mending (The Speedy Gonzalez Update)

- Auto Mend temporarily applies the downward client pitch during bottle use, including when another rotation precedes it; the normal view is restored afterward. Minecraft's item-use packet carries that pitch separately from movement rotation.
- A stash worker seeing no elytra repair progress for 200 maintenance ticks logs the remaining durability, XP count and Auto Mend status, then continues cargo work and retries after one minute if at least 64 durability remains. The cooldown survives native action handoffs; the last-glider reserve, spare swap, food, cursor and pending-transfer guards remain enforced.

## 0.15.41 — Find the complete ender-cargo view (The Speedy Gonzalez Update)

- Prefer nearby paired ender chests so a single-chest view cannot hide half of the transport buffer. Before sending any cargo clicks, a mismatching view can try other loaded ender chests, bounded to eight views without revisiting inspected positions. Actual contents and exact receipts remain required; interrupted or already-started moves retain their strict reconciliation checks.

## 0.15.40 — Deliver partial ender-buffer loads (The Speedy Gonzalez Update)

- A verified partial ender store now delivers the carried remainder and then drains the known buffer, instead of failing the transfer. Adopt the actual 27/54-slot capacity reported by each buffer operation, and avoid immediately rebuffering a rejected remainder. Exact cargo receipts remain required; this host workflow change supports existing workers.

## 0.15.39 — Refresh raced inventory slots before retrying (The Speedy Gonzalez Update)

- Check live source, destination and cursor slots before arming a confirmed stash move or deposit retry. A hopper filling a reopened destination now requests fresh server contents and reselects a usable slot through the existing reconciliation flow, instead of failing the job with an unsent move marked pending. Ordinary withdrawals and legacy ender deposits share the same preflight guard; uncertain sent clicks retain their existing receipt requirements.

## 0.15.38 — Accept larger native action reports (The Speedy Gonzalez Update)

- Share the Lua/native action size bound with the host status validator so larger source manifests do not disconnect a worker when it reports its active pickup action. Workflow results retain their separate existing limit.

## 0.15.37 — Keep source planning metadata host-side (The Speedy Gonzalez Update)

- Strip observed-source selection and scan-gap metadata from native pickup actions. The host retains both in the job and result, while workers receive only the selected positions and normal transfer fields.

## 0.15.36 — Larger bounded source manifests (The Speedy Gonzalez Update)

- Support up to 2,048 compact source positions per kit, with a 60,000-character host plan limit and bounded 64 KiB Lua state/action data. JSON node budgets accommodate numeric position triples while keeping nesting, script, frame and inventory-operation limits intact. Update both host and worker for larger manifests.

## 0.15.35 — Transfer verified stock around scan gaps (The Speedy Gonzalez Update)

- Sequential stash transfers can explicitly select observed sources only after a full, non-lazy scan with unread containers. Unread records remain unknown, confirmed-withdrawal sources remain revisitable, and results retain the scan-gap count rather than claiming the entire source is empty. Strict whole-source planning remains the default. This host change supports existing workers and ender transport.

## 0.15.34 — Catch worker falls (The Speedy Gonzalez Update)

- Native stash scans, transfers, travel and stash hunting catch unexpected falls with an equipped usable elytra and Vanilla ElytraFly. Recovery requests flight immediately, retries at four-tick intervals if needed, brakes the descent and replans movement after a short hover. Normal jumps, fluids and short supported landings do not trigger recovery; cancellation and inventory confirmation remain intact.
- After catching a fall, release only the worker's own Baritone goal immediately so an unfinished falling segment cannot block flight recovery. Normal navigation keeps its safe cancellation rules, and unrelated replacement goals are preserved.

## 0.15.33 — Rotate by allocated storage columns (The Speedy Gonzalez Update)

- Stash transfers can rotate through ordered kit types one destination column at a time, using preassigned neighboring columns for each kit. Inventory-only turns return overflow before switching types; verified exhaustion removes a type, while unavailable sources and exhausted allocations are reported separately without blocking the other types. This host workflow supports existing 0.15.32 clients.

## 0.15.32 — Reuse saved chest occupancy (The Speedy Gonzalez Update)

- Include verified slot occupancy in the compact stash catalog. New transfer jobs reuse SQLite observations from the past 24 hours to skip known empty withdrawal chests and full deposit chests, including within hopper chains. Later observations update availability; unknown, inferred and older contents retain physical checks, and zero-result source searches still perform their final verification pass.

## 0.15.31 — Keep ender transport after skipped sources (The Speedy Gonzalez Update)

- Keep using ender-chest transport after skipping an unresolved source while more source containers remain. Final deferred-source pickups still drain carried and buffered cargo before the workflow advances.

## 0.15.30 — Search the full reachable aisle (The Speedy Gonzalez Update)

- Search supported vertical shafts up to four blocks around destination chests, with an explicit interaction-reach bound. Dense hopper-fed rows can require a shaft beyond the previous two-block search; collision, footing and visibility checks still apply.

## 0.15.29 — Use actual aisle footing (The Speedy Gonzalez Update)

- Ground alignment drives the normal walking input with bounded movement and checks actual collision-shaped support using the player's real width, including fractional container tops. It no longer requires a padded full-block floor that can reject the worker's current safe footing.

## 0.15.28 — Align and retry destination aisles (The Speedy Gonzalez Update)

- Center precisely on the checked, supported aisle shaft after Baritone releases movement, before taking off. Bound the centering movement after speed modules so a nearby walking endpoint cannot launch a diagonal ascent through storage.
- Exhausted aisle flight retries land, wait ten seconds and replan while retaining the same cargo and deposit state, instead of ending the job. Inventory reconciliation and normal flight safety guards remain active.

## 0.15.27 — Recover inherited source menus (The Speedy Gonzalez Update)

- Close an idle container inherited from a previous action and open the requested source with fresh contents instead of timing out the transfer. Awaited openings, held cursors and unconfirmed inventory moves retain their reconciliation guards; confirmed pickups remain unchanged.

## 0.15.26 — Drain cargo before deferring a kit (The Speedy Gonzalez Update)

- Deliver the final partial pickup and any buffered kits before advancing past an unresolved hopper source. Previously, that partial pickup could be buffered immediately before the workflow advanced, leaving kits for the next type's empty-buffer check. This host workflow fix supports 0.15.24 workers; prepare a new package to use it.

## 0.15.25 — Return incidental overflow cargo (The Speedy Gonzalez Update)

- Column-fill workflows also return any remaining recognized incidental kits before moving to the next type. This workflow correction supports 0.15.24 workers; prepare a new package to use it.

## 0.15.24 — Fill allocated columns (The Speedy Gonzalez Update)

- Sequential stash transfers can fill their allocated compatible columns, return excess carried and ender-buffer cargo to the source, then continue with the next kit type. Returned kits update source stock and are separate from destination deliveries.
- Record destination-limited kit types without claiming the source is exhausted. Existing until-empty transfers retain capacity waiting, and explicit single-column delivery behavior stays unchanged.

## 0.15.23 — Wait for destination capacity (The Speedy Gonzalez Update)

- Ordinary stash deposits land and retry the host every ten seconds when compatible storage is unavailable. New space or changed reservations can restart the deposit without recreating the job; verified receipts, carried cargo and ender-buffer state remain intact.
- Explicit single-column limits and uncertain inventory moves retain their safeguards. Update both host and worker for the capacity response; older workers retain the existing stop behavior.

## 0.15.22 — Recover occupied deposit slots (The Speedy Gonzalez Update)

- Recover when the planned destination fills while the exact kit remains on the cursor: close and reopen, prove the kit returned to inventory, then select fresh space without crediting the occupied slot as a deposit. Missing cargo and uncertain returns remain protected.
- A replaced destination menu enters the existing fresh-menu reconciliation path instead of waiting on the obsolete menu until timeout.

## 0.15.21 — Recover changed ender-chest menus (The Speedy Gonzalez Update)

- Reopen changed ender-cargo menus instead of failing immediately. Preserve pending clicks and reconcile exact conserved cargo before sending more moves, including when the replacement snapshot arrives before the next tick.
- Keep pending ender-buffer moves in the worker's unresolved-action guard, alongside pickups and deposits.

## 0.15.20 — Aisle overflow takeoff recovery (The Speedy Gonzalez Update)

- Skip visibly blocked deposit chests before moving toward them. Wait out collision recovery before aisle takeoff rather than consuming all three retries while the landing guard is active.

## 0.15.19 — Capacity-sized transfers and destination overflow (The Speedy Gonzalez Update)

- Send the full verified capacity-sized withdrawal or deposit batch without a four/five-move cap, including ender transport and hopper-refilled bottom chests. Sources with an active output hopper retain exact single-kit moves; small batches and uncertain transfers retain their fallback.
- Skip blocked, unopened or unreadable destination chests before any click, try other chests, and request another compatible host column when necessary. Unreadable columns are excluded for this run without being falsely marked full. Locked single-column jobs retain their kits instead of overflowing elsewhere.

## 0.15.18 — Ender transport chest selection (The Speedy Gonzalez Update)

- Choose the nearest loaded ender chest around the stash's height, including reachable floor chests across the storage, instead of selecting buried chests with a similar X/Z position.

## 0.15.17 — Ender-chest cargo transport (The Speedy Gonzalez Update)

- Solo sequential stash transfers can fill a nearby ender chest, collect another inventory, then drain both at the destination using server-confirmed 27/54-slot capacity. Internal buffer moves do not count as stash deposits or withdrawals.
- Resume interrupted buffer clicks by reopening and reconciling conserved cargo; preserve personal ender contents and stop on ambiguous cargo changes.
- Full hopper-fed destination inputs advance down the connected column before requesting another column. Existing source withdrawals already inspect upper chests when the bottom cannot supply a load.

## 0.15.16 — Solo stash transfer recovery audit (The Speedy Gonzalez Update)

- Import kit types for manual destination reservations across dimensions on the same Minecraft server, retaining crew/server isolation in both the API and column picker.
- Let interrupted couriers land before profile recovery in the current stash action's dimension, as well as the original job world.
- Mark column fill uncertain when a chest disappears or verified cargo exceeds mapped capacity, rather than retaining a false exact-full claim.
- Keep counts uncertain when a column footprint changes or mapped capacity shrinks, and support 1,024 source positions with numeric triples above 512 so large inventories retain every source inside the existing byte budget.

## 0.15.15 — Cross-dimension stash transfers and alternating columns (The Speedy Gonzalez Update)

- Stash deliveries can use an explicit destination dimension on the same Minecraft server. Each home leg, reservation, observation and verified stock receipt uses that stash's world.
- Recognize mapped alternating chest stacks in multi-aisle storage, include all their rows in capacity and deposit validation, and allocate assumed-empty columns in physical aisle order.
- The control panel offers cross-dimension destinations and projects alternating columns together. Detailed column fill ledgers stay off compact worker catalog frames.
- Larger transfers retain up to 512 source positions in a compact manifest, subject to the existing workflow byte limit, rather than failing at the 55th matching chest.

## 0.15.14 — Empty-storage aisle deposits (The Speedy Gonzalez Update)

- Assume-empty destinations walk through supported aisles to a checked chest-side position before taking off vertically. Keep real collision, loaded-chunk and hazard checks without requiring the open-air three-block cruise buffer in narrow aisles.
- Prefer the highest usable destination chest and descend the checked shaft before completing a deposit or changing columns. Existing stash scans, withdrawals and ordinary destination movement remain unchanged.
- Persist confirmed net kit transfers per initially empty destination column, retain them across jobs/restarts, and expose mapped capacity and uncertainty in host summaries and column-map tooltips.

## 0.15.13 — Hopper-fed sorting pickups (The Speedy Gonzalez Update)

- Detect enabled hopper feeds to either chest half independently of the withdrawal route. Sorting still leases exact containers, but now confirms identical source-slot refills with the existing exact destination, carried-count and empty-cursor checks.
- Keep physically refilling sources on single-kit confirmed transfers rather than static-container bulk withdrawal. Static chests, locked hoppers and hoppers pointing away retain their existing checks.

## 0.15.12 — Recoverable sorting verification (The Speedy Gonzalez Update)

- Retry unread sorting columns up to three times with fresh full-column reads; retain stock, geometry and unknown-kit safety checks.
- Allow Resume on a stopped, cargo-free verification failure using a fresh worker run and the existing sorting ledger. Preserve moved counts, completed columns and reservations; show verification retries and a recovery button in the stash panel. Client 0.15.11 remains compatible.

## 0.15.11 — Airborne resume recovery (The Speedy Gonzalez Update)

- An explicit host Resume can land a hovering worker before applying its task profile. Keep inventory actions and profile changes blocked until grounded, and release landing control on pause, cancellation, disconnect or world/owner mismatch.
- Accept the unchanged default pickup index added by native withdrawal validation in both instruction checks and exact receipt accounting; reject changed pickup positions.
- Include the pickup index and incomplete-kit selector in generated sort withdrawals, matching the existing native resupply contract.
- Compare validated receipt positions as integer coordinates so Lua's `237.0` and a catalog's `237` identify the same container.

## 0.15.10 — Chest-edge landing recovery (The Speedy Gonzalez Update)

- Preserve an ongoing landing search across repeated wall-collision damage reports rather than restarting it indefinitely.
- Allow a checked two-block rise before moving over verified landing support when the worker is below a chest lip. Check both flight segments, standing headroom and support; retain direct landings and route retreat as fallbacks.
- Include the host-side sorting scan dimension compatibility fix from the first live test.

## 0.15.9 — Experimental stash sorting (The Speedy Gonzalez Update)

- Add physical growth gaps reserved for the category immediately to their left, with separate growth, buffer and unassigned-space metrics.
- Add an explicit single-courier write mode using existing scan, withdrawal, deposit, movement and mending engines. Journal each instruction and in-transit batch; verify columns before use and after packing.
- Preserve protected columns and unknown cargo, validate hopper footprints, keep cancelled cargo records and block conflicting work until completion or explicit recovery/release.
- Prevent partial column verification from resetting full-stash stock estimates. Keep internal moves neutral to stash totals and use strict kit-variant matching only for sorting.
- Accept the game runtime's matching job-dimension enrichment on sorting scans without relaxing the journaled coordinates, inventory instructions or cross-dimension checks.

## 0.15.8 — Storage groups and kitbot names (The Speedy Gonzalez Update)

- Separate broad storage categories from narrower kitbot request categories with Storage, Kitbot or Both usage. Untagged definitions retain Both behavior.
- Sorting previews ignore kitbot-only rules; whisper requests ignore storage-only rules. Keep existing reservations and old worker placement messages compatible.
- Expose category usage in the control panel and allow 128 definitions across both purposes. Physical sorting remains read-only.

## 0.15.4 — Skip confirmed-full deposit columns (The Speedy Gonzalez Update)

- Skip columns whose complete scanned chest rows are confirmed full, as well as columns exhausted by an earlier deposit. Do not revisit known-full columns merely because no other destination remains.
- Confirmed withdrawals clear full-column exclusions; fresh observations of free space restore eligibility. Reserved columns invalidated by a confirmed bottom-chest withdrawal may be reopened for validation, never deposited into without the existing observation checks.
- Preserve explicit single-column completion and shared-column ownership. Apply the allocator change to standalone and ingame hosts without changing transfer execution or scan behavior.

## 0.15.3 — Stash flight corridor recovery (The Speedy Gonzalez Update)

- Use the same padded player width for gaining cruise clearance and following cruise routes, avoiding hovering just inside the route's safety margin.
- When both projected cruise waypoints lie inside a long storage bank, use a bounded collision-checked detour toward the destination instead of repeatedly searching for an occupied waypoint. Keep the three-block cruise buffer and existing approach, landing and durability safeguards.

## 0.15.2 — Responsive catalog reads (The Speedy Gonzalez Update)

- Cache unchanged SQLite stash summaries using committed-write and external-connection revision checks; preserve fresh transfer planning and revision guards. Avoid a second summary for non-overlapping storage.
- Stream canonical revision hashes instead of allocating a recursively sorted copy of every nested kit. Move standalone-host catalog/watch refreshes off the coordinator monitor, and stop rebuilding catalogs during roster checkpoints.
- Status APIs still obtain fresh catalog data, outside the worker coordination lock. Background catalog failures retain the last good snapshot and expose `catalogError`.

## 0.15.1 — Grouped scan positions (The Speedy Gonzalez Update)

- Scan-only bounded coverage planning selects safe positions that expose multiple unread containers, using conservative eye reach, actual visibility, supported collision surfaces and travel cost. Reuse a stop for its remaining group rather than navigating to every chest separately.
- Checked Baritone goals and native flight routes handle the chosen positions; blocked/unreachable optimization falls back to the existing single-container approach. Match Baritone's chest-top/slab feet convention, retain movement/read backpressure safety, and recompute ephemeral plans on recovery.
- Bound target, candidate and visibility work, throttle replanning, and expose coverage position/count/search-time telemetry. Transfers, stock semantics and the running scan are unchanged until the new client is installed.

## 0.15.0 — The Speedy Gonzalez Update

- Replace stash snapshot/delta storage with SQLite on clients and both host types. Indexed per-container rows and transactional metadata eliminate whole-catalog copies from scan/live-observation ingestion; retain existing kit, reservation, stock and API behavior.
- Commit scan observations and recovery state together with WAL and full durability. Concurrent reader snapshots, bounded cached writers, rollback and schema validation keep failures explicit and receipts durable.
- Intentional fresh start: no legacy stash import. Old JSON catalogs, caches and outboxes remain untouched and are ignored. Redefine storage and start new stash jobs after upgrading; see `docs/stash-sqlite.md` for layout, backup and verification details.
- Bundle the existing SQLite JDBC dependency in the standalone host as well as the client, including license notices. Add SQL rollback, process-crash, reader concurrency and large-catalog write checks.

## 0.14.49 — Moving stash reads (The Reckoning Update)

- Scans can keep walking toward nearby unread storage while a server-confirmed read is pending, braking when the active container approaches the reach margin, the upload buffer fills, or normal movement/eating safety requires it. Transfers retain their existing movement behavior.
- Full scans choose the closer end of each four-block band instead of blindly alternating across empty bands. Drain each band's candidate queue before reversing; existing band checkpoints transition only at a drained band boundary without reinterpreting visited cells.

## 0.14.48 — Pipelined stash scans (The Reckoning Update)

- Separate container reading/navigation from host delivery: scans buffer up to 32 observations / 2 MiB, with eight uploads in flight and eight bounded background writes. Only durable reads upload; buffers apply backpressure instead of silently dropping observations.
- Save per-action scan recovery journals off the game thread instead of rewriting all worker history for every read/acknowledgement. Restore legacy checkpoints, replay unacknowledged reads idempotently, and do not finish until local writes and host receipts drain.
- Both host adapters coalesce scan receipt checkpoints and acknowledge only after the grouped task journal is committed. Item withdrawals, deposits, courier jobs and their transactional checkpoint paths are unchanged.
- Reduce scan-only post-contents settling to one tick and expose queued observations/bytes, pending writes, durable-read and host-confirmed counters for bottleneck diagnosis.

## 0.14.47 — Scan acknowledgement congestion (The Reckoning Update)

- Both hosts re-acknowledge previously committed scan findings without rewriting the whole task journal; standalone-host duplicate retries no longer count as new container-read events.
- Passive observation leaves scanner-owned container opens to the explicit scanner, including solo scans and either half of a double chest. Ordinary manual opens, transfer reads and geometry updates retain live observation.
- Scan findings send immediately before display telemetry, with duplicate retries every three seconds instead of one. Periodic read-only scan movement checkpoints/status run every five seconds; new observations, host acknowledgements and mutating actions retain their immediate durable checkpoints.

## 0.14.46 — Continuous Auto Wither boundary placement (The Reckoning Update)

- Zero-delay autopilot can send the confirmed structure's final skull and the next reachable stem in the same two-placement tick, including the hotbar item switch. Preserve the next stem's acknowledgement across spawn confirmation and final-head retries.
- Consume already-confirmed paired placements together instead of spending separate ticks advancing each acknowledgement. Count limits, material budgets, site/reach checks, delays and final-head prerequisites remain enforced.

## 0.14.45 — Direct Auto Wither placement (The Reckoning Update)

- Auto Wither sends native block interactions directly instead of deferring every part behind a player-rotation callback, like Highway Builder with placement rotation off.
- Retain the two-placement-per-tick budget, paired prediction checks, server acknowledgement/retry handling and hotbar restoration. The final summoning head is still never pipelined speculatively.
- A cancelled interaction does not claim a sent placement or authorize its paired follow-up. Actual throughput remains dependent on server confirmation.

## 0.14.44 — Stash movement recovery (The Reckoning Update)

- Retry retained Baritone goals with no path/calculation after a quiet interval, bounded to three requests per navigation lease. Do not restart active calculations, completed goals, foreign controllers or eating/combat pauses.
- Any walking chest approach that stays stationary for 60 ticks can safely hand movement back to flight, instead of retaining an impossible walking fallback forever. This includes nearby scan/deposit chests, not just withdrawals. Collision cooldowns and elytra safety reserves still apply.
- Actual flight progress renews the consecutive failure budget. Airborne non-gliding workers initiate the same bounded glide-confirmation retries as grounded takeoffs.

## 0.14.43 — Transfer stock accounting (The Reckoning Update)

- Keep durable transfer-adjusted kit/resource estimates separate from observed chest contents. Hopper drainage and source snapshot invalidation no longer silently erase the transfer balance.
- Apply only new confirmed deposit/withdrawal receipt entries, including actual partial-kit quantities. Receipt checkpoints survive restart and full-scan reconciliation.
- Expose `transferKitCounts` / `transferItems` and uncertainty on stash summaries; kit stock queries add `estimatedComplete` / `estimatedIncomplete`. The panel labels estimates separately from observed stock, and overlapping stashes do not sum transfer estimates.
- A completed full non-lazy, gap-free host scan reconciles a balance only after every known non-removed container has been read since its last transfer. Existing incomplete historical counts require a rescan; historical delivery totals are not blindly added.

## 0.14.42 — Restore side Wither line (The Reckoning Update)

- Revert the overhead Auto Wither experiment, restoring the previously working two-side layout, flight height, pacing, and five-block placement check.

## 0.14.41 — Overhead Wither corridor (The Reckoning Update)

- Auto Wither autopilot completes two side structures and three overhead per row, leaving two clear center blocks and flying halfway above the lower air block.
- Placement reach follows the player's interaction range, capped conservatively at 4.5 blocks. The existing two-placement tick budget and server confirmation remain unchanged.
- No lower structures are placed beneath the flight corridor.

## 0.14.40 — Owned eating and combat pauses (The Reckoning Update)

- Auto Eat, Auto Gap, Kill Aura and Bow Aimbot share identity-based path pause ownership. A second module can acquire an already-paused path, including pending navigation; only the last owner resumes the backend. Duplicate acquisition/release and unknown-owner releases are harmless. Existing path-manager backend interfaces are unchanged.
- Bow Aimbot releases its pause when disabled, when the bow/arrows/target disappear, or when aiming stops. Disabling combat pause while attacking also releases only that module's pause. Food and combat module cleanup uses the same owned release.
- Tests exercise overlapping meal/combat pauses, release ordering, duplicate and unknown releases, distinct equal-valued owners and all four module call sites. Includes 0.14.38–39's flight-first stash approaches and safe Baritone movement handoffs. Client update only.

## 0.14.39 — Safe Baritone ownership handoffs (The Reckoning Update)

- Worker steering and native-action busy checks distinguish currently walking from retained paused paths, background calculations, accepted navigation processes and residual forced movement keys. Applying a workflow profile also waits for Baritone ownership to end; eating/combat walking-status semantics are unchanged.
- Stash navigation retains its lease and read-only settings when safe cancellation is deferred or a cancelled calculation has not drained. Steering, target changes, suspension and subsequent actions wait for release. A temporary client tick listener completes cleanup even after the action stops; it unregisters when done.
- Replacement custom goals and newly active foreign processes are preserved. Cleanup is idempotent and clears residual input only after the owned path and calculation are gone. No force-cancellation, Baritone fork, host update or inventory replay is added.
- Behavioral checks execute the real lease against simulated Baritone APIs: paused/calculating ownership, unsafe cancellation, delayed calculation drainage, asynchronous settings restoration, repeat cleanup and foreign-goal/process preservation. Live movement timing remains an installation-time test.

## 0.14.38 — Flight-first stash approaches (The Reckoning Update)

- Stash scans, withdrawals, deposits and personal-cache approaches prefer usable Vanilla ElytraFly beyond opening distance, including short trips and elevated chests outside the former 24-block flight window. Already-reachable containers still open without a takeoff; missing equipment retains walking.
- Failed takeoffs, missing interaction routes and missing cruise clearance share a three-failure budget per target with a short retry delay. Only then does that target use walking; a new chest gets a fresh budget. Existing collision landing, safe support, durability and inventory confirmation guards remain.
- Walking status sent to the host names Baritone's current movement, destination, forced jump and path-request count. Client checkpoints additionally retain the actual goal, controller and executor progress; flight checkpoints include target, failure count, launch tick and landing state. No Baritone fork or replacement is required.

## 0.14.37 — Escape-safe stash source menus (The Reckoning Update)

- Closing a source menu with Escape, or replacing it before an inventory move is issued, invalidates the old menu ID and server snapshot. The worker requests a fresh opening instead of indefinitely ignoring contents from the new menu. Confirmed pickups, source progress and personal inventory protection remain intact.
- Pending single/bulk pickups, ender-chest moves and discards retain their existing confirmation/reconciliation guards; a closed UI cannot authorize replay. Menu mismatches and unconfirmed ender-chest/discard operations now retain bounded waits even when normal menu callbacks are unavailable. Client update; scan and destination reopen behavior is unchanged.

## 0.14.36 — Stash movement handoffs (The Reckoning Update)

- Owned stash navigation clears Baritone's residual forced keys after a completed or safely cancelled path, and restores only Baritone's exact idle input to keyboard control. Unrelated goals/controllers, active path executors and other custom inputs remain untouched.
- Worker steering closes its own walking lease before acquiring flight input, waiting for a deferred safe path cancellation instead of racing it. Genuine input replacement still stops the action, but now reports expected, actual and previous input classes, player replacement and action type to the game log and host failure detail.
- Client update; running jobs and host configuration are unchanged.

## 0.14.35 — Personal kitbot quantity caps (The Reckoning Update)

- Each whitelisted user can set `maxKits` from 1–36; omitted values and new editor entries default to five kits per request. The host checks the cap before source reads or dispatch. An over-cap request receives a limit reply, records a rejection, and starts no job or personal cooldown; the duplicate guard still applies.
- Per-user caps are independent of cooldowns, including zero cooldown. Existing accepted jobs and manual operator-created deliveries are unchanged. The whitelist editor saves both settings per person; legacy whitelist definitions remain valid. Host update only.
- Whitelisted users can whisper `kits`, `kits 2`, etc. for stable, alphabetically paginated raw stocked-kit names with request-category labels. Pages fit the 256-character client boundary including `/msg <username>` and end with `,...` when more follow. Listings never consume a delivery quota. Kitbot replies and operator DMs share a bounded, session-only five-second-per-worker FIFO; wrong-world and two-minute-old replies are discarded.

## 0.14.34 — Personal kitbot cooldowns and rejected-TPA recovery (The Reckoning Update)

- Whitelist entries can set their own `cooldownSeconds`; the editor defaults new users to one hour and accepts zero to disable their quota. The five-second duplicate guard and one outstanding request per recipient still apply. Legacy entries retain the configured shared fallback, and edits preserve request history.
- A native worker recognizes the exact server `Player not found!` response during the first ten seconds after issuing TPA and settles the rejected request immediately, without clearing genuinely uncertain teleports or interpreting public chat as a rejection.
- Newly captured return-home courier workflows redeposit only undelivered kits at their source after a failed TPA or safely settled handoff. The job reports unsuccessful delivery after verified redeposit; successful deliveries and explicit cancellation remain unchanged. Immediate rejection recognition requires a client update; per-user cooldowns and newly captured recovery workflows require a host update.

## 0.14.33 — Whitelist kitbot and category columns (The Reckoning Update)

- Standalone hosts accept exact incoming `kit <category> [1–36]` whispers from an operator-defined username/UUID whitelist. The configured courier, crew, server and ready-world state gate requests. Public chat, sent echoes, followups and lobby messages cannot enqueue work. Accepted submissions and requester cooldowns survive restarts; ordinary low-priority Kit Delivery jobs perform pickup, TPA, verified handoff and return home.
- Crew/world categories support selected kit IDs or kits containing only specified items. Operators can seed categories from complete, directly observed source stock at 250+ per variant; partial, ambiguous, inferred and historical-alias totals cannot satisfy availability. Queued deliveries reserve stock. The Kits panel exposes category editing, whitelist/configuration and request receipts.
- Column maps can reserve individual columns or shift-selected ranges for categories. Updated workers receive the rule and verify actual contents before depositing; older workers skip category columns. Existing kit-only reservations are preserved. No LLM, natural-language requests, gear locker or recipient-pickup attribution is included.

## 0.14.32 — Direct inventory courier bursts (The Reckoning Update)

- Couriers keep the supported three-block handoff, then throw directly from main-inventory and hotbar slots in batches of up to four clicks per tick, reusing the stash transfer packet budget. No hotbar staging or per-kit acknowledgement is required for new batches.
- Each batch is durably prepared, checks each source stack and the cursor before emitting, then requires exact server-confirmed source changes and total removal before counting delivery. Uncertain bursts never replay after interruption; legacy pending single drops and hotbar swaps retain their reconciliation path. Partial-stack requests keep vanilla's one-item-or-whole-stack semantics.
- The player's own Inventory screen can remain open during drops; other containers and held cursors still block transfers. Regression checks cover full inventories, batching bounds, supplies left untouched, partial-stack precision, malformed journals, missing confirmations and recovery wiring. Client update only when the host already provides 0.14.30+ courier workflows.

## 0.14.31 — Hotbar courier handoffs (The Reckoning Update)

- Player couriers prefer a supported walking spot three blocks from the recipient's captured location, face it and aim the throw. Unsafe footing still blocks movement; recipient chasing and terrain edits remain excluded.
- Drop Items drains matching hotbar stacks, stages the next main-inventory stack using one cursor-free hotbar swap, and waits for exact server confirmation before dropping. Empty hotbar slots are preferred; a full hotbar swaps its selected item into the source slot without discarding it. Staging never counts as delivery, and uncertain swaps or drops are never replayed after pause/restart.
- Includes 0.14.30's proximity-based TPA and WebUI source-return option. New client required for hotbar cycling and three-block handoffs; new host/workflow required for courier handoff jobs. Existing captured jobs are not rewritten.

## 0.14.30 — Fixed-location player kit handoffs (The Reckoning Update)

- Player Kit Delivery now snapshots the recipient's position/facing on observed TPA arrival, walks toward a supported spot about 1.75 blocks in front (or a supported side), and faces the original position for a nominal vanilla item-toss arc. It does not chase the recipient. The whole short corridor and actual movement are checked for loaded, unobstructed, non-hazardous ground; handoff movement is capped after Speed and other movement modules. Unknown footing and ledges retain the kits.
- External TPA arrival uses the recipient's live proximity rather than a starting-dimension match. A recipient already nearby can satisfy the action before a TPA is issued; already-issued requests still wait out their configured warmup, and late host commands cannot restart completed teleport actions. Known crew/stash dimension guards remain unchanged.
- Both WebUI delivery forms default new player jobs to returning to the source stash after the final drop. Return travel reuses that worker's stash home, dimension, cooldown, warmup and arrival checks. A timed-out TPA or safely settled failed handoff returns before reporting failure; uncertain issued drops still require receipt reconciliation and are never replayed. Jobs without `returnHome` preserve their final-location behavior, and running captured workflows do not change.
- Tests cover fixed anchors, unsafe corridors, arc fitting, cross-dimension/nearby arrival, failed-request return, preserved old capability lists, full host socket dispatch and WebUI idempotent submission. Requires updated client and host. Gear lockers, pickup attribution, `/kill` returns and chat/LLM automation are not included; real server movement and throw scatter still need live validation.

## 0.14.29 — Defer optional stash mending without XP (The Reckoning Update)

- Missing personal XP is recoverable while the equipped elytra retains the 64-durability takeoff reserve. Stash jobs log the shortage, expose `stashMendDeferred` in action diagnostics and continue their existing pickup/deposit route; nearby personal caches and carried pure-XP supply boxes are still preferred when available. Replenished XP resumes mending normally.
- Below the takeoff reserve, a safe inventory handoff lands and equips the healthiest usable loose spare elytra, retaining the worn elytra in its inventory slot. Curse of Binding, unknown inventories, held cursors and native pending moves still prohibit replacement. No kits are unpacked or discarded to find spares.
- Food shortages, uncertain supply receipts, malformed cache journals and the final flight/landing reserves remain failures. Tests cover XP-only deferral, reserve boundaries, pending-click exclusion and spare selection. Client update required; no host or workflow migration. Built separately; live replacement remains an operator decision.

## 0.14.28 — Keep empty-source memory across supply trips (The Reckoning Update)

- Worker task ownership, rather than action stop/disconnect, now resets the stash empty-source cache. `/home` world handoffs, host reconnects and suspension cleanup preserve learned empties when the same task resumes. Starting a different task clears them; client restart still starts fresh.
- Retains server/dimension scoping, 4096-entry bounds, 30-minute expiry, mixed/changed-chain guards and the fresh zero-result verification pass. No change to inventory confirmations, source exhaustion claims or hopper propagation timing. Regression checks cover same-task reuse, different-task reset, rejected identities and runtime lifecycle wiring. Includes 0.14.26–27's pickup and consumable recovery fixes. Client update required; no host or workflow change.

## 0.14.27 — Wait for consumable inventory after reconnect (The Reckoning Update)

- Stash food/XP maintenance waits for a full, server-applied player inventory snapshot after reconnect, player replacement or respawn. World/chunk readiness and Lobby Skip are unchanged. Missing inventory snapshots use the existing read-only resync request, and the action reports synchronization as a healthy wait rather than a shortage or stall.
- Food reserves include the offhand exactly once and reuse Auto Eat's blacklist/named-food policy. Obsolete personal restock requests cancel before navigation or withdrawal, retaining persistent cache records; issued withdrawals and held cursors keep their reconciliation guards. Shortage errors now include synchronization status, carried supply count and offhand contents.
- Regression checks cover lifecycle resets, wrong/incomplete menus, replacement players, 42 offhand apples, named/blacklisted food, request changes and pending-click exclusion. Includes 0.14.26's silent-pickup recovery. Client update required; no host or workflow change.

## 0.14.26 — Recover silent stash pickup responses (The Reckoning Update)

- If a single-kit pickup receives no full server inventory response within 20 client ticks, reopen the source for read-only reconciliation rather than timing out with zero recovery attempts. Fresh server contents still decide whether the pickup happened or remained unapplied; the old click is never blindly replayed or counted.
- Reuses the existing two-reopen limit and deferred-source handling. Held cursors, missing inventory baselines, ambiguous cargo changes and unconfirmed checkpoint recovery remain guarded. Regression checks cover missing/delayed replies, old-menu rejection, applied and unapplied outcomes, duplicate confirmations and the retry bound. Client update required; no host or workflow change.

## 0.14.25 — Stash accounting foundation (The Reckoning Update)

- Both hosts process newly confirmed entries in growing withdrawal receipts instead of ignoring everything after the first report for an action. A catalog-side prefix checkpoint commits alongside invalidation; retries after a lost host checkpoint preserve newer observations, and changed confirmed prefixes are rejected.
- Deposits retain exact kit contents and destination slots for each verified move, including partial kits and confirmed burst prefixes. Evidence survives worker checkpoints and is retained in host run inspection; it is kept out of the bounded Lua result. Older checkpoints retain their verified counts but explicitly lack complete item evidence.
- Deposit observations reuse the passive observer's manifest construction, including legacy-NBT shulker contents. Observed item totals exclude Lazy Mode estimates, exposed separately as `estimatedItems`; detailed estimates stay out of compact worker catalog frames. New identical empty reads refresh freshness without duplicate delivery doing so.
- This is the first storage-model increment, not conserved stock accounting yet: connected-column flow reconciliation, external opening invalidation and yield/travel-time source ranking remain pending. Existing snapshots can still drift as hoppers redistribute stock; no automatic rescan, live deployment or movement change is included.

## Unreleased — Kit stock comparisons

- The standalone operator API adds `GET /v1/kits/{kitTypeId}/stock`: alias-aware, per-stash family counts reconstructed from recorded box contents. TSR-style beacon/chest-minecart versions remain separate even after a merged catalog exemplar changes. Ambiguous partial kits and unknown contents are explicit; recorded coverage, stale observations and overlapping selections remain qualified.
- Reporting is read-only and leaves existing kit identities, aliases, reservations and running transfers unchanged. Regression checks cover cross-stash mismatches, exemplar replacement, ambiguous partial kits, legacy aliases, unknown IDs and snapshot isolation.

## 0.14.24 — Retry stash travel and inspect empties sooner (The Reckoning Update)

- A stash `/home` that never arrives retries after its configured warmup plus the existing 60-second arrival grace, respecting the configured cooldown. Retries no longer terminate after three sends or trigger a generic 120-second stall pause; the checkpoint-compatible counter reports `3+` once saturated. A confirmed arrival after reconnect also renews the actual cooldown instead of retaining the predicted warmup completion time. Navigation and inventory moves remain gated on verified arrival, and operator cancellation still stops the job.
- Until-empty carried-kit transfers inspect upper chests while an empty hopper outlet refills, rather than holding each upper chest open for another refill wait. A fresh final outlet check still requires the full TPS-adjusted column propagation window before declaring the column empty; navigation/inspection time contributes to that window. Interrupted checks retain their final-outlet obligation and conservatively restart its clock. Other transfer policies, crew source leases, deposit timing and inventory confirmation guards are unchanged.
- Regression checks cover continuing home retries/watchdog behavior, overlapping empty-chest inspection, slow server ticks, checkpoint recovery and pending-click exclusion. Client update required; no host or captured-workflow migration.

## 0.14.23 — Continue after unapplied stash pickups (The Reckoning Update)

- Drain-style kit withdrawals defer a troublesome source after two reopened server inventories prove the attempted pickup unapplied. Confirmed cargo is retained and other sources continue; the skipped source is reported as deferred, not empty or exhausted. No uncertain click is replayed or counted as delivered.
- Occupied/previously held cursors, changed or missing cargo, ambiguous gains, stale menus, deposits and locally leased crew sources retain their safety guards. Regression checks cover bounded recovery, retained receipts, deferred-source results and checkpoint restoration. Includes 0.14.22's faster checked cruise. Client update required; built separately without replacing running clients.

## 0.14.22 — Faster checked stash cruise (The Reckoning Update)

- Worker stash cruise now permits up to 2.5 blocks/tick (50 BPS at 20 TPS), bounded by the configured ElytraFly speed. Cruise honors ElytraFly acceleration/start/step and resets its ramp when braking, correcting, recovering or starting new travel. Close chest approaches, clearance exits, escape recovery and landing retain their existing speeds.
- Faster motion still sweeps the entire per-tick path against loaded-world collision/hazard checks and three-block cruise clearance, without overshooting waypoints. Other flight callers keep their existing requested speeds. Regression checks cover the ceiling, acceleration, slower user settings, reset paths and intervening obstacles. Client update required; no host or workflow migration. Live throughput and server acceptance remain to be measured.

## 0.14.21 — Recover from chest-side flight damage (The Reckoning Update)

- Server-reported wall damage during stash work interrupts navigation, lands using verified support and prefers walking for 200 ticks before permitting flight again. Stash flight planning adds a 0.12-block horizontal body-width margin; fractional landing/support geometry remains unchanged.
- Flight corrections and damage recovery run on the client thread and ignore events queued for a replaced world/player. The food reserve remains enforced: collision recovery does not conjure supplies or bypass survival failures. This mitigates the observed collision loop; its exact server-side cause still requires live testing.
- Regression checks cover the bounded walking window, stale tick clocks, wall-skimming geometry, client-thread dispatch and separation from landing geometry. Includes 0.14.20's pickup recovery fix. Client update required; no host change.

## 0.14.20 — Tolerate survival changes during pickup recovery (The Reckoning Update)

- An unconfirmed single-kit withdrawal can reopen and reselect after eating, tool wear, equipment changes or non-kit pickups, provided existing shulkers remain unchanged, the kit count has not increased and a fresh server menu proves the old click unapplied. A returned cursor kit can also be confirmed alongside unrelated non-kit changes, using exact identity, one newly occupied slot and a +1 kit count.
- Reuses the shared non-cargo comparison already used by deposit bursts. Changed/lost shulkers, ambiguous extra kit gains, held cursors, old menus and uncertain restarts retain their existing guards. Withdrawal diagnostics include changed inventory slots with before/after items, counts, damage and whether cargo changed.
- Regression checks cover food consumption, tool damage, incidental pearls, foreign-kit additions, relocated cursor returns, cargo loss and duplicate replies. Client update required; no host change. Running transfers remain on their installed version until an operator requests the update.

## 0.14.19 — Capacity-sized kit deposits (The Reckoning Update)

- Stash and ender-chest deposit batches plan the minimum of eligible carried kits, server-confirmed empty destination slots and the remaining job quantity, up to 36 kits. Small deposits still use the existing exact-slot path; withdrawals retain their four-kit/static and single-kit/hopper policies.
- Larger plans retain the existing four-move-per-tick packet budget. Each part must be authoritatively confirmed before another is sent, with source identity, destination availability and quick-move ordering rechecked. Confirmation timeouts track the last issued part; reopening never resumes unsent work or replays uncertain clicks. Diagnostics distinguish planned, sent and verified counts.
- Offline regression checks cover full inventories, partial capacity, job limits, eligibility, packet pacing, changed slots, hopper drainage, unissued receipts and fallback ownership. Client update required; no host or protocol change. Held for the next operator-requested update; running workers are unchanged.

## 0.14.18 — Land on real storage surfaces (The Reckoning Update)

- Worker landing uses actual collision surfaces and their fractional heights instead of requiring full-cube floors. Chest lids, slabs and suitable hopper surfaces can support a landing, with the entire footprint supported and standing headroom verified. Transit checks use the actual gliding body; void, portal, fluid and damaging-support guards remain. Grid walking retains its existing full-floor policy.
- Nearby supported landing spots are checked before retracing a long flight trail. Detailed landing blockers survive both stash clearance checks and workflow/profile handoff status, instead of being replaced by generic "Landing before continuing" messages.
- Offline checks cover real chest/hopper collision shapes, fractional surfaces at large stash coordinates, connected chest seams versus gaps, unsupported edges, low transit ceilings, insufficient standing headroom, hazards and bounded searches. Client update required; no host, catalog or workflow migration. Live landing and a complete transfer cycle remain to be verified.

## 0.14.17 — Reach top-row storage (The Reckoning Update)

- ElytraFly autopilot no longer treats the world's placement ceiling as a flight ceiling. Stash cruise paths above top-row chests can execute; actual swept-body collisions, loaded chunks, borders, hazards and the lower void boundary remain guarded.
- Stash flights retain three-block clearance for cruising, but their bounded final container approach uses actual body clearance. Opening, holding, steering and escape recovery use the same approach policy, so dense storage does not force a climb away from a reachable container. Solo scans, withdrawals and deposits share this fix.
- Flight-stall evidence includes approach/cruise phase, cruise clearance, build height and equipped-glider durability. Equipment failures identify the equipped chest item and remaining durability; the 64-point takeoff and 32-point landing reserve are unchanged. Offline checks cover top-height cruising and dense-container approaches. Live verification requires updated workers.

## 0.14.16 — Tolerate incidental deposit pickups (The Reckoning Update)

- Kit deposits tolerate non-shulker pickups in slots freed by verified transfers, including ender pearls. Exact kit destinations still prove successful deposits; after a fresh-menu barrier, absent kits replaced by non-kit items remain logged as unverified/missing instead of blocking the load.
- Exact-slot deposit recovery also tolerates unrelated non-kit inventory changes and reselects the original kit if closing the menu returned it elsewhere. Changed shulkers, held cursors and occupied withdrawal destinations retain their ownership guards. Offline regression tests cover pearl pickups during full and partial bursts and exact-slot recovery.

## 0.14.15 — Recover uncertain bulk deposits (The Reckoning Update)

- Kit deposits reconcile a different, freshly opened server menu before abandoning an uncertain burst. Boxes no longer carried are logged as unverified/missing, never counted as successful deposits or replayed; retained boxes continue through exact-slot transfers with bulk disabled for that action.
- Personal food changes no longer block this recovery. Unexpected shulker replacements, changes to unplanned cargo, held cursors and unread menus retain their safety gates. Current container contents are sent back through the existing catalog/column acknowledgement path.
- Bulk diagnostics include the differing player slots and matching destination slots. Partial missing-kit outcomes survive checkpoints without ending the job; the shared workflow already consumes these outcomes and continues its routes.

## 0.14.14 — Personal worker supplies (The Reckoning Update)

- Stash scans and kit transfers can land and yield the equipped Mending elytra to Auto Mend, then resume the original action. Built-in stash profiles enable 30% → 95% maintenance without stealing Strict Auto Totem's offhand. Both host implementations and solo scans use the shared executor.
- Personal pure enchanted-apple/XP shulkers can become persistent consumable caches. Their locations and placement intents survive jobs and client restarts, scoped per account/server/dimension; nearby caches are re-observed before taking a server-confirmed stack. Mixed kits and matching cargo are protected. Pending native inventory moves retain priority.
- Dead workers await respawn rather than generating navigation-stall retries. Supply/mending state is exposed in native checkpoints with bounded failure deadlines. This first stage requires carried supplies or nearby saved caches; distant stash provisioning and live validation remain pending. See [personal supplies](docs/worker-personal-supplies.md).

This file records major Monocle milestones. Detailed development and failure analysis lives in the linked guides, incident records, and Git history.

## 0.14.13 — Recover stalled stash approaches (The Reckoning Update)

- Stash flight eligibility includes descending to lower chests. Solo source approaches with 60 ticks of no useful progress can try the existing collision-checked flight route, with durability and takeoff-clearance guards, before their normal approach deadline.
- Exhausted navigation-watchdog retries defer safe until-empty solo Carry sources rather than suspending the whole transfer. Confirmed kits and unresolved-source diagnostics are retained; held cursors, pending transfers, fixed-count work and crew-local leases retain guarded handling.
- Stash approach checkpoints include native idle ticks, player/action tick counters and walking-fallback state to distinguish a stopped action from an inaccessible chest.
- Includes the 0.14.12 snapshot fixes. Offline checks cover downward flight, stalled-walk fallback and production watchdog routing; live path recovery remains subject to server/world geometry.

## 0.14.12 — Fresh stash transfer snapshots (The Reckoning Update)

- Idle stash pickup and deposit snapshots now consume menu-scoped server slot updates, including hopper and player inventory changes. Stale/out-of-range updates cannot seed an unread menu; pending moves and held cursors still require their existing full-snapshot receipts.
- Before a new click, an idle snapshot mismatch can reopen the container twice for a fresh baseline. Repeated unresolved solo until-empty sources are deferred rather than losing the confirmed carried batch; uncertain clicks, discards and held cursors remain guarded.
- Recovery logs include the target, differing slot indices, carried counts, state and snapshot age. Offline checks cover snapshot isolation, state rollover, invalid deltas, pending-move exclusion and bounded recovery. Live 6b6t verification remains pending. See [source snapshot incident](docs/incidents/2026-10-11-stash-source-snapshot.md).

## 0.14.11 — Worker job safety defaults (The Reckoning Update)

- Built-in stash scans, kit deliveries/transfers, resupply, travel, recovery and guard/follow jobs now carry a shared job-scoped safety profile instead of relying on a fresh worker's disabled modules. Both standalone and in-game hosts use the same preset library.
- Enchanted apples and named food are allowed; Auto Gap, Strict Auto Totem, armor/tool handling, Velocity and non-stunning Enderman avoidance are enabled. Flight uses Vanilla, acceleration step 1.5 and zero natural descent; native stash movement enables flight on demand, with Speed and combat auras off during ordinary stash work.
- All built-in highway roles retain the established 5.5 walking speed, 10-break/10-place limits and managed inventory with empty-shulker disposal. They share the safety policy while retaining their existing horizontal flight ceiling. The 6b6t baseline includes one block of extra reach; other servers may require a duplicate with lower flight/reach limits.
- Personal stash homes, drop lists, credentials, Lobby Skip and disconnect/reconnect policies are not copied or temporarily toggled by these presets. Existing saved/custom packages and running jobs retain their snapshots. See [worker job defaults](docs/worker-job-defaults.md).

## 0.14.10 — Follow over RWP (The Reckoning Update)

- Monocle advertises `dev.monocle.follow.v1` on its draft RWP connection. A compatible coordinator can assign a player UUID, radius and bounded or unlimited interval, using the existing native walking/adaptive-flight follower. Missing targets are awaited, not chased at stale coordinates; no private crew/TPA or combat behavior is implied.
- Follow intervals survive reconnects in the RWP execution journal. Native job ownership prevents RWP movement from taking over an existing highway, printer, stash scan or worker task.
- RWP movement cancellation now retains a durable cleanup-pending state until the client thread releases native control, using its landing/suspension handoff when airborne. Wait ticks do not advance outside a ready world.
- Offline socket tests cover capability discovery, exact extension dispatch, finite/unlimited Follow, progress, durable interval state and cancellation handoff; compiled-client checks cover native action mapping. Full highway/stash workflow adapters and live game-backed interoperability remain pending. See [Monocle RWP actions](docs/workers-api-v1/monocle-actions.md).

## 0.14.9 — Recover unread source containers (The Reckoning Update)

- Until-empty solo carried-kit transfers defer source containers that fail to open or send contents within 200 ticks, just like an unreachable source. They retain confirmed pickups, continue through later candidates and use the workflow's bounded revisit policy. An unread container stays unresolved, never cached as empty or reported as verified exhaustion.
- Pending inventory moves, held cursors, crew-local leases and fixed-count/ender-chest requirements retain their existing safeguards. Unrelated open menus are not forcibly closed by this recovery.
- Offline checks cover opening/content timeouts before and after confirmed pickups, retained checkpoint evidence, production timeout routing and held-cursor rejection. Live server testing still requires a worker update; existing workflow scripts do not need changing.

## 0.14.8 — Mixed loads and hopper outlets (The Reckoning Update)

- Solo stash transfers offer opt-in Mixed loads in both host control panels: select 2–16 kit types from one source for one destination. A partial batch tries other selected types before travelling home; confirmed per-type deposits retain column reservations and personal-box protection. Existing drain and Round Robin modes are unchanged. Carried-load recovery remains a separate job.
- Historical hopper picks resolve enabled output links to the actual chest and then its physical column, including single-chest outlets. Locked, cyclic, missing and out-of-selection links retain direct hopper handling; exact receipts use the opened container, not the old hopper coordinate. No simulated item flow is reported as an observed inventory count.
- Offline regression checks cover one-kit partial loads topped up to 34, per-type deposits and empty verification, capacity/receipt rejection, plus rotated hopper outlets and selection/lock/cycle guards. Update hosts and workers together; live server behavior is not yet tested.

## 0.14.7 — Hopper withdrawal fallback (The Reckoning Update)

- Hopper-fed chest and hopper withdrawals use the existing individually confirmed kit moves rather than four-box bursts. Static chest withdrawals and bounded deposits keep their bulk path; uncertain moves still stop without replay. This avoids preselecting four source slots while hopper stock is changing.
- Until-empty solo carried-kit transfers defer an unread source after the 200-tick approach deadline and continue through other candidates. Deferred sources remain unresolved, survive checkpoints and retain the workflow's bounded revisit policy; they are never cached as empty. Fixed-count and crew-local requirements and all pending inventory-move safeguards are unchanged.
- Native regression checks cover the dynamic-source fallback, static bursts, exact receipts, deferred cursors, checkpoint retention and guarded failures. Live 6b6t verification is still required. See [wool transfer incident](docs/incidents/2026-10-10-wool-withdrawal.md).

## 0.14.6 — Action-aware stall recovery (The Reckoning Update)

- Worker actions report their phase, expected next event, active time without meaningful progress and navigation retry budget. Confirmed stash work, approach improvement and advancing mining count as progress; changing status text or retrying does not. Cooldowns, warmups, loading, eating and intentional long-running follow/module tasks do not consume the watchdog clock.
- After two minutes without progress, an owned navigation route can be replanned twice. Inventory confirmations, drops and teleports stay with their existing guarded executors; the monitor never repeats them. Exhausted or non-navigation stalls use durable safe suspension, retain the action/checkpoint and notify the configured Discord owner before cleanup finishes. Existing native highway pauses remain native-owned.
- Both hosts retain per-worker stall pauses through reconnect/restart until explicit Resume/Rejoin. Delayed copies of the dismissed incident cannot relatch it; cancellation still reconciles cleanup. WebUI and in-game job inspectors show action progress; recovery transitions are logged without logging every tick.

- Added complete two-load Kit Delivery Lua/socket replay with a lost deposit acknowledgement, host restart, explicit resume and stale previous-action observations. Additional scenarios cover cancellation with uncertain cleanup, reconnect and missing executed checkpoints; three-worker replays retain offline destination leases.
- `./gradlew unattendedReplayCheck` groups native inventory/checkpoint checks and coordinator fault replays. Clock, slow-mining/movement, healthy-wait, retry-budget, stale-pause and notification checks run offline. Update host and workers together; live server behavior and threshold calibration remain untested. See [remaining reliability batches](docs/worker-reliability.md).

## 0.14.5 — Readiness and command receipts (The Reckoning Update)

- Passive 6b6t lobby/world readiness survives Lobby Skip toggles and walking timeouts. Safety observations remain excluded in detected lobbies, and gameplay waits through transfers/local-chunk loading without blocking host cancellation. Worker inspection reports the readiness state and reason.
- Both hosts journal per-worker lifecycle/configuration command evidence. Send-queue admission, correlated worker acknowledgement, completion, rejection, supersession and unresolved cleanup are distinct; old acknowledgements cannot complete a newer request. Standalone HTTP operation receipts advance with these reports, preserve exact idempotent responses, survive restarts and emit state-change events. Native highway starts/resumes reporting a Highway action also wait for a fresh current-generation building report.
- The WebUI job inspector and in-game task inspector expose receipt evidence. Crew reassignment is confirmed on authenticated reconnect, not when its packet is queued. Older clients remain compatible but cannot provide correlated lifecycle acknowledgements; update host and workers together. Worker-reported completion is not universal game-server verification.
- Offline regression checks cover passive detection, transfer/readiness gates, command ordering, partial acknowledgements, stale reports, configuration revisions, missing checkpoints and durable HTTP/socket outcomes. Live server verification remains pending.

## 0.14.4 — Host-wide hostile warnings (The Reckoning Update)

- Current loaded-player checks go to the host; a known hostile seen by one worker warns all updated native clients across all crews. With the guard enabled, same-server clients disconnect using the existing safe reconnect policy, even if they cannot see the hostile themselves. Cached per-tick local detection remains immediate.
- Shared ten-second alarms, duplicate-report suppression, capability gating, negative check receipts and current-view freshness; retained entrance history never triggers an alarm. WebUI shows active sightings and clients missing host-wide support; Discord reports new alarms when configured.
- Detected lobby reports are excluded from the incident timeline, hostile learning and alarms. Clients do not capture lobby combat events, and send no lobby player history. Connection/work diagnostics remain available.
- Core, native socket, client safety and UI checks cover warning delivery, scope, forgiveness, reconnects, old clients and lobby exclusions. Update both host and clients; live server behavior remains untested.

## 0.14.3 — Discord owner notifications (The Reckoning Update)

- Standalone and in-game hosts DM an owner on job/worker failures and inspection states, disconnects, deaths, hostile safety logouts, whole-job completions and cancellations. Private bot token + owner ID, WebUI Notifications page, in-game host controls and test DM; no slash commands.
- Shared asynchronous delivery, persisted bounded outbox, state deduplication, safe errors, Discord rate-limit handling and nonce-based retries. Credentials never enter job profiles, worker telemetry or status responses. Delivery is best-effort and cannot alert about a powered-off host. See [setup and limits](docs/discord-notifications.md).
- Local HTTP delivery, failure/restart/deduplication, token privacy, authenticated UI/API and build checks. Live Discord and in-game tests still require operator credentials/testing.

## 0.14.2 — Incident timeline and safe reconnect (The Reckoning Update)

- New Incidents page retains player entrances (neutral, friendly or known hostile), damage, deaths, crystal evidence, hostile logouts and safety reconnect events. Authenticated `/v1/incidents` supports bounded paging/filtering; rotated best-effort JSONL history survives host restarts. Sightings are sampled once per second, debounced, and exclude detected lobbies/known workers.
- Optional host-controlled safe reconnect waits five minutes by default after a hostile logout, honors existing retry/error limits and enables 6b6t Lobby Skip before reconnecting. Manual reconnect/cancel remain available. In-game hosting and solo clients share the same safety policy and local incident log.
- Offline policy, timeline, socket/API, UI and reconnect checks included. No greetings, teabagging or retaliation; no guarantee of safety on combat-tagged servers. Update both host and clients; live server testing remains pending.

## 0.14.1 — Known hostiles (The Reckoning Update)

- Shared, durable server-scoped hostile players list in the standalone and in-game hosts. Server-attributed player attacks can add UUID-backed entries automatically; crystal suspects, friends and worker identities do not. Operators can add/remove players and enable the shared disconnect guard from the WebUI's **Known hostiles** page.
- Updated workers cache the trusted host list and disconnect when a listed player model is visible outside a detected lobby. This host policy is independent of Auto Log activation/job profiles and survives reconnects. Existing reconnect settings are respected; reporting is best-effort and never gates the disconnect. The guard is off until enabled by an operator.
- Removing a player survives replayed announcements and host restarts; a fresh attributed attack can add them again. Older clients are not sent unsupported control frames and are clearly marked unprotected in the WebUI. See [known hostiles](docs/known-hostiles.md) for controls, API fields and limitations.
- Offline checks cover socket synchronization, cross-crew sharing, controls, UUID/name identity, durable forgiveness, reconnects, lobby/notification wiring and invalid updates. Live game testing remains pending; logging out cannot guarantee safety on combat-tag servers.

## 0.14.0 — The Reckoning Update

- Passive worker damage/death tracking records server-reported causing/direct entity IDs, available player UUIDs/names, timestamps, world/coordinates and raw death messages. Crystal placement/detonation swing correlations are explicitly suspects, never silently asserted as killers. World changes clear correlation caches without erasing the last attack/death. No combat or retaliation behavior is added.
- Evidence appears in client logs, in-game host inspection, and (with an updated standalone host) worker API/WebUI inspection and structured host events. Immediate announcements help retain the cause before logout; delivery is still best-effort. The bounded combat field is separate from highway diagnostics and optional for older hosts. See [worker combat evidence](docs/worker-combat-evidence.md).
- Worker audit should #1: native walking caches a route/failed search for at most five ticks while start/goal cells match, retaining live next-step footing/corridor verification and immediate invalidation. Repeated footing probes within a single search are shared. Baritone, manual movement, flight and crew synchronization are unchanged.
- Offline checks cover attribution uncertainty, independent crystal placer/detonator suspects, stale evidence, world resets, custom death text, API snapshot ownership/payload bounds and walking cache invalidation. Live server testing remains pending.

## 0.13.86 — Re-arm bodyguard takeoff after successful flight

- Native travel clears its takeoff-attempt counter and launch timer when the worker is observed gliding with ElytraFly active. Repeated bodyguard flight/landing cycles no longer consume a lifetime two-attempt budget or inherit an old launch timeout. Ordinary native travel shares the fix; walking-only Follow remains walking-only.
- Failed launches retain the existing two-attempt limit, 30-tick timeout and walking fallback. Jumping or merely waiting for launch does not reset the budget; landing, route/portal checks, speed matching and module ownership are unchanged.
- A headless compiled-branch regression check fails before the fix and verifies both resets occur inside the observed-gliding branch, not on every grounded launch tick. Worker, flight and module-handoff checks pass. Update the client; no host update is required. Live repeated takeoff/landing testing remains pending.

## 0.13.85 — Preserve unresolved receipts at the worker checkpoint limit

- Admitting a new workflow at the 64-run checkpoint limit evicts only the oldest settled terminal run, never the current run or a run with an uncertain drop, stash move/discard, deposit acknowledgement or teleport. Timed history cleanup already applies these protections. Cancellation still releases the execution gate without making unresolved evidence disposable.
- If every checkpoint is protected, admission fails before changing the retained queue and explains why it is full. The existing journal limit, format, atomic save/rollback and reconciliation rules are unchanged; previously evicted records cannot be reconstructed.
- Headless regression checks reproduce unsafe eviction and cover all terminal outcomes, pending receipt types, current-run protection, oldest-settled selection, paused/inspection records and rejection without mutation when no record is disposable. Update the client; no host update is required. Live testing remains pending.

## 0.13.84 — Keep native worker travel out of portals

- Native worker body-clearance and footing checks reject End portals, End gateways and Nether portals even when they have no collision. Walking, following/bodyguard flight, surveying, stash flight and safe landing use the shared guard; existing loaded/verified terrain, collision, border and entity checks remain in place. Portal frames use ordinary collision checks, not a special blacklist.
- Headless regression checks use real portal block states and swept-body routes, covering frameless portals between endpoints, sideways contact, unsafe destinations and safe detours. Existing fluid/fire/powder-snow hazards remain blocked. Update the client; no host update is required. Live server testing remains pending; this does not change manual movement or Baritone's separate navigation policy.

## 0.13.83 — Preserve job defaults across worker pause/resume

- Effective worker-profile checkpoints record every live gameplay setting, including values equal to module defaults. Resuming after personal settings are restored no longer inherits different personal values where the job checkpoint previously omitted defaults. Pre-job snapshots share the same complete capture path; workflow profiles remain sparse overlays.
- Checkpoints retain validation, gameplay-only filtering, task-owned executor activation guards and independent copies. Existing incomplete checkpoints remain readable, but omitted historical values cannot be reconstructed reliably; start a new job to obtain a complete checkpoint.
- Headless regression checks reproduce default-value loss, round-trip default/nondefault checkpoints through JSON, restore differing personal values, and verify exact resumption, unchanged-state reapplication and capture filtering. Update the client; no host update is required. Live pause/resume testing remains pending.

## 0.13.82 — Preserve unchanged worker modules during profile application

- Worker profiles and live configuration edits compare complete settings on both sides, including default-valued settings. Empty overlays, explicit unchanged defaults and reapplication no longer disable/re-enable active modules or reload their settings unnecessarily.
- Rollback snapshots use the same complete representation. Genuine settings changes still install while the module is inactive, and activation-only changes still toggle as requested. Profile capture and pause/resume behavior are unchanged; the separate audit finding about omitted defaults in effective-profile checkpoints remains pending.
- Headless regression checks execute the actual application loop with real settings and counted module lifecycle calls, covering unchanged defaults, mixed default/nondefault values, activation-only changes, genuine changes and rollback. Update the client; no host update is required. Live flight testing remains pending.

## 0.13.81 — Bounded bulk kit transfers with exact-slot fallback

- Native kit pickups, stash deposits and ender-chest deposits can issue four-kit bursts when at least four eligible boxes, four empty destination slots and four requested kits remain. Smaller amounts, limited space, illegal stacked boxes and unsupported menus retain the existing single-kit exact-slot path. Protected boxes and exact kit matching still apply.
- Ordinary chest menus use non-predicted, server-confirmed shift-clicks, one click per kit. Hopper-fed storage keeps explicit source/destination pairs so drainage cannot redirect a deposit, but sends four ordered pairs without a separate round trip per kit. Every new receipt requires the exact expected player inventory and exact kit at its destination; duplicates, held cursors and count changes alone cannot produce success.
- A stalled burst reopens the container as a server ordering barrier. Only a fresh snapshot proving the unconfirmed moves left player inventory untouched enables the old single-kit fallback, disabled-bulk state is checkpointed, and known completed moves are never replayed. Ambiguous moves retain the existing inspection/reconciliation boundary rather than guessing. Burst progress, slots and packet evidence appear in diagnostics.
- Offline tests exercise real vanilla three-/six-row chest shift-click order, bounded quotas, small-load fallback, mixed kits, protected slots, hopper refill/drainage, duplicate and skipped replies, partial completion and interrupted checkpoints. No host update is required. Live server testing and speed measurements remain pending.

## 0.13.80 — Three-block stash flight clearance

- Automated stash scan, withdrawal and deposit cruise flight keeps a conservative three-block terrain buffer around the whole player body. Routes, each steering step, container-opening hover and workflow handoff waits use the same rule; floor, ceiling and sideways obstacles count. Unloaded or unconfirmed terrain is not assumed safe.
- Takeoff first seeks a collision-checked exit into clear air; intentional landing remains exempt until grounded. When no buffered opening route fits, the action lands safely and uses existing walking navigation instead of skimming containers. Flight can reach clear air above world build height, which is not a flight ceiling.
- Offline regression checks cover floor/wall/ceiling clearance, buffered container reach, bounded takeoff exits, blocked narrow spaces and landing wiring. Manual ElytraFly and highway flight are unchanged. Update the client; no host update is required. Live server testing remains pending; cramped stashes may require more walking, and clearance is not protection against combat or server-forced flight loss.

## 0.13.79 — Lighter stash transfer bookkeeping

- Pending-observation presence checks no longer deep-copy full container manifests every tick or for discarded acknowledgments. Uploads and durable snapshots still take owned copies; receipt and approval rules are unchanged.
- Each accepted stash-resupply inventory packet counts carried kits once and copies its full menu once, sharing a read-only container view of that detached snapshot. Status reports build each native stash telemetry/receipt payload once instead of twice.
- Offline regression checks cover pending-state transitions, mutation isolation, acknowledgement guards and hot-path call counts. This reduces repeated classification/allocation work, not server inventory-confirmation delays. Update the client; no host update is required. Live performance measurements remain pending.

## 0.13.78 — Lobby-aware player logout guard

- Auto Log's Only Trusted player trigger ignores the current lobby while Lobby Skip is active. Lobby detection is independent of walking: a timeout, movement handoff or manual walking stop does not mistake lobby players for a stash visitor. Applied world transfer, respawn, lobby teleport or a replacement world re-arms player protection; merely receiving a queued transfer packet does not expose the old lobby players to the guard.
- Health, damage, totem, gear and configured dangerous-entity checks remain enabled. Keep Lobby Skip enabled for this exemption, Auto Log's toggle-off and toggle-auto-reconnect disabled for repeated protection, and Auto Reconnect's delay at 300 seconds for a five-minute retry.
- Offline checks cover lobby/world boundaries, lifecycle wiring and survival-guard ordering. Update the client; no host update is required. Live reconnect testing remains pending.

## 0.13.77 — Recover delayed stash deposit acknowledgments

- Duplicate or stale deposit acknowledgments no longer rewrite the full worker checkpoint. Pending stash findings retry at most once per real second, independently of game tick speed; new observations still upload immediately.
- Deposits retain their exact pending observation and approval gate for a bounded 60-second host-response window instead of failing after 200 game ticks. A delayed-response warning and final timeout include the receipt cursor and verified kit count. Already confirmed deposits are never replayed, and unapproved deposits remain blocked.
- Regression checks cover duplicate/stale/future receipts, immediate new deliveries, accelerated-tick retry pressure, delayed approvals, bounded failure and reconnect retention. Update the client; no host update is required. Live heavy-catalog testing remains pending.

## 0.13.76 — Configurable gaps in Wither lines

- Spam autopilot has a `wither-gap` setting, defaulting to four empty blocks between successive structures along each side. Zero restores touching structures; the configured gap is captured at activation so changing it cannot move an in-progress structure.
- Both sides remain in placement reach while building; forward movement crosses each gap after completing the pair. Two placements per tick, confirmation guards, native ElytraFly acceleration and manual Spam behavior are unchanged. Nearby Withers still do not trigger evasion, and spacing is not a guarantee against targeting or explosions.
- Geometry checks exercise all headings with zero, one, four, sixteen and sixty-four-block gaps. Update the client; no host update is required. Live server testing is still required.

## 0.13.75 — User-controlled Wither evasion

- Auto Wither no longer retreats or detours because a Wither is nearby, including before restocking. Wither avoidance is the user's responsibility; construction pacing, collision checks, and count/material-limit exits remain unchanged.
- Removed unused spawn-grace tracking and automatic line-restart logic. Client checks pass; live server testing is still required. No host update is needed.

## 0.13.74 — Continuous two-sided Wither autopilot

- Spam autopilot builds fixed-height Wither rows on both sides of a clear center flight corridor. Each three-block-wide structure touches the next along the line; completed sites are not reused. This replaces the old per-stop count and inter-stop spacing settings. Manual Spam and hand-placed starter blocks retain their existing behavior.
- Forward travel is capped by confirmed construction progress and the configured ElytraFly speed/acceleration. Placement callbacks enforce at most two blocks per tick; the summoning head still waits for the rest of its structure. Confirmation stalls hold the progress boundary rather than flying past unfinished work.
- Autopilot clears nearby Withers before shulker restocking, at the count/material limit, or before their spawn grace expires. Obstructed lines and unresolved placements exit with an explicit warning rather than silently relocating or claiming unfinished structures as complete. Grace checks use both synchronized invulnerability and bounded local age, not an assumption of permanent safety on custom servers.
- Geometry checks cover all headings, adjacent/non-overlapping rows, flying reach throughout each structure, monotonic movement bounds, two-head mode and placement/grace limits. Update the client; no host update is required. Live flight and server placement behavior still require testing.

## 0.13.73 — Deliver partial loads when inventory fills

- A kit pickup that loses its remaining free slot after confirmed withdrawals ends the current batch instead of failing the transfer. Stash/player delivery and ender-chest loading use their existing handoff paths; subsequent loads retain the requested size and source cursor. Unrelated inventory is not discarded.
- Regression checks cover a one-kit partial load, reconnect persistence, and refusal to claim an empty load as delivered. Update the client; no host update is required.

## 0.13.72 — Lighter live stash discovery

- Chunk packets enqueue discovery rather than walking every stash record inline. Known coordinates are indexed by chunk, overlapping watches share a coordinate pass, and physical reconciliation yields after a two-millisecond between-record budget (still at most 32 records per tick).
- Repeated discovery compares against newer queued or in-flight reports before acknowledged geometry. It does not duplicate pending uploads or replace server-confirmed contents with an unscanned report; actual resize, removal and recreation still reconcile. Queue byte checks reuse immutable entry sizes and refresh them when acknowledgement hashes change.
- Regression checks cover chunk boundaries, acknowledgement index updates, pending/in-flight precedence, recreation and exact UTF-8 queue sizes. Update the client; the running 0.13.71 host and stash data need no changes. Live frame-time improvement requires testing.

## 0.13.71 — Batched passive stash updates

- Passive observations use up to 32 entries per upload/acknowledgement, with a 64 KiB wire and expanded-payload bound. Both host adapters advertise support; clients negotiate per connection and fall back to single updates on older hosts or oversized contents reads. Existing frame/outbox limits remain unchanged.
- Individual event IDs, compare-and-set hashes and durable receipts survive batch retries, partial acknowledgements, disconnects and world-report races. The flat recovery journal remains compatible; newer queued contents are not replaced by older in-flight observations. The host also avoids rereading a catalog immediately after committing its update.
- Checks exercise batching limits, malformed envelopes, cold replay, lost acknowledgements, retained committed prefixes, reconnect queue preservation and real socket batch ingestion. A 32-container discovery test uses 7,970 bytes for its entire upload. Update workers and hosts to enable batching; this does not change scan movement or kit transfers.

## 0.13.70 — Legacy stash watch compatibility

- Passive watch manifests exclude historical scan rows outside the current stash selection. Solo, client-host and standalone-host baselines share the filter; saved contents and exact in-bounds observation hashes remain unchanged. Legacy definitions without a valid required home are retained but do not issue watches.
- Regression checks cover local/network baselines, negotiated limits, missing homes and preservation of the original catalog. A standalone host update fixes outgoing manifests without requiring a worker update.

## 0.13.69 — Content-aware kit variants and incremental stash storage

- Same-name/color shulkers with unrelated contents get distinct type IDs instead of becoming false incomplete kits. Related partial boxes still use their fullest observed exemplar. Old IDs remain aliases for captured jobs and column reservations; raw names are unchanged, and incoming observations cannot assign their own kit identity. Pickup, incidental handling, deposit and WebUI locations use the shared classification.
- Chest observations now commit bounded, checksummed deltas beside a retained JSON snapshot instead of rewriting the full catalog. Observation receipts, changed rows, double-chest alias retirement and deletions share the durable write. Cold restarts replay the journal; periodic compaction atomically commits the snapshot before retiring obsolete deltas. Invalid journals preserve their files and fail explicitly.
- Regression checks cover legacy aliases/reservations, unrelated and partial kits, malformed classification metadata, cold replay, interrupted compaction, changed snapshots, failed writes, lost receipts and 16,000-container updates. Existing byte/frame limits remain unchanged; no new dependency.
- Update hosts and workers together and create fresh kit jobs for the new classification. Back up each snapshot **and its adjacent `.json.deltas` directory**; old versions cannot read pending deltas. See [stash audit status](docs/stash-management-audit.md). Live deployment and game-backed testing remain pending.

## 0.13.68 — Legacy stash preservation and lighter passive watches

- Discovering compatible geometry for a legacy verified container no longer erases its items or kits when old scans lack slot counts. Historical contents keep their original observation time; discovery does not pretend to rescan them. Contradictory capacity, slot or known-shape evidence still requires a contents read.
- Passive observer reloads read each selected catalog once in the background, without operator summaries, overlap aggregation or kit learning. Workers receive compact geometry/CAS baselines instead of hashing full chest contents on the game thread.
- Regression checks cover legacy contents, stale discovery retries, incompatible shapes and all 16,384 watch records. No kit IDs, reservations, movement or workflow scheduling changed. Update host and workers; the live service remains untouched.

## 0.13.67 — Durable stash exports and definition protection

- Client definition exports use a definition-only revision, reject stale edits and cannot shrink observed storage. Worker edits change only that worker's home route, never the host's default or another worker's route. Both host adapters protect legacy exports too; conflicts are logged without disconnecting the worker.
- Passive live updates force their outbox journal before applying local catalog changes, closing the crash window where an update could disappear before upload.
- Solo scans checkpoint a durable, host/account/world-scoped export before advancing. Uploads retry after reconnect/restart, coalesce later reads of the same container and retire only after host receipts. Receipts share the observation's atomic catalog write, so lost ACKs cannot replay old stock over newer observations. Conflicted definitions stay on disk for review; old hosts leave exports queued until updated.
- Stash Manager's home cooldown retains fractional minutes instead of truncating second-level values to zero. Existing integer-minute settings keep their units; a 16-second cooldown survives editing and saving.
- Regression checks cover stale definitions, offline scans beyond 256 results, journal failures, account/host isolation, lost receipts and authenticated socket delivery. Update host and workers; no live service or stash data was changed while preparing this build.

## 0.13.66 — Expanded stashes and bounded discovery

- Stash catalogs and scans support 16,384 container records, including scan delivery receipts, restore counters, removal telemetry and paged observer baselines. Existing frame and journal byte limits remain. Workers advertise their container limit; both host types omit oversized live baselines for older workers instead of sending truncated data or disconnecting them.
- Revision-checked operator edits can enlarge an observed stash without deleting its stock, kit identities or column reservations. Shrinking or moving an observed selection remains protected. Observations from older jobs no longer overwrite a newer definition's bounds or home route; restart active transfers after changing their destination selection.
- Passive discovery waits for upload queue room rather than exhausting the outbox during a large chunk catch-up. Double-chest partner reconciliation checks four neighboring coordinates rather than every record in the stash, and arriving watch baselines no longer discard queued physical observations.
- Update both host and workers for expanded stash support. Live observation remains enabled by default; new unopened containers still have unknown contents, never invented empty slots. Category reservations and freshness-based scheduled scans remain future work.

## 0.13.65 — Live stash observations and removal reconciliation

- Stash Manager passively observes manual and automated server-confirmed container contents, coalescing full snapshots and slot deltas without opening menus, clicking or moving the player. Live updates default on independently of the scan module's activation; host export retains its existing opt-out.
- Defined storage discovers new physical containers and retires vanished ones from totals, source plans and the column map. New unopened chests report capacity with unknown contents, never invented empty stock. Loaded chunk catch-up and explicit scans reconcile known missing coordinates without deleting unread or unloaded storage by omission.
- Double-chest halves resolve one physical record, merge old aliases without double counting, and preserve surviving capacity after either half is broken. Lifecycle history and removal tombstones remain bounded; recreation requires a fresh observation.
- Both host types use the same validated, revision-checked ingestion, duplicate suppression and acknowledgement outcomes. Small paced watch manifests, a bounded durable client outbox and background local writes avoid per-click network/disk stalls. Reconnect/world-report races defer updates rather than disconnecting workers; stale snapshots cannot overwrite newer records.
- Update the client and host to enable live exports. Older hosts do not advertise watches and receive no new live messages on a fresh connection. New hosts retain support for older workers.

## 0.13.64 — Skip vanished stash sources

- A mapped source that remains a non-block-entity block in a loaded chunk after the existing 40-tick recheck is logged and skipped, rather than failing a transfer after its first partial delivery. The worker continues to the next connected chest or mapped source and retains every confirmed pickup.
- Missing positions reuse the bounded, world-scoped empty-source hints and checkpointed source cursor. Unloaded chunks, storage block entities still loading, uncertain withdrawals/deposits/discards and cursor items retain their safety gates. Update workers; existing captured workflows and the host need no update for this fix.

## 0.13.63 — Incidental kits do not strand stash pickups

- Stash-to-stash plans include the known kit IDs from their source and destination catalogs. A newly acquired recognized kit is retained while the requested pickup continues, then deposited through the existing workflow into its own eligible column. It never inflates the requested kit's pickup or delivery counts; a load filled by extras is delivered without claiming source exhaustion.
- Newly acquired unrecognized shulkers are discarded using a server-confirmed slot operation. Shulkers already carried when pickup began remain protected, including during incidental-kit deposits. Old plans without recognition metadata do not enable automatic discards.
- A wrong shulker occupying a previously empty pickup slot can reconcile through a fresh source menu when the requested-kit count is unchanged, the cursor is empty, and player inventory changes are only additions of non-requested shulkers. Requested gains, removals and other mutations still require exact reconciliation; no uncertain withdrawal is counted or blindly replayed.
- Update host and client and create a fresh job to capture the new kit workflow. Regression checks cover personal-box protection, wrong-kit slot collisions, incidental-only loads, separate deposit counts and native action validation.

## 0.13.62 — Resume interrupted kit deposits

- A deposit checkpoint interrupted by connection loss no longer fails on resume merely because a click was awaiting verification. It retains confirmed receipts, renews the destination-column acknowledgement, opens a fresh chest menu and reconciles complete server-observed inventory with an empty cursor before transferring the remaining carried kits. The old slot click is never replayed.
- Unverified kits already absent from inventory remain logged shortfalls, not fabricated successful deposits. The pending reconciliation gate survives another disconnect and still blocks new clicks, missing-kit shortcuts and full-column completion until fresh stock is established. Existing exact live-click verification and bounded reopen/retry checks are unchanged. Old pending-deposit checkpoints can use this recovery. Update workers; no host/workflow update is required.

## 0.13.61 — Remember empty sources across kit switches

- A truly empty bottom chest on a stable enabled hopper chain waits once for the existing propagation window (40 ticks plus 8 per connection, scaled for measured low TPS), then skips that chain's mapped upper chests for the current action. The existing checked-source checkpoint remembers the skip; repeat workflows still perform their later empty-verification passes. This is a drainage assumption, not a new scan or an inventory-count update.
- Workers also share truly empty source hints across pickup actions, kit switches and loads in the same job, scoped by server and dimension. Up to 4096 physical positions expire after 30 minutes; stopping the job, losing the world or restarting the worker clears this shared memory. Fresh non-empty reads invalidate hints. A search returning no pickups after cached skips verifies those sources again before it can report exhaustion, including after action checkpoint recovery. Non-matching stock is never cached as empty.
- Non-matching contents, changed/disconnected chains, independent chests and individually leased crew sources retain their existing upper-chest search/deferral logic. An uncertain inventory move cannot be skipped. Update workers; host changes are not required. Landing behavior is unchanged in this patch.

## 0.13.60 — Remember full destination columns

- The shared coordinator persists worker-reported exhausted destination columns in the stash catalog, across kit switches, batches, jobs and host restarts. Ordinary allocation prefers any other suitable column before revisiting known-full ones, including full manual reservations. If no other suitable column remains, known-full columns are eligible for a bounded recheck rather than permanently blacklisted.
- A fresh, non-inferred chest observation with explicit free slots clears its column's full marker and the active run's full-only exclusion. Unknown reads and full bottom-chest snapshots cannot establish or clear column fullness. Bounds changes invalidate the markers. Locked-column stopping, kit ownership, worker leases, inventory verification and movement are unchanged.
- Update the standalone host for standalone coordination; existing workers and captured jobs need no update. Client-based hosting uses the new client build. No new setting or wire message is required.

## 0.13.59 — Round Robin stash routes

- Solo Transfer Stash Kits optionally rotates through 2–16 source/kit/destination routes, completing one carried inventory load per turn before moving to the next route. Different sources and destinations are supported. The existing drain-one-type mode stays unchanged; no mixed-kit inventory or new transfer engine is introduced.
- Each route retains its own source cursor, empty-pass count and verified delivery/missing/recovery totals. Both host adapters resolve and durably freeze each bounded source manifest on first use so catalog changes cannot shift a saved cursor. Two verified empty passes retire a route. Repeatedly deferred sources park while other routes continue; unresolved routes are reported only after useful routes finish, never falsely exhausted.
- Optional carried-kit recovery visits the matching route's destination before any pickup. Same-kit routes must share a destination to avoid ambiguous recovery. Existing full non-lazy scans, exact-kit reservations, home cooldowns/warmups, cancellation and uncertain-click protection remain intact. WebUI adds a route editor and mode picker; the in-game host editor provides the same mode. Update host and clients, then create a new job; existing captured jobs keep their scripts.

## 0.13.58 — Reconcile stalled kit withdrawals before failing

- An unconfirmed pickup holds position and can reopen its source after 20 ticks, at most twice per unsuccessful pickup. A different server-confirmed menu must prove player inventory unchanged and cursor empty before selecting a matching kit from current contents, including when a hopper changed the original source slot. Reselecting does not reset the retry budget or count a pickup.
- Closing an exact held kit can return it to a different empty player slot; a fresh snapshot must prove exactly that one-kit inventory gain before counting it. Old menus, wrong kits, unrelated inventory changes and ambiguous receipts cannot authorize replay. Pending checkpoints still require inspection. Transaction diagnostics now retain recovery attempts and current source, destination, cursor and menu evidence. Shared withdrawal logic only; update workers, no host restart required.

## 0.13.57 — Reconcile stalled kit deposits before failing

- A pending kit deposit can reopen the same chest after 20 ticks, at most twice per kit. Old-menu packets cannot authorize a replay. Retrying requires a fresh server snapshot with the original player inventory restored (allowing only the held kit's return to another previously empty slot), empty cursor and destination, and compatible column contents. Uncertain depletion still stops safely.
- Deposit journals now retain current source/destination/cursor evidence even when the carried count never changes. Failure telemetry includes transaction slots, counts, snapshot tick and recovery attempts; the client log retains full verification evidence. Pending checkpoints remain non-replayable after restart. Shared kit-deposit logic changes only; update workers, no host restart required.

## 0.13.56 — Check upper stash chests when an outlet stops supplying

- Kit withdrawals still start at a live hopper chain's bottom, but an empty outlet waits at most 16 ticks before checking the next connected chest upward. An outlet with only non-matching stock skips that wait entirely. Each actually checked chest is remembered for that action and its recovery checkpoint, so duplicate mapped heights do not repeatedly reopen an empty bottom.
- The fallback follows only live, bounded, unbranched hopper links and preserves exact-kit server-confirmed pickup receipts. Unconfirmed clicks and crew source leases cannot be bypassed. Mixed stock that can strand kits in connecting hoppers, or lost upstream connections, remains deferred rather than falsely reported exhausted. Deposits still use the top and retain their existing drainage window. Client update required; the running host/job is untouched.

## 0.13.55 — Explicitly shared kit columns

- Locked-column deliveries optionally declare `columnKitTypeIds` (2–16 unique kit IDs, including the delivered type). The shared coordinator persists that exact group in the reservation and permits only those shulkers, while the client independently checks actual chest contents. Loose items, unknown observations and unrelated kits remain conflicts; ordinary single-kit jobs cannot allocate shared columns.
- Sharing can explicitly upgrade a compatible existing column without moving its contents. Full-column receipts, verified inventory clicks and no-overflow behavior are unchanged. Both client and host updates are required. This is an API/workflow option, not yet a WebUI category editor.

## 0.13.54 — Stop kit transfers at one selected column

- Single-kit stash deliveries accept an optional `targetColumn` bottom anchor (`x`, `y`, `z`, `axis`). Both host adapters reserve only that column; live incompatibility stops the transfer, and a full column produces a completion receipt rather than allocating overflow storage. The worker independently refuses a host acknowledgement selecting another column, including an older host that does not understand the limit.
- Full-column completion counts verified deposits, genuinely missing kits, and still-carried excess separately. Pending inventory clicks cannot be skipped; recovery checkpoints retain the full-column outcome. The reusable Kit Delivery workflow ends without another source trip and does not claim source exhaustion. Multi-kit transfers do not accept this single-kit limit; ordinary transfers retain their existing overflow behavior. Host and client updates plus a newly prepared workflow are required. The option is available to operator API/workflow callers, not yet a WebUI checkbox.
- Deferred hopper outlets no longer end an until-empty kit workflow immediately after a full batch while unvisited source targets remain. It continues from the saved source cursor instead of returning to the start. Mixed blocked outlets still report uncertainty; this does not yet add direct upper-chest fallback or combine separate source plans into a single load.
- Includes the pending 0.13.53 scan persistence/performance changes below.

## 0.13.53 — Keep stash catalog writes off the game thread

- Solo and worker scans record immutable container observations through a bounded background catalog writer. The game thread polls completion rather than reading, parsing, summarizing, forcing and replacing an increasingly large catalog synchronously. Contents remain locally durable before uploading or advancing; disk errors surface as failures, and an interrupted write resumes by rereading the checkpointed target. Existing scan checkpoints and route order remain compatible.
- Shared observation ingestion skips full operator summaries when the caller only needs a durable receipt. Catalog mutations are serialized against background scan writes, preserving concurrent definition/reservation updates. Both host adapters reuse this path; the standalone host requires an update for its ingestion savings.
- Workers immediately send newly checkpointed observations and retain the half-second retry cadence. Scan display-phase changes no longer rewrite the entire worker checkpoint; new observations, regular recovery checkpoints and acknowledgements remain durable. Duplicate/stale scan acknowledgements do not cause additional checkpoint writes. Deposit reservation gates are unchanged.
- Regression checks cover nonblocking catalog contention, immutable writer inputs, failed-write preservation, legacy scan restore, observation-count checkpointing and independent deposit request dispatch. This is not yet a multi-observation upload queue or incremental catalog storage; one observation still waits for its host receipt. No live scan was stopped or runtime updated while preparing this build.

## 0.13.52 — Recheck changing stash hopper connections

- Stash withdrawals refresh a changed physical hopper chain and reopen the source for fresh server contents instead of immediately failing the whole transfer. Three consecutive no-progress changes defer that source through the existing workflow retry path so other sources and kit types can proceed. Previously connected but missing upstream chests remain unresolved, not falsely exhausted.
- Source refreshes preserve confirmed pickups, never discard an unconfirmed inventory move, and cannot expand a crew source lease. Logs include the old/new physical chains; checkpoints retain the refresh budget and unresolved-source flag. Native checks cover changes, refill reset, retry bounds, reconnects, lost links and pending-click/lease safety. Client update required; no host restart is needed.

## Unreleased — External kit-delivery teleport correction

- Explicit cancellation also reaches failed executions whose workers restore uncertain inventory receipts as inspection-required after reconnect. Failed runs reopen only for cancellation acknowledgement, never for job replay; their original failure detail is retained. Shared policy regression covers this for both host adapters. Standalone host update required; the 0.13.45 worker cleanup fix handles the retained receipt.
- Stash catalogs refresh on connection, explicit edits/imports, and at most once per minute on checkpoint heartbeats, instead of rereading every stash on every heartbeat. The first three-worker live transfer exposed coordinator starvation and delayed TPA dispatch with a large catalog. Both host adapters use the same cadence; a socket regression checks that repeated heartbeats do not resend catalogs and explicit edits still sync immediately.
- The standalone host emits `task-tpa-send`, not `task-task-tpa-send`, when delivering kits to a player who is not connected to the host. A socket regression checks the exact command, recipient, run/token and one-time dispatch. Requires updating the host only; existing workers understand the corrected command.
- Multi-kit transfers advertise only their four required native actions. Advertising all twelve host actions exceeded the 0.13.36 worker's eleven-entry capability bound and rejected installation before execution. A host socket regression covers the exact reduced action set; host-only update, no client reinstall needed.

## 0.13.51 — Reserve stash columns from the map

- Click a chest column, or Shift-click an inclusive range, choose any kit type known to that crew/world and reserve the selection. Clear returns those columns to automatic allocation. Selection and kit choice survive polling; uncertain writes reuse their original idempotency key and revision.
- Manual reservations persist across jobs/restarts and take priority over automatic destinations. The host validates the entire range before writing, rejects incompatible observed stock and active deposit leases, and workers still confirm contents before depositing. This is metadata only: nothing is moved or mixed by reserving a column.
- Shared column allocation uses the lowest recorded chest row, not the cuboid's minimum Y, and excludes hoppers. Update workers to this version when the selected stash bounds extend below the lowest chest row (including `main`); older workers reject those elevated anchors.

## 0.13.50 — Linked stash pages and storage fullness

- Stashes opens a searchable directory of linked stashes with fullness meters. Each stash has a bookmarkable page with its existing column map, kits, resources and scan controls; browser Back and All stashes return to the directory. Polling preserves the active view.
- Shared catalog summaries report physical chest/barrel capacity and occupied slots, excluding hoppers and nested shulker contents. Scans and kit deposits record exact slot counts; older catalogs explicitly estimate double-chest capacity. Unknown or inferred occupancy is shown as a lower bound, not empty space. Requires the updated host for summaries and UI; updated clients provide exact measurements on subsequent scans/deposits.

## 0.13.49 — Scan neighboring storage in one sweep

- Fresh Full scans with Lazy Mode off interleave nearby chest rows, hoppers and heights in four-by-four cross-sections along the longer horizontal axis. Sweeps snake from the initially nearer end and the 64-container window can cross sweep boundaries, so reachable neighboring containers take priority over more movement instead of requiring separate chest/hopper row passes. Discovery covers each selected coordinate once, retaining double-chest deduplication and existing opening/save safeguards.
- Column Map and Lazy Mode keep the bookend row strategy. Pre-update checkpoints retain their saved order; new checkpoints persist the spatial orientation without an additional visited-container ledger. This applies to both solo Stash Manager and coordinated scans; client update plus a fresh scan required, no host update needed.
- Regression checks cover both horizontal orientations, all reversal combinations, partial edge bands, cross-band chest/hopper proximity, exact coordinate coverage and checkpoint queue validation. The live scan was not stopped or restarted.

## 0.13.48 — Nearest-end stash scan rows

- New stash scans sweep the longer horizontal axis and start each row at the end nearest the worker. Rows drain before discovering the next row, producing a snake instead of full-width return trips even when a row exceeds the 64-container look-ahead. Each layer also starts from its nearer side; X- and Z-oriented layouts share the same logic. Existing checkpoints finish in their original traversal order, and new checkpoints retain discovery direction and canonical queued coordinates.
- Candidate selection evaluates visibility/reach once per candidate rather than repeatedly in a comparator. An unanswered opening request no longer prevents actual approach progress from renewing the no-progress watchdog. Read-only Baritone ownership, collision-checked flight, server-confirmed contents, menu settling, and durable host acknowledgements remain intact.
- Offline checks cover wide-row snaking in both orientations, exhaustive coordinate round-trips, reversed and legacy checkpoints, invalid queues, and progress after an unanswered opening. Client update and a fresh scan are required to use the new order; the current live scan was left uninterrupted.

## 0.13.47 — Lower stash flight durability reserve

- Stash scans, withdrawals and deposits can take off with 64 elytra durability remaining instead of 161. Ongoing flight starts safe landing at 32 instead of 120 and no longer reapplies the higher takeoff threshold each approach tick. Cruise eligibility shares the same policy; unusable gliders remain blocked and low-reserve failures retain carried kits. Client update required.
- Boundary and production-call-site checks cover takeoff, ongoing flight, landing reserve, missing gliders and the shared navigation path.

## 0.13.46 — Hopper refill pickup receipts and failed-job cancellation

- Added a standalone **Kits** catalog to the host web UI, with per-kit contents and stash/chest locations. Kits and stash tables share a direct delivery modal with source, worker username, quantity, and recipient username; identity lookup and durable retry reuse the existing Kit Delivery workflow without the full job wizard. Worker clients do not need an update for this UI feature.
- Hopper-fed withdrawals no longer reject an exact server-confirmed carried pickup merely because the source slot has already refilled with an identical kit. The move begins only from a reconciled full menu, an exact server-observed source and an empty server-observed destination. Confirmation still requires that destination's exact box, exactly one matching-kit gain and an empty cursor; ordinary sources retain the source-change check. Wrong-menu/partial packets are ignored and uncertain clicks are never replayed.
- Failed client runs now accept their owning host's cancellation while retaining the original failure/uncertain receipt. They cannot resume failed work. Both hosts continue cancel delivery after late Failed reports and accept only cancellation-cleanup reactivation, never failed-work replay. Unacknowledged cancellation is retained rather than deletable history; explicit retries preserve the original failure and acknowledged cancellation remains idempotent.
- Regression checks cover identical hopper refills, cursor/intermediate/stale receipts, wrong kits/counts, occupied baseline destinations, cancellation after failure and late failure reports. Live validation is recorded in the stash incident log.

## 0.13.45 — Explicit remote Lobby Skip and canceled stash handoff

- `.worker lobby-skip start`, `stop`, and `status` use the existing authenticated host worker-command surface. Explicit start can retry an already joined 6b6t lobby without off/on toggles. Lobby walking uses owned custom input, including while backgrounded or in chat/pause screens; input is released on transfer, disconnect, disable, or a 60-second timeout, and other movement owners are not replaced. Automatic login skipping remains separate from explicit retries.
- Cancellation no longer repeatedly restores an interrupted, unconfirmed stash inventory click and traps the worker in cleanup. The canceled run retains its original uncertain receipt without replay or false confirmation; grounding and an empty cursor remain required before profile/control handoff. Uncanceled uncertain stash records block new work and are retained for inspection; drop/TPA reconciliation is unchanged.
- Offline checks cover command wiring, server matching, walk bounds, input cleanup, cancellation restore decisions, durable uncertain receipts and cursor gating. Client update required; no lobby walk was triggered at the live End stash.

## 0.13.44 — Recover stash flight stalls and interrupted homes

- Container flight approaches use interaction reach and the player's actual collision height, rather than reserving an empty placement cube for an existing chest. A three-second motion watchdog tries up to three collision-checked escapes/replans, logs approach diagnostics in the worker journal, and lands safely with carried kits retained if it cannot recover. Scans, pickups and deposits share this path; printer placement clearance is unchanged.
- Stash actions invalidate old arrival/warmup state on disconnect and checkpoint restoration, re-observe their location after host reconciliation, and resend interrupted `/home` requests when needed. Unloaded chunks and missing server response do not consume the arrival deadline. Failed arrivals retry at most twice; unsuccessful attempt counts persist until verified arrival, cooldowns begin at predicted/observed arrival, and confirmed inventory receipts and uncertain-click protections remain intact.
- Regression checks cover tight chest access, swept collision checks, bounded stall recovery, home retry limits, reconnect wiring and inventory gating. Live restart validation requires the new client; the active solo transfer was restarted on the existing client with carried-kit recovery first.

## 0.13.43 — Continue after carried kits are manually moved

- When a reconciled destination inventory shows no requested kits still carried and no click awaits verification, log the shortfall, report confirmed deposits separately, and continue the transfer. Missing kits are not counted as delivered; uncertain clicks still require verification.
- Shared kit workflows, carried-kit recovery, and crew transfer accounting consume actual deposit receipts and preserve missing-kit totals across checkpoints. Update the host and create fresh jobs to receive the revised workflows.

## 0.13.42 — Defer mismatched hopper outlets without losing the transfer

- Fixes the 0.13.41 regression that failed the whole solo transfer when a mapped hopper outlet held a different kit. The native pickup closes and defers that outlet, continues other candidates, and reports expected kit ID versus observed box names/types/items. A full wrong-kit outlet skips immediately; an outlet with space retains the bounded refill wait. Deferred sources persist in checkpoints and never count as verified exhaustion. Bottom withdrawals, top deposits and server-confirmed inventory moves are unchanged.
- The shared Lua kit workflow deposits any confirmed partial load before reporting deferral. Multi-kit transfers continue other types, revisit only deferred types once after that pass, and report still-unresolved columns with retained totals rather than loop forever or falsely complete. Both host adapters use the same workflows; update the host/package and client before a fresh transfer. Legacy parallel source leases remain unchanged and do not silently redirect workers into shared outlets.
- Offline regression checks cover full versus refillable outlets, zero/partial pickup deferrals, checkpoint restoration, other-type progression, bounded revisit, accumulated totals and unresolved reporting. Live validation pending; no transfer was restarted during this patch.

## 0.13.41 — Hopper-column withdrawal and deposit endpoints

- Stash withdrawals resolve live, enabled hopper connections between chest inventories and use the bottom outlet instead of navigating through mapped upper chests. Vertical/diagonal layouts, rotated storage and either double-chest half are recognized; unrelated, branched or redstone-locked connections retain independent handling. Empty outlets wait 40 ticks plus eight per hopper link for server-confirmed refill. Repeated upper picks for an already-drained outlet are skipped within the action, and withdrawal receipts identify the actual bottom chest. Changed connections or non-matching items blocking the outlet cannot masquerade as exhausted stock.
- Reserved-column deposits use the connected top chest and allow the same bounded drainage wait before allocating another column. Exact-kit compatibility, host column approval and ordered server-confirmed inventory clicks are unchanged. No rescan or host update required for solo transfers/resupply; full inventory scans still inspect upper stock for quantities. Legacy parallel crew plans that split a hopper column fail safely instead of redirecting two workers to the same outlet; whole-column source leases remain follow-up work.
- Offline checks cover vertical/rotated diagonal chains, double chests, wrong-facing/locked hoppers, branches, selection bounds, bounded refill/drain and production endpoint wiring. Client update required; live validation pending. The prior solo run confirmed 709 deposited kits before an empty-source recheck timed out approaching an upper chest; it remains stopped.

## 0.13.40 — Stash navigation survives Monocle pauses

- Shared stash navigation recognizes Monocle's actual temporary Baritone pause controller, used by food/combat modules, instead of treating it as a foreign takeover. It waits without replacing its goal and resumes when the pause ends. Cleanup can release its owned goal while paused; changed goals and unrelated active controllers remain protected. Regression checks cover the actual pause-controller type, foreign/owned/inactive controllers and production move/cleanup wiring. Client update required; live validation pending.
- The Unbans solo transfer uses 32-second source/destination home cooldowns in its host-sent workflow. This is a job override, not a change to other workers' rank-specific home settings.

## 0.13.39 — Confirmed stash pickups and deposit-first recovery

- Stash withdrawals reuse the explicit-slot, non-predicted two-click move used by deposits. The next move waits for local inventory and the server snapshot to agree. An intermediate empty-source/cursor snapshot or late probe is no longer an immediate failure: confirmation requires a changed source slot, the exact box in its selected carried destination, exactly one matching-kit increase, and an empty cursor. Already-confirmed receipts cannot be revoked by a late packet. Uncertain moves still time out without replay; their checkpoints now include slot/count/cursor verification diagnostics.
- `task-stash-transfer` supports `depositCarried: true, depositOnly: true` for a recovery pass that counts actual carried matching kits and deposits them without withdrawing anything or declaring the source exhausted. Recover the workers' carried loads before starting the next crew transfer. This uses the existing inventory/column/home workflow rather than a separate transfer engine. Native assertion checks cover intermediate/late receipts, exact-item mismatches, no replay, shared move usage, and deposit-only completion. Client update required; live retry remains pending.

## 0.13.38 — Parallel crew stash transfers with overlapping teleports

- New **Crew Stash Transfer** workflow supports two or three workers, keeping the solo transfer unchanged. One leader uses its mapped stash homes and cooldowns; followers request `/tpa` as soon as its actual `/home` command is reported, overlapping warmups rather than waiting for arrival. Both hosts auto-accept those job-owned requests after the existing configurable delay. The client immediately checkpoints/reports the home-send event, verifies the intended stash as well as the nearby leader, and retries failed TPAs at most twice without replaying inventory work.
- The shared, durable coordinator partitions source chests and leases separate destination columns. Workers withdraw/deposit in parallel and wait for the slowest batch before the next travel leg. Counts, cursors and acknowledgements survive retries/restarts; cancellation cannot release another command. Paused/inspection workers wait for reconciliation, and a bounded batch watchdog retains carried kits rather than hanging indefinitely. Repeated teleport actions clear the previous token's acceptance state.
- WebUI and in-game host forms expose the workflow and TPA warmup; compact crew-transfer telemetry includes phase, per-worker readiness/batches and verified totals. Full non-lazy source scans and existing inventory/column safety remain required. Update both host and all participating clients; offline three-worker replay and real socket checks cover home-send release, repeated TPAs, reservations, barriers, cancellation and restart. Live-server testing is still required; no live job was launched or host restarted during development.

## 0.13.37 — Bind stash deposits to their requested chest opening

- Stash deposits ignore container packets until a destination is selected and its opening requested, including late source packets during `/home` cooldown/warmup and host column acknowledgement. The first destination menu stays bound across verified clicks; unrelated menus cannot replace it. A closed/replaced menu reopens and obtains fresh host approval only when no inventory move is pending. Uncertain moves retain their existing verification timeout and inspection requirement.
- Regression checks cover pre-opening packets, the post-reservation/pre-navigation gap, host acknowledgement waits, duplicate/wrong-menu receipts and checkpoint ownership reset. Applies to single-kit and multi-kit deliveries through the shared native deposit action. Client update required; the running transfer was not interrupted or upgraded. See the [live failure record](docs/incidents/2026-10-06-stash-deposit-before-opening.md).

## 0.13.36 — Multi-kit stash transfer and cleared-goal recovery

- **Transfer Stash Kits** moves all known or explicitly selected Shulker Type IDs between two stashes in one job, one type at a time. It calls the existing Kit Delivery workflow for repeated batches of up to 36 boxes, preserving verified inventory moves, exact-kit column reservations, cooldowns, cancellation and checkpoints. Both hosts, the WebUI preset form and the in-game host editor support it. Plans are fetched lazily per type instead of bundling a large stash into Lua arguments; per-type totals survive completion.
- Optional **Deposit already-carried matching kits first** explicitly unloads selected types before any new source pickup, allowing recovery of a full carried load from a failed job without silently donating personal kits. Confirmed withdrawals retain known source kit identities so later types do not search every chest emptied by an earlier type. Old records without those identities remain conservative and may need rescanning.
- Shared stash navigation distinguishes a goal cleared by Baritone on arrival/path failure from a foreign replacement goal. It can reacquire its own cleared goal; active unrelated processes and replacement goals still stop it. Source/deposit watchdogs and uncertain-click protection remain intact. Host/core/native regression checks cover planning, nested workflow results, retries, cancellation and cleared goals. Requires updating host and workers; no live job was restarted during development.

## 0.13.35 — Verify stash-home arrival and bound deposit waits

- Stash scans, pickups and deposits wait for both the configured home warmup and arrival in the correct dimension within the existing 256-block local-stash radius. A delayed teleport has up to 60 additional seconds; a missing/failed teleport reports the home name without navigating from the old stash or attempting inventory moves. Cooldowns and uncertain-transfer protection are unchanged.
- Destination opening retries now stop after 200 ticks with the chest coordinates and remaining kits retained. An unconfirmed deposit holds position and retains its verification timeout even if the worker is knocked out of opening reach, rather than walking/flying away with an unresolved move. Client update required; no transfer job was started.

## 0.13.34 — Skip known blocked chest lids

- Solo and worker stash scans detect solid blocks over chest lids before pathing, opening retries or Lazy Mode inference. Either connected half of a double chest can block opening. These containers are immediately recorded as unscanned with the blocking block and coordinates, not counted as empty or inferred stock; durable host acknowledgement is unchanged.
- Uses vanilla's redstone-conductor lid rule rather than treating every non-air block as an obstruction, so stacked chests, hoppers, glass and non-blocking slabs remain eligible. Hoppers themselves have no chest-lid restriction. Regression checks cover both halves, missing partners and the early-check ordering. Client update required; no host update or live-job restart was performed.

## 0.13.33 — Nearby-first stash scanning

- Scans choose an immediately openable or nearby container from a bounded 64-container look-ahead instead of always returning to the start of the next compass row. This reduces orientation-dependent backtracking without changing container ownership, double-chest deduplication, exact contents or durable observation acknowledgements. Lazy scans retain bottom/top-layer ordering.
- Queued container indices are saved with the discovery cursor so resuming cannot silently skip queued chests. Existing pre-queue checkpoints remain readable. Regression checks cover both storage orientations, complete coverage, checkpoint round-trips and malformed queues.
- Shared stash flight approaches now allow up to 20 blocks/sec between waypoints, capped by the user's ElytraFly setting, slowing to 5.6 blocks/sec within six blocks of the chest or two blocks of a route corner. Swept collision checks and verified opening reach remain unchanged. Client update required; ongoing scans are not interrupted or changed remotely. Live retesting remains pending.

## 0.13.32 — Respect Enderman Look during navigation

- Active Enderman Look in Away mode checks actual outgoing movement rotations after navigation and queued rotations, rather than checking only the early-tick camera direction. Unsafe gaze is redirected without changing the camera, position or steering. Rotationless movement also rechecks retained server aim; flight checks that looking straight down will not provoke an Enderman below the worker. Packet collision/ground flags and internal tags are preserved.
- At mode, hostile stun behavior, creative/pumpkin exemptions and module-off behavior remain unchanged. This prevents new gaze aggression; it does not pacify already-hostile Endermen or prevent other causes of aggression.
- Includes the long-distance stash flight preference and final chest-approach fixes from 0.13.30–31. No host update is required; enable Enderman Look in Away mode for the worker. Live retesting remains pending.

## 0.13.31 — Fly longer stash trips

- Scans, kit pickups and deposits prefer flight for horizontal trips over 64 blocks when Vanilla ElytraFly and a usable elytra are available. Unavailable flight equipment or obstructed takeoff retains Baritone walking. Once launched, crossing back below 64 blocks does not force landing.
- Long trips reuse the collision-checked local flight router in bounded segments, capped at the configured ElytraFly speed or 20 blocks/sec. Within the local chest-search range, movement returns to the slower precise opening approach. Chunk, hazard, durability, eating and safe-handoff checks remain in place.
- Regression checks cover the distance boundary, launch/flight continuity, walking fallback and a 200-block cruise. Includes the 0.13.30 final-approach fix; no host update is required. Live testing remains pending.

## 0.13.30 — Finish stash flight approaches at a real opening

- Elevated scans, withdrawals and deposits no longer consume the final flight waypoint merely because the bot is within 0.2 blocks. The actual eye position must be within opening reach and have a visible chest face; otherwise flight continues to the waypoint or replans a finished route. This addresses the live “Holding near chest” stall followed by a source-approach timeout.
- Regression checks preserve intermediate-waypoint tolerance while rejecting premature final arrival. Inventory confirmation, uncertain-click protection and bounded source failure handling are unchanged. Host changes are not required; live validation of the new client remains pending.

## 0.13.29 — Wait for disconnect acknowledgement in reconnect tests

- RWP reconnect checks wait for the host to observe socket closure before replacing the worker. This avoids racing the gateway's duplicate-live-worker protection on slower CI runners; production authentication and reconnect retry behavior are unchanged.

## 0.13.28 — Public release checkpoint

- Includes the 0.13.27 stash takeoff fix and preceding worker API, stash/kit transfer and flight resilience work. Regression fixtures use synthetic coordinates rather than an operator's live stash location.
- Stash-summary assertions select the named stash rather than assuming filesystem directory order, so the release checks behave consistently on macOS and Linux.
- Update hosts and workers together when upgrading from native protocol versions before 0.13.15. The full automated release build covers the client, coordinator, host, documentation and bundled notices; the latest takeoff change still needs a live retest.

## 0.13.27 — Take off beside stash chests without phantom collisions

- Stash takeoff sweeps use the player's actual bounding box instead of widening it into an adjacent chest. Stash flight routes also use the real player width, so the same phantom collision cannot return immediately after launch. Real ceiling, block, chunk and hazard checks remain in place; obstructed takeoffs report the launch and target coordinates.
- A regression reproduces the live chest-edge position near the world border, checks takeoff and initial flight, and still rejects real ceilings and chest overlap. Host changes are not required.
- Live 0.13.26 validation recovered the nine retained V4 kits and deposited another 65 before the next chest-layer approach exposed this takeoff issue. Fourteen confirmed pickups remain carried in the paused transfer.

## 0.13.26 — Hopper-aware withdrawals and recoverable source searches

- The failed V4 transfer targeted a scanned hopper, not a missing chest. Withdrawals now accept scanned storage containers and reuse the scanner's hopper perch/through-chest opening approach. Source diagnostics include coordinates, observed block type and confirmed pickup count.
- Missing containers receive a 40-tick settling window; openings, missing chunks and stalled approaches have bounded progress-aware waits. Recoverable source failures deliver an already-confirmed partial stash load before reporting failure. Uncertain clicks remain inspection-required and are never replayed.
- Updated built-in repeat-transfer workflows revisit sources after productive passes and require two empty passes separated by 20 ticks before declaring exhaustion. Hopper refills reset verification. Known source locations invalidated by our own withdrawals remain live search targets, without restoring stale stock counts or accepting unknown scan gaps.
- Same-world local stash travel skips `/home` within 256 blocks, using the selected withdrawal target when available. An unloaded destination does not itself force a teleport; navigation can approach it while waiting for chunks. Long-distance flight preference remains queued separately.
- Worker and shared-core regression checks cover hopper acceptance, partial failure checkpoints, final rechecks, refill detection and local travel thresholds. Update the host for the new built-in workflow when creating new jobs; existing job packages keep their original script. Live validation is still pending.

## 0.13.25 — Confirm deposits before hopper-fed storage moves them

- Live changed-slot diagnostics showed previously filled destination slots becoming empty while the inventory remained reduced. Kit deposits now use two ordered, explicit-slot clicks without local prediction, each requesting vanilla's immediate post-click full snapshot. Intermediate kit-on-cursor snapshots do not acknowledge placement.
- Each exact kit is verified at its explicit destination with an empty cursor and one fewer carried kit. A later hopper transfer no longer invalidates that completed receipt; delayed batch reopen checks have been removed. Stash and kit ender-chest deposits share the confirmed move helper, retain bounded waits and never replay an uncertain click.
- Regression checks cover intermediate pickup snapshots, wrong/pre-existing kits, duplicate receipts, hopper movement of previously confirmed kits, immutable baselines and 36 deposits crossing chest layers.

## 0.13.24 — Record changed chest slots on uncertain receipts

- Unconfirmed deposits retain the actual changed-slot manifests and their component differences from the carried kit, including before/after contents. This distinguishes merges or changed kit metadata from a missing insertion without weakening receipt checks or replaying clicks.

## 0.13.23 — Verify actual server-side kit insertion

- Live diagnostics found an exact kit in slot 1 while local prediction expected slot 2. Stash and kit ender-chest deposits now capture an immutable server-confirmed baseline, require one empty-to-exact-kit slot change plus one carried kit removed, and verify the actual insertion slot rather than a predicted one. Existing identical kits, concurrent chest edits and nonempty cursors cannot acknowledge a deposit.
- New clicks wait for local inventory to reconcile with the server snapshot. Batch reopen checks, bounded waits and no replay of uncertain moves remain in place. Regression checks cover displaced insertion slots, pre-existing identical kits, concurrent edits and immutable baselines.
- Live testing recovered all 27 carried kits and confirmed an empty inventory. A fresh 36-kit transfer verified 26 deposits across chest layers before a different receipt mismatch; the second pickup was withheld.

## 0.13.22 — Diagnose exact deposit-slot mismatches

- Unconfirmed kit deposits retain the server menu/state ID, cursor count, expected and actual kit manifests, exact-match slot locations and differing component keys in the worker checkpoint. Timeout details also identify the actual item/quantity and match locations. No uncertain move is replayed and no verification requirement is relaxed.

## 0.13.21 — Reconcile stale deposit slots before the next move

- Live 0.13.20 testing reached an upper stash chest, then stopped when an already-acknowledged destination slot appeared empty again. A stale slot or carried-inventory rollback now enters the existing close/reopen verification path before any new move, rather than tripping the duplicate-slot guard.
- Exact per-slot batch verification, bounded timeouts and no replay of uncertain moves remain unchanged. Regression checks cover stale acknowledged slots, inventory rollback, full chests and releasing slots after verification.
- Live retesting still stopped safely on an upper-chest destination-slot mismatch (carried kits 31 → 30, expected slot 1). Automated checks pass, but this does not yet resolve the full live transfer failure; the second pickup was withheld.

## 0.13.20 — Atomic kit deposits across stash chest layers

- Stash and ender-chest deposits use a single shift-click instead of picking a kit up onto the cursor and placing it with a second click. Exact destination-slot confirmation and final chest-reopen verification remain required.
- A mismatched in-flight stash snapshot triggers bounded refreshes without replaying the move or counting it as deposited. Unresolved verification still stops safely, now reporting the target chest, menu, stage, inventory count change and destination slot.
- Regression checks reproduce the old immediate rejection and verify a 36-kit load filling 18 remaining bottom-chest slots before depositing 18 kits into the next chest. Existing 0.13.18 hosts remain compatible.

## 0.13.19 — Send destination reservation requests outside scan telemetry

- Fix the live 0.13.18 deposit failure: native destination-column requests were still sent only from the scan telemetry branch. Deposits have no scan telemetry, so the host never received their requests. Pending stash findings now use the independent shared retry path, retaining the existing host acknowledgement and contents-verification requirements.
- Worker regression checks cover the scan-independent send path. A failed reservation attempt that withdrew kits but deposited none can be recovered with a deposit-only workflow using the carried kits; do not withdraw the same load again. Existing 0.13.18 hosts support the fix.

## 0.13.18 — Kit column reservations and live destination checks

- Both host modes persist kit ownership of destination columns. Matching-kit columns are preferred; otherwise a directly observed empty column is reserved. Mixed, loose-item, unknown and foreign-reserved columns are not free space.
- Workers restrict deposit searches to the assigned vertical/diagonal column, report live contents before moving a kit, and revalidate contents between clicks. Changed contents update the host catalog and redirect to another column before any new deposit. Verified batch observations keep destination counts current instead of being erased by late status receipts.
- The WebUI stash view shows observed free, reserved and conflicted column counts. A huge nearby stash checks its nearest loaded chunk rather than its distant minimum corner before deciding to use `/home`.
- Stash-to-stash deposits require **0.13.18 on both the host and worker**. Automated shared-core, client and socket checks cover durable reservations, changed contents, retries, exact kit matching and stale ownership; live transfers still need testing.

## 0.13.17 — Repeat kit transfers and full inventory batches

- Kit Delivery can transfer all selected kits between separate stashes, returning after each verified deposit until every selected source chest is confirmed empty. A partial final load is deposited; unavailable chests and uncertain inventory moves remain failures, not empty-stock evidence.
- Kit batches now allow all 36 inventory slots. Existing personal contents are retained; batches use only free slots. Optional inclusion of incomplete boxes matches the same kit type, not unrelated shulkers.
- Repeat transfers require full, non-lazy source observations and refuse truncated source lists (54 matching source chests maximum). Workflow receipts are compact while full withdrawal telemetry remains available; completed usage totals report actual deliveries.
- Native stash receipts are sent only alongside their matching active action/token, avoiding stale-receipt rejection after completion or between trips.
- Nearby loaded stash actions can navigate locally within 128 blocks without an unnecessary `/home` command/cooldown. Both the WebUI and in-game host task editor expose the new transfer controls. Update the worker to 0.13.17 before using repeat transfers.

## 0.13.16 — Full-inventory stash interaction

- Host dispatch prepares kit withdrawals only for kit-delivery/removal presets; custom deposit-only workflows carrying a kit type ID no longer stop coordination by being reinterpreted as withdrawals. Client-based hosting uses the same selection rule.
- Stash scans, withdrawals, and deposits open containers using the existing non-sneaking block interaction instead of requiring an empty hotbar slot or pickaxe. A full inventory can now reach the deposit phase without failing at the opener.
- All three paths share a current-container check, so a missing/replaced container is not clicked with a held kit. The opener does not move items or change the selected hotbar slot; server-confirmed transfer and batch verification remain unchanged.
- Native protocol 7 is unchanged; 0.13.15 hosts remain compatible. Automated checks cover shared opener routing and container validation; live full-hotbar deposits still need testing.

## 0.13.15 — Larger bounded native worker messages

- Native TCP and WebSocket worker records accept up to 256 KiB of UTF-8 text, allowing full inventory kit-transfer receipts without the old 16,000-character disconnect. TCP uses a checked four-byte byte-length prefix instead of Java's 65,535-byte `writeUTF` format.
- Each native incoming/outgoing queue is limited to 1 MiB of encoded text and 128 records. Oversized, malformed, truncated, and congested frames fail explicitly; writer failures retain their reason.
- Reconnected clients send their identity before task status, chat, or crew join requests, preventing secondary identity-rejection loops.
- Native protocol 7 requires updating both hosts and workers together. Existing chunked 4 MiB workflow packages and the separate public RWP draft limits are unchanged.

## 0.13.14 — Stash flight handoffs and useful-progress watchdog

- Consecutive nearby stash actions in a worker workflow retain controlled flight rather than landing between every scan, withdrawal, or deposit. Nested workflow calls retain the same handoff; teleport, profile changes, cancellation, and final cleanup still wait for safe landing.
- A bounded trail of actual flown positions survives compatible handoffs and gives final cleanup a return path. Server corrections and disconnects discard stale routes; landing still checks the current world for collisions and support.
- Scan approach timeouts reset only after a new closest approach of at least a quarter block. Jitter, movement away, and retracing the same approach no longer hide stalls. Scan telemetry includes no-progress ticks and closest opening distance.
- Automated worker and flight checks cover compatibility boundaries, handoff ordering, bounded return trails, and scan watchdog expiry. Live multi-step stash behavior still needs testing.

## 0.13.13 — Batched kit deposits

- Destination stash deposits keep a chest open between individually server-confirmed moves, reopening once when the chest fills or the requested batch is complete.
- The final reopen verifies every deposited box's exact slot, contents, name, and quantity together with the full carried-inventory change. Unverified batches remain pending and cannot be replayed after a restart.
- Unexpected menu changes cannot take over an unfinished batch; live pause/disconnect handoffs verify that batch before continuing.
- Worker checks cover multi-box and multi-chest batches, duplicate updates, incorrect or missing boxes, inventory changes, and interrupted checkpoints. Live deposit behavior still needs testing.

## 0.13.12 — Worker flight and stash efficiency

- Landing checks use the actual player footprint, including block edges. Restarted flights search all nearby landing columns, checking at most 16 candidates per tick instead of performing a large search on one frame.
- Local flight detours can route beyond an intermediate waypoint buried inside an obstacle.
- Confirmed withdrawals keep the source chest open when subsequent boxes come from the same chest. Kit inventory counting checks the requested kit once per stack instead of repeating it for every mapped source.
- Destination chest selection uses existing loaded block entities rather than scanning every block in the stash cuboid. Verified deposits reuse the reopened chest's contents immediately without an extra synchronization wait.
- Withdrawals wait for server container contents before selecting a box; cancellation and disconnect cleanup no longer dereference a missing player while restoring the hotbar slot. Carry-mode completion reports the correct destination.
- Automated flight, stash-boundary, worker, and printer integration checks pass. Live transfer and landing behavior still needs testing.

## 0.13.11 — Flight route reliability

- Flight routes skip unnecessary grid turns and check short overlapping body sweeps, so nearby blocks outside a diagonal corridor no longer reject the whole route.
- Travel and stash hunting reuse local detours between ticks, retry failed searches once per second, and discard cached routes after server corrections or reconnects.
- Elevated stash work replans after displacement and reports failed flight searches instead of claiming to be holding near an unreachable chest. Normal ElytraFly checks the correct chunks at negative coordinates.
- Geometry, route-following, printer integration, settings, and worker checks pass without a running game; live flight still needs testing.

## 0.11.3 — Render-distance repair survey

- Repair HUD now scans the complete loaded road out to render distance and reports the next defect type, distance, and coordinates, or the verified clean horizon.
- Clean-stretch Repair flight now uses ElytraFly's bounded high-speed survey lease. A configured speed of 5 blocks/tick yields 100 blocks/sec while each movement step remains collision-, footing-, chunk-, and server-verification-checked.
- Repair flights remain capped to 126-block legs and chain as chunks load; normal building, printer, supply, and crew travel retain their conservative speed ceilings.

## 0.11.2 — Repair transit

- Solo Repair jobs now scan the loaded highway pattern and use the existing ElytraFly autopilot to cross clean stretches, landing immediately before the next defective row.
- Added `fly-over-clean-stretches` (on by default for Repair) and a configurable minimum flight distance (16 blocks by default). Flights are bounded to verified, loaded, supported road and fall back to the reached row if takeoff or flight stalls.
- Build, Pave, Clear Tunnel, diagonal, and coordinated crew behavior are unchanged; crew repair flights require host-assigned repair segments rather than competing client-side scans.

## 0.11.1 — Solo stash round trips

- Wired mapped stashes into solo Highway Builder exhaustion. After local inventory/shulker/ender-chest sources are exhausted, it saves the road as `/sethome monocle_work`, waits the configured server cooldown, visits the stash, refills the ender chest from indexed shulkers, waits again, returns, verifies the saved road position, and resumes the same job.
- Added solo stash restocking and return-home-name settings. Invalid return names or absent matching stash stock retain the previous fail-safe resource-exhaustion shutdown.
- Corrected `/home` cooldown accounting from per-home to per-server and kept it persisted across restarts, preventing stash and return commands from incorrectly bypassing one another's cooldown.
- Reuses the existing stash catalog, authoritative withdrawal invalidation, Baritone container navigation, and refill implementation. Live server command timing and the complete round trip require an in-game smoke test.

## 0.11.0 — Autonomous stash routes

- Made a valid `/home` name mandatory for every stash definition at the shared wire/catalog boundary. Legacy definitions remain editable but cannot be scanned, exported, or used for resupply until a route is added and saved.
- Reworked stash setup into an explicit select → validate/save → save/scan flow. Scanning now saves the definition and this worker's route before opening containers, while readiness and validation failures remain visible in the workspace.
- Persisted `/home` cooldown use across restarts and retained each worker's individual route, warmup, and cooldown. TPA return timing continues to use the host's configured teleport settings.
- Added catalog-backed highway shortage dispatch to the in-game host, matching the standalone host's existing stash refill → ender-chest deposit → TPA return workflow. Resource selection remains batched and authoritative observations are invalidated after confirmed withdrawal.
- Automated checks cover mandatory route validation, refill planning, both host implementations, workflow transport, and client compilation. Live `/home`, container, and TPA behavior still requires an in-game smoke test.

## 0.10.0 — Operator HUD and notification history

- Added one reusable Operator Panel HUD element with Activity, Crew, Supplies, Navigation, and Notifications presets. Healthy work stays compact; blocked work exposes the actionable module or crew detail.
- Added full Highway Operator and Minimal HUD layouts. Both reuse the existing anchored, snapping HUD editor and provide per-panel width, scale, line-limit, background, and live preview controls.
- Supply cards distinguish local observation time, road forecasts remain explicitly estimated, and crew rows identify live versus stale worker reports.
- Grouped notification cards display repeat counts and now retain one bounded history incident per grouped card instead of duplicating every update. Notification history and feed previews are available directly from the HUD screen and Notifier.
- No highway execution, worker protocol, supply policy, or module defaults changed. Native rendering and placement at multiple GUI scales still require an in-game smoke test.

## 0.9.2 — Management workspace refinement

- Management windows now retain vertical scroll position when opening a child editor or switching away and back. Existing section expansion and editor instances remain authoritative.
- Workers remembers its Crews, Jobs or History page; Workflows remembers its folder. The redundant Workflows sub-page was removed from Workers now that Workflows is a primary destination.
- Worker action groups, the Stash catalog/editor, Highway Builder setup, and Inventory Manager adapt to narrow windows. Compact inventory rules become expandable cards instead of an overflowing five-column table.
- Stash cards retain expansion across catalog refreshes, scan controls track the live scan state, and recovered errors return to normal status styling. Empty and error explanations remain visible text rather than color-only indicators.
- No worker protocol, workflow execution, module behavior, highway settings or job defaults changed. Native GUI rendering requires an in-game smoke test.

## 0.9.1 — Module detail workspace

- Reorganized module details around live status and module-specific controls, settings, then keybind/export controls. Highway Builder and Inventory Manager retain their dedicated operational screens and open this shared view for advanced configuration.
- Added setting search across names, descriptions and section titles. Filtered sections expand temporarily, while normal expansion choices, query text and scroll position remain intact when navigating away and back.
- Common setting groups remain first; groups named Advanced are consistently moved to the end without rewriting their saved expanded/collapsed state.
- Added explicit personal/job-profile and movement/inventory control ownership. Active worker jobs link directly to their inspection screen; native crew jobs link to Workers.
- No module behavior, highway execution, host protocol or defaults changed. Native GUI rendering requires an in-game smoke test.

## 0.9.0 — Right Shift workspace, first slice

- Promoted Workflows and Stashes alongside Modules, Workers, HUD and Profiles. Settings groups the existing Config, appearance, Friends, Macros and pathing editors; addon tabs remain visible.
- Kept draggable module category columns. Search now matches names, enabled aliases, descriptions and setting text, with All/Active/Favorites filters, counts and a retained query/filter. Category scroll offsets survive navigation; existing window positions and expansion preferences remain intact.
- Added a worker-control/profile-overlay indicator and shortcut from Modules. This is assignment-level context, not a new movement or inventory ownership system.
- Navigation wraps on narrower windows; Ctrl+Tab / Ctrl+Shift+Tab switch destinations, while Ctrl/Cmd+F focuses module search.
- This is the navigation/Modules slice, not the completed management-screen redesign. No highway execution, host protocol, settings defaults or shutdown changes. Existing 0.8.4 host remains compatible; native GUI rendering still needs an in-game smoke test.

## 0.8.4 — Worker and crew operator controls

- Added live job controls and per-worker Detach/Rejoin to the in-game worker/crew management screens and WebUI. Whole-job actions and individual participation are explicitly separated; cancellation and cleanup ownership are explained.
- Per-worker participation intent is host-owned and persisted. Whole-job Resume cannot silently rejoin an operator-detached worker. Native highways reuse the existing safe withdrawal/return handshake, retaining container recovery and required-duty/site-anchor checks; rejected or pending handoffs explain why.
- Added direct preset launch shortcuts and session-local launch preferences. New highway origins come from current positions rather than remembered coordinates. Every dispatch still requires review.
- Preserve WebUI editor nodes, selections, expanded sections and scroll through inspection refreshes; update operational status without rebuilding editors. In-game management refreshes update labels in place and retain job section expansion when membership changes.
- Use matching 0.8.4 host and client builds for both operator interfaces. Automated API, native handoff and browser checks use simulated workers; a live Minecraft smoke test remains required. No mining, paving or restock policy changes.

## 0.8.3 — Reusable job presets and passive following

- Added preset-library controls in both host interfaces: inspect, duplicate, rename/move and preview guided changes to future-job defaults. Built-ins remain read-only; running jobs retain captured settings. Stale guided edits are rejected.
- Added reviewed dispatch of saved presets, including the built-in 6b6t highway policy, Travel, Wait and Follow crewmate. Highway start coordinates/direction are rebound at launch without changing the saved preset.
- Added reusable `bot.follow({target=UUID,radius=3,ticks=0})`: walking through loaded safe routes, live player positions, waiting when the leader disappears, and normal job cancellation. The leader receives no job. Combat/bodyguard behavior is intentionally not included.
- Follow presets require 0.8.3 clients and matching host/UI; no changes to highway mining, paving or supply execution. Movement and visual UI still require operator smoke testing.

## 0.8.2 — Profile comparison and personal copies

- Added on-demand, per-module comparison of personal settings, a selected host-captured overlay (or latest live request), and actual worker settings in both host interfaces. Snapshots show their age and do not pretend omitted settings are host overrides.
- Bounded and chunked readback over the existing authenticated worker connection; request/run/worker matching, timeouts, and capability checks keep older clients and late responses from being mistaken for fresh readings. No extra recurring full-profile telemetry.
- Workers can inspect their received job profiles and explicitly save an independent personal copy under a new name. Copying never applies settings, overwrites an existing profile, or starts a job executor. Profile rediscovery no longer overwrites saved module files.
- New job leases checkpoint default-valued settings too, enabling accurate personal-baseline comparisons. Missing values from older checkpoints remain labeled as unrecorded.
- Readback requires 0.8.2 workers and the matching host. The latest live request remains a patch, not a cumulative desired-state profile; personal copying is intentionally local-only.

## 0.8.1 — Guided live job settings

- Added matching guided editors to the in-game host and WebUI for Speed, Elytra Fly speed, Auto Eat thresholds, and common module activation controls.
- Require an explicit target and preview before applying to one worker or all unfinished workers in a job. Preserve unrelated settings and future-job defaults.
- Keep drafts intact while configuration acknowledgements refresh. Proposed values are clearly distinguished from worker readings; actual readback and preset comparison remain later milestones.
- Standalone operators need the matching host distribution for the new WebUI. No highway execution or worker protocol changes.

## 0.8.0 — Configuration visibility

- Added a read-only job configuration inspector to the in-game host and standalone WebUI, showing captured profiles, module overrides, native highway supply capabilities, and the latest live configuration request for each worker.
- Distinguished pending, accepted, rejected, and unacknowledged-at-end requests without confusing captured settings with live worker state.
- Documented overlay semantics and added a staged roadmap toward 1.0, with separate operator-control, GUI, HUD, logistics, performance, and release-hardening milestones.

## 0.7.139

- Made crew cancellation stop Highway Builder unconditionally, preventing a cleared assignment flag from leaving the module running as an uncoordinated solo builder.

## 0.7.138 — development checkpoint

- Removed ender-chest farming from the built-in 6b6t highway workflow. Default jobs search carried shulkers and ender-chest contents but never consume ender chests as paving material.

## 0.7.137 — development checkpoint

- Made shared-supply donors discard stale rear service waypoints before opening a shulker, and let recipients follow the donor's verified container anywhere along the active highway corridor.

## 0.7.136 — development checkpoint

- Rebased stale detached-restock sites onto the worker's current completed road instead of sending it hundreds of blocks backward after an earlier failed attempt.
- Made managed restocking reserve only the physical container-recovery slots and sacrifice expendable filler when necessary, rather than prematurely reporting that the inventory is full.

- Added experimental **Roles** highway sharing. Dedicated excavators take centered front positions and remain 5–16 rows ahead; paving specialists retain separate rear lanes, while dual-duty workers are permitted between both fronts.
- Made role-based column ownership match physical worker positions so a centered excavator owns the full excavation face without forcing paving and rail work onto an edge lane.

- Rebuilt Crystal Aura around scored attack plans with proactive airplaced obsidian bases, ping-adaptive server confirmation, spawn-driven breaking, safer primary-target selection, friend protection, health reserves, and optional damage-simulated one- or two-block cover.
- Updated Crystal Aura defaults for anarchy combat: movement prediction, confirmed base building, smart cover, and silent switching are enabled while fixed hurt-time throttling remains off.

- Highway Builder now automatically retries transient movement, route, cursor, and server-verification stalls in solo and crew jobs while preserving pending work.
- Supply recovery now abandons confirmed-missing or externally collected containers instead of permanently pausing, skips exhausted ender-chest sources, and retries around occupied supply sites.
- Double ender-chest restocking now degrades to a single chest when the pair is blocked, changed, unsupported, or exposed as only 27 slots by the server.

- Added an authenticated crew directory to connected workers, showing crew status, live job, and occupancy without disclosing crew keys.
- Added per-job public joining controls to the in-game host and standalone WebUI. Eligible workers can switch crews through the existing encrypted key handoff and join a highway already in progress.
- Host-driven worker moves now carry the destination's active job through reconnect, so moving an idle worker into a working crew no longer requires a second manual admission step.
- Added host-owned Auto TPY for exact same-crew `/tpa` commands, with a 10-tick delay, fresh server/identity checks, authenticated delivery, and controls in both Workers host UIs.
- Added last-line highway recovery through a healthy crewmate using the authenticated TPA/TPY path. A worker already awaiting teleport recovery cannot be its anchor.
- Capped native highway crews at three workers across host, workflow, join, and assignment boundaries.
- Kept worker connection controls visible while connected, added an explicit disconnect action, and allowed offline workers to change host address/port without discarding their saved assignment.

## 0.7.128 — development checkpoint

- Continued hardening of coordinated highway excavation, paving, verification, crew rejoining, supply recovery, and managed inventory.
- Added the standalone Workers host, authenticated control API, bundled operator WebUI, telemetry, resource accounting, and live module configuration.
- Added workflow-backed stash scanning and resupply foundations, Workers API planning, and shared coordinator extraction.
- Added Air Mine, Derp, Lobby Skip, Stash Manager, and supporting module/UI tests.
- Ported Highway Builder's experimental managed loadout policy into Inventory Manager so solo and coordinated operation share one implementation.

## 0.7.x — Workers and standalone coordination

The 0.7 line introduced shared coordinator code, the standalone Java host, native highway hosting, WebSocket transport, the WebUI, durable cancellation/recovery, worker diagnostics, resource exchange, stash telemetry, and increasingly adaptive highway resupply. See the [Workers guide](docs/bots.md), [host guide](host-service/README.md), and [incident index](docs/incidents/README.md).

## 0.6.x — Throughput and detached supplies

The 0.6 line added distributed stash hunting, rolling supply departures and rejoins, live-model rendezvous, running fallback, compact supply spacing, background Auto Eat, and predictive resource telemetry. The staged highway measurements are preserved in [performance-builds.md](docs/performance-builds.md).

## 0.5.x — Workflows and resilient crews

The 0.5 line added the embedded Lua workflow runtime, nested actions, captured profiles, priority queues, detached resupply, work-sharing modes, sliding server verification, predictive supplies, cancellation recovery, and bounded job history. Lua is embedded; users do not install a separate interpreter.

## 0.4.x — Workers control room

The former Swarm module became the Workers control room with crews, jobs, host-managed assignments, per-crew authentication, recovery records, and shared-width highway construction. `.bot` remains the command namespace for compatibility.

## 0.3.x — Shared services and module overhauls

This line introduced community chat, the notification feed, encounter history, early crew building, and broad module overhauls including Auto Reconnect and Logout Spots.

## 0.2.x — Monocle UI and highway foundation

This line established the Monocle visual identity, bundled Glacial Indifference fonts, Printer Helper, Schematic Selector, and the first extensive Highway Builder overhaul.

## 0.1.x — Initial Monocle builds

Initial branding, packaging, launcher artwork, and early Highway Builder behavior were established on top of Meteor Client.
