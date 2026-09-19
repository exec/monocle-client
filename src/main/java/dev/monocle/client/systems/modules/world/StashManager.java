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

/** Experimental discovery only: no withdrawal, inventory management, container breaking or teleport commands. */
public final class StashManager extends Module {
    private final Setting<String> stashName=settings.getDefaultGroup().add(new StringSetting.Builder().name("stash-name").description("Named stash in this world; later scans update observations at each container coordinate.").defaultValue("Main stash").build());
    private final Setting<Boolean> exportToHost=settings.getDefaultGroup().add(new BoolSetting.Builder().name("export-to-connected-host").description("Publish this stash name and cuboid to the authenticated Workers host so crews can scan it. Item observations are still sent only by assigned scans.").defaultValue(true).build());
    private final Setting<Boolean> lazyMode=settings.getDefaultGroup().add(new BoolSetting.Builder().name("lazy-mode").description("Scan the bottom and top storage layers first. If both are uniformly full and homogeneous, estimate matching containers between them. Inferred totals are labeled.").defaultValue(true).build());
    private final Setting<String> homeName=settings.getDefaultGroup().add(new StringSetting.Builder().name("home-name").description("Optional server home used before scanning. Runs /home [name]; leave blank to scan from the current position.").defaultValue("").build());
    private final Setting<Integer> homeWarmup=settings.getDefaultGroup().add(new IntSetting.Builder().name("home-warmup").description("Seconds to wait after /home before scanning.").defaultValue(15).range(0,3600).sliderRange(0,60).build());
    private final Setting<Integer> homeCooldown=settings.getDefaultGroup().add(new IntSetting.Builder().name("home-cooldown").description("Minutes before this home may be requested again.").defaultValue(10).range(0,1440).sliderRange(0,60).build());
    private BotActions scanner;
    private JsonObject exportedPlan;
    private String exportedScope="";
    private String status="Experimental read-only scanner. Select a cuboid, then start scanning.";
    private WLabel label;
    public StashManager(){super(Categories.World,"stash-manager","Experimental: inspect a selected stash, classify supply shulkers and save resource observations. Solo or bot workflow; never moves items.");}
    public boolean isScanning(){return scanner!=null;}
    public void startScan(){
        try{
            if(scanner!=null)throw new IllegalStateException("Stop the current scan first");
            exportedPlan=null;exportedScope="";
            if(Bots.get().tasks().hasWork()&&Bots.get().isWorker())throw new IllegalStateException("Pause or cancel the worker job before scanning locally");
            SchematicSelector selector=Modules.get().get(SchematicSelector.class);
            JsonObject p=selector.selectionBounds(stashName.get());p.addProperty("type","StashScan");p.addProperty("lazyMode",lazyMode.get());
            p.addProperty("homeName",homeName.get());p.addProperty("homeWarmupTicks",homeWarmup.get()*20);p.addProperty("homeCooldownTicks",homeCooldown.get()*1200);
            if(exportToHost.get()){
                exportedPlan=p.deepCopy();exportedScope=(mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip)+"\n"+mc.level.dimension().identifier();
                JsonObject export=dev.monocle.coordinator.TaskWire.message("stash-definition");export.add("stash",exportedPlan.deepCopy());export.addProperty("scope",exportedScope);
                if(!Bots.get().sendToHost(export))warning("Stash definition stayed local because no Workers host is connected.");
            }
            selector.disable(); if(!isActive())enable();
            scanner=new BotActions(Bots.get());scanner.start(p);setStatus("Scan started; no items will be moved.");
        }catch(RuntimeException e){setStatus(e.getMessage());error("%s",status);}
    }
    public void exportSelection(){
        SchematicSelector selector=Modules.get().get(SchematicSelector.class);JsonObject p=selector.selectionBounds(stashName.get());p.addProperty("type","StashScan");p.addProperty("lazyMode",lazyMode.get());p.addProperty("homeName",homeName.get());p.addProperty("homeWarmupTicks",homeWarmup.get()*20);p.addProperty("homeCooldownTicks",homeCooldown.get()*1200);
        String scope=(mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip)+"\n"+mc.level.dimension().identifier();StashCatalog.define(MonocleClient.FOLDER.toPath(),"Local",scope,p);
        JsonObject export=dev.monocle.coordinator.TaskWire.message("stash-definition");export.add("stash",p);export.addProperty("scope",scope);if(!Bots.get().sendToHost(export))warning("Saved locally; no Workers host is connected.");setStatus("Stash definition saved and offered to the connected host.");
    }
    public void stopScan(){if(scanner!=null){scanner.stop();scanner=null;}exportedPlan=null;exportedScope="";setStatus("Scan stopped; saved observations retained.");}
    @Override public void onDeactivate(){stopScan();label=null;}
    public String status(){return status;}
    public JsonArray catalog(){JsonArray all=StashCatalog.list(MonocleClient.FOLDER.toPath());for(var value:StashCatalog.remote(MonocleClient.FOLDER.toPath()))all.add(value.deepCopy());return all;}
    public void editDefinition(JsonObject stash,boolean reselect){
        JsonObject p=StashCatalog.plan(stash.getAsJsonObject("bounds"));stashName.set(stash.get("name").getAsString());lazyMode.set(p.get("lazyMode").getAsBoolean());
        JsonObject route=p;if(stash.has("homes")&&mc.player!=null&&stash.getAsJsonObject("homes").has(mc.player.getUUID().toString()))route=stash.getAsJsonObject("homes").getAsJsonObject(mc.player.getUUID().toString());
        homeName.set(route.has("name")?route.get("name").getAsString():p.get("homeName").getAsString());homeWarmup.set((route.has("warmupTicks")?route.get("warmupTicks"):p.get("homeWarmupTicks")).getAsInt()/20);homeCooldown.set((route.has("cooldownTicks")?route.get("cooldownTicks"):p.get("homeCooldownTicks")).getAsInt()/1200);
        SchematicSelector selector=Modules.get().get(SchematicSelector.class);selector.selectionBounds(p);if(reselect){selector.clearSelection();selector.equipWand();}setStatus(reselect?"Reselect both corners, then save the definition.":"Definition loaded for editing.");
    }
    private void setStatus(String text){status=text;if(label!=null)label.set(text);}
    @EventHandler private void tick(TickEvent.Post event){
        if(scanner==null)return;
        try{
            JsonObject s=scanner.tick();setStatus(s.get("detail").getAsString());
            // Solo observations already reached the same atomic local catalog; no remote receipt is required.
            if(scanner.stashPending()!=null){
                if(exportedPlan!=null){JsonObject export=dev.monocle.coordinator.TaskWire.message("stash-import");export.addProperty("scope",exportedScope);export.add("stash",exportedPlan.deepCopy());export.add("observation",scanner.stashPending());Bots.get().sendToHost(export);}
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
