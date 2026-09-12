package dev.monocle.client.utils.world;

import dev.monocle.client.utils.world.EncounterHistory.*;
import dev.monocle.client.systems.modules.render.LogoutSpots;
import java.util.*;

public final class EncounterHistoryTest {
    private static Sight sight(UUID id, long now) { return new Sight(id, "Player", 12.5, 64, -3.5, .6, 1.8, 18, 20, "mainhand: Pickaxe", now); }
    public static void main(String[] args) {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Assertions required");
        var h = new EncounterHistory();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assert h.update("server", "nether", List.of(sight(a, 1000), sight(b, 1000)), Set.of(a, b), 1000, 100000, 500).isEmpty();
        var alerts = h.update("server", "nether", List.of(sight(b, 1250), sight(c, 1250)), Set.of(b, c), 1250, 100000, 500);
        assert alerts.size() == 1 && alerts.getFirst().sight().uuid().equals(a) : "Equal-size UUID replacement must detect departure";
        assert alerts.getFirst().kind() == Kind.LeftPlayerList && alerts.getFirst().sight().seenAt() == 1000;
        assert h.update("server", "nether", List.of(sight(b, 1500)), Set.of(b, c), 1500, 100000, 500).isEmpty();
        assert h.scope("server", "nether").stream().anyMatch(e -> e.sight().uuid().equals(c) && e.kind() == Kind.LastSeen);
        assert h.update("server", "nether", List.of(sight(b, 1750)), Set.of(b), 1750, 100000, 500).size() == 1 : "Entity first, then list removal";
        assert h.update("server", "nether", List.of(sight(b, 2000)), Set.of(), 2000, 100000, 500).isEmpty();
        assert h.update("server", "nether", List.of(), Set.of(), 2250, 100000, 500).size() == 1 : "List first, then entity removal";
        assert h.update("server", "nether", List.of(), Set.of(), 2500, 100000, 500).isEmpty() : "No duplicate alerts";
        h.update("server", "nether", List.of(), Set.of(b), 2750, 100000, 500);
        assert h.scope("server", "nether").stream().anyMatch(e -> e.sight().uuid().equals(b) && e.kind() == Kind.BackOnList);
        h.update("server", "nether", List.of(sight(b, 3000)), Set.of(b), 3000, 100000, 500);
        assert h.scope("server", "nether").getFirst().kind() == Kind.Visible;
        h.update("server", "nether", List.of(), Set.of(b), 3250, 100000, 500);
        assert h.update("server", "nether", List.of(), Set.of(), 6000, 100000, 500).isEmpty() : "Old sightings cannot become logout evidence";
        assert h.update("other", "nether", List.of(sight(a, 6250)), Set.of(a), 6250, 100000, 500).isEmpty();
        assert h.scope("other", "nether").size() == 1 && h.scope("server", "nether").size() == 3;
        assert h.update("other", "overworld", List.of(), Set.of(), 6500, 100000, 500).isEmpty() : "Dimension switch is not logout evidence";
        assert h.scope("other", "nether").getFirst().kind() == Kind.LastSeen;
        var entry = new Entry(new Key("host:25565", "minecraft:the_nether", a), sight(a, System.currentTimeMillis()), Kind.LeftPlayerList);
        assert LogoutSpots.decode(LogoutSpots.encode(entry)).equals(entry) : "NBT round trip";
        var restored = new EncounterHistory();
        restored.restore(entry);
        restored.update("host:25565", "minecraft:the_nether", List.of(), Set.of(a), System.currentTimeMillis(), 100000, 500);
        assert restored.entries().getFirst().kind() == Kind.BackOnList : "Existing online player clears a stale departure marker on reconnect";
        assert LogoutSpots.decode(LogoutSpots.encode(new Entry(entry.key(), entry.sight(), Kind.Visible))).kind() == Kind.LastSeen;
        var invalid = LogoutSpots.encode(entry); invalid.putDouble("x", Double.NaN);
        try { LogoutSpots.decode(invalid); throw new AssertionError("NaN accepted"); } catch (IllegalArgumentException expected) { }
        for (int i = 0; i < 100; i++) h.restore(new Entry(new Key("host", "dimension", UUID.randomUUID()), sight(a, i + 1), Kind.LastSeen));
        h.prune(100, 10000, 10);
        assert h.entries().size() == 10;
        h.prune(100000, 100, 10);
        assert h.entries().isEmpty();
        System.out.println("Encounter history checks passed: equal-count player changes, both removal orders, correlation expiry, reappearance, scope changes, persistence and bounds.");
    }
}
