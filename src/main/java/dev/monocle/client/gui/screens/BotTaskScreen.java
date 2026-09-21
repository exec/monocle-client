package dev.monocle.client.gui.screens;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WSection;
import dev.monocle.client.gui.widgets.containers.WTable;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.gui.widgets.input.WIntEdit;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.systems.bots.BotScheduler;
import dev.monocle.client.systems.bots.BotProfiles;
import dev.monocle.client.systems.bots.BotWorkflows;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.render.color.Color;

import java.util.*;
import java.util.function.Supplier;

import static dev.monocle.client.MonocleClient.mc;

/** Queued execution is separate from saved highway geometry and never runs while editing. */
public final class BotTaskScreen extends WindowScreen {
    private record Choice(String id, String label) { @Override public String toString() { return label; } }
    private final Bots bots;
    private final UUID id;
    private final String initialWorkflow, initialCrew;
    private final Set<UUID> targets = new LinkedHashSet<>();
    private final Map<UUID, Integer> overrides = new LinkedHashMap<>();
    private WLabel feedback, summary, workflowDetails;
    private WVerticalList targetList;
    private WVerticalList arguments;
    private WVerticalList history;
    private boolean historyVisible;
    private Supplier<JsonObject> parameters;
    private WDropdown<Choice> workflow, crew;
    private WIntEdit priority;
    private double contentWidth;
    private int ticks;

    public BotTaskScreen(GuiTheme theme, Bots bots, UUID id) { this(theme, bots, id, null, null); }
    public BotTaskScreen(GuiTheme theme, Bots bots, UUID id, String initialWorkflow, String initialCrew) {
        super(theme, id == null ? "Queue a workflow" : "Task execution");
        this.bots = bots; this.id = id; this.initialWorkflow = initialWorkflow; this.initialCrew = initialCrew;
    }

    @Override public void initWidgets() {
        contentWidth = Math.clamp(Utils.getWindowWidth() / theme.scale(1) - 100, 330, 700);
        feedback = add(theme.label("", contentWidth)).expandX().widget();
        try { if (id == null) createEditor(); else inspect(); }
        catch (RuntimeException e) { error(e); }
        add(theme.button("Back to Workers")).expandX().widget().action = this::onClose;
    }

