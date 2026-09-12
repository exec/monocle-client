# Logout Spots / Encounter History (0.3.9)

The old module cross-checked player-list departures against recently loaded player
entities, but only performed that comparison when the list's total size changed.
It could miss one player leaving while another joined. It retained Player objects,
cleared its markers on disabling/dimension changes and had no saved notebook.

The new implementation samples every five client ticks and compares UUID sets.
It copies position, bounding size, health and visible equipment into plain records,
never retaining Player entities. It excludes yourself, dead players and Monocle
fake players. Entity tracking is not line of sight: players behind walls can be
observed, while players the server never sends cannot be tracked.

## What the labels mean

- **Tracked:** currently in the sampled entity set; not rendered as a logout marker.
- **Last seen:** left entity tracking, or tracking stopped. This does not mean logout.
- **Left player list:** entity disappearance and player-list departure were observed
  within two seconds. Either arrival order works. This is evidence, not proof:
  proxies, vanish systems and custom tab lists can simulate a departure.
- **Back on list · last seen:** a departed UUID is listed again, but its current
  position is not known. The default departure marker is hidden again.

Positions, equipment, health and times describe the last observation, not the
exact logout location/time or the player's current state. Equipment includes the
names of visible armor and held items, plus durability where the client knows it;
it does not inspect inventories. Leaving the world/changing dimension/disabling
tracking never fabricates a player-list departure. Reconnecting seeds the current
list and clears stale departure markers for already-listed players.

## Controls and storage

Render → Logout Spots retains scale, full-height, shape and color settings.
Show Last Seen defaults off; enable to also mark ordinary tracking departures.
Notify Departures defaults on and uses the shared module feedback destination.

Open Encounter History shows the current server/dimension's latest sighting per
UUID, newest first. This is a bounded latest-sighting notebook, not an unlimited
chronological archive of every visit. Search filters names; pages contain 20 rows.
Refresh updates the snapshot. Copy Coordinates is local; Waypoint checks that the
server and dimension are still current and disables opposite-dimension rendering.
The existing waypoint system only supports vanilla dimensions; for custom
dimensions use coordinate copying instead of creating a misleading waypoint.
Forget/Clear removes notebook records, not waypoints; currently tracked players
will be observed again while the module is enabled.

Default limit: 500 latest sightings across all scopes, configurable 10–2000.
Default expiry: 24 hours including offline time, configurable 1–720 hours.
Expiry uses last-seen wall-clock time. Multiplayer scopes use the connection's
server address (different addresses/proxy targets can be separate scopes).
Singleplayer scopes use the absolute overworld save path; dimensions have their
full registry identifiers. Marker lists are refreshed on sampling, not sorted
every render frame.

Records are saved under encounter-history in the module's normal NBT serialization,
including normal profile saves. No separate database/dependency or autosave thread.
Saving a Tracked record stores it as Last Seen, never as current presence. This
uses the client's existing save lifecycle: unsaved changes can be lost in a crash.
Names/coordinates/equipment are local, not sent to the server or IRC; they are not
encrypted, so treat client data/profile backups as containing private sightings.
Malformed records and invalid coordinates are skipped during loading.

## Verification

Run ./gradlew encounterHistoryCheck. Checks cover equal-count membership changes,
both disappearance orders, duplicate suppression, stale correlation rejection,
reappearance/re-listing, initial restored-list reconciliation, server/dimension
isolation, NBT round trips, invalid coordinates, expiry and capacity bounds.
All game rendering, controls, real server tab-list behavior, native waypoint
creation and normal client-save/restart interaction still need live testing.
