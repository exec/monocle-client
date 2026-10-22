# Known hostiles

Available in **0.14.1**, part of The Reckoning Update. This is a shared avoidance policy, not a retaliation system or proof of stash ownership.

## Operator controls

Open **Known hostiles** in the standalone host's WebUI sidebar. The page lists each player's name, server, optional UUID, source and date added. Add players using their Minecraft username and server address; supplying a UUID is recommended. **Remove** forgives an entry. Read-only sessions cannot edit the list or policy.

Enable **Disconnect workers when a known hostile becomes visible** to arm the policy. It defaults off; learning still occurs while off. The shared list applies to every updated native worker connected to this host, across its crews, but only on the matching Minecraft server. The page explicitly identifies connected clients that do not support the policy. Update both host and clients; older clients are not protected.

### Host-wide warnings (0.14.4)

Every updated native client submits its current loaded-player view for a host lookup, once per second through its existing announcements. This is separate from the retained entrance history. The host acknowledges checks (including negative results) and broadcasts a known-hostile warning to **all updated connected clients, across crews**. Notifications show who saw the player, crew, server and coordinates. Warnings appear even while the disconnect guard is off. When armed, **all clients on the matching Minecraft server**, including other dimensions, disconnect using the existing safety/reconnect path; clients on other servers only receive the warning. Cached local detection still checks every tick and never waits for a host answer.

Alarms expire after ten seconds without a fresh sighting. Multiple reporters and repeated views refresh the same alarm without repeatedly warning the crew. Arming the guard reissues any still-active alarms so an earlier warning-only delivery cannot suppress protection. A reconnecting client gets an alarm only while it is still active; removing a hostile invalidates pending alarms. Host restarts discard transient alarms, not the hostile list. Workers ignore samples older than five seconds and host checks must match the reporter's current scope. Each sample is bounded to 32 players; larger crowds rotate through batches. Transport delays and lost connections can delay/miss protection, so this is not a safety guarantee.

The WebUI's Known hostiles page shows current alarms and clients with only local detection. Authenticated status adds `hostileWarnings` and per-worker `hostileWarningsSupported`. Native hello advertises `hostileWarningsProtocol: 1`, `playerChecks: {ageMs, sightings}` and `inLobby`. The host replies with `task-player-check-result` (`checked`, `revision`), sends catalog updates before `task-hostile-warning` (`warning`, `remainingMs`, `revision`), and retries unqueued warnings while fresh. These frames are accepted only from the authenticated connected host; old clients never receive unknown frames. Standalone and in-game hosts use the same decision logic. This remains a Monocle extension, not a portable RWP requirement.

**Detected lobbies produce no player sightings, hostile alarms, learning or incident-timeline records.** Lobby combat packets are not captured, preventing replay after leaving. Connection diagnostics and job failures remain logged. Passive 6b6t detection is independent of Lobby Skip activation and walking timeouts; an unknown lobby on another server is not automatically exempt. Configured Discord notifications include new host-wide hostile alarms, with ordinary neutral sightings excluded.

The in-game host uses the same catalog and sync message. Its controls are:

```text
.worker hostile list
.worker hostile guard true
.worker hostile guard false
.worker hostile add PlayerName
.worker hostile remove <entry-id>
```

Adding through the client uses the current server and records a UUID when that player's model is available. A connected worker cannot override its host's catalog through these commands. Solo clients can use them with no host active.

## Automatic learning and forgiveness

A worker's `combat.lastDamage` can add a hostile only when attribution is `server_reported`, its attacker is a resolved player with a valid UUID/username, and the reported server matches the worker's current server. Self/known worker identities and locally trusted friends are excluded. Crystal placement/detonation suspects and unstructured death text **never automatically add a player**; use a manual entry if you choose to act on that evidence.

UUID-backed entries follow name changes, rather than matching whoever later takes that username. A name-only manual entry is case-insensitive and may match a different person who reuses the name. An attributed attack upgrades a matching name-only entry to the observed UUID. Server addresses are normalized for case and the default `:25565` suffix, not resolved as DNS aliases.

The host saves the latest processed damage receipt per reporting worker. Removing a player does not erase that receipt: reconnecting or restarting the host cannot re-add them from the same old damage report. A **new** attributed attack can re-add them. Receipts and players are bounded to 256 each. Worker reports are trusted host inputs, not cryptographic proof about the Minecraft server or the attacker.

## Disconnect behavior

Workers check actual loaded player models each game tick. Tab-list presence alone does not trigger a logout. Dead/spectator/self models are ignored. Lobby sightings are ignored **while the passive connection tracker identifies a lobby**, even with Lobby Skip disabled; there is no universal lobby detector for arbitrary servers.

The guard is host-controlled and independent of Auto Log's activation and its `AlertOnly` action. Once explicitly enabled, it disconnects even when Auto Log is inactive, and job/profile changes cannot toggle it off. It does not disable its own policy after firing. Unless safe reconnect is explicitly enabled (below), Auto Log's existing AutoReconnect-disable preference still applies; if reconnect is allowed, the guard remains armed on the next connection.

Workers cache the last trusted catalog on disk and use it if the host connection drops. A disconnected worker cannot receive a forgiveness/policy change until it reconnects. Switching hosts replaces the cache with the new host's catalog. Corrupt catalogs are preserved and reported instead of silently cleared; repair the file before relying on protection.

