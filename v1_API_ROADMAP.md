# Redstone Worker Protocol (RWP) core roadmap

## Goal and current state

Turn Monocle's existing host, crew, job, and per-worker execution model into an implementation-neutral protocol that other Minecraft clients and bot platforms can use without copying Monocle's Lua runtime or highway internals. RWP/1 standardizes capability negotiation, assignment, observable state changes, acknowledgements, cancellation, and recovery—not a mandatory job catalog. Monocle is the first implementation, not the required host. The final contract needs review by another implementer before it is frozen.

The core is **coordination, not a labor market**. A trusted operator or host submitting and assigning a job is enough for v1. Public offers, claims, prices, reputation, and escrow are optional later layers, not prerequisites for making independent workers interoperate. The public worker binding now uses `rwp/1-draft` and temporarily accepts `workers.monocle.dev/v1` for existing workers; neither identifier implies a frozen RWP/1 contract. Monocle's separate operator API and persistent ID namespace retain their own names.

Today the standalone host has a substantial draft resource API alongside `GET /v1/status`, RPC-style `POST /v1/control`, and the private `WS /v1/workers` protocol (plus raw TCP). The WebUI uses same-origin `/ui/api/*` and `/ui/api/v1/*` equivalents. The in-game host shares coordinator policy but does not expose the standalone administrative HTTP server. Crew keys grant trusted execution rather than scoped sharing. See [the current API notes](docs/workers-api.md) and [transport guide](docs/bot-web-transport.md).

## Implementation status (2026-09-30)

