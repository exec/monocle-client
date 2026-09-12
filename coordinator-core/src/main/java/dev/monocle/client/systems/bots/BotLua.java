package dev.monocle.client.systems.bots;

import com.google.gson.*;
import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.*;
import java.io.StringReader;
import java.util.*;

/** Shared, Minecraft-free Lua evaluator. Package retained for client compatibility; no live coroutine is persisted. */
public final class BotLua {
    public static final int MAX_SOURCE = 32_768, MAX_STATE = 32_768;
    private static final LuaValue JSON_NULL = new LuaUserdata(new Object()) { @Override public String tojstring() { return "null"; } };
    public record Decision(JsonObject state, JsonObject action) { }
    private static final Map<String, Prototype> CACHE = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Prototype> eldest) { return size() > 64; }
    };
    private BotLua() { }

    public static void validate(String source) { prototype(source); }
    private static synchronized Prototype prototype(String source) {
        if (source == null || source.isBlank() || source.length() > MAX_SOURCE || source.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Lua source must be 1–32768 characters of text.");
        Prototype known = CACHE.get(source);
        if (known != null) return known;
        try {
            Globals compiler = new Globals(); LuaC.install(compiler);
            Prototype result = compiler.compilePrototype(new StringReader(source), "workflow");
            checkPrototype(result, 0); CACHE.put(source, result); return result;
        } catch (Exception | StackOverflowError e) { throw new IllegalArgumentException("Invalid Lua workflow: " + e.getMessage(), e); }
    }
    private static void checkPrototype(Prototype prototype, int depth) {
        if (depth > 32 || prototype.code.length > 32_768 || prototype.maxstacksize > 250)
            throw new IllegalArgumentException("Lua workflow exceeds compilation limits.");
        for (Prototype child : prototype.p) checkPrototype(child, depth + 1);
    }

    public static Decision next(String source, JsonObject state, JsonObject args, JsonObject result, JsonObject world) {
        try {
            Globals globals = new Globals();
            globals.load(new BaseLib()); globals.load(new PackageLib());
            globals.load(new MathLib());
            LuaTable math = globals.get("math").checktable();
            Set<String> safeMath = Set.of("abs", "ceil", "floor", "min", "max", "sqrt", "sin", "cos", "tan", "rad", "deg", "pow", "pi");
            for (LuaValue key : math.keys()) if (!safeMath.contains(key.tojstring())) math.rawset(key, LuaValue.NIL);
            Quota quota = new Quota(); globals.load(quota); // Initialize LuaJ's internal error hooks, then hide the debug API.
            // BaseLib's file loaders, package loaders, debug, Java access, coroutines and OS APIs are not exposed.
            Set<String> allowed = Set.of("assert", "error", "ipairs", "pairs", "next", "select", "tonumber", "tostring", "type", "pcall", "xpcall", "_VERSION", "math");
            List<LuaValue> remove = new ArrayList<>();
            for (LuaValue key : globals.keys()) if (!allowed.contains(key.tojstring())) remove.add(key);
            remove.forEach(key -> globals.rawset(key, LuaValue.NIL));
            globals.set("tostring", new OneArgFunction() { @Override public LuaValue call(LuaValue value) {
                // JVM identity hashes are not checkpoint-stable values.
                return LuaValue.valueOf(value == JSON_NULL ? "null" : value.istable() || value.isfunction() ? value.typename() : value.tojstring());
            } });
            IdentityHashMap<LuaValue, Boolean> arrays = new IdentityHashMap<>();
            globals.set("bot", api(arrays));
            quota.approve(globals);
            quota.nativeFunctions.add(globals.get("ipairs").invoke(new LuaTable()).arg1());
            LuaTable context = new LuaTable();
            context.set("state", fromJson(state, arrays, 0, new int[2])); context.set("args", fromJson(args, arrays, 0, new int[2]));
            context.set("result", fromJson(result, arrays, 0, new int[2])); context.set("world", fromJson(world, arrays, 0, new int[2]));
            LuaValue entry = new LuaClosure(prototype(source), globals).call();
            if (!entry.isfunction()) throw new IllegalArgumentException("A Lua workflow must return function(ctx) ... end.");
            LuaValue action = entry.call(context);
            JsonElement saved = toJson(context.get("state"), arrays, new IdentityHashMap<>(), 0, new int[2]);
            JsonElement requested = toJson(action, arrays, new IdentityHashMap<>(), 0, new int[2]);
            if (!saved.isJsonObject() || !requested.isJsonObject()) throw new IllegalArgumentException("Lua must keep ctx.state as an object and return one bot action.");
            if (saved.toString().length() > MAX_STATE || requested.toString().length() > MAX_STATE) throw new IllegalArgumentException("Lua state/action exceeds 32 KiB.");
            return new Decision(saved.getAsJsonObject(), requested.getAsJsonObject());
        } catch (QuotaExceeded | StackOverflowError e) { throw new IllegalArgumentException("Lua workflow exceeded its instruction, allocation or call-depth budget.", e); }
        catch (LuaError e) { throw new IllegalArgumentException("Lua workflow: " + e.getMessage(), e); }
    }

    private static LuaTable api(IdentityHashMap<LuaValue, Boolean> arrays) {
        LuaTable bot = new LuaTable();
        bot.set("null", JSON_NULL);
        bot.set("array", new OneArgFunction() { @Override public LuaValue call(LuaValue items) {
            LuaTable value = items.isnil() ? new LuaTable() : items.checktable(); arrays.put(value, true); return value;
        } });
        Map<String, String> actions = Map.of("travel", "Travel", "stash_hunt", "StashHunt", "drop", "DropItems", "modules", "Modules", "tpa", "Tpa", "highway", "Highway", "recover", "RecoverSupplies");
        actions.forEach((name, type) -> bot.set(name, new OneArgFunction() {
            @Override public LuaValue call(LuaValue argument) {
                LuaTable value = argument.checktable(); value.set("type", type); return value;
            }
        }));
        bot.set("done", new OneArgFunction() { @Override public LuaValue call(LuaValue result) {
            LuaTable value = action("Done"); if (!result.isnil()) value.set("result", result); return value;
        } });
        bot.set("wait", new OneArgFunction() { @Override public LuaValue call(LuaValue ticks) { LuaTable value = action("Wait"); value.set("ticks", ticks.checkint()); return value; } });
        bot.set("profile", new OneArgFunction() { @Override public LuaValue call(LuaValue name) { LuaTable value = action("SetProfile"); value.set("name", name.checkjstring()); return value; } });
        bot.set("call", new TwoArgFunction() { @Override public LuaValue call(LuaValue id, LuaValue args) {
            LuaTable value = action("Call"); value.set("workflow", id.checkjstring()); value.set("args", args.isnil() ? new LuaTable() : args.checktable()); return value;
        } });
        bot.set("fail", new OneArgFunction() { @Override public LuaValue call(LuaValue reason) { LuaTable value = action("Fail"); value.set("detail", reason.checkjstring()); return value; } });
        return bot;
    }
    private static LuaTable action(String type) { LuaTable value = new LuaTable(); value.set("type", type); return value; }
    private static LuaValue fromJson(JsonElement json, IdentityHashMap<LuaValue, Boolean> arrays, int depth, int[] budget) {
        if (depth > 16 || ++budget[0] > 4096) throw new IllegalArgumentException("Workflow data exceeds its size/nesting limit.");
        if (json == null) return LuaValue.NIL;
        if (json.isJsonNull()) return JSON_NULL;
        if (json.isJsonObject()) {
            LuaTable value = new LuaTable();
            for (var entry : json.getAsJsonObject().entrySet()) {
                if (entry.getKey().length() > 256 || (budget[1] += entry.getKey().length()) > MAX_STATE) throw new IllegalArgumentException("Workflow object keys exceed the data limit.");
                value.set(entry.getKey(), fromJson(entry.getValue(), arrays, depth + 1, budget));
            }
            return value;
        }
        if (json.isJsonArray()) {
            LuaTable value = new LuaTable(); arrays.put(value, true);
            int index = 1; for (JsonElement child : json.getAsJsonArray()) value.set(index++, fromJson(child, arrays, depth + 1, budget)); return value;
        }
        JsonPrimitive value = json.getAsJsonPrimitive();
        if (value.isBoolean()) return LuaValue.valueOf(value.getAsBoolean());
        if (value.isNumber()) {
            double number = value.getAsDouble(); if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite workflow number."); return LuaValue.valueOf(number);
        }
        if ((budget[1] += value.getAsString().length()) > MAX_STATE) throw new IllegalArgumentException("Workflow strings exceed 32 KiB.");
        return LuaValue.valueOf(value.getAsString());
    }
    private static JsonElement toJson(LuaValue value, IdentityHashMap<LuaValue, Boolean> arrays, IdentityHashMap<LuaValue, Boolean> path, int depth, int[] count) {
        if (++count[0] > 4096 || depth > 16) throw new IllegalArgumentException("Workflow data exceeds its size/nesting limit.");
        if (value.isnil() || value == JSON_NULL) return JsonNull.INSTANCE;
        if (value.isboolean()) return new JsonPrimitive(value.toboolean());
        if (value.type() == LuaValue.TNUMBER) {
            double number = value.todouble(); if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite workflow number."); return new JsonPrimitive(number);
        }
        if (value.type() == LuaValue.TSTRING) {
            if ((count[1] += value.rawlen()) > MAX_STATE) throw new IllegalArgumentException("Workflow strings exceed 32 KiB."); return new JsonPrimitive(value.tojstring());
        }
        if (!value.istable() || path.put(value, true) != null) throw new IllegalArgumentException("Only acyclic JSON data can be checkpointed; no functions or game objects.");
        LuaTable table = value.checktable(); LuaValue[] keys = table.keys();
        boolean sequence = keys.length > 0 && Arrays.stream(keys).allMatch(key -> key.type() == LuaValue.TNUMBER && key.isint() && key.toint() >= 1 && key.toint() <= keys.length);
        boolean array = arrays.containsKey(value) || sequence;
        if (array && keys.length > 0 && !sequence) throw new IllegalArgumentException("JSON arrays must have consecutive integer keys.");
        JsonElement result;
        if (array) {
            JsonArray items = new JsonArray(); for (int i = 1; i <= keys.length; i++) items.add(toJson(table.get(i), arrays, path, depth + 1, count)); result = items;
        } else {
            JsonObject object = new JsonObject();
            for (LuaValue key : keys) {
                if (key.type() != LuaValue.TSTRING || key.rawlen() > 256) throw new IllegalArgumentException("Object keys must be strings up to 256 bytes.");
                if ((count[1] += key.rawlen()) > MAX_STATE) throw new IllegalArgumentException("Workflow object keys exceed 32 KiB.");
                object.add(key.tojstring(), toJson(table.get(key), arrays, path, depth + 1, count));
            }
            result = object;
        }
        path.remove(value); return result;
    }
    private static final class QuotaExceeded extends Error { }
    private static final class Quota extends DebugLib {
        private int instructions, allocations, stringBytes;
        private final IdentityHashMap<LuaValue, Boolean> seen = new IdentityHashMap<>();
        private final ArrayDeque<LuaValue[]> frames = new ArrayDeque<>();
        private final Set<LuaValue> nativeFunctions = Collections.newSetFromMap(new IdentityHashMap<>());
        private void approve(LuaTable table) {
            for (LuaValue key : table.keys()) {
                LuaValue value = table.get(key);
                if (value.isfunction()) nativeFunctions.add(value);
                else if (value.istable()) approve(value.checktable());
            }
        }
        @Override public void onCall(LuaClosure function, Varargs arguments, LuaValue[] stack) {
            if (frames.size() >= 32) throw new QuotaExceeded(); frames.push(stack);
        }
        @Override public void onCall(LuaFunction function) { if (frames.size() >= 32) throw new QuotaExceeded(); frames.push(new LuaValue[0]); }
        @Override public void onReturn() { if (!frames.isEmpty()) frames.pop(); }
        @Override public void onInstruction(int pc, Varargs varargs, int top) {
            if (++instructions > 12_000) throw new QuotaExceeded();
            if (!frames.isEmpty()) for (LuaValue value : frames.peek()) if (value != null) {
                // LuaJ's scalar metatables are JVM-wide. A StringLib loaded elsewhere must not
                // smuggle allocation-heavy native functions into this restricted Globals.
                if (value.isfunction() && !value.isclosure() && !nativeFunctions.contains(value)) throw new QuotaExceeded();
                if (value.type() == LuaValue.TSTRING && value.rawlen() > MAX_STATE) throw new QuotaExceeded();
                if ((value.istable() || value.type() == LuaValue.TSTRING) && seen.put(value, true) == null) {
                    if (++allocations > 8192) throw new QuotaExceeded();
                    if (value.type() == LuaValue.TSTRING && (stringBytes += value.rawlen()) > 2_097_152) throw new QuotaExceeded();
                }
            }
        }
    }
}
