# Phase 4 public-worker Wait and Travel prototype

The standalone host can optionally open `ws://127.0.0.1:<interopPort>/v1/interop/workers`. This is a **loopback-only, draft public-worker endpoint**, separate from the existing `/v1/workers` crew protocol. Do not expose it to the Internet.

This endpoint implements two optional standard-action candidates, Wait and Travel, and can relay a bounded namespaced extension action to a public worker advertising that exact capability. This is **not** RWP's job catalog. The host handles the common lifecycle but does not interpret an extension's arguments or independently verify its gameplay result. Monocle's native highway and stash jobs are still separate; no public highway or stash adapter exists yet.

Monocle now supports RWP **Wait and Travel**. In **Right Shift → Workers → Worker**, set `ip` to `ws://127.0.0.1:<interopPort>/v1/interop/workers` (or `wss://` through a trusted TLS proxy) and `crew-key` to that account's `interopWorkerTokens` secret, then enable Workers while in the game world. The configured worker UUID must be that Minecraft account's profile UUID. Monocle reports its live world/position and advertises `workers.wait.v1` and `workers.travel.v1`. Wait counts only game ticks while the host authorizes it. Travel reuses Monocle's native safe walking/ElytraFly action and sends a fresh position observation before reporting arrival on safe footing. Remaining Wait ticks, Travel targets, and pending reports are saved to `rwp-execution.json`; reconnect reconciles the checkpoint and retries unacknowledged reports. Switching URLs closes the old connection first. Plain `ws://` is accepted only on loopback. Use the existing private worker endpoint for highway, stash, or other gameplay jobs; Monocle does not yet execute public extension actions.

To enable a local integration test, set `interopPort` to an unused local port and add an entry to `interopWorkerTokens` in `host-config.json`:

```json
"interopPort": 6972,
"interopWorkerTokens": {
  "651be0b7-38fb-4756-a126-a7468d71aefe": "REPLACE_WITH_DISTINCT_RANDOM_SECRET_AT_LEAST_24_CHARACTERS"
}
```

The worker connects with `Authorization: Bearer <its secret>`, no browser `Origin`, then sends one text `session.hello` frame matching the [draft worker schema](schemas/worker-message.schema.json). The `payload.workerId` must match the credential's configured UUID. New workers use `apiVersion: "rwp/1-draft"`, include it in `supportedVersions`, and report `implementation: {"id":"dev.monocle.client","version":"<client version>"}`. The host temporarily accepts the legacy `workers.monocle.dev/v1` version without implementation identity. Its `session.accepted` frame reports the selected version and host implementation; the session uses that version throughout. The ID/version are self-reported software information, not credentials or capability grants, and appear in the operator roster. The host advertises `workers.wait.v1` and `workers.travel.v1` with a 16,000-byte frame limit. Bad credentials are rejected during upgrade; bad versions/hello frames receive `protocol.error` and close. A second simultaneous session for the same UUID is rejected. There is a five-second hello deadline.

The socket library uses transport ping/pong with a ten-second connection-loss setting; no application heartbeat message is required yet. A worker may send at most 64 text frames per second per session; exceeding that limit closes its socket with code `1008`. If a worker stops reading and 32 outbound frames are queued, the host closes that connection instead of buffering more work. The worker must reconnect and reconcile; the host does not treat an unsent assignment or lost acknowledgement as completed work.

After acceptance, the worker sends `worker.observation` with `observedAt` and world `scope` (`server`, `dimension`), optionally `position` (`x`, `y`, `z`), then `state.reconcile`. The host publishes it in the operator roster using host receipt time for freshness. Unknown execution claims receive `inspect`; a known Wait or Travel execution receives `continue`, `cancel`, or `wait` according to host state, capability, world, and Travel position freshness. Nonempty `unresolvedEffects` cannot be reconciled yet and are rejected. A disconnect marks the retained roster entry offline. A reconnect repeats hello and reconcile; a Wait or Travel execution missing from the worker checkpoint receives a new generation, but is not reassigned until the required world observation is fresh. An extension execution missing after it might have started instead becomes `Inspection required`; it is **not replayed**, because its effects may be non-idempotent. Host restart also leaves unfinished extensions for inspection. Travel replay means re-evaluating the current position, never assuming movement packets succeeded.

An operator assigns the registered worker to a crew through `PUT /v1/crews/{crewId}/workers/{workerId}`. Once reconciled and observed in the job's world, it can receive a built-in Wait job submitted through `POST /v1/jobs` with `action.type=workers.wait.v1`. Travel additionally requires `workers.travel.v1` and a position observed by the host within five seconds. Its arguments contain the same world scope, `x`, `y`, `z`, and optional `radius` (default 2). Travel must reach the destination on safe footing without mining or placing blocks. The host stores either job in its existing durable queue, sends `execution.assign`, and requires `execution.accepted`, `execution.started`, and `execution.completed` (or `execution.failed`). A Travel completion also requires a fresh destination-position observation; this is still a **worker claim**, not game-server proof. Host cancellation sends `execution.cancel` until `execution.cancelled` acknowledges cleanup. The same worker cannot hold a second unfinished public job; active public actions cannot be paused or preempted yet. The host adapter does **not** control a Minecraft client or execute Lua; each worker implements its advertised action. Mixed native/public jobs, live configuration, and public highway work remain unsupported.

