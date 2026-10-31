package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.Predicate;

/** Bounded local handoff; no terrain edits or recipient chasing. */
final class BotDelivery {
    static JsonObject anchor(Vec3 position,float yaw,String dimension){
        JsonObject a=new JsonObject();a.addProperty("x",position.x);a.addProperty("y",position.y);a.addProperty("z",position.z);
        a.addProperty("yaw",net.minecraft.util.Mth.wrapDegrees(yaw));a.addProperty("dimension",dimension);return checked(a);
    }
    static JsonObject checked(JsonObject a){
        if(!a.keySet().equals(java.util.Set.of("x","y","z","yaw","dimension")))throw new IllegalArgumentException("Invalid delivery anchor fields");
        for(String key:List.of("x","y","z","yaw")){
            if(!a.get(key).isJsonPrimitive()||!a.getAsJsonPrimitive(key).isNumber()||!Double.isFinite(a.get(key).getAsDouble()))throw new IllegalArgumentException("Invalid delivery anchor coordinate");
        }
        if(Math.abs(a.get("x").getAsDouble())>29_999_984||Math.abs(a.get("z").getAsDouble())>29_999_984||Math.abs(a.get("y").getAsDouble())>2048
            ||Math.abs(a.get("yaw").getAsDouble())>360_000||!a.get("dimension").isJsonPrimitive()||!a.getAsJsonPrimitive("dimension").isString()
            ||!a.get("dimension").getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||a.get("dimension").getAsString().length()>128)throw new IllegalArgumentException("Invalid delivery anchor");
        return a.deepCopy();
    }
    static Vec3 position(JsonObject a){return new Vec3(a.get("x").getAsDouble(),a.get("y").getAsDouble(),a.get("z").getAsDouble());}
    static Vec3 front(Vec3 anchor,double yaw,double turn){
        double angle=Math.toRadians(yaw+turn);return anchor.add(-Math.sin(angle)*3,0,Math.cos(angle)*3);
    }
    static Vec3 goal(Vec3 from,JsonObject anchor,Predicate<Vec3> safe){
        Vec3 target=position(anchor);
        if(from.distanceToSqr(target)>64)return null;
        for(double distance:new double[]{3,2,1.25})for(double turn:new double[]{0,45,-45,90,-90,135,-135,180}){
            Vec3 goal=target.add(front(Vec3.ZERO,anchor.get("yaw").getAsDouble(),turn).scale(distance/3));
            goal=new Vec3(goal.x,from.y,goal.z);
            if(Math.abs(target.y-from.y)>.6||!corridor(from,goal,safe))continue;
            return goal;
        }
        return null;
    }
    static boolean holdPosition(Vec3 from,Vec3 original,Vec3 current,boolean walking){
        return current.distanceToSqr(original)>.75*.75||!walking&&from.multiply(1,0,1).distanceToSqr(current.multiply(1,0,1))>=1.25*1.25;
    }
    static boolean corridor(Vec3 from,Vec3 goal,Predicate<Vec3> safe){
        if(from.distanceToSqr(goal)>64)return false;
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(goal)/.15));
        for(int i=0;i<=steps;i++)if(!safe.test(from.lerp(goal,(double)i/steps)))return false;
        return true;
    }
    static float pitch(double eyeY,double groundY,double distance){
        float best=45;double error=Double.MAX_VALUE;
        for(int pitch=-20;pitch<=80;pitch++){
            double landing=landingDistance(eyeY-groundY,pitch),difference=Math.abs(landing-distance);
            if(difference<error){best=pitch;error=difference;}
        }
        return best;
    }
    static double landingDistance(double eyeHeight,float pitch){
        // Vanilla 26.2 item throw: eyeY-.3, speed .3, upward bias .1, gravity .04, drag .98; random scatter omitted.
        double y=eyeHeight-.3,x=0,vx=.3*Math.cos(Math.toRadians(pitch)),vy=.1-.3*Math.sin(Math.toRadians(pitch));
        for(int tick=0;tick<80&&y>0;tick++){
            double previous=y;vy-=.04;y+=vy;
            if(y<=0)return x+vx*previous/(previous-y);
            x+=vx;vx*=.98;vy*=.98;
        }
        return x;
    }
}
