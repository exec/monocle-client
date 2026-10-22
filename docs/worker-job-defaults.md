# Worker job defaults

From 0.14.11, `OperationsLibrary` supplies the same built-in profiles to the standalone host and the in-game Workers host. The default preset path does not require a worker to have copied an operator's personal module configuration. Profiles are overlays: named settings are applied for the job, unrelated settings remain local, and the worker's original profile is restored after safe cleanup.

## Safety and movement

Highways, stash scans/resupply, kit delivery/removal, solo/crew stash transfers, Travel, TPA, recovery, Follow and guards share:

- Auto Eat enabled, full-inventory food search, Baritone pause while eating, enchanted golden apples allowed, named food protection disabled. Ordinary golden apples and the existing unsafe/disruptive foods remain blacklisted.
- Auto Gap enabled with enchanted apples allowed, continuous eating off and the health trigger at 20 (including absorption).
- Auto Totem in Strict mode with zero slot delay; Auto Armor preserving elytras and keeping worn armor on when there is no replacement; Auto Tool and Velocity enabled.
- Enderman Look in Away mode, hostile staring/stunning disabled.
- Reach enabled with +1 block and no additional entity reach.
- Vanilla Elytra Fly, zero natural descent, acceleration enabled with step 1.5 and offset 0. Stash/travel flight ceiling is about 5.395 blocks/tick; vertical multiplier is about 1.803. These are ceilings, not promised actual speeds: native approach, obstacle, durability and landing limits still apply.

Ordinary stash, travel and recovery jobs leave Speed and combat auras off and configure Elytra Fly inactive so the native movement executor enables it only when needed. Follow retains its existing inactive movement/aura policy; guards activate their existing movement/combat policy, and Crystal Guard still arms auras only after finding its protected subject.

All built-in highway roles retain Speed 5.5, a horizontal flight ceiling of about 3.618 blocks/tick, break-ahead 3, pave-ahead 5, 10 breaks/placements per tick, and managed inventory with `keep-shulkers=false`, ender-chest search/double chests enabled and ender-chest farming disabled. Their excavation/paving duties still come from their separate workflows.

These movement/reach settings are the operator's **6b6t baseline**, not universal server-safe settings. Duplicate a built-in before adapting it to another server. Profiles do not supply equipment or food; the worker must bring them.

## Deliberate boundaries

As of 0.14.14, built-in stash workflows also enable Auto Mend (30% start, 95% target, auto-disable off). Their shared executor yields safely for equipped-elytra maintenance and can use personal carried food/XP shulkers as persistent nearby caches. See [personal supply setup and limits](worker-personal-supplies.md). Existing packages still keep their captured settings.

- Wait, Drop, Run configured modules and Set profile remain configuration-neutral. Custom/local workflows and saved copies keep their own profiles; this does not rewrite them.
- Stash home names, cooldowns, warmups, coordinates and reservations remain job/catalog data, not copied from a particular client module's current stash.
- Lobby Skip must be enabled as a persistent worker setting if desired. It has to work before a job starts; putting it in a temporary job profile would restore it at the wrong time.
- Connection credentials, hostile-player policy and auto-disconnect/reconnect settings stay in their existing persistent configuration. Auto Log's personal AlertOnly settings and Inventory Manager's personal drop lists are not imported.
- Updating the host exposes these defaults for **new jobs**. Already queued/running jobs and saved preset copies keep their captured packages; pause/resume does not retrofit the new settings. The native workflow presets do not change RWP Wait/Travel/Follow requests, which have no workflow-profile payload yet.

## Reference comparison

The October 11 comparison used Windows Modrinth profile `Unbans (1)` versus Debian's fresh `Monocle-0.14.9` profile. Debian had the referenced safety/movement modules disabled. Windows had enchanted/named-food eating enabled, Strict Auto Totem, Enderman avoidance, zero-drop accelerated flight, Reach and Velocity. Its current solo highway had width 1, height 2, netherrack paving, `keep-shulkers=true`, single ender chests, 25 breaks/tick and Speed 7.029: those temporary solo choices were deliberately **not** made crew defaults. No remote profile or credentials were overwritten.
