# Worker personal supplies — first stage (0.14.14)

Stash scans, withdrawals and deposits share the native executor, so this maintenance path works for host jobs and solo Stash Manager scans. Highway Builder keeps its existing Auto Mend handoff. Built-in stash workflows on either host now enable Auto Mend with a 30% start threshold, 95% target and auto-disable off. Saved/custom packages and running jobs retain their own module settings.

## How to test

Bring a Mending elytra, loose food, and loose experience bottles or a **personal, pure XP shulker**. For sustained food supply, bring a **personal shulker containing only enchanted golden apples**. Food must be allowed by Auto Eat, including named food if applicable. Mixed PvP kits are not treated as consumable supply boxes; matching shipment cargo is protected.

When the equipped elytra reaches Auto Mend's start threshold, stash movement stops at an inventory-safe boundary, uses the existing supported landing logic, and yields to Auto Mend. Bottles outside the hotbar are swapped into it without dropping the previous item. Strict Auto Totem keeps the offhand. Once the repair target is reached, the original action and navigation resume. Eating, pending kit clicks, held cursors, unsaved scans and host column acknowledgements retain priority.

At eight or fewer allowed food items, the executor replenishes enchanted golden apples. Missing loose XP during a repair uses the same cache path. It reuses a saved, loaded cache within 32 blocks, or places a carried pure supply shulker on a clear supported site within reach. Cache placement excludes the player's body and checks other placement-blocking entities. It opens the box and takes a stack with a normal server-confirmed shift-click; an uncertain click is never replayed. It does **not** break these boxes afterward.

## Persistence and diagnostics

`monocle-client/worker-supply-caches.json` is separate from job checkpoints. An atomic placement intent is saved before sending its packet, followed by server confirmation. Multiple sites are retained, scoped to player UUID, server/world and dimension. A restart/new job re-observes the actual block and contents rather than assuming it is still stocked. Unloaded and distant caches are retained, not pursued at stale coordinates. Empty/missing confirmed caches are invalidated. Unconfirmed placement records remain available for inspection.

Native action checkpoints expose `personalSupplies` (resource, target, detail, pending withdrawal) and `stashMending` for host debugging. Dead workers explicitly await respawn rather than being diagnosed as navigation stalls. Personal supply travel has a 600-active-tick deadline; a repair with no durability improvement for 200 ticks fails visibly rather than burning XP indefinitely.

## Limits of this stage

This is not yet a general `/home` consumable resupply run. It opens personal carried shulkers and nearby persistent caches; it does not take a replacement box from catalogued stash stock, provision an ender chest, share another worker's cache, or travel to a distant cache automatically. Cache locations are local to the client profile, not a host-wide provisioning catalog. Carry food/XP ahead of time, and leave room for consumables: cargo is never discarded to make space. This prototype needs live 6b6t validation before unattended use.
