# Monocle coordinator core

Shared Java 25 code used by the existing client host and the [standalone host service](../host-service/README.md). This artifact is a library, not an executable. No account, world or rendering code is included.

## Included

- The existing bounded Lua evaluator, moved without changing its public class/package or workflow API.
- Queue priorities, per-worker overrides and FIFO tie handling.
- Pause/resume/cancel record mutations, terminal-state classification and task status aggregation.
- Missing-checkpoint reconciliation and cancellation-cleanup command selection.
- Teleport scope, expiry, pending-action and acknowledged warmup rules, with explicit time inputs. The native host controller also retains its existing monotonic transport/cleanup timers.
- Immutable player observations, position freshness, proximity checks, TPA recipient and jobsite-anchor selection. A local host player is optional; remote-only decisions do not manufacture one.
- Captured row verification masks and checkpoint decisions. A participating host's observations take precedence, including when they are missing; otherwise the active worker's observations apply. Unknown is never confirmed.
- Authenticated bounded LAN connection/listener, atomic task journals, task envelope/chunk construction, worker status transitions, generic dispatch and shared history policy (0.7.3).
- Native highway host state machine, preparation, lane/service generations, mining ownership, supply reservations, peer resource-exchange decisions, completion and durable cancellation receipts (0.7.4). Native workflow libraries, geometry validation and resource accounting also live here.

`BotScheduler` uses `QueuePolicy`, `PlayerObservation` and `HighwayJobs`; `BotRuntime` shares terminal-state rules. `SwarmCrew` and the service's `HighwayHost` both inherit `HighwayCoordinator`. Hooks isolate local actions, world observations, paths and status reporting; core coordination has no Minecraft dependency. The client embeds the core JAR via Fabric's nested-JAR support, so users install only the normal Monocle JAR. Gson and LuaJ are existing dependencies.

## Build and verify

From the repository root with Java 25:

```sh
./gradlew :coordinator-core:build
./gradlew botsCheck
```

The first command builds `coordinator-core/build/libs/monocle-coordinator-core-<version>.jar` and runs an assertion-based replay with only this library, Gson and LuaJ on the runtime classpath. It explicitly fails if Minecraft, Fabric or GLFW becomes loadable. Queue/cancellation/restart traces must match before and after JSON checkpoint serialization. The second command exercises the client adapter and existing bot regression checks, including compiled calls into this shared library and persistence ordering. Root `build` runs both.

The core JAR is a thin development library, not an executable or an extra mod to install. Downstream consumers need its Gson/LuaJ runtime dependencies. The sources JAR and GPL-3.0 license are included in the module's build output.

## Ownership contract

Queue records are the existing internal, validated task schema. The caller owns them on a single coordinator thread, preserves insertion order, authenticates and validates incoming messages, and persists changed records before sending effects. The core does not grant a second host authority or make game actions safe to repeat. A missing executed worker checkpoint still requires inspection; a persisted cancellation cannot become resumed gameplay.

## Still client-bound

Live profile capture, world sampling, movement, inventory clicks and native action recovery still use the client adapter. TPA/stash task orchestration and individual priority borrowing/return travel remain client-host-only; the standalone task adapter pauses a highway crew during worker preemption. There is no host-handoff mechanism, rendering suppression or focus emulation. Existing messages remain compatible; service-capable workers announce `taskProtocol: 1` and enforce an optional immutable `supportedActions` list in task metadata.

## Observation boundary (0.7.2)

`PlayerObservation` contains only UUID, display name, server/dimension scope, optional position and adapter-supplied monotonic receipt time. A task heartbeat does not refresh a position. The client adapter rejects disconnected/replaced sessions and supplies only eligible crew members to target selection. Selection fails on ambiguous remote names, missing worlds or expired observations. UUID selectors remain unambiguous. World scope is an explicit task-creation input, including for a return to a job in a world other than the host's current world.

`RowVerification` accepts already authenticated, current-generation, same-world worker progress and a captured map of host row results. The adapter checks remote report freshness and performs at most six unique row samples per pass (current row plus five ahead), on the client thread. The core has no callback into Minecraft for its mask/checkpoint decisions. Host authority is an explicit input from the active roster; an absent host observation is not permission to use worker claims. Remote-only verification consumes only admitted worker reports. These inputs are transient observations, not durable evidence to replay after restart.

Standalone checks exercise fresh/stale/unknown positions, local-host and no-local-host targets/anchors, ambiguity, rolling masks, checkpoint rollback and authority selection. Client checks also exercise wrong-world/generation rejection and compiled delegation into this library. Service socket tests now exercise native preparation, detached supplies, continued building, rejoining, verified completion and offline END receipts without loading Minecraft. Neither host may run alongside a competing coordinator for the same workers.
