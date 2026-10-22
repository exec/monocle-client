package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;

import java.io.DataOutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.util.List;

/** Profile validation must reject malformed settings before touching any live module. */
public final class BotProfilesTest {
    public static void run() throws Exception {
        unchangedModules();
        effectiveCheckpoint();
        builtinWorkerProfiles();
        String launcherSettings = """
            {"groups":[{"name":"General","settings":[{"name":"vanilla-speed","value":5d},{"name":"existing","value":["a]b"]},{"name":"mode","value":"Vanilla"}]}]}
            """;
        assert BotProfiles.validate(profile(launcherSettings)).equals(profile(launcherSettings)) : "Launcher merges use the same worker SNBT validation";
        var launcherTag=TagParser.parseCompoundFully(launcherSettings);
        String settingsPatch="{groups:[{name:'General',settings:[{name:'vanilla-speed',value:6d}]}]}";
        assert TagParser.parseCompoundFully(dev.monocle.coordinator.SettingsOverlay.merge(launcherSettings,settingsPatch)).equals(BotProfiles.mergeSettings(launcherTag,TagParser.parseCompoundFully(settingsPatch))):"Host overlay must agree with Minecraft's settings merge";
        JsonObject personal=profile(launcherSettings).getAsJsonObject("elytra-fly"),host=profile("{groups:[{name:'General',settings:[{name:'vanilla-speed',value:6d}]}]}").getAsJsonObject("elytra-fly");
        JsonObject comparison=BotProfiles.comparison(personal,host,personal,"test snapshot");
        assert comparison.getAsJsonArray("rows").asList().stream().map(v->v.getAsJsonObject()).anyMatch(row->row.get("setting").getAsString().equals("General / vanilla-speed")&&row.get("requested").getAsString().equals("6.0d")&&row.get("current").getAsString().equals("5.0d"));
        assert comparison.getAsJsonArray("rows").asList().stream().map(v->v.getAsJsonObject()).anyMatch(row->row.get("setting").getAsString().equals("General / mode")&&row.get("requested").getAsString().equals("Inherit worker value"));
        assert BotProfiles.comparison(null,host,personal,"legacy").getAsJsonArray("rows").get(0).getAsJsonObject().get("personal").getAsString().equals("Not recorded");
        for(String valid:List.of("Highway copy","6b6t-import_1"))BotProfiles.checkCopyName(valid);
        for(String invalid:List.of("../escape","a/b","a\\b","Current","CON","LPT1","trailing ","")){
            try{BotProfiles.checkCopyName(invalid);throw new AssertionError("Unsafe name accepted: "+invalid);}catch(IllegalArgumentException expected){}
        }
        var localProfile=TagParser.parseCompoundFully("{modules:[{name:'elytra-fly',active:0b,settings:"+launcherSettings+"},{name:'ambience',active:1b,settings:{}},{name:'highway-builder',active:1b,settings:{}}]}");
        var localBefore=localProfile.copy();JsonObject copyOverlay=new JsonObject();copyOverlay.add("elytra-fly",host);
        var copied=BotProfiles.personalCopy(localProfile,copyOverlay);
        assert localProfile.equals(localBefore):"Copying profiles must not change the local baseline";
        var copiedModules=copied.getListOrEmpty("modules");
        assert copiedModules.getCompoundOrEmpty(0).toString().contains("6.0d")&&copiedModules.getCompoundOrEmpty(0).toString().contains("Vanilla");
        assert copiedModules.getCompoundOrEmpty(1).equals(localProfile.getListOrEmpty("modules").getCompoundOrEmpty(1));
        assert !copiedModules.getCompoundOrEmpty(2).getBooleanOr("active",true):"Copied profiles must not launch a native executor";
        JsonObject unavailable=new JsonObject();unavailable.add("missing-module",host);
        try{BotProfiles.personalCopy(localProfile,unavailable);throw new AssertionError("Missing module silently lost");}catch(IllegalArgumentException expected){}
        for (var control : dev.monocle.coordinator.JobSettingControls.ALL) {
            JsonObject request = new JsonObject(); request.addProperty("control", control.id());
            request.addProperty("active", true); request.addProperty("value", control.example());
            var modules = dev.monocle.coordinator.JobSettingControls.preview(request).getAsJsonObject("modules");
            assert BotProfiles.validate(modules).equals(modules);
            var settings = TagParser.parseCompoundFully(modules.getAsJsonObject(control.module()).get("settings").getAsString());
            if (control.numeric()) {
                var value = settings.getListOrEmpty("groups").getCompoundOrEmpty(0).getListOrEmpty("settings").getCompoundOrEmpty(0).get("value");
                assert control.step() == 1 ? value instanceof net.minecraft.nbt.IntTag : value instanceof net.minecraft.nbt.DoubleTag;
            }
            if (control.id().equals("speed")) {
                String mergedPreview = BotProfiles.mergeSettings(launcherTag, settings).toString();
                assert mergedPreview.contains("5.5d") && mergedPreview.contains("Vanilla") && mergedPreview.contains("a]b");
            }
        }
        assert launcherTag.getListOrEmpty("groups").size()==1;
        for (String valid : List.of("{}", "{groups:[]}", "{groups:[{name:'General',settings:[{name:'speed',value:2.5}]}]}")) {
            JsonObject profile = profile(valid);
            assert BotProfiles.validate(profile).equals(profile);
            BotProfiles.validateSettings(TagParser.parseCompoundFully(valid));
        }
        for (String invalid : List.of("{groups:{}}", "{groups:[1]}", "{groups:[{}]}", "{groups:[{name:1}]}",
            "{groups:[{name:'General',settings:{}}]}", "{groups:[{name:'General',settings:[1]}]}",
            "{groups:[{name:'General',settings:[{}]}]}", "{groups:[{name:'A'},{name:'A'}]}",
            "{groups:[{name:'General',settings:[{name:'x'},{name:'x'}]}]}")) {
            try { BotProfiles.validate(profile(invalid)); throw new AssertionError("Invalid settings accepted: " + invalid); }
            catch (IllegalArgumentException expected) {}
        }
        JsonObject nativeExecutor = new JsonObject(); nativeExecutor.add("highway-builder", profile("{}").get("elytra-fly").deepCopy());
        nativeExecutor.getAsJsonObject("highway-builder").addProperty("active", true);
        try { BotProfiles.validate(nativeExecutor); throw new AssertionError("Profiles must not start native jobs"); } catch (IllegalArgumentException expected) {}
        var original = TagParser.parseCompoundFully(launcherSettings);
        var merged = BotProfiles.mergeSettings(original, TagParser.parseCompoundFully("{groups:[{name:'General',settings:[{name:'vanilla-speed',value:6d}]}]}"));
        assert original.equals(TagParser.parseCompoundFully(launcherSettings)) : "Live patches must not mutate the checkpoint";
        assert merged.toString().contains("6.0d") && merged.toString().contains("Vanilla") && merged.toString().contains("a]b") : "Unspecified settings survive live updates";
        try { BotProfiles.mergeSettings(original, TagParser.parseCompoundFully("{groups:[{name:'General',settings:[{name:'typo',value:1}]}]}")); throw new AssertionError("Unknown live setting accepted"); }
        catch (IllegalArgumentException expected) {}
        var same = TagParser.parseCompoundFully("{groups:[{name:'General',settings:[{name:'speed',value:2.5}]}]}");
        assert !BotProfiles.settingsChanged(same, same.copy()) : "Same settings must not cycle ElytraFly or other active modules";
        assert BotProfiles.settingsChanged(same, new CompoundTag());
        var file = Files.createTempFile("monocle-profile-check-", ".nbt");
        try {
            NbtIo.write(same, file);
            assert BotProfiles.readProfile(file).equals(same) : "Existing module profiles use uncompressed NBT";
            try (var out = new DataOutputStream(Files.newOutputStream(file))) {
                out.writeByte(10); out.writeUTF(""); out.writeByte(7); out.writeUTF("oversized"); out.writeInt(1_000_000_000);
            }
            try { BotProfiles.readProfile(file); throw new AssertionError("Claimed NBT array allocation bypassed the quota"); }
            catch (net.minecraft.nbt.NbtAccounterException expected) {}
        } finally { Files.deleteIfExists(file); }
        try (var bytes = BotProfiles.class.getResourceAsStream("BotProfiles.class")) {
            var code = ClassFile.of().parse(bytes.readAllBytes());
            for (String method : List.of("apply", "begin", "applyStates", "persistentTag", "fullSettings")) {
                var calls = code.methods().stream().filter(m -> m.methodName().equalsString(method) && (!method.equals("apply") || m.methodTypeSymbol().parameterCount() == 2)).findFirst().orElseThrow().code().orElseThrow().elementList().stream()
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
                if (method.equals("apply") || method.equals("begin")) assert calls.contains("requireIdle");
                if (method.equals("apply")) assert calls.stream().filter("applyStates"::equals).count() == 2 && calls.contains("addSuppressed") : "Failure rolls back prior in-memory settings and preserves recovery errors";
                if (method.equals("applyStates")) assert calls.containsAll(List.of("contains", "disable", "fromTag", "enable"));
                if (method.equals("persistentTag")) assert calls.containsAll(List.of("put", "putBoolean")) : "Native persistence still substitutes the original lease";
                if (method.equals("fullSettings")) assert !calls.contains("wasChanged") && calls.stream().filter("toTag"::equals).count() == 2 : "Live configuration must serialize every setting, including defaults";
            }
        }
        try (var bytes = BotProfiles.class.getResourceAsStream("/dev/monocle/client/systems/modules/Modules.class")) {
            var method = ClassFile.of().parse(bytes.readAllBytes()).methods().stream().filter(m -> m.methodName().equalsString("fromTag") && m.methodTypeSymbol().returnType().descriptorString().equals("Ldev/monocle/client/systems/modules/Modules;")).findFirst().orElseThrow();
            var calls = method.code().orElseThrow().elementList().stream().filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
            assert calls.indexOf("leased") >= 0 && calls.indexOf("leased") < calls.indexOf("disableAll") : "Global profile reload must not reset an active task's settings";
        }
        try(var bytes=BotProfiles.class.getResourceAsStream("/dev/monocle/client/systems/profiles/Profiles.class")){
            var methods=ClassFile.of().parse(bytes.readAllBytes()).methods();
            for(String name:List.of("registerSaved","fromTag")){
                var method=methods.stream().filter(m->m.methodName().equalsString(name)&&!m.flags().has(java.lang.reflect.AccessFlag.BRIDGE)).findFirst().orElseThrow();
                var calls=method.code().orElseThrow().elementList().stream().filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
                assert calls.stream().noneMatch(c->c.owner().asInternalName().equals("dev/monocle/client/systems/profiles/Profile")&&c.name().equalsString("save")):"Registering a copy must never recapture live settings";
                if(name.equals("fromTag"))assert calls.stream().anyMatch(c->c.name().equalsString("registerSaved")):"Rediscovery must preserve imported files";
            }
        }
        System.out.println("Bot profile checks passed: structured settings, complete effective checkpoints, unchanged-state policy, bounded NBT, native-idle guards and rollback/persistence paths.");
    }
    private static void builtinWorkerProfiles() throws Exception {
        var library = new dev.monocle.coordinator.OperationsLibrary(Files.createTempDirectory("monocle-builtin-profiles-").resolve("operations.json"));
        var classes = java.util.Map.ofEntries(
            java.util.Map.entry("auto-eat", dev.monocle.client.systems.modules.player.AutoEat.class),
            java.util.Map.entry("auto-gap", dev.monocle.client.systems.modules.player.AutoGap.class),
            java.util.Map.entry("auto-totem", dev.monocle.client.systems.modules.combat.AutoTotem.class),
            java.util.Map.entry("auto-armor", dev.monocle.client.systems.modules.combat.AutoArmor.class),
            java.util.Map.entry("enderman-look", dev.monocle.client.systems.modules.world.EndermanLook.class),
            java.util.Map.entry("reach", dev.monocle.client.systems.modules.player.Reach.class),
            java.util.Map.entry("speed", dev.monocle.client.systems.modules.movement.speed.Speed.class),
            java.util.Map.entry("elytra-fly", dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly.class),
            java.util.Map.entry("highway-builder", dev.monocle.client.systems.modules.world.HighwayBuilder.class));
        for (var record : library.list()) {
            var profile = library.get(record.getAsJsonObject().get("id").getAsString()).getAsJsonObject("package").getAsJsonObject("profiles").getAsJsonObject("Current");
            BotProfiles.validate(profile);
            for (var entry : profile.entrySet()) {
                var module = classes.get(entry.getKey());
                if (module == null) continue;
                try (var bytes = module.getResourceAsStream(module.getSimpleName() + ".class")) {
                    var constants = ClassFile.of().parse(bytes.readAllBytes()).methods().stream().filter(m -> m.code().isPresent())
                        .flatMap(m -> m.code().orElseThrow().elementList().stream())
                        .filter(java.lang.classfile.instruction.ConstantInstruction.class::isInstance)
                        .map(java.lang.classfile.instruction.ConstantInstruction.class::cast).map(c -> (Object)c.constantValue()).toList();
                    var settings = TagParser.parseCompoundFully(entry.getValue().getAsJsonObject().get("settings").getAsString());
                    for (var value : settings.getListOrEmpty("groups")) {
                        var group = (CompoundTag)value;
                        String name = group.getStringOr("name", "");
                        assert name.equals("General") || constants.contains(name) : module + " has no group " + name;
                        for (var item : group.getListOrEmpty("settings"))
                            assert constants.contains(((CompoundTag)item).getStringOr("name", "")) : module + " has no setting " + item;
                    }
                }
            }
        }
    }

