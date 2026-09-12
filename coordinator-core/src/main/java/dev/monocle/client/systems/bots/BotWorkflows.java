package dev.monocle.client.systems.bots;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Small native-work library: repeating work capabilities and ordered, on-demand supply fallbacks. */
public final class BotWorkflows {
    private static String checkedName(String value) { String name=value.strip(); if(name.isEmpty() || name.length()>48 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Name must be 1–48 printable characters"); return name; }
    public static final String DEFAULT_ID = "highway-default";
    public enum Action { Excavating, Paving, InventoryShulkers, EnderChestContents, EnderChestFarm, Call }
    public record Step(Action action, String target) {
        public Step { Objects.requireNonNull(action); target = target == null ? "" : target; }
    }
    public record Workflow(String id, String name, String folder, boolean builtin, List<Step> steps, String script, List<String> dependencies, List<String> profiles) {
        public Workflow { steps = List.copyOf(steps); script = script == null ? "" : script; dependencies = List.copyOf(dependencies); profiles = List.copyOf(profiles); }
        public Workflow(String id, String name, String folder, boolean builtin, List<Step> steps) { this(id, name, folder, builtin, steps, "", List.of(), List.of()); }
        @Override public String toString() { return name; }
    }
    private final Path file;
    private final Map<String, Workflow> entries = builtins();
    private boolean loaded;
    private String failure;

    public BotWorkflows(Path file) { this.file = file; }
    private static Map<String, Workflow> builtins() {
        Map<String, Workflow> values = new LinkedHashMap<>();
        addBuiltin(values, "highway-supplies", "Carried supplies", List.of(new Step(Action.InventoryShulkers, ""),
            new Step(Action.EnderChestContents, ""), new Step(Action.EnderChestFarm, "")));
        addBuiltin(values, DEFAULT_ID, "Highway Builder", List.of(new Step(Action.Excavating, ""), new Step(Action.Paving, ""), new Step(Action.Call, "highway-supplies")));
        addBuiltin(values, "highway-excavate", "Excavation crew", List.of(new Step(Action.Excavating, ""), new Step(Action.Call, "highway-supplies")));
        addBuiltin(values, "highway-pave", "Paving crew", List.of(new Step(Action.Paving, ""), new Step(Action.Call, "highway-supplies")));
        for (var entry : Map.of("travel", "Travel to coordinates", "drop", "Drop items", "tpa", "TPA rendezvous", "wait", "Wait", "modules", "Run configured modules", "profile", "Set profile").entrySet()) {
            String argument = entry.getKey().equals("wait") ? "ctx.args.ticks or 20" : entry.getKey().equals("profile") ? "ctx.args.name or 'Current'" : "ctx.args";
            String script = "return function(ctx)\n  if ctx.state.started then return bot.done() end\n  ctx.state.started = true\n  return bot." + entry.getKey() + "(" + argument + ")\nend\n";
            String id = "task-" + entry.getKey();
            values.put(id, new Workflow(id, entry.getValue(), "Common Tasks", true, List.of(), script, List.of(), List.of("Current")));
        }
        values.put("task-stash-hunt", new Workflow("task-stash-hunt", "Distributed stash hunt", "Stash Hunting", true, List.of(),
            "return function(ctx)\n  if ctx.state.started then return bot.done() end\n  ctx.state.started = true\n  return bot.stash_hunt(ctx.args)\nend\n", List.of(), List.of("Current")));
        values.put("task-recover", new Workflow("task-recover", "Recover supplies", "Common Tasks", true, List.of(),
            "return function(ctx)\n if ctx.state.started then return bot.done() end\n ctx.state.started=true\n return bot.recover(ctx.args)\nend", List.of(), List.of("Current")));
        return values;
    }
    private static void addBuiltin(Map<String, Workflow> values, String id, String name, List<Step> steps) {
        values.put(id, new Workflow(id, name, "Highway Builder", true, steps));
    }
    public List<Workflow> all() { load(); return List.copyOf(entries.values()); }
    public Workflow get(String id) {
        load(); Workflow result = entries.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown workflow: " + id);
        return result;
    }
    public Workflow duplicate(String id, String name, String folder) {
        Workflow source = get(id);
        return source.script().isEmpty() ? save(UUID.randomUUID().toString(), name, folder, source.steps())
            : saveProgram(UUID.randomUUID().toString(), name, folder, source.script(), source.dependencies(), source.profiles());
    }
    public Workflow save(String id, String name, String folder, List<Step> steps) {
        return put(new Workflow(id, name, folder, false, steps));
    }
    public Workflow createProgram(String name, String folder, String script) { return saveProgram(UUID.randomUUID().toString(), name, folder, script, List.of(), List.of("Current")); }
    public Workflow saveProgram(String id, String name, String folder, String script, List<String> dependencies, List<String> profiles) {
        return put(new Workflow(id, name, folder, false, List.of(), script, dependencies, profiles));
    }
    private Workflow put(Workflow input) {
        load(); String id = input.id();
        Workflow previous = entries.get(id);
        if (previous != null && previous.builtin()) throw new IllegalArgumentException("Duplicate a built-in workflow to edit it.");
        UUID.fromString(id);
        if (previous == null && entries.size() >= 256) throw new IllegalArgumentException("Workflow library is full (256 entries).");
        Workflow value = checked(input);
        for (Workflow other : entries.values()) if (!other.id().equals(id) && other.folder().equalsIgnoreCase(value.folder()) && other.name().equalsIgnoreCase(value.name()))
            throw new IllegalArgumentException("That folder already contains a workflow named " + value.name() + ". Choose another name.");
        entries.put(id, value);
        try { validateAll(); persist(); }
        catch (RuntimeException e) { if (previous == null) entries.remove(id); else entries.put(id, previous); throw e; }
        return value;
    }
    public void delete(String id) {
        Workflow value = get(id);
        if (value.builtin()) throw new IllegalArgumentException("Built-in workflows are read-only.");
        for (Workflow other : entries.values()) for (Step step : other.steps())
            if (step.action() == Action.Call && step.target().equals(id)) throw new IllegalStateException("Used by workflow: " + other.name());
        for (Workflow other : entries.values()) if (other.dependencies().contains(id)) throw new IllegalStateException("Used by workflow: " + other.name());
        entries.remove(id);
        try { persist(); } catch (RuntimeException e) { entries.put(id, value); throw e; }
    }
    /** Snapshot the expanded instructions; edits to the library never change a saved or running job. */
    public JsonObject compile(String id) { load(); return compileEntry(id); }
    private JsonObject compileEntry(String id) {
        Workflow value = entries.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown workflow: " + id);
        if (!value.script().isEmpty()) throw new IllegalArgumentException("Choose a native highway workflow here; Lua programs run from the job queue.");
        List<Action> actions = new ArrayList<>(); expand(id, new LinkedHashSet<>(), actions);
        JsonObject plan = new JsonObject(); plan.addProperty("version", 1); plan.addProperty("id", id); plan.addProperty("name", value.name());
        JsonArray encoded = new JsonArray(); actions.forEach(action -> encoded.add(action.name())); plan.add("actions", encoded);
        return checkedPlan(plan);
    }
    private void expand(String id, Set<String> path, List<Action> actions) {
        if (path.size() >= 16 || !path.add(id)) throw new IllegalArgumentException("Workflow call cycle or nesting deeper than 16: " + id);
        Workflow value = entries.get(id);
        if (value == null) throw new IllegalArgumentException("Missing called workflow: " + id);
        if (!value.script().isEmpty()) throw new IllegalArgumentException("A native highway preset cannot call a Lua program. Call the highway preset from Lua instead.");
        for (Step step : value.steps()) {
            if (step.action() == Action.Call) expand(step.target(), path, actions);
            else {
                if (actions.contains(step.action())) throw new IllegalArgumentException("Repeated action " + step.action() + " in " + id);
                actions.add(step.action());
            }
        }
        path.remove(id);
    }
    public static JsonObject checkedPlan(JsonObject input) {
        if (input == null || input.toString().length() > 2048) throw new IllegalArgumentException("Invalid workflow snapshot");
        JsonObject plan = input.deepCopy();
        if (!plan.getAsJsonPrimitive("version").isNumber() || plan.get("version").getAsBigDecimal().intValueExact() != 1)
            throw new IllegalArgumentException("Unsupported workflow version");
        String id = dev.monocle.coordinator.TaskWire.text(plan, "id");
        if (!builtins().containsKey(id)) UUID.fromString(id);
        plan.addProperty("name", checkedName(dev.monocle.coordinator.TaskWire.text(plan, "name")));
        JsonArray values = plan.getAsJsonArray("actions");
        if (values == null || values.isEmpty() || values.size() > 5) throw new IllegalArgumentException("A workflow needs 1–5 distinct native actions");
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        for (JsonElement value : values) {
            Action action = Action.valueOf(value.getAsString());
            if (action == Action.Call || !actions.add(action)) throw new IllegalArgumentException("Workflow snapshot must contain distinct expanded actions");
        }
        boolean excavates = actions.contains(Action.Excavating), paves = actions.contains(Action.Paving);
        plan.addProperty("duty", excavates ? paves ? "Build" : "Excavate" : paves ? "Pave" : "Supply");
        return plan;
    }
    public static String operation(JsonObject plan) {
        return switch (checkedPlan(plan).get("duty").getAsString()) {
            case "Build" -> "Build"; case "Excavate" -> "ClearTunnel"; case "Pave" -> "Pave";
            default -> throw new IllegalArgumentException("A job or worker workflow needs Excavating or Paving; supply-only workflows can be called by another workflow.");
        };
    }
    public static JsonObject legacyPlan(String operation) {
        String id = operation.equals("ClearTunnel") ? "highway-excavate" : Set.of("Pave", "Repair").contains(operation) ? "highway-pave" : DEFAULT_ID;
        return new BotWorkflows(null).compileEntry(id);
    }
    private static Workflow checked(Workflow value) {
        String folder = value.folder().strip();
        if (folder.isEmpty() || folder.length() > 96 || folder.chars().anyMatch(Character::isISOControl) || folder.contains("\\"))
            throw new IllegalArgumentException("Choose a folder path of 1–96 printable characters.");
        for (String part : folder.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals(".."))
            throw new IllegalArgumentException("Workflow folders need nonempty names, without . or ..");
        if (value.script().isEmpty() && value.steps().isEmpty() || value.steps().size() > 32) throw new IllegalArgumentException("A workflow needs native steps or Lua source.");
        if (!value.script().isEmpty()) {
            if (!value.steps().isEmpty()) throw new IllegalArgumentException("A definition is either Lua or a native highway preset.");
            BotLua.validate(value.script());
        }
        if (value.dependencies().size() > 32 || new HashSet<>(value.dependencies()).size() != value.dependencies().size()
            || value.profiles().size() > 16 || value.profiles().stream().anyMatch(name -> name.isBlank() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Invalid workflow dependencies/profile names.");
        for (Step step : value.steps()) {
            if (step.action() == Action.Call && (step.target().isBlank() || step.target().length() > 48)) throw new IllegalArgumentException("Choose a workflow to call.");
            if (step.action() != Action.Call && !step.target().isEmpty()) throw new IllegalArgumentException("Only calls take a workflow target.");
        }
        return new Workflow(value.id(), checkedName(value.name()), folder, value.builtin(), value.steps(), value.script(), value.dependencies(), value.profiles());
    }
    private void validateAll() {
        for (Workflow value : entries.values()) {
            if (value.script().isEmpty()) compileEntry(value.id()); else { BotLua.validate(value.script()); dependencies(value.id(), new LinkedHashSet<>(), new LinkedHashSet<>()); }
        }
    }
    private void dependencies(String id, Set<String> path, Set<String> collected) {
        if (path.size() >= 16 || !path.add(id)) throw new IllegalArgumentException("Workflow dependency cycle or excessive nesting: " + id);
        Workflow value = entries.get(id); if (value == null) throw new IllegalArgumentException("Missing dependency: " + id);
        collected.add(id);
        if (collected.size() > 32) throw new IllegalArgumentException("A job package may contain at most 32 workflows.");
        for (String child : value.dependencies()) dependencies(child, path, collected);
        for (Step step : value.steps()) if (step.action() == Action.Call) dependencies(step.target(), path, collected);
        path.remove(id);
    }
    public JsonObject packageWorkflows(String id) {
        get(id); Set<String> included = new LinkedHashSet<>(); dependencies(id, new LinkedHashSet<>(), included);
        JsonObject packageData = new JsonObject(), programs = new JsonObject(), highways = new JsonObject(); JsonArray profiles = new JsonArray();
        Set<String> profileNames = new LinkedHashSet<>(Set.of("Current"));
        for (String key : included) {
            Workflow value = entries.get(key); profileNames.addAll(value.profiles());
            JsonObject program = new JsonObject(); program.addProperty("name", value.name());
            if (value.script().isEmpty()) {
                JsonObject plan = compileEntry(key); highways.add(key, plan);
                program.addProperty("script", "return function(ctx)\n if not ctx.state.recovered then\n  ctx.state.recovered=true\n  return bot.recover(ctx.args.recovery or {})\n end\n if ctx.state.started then return bot.done() end\n ctx.state.started=true\n local a=ctx.args\n a.workflow='" + key + "'\n return bot.highway(a)\nend");
            } else program.addProperty("script", value.script());
            programs.add(key, program);
        }
        profileNames.forEach(profiles::add);
        packageData.addProperty("version", 1); packageData.addProperty("entry", id); packageData.add("programs", programs); packageData.add("highways", highways); packageData.add("profileNames", profiles);
        return packageData;
    }
    public JsonObject exportDefinition(String id) { return encode(get(id)); }
    public Workflow importDefinition(JsonObject object) {
        Workflow value = decode(object, UUID.randomUUID().toString());
        return put(value);
    }
    private static Workflow decode(JsonObject object, String id) {
        List<Step> steps = new ArrayList<>();
        if (object.has("steps")) for (JsonElement item : object.getAsJsonArray("steps")) {
            JsonObject step = item.getAsJsonObject(); steps.add(new Step(Action.valueOf(dev.monocle.coordinator.TaskWire.text(step, "action")), dev.monocle.coordinator.TaskWire.text(step, "target")));
        }
        List<String> dependencies = new ArrayList<>(), profiles = new ArrayList<>();
        if (object.has("dependencies")) object.getAsJsonArray("dependencies").forEach(value -> dependencies.add(value.getAsString()));
        if (object.has("profiles")) object.getAsJsonArray("profiles").forEach(value -> profiles.add(value.getAsString()));
        return checked(new Workflow(id, dev.monocle.coordinator.TaskWire.text(object, "name"), dev.monocle.coordinator.TaskWire.text(object, "folder"), false, steps, dev.monocle.coordinator.TaskWire.text(object, "script"), dependencies, profiles));
    }
    private static JsonObject encode(Workflow value) {
        JsonObject object = new JsonObject(); object.addProperty("id", value.id()); object.addProperty("name", value.name()); object.addProperty("folder", value.folder()); object.addProperty("script", value.script());
        JsonArray steps = new JsonArray();
        for (Step step : value.steps()) { JsonObject item = new JsonObject(); item.addProperty("action", step.action().name()); item.addProperty("target", step.target()); steps.add(item); }
        object.add("steps", steps); JsonArray dependencies = new JsonArray(), profiles = new JsonArray(); value.dependencies().forEach(dependencies::add); value.profiles().forEach(profiles::add);
        object.add("dependencies", dependencies); object.add("profiles", profiles); return object;
    }
    private void load() {
        if (failure != null) throw new IllegalStateException(failure);
        if (loaded) return;
        try {
            if (Files.exists(file)) {
                if (Files.size(file) > 1_048_576) throw new IllegalArgumentException("Workflow library is too large");
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.get("version").getAsBigDecimal().intValueExact() != 1) throw new IllegalArgumentException("Unsupported workflow library version");
                JsonArray stored = root.getAsJsonArray("workflows");
                if (stored.size() > 246) throw new IllegalArgumentException("Too many workflows");
                for (JsonElement element : stored) {
                    JsonObject object = element.getAsJsonObject(); String id = dev.monocle.coordinator.TaskWire.text(object, "id"); UUID.fromString(id);
                    Workflow workflow = decode(object, id);
                    if (entries.putIfAbsent(id, workflow) != null) throw new IllegalArgumentException("Duplicate workflow ID");
                }
                validateAll();
            }
            loaded = true;
        } catch (IOException | RuntimeException e) {
            failure = "Cannot read Bots workflows at " + file + ": " + e.getMessage() + ". Original file left untouched.";
            throw new IllegalStateException(failure, e);
        }
    }
    private void persist() {
        JsonObject root = new JsonObject(); root.addProperty("version", 1); JsonArray workflows = new JsonArray();
        for (Workflow value : entries.values()) if (!value.builtin()) {
            workflows.add(encode(value));
        }
        root.add("workflows", workflows); Path temporary = null;
        try {
            Files.createDirectories(file.getParent()); temporary = Files.createTempFile(file.getParent(), "bot-workflows-", ".tmp");
            Files.writeString(temporary, root.toString()); Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException("Could not save Bots workflows: " + e.getMessage(), e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
}
