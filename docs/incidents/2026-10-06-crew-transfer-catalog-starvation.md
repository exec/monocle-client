# Crew transfer delayed by repeated catalog refreshes

First live three-worker transfer: `6f1b5f9c-39e9-4020-b1f8-9e5c8476a8b5`, `zungle1` to `newstorage`, Unbans leading UnbansBot and iotacism.

Workers installed slowly, reconnected, and waited for host-managed planning. Unbans completed `/home` before both follower TPAs dispatched. iotacism arrived first and correctly waited for the third worker at the travel barrier. No kit delivery had been confirmed at that point.

A live JVM thread dump caught the coordinator inside `StashCatalog.list`, called by `sendStashCatalog` on every `task-worker` checkpoint heartbeat. The coordinator consumed about 216 CPU seconds in 226 seconds of uptime. This synchronous full-catalog read/summary repeatedly delayed gameplay coordination with the large real stash catalog; no rejected worker message was reported.

Correction: both host adapters send a periodic catalog at most once per minute per connection. Reconnects and explicit definition/edit/import updates still sync promptly. A socket check sends ten checkpoint heartbeats without another catalog, and the existing edit check verifies immediate synchronization.

After tests passed, the standalone host restarted with the correction and resumed the same durable job. UnbansBot teleported, all three arrived at the source, and the phase advanced to parallel pickup. Control status requests returned in under a second. This verifies the initial handoff, not an entire transfer or all future travel legs. The in-game host correction compiled but was not exercised live; running workers need no update for the standalone-host correction.
