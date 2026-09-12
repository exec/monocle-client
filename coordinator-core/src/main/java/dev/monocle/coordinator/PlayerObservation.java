package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable game observation. Receipt time is supplied by the adapter's monotonic clock. */
public record PlayerObservation(UUID id, String name, String scope, Position position, long receivedAt) {
    public static final long MAX_AGE = 5_000_000_000L;

    public record Position(double x, double y, double z) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Non-finite player position");
        }
        public double distanceSquared(Position target) {
            double dx = x - target.x, dy = y - target.y, dz = z - target.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public PlayerObservation {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(scope);
        if (name.length() > 64 || scope.length() > 1024) throw new IllegalArgumentException("Invalid peer metadata");
    }

    /** Decode only roster fields; no mutable protocol object crosses into decisions. */
    public static PlayerObservation fromHello(JsonObject report, long receivedAt) {
        if (report.has("diagnostics")) {
            if (!report.get("diagnostics").isJsonObject()) throw new IllegalArgumentException("Invalid worker diagnostics");
            TaskFiles.jsonBytes(report.getAsJsonObject("diagnostics"), 8192);
        }
        Position position = null;
        if (report.has("x") || report.has("y") || report.has("z")) {
            double[] xyz = new double[3]; int index = 0;
            for (String key : new String[]{"x", "y", "z"}) {
                var value = report.get(key);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("Incomplete worker position");
                xyz[index++] = value.getAsDouble();
            }
            position = new Position(xyz[0], xyz[1], xyz[2]);
        }
        return new PlayerObservation(UUID.fromString(report.get("id").getAsString()), report.get("name").getAsString(), report.get("scope").getAsString(), position, receivedAt);
    }

    public boolean fresh(long now) { long age = now - receivedAt; return age >= 0 && age < MAX_AGE; }
    public boolean inWorld(String expectedScope, long now) {
        return fresh(now) && !scope.isEmpty() && scope.equals(expectedScope);
    }
    public boolean nearby(String expectedScope, Position target, double radius, boolean box, long now) {
        return inWorld(expectedScope, now) && position != null && position.y == target.y && (box
            ? Math.abs(position.x - target.x) <= radius && Math.abs(position.z - target.z) <= radius
            : position.distanceSquared(target) <= radius * radius);
    }

    /** Caller supplies only authenticated, current-session members of the requested crew. */
    public static PlayerObservation target(String selector, PlayerObservation local, Collection<PlayerObservation> crew, long now) {
        if (matches(local, selector, now)) return local;
        PlayerObservation selected = null;
        for (PlayerObservation candidate : crew) if (matches(candidate, selector, now)) {
            if (selected != null && !selected.id.equals(candidate.id)) return null; // Ambiguous names never pick an arbitrary recipient.
            selected = candidate;
        }
        return selected;
    }
    private static boolean matches(PlayerObservation player, String selector, long now) {
        return player != null && player.fresh(now) && !player.scope.isEmpty()
            && (player.id.toString().equals(selector) || player.name.equalsIgnoreCase(selector));
    }

    /** Local host remains an anchor only when it is in the active roster and correct world. */
    public static UUID anchor(Collection<UUID> active, UUID except, PlayerObservation local,
                              Map<UUID, PlayerObservation> crew, String scope, Position site, long now) {
        for (UUID id : active) {
            if (id.equals(except)) continue;
            if (local != null && id.equals(local.id) && local.inWorld(scope, now)) return id;
            PlayerObservation player = crew.get(id);
            if (player != null && player.inWorld(scope, now) && player.position != null && player.position.distanceSquared(site) < 32 * 32) return id;
        }
        return null;
    }
}
