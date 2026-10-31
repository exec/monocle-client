package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import dev.monocle.coordinator.StashCatalog;

/** Checkpoint /kill intent before sending; a restored intent never immediately sends again. */
final class BotRespawnReturn {
    static final long COOLDOWN_MS=61_000;
    enum Next { Checkpoint, Kill, Wait, Respawn, Arrived, WrongSpawn, Exhausted }
    private long issuedAt,verifyAt;
    private int attempts;
    private boolean armed,respawnSeen;
    long readyAt(long sharedReadyAt){if(respawnSeen)return verifyAt;return Math.max(sharedReadyAt,issuedAt==0?0:issuedAt+COOLDOWN_MS);}
    Next next(long now,long sharedReadyAt,boolean alive,boolean nearStash){
        if(!alive){died(now);return Next.Respawn;}
        if(respawnSeen)return now<verifyAt?Next.Wait:nearStash?Next.Arrived:Next.WrongSpawn;
        if(armed){armed=false;return now<sharedReadyAt?Next.Wait:Next.Kill;}
        if(now<readyAt(sharedReadyAt))return Next.Wait;
        if(attempts>=3)return Next.Exhausted;
        issuedAt=now;attempts++;return Next.Checkpoint;
    }
    void checkpointSaved(){if(issuedAt>0&&!respawnSeen)armed=true;}
    void respawned(long now){if(issuedAt>0)died(now);}
    void died(long now){respawnSeen=true;verifyAt=now+500;}
    boolean issued(){return issuedAt>0;}
    JsonObject snapshot(){JsonObject s=new JsonObject();s.addProperty("issuedAt",issuedAt);s.addProperty("attempts",attempts);s.addProperty("verifyAt",verifyAt);s.addProperty("respawnSeen",respawnSeen);return s;}
    void restore(JsonObject s){
        issuedAt=s.get("issuedAt").getAsBigDecimal().longValueExact();verifyAt=s.get("verifyAt").getAsBigDecimal().longValueExact();attempts=StashCatalog.integer(s,"attempts",0,3);
        if(issuedAt<0||issuedAt>System.currentTimeMillis()+COOLDOWN_MS||verifyAt<0||verifyAt>System.currentTimeMillis()+COOLDOWN_MS||issuedAt==0&&attempts!=0||issuedAt>0&&attempts==0||!s.get("respawnSeen").isJsonPrimitive()||!s.getAsJsonPrimitive("respawnSeen").isBoolean())throw new IllegalArgumentException("Invalid respawn-return checkpoint");
        respawnSeen=s.get("respawnSeen").getAsBoolean();armed=false;
    }
}
