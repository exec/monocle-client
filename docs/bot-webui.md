# Operator WebUI

## Whitelist kitbot and category columns (0.14.33)

Under **Kits**, open **Categories & whisper kitbot** to choose a source stash, seed/edit categories, select the courier, and configure exact username/UUID whitelist entries and a persistent requester cooldown. Requests use `/msg <courier> kit <category-slug> [count]`; quantity defaults to one and is capped at 36. Each eligible variant must have 250 complete directly observed kits after outstanding requests. Low-stock kits remain visible for management but are unavailable to kitbot. Incoming ready-world whispers are the only trigger; the standalone host queues the existing delivery-and-return workflow. Receipts show accepted/out-of-stock requests and job outcomes. Disable stops new requests; cancel already accepted deliveries in Jobs.

In 0.14.34 the whitelist editor has a cooldown field for each person. New users default to **one hour**; **0 disables their cooldown**, while the duplicate and in-flight delivery guards remain. Existing entries without overrides retain the shared fallback until edited. Saving settings does not clear request history or restart jobs.

In 0.14.35 **Maximum kits per request** is also configurable per person, from 1–36, default five. Requests above that person's cap get a limit reply and no delivery; they do not consume the personal cooldown. This setting is independent of cooldowns and does not limit operator-created jobs.

The stash column-map reservation dropdown also lists categories. Click one column or shift-click a range, then reserve it for a category. Contents rules exclude unrelated loose stock and incidental resources inside other kits. Updated workers receive the category rule in the host's deposit acknowledgement; older clients skip those columns. See [stash columns and kits](stash-kits.md#categories-and-whitelist-whispers-01433) for validation, stock and server-trust limits.

## Host-wide hostile warnings (0.14.4)

**Known hostiles** shows live hostile sightings with observer, crew, server and position. Warnings reach all updated native clients; the disconnect policy protects all clients on the matching Minecraft server, not only the one that saw the player. Unsupported clients are called out separately from clients with only cached local detection. Detected lobbies generate no player sightings/combat incidents or alarms. See [behavior and limits](known-hostiles.md).

## Discord owner notifications (0.14.3)

The **Notifications** page configures a host-private Discord bot token and owner user ID, enables failure/completion/safety DMs, sends a test, and shows delivery health. A blank token keeps the saved credential. Neither status nor job profiles export the token. Both standalone and in-game hosts use the same asynchronous notifier. See [setup, API and limits](discord-notifications.md).

## Known hostiles (0.14.1)

