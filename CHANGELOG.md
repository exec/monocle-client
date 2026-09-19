# Changelog

This file records major Monocle milestones. Detailed development and failure analysis lives in the linked guides, incident records, and Git history.

## Unreleased — 0.7.128 development checkpoint

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
