package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.UUID;
import static dev.monocle.coordinator.TaskWire.*;

final class CommandReceiptsTest {
    private static JsonObject task(UUID... workers) {
        JsonObject task=new JsonObject(),runs=new JsonObject();task.add("runs",runs);task.addProperty("status","Running");
        for(UUID worker:workers){JsonObject run=new JsonObject();run.addProperty("id",UUID.randomUUID().toString());run.addProperty("status","Running");runs.add(worker.toString(),run);}return task;
    }
    private static JsonObject request(JsonObject task,String kind,UUID worker) { return CommandReceipts.request(task,UUID.randomUUID().toString(),kind,worker); }
    private static JsonObject run(JsonObject task,UUID worker) { return task.getAsJsonObject("runs").getAsJsonObject(worker.toString()); }
    private static JsonObject report(String state,JsonObject receipt) { JsonObject report=new JsonObject();report.addProperty("status",state);report.addProperty("controlId",text(receipt,"id"));report.addProperty("detail","Worker state");return report; }
    static void check() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();JsonObject task=task(a,b),ra=run(task,a),rb=run(task,b);
        JsonObject pause=request(task,"pause",null);
        assert text(pause,"state").equals("host_accepted");
        assert CommandReceipts.queued(task,ra,"pause");assert !CommandReceipts.queued(task,ra,"pause");
        JsonObject stale=report("Suspended",pause);stale.addProperty("controlId",UUID.randomUUID().toString());CommandReceipts.report(task,ra,stale);
        assert !pause.getAsJsonObject("workers").getAsJsonObject(a.toString()).has("acknowledgedAt");
        CommandReceipts.queued(task,rb,"pause");CommandReceipts.report(task,ra,report("Suspending",pause));
        assert text(pause,"state").equals("delivery_queued");
        CommandReceipts.report(task,ra,report("Suspended",pause));assert !text(pause,"state").equals("completed");
        CommandReceipts.report(task,rb,report("Suspended",pause));assert text(pause,"state").equals("completed");
        JsonObject cancel=request(task,"cancel",null);task.addProperty("cancelled",true);
        assert !text(cancel,"state").equals("completed") : "Host cancellation is authoritative, worker cleanup is separate";
        CommandReceipts.report(task,ra,report("Inspection required",cancel));assert text(cancel,"state").equals("outcome_uncertain")&&cancel.getAsJsonObject("workers").getAsJsonObject(a.toString()).has("acknowledgedAt");
        QueuePolicy.reconcileMissingRun(task,ra);QueuePolicy.reconcileMissingRun(task,rb);assert text(cancel,"state").equals("completed");
        task=task(a);ra=run(task,a);JsonObject resume=request(task,"resume",null);assert CommandReceipts.pendingResume(task,ra);
        JsonObject highway=report("Running",resume),action=new JsonObject();action.addProperty("type","Highway");highway.add("action",action);CommandReceipts.report(task,ra,highway);
        assert text(resume,"state").equals("worker_acknowledged")&&!CommandReceipts.pendingResume(task,ra);
        assert !CommandReceipts.confirmNative(task,w->false);assert CommandReceipts.confirmNative(task,w->w.equals(a));assert text(resume,"state").equals("completed");
        JsonObject old=request(task,"pause",a),newer=request(task,"resume",a);assert text(old,"state").equals("superseded");
        CommandReceipts.report(task,ra,report("Suspended",old));assert text(newer,"state").equals("host_accepted");
        JsonObject legacy=report("Running",newer);legacy.remove("controlId");CommandReceipts.report(task,ra,legacy);assert text(newer,"state").equals("host_accepted");
        JsonObject loading=report("Running",newer);loading.addProperty("worldReady",false);CommandReceipts.report(task,ra,loading);assert text(newer,"state").equals("worker_acknowledged") : "An old Running checkpoint while loading is not successful resume";
        loading.addProperty("worldReady",true);CommandReceipts.report(task,ra,loading);assert text(newer,"state").equals("completed");
        JsonObject config=new JsonObject();config.addProperty("revision",3);ra.add("configuration",config);JsonObject configure=request(task,"configure",a);
        JsonObject ack=new JsonObject();ack.addProperty("status","Running");ack.addProperty("configRevision",2);CommandReceipts.report(task,ra,ack);assert text(configure,"state").equals("host_accepted");
        ack.addProperty("configRevision",3);ack.addProperty("configError","Unsupported setting");CommandReceipts.report(task,ra,ack);assert text(configure,"state").equals("rejected");
        task=task(a);ra=run(task,a);ra.addProperty("status","Queued");JsonObject start=request(task,"start",null);
        JsonObject installed=new JsonObject();installed.addProperty("status","Ready");CommandReceipts.report(task,ra,installed);assert text(start,"state").equals("worker_acknowledged");
        installed.addProperty("status","Running");CommandReceipts.report(task,ra,installed);assert text(start,"state").equals("completed");
        JsonObject unsent=request(task,"cancel",null);ra.addProperty("status","Cancelled");ra.addProperty("detail","Cancelled before dispatch");CommandReceipts.noExecution(task,ra);
        assert text(unsent,"state").equals("completed")&&!unsent.getAsJsonObject("workers").getAsJsonObject(a.toString()).has("acknowledgedAt");
        task=task(a);ra=run(task,a);cancel=request(task,"cancel",null);JsonObject uncertain=report("Cancelled",cancel);uncertain.addProperty("inspectionRequired",true);CommandReceipts.report(task,ra,uncertain);
        assert text(cancel,"state").equals("outcome_uncertain");
        for(int i=0;i<40;i++){request(task,"pause",a);CommandReceipts.prune(task);}assert task.getAsJsonObject("controlReceipts").size()<=32;
    }
}
