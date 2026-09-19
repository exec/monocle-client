package dev.monocle.coordinator;

import com.google.gson.*;
import dev.monocle.client.systems.bots.BotLua;
import dev.monocle.client.systems.bots.BotWorkflows;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

/** Portable captured workflows; native module settings receive further validation in the worker. */
public final class WorkflowPackages {
    private WorkflowPackages() {}
    public static String label(String value) {
        value = value.strip();
        if (value.isEmpty() || value.length() > 48 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Choose a name of 1–48 printable characters");
        return value;
    }
    public static JsonObject checked(JsonObject input) {
        TaskFiles.jsonBytes(input, TaskFiles.MAX_PACKAGE);
        if (!input.has("version") || !input.getAsJsonPrimitive("version").isNumber() || input.get("version").getAsBigDecimal().intValueExact() != 1)
            throw new IllegalArgumentException("Unsupported workflow package version");
        if (input.has("dispatch")) throw new IllegalArgumentException("Import a workflow package, not a dispatched job");
        JsonObject programs = input.getAsJsonObject("programs"), profiles = input.getAsJsonObject("profiles"), highways = input.getAsJsonObject("highways");
        if (programs == null || programs.isEmpty() || programs.size() > 32 || profiles == null || profiles.size() > 17 || !profiles.has("Current") || highways == null || highways.size() > 32)
            throw new IllegalArgumentException("Incomplete workflow package");
        for (var entry : programs.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid workflow ID");
            label(text(entry.getValue().getAsJsonObject(), "name")); BotLua.validate(text(entry.getValue().getAsJsonObject(), "script"));
        }
        if (!programs.has(text(input, "entry"))) throw new IllegalArgumentException("Missing workflow entry");
        for (var entry : highways.entrySet()) {
            if (!programs.has(entry.getKey())) throw new IllegalArgumentException("Highway preset has no bundled program");
            BotWorkflows.checkedPlan(entry.getValue().getAsJsonObject());
        }
        if (!highways.isEmpty()) {
            if (!input.has("geometry")) throw new IllegalArgumentException("Highway workflows need captured geometry");
            JsonObject geometry = input.getAsJsonObject("geometry").deepCopy();
            geometry.addProperty("id", UUID.randomUUID().toString()); geometry.addProperty("name", "Exported highway"); geometry.addProperty("length", 128); geometry.addProperty("progress", 0);
            HighwayJobs.checked(geometry);
        }
        for (var entry : profiles.entrySet()) {
            String name = entry.getKey();
            if (name.isBlank() || name.length() > 48 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl) || !entry.getValue().isJsonObject())
                throw new IllegalArgumentException("Invalid profile");
            if (entry.getValue().getAsJsonObject().size() > 512) throw new IllegalArgumentException("Too many modules in profile");
            for(var module:entry.getValue().getAsJsonObject().entrySet()) {
                if(!module.getKey().matches("[a-z0-9-]{1,64}") || !module.getValue().isJsonObject())throw new IllegalArgumentException("Invalid captured module");
                JsonObject state=module.getValue().getAsJsonObject();
                if(state.size()!=2 || !state.has("active") || !state.get("active").isJsonPrimitive() || !state.getAsJsonPrimitive("active").isBoolean()
                    || !state.has("settings") || !state.get("settings").isJsonPrimitive() || !state.getAsJsonPrimitive("settings").isString())throw new IllegalArgumentException("Invalid captured module settings/activation");
                if(Set.of("highway-builder","printer-helper","schematic-selector").contains(module.getKey()) && state.get("active").getAsBoolean())throw new IllegalArgumentException("Native executors are started by workflows, not profile activation");
            }
            TaskFiles.jsonBytes(entry.getValue().getAsJsonObject(), 524_288);
        }
        return input.deepCopy();
    }
}
