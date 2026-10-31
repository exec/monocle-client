package dev.monocle.coordinator;

import com.google.gson.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

/** Explicit operator assertions of shared worlds; never infers aliases from DNS or suffixes. */
public final class ServerAliases {
    public static final String FILE="minecraft-servers.json";
    private final JsonArray servers;
    private final Map<String,String> addresses=new HashMap<>();
    public ServerAliases(JsonArray definitions){
        if(definitions==null||definitions.size()>32)throw new IllegalArgumentException("Define at most 32 Minecraft servers");
        servers=definitions.deepCopy();
        for(JsonElement value:servers){
            JsonObject server=value.getAsJsonObject();
            if(!server.keySet().equals(Set.of("name","address","aliases")))throw new IllegalArgumentException("A server needs name, address and aliases");
            for(String key:List.of("name","address"))if(!server.get(key).isJsonPrimitive()||!server.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException("Server fields must be text");
            String name=text(server,"name"),primary=text(server,"address");
            if(name.isBlank()||name.length()>64||name.chars().anyMatch(Character::isISOControl)||!server.get("aliases").isJsonArray()||server.getAsJsonArray("aliases").size()>16)throw new IllegalArgumentException("Invalid server definition");
            add(primary,primary);
            for(JsonElement alias:server.getAsJsonArray("aliases")){
                if(!alias.isJsonPrimitive()||!alias.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Server aliases must be addresses");
                add(alias.getAsString(),primary);
            }
        }
        TaskFiles.jsonBytes(snapshot(),65_536);
    }
    private void add(String address,String primary){
        if(addresses.putIfAbsent(addressKey(address),primary)!=null)throw new IllegalArgumentException("An address may belong to only one server, without duplicates");
    }
    private static String addressKey(String address){
        if(address==null||address.isBlank()||address.length()>255||address.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)))throw new IllegalArgumentException("Invalid Minecraft address");
        URI uri;
        try{uri=URI.create("minecraft://"+address);}catch(RuntimeException e){throw new IllegalArgumentException("Use a hostname or IP with an optional port");}
        String host=uri.getHost();int port=uri.getPort();
        if(host==null||host.equalsIgnoreCase("local")||host.equalsIgnoreCase("realms")||uri.getUserInfo()!=null||!uri.getPath().isEmpty()||uri.getQuery()!=null||uri.getFragment()!=null||port==0||port>65535||address.endsWith(":"))throw new IllegalArgumentException("Use a multiplayer hostname or IP with an optional port");
        host=host.toLowerCase(Locale.ROOT);if(host.endsWith("."))host=host.substring(0,host.length()-1);
        return host+(port==-1||port==25565?"":":"+port);
    }
    public String server(String address){
        if(address==null)return "";
        try{return addresses.getOrDefault(addressKey(address),address);}catch(IllegalArgumentException e){return address;}
    }
    public String scope(String scope){int split=scope.indexOf('\n');return split<1?scope:server(scope.substring(0,split))+scope.substring(split);}
    public String revision(){return TaskFiles.hash(servers.toString());}
    public JsonObject snapshot(){JsonObject out=new JsonObject();out.addProperty("version",1);out.add("servers",servers.deepCopy());out.addProperty("revision",revision());return out;}
    public static ServerAliases checked(JsonObject snapshot){
        if(!snapshot.keySet().containsAll(Set.of("version","servers"))||!Set.of("version","servers","revision").containsAll(snapshot.keySet())||StashCatalog.integer(snapshot,"version",1,1)!=1||!snapshot.get("servers").isJsonArray())throw new IllegalArgumentException("Invalid Minecraft server catalog");
        ServerAliases result=new ServerAliases(snapshot.getAsJsonArray("servers"));
        if(snapshot.has("revision")&&!text(snapshot,"revision").equals(result.revision()))throw new IllegalArgumentException("Minecraft server revision mismatch");
        return result;
    }
    public static ServerAliases load(Path root){JsonObject saved=TaskFiles.read(root.resolve(FILE));return saved.isEmpty()?new ServerAliases(new JsonArray()):checked(saved);}
    public static synchronized ServerAliases save(Path root,String expected,JsonArray definitions){
        if(!load(root).revision().equals(expected))throw new IllegalStateException("Minecraft servers changed; reload before editing");
        ServerAliases next=new ServerAliases(definitions);TaskFiles.write(root.resolve(FILE),next.snapshot());return next;
    }
}
