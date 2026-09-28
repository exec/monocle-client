# Phase 2 operator adapter (draft)

The standalone host now serves an authenticated, loopback-only resource API alongside `/v1/status` and `/v1/control`. It delegates decisions to the same `HostService.control` path as the existing WebUI. These routes are an **incremental draft**, not yet the frozen v1 contract in `openapi.json`.

The bundled control panel can call the same resource handlers at `/ui/api/v1/*`. This alias requires the API token and the panel's exact same-origin check; native `/v1/*` still rejects browser `Origin` headers. The panel now uses resource routes for job pause/resume/cancel, history deletion, worker detach/rejoin, job priority, guided job configuration, configuration reads, and crew creation. Its rich live snapshot, highway controls, workflow editor, and other features still use legacy `/ui/api/*` calls until equivalent resource views exist.

Send `Authorization: Bearer <host API token>`. Browser `Origin` headers are rejected. Mutations with a body require `Content-Type: application/json`; command POSTs with no arguments send `{}`. Responses are JSON. Resource mutations now also require a UUID `Idempotency-Key`; see the [Phase 3 reliability notes](phase3-operator.md).

| Resource | Implemented routes |
| --- | --- |
| Host | `GET /v1/host`, `/v1/health`, `/v1/capabilities` |
| Workers | `GET /v1/workers`, `GET/DELETE /v1/workers/{id}`, `GET /v1/workers/{id}/resources` |
| Crews | `GET/POST /v1/crews`, `GET/PATCH/DELETE /v1/crews/{id}`, `PUT /v1/crews/{id}/workers/{workerId}` |
| Jobs | `GET/POST /v1/jobs`, `GET/PATCH/DELETE /v1/jobs/{id}`, `GET/PATCH /v1/jobs/{id}/configuration`, `POST /v1/jobs/{id}/{pause,resume,cancel,release}`, `POST /v1/jobs/{id}/workers/{workerId}/{detach,rejoin}` |
| Drafts | `GET/POST /v1/drafts`, `GET/DELETE /v1/drafts/{id}`, `POST /v1/drafts/{id}/dispatch` |
| Workflows | `GET/POST /v1/workflows`, `GET/PUT/DELETE /v1/workflows/{id}` |
| Stashes | `GET/POST /v1/stashes`, `GET/PATCH /v1/stashes/{id}`, `GET /v1/stashes/{id}/resources`, `POST /v1/stashes/{id}/scan` |
| Setting catalog | `GET /v1/configuration-controls` |

List routes return `{"items":[...],"nextCursor":"..."}`. `limit` is 1–100 (default 50); `cursor` is opaque. `crewId` filters workers, jobs, and stashes; `status` filters jobs; `connected=true|false` filters workers. Pagination is over a live snapshot, so restart a listing if the collection changes during traversal. Worker records include `observedAt` and `observationAgeMs`; stash records include `observedAt` when a saved update time exists.

All resource IDs are UUIDs. Existing custom crew and workflow UUIDs are preserved. Legacy default/built-in IDs and stashes receive deterministic UUIDs; clients should not derive them. Job records expose `crewId`, `workerIds`, `scope`, and normalized `state` in addition to current Monocle-specific fields.

Submit a job from a saved workflow with `POST /v1/jobs`:

```json
{
  "id": "f311a49f-ed0a-461d-b2b9-0203f230d57d",
  "crewId": "<id from GET /v1/crews>",
  "workerIds": ["<id from GET /v1/workers>"],
  "name": "Inspection run",
  "scope": {"server": "play.example.org", "dimension": "minecraft:the_nether"},
  "workflowId": "<id from GET /v1/workflows>",
  "args": {},
  "priority": 0
}
```

Alternatively, supply exactly one typed `action` instead of `workflowId` or `package`. `GET /v1/capabilities` advertises the currently executable portable IDs: `workers.wait.v1` (`{"ticks":20}`), `workers.travel.v1` (matching `scope`, `x`, `y`, `z`, optional `radius`), and `workers.drop-items.v1` (`item`, `count`, optional `recipientWorkerId`). The host validates and maps these to its existing captured worker workflows; the caller does not send Lua. `workers.set-profile.v1` remains unsupported until portable profile IDs and revisions can be resolved safely. A captured Monocle `package` is still accepted as a vendor extension. The host captures the prepared package at submission. `PATCH /v1/jobs/{id}` currently accepts `{"priority": 5}`. Release accepts `{"newId":"<UUID>"}` and returns a draft job. Crew reassignment reports only that the instruction was sent; read the worker again to confirm reconnection. Cancellation reports the host decision, not worker cleanup or game-server confirmation.

`GET /v1/workers/{id}/resources` returns `{"known":false}` unless a fresh native-highway report includes that worker's supply ledger. A known report separates carried inventory, ender-chest contents, and totals, and marks whether the ender-chest snapshot itself is known. `pavingBlocks` means the selected highway material, not necessarily obsidian. Stash resource counts are observations, not stock reservations; `complete` means every *recorded* container is directly observed, not that the entire cuboid has been searched. Crew details include aggregate highway counts only while at least one worker has a fresh report.

`POST /v1/stashes` defines a new stash with `crewId`, `name`, `world:{server,dimension}`, and `bounds:{minX,maxX,minY,maxY,minZ,maxZ,homeName}`. The `/home` name is required; `lazyMode`, `homeWarmupTicks`, and `homeCooldownTicks` may also be set in `bounds`. Re-creating the same crew/world/name is rejected rather than overwriting observations. `POST /v1/stashes/{id}/scan` takes a caller-generated job `id`, `workerIds`, and optional `priority`, and dispatches the existing partitioned stash-scan workflow. The resulting job and its per-worker telemetry are read through the normal job routes. A newly defined stash with zero observations reports `complete:false`, not a known-empty inventory.

`PATCH /v1/stashes/{id}` accepts partial `bounds` changes, for example `{"bounds":{"homeName":"depot","lazyMode":false}}`, with the current `revision` in `If-Match`. Name and world remain fixed because they determine the stash ID. Bounds may change only before any container has been observed; otherwise create a new stash so old counts are not silently assigned to a different area. A stash with worker-specific `/home` settings cannot change its default home settings through this route. Edits refresh connected workers' catalogs.

`GET /v1/jobs/{id}/configuration` shows captured job profiles and per-worker requests/acknowledgements, **not** a live read of every client setting. `PATCH` accepts an advertised control from `GET /v1/configuration-controls`, for example `{"control":"speed","active":true,"value":5.5,"workerId":"<UUID>"}`. Omit `workerId` to target every unfinished worker on the job. The host validates the control and queues the existing worker-configuration protocol; inspect the returned `updates` until acknowledged.

Drafts are validated but unassigned job intents. `POST /v1/drafts` accepts the existing captured draft record (`id`, `name`, `server`, `dimension`, `priority`, `args`, `package`, and optional native definition). Dispatch accepts `{"crewId":"<UUID>","workerIds":["<UUID>"]}` and returns the created job. Its duplicate handling and safety gates are the same as legacy `draft-assign`.

`DELETE /v1/workers/{id}` forgets only an offline worker with no outstanding job, highway recovery record, or cleanup acknowledgement. This guard also applies to the legacy `worker-forget` control.

Still pending in Phase 2: portable profile selection, general (non-highway) worker resource/configuration reads, safe stash deletion and edits to previously observed bounds or worker-specific home settings, independent in-game HTTP serving, and the remaining WebUI migration. The Phase 3 reliability adapter is also incremental; external integrations should treat these endpoints as experimental and keep the legacy controls available.
