# Changelog

This file records major Monocle milestones. Detailed development and failure analysis lives in the linked guides, incident records, and Git history.

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
