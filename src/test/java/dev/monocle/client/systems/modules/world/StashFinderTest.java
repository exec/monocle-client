package dev.monocle.client.systems.modules.world;

import net.minecraft.world.level.ChunkPos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** ./gradlew stashFinderCheck — no world, network connection or renderer required. */
public final class StashFinderTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Run with assertions enabled");
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var small = new StashFinder.Chunk(new ChunkPos(-3, 7));
        small.shulkers = 1;
        small.note = "=kit, \"north\"\nreturn later";
        small.firstSeen = 100;
        small.lastSeen = 200;
        var large = new StashFinder.Chunk(new ChunkPos(2, 0));
        large.chests = 30;
        large.otherStorage = 4;
        assert large.getTotal() == 34 : "Selected pots, crafters and other storage must not silently disappear from totals";
        large.lastSeen = 300;
        assert small.x == -40 && small.z == 120;
        assert StashFinder.qualifies(small, 4, 1) && !StashFinder.qualifies(small, 4, 0);
        assert StashFinder.qualifies(large, 4, 0);
        assert !small.countsEqual(large) && !small.countsEqual(null);
        var same = new StashFinder.Chunk(small.chunkPos);
        same.shulkers = 1;
        assert small.countsEqual(same) : "Unchanged observations must not trigger repeat alerts";
        same.barrels++;
        assert !small.countsEqual(same);
        List<StashFinder.Chunk> records = new ArrayList<>(List.of(large, small));
        assert StashFinder.findings(records, "", false, StashFinder.Sort.Shulkers, 0, 0).getFirst() == small;
        for (var sort : List.of(StashFinder.Sort.Storage, StashFinder.Sort.Nearest, StashFinder.Sort.Recent))
            assert StashFinder.findings(records, "", false, sort, 0, 0).getFirst() == large;
        assert StashFinder.findings(records, "NORTH", false, StashFinder.Sort.Shulkers, 0, 0).equals(List.of(small));
        assert StashFinder.findings(records, "-40", false, StashFinder.Sort.Shulkers, 0, 0).equals(List.of(small));
        small.review = StashFinder.Review.Ignored;
        var removed = new StashFinder.Chunk(small.chunkPos);
        StashFinder.retainReview(removed, small, 400);
        assert removed.getTotal() == 0 && removed.note.equals(small.note) && removed.review == StashFinder.Review.Ignored;
        assert removed.firstSeen == 100 && removed.lastSeen == 400 : "Rescanning a cleared finding must preserve review history";
        StashFinder.retainReview(same, null, 500);
        assert same.firstSeen == 500 && same.lastSeen == 500;
        assert StashFinder.findings(records, "", false, StashFinder.Sort.Shulkers, 0, 0).equals(List.of(large));
        assert StashFinder.findings(records, "", true, StashFinder.Sort.Shulkers, 0, 0).size() == 2;
        assert records.getFirst() == large : "Sorting must not reorder storage";
        assert !StashFinder.scopeId("server", "minecraft:overworld").equals(StashFinder.scopeId("server", "minecraft:the_nether"));
        assert !StashFinder.scopeId("server/a", "minecraft:overworld").equals(StashFinder.scopeId("server:a", "minecraft:overworld"));
        assert StashFinder.scopeId("../../server", "custom:../dimension").matches("[a-f0-9-]+");
        Path directory = Files.createTempDirectory("monocle-stash-check-");
        try {
            Path file = directory.resolve("notebook.json");
            assert StashFinder.readNotebook(file).isEmpty();
            StashFinder.writeNotebook(file, records);
            var restored = StashFinder.readNotebook(file);
            assert restored.size() == 2 && restored.get(1).countsEqual(small);
            assert restored.get(1).note.equals(small.note) && restored.get(1).review == small.review;
            assert restored.get(1).firstSeen == 100 && restored.get(1).lastSeen == 200;
            assert restored.get(1).x == -40 && restored.get(1).z == 120;
            String before = Files.readString(file);
            small.chests = -1;
            expectInvalid(() -> StashFinder.writeNotebook(file, records));
            assert Files.readString(file).equals(before);
            small.chests = 0;
            expectInvalid(() -> StashFinder.writeNotebook(file, List.of(small, small)));
            assert Files.readString(file).equals(before);
            Path invalid = directory.resolve("invalid.json");
            for (String content : List.of("null", "[null]", "{broken", "[{\"chunkPos\":null}]")) {
                Files.writeString(invalid, content);
                expectInvalid(() -> StashFinder.readNotebook(invalid));
                assert Files.readString(invalid).equals(content) : "Reading malformed files must not modify them";
            }
            Files.writeString(invalid, "[{\"chunkPos\":{\"x\":-3,\"z\":7},\"chests\":4}]");
            var legacy = StashFinder.readNotebook(invalid).getFirst();
            assert legacy.x == -40 && legacy.z == 120 && legacy.note.isEmpty() && legacy.review == StashFinder.Review.New;
            assert legacy.firstSeen == 0 : "Never invent legacy dates";
            Path csv = directory.resolve("survey.csv");
            StashFinder.exportCsv(csv, records, "minecraft:the_nether");
            String exported = Files.readString(csv);
            assert exported.contains("\"minecraft:the_nether\",-40,120,");
            assert exported.contains("\"'=kit, \"\"north\"\"\nreturn later\"") : "CSV notes need quoting and formula neutralization";
            StashFinder.writeNotebook(file, List.of());
            assert StashFinder.readNotebook(file).isEmpty();
        } finally {
            try (var paths = Files.list(directory)) { for (Path path : paths.toList()) Files.delete(path); }
            Files.delete(directory);
        }
        System.out.println("Stash Finder checks passed: detection thresholds, counts, sorting/filtering, scope keys, atomic persistence, legacy reading and CSV notes.");
    }

    private static void expectInvalid(IO action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid notebook was accepted"); }
        catch (IOException expected) { }
    }
    @FunctionalInterface private interface IO { void run() throws Exception; }
}
