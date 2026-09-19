# Experimental shared-width highway crews — 0.3.15

Legacy guide for 0.3.x. Current builds use **Right Shift → Workers** and **`.worker`** (`.bot` remains an alias); see the [Workers control-room guide](bots.md) for multi-crew management and host-side recovery.

Swarm coordinates one road job with players working side by side across its width. Everyone shares the same road origin and length, but keeps a distinct walking lane. Each floor column belongs to the nearest walking lane; equidistant seam columns alternate between the neighboring workers by row. On a five-wide road with two players, the left pair and right pair stay assigned while the middle column alternates. Players do not swap sides. Railings/supports outside the road belong to the edge workers. Workers need Monocle running and Swarm connected, but do not manually enable Highway Builder.

## Setup

1. Install this build on every participating account. Configure the host's Highway Builder layout but leave Highway Builder stopped. Choose North, East, South or West (Facing is resolved when assigning). Diagonals are intentionally unsupported in this first crew build.
2. Enable Swarm on the host, choose Host, and use **Generate and copy new crew key**. Paste that same private key into each worker's Swarm **crew-key** setting. Workers choose Worker, the host IP and matching port (default 6969). **Accept highway assignments** is on by default; joining this trusted host grants that authority.
3. For clients on one computer, keep **bind-address = 127.0.0.1** and worker IP **localhost**, port **6969**. For multiple computers, set bind-address to the host computer's specific private LAN IP and use that IP on workers. Press **Start host** on the host. Simply enable Swarm in **Worker** mode on each worker; workers have no Start/Stop buttons. With the shared key configured, they connect automatically and retry failed/lost connections after 1, 2, 4, 8, then at most 10 seconds between attempts. Disable worker Swarm to stop retrying. The settings panel shows connection/authentication status and the last error. The host announces each authenticated worker's name when it identifies itself. There is no public listener, port forwarding, Cloudflare or web UI in this build.
4. Join the same Minecraft server using the same address string and the same dimension. Leave worker Highway Builders stopped. Existing jobs or unresolved recovery records block assignment.
5. Stand the host at the normal Highway Builder road center, then run `.swarm highway start 128` (**128 total shared road blocks**), or use **Start shared-width highway crew (host)** with the crew-road-length setting. Limits: 16–4096 total blocks, 2–5 players including the host, and no more players than floor columns.
6. Each client reports a lane start at that same cross-section. Chat and the Minecraft pause menu may stay open on every client; close inventory/container and other screens. Nearby clients automatically walk to their assigned block centers, and the host starts building when everyone is ready. On a five-wide road with two players, the host takes block 2 and the worker block 4, counting from the left while facing progress. Start within eight blocks of your lane target on the same level, on a clear supported platform. Setup does not dig, jump gaps or change levels. If a route is blocked, move closer manually or clear it; `.swarm highway status` shows each target and waiting reason. The crew Pause command/button, leave and disconnect release positioning controls; opening the Minecraft pause menu does not. After another feature takes movement control, stop that feature and have the host resume the crew.

Workers mine and pave their owned columns, including speculative rows ahead. The host authorizes the next row after every participant reports its portion verified. A waiting worker does not skip the other player's columns or race ahead. This synchronization favors correctness over maximum speed in the first shared-width build. It can wait for a slow or restocking member. Cross-width liquid checks keep excavation from proceeding while a neighboring lane still needs its passage plugged.

Shared settings: operation, resolved direction, width, height, floor policy, railings/supports, mining above railings, building-material list and assigned length. Worker placement/mining rates, reach, inventory reserves, supply choices and safety settings remain local. The original layout settings are retained for restoration and profile saves serialize the originals, not the temporary host layout. Editing the layout mid-job triggers the existing builder pause; leave and prepare a new crew instead of changing geometry independently.

## Commands

- `.swarm highway status`: local lane start, common job phase, permitted row and host's worker reports.
- `.swarm highway pause`: host pauses the entire crew.
- `.swarm highway resume`: host requests resumption while the job remains connected. Existing local safety/readiness checks still apply. This does not override a lost connection.
- `.swarm highway leave`: **local, explicit abandonment**. Stops the assigned builder, restores its original layout, clears its recovery record and releases local crew guards. Inspect/recover all supplies first. Every member must leave separately; the host leaving does not silently abandon workers' containers.

Completing the common road length does not start another job. Inspect the result, then leave on each client before preparing another. Workers can still access their local settings and stop their builder; the host does not lock out the user.

## Supplies and boundaries

The first implementation serializes restocking across the crew. Before a grant, peers stop mining/placing, resolve outstanding predictions and acknowledge the reservation. Nearby active builders try safe one-block walking steps away from the site, bounded to their local work area. The owner backsteps one block on safe footing before restocking. If there is nowhere safe to move, the crew waits; it does not manufacture footing under another player or walk off the highway. A paused/completed worker may need to be moved manually using the existing controls.

