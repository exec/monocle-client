package dev.monocle.client.systems.bots;

import com.google.gson.*;

/** Actual LuaJ interpretation, not mocks or bytecode-string checks; callable from botsCheck. */
public final class BotLuaTest {
    public static void main(String[] args) { run(); System.out.println("Bot Lua checks passed."); }
    public static void run() {
        boolean assertions = false; assert assertions = true;
        if (!assertions) throw new IllegalStateException("Assertions required");
        JsonObject state = object("{\"count\":2}"), arguments = object("{\"step\":3}"), result = object("{\"moved\":7}");
        String script = "return function(ctx) ctx.state.count=ctx.state.count+ctx.args.step+ctx.result.moved; return bot.wait(ctx.state.count) end";
        BotLua.Decision first = BotLua.next(script, state, arguments, result, new JsonObject());
        assert first.state().get("count").getAsInt() == 12 && first.action().get("ticks").getAsInt() == 12;
        assert first.equals(BotLua.next(script, state, arguments, result, new JsonObject()));
        var follow=decide("return function(ctx) return bot.follow({target='00000000-0000-0000-0000-000000000001'}) end").action();
        assert follow.get("type").getAsString().equals("Travel")&&follow.get("follow").getAsBoolean();
        assert state.get("count").getAsInt() == 2 && arguments.get("step").getAsInt() == 3 && result.get("moved").getAsInt() == 7;
        assert BotLua.next(script, first.state(), arguments, result, new JsonObject()).state().get("count").getAsInt() == 22;
        assert decide("local n=0; return function(ctx) n=n+1; ctx.state.n=n; return bot.done() end").state().get("n").getAsInt() == 1;
        assert decide("local n=0; return function(ctx) n=n+1; ctx.state.n=n; return bot.done() end").state().get("n").getAsInt() == 1 : "Globals and closure captures do not survive a decision";
        String stableText = "return function(ctx) ctx.state.text=tostring({})..tostring(function() end); return bot.done() end";
        assert decide(stableText).equals(decide(stableText)) : "Object identities must not leak nondeterministic JVM addresses into checkpoints";
        invalid("return function(ctx) while true do end end", "budget");
        invalid("while true do end; return function(ctx) return bot.done() end", "budget");
        invalid("return function(ctx) pcall(function() while true do end end); return bot.done() end", "budget");
        invalid("return function(ctx) xpcall(function() while true do end end, function(e) return e end); return bot.done() end", "budget");
        invalid("return function(ctx) xpcall(function() error('x') end, function(e) while true do end end); return bot.done() end", "budget");
        invalid("return function(ctx) while true do pcall(function() error('x') end) end end", "budget");
        invalid("return function(ctx) local function f(n) return 1+f(n+1) end; f(0); return bot.done() end", "budget");
        invalid("return function(ctx) local s='x'; for i=1,30 do s=s..s end; ctx.state.s=s; return bot.done() end", "budget");
        invalid("return function(ctx) local s='" + "x".repeat(20_000) + "'; for i=1,100 do ctx.state['v'..i]=s end; return bot.done() end", "32 KiB");
        invalid("return function(ctx) ctx.state.self=ctx.state; return bot.done() end", "acyclic");
        invalid("return function(ctx) ctx.state.f=function() end; return bot.done() end", "acyclic");
        invalid("return function(ctx) ctx.state[true]=1; return bot.done() end", "keys");
        invalid("return function(ctx) ctx.state.bad=0/0; return bot.done() end", "finite");
        invalid("return function(ctx) return nil end", "action");
        invalid("return function(ctx) error('test failure') end", "test failure");
        BotLua.Decision recovered = decide("return function(ctx) local ok,err=pcall(function() error('expected') end); assert(not ok); ctx.state.caught=true; return bot.done() end");
        assert recovered.state().get("caught").getAsBoolean();
        assert decide("return function(ctx) for i=1,50 do assert(not pcall(function() error('recoverable') end)) end; return bot.done() end").action().get("type").getAsString().equals("Done") : "Caught errors must unwind quota frames cleanly";
        String[] forbidden = {"io", "os", "package", "require", "dofile", "loadfile", "load", "loadstring", "luajava", "java", "debug", "coroutine", "collectgarbage", "getmetatable", "setmetatable", "rawget", "rawset", "getfenv", "setfenv", "print", "string", "table", "_G"};
        for (String name : forbidden) decide("return function(ctx) assert(" + name + "==nil, 'exposed " + name + "'); return bot.done() end");
        assert decide("return function(ctx) local n=0; for _,v in ipairs({1,2,3}) do n=n+v end; for _,v in pairs({a=4,b=5}) do n=n+v end; ctx.state.n=n; return bot.done() end").state().get("n").getAsInt() == 15;
        assert decide("return function(ctx) assert(math.random==nil and math.randomseed==nil and math.frexp==nil); ctx.state.n=math.max(math.floor(4.8),math.sqrt(9))+math.abs(-2); return bot.done() end").state().get("n").getAsInt() == 6;
        JsonObject json = object("{\"emptyArray\":[],\"emptyObject\":{},\"null\":null,\"items\":[null,2,{\"n\":null}],\"numericKeys\":{\"1\":\"a\",\"2\":\"b\"}}");
        assert BotLua.next("return function(ctx) return bot.done() end", json, null, null, null).state().equals(json) : "JSON nulls, empty arrays and numeric string keys must survive checkpoints exactly";
        BotLua.Decision created = decide("return function(ctx) ctx.state.list=bot.array(); ctx.state.n=bot.null; return bot.done() end");
        assert created.state().get("list").isJsonArray() && created.state().get("n").isJsonNull();
        invalid("return function(ctx) ctx.state.list=bot.array({[2]=1}); return bot.done() end", "consecutive");
        invalid("return function(ctx) local t={}; for i=1,20000 do t[i]={} end; return bot.done() end", "budget");
        invalid("return function(ctx) local t=ctx.state; for i=1,18 do t.next={}; t=t.next end; return bot.done() end", "nesting");
        JsonObject oversized = new JsonObject();
        for (int i = 0; i < 4097; i++) oversized.addProperty("v" + i, 0);
        invalidData(oversized);
        JsonObject cycle = new JsonObject(); cycle.add("self", cycle); invalidData(cycle);
        oversized = new JsonObject(); oversized.addProperty("first", "x".repeat(20_000)); oversized.addProperty("second", "y".repeat(20_000)); invalidData(oversized);
        try { BotLua.validate("return " + "function() return ".repeat(40) + "nil " + "end ".repeat(40)); throw new AssertionError("Nested prototypes must be bounded"); }
        catch (IllegalArgumentException expected) { }
        nativeMetatableIsolation();
    }
    private static void nativeMetatableIsolation() {
        org.luaj.vm2.LuaValue previous = org.luaj.vm2.LuaString.s_metatable;
        try {
            org.luaj.vm2.LuaString.s_metatable = null;
            org.luaj.vm2.Globals other = new org.luaj.vm2.Globals();
            other.load(new org.luaj.vm2.lib.BaseLib()); other.load(new org.luaj.vm2.lib.PackageLib()); other.load(new org.luaj.vm2.lib.StringLib());
            invalid("return function(ctx) ctx.state.s=('x'):rep(1000000000); return bot.done() end", "budget");
            invalid("return function(ctx) pcall(('x').rep,'x',1000000000); return bot.done() end", "budget");
            invalid("return function(ctx) ctx.state.s=('%1000000000s'):format('x'); return bot.done() end", "budget");
        } finally { org.luaj.vm2.LuaString.s_metatable = previous; }
    }
    private static void invalidData(JsonObject state) {
        try { BotLua.next("return function(ctx) return bot.done() end", state, null, null, null); throw new AssertionError("Oversized/cyclic input must fail before Lua allocation"); }
        catch (IllegalArgumentException expected) { }
    }
    private static JsonObject object(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static BotLua.Decision decide(String source) { return BotLua.next(source, new JsonObject(), new JsonObject(), new JsonObject(), new JsonObject()); }
    private static void invalid(String source, String message) {
        try { decide(source); throw new AssertionError("Expected failure: " + message); }
        catch (IllegalArgumentException expected) { assert expected.getMessage().contains(message) : expected; }
    }
}
