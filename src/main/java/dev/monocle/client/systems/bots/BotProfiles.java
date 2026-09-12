package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.profiles.Profile;
import dev.monocle.client.systems.profiles.Profiles;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.systems.modules.world.PrinterHelper;
import net.minecraft.nbt.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** Task-scoped gameplay settings. Never applies profile directories or synchronizes account/transport secrets. */
public final class BotProfiles {
    public static final int MAX_BYTES = 524_288;
    private static JsonObject originals;
    private BotProfiles() { }
    public static List<String> names() {
        List<String> result = new ArrayList<>(List.of("Current"));
        if (Profiles.get() != null) for (Profile profile : Profiles.get().getAll()) if (profile.modules.get() && !profile.name.get().equals("Current")) result.add(profile.name.get());
        return List.copyOf(result);
    }
    private static boolean gameplay(Module module) {
        return module.category == Categories.Combat || module.category == Categories.Movement || module.category == Categories.Player || module.category == Categories.World;
    }
    private static boolean executorOwned(String name) { return Set.of("highway-builder", "printer-helper", "schematic-selector").contains(name); }
    public static JsonObject capture(String name) {
        JsonObject result = new JsonObject();
        if (name.equals("Current")) {
            for (Module module : Modules.get().getAll()) if (gameplay(module)) result.add(module.name, encode(module.settings.toTag(), module.isActive() && !executorOwned(module.name)));
        } else {
            Profile profile = Profiles.get().get(name);
            if (profile == null || !profile.modules.get()) throw new IllegalArgumentException("Missing gameplay profile: " + name);
            try {
                var path = profile.getFile().toPath().resolve("modules.nbt");
                if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES * 2L) throw new IllegalArgumentException("Missing or oversized module profile: " + name);
                CompoundTag saved = readProfile(path);
                if (saved == null) throw new IllegalArgumentException("Empty module profile: " + name);
                for (Tag element : saved.getListOrEmpty("modules")) if (element instanceof CompoundTag tag) {
                    Module module = Modules.get().get(tag.getStringOr("name", ""));
                    if (module != null && gameplay(module)) result.add(module.name, encode(tag.getCompoundOrEmpty("settings"), tag.getBooleanOr("active", false) && !executorOwned(module.name)));
                }
            } catch (java.io.IOException e) { throw new IllegalStateException("Could not capture profile " + name + ": " + e.getMessage(), e); }
        }
        return validate(result);
    }
    /** Read actual in-memory task overrides, not the original settings substituted during module persistence. */
    public static JsonObject captureEffective() { return capture("Current"); }

    static CompoundTag readProfile(Path path) throws IOException {
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            return NbtIo.read(input, NbtAccounter.create(MAX_BYTES * 8L));
        }
    }
    private static JsonObject encode(CompoundTag settings, boolean enabled) {
        JsonObject value = new JsonObject(); value.addProperty("settings", settings.toString()); value.addProperty("active", enabled); return value;
    }
    public static JsonObject validate(JsonObject input) {
        if (input == null || input.size() > 512 || input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Task profile exceeds its size limit.");
        JsonObject copy = input.deepCopy();
        for (var entry : copy.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid module name in task profile.");
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Expected module settings object.");
            JsonObject value = entry.getValue().getAsJsonObject();
            if (value.size() != 2 || !value.has("active") || !value.get("active").isJsonPrimitive() || !value.getAsJsonPrimitive("active").isBoolean()) throw new IllegalArgumentException("Invalid module activation state.");
            if (!value.has("settings") || !value.get("settings").isJsonPrimitive() || !value.getAsJsonPrimitive("settings").isString()) throw new IllegalArgumentException("Expected settings SNBT string.");
            validateSettings(parse(value.get("settings").getAsString()));
            if (executorOwned(entry.getKey()) && value.get("active").getAsBoolean()) throw new IllegalArgumentException("Native executors are started by tasks, not by profiles.");
        }
        return copy;
    }
    static void validateSettings(CompoundTag settings) {
        Tag groups = settings.get("groups");
        if (groups == null) return;
        if (!(groups instanceof ListTag list) || list.size() > 128) throw new IllegalArgumentException("Invalid settings groups.");
        Set<String> groupNames = new HashSet<>();
        for (Tag element : list) {
            if (!(element instanceof CompoundTag group) || !(group.get("name") instanceof StringTag)) throw new IllegalArgumentException("Invalid settings group.");
            if (!groupNames.add(group.getStringOr("name", ""))) throw new IllegalArgumentException("Duplicate settings group.");
            Tag values = group.get("settings");
            if (values == null) continue;
            if (!(values instanceof ListTag entries) || entries.size() > 512) throw new IllegalArgumentException("Invalid settings entries.");
            Set<String> names = new HashSet<>();
            for (Tag value : entries) {
                if (!(value instanceof CompoundTag setting) || !(setting.get("name") instanceof StringTag)) throw new IllegalArgumentException("Invalid setting entry.");
                if (!names.add(setting.getStringOr("name", ""))) throw new IllegalArgumentException("Duplicate setting entry.");
            }
        }
    }
    private static CompoundTag parse(String text) {
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("Task settings exceed their size limit.");
        try { return TagParser.parseCompoundFully(text); }
        catch (Exception | StackOverflowError e) { throw new IllegalArgumentException("Invalid task settings: " + e.getMessage(), e); }
    }
    public static boolean leased() { return originals != null; }
    public static JsonObject originalSnapshot() { return originals == null ? new JsonObject() : originals.deepCopy(); }
    /** Save this before applying the first task profile, so a crash cannot turn temporary settings permanent. */
    public static JsonObject begin() {
        if (originals != null) return originals.deepCopy();
        requireIdle();
        originals = capture("Current"); return originals.deepCopy();
    }
    public static void apply(JsonObject snapshot) {
        JsonObject checked = validate(snapshot);
        requireIdle();
        Map<Module, CompoundTag> desired = new LinkedHashMap<>(), previous = new LinkedHashMap<>();
        Map<Module, Boolean> enabled = new LinkedHashMap<>(), oldEnabled = new LinkedHashMap<>();
        for (String name : checked.keySet()) {
            Module module = Modules.get().get(name);
            if (module == null || !gameplay(module)) throw new IllegalArgumentException("Worker cannot apply gameplay module: " + name);
            desired.put(module, parse(checked.getAsJsonObject(name).get("settings").getAsString()));
            enabled.put(module, checked.getAsJsonObject(name).get("active").getAsBoolean());
            previous.put(module, module.settings.toTag().copy()); oldEnabled.put(module, module.isActive());
        }
        if (originals == null) throw new IllegalStateException("Checkpoint original settings before applying a task profile.");
        try { applyStates(desired, enabled, false); }
        catch (RuntimeException failure) {
            // The original lease remains durable even if a module's rollback callback also fails.
            try { applyStates(previous, oldEnabled, true); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
    private static void requireIdle() {
        if (!dev.monocle.client.MonocleClient.mc.isSameThread()) throw new IllegalStateException("Apply task profiles on the client thread.");
        if (Modules.get().get(HighwayBuilder.class).hasJob() || Modules.get().get(PrinterHelper.class).isActive())
            throw new IllegalStateException("Finish native highway/printer recovery before changing task profiles.");
    }
    static boolean settingsChanged(CompoundTag before, CompoundTag after) { return !before.equals(after); }

    private static void applyStates(Map<Module, CompoundTag> settings, Map<Module, Boolean> enabled, boolean restoring) {
        RuntimeException failed = null;
        Set<Module> changed = new HashSet<>(), broken = new HashSet<>();
        settings.forEach((module, tag) -> { if (settingsChanged(module.settings.toTag(), tag)) changed.add(module); });
        // Changed modules are inactive while ALL settings are installed. Unchanged modules never cycle.
        for (int phase = 0; phase < 3; phase++) for (var entry : settings.entrySet()) {
            Module module = entry.getKey();
            try {
                boolean active = enabled.get(module);
                if (phase == 0 && module.isActive() && !executorOwned(module.name) && (changed.contains(module) || !active)) module.disable();
                if (phase == 1 && changed.contains(module)) module.settings.fromTag(entry.getValue());
                if (phase == 2 && !broken.contains(module) && !executorOwned(module.name) && active && !module.isActive()) module.enable();
            } catch (RuntimeException error) {
                if (!restoring) throw error;
                broken.add(module);
                if (failed == null) failed = error; else failed.addSuppressed(error);
            }
        }
        if (failed != null) throw failed;
    }
    public static void restore() {
        if (originals == null) return;
        JsonObject previous = originals;
        try { apply(previous); originals = null; }
        catch (RuntimeException e) { originals = previous; throw e; }
    }
    public static void recoverOriginal(JsonObject snapshot) { originals = validate(snapshot); restore(); }
    /** Module persistence retains the user's settings while a job owns temporary overrides. */
    public static CompoundTag persistentTag(String module, CompoundTag tag) {
        if (originals == null || !originals.has(module)) return tag;
        JsonObject previous = originals.getAsJsonObject(module);
        tag.put("settings", parse(previous.get("settings").getAsString()));
        tag.putBoolean("active", previous.get("active").getAsBoolean()); return tag;
    }
}
