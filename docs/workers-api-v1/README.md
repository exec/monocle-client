# Workers API v1: Phase 1 contract draft

Status: **proposal for cross-platform review, not frozen**. This package is the Phase 1 discussion artifact from [the roadmap](../../v1_API_ROADMAP.md). The current Monocle endpoints and private native protocol are described in [the existing API notes](../workers-api.md); the [transport notes](../bot-web-transport.md) cover `monocle-crew-7` (0.13.15+). A [Phase 2 operator adapter](phase2-operator.md) implements a subset of the HTTP route names, but not yet the proposed schemas. A separate [Phase 4 worker endpoint](phase4-worker.md) supports authenticated registration, observation, reconciliation, built-in Wait and Travel, and bounded routing of worker-advertised extension actions.

RWP defines **how a host and worker agree on and track work, not which jobs every client must offer**. A job carries a namespaced, versioned action and its arguments. The worker advertises exact action capabilities; the host must refuse unsupported assignments. RWP governs identity, authority, assignment, reports, cancellation, retries, and reconciliation, while the action's own specification governs its arguments, permitted effects, and success criteria. Monocle highway and stash jobs remain Monocle-defined actions. A generic RWP host can transport an unfamiliar action for a capable worker without claiming to understand or independently verify its gameplay result.

## Vocabulary

| Term | Meaning |
| --- | --- |
| Host | Authority that accepts jobs, schedules work, persists decisions, and reconciles workers. It may be a standalone service or a game client. |
| Worker | Authenticated game client that advertises capabilities, observes the game, and executes assignments. A worker identity is independent of crew membership. |
| Crew | Host-managed set of workers eligible for a shared assignment. Membership and access grants are separate. |
| Job | Durable operator request: scope, target workers, priority, and a captured workflow/action definition. Its ID is stable across worker disconnects. |
| Execution | One worker's attempt to carry out a job. It has its own ID and generation. A new generation requires a host decision, never an inference from reconnect. |
| Action | Namespaced, versioned unit of work. Its owner defines argument validation, permitted effects, and observable completion; RWP defines the shared execution lifecycle. |
| Workflow | Optional versioned composition of actions; a job captures the revision it will use. RWP does not require a particular workflow language or Lua. |
| Capability | Namespaced/versioned action or protocol feature a worker explicitly supports, such as `workers.travel.v1`. |
| Observation | Timestamped worker report about position, inventory, game state, or an attempted action. It may be stale or wrong; it is not automatically server confirmation. |
| Resource inventory | Counts of carried or stored items with provenance and observation time. An unknown container is not an empty one. |
| Stash | Named, world-scoped storage definition and its observed contents, including its access method such as a server home name where relevant. |
| Event | Durable or bounded report of a state change, observation, receipt, or warning, with a correlation ID. |
| Operation | Operator command tracked from host acceptance through delivery and outcome. |
| Grant | Revocable authorization to observe or control specific resources or capabilities. The exact credential mechanism belongs to Phase 5. |

## State and authority

Job states: `queued`, `running`, `paused`, `inspection_required`, `completed`, `failed`, `cancelled`. Execution states: `offered`, `accepted`, `running`, `suspending`, `suspended`, `inspection_required`, `completed`, `failed`, `cancelled`. Terminal states are `completed`, `failed`, and `cancelled`; terminal records do not resume. Connectivity (`online`, `offline`, `reconciling`) and cleanup (`none`, `pending`, `acknowledged`, `inspection_required`) are **separate fields**, not job states.

| Decision | Allowed transitions and rule |
| --- | --- |
| Submit | New job is `queued`; host creates a distinct execution ID and generation for each selected worker. The same idempotent request returns the same job. |
| Assign | `offered → accepted → running`; rejection leaves the job unstarted for that worker and gives a reason. Work starts only after capability and scope checks. |
| Pause | Nonterminal job `→ paused`; a running execution `→ suspending → suspended` after a safe checkpoint. A disconnected worker may still be running until it learns the decision. |
| Resume | `paused → queued/running` and `suspended → running` only after checkpoint reconciliation. `inspection_required` needs an explicit operator decision; it is not a timer-based retry. |
| Cancel | Any nonterminal job `→ cancelled` immediately and permanently at the host. Running workers receive cancellation when connected, then acknowledge cleanup. No later report may restore work authority. |
| Finish | A job reaches `completed` when every required execution completes, or `failed` when its job policy determines a failure; individual execution results remain visible. |
| Disconnect | Keep the last execution state but mark observation stale and connectivity offline. Do not infer completion, cancellation delivery, or a safe replay. |
| Restart | Host and worker reload durable decisions/checkpoints. Uncertain external effects or missing executed checkpoints become `inspection_required`; unsent work may be resent with its original identity. |

