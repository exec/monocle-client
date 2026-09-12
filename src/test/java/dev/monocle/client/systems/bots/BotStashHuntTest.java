package dev.monocle.client.systems.bots;

import com.google.gson.*;
import java.util.*;

/** ./gradlew botsCheck: real survey reducer with simulated chunk delivery and durable outbox retries. */
final class BotStashHuntTest {
    static JsonObject plan(int index, int workers, int radius) {
        JsonObject p = new JsonObject(); p.addProperty("type", "StashHunt");
        p.addProperty("minX", -131); p.addProperty("maxX", 612); p.addProperty("minZ", -49); p.addProperty("maxZ", 81);
        p.addProperty("y", 180); p.addProperty("workerIndex", index); p.addProperty("workerCount", workers); p.addProperty("radiusChunks", radius);
        return p;
    }
    static void run() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        for (int workers : List.of(1, 2, 3, 5, 16)) for (int radius : List.of(1, 2, 8)) {
            Set<String> coverage = new HashSet<>();
            for (int worker = 0; worker < workers; worker++) {
                BotStashHunt survey = new BotStashHunt(plan(worker, workers, radius));
                for (int i = 0; i < survey.totalRows(); i++) {
                    int[] row = survey.row(i);
                    for (int x = row[0]; x <= row[1]; x++) assert coverage.add(x + ":" + row[2]) : "Workers overlap a strip";
                }
            }
            for (int x = Math.floorDiv(-131, 16); x <= Math.floorDiv(612, 16); x++) for (int z = Math.floorDiv(-49, 16); z <= Math.floorDiv(81, 16); z++)
                assert coverage.remove(x + ":" + z) : "Unclaimed gap between workers";
            assert coverage.isEmpty() : "Coverage escaped the requested chunk bounds";
        }
        BotStashHunt hunt = new BotStashHunt(plan(0, 3, 2));
        JsonObject initial = hunt.snapshot(); int[] first = hunt.row(0);
        assert !hunt.observe(hunt.target(), (x, z) -> x != first[0], (x, z) -> { throw new AssertionError("Partial row must not be scanned"); });
        assert hunt.snapshot().equals(initial) : "Missing chunk cannot advance coverage";
        assert hunt.observe(hunt.target(), (x, z) -> true, BotStashHuntTest::finding);
        JsonObject saved = hunt.snapshot(); assert saved.get("cursor").getAsInt() == 1;
        BotStashHunt restored = new BotStashHunt(plan(0, 3, 2)); restored.restore(saved);
        assert restored.snapshot().equals(saved);
        JsonArray batch = BotStashHunt.batch(saved);
        assert batch.size() == 5 && BotStashHunt.batch(saved).equals(batch) : "Missing ACK resends identical findings";
        restored.acknowledge(batch.get(2).getAsJsonObject().get("delivery").getAsInt());
        assert BotStashHunt.batch(restored.snapshot()).size() == 2;
        restored.acknowledge(3); assert BotStashHunt.batch(restored.snapshot()).size() == 2 : "Duplicate ACK must be idempotent";
        while (!restored.covered() && restored.observe(restored.target(), (x, z) -> true, BotStashHuntTest::finding)) { }
        assert restored.snapshot().getAsJsonArray("pending").size() <= BotStashHunt.OUTBOX;
        JsonObject blocked = restored.snapshot();
        if (!restored.covered()) {
            assert !restored.observe(restored.target(), (x, z) -> true, BotStashHuntTest::finding);
            assert restored.snapshot().equals(blocked) : "Outbox pressure must not skip coverage or drop findings";
            restored.acknowledge(blocked.get("nextId").getAsInt() - 1);
            assert restored.observe(restored.target(), (x, z) -> true, (x, z) -> null) : "Host recovery unblocks local survey";
        }
        BotStashHunt ahead = new BotStashHunt(plan(0, 1, 2));
        ahead.observe(ahead.target().add(0, 0, -20), (x, z) -> true, (x, z) -> null);
        JsonObject partial = ahead.snapshot(); assert partial.get("rowScanned").getAsBoolean();
        BotStashHunt resume = new BotStashHunt(plan(0, 1, 2)); resume.restore(partial);
        assert resume.observe(resume.target(), (x, z) -> true, (x, z) -> null);
        assert resume.snapshot().get("scanned").getAsInt() == 5 : "Checkpoint rescan must not double count coverage";
        double speed = .25;
        for (int i = 0; i < 10000; i++) speed = BotStashHunt.adaptSpeed(speed, 3, .01, false);
        assert speed == 3 && BotStashHunt.adaptSpeed(speed, 3, .01, true) < speed;
        assert BotStashHunt.adaptSpeed(.25, .001, .01, true) <= .001;
        JsonObject invalid = plan(0, 1, 2); invalid.addProperty("maxX", -1000); bad(() -> BotActions.validate(invalid));
        JsonObject nonfinite = plan(0, 1, 2); nonfinite.addProperty("maxSpeed", Double.NaN); bad(() -> BotActions.validate(nonfinite));
        JsonObject foreign = finding(33, -4); bad(() -> BotStashHunt.validateFinding(foreign, BotStashHunt.validate(plan(0, 3, 2))));
        JsonObject run = new JsonObject(), frame = new JsonObject(), packaged = new JsonObject();
        packaged.add("programs", new JsonObject()); run.add("package", packaged);
        JsonArray stack = new JsonArray(); frame.add("state", new JsonObject()); stack.add(frame); run.add("stack", stack);
        run.addProperty("workerIndex", 2); run.addProperty("workerCount", 3); run.addProperty("dimension", "minecraft:the_nether");
        BotRuntime.applyDecision(run, new BotLua.Decision(new JsonObject(), plan(0, 1, 2)));
        JsonObject assigned = frame.getAsJsonObject("action");
        assert assigned.get("workerIndex").getAsInt() == 2 && assigned.get("workerCount").getAsInt() == 3;
        assert assigned.get("dimension").getAsString().equals("minecraft:the_nether") : "Nested Lua surveys inherit immutable host partition and scope";
        var decision = BotLua.next("return function(ctx) return bot.stash_hunt(ctx.args) end", new JsonObject(), plan(0, 1, 2), new JsonObject(), new JsonObject());
        assert decision.action().get("type").getAsString().equals("StashHunt");
        System.out.println("Stash hunt checks passed: exhaustive gap-free partitions, missing-chunk gating, checkpoint replay, bounded outbox, ACK retry and adaptive speed.");
    }
    private static JsonObject finding(int x, int z) {
        JsonObject p = new JsonObject(); p.addProperty("cx", x); p.addProperty("cz", z);
        JsonArray counts = new JsonArray(); for (int n : new int[] {4, 0, 1, 0, 0, 0, 0, 0}) counts.add(n); p.add("counts", counts); return p;
    }
    private static void bad(Runnable action) { try { action.run(); throw new AssertionError("Invalid survey accepted"); } catch (IllegalArgumentException expected) { } }
}
