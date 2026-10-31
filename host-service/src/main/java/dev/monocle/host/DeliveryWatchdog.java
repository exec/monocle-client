package dev.monocle.host;

import com.google.gson.JsonObject;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

/** Player deliveries are short-lived; never restart their workflow to recover a lost connection. */
final class DeliveryWatchdog {
    record Decision(String command,String reason) { }
    private static final Decision NONE=new Decision("","");
    private static final long OFFLINE_MS=120_000, IDLE_MS=300_000, ACTION_MS=900_000;
    private final Map<String,Watch> watches=new HashMap<>();
    private static final class Watch {
        String token="";
        long started,progressAt,unavailableAt=-1,age=-1;
        boolean retryPending;
        Watch(long now){started=progressAt=now;}
    }
    static boolean applies(JsonObject task){
        return task.has("package")&&text(task.getAsJsonObject("package"),"entry").equals("task-kit-delivery")
            &&task.has("args")&&text(task.getAsJsonObject("args"),"destination").equals("Player");
    }
    Decision check(JsonObject task,JsonObject run,boolean ready,long now){
        String id=text(run,"id"),status=text(run,"status");
        if(!applies(task)||flag(task,"cancelled")||status.equals("Complete")||status.equals("Cancelled")
            ||flag(run,"operatorDetached")||flag(task,"paused")&&text(task,"status").equals("Paused")){
            watches.remove(id);return NONE;
        }
        if(status.equals("Failed"))return cancel(id,"Delivery failed; cancelling remaining execution and releasing its queue slot");
        if(status.equals("Inspection required")||text(task,"status").equals("Inspection required"))
            return cancel(id,"Delivery checkpoint requires inspection; cancelled without replaying transfers");
        if(flag(run,"stallPaused"))return cancel(id,"Delivery exhausted its navigation retries");
        Watch watch=watches.computeIfAbsent(id,ignored->new Watch(now));
        if(!ready){
            if(watch.unavailableAt<0)watch.unavailableAt=now;
            return now-watch.unavailableAt>=OFFLINE_MS?cancel(id,"Courier unavailable for two minutes; delivery cancelled"):NONE;
        }
        watch.unavailableAt=-1;
        if(status.equals("Suspended")&&!watch.retryPending){
            if(run.has("deliveryResumeAttempts")&&run.get("deliveryResumeAttempts").getAsInt()>=1)
                return cancel(id,"Delivery was interrupted again after its checkpoint retry");
            run.addProperty("deliveryResumeAttempts",1);
            watch.retryPending=true;
            watch.started=watch.progressAt=now;
            return new Decision("resume","Retrying the saved delivery checkpoint once after interruption");
        }
        if(status.equals("Running"))watch.retryPending=false;
        if(Set.of("Queued","Ready").contains(status)&&!flag(run,"resumeSent"))return NONE;
        String token=text(run,"token");
        JsonObject progress=run.has("actionProgress")?run.getAsJsonObject("actionProgress"):null;
        long age=progress==null?-1:progress.get("progressAgeMs").getAsLong();
        if(!watch.token.equals(token)){
            watch.token=token;watch.started=watch.progressAt=now;watch.age=-1;
        }
        if(age>=0&&watch.age>age)watch.progressAt=now;
        watch.age=age;
        JsonObject action=run.has("action")?run.getAsJsonObject("action"):new JsonObject();
        long allowance=0;
        if(Set.of("StashVisit","StashResupply","StashDeposit","StashBuffer").contains(text(action,"type"))){
            for(String key:List.of("homeCooldownTicks","homeWarmupTicks"))if(action.has(key))allowance+=action.get(key).getAsLong()*50;
        }
        if(now-watch.progressAt>=IDLE_MS+allowance)return cancel(id,"Delivery made no verified progress before its action deadline");
        if(now-watch.started>=ACTION_MS+allowance)return cancel(id,"Delivery action exceeded its total time limit");
        return NONE;
    }
    private Decision cancel(String id,String reason){watches.remove(id);return new Decision("cancel",reason);}
}
