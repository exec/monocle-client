package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;

/** Shared dispatch envelope and report transitions; adapters validate native action semantics. */
public final class TaskWire {
    private TaskWire() { }
    public static String text(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsString() : ""; }
    public static boolean flag(JsonObject object, String key) { return object.has(key) && object.get(key).getAsBoolean(); }
    public static void validateProgress(JsonObject message, boolean full) {
        if (!message.has("actionProgress")) return;
        if (!full || !message.has("action") || !message.get("action").isJsonObject() || !message.get("actionProgress").isJsonObject()) throw new IllegalArgumentException("Action progress requires a full active-action report");
        UUID.fromString(text(message,"token")); ActionProgress.checked(message.getAsJsonObject("actionProgress"));
    }
    public static JsonObject message(String type) { JsonObject m = new JsonObject(); m.addProperty("type", "task-" + type); return m; }
    public static JsonObject envelope(JsonObject task, UUID worker) {
        JsonObject metadata = new JsonObject();
        for (String key : List.of("id", "name", "crew", "workflowName", "args", "priority")) metadata.add(key.equals("id") ? "task" : key, task.get(key).deepCopy());
        for (String key : List.of("server", "dimension", "supportedActions")) if (task.has(key)) metadata.add(key, task.get(key).deepCopy());
        List<String> workers = List.copyOf(task.getAsJsonObject("runs").keySet());
        if (!workers.contains(worker.toString())) throw new IllegalArgumentException("Worker not targeted by task");
        metadata.addProperty("workerIndex", workers.indexOf(worker.toString())); metadata.addProperty("workerCount", workers.size());
        JsonObject envelope = task.getAsJsonObject("package").deepCopy(); envelope.add("dispatch", metadata); return envelope;
    }
    public static JsonObject begin(UUID run, String data) {
        JsonObject m = message("begin"); m.addProperty("run", run.toString()); m.addProperty("hash", TaskFiles.hash(data));
        m.addProperty("count", (data.length() + TaskFiles.CHUNK - 1) / TaskFiles.CHUNK); return m;
    }
    public static JsonObject chunk(UUID run, String data, int index) {
        JsonObject m = message("chunk"); m.addProperty("run", run.toString()); m.addProperty("index", index);
        m.addProperty("data", data.substring(index * TaskFiles.CHUNK, Math.min(data.length(), (index + 1) * TaskFiles.CHUNK))); return m;
    }
    public static JsonObject control(UUID run, String command) {
        if (!Set.of("resume", "pause", "cancel").contains(command)) throw new IllegalArgumentException("Invalid task control");
        JsonObject m = message("control"); m.addProperty("run", run.toString()); m.addProperty("command", command); return m;
    }
    public static JsonObject checkedConfiguration(JsonObject modules) {
        if (modules == null || modules.isEmpty() || modules.size() > 64) throw new IllegalArgumentException("Choose 1–64 gameplay modules");
        TaskFiles.jsonBytes(modules, 12_000);
        for (var entry : modules.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9-]{1,64}") || !entry.getValue().isJsonObject()
                || Set.of("highway-builder", "printer-helper", "schematic-selector").contains(entry.getKey()))
                throw new IllegalArgumentException("Live configuration cannot change job-owned executors");
            JsonObject state = entry.getValue().getAsJsonObject();
            if (state.size() != 2 || !state.has("active") || !state.get("active").isJsonPrimitive() || !state.getAsJsonPrimitive("active").isBoolean()
                || !state.has("settings") || !state.get("settings").isJsonPrimitive() || !state.getAsJsonPrimitive("settings").isString())
                throw new IllegalArgumentException("Expected active boolean and settings SNBT for each module");
        }
        return modules.deepCopy();
    }
    public static void configure(JsonObject task, UUID worker, JsonObject modules) {
        if (QueuePolicy.terminal(text(task, "status")) || flag(task, "cancelled")) throw new IllegalArgumentException("Job has ended");
        JsonObject checked = checkedConfiguration(modules), runs = task.getAsJsonObject("runs");
        if (worker != null && !runs.has(worker.toString())) throw new IllegalArgumentException("Worker is not assigned to this job");
        Map<JsonObject, JsonObject> updates = new IdentityHashMap<>();
        for (var entry : runs.entrySet()) {
            JsonObject run = entry.getValue().getAsJsonObject();
            if (worker != null && !entry.getKey().equals(worker.toString()) || QueuePolicy.terminal(text(run, "status"))) continue;
            if (!run.has("configurationVersion") || run.get("configurationVersion").getAsInt() != 1)
                throw new IllegalArgumentException("Worker must run Monocle 0.7.50 or newer and report its state first");
            if (run.has("configuration") && (!run.has("configRevision") || run.get("configRevision").getAsInt() < run.getAsJsonObject("configuration").get("revision").getAsInt()))
                throw new IllegalArgumentException("Wait for the previous worker configuration acknowledgement");
            JsonObject config = new JsonObject();
            config.add("modules", checked.deepCopy());
            config.addProperty("revision", run.has("configuration") ? Math.incrementExact(run.getAsJsonObject("configuration").get("revision").getAsInt()) : 1);
            updates.put(run, config);
        }
        if (updates.isEmpty()) throw new IllegalArgumentException("No unfinished worker execution to configure");
        updates.forEach((run, config) -> { run.add("configuration", config); run.remove("configurationSentAt"); });
    }
    public static JsonObject configurationToSend(JsonObject run, long now) {
        if (!run.has("configuration") || !text(run, "status").equals("Running")) return null;
        JsonObject config = run.getAsJsonObject("configuration");
        if (run.has("configRevision") && run.get("configRevision").getAsInt() >= config.get("revision").getAsInt()) return null;
        long last = run.has("configurationSentAt") ? run.get("configurationSentAt").getAsLong() : 0;
        if (run.has("configurationSentAt") && now >= last && now - last < 1000) return null;
        JsonObject m = message("configure"); m.addProperty("run", text(run, "id"));
        m.add("revision", config.get("revision").deepCopy()); m.add("modules", config.get("modules").deepCopy());
        run.addProperty("configurationSentAt", now); return m;
    }
    public static boolean applyStatus(JsonObject task, JsonObject run, JsonObject message, boolean full) {
        return applyStatus(task, run, message, full, false);
    }
    public static boolean applyStatus(JsonObject task, JsonObject run, JsonObject message, boolean full, boolean isolateInspection) {
        String state = text(message, "status");
        UUID.fromString(text(message, "run"));
        if (!text(run, "id").equals(text(message, "run"))) throw new IllegalArgumentException("Wrong execution");
        if (!Set.of("Ready", "Running", "Suspending", "Suspended", "Inspection required", "Complete", "Failed", "Cancelled").contains(state)) throw new IllegalArgumentException("Invalid task execution state");
        boolean cancellationCleanup = text(run,"status").equals("Failed") && flag(task,"cancelled")
            && text(message,"requestedStatus").equals("Cancelled") && Set.of("Inspection required","Suspending").contains(state);
        if (QueuePolicy.terminal(text(run, "status")) && !QueuePolicy.terminal(state) && !cancellationCleanup) return false;
        JsonObject checked = message.deepCopy();
        validateProgress(checked,full);
        if(checked.has("controlId"))UUID.fromString(text(checked,"controlId"));
        if(checked.has("worldReady")&&(!checked.get("worldReady").isJsonPrimitive()||!checked.getAsJsonPrimitive("worldReady").isBoolean()))throw new IllegalArgumentException("Invalid execution world readiness");
        if (checked.has("configRevision")) {
            int revision = checked.get("configRevision").getAsBigDecimal().intValueExact();
            if (revision < 1 || !run.has("configuration") || revision > run.getAsJsonObject("configuration").get("revision").getAsInt()
                || text(checked, "configError").length() > 1024) throw new IllegalArgumentException("Invalid configuration acknowledgement");
        }
        if (full) {
            if (text(checked, "detail").length() > 1024) throw new IllegalArgumentException("Task status is too large");
            if(checked.has("stashTravel")){
                JsonObject travel=checked.getAsJsonObject("stashTravel");
                if(!checked.has("action")||!text(checked.getAsJsonObject("action"),"type").equals("StashVisit")||travel.size()!=3||!text(travel,"name").equals(text(checked.getAsJsonObject("action"),"name")))throw new IllegalArgumentException("Stash travel must match its active visit");
                for(String key:List.of("homeSent","arrived"))if(!travel.has(key)||!travel.get(key).isJsonPrimitive()||!travel.getAsJsonPrimitive(key).isBoolean())throw new IllegalArgumentException("Invalid stash travel flag");
            }
            if(checked.has("deliveryReceipt")){
                if(!task.has("package")||!text(task.getAsJsonObject("package"),"entry").equals("task-kit-delivery")||!text(task.getAsJsonObject("args"),"destination").equals("Player"))throw new IllegalArgumentException("Delivery receipts require a player kit job");
                JsonObject receipt=DeliveryReceipt.checked(checked.getAsJsonObject("deliveryReceipt"),StashCatalog.integer(task.getAsJsonObject("args"),"count",1,1000000));
                if(run.has("deliveryReceipt")){
                    JsonObject previous=run.getAsJsonObject("deliveryReceipt");receipt.addProperty("delivered",Math.max(receipt.get("delivered").getAsInt(),previous.get("delivered").getAsInt()));
                    if(previous.has("failureReason")&&!receipt.get("uncertain").getAsBoolean())receipt.add("failureReason",previous.get("failureReason").deepCopy());
                }
                checked.add("deliveryReceipt",DeliveryReceipt.checked(receipt,receipt.get("expected").getAsInt()));
            }
            if(checked.has("workflowResult")&&checked.get("workflowResult").toString().length()>32_768)throw new IllegalArgumentException("Workflow result is too large");
            if (checked.has("action")) {
                if (!checked.get("action").isJsonObject() || checked.get("action").toString().length() > dev.monocle.client.systems.bots.BotLua.MAX_STATE) throw new IllegalArgumentException("Native action is too large");
                UUID.fromString(text(checked, "token"));
            }
        }
        if (checked.has("configurationVersion")) {
            if (checked.get("configurationVersion").getAsBigDecimal().intValueExact() != 1) throw new IllegalArgumentException("Unsupported configuration protocol");
            run.addProperty("configurationVersion", 1);
        }
        if (checked.has("readbackVersion")) {
            if (checked.get("readbackVersion").getAsBigDecimal().intValueExact() != 1) throw new IllegalArgumentException("Unsupported readback protocol");
            run.addProperty("readbackVersion", 1);
        }
        if (checked.has("configRevision") && (!run.has("configRevision") || checked.get("configRevision").getAsInt() >= run.get("configRevision").getAsInt())) {
            run.add("configRevision", checked.get("configRevision")); run.addProperty("configError", text(checked, "configError"));
        }
        run.addProperty("status", state);
        if (state.equals("Inspection required") && !isolateInspection && !flag(task, "cancelled") && !text(task, "status").equals("Inspection required") && !flag(run, "resumeInspection")) {
            task.addProperty("paused", true); task.addProperty("status", "Inspection required"); task.addProperty("detail", "Worker restarted; inspect the saved action and Resume from the host");
        }
        if (!state.equals("Inspection required")) run.remove("resumeInspection");
        if (full) {
            run.addProperty("detail", text(checked, "detail"));
            if(checked.has("deliveryReceipt"))run.add("deliveryReceipt",checked.get("deliveryReceipt").deepCopy());
            if(checked.has("workflowResult"))run.add("workflowResult",checked.get("workflowResult").deepCopy());
            if (checked.has("stashScan")) {
                if (!checked.has("action") || !text(checked.getAsJsonObject("action"),"type").equals("StashScan")) throw new IllegalArgumentException("Stash telemetry requires its active scan action");
                StashCatalog.telemetry(run,checked.getAsJsonObject("stashScan"));
            }
            if (checked.has("action")) {
                if(!text(run,"token").equals(text(checked,"token"))){run.remove("tpaAccepted");run.remove("tpaSentAt");}
                run.add("action", checked.get("action")); run.addProperty("token", text(checked, "token")); run.addProperty("commandSent", flag(checked, "commandSent"));
                if(text(checked.getAsJsonObject("action"),"type").equals("Tpa")&&flag(checked,"commandSent")&&!run.has("tpaSentAt"))run.addProperty("tpaSentAt",System.currentTimeMillis());
            } else { run.remove("action"); run.remove("token"); }
            if (checked.has("actionProgress")) {
                JsonObject progress = checked.getAsJsonObject("actionProgress"); run.add("actionProgress", progress.deepCopy());
                if (!flag(task,"cancelled") && !QueuePolicy.terminal(state) && text(progress,"state").equals("Paused") && !text(progress,"incidentId").equals(text(run,"dismissedStallId"))) {
                    run.addProperty("stallPaused",true); run.addProperty("stallIncidentId",text(progress,"incidentId"));
                }
            } else if (!checked.has("action")) run.remove("actionProgress");
            if(checked.has("stashTravel")){run.add("stashTravel",checked.get("stashTravel").deepCopy());run.addProperty("stashTravelToken",text(checked,"token"));}
            else if(!text(run,"stashTravelToken").equals(text(run,"token"))){run.remove("stashTravel");run.remove("stashTravelToken");}
            if (checked.has("requestedStatus")) run.addProperty("requestedStatus", text(checked, "requestedStatus")); else run.remove("requestedStatus");
            run.addProperty("connectionSuspended", flag(checked, "connectionSuspended"));
        }
        if(!full&&run.has("action"))checked.add("action",run.get("action"));
        CommandReceipts.report(task,run,checked);
        return true;
    }
}
