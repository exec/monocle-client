package dev.monocle.client.gui.tabs.builtin;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import dev.monocle.coordinator.HighwayJobs;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.screens.HighwayBuilderScreen;
import dev.monocle.client.gui.screens.WorkflowEditorScreen;
import dev.monocle.client.gui.screens.BotTaskScreen;
import dev.monocle.client.gui.tabs.Tab;
import dev.monocle.client.gui.tabs.TabScreen;
import dev.monocle.client.gui.tabs.WindowTabScreen;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.*;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.gui.widgets.input.WIntEdit;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.gui.widgets.pressable.WCheckbox;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.systems.bots.BotWorkflows;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.swarm.SwarmCrew;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.render.color.Color;
import net.minecraft.client.gui.screens.Screen;

import java.util.*;

import static dev.monocle.client.MonocleClient.mc;

/** A persistent control room; opening or closing it never changes the connection or job. */
public class BotsTab extends Tab {
    private static final Color GOLD = new Color(218, 188, 120);
    private static final Color GREEN = new Color(115, 211, 181);
    private static final Color RED = new Color(230, 139, 147);

    public BotsTab() { super("Workers"); }

    @Override public TabScreen createScreen(GuiTheme theme) { return new BotsScreen(theme, this); }
    @Override public boolean isScreen(Screen screen) { return screen instanceof BotsScreen; }

    private enum Page { Crews, Jobs, Workflows, History }
    private record Choice(String id, String label) { @Override public String toString() { return label; } }

    private static class BotsScreen extends WindowTabScreen {
        private final Bots bots = Bots.get();
        private final Set<UUID> selected = new LinkedHashSet<>();
        private final Map<UUID, WLabel> memberStatus = new HashMap<>();
        private final Map<UUID, WCheckbox> memberChecks = new HashMap<>();
        private WLabel headline, connection, connectionDetail, keyStatus, feedback, crewDetail, rosterSummary, activity;
        private WVerticalList connectionControls, pageBody, roster, crewControls, catalog, discovery;
        private WVerticalList workflowLibrary;
        private WVerticalList taskQueue;
        private final Map<UUID, WLabel> taskLabels = new LinkedHashMap<>();
        private WHorizontalList navigation;
        private WTextBox crewName;
        private WDropdown<Bots.Mode> role;
        private WDropdown<Choice> crewSelector;
        private final Map<UUID, WLabel> jobLabels = new LinkedHashMap<>();
        private Page page = Page.Crews;
        private Bots.Mode shownRole;
        private String rosterShape = "", selectedCrew = "", crewShape = "", catalogShape = "", workflowShape = "", workflowFolder = "", taskShape = "", discoveryShape = "";
        private boolean shownListening, shownEnabled, selectionEdited;
        private double contentWidth, panelWidth;
        private int ticks;

        BotsScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override public void initWidgets() {
            contentWidth = Math.clamp(Utils.getWindowWidth() / theme.scale(1) - 100, 340, 820);
            panelWidth = contentWidth - 16;

            Card hero = add(card()).expandX().minWidth(contentWidth).widget();
            headline = hero.add(theme.label("WORKER CONTROL", true, contentWidth - 24).color(GOLD)).expandX().widget();
            hero.add(theme.label("Crews are your people. Jobs are their work. Manage them independently.", contentWidth - 24)
                .color(theme.textSecondaryColor())).expandX();
            feedback = hero.add(theme.label("Opening this tab does not pause your crew.", contentWidth - 24)).expandX().widget();

            connectionPanel(add(theme.verticalList()).expandX().widget());
            navigation = add(theme.horizontalList()).expandX().widget();
            pageBody = add(theme.verticalList()).expandX().widget();

            WSection history = add(theme.section("Activity", false)).expandX().widget();
            activity = history.add(theme.label("No connection or job events yet.", contentWidth - 20)
                .color(theme.textSecondaryColor())).expandX().widget();

            buildPage();
            refresh();
        }

        private void connectionPanel(WVerticalList parent) {
            WSection section = parent.add(theme.section("Connection & private crew key", true)).expandX().minWidth(panelWidth).widget();
            connection = section.add(theme.label("", true, panelWidth - 16)).expandX().widget();
            connectionDetail = section.add(theme.label("", panelWidth - 16).color(theme.textSecondaryColor())).expandX().widget();

            WHorizontalList roleRow = section.add(theme.horizontalList()).expandX().widget();
            roleRow.add(theme.label("This account is a"));
            role = roleRow.add(theme.dropdown(bots.mode.get())).expandX().widget();
            role.action = () -> perform(() -> {
                Bots.Mode next = role.get();
                try {
                    connectionEditable();
                    bots.mode.set(next);
                    bots.save();
                } finally { role.set(bots.mode.get()); }
            }, "Role saved. Configure the connection below.");

            connectionControls = section.add(theme.verticalList()).expandX().widget();
            keyStatus = section.add(theme.label("", panelWidth - 16)).expandX().widget();
            WHorizontalList keys = section.add(theme.horizontalList()).expandX().widget();
            button(keys, "Paste key", () -> perform(() -> {
                connectionEditable();
                if (bots.mode.get() == Bots.Mode.Host) throw new IllegalStateException("Host crew keys are generated here. Paste the copied crew key on a worker instead.");
                String key = mc.keyboardHandler.getClipboard().trim();
                if (key.length() < 24) throw new IllegalStateException("The shared key must contain at least 24 characters. Copy it from the host first.");
                bots.crewKey.set(key);
                bots.save();
            }, "Shared key saved privately. It is never displayed in this panel."));
            button(keys, "Copy key", () -> perform(() -> {
                if (connectionKey().length() < 24) throw new IllegalStateException("Generate a host key first, then paste it on each worker.");
                mc.keyboardHandler.setClipboard(connectionKey());
            }, "Shared key copied. Paste it only into your own workers."));
            var generate = keys.add(theme.confirmedButton("New key", "Replace key?")).widget();
            generate.tooltip = "Stop connections first. Replacing the shared key requires updating every worker.";
            generate.action = () -> perform(() -> {
                requireHostRole();
                connectionEditable();
                bots.generateKey();
                mc.keyboardHandler.setClipboard(connectionKey());
                bots.save();
            }, "New crew key copied. Paste it into this crew's workers before reconnecting.");

            WSection endpoint = section.add(theme.section("Address & port", false)).expandX().widget();
            endpoint.add(theme.label("LAN: host IP + TCP port. Web: wss://HOST[:PORT]/v1/workers (TCP port ignored). Stop connections before editing.", panelWidth - 24)
                .color(theme.textSecondaryColor()));
            WTable fields = endpoint.add(theme.table()).expandX().widget();
            fields.add(theme.label("Host address"));
            WTextBox address = fields.add(theme.textBox(bots.ipAddress.get())).minWidth(150).expandX().widget();
            address.tooltip = bots.ipAddress.description;
            fields.row();
            fields.add(theme.label("Listen on"));
            WTextBox bind = fields.add(theme.textBox(bots.bindAddress.get())).expandX().widget();
            bind.tooltip = "Host only. 127.0.0.1 accepts this computer; a specific LAN address accepts workers on your LAN.";
            fields.row();
            fields.add(theme.label("Port"));
            WIntEdit port = fields.add(theme.intEdit(bots.serverPort.get(), 1, 65535, true)).expandX().widget();
            fields.row();
            fields.add(theme.label("Host web port (0 = off)"));
            WIntEdit webPort = fields.add(theme.intEdit(bots.webPort.get(), 0, 65535, true)).expandX().widget();
            webPort.tooltip = "Host only. Loopback WebSocket ingress for a TLS reverse proxy; suggested port 6971.";
            button(endpoint, "Apply connection settings", () -> perform(() -> {
                endpointEditable();
                if (address.get().isBlank() || bind.get().isBlank()) throw new IllegalStateException("Both addresses are required.");
                if (port.get() < 1 || port.get() > 65535) throw new IllegalStateException("Port must be between 1 and 65535.");
                bots.ipAddress.set(address.get().trim());
                bots.bindAddress.set(bind.get().trim());
                bots.serverPort.set(port.get());
                bots.webPort.set(webPort.get());
                bots.save();
            }, "Connection settings saved. Start the host or enable the worker connection."));
        }

