package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;

/** Bounded, session-only chat feed shared by both host adapters. Never queues chat for offline workers. */
public final class BotChat {
    private final Deque<JsonObject> feed = new ArrayDeque<>();
    private final Set<UUID> executed = new LinkedHashSet<>();
    public static String command(String text) {
        if (text == null || text.isBlank() || text.length() > 256 || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use 1–256 printable chat characters");
        return text;
    }
    public boolean first(UUID id) { if (!executed.add(id)) return false; if (executed.size() > 128) executed.remove(executed.iterator().next()); return true; }
    public void append(UUID worker, String crew, String name, String scope, String direction, String text) {
        if (!Set.of("received", "sent", "error").contains(direction) || text == null || text.length() > 2048)
            throw new IllegalArgumentException("Invalid worker chat report");
        JsonObject row = new JsonObject(); row.addProperty("worker",worker.toString());row.addProperty("crew",crew);row.addProperty("name",name);
        row.addProperty("scope",scope);row.addProperty("direction",direction);row.addProperty("text",text);row.addProperty("at",System.currentTimeMillis());
        feed.addLast(row);while(feed.size()>256)feed.removeFirst();
    }
    public JsonArray json() { JsonArray result=new JsonArray();feed.forEach(row->result.add(row.deepCopy()));return result; }

    public void append(UUID worker, String crew, String name, String scope, String direction, String text, JsonArray parts) {
        if (parts != null) {
            if (parts.size() > 256) throw new IllegalArgumentException("Too many chat color runs");
            StringBuilder plain = new StringBuilder();
            for (JsonElement value : parts) {
                JsonObject part = value.getAsJsonObject();
                plain.append(part.get("text").getAsString());
                if (part.has("color") && (!part.get("color").isJsonPrimitive()
                    || !part.get("color").getAsString().matches("#[0-9a-fA-F]{6}"))) throw new IllegalArgumentException("Invalid chat color");
            }
            // Translated/server-modified components can stringify differently from their styled traversal.
            // Plain text is authoritative; discard only inconsistent presentation data.
            if (!plain.toString().equals(text)) parts = null;
        }
        append(worker, crew, name, scope, direction, text);
        if (parts != null) feed.getLast().add("parts", parts.deepCopy());
    }

    /** Presentation only: retain raw receipts for each worker and combine cross-worker duplicates. */
    public static JsonArray grouped(JsonArray raw) {
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement value : raw) {
            JsonObject receipt = value.getAsJsonObject(), match = null;
            if (receipt.get("direction").getAsString().equals("received")) {
                for (int i = rows.size() - 1; i >= 0; i--) {
                    JsonObject candidate = rows.get(i);
                    long age = receipt.get("at").getAsLong() - candidate.get("at").getAsLong();
                    if (age > 1500) break;
                    if (age >= 0 && candidate.get("direction").equals(receipt.get("direction"))
                        && candidate.get("crew").equals(receipt.get("crew")) && candidate.get("scope").equals(receipt.get("scope"))
                        && candidate.get("text").equals(receipt.get("text"))) {
                        if (!Objects.equals(candidate.get("parts"), receipt.get("parts"))) continue;
                        if (candidate.getAsJsonArray("recipients").asList().stream().anyMatch(r -> r.getAsJsonObject().get("worker").equals(receipt.get("worker")))) break;
                        match = candidate; break;
                    }
                }
            }
            if (match == null) {
                match = receipt.deepCopy(); match.add("recipients", new JsonArray()); rows.add(match);
            }
            JsonObject recipient = new JsonObject();recipient.add("worker",receipt.get("worker"));recipient.add("name",receipt.get("name"));
            match.getAsJsonArray("recipients").add(recipient);
            String names = String.join(", ", match.getAsJsonArray("recipients").asList().stream().map(r -> r.getAsJsonObject().get("name").getAsString()).toList());
            match.addProperty("name", names);
        }
        JsonArray result = new JsonArray(); rows.forEach(result::add); return result;
    }
}