    private void createEditor() {
        requireHost();
        add(theme.button("Saved job presets · duplicate / edit defaults")).expandX().widget().action = () -> mc.gui.setScreen(new JobPresetsScreen(theme,bots));
        List<Choice> choices = new ArrayList<>();
        for (var value : bots.workflows().all()) if (!value.script().isEmpty() || !bots.workflows().compile(value.id()).get("duty").getAsString().equals("Supply"))
            choices.add(new Choice(value.id(), (value.script().isEmpty() ? "Native · " : "Lua · ") + value.folder() + " / " + value.name()));
        Choice[] crews = bots.presets().stream().map(p -> new Choice(p.name(), bots.crewLabel(p.name()))).toArray(Choice[]::new);
        for (var item : bots.operations().list()) {
            JsonObject value=item.getAsJsonObject();choices.addFirst(new Choice("package:"+value.get("id").getAsString(),"Preset · "+value.get("folder").getAsString()+" / "+value.get("name").getAsString()));
        }
        if (choices.isEmpty() || crews.length == 0) throw new IllegalStateException("Create a crew and a runnable workflow first.");
        add(theme.label("Queue work for one worker or a whole crew. Higher priority requests a safe interruption; equal priority waits its turn. Completing an interruption resumes the suspended task.", contentWidth).color(theme.textSecondaryColor()));
        WTable fields = add(theme.table()).expandX().widget();
        fields.add(theme.label("Task name")); WTextBox name = fields.add(theme.textBox("Workflow task")).minWidth(220).expandX().widget();
        fields.row(); fields.add(theme.label("Workflow"));
        workflow = fields.add(theme.dropdown(choices.toArray(Choice[]::new), choices.stream().filter(c -> c.id().equals("package:"+initialWorkflow)).findFirst().orElseGet(()->choices.stream().filter(c->c.id().equals(initialWorkflow)).findFirst().orElseGet(()->choices.stream().filter(c->c.id().equals("package:highway-default")).findFirst().orElse(choices.getFirst()))))).expandX().widget();
        fields.row(); fields.add(theme.label("Crew"));
        crew = fields.add(theme.dropdown(crews, Arrays.stream(crews).filter(c -> c.id().equals(initialCrew == null ? bots.selectedCrew() : initialCrew)).findFirst().orElse(crews[0]))).expandX().widget();
        fields.row(); fields.add(theme.label("Priority")); priority = fields.add(theme.intEdit(0, -1000, 1000, true)).expandX().widget();
        priority.tooltip = "Higher runs first. An interruption waits for container recovery and a safe checkpoint; it never abandons supplies.";
        workflowDetails = add(theme.label("", contentWidth)).expandX().widget();
        workflow.action = () -> perform(() -> { describeWorkflow(); rebuildArguments(); }, "Workflow selected. Review its refreshed inputs before queuing.");
        describeWorkflow();
        WSection workers = add(theme.section("Target workers & individual priorities", true)).expandX().widget();
        workers.add(theme.label("The host coordinates but is not a target. Select every connected member for a crew task, or choose a single worker. Busy workers can accept queued work.", contentWidth - 20).color(theme.textSecondaryColor()));
        targetList = workers.add(theme.verticalList()).expandX().widget();
        crew.action = () -> perform(() -> rebuildTargets(true), "Crew selected. Connected workers selected; review before queuing.");
        rebuildTargets(true);
        WSection inputs = add(theme.section("Workflow inputs", true)).expandX().widget();
        arguments = inputs.add(theme.verticalList()).expandX().widget();
        rebuildArguments();
        final String[] reviewed={null};
        var captured=add(theme.section("Captured package preview",false)).expandX().widget();
        var review=add(theme.label("Preview the selected workers, inputs and captured configuration before queuing.",contentWidth)).expandX().widget();
        var queue=add(theme.button("Preview workflow")).expandX().widget();
        queue.action = () -> perform(() -> {
            requireHost();
            if (targets.isEmpty()) throw new IllegalStateException("Choose at least one connected worker.");
            Map<UUID, Integer> selectedPriorities = new LinkedHashMap<>(overrides); selectedPriorities.keySet().retainAll(targets);
            JsonObject args=parameters.get();
            String scope=(mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip)+"\n"+mc.level.dimension().identifier();
            String source=workflow.get().id().startsWith("package:")?bots.operations().prepare(workflow.get().id().substring(8),scope,args).toString():bots.workflows().packageWorkflows(workflow.get().id()).toString();
            String fingerprint=name.get()+workflow.get().id()+crew.get().id()+targets+selectedPriorities+priority.get()+args+source;
            if(!fingerprint.equals(reviewed[0])) { reviewed[0]=fingerprint;review.set(name.get()+" · "+crew.get()+" · "+targets.size()+" followers/workers\nInputs: "+args+"\nCaptured preset settings are fixed for this job. Click Queue reviewed workflow to start. Changes require another preview.");captured.clear();captured.add(new WorkflowCodeBox(source,true,8)).expandX().widget();queue.set("Queue reviewed workflow");return; }
            UUID created = bots.tasks().create(name.get(), workflow.get().id(), crew.get().id(), Set.copyOf(targets), args, priority.get(), Map.copyOf(selectedPriorities));
            mc.gui.setScreen(new BotTaskScreen(theme, bots, created));
        }, "Workflow queued with captured code, profiles and arguments.");
    }

