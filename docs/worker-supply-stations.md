# Worker home supply stations

## Current implementation boundary

Host-only, durable station definitions and **read-only stocking proposals** are
implemented. Proposals do not submit jobs, teleport, withdraw, unpack, equip,
respawn, establish a bed spawn, or resume interrupted deliveries. The API returns
`executionSupported: false`; do not present it as automatic death recovery yet.
No client update or active-job interruption is required to design a station.

## Physical layout

Build a small safe **Overworld** home with one ordinary double chest, one bed,
and one server-supported double ender chest. Leave room to stand, open containers,
place/open shulkers, and take off. The fixtures must have distinct positions,
within 16 horizontal and 8 vertical blocks of the ordinary chest. Register the
block coordinate of one half of each double chest and one bed block; a later
live verification step must resolve the actual connected halves.

Set `/home <name>` for each worker separately. Set and verify its bed respawn
point in game; recording the bed coordinate does **not** establish a spawn point.
The End is permitted as a source stash dimension, never as the bed location.
Teleport success must be verified near the destination, not by requiring a
dimension change. Cooldown starts when teleportation actually completes.

The initial loadout is a netherite helmet, leggings and boots, one elytra instead
of a chestplate, one totem, 64 enchanted golden apples and 64 XP bottles.
Retaining one XP slot is preferred to repeatedly replacing elytras. Equipment
durability and enchantments (especially Mending) are **not** verified by aggregate
kit item counts and must be checked by the eventual native equipment action.
The ordinary chest is shared physical storage; ender chest contents remain
private to each account. Two workers opening the same ender chest do not see
each other's supplies. The server's actual menu size must be read, not assumed
to be vanilla's 27 slots or this server's 54-slot variant.

## Existing authenticated operator control API

These are private Monocle operations, not new RWP core actions. All three require
`crew` and the UUID `worker` of an existing worker in that crew. Offline workers
may be configured. Definitions are scoped by crew and worker, not by the job or
its current dimension. No credentials are included in the definition.

`worker-supply-get` returns the saved station and an optimistic `revision`.
An unset station returns only its revision.

`worker-supply-save` requires that revision as `expected` and a `station` object:

```json
{
  "server": "example.org",
  "dimension": "minecraft:overworld",
  "homeName": "bot-home",
  "homeWarmupTicks": 320,
  "homeCooldownTicks": 340,
  "chest": {"x": 0, "y": 64, "z": 0},
  "bed": {"x": 2, "y": 64, "z": 0},
  "enderChest": {"x": 4, "y": 64, "z": 0}
}
```

Home names are one safe command argument; no spaces or control characters.
Unknown settings are rejected rather than silently implying behavior.

`worker-supply-plan` requires `sets` (1–16 spare loadouts) and `sources`, an array
of 1–16 `{ "scope": "example.org\nminecraft:the_end", "name": "source" }`
references. Only these explicitly selected stashes are considered. Main is not
implicitly included, nor are another crew's resources. Sources may be in another
dimension on the same server. No target home is inferred from the source.

The planner uses canonical complete-kit counts and transfer-adjusted estimates
from the existing stash catalog. Incomplete kits are excluded. It scores actual
kit contents against remaining requirements, combines equipment/food/XP when a
mixed kit supplies them, and prefers fewer unrelated item types on a tie. It
never identifies a kit by its display name or color. This is a bounded greedy
proposal, not a claim of a globally optimal packing solution.

The response reports selected `kits` with source, scope, kit ID and quantity;
`suppliedItems` (including extras); unmet `missingItems`; `complete`; and
`stockUncertain`. At most 54 shulkers are proposed, respecting the double chest's
outer slot capacity. It does not assume the chest is currently empty or that
unpacked contents would fit. Estimated availability is not a withdrawal permit.

## Remaining execution work

1. A provisioning job must reuse confirmed kit withdrawals and deposits, reserve
   enough space, and protect already-present equipment and unrelated items.
2. Native personal restock must open the ordinary chest, handle shulker contents,
   take only loadout deficits, equip and verify gear, and return leftovers without
   losing track of cargo. Never dump valuables to force a rigid layout.
3. Maintenance must pause only at inventory-safe boundaries, persist its original
   task/destination, teleport or navigate to the home, restock/mend, then return.
   A full inventory must not permit an unverified withdrawal.
4. Death recovery needs explicit operator opt-in, verified respawn/lobby handling,
   bed/home reachability, fresh inventory reconciliation, and loss reporting.
   A historical cargo checkpoint is not evidence that cargo survived death.
5. Add WebUI setup, shortages, stock status and recovery controls before calling
   the feature operator-ready. Missing supplies or a blocked station must produce
   a clear failure/notification, not an infinite retry loop.

Existing local personal food/XP caches and Auto Mend remain unchanged. No death
recovery or new provisioning behavior is enabled by this implementation.
