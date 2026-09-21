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
        String launcherSettings = """
            {"groups":[{"name":"General","settings":[{"name":"vanilla-speed","value":5d},{"name":"existing","value":["a]b"]},{"name":"mode","value":"Vanilla"}]}]}
            """;
        assert BotProfiles.validate(profile(launcherSettings)).equals(profile(launcherSettings)) : "Launcher merges use the same worker SNBT validation";
        var launcherTag=TagParser.parseCompoundFully(launcherSettings);
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
        System.out.println("Bot profile checks passed: structured settings, unchanged-state policy, bounded NBT, native-idle guards and rollback/persistence paths.");
    }
    private static JsonObject profile(String settings) {
        JsonObject module = new JsonObject(); module.addProperty("settings", settings); module.addProperty("active", false);
        JsonObject profile = new JsonObject(); profile.add("elytra-fly", module); return profile;
    }
}
