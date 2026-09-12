# Highway efficiency test builds

These builds are cumulative. Install only one Monocle JAR per client, and use the same version on the host and every worker. Keep highway settings, crew size and terrain comparable; measure complete supply cycles as well as short paving runs. These are candidate optimizations, not measured speed claims.

## 0.6.2 — unchanged job saves

Job summaries no longer mark themselves dirty when unchanged. The shared task journal writer skips the temporary-file write, fsync and rename when the existing bytes are identical. It still serializes and compares the journal; no persistent cache can conceal a deleted or replaced file. Changed state retains the original synchronous durable checkpoint before acknowledgment/action.

Test: run, pause, resume and cancel native highway jobs; include a supply stop and restart the clients to inspect recovery. Watch for fewer periodic stutters, not a different mining or placement pattern.

## 0.6.3 — reuse highway geometry

Adds immutable relative-coordinate reuse for excavation faces, paving shapes and liquid inlet boundaries. All cache keys include their relevant direction, dimensions and options. The valid settings bound memory independently of highway/session length. World reads, pending actions, placement order and server verification are unchanged.

Test: the same highway run, then change width/height/railings and try another heading. Lava sealing and ghost-block repair must behave exactly as before. This targets CPU/allocation overhead rather than faster server packets.

## 0.6.4 — reuse crew ownership decisions

Adds a small per-crew table for excavation/paving owners by column and row/height parity, and reuses parsed worker UUIDs. Inputs are checked before reuse, including in-place roster/duty changes and legacy workflow-duty defaults. Detach, return, reassignment and width changes invalidate immediately. No mining claims, work stealing, verification permissions or lane movement rules change.

Test: a three-bot crew in both sharing modes, a supply detach (3 → 2), return (2 → 3), and pause/cancel. Check that abandoned columns transfer immediately and nobody excavates another bot's paving.

## 0.6.5 — local timing diagnostics

Adds a collapsed **Timing breakdown (local job)** section and **Copy timings** button to the Highway Builder screen. Seconds and percentages cover excavation, paving/advance, verification gates, crew waits, supply handling, supply travel, sealing/recovery, entity/combat/eating, pauses and alignment/other. These are controller-phase observations, not a CPU profiler or exclusive resource utilization: speculative work can continue while a foreground gate waits.

Totals use a fixed array, not a growing sample history, and formatting updates once per second in this screen. Same-crew-job native restarts retain totals, with handoff downtime counted as unavailable; new jobs reset them. Stopped reports freeze. The counters are local and are not sent to the host or persisted to disk.

Test: complete a run including a supply round trip and use **Copy timings** on each client. Compare the breakdown with what you observed. Packet rates, inventory delays and mining assignments are unchanged.

Inventory batching and cross-lane work stealing are intentionally deferred until these isolated builds have been live-tested.
