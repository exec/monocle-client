package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

final class KnownHostilesTest {
    static JsonObject evidence(UUID attacker,long at){JsonObject damage=new JsonObject();damage.addProperty("scope","EXAMPLE.org:25565\nminecraft:the_nether");damage.addProperty("attribution","server_reported");damage.addProperty("at",at);JsonObject actor=new JsonObject();actor.addProperty("uuid",attacker.toString());actor.addProperty("name","Enemy");actor.addProperty("player",true);damage.add("attacker",actor);JsonObject combat=new JsonObject();combat.add("lastDamage",damage);return combat;}
    static void run()throws Exception{
        Path file=Files.createTempDirectory("known-hostiles-").resolve("known-hostiles.json");KnownHostiles list=new KnownHostiles(file);
        UUID reporter=UUID.randomUUID(),attacker=UUID.randomUUID();String scope="example.org\nminecraft:the_nether";
        assert !list.enabled();JsonObject combat=evidence(attacker,100);
        assert list.observe(reporter,scope,combat,Set.of());assert list.match("example.org:25565",attacker,"RenamedEnemy").equals("Enemy");
        assert list.match("elsewhere.org",attacker,"Enemy")==null;assert list.match("example.org",UUID.randomUUID(),"Enemy")==null : "UUID entries must not match a reused name";
        assert !list.observe(reporter,scope,combat,Set.of());long revision=list.revision();
        JsonObject entry=list.snapshot().getAsJsonArray("players").get(0).getAsJsonObject();list.remove(entry.get("id").getAsString());
        list=new KnownHostiles(file);assert !list.observe(reporter,scope,combat,Set.of()) : "Forgiveness survives replay and host restart";
        assert list.snapshot().getAsJsonArray("players").isEmpty();assert list.observe(reporter,scope,evidence(attacker,101),Set.of());
        assert !list.observe(reporter,scope,evidence(reporter,102),Set.of());
        UUID crewmate=UUID.randomUUID();assert !list.observe(reporter,scope,evidence(crewmate,104),Set.of(crewmate));
        JsonObject suspect=evidence(UUID.randomUUID(),105);suspect.getAsJsonObject("lastDamage").addProperty("attribution","unknown");assert !list.observe(reporter,scope,suspect,Set.of());
        JsonObject trusted=evidence(UUID.randomUUID(),106);trusted.getAsJsonObject("lastDamage").addProperty("attackerTrusted",true);assert !list.observe(reporter,scope,trusted,Set.of());
        assert !list.observe(reporter,"other.org\nminecraft:the_nether",evidence(UUID.randomUUID(),107),Set.of());
        var manual=list.add("example.org","Manual","");assert list.match("example.org",UUID.randomUUID(),"manual").equals("Manual");
        var duplicate=list.add("EXAMPLE.org:25565","manual","");assert manual.get("id").equals(duplicate.get("id"));
        UUID manualId=UUID.randomUUID();JsonObject upgrade=evidence(manualId,109);upgrade.getAsJsonObject("lastDamage").getAsJsonObject("attacker").addProperty("name","Manual");
        assert list.observe(reporter,scope,upgrade,Set.of());assert list.match("example.org",manualId,"NewName").equals("Manual");assert list.match("example.org",UUID.randomUUID(),"Manual")==null;
        JsonObject mob=evidence(UUID.randomUUID(),108);mob.getAsJsonObject("lastDamage").getAsJsonObject("attacker").addProperty("player",false);assert !list.observe(reporter,scope,mob,Set.of());
        list.policy(true);var cached=list.snapshot();cached.getAsJsonArray("players").asList().clear();assert !list.snapshot().getAsJsonArray("players").isEmpty();assert new KnownHostiles(file).enabled();
        KnownHostiles client=new KnownHostiles(file.resolveSibling("client.json"));client.accept(list.snapshot());assert client.enabled();assert client.snapshot().equals(list.snapshot());
        JsonObject invalid=list.snapshot();invalid.addProperty("enabled","true");try{client.accept(invalid);throw new AssertionError("String policy accepted");}catch(IllegalArgumentException expected){}
        assert client.enabled() : "Rejected updates cannot erase the guard";
        invalid=list.snapshot();invalid.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("server","EXAMPLE.org");try{client.accept(invalid);throw new AssertionError("Noncanonical server accepted");}catch(IllegalArgumentException expected){}
        invalid=list.snapshot();invalid.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("name","not a player");try{client.accept(invalid);throw new AssertionError("Invalid name accepted");}catch(IllegalArgumentException expected){}
        invalid=list.snapshot();invalid.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("uuid","invalid");try{client.accept(invalid);throw new AssertionError("Invalid UUID accepted");}catch(IllegalArgumentException expected){}
        var full=list.snapshot();JsonArray players=new JsonArray();full.add("players",players);
        for(int i=0;i<256;i++){JsonObject p=manual.deepCopy();p.addProperty("id",UUID.randomUUID().toString());p.addProperty("name","Enemy_"+i);p.addProperty("server","a".repeat(255));players.add(p);}
        KnownHostiles.validate(full);assert TaskFiles.jsonBytes(full,196_608).length<196_608;client.accept(full);
        try{client.add("example.org","Overflow","");throw new AssertionError("Player bound exceeded");}catch(IllegalStateException expected){}assert client.snapshot().equals(full):"Full-list failures preserve the previous snapshot";
        players.add(manual.deepCopy());try{KnownHostiles.validate(full);throw new AssertionError("Oversized list accepted");}catch(IllegalArgumentException expected){}
        var corrupt=file.resolveSibling("broken.json");Files.writeString(corrupt,"broken");try{new KnownHostiles(corrupt);throw new AssertionError("Corrupt list accepted");}catch(IllegalStateException expected){}assert Files.readString(corrupt).equals("broken");
        assert list.revision()>revision;
        assert !list.safeReconnect()&&list.reconnectSeconds()==300;
        list.policy(true,true,300);assert new KnownHostiles(file).safeReconnect();client.accept(list.snapshot());assert client.safeReconnect();
        list.policy(false);assert list.safeReconnect() : "Guard-only changes preserve reconnect preferences";
        try{list.policy(true,true,0);throw new AssertionError("Unsafe wait accepted");}catch(IllegalArgumentException expected){}
        invalid=list.snapshot();invalid.addProperty("safeReconnect","true");try{KnownHostiles.validate(invalid);throw new AssertionError("String reconnect accepted");}catch(IllegalArgumentException expected){}
        invalid=list.snapshot();invalid.remove("safeReconnect");invalid.remove("reconnectSeconds");client.accept(invalid);assert !client.safeReconnect()&&client.reconnectSeconds()==300 : "Older catalogs do not opt into retries";
        System.out.println("Known-hostile checks passed: server/UUID scope, manual names, trusted-worker exclusion, durable forgiveness, replay, restart, snapshot ownership and bounded validation.");
    }
}