        private void buildPage() {
            navigation.clear(); pageBody.clear(); memberStatus.clear(); memberChecks.clear(); jobLabels.clear(); taskLabels.clear();
            rosterShape = crewShape = catalogShape = taskShape = discoveryShape = "";
            roster = null; crewSelector = null; crewName = null; crewDetail = null; catalog = null; workflowLibrary = null; taskQueue = null; discovery = null;
            for (Page destination : Page.values()) if (destination != Page.History || bots.mode.get() == Bots.Mode.Host) button(navigation, destination == page ? "[ " + destination + " ]" : destination.toString(), () -> {
                page = destination; buildPage(); refresh();
            });
            if (page == Page.Workflows) {
                pageBody.add(theme.label("Lua programs make decisions and run resumable actions. Native presets configure highway duties and supply fallback. Built-ins are read-only; queued tasks keep captured code and tool profiles.", panelWidth)
                    .color(theme.textSecondaryColor()));
                if (bots.mode.get() == Bots.Mode.Host) {
                    WSection create = pageBody.add(theme.section("Create or import a Lua program", false)).expandX().widget();
                    WTable fields = create.add(theme.table()).expandX().widget();
                    fields.add(theme.label("Name")); WTextBox name = fields.add(theme.textBox("", "Program name")).expandX().widget();
                    fields.row(); fields.add(theme.label("Folder")); WTextBox folder = fields.add(theme.textBox("My Workflows")).expandX().widget();
                    button(create, "Create Lua program", () -> perform(() -> {
                        requireHostRole();
                        var program = bots.workflows().createProgram(name.get(), folder.get(), "return function(ctx)\n    -- Return one action; keep progress in ctx.state.\n    return bot.done()\nend\n");
                        mc.gui.setScreen(new WorkflowEditorScreen(theme, bots, program.id()));
                    }, "Custom program created. Editing code never starts a task."));
                    var load = create.add(theme.confirmedButton("Import definition from clipboard", "Import as a new custom definition?")).expandX().widget();
                    load.action = () -> perform(() -> {
                        requireHostRole(); String text = mc.keyboardHandler.getClipboard();
                        if (text.length() > 1_048_576) throw new IllegalArgumentException("Shared definition exceeds 1 MiB.");
                        var imported = bots.workflows().importDefinition(JsonParser.parseString(text).getAsJsonObject());
                        mc.gui.setScreen(new WorkflowEditorScreen(theme, bots, imported.id()));
                    }, "Definition imported with a new identity. Review its code, calls and profiles before queuing it.");
                }
                workflowLibrary = pageBody.add(theme.verticalList()).expandX().widget();
                if (bots.mode.get() == Bots.Mode.Host) {
                    button(pageBody,"Import captured package from clipboard",()->perform(()->{
                        String source=mc.keyboardHandler.getClipboard();if(source.length()>4*1024*1024)throw new IllegalArgumentException("Package exceeds 4 MiB");
                        JsonObject packet=JsonParser.parseString(source).getAsJsonObject();String id=java.util.UUID.randomUUID().toString();
                        String name=packet.getAsJsonObject("programs").getAsJsonObject(packet.get("entry").getAsString()).get("name").getAsString();
                        bots.operations().save(id,name,"Imported",packet);buildPage();
                    },"Portable package imported. Captured profiles travel to workers when queued."));
                    for(var item:bots.operations().list()) {
                        JsonObject record=item.getAsJsonObject();if(record.get("builtin").getAsBoolean())continue;
                        String id=record.get("id").getAsString();
                        WHorizontalList row=pageBody.add(theme.horizontalList()).expandX().widget();row.add(theme.label(record.get("folder").getAsString()+" / "+record.get("name").getAsString())).expandX();
                        button(row,"Queue",()->mc.gui.setScreen(new BotTaskScreen(theme,bots,null,"package:"+id,bots.selectedCrew())));
                        button(row,"Copy package",()->mc.keyboardHandler.setClipboard(bots.operations().get(id).get("package").toString()));
                        var remove=row.add(theme.confirmedButton("Delete","Delete captured package?")).widget();remove.action=()->perform(()->{bots.operations().delete(id);buildPage();},"Package removed; queued jobs retain their snapshots.");
                    }
                }
                workflowShape = "";
                refreshWorkflows();
                return;
            }
            if (bots.mode.get() == Bots.Mode.Worker) {
                pageBody.add(theme.label("This worker is managed by its host. Keep automatic connection enabled; job and crew controls are host-only.", panelWidth));
                crewDetail = pageBody.add(theme.label("", panelWidth)).expandX().widget();
                button(pageBody, "Inspect assignment", () -> mc.gui.setScreen(new InspectionScreen(theme, bots, bots.crew)));
                if(page==Page.Crews){pageBody.add(theme.label("Crews advertised by this authenticated host. Open jobs can be joined without asking the host operator to move this worker.",panelWidth).color(theme.textSecondaryColor()));discovery=pageBody.add(theme.verticalList()).expandX().widget();refreshDiscovery();}
                if (page == Page.Jobs) { taskQueue = pageBody.add(theme.verticalList()).expandX().widget(); refreshTasks(); }
                return;
            }
            if (page == Page.Crews) crewsPage(); else if (page == Page.History) historyPage(); else jobsPage();
        }

        private void crewsPage() {
            pageBody.add(theme.label("Create a crew once, rename it freely, then assign it work. Renaming preserves its identity and private key.", panelWidth)
                .color(theme.textSecondaryColor()));
            WHorizontalList create = pageBody.add(theme.horizontalList()).expandX().widget();
            WTextBox newName = create.add(theme.textBox("", "New crew name")).expandX().widget();
            button(create, "Create crew", () -> perform(() -> {
                String id = bots.createCrew(newName.get()); bots.selectCrew(id); newName.set(""); buildPage();
            }, "Crew created with its own private key. Add workers below, then assign a job."));
            crewControls = pageBody.add(theme.verticalList()).expandX().widget();
            rebuildCrewControls();
            WSection members = pageBody.add(theme.section("Worker roster", true)).expandX().widget();
            rosterSummary = members.add(theme.label("", panelWidth)).expandX().widget();
            roster = members.add(theme.verticalList()).expandX().widget();
            button(members, "Save roster", () -> perform(() -> bots.saveCrewWorkers(bots.selectedCrew(), Set.copyOf(selected)), "Crew roster saved. Work assignments are unchanged."));
        }

        private void rebuildCrewControls() {
            crewControls.clear();
            crewShape = bots.presets().stream().map(p -> p.name() + ":" + bots.crewLabel(p.name())).toList().toString();
            WHorizontalList choose = crewControls.add(theme.horizontalList()).expandX().widget();
            Choice[] choices = crewChoices(bots);
            if (choices.length == 0) {
                crewDetail = crewControls.add(theme.label("No crews yet. Create one above; jobs stay independent of crews.", panelWidth)).expandX().widget();
                crewSelector = null; crewName = null;
                return;
            }
            crewSelector = choose.add(theme.dropdown(choices, Arrays.stream(choices).filter(c -> c.id().equals(bots.selectedCrew())).findFirst().orElse(choices[0]))).expandX().widget();
            crewSelector.action = () -> perform(() -> { bots.selectCrew(crewSelector.get().id()); selectedCrew = ""; rebuildCrewControls(); }, "Crew selected. Other crews keep working.");
            var remove = choose.add(theme.confirmedButton("Delete crew", "Delete idle crew?")).widget();
            remove.action = () -> perform(() -> { bots.removePreset(bots.selectedCrew()); selectedCrew = ""; rebuildCrewControls(); }, "Crew removed. Its saved jobs are not deleted.");
            WHorizontalList rename = crewControls.add(theme.horizontalList()).expandX().widget();
            crewName = rename.add(theme.textBox(bots.crewLabel(bots.selectedCrew()))).expandX().widget();
            button(rename, "Rename", () -> perform(() -> { bots.renameCrew(bots.selectedCrew(), crewName.get()); rebuildCrewControls(); }, "Crew renamed. Its key, identity and worker connections are unchanged."));
            Card details = crewControls.add(card()).expandX().widget();
            crewDetail = details.add(theme.label("", panelWidth - 20)).expandX().widget();
            WHorizontalList actions = details.add(theme.horizontalList()).expandX().widget();
            button(actions, "Assign crew", () -> mc.gui.setScreen(new AssignmentScreen(theme, bots, bots.selectedCrew(), null)));
            button(actions, "Queue workflow", () -> mc.gui.setScreen(new BotTaskScreen(theme, bots, null, null, bots.selectedCrew())));
            button(actions, "Inspect current job", () -> mc.gui.setScreen(new InspectionScreen(theme, bots, bots.controlCrew())));
            button(actions, "Manage & chat", () -> mc.gui.setScreen(new ManagementScreen(theme,bots,bots.selectedCrew(),null)));
        }

