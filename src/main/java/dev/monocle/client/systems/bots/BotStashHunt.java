package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.world.StashFinder;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Disjoint chunk strips, local scanning and a bounded, acknowledged findings outbox. */
public final class BotStashHunt {
    public static final String WORKFLOW = "task-stash-hunt";
    public static final int BATCH = 16, OUTBOX = 128;
    private final JsonObject plan;
    private final List<JsonObject> pending = new ArrayList<>();
    private int cursor, scanned, nextId = 1;
    private double speed = .25;
    private boolean rowScanned;
    private volatile boolean corrected;

    public BotStashHunt(JsonObject plan) { this.plan = validate(plan); }

    public static JsonObject validate(JsonObject input) {
        JsonObject p = input.deepCopy();
        for (String axis : List.of("minX", "maxX", "minZ", "maxZ")) integer(p, axis, -29_900_000, 29_900_000);
        integer(p, "y", -2048, 2048);
        defaults(p, "radiusChunks", 2); integer(p, "radiusChunks", 1, 8);
        defaults(p, "maxSpeed", 60); number(p, "maxSpeed", 1, 120);
        defaults(p, "acceleration", 4); number(p, "acceleration", .1, 40);
        defaults(p, "workerIndex", 0); defaults(p, "workerCount", 1);
        integer(p, "workerCount", 1, 16); integer(p, "workerIndex", 0, p.get("workerCount").getAsInt() - 1);
        if (chunk(p, "minX") > chunk(p, "maxX") || chunk(p, "minZ") > chunk(p, "maxZ")
            || p.get("minX").getAsInt() > p.get("maxX").getAsInt() || p.get("minZ").getAsInt() > p.get("maxZ").getAsInt())
            throw new IllegalArgumentException("Search minimum coordinates must not exceed maximum coordinates");
        long area = (long) (chunk(p, "maxX") - chunk(p, "minX") + 1) * (chunk(p, "maxZ") - chunk(p, "minZ") + 1);
        if (area > 1_048_576) throw new IllegalArgumentException("Limit one survey to 1,048,576 chunks; queue another area afterward");
        return p;
    }
    private static void defaults(JsonObject p, String key, Number value) { if (!p.has(key)) p.addProperty(key, value); }
    private static double number(JsonObject p, String key, double min, double max) {
        if (!p.has(key) || !p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Expected numeric " + key);
        double n = p.get(key).getAsDouble();
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException("Invalid " + key);
        return n;
    }
    private static int integer(JsonObject p, String key, int min, int max) {
        double n = number(p, key, min, max); if (n != Math.rint(n)) throw new IllegalArgumentException("Expected integer " + key); return (int) n;
    }
    private static int chunk(JsonObject p, String key) { return Math.floorDiv(p.get(key).getAsInt(), 16); }
    private int rows() { return chunk(plan, "maxZ") - chunk(plan, "minZ") + 1; }
    private int width() { return plan.get("radiusChunks").getAsInt() * 2 + 1; }
    private int workers() { return plan.get("workerCount").getAsInt(); }
    private int lane(int position) { return plan.get("workerIndex").getAsInt() + position / rows() * workers(); }
    private int lanes() { return Math.ceilDiv(chunk(plan, "maxX") - chunk(plan, "minX") + 1, width()); }
    public int totalRows() { return Math.max(0, Math.ceilDiv(lanes() - plan.get("workerIndex").getAsInt(), workers())) * rows(); }
    public boolean covered() { return cursor >= totalRows(); }
    public int[] row(int position) {
        if (position < 0 || position >= totalRows()) throw new IllegalArgumentException("Invalid survey row");
        int left = chunk(plan, "minX") + lane(position) * width(), right = Math.min(chunk(plan, "maxX"), left + width() - 1);
        int z = position / rows() % 2 == 0 ? chunk(plan, "minZ") + position % rows() : chunk(plan, "maxZ") - position % rows();
        return new int[] {left, right, z};
    }
    public Vec3 target() {
        int[] r = row(Math.min(cursor, totalRows() - 1));
        return new Vec3((r[0] + r[1]) * 8.0 + 8, plan.get("y").getAsInt(), r[2] * 16.0 + 8);
    }
    public void correction() { corrected = true; }
    public double speed(double cap, boolean blocked) {
        speed = adaptSpeed(speed, Math.min(cap, plan.get("maxSpeed").getAsDouble() / 20), plan.get("acceleration").getAsDouble() / 400, blocked || corrected);
        corrected = false; return speed;
    }
    static double adaptSpeed(double previous, double cap, double increment, boolean blocked) {
        return Math.min(cap, Math.max(.05, blocked ? previous * .65 : previous + increment));
    }
    /** Scan only FULL received chunks. Missing rows never advance the durable coverage cursor. */
    public boolean observe(Vec3 from) {
        StashFinder finder = Modules.get().get(StashFinder.class);
        return observe(from, finder::surveyChunkLoaded, finder::surveyScan);
    }
    boolean observe(Vec3 from, java.util.function.BiPredicate<Integer, Integer> loaded, java.util.function.BiFunction<Integer, Integer, JsonObject> scan) {
        if (covered()) return pending.isEmpty();
        Vec3 target = target();
        if (from.distanceToSqr(target) > 32 * 32) return true; // Still approaching this strip at its configured altitude.
        if (pending.size() + width() > OUTBOX) return false;
        int[] r = row(cursor);
        if (!rowScanned) {
            for (int x = r[0]; x <= r[1]; x++) if (!loaded.test(x, r[2])) return from.distanceToSqr(target) > 8 * 8;
            for (int x = r[0]; x <= r[1]; x++) {
                JsonObject finding = scan.apply(x, r[2]);
                if (finding != null) { finding.addProperty("delivery", nextId++); pending.add(finding); }
            }
            scanned += r[1] - r[0] + 1; rowScanned = true;
        }
        if (from.distanceToSqr(target) <= 8 * 8) { cursor++; rowScanned = false; }
        return true;
    }
    public String detail() {
        return cursor + "/" + totalRows() + " strip rows · " + scanned + " chunks scanned · " + String.format(Locale.ROOT, "%.1f", speed * 20)
            + " blocks/sec target · " + (nextId - 1) + " findings · " + pending.size() + " awaiting host save";
    }
    public JsonObject snapshot() {
        JsonObject s = new JsonObject(); s.addProperty("cursor", cursor); s.addProperty("scanned", scanned); s.addProperty("nextId", nextId);
        s.addProperty("rowScanned", rowScanned);
        JsonArray outbox = new JsonArray(); pending.forEach(p -> outbox.add(p.deepCopy())); s.add("pending", outbox); return s;
    }
    public void restore(JsonObject saved) {
        cursor = integer(saved, "cursor", 0, totalRows()); scanned = integer(saved, "scanned", 0, 1_048_576);
        if (!covered() && saved.has("rowScanned") && saved.get("rowScanned").getAsBoolean()) {
            int[] row = row(cursor); scanned = Math.max(0, scanned - (row[1] - row[0] + 1));
        }
        nextId = integer(saved, "nextId", 1, 1_048_577); pending.clear();
        JsonArray entries = saved.getAsJsonArray("pending");
        if (entries.size() > OUTBOX) throw new IllegalArgumentException("Survey outbox exceeds its bound");
        int last = 0;
        for (JsonElement entry : entries) {
            JsonObject finding = entry.getAsJsonObject(); int id = integer(finding, "delivery", 1, nextId - 1);
            if (id <= last) throw new IllegalArgumentException("Survey delivery order is invalid");
            validateFinding(finding, plan); last = id; pending.add(finding.deepCopy());
        }
        rowScanned = false; speed = .25;
    }
    public static void validateFinding(JsonObject finding, JsonObject plan) {
        int x = integer(finding, "cx", chunk(plan, "minX"), chunk(plan, "maxX")), z = integer(finding, "cz", chunk(plan, "minZ"), chunk(plan, "maxZ"));
        int lane = (x - chunk(plan, "minX")) / (plan.get("radiusChunks").getAsInt() * 2 + 1);
        if (lane % plan.get("workerCount").getAsInt() != plan.get("workerIndex").getAsInt()) throw new IllegalArgumentException("Finding is outside this worker's strip");
        StashFinder.decodeFinding(finding);
    }
    public static JsonArray batch(JsonObject saved) {
        JsonArray batch = new JsonArray(); JsonArray pending = saved.getAsJsonArray("pending");
        for (int i = 0; i < Math.min(BATCH, pending.size()); i++) batch.add(pending.get(i).deepCopy()); return batch;
    }
    public void acknowledge(int delivery) { pending.removeIf(p -> p.get("delivery").getAsInt() <= delivery); }
    public static void acknowledge(JsonObject saved, int delivery) {
        JsonArray kept = new JsonArray(); for (JsonElement p : saved.getAsJsonArray("pending")) if (p.getAsJsonObject().get("delivery").getAsInt() > delivery) kept.add(p);
        saved.add("pending", kept);
    }
}
