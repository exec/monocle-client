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
        JsonObject full = new JsonObject();
        for (Module module : Modules.get().getAll()) if (gameplay(module))
            full.add(module.name, encode(fullSettings(module.settings), module.isActive() && !executorOwned(module.name)));
        originals = validate(full); return originals.deepCopy();
    }

    /** A client-thread observation, not the effectiveProfile checkpoint (which may be old). */
    public static JsonObject compareModule(JsonObject profile, String name, JsonObject personal) {
        if (!dev.monocle.client.MonocleClient.mc.isSameThread()) throw new IllegalStateException("Read module settings on the client thread");
        Module module = Modules.get().get(name);
        if (module == null || !gameplay(module)) throw new IllegalArgumentException("Unknown gameplay module");
        JsonObject current = encode(fullSettings(module.settings), module.isActive());
        JsonObject baseline = personal == null ? current : personal.has(name) ? personal.getAsJsonObject(name) : null;
        return comparison(baseline, profile.has(name) ? profile.getAsJsonObject(name) : null, current,
            "Point-in-time client-thread snapshot. Host column is the selected captured overlay, not a claim that it is the active workflow profile. Live edits are shown separately in Latest live request. "
            + (personal == null ? "No job lease: personal and current values coincide." : "Personal values are the pre-job checkpoint; missing legacy values are not guessed.")
            + (executorOwned(name) ? " Executor activation is job-controlled." : ""));
    }
    static JsonObject comparison(JsonObject personal, JsonObject requested, JsonObject current, String note) {
        Map<String,String> now = flattened(current), before = flattened(personal), desired = flattened(requested);
        Set<String> keys = new TreeSet<>(now.keySet()); keys.addAll(before.keySet()); keys.addAll(desired.keySet());
        JsonArray rows = new JsonArray();
        for (String key : keys) {
            JsonObject row = new JsonObject(); row.addProperty("setting", key);
            row.addProperty("personal", before.getOrDefault(key,"Not recorded"));
            row.addProperty("requested", desired.getOrDefault(key,"Inherit worker value"));
            row.addProperty("current", now.getOrDefault(key,"Unavailable")); rows.add(row);
        }
        JsonObject report = new JsonObject(); report.addProperty("note", note); report.add("rows",rows);
        return dev.monocle.coordinator.ConfigurationReadback.checkedReport(report);
    }
    private static Map<String,String> flattened(JsonObject module) {
        Map<String,String> values = new TreeMap<>(); if (module == null) return values;
        values.put("Activation", module.get("active").getAsBoolean() ? "On" : "Off");
        for (Tag item : parse(module.get("settings").getAsString()).getListOrEmpty("groups")) {
            CompoundTag group = (CompoundTag)item;
            for (Tag entry : group.getListOrEmpty("settings")) {
                CompoundTag setting = (CompoundTag)entry;
                values.put(group.getStringOr("name","") + " / " + setting.getStringOr("name",""), String.valueOf(setting.get("value")));
            }
        }
        return values;
    }

    /** Explicit local copy only: build the file without applying any module or changing the active job. */
    public static void savePersonalCopy(String name, JsonObject overlay) {
        if (!dev.monocle.client.MonocleClient.mc.isSameThread()) throw new IllegalStateException("Save profiles on the client thread");
        checkCopyName(name);
        if (Profiles.get().get(name) != null) throw new IllegalArgumentException("A personal profile with that name already exists");
        JsonObject checked = validate(overlay);
        for(String id:checked.keySet())if(Modules.get().get(id)==null||!gameplay(Modules.get().get(id)))throw new IllegalArgumentException("Unknown gameplay module: "+id);
        CompoundTag output = Modules.get().toTag();
        for (Tag item : output.getListOrEmpty("modules")) {
            CompoundTag tag = (CompoundTag)item; String id = tag.getStringOr("name",""); Module module = Modules.get().get(id);
            if (module == null) continue;
            tag.put("settings",originals != null && originals.has(id) ? parse(originals.getAsJsonObject(id).get("settings").getAsString()) : fullSettings(module.settings));
        }
        output = personalCopy(output,checked);
        Path folder = Profiles.FOLDER.toPath().resolve(name);
        Path temporary = null;
        boolean created=false;
        try {
            Files.createDirectories(Profiles.FOLDER.toPath()); Files.createDirectory(folder); // Never replace an existing profile, even an unregistered one.
            created=true;
            temporary = Files.createTempFile(folder,"modules-",".tmp");
            NbtIo.write(output,temporary);
            try { Files.move(temporary,folder.resolve("modules.nbt"),java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temporary,folder.resolve("modules.nbt"));}
            Profile profile = new Profile(); profile.name.set(name); profile.modules.set(true);
            Profiles.get().registerSaved(profile);
        } catch (IOException e) { throw new IllegalStateException("Could not save profile: " + e.getMessage(),e); }
        finally {
            if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}
            if(created&&!Files.exists(folder.resolve("modules.nbt")))try{Files.deleteIfExists(folder);}catch(IOException ignored){}
        }
    }
    static void checkCopyName(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,63}") || !name.equals(name.strip()) || name.equalsIgnoreCase("Current")
            || name.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]"))
            throw new IllegalArgumentException("Use a new non-reserved name: letters, numbers, spaces, underscores or dashes (1–64 characters)");
    }
    static CompoundTag personalCopy(CompoundTag local, JsonObject overlay) {
        JsonObject checked=validate(overlay);CompoundTag output=local.copy();Set<String> remaining=new HashSet<>(checked.keySet());
        for(Tag item:output.getListOrEmpty("modules")) {
            CompoundTag module=(CompoundTag)item;String id=module.getStringOr("name","");
            if(checked.has(id)) {
                JsonObject patch=checked.getAsJsonObject(id);
                module.put("settings",mergeSettings(module.getCompoundOrEmpty("settings"),parse(patch.get("settings").getAsString())));
                module.putBoolean("active",patch.get("active").getAsBoolean());remaining.remove(id);
            }
            if(executorOwned(id))module.putBoolean("active",false);
        }
        if(!remaining.isEmpty())throw new IllegalArgumentException("Cannot copy unavailable modules: "+remaining);
        return output;
    }
    public static void apply(JsonObject snapshot) {
        apply(snapshot, false);
    }
    public static void applyLive(JsonObject snapshot) {
        apply(dev.monocle.coordinator.TaskWire.checkedConfiguration(snapshot), true);
    }
    private static void apply(JsonObject snapshot, boolean live) {
        JsonObject checked = validate(snapshot);
        if (!live) requireIdle();
        else if (!dev.monocle.client.MonocleClient.mc.isSameThread()) throw new IllegalStateException("Apply live configuration on the client thread");
        Map<Module, CompoundTag> desired = new LinkedHashMap<>(), previous = new LinkedHashMap<>();
        Map<Module, Boolean> enabled = new LinkedHashMap<>(), oldEnabled = new LinkedHashMap<>();
        for (String name : checked.keySet()) {
            Module module = Modules.get().get(name);
            if (module == null || !gameplay(module)) throw new IllegalArgumentException("Worker cannot apply gameplay module: " + name);
            CompoundTag patch = parse(checked.getAsJsonObject(name).get("settings").getAsString());
            // Workflow profiles are overlays: built-ins intentionally specify only their
            // job policy, while workers retain unrelated local module settings.
            desired.put(module, mergeSettings(fullSettings(module.settings), patch));
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
    static CompoundTag fullSettings(dev.monocle.client.settings.Settings settings) {
        CompoundTag tag = new CompoundTag(); net.minecraft.nbt.ListTag groups = new net.minecraft.nbt.ListTag();
        for (var group : settings) {
            CompoundTag g = group.toTag(); net.minecraft.nbt.ListTag values = new net.minecraft.nbt.ListTag();
            for (var setting : group) values.add(setting.toTag());
            g.put("settings", values); groups.add(g);
        }
        tag.put("groups", groups); return tag;
    }
    static CompoundTag mergeSettings(CompoundTag current, CompoundTag patch) {
        validateSettings(patch);
        CompoundTag merged = current.copy();
        for (Tag t : patch.getListOrEmpty("groups")) {
            CompoundTag group = (CompoundTag) t;
            CompoundTag target = null;
            for (Tag g : merged.getListOrEmpty("groups")) if (((CompoundTag) g).getStringOr("name", "").equals(group.getStringOr("name", ""))) target = (CompoundTag) g;
            if (target == null) throw new IllegalArgumentException("Unknown settings group: " + group.getStringOr("name", ""));
            for (Tag s : group.getListOrEmpty("settings")) {
                CompoundTag setting = (CompoundTag) s; CompoundTag destination = null;
                for (Tag old : target.getListOrEmpty("settings")) if (((CompoundTag) old).getStringOr("name", "").equals(setting.getStringOr("name", ""))) destination = (CompoundTag) old;
                if (destination == null) throw new IllegalArgumentException("Unknown setting: " + setting.getStringOr("name", ""));
                destination.merge(setting);
            }
        }
        return merged;
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