**0.14.2:** the separate **Incidents** page adds bounded, persistent player-sighting/combat/reconnect history with filters and paging. **Known hostiles** adds an opt-in safe reconnect policy, with a five-minute default wait and explicit unsupported-client warnings. See [timeline, safe reconnect and API details](known-hostiles.md#incident-timeline-0142). Update host and workers together; live server testing remains pending.

The **Known hostiles** sidebar page shows the host-shared server-scoped safety list, with manual add/remove controls and an opt-in disconnect policy. Updated workers report attributed attackers automatically, cache the list across reconnects and ignore sightings in a detected lobby. Crystal suspects are not automatically added. Connected clients missing support are explicitly marked unprotected. See [known-hostile controls, persistence and limits](known-hostiles.md).

## Attack and death evidence (0.14.0 — The Reckoning Update)

Open a worker's inspection to see its latest attack, death message and crystal evidence.
Server-reported attackers and inferred suspects are kept separate; neither establishes stash ownership.
Update both client and standalone host. See [evidence limits and logging](worker-combat-evidence.md).

## Alternating stash transfers (0.13.59)

In **New job → Transfer Stash Kits**, choose **Round Robin** and one worker.
Add 2–16 source/kit/destination routes, set the per-trip cap (up to 36), then
preview and dispatch. Each turn deposits one load before switching routes;
source cursors and destination reservations remain separate. Routes for the
same kit must share a destination so carried-kit recovery is unambiguous.
Normal **Drain one type at a time** remains the default. Update host and worker
to 0.13.59 and start a new job; existing captured jobs do not change.
See [route behavior and limits](stash-kits.md#round-robin-sources-and-destinations-01359).

## Job presets (0.8.3)

Open **Presets → Open preset library** to inspect captured packages, duplicate
built-ins, rename/move custom presets, or preview and save guided settings changes.
These change **future jobs only**; a running job keeps its own captured package.
Guided edits include before/after settings and reject stale previews.

In **New job**, select **Saved job preset**. The built-in **6b6t Highway Builder**
also remains a direct choice. Set the highway origin, direction and length, then
**Preview job → Dispatch reviewed job**. The preview captures the package; edits
to the preset afterward cannot silently change the reviewed dispatch.

Simple presets include **Follow crewmate**, **Travel to coordinates**, and **Wait**.
For Follow, choose a connected leader and select only the followers. The leader
receives no task and plays normally. Followers walk using live visible positions,
wait when the leader disappears, and do not mine, pave, teleport or enable combat.
Cancel the job to stop following. This first version does not follow through
portals or promise navigation around arbitrary terrain; test on clear ground.
The follow profile disables Speed, Elytra Fly, Kill Aura and Crystal Aura and
enables Auto Eat; unspecified modules/settings still inherit worker configuration.

Client hosts have the same library under **Workers → Workflows → Job presets**
and in the new-task screen. No launcher is required. Install 0.8.3 on followers
and use the matching host distribution; old workers cannot execute `bot.follow`.

Lua: `bot.follow({target="player-uuid", radius=3, ticks=0})`; zero duration runs
until cancelled. It uses the existing Travel action lifecycle and cancellation.
Future bodyguard behavior is not enabled by this preset.

The standalone Java host now bundles a dark-and-gold operator dashboard. No Node, frontend server, or browser extension is needed. It uses the existing host coordinator and job controls; the in-game Workers GUI remains available without this dashboard.

Job **Inspect** now shows the latest correlated command receipt and expandable per-worker acknowledgement/outcome evidence. A command accepted or queued by the host is not yet worker success; cancellation can be authoritative while cleanup acknowledgements remain pending. Worker **Manage** shows passive world readiness and its reason independently of Lobby Skip activation. See [command receipt semantics and compatibility](workers-api-v1/phase3-operator.md).

### Worker and crew management (0.8.4)

Open **Manage** on a crew or worker. Start work from a saved preset, inspect its
captured configuration, or use **Pause job**, **Resume job**, and **Cancel job**.
Those three controls affect the entire job, even from a single worker's view.
**Detach worker** parks only that worker's execution at a safe checkpoint;
**Rejoin worker** requests participation again. Whole-job Resume deliberately
does not clear individual detach decisions. Detached is operator intent; the
reported execution state shows whether cleanup has actually finished.

Native highway detachment uses safe lane withdrawal, not forced removal. It
requires an independent running highway, connected on-site workers, no competing
supply/handoff, and another worker covering the remaining required duties.
A participating in-game host stays as the site anchor. If those checks fail,
the control explains the blocker; use whole-job Pause when appropriate. A
standalone-host rejoin waits until the worker is on the highway floor within
16 blocks along each horizontal axis of the current front. Bring it back or
complete a separate travel task first. Cancellation remains final and is
reconciled with offline workers when they reconnect.

Preset launch shortcuts are available directly in the library and management
views. Common launch preferences last for this UI session only; new origins
are taken from fresh worker observations (or the in-game host's current position)
and must be reviewed. Inspection refreshes keep unfinished edits, expanded
sections, selections and scroll. Operational labels update live; explicitly
refresh timestamped configuration snapshots when a new reading is needed.

## Open it

Update the standalone host distribution between jobs, keeping the existing protected data directory, crew keys and journals. Start it normally, then open **http://127.0.0.1:6970/ui/** on the host machine (or the API port configured in `host-config.json`). Enter the **API token**, not a worker crew key. The token grants full host administration; do not share it with workers or clanmates.

The token is remembered in this tab's session storage across reloads. Disconnect console clears it; it is not stored in cookies, URLs, or persistent local storage. The read-only demo works without connecting to any workers.

## Stash management desk

**Stashes** contains two section tabs: **Stashes** and the all-stash **Kits** catalog.
The Stashes tab is a searchable directory of links, each showing storage fullness.
Click a stash for its own bookmarkable page; **All stashes** and browser Back return
to the directory. Its **Columns** sub-tab opens by default, beside **Kits** (stock,
direct delivery, incomplete-kit removal) and **Resources** (that stash's observed
item totals). Global worker metrics are hidden here to leave room for storage
management. Ordinary polling preserves the stash, sub-tab, column and map scroll.

Fullness measures occupied **chest/barrel slots**, not the item quantities inside
shulkers; even a partial stack occupies one slot. Hoppers are excluded. Updated
clients record exact slot capacity and occupancy during scans and kit deposits.
Older catalogs show an explicit estimate assuming double chests and counting
observed shulker slots; unknown loose-item stack counts, changed containers and
inferred contents produce an **At least** reading, not a claim of empty space.
Only recorded storage is included: a column scan cannot establish the capacity
of undiscovered upper containers. Counts are timestamped observations, not live
inventory guarantees; a fresh full scan on the updated client removes legacy
capacity assumptions.

The map reads recorded chest observations on demand, at most once per five
seconds while the catalog changes. Reading it does not scan Minecraft or move
workers. Kit colors are stable for
the same name/color/exemplar; hover for names, coordinates, recorded chest heights
and uncertainty. Click to pin details and open the existing kit/delivery modal.
Click one column, then **Shift-click** another to select every recorded column
between them in map order (in either direction). Choose **Column reservation kit
type** and **Reserve selected columns**; **Clear selected reservations** returns
the selection to automatic allocation. The picker includes kit types known in
this crew and world, even if this destination has none yet. Selection and kit
choice survive polling. An uncertain save offers an exact retry, not another write.

Manual reservations persist across jobs and restarts and take priority when a
transfer chooses its destination column. The whole range is validated atomically:
another kit's observed stock or loose items reject the range without a partial
save, and a column currently leased for a worker's deposit cannot be changed.
Unobserved stock is still unknown; the worker must confirm live contents before
depositing, and a later conflict blocks rather than silently overwrites a manual
reservation. No inventory is rearranged by these controls. Update the host for
the UI, and clients to **0.13.51** for stashes whose recorded bottom chest row is
above their selected minimum Y (such as `main`). Layout overrides remain display
heuristics; only actual recorded bottom-row chest anchors can be reserved.
Empty recorded columns are outlined, unknown/changed contents hatched, mixed
contents striped, inferred data dashed, and reservation conflicts red. Upper
unobserved stock is never asserted empty. Hoppers and loose placed shulker boxes
are not shown as chest columns.

Auto layout tests vertical and one-block-per-height stepped arrangements against
the lowest recorded chest row. This is a **display heuristic**, not verified
geometry; use the layout dropdown to override it for +X/-X/+Z/-Z steps or vertical
storage. Coordinate gaps are compressed and unseen positions are not fabricated.
**Fit columns** gives a compact overview; **Larger columns** provides wider
interactive cells. Large maps scroll inside the panel, including on mobile.
Kit search and the stash filter remain separate from the delivery executor picker.

## MVP controls

- **Kits** is an all-stashes catalog, searchable by kit name, contained items, or stash name. Click a kit for its fullest observed contents and source stash names, server/dimension, bounds, and on-demand chest coordinates. Matching name/color with different exemplars remains separate. Global kit and resource totals deduplicate matching container coordinates within a server/dimension across stash names and crews. The latest non-inferred record wins, including an empty observation or a withdrawal/deposit invalidation; estimates cannot replace measured stock. Individual stash views retain their own saved counts. Counts remain observations, not live availability.
- **Deliver** opens the same compact modal from Kits or the existing stash kit tables: choose a source stash, the connected worker running the job, 1–36 shulkers, and a recipient username. UUID lookup is automatic (connected Monocle roster first, otherwise Mojang's Java-account profile service). No job wizard is needed. This submits the existing Kit Delivery workflow at priority 100, preserves stash /home routing/warmup/cooldown, and waits for physical TPA arrival before dropping items. The recipient must accept; host Auto TPY remains restricted to eligible crewmates. Incomplete kits are opt-in. Unknown submission outcomes retain the exact job payload and idempotency key for **Retry same delivery**.
- Whitelisted kitbot users can whisper `kits` or `kits <page>` for raw stocked-kit names and category labels without creating a job or consuming their delivery cooldown. Automated replies and operator DMs are queued at one per five seconds per worker; replies expire after two minutes and do not survive a host restart. Public operator chat keeps its existing throttle.
- Overview and Crews show connection health, native highway progress, supply recovery and exchange state.
- Click a worker name or Manage to open its management desk: resources, live game chat, new work, assigned job actions, worker priority and live module configuration. Click a crew name or Manage for combined resources, crew chat, all assigned jobs, renaming/deletion and membership management. Job pause/resume/cancel still affects the whole selected job, not just the worker whose panel is open.
- Crews can be created directly. Moving an idle worker into a crew with a running highway carries that job through its authenticated reconnect and late admission. Active source jobs and unfinished cleanup still cannot be orphaned.
- Live native highway jobs can be opened or closed for joining. Connected workers discover open crews in their in-game Workers tab; discovery never exposes crew keys.
- Jobs support pause, resume, cancellation and global or worker-specific priorities. Terminal jobs live separately in History; deletion respects outstanding recovery/cleanup obligations.
- New Job accepts an exported `.bot export-workflow <workflow-id>` JSON package, a simple wait workflow, or custom Lua plus JSON arguments. Select workers in one crew and matching server/dimension. Review uploaded gameplay profiles before dispatching.
- Overview exposes the host-owned **Auto TPY** policy. It accepts only an observed exact `/tpa <username>` whose unique recipient is a fresh, online member of the same authenticated crew and server, after a 10-tick delay. Workers cannot enable it.

Status polls once per second, slowing to five seconds in a hidden tab. Stale snapshots disable writes. If a submission response is lost, **Retry same submission** reuses the identical payload and UUID; it never blindly creates a second job. Cancellation goes through the existing durable host controls, not a browser-only state change.

Chat requires 0.7.54 workers and is independent of an active job. The latest 256 lines are retained globally in host memory, filtered by worker or crew; restart clears the feed. Message bodies are plain text (Minecraft component colors are not preserved); timestamps, sender labels, sent messages and errors use dashboard colors. Slash commands go to the Minecraft server. Crew sending explicitly confirms broadcast through every connected member. Delivery is limited to one command per second per worker, never queued for offline workers, and never automatically retried. A sent receipt confirms submission by the client, not acceptance by the Minecraft server. Uncertain delivery should be inspected before resending. The in-game host uses the same authenticated messages via Manage & chat, showing the latest 40 matching lines. Both hosts remain supported; neither requires the launcher.

Live configuration uses the existing configure command, scoped to a worker or all runs in the selected job. JSON maps module IDs to `{"active":true,"settings":"{}"}` (settings are SNBT strings). Job-owned executors remain protected; client configuration acknowledgements/errors are shown in the panel. This dashboard does not yet provide a visual workflow editor, account launching or permission-scoped sharing.

## Optional remote access

The API still binds only to loopback. Do not expose it directly or port-forward it. For an operator-managed HTTPS reverse proxy, set `"uiOrigin": "https://bots.example.com"` in the protected host config, restart between jobs, and proxy `/ui` and `/ui/*` unchanged to `127.0.0.1:6970`. Preserve the original `Host` and `Origin` headers and the returned security headers. The configured HTTPS origin must match exactly; wildcard origins, insecure remote HTTP and cross-site requests are rejected. Protect access and rate-limit at the perimeter. This is full operator access, not the future shared-bot permission model.

The browser uses only `/ui/api/status` and `/ui/api/control`. Native `/v1/status`, `/v1/control` and legacy `/control` retain their no-browser-Origin policy. Worker WebSockets remain separate; see [web transport](bot-web-transport.md).

The unmodified Glacial Indifference font is bundled with its OFL license and attribution notice under `/ui/GlacialIndifference-OFL.txt` and `/ui/GlacialIndifference-NOTICE.txt`.

## Development checks

Run `./gradlew :host-service:check` with Java 25. For a completely isolated demo host with simulated workers, run `./gradlew :host-service:webUiPreview`; it prints a temporary URL and fake test token. Stop it with Ctrl+C. Optional browser QA lives in `host-service/src/test/webui-browser-check.mjs` and uses an existing Playwright installation only as a development tool, not a runtime dependency.

## Job configuration inspection (0.8.0)

Open a job's **Inspect** view or a worker/crew's **Manage** view and expand
**Captured configuration & live requests**. Profiles, module activation/serialized
settings, native highway supply capabilities, and latest per-worker update receipts
load on demand. Regular polling leaves this inspector alone; use its explicit
refresh button to retrieve new receipts. Inspection never applies settings.

The authenticated `task-configuration` control operation accepts a task `id` and
returns `profiles`, `highways`, `updates`, and an explanation of their scope. Both
host implementations use the same report logic. Captured overlays are not live
readbacks: unspecified worker settings and subsequent workflow actions can differ.
