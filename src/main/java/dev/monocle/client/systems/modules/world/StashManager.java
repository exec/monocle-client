package dev.monocle.client.systems.modules.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.bots.*;
import dev.monocle.client.systems.modules.*;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.coordinator.StashCatalog;
import meteordevelopment.orbit.EventHandler;

/** Local stash definitions and read-only discovery. */
public final class StashManager extends Module {
    public enum ScanMode { Full, ColumnMap }
    private final SettingGroup definition=settings.getDefaultGroup(),route=settings.createGroup("Route"),scanning=settings.createGroup("Scanning");
    private final Setting<String> stashName=definition.add(new StringSetting.Builder().name("stash-name").description("Named stash in this world; later scans update observations at each container coordinate.").defaultValue("Main stash").build());
    private final Setting<Boolean> assumeEmpty=definition.add(new BoolSetting.Builder().name("assume-empty-storage").description("Use discovered, unopened chests as destination candidates without a contents scan. Each chest is verified before depositing; known contents are never erased. Enable live storage updates to map loaded chests.").defaultValue(false).build());
    private final Setting<String> homeName=route.add(new StringSetting.Builder().name("home-name").description("Required server /home name used to reach this stash. A definition cannot be saved or scanned without it.").defaultValue("").build());
    private final Setting<Integer> homeWarmup=route.add(new IntSetting.Builder().name("home-warmup").description("Seconds to wait after /home before scanning.").defaultValue(15).range(0,3600).sliderRange(0,60).build());
    private final Setting<Double> homeCooldown=route.add(new DoubleSetting.Builder().name("home-cooldown").description("Minutes before this home may be requested again; fractional minutes preserve second-level cooldowns.").defaultValue(10).range(0,1440).sliderRange(0,60).decimalPlaces(4).build());
    private final Setting<Boolean> exportToHost=scanning.add(new BoolSetting.Builder().name("export-to-connected-host").description("Publish this stash definition and observations to the authenticated Workers host.").defaultValue(true).build());
    private final Setting<Boolean> liveUpdates=scanning.add(new BoolSetting.Builder().name("live-storage-updates").description("Passively observe defined stashes, even when no scan is running: confirmed chest contents, new storage and removals. Never opens containers or moves you.").defaultValue(true).build());
    public boolean liveUpdates(){return liveUpdates.get();}
    public boolean exportsScans(){return exportToHost.get();}
    public boolean exportsLiveUpdates(){return liveUpdates.get()&&exportToHost.get();}
    private final Setting<Boolean> lazyMode=scanning.add(new BoolSetting.Builder().name("lazy-mode").description("Scan the bottom and top storage layers first. If both are uniformly full and homogeneous, estimate matching containers between them. Inferred totals are labeled.").defaultValue(true).build());
    private final Setting<ScanMode> scanMode=scanning.add(new EnumSetting.Builder<ScanMode>().name("scan-mode").description("Full scans contents and quantities. Column Map opens only the bottom layer to identify each column; upper quantities remain unknown.").defaultValue(ScanMode.Full).build());
    private BotActions scanner;
    private JsonObject export;
    private java.util.concurrent.CompletableFuture<Void> exportWrite;
    private String editingName="",editingScope="",editingRevision="";
    private String status="Enter the required /home name, select both corners, then save or scan.";
    private WLabel label;
    public StashManager(){super(Categories.World,"stash-manager","Experimental: inspect a selected stash, classify supply shulkers and save resource observations. Solo or bot workflow; never moves items.");}
    public boolean isScanning(){return scanner!=null;}
    public boolean ownsScanRead(net.minecraft.core.BlockPos pos){return scanner!=null&&scanner.ownsScanRead(pos);}
    public static int cooldownTicks(double minutes){return (int)Math.round(minutes*1200);}
    public static double cooldownMinutes(int ticks){return ticks/1200d;}
    private String scope(){if(mc.level==null)throw new IllegalStateException("Join the stash world first");return (mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip)+"\n"+mc.level.dimension().identifier();}
    private JsonObject definitionPlan(){
        JsonObject p=Modules.get().get(SchematicSelector.class).selectionBounds(stashName.get());p.addProperty("type","StashScan");p.addProperty("assumeEmpty",assumeEmpty.get());p.addProperty("lazyMode",lazyMode.get());p.addProperty("scanMode",scanMode.get()==ScanMode.ColumnMap?"Column Map":"Full");p.addProperty("homeName",homeName.get());p.addProperty("homeWarmupTicks",homeWarmup.get()*20);p.addProperty("homeCooldownTicks",cooldownTicks(homeCooldown.get()));return StashCatalog.plan(p);
    }
    public String definitionReadiness(){try{JsonObject p=definitionPlan();return "Ready · /home "+p.get("homeName").getAsString()+" · cuboid validated";}catch(RuntimeException e){return "Not ready · "+e.getMessage();}}
    private JsonObject saveDefinition(boolean sync){
        JsonObject p=definitionPlan();String scope=scope(),worker=mc.player.getUUID().toString();StashCatalog.define(MonocleClient.FOLDER.toPath(),"Local",scope,p,worker);
        export=null;
        if(sync&&exportToHost.get()){
            try{
                JsonObject message=dev.monocle.coordinator.TaskWire.message("stash-definition");message.add("stash",p.deepCopy());message.addProperty("scope",scope);
                message.addProperty("definitionRevision",editingName.equals(stashName.get())&&editingScope.equals(scope)?editingRevision:remoteRevision(scope,stashName.get()));
                export=new JsonObject();export.addProperty("owner",Bots.get().stashExporter.owner());export.addProperty("worker",worker);export.add("message",message);
                exportWrite=BotStashScan.queueExport(MonocleClient.FOLDER.toPath(),export);
                exportWrite.whenComplete((ignored,error)->{if(error!=null)MonocleClient.LOG.error("[Workers] Stash definition export was not journaled; retry Save",error);});
            }catch(IllegalArgumentException e){export=null;warning("Saved locally; configure the Workers host and crew key before exporting.");}
        }
        return p;
    }
    private String remoteRevision(String scope,String name){for(var v:StashCatalog.remote(MonocleClient.FOLDER.toPath())){JsonObject s=v.getAsJsonObject();if(dev.monocle.coordinator.TaskWire.text(s,"scope").equals(scope)&&dev.monocle.coordinator.TaskWire.text(s,"name").equals(name))return dev.monocle.coordinator.TaskWire.text(s,"definitionRevision");}return "";}
    public void startScan(){
        try{
            if(scanner!=null)throw new IllegalStateException("Stop the current scan first");
            export=null;
            if(Bots.get().tasks().hasWork()&&Bots.get().isWorker())throw new IllegalStateException("Pause or cancel the worker job before scanning locally");
            SchematicSelector selector=Modules.get().get(SchematicSelector.class);JsonObject p=saveDefinition(true);
            selector.disable(); if(!isActive())enable();
            scanner=new BotActions(Bots.get());scanner.start(p);scanner.stashExport(export);setStatus("Scan started; no items will be moved.");
        }catch(RuntimeException e){setStatus(e.getMessage());error("%s",status);}
    }
    public void exportSelection(){
        saveDefinition(true);setStatus((export==null?"Stash definition saved locally with its worker /home route.":"Stash definition saved locally; host export queued for durable delivery.")+(assumeEmpty.get()?" Empty-storage mode: live updates map loaded chests; deposits verify contents first.":""));
    }
    public void stopScan(){if(scanner!=null){scanner.stop();scanner=null;}export=null;setStatus("Scan stopped; saved observations and queued exports retained.");}
    @Override public void onDeactivate(){stopScan();label=null;}
    public String status(){return status;}
    public JsonArray catalog(){JsonArray all=StashCatalog.list(MonocleClient.FOLDER.toPath());for(var value:StashCatalog.remote(MonocleClient.FOLDER.toPath()))all.add(value.deepCopy());return all;}
    public void editDefinition(JsonObject stash,boolean reselect){
        editingName=dev.monocle.coordinator.TaskWire.text(stash,"name");editingScope=dev.monocle.coordinator.TaskWire.text(stash,"scope");editingRevision=remoteRevision(editingScope,editingName);
        JsonObject p=StashCatalog.bounds(stash.getAsJsonObject("bounds"));stashName.set(stash.get("name").getAsString());assumeEmpty.set(dev.monocle.coordinator.TaskWire.flag(p,"assumeEmpty"));lazyMode.set(!p.has("lazyMode")||p.get("lazyMode").getAsBoolean());scanMode.set(p.has("scanMode")&&p.get("scanMode").getAsString().equals("Column Map")?ScanMode.ColumnMap:ScanMode.Full);
        JsonObject route=p;if(stash.has("homes")&&mc.player!=null&&stash.getAsJsonObject("homes").has(mc.player.getUUID().toString()))route=stash.getAsJsonObject("homes").getAsJsonObject(mc.player.getUUID().toString());
        homeName.set(route.has("name")?route.get("name").getAsString():route.has("homeName")?route.get("homeName").getAsString():"");homeWarmup.set((route.has("warmupTicks")?route.get("warmupTicks").getAsInt():p.has("homeWarmupTicks")?p.get("homeWarmupTicks").getAsInt():300)/20);homeCooldown.set(cooldownMinutes(route.has("cooldownTicks")?route.get("cooldownTicks").getAsInt():p.has("homeCooldownTicks")?p.get("homeCooldownTicks").getAsInt():12_000));
        SchematicSelector selector=Modules.get().get(SchematicSelector.class);selector.selectionBounds(p);if(reselect){selector.clearSelection();selector.equipWand();}setStatus(reselect?"Reselect both corners, then save the definition.":"Definition loaded for editing.");
    }
    private void setStatus(String text){status=text;if(label!=null)label.set(text);}
    @EventHandler private void tick(TickEvent.Post event){
        if(exportWrite!=null&&exportWrite.isDone()){try{exportWrite.join();}catch(RuntimeException e){setStatus("Definition export could not be saved; retry Save: "+e.getMessage());error("%s",status);}exportWrite=null;}
        if(scanner==null)return;
        try{
            JsonObject s=scanner.tick();setStatus(s.get("detail").getAsString());
            // Both local contents and the host-scoped export are durable before advancing.
            if(scanner.stashPending()!=null){
                scanner.acknowledgeStash(scanner.stashDelivery());
            }
            if(!s.get("state").getAsString().equals("Running")){scanner.stop();scanner=null;}
        }catch(RuntimeException e){stopScan();setStatus("Scan failed: "+e.getMessage());error("%s",status);}
    }
    @Override public WWidget getWidget(GuiTheme theme){
        var list=theme.verticalList();list.add(theme.label("Discovery only. Scans never extract items or alter terrain."));label=list.add(theme.label(status)).widget();
        list.add(theme.button("Select Stash · Wooden Pickaxe")).expandX().widget().action=()->Modules.get().get(SchematicSelector.class).equipWand();
        list.add(theme.button("Copy Selected Bounds for Host Console")).expandX().widget().action=()->{
            try{mc.keyboardHandler.setClipboard(Modules.get().get(SchematicSelector.class).selectionBounds(stashName.get()).toString());setStatus("Bounds copied. Paste into an Inspect stash job's JSON arguments.");}catch(RuntimeException e){error("%s",e.getMessage());}
        };
        list.add(theme.button("Open Stash Control Center")).expandX().widget().action=()->mc.gui.setScreen(new dev.monocle.client.gui.screens.StashManagerScreen(theme,this));
        list.add(theme.button("Scan Selected Stash")).expandX().widget().action=this::startScan;
        list.add(theme.button("Stop Scan")).expandX().widget().action=this::stopScan;
        var saved=list.add(theme.verticalList()).expandX().widget();
        Runnable refresh=()->{
            saved.clear();
            for(var value:StashCatalog.list(MonocleClient.FOLDER.toPath())){
                JsonObject s=value.getAsJsonObject();var section=saved.add(theme.section(s.get("name").getAsString(),false)).expandX().widget();
                section.add(theme.label(s.get("scope").getAsString()+" · "+s.get("observed")+" observed · "+(s.has("inferred")?s.get("inferred"):0)+" inferred · "+s.get("unscanned")+" unscanned"));
                section.add(theme.label("Last observation: "+java.time.Instant.ofEpochMilli(s.get("updatedAt").getAsLong())));
                s.getAsJsonObject("items").entrySet().stream().sorted((a,b)->Long.compare(b.getValue().getAsLong(),a.getValue().getAsLong())).forEach(item->section.add(theme.label(item.getKey()+" · "+item.getValue())));
            }
        };
        list.add(theme.button("Refresh Saved Resource Totals")).expandX().widget().action=()->{
            try{refresh.run();}catch(RuntimeException e){error("Could not load stash observations: %s",e.getMessage());}
        };
        refresh.run();
        return list;
    }
    @Override public String getInfoString(){return scanner==null?"Idle":"Scanning";}
}
