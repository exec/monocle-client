# Worker attack and death evidence

Available in 0.14.0 — **The Reckoning Update**. This is a passive observer for every connected game client, including stash workers and solo play. It does not retaliate, change combat targets, determine stash ownership, or confer any authority on a named player.

## What is known

Damage packets carry causing/direct entity IDs. If one resolves to a player, the record contains that player's UUID/name with `attribution: server_reported`. This covers ordinary melee and projectiles/explosions when the server actually supplies a player cause. The raw IDs and damage type are retained even when an entity cannot be resolved. Health/absorption fields are observations, **not measured damage amounts**.

Crystals do not expose a dependable placer field to this client. A remote player swinging within six blocks, while visibly holding a crystal within the last 750 ms, is a placement **suspect**. Nearby swings at removal are detonation suspects; the placer and detonator may differ. Removal alone can be unloading, so a separate explosion observation requires a matching explosion center. Multiple matching crystals are marked ambiguous without choosing one crystal's suspects. These candidates are never promoted to server-reported attackers, and a player merely standing nearby is not a candidate. The nearest player is never assumed to be the killer.

The client retains the death packet's text, and a named actor from structured vanilla `death.attack.*` messages when available (not the intentional-game-design link). Arbitrary server plaintext is not parsed into a killer. A recent damage record is included for context, labelled `recentDamage`; an earlier sword hit followed by a fall is not proof the swordsman dealt the final blow. Unknown attribution remains unknown. Server messages/IDs are server claims, not independent proof.

These constraints follow the [Java protocol's damage event fields](https://wikivg.booky.dev/Protocol#Damage_Event) and the actual Minecraft 26.2 packet classes used by this build.

## Where to look

For the host-shared avoidance list and manual add/remove controls, see [known hostiles (0.14.1)](known-hostiles.md). Crystal suspects are never automatically added.

- Client `logs/latest.log` and rotated Minecraft logs: search `[Workers combat]`. Each record is JSON with a timestamp, sequence, world scope, coordinates and evidence. Recording does not require a Crystal Guard job.
- Standalone host `/v1/status` and worker resource responses: optional `combat` object containing the latest damage/death/crystal explosion. Worker inspection in the WebUI exposes it without changing worker controls.
- Host event history/telemetry: `worker-combat` events carry the full bounded `combat` object separately from the short human-readable detail. This avoids truncating the structured evidence at the ordinary 1,024-character detail limit.
- In-game host: worker diagnostic inspection includes the same `combat` snapshot. No new host-to-worker control channel is introduced.

Damage/death/explosion observations prompt an immediate existing worker announcement, followed by normal announcements. It is best-effort delivery: abrupt process termination or a broken host connection can still prevent the final report. Local game logs remain the fallback. The worker evidence is capped at 4 KiB, separate from the existing 8 KiB highway diagnostic limit; oversized repeated context is omitted from the compact snapshot, not the original local record. Update the client for observation and the standalone host for API/event/WebUI display. Old hosts ignore the extra announcement field.

Swing and crystal caches are bounded to 128 entries each. Removed crystals remain briefly for packet ordering; world/connection changes clear correlation state, but preserve the latest damage/death for inspection. Evidence from a previous world is never attached to a new-world death. Missed/cancelled packets, absent player models, out-of-range actors, lack of a swing, simultaneous activity and server-customized death messages can leave attribution ambiguous or unavailable. Do not treat crystal suspects as evidence sufficient to blame a stash owner.

## Worker audit: should #1

Native walking travel previously rebuilt its candidate list and performed bounded BFS searches every tick. The next route is now reused for up to five ticks only while start/goal cells match. Each tick still checks the start and next step's footing and the swept movement corridor. A blocked step, changed cell/goal, server correction, world/action reset or tick-counter rewind invalidates reuse. Failed searches retry within five ticks; there is no permanent negative cache. Within one search, repeated footing probes share a temporary memoized result; it does not survive between searches.

This affects native walking travel/follow/bodyguard, not Baritone, manual movement or highway crew synchronization. Offline checks cover cache hits, expiry, blocked steps, moving goals, resets and failed-search retry. Live speed and combat attribution validation remain pending.