        private void jobsPage() {
            pageBody.add(theme.label("Queue a workflow for one bot or its whole crew. Higher priorities interrupt at safe checkpoints; suspended highway work resumes afterward. Live detail explains waiting, recovery and return-to-anchor stages.", panelWidth)
                .color(theme.textSecondaryColor()));
            button(pageBody, "Queue workflow", () -> mc.gui.setScreen(new BotTaskScreen(theme, bots, null)));
            button(pageBody, "Create stash hunt", () -> mc.gui.setScreen(new BotTaskScreen(theme, bots, null, dev.monocle.client.systems.bots.BotStashHunt.WORKFLOW, bots.selectedCrew())));
            button(pageBody, "Inspect stash · Experimental", () -> mc.gui.setScreen(new BotTaskScreen(theme,bots,null,dev.monocle.client.systems.bots.BotStashScan.WORKFLOW,bots.selectedCrew())));
            taskQueue = pageBody.add(theme.verticalList()).expandX().widget();
            refreshTasks();
            WSection nativeJobs = pageBody.add(theme.section("Native highway jobs · saved geometry & progress", false)).expandX().widget();
            nativeJobs.add(theme.label("Unfinished highways and reusable geometry. Finished records are kept separately in History.", panelWidth - 20).color(theme.textSecondaryColor()));
            button(nativeJobs, "Create native highway job", () -> mc.gui.setScreen(new JobEditorScreen(theme, bots, null)));
            catalog = nativeJobs.add(theme.verticalList()).expandX().widget();
            refreshCatalog();
        }

        private void refreshTasks() {
            try {
                var tasks = bots.tasks().list().stream().filter(t -> !t.history()).toList();
                String shape = tasks.stream().map(t -> t.id().toString()).toList().toString();
                if (!shape.equals(taskShape) || taskQueue.cells.isEmpty()) {
                    taskShape = shape; taskQueue.clear(); taskLabels.clear();
                    if (tasks.isEmpty()) taskQueue.add(theme.label(bots.mode.get() == Bots.Mode.Host ? "No queued tasks. Choose a workflow and its target workers to begin." : "No local workflow task reported. Your host manages this queue.", panelWidth).color(theme.textSecondaryColor()));
                    for (var task : tasks) {
                        Card entry = taskQueue.add(card()).expandX().widget();
                        taskLabels.put(task.id(), entry.add(theme.label("", panelWidth - 20)).expandX().widget());
                        button(entry, "Inspect task / priorities", () -> mc.gui.setScreen(new BotTaskScreen(theme, bots, task.id())));
                    }
                }
                for (var task : tasks) taskLabels.get(task.id()).set(BotTaskScreen.describe(bots, task));
            } catch (RuntimeException e) {
                String error = "Task queue unavailable: " + failure(e);
                if (!taskShape.equals(error)) { taskShape = error; taskQueue.clear(); taskLabels.clear(); taskQueue.add(theme.label(error, panelWidth).color(RED)); }
            }
        }

        private void refreshCatalog() {
            List<Bots.JobView> jobs;
            try { jobs = bots.jobs().stream().filter(j -> !Set.of("Complete", "Cancelled").contains(j.status()) || !j.unclaimed()).toList(); }
            catch (RuntimeException e) {
                String message = "Job catalog unavailable: " + failure(e) + "\nSaved data has not been replaced. Resolve the file issue before editing or assigning jobs.";
                if (!catalogShape.equals(message)) {
                    catalogShape = message; catalog.clear(); jobLabels.clear();
                    catalog.add(theme.label(message, panelWidth).color(RED));
                }
                return;
            }
            String shape = jobs.stream().map(j -> j.id() + ":" + j.crewId() + ":" + j.unfinished()+":"+j.publicJoin()).toList().toString();
            if (!shape.equals(catalogShape) || catalog.cells.isEmpty()) {
                catalogShape = shape; catalog.clear(); jobLabels.clear();
                if (jobs.isEmpty()) catalog.add(theme.label("No saved jobs yet. Create one here, then assign any available crew to it.", panelWidth));
                for (var job : jobs) {
                    Card entry = catalog.add(card()).expandX().widget();
                    jobLabels.put(job.id(), entry.add(theme.label("", panelWidth - 20)).expandX().widget());
                    WHorizontalList actions = entry.add(theme.horizontalList()).expandX().widget();
                    button(actions, "Details / edit", () -> mc.gui.setScreen(new JobEditorScreen(theme, bots, job.id())));
                    if (job.unclaimed() && job.unfinished()) button(actions, "Assign crew", () -> mc.gui.setScreen(new AssignmentScreen(theme, bots, null, job.id())));
                    if (!job.unclaimed()) {
                        button(actions, job.publicJoin()?"Close joining":"Open joining", () -> perform(() -> bots.setJobPublic(job.id(),!job.publicJoin()), job.publicJoin()?"Public joining closed.":"Connected workers can now discover and join this job."));
                        button(actions, "Pause", () -> perform(() -> bots.pauseJob(job.id()), "Job pause requested."));
                        button(actions, "Resume", () -> perform(() -> bots.resumeJob(job.id()), "Job resume requested."));
                        button(actions, "Inspect", () -> mc.gui.setScreen(new InspectionScreen(theme, bots, bots.coordinator(job.crewId()))));
                    }
                }
            }
            for (var job : jobs) jobLabels.get(job.id()).set(jobSummary(bots, job));
        }

        private void refreshDiscovery(){
            if(discovery==null)return;var rows=bots.discoveries();String shape=rows.toString();if(shape.equals(discoveryShape))return;discoveryShape=shape;discovery.clear();
            if(rows.isEmpty()){discovery.add(theme.label("No crew directory received yet.",panelWidth).color(theme.textSecondaryColor()));return;}
            for(var row:rows){Card card=discovery.add(card()).expandX().widget();card.add(theme.label(row.name()+" · "+row.workers()+"/"+row.capacity()+" workers",panelWidth-20).color(row.publicJoin()?GREEN:theme.textColor()));
                card.add(theme.label((row.jobName().isBlank()?"No active job":row.jobName())+" · "+row.status(),panelWidth-20));
                if(row.publicJoin()&&!row.job().isBlank()&&row.workers()<row.capacity())button(card,"Join crew job",()->perform(()->bots.requestPublicJoin(row.id(),row.job()),"Join requested. If this is another crew, the worker will reconnect with its assigned key."));
            }
        }

        private void historyPage() {
            pageBody.add(theme.label("Finished workflow and highway records, unified here. Retention is configured under Config → Workers (30 days by default). Active jobs and recovery records are protected.", panelWidth).color(theme.textSecondaryColor()));
            var clear = pageBody.add(theme.confirmedButton("Clear all job history", "Permanently delete all finished job history?")).expandX().widget();
            clear.action = () -> perform(bots::clearJobHistory, "Finished history deleted from both job lists. Active jobs and recovery records were kept.");
            catalog = pageBody.add(theme.verticalList()).expandX().widget();
            refreshJobHistory();
        }