| Phase | Shipped | Still required for v1 |
| --- | --- | --- |
| 1 — contract | Monocle-derived vocabulary, schemas, examples, initial OpenAPI, scenarios, decision log and checker are drafted. | Separate the neutral RWP core from Monocle-specific bindings and extensions, settle names/versioning with an external implementer, and then freeze schemas. |
| 2 — operator HTTP | Authenticated resource routes for host/health/capabilities, workers, crews, jobs, drafts, workflows, stashes, configuration controls, and bounded list pagination. The panel uses resource routes for common dispatch/control and inspections. | Portable profile selection, general worker resource/configuration reads, safe stash deletion, remaining legacy UI flows, and documented host parity. |
| 3 — reliability/events | Durable idempotency receipts, selected revision guards, structured resource errors, and a bounded resumable polling feed with native/public worker connection transitions. | Correlate receipts to delivery/worker/server outcomes, richer events, push delivery if needed, and failure/reconnect conformance. |
| 4 — public worker protocol | An **optional, loopback-only, standalone-host prototype** at `/v1/interop/workers` authenticates per-worker tokens, tracks observations, reconciles execution generations, and runs built-in Wait and Travel through the durable host queue. It also relays bounded namespaced extension actions to workers advertising the exact capability, without interpreting their gameplay semantics. Execution reports have bounded durable retry receipts; transport keepalives, an outbound queue cap, and a per-session inbound rate limit bound traffic. Socket tests and a separate dependency-free Node mock worker cover completion, cancellation, reconnect, and opaque extension delivery. The independent [`exec/rwp`](https://github.com/exec/rwp) Java Wait example has also completed a real job against the standalone host. Monocle now advertises and executes **Wait and Travel** with a durable local checkpoint, report retries, reconnect reconciliation, and offline cancellation. Travel reuses its safe native movement action and reports a fresh position on safe-footing arrival. Replaceable progress and optional worker names now appear in the operator roster/panel. | Run a game-backed Monocle Wait/Travel trial; add trusted remote `wss://` deployment and safe coexistence with native crew sessions; then broaden capability negotiation, pause/preemption, and add an in-game-host adapter. Drop Items is a proposed optional action profile, not a core protocol gate. **Only Wait and Travel have built-in public semantics; no public highway/stash adapter exists yet.** |
| 5 — identity/grants | Separate per-worker credentials exist only for the Phase 4 prototype. | Operator identities, scoped grants, rotation/revocation, sharing, and audit enforcement. |
| 6 — interoperability/release | Local schema and socket checks, a separate-process mock worker, and a dependency-free draft-host probe. | Independent platform review, game-backed third-party worker trials against both hosts, broader conformance fixtures/report, and v1 freeze. |

Monocle's first RWP implementation has three surfaces:

1. An operator HTTP API for workers, crews, jobs, workflows, stashes, configuration, and access grants.
2. A worker WebSocket API for capabilities, assignments, observations, progress, acknowledgements, and reconciliation.
3. An operator event stream for dashboards and integrations that need updates without polling the entire host snapshot.

These HTTP and WebSocket routes are **bindings/adapters**, not required transports for RWP itself. The core JSON messages and state rules must remain meaningful over a future low-bandwidth `/msg` binding or another host link. The current coordinator and private protocol remain usable during migration. New routes should initially adapt to the same coordinator operations and durable records, so client-based hosting and standalone hosting keep matching job behavior.

**Boundary decision:** RWP understands a job's identity, authority, lifecycle, and versioned action capability, but does not interpret every action payload or prescribe a client's algorithm. A host may carry a Monocle highway or stash job as a namespaced action only to a worker advertising that exact capability. The host still validates the common envelope, authorization, bounds, and lifecycle; the action's owner defines argument validation and completion semantics. Optional standard action profiles can make selected tasks reproducible across clients, but no worker must implement them merely to speak RWP.

**Next core milestone:** take the documented [minimal execution messages](docs/workers-api-v1/phase4-worker.md) to an external client implementer, test a game-backed worker against the host, and resolve differences before freezing RWP/1. The local dependency-free mock proves the wire path, not Minecraft behavior or independent review. Do not build listing, payments, or escrow into this milestone.

## Phase 1 — Agree on the contract

### 1.1 Vocabulary and state model

- Define `Host`, `Worker`, `Crew`, `Job`, `Execution`, `Action`, `Workflow`, `Capability`, `Observation`, `ResourceInventory`, `Stash`, `Event`, `Operation`, and `Grant` in plain language.
- Mark `Crew`, `Stash`, and Monocle workflow packaging as optional host capabilities rather than requirements for every RWP worker. A minimal worker needs only identity, capability negotiation, typed assignment, reports, and reconciliation.
- Distinguish a reusable job definition from one execution attempt; give each execution an ID and generation so stale reports cannot revive cancelled work.
- Publish allowed job and execution states, transitions, terminal states, and what survives host or worker restart.
- Specify authority for each field: host intent, worker observation, game-server confirmation, or operator assertion. An observation must never silently become confirmation.

### 1.2 Common formats

- Specify stable IDs, UTC timestamps, revisions, world scope (`server` and `dimension`), cursors, pagination, and structured errors.
- Define one versioned JSON envelope for worker messages with message ID, correlation ID, actor, type, timestamp, and payload. Define which fields are required and how unknown optional fields are handled.
- Make event IDs and execution generations durable across transports; session sequence numbers belong to ordered bindings such as WebSocket, not to the transport-neutral core.
- Define namespaced, versioned capability and action IDs, such as optional standard profile `workers.travel.v1` and extension `dev.monocle.highway.build.v1`; missing exact capabilities reject an assignment before execution. RWP has no closed list of job types.
- Define a small optional standard-action profile set—initial candidates are `Wait`, `Travel`, and `DropItems`—with arguments, observable success/failure, cancellation, and retry rules. Standardize outcomes and permitted effects, not pathfinding or mining algorithms. `SetProfile` remains a separate candidate until portable profile identity and revision semantics are settled. Highway, stash, and client-specific actions stay namespaced extensions.
- Describe the behavior of duplicate messages, conflicting IDs, timeouts, reordered reports, unsupported versions, and oversized payloads.

Illustrative action, subject to Phase 1 review:

```json
{
  "type": "workers.travel.v1",
  "arguments": {"scope": {"server": "play.example.org", "dimension": "minecraft:the_nether"}, "x": 100, "y": 116, "z": -5000}
}
```

### 1.3 Reviewable artifacts and exit gate

- The [Phase 1 contract draft](docs/workers-api-v1/README.md) now contains the vocabulary, state model, schemas, initial OpenAPI, examples, decision log, and review scenarios; it remains a proposal until partner review.
- Add JSON Schemas, example valid and invalid payloads, and an initial OpenAPI document for the HTTP surface.
- Write a compact decision log for protocol choices made with other platform authors, including rejected alternatives and compatibility consequences.
- Have at least one external implementer review a mock worker registration, a job lifecycle, cancellation, and reconnect using only these documents.
- **Exit gate:** the terms and examples let two implementations make the same decision for every documented lifecycle transition; no production endpoint needs to change yet.

## Phase 2 — Resource-oriented operator HTTP API

Build a thin adapter over the existing host controls. Keep `GET /v1/status`, `POST /v1/control`, `/control`, and `/ui/api/*` working while the new resources gain coverage. Route names below are candidates for review, not a frozen standard.

An initial standalone-host adapter is implemented for core reads, crew/job/workflow controls, typed Wait/Travel/Drop Items submission, and revision-guarded stash definition/edit/scan dispatch. The bundled WebUI now uses same-origin resource routes for job dispatch, common job commands, and configuration while retaining its legacy rich snapshot; [exact current routes and limits](docs/workers-api-v1/phase2-operator.md) are documented separately. The remaining deliverables below are still open.

### 2.1 Read surfaces

- `GET /v1/host`, `/v1/health`, and `/v1/capabilities`: host identity, health, protocol versions, and advertised abilities.
- `GET /v1/workers` and `/v1/workers/{id}`: roster, connection and reconciliation state, current execution, and observation age.
- `GET /v1/crews` and `/v1/crews/{id}`: crew metadata, membership, work, and resource summaries.
- `GET /v1/jobs` and `/v1/jobs/{id}`: active and historical jobs with state, per-worker runs, cleanup debt, and progress; a job detail may link to its captured package rather than embedding it in every list response.
- `GET /v1/workflows` and `/v1/workflows/{id}`: workflow metadata and immutable captured package revisions.
- `GET /v1/stashes` and `/v1/stashes/{id}`: world-scoped definitions and catalog summaries.
- Add bounded filters, cursor pagination, and explicit `observedAt`/`age` fields so clients can tell fresh reports from retained snapshots.

### 2.2 Mutations

- Create, rename, and delete crews; add or remove eligible workers through membership subresources.
- Create jobs from a portable action or captured workflow; expose pause, resume, cancel, detach/rejoin, priority, and release as explicit commands with operation receipts.
- Create, validate, update, duplicate, and delete workflows; preserve reviewed/captured revisions when a job is dispatched.
- Define and scan stashes and expose resource counts with provenance and freshness.
- Read and patch worker or job configuration only for advertised settings; keep Monocle's serialized module settings behind a namespaced extension.
- Keep safety gates from existing controls: live jobs cannot be deleted as history, active crew work cannot be orphaned, and uncertain resource transfers require explicit resolution.

### 2.3 Host parity and exit gate

- Give the in-game host and standalone host the same resource semantics through shared coordinator methods, even if only the standalone host serves HTTP initially.
- Migrate one control-panel flow at a time to the new routes: inspect workers, start a job, then pause/resume/cancel. Compare results against the legacy controls.
- **Exit gate:** an independent operator client can list workers, create a crew, submit and control a job, and inspect its outcome without calling `/v1/control`.

### Candidate route map

| Resource | Candidate routes |
| --- | --- |
| Host | `GET /v1/host`, `GET /v1/capabilities`, `GET /v1/health` |
| Workers | `GET /v1/workers`, `GET /v1/workers/{workerId}`, `DELETE /v1/workers/{workerId}`, `GET /v1/workers/{workerId}/observations`, `GET /v1/workers/{workerId}/resources`, `GET/PATCH /v1/workers/{workerId}/configuration` |
| Crews | `GET/POST /v1/crews`, `GET/PATCH/DELETE /v1/crews/{crewId}`, `PUT/DELETE /v1/crews/{crewId}/workers/{workerId}` |
| Jobs | `GET/POST /v1/jobs`, `GET/PATCH/DELETE /v1/jobs/{jobId}`, `POST /v1/jobs/{jobId}/{pause,resume,cancel,release}`, `POST /v1/jobs/{jobId}/workers/{workerId}/{detach,rejoin}` |
| Workflows | `GET/POST /v1/workflows`, `GET/PUT/DELETE /v1/workflows/{workflowId}`, `POST /v1/workflows/{workflowId}/{validate,instantiate}` |
| Drafts | `GET/POST /v1/drafts`, `GET/DELETE /v1/drafts/{draftId}`, `POST /v1/drafts/{draftId}/dispatch` |
| Stashes | `GET/POST /v1/stashes`, `GET/PATCH/DELETE /v1/stashes/{stashId}`, `POST /v1/stashes/{stashId}/scan`, `GET /v1/stashes/{stashId}/resources` |
| Operations and events | `GET /v1/operations/{operationId}`, `GET /v1/events` (resumable stream) |

Command routes make lifecycle transitions explicit and auditable. A future `desiredState` patch can be considered if implementers find it clearer; Phase 1 should settle the public form before it becomes stable.

## Phase 3 — Reliable controls and operator events

An incremental standalone-host adapter now has durable idempotency receipts, revision-guarded crew/workflow edits, structured resource-route problems, and a bounded resumable polling feed. See [the current Phase 3 behavior and limits](docs/workers-api-v1/phase3-operator.md). Worker/game-server acknowledgement correlation, push delivery, grants, and the exit gate remain open.

### 3.1 Command semantics

- Require an idempotency key or caller-generated command ID for mutating requests; the same command returns the same result, while reuse with different content fails.
- Use revisions (`ETag`/`If-Match` or an equivalent explicit field) for edits where stale writes would overwrite another operator.
- Return an operation ID and `202 Accepted` when a command is asynchronous. Report separate stages for host accepted, worker delivered, worker acknowledged, game-server confirmed, completed, and outcome uncertain where applicable.
- Define retryable versus final failures using stable machine-readable codes and RFC 9457-style problem bodies. Include a correlation ID in responses, events, and logs.
- Preserve cancellation and cleanup records for offline workers; a successful host-side cancellation must not imply that a worker has already stopped or recovered supplies.

Example error shape:

```json
{
  "type": "https://workers.monocle.dev/problems/worker-offline",
  "title": "Worker is offline",
  "status": 409,
  "code": "worker_offline",
  "detail": "The worker must reconnect before changing crews.",
  "instance": "/v1/operations/2c553302-6e88-4ce1-af24-40eb6170131f",
  "retryable": true,
  "correlationId": "d8ab7143-6414-4cf2-a42d-8c072983f4a8"
}
```

### 3.2 Operator event stream

- Add a read-only stream of job transitions, worker connection changes, progress, resource alerts, and command receipts, scoped to the caller's grants.
- Use resumable event IDs and a bounded replay window; after a gap, tell the consumer to fetch a fresh resource snapshot.
- Keep status reads authoritative. The stream is a change feed, not a second store of job truth.

### 3.3 Exit gate

- Test lost responses, duplicate submissions, stale revisions, offline cancellation, reconnect, slow event consumers, and stream gaps.
- **Exit gate:** an operator can issue a command once, recover its result after a disconnect, and distinguish an acknowledged effect from an uncertain one.

## Phase 4 — Public worker WebSocket binding

Add a new versioned worker endpoint alongside the private `WS /v1/workers` transport. Do not silently reinterpret the existing endpoint's `monocle-crew-6` frames. The initial standalone-host Wait adapter is described in [its operator notes](docs/workers-api-v1/phase4-worker.md); it does not yet satisfy the Phase 4 exit gate.

### 4.1 Session and negotiation

- Define `session.hello` and `session.accepted` with worker identity, supported API versions, capability versions, limits, and heartbeat intervals.
- Authenticate the worker independently of crew membership; associate it with authorized crews after authentication.
- Reject unsupported major versions and required capabilities with explicit protocol errors, without beginning the job.
- Bound frame size, message size, queues, and heartbeat/idle timeouts; define backpressure behavior.

### 4.2 Execution messages

- Define assignment, acceptance/rejection, start, progress, event, result, failure, cancel, cancellation acknowledgement, and generic acknowledgement messages.
- Every command identifies job, execution ID, generation, and command ID. Every report identifies the execution it describes and its monotonic worker sequence.
- Define which actions are safe to retry, which require an idempotency token, and which become uncertain after a disconnect (for example, dropping items or issuing a teleport command).
- Send only actions whose exact versioned capability the worker advertises. Monocle Lua packages and native highway/stash control remain namespaced capabilities; RWP carries their assignments and results without interpreting their internal steps.

Illustrative worker message:

```json
{
  "apiVersion": "rwp/1-draft",
  "type": "execution.progress",
  "messageId": "d83d76db-3ab7-405e-9258-f893f16d8383",
  "correlationId": "d8ab7143-6414-4cf2-a42d-8c072983f4a8",
  "workerId": "651be0b7-38fb-4756-a126-a7468d71aefe",
  "sequence": 1842,
  "sentAt": "2026-09-27T12:00:00Z",
  "payload": {"executionId": "c9547876-e3d8-4713-97d3-35a751718ccd", "generation": 3}
}
```

### 4.3 Reconciliation and host adapters

- On reconnect, have the worker report its last accepted host sequence, last emitted worker sequence, active execution/generation, cancellation knowledge, and unresolved external effects.
- Have the host answer with an explicit reconciliation result: continue, cancel, inspect, or wait. Never replay a non-idempotent effect solely because its acknowledgement was lost.
- Implement adapters for both standalone and in-game hosts against the same coordinator policy. Let Monocle workers use either protocol during the migration window.
- **Exit gate:** a third-party test worker can register, run a portable job, cancel while offline, reconnect, and converge on the host's final state without Lua or Monocle classes.

### 4.4 Secure remote access and Monocle coexistence

- Keep the RWP backend bound to loopback. Support remote workers through a documented TLS-terminating proxy and `wss://` with normal certificate-chain and hostname validation; choose either an operator-owned domain/certificate or an explicitly trusted LAN CA. Never send bearer tokens over remote `ws://`, disable TLS verification, or publish the admin API as part of worker ingress. WebSocket is an HTTP upgrade; replacing it with plain HTTP would not add encryption.
- Let a Monocle game client connect to RWP alongside its native crew connection, with separate endpoint/token settings and reconnect state. Define how the host represents two sessions for one Minecraft account without duplicating the worker or granting conflicting movement authority: native highway/stash work must not be interrupted by an RWP Wait/Travel assignment.
- Verify from a second machine that valid WSS connects, untrusted/wrong-host certificates and remote plaintext URLs fail, credentials stay out of URLs/logs, and RWP disconnect/reconnect leaves an active native crew job intact.

## Phase 5 — Identity, permissions, and sharing

### 5.1 Credentials and grants

- Separate worker identity, operator identity, host identity, and crew membership. Replace shared crew keys for public connections with individually revocable credentials.
- Define scopes for reading workers, assigning work, controlling jobs, managing crews/workflows/stashes, sending chat, and using particular workers or capabilities.
- Support expiration, revocation, rotation, and credential handoff without placing secrets in URLs, workflow exports, status snapshots, or logs.
- Keep trusted LAN crew-key connections available as a legacy mode until migration is complete.

### 5.2 Authorization and audit

- Check grants at read, assignment, command, event-stream subscription, and worker reconnection boundaries; do not rely on UI hiding.
- Record who assigned work, changed membership, controlled a job, or changed a grant, along with result, time, and correlation ID.
- Provide a read-only operator role and a scoped invitation flow for lending a worker to another owner or crew.
- Keep the first standard independent of OAuth, mTLS, or a particular identity provider; deployments may bind its grants to those systems later.
- **Exit gate:** revoke a shared worker while it is offline, reconnect it, and show that it cannot regain unauthorized work or read another crew's data.

## Phase 6 — Interoperability and release

### 6.1 Reference material

- Publish the OpenAPI document, JSON Schemas, state diagrams, optional standard-action profiles, extension rules, error codes, size limits, examples, and version negotiation/deprecation policy. Do not publish a closed RWP job catalog.
- Provide a small simulated host and worker plus conformance fixtures for valid and malformed registration, assignment, duplicate delivery, cancellation, reconnect, and uncertain effects.
- Provide a minimal Java and TypeScript example or SDK only where it removes repeated protocol work for adopters.

### 6.2 Mixed implementation trials

- Run a portable job with a third-party worker against the standalone host, then against an in-game host.
- Run a mixed Monocle/third-party crew through priority changes, worker departure, cancellation, host restart, and reconnection.
- Test one capability extension end to end without forcing the other worker to understand it.
- Review API ergonomics with partner platforms and revise draft schemas before declaring v1 stable.

### 6.3 Release gate and migration

- Publish a conformance report and identify which optional capabilities each implementation supports.
- Freeze v1 schemas and compatibility guarantees only after the mixed trials pass.
- Keep the private protocol as an explicit `legacy-v0` adapter for at least one substantial release cycle; retire it only when migration and recovery behavior are proven in real jobs.

## What v1 should preserve

RWP core must preserve durable cancellation for offline workers, execution generations, idempotent submissions, capability checks before assignment, the difference between host acceptance, worker acknowledgement, and game-server confirmation, and reconnect reconciliation. The Monocle adapter must additionally preserve its resource-recovery debt, per-worker priorities, safe detach/rejoin, and standalone host that needs no Minecraft account or rendering context; these are not mandatory features for every RWP implementation.

## Vendor extensions for now

Monocle highway building, stash hunting/scanning/resupply, follow/bodyguard jobs, its Lua dialect and native highway lane/window protocol, shulker and ender-chest recovery internals, server-specific `/home` and `/tpa` syntax, serialized Meteor/Monocle module settings, launcher account management, and presentation features such as Banter Mode should remain namespaced extensions. Other platforms can advertise support for an extension without making it a requirement for every v1 worker. Chat and IRC are possible transport bindings, not gameplay capabilities; neither should change the core job state machine.

## Long-term optional layers — not on the RWP/1 critical path

- **RWP-Market/1:** public listings/discovery, worker claims, issuer selection, prices, reviews, and federated reputation. An accepted claim would create an ordinary RWP assignment; a claim alone grants no execution authority. Clan or group affiliation and payment terms remain optional metadata rather than required core job fields.
- **RWP-Escrow/1:** provider-specific custody terms, observed deposits, fees, releases, cancellations, and disputes. Worker-reported completion must not itself trigger payment; escrow requires its own agreed verification and trust model. Do not implement this until the core has independent interoperability trials and there is an actual provider willing to operate it.
