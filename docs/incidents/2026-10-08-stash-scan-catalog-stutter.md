# Tango2 scan progressively stuttered as its catalog grew

## Evidence

The operator reported progressive freezes during full, non-lazy scan job `4945532e-3652-48bd-9bee-5c1aa54759c0`, run `a1ae2861-a1ba-4da4-b12f-1c56ca00ada5`, on Unbans 0.13.52. At the start of the fix it had read 1,955 containers, recorded five unscanned containers and no missing chunks, and discovered 129,110 of 229,680 selected cells (56.2%). That is discovery progress, not a known final container count.

Earlier readings showed approximately 0.8 containers/sec, a 14 MB local tango2 catalog and a 1.7 MB worker checkpoint. The worker remained connected with roughly 53 ms ping and fresh server ticks. An earlier Baritone path-search log burst was not continuing. No new message rejection or exception was observed in the sampled scan logs. Frame-time profiling was not performed, so the exact share of visible lag attributable to each component is not established.

## Cause and change

`BotStashScan.save` synchronously read and parsed the entire local catalog, replaced a coordinate observation, atomically rewrote/forced the full file, and computed a full unused summary on the game thread. The same file grew with every new container. The worker also rewrote its complete job checkpoint on scan display-phase changes, waited up to ten ticks before uploading a new observation, and checkpointed duplicate acknowledgements.

0.13.53 moves the local catalog work to a bounded single daemon writer. Immutable JSON copies cross the thread boundary; Minecraft objects never do. The game thread polls the future, closes the menu while saving, and only exposes a pending upload after the local write succeeds. Failed writes preserve the old file and fail visibly. Restarts during a write reobserve the checkpointed target; old checkpoint invariants remain intact. Shared catalog mutations are serialized to prevent lost definition/reservation updates.

Observation-only shared ingestion skips operator aggregates, benefiting both coordinator adapters. New observation counts still checkpoint before immediate upload; ordinary display changes use periodic status/checkpoints. Matching acknowledgement saves remain, while duplicate or stale scan receipts are ignored. Inventory moves and deposit permissions retain their original gates.

## Limits and verification

This first patch retains whole-file JSON storage and one outstanding host receipt. It does not implement the proposed pipelined upload queue; that requires independent persisted delivery cursors, bounded buffering, reconnect retries, cancelled-run delivery and final drainage. Neither the live scan nor the installed client/host was restarted during development.

Offline regression covers background lock contention without blocking the caller, immutable observation copies, failed-write preservation, legacy pending-observation restore, phase-versus-result checkpoint signatures and deposit dispatch independent of scan-only telemetry. Live performance validation awaits installation.

`botsCheck build` passed all 63 tasks. The prepared client artifact is `build/libs/monocle-v0.13.53-26.2.jar` (SHA-256 `8d91878d978a38e71816bc8a8ab428d2f7950133c02bacf61e370c68ba5fff92`). The installed worker remains 0.13.52 and the running host remains 0.13.51; neither was restarted. During the build the live scan reached 2,184 observed, six unscanned and 63.3% discovery with no missing chunks.