        private void refreshJobHistory() {
            try {
                var entries = bots.jobHistory();
                String shape = entries.toString();
                if (shape.equals(catalogShape) && !catalog.cells.isEmpty()) return;
                catalogShape = shape; catalog.clear();
                if (entries.isEmpty()) catalog.add(theme.label("No finished job history.").color(theme.textSecondaryColor()));
                for (var entry : entries) {
                    Card card = catalog.add(card()).expandX().widget();
                    String date = entry.finishedAt() == 0 ? "worker reconciliation pending" : java.time.Instant.ofEpochMilli(entry.finishedAt()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
                    card.add(theme.label(entry.name() + " · " + entry.status() + " · " + date, panelWidth - 20));
                    WSection detail = card.add(theme.section("Details", false)).expandX().widget();
                    detail.add(theme.label(entry.detail(), panelWidth - 40));
                    for (UUID task : entry.tasks()) button(detail, "Inspect workflow record", () -> mc.gui.setScreen(new BotTaskScreen(theme, bots, task)));
                    var delete = card.add(theme.confirmedButton("Delete history", "Permanently delete linked history?")).expandX().widget();
                    delete.action = () -> perform(() -> bots.deleteHistory(entry.id()), "Linked finished history deleted. This cannot be undone.");
                }
            } catch (RuntimeException e) {
                String error = "History unavailable: " + failure(e);
                if (!error.equals(catalogShape)) { catalogShape = error; catalog.clear(); catalog.add(theme.label(error, panelWidth).color(RED)); }
            }
        }

        private void refreshWorkflows() {
            List<BotWorkflows.Workflow> workflows;
            try { workflows = bots.workflows().all(); }
            catch (RuntimeException e) {
                String error = "Workflow library unavailable: " + failure(e);
                if (!error.equals(workflowShape)) { workflowShape = error; workflowLibrary.clear(); workflowLibrary.add(theme.label(error, panelWidth).color(RED)); }
                return;
            }
            String shape = workflowFolder + workflows.stream().map(w -> w.id() + ":" + w.folder() + ":" + w.name() + ":" + w.script().hashCode() + ":" + w.profiles() + ":" + w.dependencies() + ":" + w.steps()).toList() + bots.mode.get();
            if (shape.equals(workflowShape)) return;
            workflowShape = shape; workflowLibrary.clear();
            List<Choice> folders = new ArrayList<>(); folders.add(new Choice("", "All folders"));
            workflows.stream().map(BotWorkflows.Workflow::folder).distinct().sorted().forEach(folder -> folders.add(new Choice(folder, folder)));
            Choice selectedFolder = folders.stream().filter(f -> f.id().equals(workflowFolder)).findFirst().orElse(folders.getFirst());
            workflowFolder = selectedFolder.id();
            WDropdown<Choice> filter = workflowLibrary.add(theme.dropdown(folders.toArray(Choice[]::new), selectedFolder)).expandX().widget();
            filter.action = () -> { workflowFolder = filter.get().id(); refreshWorkflows(); };
            for (BotWorkflows.Workflow workflow : workflows) {
                if (!workflowFolder.isEmpty() && !workflow.folder().equals(workflowFolder)) continue;
                Card entry = workflowLibrary.add(card()).expandX().widget();
                entry.add(theme.label(workflow.folder() + " / " + workflow.name(), true, panelWidth - 20).color(GOLD));
                boolean program = !workflow.script().isEmpty();
                long calls = program ? workflow.dependencies().size() : workflow.steps().stream().filter(step -> step.action() == BotWorkflows.Action.Call).count();
                entry.add(theme.label((program ? "Lua program · " + workflow.profiles().size() + " shared profile(s)" : "Native preset · " + workflow.steps().size() + " capabilities/fallbacks")
                    + " · " + calls + " call(s) · " + (workflow.builtin() ? "built-in, read-only" : "custom"), panelWidth - 20));
                WHorizontalList actions = entry.add(theme.horizontalList()).expandX().widget();
                button(actions, workflow.builtin() || bots.mode.get() != Bots.Mode.Host ? "View definition" : "Edit definition", () -> mc.gui.setScreen(new WorkflowEditorScreen(theme, bots, workflow.id())));
                if (bots.mode.get() == Bots.Mode.Host && (program || !bots.workflows().compile(workflow.id()).get("duty").getAsString().equals("Supply")))
                    button(actions, "Queue", () -> mc.gui.setScreen(new BotTaskScreen(theme, bots, null, workflow.id(), null)));
                if (bots.mode.get() == Bots.Mode.Host) button(actions, "Duplicate", () -> perform(() -> {
                    BotWorkflows.Workflow copy = bots.workflows().duplicate(workflow.id(), workflow.name().substring(0, Math.min(42, workflow.name().length())) + " copy", workflow.folder());
                    mc.gui.setScreen(new WorkflowEditorScreen(theme, bots, copy.id()));
                }, "Custom workflow created. Edit its name, folder and steps, then Save."));
            }
        }

        private void refreshConnectionControls() {
            boolean roleChanged = shownRole != bots.mode.get();
            shownRole = bots.mode.get(); shownListening = bots.isHost(); shownEnabled = bots.isActive();
            connectionControls.clear();
            role.set(shownRole);
            if (roleChanged) buildPage();
            if (shownRole == Bots.Mode.Host) {
                WHorizontalList tpy = connectionControls.add(theme.horizontalList()).expandX().widget();
                WCheckbox autoTpy = tpy.add(theme.checkbox(bots.autoTpy.get())).widget();
                tpy.add(theme.label("Auto TPY same-crew requests"));
                autoTpy.tooltip = "Accept an observed /tpa from a worker in this crew after 10 ticks. Workers cannot enable this policy.";
                autoTpy.action = () -> { bots.autoTpy.set(autoTpy.checked); bots.save(); };
                if (shownListening) {
                    var stop = connectionControls.add(theme.confirmedButton("Stop host", "Stop connections?")).expandX().widget();
                    stop.action = () -> perform(() -> { noJob(); bots.close(); }, "Host stopped. Workers will keep retrying until you start it again.");
                } else button(connectionControls, "Start host", () -> perform(bots::startHost, "Host start requested. Watch the live connection status above."));
            } else {
                WHorizontalList auto = connectionControls.add(theme.horizontalList()).expandX().widget();
                WCheckbox enabled = auto.add(theme.checkbox(bots.isActive())).widget();
                auto.add(theme.label("Connect automatically"));
                enabled.action = () -> perform(() -> {
                    try {
                        if (enabled.checked) bots.enable();
                        else { noJob(); bots.disable(); }
                        bots.save();
                    } finally { enabled.checked = bots.isActive(); }
                }, "Worker connection preference saved.");
                if (shownEnabled) {
                    var disconnect = connectionControls.add(theme.confirmedButton("Disconnect from host", "Disconnect this worker?")).expandX().widget();
                    disconnect.tooltip = "Disconnect now and stop automatic reconnect. Any host-owned assignment is preserved for a later reconnect.";
                    disconnect.action = () -> perform(() -> { bots.disable(); bots.save(); }, "Worker disconnected. Address and port can now be changed below.");
                }
                WHorizontalList jobs = connectionControls.add(theme.horizontalList()).expandX().widget();
                WCheckbox accept = jobs.add(theme.checkbox(bots.acceptCrew.get())).widget();
                jobs.add(theme.label("Accept crew assignments"));
                accept.action = () -> { bots.acceptCrew.set(accept.checked); bots.save(); };
                connectionControls.add(theme.label("No Start button needed. Enabled workers reconnect automatically; the host starts jobs.", panelWidth - 16)
                    .color(theme.textSecondaryColor()));
            }
        }

        private void refresh() {
            if (shownRole != bots.mode.get() || shownListening != bots.isHost() || shownEnabled != bots.isActive()) refreshConnectionControls();
            if (bots.presets().isEmpty()) selected.clear();
            if (!selectedCrew.equals(bots.selectedCrew())) {
                selectedCrew = bots.selectedCrew();
                bots.presets().stream().filter(p -> p.name().equals(selectedCrew)).findFirst().ifPresent(p -> {
                    selected.clear(); selected.addAll(p.workers());
                    selectionEdited = !selected.isEmpty();
                });
                if (bots.mode.get() == Bots.Mode.Host && page == Page.Crews) rebuildCrewControls();
            }
            var job = bots.mode.get() == Bots.Mode.Host && bots.presets().isEmpty()
                ? new SwarmCrew.JobView("No crew", "idle", "Create a crew above, then assign it a saved job.", "", "", "", false, false, false)
                : bots.controlCrew().inspect();
            var members = bots.allMembers();
            headline.set("WORKER CONTROL  ·  " + bots.mode.get() + "  ·  " + members.stream().filter(SwarmCrew.MemberView::connected).count() + " online");
            connection.set(bots.connectionStatus());
            connection.color(bots.isHost() || bots.isWorker() ? GREEN : GOLD);
            connectionDetail.set(bots.connectionDetail());
            keyStatus.set(connectionKey().length() >= 24 ? (bots.mode.get() == Bots.Mode.Host ? bots.crewLabel(bots.selectedCrew()) + " crew key" : "Shared key") + " ready · kept private" : "Shared key missing · generate on host, paste on workers");
            keyStatus.color(connectionKey().length() >= 24 ? theme.textSecondaryColor() : GOLD);
            if (page == Page.Crews && bots.mode.get() == Bots.Mode.Host) {
                String next = bots.presets().stream().map(p -> p.name() + ":" + bots.crewLabel(p.name())).toList().toString();
                if (!next.equals(crewShape)) rebuildCrewControls();
                crewDetail.set((selectedCrew.isEmpty() ? "No crew" : bots.crewLabel(selectedCrew)) + " · " + job.phase() + "\n" + job.detail()
                    + (job.origin().isBlank() ? "" : "\n" + job.origin()) + (job.supply().isBlank() ? "" : "\n" + job.supply())
                    + (job.assigned() ? "\nVerification: " + (bots.controlCrew().localAssigned() && !bots.controlCrew().detachedSupply()
                        ? "participating host confirms the paving" : bots.controlCrew().detachedSupply() ? "worker reports while the host resupplies" : "worker reports; host coordinates remotely") : ""));
                refreshRoster(members, job);
            } else if (page == Page.Jobs && bots.mode.get() == Bots.Mode.Host) { refreshTasks(); refreshCatalog(); }
            else if(page==Page.Crews&&bots.mode.get()==Bots.Mode.Worker)refreshDiscovery();
            else if (page == Page.History && bots.mode.get() == Bots.Mode.Host) refreshJobHistory();
            else if (page == Page.Workflows) refreshWorkflows();
            else if (crewDetail != null) {
                crewDetail.set(job.name() + " · " + job.phase() + "\n" + job.detail() + "\n" + job.origin() + "\n" + job.supply());
                if (taskQueue != null) refreshTasks();
            }
            List<Bots.Event> events = bots.events();
            StringBuilder log = new StringBuilder();
            for (int i = events.size() - 1; i >= Math.max(0, events.size() - 8); i--) {
                var event = events.get(i);
                if (!log.isEmpty()) log.append('\n');
                log.append(event.time()).append(" · ").append(event.level()).append(" · ").append(event.message());
            }
            activity.set(log.isEmpty() ? "No connection or job events yet." : log.toString());
        }

        private void refreshRoster(List<SwarmCrew.MemberView> members, SwarmCrew.JobView job) {
            UUID local = mc.player == null ? mc.getUser().getProfileId() : mc.player.getUUID();
            String shape = bots.mode.get() + ":" + local + ":" + selectedCrew + ":" + job.assigned() + ":" + job.recovery()
                + members.stream().map(m -> m.id() + ":" + m.name() + ":" + bots.workerCrew(m.id()) + ":" + m.available()).toList();
            if (!shape.equals(rosterShape)) {
                rosterShape = shape;
                roster.clear(); memberStatus.clear(); memberChecks.clear();
                for (var member : members) {
                    WVerticalList memberBox = roster.add(theme.verticalList()).expandX().widget();
                    WHorizontalList row = memberBox.add(theme.horizontalList()).expandX().widget();
                    boolean self = member.id().equals(local);
                    if (!self && bots.mode.get() == Bots.Mode.Host && !job.assigned() && !job.recovery()) {
                        if (!selectionEdited && member.available() && selectedCrew.equals(bots.workerCrew(member.id()))) selected.add(member.id());
                        WCheckbox include = row.add(theme.checkbox(selected.contains(member.id()))).widget();
                        include.tooltip = "Save this account in the crew's roster. Host participation is chosen when assigning a job.";
                        include.action = () -> {
                            selectionEdited = true;
                            if (include.checked) selected.add(member.id()); else selected.remove(member.id());
                        };
                        memberChecks.put(member.id(), include);
                    }
                    row.add(theme.label(member.name() + (self ? " · this account" : "\n" + bots.crewLabel(bots.workerCrew(member.id()))), Math.min(180, contentWidth / 3))).minWidth(120);
                    WContainer statusParent = contentWidth >= 600 ? row : memberBox;
                    WLabel status = statusParent.add(theme.label("", contentWidth >= 600 ? contentWidth - 370 : contentWidth - 30)).expandX().widget();
                    memberStatus.put(member.id(), status);
                    if(!self&&bots.mode.get()==Bots.Mode.Host)button(memberBox,"Manage & chat",()->mc.gui.setScreen(new ManagementScreen(theme,bots,bots.workerCrew(member.id()),member.id())));
                    button(memberBox, "Copy diagnostics", () -> {
                        var snapshot = bots.coordinator(bots.workerCrew(member.id())).workerDiagnostics(member.id());
                        mc.keyboardHandler.setClipboard(snapshot.toString());
                        feedback.set(snapshot.isEmpty() ? "No diagnostics from this worker yet; update it to 0.7.12." : "Read-only worker diagnostics copied; no connection keys included.");
                    });
                    if (!self && bots.mode.get() == Bots.Mode.Host && !selectedCrew.equals(bots.workerCrew(member.id()))) {
                        WButton move = button(row, "Move here", () -> perform(() -> bots.reassignWorker(member.id(), bots.selectedCrew()), "Worker reassignment sent. Waiting for its reconnect with this crew's key."));
                        move.tooltip = "Move this idle worker to " + bots.crewLabel(selectedCrew) + ". Once it reconnects, it can join this crew's active job.";
                    } else if (!self && bots.mode.get() == Bots.Mode.Host && job.assigned() && member.available()) {
                        WButton join = button(row, "Join running job", () -> perform(() -> bots.addWorker(bots.selectedCrew(), member.id()), "Worker admission requested. The crew will finish supply recovery, then safely regroup."));
                        join.tooltip = "The worker must be nearby, on the same floor and ready. Existing work pauses briefly while lanes are redistributed.";
                    }
                }
                if (members.isEmpty()) roster.add(theme.label("No workers discovered yet. Enable automatic connection on each worker and use the same shared key.", contentWidth - 20));
            }
            for (var member : members) {
                WLabel status = memberStatus.get(member.id());
                String lane = member.lane().isBlank() ? "" : " · " + member.lane();
                status.set((member.connected() ? member.phase() : "Disconnected") + lane + "\n" + member.status());
                status.color(!member.connected() ? RED : member.available() ? GREEN : theme.textColor());
            }
            long absent = selected.stream().filter(id -> members.stream().noneMatch(m -> m.id().equals(id))).count();
            rosterSummary.set(job.assigned() ? "Workers move independently within a five-row window. Only the lead limit or a shared safety hold makes them wait; supplies remain protected."
                : bots.mode.get() == Bots.Mode.Host ? selected.size() + " worker(s) selected for this crew" + (absent == 0 ? "" : " · " + absent + " selected account(s) offline")
                : "Your host owns job controls. Connection and readiness update automatically.");
        }

        private void noJob() {
            if (bots.mode.get() == Bots.Mode.Host) {
                for (var preset : bots.presets()) {
                    var job = bots.coordinator(preset.name()).inspect();
                    if (job.assigned() || job.recovery()) throw new IllegalStateException("Inspect and release the job for " + bots.crewLabel(preset.name()) + " first. Other crews share this connection.");
                }
            } else {
                var job = bots.crew.inspect();
                if (job.assigned() || job.recovery()) throw new IllegalStateException("Ask the host to Inspect and End the current job first. This keeps supply recovery protection intact.");
            }
        }

        private void requireHostRole() {
            if (bots.mode.get() != Bots.Mode.Host) throw new IllegalStateException("Crews are managed by the host. This worker only needs automatic connection enabled.");
        }

        private String connectionKey() { return bots.mode.get() == Bots.Mode.Host ? bots.crewKey(bots.selectedCrew()) : bots.crewKey.get(); }

        private void connectionEditable() {
            noJob();
            if (bots.isHost() || bots.mode.get() == Bots.Mode.Worker && bots.isActive())
                throw new IllegalStateException("Stop the host or turn off automatic worker connection before changing the role, key or address.");
        }

        private void endpointEditable() {
            if (bots.mode.get() == Bots.Mode.Worker) {
                if (bots.isActive()) throw new IllegalStateException("Disconnect this worker before changing its host address or port.");
                return; // A disconnected assignment may reconnect to the same host at its new endpoint.
            }
            connectionEditable();
        }

        private void perform(Runnable action, String success) {
            try { action.run(); feedback.set(success); feedback.color(GREEN); }
            catch (RuntimeException e) { feedback.set(e.getMessage() == null ? "The action could not be completed." : e.getMessage()); feedback.color(RED); }
            refresh();
        }

        @Override public void tick() { super.tick(); if (++ticks % 5 == 0) refresh(); }
        @Override protected void onClosed() { bots.save(); }

        private WButton button(WContainer parent, String text, Runnable action) {
            WButton button = parent.add(theme.button(text)).widget(); button.action = action; return button;
        }

        private Card card() { Card card = new Card(); card.theme = theme; return card; }
    }

