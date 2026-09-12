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
            for (String method : List.of("apply", "begin", "applyStates", "persistentTag")) {
                var calls = code.methods().stream().filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow().code().orElseThrow().elementList().stream()
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
                if (method.equals("apply") || method.equals("begin")) assert calls.contains("requireIdle");
                if (method.equals("apply")) assert calls.stream().filter("applyStates"::equals).count() == 2 && calls.contains("addSuppressed") : "Failure rolls back prior in-memory settings and preserves recovery errors";
                if (method.equals("applyStates")) assert calls.containsAll(List.of("contains", "disable", "fromTag", "enable"));
                if (method.equals("persistentTag")) assert calls.containsAll(List.of("put", "putBoolean")) : "Native persistence still substitutes the original lease";
            }
        }
        try (var bytes = BotProfiles.class.getResourceAsStream("/dev/monocle/client/systems/modules/Modules.class")) {
            var method = ClassFile.of().parse(bytes.readAllBytes()).methods().stream().filter(m -> m.methodName().equalsString("fromTag") && m.methodTypeSymbol().returnType().descriptorString().equals("Ldev/monocle/client/systems/modules/Modules;")).findFirst().orElseThrow();
            var calls = method.code().orElseThrow().elementList().stream().filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
            assert calls.indexOf("leased") >= 0 && calls.indexOf("leased") < calls.indexOf("disableAll") : "Global profile reload must not reset an active task's settings";
        }
        System.out.println("Bot profile checks passed: structured settings, unchanged-state policy, bounded NBT, native-idle guards and rollback/persistence paths.");
    }
    private static JsonObject profile(String settings) {
        JsonObject module = new JsonObject(); module.addProperty("settings", settings); module.addProperty("active", false);
        JsonObject profile = new JsonObject(); profile.add("elytra-fly", module); return profile;
    }
}
