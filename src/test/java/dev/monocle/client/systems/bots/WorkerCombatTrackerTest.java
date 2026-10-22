package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class WorkerCombatTrackerTest {
    static void run() throws Exception {
        try(var bytes=WorkerCombatTracker.class.getResourceAsStream("WorkerCombatTracker.class")){
            var compiled=java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var receive=compiled.methods().stream().filter(m->m.methodName().equalsString("receive")).findFirst().orElseThrow();
            var instructions=receive.code().orElseThrow().elementList();int lobby=-1,capture=-1;
            for(int i=0;i<instructions.size();i++)if(instructions.get(i) instanceof java.lang.classfile.instruction.InvokeInstruction call){if(call.name().equalsString("isInLobby"))lobby=i;if(capture<0&&call.name().equalsString("append"))capture=i;}
            assert lobby>=0&&capture>lobby&&instructions.subList(lobby,capture).stream().anyMatch(java.lang.classfile.instruction.ReturnInstruction.class::isInstance):"Lobby packets must return before any combat event is captured/logged";
        }
        var tracker=new WorkerCombatTracker();
        var attacker=new WorkerCombatTracker.Actor(UUID.randomUUID().toString(),"Attacker",true);
        var innocent=new WorkerCombatTracker.Actor(UUID.randomUUID().toString(),"Bystander",true);
        var crystal=new WorkerCombatTracker.Actor(UUID.randomUUID().toString(),"End Crystal",false);
        JsonObject hit=tracker.damage(1_000,"player",attacker,attacker,12);
        assert hit.get("attribution").getAsString().equals("server_reported");
        assert hit.getAsJsonObject("attacker").get("uuid").getAsString().equals(attacker.uuid());
        JsonObject unknown=tracker.damage(1_100,"explosion",crystal,crystal,50);
        assert !unknown.has("attacker")&&unknown.get("attribution").getAsString().equals("unknown");
        tracker.swing(attacker,new Vec3(1,0,0),true,1_000);
        tracker.swing(innocent,new Vec3(2,0,0),false,1_000);
        tracker.spawn(50,Vec3.ZERO,1_100);
        tracker.swing(innocent,new Vec3(2,0,0),false,1_150);
        tracker.remove(50,1_200);
        JsonObject inferred=tracker.damage(1_250,"explosion",crystal,crystal,50);
        assert !inferred.has("attacker") : "Nearness or a swing must never manufacture a confirmed attacker";
        assert inferred.getAsJsonArray("placementSuspects").size()==1;
        assert inferred.getAsJsonArray("placementSuspects").get(0).getAsJsonObject().get("name").getAsString().equals("Attacker");
        assert inferred.getAsJsonArray("detonationSuspects").size()==2 : "Placement and detonation can be by different people";
        var explosion=tracker.explosion(Vec3.ZERO,1_250);
        assert explosion.get("crystalEntityId").getAsInt()==50;
        assert !explosion.has("attacker");
        tracker.spawn(52,Vec3.ZERO,1_250);
        var ambiguous=tracker.explosion(Vec3.ZERO,1_300);
        assert ambiguous.get("ambiguous").getAsBoolean()&&!ambiguous.has("placementSuspects") : "Overlapping crystals must not arbitrarily assign evidence";
        assert tracker.explosion(new Vec3(0,10,0),1_300)==null : "Unrelated explosions must not be called crystals";
        assert tracker.explosion(Vec3.ZERO,5_000).get("crystalEntityId").getAsInt()==52 : "Old removals must not match a later explosion";
        tracker.spawn(51,Vec3.ZERO,3_000);
        assert tracker.damage(3_100,"explosion",null,null,51).getAsJsonArray("placementSuspects").isEmpty() : "Stale swings are not evidence";
        tracker.resetTracking();
        assert !tracker.damage(3_200,"explosion",null,null,50).has("placementSuspects") : "Entity IDs must not leak across worlds";
        var death=WorkerCombatTracker.death(1_200,Component.translatable("death.attack.player",Component.literal("Worker"),Component.literal("Attacker")),hit);
        assert death.get("serverDeathMessageActor").getAsString().equals("Attacker");
        assert death.has("recentDamage");
        assert !WorkerCombatTracker.death(7_000,Component.literal("Worker died"),hit).has("recentDamage");
        assert !WorkerCombatTracker.death(1_200,Component.literal("Worker was slain by Attacker"),hit).has("serverDeathMessageActor") : "Custom plaintext is retained, not guessed by regex";
        assert !WorkerCombatTracker.death(1_200,Component.translatable("death.attack.badRespawnPoint.message",Component.literal("Worker"),Component.literal("Intentional Game Design")),hit).has("serverDeathMessageActor");
        assert !WorkerCombatTracker.fresh(999,1_000,5_000);
        var longDeath=WorkerCombatTracker.death(1_000,Component.literal("字".repeat(2_000)),null);
        assert longDeath.get("deathMessage").getAsString().length()==512;
        hit.addProperty("scope","字".repeat(1_024));
        longDeath.addProperty("scope","字".repeat(1_024));longDeath.add("recentDamage",hit.deepCopy());
        var damageField=WorkerCombatTracker.class.getDeclaredField("lastDamage");damageField.setAccessible(true);damageField.set(tracker,hit.deepCopy());
        var deathField=WorkerCombatTracker.class.getDeclaredField("lastDeath");deathField.setAccessible(true);deathField.set(tracker,longDeath.deepCopy());
        var logout=new JsonObject();logout.addProperty("kind","hostile_logout");logout.addProperty("name","Enemy");logout.addProperty("server","字".repeat(255));
        var logoutField=WorkerCombatTracker.class.getDeclaredField("lastHostileLogout");logoutField.setAccessible(true);logoutField.set(tracker,logout);
        assert tracker.snapshot().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096;
        var snapshot=tracker.snapshot();assert snapshot.has("lastDeath");
        snapshot.getAsJsonObject("lastDeath").addProperty("deathMessage","mutated");
        assert tracker.snapshot().getAsJsonObject("lastDeath").get("deathMessage").getAsString().length()==512 : "API snapshots must not mutate evidence";
        tracker.resetTracking();
        assert tracker.snapshot().has("lastDeath") : "Keep the latest death after disconnect for inspection";
        assert tracker.snapshot().getAsJsonObject("lastHostileLogout").get("name").getAsString().equals("Enemy") : "Keep the logout reason after world changes";
    }
}