    private static Choice[] crewChoices(Bots bots) {
        return bots.presets().stream().map(p -> new Choice(p.name(), bots.crewLabel(p.name()))).toArray(Choice[]::new);
    }

    private static String failure(RuntimeException error) {
        return error.getMessage() == null ? "The operation could not be completed." : error.getMessage();
    }

    private static Choice[] workChoices(Bots bots, String capturedId) {
        List<Choice> choices = new ArrayList<>();
        for (var workflow : bots.workflows().all()) {
            if (workflow.script().isEmpty() && !bots.workflows().compile(workflow.id()).get("duty").getAsString().equals("Supply"))
                choices.add(new Choice(workflow.id(), workflow.folder() + " / " + workflow.name()));
        }
        if (capturedId != null && !capturedId.isEmpty() && choices.stream().noneMatch(c -> c.id().equals(capturedId)))
            choices.add(new Choice(capturedId, "Captured workflow (definition unavailable)"));
        return choices.toArray(Choice[]::new);
    }

    private static String jobSummary(Bots bots, Bots.JobView job) {
        String layout = job.layout().get("width").getAsInt() + " wide × " + job.layout().get("height").getAsInt() + " high · "
            + job.layout().get("heading").getAsString();
        return job.name() + " · " + job.status() + "\nWorkflow " + job.workflowName() + "\n"
            + job.progress() + " / " + job.length() + " road blocks · " + (job.unclaimed() ? "No crew assigned" : bots.crewLabel(job.crewId()))
            + "\nOrigin " + job.origin().toShortString() + " · " + layout + "\n" + job.scope().replace("\n", " · ")
            + "\nWork sharing: " + SwarmCrew.workSharing(job.layout())
            + "\nVerification: " + (job.unclaimed() ? "host-confirmed when the host participates; worker reports otherwise"
                : job.includeHost() ? bots.presets().stream().anyMatch(p -> p.name().equals(job.crewId())) && bots.coordinator(job.crewId()).detachedSupply()
                    ? "worker reports while the host resupplies" : "participating host confirms the paving"
                : "worker reports; host coordinates remotely")
            + "\n" + job.detail();
    }

