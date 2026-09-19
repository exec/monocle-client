package dev.monocle.coordinator;
import com.google.gson.*;

/** Wire resource quantities and policy; contains no inventory or game actions. */
public class ResourceLedger {
    protected ResourceLedger() {}
    public static final int MATERIALS = 0, PICKS = 1, FOOD = 2, FILLERS = 3, ECHESTS = 4, RESOURCES = 5;
    public record Policy(boolean enabled, boolean trash, int paving, int picks, int food, int filler) {
        public Policy {
            if (paving < 64 || paving > 1536 || picks < 2 || picks > 9 || food < 16 || food > 256 || filler < 0 || filler > 256)
                throw new IllegalArgumentException("Invalid crew loadout quantities");
        }
        public static Policy defaults() { return new Policy(true, true, 512, 3, 64, 64); }
        public JsonObject json() {
            JsonObject o = new JsonObject(); o.addProperty("enabled", enabled); o.addProperty("trash", trash);
            o.addProperty("paving", paving); o.addProperty("picks", picks); o.addProperty("food", food); o.addProperty("filler", filler); return o;
        }
        public static Policy read(JsonObject layout) {
            if (!layout.has("inventory")) return defaults();
            JsonObject o = layout.getAsJsonObject("inventory");
            if (o.size() != 6 || !o.getAsJsonPrimitive("enabled").isBoolean() || !o.getAsJsonPrimitive("trash").isBoolean())
                throw new IllegalArgumentException("Invalid crew inventory policy");
            return new Policy(o.get("enabled").getAsBoolean(), o.get("trash").getAsBoolean(), integer(o, "paving"), integer(o, "picks"), integer(o, "food"), integer(o, "filler"));
        }
        public int target(int resource) { return switch (resource) { case MATERIALS -> paving; case PICKS -> picks; case FOOD -> food; case FILLERS -> filler; case ECHESTS -> 4; default -> throw new IllegalArgumentException("Unknown resource"); }; }
        public int reserve(int resource) { return switch (resource) { case MATERIALS -> Math.min(paving, 128); case PICKS, ECHESTS -> 2; case FOOD -> 16; case FILLERS -> Math.min(filler, 16); default -> throw new IllegalArgumentException("Unknown resource"); }; }
    }

    public static int integer(JsonObject o, String key) {
        if (!o.has(key) || !o.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Missing number: " + key);
        return o.get(key).getAsBigDecimal().intValueExact();
    }

    public static String name(int resource) { return switch (resource) { case MATERIALS -> "paving blocks"; case PICKS -> "pickaxes"; case FOOD -> "food"; case FILLERS -> "filler blocks"; case ECHESTS -> "ender chests"; default -> throw new IllegalArgumentException("Unknown resource"); }; }

    public static JsonArray array(int[] values) { JsonArray result = new JsonArray(); for (int value : values) result.add(value); return result; }
    public static int value(JsonObject ledger, String tier, int resource) {
        if (ledger == null || !ledger.has(tier)) return 0;
        JsonArray values = ledger.getAsJsonArray(tier);
        if (values.size() != RESOURCES) throw new IllegalArgumentException("Invalid resource ledger");
        int value = values.get(resource).getAsBigDecimal().intValueExact();
        if (value < 0 || value > 2_000_000) throw new IllegalArgumentException("Invalid resource count");
        return value;
    }
    public static int available(JsonObject ledger, int resource) {
        return value(ledger, "loose", resource) + value(ledger, "shulkers", resource) + value(ledger, "echest", resource);
    }
    public static boolean possibleDonor(JsonObject ledger, int resource) {
        return available(ledger, resource) > value(ledger,"target",resource)
            || ledger.has("echestKnown") && !ledger.get("echestKnown").getAsBoolean() && available(ledger, ECHESTS) > 0;
    }
    /** Stable API view; inventory includes resources inside carried shulkers. */
    public static JsonObject resourceCounts(JsonObject ledger) {
        JsonObject inventory = counts(value(ledger, "loose", MATERIALS) + value(ledger, "shulkers", MATERIALS), value(ledger, "loose", PICKS) + value(ledger, "shulkers", PICKS), value(ledger, "loose", FOOD) + value(ledger, "shulkers", FOOD));
        JsonObject chest = counts(value(ledger, "echest", MATERIALS), value(ledger, "echest", PICKS), value(ledger, "echest", FOOD));
        JsonObject result = new JsonObject(); result.add("inventory", inventory); result.add("enderChest", chest);
        result.add("total", counts(inventory.get("obsidian").getAsInt() + chest.get("obsidian").getAsInt(), inventory.get("pickaxes").getAsInt() + chest.get("pickaxes").getAsInt(), inventory.get("food").getAsInt() + chest.get("food").getAsInt()));
        result.addProperty("enderChestKnown", ledger != null && ledger.has("echestKnown") && ledger.get("echestKnown").getAsBoolean()); return result;
    }
    private static JsonObject counts(int obsidian, int picks, int food) { JsonObject r = new JsonObject(); r.addProperty("obsidian", obsidian); r.addProperty("pickaxes", picks); r.addProperty("food", food); return r; }
    public static int surplus(JsonObject ledger, int resource) { return Math.max(0, available(ledger, resource) - value(ledger, "reserve", resource)); }
    /** A shared handoff fills a second working loadout, rather than bouncing for one target-sized refill. */
    public static int exchangeTarget(JsonObject ledger, int resource) {
        return switch (resource) {
            case MATERIALS -> 1536;
            case PICKS, ECHESTS -> 9;
            case FOOD, FILLERS -> 256;
            default -> throw new IllegalArgumentException("Unknown resource");
        };
    }
    /** Bound a donor's temporary carried load; it keeps its normal target after each batch. */
    public static int exchangeBatch(int resource) {
        return switch (resource) {
            case MATERIALS -> 512;
            case PICKS -> 3;
            case FOOD, FILLERS -> 64;
            case ECHESTS -> 2;
            default -> throw new IllegalArgumentException("Unknown resource");
        };
    }
    public static boolean transferConfirmed(String donor, String receiver) { return donor.equals("sent") && receiver.equals("received"); }
}