    private static JsonObject profile(String settings) {
        JsonObject module = new JsonObject(); module.addProperty("settings", settings); module.addProperty("active", false);
        JsonObject profile = new JsonObject(); profile.add("elytra-fly", module); return profile;
    }

    private static void unchangedModules() throws Exception {
        var apply = BotProfiles.class.getDeclaredMethod("applyStates", java.util.Map.class, java.util.Map.class, boolean.class);
        apply.setAccessible(true);
        var module = countingModule();
        var baseline = BotProfiles.fullSettings(module.settings);
        assert module.settings.toTag().isEmpty() && !baseline.isEmpty() : "Real setting persistence omits defaults";
        for (var patch : List.of(new CompoundTag(), TagParser.parseCompoundFully("{groups:[{name:'General',settings:[{name:'speed',value:6d}]}]}"))) {
            apply.invoke(null, java.util.Map.of(module, BotProfiles.mergeSettings(baseline, patch)), java.util.Map.of(module, true), false);
            assert module.disabled == 0 && module.enabled == 0 && module.writes == 0 && module.isActive()
                : "An empty overlay or explicit unchanged default must not restart or reload an active module";
        }
        var changed = BotProfiles.mergeSettings(baseline, TagParser.parseCompoundFully("{groups:[{name:'General',settings:[{name:'speed',value:7d}]}]}"));
        apply.invoke(null, java.util.Map.of(module, changed), java.util.Map.of(module, true), false);
        assert module.speed.get() == 7 && module.disabled == 1 && module.enabled == 1 && module.writes > 0
            : "Real changes still install settings while inactive, then reactivate once";
        int writes = module.writes;
        for (boolean restoring : List.of(false, true)) {
            apply.invoke(null, java.util.Map.of(module, BotProfiles.fullSettings(module.settings)), java.util.Map.of(module, true), restoring);
            assert module.disabled == 1 && module.enabled == 1 && module.writes == writes
                : "Mixed default/nondefault settings must not cycle on reapplication or rollback";
        }
        apply.invoke(null, java.util.Map.of(module, changed), java.util.Map.of(module, false), false);
        assert !module.isActive() && module.disabled == 2 && module.enabled == 1 && module.writes == writes
            : "An activation-only disable still applies without reloading settings";
        apply.invoke(null, java.util.Map.of(module, changed), java.util.Map.of(module, true), false);
        assert module.isActive() && module.disabled == 2 && module.enabled == 2 && module.writes == writes;
        apply.invoke(null, java.util.Map.of(module, baseline), java.util.Map.of(module, true), true);
        assert module.speed.get() == 6 && module.disabled == 3 && module.enabled == 3
            : "Rollback restores real changes, including defaults";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void effectiveCheckpoint() throws Exception {
        var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        var allocator = (sun.misc.Unsafe) singleton.get(null);
        var modules = (dev.monocle.client.systems.modules.Modules) allocator.allocateInstance(dev.monocle.client.systems.modules.Modules.class);
        var module = countingModule();
        var instances = dev.monocle.client.systems.modules.Modules.class.getDeclaredField("moduleInstances"); instances.setAccessible(true);
        instances.set(modules, java.util.Map.of(CountingModule.class, module));
        var registryField = dev.monocle.client.systems.Systems.class.getDeclaredField("systems"); registryField.setAccessible(true);
        var registry = (java.util.Map) registryField.get(null);
        var key = dev.monocle.client.systems.modules.Modules.class;
        Object previous = registry.put(key, modules);
        var apply = BotProfiles.class.getDeclaredMethod("applyStates", java.util.Map.class, java.util.Map.class, boolean.class); apply.setAccessible(true);
        try {
            var defaults = BotProfiles.fullSettings(module.settings);
            assert BotProfiles.capture("Current").getAsJsonObject(module.name).get("settings").getAsString().equals("{}")
                : "Workflow captures remain sparse overlays";
            for (double speed : List.of(6d, 8d)) {
                var jobSettings = BotProfiles.mergeSettings(defaults, TagParser.parseCompoundFully("{groups:[{name:'General',settings:[{name:'speed',value:" + speed + "d}]}]}"));
                apply.invoke(null, java.util.Map.of(module, jobSettings), java.util.Map.of(module, true), false);
                var checkpoint = com.google.gson.JsonParser.parseString(BotProfiles.captureEffective().toString()).getAsJsonObject();
                var recorded = TagParser.parseCompoundFully(checkpoint.getAsJsonObject(module.name).get("settings").getAsString());
                assert recorded.equals(jobSettings) : "Effective checkpoints include default and nondefault values";
                assert checkpoint.getAsJsonObject(module.name).get("active").getAsBoolean();
                assert !BotProfiles.leased() : "Reading a checkpoint must not acquire a settings lease";
                var personal = BotProfiles.mergeSettings(defaults, TagParser.parseCompoundFully("{groups:[{name:'General',settings:[{name:'speed',value:7d},{name:'other',value:9d}]}]}"));
                apply.invoke(null, java.util.Map.of(module, personal), java.util.Map.of(module, true), true);
                assert recorded.equals(TagParser.parseCompoundFully(checkpoint.getAsJsonObject(module.name).get("settings").getAsString()))
                    : "Checkpoint values remain detached from later personal restoration";
                apply.invoke(null, java.util.Map.of(module, BotProfiles.mergeSettings(BotProfiles.fullSettings(module.settings), recorded)), java.util.Map.of(module, true), false);
                assert module.speed.get() == speed && module.settings.get("other").get().equals(0d)
                    : "Resume must restore job defaults rather than inherit personal values";
                int disabled = module.disabled, enabled = module.enabled, writes = module.writes;
                apply.invoke(null, java.util.Map.of(module, recorded), java.util.Map.of(module, true), false);
                assert module.disabled == disabled && module.enabled == enabled && module.writes == writes
                    : "Reapplying a complete checkpoint must not restart unchanged modules";
            }
            var name = dev.monocle.client.systems.modules.Module.class.getDeclaredField("name"); name.setAccessible(true);
            name.set(module, "highway-builder");
            assert !BotProfiles.captureEffective().getAsJsonObject(module.name).get("active").getAsBoolean()
                : "Checkpoints cannot activate task-owned executors";
            var category = dev.monocle.client.systems.modules.Module.class.getDeclaredField("category"); category.setAccessible(true);
            category.set(module, dev.monocle.client.systems.modules.Categories.Misc);
            assert BotProfiles.captureEffective().isEmpty() : "Non-gameplay modules remain outside task checkpoints";
        } finally {
            if (previous == null) registry.remove(key); else registry.put(key, previous);
        }
    }

    // Module/Setting constructors initialize renderer helpers; bypass them only in this headless fixture.
    private static CountingModule countingModule() throws Exception {
        var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        var allocator = (sun.misc.Unsafe) singleton.get(null);
        var module = (CountingModule) allocator.allocateInstance(CountingModule.class);
        module.enabledState = true;
        var settings = new dev.monocle.client.settings.Settings();
        for (String name : List.of("speed", "other")) {
            var setting = (dev.monocle.client.settings.DoubleSetting) allocator.allocateInstance(dev.monocle.client.settings.DoubleSetting.class);
            for (String field : List.of("name", "defaultValue", "value", "onChanged")) {
                var member = dev.monocle.client.settings.Setting.class.getDeclaredField(field); member.setAccessible(true);
                member.set(setting, switch (field) {
                    case "name" -> name;
                    case "onChanged" -> (java.util.function.Consumer<Double>) value -> { assert !module.isActive() : "Settings installed before activation"; module.writes++; };
                    default -> name.equals("speed") ? 6d : 0d;
                });
            }
            for (String field : List.of("min", "max")) {
                var member = dev.monocle.client.settings.DoubleSetting.class.getDeclaredField(field); member.setAccessible(true);
                member.setDouble(setting, field.equals("min") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
            }
            settings.getDefaultGroup().add(setting);
            if (name.equals("speed")) module.speed = setting;
        }
        for (String field : List.of("name", "settings", "category")) {
            var member = dev.monocle.client.systems.modules.Module.class.getDeclaredField(field); member.setAccessible(true);
            member.set(module, switch (field) {
                case "name" -> "elytra-fly";
                case "category" -> dev.monocle.client.systems.modules.Categories.Movement;
                default -> settings;
            });
        }
        return module;
    }

    private static final class CountingModule extends dev.monocle.client.systems.modules.Module {
        private boolean enabledState = true;
        int disabled, enabled, writes;
        dev.monocle.client.settings.Setting<Double> speed;

        CountingModule() {
            super(dev.monocle.client.systems.modules.Categories.Movement, "elytra-fly", "Profile test");
        }
        @Override public boolean isActive() { return enabledState; }
        @Override public void disable() { disabled++; enabledState = false; }
        @Override public void enable() { enabled++; enabledState = true; }
    }
}