    private static class AssignmentScreen extends WindowScreen {
        private final Bots bots;
        private final String initialCrew;
        private final UUID initialJob;
        private WLabel feedback, preview;
        private WDropdown<Choice> crews, jobs;
        private WVerticalList workerWorkflows;
        private Choice[] workflowChoices;
        private final Map<UUID, String> overrides = new LinkedHashMap<>();

        AssignmentScreen(GuiTheme theme, Bots bots, String crewId, UUID jobId) {
            super(theme, "Assign crew to job"); this.bots = bots; initialCrew = crewId; initialJob = jobId;
        }

        @Override public void initWidgets() {
            double width = Math.clamp(Utils.getWindowWidth() / theme.scale(1) - 100, 320, 580);
            add(theme.label("ASSIGN WORK", true, width).color(GOLD));
            feedback = add(theme.label("Choose an available crew and an unfinished, unclaimed job. Assignment uses the job's saved origin and layout.", width)).expandX().widget();
            Choice[] availableCrews, availableJobs;
            try {
                availableCrews = Arrays.stream(crewChoices(bots)).filter(c -> initialCrew == null || c.id().equals(initialCrew))
                    .filter(c -> { var state = bots.coordinator(c.id()).inspect(); return !state.assigned() && !state.recovery(); }).toArray(Choice[]::new);
                availableJobs = bots.jobs().stream().filter(Bots.JobView::unclaimed).filter(Bots.JobView::unfinished)
                    .filter(j -> initialJob == null || j.id().equals(initialJob)).map(j -> new Choice(j.id().toString(), j.name())).toArray(Choice[]::new);
                List<Choice> choices = new ArrayList<>(); choices.add(new Choice("", "Use captured job workflow"));
                Collections.addAll(choices, workChoices(bots, null));
                workflowChoices = choices.toArray(Choice[]::new);
            } catch (RuntimeException e) {
                feedback.set("Cannot load assignments: " + failure(e)); feedback.color(RED);
                add(theme.button("Back")).widget().action = this::onClose;
                return;
            }
            if (bots.mode.get() != Bots.Mode.Host || availableCrews.length == 0 || availableJobs.length == 0) {
                feedback.set(bots.mode.get() != Bots.Mode.Host ? "Only the host can assign jobs."
                    : availableCrews.length == 0 ? "No available crews. Create a crew in Crews, or inspect and release its existing job first."
                    : "No unfinished, unclaimed jobs are available. Create one in Jobs, or release another crew's unfinished job.");
                add(theme.button("Back")).widget().action = this::onClose;
                return;
            }
            WTable fields = add(theme.table()).expandX().widget();
            fields.add(theme.label("Crew"));
            crews = fields.add(theme.dropdown(availableCrews, availableCrews[0])).expandX().widget(); fields.row();
            fields.add(theme.label("Job"));
            jobs = fields.add(theme.dropdown(availableJobs, availableJobs[0])).expandX().widget();
            WHorizontalList participation = add(theme.horizontalList()).expandX().widget();
            WCheckbox includeHost = participation.add(theme.checkbox(!bots.crew.localAssigned())).widget();
            participation.add(theme.label("Include this host in the build"));
            includeHost.tooltip = "The host can participate in one crew while coordinating other worker-only crews.";
            preview = add(theme.label("", width)).expandX().widget();
            WSection duties = add(theme.section("Per-worker workflows", true)).expandX().widget();
            duties.add(theme.label("Default: everyone uses the job's captured workflow. For mixed duties, choose Excavation crew on one account and Paving crew on others. All job capabilities must remain covered.", width).color(theme.textSecondaryColor()));
            workerWorkflows = duties.add(theme.verticalList()).expandX().widget();
            jobs.action = () -> {
                try {
                    int rosterSize = bots.presets().stream().filter(p -> p.name().equals(crews.get().id())).mapToInt(p -> p.workers().size()).findFirst().orElse(0);
                    preview.set(rosterSize + " worker(s) in saved crew roster\n" + jobSummary(bots, bots.job(UUID.fromString(jobs.get().id()))));
                    refreshWorkerWorkflows(includeHost.checked, width);
                } catch (RuntimeException e) { preview.set("Job preview unavailable: " + failure(e)); preview.color(RED); }
            };
            crews.action = jobs.action;
            includeHost.action = jobs.action;
            jobs.action.run();
            add(theme.label("Save the crew's roster first and place its members near the saved job origin (or the verified frontier when resuming). Assignment positions them into their lanes automatically.", width).color(theme.textSecondaryColor()));
            var assign = add(theme.button("Assign crew & start positioning")).expandX().widget();
            assign.action = () -> {
                try {
                    bots.assignJob(UUID.fromString(jobs.get().id()), crews.get().id(), includeHost.checked, Map.copyOf(overrides));
                    feedback.set("Job assigned. The crew will position automatically; inspect its live status from Crews or Jobs."); feedback.color(GREEN);
                    assign.set("Assigned"); assign.action = null;
                } catch (RuntimeException e) { feedback.set(e.getMessage() == null ? "Unable to assign this job." : e.getMessage()); feedback.color(RED); }
            };
            add(theme.button("Back to Workers")).expandX().widget().action = this::onClose;
        }

        private void refreshWorkerWorkflows(boolean includeHost, double width) {
            Set<UUID> participants = new LinkedHashSet<>(bots.presets().stream().filter(p -> p.name().equals(crews.get().id())).findFirst().orElseThrow().workers());
            UUID local = mc.player == null ? mc.getUser().getProfileId() : mc.player.getUUID();
            if (includeHost) participants.add(local);
            overrides.keySet().retainAll(participants);
            workerWorkflows.clear();
            List<SwarmCrew.MemberView> members = bots.allMembers();
            for (UUID id : participants) {
                String name = id.equals(local) ? mc.getUser().getName() + " · host" : members.stream().filter(m -> m.id().equals(id)).map(SwarmCrew.MemberView::name).findFirst().orElse(id.toString());
                WContainer row = width >= 500 ? workerWorkflows.add(theme.horizontalList()).expandX().widget() : workerWorkflows.add(theme.verticalList()).expandX().widget();
                row.add(theme.label(name, width >= 500 ? 170 : width - 20)).minWidth(120);
                String value = overrides.getOrDefault(id, "");
                Choice selected = Arrays.stream(workflowChoices).filter(c -> c.id().equals(value)).findFirst().orElse(new Choice(value, "Unavailable workflow · choose another"));
                WDropdown<Choice> workflow = row.add(theme.dropdown(workflowChoices, selected)).expandX().widget();
                workflow.action = () -> {
                    if (workflow.get().id().isEmpty()) overrides.remove(id); else overrides.put(id, workflow.get().id());
                };
            }
        }
    }

