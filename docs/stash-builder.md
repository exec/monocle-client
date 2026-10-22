# Stash Builder — experimental native construction

`stash-builder` in World builds a Java-generated copy of Main's diagonal double-chest/hopper layout. It does not require Litematica or an external printer and does not connect to or alter another person's bot. `StashLayout.generate` is the deterministic Java layout generator; `StashBuilder` supplies client placement and optional local flight.

## Layout and budget

The origin is the **left half of the first bottom double chest**, viewed facing the same direction as the chest fronts. Columns extend clockwise from the chest-facing direction; each higher row moves one block backward and one up. Two horizontal hoppers below each upper chest feed the double chest one level lower and one block forward. The top row has no unnecessary hoppers behind it. Rows snake across the bank rather than flying back to the same end every time.

For `C` columns and `R` rows:

- Chests: `2 × C × R` (one item for each half).
- Hoppers: `2 × C × (R − 1)`.
- Chest capacity: `54 × C × R` slots, excluding hopper capacity.
- Footprint: `2C` blocks across, `R` blocks deep, `R` blocks high. No walls, floor, decoration or stock are included.

Main's regular bank is 128 columns × 19 rows: **4,864 chests, 4,608 hoppers, 2,432 double chests, 131,328 chest slots**. The catalog has one extra hopper beyond this regular pattern; the generator intentionally does not reproduce stray blocks.

## First use

1. Test a small 2-column × 2-row bank in an empty area. Carry eight chests and four hoppers.
2. Set `origin`, `chest-facing`, `columns`, and `rows`. The **Set origin 3 blocks ahead / face toward me** button captures a convenient origin and facing without using the player's standing block as a placement target. The default is a small 8 × 8 bank, not a full Main replica.
3. Enable the module. With default `autopilot=false`, walk/fly within reach yourself. This avoids starting flight unexpectedly. With autopilot enabled, equip a healthy elytra, select Vanilla ElytraFly, and start near the origin. Existing `PrinterFlight` local routing and collision checks guide short construction moves; there is no long-range travel to the site.
4. Keep chest/hopper supplies in carried inventory. The module moves them into the hotbar as needed and pauses for more when exhausted. **This first prototype does not unpack shulkers or make stash/home supply runs.**
5. Stop anytime. Re-enable with the same origin and dimensions to recheck/reuse existing correct blocks. A final pass requires all generated states, including paired chest halves and hopper directions, to match before reporting complete. Load earlier rows again if they are outside render distance.

The cap is two placement attempts per tick, not a guaranteed measured speed. The client batches under one facing rotation, sneaks for container interactions, attaches the second half to the intended single chest, and clicks the receiving chest to orient a hopper. Server block updates/acknowledgments drive progress; predictions alone cannot finish a build. Repeated rejected placements stop for inspection. Unsupported/air placement depends on the server: this is intended for testing on the permissive building server already in use, not a universal claim that vanilla accepts unsupported placement.

## Boundaries

The builder never mines, clears terrain, replaces wrongly oriented containers, discards inventory, teleports or spawns items. Conflicting native jobs, highway/printer/pathing controllers, low health, world changes and unhealthy elytra stop it. Inventory rearrangers and Scaffold yield while it is active. Eating is allowed to interrupt building. ElytraFly remains enabled on stop for manual control; the builder releases only its autopilot request.

No crew/RWP workflow, automatic stash definition, restock, or cross-session action journal is included. Recovery is additive world-state rechecking, not replay of inventory actions. This is a new native builder needing live validation of placement, pairing, flight and server rate limits before unattended use. Existing stash scan, transfer, and highway algorithms are not replaced.
