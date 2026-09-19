# Experimental Stash Manager · 0.7.68

Discovery only. Scanning never extracts items, discards inventory, mines containers,
or changes terrain. Highway supply logic is unchanged.

## Solo

1. Open **Stash Manager**, give the stash a name, and click **Select Stash · Wooden Pickaxe**.
2. Left-click and right-click the two corners with Schematic Selector's existing
   client-only wand. The inclusive cuboid should contain the entire stash.
3. Return to Stash Manager and click **Scan Selected Stash**.
4. Click **Refresh Saved Resource Totals** to inspect observed contents locally.

With **Export to connected host** enabled (the default), starting a solo scan also
publishes its name, bounds, server and dimension to the authenticated Workers host.
This exports no items and starts no remote work. Each read-only observation is also
copied to the host as the solo scan progresses. The host's **Stashes** page shows
the definition immediately; use **Scan with workers** to select any number of workers.
Disable the setting before starting a scan to keep that stash entirely local.

Install **Baritone for Minecraft 26.2** in the profile's `mods/` folder (required
for stash scans since 0.7.69; other modules do not require it). Both solo and bot
scans use Baritone to walk around obstacles and between floors. During navigation,
breaking, placing and inventory rearrangement are disabled; previous Baritone
settings are restored when navigation stops. Cancelling or pausing a scan stops
its path too. Containers without a read-only route remain unscanned.

The tested dependency is the unmodified MeteorDevelopment Baritone build
`26.2-20260813.152229-1` from its
[Maven repository](https://maven.meteordev.org/snapshots/meteordevelopment/baritone/26.2-SNAPSHOT/).
Baritone is by leijurv, Brady and its contributors, licensed under
[LGPL-3.0](https://github.com/MeteorDevelopment/baritone/blob/26.2/LICENSE).
Its [source repository](https://github.com/MeteorDevelopment/baritone/tree/26.2)
and [matching snapshot sources](https://maven.meteordev.org/snapshots/meteordevelopment/baritone/26.2-SNAPSHOT/baritone-26.2-20260813.152229-1-sources.jar)
remain available separately; Baritone is not embedded in the Monocle jar.

Keep a pickaxe or an empty slot in the hotbar: the scanner selects it when opening
a container, preventing a held block from being placed when an opening fails.
It restores the previous selected slot if you have not changed it yourself.

## Bot jobs and host console

Use matching 0.7.68 clients and host service. In the client-based host, open
**Workers → Jobs → Inspect stash · Experimental**. Bounds copy the current wand
selection. Choose one or several workers and queue the workflow.

For the standalone host, Stash Manager's **Copy Selected Bounds for Host Console**
button copies the JSON arguments. In the web UI, choose **New job → Inspect stash**,
paste those arguments, and select the workers. The saved **Inspect stash** workflow
is also callable from Lua using `bot.stash_scan(ctx.args)` or as a nested workflow.

```json
{"name":"Main stash","minX":0,"maxX":15,"minY":116,"maxY":120,"minZ":0,"maxZ":15}
```

Workers inspect deterministic, nonoverlapping sets of containers. Connected double
chests are counted once, including all slots supplied by the server. Ender chests
are excluded because their inventory is player-specific, not physical stash stock.
Hoppers are skipped in Lazy Mode. With Lazy Mode disabled they are scanned when they
are inside the selected cuboid. The scanner can send an
in-reach interaction directly through an adjacent chest, so it does not need to crawl
under diagonal storage. Hopper pathing stops anywhere within a two-block Baritone
goal radius instead of requiring an impossible adjacent walking block. Container screens are suppressed locally: the server menu is
When an adjacent chest has headroom, its top becomes the exact walking goal; each
scanned diagonal row therefore acts as the step used to reach the hopper behind it.
kept for two confirmation ticks, recorded, and closed without interrupting the user.

**Lazy Mode** is enabled by default. It visits the entire bottom storage layer, then
the top layer, then works downward. When both bookend layers are uniformly full,
homogeneous and identical, matching containers between them are estimated without
opening them. Any mixed, partial or mismatched bookend disables inference. Estimated
containers and totals remain labeled **inferred** because a manually misplaced kit
can make the assumption wrong. Disable `lazy-mode` for a fully observed audit.

The host's **Stashes** page shows item totals, container coordinates, observation
times, and shulker contents, dominant item, quantity, and mixed-kit classification.
Names and colors are metadata, not a source of resource classification.

Job, worker and crew inspection show live scan phases, current and movement targets,
wait reasons, last successful actions, opening attempts, telemetry age, and the last
64 diagnostic state changes. The in-game host's task report exposes these too.
Workers report state changes immediately and heartbeat once per second; the web UI
polls once per second while visible. Network loss cannot provide instant telemetry:
stale timestamps make that visible instead.

Each result is written locally, sent with a delivery ID, and acknowledged only after
the host saves it. Retries do not add counts twice. Resume checkpoints preserve the
cursor and outstanding observation; host pause/cancel uses the existing job controls.

## Prototype limits

- Only received chunks can be inspected. Missing chunks are reported, not counted as empty.
- Walking uses read-only Baritone navigation; blocked/buried containers and unsupported
  menus are marked **unscanned** after bounded opening/navigation retries.
- Selections are limited to 1,048,576 blocks and 4,096 containers. Discovery examines
  at most 512 blocks per tick; one result can await host acknowledgement at a time.
- Counts are observations, not guaranteed available stock. Concurrent players can
  change them; scan again before relying on them. Unknown shulker contents remain unknown.
- No `/home`, teleport acceptance, extraction or delivery yet. Those can build on
  this verified discovery workflow without rewriting highway restocking.

Local observations live in the profile's `monocle-client/stashes/` directory;
standalone-host observations live under its data directory's `stashes/` folder.
They are independent of job-history cleanup and isolated by crew, server and dimension.
