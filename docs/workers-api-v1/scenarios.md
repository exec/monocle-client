# Phase 1 implementation review scenarios

Give this file and the linked schemas to someone implementing a worker or host. Ask them to state the next job state, execution state, authority, and message/HTTP result at each step. Record disagreements in [the decision log](decisions.md).

## 1. Register and negotiate

1. Worker `W` connects to the proposed public worker endpoint and sends `session.hello` with API major v1 and `workers.wait.v1` plus `workers.travel.v1` capabilities.
2. Host authenticates `W` and replies `session.accepted` with session ID, negotiated limits, heartbeat, and grants.
3. Host offers a `workers.drop-items.v1` assignment to `W`.

Expected: step 2 establishes identity but grants no capability the worker did not advertise. Step 3 is rejected before any game control is acquired, with `unsupported_capability`; the job remains unstarted for `W`. A v2-only hello receives `unsupported_version` and no assignment.

## 2. Run and pause a portable job

1. Operator submits job `J` with `workers.wait.v1` for 200 ticks and a stable idempotency key. Host creates execution `E`, generation 1, and returns `J` in `queued` state.
2. Host sends `execution.assign`; worker accepts and starts. After 50 active ticks it reports 150 remaining.
3. Operator pauses `J`; worker reports `suspending`, saves its checkpoint, then reports `suspended`.
4. Operator resumes `J`; a duplicate assignment or lost response is retried using the original IDs.

Expected: only active ticks count; the resumed worker has 150 remaining. Duplicate request/message IDs with identical content return their recorded result; conflicting content is rejected. The job is `completed` only after required executions complete, not when the host merely delivers the command.

## 3. Cancel while offline

1. Worker starts a travel execution and disconnects. Host marks its connection offline, retaining its last observation time.
2. Operator cancels `J`; host records `cancelled` immediately and `cleanup=pending` for `E`.
3. A delayed `execution.progress` from before cancellation arrives, followed by a reconnect.

Expected: the old progress cannot restore `J` or advance its generation. During reconciliation the host sends final cancellation; the worker stops local controls, acknowledges cancellation, and reports any unresolved effects. Only then can cleanup become acknowledged. No new work is assigned under the cancelled execution.

## 4. Uncertain drop after restart

1. Worker durably records a drop intent, sends a game packet, then loses the host connection before learning whether the item left its inventory.
2. Worker and host restart; the worker reports execution `E`, generation 1, last sequences, and an unresolved drop.
3. Host reconciles the session while another operator attempts to resume the job.

Expected: the drop is **not sent again**. The execution becomes `inspection_required` or remains in an explicit observe-only recovery path. A worker observation or operator assertion may resolve the uncertainty, with its evidence source recorded. The API does not relabel an assertion as game-server confirmation. Resume cannot erase the recovery record.

## 5. Stale generation and wrong world

1. Host assigns `E` generation 2 for a job in `minecraft:the_nether` after generation 1 is settled.
2. A delayed generation-1 completion arrives. Worker then reports it is in `minecraft:overworld`.

Expected: the old completion is acknowledged as stale or rejected, but cannot change generation 2. The current assignment waits or rejects on scope mismatch; it does not run travel in the wrong dimension.

## External review record

- Reviewer/platform: **pending**
- Date and schema revision: **pending**
- Differences found: **pending**
- Resolution and decision-log links: **pending**
