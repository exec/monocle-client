# Stash flight stall and interrupted home (0.13.44)

## Canceled stash restore loop (0.13.45)

On the operator's return, the host API was not listening. Restarting the existing host showed Unbans already in the End at zungle1, not in the lobby. The old canceled run `c9df4635-98ba-4890-9e27-d2c08c642e9f` still owned the worker, repeatedly reporting an interrupted unconfirmed stash inventory move; replacement task `c53c048c-9f1b-4913-ac1d-bc7e18787f13` was Ready/Queued, waiting for its safe handoff. A start request alone did not move anything.

The runtime attempted native checkpoint restoration before cancellation cleanup. `BotStashResupply.restore` correctly rejected `withdrawPending`, but that exception prevented cleanup on every tick. 0.13.45 skips restoring an unloaded uncertain stash move only for explicit cancellation, retains the original receipt without replay/confirmation, and requires grounding and an empty cursor before releasing profiles and ownership. Pending stash receipts now participate in uncertain-action retention and block new work unless canceled. Existing drop/teleport reconciliation is unchanged. Offline checks cover these branches; live client update/restart validation remains pending.

The same build adds `.worker lobby-skip start|stop|status` through existing authenticated commands and background-safe owned input with a bounded walk. No forced lobby walk was sent to the live End stash. The operator was asked to fully restart the old client; the replacement transfer remained queued at the last check.

### Remote deployment and resumed transfer

At the operator's request, Unbans alone was updated remotely from 0.13.42 to 0.13.45. The old JAR was retained outside `mods` in `monocle-client/jar-backups`; staged and local SHA-256 hashes matched. Modrinth's instance launch link ran in the logged-in Windows session and connected to 6b6t. A one-shot Windows launch task was removed after successful dispatch. Once the API confirmed the known Overworld lobby coordinates, `.worker lobby-skip start` moved the worker through to the End stash and released its input on transfer.

A second older run, `8ab9bd72-8e03-4b05-a917-d1287a173df7` / task `f27b3b5d-e0e5-4070-9295-71d2a0b8807a`, restored an unconfirmed deposit as inspection-required. The host retained its earlier Failed result and refused cancellation of terminal tasks. The shared `QueuePolicy.cancel` now accepts explicit cancellation of Failed jobs, preserves each failed run's `failureDetail`, and reopens those runs only for cancellation acknowledgement. Complete and already-Cancelled jobs remain unchanged; failed work is never resumed. The updated standalone host delivered the cancellation through its existing authenticated path.

The replacement `c53c048c-9f1b-4913-ac1d-bc7e18787f13` then ran and confirmed **13 recovered Tools kits deposited**, with the parent checkpoint recording `recovered: 13`, `total: 13`. It proceeded to `/home zungle` for new withdrawals under the existing 32-second cooldown. This validates remote launch, explicit lobby retry, cancellation reconciliation and the first recovery deposit, not overnight completion of the whole job.

## Observed failures

### Hopper withdrawal false-negative and cancellation retry (0.13.46)

Run `4d9f2831-c787-440b-9cd3-c5eeb4d5f88b` / task `c53c048c-9f1b-4913-ac1d-bc7e18787f13` confirmed 13 recovered Tools kits plus three subsequent 36-kit loads (**121 deposited**), then failed at the hopper-fed bottom chest `[coordinates redacted]`. Its server full-menu receipt showed carried count `0 -> 1`, empty cursor, the exact box in destination slot 54, and an identical box still in source slot 0. Refill is the likely source-slot explanation; no intermediate refill event was recorded, so that particular cause is inferred rather than directly observed. The existing predicate rejected any unchanged source even when the carried pickup was authoritative.

The operator requested restart then diagnosis/update on another failure. Replacement `57decad6-69a1-4e4d-9087-2be623d59d45` was queued with deposit-carried recovery first, but the old client ignored cancellation of a terminal Failed run. Relaunch changed its local run to inspection-required; the host had already consumed its earlier Failed reply and stopped dispatching cleanup. Explicit cancellation retries now reopen that failed cleanup debt without losing `failureDetail`. The client accepts cancel (not resume/pause) after Failed, uses existing cursor/grounding cleanup, and retains uncertain inventory intent. Both host adapters now keep dispatching cancellation after late Failed reports; the shared status reducer accepts a Failed-to-cleanup transition only for a canceled task with requested outcome Cancelled. Pending cancellation is not deletable finished history.

For known hopper-fed sources, pickup confirmation now relies on the exact box entering a **server-confirmed previously empty carried slot**, matching-kit count increasing by exactly one and an empty cursor. The source may refill identically. Ordinary sources still require source change. Full menu reconciliation precedes clicks; packet menu/shape must match before any receipt is observed. Timeouts, no-replay behavior and restart inspection for uncertain clicks are unchanged. Offline regression checks exercise both accepted refill and rejected ghost/wrong-kit/count/cursor cases. Live retry results will be appended after deployment.

The replacement ran on 0.13.45 after the host cancellation retry update, confirmed its one carried kit recovered, then four full loads (**145 deposited in this retry** by the 22:33 UTC checkpoint), and began depositing another load. The complete 0.13.46 build and host checks pass; its JAR is staged remotely with matching SHA-256 `218b3cb8c9dec004adf2a00d9ead1c1f8bb6bbe92a01cc14ffaf060499293e78`. It has not been installed into the live client yet: the operator requested leaving the healthy retry running until its next failure. This is continued-progress validation of the old-client restart, not live validation of the new hopper predicate.

