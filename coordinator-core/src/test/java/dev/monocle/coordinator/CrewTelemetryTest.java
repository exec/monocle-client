package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class CrewTelemetryTest {
    static void run() throws Exception {
        List<JsonObject> records = new ArrayList<>(); CrewTelemetry log = new CrewTelemetry(records::add);
        JsonObject snapshot = snapshot(0);
        for (int i = 0; i <= 4; i++) log.sample(snapshot, 1_000_000_000L + i * 250_000_000L);
        assert records.stream().anyMatch(e -> event(e, "before-wait"));
        JsonObject wait = records.stream().filter(e -> event(e, "wait-observed")).findFirst().orElseThrow();
        assert wait.get("noRoadProgressMs").getAsLong() == 1000;
        assert wait.getAsJsonArray("stationaryWorkers").size() == 1;
        assert !snapshot.has("at") && !snapshot.getAsJsonArray("workers").get(0).getAsJsonObject().has("stationaryMs") : "Telemetry cannot mutate controller inputs";
        int size = records.size(); log.sample(snapshot, 2_100_000_000L); assert records.size() == size : "Sub-250ms reads are bounded";
        log.sample(snapshot(1), 2_250_000_000L);
        assert event(records.getLast(), "movement-resumed") : "Even a short recovered stall leaves evidence";
        log.sample(snapshot(2), 4_250_000_000L);
        assert records.stream().anyMatch(e -> event(e, "sampling-gap")) : "A blocked host thread must not hide its sampling gap";
        JsonObject ended = snapshot(2); ended.addProperty("execution", ""); log.sample(ended, 4_500_000_000L);
        assert event(records.getLast(), "execution-ended-or-replaced") && records.getLast().get("previousExecution").getAsString().equals("job-a");

        var trace = new CrewTelemetry.Trace();
        trace.observe("eating", "Forward", "Paused for eating", 100, 1_000_000_000L);
        trace.observe("eating", "Forward", "Paused for eating", 101, 1_050_000_000L);
        trace.observe("forward:advance", "Forward", "Healthy", 102, 1_100_000_000L);
        JsonObject d = new JsonObject(); trace.attach(d, 1_200_000_000L);
        assert d.get("decisionSequence").getAsLong() == 2 && d.getAsJsonArray("decisions").size() == 2;
        assert d.getAsJsonArray("decisions").get(1).getAsJsonObject().get("previousDurationMs").getAsLong() == 100;
        assert d.get("decisionAgeMs").getAsLong() == 100 : "Worker monotonic durations do not depend on host wall clock";
        for (int i = 0; i < 30; i++) trace.observe("gate-" + i, "Restock", "界".repeat(1000), i, 2_000_000_000L + i * 50_000_000L);
        d = new JsonObject(); trace.attach(d, 4_000_000_000L); assert d.getAsJsonArray("decisions").size() == 8;
        d.addProperty("large", "界".repeat(4000)); trace.attach(d, 4_000_000_000L);
        assert d.toString().getBytes(StandardCharsets.UTF_8).length <= 8192 && d.get("diagnosticsTruncated").getAsBoolean();

        Path dir = Files.createTempDirectory("monocle-telemetry-check-");
        try {
            Path file = dir.resolve("crew.telemetry.jsonl");
            for (int i = 0; i < 50; i++) CrewTelemetry.append(file, "{\"seq\":" + i + "}\n", 64);
            assert Files.exists(dir.resolve("crew.telemetry.jsonl.3")) && !Files.exists(dir.resolve("crew.telemetry.jsonl.4"));
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) { assert Files.size(p) <= 64; for (String line : Files.readAllLines(p)) assert JsonParser.parseString(line).isJsonObject(); }
            }
            Path parentFile = dir.resolve("not-a-directory"); Files.writeString(parentFile, "unchanged");
            CrewTelemetry broken = new CrewTelemetry(parentFile.resolve("crew.jsonl")); broken.event(new JsonObject());
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (broken.error().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assert !broken.error().isEmpty() && broken.dropped() > 0 : "A failed telemetry write is visible, not fatal";
            assert Files.readString(parentFile).equals("unchanged");
        } finally {
            try (var files = Files.walk(dir)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
        System.out.println("Crew telemetry checks passed: short waits/recovery, prehistory, sampling gaps, bounded decisions/UTF-8, rotation and nonfatal disk failure.");
    }
    private static boolean event(JsonObject record, String name) { return record.get("event").getAsString().equals(name); }
    private static JsonObject snapshot(int row) {
        JsonObject d = new JsonObject(); d.addProperty("execution", "job-a"); d.addProperty("phase", "building"); d.addProperty("progress", row);
        JsonObject worker = new JsonObject(); worker.addProperty("id", "worker-a"); worker.addProperty("currentRow", row);
        worker.addProperty("x", 0); worker.addProperty("y", 116); worker.addProperty("z", -row); worker.addProperty("fresh", true);
        JsonArray workers = new JsonArray(); workers.add(worker); d.add("workers", workers); return d;
    }
}