`POST /v1/jobs` also accepts a single extension `action` with `type` such as `dev.example.inspect.v1` and an `arguments` object, instead of a Monocle `workflowId` or package. The action envelope is limited to 8,192 UTF-8 bytes; the `workers.*` namespace remains reserved for explicitly implemented standard profiles. Every selected worker must be connected, reconciled, in the job's world/crew, and advertise that exact type. Only public workers can receive this path; unknown Monocle-native workflows are not converted automatically. The host validates the common envelope, authority, scope, size, and execution state, while the worker validates extension-specific arguments and effects. A generic completion is displayed as **worker-reported, not independently game-verified**. `session.accepted.extensionRouting` and `GET /v1/capabilities.extensionRouting` advertise this draft adapter when enabled.

## Minimal core execution messages

These are JSON message **semantics**, not a requirement to use WebSocket. Monocle's current frames add `apiVersion`, sender identity, message/correlation IDs, time, and a session-local sequence; a future chat or HTTP binding may encode those differently while retaining job/execution IDs and generation.

| Message | Direction | Core effect |
| --- | --- | --- |
| `execution.assign` | Host → worker | Offer one typed action under `jobId`, `executionId`, `generation`, and `commandId`; no game control is implied yet. |
| `execution.accepted` / `execution.rejected` | Worker → host | Acknowledge or reject that exact assignment command. Acceptance is not a start or completion. |
| `execution.started` | Worker → host | Report that execution began; it does not prove a Minecraft-side result. |
| `execution.progress` | Worker → host | Replaceable, at-most-once-per-second detail for an active execution; no receipt or completion proof. |
| `execution.completed` / `execution.failed` | Worker → host | Report terminal worker outcome for the same execution generation; Travel completion needs a fresh destination observation. |
| `execution.cancel` | Host → worker | Withdraw authority for the execution; the host's job cancellation is already final. |
| `execution.cancelled` | Worker → host | Acknowledge cleanup or identify inspection-required cleanup. A late completion cannot undo cancellation. |
| `message.ack` | Host → worker | Confirm a durable execution report receipt as `accepted` or `duplicate`; this does not confirm a game-server effect. |
| `worker.observation` | Worker → host | Supply timestamped world/position evidence and an optional validated Minecraft name; freshness is based on host receipt, and the worker may be wrong. |
| `state.reconcile` / `state.reconciled` | Worker ↔ host | Compare durable execution IDs/generations after reconnect and decide `continue`, `wait`, `cancel`, or `inspect`. |

`session.hello` and `session.accepted` are authentication/negotiation for this WebSocket binding. Public listing, claiming, payment, and escrow are outside this core lifecycle.

## Report retries

The host replies to each execution report with `message.ack`, correlated to that report and containing its `messageId` and `result` (`accepted` or `duplicate`). It journals the resulting execution state and a receipt for the message ID together. If an acknowledgement is lost, resend the **same report type and payload with the same message ID**, but use the next sequence number in the new or current session. The host recognizes matching retries across reconnects and host restarts; reuse of an ID with different content produces `protocol.error` / `message_id_conflict` and closes the connection. JSON object key order does not affect matching.

Receipts are bounded to the latest 128 execution reports per worker (and 256 workers). After that window, reconcile the execution rather than assuming an old report can still be acknowledged as a duplicate. `worker.observation` is a replaceable live snapshot, not receipt-cached; `state.reconcile` is always re-evaluated so an old `continue` cannot be replayed after cancellation. This is report retry safety, not proof of a Minecraft-side effect.

## Standalone compatibility check

With Node 26+, another host author can run the dependency-free [RWP host probe](../../host-service/src/test/rwp-conformance.mjs) against an **idle, dedicated test worker credential**:

```sh
RWP_URL='ws://127.0.0.1:6972/v1/interop/workers' \
RWP_TOKEN='TEST_WORKER_SECRET' RWP_WORKER_ID='TEST_WORKER_UUID' \
RWP_SERVER='play.example.org' RWP_DIMENSION='minecraft:the_nether' \
node host-service/src/test/rwp-conformance.mjs
```

It checks hello, observation, unknown-execution reconciliation, and rejection of a deliberately skipped session sequence; it then disconnects. Use `wss://` outside loopback. This is a narrow draft-host probe, **not** certification of job execution or Minecraft behavior.

`host-service/src/test/rwp-mock-worker.mjs` is a test-only Node 26 worker with no Monocle imports. `:host-service:hostServiceCheck` launches it as a separate process and exercises Wait, simulated Travel, cancellation, and a fresh-process reconnect with a new execution generation. It intentionally snaps its mock position to the Travel target; it does **not** move a Minecraft player or prove game-server arrival. The check is skipped when Node 26 is unavailable. A real third-party client review and game-backed worker trial remain outstanding.