    private static class JobEditorScreen extends WindowScreen {
        private final Bots bots;
        private UUID id;
        private WLabel details, feedback;
        private WTextBox name;
        private WIntEdit length;
        private WDropdown<Choice> workflow;
        private WDropdown<SwarmCrew.WorkSharing> sharing;
        private WVerticalList actions;
        private String shownState = "";
        private int ticks;

        JobEditorScreen(GuiTheme theme, Bots bots, UUID id) { super(theme, "Workflow job"); this.bots = bots; this.id = id; }

        @Override public void initWidgets() {
            double width = Math.clamp(Utils.getWindowWidth() / theme.scale(1) - 100, 320, 580);
            add(theme.label(id == null ? "CREATE JOB" : "WORKFLOW JOB", true, width).color(GOLD));
            Bots.JobView existing;
            Choice[] workflows;
            try {
                existing = id == null ? null : bots.job(id);
                workflows = workChoices(bots, existing == null ? null : existing.workflowId());
                if (workflows.length == 0) throw new IllegalStateException("Create a workflow with an Excavating or Paving action first.");
            }
            catch (RuntimeException e) {
                add(theme.label("Job unavailable: " + failure(e) + "\nSaved data has not been changed. Resolve the issue, then reopen this editor.", width).color(RED));
                add(theme.button("Back to Workers")).expandX().widget().action = this::onClose;
                return;
            }
            WTable fields = add(theme.table()).expandX().widget();
            fields.add(theme.label("Job name"));
            name = fields.add(theme.textBox(existing == null ? "Highway job" : existing.name())).minWidth(180).expandX().widget(); fields.row();
            fields.add(theme.label("Workflow"));
            String selectedWorkflow = existing == null ? BotWorkflows.DEFAULT_ID : existing.workflowId();
            workflow = fields.add(theme.dropdown(workflows, Arrays.stream(workflows).filter(w -> w.id().equals(selectedWorkflow)).findFirst().orElse(workflows[0]))).expandX().widget(); fields.row();
            fields.add(theme.label("Road length"));
            length = fields.add(theme.intEdit(existing == null ? bots.sectionLength.get() : existing.length(), 16, HighwayJobs.MAX_LENGTH, true)).expandX().widget();
            fields.row(); fields.add(theme.label("Work sharing"));
            sharing = fields.add(theme.dropdown(SwarmCrew.WorkSharing.values(), existing == null ? SwarmCrew.WorkSharing.Lanes : SwarmCrew.workSharing(existing.layout()))).expandX().widget();
            sharing.tooltip = "Lanes: separate columns and a five-row work window. Roles: dedicated excavators lead 5–16 rows from the center while pavers form lanes behind them. Break Order: shared center lane, different mining orders and at most one row between builders.";
            details = add(theme.label("", width)).expandX().widget();
            details.tooltip = "Click to copy the saved job details, including its origin.";
            details.action = () -> perform(() -> {
                if (id == null) throw new IllegalStateException("Create the job before copying its saved details.");
                mc.keyboardHandler.setClipboard(jobSummary(bots, bots.job(id)));
            }, "Job details copied.");
            feedback = add(theme.label(existing == null ? "Stand at the intended road center. Creating a job captures this workflow, your position and Highway Builder geometry; it does not assign workers or start building."
                : !existing.unclaimed() ? "Assigned jobs are read-only. Inspect and release the crew before editing this job."
                : "This job is unassigned. Edit its name or assign a crew; origin and layout stay fixed after progress begins.", width).color(theme.textSecondaryColor())).expandX().widget();
            WHorizontalList editing = add(theme.horizontalList()).expandX().widget();
            var save = editing.add(theme.button(existing == null ? "Create job" : "Save edits")).widget();
            save.action = () -> perform(() -> {
                if (id == null) { id = bots.createHighwayJob(name.get(), length.get(), workflow.get().id(), sharing.get()); save.set("Save edits"); }
                else bots.updateHighwayJob(id, name.get(), length.get(), workflow.get().id(), sharing.get());
            }, "Job saved independently of crews. Assign a crew when ready.");
            editing.add(theme.button("Geometry & materials")).widget().action = () -> perform(() -> {
                if (bots.crew.localAssigned()) throw new IllegalStateException("This host is currently building. Release its crew before editing its local highway layout.");
                mc.gui.setScreen(new HighwayBuilderScreen(theme, Modules.get().get(HighwayBuilder.class)));
            }, "Edit highway geometry/materials, then recapture them here. Work actions come from the selected workflow.");
            var capture = add(theme.confirmedButton("Capture current origin & layout", "Replace saved origin & layout?")).expandX().widget();
            capture.action = () -> perform(() -> {
                if (id == null) throw new IllegalStateException("Create the job first. Creation captures the current origin and layout automatically.");
                bots.recaptureHighwayJob(id);
            }, "Saved origin and layout replaced with this account's current position and Highway Builder setup.");
            var refreshWorkflow = add(theme.confirmedButton("Refresh workflow snapshot", "Capture updated workflow definition?")).expandX().widget();
            refreshWorkflow.action = () -> perform(() -> {
                if (id == null) throw new IllegalStateException("Create the job first. Creation captures its workflow automatically.");
                bots.refreshJobWorkflow(id);
            }, "Job workflow refreshed from its saved definition. The origin and highway geometry are unchanged.");
            add(theme.label("Workflows control excavating, paving and supply fallback; Highway Builder supplies geometry/materials. Origin, layout, workflow and length changes require zero progress. Names can be edited while unassigned. Existing progress is never silently discarded.", width).color(theme.textSecondaryColor()));
            actions = add(theme.verticalList()).expandX().widget();
            add(theme.button("Back to Workers")).expandX().widget().action = this::onClose;
            refresh();
        }

        private void refresh() {
            if (id == null) { details.set("Not yet saved. Stand at the intended road center and configure Highway Builder before creating this job."); return; }
            Bots.JobView job;
            try { job = bots.job(id); } catch (RuntimeException e) { details.set("Job unavailable: " + failure(e)); actions.clear(); shownState = ""; return; }
            details.set(jobSummary(bots, job));
            String state = job.crewId() + ":" + job.unfinished();
            if (state.equals(shownState)) return;
            shownState = state; actions.clear();
            if (bots.mode.get() != Bots.Mode.Host) return;
            if (job.unclaimed() && job.unfinished()) actions.add(theme.button("Assign crew")).expandX().widget().action = () -> mc.gui.setScreen(new AssignmentScreen(theme, bots, null, id));
            if (!job.unclaimed()) {
                actions.add(theme.button("Inspect crew & supply recovery")).expandX().widget().action = () -> mc.gui.setScreen(new InspectionScreen(theme, bots, bots.coordinator(job.crewId())));
            } else {
                if (job.unfinished()) {
                    var cancel = actions.add(theme.confirmedButton("Cancel unfinished job", "Cancel permanently?")).expandX().widget();
                    cancel.action = () -> perform(() -> bots.cancelJob(id), "Job cancelled. Its record remains until explicitly deleted.");
                }
                var delete = actions.add(theme.confirmedButton("Delete job record", "Delete saved record?")).expandX().widget();
                delete.action = () -> perform(() -> { bots.deleteJob(id); }, "Job record deleted. Crews are unchanged.");
            }
        }

        private void perform(Runnable action, String success) {
            try { action.run(); feedback.set(success); feedback.color(GREEN); }
            catch (RuntimeException e) { feedback.set(e.getMessage() == null ? "Unable to update this job." : e.getMessage()); feedback.color(RED); }
            refresh();
        }
        @Override public void tick() { super.tick(); if (actions != null && ++ticks % 5 == 0) refresh(); }
    }

