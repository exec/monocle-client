package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.MonocleClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Names are evidence, not authority or permission to retaliate. All access is on the client thread. */
public final class WorkerCombatTracker {
    record Actor(String uuid, String name, boolean player) {
        JsonObject json() { JsonObject o=new JsonObject();o.addProperty("uuid",uuid);o.addProperty("name",name);o.addProperty("player",player);return o; }
    }
    private record Swing(Actor actor, Vec3 position, boolean holdingCrystal, long at) {}
    private record Crystal(Vec3 position, JsonArray placement, JsonArray breaking, long removedAt) {}
    private final LinkedHashMap<String,Swing> swings=new LinkedHashMap<>();
    private final LinkedHashMap<Integer,Crystal> crystals=new LinkedHashMap<>();
    private ClientLevel world;
    private long sequence;
    private JsonObject lastDamage, lastDeath, lastCrystalExplosion, lastHostileLogout;
    private boolean currentWorldDamage;

    static boolean relevant(Packet<?> p) {
        return p instanceof ClientboundDamageEventPacket || p instanceof ClientboundPlayerCombatKillPacket
            || p instanceof ClientboundAnimatePacket || p instanceof ClientboundExplodePacket
            || p instanceof ClientboundRemoveEntitiesPacket || p instanceof ClientboundAddEntityPacket add && add.getType()==EntityTypes.END_CRYSTAL;
    }
    public void receive(Packet<?> packet) {
        if(mc.level==null||mc.player==null)return;
        if(dev.monocle.client.systems.modules.Modules.get().get(dev.monocle.client.systems.modules.movement.LobbySkip.class).isInLobby()){resetTracking();return;}
        if(world!=mc.level){resetTracking();world=mc.level;}
        long now=System.currentTimeMillis();
        prune(now);
        if(packet instanceof ClientboundAnimatePacket p && (p.getAction()==ClientboundAnimatePacket.SWING_MAIN_HAND || p.getAction()==ClientboundAnimatePacket.SWING_OFF_HAND)) {
            Entity entity=mc.level.getEntity(p.getId());
            if(entity instanceof Player player && player.distanceToSqr(mc.player)<=32*32)
                swing(actor(player),player.position(),player.getMainHandItem().is(Items.END_CRYSTAL)||player.getOffhandItem().is(Items.END_CRYSTAL),now);
        } else if(packet instanceof ClientboundAddEntityPacket p) {
            Vec3 pos=new Vec3(p.getX(),p.getY(),p.getZ());
            if(pos.distanceToSqr(mc.player.position())<=32*32)spawn(p.getId(),pos,now);
        } else if(packet instanceof ClientboundRemoveEntitiesPacket p) {
            for(int id:p.getEntityIds())remove(id,now);
        } else if(packet instanceof ClientboundExplodePacket p) {
            // Removal alone is not proof of an explosion; it can also mean unloading.
            JsonObject o=explosion(p.center(),now);
            if(o!=null){o.addProperty("scope",scope());append(o);lastCrystalExplosion=o.deepCopy();}
        } else if(packet instanceof ClientboundDamageEventPacket p && p.entityId()==mc.player.getId()) {
            var source=p.getSource(mc.level);
            Crystal crystal=crystals.get(p.sourceDirectId());
            boolean explosion=source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION);
            JsonObject o=damage(now,source.getMsgId(),actor(source.getEntity()),actor(source.getDirectEntity()),p.sourceCauseId(),p.sourceDirectId(),explosion?crystal:null);
            o.addProperty("scope",scope());position(o,mc.player.position());
            o.addProperty("healthAtObservation",mc.player.getHealth());o.addProperty("absorptionAtObservation",mc.player.getAbsorptionAmount());
            Entity attacker=source.getEntity() instanceof Player?source.getEntity():source.getDirectEntity();
            if(attacker instanceof Player player)o.addProperty("attackerTrusted",dev.monocle.client.systems.friends.Friends.get().isFriend(player));
            append(o);lastDamage=o.deepCopy();currentWorldDamage=true;
        } else if(packet instanceof ClientboundPlayerCombatKillPacket p && p.playerId()==mc.player.getId()) {
            JsonObject o=death(now,p.message(),currentWorldDamage?lastDamage:null);o.addProperty("scope",scope());position(o,mc.player.position());
            append(o);lastDeath=o.deepCopy();
        }
    }
    private static Actor actor(Entity entity) {
        return entity==null?null:new Actor(entity.getUUID().toString(),bounded(entity.getName().getString(),64),entity instanceof Player);
    }
    private static String scope(){return (mc.getCurrentServer()==null?"local":dev.monocle.client.utils.Utils.getServerIdentity())+"\n"+mc.level.dimension().identifier();}
    void swing(Actor actor,Vec3 position,boolean holdingCrystal,long now) {
        swings.remove(actor.uuid);swings.put(actor.uuid,new Swing(actor,position,holdingCrystal,now));
        while(swings.size()>128)swings.remove(swings.keySet().iterator().next());
    }
    void spawn(int id,Vec3 position,long now) {
        crystals.remove(id);crystals.put(id,new Crystal(position,candidates(position,now,true),new JsonArray(),0));
        while(crystals.size()>128)crystals.remove(crystals.keySet().iterator().next());
    }
    void remove(int id,long now) {
        Crystal c=crystals.get(id);if(c!=null)crystals.put(id,new Crystal(c.position,c.placement,candidates(c.position,now,false),now));
    }
    JsonObject explosion(Vec3 position,long now) {
        var matches=crystals.entrySet().stream().filter(e->e.getValue().position.distanceToSqr(position)<=.01
            &&(e.getValue().removedAt==0||fresh(now,e.getValue().removedAt,1_000))).toList();
        if(matches.isEmpty())return null;
        JsonObject o=base("crystal_explosion",now);position(o,position);
        if(matches.size()>1){o.addProperty("ambiguous",true);JsonArray ids=new JsonArray();matches.forEach(e->ids.add(e.getKey()));o.add("possibleCrystalEntityIds",ids);return o;}
        var entry=matches.getFirst();Crystal c=entry.getValue();o.addProperty("crystalEntityId",entry.getKey());
        o.add("placementSuspects",c.placement.deepCopy());o.add("detonationSuspects",c.removedAt==0?candidates(c.position,now,false):c.breaking.deepCopy());return o;
    }
    private JsonArray candidates(Vec3 pos,long now,boolean placement) {
        JsonArray result=new JsonArray();
        swings.values().stream().filter(s->fresh(now,s.at,750)&&(!placement||s.holdingCrystal)&&s.position.distanceToSqr(pos)<=36)
            .sorted(Comparator.comparingLong(Swing::at).reversed()).limit(3).forEach(s->result.add(s.actor.json()));
        return result;
    }
    private void prune(long now) {
        swings.values().removeIf(s->!fresh(now,s.at,1_000));
        crystals.values().removeIf(c->c.removedAt!=0&&!fresh(now,c.removedAt,5_000));
    }
    JsonObject damage(long now,String cause,Actor causing,Actor direct,int causeId,int directId,Crystal crystal) {
        JsonObject o=base("damage",now);o.addProperty("cause",cause);o.addProperty("causingEntityId",causeId);o.addProperty("directEntityId",directId);
        if(causing!=null)o.add("serverReportedCause",causing.json());if(direct!=null)o.add("serverReportedDirect",direct.json());
        Actor attacker=causing!=null&&causing.player?causing:direct!=null&&direct.player?direct:null;
        o.addProperty("attribution",attacker==null?"unknown":"server_reported");if(attacker!=null)o.add("attacker",attacker.json());
        if(crystal!=null){o.addProperty("crystalEntityId",directId);o.add("placementSuspects",crystal.placement.deepCopy());o.add("detonationSuspects",crystal.removedAt==0?candidates(crystal.position,now,false):crystal.breaking.deepCopy());}
        return o;
    }
    JsonObject damage(long now,String cause,Actor causing,Actor direct,int directId) { return damage(now,cause,causing,direct,-1,directId,crystals.get(directId)); }
    static JsonObject death(long now,Component message,JsonObject recentDamage) {
        JsonObject o=base("death",now);o.addProperty("deathMessage",bounded(message.getString(),512));
        if(message.getContents() instanceof TranslatableContents translation) {
            o.addProperty("translationKey",bounded(translation.getKey(),128));
            Object[] args=translation.getArgs();
            if(translation.getKey().startsWith("death.attack.")&&!translation.getKey().startsWith("death.attack.badRespawnPoint")&&args.length>=2&&args[1] instanceof Component name)
                o.addProperty("serverDeathMessageActor",bounded(name.getString(),64));
        }
        // A recent hit is not necessarily the final cause (e.g. sword hit followed by falling).
        if(recentDamage!=null&&fresh(now,recentDamage.get("at").getAsLong(),5_000))o.add("recentDamage",recentDamage.deepCopy());
        return o;
    }
    private static JsonObject base(String kind,long now) { JsonObject o=new JsonObject();o.addProperty("kind",kind);o.addProperty("at",now);return o; }
    private static void position(JsonObject o,Vec3 pos) { o.addProperty("x",pos.x);o.addProperty("y",pos.y);o.addProperty("z",pos.z); }
    private static String bounded(String value,int length) { return value.length()<=length?value:value.substring(0,length); }
    static boolean fresh(long now,long then,long maxAge) { return now>=then&&now-then<=maxAge; }
    void append(JsonObject o) {
        o.addProperty("sequence",++sequence);
        MonocleClient.LOG.warn("[Workers combat] {}",o);
    }
    long sequence(){return sequence;}
    public void hostileLogout(String name,String server){JsonObject o=base("hostile_logout",System.currentTimeMillis());o.addProperty("name",bounded(name,16));o.addProperty("server",bounded(server,255));position(o,mc.player.position());append(o);lastHostileLogout=o.deepCopy();}
    public JsonObject snapshot() {
        JsonObject o=new JsonObject();o.addProperty("version",1);o.addProperty("sequence",sequence);
        if(lastDamage!=null)o.add("lastDamage",lastDamage.deepCopy());if(lastDeath!=null)o.add("lastDeath",lastDeath.deepCopy());
        if(lastCrystalExplosion!=null)o.add("lastCrystalExplosion",lastCrystalExplosion.deepCopy());
        if(lastHostileLogout!=null)o.add("lastHostileLogout",lastHostileLogout.deepCopy());
        if(oversized(o))o.remove("lastDamage");
        if(oversized(o)&&o.has("lastDeath"))o.getAsJsonObject("lastDeath").remove("recentDamage");
        if(oversized(o)&&o.has("lastDeath"))o.getAsJsonObject("lastDeath").remove("scope");
        if(oversized(o))o.remove("lastCrystalExplosion");
        return o;
    }
    private static boolean oversized(JsonObject o){return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096;}
    void resetTracking() { swings.clear();crystals.clear();lastCrystalExplosion=null;currentWorldDamage=false;world=null; }
}
