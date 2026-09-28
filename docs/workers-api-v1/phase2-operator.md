# Phase 2 operator adapter (draft)

The standalone host now serves an authenticated, loopback-only resource API alongside `/v1/status` and `/v1/control`. It delegates decisions to the same `HostService.control` path as the existing WebUI. These routes are an **incremental draft**, not yet the frozen v1 contract in `openapi.json`.

Send `Authorization: Bearer <host API token>`. Browser `Origin` headers are rejected. Mutations with a body require `Content-Type: application/json`; command POSTs with no arguments send `{}`. Responses are JSON. Rejections currently use `{"error":"..."}`; tracked operations and RFC 9457 problem bodies are Phase 3 work.

| Resource | Implemented routes |
| --- | --- |
| Host | `GET /v1/host`, `/v1/health`, `/v1/capabilities` |
| Workers | `GET /v1/workers`, `/v1/workers/{id}` |
| Crews | `GET/POST /v1/crews`, `GET/PATCH/DELETE /v1/crews/{id}`, `PUT /v1/crews/{id}/workers/{workerId}` |
| Jobs | `GET/POST /v1/jobs`, `GET/PATCH/DELETE /v1/jobs/{id}`, `POST /v1/jobs/{id}/{pause,resume,cancel,release}`, `POST /v1/jobs/{id}/workers/{workerId}/{detach,rejoin}` |
| Workflows | `GET/POST /v1/workflows`, `GET/PUT/DELETE /v1/workflows/{id}` |
| Stashes | `GET /v1/stashes`, `/v1/stashes/{id}` |

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

The draft also accepts a captured Monocle `package` instead of `workflowId`; this is a vendor extension, not a portable action. The host captures the prepared package at submission. An identical submission ID/body remains idempotent through the existing host check. `PATCH /v1/jobs/{id}` currently accepts `{"priority": 5}`. Release accepts `{"newId":"<UUID>"}` and returns a draft job. Crew reassignment reports only that the instruction was sent; read the worker again to confirm reconnection. Cancellation reports the host decision, not worker cleanup or game-server confirmation.

Still pending in Phase 2: portable-action submission, worker configuration/resources routes, stash definition and scan mutations, draft resources, independent in-game HTTP serving, and WebUI migration. Phase 3 adds command IDs, preconditions, operation receipts, stable problems, and events. Until then, external integrations should treat these endpoints as experimental and keep the legacy controls available.
