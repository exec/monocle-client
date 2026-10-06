# Phase 1 decision log

These are draft contract choices, not ratified decisions of other bot platforms.

| Choice | Reason and compatibility consequence |
| --- | --- |
| Separate job and per-worker execution IDs, with generations | A stale worker report cannot revive cancelled or reassigned work. Older Monocle task/run IDs map naturally into this model. |
| Final host cancellation with separate cleanup | Offline workers must learn cancellation later, while the operator can immediately stop scheduling the job. The API must expose cleanup debt instead of calling it complete recovery. |
| Action-neutral core with exact capability negotiation | RWP owns assignment and lifecycle, not a closed job catalog. A host routes a namespaced, versioned action only to a worker advertising it; Monocle highway, stash, and Lua packages remain extensions. |
| Optional standard-action profiles | Wait, Travel, and Drop Items are candidates for cross-client semantics, not mandatory worker features. Profiles specify arguments, permitted effects, observable outcomes, and recovery—not the client's algorithm. Set Profile needs separate review. |
| New public worker endpoint | The current `/v1/workers` route carries private `monocle-crew-7` records (0.13.15+). A new path avoids ambiguous negotiation or accidental protocol mixing. `/v1/interop/workers` is an experimental standalone-host path, not yet ratified for v1. |
| Operator resources over HTTP; worker control over WebSocket | Operators need inspectable, idempotent resource commands; workers need ordered bidirectional delivery and observations. The current `/v1/control` stays a compatibility adapter. |
| Operator event stream is a change feed | Resource reads remain authoritative after a stream gap. This prevents a dashboard from inventing state from lost events. |
| Individual credentials and scoped grants | A shared crew key grants too much authority for sharing workers between operators. Credential format and federation mechanism remain Phase 5 decisions. |
| Unknown optional fields tolerated; unknown required capabilities rejected | Additive versions remain possible without silently asking a worker to perform unsupported work. |

## Questions for partner review

1. Who owns IDs for optional standard-action profiles: a shared `workers.*.v1` namespace, an eventual standards-body namespace, or individually owned namespaces? This draft uses `workers.*.v1` only as a placeholder; vendor actions use their owner's namespace.
2. Should the operator event stream use SSE, WebSocket, or both? Phase 1 specifies resumable events, not transport.
3. What is the smallest useful portable `SetProfile` representation? The draft references a captured profile revision and leaves its contents capability-specific.
4. Which observations can count as game-server confirmation across different clients and servers, especially inventory drops and teleports?
5. How long must a host retain command deduplication records and event replay cursors? A bounded policy must be published before implementation.
6. Should a failed worker always fail a shared job, or should the job carry an explicit completion policy? The state model leaves the policy explicit.
7. Which bounded frame/message sizes and heartbeat defaults can all implementations meet?
8. Which resource routes and names should be frozen in the first OpenAPI release? The current route map is a candidate.

Rejected for the draft: a mandatory RWP job catalog or prescribed client algorithms; silently replaying uncertain side effects; treating disappearance from the player list as proof of success; assuming a worker's observed inventory is fresh indefinitely; using one shared crew key as an Internet-wide identity; and requiring Lua in every worker.
