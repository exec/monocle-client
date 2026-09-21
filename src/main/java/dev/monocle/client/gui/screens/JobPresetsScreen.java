package dev.monocle.client.gui.screens;

import com.google.gson.JsonObject;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.coordinator.JobSettingControls;
import java.util.UUID;
import static dev.monocle.client.MonocleClient.mc;

/** Shared operations library, not a second profile store. */
public final class JobPresetsScreen extends WindowScreen {
    private record Choice(String id,String name){public String toString(){return name;}}
    private final Bots bots;
    public JobPresetsScreen(GuiTheme theme,Bots bots){super(theme,"Job presets · future jobs");this.bots=bots;}
    @Override public void initWidgets(){
        add(theme.label("Built-ins are read-only. Duplicate to customize. Running jobs never change.",550)).expandX();
        Choice[] choices=bots.operations().list().asList().stream().map(v->{JsonObject r=v.getAsJsonObject();return new Choice(r.get("id").getAsString(),r.get("folder").getAsString()+" / "+r.get("name").getAsString());}).toArray(Choice[]::new);
        var selected=add(theme.dropdown(choices,choices[0])).expandX().widget();
        add(theme.button("Start job from selected preset")).expandX().widget().action=()->mc.gui.setScreen(new BotTaskScreen(theme,bots,null,"package:"+selected.get().id(),null));
        add(theme.label("Preset name"));var name=add(theme.textBox("")).expandX().widget();
        add(theme.label("Folder"));var folder=add(theme.textBox("")).expandX().widget();
        var feedback=add(theme.label("",550)).expandX().widget();
        Runnable load=()->{var r=bots.operations().get(selected.get().id());name.set(r.get("name").getAsString());folder.set(r.get("folder").getAsString());feedback.set(r.get("builtin").getAsBoolean()?"Built-in · duplicate before editing":"Custom · changes affect future launches only");};selected.action=load;load.run();
        add(theme.button("Duplicate as new preset")).expandX().widget().action=()->{try{var r=bots.operations().get(selected.get().id());bots.operations().save(UUID.randomUUID().toString(),name.get()+" copy",folder.get(),r.getAsJsonObject("package"));mc.gui.setScreen(new JobPresetsScreen(theme,bots));}catch(RuntimeException e){feedback.set(e.getMessage());}};
        add(theme.button("Rename / move custom preset")).expandX().widget().action=()->{try{var r=bots.operations().get(selected.get().id());bots.operations().save(selected.get().id(),name.get(),folder.get(),r.getAsJsonObject("package"));mc.gui.setScreen(new JobPresetsScreen(theme,bots));}catch(RuntimeException e){feedback.set(e.getMessage());}};
        var control=add(theme.dropdown(JobSettingControls.ALL.toArray(JobSettingControls.Control[]::new),JobSettingControls.ALL.getFirst())).expandX().widget();
        add(theme.label("Proposed activation (not the current setting)"));var active=add(theme.dropdown(new String[]{"On","Off"},"On")).expandX().widget();
        add(theme.label("Proposed numeric value (ignored for activation-only controls)"));var value=add(theme.textBox("5.5")).expandX().widget();
        final JsonObject[] draft={null};
        selected.action=()->{draft[0]=null;load.run();};
        control.action=()->{draft[0]=null;value.set(Double.toString(control.get().example()));feedback.set(control.get().help());};
        active.action=()->draft[0]=null;value.action=()->draft[0]=null;
        add(theme.button("Preview settings change")).expandX().widget().action=()->{try{JsonObject request=new JsonObject();request.addProperty("control",control.get().id());request.addProperty("active",active.get().equals("On"));if(control.get().numeric())request.addProperty("value",Double.parseDouble(value.get()));var p=bots.operations().previewControl(selected.get().id(),request);request.addProperty("expected",p.get("expected").getAsString());draft[0]=request;feedback.set(p.get("description").getAsString()+"\nBefore: "+p.get("before")+"\nAfter: "+p.get("after")+"\nFuture jobs only.");}catch(RuntimeException e){draft[0]=null;feedback.set(e.getMessage());}};
        add(theme.button("Save previewed settings")).expandX().widget().action=()->{try{if(draft[0]==null)throw new IllegalStateException("Preview first");bots.operations().editControl(selected.get().id(),draft[0].get("expected").getAsString(),draft[0]);draft[0]=null;feedback.set("Saved for future jobs. Running jobs unchanged.");}catch(RuntimeException e){feedback.set(e.getMessage());}};
        add(theme.button("Copy captured package for inspection")).expandX().widget().action=()->mc.keyboardHandler.setClipboard(bots.operations().get(selected.get().id()).get("package").toString());
        add(theme.button("Back")).expandX().widget().action=this::onClose;
    }
}