The reservation protects a bounded area around the work position, including the shulker/chest, double-chest pair and temporary support/protection blocks. Non-owners' mining/placement interactions are blocked during the hold, including ordinary packets from other modules. No foreign container is deliberately mined. Recovery waits for other players to clear a generous pickup margin. Border liquid plugs also require a reservation when they extend outside the assigned paving rows. Required trailing paving is verified before section completion rather than simply retiring pending tail placements.

If a crew member accidentally collects the owner's tracked shulker, automatic return requires the owner's observed drop/pickup identity plus the collecting client's own pickup observation. A pre-existing identical box, multiple matching slots or a server-stacked box prevents automatic dropping. The matching borrowed box is protected from ordinary inventory clicks/drop commands while held. The recipient must have room before authorizing return; the collector must be nearby, grounded, with a supported, clear route between them. It faces the owner and makes one drop attempt, then waits for owner pickup. No blind repeated drops or arbitrary lookalike selection.

This cannot force an unrelated player or an unmodified client to return items, prevent the server from moving/deleting them, or guarantee that a nearby player never intercepts a return. Ambiguous/missing observations and rejected drops may need manual recovery; leave only after inspection. Avoid other movement/inventory automation during initial tests. Explicitly disabling Swarm/abandoning the job removes its packet guards; it is not a permanent world-protection system.

## Disconnects and security

Swarm remains enabled through Minecraft joins/leaves, and its transport also runs in the main menu. Workers reconnect after world transitions to refresh their identity; workers outside a world are marked unavailable for highway assignments. Host listeners remain running until stopped or Swarm is disabled. Connection attempts run off the game thread; `.swarm join` reports a request, not premature success. `.swarm disconnect` disables Worker mode's connection loop. Network reconnection does **not** resume an interrupted highway job: inspect supplies, explicitly leave the old assignment on each client, then prepare another.

LAN protocol v2 uses fresh session challenges and HMAC-SHA256 authentication of ordered messages, bounded queues and socket timeouts. Keys must be at least 24 characters; the generated key is random. Socket threads enqueue messages; game commands/state changes execute on the client thread. All accounts must use this build: 0.3.10 and old raw Swarm clients are incompatible. Inspect and explicitly leave any older crew job before starting this one.

This is **authentication, not encryption**. Coordinates and other traffic are readable on the LAN. Only share keys with trusted operators; ordinary Swarm remote commands remain available outside assigned jobs. Do not expose this service to the public internet. Use unique keys, and stop connections before rotating them.

An interrupted assigned builder pauses rather than falling back into standalone building. Each participating profile writes `monocle-client/swarm-crew-recovery.json` beneath its game directory with its assignment and latest reserved supply position. It blocks new assignments after restart until explicit local leave. The record is for inspection, not automatic job/container recovery. No timeout grants an abandoned section to someone else, and AutoLog/disconnects are never automatically undone. Use a separate profile/data folder for each worker process.

## Verification and live test checklist

`./gradlew swarmCrewCheck` exercises all supported width/crew-count combinations, stable distinct walking lanes, alternating seam ownership, edge ownership, longitudinal limits, acknowledgment barriers, standalone no-op and ambiguous-inventory policies; actual authenticated bidirectional loopback sockets; ordered bursts; mismatched keys; message bounds and disconnects. It also checks bounded retry delays, the actual worker's localhost-to-IPv4 connection, refused-connection diagnostics, a later successful attempt, authentication before reporting success, and cancellation. Existing highway geometry/supply checks also run in `./gradlew check`.

Live Minecraft multi-account behavior is not proven by those tests. Start with two accounts and short sections on safe existing ground, with inexpensive uniquely named shulkers:

- Host-only versus connected-but-unassigned building: unchanged behavior.
- Enable a worker before starting the host; verify automatic connection and one host join notification. Stop/restart the host, try a wrong key then correct it, and leave/rejoin Minecraft. Verify retries/status and that interrupted jobs remain paused.
- Side-by-side readiness/start, alternating middle-column ownership, slow/missing member holds, common row advancement and final trailing verification.
- Automatic nearby lane positioning: blocks 2/4, closed versus open screens, unsupported/blocked routes, wrong level or too far away, manual relocation, and pause/disconnect during movement. Verify no digging, jumping or movement after leaving.
- Two restock requests together, nearby yielding, no safe yielding route and double-chest recovery.
- Deliberately intercept a shulker: unique inventory arrival versus a pre-existing identical box; full recipient inventory; failed server drop and a third player intercepting it.
- Pause or disconnect each participant before grant, after placement, during breaking, after pickup and during return. Confirm no section is silently reassigned and inspect the recovery record.
- Finish/stop, explicitly leave on every client, and verify original layout settings return.