Before disconnecting, the worker writes a `hostile_logout` evidence record and attempts an immediate existing announcement. Worker inspection shows `combat.lastHostileLogout`; host combat events and local game logs retain the reason. Delivery is best-effort, with no acknowledgement delay before logout. A failed report cannot block the disconnect.

**Logging out is not guaranteed protection.** A server may keep a combat-tagged player in the world, reject logout assumptions or deliver lethal damage first. Unknown/unresolved attackers and missed player models cannot trigger this policy.

## API and persistence

### Incident timeline (0.14.2)

Open **Incidents** in the WebUI for neutral/friendly/hostile player entrances, damage, deaths, crystal evidence, hostile logouts and safety reconnect events. Filter by event type or search for a worker, player or server; expand Evidence for original timestamps, positions and confidence. Latest refreshes automatically; Older pages use stable receipt cursors. Known workers are excluded from sightings, not combat evidence.

Clients sample loaded player models once per second, not the tab list. A player must disappear for ten seconds before another entrance is recorded; world/session changes reset this debounce. Detected lobbies are excluded. Friends are labeled friendly; others are neutral unless listed as hostile at receipt time. These are observations, not proof of ownership, intent or visibility to the human camera.

`GET /v1/incidents` (or same-origin `/ui/api/v1/incidents`) uses existing bearer authentication, including read-only tokens. Optional queries: `limit` (1–100, default 100), `kind` (`sighting`, `damage`, `death`, `crystal`, `hostile_logout`, `reconnect`), `search` (up to 100 characters), `before` (previous `nextCursor`). Replies include `items`, `hasMore`, `nextCursor` and logging health. `cursorExpired: true` means the referenced receipt aged out; request Latest. `/v1/status.incidentSummary` reports health without including the full timeline.

The host retains the latest 2,048 events in memory and restores them from `incidents.telemetry.jsonl`, rotated through three backups at approximately 16 MiB each. Local clients/in-game hosts use the same log under the Monocle config folder; `.worker hostile incidents` prints their latest ten receipts. Records use host receipt order and retain worker observation timestamps separately. Repeated announcements are deduplicated. A client keeps its latest 32 sightings for delivery; large crowds, dropped connections, full queues/disks or abrupt process exits can lose evidence. Corrupt log lines are skipped and counted, never used to overwrite the hostile list. Logging is asynchronous/best-effort and never gates work or a disconnect. There is no greeting, teabagging or retaliation behavior.

### Safe reconnect (0.14.2)

In **Known hostiles**, enable **Retry safely after a hostile logout** and save. It defaults **off**, with a five-minute wait when enabled; the operator can choose 5–3,600 seconds. The disconnect guard must also be enabled. Normal disconnects keep their existing reconnect behavior. The transient safety wait is independent of AutoReconnect's active flag and cannot be removed by a job restoring module settings. Existing permanent-error filters, attempt limits and retry backoff still apply. Pause/Cancel Retries remain available; Reconnect Now deliberately bypasses the wait.

For 6b6t the retry enables Lobby Skip before connecting so it can arm on the connection event. After joining, the normal known-hostile guard continues outside the detected lobby. Seeing the hostile again triggers another logout/wait; the client cannot determine from offline state whether the area is safe. It does not automatically launch a new job or bypass checkpoint/recovery rules. Closing/restarting Minecraft discards the transient timer; this feature reconnects a running client, not a closed process.

In-game host or solo commands: `.worker hostile reconnect true`, `.worker hostile cooldown 300`; a connected worker cannot change the host policy. Use `hostile-policy` with `enabled`, optional `safeReconnect` and optional `reconnectSeconds`; omitted optional fields preserve preferences. Older catalogs default safe reconnect off. `safeReconnectProtocol: 1` advertises native worker support; worker status exposes `safeReconnectSupported` and `safeReconnect` telemetry. Upgrade host and clients; older clients supporting only the hostile list cannot honor this wait. These are Monocle extensions, not additions to the portable RWP core.

Authenticated status (`/v1/status`, `/ui/api/status`) exposes `knownHostiles`:

```json
{"version":1,"revision":4,"enabled":true,"players":[{"id":"e35a6d95-76d0-47ef-9145-011dbf04f14a","server":"example.org","name":"PlayerName","uuid":"","source":"manual","reporter":"","addedAt":1791648000000}]}
```

Use the existing authenticated `/v1/control` or same-origin `/ui/api/control` endpoint:

```json
{"op":"hostile-add","server":"example.org","name":"PlayerName","uuid":""}
{"op":"hostile-remove","id":"e35a6d95-76d0-47ef-9145-011dbf04f14a"}
{"op":"hostile-policy","enabled":true}
```

These operations are durable and return the updated catalog. Repeated adds merge matching identities; removal of a missing ID reports an error. Worker status includes `knownHostilesSupported`. Native workers advertise `knownHostilesProtocol: 1` in hello; the authenticated host sends `task-known-hostiles` with a `catalog` snapshot on connect and revision changes. Worker-origin policy frames are rejected. This is a Monocle-native safety extension, not a newly standardized RWP action.

Standalone persistence is `<host data directory>/known-hostiles.json`; client persistence is `<Monocle config directory>/known-hostiles.json`. Atomic writes precede publishing edits. Private replay receipts remain in the file and are omitted from status and sync snapshots.
