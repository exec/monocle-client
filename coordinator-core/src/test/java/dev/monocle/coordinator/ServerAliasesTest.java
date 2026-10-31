package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.Files;

/** Invented server addresses only; no DNS or game connections. */
public final class ServerAliasesTest {
    private static JsonArray servers(String text){return JsonParser.parseString(text).getAsJsonArray();}
    private static void rejects(Runnable code){try{code.run();throw new AssertionError("Invalid aliases accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    public static void run() throws Exception {
        JsonArray definitions=servers("[{name:'Synthetic server',address:'primary.example.invalid',aliases:['relay.example.invalid','192.0.2.12:25566','[2001:db8::1]:25566']}]");
        ServerAliases aliases=new ServerAliases(definitions);
        assert aliases.server("RELAY.EXAMPLE.INVALID.:25565").equals("primary.example.invalid");
        assert aliases.server("192.0.2.12:25566").equals("primary.example.invalid")&&aliases.server("[2001:db8::1]:25566").equals("primary.example.invalid");
        for(String unknown:new String[]{"evil-relay.example.invalid","relay.example.invalid.attacker.invalid","relay.example.invalid:25566","local","local:Synthetic world","different.invalid"})assert aliases.server(unknown).equals(unknown);
        assert aliases.scope("relay.example.invalid\nminecraft:the_end").equals("primary.example.invalid\nminecraft:the_end");
        assert !aliases.scope("relay.example.invalid\nminecraft:the_end").equals(aliases.scope("primary.example.invalid\nminecraft:overworld"));
        definitions.get(0).getAsJsonObject().addProperty("address","changed.invalid");assert aliases.server("relay.example.invalid").equals("primary.example.invalid");
        for(String invalid:new String[]{"https://example.invalid","name@example.invalid","example.invalid/path","example.invalid?key=x","example.invalid:0","example.invalid:65536","local","realms","*.example.invalid","example.invalid\n"}){
            JsonObject record=new JsonObject();record.addProperty("name","Synthetic");record.addProperty("address",invalid);record.add("aliases",new JsonArray());JsonArray list=new JsonArray();list.add(record);rejects(()->new ServerAliases(list));
        }
        rejects(()->new ServerAliases(servers("[{name:'A',address:'primary.invalid',aliases:['PRIMARY.INVALID:25565']}]") ));
        rejects(()->new ServerAliases(servers("[{name:'A',address:'primary.invalid',aliases:['relay.invalid']},{name:'B',address:'other.invalid',aliases:['RELAY.INVALID']}]") ));
        JsonObject corrupt=aliases.snapshot();corrupt.addProperty("revision","changed");rejects(()->ServerAliases.checked(corrupt));
        var root=Files.createTempDirectory("synthetic-server-aliases-");String revision=ServerAliases.load(root).revision();
        ServerAliases saved=ServerAliases.save(root,revision,aliases.snapshot().getAsJsonArray("servers"));assert ServerAliases.load(root).snapshot().equals(saved.snapshot());
        rejects(()->ServerAliases.save(root,revision,new JsonArray()));assert ServerAliases.load(root).revision().equals(saved.revision());
        assert BotChat.whisper("[unknown player head] SyntheticUser whispers: hello")[0].equals("SyntheticUser");
        assert BotChat.whisper("someone: SyntheticUser whispers: hello")==null&&BotChat.whisper("SyntheticUser whispers: hi\ncommand")==null;
        System.out.println("Server alias checks passed: exact explicit membership, ports/case, dimension isolation, rejection, owned snapshots and durable revisions.");
    }
}
