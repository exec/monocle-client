package dev.monocle.client.systems.bots;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.Map;

final class FightEventStoreTest {
    static void run() throws Exception {
        var file = Files.createTempDirectory("monocle-crystal-fight-test-").resolve("fight.sqlite");
        try (FightEventStore store = new FightEventStore(file, Map.of("schema", "crystal-fight-1"))) {
            store.append(new FightEventStore.Event(100, 1, "motion", "observed", "worker", "Worker", 1d, 2d, 3d, "{\"health\":20}"));
            store.append(new FightEventStore.Event(150, 2, "health_change", "observed", "opponent", "Opponent", 4d, 5d, 6d, "{\"delta\":-6}"));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
            var rows = statement.executeQuery("SELECT kind,actor_name,json_extract(data,'$.delta') FROM events ORDER BY id");
            assert rows.next() && rows.getString(1).equals("motion") && rows.getString(2).equals("Worker");
            assert rows.next() && rows.getString(1).equals("health_change") && rows.getString(2).equals("Opponent") && rows.getInt(3) == -6;
            assert !rows.next();
            var metadata = statement.executeQuery("SELECT value FROM metadata WHERE key='schema'");
            assert metadata.next() && metadata.getString(1).equals("crystal-fight-1");
        }
        System.out.println("Crystal fight SQLite checks passed: durable ordered events, JSON queries and session metadata.");
    }
}