The subsequent ten-minute observation window ended without another failure. The final checkpoint showed 188 Tools kits deposited (including the recovered one), source exhaustion and progression to the next kit type, then 36 XP kits deposited: **224 confirmed deposits in the retry**. The job was left running. Unbans's last observed hunger was 6; the staged build was not installed and no indefinite unattended monitoring was established.

The solo remaining-kit transfer `1e8d5e86-c24e-4be4-9a8d-a4820ef486fc` stalled depositing 35 carried kits at newstorage. Its native deposit had no pending click and no confirmed deposit. It repeatedly reported no loaded, clear flight route to chest `[coordinates redacted]`, while Unbans hovered around `[coordinates redacted]`. The shared route used the printer's placement-clearance rules for interacting with existing containers. That overly restrictive clearance is a code-level contributor; the exact live collision geometry was not reproduced offline.

After a deposit-first restart (`188f410b-b4a5-4b2f-9d3c-34de50725bb2`), the worker confirmed 35 recovered kits and 175 subsequent deliveries in the active child workflow. A server restart then interrupted `/home zungle`. Its saved `StashResupply` retained `stashHomeSent: true` and an expired wall-clock `homeReadyAt`. It failed with `/home zungle did not reach the stash within 60 seconds after warmup; no further stash transfer attempted` while still at newstorage. Disconnect cleanup and checkpoint restore did not invalidate that travel state; the command was never retried.

## Changes and safety boundaries

- Reuse bounded swept-body routing with an interaction-specific goal instead of future-block placement clearance. Use the player's actual flight collision height.
- After 60 ticks without half a block of motion, reset the route and try a collision-checked escape. Allow three escapes, then safe landing and a retained-load failure. The worker checkpoint/log records target, position, body size, loaded/collision status and escape-node count.
- Clear stale home arrival flags and timer on disconnect/restore, but preserve the native pickup/deposit checkpoint. Re-check location first; do not resend if already near the correct stash in its dimension.
- Wait for the current chunk and server response. Retry an unobserved arrival after its warmup plus 60 seconds, at most three consecutive unsuccessful home commands; persist attempts through reconnects and reset the budget on verified arrival. Preserve server-specific cooldowns from predicted/observed teleport completion.
- Do not weaken pending-click verification, replay uncertain withdrawals/deposits, or infer kit delivery from a timeout. Host pause/cancel/ownership reconciliation still takes precedence over client recovery.

The second restart is `a4d1d630-6935-427a-9097-2851989caa5c`, with deposit-first recovery and 32-second source/destination home cooldowns. It initially waited for profile application because Unbans was gliding; a host-issued `.toggle elytra-fly off` allowed landing and execution. It then reported a running deposit at newstorage. The new recovery code is not live until the client is updated.

## Checks

### 0.13.47 remote deployment and pickup retry

The operator returned the previously carried Wind Trims kit to zungle1 before retrying. Unbans was updated to 0.13.47 with matching local/remote SHA-256 `720c9166174eb59d9a801b5f235fb7cb779a2c0ef5d9b350027a3fe3895c7a78`; the previous 0.13.45 JAR remains in Downloads as a recoverable backup. Only the Unbans instance was restarted; the other Minecraft process was left running.

Replaying the captured Modrinth launch command failed because its temporary Theseus javaagent had been deleted, and its wrapper depended on the old launcher RPC session. The successful relaunch used the original game arguments and classpath with Fabric's KnotClient entry point, omitting only that temporary launcher agent/wrapper. Both temporary Windows launch task definitions were removed after startup; no launch credentials were recorded here.

The host confirmed Unbans connected in the End on 0.13.47. Explicit cancellation of the old failed task `e4476ae4-8766-4a42-9338-48881a8038f9` and resumption of replacement `74d14058-67d9-45d8-a9d6-d0d16032f95b` released the old pickup blocker. The replacement subsequently collected and **confirmed depositing 34 Wind Trims kits** at newstorage; its child checkpoint recorded `total: 34`, `phase: taking`, then waited for the normal home cooldown to return to zungle1. This demonstrates successful updated-client pickup beyond the earlier first-slot failure and a completed load, not completion of the remaining transfer or every recovery scenario.

### Live WebUI assets lost during distribution rebuild

At 2026-10-08 02:34 UTC, `/ui` returned its normal empty redirect, but `/ui/` returned an empty 404 instead of HTML. The operator's browser downloaded an empty `ui` file. The live host's classpath pointed at `build/install/monocle-host/lib/*0.13.45.jar`; a subsequent `installDist` had removed those JAR paths while installing 0.13.46. The process and worker connection remained alive, but lazy classpath resource lookup could not find the web assets.

Restored the retained 0.13.45 host and coordinator JARs from their build outputs to the exact missing classpath paths, without replacing the running process or cancelling the transfer. `/ui/` then returned 200 `text/html` with 11,565 bytes, and `/ui/app.js` returned 200 JavaScript with 92,568 bytes. The host and Unbans remained connected and the transfer reported Running. Future deployments must use a separate versioned extracted runtime, leaving that version's files intact until its process stops; the Run instructions now call this out. The newer Kits UI still requires a planned host upgrade.

`./gradlew botsCheck printerFlightCheck` covers the new routing and retry rules alongside existing inventory uncertainty guards. Repeat a server restart during warmup, at the source, and at the destination on the updated client; verify correct stash arrival precedes navigation/transfer and confirmed counts never replay. An interrupted in-flight inventory click may still require inspection rather than an unsafe retry.
