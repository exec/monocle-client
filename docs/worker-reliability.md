# Unattended worker reliability

This is the remaining reliability review after the 0.14.5 readiness and command-receipt fixes. Implement these in separate batches; a passing offline test is not a claim of safe unattended operation on a live server.

## Completed musts — 0.14.5

- Passive world/lobby readiness is independent of Lobby Skip movement and is exposed to actions, safety controls and both host interfaces.
- Correlated lifecycle/configuration receipts distinguish queue admission, worker acknowledgement, completion, rejection and uncertainty. Offline cancellation remains authoritative without pretending cleanup has completed.

## Should 3 — fault replay (offline baseline implemented)

Run with Java 25:

```sh
./gradlew unattendedReplayCheck
```

This combines existing native inventory/checkpoint tests, shared coordinator replays and new authenticated host-socket scenarios. All journals and sockets are isolated test fixtures. It neither connects to a Minecraft server nor changes the running host.

- A real built-in Kit Delivery Lua program completes two simulated inventory loads. After the first deposit commits, its reply is lost and the host restarts. Explicit resume retains the execution, repeats the same receipt, and finishes the second load without reinstalling work or doubling catalog totals.
- A delayed observation from the first action cannot overwrite the second action's stock. The final delivered count matches the two accepted deposits.
- Cancellation during uncertain cleanup survives host restart and worker reconnect. Cleanup-only resume is distinct from gameplay resume; once cleanup is acknowledged, its command receipt settles.
- Loss of a previously executed worker checkpoint requires inspection, not automatic reinstallation. Authenticated absence can settle cancellation without replaying side effects.
- Three-worker destination leases survive lost replies, a departed worker and a serialized host checkpoint. An offline worker's unresolved column is not reassigned.
- Existing native checks cover delayed/stale inventory packets, partial bulk receipts, interrupted deposits, bounded retries, missing sources, full inventories and retained recovery checkpoints. Shared team tests complete a job through failed TPA, partial loads and exhaustion.

For only the new socket scenarios, use `./gradlew :host-service:faultReplayCheck`. The ordinary `check`/`build` paths include the scenarios as well.

Remaining acceptance: live packet timing, server restarts and repeated unattended runs. The fixture simulates inventory/movement outcomes; it does not execute the game-backed runtime over sockets. Shared-core coverage applies to both hosts, but the socket test exercises the standalone adapter, not a running in-game host.

## Should 4 — action-aware stall recovery (implemented, 0.14.6)

The existing native action runtime now owns a small active-time watchdog. Reports expose `actionProgress`: `phase`, `expectedNextEvent`, `progressAgeMs`, `recoveryAttempts`, `state`, optional `target`/`closestDistance` evidence and an `incidentId` for recovery/pause episodes. The optional bounded field is tied to the reported action token, not a separate control protocol. Both hosts store it; WebUI and right-shift task inspectors show it. Recovery transitions enter client/host logs rather than creating a per-tick event stream.

- Stash scan observations/discovery, confirmed withdrawals/deposits, meaningful approach improvement and advancing highway mining reset inactivity. Phase/target churn, tiny jitter, countdown text and the monitor's own retries do not. Verified units reset the recovery budget; moving again alone does not replenish it.
- A two-minute active inactivity window allows up to two owned navigation replans. Stash flight replanning retains the safe landing trail; highways use the existing `crewNudge`. Pending inventory reconciliation, host leases, drops and teleports never receive a speculative click, resend or new executor. Their existing bounded native reconciliation remains authoritative.
- Finite home cooldowns/warmups, eating/combat, server lag/loading, safe profile grounding, Wait and intentionally ongoing follow/module work do not consume the clock. Clock gaps are capped at one second; disconnected/restore paths reset the sample clock and establish a fresh approach baseline without consuming offline time or restoring a spent retry budget. Existing native highway pauses remain native-owned and are reported as waiting, not forcibly resumed.
- With no safe navigation recovery, two minutes of inactivity requests safe suspension. Navigation gets one interval per retry before suspension (up to six minutes with no meaningful progress). The workflow action, transaction journal, cursor guard, landing and original-profile handoff remain intact. Cleanup may still require inspection; "suspending" never means cleanup has succeeded.
- Only this execution is held out of the shared queue. Explicit Resume or Rejoin clears the hold; repeated reports of the dismissed incident cannot reapply it. A later new incident can hold it again. Cancel overrides the hold and still reconciles pending effects. Native highway lane withdrawal uses existing crew rules; those rules may temporarily synchronize other builders during handoff.
- Configured Discord owner notifications are deduplicated by incident and journaled across host restarts. They are queued as soon as the host receives the stall, even if safe cleanup is not finished. No credentials means no outbound notification.

Offline coverage includes healthy waits longer than the watchdog, slow incremental movement/mining, changing targets without progress, two retries, preserved budgets across restore, uncertain clicks, malformed telemetry, explicit recovery/stale incidents, cancellation and Discord restart deduplication. Run `./gradlew unattendedReplayCheck :host-service:hostServiceCheck`.

Remaining acceptance: live calibration and game-backed navigation/cleanup timing. This is a final guard around existing executors, not proof that every game condition can recover autonomously. Intentional indefinite follow/module work is not diagnosed as a stall; native highway hazards and transfer uncertainty are not overridden. Solo/client-only native actions retain their existing recovery rather than receiving host-owned suspension.

## Should 5 — Stash Quartermaster (pending)

Start with idle-worker scans of unknown or stale containers, using a configurable 24-hour default freshness threshold. Operator jobs take priority. Later add replenishment targets using existing stash transfer workflows, not another inventory executor. Preserve exact kit identity, observations and transaction uncertainty.

## Should 6 — real Monocle work through RWP (pending)

Carry one existing workflow through a namespaced Monocle capability and the existing runtime. Validate capability gating, cancellation and reconnect before expanding to more work types. Add the corresponding in-game-host adapter; do not create a parallel executor or make RWP understand Monocle's job algorithms.

Category-based column reservations remain a separate nice-to-have, not part of these four batches.
