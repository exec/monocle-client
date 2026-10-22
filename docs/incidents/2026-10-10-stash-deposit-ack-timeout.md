# Completed deposit failed waiting for its host receipt

## Evidence

Unbans job `8391ae39-2ad5-4e9d-bd5a-5cd4e6e9b244`, run `528fad4c-6b4b-42b9-a8a3-7b522ea78b39`, collected chest/hopper kits at tango2 and successfully used `/home main`. The host recorded an intermediate `Storing kit 2/31` report, then a destination-column acknowledgment timeout at 08:29:21 UTC on 2026-10-10. That intermediate report was not the final deposited count.

The client checkpoint subsequently showed **31 confirmed deposits, zero missing kits, no unverified inventory move, delivery 5 and finding mode 2**. The host retained the delivery-4 approval. This was a final observation/acknowledgment failure, not failed `/home`, lost kits or an unsuccessful chest deposit. The later recovery run found no matching kits still carried.

The host logged 96 passive stash events during the surrounding 37-second window and repeatedly acknowledged the same pending deposit finding. The client's worker checkpoint was roughly 4.9 MB. `BotRuntime.acknowledgeStash` invoked the full checkpoint save even when `BotStashDeposit.acknowledge` had rejected a duplicate or stale receipt. `TaskFiles.write` can avoid a physical write when bytes match, but still serializes and compares the full journal. New findings also retried every ten game ticks, adding more duplicate acknowledgments and checkpoint work while the host handled large observer batches. The original response deadline was only 200 game ticks. No profiler captured the exact time split among catalog work, network queues and checkpoint serialization; these are identified pressure sources, not a measured attribution of every stalled millisecond.

## Correction

0.13.77 checks pending receipt identity before invoking the native acknowledgment or saving the checkpoint. Scan and deposit paths share the duplicate/stale guard. New findings still upload immediately, but repeated findings retry at most once per real second rather than depending on game tick rate.

The deposit keeps its pending observation and approval gate for at most 60 seconds of monotonic time. A delayed-response warning identifies stash, delivery, verified count and finding mode; a terminal timeout includes the delivery and verified count. Receiving the matching receipt resets the wait. Reconnect preserves the delivery and pending observation while starting a fresh bounded response window. This does not authorize unapproved deposits, blindly retry inventory clicks, or mark missing kits as delivered.

Offline checks cover new-versus-repeated uploads, stale/duplicate/future receipts, the longer bounded wait, late acceptance and reconnect retention. A fresh tango2-only job was started before editing code; it continues on the old client. Client installation and live heavy-catalog verification are still required. No host deployment or stash catalog edits are needed for this patch.
