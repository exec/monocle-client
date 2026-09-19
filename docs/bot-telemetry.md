# Bot telemetry (0.7.15)

## Visible-road prediction (0.7.55)

Workers report `roadPrediction` (`nextBlocks`, `blocksPerSecond`, `ageTicks`) in their existing status frames. The Workers/Crews dashboard and highway control screen display “Estimated 2.1 blocks/sec for next X blocks”. X stops at the first unloaded corridor chunk or the job endpoint; no chunks are requested and no whole-job ETA is inferred. Crew estimates use the slowest reported worker and shortest observed horizon.

Mining cost uses the highway's best carried tool and existing Minecraft break-delta helper (including enchantments, effects and ground/water penalties). Netherrack counts as zero mining cost. From 0.7.57, obsidian uses 55% of normal break time when double-mining is enabled and eligible, with the configured break delay added afterward. This is an observational approximation, not a simulated double-mining schedule. Movement speed, placement budget and observed TPS supply the other costs. This is advisory: supplies, entity delays, latency and mining/placement overlap can make actual speed differ; it never gates work.

New corridor chunks and crossing a chunk boundary invalidate the scan, coalesced to at most once per second. A five-second fallback refresh catches edits, unloads and tool/effect changes. Prefix totals make progress updates constant-time between scans; block-state costs are reused within each scan. Worker diagnostics expose `roadForecastScanMicros`, `roadForecastRows` and `roadForecastAgeTicks` for measuring real client cost.

Telemetry records observations, not proof of a bug or permission to change gameplay. Slow mining, eating, legitimate supply trips and manual pauses can all produce wait records. Nothing in this logger resumes, cancels, abandons resources or issues gameplay commands.

## Files and limits

Both host types use the same highway recorder. Each crew writes `<crew-journal-filename>.telemetry.jsonl` next to its recovery journal. The standalone host exposes its exact path in `status.highways.<crew>.telemetryPath`; it also writes `host-events.telemetry.jsonl` beside `host-tasks.json` for control requests, worker status changes, connection/world transitions and reported failures.

Each file rolls at 16 MiB, retaining the current file and three older generations (`.1` is newest). This is approximately 64 MiB per crew plus 64 MiB for host events, not permanent job history. A shared daemon writer has a 64-record queue: a full queue drops telemetry instead of blocking gameplay. Disk failures trigger a one-minute retry backoff. `telemetryError` and `telemetryDropped` in host status expose loss; crew records also carry dropped-record counts. A process crash can lose the queued tail. Recovery journals are completely separate and are never rotated by telemetry.

## What gets captured

- The host samples at most four times per second. It retains twelve preceding samples (roughly three seconds) in memory. One second without verified road progress, or a worker staying in the same reported block/row, emits `before-wait` records and `wait-observed`. Continuing waits receive periodic snapshots; movement resuming or the set of stationary workers changing produces another record. Healthy work gets state-change samples and periodic checkpoints.
- `sampling-gap` marks a host sampling interval of at least one second. This is an observation of the host loop, not an estimate of server TPS. Reports include host-measured receipt age and freshness; stale reports are retained as stale evidence, never treated as permission to advance.
- Crew context includes execution/catalog/generation, active workers and detached supply sites, handoff flags, outstanding requests, lock acknowledgments, recent command attempts, and per-worker last verification permits sent. A command attempt is not an acknowledgment. `windowDecision` explains report/roster/revision/world gates; its age distinguishes an old decision from one just evaluated.
- Workers capture the final Highway Builder gate, state and reason per tick, retaining the last eight changes. `traceSession` plus `decisionSequence` identifies repeats, restarts and gaps. These are observations of the executed branch, not guessed causes. Snapshots refresh up to four times per second and retain the existing 8 KiB UTF-8 wire limit, trimming older history first. `diagnosticsTruncated` reports trimming.
- Worker detail includes focus/screens, input ownership, hunger/use/combat, tick and server-tick ages, ping, mining progress and stop intent, pending block sequences, supply type/minimum/counts/timers/recovery state, inventory deadlines, travel target and received crew verification state.

The existing hello cadence is unchanged; a cached snapshot can appear in multiple reports. Fast transitions beyond the eight-entry worker history, waits shorter than one second and intervals where the client cannot tick are not guaranteed to be captured. Larger history or packet traces should be added only if a concrete failure requires them.

## Reading a freeze

Worker diagnostics include `travelBlocker`: the most recent rejected travel
footing/clearance coordinate and block state (or an unresolved state). Body,
entity, loading and bounds failures report the rejected swept box. This is a
navigation diagnostic, not proof that a physical obstacle exists: pending server
verification can also reject a route.

Start with `wait-observed`, correlate execution and worker IDs, and read its preceding snapshots through `movement-resumed` or `stationary-workers-changed`. Compare:

1. Host `lastPermitSent` with worker `diagnostics.crew` (base, mask, limit, permit age). A missing roster/revision ACK is different from a server-unverified row.
2. `diagnostics.decisions`, restock resource/minimum, inventory quantity vectors and supply timers. These distinguish a repeated resupply request from a slow container interaction.
3. Mining progress/stop intent and pending block sequences. A stationary bot steadily mining obsidian is not a movement deadlock.
4. Worker tick age, host report age, world/focus/input ownership, and host sampling gaps. Worker and host wall clocks need not agree; use each side's monotonic durations and sequence IDs.

Logs include names, server/dimension, coordinates and inventory quantities: treat them as private. The recorder selects diagnostic fields and does not dump workflow scripts, profiles, credentials, raw packets or general chat. Status/reason text may originate in custom workflows, so review logs before sharing publicly. Root cause still requires interpreting the evidence; the logger does not guarantee automatic diagnosis.

Update both host and workers for the full detail. Older hosts accept these bounded snapshots but do not persist the new host timeline. Schedule the standalone host restart between jobs or explicitly recover the interrupted job afterward.
