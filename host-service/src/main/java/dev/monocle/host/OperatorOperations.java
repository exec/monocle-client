package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.TaskFiles;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Durable HTTP command receipts. An interrupted command is never replayed blindly. */
final class OperatorOperations {
    static final class JournalException extends RuntimeException {
        JournalException(Throwable cause) { super("Operator receipt storage is unavailable; inspect the operation before retrying", cause); }
    }
    private static final int LIMIT = 1024;
    private final Path file;
    private final JsonObject records;

    OperatorOperations(Path file) {
        this.file = file;
        records = TaskFiles.read(file);
        if (records.size() > LIMIT) throw new IllegalStateException("Operator receipt journal exceeds its limit");
    }

    synchronized JsonObject get(String id) {
        JsonElement record = records.get(uuid(id));
        return record == null ? null : record.getAsJsonObject().deepCopy();
    }

    synchronized JsonObject begin(String id, String fingerprint) {
        id = uuid(id);
        JsonObject prior = get(id);
        if (prior != null) {
            if (!prior.get("fingerprint").getAsString().equals(fingerprint)) throw new IllegalStateException("Command ID was already used for a different request");
            return prior;
        }
        JsonObject previous = records.deepCopy();
        if (records.size() == LIMIT) {
            String oldest = records.keySet().iterator().next();
            records.remove(oldest);
        }
        JsonObject record = new JsonObject();
        record.addProperty("id", id);
        record.addProperty("correlationId", id);
        record.addProperty("fingerprint", fingerprint);
        record.addProperty("state", "outcome_uncertain");
        record.addProperty("acceptedAt", Instant.now().toString());
        records.add(id, record);
        try { checkpoint(id); }
        catch (RuntimeException e) { records.entrySet().clear(); previous.entrySet().forEach(entry -> records.add(entry.getKey(), entry.getValue())); throw new JournalException(e); }
        return record.deepCopy();
    }

    synchronized JsonObject finish(String id, int status, JsonObject response, boolean asynchronous) {
        JsonObject record = records.get(uuid(id)).getAsJsonObject();
        JsonObject previous = records.deepCopy();
        record.addProperty("state", asynchronous && status < 400 ? "host_accepted" : "completed");
        record.addProperty("httpStatus", status);
        record.addProperty("updatedAt", Instant.now().toString());
        record.add("response", response.deepCopy());
        try { checkpoint(id); }
        catch (RuntimeException e) { records.entrySet().clear(); previous.entrySet().forEach(entry -> records.add(entry.getKey(), entry.getValue())); throw new JournalException(e); }
        return record.deepCopy();
    }

    private void checkpoint(String current) {
        while (records.toString().getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024 && records.size() > 1) {
            String oldest = records.keySet().stream().filter(id -> !id.equals(current)).findFirst().orElseThrow();
            records.remove(oldest);
        }
        TaskFiles.jsonBytes(records, 16 * 1024 * 1024);
        TaskFiles.write(file, records);
    }

    private static String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Idempotency-Key must be a UUID"); }
    }
}
