package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** Transfer the real immutable package through JSON frames, including retries and hostile size/geometry inputs. */
final class BotTaskDataTest {
    static void run() throws Exception {
        unchangedJournal();
        JsonObject source = packaged();
        String difficult = "\"\\\u0000\n<>&雪😀".repeat(4000);
        JsonObject dispatch = new JsonObject(); dispatch.addProperty("notes", difficult); source.add("dispatch", dispatch);
        JsonObject checked = BotTaskData.checkedPackage(source);
        dispatch.addProperty("notes", "changed later");
        assert checked.getAsJsonObject("dispatch").get("notes").getAsString().equals(difficult) : "Dispatch arguments cannot follow caller mutations";
        String encoded = BotTaskData.encode(checked);
        UUID run = UUID.randomUUID(); JsonObject begin = begin(run, encoded);
        BotTaskData.Incoming incoming = new BotTaskData.Incoming(begin);
        assert incoming.matchesBegin(begin.deepCopy());
        JsonObject different = begin.deepCopy(); different.addProperty("hash", "0".repeat(64)); assert !incoming.matchesBegin(different);
        JsonObject assembled = null;
        for (int index = 0; index < incoming.count; index++) {
            JsonObject chunk = chunk(run, encoded, index);
            assert chunk.toString().getBytes(StandardCharsets.UTF_8).length < 16_384 : "All JSON-escaped frames fit the existing transport";
            // Exercise the actual UTF-8/string JSON boundary, not only same-process JsonObject references.
            JsonObject received = JsonParser.parseString(new String(chunk.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).getAsJsonObject();
            assembled = incoming.add(received);
            assert incoming.add(received.deepCopy()) == null : "A repeated chunk cannot install an execution twice";
            assert incoming.next == index + 1 : "Retrying the same chunk must not advance transfer state";
            if (index == 0) {
                JsonObject changed = received.deepCopy(); String data = changed.get("data").getAsString();
                changed.addProperty("data", (data.charAt(0) == 'A' ? "B" : "A") + data.substring(1)); bad(() -> incoming.add(changed));
            }
        }
        assert assembled != null && assembled.equals(checked) : "Quotes, controls, CJK and surrogate pairs survive chunk boundaries";
        assert assembled.getAsJsonObject("dispatch").get("notes").getAsString().equals(difficult);
        var outOfOrder = new BotTaskData.Incoming(begin); bad(() -> outOfOrder.add(chunk(run, encoded, 1)));
        JsonObject shortChunk = chunk(run, encoded, 0); shortChunk.addProperty("data", "AAAA"); bad(() -> outOfOrder.add(shortChunk));
        JsonObject wrongRun = chunk(UUID.randomUUID(), encoded, 0); bad(() -> outOfOrder.add(wrongRun));
        JsonObject hugeCount = begin.deepCopy(); hugeCount.addProperty("count", Integer.MAX_VALUE); bad(() -> new BotTaskData.Incoming(hugeCount));
        JsonObject invalidHash = begin.deepCopy(); invalidHash.addProperty("hash", "invalid"); bad(() -> new BotTaskData.Incoming(invalidHash));
        String corrupt = "QQ=="; var wrongDigest = begin(run, corrupt); wrongDigest.addProperty("hash", "0".repeat(64));
        bad(() -> new BotTaskData.Incoming(wrongDigest).add(chunk(run, corrupt, 0)));
        String malformedUtf8 = "/w=="; bad(() -> new BotTaskData.Incoming(begin(run, malformedUtf8)).add(chunk(run, malformedUtf8, 0)));

        JsonObject big = packaged(); big.addProperty("notes", "雪".repeat(BotTaskData.MAX_PACKAGE / 3));
        assert big.toString().length() < BotTaskData.MAX_PACKAGE;
        bad(() -> BotTaskData.checkedPackage(big)); bad(() -> BotTaskData.encode(big));
        JsonObject unpairedSurrogate = packaged(); unpairedSurrogate.addProperty("notes", "\ud83d"); bad(() -> BotTaskData.encode(unpairedSurrogate));
        JsonObject missing = packaged(); missing.addProperty("entry", "unbundled"); bad(() -> BotTaskData.checkedPackage(missing));
        JsonObject orphan = packaged(); orphan.getAsJsonObject("highways").add("orphan", BotWorkflows.legacyPlan("Build")); bad(() -> BotTaskData.checkedPackage(orphan));
        JsonObject validGeometry = geometry(); JsonObject withGeometry = packaged(); withGeometry.add("geometry", validGeometry);
        assert BotTaskData.checkedPackage(withGeometry).get("geometry").equals(validGeometry);
        for (String key : List.of("x", "y", "z")) {
            JsonObject invalid = withGeometry.deepCopy(); invalid.getAsJsonObject("geometry").addProperty(key, Long.MAX_VALUE); bad(() -> BotTaskData.checkedPackage(invalid));
        }
        for (String key : List.of("width", "height", "dx", "dz")) {
            JsonObject invalid = withGeometry.deepCopy(); invalid.getAsJsonObject("geometry").getAsJsonObject("layout").addProperty(key, 100); bad(() -> BotTaskData.checkedPackage(invalid));
        }
        JsonObject wrongHeading = withGeometry.deepCopy(); wrongHeading.getAsJsonObject("geometry").getAsJsonObject("layout").addProperty("heading", "North"); bad(() -> BotTaskData.checkedPackage(wrongHeading));

        var directory = Files.createTempDirectory("monocle-task-data-check-");
        try {
            var file = directory.resolve("journal.json"); assert BotTaskData.read(file).isEmpty();
            BotTaskData.write(file, checked); assert BotTaskData.read(file).equals(checked);
            Files.writeString(file, "{broken"); bad(() -> BotTaskData.read(file)); assert Files.readString(file).equals("{broken");
            var library = new BotWorkflows(directory.resolve("workflows.json"));
            var child = library.createProgram("Child", "Tests", "return function(ctx) return bot.wait(5) end");
            var parent = library.saveProgram(UUID.randomUUID().toString(), "Parent", "Tests", "return function(ctx) return bot.call('" + child.id() + "') end", List.of(child.id()), List.of("Current"));
            JsonObject captured = library.packageWorkflows(parent.id());
            assert captured.getAsJsonObject("programs").has(parent.id()) && captured.getAsJsonObject("programs").has(child.id()) : "Declared nested workflows travel with their parent";
            String before = captured.getAsJsonObject("programs").getAsJsonObject(child.id()).get("script").getAsString();
            library.saveProgram(child.id(), child.name(), child.folder(), "return function(ctx) return bot.wait(10) end", List.of(), List.of("Current"));
            assert captured.getAsJsonObject("programs").getAsJsonObject(child.id()).get("script").getAsString().equals(before) : "Editing a dependency cannot mutate queued source";
        } finally {
            try (var files = Files.list(directory)) { for (var file : files.toList()) Files.delete(file); }
            Files.delete(directory);
        }
        System.out.println("Bot task data checks passed: immutable dependencies, native geometry, UTF-8 byte limits, bounded frames, idempotent transfer retries and atomic journals.");
    }
    private static JsonObject packaged() {
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("entry", "test");
        JsonObject program = new JsonObject(); program.addProperty("name", "Test"); program.addProperty("script", "return function(ctx) return bot.done() end");
        JsonObject programs = new JsonObject(); programs.add("test", program); value.add("programs", programs); value.add("highways", new JsonObject());
        JsonObject profiles = new JsonObject(); profiles.add("Current", new JsonObject()); value.add("profiles", profiles); return value;
    }
    private static JsonObject geometry() {
        JsonObject value = new JsonObject(); value.addProperty("scope", "test\nminecraft:the_nether"); value.addProperty("x", 0); value.addProperty("y", 116); value.addProperty("z", 1000);
        JsonObject layout = new JsonObject(); layout.addProperty("dx", 0); layout.addProperty("dz", 1); layout.addProperty("width", 5); layout.addProperty("height", 3);
        layout.addProperty("heading", "South"); layout.addProperty("operation", "Build"); layout.addProperty("floor", "Replace"); layout.addProperty("blocks", "minecraft:obsidian");
        layout.addProperty("railings", true); layout.addProperty("supports", false); layout.addProperty("above", true); value.add("layout", layout); return value;
    }
    private static JsonObject begin(UUID run, String encoded) {
        JsonObject m = BotTaskData.message("begin"); m.addProperty("run", run.toString()); m.addProperty("hash", BotTaskData.hash(encoded)); m.addProperty("count", (encoded.length() + BotTaskData.CHUNK - 1) / BotTaskData.CHUNK); return m;
    }
    private static JsonObject chunk(UUID run, String encoded, int index) {
        JsonObject m = BotTaskData.message("chunk"); m.addProperty("run", run.toString()); m.addProperty("index", index);
        m.addProperty("data", encoded.substring(index * BotTaskData.CHUNK, Math.min(encoded.length(), (index + 1) * BotTaskData.CHUNK))); return m;
    }
    private static void unchangedJournal() throws Exception {
        var directory = Files.createTempDirectory("monocle-journal-check-");
        var file = directory.resolve("tasks.json");
        JsonObject state = new JsonObject(); state.addProperty("status", "Running");
        try {
            BotTaskData.write(file, state);
            var marker = java.nio.file.attribute.FileTime.fromMillis(1_000);
            Files.setLastModifiedTime(file, marker);
            BotTaskData.write(file, state.deepCopy());
            assert Files.getLastModifiedTime(file).equals(marker) : "Unchanged checkpoints must not rewrite the journal";
            state.addProperty("status", "Stopped"); // Same byte length, different intent.
            BotTaskData.write(file, state);
            assert BotTaskData.read(file).equals(state) && !Files.getLastModifiedTime(file).equals(marker);
            Files.delete(file);
            BotTaskData.write(file, state);
            assert BotTaskData.read(file).equals(state) : "An absent journal must be recreated, not mistaken for a cached success";
            JsonObject other = state.deepCopy(); other.addProperty("status", "Running");
            BotTaskData.write(file, other);
            BotTaskData.write(file, state);
            assert BotTaskData.read(file).equals(state) : "Externally replaced journals must be reconciled";
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    }

    private static void bad(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid task data must be rejected"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
