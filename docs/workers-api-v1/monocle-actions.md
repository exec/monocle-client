# Monocle actions over RWP

Status: experimental client extensions on `rwp/1-draft`, starting in **0.14.10**. These are not mandatory RWP actions or a universal job catalog. A coordinator must discover the exact capability before assigning it. The current standalone coordinator already routes advertised extension actions through its durable job queue; it does not implement or independently verify their game behavior.

## `dev.monocle.follow.v1`

Follow a player using Monocle's existing native Travel/follow executor. The subject is not controlled by this assignment and need not be a connected worker. The worker and subject must be in the same game world; this first adapter does **not** issue `/tpa`, enable combat, install a profile, or coordinate a formation.

The action object has exactly `type` and `arguments`. Arguments are required:

| Field | Contract |
| --- | --- |
| `target` | Canonical lowercase player UUID; not the executing worker's own UUID. Names are not accepted. |
| `radius` | Number from 0.15 to 8 blocks; desired proximity, not a promise of exact positioning. |
| `ticks` | Integer from 0 to 1,728,000; 0 runs until cancelled. A finite interval counts ready, authorized client ticks handed to the native action, including time awaiting a missing subject. |

The enclosing assignment supplies RWP job/execution/generation/command IDs and the world `scope`. No Monocle crew object or Lua package is required on the wire.

```json
{
  "type": "dev.monocle.follow.v1",
  "arguments": {
    "target": "00000000-0000-0000-0000-000000000002",
    "radius": 2,
    "ticks": 0
  }
}
```

To submit through Monocle's standalone operator API, use `POST /v1/jobs` with the ordinary `crewId`, `workerIds`, `name`, `scope`, and this `action` object. Select a worker connected to `/v1/interop/workers` and advertising `dev.monocle.follow.v1`; do not submit a native workflow package alongside it. Another compatible RWP coordinator can send the same action in its `execution.assign` envelope.

### Execution and safety

- Native pathfinding follows the subject's currently observed position. Walking and adaptive ElytraFly movement reuse the existing executor, equipped Elytra requirements, collision/landing checks and local module settings. This is not teleportation, nor a guarantee of catching a faster subject.
- An absent or dead subject produces a waiting progress message. The worker does not fly toward a remembered location. The finite interval still advances; it describes time assigned to following, not uninterrupted proximity.
- An existing native worker task, local highway, printer or stash scan prevents takeover. The RWP execution fails instead of silently acquiring conflicting movement authority. Native worker connections remain unchanged; simultaneous native/RWP sessions are not implemented yet.
- The remaining interval and assignment identity are saved in `rwp-execution.json`. Disconnect, wrong world and unready world revoke active movement. Reconnect requires host reconciliation before resuming; the native route is recalculated, never replayed as movement packets. The coordinator's conservative inspection policy still applies after host restart or a missing extension checkpoint.
- A finite interval completes only after releasing native control, including the existing landing handoff if airborne. Completion means the **assigned interval ended**, not that the target was reached or protected. Unlimited Follow never reports completion automatically.
- Cancellation immediately revokes following authority. The durable `cancelling` phase remains until the game thread releases control; only then is `execution.cancelled` reported with acknowledged cleanup. Landing may take longer than one tick. Cancellation is not undone by a late completion.
- Native failure reports `follow_failed`. Outcomes and proximity remain worker observations, not independent Minecraft-server proof.

## Migration scope

Wait and fixed-destination Travel remain supported. Follow is the first client-specific public action adapter, not full workflow execution. Highway building, stash scans, kit transfers, TPA handshakes, captured profiles and live configuration still use the private Monocle transport. Future adapters must reuse their existing execution and inventory-receipt safeguards rather than forwarding a job name and pretending the host-dependent behavior is implemented.

Local automated checks exercise the real WebSocket connection, draft coordinator dispatch, native argument mapping, interval persistence, progress and cleanup acknowledgements. They do **not** simulate a real player's flight, certify a third-party coordinator, or freeze RWP/1.
