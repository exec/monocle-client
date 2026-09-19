# West highway: stale pickaxe receipt and supply-site clearance stall

Captured before repair on 2026-09-13 at 15:50 America/Chicago.

- Client/host version: 0.7.27.
- Task: `4729cfb7-99d7-4f71-8bb7-f61d400e6886`.
- Native execution: `40877174-df54-4b54-929f-c886c0d46210`.
- Geometry: westbound from X -8230, Y116, Z0 to X -20000; 5 wide, 3 high, railings; Lanes.
- Paving target: 512. Verified progress: 1402; next front X -9632.
- All three workers detached for supplies; active builder roster empty.
- Shared supply owner: Unbans. No worker reports an owned/placed container, pending pickup, pending block ACK, or cursor item.

## Observations

UnbansBot2, X -9620:

- `Crew supplies: waiting for a donor of pickaxes`.
- Exchange `1619dd50-11ab-416c-8ea6-ff89e3ea5f66`, sequence 0, stage `uncertain`.
- `An older item drop needs inspection; refusing a duplicate transfer`.
- Current exchange reports no issued drop. This does **not** prove that the older receipt has no issued drop.
- One loose pickaxe, no known carried pickaxe-shulker stock; 261 loose paving blocks.
- Decision unchanged approximately 205 seconds; inventory-exchange gate bypasses normal native progress watchdog.

UnbansBot, X -9627:

- Same uncertain exchange; alternates `Crew transfer uncertain` and `Waiting for crew synchronization` approximately every 6/12 seconds.
- Two loose pickaxes, eleven known carried pickaxes in shulkers, 481 loose paving blocks.
- Native pickaxe restock minimum 6, exceeding job target 3; likely gathering donor stock, not confirmed from this snapshot.
- InventoryScreen open at capture; movement input still forward, but actual velocity zero.

Unbans, X -9629:

- `Waiting for nearby players to clear the supply site`.
- Owns shared reservation but has not placed a container.
- Zero loose paving blocks, 2374 carried paving blocks, two loose and twenty carried pickaxes.
- Supply pickup center X -9628; Bot1 is only about two blocks away.
- Repeated travel/staging/clearance decisions, without a successful supply placement.

## Root-cause candidates to follow

1. Durable prior exchange receipt prevents new pickaxe transfer; transient exchange retries never reconcile the old receipt.
2. Donor inventory exchange blocks native travel/restock and leaves donor near another worker's supply site.
3. Pickup-clearance reservation blocks the final supplier while every member is detached, leaving nobody to move the work front.
4. Status churn resets superficial timers while the effective action remains blocked. Gate-level no-progress handling is needed.

Do not replay an uncertain item drop or declare recovery confirmed from telemetry alone. Physical drop inspection was requested before retiring that receipt. Live host telemetry remains in `highway-7a1920d6-1156-3bc0-9a60-135aefe8bc67.json.telemetry.jsonl` under the host data directory.

## Live repair

- Cancelled the old task and ended its native execution; all three acknowledged cleanup, with no owned container reported.
- Preserved uncertain receipts; no drop was replayed or declared recovered.
- Restarted Unbans and UnbansBot at verified X -9632 with unchanged 512 target, Y116, westbound to -20000.
- Replacement task: `181a5e24-513c-4140-92f2-530a3c6034e2`.
- UnbansBot2 temporarily excluded because it has only one pickaxe and no known carried pickaxe stock; awaiting operator inspection before reconciling its old receipt.
- Backed up host task history and deleted one completed record to free a task slot.
