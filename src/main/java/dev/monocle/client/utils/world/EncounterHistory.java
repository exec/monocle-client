package dev.monocle.client.utils.world;

import java.util.*;

/** Client-observed evidence only; never retains live entities or claims a proven logout. */
public final class EncounterHistory {
    public enum Kind { Visible, LastSeen, LeftPlayerList, BackOnList }
    public record Sight(UUID uuid, String name, double x, double y, double z, double width, double height,
                        int health, int maxHealth, String equipment, long seenAt) { }
    public record Key(String server, String dimension, UUID uuid) { }
    public record Entry(Key key, Sight sight, Kind kind) { }
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Sight> visible = new HashMap<>();
    private final Map<UUID, Long> departed = new HashMap<>();
    private Set<UUID> listed = Set.of();
    private String server = "", dimension = "";
    private boolean initialized;

    public List<Entry> update(String server, String dimension, List<Sight> sightings, Set<UUID> online, long now, long expiry, int limit) {
        if (!this.server.equals(server) || !this.dimension.equals(dimension)) resetTracking();
        this.server = server;
        this.dimension = dimension;
        List<Entry> alerts = new ArrayList<>();
        Set<UUID> current = new HashSet<>();
        if (initialized) {
            for (UUID id : listed) if (!online.contains(id)) departed.put(id, now);
        }
        for (UUID id : online) if (!initialized || !listed.contains(id)) {
            Key key = new Key(server, dimension, id);
            Entry old = entries.get(key);
            if (old != null && old.kind == Kind.LeftPlayerList) put(new Entry(key, old.sight, Kind.BackOnList));
            departed.remove(id);
        }
        for (Sight sight : sightings) {
            current.add(sight.uuid);
            visible.put(sight.uuid, sight);
            put(new Entry(new Key(server, dimension, sight.uuid), sight, Kind.Visible));
        }
        for (var it = visible.entrySet().iterator(); it.hasNext();) {
            var old = it.next();
            if (current.contains(old.getKey())) continue;
            Sight sight = old.getValue();
            boolean left = departed.containsKey(sight.uuid) && now - departed.get(sight.uuid) <= 2000;
            Entry entry = new Entry(new Key(server, dimension, sight.uuid), sight, left ? Kind.LeftPlayerList : Kind.LastSeen);
            put(entry);
            if (left) alerts.add(entry);
            it.remove();
        }
        // Also handles the entity disappearing before the player-list removal arrives.
        for (UUID id : departed.keySet()) {
            Key key = new Key(server, dimension, id);
            Entry old = entries.get(key);
            if (!current.contains(id) && old != null && old.kind == Kind.LastSeen && now - old.sight.seenAt <= 2000) {
                Entry entry = new Entry(key, old.sight, Kind.LeftPlayerList);
                put(entry);
                alerts.add(entry);
            }
        }
        departed.entrySet().removeIf(e -> now - e.getValue() > 2000);
        listed = Set.copyOf(online);
        initialized = true;
        prune(now, expiry, limit);
        return alerts;
    }

    private void put(Entry entry) { entries.remove(entry.key); entries.put(entry.key, entry); }
    public void restore(Entry entry) {
        put(entry.kind == Kind.Visible ? new Entry(entry.key, entry.sight, Kind.LastSeen) : entry);
    }
    public List<Entry> entries() { return List.copyOf(entries.values()); }
    public List<Entry> scope(String server, String dimension) {
        return entries.values().stream().filter(e -> e.key.server.equals(server) && e.key.dimension.equals(dimension))
            .sorted(Comparator.comparingLong((Entry e) -> e.sight.seenAt).reversed()).toList();
    }
    public void remove(Key key) { entries.remove(key); }
    public void clearScope(String server, String dimension) {
        entries.keySet().removeIf(k -> k.server.equals(server) && k.dimension.equals(dimension));
    }
    public void prune(long now, long expiry, int limit) {
        entries.values().removeIf(e -> now - e.sight.seenAt > expiry);
        while (entries.size() > Math.clamp(limit, 1, 2000)) entries.remove(entries.keySet().iterator().next());
    }
    public void resetTracking() {
        entries.replaceAll((key, entry) -> entry.kind == Kind.Visible ? new Entry(key, entry.sight, Kind.LastSeen) : entry);
        visible.clear(); departed.clear(); listed = Set.of(); initialized = false;
    }
    public void clear() { entries.clear(); resetTracking(); }
}
