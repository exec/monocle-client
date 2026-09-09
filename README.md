# Monocle

Monocle: A Clear Advantage.

Monocle is a Minecraft Fabric utility client intended for anarchy servers where client mods are permitted.

Monocle is a fork of [Meteor Client](https://github.com/MeteorDevelopment/meteor-client), with Monocle-specific module and UI changes. Meteor's upstream history, source attribution and GPL-3.0 license are retained.

## Build

Monocle **0.3.0** targets Java 25 and Minecraft 26.2. Client releases use their own version in `gradle.properties` (`mod_version`), independently of Minecraft's version.

```sh
./gradlew build
```

The installable JAR is `build/libs/monocle-v0.3.0-26.2.jar`. Replace the previous Monocle JAR in your Fabric instance's `mods` folder; do not install both versions together.

0.3.0 bundles the Auto Log, Auto Eat, Auto Mend, Auto Armor, Auto Tool/Kill Aura, Surround, Scaffold and ElytraFly settings updates, plus shared Chest Swap landing recovery. The 0.2.15–0.2.23 changes were user-tested; the newest landing recovery still needs live-server testing. GitHub Actions checks tag/version agreement, runs the build and all 26 checks, verifies the installable JAR and publishes it with a SHA-256 checksum on version tags.

0.2.24 moves ElytraFly landing swaps onto Chest Swap's shared directional requests. WaitForGround now restores a chestplate after landing while ElytraFly remains enabled, as well as after disabling in flight. Requests defer during inventory/item use and Printer steering, retry every 10 ticks for up to 200 eligible ticks, and retain a 20-tick local observation window for inventory corrections (not a server acknowledgment guarantee). World/player changes and disconnects cancel requests; re-enabling ElytraFly cancels stale landing work. Broken gliders and Auto Mend-reserved inventory slots are skipped, and an optional Any chestplate preference accepts lower-tier armor. Live 6b6t landing and rejected inventory swaps need testing.

The user reports the 0.2.15–0.2.23 module changes tested and working as intended.

0.2.23 reorganizes ElytraFly into Flight, Acceleration, Takeoff & Landing, Safety, Autopilot, Inventory and Advanced sections. Existing setting keys, defaults and flight mechanics are unchanged; old profile groups migrate automatically. Tooltips explain speed units, the legacy per-update acceleration offset, landing risks and mode-specific recovery. Fireworks remain controllable independently of forward autopilot, matching their existing behavior. No ExperimentalGrim mode or server-specific preset is added. In-game layout and loading your live flight profile still need testing.

The 0.2.22 Java 25 build and all twenty-four checks passed. JAR integrity and embedded version were verified. Scaffold bridging, rejected placements, inventory promotion and tower behavior still need live-server testing.

The 0.2.21 Java 25 build and all twenty-three checks passed. JAR integrity and embedded version were verified. Combined eating/combat/mending handoffs and manual toggles still need live-server testing.

The 0.2.20 Java 25 build and all twenty-two checks passed. JAR integrity and embedded version were verified. Surround placement rejection, obstructing entities, low placement budgets and full-inventory promotion still need live-server testing.

The 0.2.19 Java 25 build and all twenty-one checks passed. JAR integrity and embedded version were verified. Kill Aura/Highway Builder sword handoffs and inventory promotion still need live-server testing.

The 0.2.18 Java 25 build and all twenty checks passed. JAR integrity and embedded version were verified. Full-inventory armor exchanges, flight protection and Chest Swap handoffs still need live testing.

The 0.2.17 Java 25 build and all nineteen checks passed on 2026-09-09. JAR integrity and embedded version were verified. Repair sessions, safety handoffs, full-hotbar eating and server-rejected inventory swaps still need live testing.

The 0.2.15 Java 25 build and all seventeen checks passed on 2026-09-09. JAR integrity and embedded version were verified. Auto Log's survival-guard additions still require live alert-only testing, including inventory swaps, reconnect behavior and server-specific logout consequences.

The 0.2.14 Java 25 build and all sixteen checks passed on 2026-09-08. JAR integrity and embedded version were verified. This release overhauls Stash Finder into a survey notebook using existing Monocle widgets; live notebook layout and chunk/dimension transitions still need testing.

0.2.13 adds **Advanced → Paving → Blocks Ahead to Pave**, a 1–5 slider defaulting to the existing 2 rows. Higher values opt into experimental farther-ahead paving using the existing speculative pass, including during entity waits. Attempts retain the placement reach/rate and finite-length limits and do not cause extra walking, excavation or restocking. Underfoot verification and two-row trailing repairs are unchanged. Selecting five rows does not guarantee the server will accept placements that far away, especially diagonally.

0.2.12 addresses shared Highway Builder mining/verification stalls: task handoffs cancel old mining, submitted double-mine completions time out independently of the held item, and explicit reconciliation can release mining and retire orphaned break records after a real server probe acknowledgment. Supply placement reports its failed prerequisite, and stalled jobs write a bounded-frequency diagnostic snapshot to the Minecraft log. Underfoot placement verification, supply ownership and existing backward-recovery fallbacks remain intact. This addresses code-level stall paths; confirmation against the reported live-server hangs is still required.

Walking destinations, alignment and supply placement now share the road-height tolerance used by the route's starting cell. Failed routes stop movement and replan for up to 40 attempts, requesting real server reconciliation when block operations remain pending. Persistent failures identify destination body/floor problems versus a disconnected route and log the tested blocks. Unsafe footing is not accepted just to force movement.

The 0.2.12 Java 25 build and all fifteen checks passed on 2026-09-08, including mining completion timeouts, task-handoff guards and short return routes with fractional road-height errors. JAR integrity and embedded version were verified. Live 6b6t restocking, sealing and walking recovery still need testing.

`./gradlew check` runs twenty-six assertion-based checks: Chest Swap equipment eligibility and landing/retry guards; ElytraFly profile migration; Scaffold footing sweeps and placement guards; module handoff guards; Surround full-ring geometry and confirmation guards; Auto Tool sword handoff and cooldown-order guards; Auto Armor replacement policy and equipment guards; Auto Mend durability targets and ownership guards; Auto Eat food selection and slot guards; Auto Log survival-guard policy; Stash Finder detection, notebook persistence and filtering; Highway Builder geometry, supply recovery and piglin-obstruction handling (`highwayMobCheck`); UI styling and native fonts; Inventory Manager loadout planning/persistence, bounded transfers/item conservation, cleanup protections/cancellation and container-toolbar layout; schematic export format/file safety plus selector input/render isolation; and Printer Helper geometry, optional integration, flight and supplies. Live GUI interaction and target-server inventory, supply, combat and maximum-rate building behavior still need testing.

The 0.2.10 Java 25 build and all fifteen checks passed on 2026-09-08, including HUD throughput, idle health, pause-time accounting, resets and bounded long-session storage. In-game HUD appearance still needs visual testing. Paving behavior is unchanged from 0.2.9.

The 0.2.9 Java 25 build and all fifteen checks passed on 2026-09-08, including the shortened rolling window, underfoot verification ordering and background prediction boundaries. JAR integrity and version were verified. This is speed stage 1; target-server timing and late-correction behavior are not yet measured.

The 0.2.8 Java 25 build and all fifteen checks passed on 2026-09-08, including one-block retreat geometry and return-recovery safety/session guards. JAR integrity and version were verified. Automatic recovery from a stuck supply return still needs live server testing.

The 0.2.7 Java 25 build and all fifteen checks passed on 2026-09-08, including new distance-limit and entity-wait-only placement guards. JAR integrity/version and development-client initialization were verified. Two-row paving, crowded-mob placement and maximum-rate server corrections still need live testing. Printer Helper and piglin combat policies are unchanged.

The 0.2.6 Java 25 build and all fifteen checks passed on 2026-09-08. JAR archive integrity, optional-mod isolation and embedded version were verified. Development-client startup was checked with and without the optional mods; combined startup exposed a first-frame native atlas/Globals initialization race, which now defers atlas animation until the required uniform exists. Combined startup after the fix completed module initialization, Mixin validation and atlas loading. Live flight, inside-out corridor closing, supply trips and server rollback behavior remain unverified; test a small schematic safely before relying on unattended building. Run the combined development client with `./gradlew runClient -PprinterSmoke` (optional dependencies remain excluded from release JARs).

The previous 0.2.5 Java 25 build and all eleven checks passed on 2026-09-08, including the expanded crossbow-default, configurable-filter and no-pursuit checks. JAR archive integrity and embedded version were verified. A development-client startup smoke test completed module initialization and rendering-resource loading; the client was stopped without joining a world or server. Configurable piglin targeting, pursuit and return-to-paving behavior still require live server testing.

The previous 0.2.4 Java 25 build and all eleven checks passed. JAR archive integrity and embedded version were verified. A development-client startup smoke test completed module initialization and rendering-resource loading; the test client was then stopped without joining a world or server. Piglin combat and return-to-paving behavior still require live server testing.

The previous 0.2.3 Java 25 build and all ten checks passed on 2026-09-07. Archive integrity, embedded version, selector/export classes and bundled font/license resources were verified. A development-client startup smoke test completed initialization and rendering-resource loading after fixing premature wand creation; the test client was then stopped. Development-account Realms authentication warnings are expected. In-world wand interaction, visual review and loading an export in Litematica remain unverified.

Every pull request and push is built by GitHub Actions; tags matching `v*` also create a GitHub release containing the JARs. Optional `-Pbuild_number=...` and `-Pcommit=...` values remain embedded as build metadata and do not change the release version or filename.

## UI styling

Version 0.2.0 introduces dark graphite surfaces, warm ivory text, champagne-brass accents and mint enabled states. Rounded panels have a thin gilded gradient rim, fading from champagne highlights to deeper bronze, with a restrained static shine across their headers. Fine outlines and local shadows carry through module rows, buttons, toggles, sliders, text fields, dropdowns, tooltips, separators, scrollbars and the top bar. Hover and focus styling is visual only; controls still respond immediately. Gilding uses the existing colored render batch, without a blur pass or continuous shimmer animation.

The click GUI layout, category positions, module ordering, widget sizing and control behavior are unchanged. Saved custom colors and window positions remain intact. The new default palette applies automatically wherever a color was not customized; only changed color settings are saved.

Glacial Indifference Regular is the new bundled default in 0.2.1, with Bold also available. Its original OpenType files are loaded directly without conversion or modification. Existing explicitly saved font choices are preserved, and Comfortaa remains available. Reset the font setting to adopt the new default if you previously selected a different font.

The graphite-and-gold styling and click GUI layout from 0.2.0 are unchanged.

## Printer Helper

Enable **World → Printer Helper** after installing these separate Fabric 26.2 mods: [Sakura Litematica 0.28.8](https://modrinth.com/mod/litematica/version/CuniXtbo), [MaLiLib 0.29.6](https://modrinth.com/mod/malilib/version/KvjmGjAV), and [Sakura Litematica Printer 3.2.2](https://modrinth.com/mod/litematica-printer/version/7l7ihnI0). They are compile-only integration dependencies, not bundled into Monocle; the rest of Monocle works without them. These exact releases are required because the queue hooks are version-specific.

Load and position **one enabled schematic placement** in Litematica, choose its render layers, equip a usable elytra and select **Vanilla** mode in ElytraFly. Stop Highway Builder, Schematic Selector, Freecam and other automatic movement jobs first. Helper enables ElytraFly if necessary but deliberately leaves it enabled on stopping: toggling it off can activate your configured chest swap or instant drop. Start in an open flight position; automatic takeoff requires clear overhead space and server acceptance.

Sakura performs the actual placement, special-block interactions and printing rotations. Its own reach setting applies (maximum five blocks), independent of Highway Builder's reach. Monocle streams the positioned schematic in bounded batches, prioritizes deeper work and guides the player between collision-checked printing positions. It does not excavate incorrect blocks. Unsupported blocks, missing supports, unavailable chunks and unreachable targets remain unfinished rather than being silently skipped.

**Inside-out safety:** before printing inside the full enclosing schematic volume, Helper verifies an actual flight route to its exterior. The swept escape corridor and neighboring placement cells are excluded from Printer's candidates. Helper can move outward and fill the corridor behind itself, but must not close the remaining exit in front. New Printer jobs stop during movement/restocking; queued jobs and server placement acknowledgments settle before route reservations change. Safety/manual pauses cancel owned queued work before returning movement control. Pink lines show the reserved escape route. World changes, moving the schematic or changing layers require restarting the session.

**Supplies:** carried-shulker restocking defaults on; ender-chest searching defaults off. Optional ender-chest visits prefer a nearby accessible existing chest, leaving it intact. Otherwise Helper places its own at a supported site outside the build, with an eight-block buffer by default. Maximum useful shulkers per chest visit defaults to two. Transfers merge matching stacks and reserve recovery space; containers and drops are tracked through server updates and pickup evidence. This initial version never throws away inventory items or shulkers. Keep material/tool space free, and carry a pickaxe for recovery. Without Silk Touch, recovering a self-placed ender chest yields obsidian rather than another chest.

Use **Pause / Resume** in the module panel to take control, and **Re-scan / Resume** after correcting a shortage or obstruction. If you stop while a supply container remains placed or dropped, the warning gives its location for manual recovery. Existing containers are never mined. Other inventory automation should remain off while testing; Inventory Manager and ElytraFly's replenishment are coordinated with the helper.

The initial navigator uses local routes up to 32 blocks, chained for longer travel, and verified straight escape corridors up to 128 blocks. It does not assume unknown chunks are air or drill an escape through a finished build. Fluid/waterlogged placements are withheld, and falling blocks cannot be placed above the escape corridor; dynamic fluid/redstone behavior is not simulated. Test a small, supported schematic first in a safe area; live 6b6t flight/printing, rollback handling and supply trips need validation before unattended use. Piglin combat behavior is unchanged in this release.

## Schematic Selector

Enable **World → Schematic Selector**, or run `.schematic wand`. The currently selected hotbar slot displays a **client-only wooden pickaxe**, named Schematic Selector. It is a rendering overlay: the real item underneath stays untouched and is never replaced with a fake inventory stack or sent to the server as a pickaxe. It appears in the hotbar and first-/third-person hands. Inventory screens show the real items normally.

- **Left-click a block:** set pos1.
- **Right-click a block:** set pos2.
- Both corner blocks are highlighted pink; a pink three-dimensional outline encloses the entire inclusive selection.
- **`.schematic export My Build`** saves a Litematica schematic in the active Minecraft launcher instance's `schematics/` directory, not Monocle's saved configuration-profile directory. Spaces are allowed without quotes. Existing names receive `-2`, `-3`, etc.; nothing is overwritten.

While holding the wand, block/entity/air clicks cannot mine, attack, place or open anything with the real item. Drop, offhand-swap and pick-block inputs are also consumed. Switch to another hotbar slot to use items normally, or disable the module to remove the visual wand and clear the selection. `.schematic wand` can move the overlay to your current slot.

The module panel also offers a schematic name, Export, Cancel Capture, Clear Selection and Open Schematics Folder. `.schematic pos1` and `.schematic pos2` use your current block position; append `x y z` (including relative coordinates) for precise corners. `.schematic clear` clears the selection; `.schematic cancel` cancels a capture before file writing starts. `.schem` is an alias, and all commands respect your configured client command prefix instead of requiring `.`.

Exports contain one rectangular region, including air, block properties and available client-side block-entity data such as sign text. Entities and scheduled ticks are omitted; multiplayer container contents and other server-only data are not guaranteed. The normalized minimum corner becomes the schematic origin. No WorldEdit, Baritone or Litematica installation is required to export; use Litematica to load the result.

The first version is limited to **2,000,000 blocks**, **64 MiB of block-entity data** and currently loaded chunks. Unloaded chunks fail explicitly instead of becoming air. Capture runs in bounded client-thread batches, so keep the build still while it is being read. Changing worlds/dimensions or disabling the module cancels an unfinished capture. Compression and writing happen only after the snapshot is complete; that final save can finish even if the module is subsequently disabled. Completed files are published atomically without overwrites; filesystems without hard-link support report an error instead of exposing a partial final file.

The writer follows the [Litematica 26.2 serializer](https://github.com/sakura-ryoko/litematica/blob/26.2/src/main/java/fi/dy/masa/litematica/schematic/LitematicaSchematic.java) and [dense block-state packing](https://github.com/sakura-ryoko/litematica/blob/26.2/src/main/java/fi/dy/masa/litematica/schematic/container/LitematicaBitArray.java), using Minecraft's native NBT support without a new runtime dependency. Loading an export in Litematica and reviewing the pink selection in-game remain required live tests.

## Scaffold — confirmed footing (0.2.22)

**Movement → Scaffold** tracks its own placement predictions until a full-block server update or vanilla's post-reconciliation ACK. Rejected placements become eligible for retry after a five-tick per-position interval. Stalled predictions request a sequenced verification probe at most every 40 ticks; timeout alone never confirms a block. Tracking stops new placements at 128 pending positions until the server resolves them. Placement highlights now render after confirmation, not when an action is merely queued.

**Verified Edge Guard** defaults on: while grounded, it sweeps the player's center over loaded full-block footing and clips horizontal movement before unsupported or Scaffold-pending positions. Ordinary placement looks slightly ahead in the movement direction so the guard can wait for a new block without preventing its placement. Fast Tower waits for pending predictions. This conservative guard does not model slabs/slopes, airborne rescue, vehicle/fluid movement or predictions made by other modules; it is not a guarantee against falls, knockback or server corrections. Disable it if you need legacy movement behavior.

**Search Inventory** defaults on with Auto Switch: promote permitted unnamed full building blocks into an empty hotbar slot, otherwise slot nine (eight if nine is selected). Displaced items remain in inventory. Inventory promotion excludes block entities and Auto Mend's reserved source slot; falling blocks are excluded from Scaffold entirely. No drops, supply-container opening or automatic reconstruction of the old hotbar layout.

Placement defers for screens/cursor items, item use, Auto Eat/Auto Gap, Kill Aura combat, active Highway Builder control and Printer Helper inventory control. Deferred rotations recheck activation, inventory access and material identity. The edge guard remains available during food/combat waits but yields movement to Highway Builder and Printer Helper. Closest-block search is capped at eight blocks and uses the world's actual maximum Y.

## Module cooperation — first pass (0.2.21)

Auto Armor excludes Auto Mend's reserved inventory slot from spare selection. Auto Gap now waits for safe inventory access and yields for 20 ticks after an external selected-slot change. Both food modules check food stack identity, selected slot and world before restoring selection. If a stack was consumed completely, conservative cleanup may leave the current slot selected rather than guessing ownership.

Each module now has a runtime toggle revision. Auto Eat, Auto Gap and Surround restore modules they disabled only if no intervening toggle changed that revision. Kill Aura explicitly yields while either food module is eating; delayed rotations retain the activation revision/world and recheck active state, attack state, pause conditions and weapon eligibility before attacking. Existing high-priority totem handling and Highway Builder's combat/eating waits are retained.

This is targeted cooperation, not a universal inventory lock. External Baritone/Voyager pause/resume ownership still uses the legacy path-manager API and remains a separate integration gap. Other combat modules' delayed callbacks and cross-module placement ownership are not claimed covered by this pass. Combined live-server testing remains required.

## Surround — verified ring (0.2.20)

**Combat → Surround** checks all four positions (eight with Double Height) before reporting **Protected** or honoring Toggle On Complete, independently of the placement budget and material availability. Attempted placements remain pending until an explicit protective server block update or vanilla's post-reconciliation sequence acknowledgment. Pending placements render gold. Existing loaded blast-resistant/unbreakable blocks count toward the ring; ordinary solid blocks such as netherrack do not. This is a ring-completeness status, not a guarantee against combat damage.

Placement remains feet-first but skips entity-blocked, pending and currently unplaceable positions to work on other openings, including the upper ring. Rejected placements retry with a five-tick per-position minimum interval; missing acknowledgments trigger a sequenced verification probe at most every 40 ticks, without starting a mine. Unresolved predictions are never declared successful just because a timer expired. Air Place, support placement, centering and the shared per-tick budget remain available. Weak solid obstructions are reported, not automatically mined.

**Search Inventory** defaults on and promotes permitted blocks into an empty hotbar slot, otherwise slot nine (eight if nine is selected), using the existing native swap. The displaced stack stays in the material's former inventory slot; no dropping, shulker opening or automatic hotbar-layout restoration. Screens, cursor items and item use defer placement. Status distinguishes incomplete counts, server waits, missing materials, blocked/support failures and inventory waits. Deferred placement/crystal rotations check the current activation/world before acting; crystal yaw/pitch ordering and module-restore list cleanup are also corrected.

## Auto Tool — Kill Aura handoff (0.2.19)

Enable **Auto Tool** alongside **Kill Aura**. Auto Tool's **Kill Aura Sword** defaults on and offers a sword before Aura's weapon/cooldown checks, even with Aura's Auto Switch off. **Inventory Swords** defaults on: search carried inventory as well as hotbar; use an empty hotbar slot first, otherwise exchange with slot nine (eight if nine is selected). Displaced items stay in the sword's former inventory slot; this does not rebuild the original hotbar layout afterward.

Auto Tool respects its tool list/durability settings and Aura's accepted weapon types; leave swords permitted. It keeps a usable selected sword instead of repeatedly reranking and resetting cooldown. Otherwise it selects using the existing target-damage estimate. Aura's explicit shield-breaking axe behavior takes priority. Without a permitted sword, Aura retains its existing weapon behavior. No module is automatically enabled, no target filters/reach are changed, and Highway Builder's separate opt-in pickaxe-based piglin clearing is unchanged.

Aura now runs before the builder's tick so its existing combat wait releases building controls before tool selection can compete. Auto Tool cancels pending mining switches while Highway Builder, Infinity Miner or Aura combat owns tools. Sword selection resets attack charge before Aura evaluates its configured timing. Combat restores the preceding selected slot only while Aura still owns the selected weapon and no item is being used. Inventory-screen/cursor/use guards protect sword promotion. Live testing with building, mobs, inventory promotion and server lag remains required; native swaps are client-predicted.

## Auto Armor — replacement-first equipment (0.2.18)

**Combat → Auto Armor** retains preferred protection, blast-protection leggings and avoided enchantments. **Anti Break** defaults on for new profiles; existing saved choices remain unchanged. **Replace Below Durability** defaults to 10% remaining (inclusive). At that threshold it selects the best-rated permitted spare above the threshold, even if that means less protection than the worn piece. Without a spare it keeps the armor equipped. **Remove Without Spare** is explicitly opt-in and requires an empty inventory slot; it never drops armor to make space.

Equally rated armor now upgrades when the spare has at least **Durability Upgrade Gap** more percentage points remaining (default 20; 100 disables these durability-only upgrades). Smaller differences do not trigger swaps. **Pinned Slots** leave the chosen slots completely alone, including when empty or nearly broken. Pinned slots and worn Curse of Binding pieces take precedence over anti-break.

An equipped elytra stays on while airborne, gliding or ElytraFly is enabled, even with Ignore Elytra off. Ignore Elytra still defaults on. Auto Armor yields the chest slot while Chest Swap is enabled and for 40 client ticks after a Chest Swap action, including calls from ElytraFly. After that window, normal preferences apply; pin the chest slot to retain your choice indefinitely. This does not change explicit Chest Swap actions themselves.

Status distinguishes upgrades, worn replacements, unavailable spares, pinned/bound pieces, flight protection and busy inventory access. Existing inventory/cursor/item-use guards and swap delay remain. Exchanges reuse the existing inventory helper, returning displaced armor to the spare's source slot; swaps remain client-predicted, not server-confirmed.

## Auto Mend — carried gear repair (0.2.17)

**Player → Auto Mend** selects carried Mending gear by lowest remaining durability percentage. **Target Durability** defaults to 95%; set 100 for full repairs. An eligible item already in the offhand finishes first, and each selected item stays there until it reaches the target. The blacklist applies to both inventory and offhand gear. Status shows the current item/percentage, queued inventory items, and observed durability repaired; five seconds without a damage decrease shows “Waiting for repair XP.” This measures local damage changes, not server-confirmed XP consumption.

Native cursor-free swaps preserve the original offhand in the selected item's source slot, including with a full inventory. Auto Mend restores it between items and when disabled only if that exact pair still matches (allowing repair damage changes). A changed pair stops the session without touching either item; busy/unsafe disable leaves restoration to the user with a warning. **Force** remains off by default for non-Mending offhand items. Auto Totem/Offhand safety locks, food, combat, screens and cursor items take priority.

Auto Mend waits while Highway Builder is enabled, Printer Helper controls inventory, or the player is gliding. Stop the building job and land before repairing; it does not automatically pause/resume travel, remove equipped armor/elytra, or throw XP bottles. Equipped gear can still receive normal vanilla Mending XP.

## Auto Eat — travel food management (0.2.16)

**Player → Auto Eat** now searches inventory by default; existing profiles that saved Search Inventory off keep that choice. It uses an empty hotbar slot first. If full, a native hotbar swap brings food into **Food Hotbar Slot** (default nine), moving the displaced stack into the food's former inventory slot. Nothing is dropped and the offhand is never used as a swap destination. The displaced stack stays in inventory; the original hotbar layout is not automatically reconstructed.

**Protect Named Food** defaults on alongside the existing blacklist, which still excludes golden apples, enchanted golden apples, chorus fruit and risky foods by default. **LeastWaste** is a new optional food priority: favor meals that fit missing hunger, with saturation preferred when health is low. The original Saturation default and other priority modes remain available.

Auto Eat waits while a screen/container or cursor item is active, yields to Auto Gap and manual/other-module slot changes, and restores the previous selection only when it still owns the selected food slot. A five-second attempt without a food-count or hunger change releases eating and retries after two seconds. This timeout targets normal food durations; unusual server-defined long-use foods still need testing. Status messages distinguish eating, missing permitted food, blocked inventory access and retries. Existing aura/Baritone pause options and the `eating` signal used by Highway Builder remain in place.

Native inventory prediction is still subject to server acceptance. Test full-hotbar swaps and eating alongside your other inventory modules before relying on unattended use. This does not search shulkers or ender chests, automatically unblacklist food, or change Auto Gap/Highway Builder implementations.

## Auto Log — survival guard (0.2.15)

Open **Combat → Auto Log**. The existing name, Meteor attribution and saved settings are retained. **Action** defaults to Disconnect; choose **AlertOnly** to test thresholds without disconnecting, toggling Auto Reconnect or disabling Auto Log. Warnings are rate-limited to once per ten client seconds. The module panel shows a snapshot of guard status, pop count and the last trigger; reopen it to refresh. The module's info string updates while running.

**Supply / Gear Guard** adds two opt-in checks, both disabled at zero: **Minimum Totems** counts inventory plus offhand, excluding shulker contents; a value of one triggers when none remain. **Gear Durability Percent** checks equipped armor and elytra, ignoring empty slots and nondamageable items. A one-second confirmation window defaults on for these checks to tolerate inventory swaps. This does not equip, restock or repair anything.

With **Only Trusted** enabled, **Untrusted Player Range** limits detection distance; zero preserves all-loaded-player detection. **Untrusted Confirmation Seconds** defaults to zero (immediate), or can require continuous stranger presence to avoid brief sightings. Friends, dead players and spectators do not count as strangers. Existing low-health, predicted-damage and lethal-melee checks remain immediate, independent of those confirmation windows. Health is measured in points (two per heart) and includes absorption for logout thresholds.

Totem-pop callbacks run on the client thread and reject stale world/activation callbacks. Pop counts and confirmation timers reset on activation or world change. After a real safety logout, disabling Auto Reconnect remains enabled by default; existing Toggle Off and Smart Toggle behavior is retained. AlertOnly does not take those actions.

**This is not a survival guarantee:** damage predictions and inventory state are client-side estimates, servers may punish combat logging or keep a player vulnerable after disconnect, and warnings do not stop Highway Builder or other automation. Test in AlertOnly first and understand the server's logout behavior before relying on disconnects. Highway Builder, Printer Helper, Auto Totem and Auto Reconnect implementations are unchanged.

## Stash Finder — survey notebook (0.2.14)

Open **World → Stash Finder**. The module name and existing detection settings remain compatible with saved profiles, and Meteor attribution remains in the source. Findings now live in a local survey notebook rather than an undifferentiated chunk list.

- Enabling scans already loaded chunks, then new chunk arrivals. Work is queued and limited to four chunks per tick; **Refresh / Scan loaded** requests another survey of what is currently loaded. Refresh the list again after that scan completes to see its new findings.
- Detect at least four selected storage blocks by default, or a single selected shulker box. Set **Minimum Shulkers** to zero to use only the original storage threshold. Support-block exclusions still apply. Counts describe observed blocks, not hidden contents; double chests count as two blocks.
- Search notes or coordinates; sort by shulkers, total storage, distance or last observation. Results are paged in groups of 50. **Review** adds a note and marks a finding New, Visited or Ignored. Visited/ignored findings stop automatic alerts; ignored entries are hidden unless Show ignored is checked. Known findings remain in the notebook even if a rescan sees no selected storage there.
- Review also shows first/last observation dates and copies coordinates. Chat alerts now copy coordinates when clicked; only the explicit **Goto** button requests navigation through the existing path manager. No automatic movement is introduced, and stale actions/traces are blocked after a world/dimension change.
- Notebooks are scoped by server/world identity and dimension under `monocle-client/stashes/notebooks/`. JSON saves are batched every five active seconds and flushed on disable, disconnect and scope changes. Writes use atomic replacement; corrupt files become read-only instead of being overwritten. On a disk-write failure, unsaved records remain in memory; fix the disk issue and toggle the module to retry before closing Minecraft.
- **Export CSV** creates a separate, uniquely named snapshot with dimensions, counts, review status, timestamps and quoted notes. It does not overwrite previous exports. Old stash JSON/CSV files remain untouched. Explicit legacy JSON import requires confirmation of the current dimension because those files did not record dimensions; legacy CSV-only data remains available in its original file for manual recovery.

The notebook holds up to 10,000 findings per scope, with a 4,096-chunk scan queue. It does not discover unloaded/hidden blocks, combine neighboring chunks into a base, or infer whether containers contain valuables. UI layout and live chunk/dimension transitions still require in-game testing. Highway Builder and Printer Helper behavior are unchanged in this release.

## Inventory Manager

Open **Misc → Inventory Manager**. The old `inventory-tweaks` name still loads saved configurations and works as a command alias. Monocle's Meteor origin remains credited in the source and on the title screen.

Capture your current inventory as a loadout, then edit desired item counts and optional hotbar pins. Use **Arrange Hotbar** to restore pins. With the module enabled, supported storage screens offer **Refill**, **Deposit Excess**, **Compact** and **Cancel**. Refill takes only missing saved quantities; Deposit Excess stores surplus saved items and leaves unlisted items alone. Compact merges main-inventory stacks without rearranging your hotbar. Named/custom items, enchanted items, tools and containers are protected from automatic deposit.

**On Open** defaults to Off; optional Refill or Deposit runs once per opened container. **Space Cleanup** also defaults to Off. Enable it and choose allowed disposable items to reclaim space when necessary. Matching stacks merge first; only whole stacks exceeding saved loadout quantities may be discarded. Equipment, offhand, pinned slots, food, tools, named/custom items, enchanted books and containers stay protected. Smaller disposable stacks are preferred. A temporary disposal turn is restored afterward unless you move the view yourself, which cancels the operation.

Transfers use bounded native clicks and server inventory snapshots, with no background transfer thread. Closing or changing screens, manual inventory clicks, module disable or a world change cancels the operation. Active Highway Builder work, eating and mending take precedence. A carried item is stowed safely when possible; if cancellation leaves an item on the cursor, put it away manually. Inventory Manager never discards cursor items. Lower **Clicks Per Tick** if the server rejects transfers.

Saved loadouts use the normal module/profile persistence. Invalid saved data is retained for recovery, and destructive cleanup is disabled until the loadout is replaced. Bundles remain manual because native clicks can insert into their contents instead of moving a stack.

Advanced retains filtered legacy Steal/Dump buttons, shift-drag, XCarry and bundle scrolling. The inventory sort key now compacts stacks; alphabetical sorting and the old ground-steal/drop-backwards mode are not used. Legacy Steal/Dump remains an explicit filtered transfer, separate from loadout quantities and protected automatic deposit.

## Highway Builder

Open **World → Highway Builder → Open Highway Builder** for the build panel. Choose a mode:

- **Build:** clear the passage and construct the roadway and optional railings.
- **Repair:** fill missing floor and railing blocks while preserving existing blocks.
- **ClearTunnel:** excavate the passage without paving a roadway.
- **Pave:** fill floor gaps in an already clear passage, without adding railings or excavating.

The top HUD uses middot-separated fields with green **Healthy** during productive work. Detailed state appears after three seconds without forward progress or completed mining/placement actions; explicit pauses and suspension show immediately. It reports `4.5 blocks/sec paved` as forward highway **length** per real second, not the number of blocks placed. Supply trips, waits and pauses count toward elapsed time; retreating and walking back do not inflate distance. Under **Advanced & keybind → UI** (the bottom settings group), **Average blocks/sec window** offers **5, 10 (default), 30, 60 seconds, or Session**. Rolling windows use bounded one-second samples with boundary interpolation. Session is total distance / elapsed time, not an average of averages, and retains no growing history. Stop freezes the final rate; a new job resets it.

Choose a direction, material, width and clearance, then inspect the cross-section and **Show world preview**. Facing captures your direction when starting. Width supports 1–5 roadway blocks, excluding railings; diagonal roads require at least 3. Clearance supports 2–7 blocks.

Placement reach can be set up to 7 in Advanced. Mining uses the player's normal interaction reach; the builder moves closer or uses temporary work steps when needed. Automated Highway Builder movement does not crouch. Shulker collection walks to the drop and returns to work.

The 0.2.9 speed-stage build uses a rolling pipeline: speculative placement two sections ahead, retries one ahead, mandatory server-resolved full-width verification underfoot, and non-blocking repairs one and two sections behind. Actual walking support must still be resolved before stepping onto it. Underfoot repairs take priority, followed by approaching retries, farther preplacement and trailing repairs; unused placement capacity can work ahead while underfoot acknowledgments are pending. Normal placement retains left-to-right order, with opportunistic placement during entity waits. All stages share your placement rate, delay and rotation settings; preplacement respects the run's distance limit. Starting-platform blocks outside the job are not replaced by the underfoot pass.

Trailing repairs only attempt reachable gaps with carried supplies: no extra walking, mining or restocking, and no movement gate waiting for their acknowledgments. Unresolved placements are not considered confirmed, but leave the tracking window once more than two sections behind. This deliberately permits a very late correction to leave a hole, rather than going back. Underfoot replacement of unexpected solids still follows the selected build mode. Speculative prediction waits do not trigger the foreground watchdog unless needed for walking support or underfoot verification.

User-reported baseline (2026-09-08): **0.2.8 built 100 blocks of highway length, width 5 with railings and no excavation, in 27 seconds** (about **3.70 length blocks/second**). Placement rate, delay, rotation, heading and server conditions were not recorded; preserve those settings for the 0.2.9 comparison. This is a baseline, not a measured speedup claim.

If returning from a supply trip hits the no-progress or movement-stall timeout, the builder backs up one block relative to its current facing, clears its walking route and retries the original return destination. This preserves the supply session and build progress. The retreat requires confirmed safe footing and clear space; an unsafe or unsuccessful backstep still pauses. Other timeout protections are unchanged.

Cursor recovery synchronizes with the server before acting. Server-confirmed expendable filler on the cursor is discarded before checking failed stow attempts or inventory space, so rejected netherrack stows do not pause recovery. Other held items are stowed safely; if inventory is full, the builder may exchange them with expendable filler and confirm that filler before discarding it. Valuable items still pause recovery if no safe space can be reclaimed.

Liquid sealing handles reachable lava and water first. Temporary plugs prefer expendable filler anywhere in the 36-slot inventory over hotbar paving materials; only actual roadway cells require paving blocks. Off-road lava at floor height is not treated as paving. Build and ClearTunnel seal liquids in the passage, floor and exterior barrier. Repair and Pave seal only the floor and exterior, preserving the passage. Once the route is clear and supported, movement can continue while remaining exterior seals are handled.

Before excavating an obstruction or removing an old temporary liquid plug, the builder seals and confirms the next barrier, including liquid inlets at its sides, ceiling and floor. It watches nearby plug positions while passing and retries holes reopened by the server; plugs intentionally mined to advance leave that watch list. Existing placement-rate settings still apply, with no additional placement delay.

In 0.2.11, passage sealing that makes no confirmed placement progress for **40 active client ticks (about two seconds)** backs up one block, cancels owned mining/queued actions, resets walking and requests native prediction reconciliation before rescanning the passage. Active placement confirmations restart the stall timer; eating, combat and configured server-lag waits do not consume it. The original build origin and supply ownership are preserved. Unsafe or unsuccessful backsteps and genuinely missing server acknowledgments still pause rather than treating unconfirmed plugs as solid.

Use **Test 20 blocks** for a short run, or set a length and press **Start**. Length `0` builds continuously. **Pause** releases your controls while keeping the work position, heading and progress; **Resume** continues that job. Finish paving verification and any supply or temporary-step recovery before changing the layout. **Stop** ends the job.

Starting or resuming enables Velocity only if it is off, with the message “Auto-Toggled Velocity for Highway Builder.” Its settings are preserved, and it stays enabled when the builder stops.

Under **Mob Handling**, **clear-piglin-obstructions** remains **off by default**, and upgrading preserves your saved toggle. In 0.2.5, the new targeting defaults select **only unnamed regular piglins holding a crossbow in either hand**. The controls apply only to mobs obstructing paving or walking; this is not a general combat aura.

| Setting | Default | Behavior |
| --- | --- | --- |
| `combat-targets` | Piglin only | Select regular piglins, piglin brutes and/or zombified piglins. Other entity families cannot be enabled. |
| `combat-target-weapons` | `CrossbowOnly` | Require a crossbow in either hand, use `NonCrossbow` for melee/empty hands, or `Any` to ignore held weapons. This filters the mob, not your own tool. |
| `combat-pursuit` | `CrossbowOnly` | Chase only crossbow holders; `AllTargets` allows chasing any eligible target, and `Never` disables chasing. This does not change which mobs may be attacked. |
| `combat-target-age` | `Both` | Allow both ages, or restrict to `Adult` or `Baby`. All other filters still apply. |
| `combat-ignore-named` | On | Exclude custom-named mobs. Turn off to allow named mobs that match the other filters. |
| `combat-wait-ticks` | 40 | Wait for an obstruction to move before engaging; range 0–1,200 ticks. Forty ticks is about two seconds at 20 TPS. |
| `combat-timeout-seconds` | 20 | Pause after this much active encounter time; range 1–120 seconds, counted at 20 active ticks per second. Eating and server-lag waits do not count. |
| `combat-rotate` | On | Aim before attacking, independently of mining/placement rotation. Off removes the combat rotation; walking still faces the route. |
| `combat-min-health` | 12 | Pause at or below six hearts, excluding absorption; range 1–20 health. |
| `combat-pursuit-range` | 6 blocks | Maximum distance from the encounter; range 1–8 blocks. This never extends melee reach. |

This is lethal combat, not a fist nudge: the builder uses a usable pickaxe above its configured tool reserve, normal melee reach and a full attack cooldown. It never enables Kill Aura. Pursuit stays near the job on level, confirmed safe ground, without temporary steps or bridges. If pursuit is disabled for a mob, the builder will not acquire it outside melee reach. If an already selected target moves out of reach, the builder waits in place for it to return or for an observed death, subject to the timeout and other safety limits.

Once engaged, the builder keeps the same target until it observes its death, even if the mob stops blocking the roadway. A disappearance or unload alone is not a confirmed kill and pauses the build. Filter or weapon changes that make the selected target ineligible also pause; they do not count as a kill. Changing combat settings cancels queued attacks before revalidation. Mobs excluded by the filters must move out of the way: the builder waits rather than skipping obstructed paving.

Low health, timeout, an unsafe or out-of-range target, lost footing, or nearby end crystals pause the build. After a confirmed kill, the builder returns to its saved work position and verifies paving through the existing confirmation/repair flow before continuing.

**Warning:** attacking these mobs can anger nearby piglins or zombified piglins and attract a swarm. The health and pursuit limits do not make that safe. Pause or stop to take control; disabling piglin clearing during an encounter stops further attacks and pauses, then Resume returns toward work without pursuing the mob. Existing route-foot calculations retain the `roadFeetY` tolerance for tiny vertical position errors. This patch extends the existing obstruction-handling helpers without a general routing overhaul or new dependencies.

Under Supplies, **search-shulkers** allows restocking from carried boxes. The builder records the box's name and color before placement, tracks the matching dropped item, and confirms your pickup through the server's pickup event and the tracked item's disappearance from the ground before returning to work. It does not compare inventory contents to confirm recovery. **filler-blocks** now also authorizes automatic disposal: ordinary cleanup keeps one working filler stack, but supply collection, recovery and obsidian conversion may reclaim it when necessary. Space recovery chooses the smallest filler stack first. Paving blocks, tools, food and storage containers are protected, with shulker disposal controlled separately by **Keep Shulkers**. **trash-items** permits other cleanup. **search-ender-chest** permits retrieving stored supplies, while **mine-ender-chests** separately permits consuming spare ender-chest blocks for obsidian; reserves still apply.

**Max Shulkers Per Restock** defaults to **2** useful boxes per ender-chest visit (range 1–36). Boxes are taken before loose supplies, one per inventory interaction delay, up to the limit or available safe capacity. Space is reserved for both placed chests and for unpacking a collected box. Expendable filler can be dropped to make room; the builder takes fewer boxes when protected inventory space is unavailable.

**Keep Shulkers** is on by default. Turning it off warns that empty boxes and kits without useful supplies may be dropped; boxes containing useful supplies remain protected.

**Double Ender Chest** is on by default and forced off for widths below two. It places two physical ender chests adjacent to each other with the same facing, then searches the server's 54-slot inventory for supplies, including raw obsidian and shulkers. Width-two roads use a deliberate placement position and sideways working stance. Both placed chests are recovered sequentially through the existing pickup flow. Restocking releases movement each tick unless explicitly walking, so collecting the first chest cannot leave forward held while mining the second. Cached contents and previews include all 54 observed slots; a 27-slot observation does not rule out supplies in the unseen half.

Restocking merges into matching partial stacks first, checking the hotbar before the main inventory. Full stacks do not count as available space. Transfers preserve the configured **minimum-empty-slots** reserve plus one pickup slot per placed container (two for the ender-chest pair). Moving a material or tool into a full hotbar exchanges the displaced stack back into its inventory source slot; it does not require an empty slot or discard the displaced item. Slots already queued for a rotated placement cannot be replaced until that tick's actions finish.

Advanced includes **Require silk touch pickaxe for ender chest recovery**, off by default. Normal pickaxes may recover a placed ender chest as obsidian independently of the bulk **mine-ender-chests** setting. Enable the requirement to recover the chest itself with Silk Touch.

BookBot uses the shorter default book title **Monocle Advantage**.

The separately reported false-obstruction issue remains deferred and unchanged.

## Credits and license

Monocle is a modified work based on [Meteor Client](https://github.com/MeteorDevelopment/meteor-client), originally developed by Meteor Development. The initial Monocle baseline was derived from Meteor commit `b587fb9d3` and then renamed and modified.

Both projects are licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE). Monocle's complete corresponding source must remain available when builds are distributed.

The bundled Glacial Indifference fonts remain separately licensed under the SIL Open Font License 1.1, not GPL. Their [original copyright notices](src/main/resources/assets/monocle-client/fonts/GlacialIndifference-NOTICE.txt) and [full OFL](src/main/resources/assets/monocle-client/fonts/GlacialIndifference-OFL.txt) accompany the unchanged font files inside the JAR. Keep these notices and license with redistributed copies; do not sell the fonts by themselves or claim author endorsement. See the [official OFL text](https://openfontlicense.org/open-font-license-official-text/) for the complete conditions.
