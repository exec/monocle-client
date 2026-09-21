package dev.monocle.coordinator;

import com.google.gson.*;
import dev.monocle.client.systems.bots.BotWorkflows;
import java.nio.file.Path;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

/** Saved portable packages and unassigned job intents. Running jobs always retain their own snapshot. */
public final class OperationsLibrary {
    private final Path file;
    private final JsonObject workflows = new JsonObject(), drafts = new JsonObject();
    private final Set<String> builtins = new HashSet<>();
    public OperationsLibrary(Path file) {
        this.file = file;
        BotWorkflows nativeLibrary = new BotWorkflows(null);
        for (var definition : nativeLibrary.all()) {
            if (definition.id().equals("task-stash-hunt") || definition.id().equals("highway-supplies")) continue;
            JsonObject packet = nativeLibrary.packageWorkflows(definition.id()), profiles = new JsonObject();
            profiles.add("Current", definition.id().equals(BotWorkflows.DEFAULT_ID) ? sixB6tHighwayProfile() : new JsonObject()); packet.add("profiles", profiles); packet.remove("profileNames");
            if (definition.id().equals("task-follow")) {
                JsonObject profile=profiles.getAsJsonObject("Current");
                for(String module:List.of("speed","elytra-fly","kill-aura","crystal-aura"))addProfile(profile,module,false,"{}");
                addProfile(profile,"auto-eat",true,"{}");
            }
            if (!packet.getAsJsonObject("highways").isEmpty()) {
                JsonObject geometry = new JsonObject(); geometry.addProperty("scope", "unconfigured\nminecraft:the_nether");
                geometry.addProperty("x", 0); geometry.addProperty("y", 116); geometry.addProperty("z", 0);
                JsonObject layout = JsonParser.parseString("{\"dx\":0,\"dz\":-1,\"heading\":\"North\",\"operation\":\"Build\",\"width\":5,\"height\":3,\"floor\":\"Replace\",\"railings\":true,\"supports\":false,\"above\":true,\"blocks\":\"minecraft:obsidian\",\"inventory\":{\"enabled\":true,\"trash\":true,\"paving\":512,\"picks\":3,\"food\":64,\"filler\":64}}").getAsJsonObject();
                layout.addProperty("operation", BotWorkflows.operation(nativeLibrary.compile(definition.id()))); geometry.add("layout", layout); packet.add("geometry", geometry);
            }
            JsonObject record = record(definition.id(), definition.name(), definition.folder(), packet); record.addProperty("builtin", true);
            builtins.add(definition.id()); workflows.add(definition.id(), record);
        }
        JsonObject saved = TaskFiles.read(file);
        if (!saved.isEmpty()) {
            if (!saved.has("version") || saved.get("version").getAsInt() != 1) throw new IllegalArgumentException("Invalid operations library version");
            for (var entry : saved.getAsJsonObject("workflows").entrySet()) {
                UUID.fromString(entry.getKey()); JsonObject r = entry.getValue().getAsJsonObject();
                workflows.add(entry.getKey(), record(entry.getKey(), text(r,"name"), text(r,"folder"), r.getAsJsonObject("package")));
            }
            for (var entry : saved.getAsJsonObject("drafts").entrySet()) {
                UUID.fromString(entry.getKey()); drafts.add(entry.getKey(), checkedDraft(entry.getValue().getAsJsonObject()));
            }
            if (workflows.size() > 128 || drafts.size() > 64) throw new IllegalArgumentException("Operations library exceeds limits");
        }
    }
    /** The immutable built-in worker policy; users duplicate it before changing it. */
    private static JsonObject sixB6tHighwayProfile() {
        JsonObject profile = new JsonObject();
        addProfile(profile, "highway-builder", false, "{groups:[{name:'Digging',settings:[{name:'blocks-ahead-to-break',value:3},{name:'double-mine',value:1b},{name:'fast-break',value:1b},{name:'blocks-per-tick',value:10}]},{name:'Paving',settings:[{name:'place-range',value:7d},{name:'blocks-ahead-to-pave',value:5},{name:'placements-per-tick',value:10}]},{name:'Inventory',settings:[{name:'experimental-managed-inventory',value:1b},{name:'keep-shulkers',value:0b},{name:'search-ender-chest',value:1b},{name:'double-ender-chests',value:1b},{name:'max-shulkers-per-restock',value:4},{name:'mine-ender-chests',value:0b}]}]}");
        addProfile(profile, "auto-eat", true, "{groups:[{name:'General',settings:[{name:'blacklist',value:['minecraft:golden_apple','minecraft:chorus_fruit','minecraft:poisonous_potato','minecraft:pufferfish','minecraft:chicken','minecraft:rotten_flesh','minecraft:spider_eye','minecraft:suspicious_stew']},{name:'protect-named-food',value:0b}]}]}");
        addProfile(profile, "speed", true, "{groups:[{name:'General',settings:[{name:'mode',value:'Vanilla'},{name:'vanilla-speed',value:5.5d}]}]}");
        addProfile(profile, "elytra-fly", true, "{groups:[{name:'Flight',settings:[{name:'mode',value:'Vanilla'},{name:'horizontal-speed',value:3.618421052631582d}]},{name:'Acceleration',settings:[{name:'acceleration',value:1b},{name:'acceleration-step',value:1.25d}]}]}");
        addProfile(profile, "velocity", true, "{}");
        addProfile(profile, "auto-tool", true, "{}");
        addProfile(profile, "auto-armor", true, "{}");
        addProfile(profile, "auto-totem", true, "{}");
        return profile;
    }
    private static void addProfile(JsonObject profile, String module, boolean active, String settings) {
        JsonObject value = new JsonObject(); value.addProperty("active", active); value.addProperty("settings", settings); profile.add(module, value);
    }
    private static JsonObject record(String id, String name, String folder, JsonObject packet) {
        folder = folder.strip();
        if (folder.isEmpty() || folder.length() > 96 || folder.contains("\\") || folder.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid workflow folder");
        for (String segment : folder.split("/", -1)) if (segment.isBlank() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("Invalid workflow folder");
        JsonObject value = new JsonObject(); value.addProperty("id", id); value.addProperty("name", WorkflowPackages.label(name)); value.addProperty("folder", folder);
        value.addProperty("builtin", false); value.add("package", WorkflowPackages.checked(packet)); return value;
    }
    public synchronized JsonArray list() {
        JsonArray result = new JsonArray();
        for (var entry : workflows.entrySet()) {
            JsonObject r = entry.getValue().getAsJsonObject(), summary = r.deepCopy(), packet = summary.remove("package").getAsJsonObject();
            summary.addProperty("entry", text(packet,"entry")); summary.addProperty("highway", !packet.getAsJsonObject("highways").isEmpty());
            summary.add("profiles", packet.getAsJsonObject("profiles").keySet().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
            summary.addProperty("configured", packet.getAsJsonObject("profiles").asMap().values().stream().anyMatch(v -> !v.getAsJsonObject().isEmpty())); result.add(summary);
        }
        return result;
    }
    public synchronized JsonObject get(String id) {
        if (!workflows.has(id)) throw new IllegalArgumentException("Unknown saved workflow");
        return workflows.getAsJsonObject(id).deepCopy();
    }
    /** Preview is detached; save requires the source fingerprint to prevent overwriting another editor. */
    public synchronized JsonObject previewControl(String id,JsonObject control) {
        JsonObject original=get(id),next=original.deepCopy(),edit=JobSettingControls.preview(control);
        JsonObject current=next.getAsJsonObject("package").getAsJsonObject("profiles").getAsJsonObject("Current");
        if(current==null)throw new IllegalArgumentException("This package has no Current profile to edit");
        JsonObject before=new JsonObject(),after=new JsonObject();
        for(var entry:edit.getAsJsonObject("modules").entrySet()){
            String module=entry.getKey();JsonObject patch=entry.getValue().getAsJsonObject(),previous=current.has(module)?current.getAsJsonObject(module):null;
            before.add(module,previous==null?JsonNull.INSTANCE:previous.deepCopy());
            JsonObject merged=patch.deepCopy();merged.addProperty("settings",SettingsOverlay.merge(previous==null?"{}":text(previous,"settings"),text(patch,"settings")));
            current.add(module,merged);after.add(module,merged.deepCopy());
        }
        JsonObject result=new JsonObject();result.addProperty("expected",TaskFiles.hash(original.toString()));result.add("before",before);result.add("after",after);
        result.addProperty("description",text(edit,"description"));result.addProperty("scope","Future jobs only. Running jobs retain their captured settings.");result.add("record",next);return result;
    }
    public synchronized JsonObject editControl(String id,String expected,JsonObject control) {
        JsonObject preview=previewControl(id,control),record=preview.getAsJsonObject("record");
        if(!text(preview,"expected").equals(expected))throw new IllegalStateException("Preset changed since preview. Preview again.");
        return save(id,text(record,"name"),text(record,"folder"),record.getAsJsonObject("package"));
    }
    public synchronized JsonObject prepare(String id,String scope,JsonObject args) {
        if(args==null||args.toString().length()>32768)throw new IllegalArgumentException("Invalid preset arguments");
        JsonObject packet=get(id).getAsJsonObject("package");
        if(!packet.getAsJsonObject("highways").isEmpty()){
            if(args.has("length"))boundedInteger(args,"length",16,HighwayJobs.MAX_LENGTH);
            JsonObject geometry=packet.getAsJsonObject("geometry"),layout=geometry.getAsJsonObject("layout");geometry.addProperty("scope",scope);
            for(String axis:List.of("x","y","z"))if(args.has(axis))geometry.add(axis,args.get(axis).deepCopy());
            String direction=args.has("direction")?text(args,"direction"):text(layout,"heading");
            int dx=0,dz=0;switch(direction){case "North"->dz=-1;case "South"->dz=1;case "East"->dx=1;case "West"->dx=-1;default->throw new IllegalArgumentException("Choose a cardinal direction");}
            layout.addProperty("dx",dx);layout.addProperty("dz",dz);layout.addProperty("heading",direction);
        }
        if(text(packet,"entry").equals("task-follow")) {
            UUID.fromString(text(args,"target"));
            if(args.has("radius")){double radius=args.get("radius").getAsDouble();if(!Double.isFinite(radius)||radius<1||radius>8)throw new IllegalArgumentException("Following distance must be 1–8 blocks");}
            if(args.has("ticks"))boundedInteger(args,"ticks",0,1_728_000);
        }
        return WorkflowPackages.checked(packet);
    }
    private static int boundedInteger(JsonObject args,String key,int min,int max) {
        if(!args.get(key).isJsonPrimitive()||!args.getAsJsonPrimitive(key).isNumber())throw new IllegalArgumentException("Expected integer "+key);
        int value;try{value=args.get(key).getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Expected integer "+key);}
        if(value<min||value>max)throw new IllegalArgumentException(key+" must be "+min+"–"+max);return value;
    }
    public synchronized JsonObject save(String id, String name, String folder, JsonObject packet) {
        if (builtins.contains(id)) throw new IllegalArgumentException("Duplicate a built-in before editing it"); UUID.fromString(id);
        if (!workflows.has(id) && workflows.size() >= 128) throw new IllegalArgumentException("Workflow library full");
        for (var entry : workflows.entrySet()) {
            JsonObject r = entry.getValue().getAsJsonObject();
            if (!entry.getKey().equals(id) && text(r,"folder").equalsIgnoreCase(folder.strip()) && text(r,"name").equalsIgnoreCase(name.strip())) throw new IllegalArgumentException("That folder already has this workflow name");
        }
        JsonObject value = record(id, name, folder, packet); JsonElement previous = workflows.get(id); workflows.add(id, value);
        try { persist(); } catch (RuntimeException e) { if(previous==null)workflows.remove(id);else workflows.add(id,previous); throw e; }
        return value.deepCopy();
    }
    public synchronized void delete(String id) {
        get(id); if (builtins.contains(id)) throw new IllegalArgumentException("Built-in workflows are read-only");
        JsonElement previous = workflows.remove(id);
        try { persist(); } catch(RuntimeException e) { workflows.add(id,previous);throw e; }
    }
    public synchronized JsonArray drafts() { JsonArray result=new JsonArray();drafts.asMap().values().forEach(v->{JsonObject r=v.getAsJsonObject().deepCopy();r.remove("package");result.add(r);});return result; }
    public synchronized JsonObject draft(String id) { if(!drafts.has(id))throw new IllegalArgumentException("Unknown unassigned job");return drafts.getAsJsonObject(id).deepCopy(); }
    private static JsonObject checkedDraft(JsonObject input) {
        JsonObject value=input.deepCopy();UUID.fromString(text(value,"id"));value.addProperty("name",WorkflowPackages.label(text(value,"name")));
        value.add("package",WorkflowPackages.checked(value.getAsJsonObject("package")));
        if(text(value,"server").isBlank() || text(value,"server").length()>1024 || text(value,"server").chars().anyMatch(Character::isISOControl)
            || !text(value,"dimension").matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Specify a server and dimension");
        int priority=value.get("priority").getAsBigDecimal().intValueExact();if(priority < -1000 || priority>1000)throw new IllegalArgumentException("Invalid priority");
        if(value.has("sourceTask"))UUID.fromString(text(value,"sourceTask"));
        if(value.has("nativeDefinition")) {
            JsonObject definition=HighwayJobs.checked(value.getAsJsonObject("nativeDefinition"));
            if(!text(definition,"scope").equals(text(value,"server")+"\n"+text(value,"dimension")) || !value.getAsJsonObject("package").getAsJsonObject("highways").has(text(definition.getAsJsonObject("workflow"),"id")))throw new IllegalArgumentException("Checkpoint world/workflow does not match its job");value.add("nativeDefinition",definition);
        }
        TaskFiles.jsonBytes(value.getAsJsonObject("args"),32_768);return value;
    }
    public synchronized JsonObject saveDraft(JsonObject input) {
        JsonObject value=checkedDraft(input);String id=text(value,"id");if(!drafts.has(id) && drafts.size()>=64)throw new IllegalArgumentException("Unassigned job list full");
        JsonElement previous=drafts.get(id);drafts.add(id,value);
        try { persist(); } catch(RuntimeException e) { if(previous==null)drafts.remove(id);else drafts.add(id,previous);throw e; }return value.deepCopy();
    }
    public synchronized void deleteDraft(String id) { draft(id);JsonElement previous=drafts.remove(id);try{persist();}catch(RuntimeException e){drafts.add(id,previous);throw e;} }
    private void persist() {
        JsonObject root=new JsonObject(),custom=new JsonObject();root.addProperty("version",1);
        workflows.entrySet().stream().filter(e->!builtins.contains(e.getKey())).forEach(e->custom.add(e.getKey(),e.getValue()));root.add("workflows",custom);root.add("drafts",drafts);TaskFiles.write(file,root);
    }
}
