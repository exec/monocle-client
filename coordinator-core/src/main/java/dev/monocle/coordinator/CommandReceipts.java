package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

/** Task-journal command evidence shared by both hosts; never authorizes execution. */
public final class CommandReceipts {
    private CommandReceipts() {}
    private static final Set<String> TERMINAL=Set.of("completed","rejected","superseded");
    public static JsonObject request(JsonObject task,String id,String kind,UUID worker) {
        UUID.fromString(id);
        if(!Set.of("start","pause","resume","cancel","detach","rejoin","configure").contains(kind))throw new IllegalArgumentException("Unsupported command receipt");
        JsonObject receipts=task.has("controlReceipts")?task.getAsJsonObject("controlReceipts"):new JsonObject();
        if(receipts.has(id))return receipts.getAsJsonObject(id);
        JsonObject receipt=new JsonObject(),targets=new JsonObject();
        receipt.addProperty("id",id);receipt.addProperty("command",kind);receipt.addProperty("acceptedAt",System.currentTimeMillis());receipt.add("workers",targets);
        for(var entry:task.getAsJsonObject("runs").entrySet()) {
            if(worker!=null&&!entry.getKey().equals(worker.toString()))continue;
            JsonObject run=entry.getValue().getAsJsonObject();
            if(kind.equals("configure")&&(!run.has("configuration")||QueuePolicy.terminal(text(run,"status"))))continue;
            for(JsonElement value:receipts.asMap().values()) {
                JsonObject old=value.getAsJsonObject(),oldTargets=old.getAsJsonObject("workers");
                if(!TERMINAL.contains(text(old,"state"))&&oldTargets.has(entry.getKey())
                    && (kind.equals("configure")==text(old,"command").equals("configure"))) {
                    JsonObject prior=oldTargets.getAsJsonObject(entry.getKey());
                    if(!TERMINAL.contains(text(prior,"state")))stage(prior,"superseded","Replaced by a newer operator request");
                    summarize(old);
                }
            }
            JsonObject target=new JsonObject();target.addProperty("run",text(run,"id"));target.addProperty("state","host_accepted");targets.add(entry.getKey(),target);
            if(kind.equals("configure"))target.add("revision",run.getAsJsonObject("configuration").get("revision").deepCopy());
            else {
                JsonObject control=new JsonObject();control.addProperty("id",id);control.addProperty("command",switch(kind){case "pause","detach"->"pause";case "cancel"->"cancel";default->"resume";});
                run.add("operatorControl",control);
            }
            if(QueuePolicy.terminal(text(run,"status")))stage(target,kind.equals("configure")?"rejected":"completed","Execution was already terminal; no further work can run");
            else if(Set.of("pause","detach").contains(kind)&&Set.of("Queued","Suspended").contains(text(run,"status")))stage(target,"completed","Host queue is held; execution was already queued or suspended");
        }
        if(targets.isEmpty())throw new IllegalArgumentException("No worker execution for command receipt");
        summarize(receipt);receipts.add(id,receipt);task.add("controlReceipts",receipts);
        return receipt;
    }
    /** Compact only after adapters have durably saved outcomes in their journals. */
    public static void prune(JsonObject task) {
        if(!task.has("controlReceipts"))return;
        JsonObject receipts=task.getAsJsonObject("controlReceipts");
        String latest=receipts.keySet().stream().reduce((a,b)->b).orElse("");
        while(receipts.size()>32) {
            String oldest=receipts.keySet().stream().filter(key->!key.equals(latest)&&TERMINAL.contains(text(receipts.getAsJsonObject(key),"state"))).findFirst().orElse(null);
            if(oldest==null)break; // Pending commands are bounded by the two intent categories per worker.
            receipts.remove(oldest);
        }
    }
    public static JsonObject control(JsonObject run,String command) {
        JsonObject frame=TaskWire.control(UUID.fromString(text(run,"id")),command);
        if(run.has("operatorControl")) {
            JsonObject request=run.getAsJsonObject("operatorControl");
            if(text(request,"command").equals(command)||text(request,"command").equals("cancel")&&command.equals("resume")&&text(run,"requestedStatus").equals("Cancelled"))
                frame.addProperty("controlId",text(request,"id"));
        }
        return frame;
    }
    public static boolean queued(JsonObject task,JsonObject run,String command) {
        if(!task.has("controlReceipts"))return false;
        boolean changed=false;
        for(JsonElement value:task.getAsJsonObject("controlReceipts").asMap().values()) {
            JsonObject receipt=value.getAsJsonObject();if(TERMINAL.contains(text(receipt,"state")))continue;
            String kind=text(receipt,"command");
            boolean matches=kind.equals("configure")?command.equals("configure")
                : kind.equals("start")?Set.of("transfer","resume").contains(command)
                : kind.equals("cancel")?command.equals("cancel")||command.equals("resume")&&text(run,"requestedStatus").equals("Cancelled")
                : Set.of("pause","detach").contains(kind)?command.equals("pause"):Set.of("resume","transfer").contains(command);
            if(!matches)continue;
            JsonObject target=target(receipt,run);if(target!=null&&text(target,"state").equals("host_accepted")){stage(target,"delivery_queued","Frames accepted by the host send queue; delivery not yet acknowledged");changed=true;}
            summarize(receipt);
        }
        return changed;
    }
    public static boolean pendingResume(JsonObject task,JsonObject run) {
        if(!run.has("operatorControl")||!text(run.getAsJsonObject("operatorControl"),"command").equals("resume")||!task.has("controlReceipts"))return false;
        JsonObject receipt=task.getAsJsonObject("controlReceipts").getAsJsonObject(text(run.getAsJsonObject("operatorControl"),"id"));
        JsonObject target=receipt==null?null:target(receipt,run);
        return target!=null&&Set.of("host_accepted","delivery_queued").contains(text(target,"state"));
    }
    /** Only call after unsent cancellation or an authenticated missing-checkpoint reconciliation. */
    public static void noExecution(JsonObject task,JsonObject run) {
        if(!text(run,"status").equals("Cancelled")||!run.has("operatorControl"))return;
        if(text(run,"detail").startsWith("Cancelled before")) {
            JsonObject receipt=task.getAsJsonObject("controlReceipts").getAsJsonObject(text(run.getAsJsonObject("operatorControl"),"id"));
            JsonObject target=receipt==null?null:target(receipt,run);
            if(target!=null){stage(target,"completed",text(run,"detail"));summarize(receipt);}return;
        }
        JsonObject evidence=new JsonObject();evidence.addProperty("status","Cancelled");evidence.addProperty("controlId",text(run.getAsJsonObject("operatorControl"),"id"));evidence.addProperty("detail",text(run,"detail"));
        report(task,run,evidence);
    }
    public static void uncertain(JsonObject task,JsonObject run) {
        if(!task.has("controlReceipts"))return;
        for(JsonElement value:task.getAsJsonObject("controlReceipts").asMap().values()) {
            JsonObject receipt=value.getAsJsonObject(),target=target(receipt,run);
            if(target!=null&&!TERMINAL.contains(text(receipt,"state"))&&!TERMINAL.contains(text(target,"state"))){stage(target,"outcome_uncertain",text(run,"detail"));summarize(receipt);}
        }
    }
    public static void report(JsonObject task,JsonObject run,JsonObject report) {
        if(!task.has("controlReceipts"))return;
        for(JsonElement value:task.getAsJsonObject("controlReceipts").asMap().values()) {
            JsonObject receipt=value.getAsJsonObject();if(TERMINAL.contains(text(receipt,"state")))continue;
            JsonObject target=target(receipt,run);if(target==null||TERMINAL.contains(text(target,"state")))continue;
            String kind=text(receipt,"command"),state=text(report,"status");
            boolean config=kind.equals("configure"),installed=kind.equals("start")&&Set.of("Ready","Running","Complete","Failed").contains(state);
            boolean acknowledged=config?report.has("configRevision")&&report.get("configRevision").equals(target.get("revision"))
                : text(report,"controlId").equals(text(receipt,"id"))||installed;
            if(!acknowledged)continue;
            stage(target,"worker_acknowledged",text(report,"detail"));
            target.remove("nativePending");
            if(!config&&(flag(report,"inspectionRequired")||state.equals("Inspection required"))){stage(target,"outcome_uncertain",text(report,"detail"));summarize(receipt);continue;}
            if(!config&&state.equals("Running")&&report.has("worldReady")&&!flag(report,"worldReady")){summarize(receipt);continue;}
            if(!config&&Set.of("start","resume","rejoin").contains(kind)&&state.equals("Running")
                &&report.has("action")&&text(report.getAsJsonObject("action"),"type").equals("Highway")) {
                target.addProperty("nativePending",true);summarize(receipt);continue;
            }
            if(config)stage(target,text(report,"configError").isEmpty()?"completed":"rejected",text(report,"configError").isEmpty()?"Worker applied the requested configuration revision":text(report,"configError"));
            else if(state.equals("Failed"))stage(target,"rejected",text(report,"detail"));
            else if(kind.equals("cancel")?state.equals("Cancelled")
                : Set.of("pause","detach").contains(kind)?Set.of("Suspended","Complete","Cancelled").contains(state)
                : Set.of("Running","Complete").contains(state))stage(target,"completed",text(report,"detail"));
            else if(state.equals("Cancelled"))stage(target,"rejected","Execution was cancelled instead of starting");
            summarize(receipt);
        }
    }
    public static boolean confirmNative(JsonObject task,java.util.function.Predicate<UUID> working) {
        if(!task.has("controlReceipts"))return false;boolean changed=false;
        for(JsonElement value:task.getAsJsonObject("controlReceipts").asMap().values()) {
            JsonObject receipt=value.getAsJsonObject();if(TERMINAL.contains(text(receipt,"state")))continue;
            for(var entry:receipt.getAsJsonObject("workers").entrySet()) {
                JsonObject target=entry.getValue().getAsJsonObject();
                if(text(target,"state").equals("worker_acknowledged")&&flag(target,"nativePending")&&working.test(UUID.fromString(entry.getKey()))) {
                    stage(target,"completed","Worker reports building under the current native crew generation");changed=true;
                }
            }
            summarize(receipt);
        }
        return changed;
    }
    private static JsonObject target(JsonObject receipt,JsonObject run) {
        return receipt.getAsJsonObject("workers").asMap().values().stream().map(JsonElement::getAsJsonObject)
            .filter(t->text(t,"run").equals(text(run,"id"))).findFirst().orElse(null);
    }
    private static void stage(JsonObject target,String state,String detail) {
        if(TERMINAL.contains(text(target,"state")))return;
        if(state.equals("worker_acknowledged"))target.addProperty("acknowledgedAt",target.has("acknowledgedAt")?target.get("acknowledgedAt").getAsLong():System.currentTimeMillis());
        if(!text(target,"state").equals(state)){target.addProperty("state",state);target.addProperty("updatedAt",System.currentTimeMillis());}
        target.addProperty("detail",detail.substring(0,Math.min(1024,detail.length())));
    }
    private static void summarize(JsonObject receipt) {
        List<String> states=receipt.getAsJsonObject("workers").asMap().values().stream().map(JsonElement::getAsJsonObject).map(t->text(t,"state")).toList();
        String next=states.stream().allMatch(TERMINAL::contains)?states.contains("rejected")?"rejected":states.contains("superseded")?"superseded":"completed"
            : states.contains("outcome_uncertain")?"outcome_uncertain":states.contains("host_accepted")?"host_accepted":states.contains("delivery_queued")?"delivery_queued":"worker_acknowledged";
        if(!next.equals(text(receipt,"state"))){receipt.addProperty("state",next);receipt.addProperty("updatedAt",System.currentTimeMillis());}
    }
    public static String summary(JsonObject task) {
        if(!task.has("controlReceipts")||task.getAsJsonObject("controlReceipts").isEmpty())return "No correlated command receipt yet";
        JsonObject latest=task.getAsJsonObject("controlReceipts").asMap().values().stream().reduce((a,b)->b).orElseThrow().getAsJsonObject();
        return text(latest,"command")+" · "+text(latest,"state").replace('_',' ')+" · "+text(latest,"id");
    }
}
