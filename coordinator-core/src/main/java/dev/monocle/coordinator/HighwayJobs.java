package dev.monocle.coordinator;
import com.google.gson.*;
import java.util.*;
import dev.monocle.client.systems.bots.BotWorkflows;
import dev.monocle.client.systems.bots.BotHistory;
import static dev.monocle.coordinator.TaskWire.text;

/** Validated native highway definitions shared by both hosting adapters. */
public final class HighwayJobs {
    private HighwayJobs() {}
    public static boolean overlaps(JsonObject first, JsonObject second) {
        if (!text(first,"scope").equals(text(second,"scope"))) return false;
        long[] a=workArea(first),b=workArea(second);
        return a[0]<b[3] && a[3]>b[0] && a[1]<b[4] && a[4]>b[1] && a[2]<b[5] && a[5]>b[2];
    }
    private static long[] workArea(JsonObject job) {
        var layout=job.getAsJsonObject("layout");long x=integer(job,"x"),y=integer(job,"y"),z=integer(job,"z");
        long ex=x+(long)integer(layout,"dx")*integer(job,"length"),ez=z+(long)integer(layout,"dz")*integer(job,"length"),radius=integer(layout,"width")/2+8;
        return new long[]{Math.min(x,ex)-radius,y-10,Math.min(z,ez)-radius,Math.max(x,ex)+radius+1,y+integer(layout,"height")+10,Math.max(z,ez)+radius+1};
    }
    public static JsonObject definition(JsonObject task, JsonObject action) {
        if (task.has("nativeDefinition")) return checked(task.getAsJsonObject("nativeDefinition"));
        JsonObject packaged = task.getAsJsonObject("package");
        JsonObject definition = packaged.getAsJsonObject("geometry").deepCopy();
        definition.addProperty("id", UUID.randomUUID().toString()); definition.addProperty("name", text(task,"name"));
        definition.addProperty("length", action.has("length") ? integer(action,"length") : 128); definition.addProperty("progress",0);
        for (String axis : List.of("x","y","z")) if (action.has(axis)) definition.add(axis,action.get(axis));
        JsonObject plan = BotWorkflows.checkedPlan(packaged.getAsJsonObject("highways").getAsJsonObject(text(action,"workflow")));
        definition.add("workflow",plan); definition.getAsJsonObject("layout").addProperty("operation",BotWorkflows.operation(plan));
        if (action.has("layout")) definition.add("layout",action.getAsJsonObject("layout").deepCopy());
        definition.addProperty("crew",text(task,"crew")); definition.addProperty("status","Assigning");
        return checked(definition);
    }
    private static int integer(JsonObject o,String key) {
        try { return ResourceLedger.integer(o,key); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Invalid integer " + key,e); }
    }
    private static String name(String name) { name=name.strip(); if(name.isEmpty() || name.length()>48 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Choose a name of 1–48 printable characters."); return name; }
    public static JsonObject checked(JsonObject input) {
        JsonObject job = input.deepCopy();
        UUID.fromString(text(job, "id"));
        job.addProperty("name", name(text(job, "name")));
        String scope = text(job, "scope");
        if (scope.isBlank() || scope.length() > 1024) throw new IllegalArgumentException("A job needs its server and dimension");
        int length = integer(job, "length"), progress = job.has("progress") ? integer(job, "progress") : 0;
        if (length < 16 || length > 4096 || progress < 0 || progress > length) throw new IllegalArgumentException("Invalid job length or progress");
        for (String axis : List.of("x", "z")) if (Math.abs((long) integer(job, axis)) > 29_900_000) throw new IllegalArgumentException("Job is outside supported world bounds");
        if (integer(job, "y") < -2048 || integer(job, "y") > 2048) throw new IllegalArgumentException("Invalid job height");
        JsonObject layout = job.getAsJsonObject("layout");
        HighwayCoordinator.workSharing(layout);
        int dx = integer(layout, "dx"), dz = integer(layout, "dz");
        if (Math.abs((long) dx) + Math.abs((long) dz) != 1 || integer(layout, "width") < 1 || integer(layout, "width") > 5
            || integer(layout, "height") < 1 || integer(layout, "height") > 7) throw new IllegalArgumentException("Unsupported highway geometry");
        String heading = text(layout,"heading");
        String expectedHeading = dx==1 ? "East" : dx==-1 ? "West" : dz==1 ? "South" : "North";
        if (!heading.equals(expectedHeading)) throw new IllegalArgumentException("Job direction does not match its geometry");
        if (!Set.of("Build","Repair","ClearTunnel","Pave").contains(text(layout,"operation")) || !Set.of("Replace","PlaceMissing").contains(text(layout,"floor"))) throw new IllegalArgumentException("Unsupported highway operation/floor");
        ResourceLedger.Policy.read(layout);
        JsonObject workflow = job.has("workflow") ? BotWorkflows.checkedPlan(job.getAsJsonObject("workflow")) : BotWorkflows.legacyPlan(text(layout, "operation"));
        String expectedOperation = BotWorkflows.operation(workflow), actualOperation = text(layout, "operation");
        if (!actualOperation.equals(expectedOperation) && !(actualOperation.equals("Repair") && expectedOperation.equals("Pave")))
            throw new IllegalArgumentException("Job geometry and workflow capabilities disagree");
        job.add("workflow", workflow);
        if (job.has("memberWorkflows")) {
            JsonObject overrides = job.getAsJsonObject("memberWorkflows");
            if (overrides.size() > 32) throw new IllegalArgumentException("Too many worker workflows");
            for (var entry : overrides.entrySet()) {
                UUID.fromString(entry.getKey()); JsonObject plan = BotWorkflows.checkedPlan(entry.getValue().getAsJsonObject());
                BotWorkflows.operation(plan); entry.setValue(plan);
            }
        }
        for (String key : List.of("railings", "supports", "above"))
            if (!layout.has(key) || !layout.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException("Missing highway option " + key);
        if (text(layout, "blocks").isBlank() || text(layout, "blocks").length() > 2048) throw new IllegalArgumentException("Invalid highway materials");
        if (layout.toString().length() > 6000) throw new IllegalArgumentException("Highway settings are too large");
        if (text(job, "crew").length() > 48) throw new IllegalArgumentException("Invalid crew ID");
        if (job.has("execution") && !text(job, "execution").isEmpty()) UUID.fromString(text(job, "execution"));
        if (job.has("type") && !text(job, "type").equals("Highway")) throw new IllegalArgumentException("Unsupported job type");
        String status = job.has("status") ? text(job, "status") : "Unassigned";
        if (!Set.of("Unassigned", "Assigning", "Positioning", "Running", "Paused", "Blocked", "Rebalancing", "Inspection required", "Releasing", "Complete", "Cancelled").contains(status))
            throw new IllegalArgumentException("Invalid job status");
        job.addProperty("type", "Highway"); job.addProperty("status", status); job.addProperty("progress", progress);
        if (!job.has("crew")) job.addProperty("crew", "");
        if (!job.has("includeHost")) job.addProperty("includeHost", false);
        BotHistory.stamp(job, BotHistory.nativeFinished(job), java.lang.System.currentTimeMillis());
        return job;
    }
}