    /** Same authenticated chat channel as the standalone dashboard; no launcher dependency. */
    private static class ManagementScreen extends WindowScreen {
        private final Bots bots;private final String crewId;private final UUID worker;private WLabel feed,feedback;private int ticks;
        ManagementScreen(GuiTheme theme,Bots bots,String crewId,UUID worker) {super(theme,worker==null?"Manage crew":"Manage worker");this.bots=bots;this.crewId=crewId;this.worker=worker;}
        @Override public void initWidgets() {
            double width=Math.clamp(Utils.getWindowWidth()/theme.scale(1)-100,320,640);
            add(theme.label(bots.crewLabel(crewId)+(worker==null?" · all workers":" · "+bots.allMembers().stream().filter(m->m.id().equals(worker)).map(SwarmCrew.MemberView::name).findFirst().orElse(worker.toString())),true,width)).expandX();
            add(theme.label("Game chat · session-only · slash commands go to the server. Crew messages are sent from every connected worker.",width).color(theme.textSecondaryColor())).expandX();
            feed=add(theme.label("Waiting for chat",width)).expandX().widget();
            WHorizontalList compose=add(theme.horizontalList()).expandX().widget();WTextBox text=compose.add(theme.textBox("","Message or /server command")).expandX().widget();
            var send=compose.add(theme.confirmedButton("Send",worker==null?"Send from whole crew?":"Send message?")).widget();
            feedback=add(theme.label("",width)).expandX().widget();
            send.action=()->{try {bots.sendChat(crewId,worker,text.get());text.set("");feedback.set("Sent to connected clients; not a server acknowledgement.");}catch(RuntimeException e){feedback.set(failure(e));}};
            add(theme.button("Manage current job & recovery")).widget().action=()->mc.gui.setScreen(new InspectionScreen(theme,bots,bots.coordinator(crewId)));
            add(theme.button("Queue workflow")).widget().action=()->mc.gui.setScreen(new BotTaskScreen(theme,bots,null,null,crewId));
            add(theme.button("Back")).widget().action=this::onClose;refreshChat();
        }
        private void refreshChat() {
            var raw=bots.chatFeed();
            var rows=(worker==null?dev.monocle.coordinator.BotChat.grouped(raw):raw).asList().stream().map(com.google.gson.JsonElement::getAsJsonObject).filter(r->worker==null?r.get("crew").getAsString().equals(crewId):r.get("worker").getAsString().equals(worker.toString())).toList();
            StringBuilder lines=new StringBuilder();for(var row:rows.subList(Math.max(0,rows.size()-40),rows.size()))lines.append('[').append(row.get("name").getAsString()).append(" · ").append(row.get("direction").getAsString()).append("] ").append(row.get("text").getAsString()).append('\n');
            feed.set(lines.isEmpty()?"No chat captured yet.":lines.toString());
        }
        @Override public void tick() {super.tick();if(++ticks%20==0)refreshChat();}
    }

    private static class InspectionScreen extends WindowScreen {
        private final Bots bots;
        private final SwarmCrew crew;
        private final String crewId;
        private final UUID catalogId;
        private final String catalogError;
        private WLabel details, feedback;
        private int ticks;

        InspectionScreen(GuiTheme theme, Bots bots, SwarmCrew crew) {
            super(theme, "Inspect crew & recovery"); this.bots = bots; this.crew = crew;
            crewId = bots.mode.get() == Bots.Mode.Host ? bots.presets().stream().map(Bots.CrewPreset::name).filter(id -> bots.coordinator(id) == crew).findFirst().orElse("") : "";
            UUID found = null; String error = "";
            try { found = crewId.isEmpty() ? null : bots.jobs().stream().filter(j -> crewId.equals(j.crewId())).map(Bots.JobView::id).findFirst().orElse(null); }
            catch (RuntimeException e) { error = "Job catalog unavailable: " + failure(e); }
            catalogId = found; catalogError = error;
        }

        @Override public void initWidgets() {
            double reportWidth = Math.clamp(Utils.getWindowWidth() / theme.scale(1) - 100, 320, 580);
            add(theme.label("RECOVERY DESK", true, reportWidth).color(GOLD)).expandX();
            add(theme.label("This is the inspection view. Review the live blocker and locations below; recover any containers or drops before ending a job.", reportWidth)).expandX();
            details = add(theme.label("", reportWidth)).expandX().widget();
            feedback = add(theme.label("Opening inspection does not clear protection or resume the crew.", reportWidth).color(theme.textSecondaryColor())).expandX().widget();
            WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
            var copy = actions.add(theme.button("Copy inspection report")).widget();
            copy.action = () -> { mc.keyboardHandler.setClipboard(report()); feedback.set("Inspection report copied. It contains no connection key."); };
            if (bots.mode.get() == Bots.Mode.Host && catalogError.isEmpty()) {
                var resume = actions.add(theme.button("Resume crew")).widget();
                resume.action = () -> perform(() -> { if (catalogId == null) crew.resume(); else bots.resumeJob(catalogId); }, "Resume requested. Any remaining blocker is shown above.");
                var supplies = add(theme.confirmedButton("Resolve item transfer", "Ground and inventories inspected · Clear transfer hold")).expandX().widget();
                supplies.action = () -> perform(crew::resolveInventoryTransfer, "Transfer hold cleared after inspection. Unknown drops are not resent; workers can return to their job.");
            }
            if (bots.mode.get() == Bots.Mode.Host && catalogError.isEmpty()) {
                add(theme.horizontalSeparator("Finish or release safely")).expandX();
                add(theme.label("Check supply locations and collect anything left behind before confirming. Release keeps verified progress for another crew; Cancel ends the job permanently. Supply recovery and worker acknowledgments must finish before the crew is released. Offline workers are cleared when they reconnect.", reportWidth).color(GOLD)).expandX();
                if (catalogId != null) {
                    var release = add(theme.confirmedButton("Release crew · keep job", "Supplies checked · Release crew")).expandX().widget();
                    release.action = () -> perform(() -> bots.releaseJob(catalogId), "Release requested. The job keeps its verified progress and becomes available after all worker confirmations.");
                    var cancel = add(theme.confirmedButton("Cancel this job", "Supplies checked · Cancel job")).expandX().widget();
                    cancel.action = () -> perform(() -> bots.cancelJob(catalogId), "Cancellation requested. Worker cleanup and acknowledgments must finish before the crew is free.");
                } else {
                    var end = add(theme.confirmedButton("Clear legacy recovery", "Supplies checked · End execution")).expandX().widget();
                    end.action = () -> perform(() -> bots.endCrewExecution(crewId), "Legacy execution ended. Waiting for any remaining worker cleanup confirmations.");
                }
            } else add(theme.label(catalogError.isEmpty() ? "Only the host can resume or end a crew job. Keep this worker connected so it can receive the host's recovery decision."
                : catalogError + "\nRecovery details remain readable. Resolve the catalog issue and reopen this view before changing job state.", reportWidth).color(GOLD)).expandX();
            var back = add(theme.button("Back to Workers")).expandX().widget();
            back.action = this::onClose;
            refresh();
        }

        private String report() {
            var job = crew.inspect();
            StringBuilder text = new StringBuilder();
            if (!catalogError.isEmpty()) text.append(catalogError).append("\n\n");
            if (catalogId != null) try { text.append(jobSummary(bots, bots.job(catalogId))).append("\n\nLive crew\n"); }
            catch (RuntimeException e) { text.append("Job catalog unavailable: ").append(failure(e)).append("\n\nLive recovery information\n"); }
            text.append(job.name()).append(" · ").append(job.phase()).append('\n')
                .append(job.detail()).append("\n\nPosition & progress\n").append(job.origin())
                .append("\n\nSupply recovery\n").append(job.supply());
            if (!job.recoveryPath().isBlank()) text.append("\nRecovery record: ").append(job.recoveryPath());
            for (var member : crew.members()) text.append("\n\n").append(member.name()).append(" · ").append(member.phase())
                .append(member.connected() ? "" : " · disconnected").append("\n").append(member.lane()).append("\n").append(member.status());
            return text.toString();
        }

        private void refresh() { details.set(report()); }
        private void perform(Runnable action, String success) {
            try { action.run(); feedback.set(success); feedback.color(GREEN); }
            catch (RuntimeException e) { feedback.set(e.getMessage() == null ? "Unable to complete this action." : e.getMessage()); feedback.color(RED); }
            refresh();
        }
        @Override public void tick() { super.tick(); if (++ticks % 5 == 0) refresh(); }
    }

    /** A theme-native gilded card, with no extra texture or rendering pass. */
    private static class Card extends WVerticalList {
        @Override public <T extends dev.monocle.client.gui.widgets.WWidget> dev.monocle.client.gui.utils.Cell<T> add(T widget) {
            return super.add(widget).padHorizontal(10).padVertical(3);
        }

        @Override protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            MonocleStyle.rounded(renderer, x, y, width, height, theme.scale(5), new Color(39, 38, 42, 238), new Color(19, 24, 31, 245));
            renderer.quad(x + theme.scale(10), y, Math.max(0, width - theme.scale(20)), theme.scale(1),
                new Color(218, 188, 120, 25), GOLD, GOLD, new Color(218, 188, 120, 25));
        }
    }
}