    private void rebuildArguments() {
        arguments.clear(); parameters = null;
        WTable fields = arguments.add(theme.table()).expandX().widget();
        String selectedWorkflow=workflow.get().id();
        if(selectedWorkflow.startsWith("package:"))selectedWorkflow=bots.operations().get(selectedWorkflow.substring(8)).getAsJsonObject("package").get("entry").getAsString();
        else if(selectedWorkflow.startsWith("highway-"))selectedWorkflow=""; // Legacy definitions still use the host's native layout.
        switch (selectedWorkflow) {
            case "highway-default", "highway-excavate", "highway-pave" -> {
                WIntEdit x=integer(fields,"Start X",mc.player.getBlockX(),-29_900_000,29_900_000),y=integer(fields,"Start Y",mc.player.getBlockY(),-2048,2048),z=integer(fields,"Start Z",mc.player.getBlockZ(),-29_900_000,29_900_000);
                WIntEdit length=integer(fields,"Length",10000,16,100000);
                fields.add(theme.label("Direction"));var direction=fields.add(theme.dropdown(new String[]{"North","East","South","West"},"North")).expandX().widget();
                parameters=()->{JsonObject a=new JsonObject();a.addProperty("x",x.get());a.addProperty("y",y.get());a.addProperty("z",z.get());a.addProperty("length",length.get());a.addProperty("direction",direction.get());return a;};
            }
            case "task-follow" -> {
                WTextBox leader=string(fields,"Leader username or UUID",mc.player.getName().getString());
                WIntEdit radius=integer(fields,"Following distance (blocks)",3,1,8),ticks=integer(fields,"Duration (ticks; 0 = until cancelled)",0,0,1_728_000);
                parameters=()->{String requested=leader.get().strip();String target=bots.allMembers().stream().filter(m->m.name().equalsIgnoreCase(requested)).map(m->m.id().toString()).findFirst().orElse(requested);if(mc.player.getName().getString().equalsIgnoreCase(requested))target=mc.player.getUUID().toString();UUID.fromString(target);JsonObject a=new JsonObject();a.addProperty("target",target);a.addProperty("radius",radius.get());a.addProperty("ticks",ticks.get());return a;};
                arguments.add(theme.label("Select followers only. Walking, no terrain edits or automatic combat. The leader plays normally. Followers wait when the leader is not visible.",contentWidth-20));
            }
            case "task-stash-scan" -> {
                JsonObject selected;
                try { selected=dev.monocle.client.systems.modules.Modules.get().get(dev.monocle.client.systems.modules.world.SchematicSelector.class).selectionBounds("Main stash"); }
                catch(RuntimeException e){selected=new JsonObject();for(String axis:List.of("X","Y","Z")){int coordinate=mc.player==null?0:axis.equals("X")?mc.player.getBlockX():axis.equals("Y")?mc.player.getBlockY():mc.player.getBlockZ();selected.addProperty("min"+axis,coordinate);selected.addProperty("max"+axis,coordinate);}}
                fields.add(theme.label("Stash name"));WTextBox name=fields.add(theme.textBox("Main stash")).expandX().widget();fields.row();
                fields.add(theme.label("Home name"));WTextBox home=fields.add(theme.textBox("")).expandX().widget();fields.row();
                WIntEdit warmup=integer(fields,"Home warmup (seconds)",15,0,3600),cooldown=integer(fields,"Home cooldown (minutes)",10,0,1440);
                Map<String,WIntEdit> bounds=new LinkedHashMap<>();
                for(String axis:List.of("X","Y","Z"))for(String end:List.of("min","max")){String key=end+axis;int limit=axis.equals("Y")?2048:29_900_000;bounds.put(key,integer(fields,key,selected.get(key).getAsInt(),-limit,limit));}
                parameters=()->{JsonObject p=new JsonObject();p.addProperty("name",name.get());p.addProperty("homeName",home.get());p.addProperty("homeWarmupTicks",warmup.get()*20);p.addProperty("homeCooldownTicks",cooldown.get()*1200);bounds.forEach((key,value)->p.addProperty(key,value.get()));return dev.monocle.coordinator.StashCatalog.plan(p);};
                arguments.add(theme.label("Optional Home name runs /home [name] before scanning. Bounds copy the current wooden-pickaxe selection. Workers inspect disjoint containers; no items or terrain are changed.",contentWidth-20));
            }
            case "task-stash-hunt" -> {
                int ox = mc.player == null ? 0 : mc.player.getBlockX(), oz = mc.player == null ? 0 : mc.player.getBlockZ();
                WIntEdit minX = integer(fields, "Minimum X", ox, -29_900_000, 29_900_000);
                WIntEdit maxX = integer(fields, "Maximum X", Math.min(29_900_000, ox + 1023), -29_900_000, 29_900_000);
                WIntEdit minZ = integer(fields, "Minimum Z", oz, -29_900_000, 29_900_000);
                WIntEdit maxZ = integer(fields, "Maximum Z", Math.min(29_900_000, oz + 4095), -29_900_000, 29_900_000);
                WIntEdit y = integer(fields, "Flight altitude", mc.player == null ? 180 : mc.player.getBlockY(), -2048, 2048);
                WIntEdit radius = integer(fields, "Strip half-width (chunks)", 2, 1, 8);
                WIntEdit speed = integer(fields, "Speed ceiling (blocks/sec)", 60, 1, 120);
                WIntEdit acceleration = integer(fields, "Acceleration (blocks/sec²)", 4, 1, 40);
                parameters = () -> {
                    JsonObject a = new JsonObject(); a.addProperty("minX", minX.get()); a.addProperty("maxX", maxX.get());
                    a.addProperty("minZ", minZ.get()); a.addProperty("maxZ", maxZ.get()); a.addProperty("y", y.get());
                    a.addProperty("radiusChunks", radius.get()); a.addProperty("maxSpeed", speed.get()); a.addProperty("acceleration", acceleration.get());
                    return a;
                };
                arguments.add(theme.label("Bounds expand to whole chunks. Workers sweep disjoint strips; missing received chunks are never marked scanned. Pick an unobstructed altitude and a strip radius within every worker's server view distance.", contentWidth - 20).color(theme.textSecondaryColor()));
                arguments.add(theme.label("Uses the host's captured Stash Finder filters and Vanilla ElytraFly settings. Equip elytras first. Speed ramps up to the LOWER of this ceiling and ElytraFly Horizontal Speed × 20, backing off on missing chunks or corrections. Findings save to both notebooks.", contentWidth - 20).color(theme.textSecondaryColor()));
            }
            case "task-travel" -> {
                WIntEdit x = integer(fields, "Destination X", mc.player == null ? 0 : mc.player.getBlockX(), -29_900_000, 29_900_000);
                WIntEdit y = integer(fields, "Destination Y", mc.player == null ? 116 : mc.player.getBlockY(), -2048, 2048);
                WIntEdit z = integer(fields, "Destination Z", mc.player == null ? 0 : mc.player.getBlockZ(), -29_900_000, 29_900_000);
                WTextBox dimension = string(fields, "Dimension", mc.level == null ? "" : mc.level.dimension().identifier().toString());
                WIntEdit radius = integer(fields, "Arrival radius", 2, 1, 8);
                parameters = () -> { JsonObject a = new JsonObject(); a.addProperty("x", x.get()); a.addProperty("y", y.get()); a.addProperty("z", z.get());
                    if (!dimension.get().isBlank()) a.addProperty("dimension", dimension.get().strip()); a.addProperty("radius", radius.get()); return a; };
                arguments.add(theme.label("Coordinates start at the host's current position. Review them: creating this form does not move anyone.", contentWidth - 20).color(theme.textSecondaryColor()));
            }
            case "task-tpa" -> {
                WTextBox target = string(fields, "Target name / UUID", mc.player == null ? mc.getUser().getName() : mc.player.getName().getString());
                WIntEdit warmup = integer(fields, "Warmup ticks", 300, 0, 1200), acceptDelay=integer(fields,"Accept delay ticks",10,0,200), timeout = integer(fields, "Timeout ticks", 1200, 20, 72_000);
                WIntEdit radius = integer(fields, "Arrival radius", 8, 1, 16);
                parameters = () -> { JsonObject a = new JsonObject(); a.addProperty("target", target.get().strip()); a.addProperty("warmupTicks", warmup.get());a.addProperty("acceptDelayTicks",acceptDelay.get()); a.addProperty("timeoutTicks", timeout.get()); a.addProperty("radius", radius.get()); return a; };
                arguments.add(theme.label("20 ticks ≈ one second. TPA waits for observed arrival near the target, not just the warmup timer; the server may require the target to accept.", contentWidth - 20).color(theme.textSecondaryColor()));
            }
            case "task-drop" -> {
                WTextBox item = string(fields, "Item ID", "minecraft:netherrack");
                WIntEdit count = integer(fields, "Item count", 64, 1, 2304);
                WTextBox recipient = string(fields, "Recipient name / UUID (optional)", "");
                parameters = () -> { JsonObject a = new JsonObject(); a.addProperty("item", item.get().strip()); a.addProperty("count", count.get());
                    if (!recipient.get().isBlank()) {
                        String requested = recipient.get().strip();
                        String value = bots.allMembers().stream().filter(m -> m.name().equalsIgnoreCase(requested)).map(m -> m.id().toString()).findFirst().orElse(requested);
                        try { UUID.fromString(value); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose a known bot/host name, or enter the recipient's UUID."); }
                        a.addProperty("recipient", value);
                    }
                    return a; };
                arguments.add(theme.label("This task drops real inventory items. Check the item/count and destination before queuing it; leave recipient blank to drop locally.", contentWidth - 20).color(new Color(218, 188, 120)));
            }
            case "task-wait" -> {
                WIntEdit ticks = integer(fields, "Wait ticks", 20, 1, 72_000);
                parameters = () -> { JsonObject a = new JsonObject(); a.addProperty("ticks", ticks.get()); return a; };
            }
            case "task-profile" -> {
                List<String> names = workflow.get().id().startsWith("package:")?new ArrayList<>(bots.operations().get(workflow.get().id().substring(8)).getAsJsonObject("package").getAsJsonObject("profiles").keySet()):BotProfiles.names(); fields.add(theme.label("Gameplay profile"));
                var profile = fields.add(theme.dropdown(names.toArray(String[]::new), names.getFirst())).expandX().widget();
                parameters = () -> { JsonObject a = new JsonObject(); a.addProperty("name", profile.get()); return a; };
                arguments.add(theme.label("A short-lived profile preview: personal settings return when this task ends. For useful work under a shared profile, call bot.profile inside your program before its actions. Connection keys and accounts are excluded.", contentWidth - 20).color(theme.textSecondaryColor()));
            }
            default -> {
                arguments.add(theme.label("JSON object passed as ctx.args. Custom programs define their own input keys; inspect the source before running. Module durations are ticks; zero means run until cancelled.", contentWidth - 20).color(theme.textSecondaryColor()));
                String example = workflow.get().id().equals("task-modules") ? "{\n  \"modules\": {\"auto-eat\": true},\n  \"ticks\": 200\n}" : "{}";
                WorkflowCodeBox args = arguments.add(new WorkflowCodeBox(example, false, 6)).expandX().minWidth(contentWidth - 20).widget();
                parameters = () -> JsonParser.parseString(args.get()).getAsJsonObject();
            }
        }
    }
    private WTextBox string(WTable table, String label, String value) {
        table.add(theme.label(label)); WTextBox field = table.add(theme.textBox(value)).expandX().widget(); table.row(); return field;
    }
    private WIntEdit integer(WTable table, String label, int value, int minimum, int maximum) {
        table.add(theme.label(label)); WIntEdit field = table.add(theme.intEdit(value, minimum, maximum, true)).expandX().widget(); table.row(); return field;
    }

    private void describeWorkflow() {
        if(workflow.get().id().startsWith("package:")) {
            JsonObject record=bots.operations().get(workflow.get().id().substring(8));
            workflowDetails.set(record.get("name").getAsString()+" · portable captured package\nProfiles and native configuration come from the saved snapshot. JSON arguments override workflow inputs. Existing jobs are immutable.");return;
        }
        BotWorkflows.Workflow selected = bots.workflows().get(workflow.get().id());
        workflowDetails.set(selected.name() + (selected.script().isEmpty() ? " · native highway preset" : " · Lua program")
            + "\nProfiles captured at queue time: " + (selected.profiles().isEmpty() ? "none declared" : String.join(", ", selected.profiles()))
            + "\n" + (selected.script().isEmpty() ? "Native execution uses the captured highway settings."
                : "Open this definition in Workflows to read its input arguments and nested calls."));
    }
    private void rebuildTargets(boolean reset) {
        if (reset) { targets.clear(); overrides.clear(); }
        targetList.clear();
        UUID local = mc.player == null ? mc.getUser().getProfileId() : mc.player.getUUID();
        var members = bots.allMembers().stream().filter(m -> !m.id().equals(local) && m.connected() && crew.get().id().equals(bots.workerCrew(m.id()))).toList();
        if (reset) members.forEach(m -> targets.add(m.id()));
        if (members.isEmpty()) targetList.add(theme.label("No connected remote workers in this crew. Connect or move a worker from Crews first.", contentWidth - 20));
        for (var member : members) {
            WVerticalList entry = targetList.add(theme.verticalList()).expandX().widget();
            WHorizontalList row = entry.add(theme.horizontalList()).expandX().widget();
            var selected = row.add(theme.checkbox(targets.contains(member.id()))).widget();
            row.add(theme.label(member.name() + " · " + member.phase(), contentWidth - 60)).expandX();
            selected.action = () -> { if (selected.checked) targets.add(member.id()); else targets.remove(member.id()); };
            WHorizontalList policy = entry.add(theme.horizontalList()).expandX().widget();
            var custom = policy.add(theme.checkbox(overrides.containsKey(member.id()))).widget();
            policy.add(theme.label("Own priority"));
            WIntEdit value = policy.add(theme.intEdit(overrides.getOrDefault(member.id(), priority.get()), -1000, 1000, true)).expandX().widget();
            custom.action = () -> { if (custom.checked) overrides.put(member.id(), value.get()); else overrides.remove(member.id()); };
            value.action = () -> { if (custom.checked) overrides.put(member.id(), value.get()); };
        }
    }

    private BotScheduler.TaskView current() {
        return bots.tasks().list().stream().filter(task -> task.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("This task is no longer in the queue."));
    }
    private void inspect() {
        var task = current();
        summary = add(theme.label(describe(bots, task), contentWidth)).expandX().widget();
        add(theme.button("Copy task report")).expandX().widget().action = () -> perform(() -> mc.keyboardHandler.setClipboard(describe(bots, current())), "Task report copied; connection keys are excluded.");
        add(theme.button("Inspect configuration & supply capabilities")).expandX().widget().action = () ->
            mc.gui.setScreen(new TaskConfigurationScreen(theme, bots, id));
        if (bots.mode.get() != Bots.Mode.Host) { feedback.set("Read-only worker monitor. Task controls belong to the host."); return; }
        if (!task.history()) add(theme.button("Edit live job settings")).expandX().widget().action = () ->
            mc.gui.setScreen(new JobSettingsScreen(theme, bots, id));
        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        controls.add(theme.button("Pause")).widget().action = () -> perform(() -> { requireHost(); bots.tasks().pause(id); }, "Task pause requested. Current activity and any recovery blocker are shown above.");
        controls.add(theme.button("Resume")).widget().action = () -> perform(() -> { requireHost(); bots.tasks().resume(id); }, "Resume requested. Inspect any recovery or return-to-anchor blocker above.");
        var cancel = controls.add(theme.confirmedButton("Cancel task", "Cancel this task?")).widget();
        cancel.action = () -> perform(() -> { requireHost(); bots.tasks().cancel(id); }, "Cancellation requested. Native supply recovery remains protected.");
        historyVisible = false;
        history = add(theme.verticalList()).expandX().widget();
        refreshHistory(task);
        WHorizontalList priorityRow = add(theme.horizontalList()).expandX().widget();
        priorityRow.add(theme.label("Default priority"));
        priority = priorityRow.add(theme.intEdit(task.priority(), -1000, 1000, true)).expandX().widget();
        priorityRow.add(theme.button("Apply")).widget().action = () -> perform(() -> { requireHost(); bots.tasks().priority(id, priority.get()); }, "Task priority updated. Preemption still waits for a safe checkpoint.");
        WSection workers = add(theme.section("Per-worker priority", false)).expandX().widget();
        for (UUID worker : task.targets()) {
            WHorizontalList row = workers.add(theme.horizontalList()).expandX().widget();
            row.add(theme.label(workerName(bots, worker))).expandX();
            WIntEdit value = row.add(theme.intEdit(task.workerPriorities().getOrDefault(worker, task.priority()), -1000, 1000, true)).widget();
            row.add(theme.button("Apply")).widget().action = () -> perform(() -> { requireHost(); bots.tasks().workerPriority(id, worker, value.get()); }, "Worker-specific priority updated.");
        }
    }
    private void refreshHistory(BotScheduler.TaskView task) {
        if (history == null) return;
        boolean finished = task.history();
        if (finished == historyVisible) return;
        historyVisible = finished; history.clear();
        if (!finished) return;
        var delete = history.add(theme.confirmedButton("Delete history", "Delete finished task history?")).expandX().widget();
        delete.tooltip = "Deletes linked finished workflow and native highway history together. Active work and recovery reservations are protected.";
        delete.action = () -> perform(() -> {
            requireHost(); bots.tasks().delete(id);
            summary = null;
            // Inspectors created by the queue form have another task screen as their parent.
            while (parent instanceof BotTaskScreen previous) parent = previous.parent;
            onClose();
        }, "Finished task history deleted.");
    }
    public static String describe(Bots bots, BotScheduler.TaskView task) {
        String report=task.name() + " · " + task.status() + "\n" + task.workflowName() + " · priority " + task.priority()
            + " · " + bots.crewLabel(task.crewId()) + "\nWorkers: " + String.join(", ", task.targets().stream().map(id -> workerName(bots, id)).toList())
            + "\n" + task.detail();
        StringBuilder debug=new StringBuilder(report);
        for(var value:bots.tasks().stashDiagnostics(task.id())){
            JsonObject s=value.getAsJsonObject();debug.append("\n\n").append(workerName(bots,UUID.fromString(s.get("worker").getAsString()))).append(" · ").append(s.get("status").getAsString()).append(" · ").append(s.get("phase").getAsString())
                .append("\n").append(s.get("detail").getAsString()).append("\nTarget ").append(s.get("target").getAsString()).append(" · movement ").append(s.get("movementTarget").getAsString())
                .append("\n").append(s.get("observed")).append(" observed · ").append(s.get("unscanned")).append(" unscanned · ").append(s.get("missingChunks")).append(" missing chunks")
                .append("\nLast action: ").append(s.get("lastAction").getAsString());
            if(s.has("events")){var events=s.getAsJsonArray("events");for(int i=Math.max(0,events.size()-6);i<events.size();i++){JsonObject e=events.get(i).getAsJsonObject();debug.append("\n").append(e.get("phase").getAsString()).append(": ").append(e.get("reason").getAsString());}}
        }
        return debug.toString();
    }
    private static String workerName(Bots bots, UUID id) { return bots.allMembers().stream().filter(m -> m.id().equals(id)).map(m -> m.name()).findFirst().orElse(id.toString()); }
    private void requireHost() { if (bots.mode.get() != Bots.Mode.Host) throw new IllegalStateException("Only the host can manage task queues."); }
    private void error(RuntimeException e) { feedback.set(e.getMessage() == null ? "Unable to update this task." : e.getMessage()); feedback.color(new Color(230, 139, 147)); }
    private void perform(Runnable action, String success) {
        try { action.run(); feedback.set(success); feedback.color(new Color(115, 211, 181)); }
        catch (RuntimeException e) { error(e); }
    }
    @Override public void tick() {
        super.tick();
        if (summary != null && ++ticks % 5 == 0) try {
            var task = current(); summary.set(describe(bots, task)); refreshHistory(task);
        } catch (RuntimeException e) { error(e); }
    }
}
