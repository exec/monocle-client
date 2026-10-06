# Changelog

This file records major Monocle milestones. Detailed development and failure analysis lives in the linked guides, incident records, and Git history.

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
