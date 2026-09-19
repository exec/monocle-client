package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Observations only: never grants permits, pauses work or changes resource ownership. */
public final class CrewTelemetry {
    private static final long SECOND = 1_000_000_000L;
    private static final int FILE_BYTES = 16 * 1024 * 1024;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("Monocle telemetry").factory(), new ThreadPoolExecutor.AbortPolicy());
    private final Consumer<JsonObject> sink;
    private final Deque<JsonObject> before = new ArrayDeque<>();
    private final Map<String, String> positions = new HashMap<>();
    private final Map<String, Long> movedAt = new HashMap<>();
    private String execution = "", signature = "";
    private int progress;
    private long sampleAt, progressAt, emittedAt;
    private boolean stalled;
    private String waitingWorkers = "";
    private final AtomicLong dropped = new AtomicLong();
    private volatile String error = "";
    private volatile long retryAt;

    public CrewTelemetry(Path file) {
        sink = record -> {
            if (System.nanoTime() < retryAt) { dropped.incrementAndGet(); return; }
            // Bounded best-effort queue: a slow/full disk must never hold the gameplay thread.
            try { WRITER.execute(() -> {
                try { append(file, record.toString() + "\n", FILE_BYTES); error = ""; }
                catch (IOException | RuntimeException e) {
                    dropped.incrementAndGet(); error = e.getClass().getSimpleName(); retryAt = System.nanoTime() + 60 * SECOND;
                    System.err.println("Monocle telemetry unavailable at " + file + ": " + error + "; gameplay continues.");
                }
            }); } catch (RejectedExecutionException e) { dropped.incrementAndGet(); error = "Telemetry queue full"; }
        };
    }
    CrewTelemetry(Consumer<JsonObject> sink) { this.sink = sink; }
    public long dropped() { return dropped.get(); }
    public String error() { return error; }
    public boolean due(long now) { return sampleAt == 0 || now - sampleAt >= SECOND / 4; }
    public void event(JsonObject input) {
        JsonObject record = input.deepCopy(); record.addProperty("at", System.currentTimeMillis()); sink.accept(record);
    }

    /** Adapter supplies authenticated, allowlisted data. Durations use host time, not worker clocks. */
    public void sample(JsonObject input, long now) {
        if (!due(now)) return;
        JsonObject sample = input.deepCopy();
        sample.addProperty("at", System.currentTimeMillis());
        sample.addProperty("sampleGapMs", sampleAt == 0 ? 0 : (now - sampleAt) / 1_000_000);
        sampleAt = now;
        String nextJob = TaskWire.text(sample, "execution");
        int row = sample.has("progress") ? sample.get("progress").getAsInt() : 0;
        if (!execution.equals(nextJob)) {
            if (!execution.isEmpty()) { sample.addProperty("previousExecution", execution); emit("execution-ended-or-replaced", sample, now); sample.remove("previousExecution"); }
            execution = nextJob; progress = row; progressAt = now; stalled = false;
            before.clear(); positions.clear(); movedAt.clear(); signature = ""; waitingWorkers = ""; emittedAt = 0;
        }
        if (execution.isEmpty()) return;
        if (row != progress) { progress = row; progressAt = now; }
        JsonArray waits = new JsonArray(); Set<String> present = new HashSet<>();
        StringBuilder key = new StringBuilder(TaskWire.text(sample, "phase"));
        for (String field : List.of("activeMembers", "suppliers", "supplyOwner", "granted", "regrouping", "releasing")) key.append(sample.get(field));
        for (JsonElement value : sample.getAsJsonArray("workers")) {
            JsonObject w = value.getAsJsonObject(); String id = TaskWire.text(w, "id"); present.add(id);
            String position = "" + w.get("currentRow") + ":" + w.get("x") + ":" + w.get("y") + ":" + w.get("z");
            if (!position.equals(positions.put(id, position))) movedAt.put(id, now);
            long stationary = (now - movedAt.getOrDefault(id, now)) / 1_000_000;
            w.addProperty("stationaryMs", stationary);
            if (stationary >= 1000) waits.add(id);
            key.append(id).append(w.get("phase")).append(w.get("fresh"));
            if (w.has("diagnostics")) {
                var d = w.getAsJsonObject("diagnostics"); key.append(d.get("gate")).append(d.get("state"));
            }
        }
        positions.keySet().retainAll(present); movedAt.keySet().retainAll(present);
        long roadWait = (now - progressAt) / 1_000_000;
        sample.addProperty("noRoadProgressMs", roadWait); sample.add("stationaryWorkers", waits);
        sample.addProperty("droppedLogRecords", dropped.get());
        if (sample.get("sampleGapMs").getAsLong() >= 1000) emit("sampling-gap", sample, now);
        boolean waiting = roadWait >= 1000 || !waits.isEmpty();
        String nextSignature = key.toString();
        if (waiting && !stalled) {
            for (JsonObject prior : before) emit("before-wait", prior, now);
            emit("wait-observed", sample, now);
        } else if (!waiting && stalled) emit("movement-resumed", sample, now);
        else if (stalled && !waits.toString().equals(waitingWorkers)) emit("stationary-workers-changed", sample, now);
        else if (emittedAt == 0 || now - emittedAt >= (waiting ? 2 : 30) * SECOND
            || !nextSignature.equals(signature) && now - emittedAt >= SECOND) emit(waiting ? "wait-sample" : "state-sample", sample, now);
        stalled = waiting; signature = nextSignature; waitingWorkers = waits.toString();
        if (before.size() == 12) before.removeFirst(); before.addLast(sample);
    }
    private void emit(String kind, JsonObject sample, long now) {
        JsonObject record = sample.deepCopy(); record.addProperty("event", kind); sink.accept(record); emittedAt = now;
    }

    /** Four bounded generations, separate from recovery journals. Only the writer thread touches these. */
    static void append(Path file, String line, int limit) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file) && Files.size(file) + line.getBytes(StandardCharsets.UTF_8).length > limit) {
            for (int i = 2; i >= 0; i--) {
                Path from = i == 0 ? file : file.resolveSibling(file.getFileName() + "." + i);
                Path to = file.resolveSibling(file.getFileName() + "." + (i + 1));
                if (Files.exists(from)) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Final per-tick decisions retain transitions between network snapshots. */
    public static final class Trace {
        private final String session = UUID.randomUUID().toString();
        private final Deque<JsonObject> events = new ArrayDeque<>();
        private String last = "";
        private long sequence, since;
        public void observe(String gate, String state, String reason, int tick, long now) {
            gate = clip(gate, 80); state = clip(state, 40); reason = clip(reason, 160);
            String next = gate + "\n" + state + "\n" + reason;
            if (next.equals(last)) return;
            JsonObject event = new JsonObject(); event.addProperty("seq", ++sequence); event.addProperty("at", System.currentTimeMillis());
            event.addProperty("tick", tick); event.addProperty("previousDurationMs", since == 0 ? 0 : (now - since) / 1_000_000);
            event.addProperty("gate", gate); event.addProperty("state", state); event.addProperty("reason", reason);
            if (events.size() == 8) events.removeFirst(); events.addLast(event); last = next; since = now;
        }
        public void attach(JsonObject d, long now) {
            d.addProperty("traceSession", session); d.addProperty("decisionSequence", sequence);
            d.addProperty("decisionAgeMs", since == 0 ? -1 : (now - since) / 1_000_000);
            JsonArray changes = new JsonArray(); events.forEach(e -> changes.add(e.deepCopy())); d.add("decisions", changes);
            // Keep compatibility with the existing 8 KiB diagnostic protocol budget, including UTF-8.
            while (bytes(d) > 8192 && !changes.isEmpty()) { changes.remove(0); d.addProperty("diagnosticsTruncated", true); }
            if (bytes(d) > 8192) { d.remove("pendingBlocks"); d.addProperty("diagnosticsTruncated", true); }
            if (bytes(d) > 8192) {
                JsonObject minimal = new JsonObject();
                for (String field : List.of("version", "capturedAt", "gate", "tickAgeMs", "traceSession", "decisionSequence")) if (d.has(field)) minimal.add(field, d.get(field));
                d.entrySet().clear(); minimal.entrySet().forEach(e -> d.add(e.getKey(), e.getValue())); d.addProperty("diagnosticsTruncated", true);
            }
        }
        private static int bytes(JsonObject d) { return d.toString().getBytes(StandardCharsets.UTF_8).length; }
        private static String clip(String value, int limit) { return value.substring(0, Math.min(limit, value.length())); }
    }
}
