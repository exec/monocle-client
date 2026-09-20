# Monocle standalone Workers host

Live gameplay configuration requires workers on 0.7.50+. POST `/v1/control` using the existing bearer token:

```json
{"op":"configure","id":"JOB-UUID","modules":{"speed":{"active":true,"settings":"{groups:[{name:'General',settings:[{name:'vanilla-speed',value:6d}]}]}"}}}
```

Add `worker: "WORKER-UUID"` to target one worker; omit it for all assigned unfinished workers. SNBT patches preserve unspecified settings. Run fields `configuration.revision`, `configRevision`, and `configError` show pending, applied, or rejected updates. Pending updates retry once per second; wait for acknowledgement before another update to that worker. Paused/offline workers apply when running again. Overrides survive pause/resume and restore personal settings at job exit; saved starting profiles stay unchanged. Job-owned Highway Builder/Printer Helper/Schematic Selector settings are excluded. Maximum patch size is 12 KB. In-game hosts use `.bot job JOB-UUID configure MODULES-JSON` with the same modules object.

The operator WebUI is available at **http://127.0.0.1:6970/ui/**. Use the API token, not a crew key. Worker/crew views, job controls, workflow upload, priorities, history and inspection share the existing coordinator. No Node or separate frontend process is required. See the [WebUI guide](https://github.com/exec/monocle-client/blob/master/docs/bot-webui.md).