The host is authoritative for job intent, membership, priority, generation, and cancellation. A worker is authoritative for its own checkpoint and what it attempted, but reports are observations. Game-server confirmation is a separate claim with evidence type and timestamp. An operator may resolve uncertainty after inspection; that assertion must be marked as such and must not be relabelled as game-server confirmation. Host cancellation is final even while cleanup is pending.

## Common wire rules

- The public RWP worker binding uses draft `rwp/1-draft`; Monocle temporarily accepts `workers.monocle.dev/v1` for existing workers. The session version is fixed at hello. A peer with no compatible version is rejected before assignment. Minor/optional additions may be ignored only if they do not alter required behavior.
- IDs are UUIDs; timestamps are UTC RFC 3339 strings; world scope contains a server identifier and namespaced dimension. Revisions are nonnegative integers. Cursor values are opaque and must not be parsed by clients.
- Worker frames use the envelope in [`worker-message.schema.json`](schemas/worker-message.schema.json): `apiVersion`, `type`, `messageId`, `correlationId`, `sequence`, `sentAt`, `payload`, and the sender identity once authenticated. Sequences increase per authenticated session; replay across sessions uses durable message IDs and execution generations.
- A repeated message ID with the same payload yields the recorded acknowledgement; the same ID with different content is a protocol conflict. A stale generation or sequence cannot change newer state. Out-of-order delivery is acknowledged or rejected according to the message type; it is never silently treated as current.
- Unknown optional fields are ignored. An unknown required field, incompatible major version, or action capability not advertised by the worker is rejected with a machine-readable reason before the worker acquires game controls. The host validates the common envelope, authorization, and size limits even when the action body belongs to an extension; the worker validates that body's arguments. Extension names must be namespaced and versioned.
- Size limits and heartbeat intervals are negotiated in `session.accepted`, then enforced on both peers. Queue overflow or timeout closes the session; reconnect follows reconciliation. Exact v1 defaults remain a review decision; the private native transport uses 256 KiB UTF-8 records and 1 MiB/128-record queue budgets in 0.13.15+. The public RWP draft binding retains its separate 16,000-byte record limit.
- HTTP errors use RFC 9457 problem bodies with stable `code`, `retryable`, and correlation ID. Mutation responses distinguish host acceptance, worker delivery, worker acknowledgement, game-server confirmation when observable, and uncertainty.

## Optional standard-action profiles (draft)

RWP itself has **no mandatory or closed job-type list**. The generic `action` schema validates only the namespaced ID and argument envelope; the candidate profiles in [`common.schema.json`](schemas/common.schema.json) have separate schemas for their own arguments. A worker may support none of them and still implement RWP. If it advertises one, it must follow that profile's arguments, permitted effects, observable outcome, and cancellation/retry rules—not a mandated movement or inventory algorithm. Arguments are copied into the job snapshot; changing a workflow later does not mutate a running job.

| Action | Completion and cancellation | Replay rule |
| --- | --- | --- |
| `workers.wait.v1` | Count `ticks` of active execution; pause freezes the remaining count. Zero ticks completes immediately. | Resend the same execution/checkpoint safely; never restart the count from zero after a reconnect. |
| `workers.travel.v1` | Reach target coordinates within `radius` in the specified world on safe footing; cancellation releases movement. | Resend the target with the same execution ID; current position is observed again and no movement packet is assumed to have succeeded. |
| `workers.drop-items.v1` | Drop an exact item/count and report authoritative inventory change or an uncertain outcome; cancellation stops further drops. | Never repeat a possibly issued drop after acknowledgement loss. Persist intent before the drop and require reconciliation or inspection. |

`workers.set-profile.v1` remains an illustrative candidate in the draft schema, **not an agreed standard profile**: portable profile identity, revisions, and restoration need review. Minecraft-specific `/home`, `/tpa`, highway construction, stash work, shulker recovery, and Monocle module settings stay namespaced extensions. `Travel` does not authorize mining or placing blocks. A host may show a generic worker-reported result for an extension, but must not relabel it as game-server confirmation without evidence defined by that extension.

## Review packet

- [JSON Schemas](schemas/common.schema.json) describe common IDs, scopes, actions, and worker message families.
- [OpenAPI draft](openapi.json) describes the initial proposed operator route shapes. It is a target contract, not a claim that current hosts serve those routes.
- [Examples](examples.json) contain valid messages, deliberately invalid messages, and expected rejections.
- [Scenario walkthrough](scenarios.md) asks a prospective implementer to decide registration, execution, cancellation, and reconnect using only this packet.
- [Decision log](decisions.md) separates current decisions from questions to resolve with partner platforms.

Run `python3 docs/workers-api-v1/check.py` with the Python `jsonschema` package installed to validate schemas, examples, OpenAPI JSON, local references, and operation IDs. This tooling is for contract review and is not a Monocle runtime dependency.

Phase 1 is ready for external review when these artifacts validate and a second implementer can explain every scenario's next state. **External review is still pending**; feedback and any resulting changes should be recorded before v1 is frozen.
