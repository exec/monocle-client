package dev.monocle.host;

import com.google.gson.*;

final class DeliveryWatchdogCheck {
    private static JsonObject object(String json){return JsonParser.parseString(json).getAsJsonObject();}
    static void run(){
        // Synthetic jobs only; no game locations or runtime data.
        JsonObject task=object("{status:'Running',package:{entry:'task-kit-delivery'},args:{destination:'Player'}}");
        JsonObject run=object("{id:'synthetic-run',status:'Running',token:'action-one',resumeSent:true}");
        DeliveryWatchdog watch=new DeliveryWatchdog();
        assert watch.check(task,run,true,0).command().isEmpty();
        assert watch.check(task,run,true,299999).command().isEmpty();
        assert watch.check(task,run,true,300000).command().equals("cancel") : "An action without telemetry still has a deadline";
        for(String type:new String[]{"StashResupply","StashDeposit","DropItems","Tpa"}){
            watch=new DeliveryWatchdog();run.add("action",object("{type:'"+type+"'}"));
            assert watch.check(task,run,true,0).command().isEmpty();
            assert watch.check(task,run,true,300000).command().equals("cancel") : type;
        }
        watch=new DeliveryWatchdog();watch.check(task,run,false,0);
        assert watch.check(task,run,false,119999).command().isEmpty();
        assert watch.check(task,run,false,120000).command().equals("cancel");
        watch=new DeliveryWatchdog();run.addProperty("status","Suspended");
        assert watch.check(task,run,true,0).command().equals("resume");
        assert watch.check(task,run,true,100).command().isEmpty() : "Wait for the retry acknowledgement";
        run.addProperty("status","Running");watch.check(task,run,true,200);
        run.addProperty("status","Suspended");
        assert watch.check(task,run,true,300).command().equals("cancel") : "Repeated interruption cannot loop resumes";
        run.remove("deliveryResumeAttempts");
        for(String status:new String[]{"Inspection required","Failed"}){
            run.addProperty("status",status);
            assert new DeliveryWatchdog().check(task,run,true,0).command().equals("cancel");
        }
        run.addProperty("status","Running");run.addProperty("stallPaused",true);
        assert new DeliveryWatchdog().check(task,run,true,0).command().equals("cancel");run.remove("stallPaused");
        task.addProperty("paused",true);task.addProperty("status","Paused");watch=new DeliveryWatchdog();
        assert watch.check(task,run,false,0).command().isEmpty()&&watch.check(task,run,false,10000000).command().isEmpty() : "Operator pauses are respected";
        task.addProperty("paused",false);task.addProperty("status","Running");
        run.add("action",object("{type:'StashVisit',homeCooldownTicks:12000,homeWarmupTicks:320}"));watch=new DeliveryWatchdog();watch.check(task,run,true,0);
        assert watch.check(task,run,true,915999).command().isEmpty() : "Configured home cooldown and warmup are legitimate waits";
        assert watch.check(task,run,true,916000).command().equals("cancel");
        for(String type:new String[]{"StashResupply","StashDeposit","StashBuffer"}){
            run.getAsJsonObject("action").addProperty("type",type);watch=new DeliveryWatchdog();watch.check(task,run,true,0);
            assert watch.check(task,run,true,915999).command().isEmpty() : "Stash actions may also wait on home cooldown: "+type;
        }
        run.remove("action");watch=new DeliveryWatchdog();run.add("actionProgress",object("{progressAgeMs:0}"));watch.check(task,run,true,0);
        run.getAsJsonObject("actionProgress").addProperty("progressAgeMs",200000);watch.check(task,run,true,200000);
        run.getAsJsonObject("actionProgress").addProperty("progressAgeMs",0);watch.check(task,run,true,250000);
        assert watch.check(task,run,true,500000).command().isEmpty() : "Verified progress renews the idle deadline";
        assert watch.check(task,run,true,550000).command().equals("cancel");
        watch=new DeliveryWatchdog();run.remove("actionProgress");watch.check(task,run,true,0);run.addProperty("token","action-two");watch.check(task,run,true,250000);
        assert watch.check(task,run,true,500000).command().isEmpty() : "The next action receives its own budget";
        watch=new DeliveryWatchdog();run.add("actionProgress",object("{progressAgeMs:0}"));watch.check(task,run,true,0);
        for(long time=100000;time<900000;time+=100000){run.getAsJsonObject("actionProgress").addProperty("progressAgeMs",10);watch.check(task,run,true,time-1);run.getAsJsonObject("actionProgress").addProperty("progressAgeMs",0);assert watch.check(task,run,true,time).command().isEmpty();}
        assert watch.check(task,run,true,900000).command().equals("cancel") : "Progress churn cannot evade the total action limit";
        run.addProperty("status","Queued");run.remove("resumeSent");watch=new DeliveryWatchdog();watch.check(task,run,true,0);
        assert watch.check(task,run,true,900000).command().isEmpty() : "Waiting behind another job is not an active delivery hang";
        task.getAsJsonObject("args").addProperty("destination","Stash");run.addProperty("status","Inspection required");
        assert new DeliveryWatchdog().check(task,run,true,0).command().isEmpty() : "Long-running transfers keep their recovery policy";
        System.out.println("Delivery watchdog checks passed: bounded reconnect, action deadlines, cooldowns, operator pause and retained cleanup.");
    }
}