Optional loopback WebSocket ingress and `GET /v1/status` / `POST /v1/control` run alongside the existing local API and LAN TCP. Set `webPort` to 6971 to opt in; 0 or an absent field keeps it disabled. Remote worker URLs use `wss://HOST[:PORT]/v1/workers` through an operator-managed TLS reverse proxy. See [web transport setup and limits](https://github.com/exec/monocle-client/blob/master/docs/bot-web-transport.md). Crew keys still grant trusted execution, not scoped account-based sharing. The admin API token remains full host control; do not share it with workers or clanmates.

0.7.20 keeps standalone coordination unchanged; its fresh-highway supply-journal gate is worker-side.

0.7.16 keeps the same exchange token alive while workers retry pickup/server confirmation; preparation failures can cancel safely and request supplies again. Install 0.7.16 workers for active dropped-stack pickup retries. This includes the bounded asynchronous telemetry added in 0.7.15, now with exchange stages. See [reading telemetry](https://github.com/exec/monocle-client/blob/master/docs/bot-telemetry.md). Deployment still requires a host restart; do not replace the live service in the middle of a job without planning its recovery.

0.7.14 supports highway jobs of 16–100,000 road blocks, including saved progress and workflow arguments. Jobs above 4,096 require 0.7.14 on both host and workers. Existing jobs retain their configured length; schedule the host update between jobs because restarting the service interrupts its live execution.

0.7.12 exposes bounded read-only worker snapshots in `status.workers[].diagnostics` (including idle workers) and `status.highways.<crew>.workers[].diagnostics`. `observationAgeMs` distinguishes a stale connection report from its last sampled builder tick. Workers cache snapshots for one second. This requires 0.7.12 workers; 0.7.11 hosts still display the compact diagnostic summary in ordinary worker status. Diagnostics never authorize gameplay, resolve resources, or expose credentials.

0.7.11 defers native Resume until the saved execution, current worker reports and original world are ready. Reconnection timing no longer stops the host listener. Resume intent remains pending; real checkpoint failures still stop dispatch. Workers on 0.7.10 are compatible.

Cancellation becomes `Cancelled` immediately, even with offline workers. `cleanupPending` and per-worker checkpoints distinguish that final decision from outstanding delivery/recovery. These records survive host restarts and history retention; deletion stays protected until cleanup is acknowledged. Offline END recipients do not block other workers starting or completing replacement jobs. Cancelled tasks never regain native work authority from stale reports.

0.7.8 supports the `RecoverSupplies` workflow action and restoring restarted worker assignments after their workflow recovery is ready. Update workers and host together. This does not remove host-process restart inspection or migrate old uncertain item-transfer receipts. See [workflow recovery](https://github.com/exec/monocle-client/blob/master/docs/workflow-recovery.md) for this stage's limits. Banter Mode is a local worker Config preference; the host cannot send arbitrary public-chat text through it.

A Java 25 background process with **no Minecraft account, game window, GPU context, Fabric or Lua installation required**. Workers still run Minecraft; use Monocle 0.7.5 or newer. Client-based hosting remains available. 0.7.6 fixes native suspension/resume deadlocks and forwards explicit Resume to a locally paused builder without replacing its execution.

Supported now: authenticated worker connections, multiple configured crews, Lua/nested workflows, Highway, Wait, Travel, DropItems, Modules, Tpa, SetProfile, RecoverSupplies, StashScan, and StashResupply actions; captured profiles; priorities; pause/resume/cancel; durable cancellation/recovery records; and separate, deletable history (30-day expiry by default).

**Not supported by this service yet: stash-hunt jobs, launcher/account management, or render suppression.** Unsupported dynamic actions fail before acquiring native controls. Idle workers can be reassigned between this host's crews; an active destination highway is carried through reconnect and late admission. Source work and unfinished recovery must still be settled first. Do not take over an unfinished client-hosted crew: finish/cancel that job and recover supplies first. The service currently pauses a highway crew while a worker performs a higher-priority task; client hosting still supports individual borrowing/automatic return travel. Native highway host restarts require inspection and ending the old execution, not automatic replay.

## Run

Build with Java 25: `./gradlew :host-service:build`. Unzip `host-service/build/distributions/monocle-host-<version>.zip`. The ZIP contains Java dependencies and scripts for macOS/Linux and Windows; the small JAR alone is not an executable bundle. Java 25 must be installed and available via `JAVA_HOME` or `PATH`.

From the extracted folder on macOS/Linux:

```sh
bin/monocle-host init /absolute/path/to/MonocleHost
bin/monocle-host serve /absolute/path/to/MonocleHost
```

Windows uses the same commands with `bin\monocle-host.bat` and, for example, `C:\MonocleHost` as the data directory. Keep this directory separate from Minecraft profiles and source repositories; it holds credentials and durable execution records. `init` refuses to overwrite an existing configuration.

`host-config.json` contains a randomly generated API token and Default crew key. Copy the **crew key**, not the API token, into every worker's **Right Shift → Workers → Worker** settings. Enable worker connections and trusted-host task execution. Connect to `127.0.0.1:6969` on this machine, or the host's LAN address if using remote workers. Stop the old in-game listener first if it occupies port 6969. An old client without capability negotiation is refused; use 0.7.3+.

The Overview page includes the host-only **Auto TPY** policy. When enabled, an observed exact `/tpa <username>` from one worker is accepted by that unique same-crew worker after 10 ticks. Both workers must have fresh observations on the same server; the request expires after 10 seconds. Workers cannot enable the policy. The equivalent config field is `"autoTpy": true`; it defaults to false.

By default the TCP worker listener binds to `127.0.0.1`. For LAN TCP workers, stop the service and set `bind` to its specific private LAN address; wildcard/public bindings are rejected. Ordinary TCP worker traffic is authenticated but **not encrypted**; keep it on a trusted LAN or VPN. Only credential handoffs use encryption on TCP. `wss://` uses TLS for all traffic through the separately configured loopback web ingress. Additional crews are entries in `crews`, each with its own unique 24–128-character key. Configuration changes require a restart. An OS file lock prevents two processes from owning the same data directory.

The administrative API always binds to **127.0.0.1:6970**, separately from worker traffic. It requires the API token; native routes reject browser-origin requests, while separate `/ui/api/*` routes enforce same-origin browser access. Do not port-forward it or publish the config file. Unix configuration files are created owner-only; use an appropriately protected user directory on Windows.

## Inspect and test

Job records roll at the 64-record limit: accepting a new job removes the oldest safely finished record. Active jobs and unacknowledged cleanup are never removed. The existing age-based retention still applies.

In a second terminal:

```sh
bin/monocle-host request /absolute/path/to/MonocleHost '{"op":"status"}'
```

Status contains connected worker UUIDs, their crews, active tasks, separate history and the service's capabilities. Use one worker UUID in this `request.json`:

```json
{
  "op": "submit",
  "id": "00000000-0000-4000-8000-000000000001",
  "name": "First standalone test",
  "crew": "Default",
  "workers": ["REPLACE-WITH-WORKER-UUID"],
  "server": "play.6b6t.org",
  "dimension": "minecraft:the_nether",
  "priority": 0,
  "script": "return function(ctx) if not ctx.state.waited then ctx.state.waited=true; return bot.wait(200) end; return bot.done() end"
}
```

```sh
bin/monocle-host request /absolute/path/to/MonocleHost @/absolute/path/to/request.json
bin/monocle-host request /absolute/path/to/MonocleHost '{"op":"pause","id":"00000000-0000-4000-8000-000000000001"}'
bin/monocle-host request /absolute/path/to/MonocleHost '{"op":"resume","id":"00000000-0000-4000-8000-000000000001"}'
bin/monocle-host request /absolute/path/to/MonocleHost '{"op":"cancel","id":"00000000-0000-4000-8000-000000000001"}'
```

Use a new UUID for each intended execution. Repeating an identical submission with the same ID returns the existing task; changed arguments with that ID are rejected. The CLI generates an ID if omitted, but specify one when you need retry-safe submission. If a request times out, inspect status before issuing another task. `server` must match the worker's actual multiplayer address; use `local` for singleplayer. Travel and task cleanup retain the worker's normal safety/landing requirements.

`priority` takes `id`, `priority` (-1000..1000), and optional `worker` UUID for a per-worker override. A strictly higher priority preempts after native cleanup; equal priority preserves FIFO order. `delete` removes completed/cancelled/failed task history only after worker cleanup is acknowledged. Set `historyDays` to 0 for immediate finished-history cleanup or -1 to retain it. Cancellation is immediately final on the host; `cleanupPending` tracks the independent delivery/recovery debt, which never expires as history.

Plain `script` submissions preserve each worker's existing gameplay settings (`Current` is an empty overlay). For uniform captured settings or nested workflows, run **`.bot export-workflow <workflow-id>`** in Monocle. This writes a uniquely named package under that profile's `monocle-client/workflow-exports/`, including referenced gameplay profiles. Replace `script` in the submission with `"packageFile":"/absolute/path/to/export.json"` and pass it through the CLI. The CLI reads that local file and sends its contents; the service API itself never reads caller-specified paths. Inspect profiles before applying them; exported gameplay settings are deliberately not account/transport secrets.

## Launcher-facing API

### Native highway test

In a client on the intended server/dimension, configure Highway Builder and its crew inventory/work-sharing settings, stand at the intended origin, then run `.bot export-workflow highway-default`. The export includes the geometry, workflow and captured gameplay profiles. Submit:

```json
{
  "op": "submit",
  "id": "00000000-0000-4000-8000-000000000002",
  "name": "Standalone highway test",
  "crew": "Default",
  "workers": ["FIRST-WORKER-UUID", "SECOND-WORKER-UUID"],
  "server": "play.6b6t.org",
  "dimension": "minecraft:the_nether",
  "packageFile": "/absolute/path/to/highway-default-export.json",
  "args": {"length": 128}
}
```

Use the same `request ... @file.json` CLI. `args` may override integer `x`, `y`, `z` and `length` (16–100,000). Choose at most one worker per floor column. The workers must reach the Highway workflow step on the captured server/dimension before PREPARE is sent; the shared controller starts them after positioning/readiness. Overlapping work/supply areas in different crews are rejected.

`status.highways.<crew>` includes the execution, progress, generation, fresh worker diagnostics, detached suppliers, reservation owner and the last 100 events. Paving, mining, flight and inventory actions remain entirely in the clients. Native coordination uses the same controller as client hosting, including both work-sharing modes, rolling verification, moving supply handoffs, inventory transfers and bounded cancellation cleanup. The service never invents host world observations: active workers report server-resolved rows.

Normal pause/resume/cancel use the task ID. `{"op":"end-highway","crew":"Default"}` ends an orphaned/interrupted native execution, cancels any owning workflow, and retries durable END receipts to reconnecting workers. Unconfirmed containers/transfers are archived in `ended-<execution>-supplies.json`, not reported as recovered. Cancellation is final without those receipts; only the affected workers must acknowledge their old ending before joining another highway. After a native host restart, inspect these records and saved `highwayProgress`, cancel/end the old execution, and prepare only the remaining work; Resume deliberately refuses to replay it.

### HTTP transport

New native jobs on 0.7.36 use independent restocking and require workers advertising
supply protocol 2. Only the requesting worker detaches; the work front stays fixed
if all workers are resupplying. `status.highways.<crew>.supplyContainers` maps owners
to observed container positions without reserving the crew's movement. A disconnected
supplier or failed individual workflow cannot pause the remaining builders. See the
[incident and rollout notes](https://github.com/exec/monocle-client/blob/master/docs/incidents/2026-09-14-independent-restocking.md).

If a resource exchange is uncertain, inspect `status.highways.<crew>.workers[].exchange` and `.inventory`, then physically check the participants and dropped items at the reported location. Only after that inspection, send `{"op":"resolve-transfers","crew":"Default","execution":"CURRENT-NATIVE-EXECUTION-UUID","confirmed":true}` to acknowledge the uncertainty and release the transfer hold without replaying its drop. A stale execution or missing confirmation is refused; this operation does not claim a pickup was server-confirmed. Client-based hosts expose the same shared resolution in Workers.

`POST http://127.0.0.1:6970/control`, with `Authorization: Bearer <apiToken>` and `Content-Type: application/json`, accepts the same operations. Direct API submissions require an explicit UUID `id`; use a JSON `package` object instead of the CLI's `packageFile`. No CORS, cookies, remote bind, shell execution, arbitrary filesystem endpoint or server-side Lua execution endpoint is exposed. Workflows run inside each worker's existing bounded interpreter and native action handlers.

The service shares `HighwayCoordinator`, native workflow/geometry/resource policy, `QueuePolicy`, `TaskWire`, `TaskFiles`, history policy, `SwarmConnection` and `CrewListener` with in-game hosting. Restarted jobs enter **Inspection required**; ordinary tasks can Resume explicitly, while interrupted native highway executions follow the inspection/end procedure above. Missing previously executed worker journals are not automatically replayed. Keep the same crew keys across restarts so workers recognize their saved tasks' owner. Unknown executions are never adopted.

Ctrl+C disconnects workers, which enter their existing connection-loss cleanup. It does not cancel or erase tasks. A journal failure stops dispatch and closes connections; repair the data/storage problem before restarting. Completed history can be removed; never delete active recovery journals to make a stuck action look complete.

## Verification and next stage

`./gradlew :host-service:hostServiceCheck` runs real authenticated socket connections, package frames, localhost HTTP auth/origin checks, cross-crew isolation, priorities, pause/resume/cancel, offline cancellation, idempotency, ownership locks and restart/missing-journal scenarios. Native tests cover preparation, worker verification authority, detached restocking while another worker advances, rejoining, completion and offline cancellation receipts. Minecraft behavior is simulated; real movement/inventory testing is still required. Root `build` runs service, core and existing client checks. No service is installed or left running automatically.

Next: live highway testing and monitoring through this API, then individual priority handoff/return orchestration and the remaining native job types. Launcher UI comes after service behavior is tested.
