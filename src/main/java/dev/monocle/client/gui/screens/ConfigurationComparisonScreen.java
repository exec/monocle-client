package dev.monocle.client.gui.screens;

import com.google.gson.JsonObject;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.utils.Utils;
import java.util.*;

/** Explicit snapshots only; polling a pending response never changes the selected controls. */
public final class ConfigurationComparisonScreen extends WindowScreen {
    private record Worker(UUID id, String name) { @Override public String toString() { return name; } }
    private final Bots bots;
    private final UUID task;
    private WDropdown<Worker> worker;
    private WDropdown<String> profile, module;
    private WVerticalList modulePanel, result;
    private WLabel feedback;
    private JsonObject profiles;
    private double width;
    private boolean pending;
    private int ticks;

    public ConfigurationComparisonScreen(GuiTheme theme, Bots bots, UUID task) {
        super(theme,"Compare job configuration"); this.bots=bots; this.task=task;
    }
    @Override public void initWidgets() {
        width=Math.clamp(Utils.getWindowWidth()/theme.scale(1)-100,330,1000);
        feedback=add(theme.label("Request one module at a time. Snapshots never apply settings.",width)).expandX().widget();
        try {
            JsonObject view=bots.tasks().configuration(task); profiles=view.getAsJsonObject("profiles").deepCopy();
            if(profiles.isEmpty())throw new IllegalStateException("No captured profiles");
            JsonObject latest=new JsonObject();for(var update:view.getAsJsonObject("updates").entrySet())for(var entry:update.getValue().getAsJsonObject().getAsJsonObject("modules").entrySet())latest.add(entry.getKey(),entry.getValue().deepCopy());
            if(!latest.isEmpty())profiles.add("Latest live request / worker",latest);
            List<Worker> workers=new ArrayList<>();
            for(String id:view.getAsJsonObject("updates").keySet())workers.add(new Worker(UUID.fromString(id),bots.allMembers().stream().filter(m->m.id().toString().equals(id)).map(m->m.name()).findFirst().orElse(id)));
            if(workers.isEmpty())throw new IllegalStateException("No workers in this job");
            add(theme.label("Worker"));worker=add(theme.dropdown(workers.toArray(Worker[]::new),workers.getFirst())).expandX().widget();
            String[] names=profiles.keySet().stream().sorted().toArray(String[]::new);
            add(theme.label("Host source"));profile=add(theme.dropdown(names,names[0])).expandX().widget();
            modulePanel=add(theme.verticalList()).expandX().widget();
            add(theme.button("Request fresh comparison")).expandX().widget().action=()->sample(true);
            result=add(theme.verticalList()).expandX().widget();
            worker.action=this::clearSnapshot;profile.action=()->{clearSnapshot();showModules();};showModules();
        }catch(RuntimeException e){feedback.set(e.getMessage());}
        add(theme.button("Back")).expandX().widget().action=this::onClose;
    }
    private void clearSnapshot(){pending=false;result.clear();feedback.set("Selection changed. Request a new snapshot.");}
    private void showModules(){
        modulePanel.clear();module=null;
        String[] names=profiles.getAsJsonObject(profile.get()).keySet().stream().sorted().toArray(String[]::new);
        if(names.length==0){modulePanel.add(theme.label("This profile has no module overrides."));return;}
        modulePanel.add(theme.label("Module"));module=modulePanel.add(theme.dropdown(names,names[0])).expandX().widget();module.action=this::clearSnapshot;
    }
    private void sample(boolean request){
        if(module==null)return;
        try{
            JsonObject sample=bots.tasks().compareConfiguration(task,worker.get().id(),profile.get(),module.get(),request);
            if(sample.has("profile")&&(!sample.get("profile").getAsString().equals(profile.get())||!sample.get("module").getAsString().equals(module.get()))){
                pending=false;result.clear();feedback.set("Readback replaced by another operator. Request again.");return;
            }
            String status=sample.get("status").getAsString();pending=status.equals("Pending");feedback.set(status);
            if(!pending&&sample.has("report")){
                result.clear();JsonObject report=sample.getAsJsonObject("report");
                result.add(theme.label("Snapshot received: "+java.time.Instant.ofEpochMilli(sample.get("receivedAt").getAsLong())+" · refresh after edits or reconnects.",width)).expandX();
                result.add(theme.label(report.get("note").getAsString(),width)).expandX();
                var table=result.add(theme.table()).expandX().widget();double column=(width-30)/4;
                for(String title:List.of("Setting","Personal","Selected host overlay","Actual now"))table.add(theme.label(title,column));table.row();
                for(var entry:report.getAsJsonArray("rows")){
                    JsonObject row=entry.getAsJsonObject();
                    for(String key:List.of("setting","personal","requested","current"))table.add(theme.label(row.get(key).getAsString(),column));table.row();
                }
            }
        }catch(RuntimeException e){pending=false;feedback.set("Readback unavailable: "+e.getMessage());}
    }
    @Override public void tick(){super.tick();if(pending&&++ticks%10==0)sample(false);}
}
