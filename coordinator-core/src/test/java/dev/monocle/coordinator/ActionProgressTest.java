package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;

final class ActionProgressTest {
    static void run() {
        ActionProgress monitor = new ActionProgress();
        var stuck = sample("Approaching", "0", "chest", 20, 0, false, true);
        monitor.observe(stuck, 1);
        for (int i = 1; i < 120; i++) assert monitor.observe(stuck, 1+i*1000L) == ActionProgress.Decision.Continue;
        assert monitor.observe(stuck, 120001) == ActionProgress.Decision.Replan;
        assert monitor.report().get("recoveryAttempts").getAsInt() == 1;
        long age = monitor.report().get("progressAgeMs").getAsLong();
        // A long cooldown is not progress, nor does it consume the active stall/retry budget.
        for (int i = 121; i <= 900; i++) monitor.observe(sample("Cooldown", "irrelevant", "", 0, 0, true, false), 1+i*1000L);
        assert monitor.report().get("progressAgeMs").getAsLong() == age;
        assert monitor.report().get("recoveryAttempts").getAsInt() == 1;
        JsonObject saved = monitor.snapshot(); monitor = new ActionProgress(); monitor.restore(saved);
        monitor.observe(stuck, 9_000_000); // Offline elapsed time is not a stall.
        for (int i = 1; i < 120; i++) assert monitor.observe(stuck, 9_000_000+i*1000L) == ActionProgress.Decision.Continue;
        assert monitor.observe(stuck, 9_120_000) == ActionProgress.Decision.Replan;
        for (int i = 1; i < 120; i++) monitor.observe(stuck, 9_120_000+i*1000L);
        assert monitor.observe(stuck, 9_240_000) == ActionProgress.Decision.Pause;
        assert monitor.report().get("recoveryAttempts").getAsInt() == 2;
        String incident = monitor.report().get("incidentId").getAsString();
        assert monitor.observe(sample("Something else", "99", "another chest", 1, 1, false, true), 9_241_000) == ActionProgress.Decision.Pause;
        assert monitor.report().get("incidentId").getAsString().equals(incident);
        monitor.resume(); assert !monitor.paused() && !monitor.report().has("incidentId");

        monitor.observe(stuck, 1);for(int i=1;i<=120;i++)monitor.observe(stuck,1+i*1000L);
        monitor.resetClock();monitor.observe(sample("Reconnected", "0", "chest", 1000, 0, false, true), 99_000_001);
        assert monitor.report().get("recoveryAttempts").getAsInt()==1 : "Repositioning must not restore a spent recovery budget";
        for(int i=1;i<=300;i++)assert monitor.observe(sample("Moving back", "0", "chest", 1000-i, 0, false, true),99_000_001+i*1000L)==ActionProgress.Decision.Continue;

        // Healthy small incremental movement/mining resets age cumulatively, not only in >.25 jumps.
        monitor = new ActionProgress();
        for (int i = 0; i < 600; i++) assert monitor.observe(sample("Moving", "0", "goal", 100-i*.02, 0, false, true), 1+i*1000L) == ActionProgress.Decision.Continue;
        monitor = new ActionProgress();
        for (int i = 0; i < 600; i++) assert monitor.observe(sample("Slow mining", "0", "obsidian", 4, i*.0001, false, true), 1+i*1000L) == ActionProgress.Decision.Continue;
        monitor = new ActionProgress();
        for (int i = 0; i < 120; i++) monitor.observe(sample("phase"+i, "0", "target"+(i%2), 20, 0, false, true), 1+i*1000L);
        assert monitor.observe(stuck,120001) == ActionProgress.Decision.Replan : "Target and detail churn cannot hide a stall";
        monitor.observe(sample("Verified deposit", "1", "chest", 20, 0, false, true),121001);
        assert monitor.report().get("recoveryAttempts").getAsInt()==0 && !monitor.report().has("incidentId");

        monitor = new ActionProgress();
        var pending = sample("Deposit", "0", "chest", 2, 0, false, false);
        for (int i = 0; i < 120; i++) monitor.observe(pending,1+i*1000L);
        assert monitor.observe(pending,120001) == ActionProgress.Decision.Pause : "Uncertain clicks are never retried by the watchdog";
        JsonObject paused = monitor.report();
        ActionProgress.checked(paused);
        for (String bad : List.of("{phase:1}", "{state:'Paused'}")) rejects(() -> ActionProgress.checked(JsonParser.parseString(bad).getAsJsonObject()));
        JsonObject invalid = paused.deepCopy(); invalid.addProperty("progressAgeMs",-1); JsonObject negative=invalid; rejects(() -> ActionProgress.checked(negative));
        invalid = paused.deepCopy(); invalid.addProperty("recoveryAttempts",3); JsonObject tooMany=invalid; rejects(() -> ActionProgress.checked(tooMany));
        invalid = paused.deepCopy(); invalid.addProperty("incidentId","not-an-id"); JsonObject badId=invalid; rejects(() -> ActionProgress.checked(badId));
        invalid = paused.deepCopy(); invalid.addProperty("extra",true); JsonObject unknown=invalid; rejects(() -> ActionProgress.checked(unknown));
        invalid = paused.deepCopy(); invalid.addProperty("closestDistance",Double.NaN); JsonObject badDistance=invalid; rejects(() -> ActionProgress.checked(badDistance));
        invalid = paused.deepCopy(); invalid.addProperty("progressAgeMs",.5); JsonObject fractional=invalid; rejects(() -> ActionProgress.checked(fractional));
        invalid = paused.deepCopy(); invalid.addProperty("progressAgeMs",new java.math.BigInteger("999999999999999999999999"));JsonObject overflow=invalid; rejects(() -> ActionProgress.checked(overflow));

        UUID worker = UUID.randomUUID(), other = UUID.randomUUID();
        JsonObject task = JsonParser.parseString("{status:'Running',priority:0,overrides:{},runs:{}}").getAsJsonObject();
        JsonObject run = JsonParser.parseString("{status:'Running'}").getAsJsonObject(); run.addProperty("id",UUID.randomUUID().toString());
        task.getAsJsonObject("runs").add(worker.toString(),run); task.getAsJsonObject("runs").add(other.toString(),JsonParser.parseString("{status:'Running'}"));
        JsonObject report = new JsonObject(); report.add("run",run.get("id")); report.addProperty("status","Suspending"); report.addProperty("requestedStatus","Suspended");
        report.addProperty("token",UUID.randomUUID().toString()); report.add("action",JsonParser.parseString("{type:'StashDeposit'}")); report.add("actionProgress",paused);
        TaskWire.applyStatus(task,run,report,true);
        assert QueuePolicy.detached(task,worker) && QueuePolicy.choose(List.of(task),worker)==null;
        assert QueuePolicy.choose(List.of(task),other)==task : "Only the stalled worker is held";
        assert QueuePolicy.dispatch(task,null,worker).command().isEmpty() : "Cleanup cannot be automatically resumed";
        report.addProperty("status","Suspended"); TaskWire.applyStatus(task,run,report,true);
        JsonObject oldReport=report.deepCopy();oldReport.getAsJsonObject("actionProgress").addProperty("state","Working");oldReport.getAsJsonObject("actionProgress").remove("incidentId");TaskWire.applyStatus(task,run,oldReport,true);
        QueuePolicy.resume(task); assert !QueuePolicy.detached(task,worker);
        TaskWire.applyStatus(task,run,report,true);
        assert !QueuePolicy.detached(task,worker) : "The old pause report cannot relatch a dismissed stall, even after pre-stall telemetry replaced its display";
        assert QueuePolicy.dispatch(null,task,worker).command().equals("resume");
        monitor.resume(); for(int i=0;i<=120;i++)monitor.observe(pending,20_000_001+i*1000L);
        report.add("actionProgress",monitor.report()); TaskWire.applyStatus(task,run,report,true);
        assert QueuePolicy.detached(task,worker) : "A new stall incident can pause again";
        QueuePolicy.detach(task,worker,false); assert !QueuePolicy.detached(task,worker);
        QueuePolicy.cancel(task); assert QueuePolicy.dispatch(task,null,worker).command().equals("cancel");
        JsonObject broken=report.deepCopy();broken.getAsJsonObject("actionProgress").addProperty("recoveryAttempts",99);JsonObject before=run.deepCopy();
        rejects(() -> TaskWire.applyStatus(task,run,broken,true)); assert before.equals(run) : "Malformed telemetry cannot mutate host intent";
    }
    private static ActionProgress.Observation sample(String phase,String milestone,String target,double distance,double effort,boolean waiting,boolean navigation) {
        return new ActionProgress.Observation(phase,"Expected event",milestone,target,distance,effort,waiting,navigation);
    }
    private static void rejects(Runnable check) { try { check.run(); throw new AssertionError("Accepted invalid progress"); } catch (IllegalArgumentException expected) { } }
}
