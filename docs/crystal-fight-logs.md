# Crystal Guard fight logs

Each Crystal Guard worker writes one SQLite database per run under `monocle-client/combat-logs/` in that Minecraft profile. The worker prints the exact file path when recording starts. Pausing or disconnecting closes the current recording; a resumed fight gets a new file. Nothing is sent to the host or public chat.

`events` has indexed `time_ms`, `kind`, and `actor_id` columns, plus `x`, `y`, `z` and JSON `data`. `evidence` distinguishes `observed` client state/server packets, `intent` sent actions, `predicted` Crystal Aura damage, `packet_correlated` health loss near a damage packet, and `inferred` possible kills. `metadata` records job, worker, subject, server, dimension and schema version.

The opponent's health and absorption are sampled from the same `Player` fields shown by Monocle Nametags. `health_change` is their observed combined-health delta. `our_crystal_damage` links a health drop to a damage packet whose direct crystal entity ID matches our recently confirmed placement; `unattributed_crystal_damage` links one to a known crystal with uncertain ownership. Simultaneous damage can still make source attribution ambiguous. A crystal spawn/removal is not proof of an explosion, and `possible_kill` is explicitly an inference.

Examples with `sqlite3 fight.sqlite`:

```sql
-- Full fight timeline, including sampled movement.
SELECT datetime(time_ms / 1000, 'unixepoch'), tick, actor_name, kind, evidence, x, y, z, data
FROM events ORDER BY id;

-- Health losses for either fighter, with packet correlation when available.
SELECT time_ms, actor_name, json_extract(data, '$.before') AS before,
       json_extract(data, '$.after') AS after,
       json_extract(data, '$.recentDamageCause') AS cause,
       json_extract(data, '$.ourCrystal') AS our_crystal
FROM events WHERE kind = 'health_change' AND json_extract(data, '$.delta') < 0
ORDER BY time_ms;

-- What the bot planned versus what the client actually observed.
SELECT time_ms, kind, evidence, actor_name, x, y, z, data
FROM events WHERE kind IN ('aura_plan', 'block_place_attempt', 'block_update',
                            'crystal_place_attempt', 'crystal_spawn', 'crystal_attack_attempt',
                            'damage_packet', 'health_change', 'death')
ORDER BY id;
```

Movement is sampled once per client tick for the worker, subject and visible eligible opponents; the database also records guard goals, food use/count changes, equipment changes and break packets, totem pops, flight/module transitions, obsidian changes, and explosion packets. A `telemetry_gap` row reports any events dropped because the bounded writer queue filled. These databases contain player names and coordinates; share them deliberately.
